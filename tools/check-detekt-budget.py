#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-detekt-budget.py —— detekt 的**预算闸**：存量不动，多了就红。

第二百六十六批（2026-10-28）新增；2026-10-29 按用户口径收紧三处。

## 判据只有一条：**总量**

`config/detekt/issue-budget.json` 记当前读数（总量 + 规则分布 + 每条指纹），
本闸**只比总量**：`当前总数 > 预算总数` ⇒ 红。为什么只比总量：

  * detekt 自己的 ID 是「规则:文件名:行号」一类的精确串，**行号一漂移键就全变**——
    266 那批就是因为这个让冻结的 `baseline.xml`（171 条）整体失效：带 `--baseline` 跑仍报 330 条。
  * 所以「逐条指纹」在这里**只作诊断**（打印对不上的前 15 条，方便人看是重构还是改名），
    **不参与判红**；规则分布超预算也只 warning。
  * 预算变少只打 notice：说明有人修了存量，应该顺手把预算降下来（不强制同 PR 改，
    免得 330 行的 JSON 天天冲突）。

## 预算只能降不能升，但留一个**带理由**的抬升通道

`--write` 默认拒绝把总量变大；确实需要（例如新代码真多了一个长函数）时：

    python3 tools/check-detekt-budget.py --write \
        --xml <CI 下载的 detekt.xml> --from-ci "run #1234 的 detekt-report artifact" \
        --allow-raise "265 新增的 XX 构造器确实超 60 行，拆不动"

理由写进预算文件的 `raised_reason`，读数出处写进 `recorded_from`。

## `--write` 只许用 **CI 的读数**

本机与 CI 的 JDK/工具链未必一致，本机总数可能与 CI 差几十条；一旦用本机重记，CI 第二天就红。
所以 `--write` **必须显式给 `--xml`**（不给 ⇒ 拒绝）：

    CI 跑 ci.yml 的「detekt 预算闸」步骤 → 下载 artifact `detekt-report`（含 detekt.xml）
    → python3 tools/check-detekt-budget.py --write --xml <解压出的 detekt.xml> --from-ci "run #N"
    → 提交（基线 diff 与理由一起进提交信息）

## 指纹（仅诊断 / 供 --write 记录）

`规则|文件名|函数名[#重载序号]`：不含行号、不含绝对路径，也**不含测出来的数字**
（`too long (527)` / `complexity: 106` 这类值只属于报告）。同名重载按行号顺序编号，
避免两条撞成一条。

## 用法

    ./gradlew :app:detektReport                 # 只出报告（本机一般也能跑，需 JDK21）
    python3 tools/check-detekt-budget.py        # 判红：只比总量
    python3 tools/check-detekt-budget.py --write --xml <CI 的 detekt.xml> --from-ci "<出处>"
"""

from __future__ import annotations

import argparse
import datetime
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
    """规则 + 文件名 + 函数名（不含行号 / 绝对路径 / 测出来的数字）。"""
    rule = source.split(".")[-1] if source else "?"
    name = os.path.basename(path.replace("\\", "/"))
    m = FUNC_RE.search(message or "")
    if m:
        return "%s|%s|%s" % (rule, name, m.group(1))
    return "%s|%s|%s" % (rule, name, re.sub(r"\d+", "#", message or ""))


def read_issues(xml_path: str):
    """返回 [(fingerprint, rule, 文件相对路径, 行号)]（重载按行号顺序加 #N）。"""
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
                }
            )
    groups = {}
    for it in out:
        groups.setdefault(it["fp"], []).append(it)
    for base, items in groups.items():
        ordered = sorted(items, key=lambda x: int(x["line"]) if str(x["line"]).isdigit() else 0)
        for i, it in enumerate(ordered, 1):
            it["fp"] = "%s#%d" % (base, i)
    return [(it["fp"], it["rule"], it["rel"], it["line"]) for it in out]


def rules_of(issues) -> dict:
    rules: dict[str, int] = {}
    for _fp, rule, _rel, _ln in issues:
        rules[rule] = rules.get(rule, 0) + 1
    return dict(sorted(rules.items()))


def main(argv=None):
    ap = argparse.ArgumentParser(description="detekt 预算闸（只比总量；存量不动，多了就红）")
    ap.add_argument("--xml", default=None, help="detekt 报告；判红时缺省用本机产物，--write 时必须显式给（用 CI 的）")
    ap.add_argument("--budget", default=DEFAULT_BUDGET)
    ap.add_argument("--write", action="store_true", help="重记预算（必须配 --xml 与 --from-ci；默认只许降）")
    ap.add_argument("--from-ci", dest="from_ci", default=None, help="读数出处（--write 必填，例如 'run #1234 的 detekt-report artifact'）")
    ap.add_argument("--allow-raise", dest="allow_raise", default=None, metavar="理由",
                    help="允许预算变大：必须写清理由（会记进 raised_reason）")
    args = ap.parse_args(argv)

    # ── 写预算模式 ────────────────────────────────────────────────
    if args.write:
        if not args.xml:
            print("::error title=detekt 预算::--write 必须显式给 --xml（用 CI 下载的 detekt.xml）；"
                  "本机读数不许进预算（本机与 CI 的 JDK/工具链未必一致，用它重记 CI 第二天就红）。"
                  "流程：CI 跑 detekt → 下载 artifact detekt-report → --write --xml <那份 xml> --from-ci \"run #N\"")
            return 1
        if not args.from_ci:
            print("::error title=detekt 预算::--write 必须配 --from-ci，写清读数出处（例如 'run #1234 的 detekt-report artifact'）。")
            return 1
        if not os.path.exists(args.xml):
            print("::error title=detekt 预算::找不到 %s" % args.xml)
            return 1

        issues = read_issues(args.xml)
        current_total = len(issues)
        rules = rules_of(issues)

        prev_total = 0
        if os.path.exists(args.budget):
            with open(args.budget, "r", encoding="utf-8") as fh:
                prev_total = json.load(fh).get("total", 0)
        # 「只比总量」：不看每条规则的键，也不看指纹集合的增减——那些会随行号/改名漂移。
        if current_total > prev_total and not args.allow_raise:
            print(
                "::error title=detekt 预算::拒绝把预算抬高（%d → %d）。预算默认只能降；"
                "确实要接受这次新增，用 --allow-raise \"为什么这次必须涨\" 并把这个理由写进提交信息。"
                % (prev_total, current_total)
            )
            return 1

        doc = {
            "total": current_total,
            "rules": rules,
            "issues": sorted(fp for fp, *_ in issues),
            "recorded_from": args.from_ci,
            "recorded_at": datetime.datetime.now().astimezone().strftime("%Y-%m-%dT%H:%M:%S%z"),
        }
        if current_total > prev_total:
            doc["raised_reason"] = args.allow_raise
        with open(args.budget, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(doc, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        print("[detekt-budget] 预算已重记：%d 条（%s）；出处：%s%s"
              % (current_total, ", ".join("%s=%d" % kv for kv in rules.items()), args.from_ci,
                 ("；抬升理由：%s" % args.allow_raise) if current_total > prev_total else ""))
        return 0

    # ── 判红模式（CI 默认）────────────────────────────────────────
    xml_path = args.xml or DEFAULT_XML
    if not os.path.exists(xml_path):
        print("::error title=detekt 预算闸::找不到 %s（先跑 ./gradlew :app:detektReport）" % xml_path)
        return 1
    if not os.path.exists(args.budget):
        print("::error title=detekt 预算闸::找不到预算文件 %s" % args.budget)
        return 1

    issues = read_issues(xml_path)
    current_total = len(issues)
    rules = rules_of(issues)
    with open(args.budget, "r", encoding="utf-8") as fh:
        budget = json.load(fh)
    budget_total = budget.get("total", 0)

    print("[detekt-budget] 当前 %d 条（%s）；预算 %d 条（%s）"
          % (current_total, ", ".join("%s=%d" % kv for kv in rules.items()),
             budget_total, ", ".join("%s=%d" % kv for kv in budget.get("rules", {}).items())))

    # 规则分布超预算：只 warning（判据只有总量）。
    for rule, n in rules.items():
        b = budget.get("rules", {}).get(rule, 0)
        if n > b:
            print("::warning title=detekt 规则分布::%s %d > 预算 %d（只提示；判据是总量）" % (rule, n, b))

    known = set(budget.get("issues", []))
    fresh = [i for i in issues if i[0] not in known]
    if known and fresh:
        if any("|too long (" in k or "complexity: " in k for k in known):
            print("::notice title=detekt 预算::预算里的指纹还是旧口径（带测出来的数字）；"
                  "不影响判红（只比总量），下次用 CI 读数 --write 时会自动转成新指纹。")
        else:
            print("::notice title=detekt 预算::%d 条指纹与预算对不上（改名/重构/行号漂移都可能）——"
                  "只作诊断，不判红；数量以总量为准。" % len(fresh))
        for fp, rule, rel, ln in fresh[:15]:
            print("::warning title=detekt 指纹诊断::%s %s:%s（%s）" % (rule, rel, ln, fp))

    if current_total > budget_total:
        print("::error title=detekt 预算闸::总量 %d > 预算 %d（多 %d 条）。"
              % (current_total, budget_total, current_total - budget_total))
        print("\n失败：新增存量必须当场修掉；确实要接受时用 --write --xml <CI 的 xml> "
              "--from-ci \"run #N\" --allow-raise \"理由\"，并把理由写进提交信息。")
        return 1

    if current_total < budget_total:
        print("::notice title=detekt 预算::降到 %d 条（预算 %d）—— 有人修掉了存量，"
              "请下次用 CI 读数 --write 顺手把预算降下来。" % (current_total, budget_total))
    print("通过：%d 条在预算内（判据＝总量，存量不动，未发现新增）" % current_total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
