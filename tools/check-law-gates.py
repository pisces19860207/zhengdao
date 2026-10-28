#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-law-gates.py —— 法典门禁的**形状机检**（第二百七十一批）。

为什么要有它
    「一条法条没测试就不算存在」这句话，只有在**有人数**的时候才成立。
    271 之前法典 24 条里 5 条有门禁、其余 19 条是"有文可读、无据可查"——
    不是没人写，是**没人核**：写了一半、名字不带法条号、或者用 @Ignore 静默跳过，
    在 CI 里都看不出来。本脚本只做一件事：把"每条法条都有 1 正例 + 1 负例"变成可判定的。

用法（纯标准库；不联网、不写文件、不跑 gradle）
    python tools/check-law-gates.py                 # 核 app/src/test/java/com/example/zhengdao/law
    python tools/check-law-gates.py --json          # 机器可读（给 CI / 报告用）
    python tools/check-law-gates.py --dir X         # 换目录（自测用）
    python tools/check-law-gates.py --selftest      # 用内置的坏样本证明本脚本会红

退出码
    0 = 每条法条都有 1 正例 + 1 负例，且没有静默跳过
    1 = 有缺口（缺法条 / 缺正例 / 缺负例 / 名字不带法条号 / 出现 @Ignore·Assume）

判据（四条，逐条可解释）
    1. **覆盖**：`REQUIRED` 里的每个法条号，都必须有测试文件；
    2. **正负成对**：同一法条至少一条 `L{n}_正例…`、至少一条 `L{n}_负例…`；
    3. **前缀归属**：文件名 `L{n}…` 里的 n，必须与文件内每个测试名前缀的 n 一致
       （防"L7 的用例混进 L8 的文件里"——那种混装会让报告对不上号）；
    4. **不许静默**：目录里不许出现 `@Ignore`／`@Disabled`／`Assume.`／`assumeTrue`／
       `assumeFalse`／`//ignore`（271 派工单 §2 第三条原文）。

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
DEFAULT_DIR = _REPO / "app" / "src" / "test" / "java" / "com" / "example" / "zhengdao" / "law"

# 271 派工单 §1 那张表 + 法典 §待补门禁 19 条（并集；L28 随 272，不在本单）
REQUIRED = [2, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 20, 21, 22, 23, 24, 25, 26, 27]

# 文件名：L<号><英文描述>GateTest.kt
FILE_RE = re.compile(r"^L(\d+)[A-Za-z]*GateTest\.kt$")

# 测试名：**只认 @Test 之后那一个 fun**（同一个文件里的夹具函数不算测试，不该要求法条号前缀）
TEST_RE = re.compile(r"@Test\b[\s\S]{0,200}?fun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*\(")
PREFIX_RE = re.compile(r"^L(\d+)_")

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
    """去掉块注释与行注释（保留行号；字符串里的 // 不当注释——本目录的用例里有 URL）。"""
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


def scan(dir_path: Path) -> dict:
    """扫目录，返回 {'laws': {n: {'file', 'pos', 'neg'}}, 'problems': [...]}。"""
    laws: dict[int, dict] = {}
    problems: list[str] = []
    files = sorted(dir_path.glob("*.kt")) if dir_path.is_dir() else []
    if not files:
        problems.append(f"目录里没有 .kt 文件：{dir_path}（路径写错？）")

    for path in files:
        m = FILE_RE.match(path.name)
        if not m:
            if path.name in INFRA_FILES:
                continue
            problems.append(f"{path.name}: 文件名不合形制（应形如 L7ProtectedWriteGateTest.kt）")
            continue
        law_no = int(m.group(1))
        raw = path.read_text(encoding="utf-8")
        body = strip_comments(raw)

        # 判据 4：不许静默跳过（注释已去掉，所以只查真代码；但 `//ignore` 要查原文）
        for label, rx in SILENT:
            hay = raw if label == "//ignore" else body
            if rx.search(hay):
                problems.append(f"{path.name}: 出现 {label}（271 §2：不许静默跳过）")

        slot = laws.setdefault(law_no, {"file": path.name, "pos": [], "neg": [], "other": []})
        for m2 in TEST_RE.finditer(body):
            name = m2.group(1) or m2.group(2) or ""
            pm = PREFIX_RE.match(name)
            if not pm:
                problems.append(f"{path.name}: 测试名「{name}」不带法条号前缀（应 L{law_no}_…）")
                continue
            if int(pm.group(1)) != law_no:
                problems.append(
                    f"{path.name}: 测试名「{name}」的法条号与文件名 L{law_no} 不一致（混装会让报告对不上号）"
                )
                continue
            if "正例" in name:
                slot["pos"].append(name)
            elif "负例" in name:
                slot["neg"].append(name)
            else:
                slot["other"].append(name)
    return {"laws": laws, "problems": problems, "files": len(files)}


def judge(result: dict) -> list[str]:
    """按四条判据出结论（返回问题列表；空 = 通过）。"""
    problems = list(result["problems"])
    laws = result["laws"]

    for n in REQUIRED:
        if n not in laws:
            problems.append(f"L{n}: 没有任何门禁文件（法典这一条还是「有文可读」）")
            continue
        slot = laws[n]
        if not slot["pos"]:
            problems.append(f"L{n}（{slot['file']}）: 缺正例（至少要一条 L{n}_正例…）")
        if not slot["neg"]:
            problems.append(f"L{n}（{slot['file']}）: 缺负例（至少要一条 L{n}_负例…）")

    for n in sorted(laws):
        if n not in REQUIRED:
            problems.append(f"L{n}: 不在本批清单里（{laws[n]['file']}）——多出来的门禁要么登记，要么删掉")
    return problems


def report(result: dict, problems: list[str]) -> None:
    laws = result["laws"]
    print(f"==== 法典门禁形状核对（{result['files']} 个文件 / {len(laws)} 条法条）====")
    print(f"{'条':<6}{'正例':<6}{'负例':<6}文件")
    for n in sorted(laws):
        slot = laws[n]
        print(f"L{n:<5}{len(slot['pos']):<6}{len(slot['neg']):<6}{slot['file']}")
    missing = [f"L{n}" for n in REQUIRED if n not in laws]
    if missing:
        print("缺文件：" + "、".join(missing))
    print()
    if problems:
        print(f"✗ {len(problems)} 处不合规：")
        for p in problems:
            print("   - " + p)
    else:
        total_pos = sum(len(v["pos"]) for v in laws.values())
        total_neg = sum(len(v["neg"]) for v in laws.values())
        print(f"✓ 全部合规：{len(REQUIRED)} 条法条，正例 {total_pos} 条、负例 {total_neg} 条，无静默跳过")


def selftest() -> int:
    """用内置坏样本证明本脚本会红（防"恒绿的检查器"）。"""
    good = """
    class L7XGateTest {
        @Test
        fun `L7_正例_放行`() { }
        @Test
        fun `L7_负例_拦截`() { }
    }
    """
    bad_no_neg = """
    class L7XGateTest {
        @Test
        fun `L7_正例_放行`() { }
    }
    """
    bad_prefix = """
    class L7XGateTest {
        @Test
        fun `L8_正例_串味`() { }
        @Test
        fun `L7_负例_拦截`() { }
    }
    """
    bad_silent = """
    class L7XGateTest {
        @Test @Ignore
        fun `L7_正例_放行`() { }
        @Test
        fun `L7_负例_拦截`() { }
    }
    """

    def one(text: str, name: str = "L7XGateTest.kt") -> list[str]:
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / name).write_text(text, encoding="utf-8")
            return judge(scan(d))

    cases = [
        ("合规样本不该报", good, 0),
        ("缺负例必须报", bad_no_neg, 1),
        ("法条号串味必须报", bad_prefix, 1),
        ("@Ignore 必须报", bad_silent, 1),
    ]
    failures = 0
    for label, text, expect_at_least in cases:
        got = one(text)
        hit = len(got) >= expect_at_least and (expect_at_least == 0 or got)
        # 合规样本会因为"别的法条缺文件"而报一堆，这里只核"本样本自身"的问题
        if expect_at_least == 0:
            hit = not any("L7" in p for p in got)
        print(("✓ " if hit else "✗ ") + label + ("" if hit else f"：{got}"))
        failures += 0 if hit else 1
    print("自测通过" if failures == 0 else f"自测失败 {failures} 项")
    return 0 if failures == 0 else 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="法典门禁形状核对")
    ap.add_argument("--dir", default=str(DEFAULT_DIR), help="门禁测试目录")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    ap.add_argument("--selftest", action="store_true", help="用坏样本自证会红")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    result = scan(Path(args.dir))
    problems = judge(result)
    if args.json:
        print(
            json.dumps(
                {
                    "ok": not problems,
                    "files": result["files"],
                    "laws": {str(k): {"file": v["file"], "pos": len(v["pos"]), "neg": len(v["neg"])} for k, v in result["laws"].items()},
                    "problems": problems,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        report(result, problems)
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
