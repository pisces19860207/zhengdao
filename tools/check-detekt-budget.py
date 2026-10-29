#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-detekt-budget.py —— detekt 的**预算闸**：存量不动，多了就红。

第二百六十六批（2026-10-28）新增。

## 为什么不用 `--baseline`（这是本批的实测发现，不是偷懒）

`config/detekt/baseline.xml` 是 2026-10-19 冻结的 171 条。带 `--baseline` 跑，
仍报 **330 条**：detekt 的 baseline ID 是「规则:文件名$**完整签名**」的精确字符串，
262 / 264 等批改过大量函数签名（参数名、尾逗号、KDoc），ID 一变就匹配不上。
而这批 baseline 自冻结起**一次都没在流水线里跑过**，所以「它已经对不上代码」这件事
此前无人发现。

派工单 §3 负例 2 明令禁止「为了让 detekt 通过去改 baseline」，所以本批**不动它**，
改用仓库已有的预算闸模式（`tools/check-lint-budget.py` 同款）：

  * `config/detekt/issue-budget.json` 记当前读数（总条数 + 规则分布 + 每条指纹）；
  * 本脚本比对：**条数或规则分布超预算 ⇒ 红**；新增的指纹逐条打成 annotation；
  * 条数变少只提示（说明有人把它修好了 ⇒ 应该顺手把预算降下来）。

指纹 = `规则|文件名|message`。故意**不含行号**（改一行日志就会漂，那不算新增），
也不含绝对路径（CI 是 Linux、本机是 Windows，路径必然不同）。

## 用法

    ./gradlew :app:detektReport        # 产出 app/build/reports/detekt/detekt.xml
    python3 tools/check-detekt-budget.py

    python3 tools/check-detekt-budget.py --write   # 重新记录预算（需项目所有者确认！）
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_XML = os.path.join(ROOT, "app", "build", "reports", "detekt", "detekt.xml")
DEFAULT_BUDGET = os.path.join(ROOT, "config", "detekt", "issue-budget.json")


FUNC_RE = re.compile(r"The function (\w+)")


def fingerprint(source: str, path: str, message: str) -> str:
    """规则 + 文件名 + **函数名**。

    不含行号 / 绝对路径（见文件头说明）；也**不含测出来的数字**——
    2026-10-29 实测：`The function runLoop is too long (527)` 比预算里的 526 多一行，
    旧指纹（带消息原文）就把它判成"新增违规"，而代码其实没变坏。测出来的值只进报告。

    取不到函数名时退回「消息去掉所有数字」的形态，保证仍然稳定。
    """
    rule = source.split(".")[-1] if source else "?"
    name = os.path.basename(path.replace("\\", "/"))
    m = FUNC_RE.search(message or "")
    if m:
        return "%s|%s|%s" % (rule, name, m.group(1))
    return "%s|%s|%s" % (rule, name, re.sub(r"\d+", "#", message or ""))


def read_issues(xml_path):
    """返回 [(fingerprint, rule, 文件相对路径, 行号)]。"""
    tree = ET.parse(xml_path)
    out = []
    for node in tree.getroot():
        if node.tag != "file":
            continue
        path = node.get("name", "")
        rel = os.path.relpath(path, ROOT).replace("\\", "/") if os.path.isabs(path) else path
        for err in node:
            if err.tag != "error":
                continue
            source = err.get("source", "")
            out.append(
                {
                    "fp": fingerprint(source, path, err.get("message", "")),
                    "rule": source.split(".")[-1],
                    "rel": rel,
                    "line": err.get("line", "?"),
                    "message": err.get("message", ""),
                }
            )
    # 同名重载（同规则+同文件+同函数名）按行号升序加 #N，避免两条撞成一条：
    # detekt 不给重载签名，行号是唯一稳定的区分依据（行号本身不进指纹的其他部分）。
    groups = {}
    for it in out:
        groups.setdefault(it["fp"], []).append(it)
    for base, items in groups.items():
        if len(items) == 1:
            items[0]["fp"] = base + "#1"
            continue
        for i, it in enumerate(sorted(items, key=lambda x: int(x["line"]) if str(x["line"]).isdigit() else 0), 1):
            it["fp"] = "%s#%d" % (base, i)
    return [(it["fp"], it["rule"], it["rel"], it["line"]) for it in out]


def main(argv=None):
    ap = argparse.ArgumentParser(description="detekt 预算闸（存量不动，新增即红）")
    ap.add_argument("--xml", default=DEFAULT_XML)
    ap.add_argument("--budget", default=DEFAULT_BUDGET)
    ap.add_argument("--write", action="store_true", help="把当前读数写成新预算（需人工确认；默认只许降不许升）")
    ap.add_argument("--allow-raise", action="store_true", help="配合 --write：允许预算变大（需在提交信息里写理由）")
    args = ap.parse_args(argv)

    if not os.path.exists(args.xml):
        print("::error title=detekt 预算闸::找不到 %s（先跑 ./gradlew :app:detektReport）" % args.xml)
        return 1

    issues = read_issues(args.xml)
    rules = {}
    for fp, rule, _rel, _ln in issues:
        rules[rule] = rules.get(rule, 0) + 1
    current = {"total": len(issues), "rules": dict(sorted(rules.items())), "issues": sorted(fp for fp, *_ in issues)}

    if args.write:
        # 预算只能降不能升：确实是新接受的存量（例如规则集扩容）才用 --allow-raise，并写进提交信息。
        if os.path.exists(args.budget):
            with open(args.budget, "r", encoding="utf-8") as fh:
                prev = json.load(fh)
            prev_total = prev.get("total", 0)
            if current["total"] > prev_total and not args.allow_raise:
                print(
                    "::error title=detekt 预算闸::拒绝把预算抬高（%d → %d）。"
                    "预算只能降不能升；确实要接受新增存量时用 --allow-raise 并在提交信息里写理由。"
                    % (prev_total, current["total"])
                )
                return 1
        with open(args.budget, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(current, fh, ensure_ascii=False, indent=2, sort_keys=False)
            fh.write("\n")
        print("[detekt-budget] 预算已重记：%d 条（%s）" % (current["total"], ", ".join("%s=%d" % kv for kv in current["rules"].items())))
        return 0

    if not os.path.exists(args.budget):
        print("::error title=detekt 预算闸::找不到预算文件 %s" % args.budget)
        return 1
    with open(args.budget, "r", encoding="utf-8") as fh:
        budget = json.load(fh)

    print(
        "[detekt-budget] 当前 %d 条（%s）；预算 %d 条（%s）"
        % (
            current["total"],
            ", ".join("%s=%d" % kv for kv in current["rules"].items()),
            budget.get("total", 0),
            ", ".join("%s=%d" % kv for kv in budget.get("rules", {}).items()),
        )
    )

    problems = []
    if current["total"] > budget.get("total", 0):
        problems.append("总数 %d > 预算 %d（多了 %d 条）" % (current["total"], budget["total"], current["total"] - budget["total"]))
    for rule, n in current["rules"].items():
        b = budget.get("rules", {}).get(rule, 0)
        if n > b:
            problems.append("%s %d > 预算 %d（多 %d）" % (rule, n, b, n - b))

    known = set(budget.get("issues", []))
    fresh = [i for i in issues if i[0] not in known]
    # 指纹格式迁移提示：旧格式（消息原文入指纹）一条都对不上时，别刷成几百条"新增"。
    if known and fresh and len(fresh) == len(issues) and any("|too long (" in k or "complexity: " in k for k in known):
        print(
            "::error title=detekt 预算闸::预算指纹仍是旧格式（带测出来的数字）——"
            "先跑 `python3 tools/check-detekt-budget.py --write` 做一次性迁移（条数不变，只是去噪）。"
        )
        return 1
    # 指纹是集合；条数不增但指纹全换（改一个函数又新增一个）也要看得见。
    if fresh and not problems:
        # 只在新指纹数超过预算余量时判红：余量 = 预算总数 - 当前总数（应为 0）
        slack = budget.get("total", 0) - current["total"]
        if len(fresh) > max(slack, 0):
            problems.append("有 %d 条预算外的**新**违规指纹（条数没超是因为旧的被修掉了）" % len(fresh))

    for fp, rule, rel, ln in fresh[:15]:
        print("::warning title=detekt 新增::%s %s:%s" % (rule, rel, ln))

    if problems:
        for p in problems:
            print("::error title=detekt 预算闸::" + p)
        print(
            "\n失败：%d 处。存量不阻塞；新增必须当场修掉，或（确实要接受时）"
            "在 PR 里说明理由后跑 `python3 tools/check-detekt-budget.py --write` 并附带说明。" % len(problems)
        )
        return 1

    if current["total"] < budget.get("total", 0):
        print(
            "::notice title=detekt 预算::降到 %d 条（预算 %d）—— 有人修掉了存量，请顺手把预算降下来"
            % (current["total"], budget["total"])
        )
    print("通过：%d 条在预算内（存量不动，未发现新增）" % current["total"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
