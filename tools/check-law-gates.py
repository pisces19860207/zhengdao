#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-law-gates.py —— 法典**门禁与规范**的形状机检（第二百七十一批；271-补 分两层）。

为什么要有它
    「一条法条没测试就不算存在」这句话，只有在**有人数**的时候才成立。
    271 之前法典 24 条里 5 条有门禁、其余 19 条是"有文可读、无据可查"——不是没人写，是**没人核**。

    271 交付后又暴露了第二种失守：**把"规范"混进"门禁"**。
    有些文件在测试里自己定义 `EmergencyCard`／撤销单 这类数据类，再断言规则——
    测的是**测试自己的模型**，生产里零命中 ⇒ 永远不会红。永不红的门禁比没有门禁更糟：
    它会让门禁账上的数字变成安慰剂。所以本脚本把两类**分开核**：

      · **门禁**（`law/Lx…GateTest.kt`）：必须碰生产——要么调生产对象，要么扫生产源码；
        造违规时它红在**生产行为**上。
      · **规范**（`law/spec/Lx…Spec.kt`）：钉"判据长什么样"，生产未接线时**不会红**；
        文件头必须有强提示，且必须写缺陷号，便于接线后销账。

用法（纯标准库；不联网、不写文件、不跑 gradle）
    python tools/check-law-gates.py                 # 两层都核＋md↔门禁同笔提交自检
    python tools/check-law-gates.py --json          # 机器可读（给 CI / 报告用）
    python tools/check-law-gates.py --dir X --spec-dir Y
    python tools/check-law-gates.py --md X.md       # 指定《法典》md（默认自动找：仓内 → 私有侧）
    python tools/check-law-gates.py --md-strict     # md 找不到也算红（本地验收用）
    python tools/check-law-gates.py --selftest      # 用内置坏样本证明本脚本会红

退出码
    0 = 门禁 19 条 / 规范 4 条，各自正负成对、前缀同号、零静默跳过，门禁都碰生产，
        且 md↔门禁 L 号集合相等（md 不可得时是**告警跳过**，不是通过——见判据 7）
    1 = 有缺口（缺条 / 缺正例 / 缺负例 / 前缀串味 / 静默跳过 / 门禁不碰生产 /
        规范缺强提示或缺缺陷号 / md↔门禁 L 号集合不等）

判据（七条，逐条可解释）
    1. **覆盖**：`GATES` 与 `SPECS` 并起来是 23 条，两表不重不漏；
    2. **正负成对**：每条至少一条 `Lx_正例…`、一条 `Lx_负例…`（两层都要）；
    3. **前缀归属**：文件名 `Lx…` 与文件内每个测试名前缀必须同号（防"L7 用例混进 L8 文件"）；
    4. **零静默**：不许 `@Ignore`／`@Disabled`／`Assume.`／`assumeTrue`／`assumeFalse`／`//ignore`；
    5. **门禁必须碰生产**（271-补 新增的硬判据）：`GateTest` 里必须出现 `baopu.` 或 `LawIo.`
       ——前者是调生产对象，后者是扫生产源码。两条都没有 ⇒ 它只可能在自己造的世界里自证，
       **不许挂在门禁账上**；
    6. **规范必须自报家门**：`Spec` 文件头必须有「本文件是规范，不是门禁」那句强提示，
       且必须写出缺陷号 `#271-Lxx`（接线后凭它销账）；
    7. **md ↔ 门禁同笔提交**（309 新增的硬判据）：《法典》md 的 L 号集合与门禁**实扫覆盖**
       （law/ 门禁文件 ∪ law/spec/ 规范文件解析出的法条 id）必须**相等**——md 说必须拦而门禁没拦
       （或反之）就是漂移，新增/删除都必须两边在同一笔提交里同步，不等即红。
       两向演练（只加 md ⇒ 红／只加门禁 ⇒ 红）在 `--selftest` 与 `lawversion/` 里各有一份。
       md 的找法：`--md` > 环境变量 `ZHENGDAO_LAW_MD` > 仓内 `*法典*.md` >
       私有侧 `../_zd-private/docs/*法典*.md`（协作规约口径）。**md 不可得 ≠ 通过**：
       本地（私有侧在）跑就是严格比对；GitHub CI 上没有私有侧，此时打 ⚠ 告警行并照常核其余判据
       ——这是"看得见的跳过"，不是静默跳过；要连"找不到 md"也红，加 `--md-strict`。

为什么是"静态检查"而不是"跑一遍测试"
    跑测试只能证明"现在绿"，证明不了"每条法条都有负例"；而形状是**跑之前**就该对的。
    两者互补：`--selftest` 用坏样本证明本脚本不是恒绿，真门禁由 `:app:testDebugUnitTest` 跑。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

try:  # Windows 控制台 cp936 下中文容易炸，统一按 utf-8 写
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

_REPO = Path(__file__).resolve().parent.parent
_LAW = _REPO / "app" / "src" / "test" / "java" / "com" / "example" / "zhengdao" / "law"
DEFAULT_DIR = _LAW
DEFAULT_SPEC_DIR = _LAW / "spec"

# 271 派工单 §1 那张表 + 法典 §待补门禁 19 条（并集；L28 随 272，不在本单）
# 分两层：GATES＝造违规会红（碰生产）；SPECS＝判据在、生产未接线（不会红）
#
# 271-补2（2026-10-29）：按"能红的就该进门禁账"把 L10/L11 拆成两半——
#   行为面（UnarchivedFailureCheck／SkillContract.validate）留在门禁账；
#   未接线的判据（catch 静默／快照）落到 spec 当补充。
# 所以这两条**同时**出现在两层：门禁表里有它，spec 目录里也有它的补充文件。
#
# 276-P2（2026-10-29）：L21/L22/L25 生产接线，判据从 spec/ 升格为门禁（用例名不变、文案逐字搬），
#   三份 spec 删除、#271-L21/L22/L25 销账；L30b 三时钟是本批新建的判据（此前台账无此项）。
#   所以 L21/L22/L25 从 SPECS 消失、进 GATES，并集 22→23（多的那一条是 L30b）。
#
# 309（2026-10-31）：法条 id 改成**带后缀的字符串**（"L30b"）——《法典》md 里 L30（灾异处置权）
#   与 L30b（超时旁路三时钟）是**两条**，门禁只覆盖后者；此前表里记 int 30、文件按 L(\d+) 解析，
#   两条被并成一条，md↔门禁的集合比对就永远对不上。字符串 id 是判据 7（md↔门禁同笔提交）的地基。
GATES = {
    "L2", "L4", "L5", "L7", "L8", "L10", "L11", "L12", "L13", "L14", "L15",
    "L17", "L18", "L20", "L21", "L22", "L23", "L25", "L30b",
}
SPECS = {"L9", "L24", "L26", "L27"}
# 允许"门禁 + 规范补充"并存的法条（拆开的那两条）；其余法条一个号只能有一个归属
SPLIT = {"L10", "L11"}

# 文件名：L<号><法条后缀><英文描述>GateTest.kt ／ L<号><法条后缀><英文描述>Spec.kt
# 法条后缀是号后**一个小写字母**（L30b 的 "b"），算进法条 id（309：md 里 L30 与 L30b 是两条）；
# 其后的 CamelCase 是描述，**不是** id——写成 [A-Za-z]* 会把 L10UnarchivedFailure 整个当成后缀
# （309 验收真踩过：id 变成 L10unarchivedfailure，整张账对不上）。
GATE_FILE_RE = re.compile(r"^L(\d+)([a-z]?)([A-Za-z]*)GateTest\.kt$")
SPEC_FILE_RE = re.compile(r"^L(\d+)([a-z]?)([A-Za-z]*)Spec\.kt$")

# 法条 id 的规范形与排序键（"L2" < "L9" < "L30" < "L30b"，按数值再按后缀）
LAW_ID_RE = re.compile(r"^L(\d+)(b?)$")


def law_key(law_id: str) -> tuple:
    m = LAW_ID_RE.match(law_id)
    if not m:
        return (10**9, law_id)  # 不合形制的 id 排到最后，别让坏值炸掉排序
    return (int(m.group(1)), m.group(2))

# 测试名：**只认 @Test 之后那一个 fun**（夹具函数不算测试，不该要求法条号前缀）
TEST_RE = re.compile(r"@Test\b[\s\S]{0,200}?fun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*\(")
PREFIX_RE = re.compile(r"^L(\d+)_")

# 门禁"碰生产"的两种证据：调生产对象 / 扫生产源码
PRODUCTION_TOUCH = re.compile(r"\bbaopu\.|\bLawIo\.|\bGateIo\.")

# 规范的自报家门（强提示原文必须逐字在）
SPEC_BANNER = "本文件是规范，不是门禁"

# 共用探针设施（不是法条文件，允许不带法条号）
INFRA_FILES = {"LawIo.kt"}

# 静默跳过的写法（一律不许）
SILENT = [
    ("@Ignore", re.compile(r"@Ignore\b")),
    ("@Disabled", re.compile(r"@Disabled\b")),
    ("Assume.", re.compile(r"\bAssume\.")),
    ("assumeTrue", re.compile(r"\bassumeTrue\s*\(")),
    ("assumeFalse", re.compile(r"\bassumeFalse\s*\(")),
    ("//ignore", re.compile(r"//\s*ignore", re.IGNORECASE)),
]

# ---------------------------------------------------------------------------
# 309 §一：md ↔ 门禁 同笔提交自检
#
# 《法典》md（私有侧 ../_zd-private/docs/抱朴-法典-*.md）与门禁覆盖必须同号同笔：
# 两边分离 ⇒ 早晚漂移（md 说必须拦，门禁没拦；或反之）。集合不等 ⇒ 红。
# 比对面是**实扫覆盖**（law/ 的 …GateTest.kt ∪ law/spec/ 的 …Spec.kt 解析出的法条 id）
# ——文件才是覆盖的实锤；表（GATES/SPECS）↔文件的一致性由判据 1 单独管，
# md ↔ 表的比对挡不住"只加文件不登记"，md ↔ 实扫才两个方向都挡得住。
#
# md 解析口径——**只认条文行，不认正文引用**：
#   ① 表行   `| **L21** | …`（五栏模板的条号列）；
#   ② 定义行 `**L30b · 超时旁路…**`（表格外御批新增的独立条，L30b 就是这种写法）；
#   正文里的 `**L9/L10**`（斜杠连写）不是条文，**不算**——斜杠断了 `\d+b?` 的边界。
CODEX_ROW_RE = re.compile(r"^\|\s*\*\*(L\d+b?)\*\*\s*\|", re.M)
# (?!/)：`**L9/L10**` 这种斜杠连写不是条文（L9 后面跟 /），不许当 L9 收进来
CODEX_DEF_RE = re.compile(r"^\*\*(L\d+b?)\b(?!/)", re.M)


def parse_codex_ids(text: str) -> set:
    """从《法典》md 原文提出条文 L 号集合（只认上面两种条文行）。"""
    return set(CODEX_ROW_RE.findall(text)) | set(CODEX_DEF_RE.findall(text))


def md_gate_sync(md_ids: set, gate_ids: set) -> list:
    """判据 7 本体：两个集合必须相等。返回漂移问题列表（空 = 相等）。"""
    problems: list[str] = []
    for x in sorted(md_ids - gate_ids, key=law_key):
        problems.append(
            f"md↔门禁漂移：{x} 在《法典》md 里有条文，门禁覆盖（law/＋law/spec/ 实扫）里没有"
            f"——补门禁或删条文，两边同笔提交"
        )
    for x in sorted(gate_ids - md_ids, key=law_key):
        problems.append(
            f"md↔门禁漂移：{x} 在门禁覆盖（law/＋law/spec/ 实扫）里挂着，《法典》md 里没有条文"
            f"——补 md 或删门禁，两边同笔提交"
        )
    return problems


def find_codex_md(explicit: str | None) -> "Path | None":
    """《法典》md 的找法（返回第一个存在的；找不到返回 None）：
    --md 显式路径 > 环境变量 ZHENGDAO_LAW_MD > 仓内（根/docs）> 私有侧 ../_zd-private/docs/。
    只认「抱朴-法典-*.md」这个名——私有侧还有交付/派工单等文件名里带"法典"的
    （交付-…-法典门禁补齐.md），按 `*法典*` 找会把它们误当法典（309 验收时真踩过）。"""
    pattern = "抱朴-法典*.md"
    cands: list[Path] = []
    if explicit:
        cands.append(Path(explicit))
    env = os.environ.get("ZHENGDAO_LAW_MD")
    if env:
        cands.append(Path(env))
    cands += sorted(_REPO.glob(pattern))
    docs = _REPO / "docs"
    if docs.is_dir():
        cands += sorted(docs.glob(pattern))
    private_docs = _REPO.parent / "_zd-private" / "docs"
    if private_docs.is_dir():
        cands += sorted(private_docs.glob(pattern))
    for c in cands:
        if c.is_file():
            return c
    return None


def strip_comments(text: str) -> str:
    """去掉块注释与行注释（保留行号；字符串里的 // 不当注释——用例里有 URL）。"""
    out: list[str] = []
    for line in text.splitlines():
        buf = []
        i = 0
        in_str = False
        in_raw = False
        while i < len(line):
            if in_raw:
                if line.startswith('"""', i):
                    in_raw = False
                    buf.append('"""')
                    i += 3
                else:
                    buf.append(line[i])
                    i += 1
            elif in_str:
                c = line[i]
                if c == "\\" and i + 1 < len(line):
                    buf.append(line[i : i + 2])
                    i += 2
                else:
                    if c == '"':
                        in_str = False
                    buf.append(c)
                    i += 1
            elif line.startswith('"""', i):
                in_raw = True
                buf.append('"""')
                i += 3
            elif line[i] == '"':
                in_str = True
                buf.append('"')
                i += 1
            elif line.startswith("//", i):
                break
            else:
                buf.append(line[i])
                i += 1
        out.append("".join(buf))
    return "\n".join(out)


def scan(dir_path: Path, kind: str) -> dict:
    """扫一层目录。kind = 'gate' | 'spec'。"""
    file_re = GATE_FILE_RE if kind == "gate" else SPEC_FILE_RE
    laws: dict[int, dict] = {}
    problems: list[str] = []
    files = sorted(dir_path.glob("*.kt")) if dir_path.is_dir() else []
    if not files:
        problems.append(f"[{kind}] 目录里没有 .kt 文件：{dir_path}（路径写错？）")

    for path in files:
        m = file_re.match(path.name)
        if not m:
            if path.name in INFRA_FILES:
                continue
            problems.append(f"[{kind}] {path.name}: 文件名不合形制（门禁 L7…GateTest.kt／规范 L21…Spec.kt）")
            continue
        law_no = int(m.group(1))
        suffix = m.group(2)
        law_id = f"L{law_no}{suffix}"  # "L30b" 这类带后缀的法条与 "L7" 同账（309）；描述不算 id
        raw = path.read_text(encoding="utf-8")
        body = strip_comments(raw)

        # 判据 4：不许静默跳过（注释已去掉查真代码；`//ignore` 查原文）
        for label, rx in SILENT:
            hay = raw if label == "//ignore" else body
            if rx.search(hay):
                problems.append(f"[{kind}] {path.name}: 出现 {label}（271 §2：不许静默跳过）")

        # 判据 5 / 6：两层各自的身份
        if kind == "gate":
            if SPEC_BANNER in raw:
                problems.append(
                    f"[gate] {path.name}: 自称「规范」却挂在门禁目录——两层不许混（这就是 271-补 要治的病）"
                )
            if not PRODUCTION_TOUCH.search(body):
                problems.append(
                    f"[gate] {path.name}: 没有一处碰生产（既无 `baopu.` 也无 `LawIo.`）"
                    f"——它只可能在自己造的世界里自证，不许当门禁；请移入 spec/ 并改名 …Spec"
                )
        else:
            if SPEC_BANNER not in raw:
                problems.append(
                    f"[spec] {path.name}: 文件头缺强提示「{SPEC_BANNER}；生产未接线（#271-Lxx），它不会红」"
                )
            if f"#271-L{law_no}" not in raw:
                problems.append(f"[spec] {path.name}: 没写缺陷号 #271-L{law_no}（接线后凭它销账）")

        slot = laws.setdefault(law_id, {"file": path.name, "pos": [], "neg": [], "other": []})
        for m2 in TEST_RE.finditer(body):
            name = m2.group(1) or m2.group(2) or ""
            pm = PREFIX_RE.match(name)
            if not pm:
                problems.append(f"[{kind}] {path.name}: 测试名「{name}」不带法条号前缀（应 L{law_no}_…）")
                continue
            if int(pm.group(1)) != law_no:
                problems.append(
                    f"[{kind}] {path.name}: 测试名「{name}」的法条号与文件名 L{law_no} 不一致（混装会让报告对不上号）"
                )
                continue
            if "正例" in name:
                slot["pos"].append(name)
            elif "负例" in name:
                slot["neg"].append(name)
            else:
                slot["other"].append(name)
    return {"laws": laws, "problems": problems, "files": len(files)}


def judge(gate: dict, spec: dict) -> list[str]:
    """按六条判据出结论（返回问题列表；空 = 通过）。"""
    problems = list(gate["problems"]) + list(spec["problems"])

    # 判据 1：两表不重不漏
    dup = set(GATES) & set(SPECS)
    if dup:
        problems.append(f"清单自相矛盾：{sorted(dup, key=law_key)} 同时在门禁与规范两表里")
    for n in sorted(GATES, key=law_key):
        if n not in gate["laws"]:
            problems.append(f"{n}: 门禁清单里有，但 law/ 下没有对应的 …GateTest.kt")
        if n in spec["laws"] and n not in SPLIT:
            problems.append(f"{n}: 同时在门禁与规范目录里（只有拆开的 {sorted(SPLIT, key=law_key)} 允许这样）")
    for n in sorted(SPECS, key=law_key):
        if n not in spec["laws"]:
            problems.append(f"{n}: 规范清单里有，但 spec/ 下没有对应的 …Spec.kt")
        if n in gate["laws"]:
            problems.append(f"{n}: 同时在门禁与规范目录里（一个法条只能有一个归属）")

    # 判据 2：正负成对（两层都查）
    for label, layer in (("门禁", gate["laws"]), ("规范", spec["laws"])):
        for n in sorted(layer, key=law_key):
            slot = layer[n]
            if not slot["pos"]:
                problems.append(f"{label} {n}（{slot['file']}）: 缺正例（至少要一条 {n}_正例…）")
            if not slot["neg"]:
                problems.append(f"{label} {n}（{slot['file']}）: 缺负例（至少要一条 {n}_负例…）")

    # 多余条目（既不在门禁表也不在规范表）
    for label, layer in (("门禁", gate["laws"]), ("规范", spec["laws"])):
        for n in sorted(layer, key=law_key):
            if n not in GATES and n not in SPECS:
                problems.append(f"{label} {n}（{layer[n]['file']}）: 不在本批清单里——要么登记，要么删掉")
    return problems


def report(gate: dict, spec: dict, problems: list[str]) -> None:
    print("==== 法典门禁 / 规范 形状核对 ====")
    print(f"【可执行门禁】{len(gate['laws'])} 条（{gate['files']} 个文件）——造违规会红")
    print(f"{'条':<6}{'正例':<6}{'负例':<6}文件")
    for n in sorted(gate["laws"], key=law_key):
        s = gate["laws"][n]
        print(f"{n:<6}{len(s['pos']):<6}{len(s['neg']):<6}{s['file']}")
    print()
    pure = len(set(spec["laws"]) - SPLIT)
    print(
        f"【规范目录】{spec['files']} 个文件 = 纯规范 {pure} 条 ＋ 拆分补充 {len(set(spec['laws']) & SPLIT)} 条"
        f"——生产未接线，不会红"
    )
    print(f"{'条':<6}{'正例':<6}{'负例':<6}文件")
    for n in sorted(spec["laws"], key=law_key):
        s = spec["laws"][n]
        print(f"{n:<6}{len(s['pos']):<6}{len(s['neg']):<6}{s['file']}")
    split_specs = sorted(set(spec["laws"]) & SPLIT, key=law_key)
    if split_specs:
        print(
            "拆分补充（这些号的门禁在 law/，未接线的判据在 spec/）："
            + "、".join(f"L{n}→{spec['laws'][n]['file']}" for n in split_specs)
        )
        print()
    if problems:
        print(f"✗ {len(problems)} 处不合规：")
        for p in problems:
            print("   - " + p)
    else:
        gp = sum(len(v["pos"]) for v in gate["laws"].values())
        gn = sum(len(v["neg"]) for v in gate["laws"].values())
        sp = sum(len(v["pos"]) for v in spec["laws"].values())
        sn = sum(len(v["neg"]) for v in spec["laws"].values())
        print(
            f"✓ 全部合规：门禁 {len(GATES)} 条（正 {gp} 负 {gn}，都碰生产）＋ "
            f"规范 {len(SPECS)} 条（正 {sp} 负 {sn}，都带强提示与缺陷号）"
            f"＋ 拆分补充 {len(set(spec['laws']) & SPLIT)} 条，无静默跳过"
        )


def selftest() -> int:
    """用内置坏样本证明本脚本会红（防"恒绿的检查器"）。"""
    import tempfile

    good_gate = """
    package com.example.zhengdao.law
    import com.example.zhengdao.baopu.RiskMatrix
    class L7XGateTest {
        @Test
        fun `L7_正例_放行`() { RiskMatrix.classify(null) }
        @Test
        fun `L7_负例_拦截`() { }
    }
    """
    good_spec = """
    package com.example.zhengdao.law.spec
    // 本文件是规范，不是门禁；生产未接线（#271-L7），它不会红
    class L7XSpec {
        @Test
        fun `L7_正例_判据`() { }
        @Test
        fun `L7_负例_判据`() { }
    }
    """
    bad_no_neg = good_gate.replace('fun `L7_负例_拦截`() { }', 'fun `L7_别的`() { }')
    bad_prefix = good_gate.replace('fun `L7_正例_放行`', 'fun `L8_正例_放行`')
    bad_silent = good_gate.replace("@Test\n        fun `L7_正例", "@Test @Ignore\n        fun `L7_正例")
    bad_no_production = """
    package com.example.zhengdao.law
    class L7XGateTest {
        @Test
        fun `L7_正例_自己造世界`() { }
        @Test
        fun `L7_负例_自己造世界`() { }
    }
    """
    bad_spec_no_banner = good_spec.replace(
        "// 本文件是规范，不是门禁；生产未接线（#271-L7），它不会红",
        "// 生产未接线（#271-L7），它不会红",
    )

    def run(text: str, name: str, kind: str) -> list[str]:
        """单文件跑一遍：scan 的形制问题 ＋ 正负成对（与 judge 同口径）。"""
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / name).write_text(text, encoding="utf-8")
            res = scan(d, kind)
            problems = list(res["problems"])
            for n, slot in res["laws"].items():
                if not slot["pos"]:
                    problems.append(f"缺正例 L{n}")
                if not slot["neg"]:
                    problems.append(f"缺负例 L{n}")
            return problems

    cases = [
        ("门禁：合规样本自身零问题", run(good_gate, "L7XGateTest.kt", "gate"), 0),
        ("规范：合规样本自身零问题", run(good_spec, "L7XSpec.kt", "spec"), 0),
        ("门禁：缺负例必须报", run(bad_no_neg, "L7XGateTest.kt", "gate"), 1),
        ("门禁：法条号串味必须报", run(bad_prefix, "L7XGateTest.kt", "gate"), 1),
        ("门禁：@Ignore 必须报", run(bad_silent, "L7XGateTest.kt", "gate"), 1),
        ("门禁：不碰生产必须报（本批新增判据）", run(bad_no_production, "L7XGateTest.kt", "gate"), 1),
        ("规范：缺强提示必须报", run(bad_spec_no_banner, "L7XSpec.kt", "spec"), 1),
    ]
    # 309 §一：md↔门禁 同笔提交的两向演练（判据 7）——只加 md ⇒ 红；只加门禁 ⇒ 红
    md_base = {"L2", "L4"}
    gate_base = {"L2", "L4"}
    cases += [
        ("md↔门禁：两边同号零问题", md_gate_sync(md_base, gate_base), 0),
        ("md↔门禁：只在 md 加一条必须报（方向一演练）",
         [p for p in md_gate_sync(md_base | {"L32"}, gate_base) if "L32" in p], 1),
        ("md↔门禁：只在门禁加一条必须报（方向二演练）",
         [p for p in md_gate_sync(md_base, gate_base | {"L33"}) if "L33" in p], 1),
    ]
    failures = 0
    for label, got, expect in cases:
        # expect=0 ⇒ 必须一条问题都没有；expect>0 ⇒ 至少报出这么多（一条坏样本可能同时踩两条判据）
        ok = (len(got) == 0) if expect == 0 else (len(got) >= expect)
        print(("✓ " if ok else "✗ ") + label + ("" if ok else f"：实得 {got}"))
        failures += 0 if ok else 1
    print("自测通过" if failures == 0 else f"自测失败 {failures} 项")
    return 0 if failures == 0 else 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="法典门禁 / 规范 形状核对（含 md↔门禁同笔提交自检）")
    ap.add_argument("--dir", default=str(DEFAULT_DIR), help="门禁测试目录（默认 law/）")
    ap.add_argument("--spec-dir", default=str(DEFAULT_SPEC_DIR), help="规范目录（默认 law/spec/）")
    ap.add_argument("--md", default=None,
                    help="《法典》md 路径（默认自动找：--md > ZHENGDAO_LAW_MD > 仓内 > 私有侧 ../_zd-private/docs/）")
    ap.add_argument("--md-strict", action="store_true",
                    help="《法典》md 找不到也算红（本地验收用；GitHub CI 无私有侧，默认只打 ⚠ 告警）")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    ap.add_argument("--selftest", action="store_true", help="用坏样本自证会红")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    gate = scan(Path(args.dir), "gate")
    spec = scan(Path(args.spec_dir), "spec")
    problems = judge(gate, spec)

    # 判据 7：md↔门禁 L 号集合必须相等。md 不可得是「看得见的跳过」（打 ⚠ 行、JSON 落状态），
    # 不是通过——但也不替 CI 假红：GitHub runner 上没有私有侧的《法典》md，找不到就明说没跑。
    md_path = find_codex_md(args.md)
    gate_ids = set(gate["laws"]) | set(spec["laws"])  # 实扫覆盖：文件是覆盖的实锤（判据 7 比对面）
    md_sync: dict = {"status": "md_not_found", "md_path": None, "md_ids": 0,
                     "md_only": [], "gate_only": []}
    if md_path is None:
        note = ("md↔门禁同笔提交自检未跑：《法典》md 没找到"
                "（--md 或环境变量 ZHENGDAO_LAW_MD 可指路）——这不是通过，是没跑")
        if not args.json:  # --json 的 stdout 只许有一个 JSON 对象，⚠ 只给人读的通道
            print(f"⚠ {note}")
        if args.md_strict:
            problems.append("md↔门禁漂移无法核验：" + note)
    else:
        try:
            md_ids = parse_codex_ids(md_path.read_text(encoding="utf-8"))
        except OSError as e:
            # 找到了却读不出：本地一律按红（fail-closed），不许当作"md 已核过"
            problems.append(f"md↔门禁漂移无法核验：md 存在但读不出（{md_path}：{e}）")
        else:
            sync_problems = md_gate_sync(md_ids, gate_ids)
            problems.extend(sync_problems)
            md_sync = {
                "status": "drift" if sync_problems else "ok",
                "md_path": str(md_path),
                "md_ids": len(md_ids),
                "md_only": sorted(md_ids - gate_ids, key=law_key),
                "gate_only": sorted(gate_ids - md_ids, key=law_key),
            }

    if args.json:
        print(
            json.dumps(
                {
                    "ok": not problems,
                    "gates": {
                        k: {"file": v["file"], "pos": len(v["pos"]), "neg": len(v["neg"])}
                        for k, v in gate["laws"].items()
                    },
                    "specs": {
                        k: {"file": v["file"], "pos": len(v["pos"]), "neg": len(v["neg"])}
                        for k, v in spec["laws"].items()
                    },
                    "md_sync": md_sync,
                    "problems": problems,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        report(gate, spec, problems)
        if md_sync["status"] in ("ok", "drift"):
            print()
            print(
                f"md↔门禁同笔提交自检（判据 7）：md {md_sync['md_ids']} 条 ↔ 门禁覆盖 {len(gate_ids)} 条；"
                f"md 独有 {md_sync['md_only'] or '无'}；门禁独有 {md_sync['gate_only'] or '无'}"
            )
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
