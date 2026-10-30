#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ci-test-annotations.py —— 把失败的单元测试**变成 CI 注解**（纯标准库）。

为什么要有（2026-10-30）
    CI 红在「全量单元测试」这一步时，GitHub 的注解里只有一句
    `Process completed with exit code 1.` —— 看不到**哪条**用例失败。
    日志要登录才能看，等于「红了但查不动」。本脚本读 Gradle 写下的 JUnit XML，
    为每条失败/错误打一个 `::error::` 注解（带类名、用例名、首行消息），
    这样匿名打开 Actions 页面就能直接看到失败清单。

用法（CI 里紧跟在测试步之后，`if: always()`）
    python3 tools/ci-test-annotations.py
    python3 tools/ci-test-annotations.py --dir app/build/test-results/testDebugUnitTest
    python3 tools/ci-test-annotations.py --json

退出码
    永远 0：它是**报告器**，不改变这一轮的成败（成败由测试步自己决定）。
    找不到结果目录时打一条 notice 说明（不是失败：可能是测试没开始就被前一步拦了）。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DIR = os.path.join(ROOT, "app", "build", "test-results", "testDebugUnitTest")


def first_line(text: str, limit: int = 300) -> str:
    text = (text or "").strip()
    if not text:
        return "(无消息)"
    line = text.splitlines()[0].strip()
    return line if len(line) <= limit else line[: limit - 1] + "…"


def collect(dir_path: str) -> dict:
    suites = []
    for path in sorted(glob.glob(os.path.join(dir_path, "*.xml"))):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:  # 坏 XML 也要说，不许静默
            suites.append({"file": os.path.basename(path), "parseError": str(e), "cases": []})
            continue
        cases = []
        for tc in root.iter("testcase"):
            failures = list(tc.iter("failure")) + list(tc.iter("error"))
            cases.append(
                {
                    "class": tc.get("classname") or root.get("name") or "",
                    "name": tc.get("name") or "",
                    "time": tc.get("time"),
                    "skipped": tc.find("skipped") is not None,
                    "failures": [
                        {"kind": f.tag, "message": first_line(f.get("message") or (f.text or ""))}
                        for f in failures
                    ],
                }
            )
        suites.append(
            {
                "file": os.path.basename(path),
                "tests": int(root.get("tests") or len(cases)),
                "failures": int(root.get("failures") or 0),
                "errors": int(root.get("errors") or 0),
                "skipped": int(root.get("skipped") or 0),
                "cases": cases,
            }
        )
    return {"dir": dir_path, "suites": suites}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="把失败的单测变成 GitHub 注解")
    ap.add_argument("--dir", default=DEFAULT_DIR, help="JUnit XML 所在目录")
    ap.add_argument("--json", action="store_true", help="输出 JSON（机器读）")
    ns = ap.parse_args(argv)

    if not os.path.isdir(ns.dir):
        print("::notice title=单测注解::没有找到 %s（测试步可能没跑到）" % ns.dir)
        return 0

    report = collect(ns.dir)
    if ns.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    total = failed = skipped = 0
    bad_suites = []
    for suite in report["suites"]:
        if "parseError" in suite:
            bad_suites.append(suite)
            continue
        total += suite["tests"]
        skipped += suite["skipped"]
        for case in suite["cases"]:
            for f in case["failures"]:
                failed += 1
                print(
                    "::error title=单测失败::%s#%s — %s" % (case["class"], case["name"], f["message"])
                )

    for suite in bad_suites:
        print("::error title=单测结果损坏::%s — %s" % (suite["file"], suite["parseError"]))

    print(
        "::notice title=单测读数::共 %d 例，失败 %d，跳过 %d，套件 %d"
        % (total, failed, skipped, len(report["suites"]))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
