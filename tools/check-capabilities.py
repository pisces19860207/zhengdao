#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-capabilities.py —— 能力目录治理闸（837，2026-11-22）。

目的
----
`docs/capabilities.json` 是机器可读的能力目录（12 字段 + 2 现算读数）。本脚本负责：

1. 现算两列（禁止手填）：
   * externalConsumers —— 本包/本类被「本包与测试之外」的生产文件 import 或全限定引用的文件数；
   * wiredState —— 生产(>0) / 影子(仅影子文件引用) / 独立(0)。
   口径写在 capabilities.json 的 sourceOfTruth 里，脚本与之一致。
2. 交叉校验：status=生产批准 但 externalConsumers=0 ⇒ 红（"把仅测试当接线"的检测）。
3. dependsOn 必须指向已存在的 capabilityId，悬空 ⇒ 红。
4. 12 字段齐（尤其 banWhen/owner），status 四值、source 五值 —— 缺/错 ⇒ 红。
5. 全覆盖：state 全部包 + :policy 显式策略件，不抽样（清单在 EXPECTED_* 常量，与磁盘比对）。
6. --emit 把现算两列写回 json（唯一允许写文件的模式）；默认模式只读校验。
7. --md 生成人读 Markdown 摘要（按域分组）。

判据（派工单 837 §四 ①–⑧）：字段齐/交叉校验/现算/悬空/全覆盖/双格式/纯标准库只读/三套门禁由外部脚本负责。

用法
----
    python3 tools/check-capabilities.py            # 只读取证 + 判闸
    python3 tools/check-capabilities.py --emit     # 现算并写回两列（生成态）
    python3 tools/check-capabilities.py --md       # 生成人读摘要 docs/capabilities.md
    python3 tools/check-capabilities.py --selftest # 负例自测（临时目录，不动仓库）

退出码：0 = 全绿；1 = 判据红；2 = 环境/解析错误。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import tempfile
from collections import Counter

try:
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "docs", "capabilities.json")
MARKDOWN = os.path.join(ROOT, "docs", "capabilities.md")

REQUIRED_FIELDS = [
    "capabilityId", "purpose", "inputs", "outputs", "dependsOn", "source",
    "status", "callWhen", "banWhen", "onFailure", "acceptance", "owner",
]
VALID_STATUS = {"研发", "独立验证", "影子", "生产批准"}
VALID_SOURCE = {"数据", "规则", "教材", "模型", "工具"}

# 全覆盖基线：state 全部包（23）+ :policy 显式策略件（7）。与磁盘扫描对拍，不抽样。
EXPECTED_STATE = {
    "anchor","bearing","bodyparts","capacity","cavity","containment","environment",
    "fiction","fluid","form","geometry","injury","motion","persona","physiology","prepuce",
    "prostate","readback","stimulus","summary","surfaceflow","track","variant",
}
EXPECTED_POLICY = {
    "policy.reading-codex","policy.mediation","policy.mediation-decide",
    "policy.mediation-judge","policy.task-mode-boundary","policy.reframe",
    "policy.companion-mode",
}

SHADOW_RE = re.compile(r"shadow|rehearsal", re.I)

# 生产源根与测试源根（相对 ROOT）；与 catalog.sourceOfTruth 一致。
PROD_ROOTS = [
    "app/src/main/java", "policy/src/main/java",
    "core/src/main/java", "native-core/src/main/java",
]


def norm_rel(path: str) -> str:
    return os.path.relpath(path, ROOT).replace("\\", "/")


def production_files(base: str = ROOT) -> list[tuple[str, str]]:
    """返回 (绝对路径, 仓库相对路径) 的生产 .kt 文件。测试源集天然不在 PROD_ROOTS 内。"""
    out = []
    for r in PROD_ROOTS:
        d = os.path.join(base, r)
        if not os.path.isdir(d):
            continue
        for dp, _dirs, files in os.walk(d):
            for fn in files:
                if fn.endswith(".kt"):
                    ap = os.path.join(dp, fn)
                    out.append((ap, os.path.relpath(ap, base).replace("\\", "/")))
    return out


def strip_code(raw: str) -> str:
    """去行注释/块注释/字符串字面量，避免注释里的类名被误算成引用。块注释状态机跨行。"""
    lines = []
    in_block = False
    for line in raw.splitlines():
        s = line
        if in_block:
            e = s.find("*/")
            if e < 0:
                continue
            s = s[e + 2:]
            in_block = False
        # 行注释
        ci = s.find("//")
        if ci >= 0:
            s = s[:ci]
        # 块注释（本行开始未闭合）
        while "/*" in s:
            b = s.find("/*")
            e = s.find("*/", b + 2)
            if e < 0:
                s = s[:b]
                in_block = True
                break
            s = s[:b] + s[e + 2:]
        # 字符串/字符字面量（含三引号内容行按空处理）
        s = re.sub(r"\"\"\".*?\"\"\"", " ", s)
        s = re.sub(r"\"(?:\\.|[^\"\\])*\"", " ", s)
        s = re.sub(r"'(?:\\.|[^'\\])*'", " ", s)
        lines.append(s)
    return "\n".join(lines)


def ref_pattern(scan: dict) -> re.Pattern:
    """按 scan.kind 造引用正则：包的 import/全限定前缀；类/符号的标识符边界。"""
    if scan["kind"] == "package":
        # import com.example.zhengdao.baopu.state.<pkg>.X 或全限定 baopu.state.<pkg>.X
        return re.compile(r"\b" + re.escape(scan["prefix"].rstrip(".")) + r"\.[A-Za-z_]")
    name = re.escape(scan["name"])
    # 类：import ...baopu.<Name> 或裸类名；符号：仅标识符边界
    return re.compile(r"\b" + name + r"\b")


def is_own(rel: str, scan: dict) -> bool:
    if scan["kind"] == "package":
        return ("/" + scan["selfDir"] + "/") in rel
    return rel.endswith("/" + scan["selfFile"])


def compute_consumers(scan: dict, files: list[tuple[str, str]]) -> dict:
    """现算 externalConsumers / shadowConsumers / consumers 明细。"""
    pat = ref_pattern(scan)
    prod, shadow, detail = [], [], []
    for ap, rel in files:
        if is_own(rel, scan):
            continue
        code = strip_code(open(ap, encoding="utf-8", errors="replace").read())
        if pat.search(code):
            if SHADOW_RE.search(os.path.basename(rel)):
                shadow.append(rel)
            else:
                prod.append(rel)
            detail.append(rel)
    ext = len(prod)
    wired = "生产" if ext > 0 else ("影子" if shadow else "独立")
    return {
        "externalConsumers": ext,
        "wiredState": wired,
        "shadowConsumers": len(shadow),
        "consumerFiles": sorted(detail),
    }


def field_ok(v) -> bool:
    if isinstance(v, str):
        return bool(v.strip())
    if isinstance(v, list):
        return len(v) > 0
    return v is not None


def validate(doc: dict, files: list[tuple[str, str]], full: bool = True) -> tuple[list[str], list[str]]:
    """返回 (errors, infos)。full=False 时跳过覆盖/交叉校验（selftest 用）。"""
    errs, infos = [], []
    caps = doc.get("capabilities", [])
    if not isinstance(caps, list):
        return ["capabilities 不是数组"], infos

    ids = [c.get("capabilityId") for c in caps]
    dup = [k for k, n in Counter(ids).items() if n > 1]
    if dup:
        errs.append("capabilityId 重复：%s" % dup)

    # 判据① 12 字段齐（banWhen/owner 尤其）
    for c in caps:
        cid = c.get("capabilityId", "<无 id>")
        for f in REQUIRED_FIELDS:
            if f not in c:
                errs.append("字段缺失/空：%s.%s" % (cid, f))
                continue
            # dependsOn 合法为空（无依赖的原子能力），只校验它是数组，不要求非空
            if f == "dependsOn":
                if not isinstance(c[f], list):
                    errs.append("dependsOn 非数组：%s" % cid)
                continue
            if not field_ok(c[f]):
                errs.append("字段缺失/空：%s.%s" % (cid, f))
        # 判据① status 四值 / source 五值
        if c.get("status") not in VALID_STATUS:
            errs.append("status 非法：%s=%r（只许 %s）" % (cid, c.get("status"), sorted(VALID_STATUS)))
        if c.get("source") not in VALID_SOURCE:
            errs.append("source 非法：%s=%r（只许 %s）" % (cid, c.get("source"), sorted(VALID_SOURCE)))
        if not isinstance(c.get("dependsOn"), list):
            errs.append("dependsOn 非数组：%s" % cid)

    # 判据④ 悬空依赖
    idset = set(ids)
    for c in caps:
        for d in c.get("dependsOn", []) if isinstance(c.get("dependsOn"), list) else []:
            if d not in idset:
                errs.append("悬空依赖：%s → %s" % (c.get("capabilityId"), d))

    # 判据⑤ 全覆盖（不抽样）
    if full:
        state_ids = {i for i in ids if str(i).startswith("state.")}
        state_pkgs = {i[len("state."):] for i in state_ids}
        miss = EXPECTED_STATE - state_pkgs
        extra = state_pkgs - EXPECTED_STATE
        if miss:
            errs.append("覆盖缺失 state 包：%s" % sorted(miss))
        if extra:
            errs.append("多出未登记 state 包：%s" % sorted(extra))
        pol_ids = {i for i in ids if str(i).startswith("policy.")}
        if EXPECTED_POLICY - pol_ids:
            errs.append("覆盖缺失 policy 策略件：%s" % sorted(EXPECTED_POLICY - pol_ids))
        if pol_ids - EXPECTED_POLICY:
            errs.append("多出未登记 policy 策略件：%s" % sorted(pol_ids - EXPECTED_POLICY))
        # 磁盘对拍：state 目录真实存在
        state_dir = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "zhengdao", "baopu", "state")
        disk_pkgs = set()
        if os.path.isdir(state_dir):
            for name in os.listdir(state_dir):
                if os.path.isdir(os.path.join(state_dir, name)):
                    disk_pkgs.add(name)
        if disk_pkgs != EXPECTED_STATE:
            infos.append("磁盘 state 包与基线差异：磁盘缺=%s 磁盘多=%s（基线需按交付更新）" %
                         (sorted(EXPECTED_STATE - disk_pkgs), sorted(disk_pkgs - EXPECTED_STATE)))

    # 判据②③ 交叉校验 + 现算两列比对
    for c in caps:
        cid = c.get("capabilityId", "<无 id>")
        scan = c.get("scan")
        if not scan or not isinstance(scan, dict):
            errs.append("缺 scan 现算定位：%s" % cid)
            continue
        r = compute_consumers(scan, files)
        # 与 json 中记录的两列比对（防手填/防漂移）
        for k in ("externalConsumers", "wiredState", "shadowConsumers", "consumerFiles"):
            if k in c and c[k] != r[k]:
                errs.append("现算不一致：%s.%s json=%r 现算=%r" % (cid, k, c[k], r[k]))
        # 交叉校验：生产批准但外部消费者 0 ⇒ 红
        if c.get("status") == "生产批准" and r["externalConsumers"] == 0:
            errs.append("交叉校验红：%s 标生产批准但 externalConsumers=0（把仅测试/影子当接线）" % cid)
        # 反向软提示：独立验证却有消费者（不红，仅提示复核）
        if c.get("status") == "独立验证" and r["externalConsumers"] > 0:
            infos.append("复核：%s 标独立验证但 externalConsumers=%d" % (cid, r["externalConsumers"]))
        infos.append("%-26s ext=%-3d shadow=%-2d wired=%s  status=%s" %
                     (cid, r["externalConsumers"], r["shadowConsumers"], r["wiredState"], c.get("status")))
    return errs, infos


def emit(doc: dict, files: list[tuple[str, str]]) -> dict:
    """现算并写回两列（唯一写文件模式）。"""
    touched = 0
    for c in doc["capabilities"]:
        r = compute_consumers(c["scan"], files)
        for k in ("externalConsumers", "wiredState", "shadowConsumers", "consumerFiles"):
            c[k] = r[k]
        touched += 1
    with open(CATALOG, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print("[emit] 已现算写回 %d 条 → %s" % (touched, CATALOG))
    return doc


def gen_markdown(doc: dict) -> str:
    groups: dict[str, list] = {}
    for c in doc["capabilities"]:
        groups.setdefault(c.get("domain", "未分组"), []).append(c)
    L = [
        "# 能力目录（人读摘要）", "",
        "> 机器可读真相源：`docs/capabilities.json`；本摘要由 `tools/check-capabilities.py --md` 生成，",
        "> 按域分组。`externalConsumers` / `wiredState` 为脚本现算列，禁止手填。", "",
        "共 **%d** 条能力（state %d ＋ policy %d）；状态以 `文件:行号` 证据为准，不评价能力好坏。" % (
            len(doc["capabilities"]),
            sum(1 for c in doc["capabilities"] if c["capabilityId"].startswith("state.")),
            sum(1 for c in doc["capabilities"] if c["capabilityId"].startswith("policy."))), "",
    ]
    for domain in sorted(groups):
        items = groups[domain]
        L += ["## %s（%d）" % (domain, len(items)), "",
              "| capabilityId | status | wiredState | 外部消费者 | source | purpose |",
              "|---|---|---|---:|---|---|"]
        for c in items:
            p = c["purpose"].replace("|", "\\|").replace("\n", " ")
            if len(p) > 52:
                p = p[:52] + "…"
            L.append("| `%s` | %s | %s | %d | %s | %s |" % (
                c["capabilityId"], c["status"], c.get("wiredState", "-"),
                c.get("externalConsumers", -1), c["source"], p))
        L.append("")
        for c in items:
            L += ["### `%s`" % c["capabilityId"], "",
                  "- **purpose**：%s" % c["purpose"],
                  "- **inputs**：%s" % c["inputs"],
                  "- **outputs**：%s" % c["outputs"],
                  "- **dependsOn**：%s" % (", ".join("`%s`" % d for d in c["dependsOn"]) or "无"),
                  "- **source/status**：%s / %s" % (c["source"], c["status"]),
                  "- **callWhen**：%s" % c["callWhen"],
                  "- **banWhen**：%s" % c["banWhen"],
                  "- **onFailure**：%s" % c["onFailure"],
                  "- **acceptance**：%s" % ", ".join(c["acceptance"]),
                  "- **owner**：%s" % c["owner"],
                  "- **现算**：externalConsumers=%d / wiredState=%s / shadowConsumers=%d" % (
                      c.get("externalConsumers", -1), c.get("wiredState", "-"), c.get("shadowConsumers", -1)),
                  ""]
    return "\n".join(L)


def selftest() -> int:
    """临时目录验证各负例能被闸门咬住，不动仓库。"""
    tmp = tempfile.mkdtemp(prefix="caps-")
    ok = []
    try:
        def mk(rel, text):
            ap = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(ap), exist_ok=True)
            with open(ap, "w", encoding="utf-8") as fh:
                fh.write(text)
        mk("app/src/main/java/x/Prod.kt", "import com.example.zhengdao.baopu.state.foo.Foo\nclass Prod\n")
        mk("app/src/test/java/x/FooTest.kt", "import com.example.zhengdao.baopu.state.foo.Foo\n")
        mk("app/src/main/java/x/ShadowX.kt", "import com.example.zhengdao.baopu.state.bar.Bar\n")
        mk("app/src/main/java/com/example/zhengdao/baopu/state/foo/Foo.kt", "package foo\nclass Foo\n")
        files = production_files(tmp)

        scan_foo = {"kind": "package", "prefix": "baopu.state.foo.", "selfDir": "baopu/state/foo"}
        scan_bar = {"kind": "package", "prefix": "baopu.state.bar.", "selfDir": "baopu/state/bar"}
        scan_baz = {"kind": "package", "prefix": "baopu.state.baz.", "selfDir": "baopu/state/baz"}
        r_foo = compute_consumers(scan_foo, files)
        r_bar = compute_consumers(scan_bar, files)
        r_baz = compute_consumers(scan_baz, files)
        ok.append(("现算：测试引用不计入", r_foo["externalConsumers"] == 1))
        ok.append(("现算：影子文件不计入生产且判影子", r_bar["externalConsumers"] == 0 and r_bar["wiredState"] == "影子"))
        ok.append(("现算：零引用判独立", r_baz["wiredState"] == "独立"))

        base = dict(capabilityId="state.baz", purpose="p", inputs="i", outputs="o", dependsOn=[],
                    source="规则", status="独立验证", callWhen="c", banWhen="b", onFailure="f",
                    acceptance=["T"], owner="o", scan=scan_baz)
        doc_ok = {"capabilities": [dict(base)]}
        e, _ = validate(doc_ok, files, full=False)
        ok.append(("正例：字段齐全独立验证 ⇒ 绿", e == []))

        d = dict(base, status="生产批准")
        e, _ = validate({"capabilities": [d]}, files, full=False)
        ok.append(("交叉校验：生产批准但0消费者 ⇒ 红", any("交叉校验红" in x for x in e)))

        d = dict(base)
        del d["banWhen"]
        e, _ = validate({"capabilities": [d]}, files, full=False)
        ok.append(("字段齐：缺 banWhen ⇒ 红", any("banWhen" in x for x in e)))

        d = dict(base, dependsOn=["state.notexist"])
        e, _ = validate({"capabilities": [d]}, files, full=False)
        ok.append(("悬空依赖 ⇒ 红", any("悬空依赖" in x for x in e)))

        d = dict(base, status="上线")
        e, _ = validate({"capabilities": [d]}, files, full=False)
        ok.append(("status 四值外 ⇒ 红", any("status 非法" in x for x in e)))

        d = dict(base, source="玄学")
        e, _ = validate({"capabilities": [d]}, files, full=False)
        ok.append(("source 五值外 ⇒ 红", any("source 非法" in x for x in e)))

        # 注释里的 import 不得误算成引用
        mk("app/src/main/java/x/Comment.kt", "// import com.example.zhengdao.baopu.state.baz.Baz\n/* import com.example.zhengdao.baopu.state.baz.Baz */\n")
        files2 = production_files(tmp)
        r2 = compute_consumers(scan_baz, files2)
        ok.append(("负例：注释里的 import 不算引用", r2["externalConsumers"] == 0))

        for name, passed in ok:
            print("  [%s] %s" % ("✔" if passed else "✘", name))
        all_ok = all(p for _n, p in ok)
        print("  自测：%s（%d/%d）" % ("全部通过 ✔" if all_ok else "有失败 ✘", sum(1 for _, p in ok if p), len(ok)))
        return 0 if all_ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(description="能力目录治理闸（837）")
    ap.add_argument("--emit", action="store_true", help="现算并写回 externalConsumers/wiredState 两列")
    ap.add_argument("--md", action="store_true", help="生成人读 Markdown 摘要")
    ap.add_argument("--selftest", action="store_true", help="负例自测")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    try:
        with open(CATALOG, encoding="utf-8") as fh:
            doc = json.load(fh)
        files = production_files()
    except (OSError, json.JSONDecodeError) as exc:
        print("::error title=能力目录闸::环境/解析错误：%s" % exc)
        return 2

    if args.emit:
        emit(doc, files)
        return 0

    if args.md:
        text = gen_markdown(doc)
        with open(MARKDOWN, "w", encoding="utf-8") as fh:
            fh.write(text)
        print("[md] 已生成 %s" % MARKDOWN)
        return 0

    errs, infos = validate(doc, files, full=True)
    print("=== 能力目录治理闸（837）现算读数 ===")
    for line in infos:
        print("  " + line)
    print("=== 判据结果 ===")
    if errs:
        for e in errs:
            print("::error title=能力目录闸(837)::%s" % e)
        print("[能力目录闸] 红：%d 项" % len(errs))
        return 1
    print("[能力目录闸] 全绿：%d 条能力，字段齐/交叉校验/现算/悬空/全覆盖 通过" % len(doc["capabilities"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
