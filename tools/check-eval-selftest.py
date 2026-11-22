#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-eval-selftest.py —— 786 叙事连续性评测集的**数据自洽校验**（797）。

为什么需要它
    792 判分器首跑被「三条改判条目 verdict 变了、accept 还停在旧档」挡住
    （NAR-IB-03 / NAR-IB-08 / NAR-CL-06）。根因不是判分器，是**数据自己不自洽**。
    200 条 accept 已由人工规范化为「只含与 verdict 同档的别名」；本脚本把这条经验
    固化成只读门禁，让同类错误下次在进判分器之前就被拦下。

它查什么（六项，逐条独立报错）
    ① accept ⊆ 同档别名       越档 ⇒ 红，指出 id 与越档词
    ② verdict ∈ accept        每条 verdict 必须能在 accept 里找到自己
    ③ id 唯一 + 双份一致       items.jsonl 与 items/*.jsonl 同 id 同内容；重复 id ⇒ 红
    ④ evidence 可机械复核     quote 必须是指定行的连续子串；语料不在仓内 ⇒ 标「未复核」
    ⑤ 未知项必填 absence_scope 真值为「未知」却缺检索范围 ⇒ 红
    ⑥ 类别与数量             11 类齐全；总数与 manifest.json 一致（差几条要点出来）

它不干什么
    - 不改数据：全程只读真实数据；--selftest 的夹具只写在系统临时目录
    - 不判分：三档混淆矩阵／幻觉率是 792 的事
    - 不跑被测系统：纯 Python 标准库，不碰 App、不碰 Gradle
    - 不改 786 的字段定义与 README：别名表与 11 类名是从 786 README 抄来的**只读快照**

用法
    python3 tools/check-eval-selftest.py                # 校验仓库里的评测集
    python3 tools/check-eval-selftest.py --data-dir DIR # 校验指定数据目录（夹具用）
    python3 tools/check-eval-selftest.py --selftest     # 原样绿 + 四类反例夹具全红

退出码
    0 = 绿（0 处错误）；1 = 红（有错误，或数据/环境坏到确认不了）；2 = 用法错误
"""

import argparse
import json
import shutil
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，别让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass


# ── 从 786 README §三/§四/§六 抄来的只读快照（不修改 786 的任何文件） ──────────
ALIASES = {
    "支持": ("支持", "SUPPORT", "一致"),
    "矛盾": ("矛盾", "CONFLICT", "冲突"),
    "未知": ("未知", "UNKNOWN", "不确定"),
}
ALIAS_TO_TIER = {alias: tier for tier, aliases in ALIASES.items() for alias in aliases}
EXPECTED_CATEGORIES = (
    "人物一致", "设定矛盾", "时间线", "场次转换", "空间不成立",
    "生理不合理", "信息越界", "物品守恒", "套话与套腔", "伏笔回收", "发现时点",
)
DATA_REL = Path("docs/evals/narrative")


@dataclass(frozen=True)
class Finding:
    code: str
    item_id: str
    field: str
    expected: str
    actual: str
    source: str = ""

    def line(self) -> str:
        src = f" src={self.source}" if self.source else ""
        return (
            f"✗ [{self.code}] id={self.item_id}{src} field={self.field} "
            f"expected={self.expected} actual={self.actual}"
        )


@dataclass
class Stats:
    main_count: int = 0
    category_count: int = 0
    category_files: int = 0
    evidence_verified: int = 0
    evidence_unverified: int = 0


def load_jsonl(path: Path, label: str, out: list[Finding]) -> list[tuple[int, dict]]:
    """逐行读 JSONL；坏行单独报错并继续，不静默跳过。"""
    rows: list[tuple[int, dict]] = []
    if not path.is_file():
        out.append(Finding("FILE_MISSING", "(文件)", str(path), "文件存在", "缺失", label))
        return rows
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as exc:  # noqa: BLE001 - 门禁脚本要说出坏在哪
        out.append(Finding("FILE_UNREADABLE", "(文件)", str(path), "UTF-8 可读", str(exc)[:80], label))
        return rows
    for n, raw in enumerate(text.splitlines(), 1):
        if not raw.strip():
            continue
        try:
            obj = json.loads(raw)
        except Exception as exc:  # noqa: BLE001
            out.append(Finding("BAD_JSON", f"(行{n})", "json", "合法 JSON 对象", str(exc)[:80], label))
            continue
        if not isinstance(obj, dict):
            out.append(Finding("BAD_JSON", f"(行{n})", "json", "JSON 对象", type(obj).__name__, label))
            continue
        rows.append((n, obj))
    return rows


def _fmt_aliases(tier: str) -> str:
    return "/".join(ALIASES.get(tier, ()))


def check(data_dir: Path) -> tuple[list[Finding], Stats]:
    """对 data_dir 做六项只读校验，返回（错误列表, 统计）。"""
    out: list[Finding] = []
    stats = Stats()

    manifest_path = data_dir / "manifest.json"
    manifest: dict = {}
    if not manifest_path.is_file():
        out.append(Finding("MANIFEST_MISSING", "(文件)", "manifest.json", "文件存在", "缺失"))
    else:
        try:
            loaded = json.loads(manifest_path.read_text(encoding="utf-8"))
            if not isinstance(loaded, dict):
                out.append(Finding("MANIFEST_BAD", "(文件)", "manifest.json", "JSON 对象", type(loaded).__name__))
            else:
                manifest = loaded
        except Exception as exc:  # noqa: BLE001
            out.append(Finding("MANIFEST_BAD", "(文件)", "manifest.json", "合法 JSON", str(exc)[:80]))

    main_rows = load_jsonl(data_dir / "items.jsonl", "items.jsonl", out)
    stats.main_count = len(main_rows)
    cat_dir = data_dir / "items"
    cat_rows: list[tuple[str, int, dict]] = []
    if not cat_dir.is_dir():
        out.append(Finding("DIR_MISSING", "(目录)", "items/", "目录存在", "缺失"))
    else:
        for path in sorted(cat_dir.glob("*.jsonl")):
            for n, obj in load_jsonl(path, path.name, out):
                cat_rows.append((path.name, n, obj))
    stats.category_count = len(cat_rows)
    stats.category_files = len(list(cat_dir.glob("*.jsonl"))) if cat_dir.is_dir() else 0

    # ── ①②⑤：逐条查 accept / verdict / absence_scope ──────────────────────
    for _n, it in main_rows:
        iid = str(it.get("id", "(缺 id)"))
        verdict = it.get("verdict")
        accept = it.get("accept")

        if verdict not in ALIASES:
            out.append(Finding(
                "VERDICT_INVALID", iid, "verdict",
                "支持/矛盾/未知", _short(verdict),
            ))

        if not isinstance(accept, list) or not accept:
            out.append(Finding(
                "ACCEPT_MISSING", iid, "accept",
                f"非空数组，含 verdict={_short(verdict)}", _short(accept),
            ))
        else:
            for alias in accept:
                tier = ALIAS_TO_TIER.get(alias) if isinstance(alias, str) else None
                if tier is None:
                    out.append(Finding(
                        "ACCEPT_ALIAS_UNKNOWN", iid, "accept",
                        "已知别名（支持/SUPPORT/一致…）", _short(alias),
                    ))
                elif verdict in ALIASES and tier != verdict:
                    out.append(Finding(
                        "ACCEPT_CROSS_TIER", iid, "accept",
                        f"{_fmt_aliases(verdict)}(与 verdict 同档)", _short(alias),
                    ))
            if isinstance(verdict, str) and verdict in ALIASES and verdict not in accept:
                out.append(Finding(
                    "VERDICT_NOT_IN_ACCEPT", iid, "accept",
                    f"包含 verdict={verdict}", "，".join(_short(a) for a in accept),
                ))

        if verdict == "未知":
            scope = it.get("absence_scope")
            if not isinstance(scope, str) or not scope.strip():
                out.append(Finding(
                    "ABSENCE_SCOPE_MISSING", iid, "absence_scope",
                    "非空字符串（检索了哪些文件/词、结论无命中）", _short(scope),
                ))

    # ── ③：id 唯一 + items.jsonl 与 items/*.jsonl 逐条一致 ──────────────────
    main_by_id: dict[str, dict] = {}
    for n, it in main_rows:
        iid = it.get("id")
        if not isinstance(iid, str) or not iid:
            out.append(Finding("ID_MISSING", f"(行{n})", "id", "非空字符串", _short(iid), "items.jsonl"))
            continue
        if iid in main_by_id:
            out.append(Finding("DUP_ID", iid, "id", "全库唯一", f"重复 {Counter(x.get('id') for _, x in main_rows)[iid]} 次", "items.jsonl"))
        else:
            main_by_id[iid] = it

    cat_by_id: dict[str, tuple[str, dict]] = {}
    for fname, n, it in cat_rows:
        iid = it.get("id")
        if not isinstance(iid, str) or not iid:
            out.append(Finding("ID_MISSING", f"(行{n})", "id", "非空字符串", _short(iid), fname))
            continue
        if iid in cat_by_id:
            out.append(Finding("DUP_ID", iid, "id", "全库唯一", f"重复（{cat_by_id[iid][0]} / {fname}）", "items/*.jsonl"))
        else:
            cat_by_id[iid] = (fname, it)

    for iid in sorted(cat_by_id.keys() - main_by_id.keys()):
        out.append(Finding("MIRROR_MISSING", iid, "id", "items.jsonl 中存在", "仅见于 items/*.jsonl", cat_by_id[iid][0]))
    for iid in sorted(main_by_id.keys() - cat_by_id.keys()):
        out.append(Finding("MIRROR_MISSING", iid, "id", "items/*.jsonl 中存在", "仅见于 items.jsonl", "items.jsonl"))
    for iid in sorted(main_by_id.keys() & cat_by_id.keys()):
        if main_by_id[iid] != cat_by_id[iid][1]:
            out.append(Finding("MIRROR_CONTENT", iid, "content", "与 items.jsonl 逐字段相同", "双份内容不一致", cat_by_id[iid][0]))

    # ── ④：evidence.quote 必须是该行连续子串；仓外语料标「未复核」 ──────────
    doc_to_file: dict[str, Path] = {}
    corpus = manifest.get("corpus")
    if isinstance(corpus, list):
        for entry in corpus:
            if isinstance(entry, dict) and entry.get("scope") == "in-repo" and entry.get("id") and entry.get("file"):
                doc_to_file[str(entry["id"])] = data_dir / str(entry["file"])
    line_cache: dict[str, list[str]] = {}

    for _n, it in main_rows:
        iid = str(it.get("id", "(缺 id)"))
        ev = it.get("evidence")
        if not isinstance(ev, list):
            out.append(Finding("EVIDENCE_MISSING", iid, "evidence", "数组", _short(ev)))
            continue
        for idx, e in enumerate(ev):
            if not isinstance(e, dict):
                out.append(Finding("EVIDENCE_BAD", iid, f"evidence[{idx}]", "对象", _short(e)))
                continue
            doc, line, quote = e.get("doc"), e.get("line"), e.get("quote")
            if doc not in doc_to_file:
                stats.evidence_unverified += 1  # 语料在仓外，按派工单标「未复核」，不判红
                continue
            path = doc_to_file[doc]
            if doc not in line_cache:
                try:
                    line_cache[doc] = path.read_text(encoding="utf-8").splitlines()
                except Exception as exc:  # noqa: BLE001
                    out.append(Finding("CORPUS_UNREADABLE", iid, f"evidence[{idx}].doc",
                                       f"{path} 可读", str(exc)[:80], str(doc)))
                    line_cache[doc] = []
            lines = line_cache[doc]
            if not isinstance(line, int) or isinstance(line, bool) or not (1 <= line <= len(lines)):
                out.append(Finding("EVIDENCE_LINE_BAD", iid, f"evidence[{idx}].line",
                                   f"1..{len(lines)} 的整数", _short(line), str(doc)))
                continue
            if not isinstance(quote, str) or not quote.strip():
                out.append(Finding("EVIDENCE_QUOTE_EMPTY", iid, f"evidence[{idx}].quote",
                                   "非空连续子串", _short(quote), str(doc)))
                continue
            if quote not in lines[line - 1]:
                out.append(Finding("EVIDENCE_QUOTE_OFF_LINE", iid, f"evidence[{idx}].quote",
                                   f"是 {doc} 第 {line} 行的连续子串", _short(quote), str(doc)))
            else:
                stats.evidence_verified += 1

    # ── ⑥：11 类齐全 + 总数与 manifest 一致 ────────────────────────────────
    actual_cats = {it.get("category") for _n, it in main_rows}
    for cat in EXPECTED_CATEGORIES:
        if cat not in actual_cats:
            out.append(Finding("CATEGORY_MISSING", "(全库)", "category", f"存在 {cat} 类", "缺失"))
    for cat in sorted(str(c) for c in actual_cats if c not in EXPECTED_CATEGORIES):
        out.append(Finding("CATEGORY_UNEXPECTED", "(全库)", "category", "786 的 11 类之一", cat))
    cat_file_cats = {it.get("category") for _f, _n, it in cat_rows}
    for cat in EXPECTED_CATEGORIES:
        if cat not in cat_file_cats:
            out.append(Finding("CATEGORY_FILE_MISSING", "(全库)", "items/*.jsonl", f"含 {cat} 类条目", "缺失"))

    expected_total = (manifest.get("counts") or {}).get("total") if isinstance(manifest.get("counts"), dict) else None
    if not isinstance(expected_total, int) or isinstance(expected_total, bool):
        out.append(Finding("MANIFEST_TOTAL_BAD", "(全库)", "manifest.counts.total", "整数", _short(expected_total)))
    elif expected_total != stats.main_count:
        diff = stats.main_count - expected_total
        out.append(Finding(
            "COUNT_MISMATCH", "(全库)", "manifest.counts.total",
            f"与 items.jsonl 一致（{expected_total}）",
            f"items.jsonl={stats.main_count}，差 {diff:+d} 条",
        ))

    return out, stats


def _short(value: object, limit: int = 60) -> str:
    text = "null" if value is None else str(value)
    return text if len(text) <= limit else text[:limit] + "…"


# ── 反例夹具：只在系统临时目录里改，绝不碰真实数据 ──────────────────────────
def _rewrite_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
        encoding="utf-8",
    )


def _mutate_both(base: Path, item_id: str, transform) -> None:
    """同时改 items.jsonl 与所属分类文件，隔离出「只有目标检查报错」的夹具。"""
    for path in [base / "items.jsonl", *sorted((base / "items").glob("*.jsonl"))]:
        rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
        changed = False
        for row in rows:
            if row.get("id") == item_id:
                transform(row)
                changed = True
        if changed:
            _rewrite_jsonl(path, rows)


def _selftest(source: Path) -> int:
    """原样数据必须绿；六类反例必须红且命中预期错误码。"""
    print("=== 797 反例夹具自检（临时目录，真实数据只读） ===")
    with tempfile.TemporaryDirectory(prefix="797-fixtures-") as td:
        tmp = Path(td)
        base = tmp / "data"
        shutil.copytree(source, base)

        clean, _ = check(base)
        print(f"  · 原样夹具：{'✅ 绿（0 处错误）' if not clean else '❌ 红（非预期）'}")
        for f in clean[:5]:
            print(f"    {f.line()}")
        if clean:
            print("❌ 自检失败：原样数据应绿")
            return 1

        def pick(pred):
            for row in _read_rows(base):
                if pred(row):
                    return row["id"]
            raise RuntimeError("夹具找不到合适样本")

        def cross_tier(row):
            other = "支持" if row["verdict"] == "未知" else "未知"
            row["accept"] = list(row["accept"]) + [other]

        def drop_self(row):
            row["accept"] = [a for a in row["accept"] if a != row["verdict"]]

        def drop_absence(row):
            row.pop("absence_scope", None)

        def break_quote(row):
            for ev in row.get("evidence", []):
                if isinstance(ev, dict) and ev.get("doc") == "P2-C4":
                    ev["quote"] = "__夹具：不是该行连续子串__"
                    return

        def apply_item(item_id, transform):
            return lambda fixture_dir, _iid: _mutate_both(fixture_dir, item_id, transform)

        def apply_dup(fixture_dir, item_id):
            """双份各追加一条同 id 记录；同步抬 manifest 总数，隔离出只有 ③ 红。"""
            src = next(r for r in _read_rows(fixture_dir) if r.get("id") == item_id)
            for path in [fixture_dir / "items.jsonl", *sorted((fixture_dir / "items").glob("*.jsonl"))]:
                rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
                if any(r.get("id") == item_id for r in rows):
                    rows.append(json.loads(json.dumps(src, ensure_ascii=False)))
                    _rewrite_jsonl(path, rows)
            _bump_total(fixture_dir)

        def apply_count(fixture_dir, _iid):
            _bump_total(fixture_dir)

        id_cross = pick(lambda r: r.get("verdict") in ALIASES and isinstance(r.get("accept"), list))
        id_self = pick(lambda r: r.get("verdict") in ALIASES and len(r.get("accept") or []) > 1)
        id_dup = pick(lambda r: True)
        id_absence = pick(lambda r: r.get("verdict") == "未知")
        id_quote = pick(lambda r: any(
            isinstance(e, dict) and e.get("doc") == "P2-C4" for e in (r.get("evidence") or [])
        ))

        # 前四个是派工单指定反例；后两个给 ④⑥ 也留上负例回归。
        fixtures = [
            ("越档", id_cross, apply_item(id_cross, cross_tier), "ACCEPT_CROSS_TIER"),
            ("缺自映射", id_self, apply_item(id_self, drop_self), "VERDICT_NOT_IN_ACCEPT"),
            ("重复 id", id_dup, apply_dup, "DUP_ID"),
            ("缺 absence_scope", id_absence, apply_item(id_absence, drop_absence), "ABSENCE_SCOPE_MISSING"),
            ("evidence 非行内子串", id_quote, apply_item(id_quote, break_quote), "EVIDENCE_QUOTE_OFF_LINE"),
            ("总数与 manifest 不符", "(全库)", apply_count, "COUNT_MISMATCH"),
        ]

        failed = 0
        for idx, (name, iid, apply, want) in enumerate(fixtures):
            fixture_dir = tmp / f"fixture-{idx}"
            shutil.copytree(base, fixture_dir)
            apply(fixture_dir, iid)

            findings, _ = check(fixture_dir)
            hits = [f for f in findings if f.code == want]
            ok = bool(hits)
            failed += 0 if ok else 1
            mark = "✅ 命中" if ok else "❌ 未命中"
            print(f"  · {name:<20} {mark} code={want} id={iid} 红={len(findings)} 处")
            for f in (hits or findings)[:2]:
                print(f"    {f.line()}")

    if failed:
        print(f"❌ 自检失败：{failed}/{len(fixtures)} 个反例未按预期报红")
        return 1
    print("✅ 自检通过：原样 0 错；越档／缺自映射／重复 id／缺 absence_scope 四类必做反例"
          "＋ evidence／总数两类补充反例，全部红")
    return 0


def _bump_total(data_dir: Path) -> None:
    mp = data_dir / "manifest.json"
    man = json.loads(mp.read_text(encoding="utf-8"))
    man["counts"]["total"] += 1
    mp.write_text(json.dumps(man, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _read_rows(base: Path) -> list[dict]:
    path = base / "items.jsonl"
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="786 评测集数据自洽校验（只读、纯标准库）")
    parser.add_argument("--data-dir", help="评测集目录（默认：<仓库根>/docs/evals/narrative）")
    parser.add_argument("--selftest", action="store_true", help="原样绿 + 六类反例夹具全红")
    args = parser.parse_args(argv[1:])

    if args.data_dir:
        data_dir = Path(args.data_dir).resolve()
    else:
        data_dir = Path(__file__).resolve().parent.parent / DATA_REL
    if not data_dir.is_dir():
        print(f"[用法错误] 数据目录不存在：{data_dir}")
        return 2
    if args.selftest:
        return _selftest(data_dir)

    findings, stats = check(data_dir)
    print("=== 797 评测集自洽校验 ===")
    print(f"数据目录: {data_dir}")
    print(f"条目数: items.jsonl {stats.main_count} 条；items/*.jsonl {stats.category_count} 条（{stats.category_files} 文件）")
    print(f"证据复核: 仓内可复核 {stats.evidence_verified} 条；语料在仓外未复核 {stats.evidence_unverified} 条")
    print("校验项: ① accept 同档 ② verdict 自映射 ③ id 唯一+双份一致 ④ evidence 行内子串 ⑤ 未知项 absence_scope ⑥ 11 类+总数")
    if findings:
        print(f"--- {len(findings)} 处错误（每条一行：id + 字段 + 期望 vs 实际）---")
        for f in findings:
            print(f.line())
        print(f"❌ 红：{len(findings)} 处不自洽 —— 数据未改，请先修数据再跑 792 判分器")
        return 1
    print("✅ 绿：六项自洽校验全通过，0 处错误（只读校验，数据未改）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
