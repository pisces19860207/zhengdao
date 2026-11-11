#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wiring_report_test.py —— 接线清单的自检套件（600 三次拍板：测试搬进工具房）。

## 为什么单独一个文件

`tools/wiring-report.py` 是闸，`tools/wiring_report_test.py` 是秤。混在一起时，
"改闸顺手改断言"没有任何摩擦；拆开之后，改断言是一次**明确的、会被 review 到的**改动。

## 怎么跑

    python3 tools/wiring_report_test.py        # 直接跑（失败退 1）
    python3 tools/wiring-report.py --selftest  # 闸里的同名入口，内部就是调这里

**不依赖 CI**：不联网、不读真仓（全部用临时目录里的合成树）、不写仓库里的任何文件
（基线/收据都落在临时目录）。所以本机随时能验。

## 它钉住什么

- 扫描口径：全限定调用、同名双类与跨包 import、声明行不算调用、注释/字符串不算；
- 分类：端口接口、纯常量/纯 DTO、`by-design ≠ 已接线`；
- 基线：往返 0 差异、翻栏可检出、幂等；
- R1–R4 四条红、**R5 减账三档**（含按声明种类判降档）、**--allow-add 五道门**、
  **R6 三态＋收据指纹**、R7 留因、裁定表上限。
"""
from __future__ import annotations

import importlib.util
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "wiring-report.py"


def _load_impl():
    """加载被测脚本（文件名带连字符，import 不了，只能按路径加载）。"""
    if not SCRIPT.exists():
        raise SystemExit("找不到被测脚本：%s" % SCRIPT)
    spec = importlib.util.spec_from_file_location("wiring_report_impl", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["wiring_report_impl"] = mod
    spec.loader.exec_module(mod)
    return mod


wr = _load_impl()
# 把被测脚本的公开名字注入本模块：断言体与原先内嵌时一字不差，只有 TEST_SRCS/RULINGS
# 这类**要写回**的全局显式写 `wr.`（否则只会改到本模块的同名变量）。
globals().update({k: v for k, v in vars(wr).items() if not k.startswith("__")})


def run() -> int:
    fails: list[str] = []
    checked = 0

    def expect(cond: bool, msg: str) -> None:
        nonlocal checked
        checked += 1
        if not cond:
            fails.append(msg)

    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        src = base / "app/src/main"
        for name, body in FIXTURE_SRC.items():
            path = src / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body.lstrip("\n"), encoding="utf-8")
        for name, body in FIXTURE_TEST.items():
            path = base / "app/src/test" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body.lstrip("\n"), encoding="utf-8")

        saved = wr.TEST_SRCS
        wr.TEST_SRCS = (base / "app/src/test", base / "app/src/androidTest")
        try:
            entries = build(src)
            # 幂等要在这里比：两遍都必须在同一个 TEST_SRCS 下，否则第二次扫的是真仓测试源，
            # 「只被测试用」那一格会翻栏——这不是幂等坏了，是夹具没关住（自检自己抓到过）。
            again = build(src)
        finally:
            wr.TEST_SRCS = saved

        got = {(e.name, e.decl.file.parent.name): e for e in entries}

        def at(name: str, pkg_dir: str = "a") -> Entry | None:
            return got.get((name, pkg_dir))

        cases = [
            ("WiredWiring", True, BUCKET_WIRED),
            ("QualifiedWiring", True, BUCKET_WIRED),
            ("CommentOnlyWiring", False, BUCKET_PENDING),
            ("ConstWiring", False, BUCKET_BY_DESIGN),
            ("DtoWiring", False, BUCKET_BY_DESIGN),
            ("TestOnlyWiring", False, BUCKET_PENDING),
            ("GrantWiring", False, BUCKET_BY_DESIGN),
            ("ForeignWiring", False, BUCKET_PENDING),
        ]
        for name, _, want in [(n, a, b) for n, a, b in cases]:
            entry = at(name)
            if entry is None:
                fails.append("没扫到 `%s`" % name)
            elif entry.bucket != want:
                fails.append("`%s` 应落「%s」，实际「%s」（%s）"
                             % (name, want, entry.bucket, entry.reason))
        co = at("CommentOnlyWiring")
        expect(co is not None and co.comment_mentions >= 3,
               "注释/字符串提及次数没数出来（挖注释可能失效）")
        qw = at("QualifiedWiring")
        expect(qw is not None and len(qw.calls) == 1,
               "全限定调用 `demo.a.QualifiedWiring.go()` 没被算成调用点")
        # 同名双类：调用方在 p2 ⇒ 只能归 p2 那份；归给 p1（或摊给两边）就是串证据
        t1, t2 = at("TwinWiring", "p1"), at("TwinWiring", "p2")
        expect(t1 is not None and t2 is not None, "同名双类没各扫一份")
        if t1 is not None and t2 is not None:
            expect(t2.bucket == BUCKET_WIRED and len(t2.calls) == 1,
                   "同名类的调用点没归给同包那份（p2）")
            expect(t1.bucket != BUCKET_WIRED,
                   "同名类的调用点被摊给了另一份（p1）—— 归属有错")
        # 跨包同名：q3 用 import 指名 q2 那份
        a1, a2 = at("AliasWiring", "q1"), at("AliasWiring", "q2")
        if a1 is not None and a2 is not None:
            expect(a2.bucket == BUCKET_WIRED,
                   "import 指名的那份（q2/AliasWiring）没判成已接")
            expect(a1.bucket != BUCKET_WIRED,
                   "同名的另一份（q1/AliasWiring）被 import 串了证据")
        else:
            fails.append("跨包同名 AliasWiring 没各扫一份")
        # 声明行不是调用：`class SelfWiredWiring(` 不能算一次自调
        sw = at("SelfWiredWiring")
        expect(sw is not None and len(sw.self_calls) == 0,
               "`class X(` 声明行被算成了构造调用（self_calls 虚高）")
        hr = at("HolderRecord")
        expect(hr is not None and hr.scope == SCOPE_COMPANION,
               "`*Wiring.kt` 里的伴随类型没进附录")
        hw = at("HolderWiring")
        expect(hw is not None and hw.scope == SCOPE_TARGET, "装配类被误判成伴随类型")
        gw = at("GrantWiring")
        expect(gw is not None and gw.by_design == BY_PORT,
               "接口没归「端口接口」（端口不许进待判/待办）")
        # 测试里在用 ≠ 接上了：必须落待判，且理由里点明「测试用≠生产接上」
        tw = at("TestOnlyWiring")
        expect(tw is not None and tw.bucket == BUCKET_PENDING,
               "只在测试里用的装配类被判成 by-design（测试用不等于接线）")
        expect(tw is not None and "测试用≠生产接上" in tw.reason,
               "待判理由里没写「测试里用了几次」")

        # 裁定表：注入一条夹具名 ⇒ 必须压过默认待判；表外的 ForeignWiring 仍待判（闭集）
        saved_rulings = dict(RULINGS)
        try:
            RULINGS["ForeignWiring"] = (BY_RULED, "夹具：人工裁定")
            ruled = {(e.name, e.decl.file.parent.name): e for e in build(src)}
            fr = ruled.get(("ForeignWiring", "a"))
            expect(fr is not None and fr.bucket == BUCKET_BY_DESIGN
                   and fr.by_design == BY_RULED, "裁定表没生效（点了名还落待判）")
            # 裁定过期：给已接的类挂一条裁定 ⇒ 体检必须点名
            RULINGS["WiredWiring"] = (BY_RULED, "夹具：已接却挂裁定")
            probs = audit_rulings(list(ruled.values()))
            expect(any("WiredWiring" in p for p in probs),
                   "裁定过期没被发现（已接的类还挂着裁定）")
        finally:
            RULINGS.clear()
            RULINGS.update(saved_rulings)

        # 基线：往返 0 差异；人为翻一栏必须被比对抓出来；两次生成字节相同
        payload = baseline_payload(entries, src)
        expect(diff_baseline(entries, payload)["clean"], "基线与现状往返比对不是 0 差异")
        tampered = json.loads(json.dumps(payload))
        for row in tampered["entries"]:
            if row["bucket"] == BUCKET_WIRED:
                row["bucket"] = BUCKET_PENDING
                break
        tdiff = diff_baseline(entries, tampered)
        expect(not tdiff["clean"] and len(tdiff["flipped"]) == 1,
               "翻栏没被基线比对抓出来")
        expect(json.dumps(payload, ensure_ascii=False, sort_keys=True)
               == json.dumps(baseline_payload(again, src), ensure_ascii=False, sort_keys=True),
               "基线两次生成不一致（幂等坏了）")
        cnt = counts(entries)
        expect(cnt["total"] == cnt["wired"] + cnt["byDesign"] + cnt["pending"],
               "三栏没铺满总数（有件漏出了统计）")
        expect(cnt["unwiredTotal"] == cnt["byDesign"] + cnt["pending"],
               "unwiredTotal 与 byDesign+pending 对不上")

        # 四条红标准：R1/R2/R3/R4 各造一条；「新增 by-design」「待判→已接」不许红
        fake = {
            "added": [{"name": "NewPendingWiring", "file": "x/a.kt", "bucket": BUCKET_PENDING},
                      {"name": "NewDtoWiring", "file": "x/b.kt", "bucket": BUCKET_BY_DESIGN}],
            "gone": [{"name": "GoneWiring", "file": "x/c.kt", "bucket": BUCKET_WIRED}],
            "flipped": [{"name": "BrokenWiring", "file": "x/d.kt",
                         "was": BUCKET_WIRED, "now": BUCKET_PENDING},
                        {"name": "FixedWiring", "file": "x/e.kt",
                         "was": BUCKET_PENDING, "now": BUCKET_WIRED}],
        }
        bad = strict_violations(fake, ["`FooWiring` 裁定过期"])
        codes = [c for c, _ in bad]
        for code in ("R1", "R2", "R3", "R4"):
            expect(code in codes, "红标准 %s 没抓到对应情形" % code)
        expect(len(bad) == 4, "四条红标准数目不对（多红/少红：%s）" % codes)
        expect(not any("NewDtoWiring" in m for _, m in bad), "新增 by-design 被误红")
        expect(not any("FixedWiring" in m for _, m in bad), "待判→已接 被误红")
        expect(strict_violations({"added": [], "gone": [], "flipped": []}, []) == [],
               "干净的基线被误红")

        first = json.dumps(to_json(entries, pick_audit(entries, 3), src), ensure_ascii=False,
                           indent=2)
        second = json.dumps(to_json(again, pick_audit(again, 3), src), ensure_ascii=False,
                            indent=2)
        expect(first == second, "两遍输出不一致（幂等坏了）")

        # ── R5/R6/R7：减账对价 + 新增四门（内存级，不依赖 git） ────────────────
        import contextlib
        import io

        def _decl(name: str, kind: str = "object", data: bool = False,
                  sealed: bool = False, funcs=()) -> Decl:
            fs = list(funcs)
            return Decl(name=name, kind=kind, package="demo",
                        file=Path("a/%s.kt" % name), line=1,
                        is_data=data, is_sealed=sealed, functions=fs,
                        has_entry=any(f.startswith(("install", "record")) for f in fs))

        class _Cur:
            def __init__(self, bucket: str, decl: Decl) -> None:
                self.bucket = bucket
                self.decl = decl

        def _skip(_path: str):
            return []

        def _d(added=(), gone=(), flipped=()):
            return {"added": list(added), "gone": list(gone), "flipped": list(flipped),
                    "clean": not (added or gone or flipped)}

        plain = _decl("Q_Wiring", "object", funcs=("go",))
        cur_map = {("Q_Wiring", "a/Q_Wiring.kt"): _Cur(BUCKET_PENDING, plain),
                   ("C_Wiring", "a/C_Wiring.kt"): _Cur(BUCKET_BY_DESIGN, _decl("C_Wiring"))}
        row_q = {"name": "Q_Wiring", "file": "a/Q_Wiring.kt", "bucket": BUCKET_PENDING}
        v = sovereignty_violations(_d(added=[row_q]), cur_map, {}, _skip)
        expect(len(v) == 1 and v[0][0] == "R5" and "疑似洗账" in v[0][1],
               "① 洗账夹具没红：删一条基线、代码没动")
        wired_map = {("W_Wiring", "a/W.kt"): _Cur(BUCKET_WIRED, _decl("W_Wiring"))}
        v = sovereignty_violations(_d(added=[{"name": "W_Wiring", "file": "a/W.kt",
                                             "bucket": BUCKET_WIRED}]), wired_map, {}, _skip)
        expect(v == [], "② 扫描到 wired（真接上了）还红")
        dto_row = {"name": "C_Wiring", "file": "a/C_Wiring.kt", "bucket": BUCKET_BY_DESIGN}
        expect(sovereignty_violations(_d(added=[dto_row]), cur_map, {"a/C_Wiring.kt": "A"},
                                      lambda _p: ["internal object C_Wiring {"]) == [],
               "新文件里看到声明行（+行护栏），被误红")
        v = sovereignty_violations(_d(added=[dto_row]), cur_map, {"a/C_Wiring.kt": "A"}, _skip)
        expect(len(v) == 1, "新文件但看不到 + 行也放过了 —— 新文件不该是免检通行证")
        expect(sovereignty_violations(_d(added=[dto_row]), cur_map, {"a/C_Wiring.kt": "M"},
                                      lambda _p: ["internal object C_Wiring {"]) == [],
               "既有文件里新增了该声明，被误红")
        v = sovereignty_violations(_d(added=[dto_row]), cur_map, {"a/C_Wiring.kt": "M"},
                                   lambda _p: ["    // 顺手加一行注释"])
        expect(len(v) == 1, "只改注释也放过了 —— 「移出基线」这一档太松")
        v = sovereignty_violations(_d(added=[dto_row]), cur_map, {"a/C_Wiring.kt": "R"}, _skip)
        expect(len(v) == 1, "改名来的文件（R）被当成新增 —— 改名塞账没堵住")

        cur2 = {("I_Wiring", "a/I.kt"): _Cur(BUCKET_BY_DESIGN, _decl("I_Wiring", "interface")),
                ("S_Wiring", "a/S.kt"): _Cur(BUCKET_BY_DESIGN,
                                             _decl("S_Wiring", "interface", sealed=True)),
                ("D_Wiring", "a/D.kt"): _Cur(BUCKET_BY_DESIGN, _decl("D_Wiring", "class",
                                                                     data=True)),
                ("E_Wiring", "a/E.kt"): _Cur(BUCKET_BY_DESIGN, _decl("E_Wiring", "enum")),
                ("O_Wiring", "a/O.kt"): _Cur(BUCKET_BY_DESIGN, _decl("O_Wiring")),
                ("X_Wiring", "a/X.kt"): _Cur(BUCKET_BY_DESIGN,
                                             _decl("X_Wiring", funcs=("install",))),
                ("K_Wiring", "a/K.kt"): _Cur(BUCKET_BY_DESIGN, _decl("K_Wiring", "class"))}

        def _flip(name: str, path: str):
            return {"name": name, "file": path,
                    "was": BUCKET_PENDING, "now": BUCKET_BY_DESIGN}

        for nm, path in (("I_Wiring", "a/I.kt"), ("S_Wiring", "a/S.kt"),
                         ("D_Wiring", "a/D.kt"), ("E_Wiring", "a/E.kt"),
                         ("O_Wiring", "a/O.kt")):
            expect(sovereignty_violations(_d(flipped=[_flip(nm, path)]), cur2, {}, _skip) == [],
                   "可降种类 %s 申请降档被误红" % nm)
        v = sovereignty_violations(_d(flipped=[_flip("X_Wiring", "a/X.kt")]), cur2, {}, _skip)
        expect(len(v) == 1 and "降档被拒" in v[0][1],
               "⑮ 有 install 入口的 object 申请降档，没被拒")
        v = sovereignty_violations(_d(flipped=[_flip("K_Wiring", "a/K.kt")]), cur2, {}, _skip)
        expect(len(v) == 1, "普通 class 申请降档，没被拒")

        gone_row = {"name": "G_Wiring", "file": "a/G.kt", "bucket": BUCKET_WIRED}
        expect(len(sovereignty_violations(_d(gone=[gone_row]), cur2, {}, _skip)) == 1,
               "名册消失且文件没动，没被 R5 抓到")
        expect(sovereignty_violations(_d(gone=[gone_row]), cur2, {"a/G.kt": "D"}, _skip) == [],
               "文件被删了，消失条目被误红")
        expect(sovereignty_violations(_d(gone=[gone_row]), cur2, None, _skip) == [],
               "取不到 git diff 时应「不判」而不是「判红」")

        # ── R6/R7：--generate 三态 + 新增四门 ────────────────────────────────
        bl = base / "tools" / "wiring-baseline.json"
        rcp = base / "tools" / "wiring-baseline-receipt.json"
        bl.parent.mkdir(parents=True, exist_ok=True)
        with contextlib.redirect_stderr(io.StringIO()):
            rc = generate_baseline(entries, src, bl, None, "夹具：首次固化", False,
                                receipt_path=rcp)
        expect(rc == 0 and bl.exists(), "generate 首次固化没写盘")
        original = bl.read_text(encoding="utf-8")
        bl.write_text(original + "\n", encoding="utf-8", newline="\n")  # 留一道"非标准"痕迹
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = generate_baseline(entries, src, bl, load_baseline(bl), "夹具：无变化", False,
                                receipt_path=rcp)
        expect(rc == 0 and "未写盘" in buf.getvalue(), "③ 无变化时应打印「未写盘」")
        expect(bl.read_text(encoding="utf-8") == original + "\n",
               "③ 无变化时文件被重写了（R6 破了：痕迹被抹掉）")

        # 收据（R6 的收据半边）：写完必须对得上；手改基线必须被抓
        expect(rcp.exists(), "generate 没写收据")
        expect(receipt_problems(load_baseline(bl), rcp) == [], "刚写完的收据就对不上")
        tampered = json.loads(original)
        tampered["entries"].append({"name": "GhostWiring", "file": "a/ghost.kt", "declLine": 1,
                                    "kind": "object", "bucket": "无调用点·待判",
                                    "byDesign": "", "callCount": 0})
        write_json(tampered, bl)
        probs = receipt_problems(load_baseline(bl), rcp)
        expect(any("指纹" in p for p in probs), "手改基线后收据没报指纹不符")
        wj = json.loads(rcp.read_text(encoding="utf-8"))
        wj["reason"] = ""
        write_json(wj, rcp)
        write_json(json.loads(original), bl)
        probs = receipt_problems(load_baseline(bl), rcp)
        expect(any("因由" in p for p in probs), "收据没写因由也没报")

        payload2 = json.loads(original)
        payload2["entries"] = [r for r in payload2["entries"] if r["name"] != "ForeignWiring"]
        write_json(payload2, bl)
        with contextlib.redirect_stderr(io.StringIO()):
            rc = generate_baseline(entries, src, bl, load_baseline(bl), "夹具：试删", False,
                                receipt_path=rcp)
        expect(rc == 1, "R6 默认没拒绝「会新增条目」的写盘")

        cur3 = {(e.name, rel(e.decl.file)): e for e in targets(entries)}

        def _path_of(name: str) -> str:
            return next(k[1] for k in cur3 if k[0] == name)

        # 种类门（防御性）：就算 bucket 被标成 by-design，带 install 入口的 object 也不许新增
        cur4 = {("Z_Wiring", "a/Z.kt"): _Cur(BUCKET_BY_DESIGN,
                                              _decl("Z_Wiring", funcs=("install",)))}
        probs = validate_allow_add(
            {"added": [{"name": "Z_Wiring", "file": "a/Z.kt", "bucket": BUCKET_BY_DESIGN}],
             "gone": [], "flipped": [], "clean": False},
            cur4, {"a/Z.kt": "A"}, _skip, "夹具：理由")
        expect(any("不是 by-design 种类" in p for p in probs),
               "带 install 入口的 object 竟然能走 --allow-add 进基线")

        # ⑬ --allow-add 加一条 unwired（ForeignWiring 是 object 无入口、但落待判）⇒ 拒
        probs = validate_allow_add(diff_baseline(entries, load_baseline(bl)), cur3,
                                   {_path_of("ForeignWiring"): "A"}, _skip, "夹具：理由")
        expect(any("待判" in p for p in probs),
               "⑬ --allow-add 加一条 unwired 没被拒（欠账不许进基线）")
        # ⑭ --note 为空 ⇒ 拒（用 ConstWiring：object 无入口、落 by-design）
        payload3 = json.loads(original)
        payload3["entries"] = [r for r in payload3["entries"] if r["name"] != "ConstWiring"]
        d4 = diff_baseline(entries, payload3)
        st4 = {_path_of("ConstWiring"): "A"}
        probs = validate_allow_add(d4, cur3, st4, _skip, "")
        expect(any("--note" in p for p in probs), "⑭ --note 为空没被拒")
        white_ok = {"ConstWiring": {"name": "ConstWiring", "kind": OBJECT_NO_ENTRY,
                                    "why": "夹具"}}
        add_lines = lambda _p: ["internal object ConstWiring {"]
        expect(validate_allow_add(d4, cur3, st4, add_lines, "夹具：理由",
                                  whitelist=white_ok) == [],
               "合规新增（种类+新声明+白名单+note 全齐）被误拒")
        # 白名单门：不在名册 ⇒ 拒；名册里登记的种类对不上 ⇒ 拒
        probs = validate_allow_add(d4, cur3, st4, add_lines, "夹具：理由", whitelist={})
        expect(any("不在白名单" in p for p in probs), "不在白名单也被放行")
        wrong_kind = {"ConstWiring": {"name": "ConstWiring", "kind": "data class",
                                      "why": "夹具"}}
        probs = validate_allow_add(d4, cur3, st4, add_lines, "夹具：理由",
                                   whitelist=wrong_kind)
        expect(any("白名单登记的种类" in p for p in probs), "白名单种类对不上也没拒")
        # 无旧同名门：added 与本次 gone 同名 ⇒ 拒（改名/替换塞账）
        d5 = {"added": [{"name": "ConstWiring", "file": _path_of("ConstWiring"),
                         "bucket": BUCKET_BY_DESIGN}],
              "gone": [{"name": "ConstWiring", "file": "a/old.kt", "bucket": BUCKET_PENDING}],
              "flipped": [], "clean": False}
        probs = validate_allow_add(d5, cur3, st4, add_lines, "夹具：理由", whitelist=white_ok)
        expect(any("同名" in p for p in probs), "与本次被删条目同名也没拒（改名塞账）")
        # 裁定表上限：11 条 ⇒ 报「超上限」
        saved2 = dict(RULINGS)
        try:
            for k in range(9):
                RULINGS["Fake%d_Wiring" % k] = (BY_RULED, "夹具：凑数")
            probs = audit_rulings(entries)
            expect(any("上限" in p for p in probs), "裁定表 11 条没报超上限")
        finally:
            RULINGS.clear()
            RULINGS.update(saved2)

        # 第四门：CI 里 --allow-add 一律拒
        os.environ["CI"] = "1"
        try:
            buf = io.StringIO()
            with contextlib.redirect_stderr(buf), contextlib.redirect_stdout(io.StringIO()):
                rc = main(["--generate", "--allow-add", "--note", "x", "--reason", "y",
                           "--src", str(src), "--baseline", str(bl)])
        finally:
            os.environ.pop("CI", None)
        expect(rc == 2 and "只许本地交互用" in buf.getvalue(),
               "CI 环境下 --allow-add 没被拒（流水线能洗账）")
        # R7：--generate 不带 --reason ⇒ 退 2
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf), contextlib.redirect_stdout(io.StringIO()):
            rc = main(["--generate", "--src", str(src), "--baseline", str(bl)])
        expect(rc == 2 and "必须带 --reason" in buf.getvalue(),
               "R7：--generate 不带 --reason 没被挡住（或没说清原因）")

    if fails:
        print("SELFTEST FAILED（%d/%d 条）" % (len(fails), checked))
        for f in fails:
            print("  · " + f)
        return 1
    print("SELFTEST OK：%d 条断言全过（扫描口径／端口接口／裁定表／基线往返／四条红／"
          "R5 减账三档（含种类判降档）／--allow-add 五门／R6 三态＋收据／R7 留因／幂等）" % checked)
    return 0


# ── 七、入口 ─────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    sys.exit(run())
