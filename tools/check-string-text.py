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

# 134 批（2026-10-23）用户已批准的最小可见文案修正：只为消 lint（TypographyDashes /
# TypographyEllipsis / TypographyOther / Typos），句子其余字逐字不变。
# 键 → 批准后的**新值**；新值再被改动一次仍会红（放行的是这一次，不是这个键以后随便改）。
APPROVED_TEXT_CHANGES = {
    ("zh", "baopu_tool_run_subtasks_desc"): (
        "把大任务拆成 1–5 个子任务，交给至多 2 个只读子 Agent 并行或串行执行，结果汇总回来。"
        "子 Agent 只能读文件、列目录、搜内容，没有任何写工具，也看不到你们的对话历史；"
        "子任务失败会自动重试一次，仍失败就照实报原因。子结果不会自动写进任何文件，"
        "要不要落产物另行决定。默认不可见：先在 capabilities 里解锁「多 Agent 协作」组才会出现。"
    ),
    ("en", "baopu_tool_run_subtasks_desc"): (
        "Splits a big task into 1–5 subtasks and runs them with up to 2 read-only sub-agents in "
        "parallel or in series, then collects the summary. Sub-agents can only read files, list "
        "directories and search content - they have no write tools and never see your conversation "
        "history. A failed subtask is retried once; if it still fails the reason is reported. "
        "Sub-results are never written to any file. Hidden by default: unlock the multi-agent group "
        "in capabilities first."
    ),
    ("en", "post_adopt_counts"): (
        "Before: OK %1$d / fail %2$d / corrected %3$d · After: +OK %4$d / +fail %5$d / +corrected %6$d"
    ),
    ("en", "decision_scope_workspace"): "Scope: this workspace [C]",
    ("en", "baopu_artifact_loading"): "Loading…",
}


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
                approved = APPROVED_TEXT_CHANGES.get((tag, k))
                if approved == cur[k]:
                    print(f"[strings] ~ 已批准修正（134 批）：{path} [{tag}] {k}")
                    continue
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
