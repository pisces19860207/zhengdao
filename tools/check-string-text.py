#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文案门禁（第 133 批新增）：已有 <string> 的值不许改。

只查「基线 commit 与工作区都存在的 key，值必须逐字相同」——新增/删除 key 不归本门禁管
（删资源要过编译，新增要过中英对称）。这样清债批次动代码不会悄悄改到用户看到的字。

用法：
  python tools/check-string-text.py [--base origin/main]
退出码：0 绿 / 1 红（逐条打印 key 与两边取值）。
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

try:  # Windows 控制台默认 GBK：成功路径的 ✓ 不能把门禁自己打死（E-170 同款）
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

PAIRS = [("app/src/main/res/values/strings.xml", "zh"), ("app/src/main/res/values-en/strings.xml", "en")]


def load_text(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def load_base_text(rev: str, path: str) -> str | None:
    r = subprocess.run(["git", "show", f"{rev}:{path}"], capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout.decode("utf-8", errors="replace")


def parse_values(text: str) -> dict[str, str]:
    root = ET.fromstring(text)
    out = {}
    for s in root.iter("string"):
        out[s.get("name")] = "".join(s.itertext())
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="origin/main")
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    bad = []
    for path, tag in PAIRS:
        cur = parse_values(load_text(f"{args.root}/{path}"))
        bt = load_base_text(args.base, path)
        if bt is None:
            print(f"[strings] ~ 取不到基线 {args.base}:{path}，跳过该文件", file=sys.stderr)
            continue
        base = parse_values(bt)
        for k, v in base.items():
            if k in cur and cur[k] != v:
                bad.append(f"{path} [{tag}] {k}\n      基线: {v!r}\n      现在: {cur[k]!r}")
    if bad:
        print(f"[strings] ✗ {len(bad)} 条已有文案被改动", file=sys.stderr)
        for b in bad:
            print("   " + b, file=sys.stderr)
        return 1
    print("[strings] ✓ 已有文案逐字未变")
    return 0


if __name__ == "__main__":
    sys.exit(main())
