#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-radius-tokens.py —— 圆角令牌门禁：拦「ui/ 下新增裸写圆角」，放行令牌、胶囊与白名单。

为什么要有它
    `ui/theme/Radius.kt` 2026-10-11 就定义了 8 档圆角令牌（rSwatch 4 / rBadge 6 / rChip 8 /
    rPill 10 / rButton 12 / rDialog 14 / rCard 16 / rSheet 24），注释写「迁移不急、改动时顺手换」。
    实测到 2026-10-20：那 8 个令牌**全仓零引用**，而 `ui/` 下裸写 `RoundedCornerShape(...)` 有 43 处。
    第七十批口径（评审 7 号）：**先立门禁防新增，再按对账表逐个收编**。本脚本只做判定与打印。

用法（纯标准库；不联网、不写文件）
    python tools/check-radius-tokens.py                       # 扫 ui/ 全量（默认）
    python tools/check-radius-tokens.py --paths a.kt,b.kt      # 只查给定文件/目录（自测、单文件核对）
    python tools/check-radius-tokens.py --whitelist tools/radius-whitelist.txt   # 换白名单（默认即此）
    python tools/check-radius-tokens.py --json                  # 机器读结果（CI 汇总用）

判定（只看 `RoundedCornerShape(<数字>…)` 这种裸写字面量）
    数字 ∈ 8 档 {4,6,8,10,12,14,16,24}      ⇒ 该用令牌 ⇒ **报红**（除非白名单里这条「路径|值」在案）
    数字 ≥ 50（`50` 百分比式 / `999.dp` 等）  ⇒ 胶囊 ⇒ **自动放行**，不必进白名单
    其他数字（2/18/20/22/28…）               ⇒ 自定义 ⇒ **报红**，必须进白名单并写理由
    `RoundedCornerShape(rCard)`、`ShapeCard`、常量参数 ⇒ 参数字面量不是数字 ⇒ 放行

白名单格式（tools/radius-whitelist.txt；`#` 开头为注释，空行忽略）
    路径 | 值 | 理由
    · 路径＝仓内相对路径（反斜杠自动折成斜杠），可写目录前缀（结尾带 /，按 / 分段匹配，ui/a 不误判 ui/ab）；
    · 值＝数字，`*` ＝该路径下任意值；
    · 理由请写清「为什么不是 8 档之一」或「待哪一批收编」——白名单就是这笔债的台账。

退出码
    0 = 没有报红项；1 = 有报红项（逐条打印 文件:行 | 值 | 判定）；2 = 用法错误（路径不存在等）。

已知局限（对策照 `BrowserBottomBarGuardTest` 的同款教训）
    · 判前先剥注释：整行 `//` 与 `/* */` 块注释剥掉（行数保持不变，行号仍对得上源码）。
      行尾跟随代码的 `//` 注释不剥——KDoc/行注释里写反例词是最常见的自杀方式，已按整行处理。
    · 只认 `RoundedCornerShape(...)`；`CircleShape` / `CutCornerShape` / 自定义 Shape 不在射程内。
    · 常量参数（如 `RoundedCornerShape(ATTACH_CHIP_RADIUS_DP)`）取不到值 ⇒ 放行＝**已知盲区**，
      对账表里逐条点名，收编常量归下一批。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:  # 与 tools/zd.py 同款：Windows 控制台 cp936 下中文容易炸，统一按 utf-8 写
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

REPO = Path(__file__).resolve().parent.parent
DEFAULT_ROOT = REPO / "app" / "src" / "main" / "java" / "com" / "example" / "zhengdao" / "ui"
DEFAULT_WHITELIST = REPO / "tools" / "radius-whitelist.txt"

TOKEN_VALUES = (4, 6, 8, 10, 12, 14, 16, 24)   # Radius.kt 的 8 档
CAPSULE_AT = 50                                 # ≥50 ⇒ 胶囊（含 `RoundedCornerShape(50)` 百分比写法）
CALL_RE = re.compile(r"RoundedCornerShape\s*\(")
DP_RE = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\.dp\b")
PERCENT_RE = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*$")

BLOCK_RE = re.compile(r"/\*.*?\*/", re.S)
FULL_LINE_COMMENT_RE = re.compile(r"(?m)^([ \t]*)//[^\n]*$")


# ---------------------------------------------------------------- 注释剥离


def strip_comments(text: str) -> str:
    """剥块注释与整行单行注释，**行数不变**（报出来的行号必须还能对上源码）。"""
    def _blank_block(m: re.Match) -> str:
        return "\n" * m.group(0).count("\n")

    out = BLOCK_RE.sub(_blank_block, text)
    out = FULL_LINE_COMMENT_RE.sub(lambda m: m.group(1), out)
    return out


# ---------------------------------------------------------------- 取参数文本


def arg_span(text: str, open_paren: int) -> tuple[str, int]:
    """从 `(` 下标起取到配平的 `)`，返回参数子串与结束下标。字符串里的括号不管（本仓无此写法）。"""
    depth = 0
    i = open_paren
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:i], i
        i += 1
    return text[open_paren + 1:], len(text)


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


# ---------------------------------------------------------------- 白名单


def norm_path(p: str) -> str:
    q = str(p).strip().strip("`'\"").replace("\\", "/")
    q = re.sub(r"/{2,}", "/", q)
    if q.startswith("./"):
        q = q[2:]
    return q


def segs(p: str) -> list[str]:
    return [s for s in norm_path(p).split("/") if s]


class Entry:
    __slots__ = ("path", "value", "reason", "raw", "used")

    def __init__(self, path: str, value: str, reason: str, raw: str):
        self.path = norm_path(path)
        self.value = None if value.strip() == "*" else _num(value)
        self.reason = reason.strip()
        self.raw = raw
        self.used = False

    def matches(self, file_rel: str, value: float | None) -> bool:
        if self.value is not None and (value is None or abs(self.value - value) > 1e-6):
            return False
        f, e = segs(file_rel), segs(self.path)
        if self.path.endswith("/"):
            # 目录条目：这条目录段必须**连续出现**在文件的目录段里。
            # 连续段而不是「从头比」＝同一张白名单既能写仓内相对路径，也能盖住自测用的绝对路径；
            # 按 / 分段而不是字符串前缀 ⇒ demo/ 不会误放行 demo2/（check-inflight 同款口径）。
            dirs = f[:-1]
            return 0 < len(e) <= len(dirs) and any(dirs[i:i + len(e)] == e for i in range(len(dirs) - len(e) + 1))
        if len(e) == len(f):
            return e == f
        # 允许白名单写仓内相对路径、命令行给绝对路径（自测的临时文件就是绝对路径）
        return len(e) < len(f) and f[-len(e):] == e


def _num(s: str) -> float | None:
    try:
        return float(str(s).strip())
    except ValueError:
        return None


def load_whitelist(path: Path) -> list[Entry]:
    entries: list[Entry] = []
    if not path.is_file():
        return entries
    for lineno, raw in enumerate(path.read_text(encoding="utf-8-sig", errors="replace").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) < 3:
            print(f"  ！白名单第 {lineno} 行格式不对（要 路径 | 值 | 理由）：{line}", file=sys.stderr)
            continue
        entries.append(Entry(parts[0], parts[1], "|".join(parts[2:]), line))
    return entries


# ---------------------------------------------------------------- 扫描


def scan_file(fp: Path, rel: str, entries: list[Entry]) -> list[dict]:
    text = strip_comments(fp.read_text(encoding="utf-8-sig", errors="replace"))
    hits: list[dict] = []
    for m in CALL_RE.finditer(text):
        args, _ = arg_span(text, m.end() - 1)
        ln = line_of(text, m.start())
        dp_values = [float(v) for v in DP_RE.findall(args)]
        form = "dp"
        if not dp_values:
            pm = PERCENT_RE.match(args)
            if pm:  # RoundedCornerShape(50) —— 百分比写法
                dp_values = [float(pm.group(1))]
                form = "percent"
            else:
                continue  # 令牌 / Shape 别名 / 常量参数 ⇒ 放行（常量是已知盲区）
        for value in dp_values:
            if value in TOKEN_VALUES:
                kind, need = "8 档裸值", True
            elif value >= CAPSULE_AT:
                kind, need = "胶囊(≥50)", False
            else:
                kind, need = "自定义裸值", True
            hit = {"file": rel, "line": ln, "value": value, "form": form,
                   "kind": kind, "verdict": "报红" if need else "放行", "reason": ""}
            if need:
                for e in entries:
                    if e.matches(rel, value):
                        e.used = True
                        hit["verdict"] = "白名单放行"
                        hit["reason"] = e.reason
                        break
            hits.append(hit)
    return hits


def collect_files(args_list: list[str] | None) -> list[Path]:
    if args_list:
        out: list[Path] = []
        for token in args_list:
            p = Path(token)
            if not p.is_absolute():
                p = REPO / token
            if p.is_file():
                out.append(p)
            elif p.is_dir():
                out.extend(sorted(p.rglob("*.kt")))
            else:
                print(f"✗ 路径不存在：{token}", file=sys.stderr)
                sys.exit(2)
        return out
    return sorted(DEFAULT_ROOT.rglob("*.kt"))


def rel_of(fp: Path) -> str:
    try:
        return norm_path(fp.resolve().relative_to(REPO))
    except Exception:
        return norm_path(fp)


# ---------------------------------------------------------------- 主流程


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="圆角令牌门禁（第七十批）")
    ap.add_argument("--paths", help="只查这些文件/目录（逗号或空格分隔）；缺省＝扫 ui/ 全量")
    ap.add_argument("--whitelist", default=str(DEFAULT_WHITELIST), help="白名单文件（默认 tools/radius-whitelist.txt）")
    ap.add_argument("--json", action="store_true", help="输出 JSON（机器读）")
    ns = ap.parse_args(argv)

    tokens = [p for p in re.split(r"[,\s]+", ns.paths.strip()) if p] if ns.paths else None
    files = collect_files(tokens)
    entries = load_whitelist(Path(ns.whitelist))

    hits: list[dict] = []
    for fp in files:
        hits.extend(scan_file(fp, rel_of(fp), entries))

    bad = [h for h in hits if h["verdict"] == "报红"]
    stale = [e for e in entries if not e.used]
    counts = {
        "files": len(files),
        "hits": len(hits),
        "token_pass": sum(1 for h in hits if h["kind"] == "胶囊(≥50)"),
        "whitelist_pass": sum(1 for h in hits if h["verdict"] == "白名单放行"),
        "red": len(bad),
    }

    if ns.json:
        print(json.dumps({"counts": counts, "hits": hits,
                          "stale_whitelist": [e.raw for e in stale]}, ensure_ascii=False, indent=2))
    else:
        print(f"圆角令牌门禁 —— 扫 {counts['files']} 个 .kt，命中 {counts['hits']} 处字面量")
        print(f"  胶囊自动放行 {counts['token_pass']} ｜ 白名单放行 {counts['whitelist_pass']} ｜ 报红 {counts['red']}")
        for h in bad:
            print(f"  ✗ {h['file']}:{h['line']} | {fmt_value(h['value'], h['form'])} | {h['kind']} 裸写，换令牌或进白名单")
        for h in hits:
            if h["verdict"] == "白名单放行":
                print(f"  · {h['file']}:{h['line']} | {fmt_value(h['value'], h['form'])} | 白名单：{h['reason']}")
        for e in stale:
            print(f"  ！白名单条目已无对应裸值（过期，可删）：{e.raw}")
        print("结论：" + ("✗ 有 %d 处报红" % counts["red"] if counts["red"] else "✓ 未发现报红项"))

    return 1 if bad else 0


def fmt_value(v: float, form: str) -> str:
    s = ("%g" % v)
    return f"{s}.dp" if form == "dp" else f"{s}（百分比）"


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
