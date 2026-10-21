#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""源码门禁：LocalContext 取资源（第 133 批 A 段的落地判据）。

只读、不联网、不依赖 gradle 产物，CI 与本地都能跑：

  ① 压制零容忍：`@Suppress("LocalContextGetResourceValueCall")` /
     `//noinspection LocalContextGetResourceValueCall` / lint.xml 里对该规则的
     severities 覆盖 —— 出现一条就红（压制＝假清零）。
  ② Composable 里不许用 `LocalContext.current` 变量去取资源：
     `<ctx>.getString(` / `<ctx>.getQuantityString(` / `<ctx>.resources` 出现即红。
     判法：先收集每个文件里 `val X = LocalContext.current` 的变量名 X，
     再查 `X.getString(` / `X.resources`。当前存量已清零，白名单为空。

退出码：0 绿；1 红（逐条打印文件:行）。
用法：python tools/check-localcontext-res.py [--root .]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SRC = "app/src/main/java"
LINT_XML = "app/src/main/lint.xml"
RULES = ("LocalContextGetResourceValueCall", "LocalContextResourcesRead")
CTX_VAL = re.compile(r"val\s+(\w+)\s*=\s*(?:androidx\.compose\.ui\.platform\.)?LocalContext\.current")
SUPPRESS = re.compile(
    r'@Suppress\s*\([^)]*LocalContext(?:GetResourceValueCall|ResourcesRead)'
    r'|noinspection\s+LocalContext(?:GetResourceValueCall|ResourcesRead)'
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path("."))
    args = ap.parse_args()
    root: Path = args.root.resolve()
    bad: list[str] = []

    # ① 压制
    for p in (root / SRC).rglob("*.kt"):
        for i, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if SUPPRESS.search(line):
                bad.append(f"{p.relative_to(root).as_posix()}:{i}: 用了压制（{line.strip()[:70]}）")
    lint_xml = root / LINT_XML
    if lint_xml.exists():
        txt = lint_xml.read_text(encoding="utf-8", errors="replace")
        for r in RULES:
            if f'id="{r}"' in txt:
                bad.append(f"{LINT_XML}: 覆盖了 {r} 的严重级别")

    # ② @Composable 顶层函数内不许用 LocalContext 变量取资源
    # 判定「在不在 composable 里」用的是列 0 的 fun 行做区间切分（比花括号配平稳：
    # HomeScreen 那种 1400 行的函数，配平会被字符串/注释里的括号带偏）。
    hits = 0
    for p2 in (root / SRC).rglob("*.kt"):
        txt = p2.read_text(encoding="utf-8", errors="replace")
        names = set(CTX_VAL.findall(txt))
        if not names:
            continue
        lines = txt.splitlines()
        fun_rows = [i for i, l in enumerate(lines) if re.match(r"^(?:internal |private |public )?fun\s", l)]
        spans = []
        for k, start in enumerate(fun_rows):
            end = fun_rows[k + 1] - 1 if k + 1 < len(fun_rows) else len(lines) - 1
            is_comp = any("@Composable" in lines[j] for j in range(max(0, start - 6), start))
            spans.append((start, end, is_comp))
        for i, line in enumerate(lines, 1):
            idx = i - 1
            # 命中的 ctx 可能是**函数参数**（同文件别处另有 LocalContext）⇒ 签名里带 `ctx:` 的不算违规
            in_comp = False
            for s, e, c in spans:
                if c and s <= idx <= e:
                    sig = " ".join(lines[s:min(s + 6, len(lines))])
                    if not any(re.search(rf"\b{re.escape(n)}\s*:", sig) for n in names):
                        in_comp = True
                    break
            if not in_comp:
                continue
            for n in names:
                if re.search(rf"(?<![\w.]){re.escape(n)}\.(getString|getQuantityString|getText|getStringArray)\s*\(", line) \
                   or re.search(rf"(?<![\w.]){re.escape(n)}\.resources\b", line):
                    bad.append(f"{p2.relative_to(root).as_posix()}:{i}: Composable 里 LocalContext 变量 `{n}` 仍在取资源 → {line.strip()[:70]}")
                    hits += 1

    if bad:
        print(f"[localcontext] ✗ {len(bad)} 条违规（压制 {len([b for b in bad if '压制' in b or '严重级别' in b])}、取值 {hits}）", file=sys.stderr)
        for b in bad:
            print("   " + b, file=sys.stderr)
        return 1
    print("[localcontext] ✓ 无压制、LocalContext 取资源 0 处")
    return 0


if __name__ == "__main__":
    sys.exit(main())
