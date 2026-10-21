#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P0-2（2026-10-19）：Android Lint 预算闸 —— 不许新增告警。

背景：CI 里 lintDebug 常年 `|| echo` 吞掉失败（ci.yml:248），告警是 0 条还是 2000 条
没人知道，新增告警也不会被拦。本脚本把「告警总数」变成一条会红的预算：
首跑写基线（--write-baseline），之后每次 CI 比一次，**超过基线就红**。

纪律：
- 只比数字，不锁具体规则；不压制任何告警（不用 lint --baseline）。
- 基线只能通过 PR 显式下调或（有理由地）上调，改基线必须改这个文件并在 PR 说明原因。
- 有 Error 级别告警时，始终判红（Error 不是"债务"，是坏）。

用法：
  python3 tools/check-lint-budget.py --write-baseline   # 首跑：把当前数字写成基线
  python3 tools/check-lint-budget.py                    # CI：比基线，超了就退出码 1
"""
from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

DEFAULT_REPORT = Path("app/build/reports/lint-results-debug.xml")
DEFAULT_BASELINE = Path("tools/lint-baseline.json")

# ⚠️ 网络/时间相关的检查不计入预算：本机与 CI 联网状态、库发布时间不同，
# 同一条代码会数出不同的 NewerVersionAvailable ⇒ 会变成假红。
# 它们仍然会出现在 artifact 报告里，只是不参与「超预算」判定。
UNBUDGETED_RULES = frozenset(
    {
        "NewerVersionAvailable",
        "GradleDependency",
        "AndroidGradlePluginVersion",
        "RemoteVersionCheck",
    }
)


def count_issues(report: Path) -> dict:
    if not report.exists():
        print(f"[lint-budget] 找不到 lint 报告：{report}", file=sys.stderr)
        print("[lint-budget] 先跑：./gradlew :app:lintDebug", file=sys.stderr)
        sys.exit(2)

    root = ET.parse(report).getroot()
    total = errors = warnings = infos = 0
    unbudgeted = 0
    for issue in root.iter("issue"):
        rid = issue.get("id") or ""
        sev = (issue.get("severity") or "").lower()
        if rid in UNBUDGETED_RULES:
            unbudgeted += 1
            continue
        total += 1
        if sev == "error" or sev == "fatal":
            errors += 1
        elif sev == "warning":
            warnings += 1
        else:
            infos += 1
    return {
        "total": total,
        "errors": errors,
        "warnings": warnings,
        "infos": infos,
        "unbudgeted": unbudgeted,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Android Lint 预算闸")
    ap.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    ap.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    ap.add_argument("--write-baseline", action="store_true", help="把当前数字写成基线")
    args = ap.parse_args()

    now = count_issues(args.report)
    print(
        "[lint-budget] 本次：total={total} errors={errors} warnings={warnings} infos={infos}"
        "（另有 {unbudgeted} 条网络/版本类不计预算）".format(**now)
    )

    if args.write_baseline:
        payload = dict(now)
        payload["_comment"] = (
            "Android Lint 告警预算基线（P0-2）。只许通过显式提交改动；"
            "下调是好消息，上调必须在 PR 里写清原因。"
        )
        args.baseline.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"[lint-budget] 基线已写入：{args.baseline}")
        return 0

    if not args.baseline.exists():
        print(
            f"[lint-budget] 缺少基线文件 {args.baseline}；"
            "先跑一次 --write-baseline 并把它提交入库。",
            file=sys.stderr,
        )
        return 2

    base = json.loads(args.baseline.read_text(encoding="utf-8"))
    base_errors = int(base.get("errors", 0))
    base_total = int(base.get("total", 0))

    # 允许的告警抖动：CI 与本机在少数规则（PluralsCandidate/UnusedResources 的
    # 资源合并顺序等）上会差 1~3 条，这不是代码债。**只对总数给 ±5% 的带宽；
    # Error 级一律零容忍**（E-167：47 批基线 701 在 CI 上多出 1~2 条导致假红）。
    tolerance = int(base_total * 0.05)
    ok = True
    if now["errors"] > base_errors:
        print(
            f"[lint-budget] ✗ Error 级告警增加：{base_errors} → {now['errors']}"
            "（Error 不是债务，必须修）",
            file=sys.stderr,
        )
        ok = False
    if now["total"] > base_total + tolerance:
        print(
            f"[lint-budget] ✗ 告警总数超过基线+抖动带：{base_total} → {now['total']}"
            f"（新增 {now['total'] - base_total} 条，带宽 ±{tolerance}；修掉或按流程调基线）",
            file=sys.stderr,
        )
        ok = False
    if now["total"] < base_total:
        print(
            f"[lint-budget] ✓ 告警比基线少：{base_total} → {now['total']}"
            f"（可以把基线下调到 {now['total']} 并提交）"
        )
    if base_total < now["total"] <= base_total + tolerance:
        print(
            f"[lint-budget] ~ 告警略高于基线但在 ±{tolerance} 抖动带内：{base_total} → {now['total']}"
        )
    if ok:
        print(f"[lint-budget] ✓ 未超基线（{now['total']}/{base_total}）")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
