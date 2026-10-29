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
    python tools/check-law-gates.py                 # 两层都核
    python tools/check-law-gates.py --json          # 机器可读（给 CI / 报告用）
    python tools/check-law-gates.py --dir X --spec-dir Y
    python tools/check-law-gates.py --selftest      # 用内置坏样本证明本脚本会红

退出码
    0 = 门禁 13 条 / 规范 9 条，各自正负成对、前缀同号、零静默跳过，且门禁都碰生产
    1 = 有缺口（缺条 / 缺正例 / 缺负例 / 前缀串味 / 静默跳过 / 门禁不碰生产 / 规范缺强提示或缺缺陷号）

判据（六条，逐条可解释）
    1. **覆盖**：`GATES` 与 `SPECS` 并起来是 22 条，两表不重不漏；
    2. **正负成对**：每条至少一条 `Lx_正例…`、一条 `Lx_负例…`（两层都要）；
    3. **前缀归属**：文件名 `Lx…` 与文件内每个测试名前缀必须同号（防"L7 用例混进 L8 文件"）；
    4. **零静默**：不许 `@Ignore`／`@Disabled`／`Assume.`／`assumeTrue`／`assumeFalse`／`//ignore`；
    5. **门禁必须碰生产**（271-补 新增的硬判据）：`GateTest` 里必须出现 `baopu.` 或 `LawIo.`
       ——前者是调生产对象，后者是扫生产源码。两条都没有 ⇒ 它只可能在自己造的世界里自证，
       **不许挂在门禁账上**；
    6. **规范必须自报家门**：`Spec` 文件头必须有「本文件是规范，不是门禁」那句强提示，
       且必须写出缺陷号 `#271-Lxx`（接线后凭它销账）。

为什么是"静态检查"而不是"跑一遍测试"
    跑测试只能证明"现在绿"，证明不了"每条法条都有负例"；而形状是**跑之前**就该对的。
    两者互补：`--selftest` 用坏样本证明本脚本不是恒绿，真门禁由 `:app:testDebugUnitTest` 跑。
"""

from __future__ import annotations

import argparse
import json
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
GATES = [2, 4, 5, 7, 8, 10, 11, 12, 13, 14, 15, 17, 18, 20, 23]
SPECS = [9, 21, 22, 24, 25, 26, 27]
# 允许"门禁 + 规范补充"并存的法条（拆开的那两条）；其余法条一个号只能有一个归属
SPLIT = {10, 11}

# 文件名：L<号><英文描述>GateTest.kt ／ L<号><英文描述>Spec.kt
GATE_FILE_RE = re.compile(r"^L(\d+)[A-Za-z]*GateTest\.kt$")
SPEC_FILE_RE = re.compile(r"^L(\d+)[A-Za-z]*Spec\.kt$")

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

        slot = laws.setdefault(law_no, {"file": path.name, "pos": [], "neg": [], "other": []})
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
        problems.append(f"清单自相矛盾：{sorted(dup)} 同时在门禁与规范两表里")
    for n in GATES:
        if n not in gate["laws"]:
            problems.append(f"L{n}: 门禁清单里有，但 law/ 下没有对应的 …GateTest.kt")
        if n in spec["laws"] and n not in SPLIT:
            problems.append(f"L{n}: 同时在门禁与规范目录里（只有拆开的 {sorted(SPLIT)} 允许这样）")
    for n in SPECS:
        if n not in spec["laws"]:
            problems.append(f"L{n}: 规范清单里有，但 spec/ 下没有对应的 …Spec.kt")
        if n in gate["laws"]:
            problems.append(f"L{n}: 同时在门禁与规范目录里（一个法条只能有一个归属）")

    # 判据 2：正负成对（两层都查）
    for label, layer in (("门禁", gate["laws"]), ("规范", spec["laws"])):
        for n in sorted(layer):
            slot = layer[n]
            if not slot["pos"]:
                problems.append(f"{label} L{n}（{slot['file']}）: 缺正例（至少要一条 L{n}_正例…）")
            if not slot["neg"]:
                problems.append(f"{label} L{n}（{slot['file']}）: 缺负例（至少要一条 L{n}_负例…）")

    # 多余条目（既不在门禁表也不在规范表）
    for label, layer in (("门禁", gate["laws"]), ("规范", spec["laws"])):
        for n in sorted(layer):
            if n not in GATES and n not in SPECS:
                problems.append(f"{label} L{n}（{layer[n]['file']}）: 不在本批清单里——要么登记，要么删掉")
    return problems


def report(gate: dict, spec: dict, problems: list[str]) -> None:
    print("==== 法典门禁 / 规范 形状核对 ====")
    print(f"【可执行门禁】{len(gate['laws'])} 条（{gate['files']} 个文件）——造违规会红")
    print(f"{'条':<6}{'正例':<6}{'负例':<6}文件")
    for n in sorted(gate["laws"]):
        s = gate["laws"][n]
        print(f"L{n:<5}{len(s['pos']):<6}{len(s['neg']):<6}{s['file']}")
    print()
    pure = len(set(spec["laws"]) - SPLIT)
    print(
        f"【规范目录】{spec['files']} 个文件 = 纯规范 {pure} 条 ＋ 拆分补充 {len(set(spec['laws']) & SPLIT)} 条"
        f"——生产未接线，不会红"
    )
    print(f"{'条':<6}{'正例':<6}{'负例':<6}文件")
    for n in sorted(spec["laws"]):
        s = spec["laws"][n]
        print(f"L{n:<5}{len(s['pos']):<6}{len(s['neg']):<6}{s['file']}")
    split_specs = sorted(set(spec["laws"]) & SPLIT)
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
    failures = 0
    for label, got, expect in cases:
        # expect=0 ⇒ 必须一条问题都没有；expect>0 ⇒ 至少报出这么多（一条坏样本可能同时踩两条判据）
        ok = (len(got) == 0) if expect == 0 else (len(got) >= expect)
        print(("✓ " if ok else "✗ ") + label + ("" if ok else f"：实得 {got}"))
        failures += 0 if ok else 1
    print("自测通过" if failures == 0 else f"自测失败 {failures} 项")
    return 0 if failures == 0 else 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="法典门禁 / 规范 形状核对")
    ap.add_argument("--dir", default=str(DEFAULT_DIR), help="门禁测试目录（默认 law/）")
    ap.add_argument("--spec-dir", default=str(DEFAULT_SPEC_DIR), help="规范目录（默认 law/spec/）")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    ap.add_argument("--selftest", action="store_true", help="用坏样本自证会红")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    gate = scan(Path(args.dir), "gate")
    spec = scan(Path(args.spec_dir), "spec")
    problems = judge(gate, spec)
    if args.json:
        print(
            json.dumps(
                {
                    "ok": not problems,
                    "gates": {
                        str(k): {"file": v["file"], "pos": len(v["pos"]), "neg": len(v["neg"])}
                        for k, v in gate["laws"].items()
                    },
                    "specs": {
                        str(k): {"file": v["file"], "pos": len(v["pos"]), "neg": len(v["neg"])}
                        for k, v in spec["laws"].items()
                    },
                    "problems": problems,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        report(gate, spec, problems)
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
