#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""grade.py —— 786「叙事连续性评测集」判分器（792 单 · 纯 Python 标准库）

一句话
    读 100 条真值（items.jsonl）＋被测系统的 {id, verdict} JSONL，出三样：
    3×3 混淆矩阵、11 类分项准确率、幻觉率（红线）。

口径（逐字照 docs/evals/narrative/README.md §五，分母写死，改口径即红）
    总体准确率 = 判对条数 / 100
    矛盾召回   = 判「矛盾」且真值矛盾 / 42      漏检率 = 1 − 该值
    支持召回   = 判「支持」且真值支持 / 40      误报率 = 1 − 该值
    未知召回   = 判「未知」且真值未知 / 18
    幻觉率     = 真值未知却被答成「支持」或「矛盾」 / 18    ← 红线指标

只做什么
    · 只比对 verdict 三档；按每条的 accept 列表做等价归一（「冲突」≡「矛盾」）。
    · 只读真值，绝不写回 items.jsonl，也不改任何真值字段。
不做什么
    · 不读 answer、不评理由、不替被测系统下结论、不给改进建议、不做 CI 打分。

用法
    python3 docs/evals/narrative/grader/grade.py --pred preds.jsonl --out-dir out
    python3 docs/evals/narrative/grader/grade.py --selftest

退出码
    0 = 判完，输入无错
    1 = 输入有错（缺 id／未知 id／重复 id／非法判定／坏 JSON），逐条列在报告 §五
    2 = 用法或环境错误（真值读不出、真值分布与 §五 口径不符、输出写不出）
"""

import argparse
import hashlib
import json
import sys
import tempfile
from pathlib import Path

try:  # Windows 控制台可能是 GBK：编不出的字符降级为替换，不让判分器自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

# ---------------------------------------------------------------- 口径常量

VERDICTS = ("支持", "矛盾", "未知")

# README §六 的 11 类，顺序即展示顺序；缺类必须显示 0 条，不许省略。
CATEGORIES = (
    "人物一致", "设定矛盾", "时间线", "场次转换", "空间不成立", "生理不合理",
    "信息越界", "物品守恒", "套话与套腔", "伏笔回收", "发现时点",
)

# README §五 的分母＝真值分布。真值一变，分母就漂 ⇒ 默认直接报错（可显式放行）。
EXPECTED_COUNTS = {"total": 100, "支持": 40, "矛盾": 42, "未知": 18}

REPORT_JSON = "eval-report.json"
REPORT_MD = "eval-report.md"

# 判定「没有有效判定」的两种错误类型：有记录但 verdict 用不了
INVALID_KINDS = ("invalid-verdict", "missing-verdict")


class CliError(Exception):
    """用法或环境错误（退出码 2）。"""


# ---------------------------------------------------------------- 小工具

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def pct(numerator, denominator):
    """读数串：42.9%（18/42）；分母 0 时给「—（0 条）」，不写 0.0% 冒充有数。"""
    if denominator <= 0:
        return "—（0 条）"
    return "%.1f%%（%d/%d）" % (numerator * 100.0 / denominator, numerator, denominator)


def rate(numerator, denominator):
    """机器读的比率。分母 0 记 0.0，同时报告里一定带上分母，防止被单独引用。"""
    if denominator <= 0:
        return 0.0
    return round(float(numerator) / float(denominator), 6)


def err(line, item_id, kind, detail):
    return {"line": line, "id": item_id, "kind": kind, "detail": detail}


def format_errors(errors):
    return "\n".join(
        "  行 %s｜%s｜%s｜%s" % (e["line"], e["id"] or "-", e["kind"], e["detail"])
        for e in errors)


# ---------------------------------------------------------------- 读真值

def load_truth(path, check_counts=True):
    """读 items.jsonl：id 唯一、verdict 三档、category 属 11 类、accept 含自身 verdict。"""
    if not path.is_file():
        raise CliError("真值文件不存在：%s" % path)
    items, errors, seen = [], [], set()
    with open(path, "r", encoding="utf-8-sig") as fh:
        for lineno, raw in enumerate(fh, 1):
            text = raw.strip()
            if not text:
                continue
            try:
                obj = json.loads(text)
            except ValueError as exc:
                errors.append(err(lineno, None, "bad-json", "不是合法 JSON：%s" % exc))
                continue
            if not isinstance(obj, dict):
                errors.append(err(lineno, None, "not-object", "该行不是 JSON 对象"))
                continue
            item_id = obj.get("id")
            if not isinstance(item_id, str) or not item_id.strip():
                errors.append(err(lineno, None, "missing-id", "真值行缺 id"))
                continue
            item_id = item_id.strip()
            if item_id in seen:
                errors.append(err(lineno, item_id, "duplicate-id", "真值 id 重复"))
                continue
            verdict = obj.get("verdict")
            if verdict not in VERDICTS:
                errors.append(err(lineno, item_id, "bad-truth-verdict",
                                  "真值 verdict 不在三档内：%r" % (verdict,)))
                continue
            category = obj.get("category")
            if category not in CATEGORIES:
                errors.append(err(lineno, item_id, "bad-truth-category",
                                  "真值 category 不在 11 类内：%r" % (category,)))
                continue
            accept = obj.get("accept")
            if not isinstance(accept, list) or not all(isinstance(a, str) for a in accept):
                errors.append(err(lineno, item_id, "bad-truth-accept", "accept 不是字符串列表"))
                continue
            accept = [a.strip() for a in accept if a.strip()]
            # accept 与 verdict 不自洽（如改判后忘改 accept）不算「输入错误」——那是真值的事。
            # 不在这里红，交给 build_alias_map 单列「真值告警」，判分照跑。
            seen.add(item_id)
            items.append({"id": item_id, "verdict": verdict,
                          "category": category, "accept": accept})

    if errors:
        raise CliError("真值文件有 %d 行不合格，拒绝判分（不静默跳过）：\n%s"
                       % (len(errors), format_errors(errors)))

    counts = {v: sum(1 for it in items if it["verdict"] == v) for v in VERDICTS}
    if check_counts:
        drift = []
        if len(items) != EXPECTED_COUNTS["total"]:
            drift.append("总条数 %d ≠ %d" % (len(items), EXPECTED_COUNTS["total"]))
        for v in VERDICTS:
            if counts[v] != EXPECTED_COUNTS[v]:
                drift.append("真值「%s」%d 条 ≠ %d 条" % (v, counts[v], EXPECTED_COUNTS[v]))
        if drift:
            raise CliError(
                "真值分布与 README §五 口径不符，分母会跟着漂，停止判分：\n  - "
                + "\n  - ".join(drift)
                + "\n（确实要跑条目变过的新版本，再加 --allow-count-drift 显式放行）")
    return items


# ---------------------------------------------------------------- 等价归一

def build_alias_map(truth_items):
    """长出「写法 → 三档」映射。

    两条规矩：
      1. 三档本名永远是自映射（「未知」不能因为某条 accept 写错就被归到「支持」）。
      2. 只有**自洽**的条目才参与长出等价写法（verdict 在 accept 里、且没混进别档本名）；
         不自洽的条目单列真值告警，**不动真值**，判分时只认它的 verdict。
    同一等价写法被两个不同档认领 ⇒ 口径真歧义，报错退出。
    """
    alias = {v: v for v in VERDICTS}
    display = {v: [v] for v in VERDICTS}
    warnings = []

    for item in truth_items:
        verdict, accept = item["verdict"], item["accept"]
        foreign = [a for a in accept if a in VERDICTS and a != verdict]
        if verdict not in accept or foreign:
            reason = ("accept 未包含自身 verdict" if verdict not in accept
                      else "accept 混入它档本名（%s）" % "、".join(foreign))
            warnings.append({"id": item["id"], "verdict": verdict,
                             "accept": list(accept), "reason": reason})
            continue
        for name in accept:
            if name in VERDICTS:
                continue
            if name in alias and alias[name] != verdict:
                raise CliError("等价写法 %r 同时被归到「%s」和「%s」，口径歧义，拒绝判分"
                               % (name, alias[name], verdict))
            alias[name] = verdict
            if name not in display[verdict]:
                display[verdict].append(name)

    return alias, display, warnings


# ---------------------------------------------------------------- 读被测输出

def load_preds(path, truth_ids, alias):
    """读 {id, verdict} JSONL。不合格的逐条记账（带行号），绝不静默跳过。"""
    if not path.is_file():
        raise CliError("被测输出不存在：%s" % path)
    records, errors, seen = [], [], {}
    with open(path, "r", encoding="utf-8-sig") as fh:
        for lineno, raw in enumerate(fh, 1):
            text = raw.strip()
            if not text:
                continue
            try:
                obj = json.loads(text)
            except ValueError as exc:
                errors.append(err(lineno, None, "bad-json", "不是合法 JSON：%s" % exc))
                continue
            if not isinstance(obj, dict):
                errors.append(err(lineno, None, "not-object", "该行不是 JSON 对象"))
                continue
            item_id = obj.get("id")
            if not isinstance(item_id, str) or not item_id.strip():
                errors.append(err(lineno, None, "missing-id", "缺 id（或 id 不是非空字符串）"))
                continue
            item_id = item_id.strip()
            if item_id not in truth_ids:
                errors.append(err(lineno, item_id, "unknown-id", "该 id 不在真值集里"))
                continue
            if item_id in seen:
                errors.append(err(lineno, item_id, "duplicate-id",
                                  "重复出现（第 %d 行已判过，本条不算数）" % seen[item_id]))
                continue
            seen[item_id] = lineno
            verdict = obj.get("verdict")
            if not isinstance(verdict, str) or not verdict.strip():
                errors.append(err(lineno, item_id, "missing-verdict", "缺 verdict"))
                continue
            canonical = alias.get(verdict.strip())
            if canonical is None:
                errors.append(err(lineno, item_id, "invalid-verdict",
                                  "verdict=%r 不在三档或等价写法里（%s）"
                                  % (verdict, "／".join(sorted(alias)))))
                continue
            records.append({"line": lineno, "id": item_id,
                            "raw": verdict.strip(), "canonical": canonical})
    return records, errors


# ---------------------------------------------------------------- 判分

def grade(truth_items, pred_records, pred_errors, meta):
    """比对并算齐五项口径 + 3×3 + 11 类。计数口径只有一套，全部从 truth_items 走一遍。"""
    index = {rec["id"]: rec for rec in pred_records}  # 重复 id 已在读入阶段记账，只认第一条

    invalid_ids = {e["id"] for e in pred_errors if e["kind"] in INVALID_KINDS}
    truth_counts = {v: 0 for v in VERDICTS}
    conf = {(t, p): 0 for t in VERDICTS for p in VERDICTS}
    correct = answered = hallucinated = 0
    absent, no_verdict = [], []
    cat = {name: {"total": 0, "correct": 0, "truth": {v: 0 for v in VERDICTS}}
           for name in CATEGORIES}

    for item in truth_items:
        truth = item["verdict"]
        truth_counts[truth] += 1
        slot = cat[item["category"]]
        slot["total"] += 1
        slot["truth"][truth] += 1

        rec = index.get(item["id"])
        if rec is None:
            # 没有可用判定：要么整条没给（未答），要么给了但 verdict 用不了（非法判定）
            no_verdict.append(item["id"])
            if item["id"] not in invalid_ids:
                absent.append(item["id"])
            continue
        pred = rec["canonical"]
        answered += 1
        conf[(truth, pred)] += 1
        if pred == truth:
            correct += 1
            slot["correct"] += 1
        if truth == "未知" and pred in ("支持", "矛盾"):
            hallucinated += 1

    total = len(truth_items)

    # 三条算术自检：行和＋表外＝真值条数；对角和＝判对；分布合计＝总条数。
    for truth in VERDICTS:
        row = sum(conf[(truth, p)] for p in VERDICTS) + \
            sum(1 for i in no_verdict if _verdict_of(truth_items, i) == truth)
        if row != truth_counts[truth]:
            raise CliError("内部不一致：真值「%s」行和 %d ≠ %d" % (truth, row, truth_counts[truth]))
    if sum(conf[(v, v)] for v in VERDICTS) != correct:
        raise CliError("内部不一致：矩阵对角和 ≠ 判对条数")
    if sum(truth_counts.values()) != total:
        raise CliError("内部不一致：真值分布合计 ≠ 总条数")

    metrics = {
        "overall_accuracy": (correct, total),
        "contradiction_recall": (conf[("矛盾", "矛盾")], truth_counts["矛盾"]),
        "support_recall": (conf[("支持", "支持")], truth_counts["支持"]),
        "unknown_recall": (conf[("未知", "未知")], truth_counts["未知"]),
        "hallucination_rate": (hallucinated, truth_counts["未知"]),
        "miss_rate": (truth_counts["矛盾"] - conf[("矛盾", "矛盾")], truth_counts["矛盾"]),
        "false_alarm_rate": (truth_counts["支持"] - conf[("支持", "支持")], truth_counts["支持"]),
        "coverage": (answered, total),
    }
    metrics = {k: {"numerator": n, "denominator": d, "rate": rate(n, d)}
               for k, (n, d) in metrics.items()}

    categories = []
    for name in CATEGORIES:
        slot = cat[name]
        categories.append({
            "name": name,
            "total": slot["total"],
            "correct": slot["correct"],
            "rate": rate(slot["correct"], slot["total"]),
            "truth": slot["truth"],
        })

    other_errors = [e for e in pred_errors if e["kind"] not in INVALID_KINDS]

    return {
        "schema": "narrative-eval-report/1",
        "input": {
            "name": meta["input_name"],
            "sha256": meta["input_sha256"],
            "valid": answered,
            "invalid": len(invalid_ids),
            "other_error_lines": len(other_errors),
        },
        "truth": {
            "name": meta["truth_name"],
            "sha256": meta["truth_sha256"],
            "total": total,
            "counts": truth_counts,
        },
        "alias": meta["alias_display"],
        "verdicts": list(VERDICTS),
        "metrics": metrics,
        "confusion": {
            "labels": list(VERDICTS),
            "matrix": [[conf[(t, p)] for p in VERDICTS] for t in VERDICTS],
            "row_totals": [truth_counts[t] for t in VERDICTS],
            "row_no_verdict": [sum(1 for i in no_verdict if _verdict_of(truth_items, i) == t)
                               for t in VERDICTS],
        },
        "categories": categories,
        "errors": pred_errors,
        "truth_warnings": meta["truth_warnings"],
        "no_verdict_ids": no_verdict,
        "absent_ids": absent,
        "invalid_ids": sorted(invalid_ids),
    }


def _verdict_of(truth_items, item_id):
    for item in truth_items:
        if item["id"] == item_id:
            return item["verdict"]
    raise CliError("内部不一致：未答 id %s 不在真值里" % item_id)


# ---------------------------------------------------------------- 报告：Markdown

def render_md(report):
    m = report["metrics"]
    conf = report["confusion"]
    out = []
    add = out.append

    add("# 叙事连续性评测报告（786 集 · 判分器 792）")
    add("")
    add("> 口径：`docs/evals/narrative/README.md` §五，逐字照抄；分母（100／42／40／18）一个未改。")
    add("> 只比对 `verdict` 三档：不读 `answer`、不评理由、不替被测系统下结论。")
    add("")

    hall = m["hallucination_rate"]
    add("## 〇 红线（先看这一行）")
    add("")
    add("> 🔴 **幻觉率 %s —— 红线指标**：真值「未知」%d 条里，%d 条被答成「支持」或「矛盾」。"
        % (pct(hall["numerator"], hall["denominator"]), hall["denominator"], hall["numerator"]))
    add("")

    add("## 一 输入与覆盖")
    add("")
    add("| 项 | 值 |")
    add("| --- | --- |")
    add("| 被测输出 | `%s`（sha256 `%s…`） |"
        % (report["input"]["name"], report["input"]["sha256"][:12]))
    add("| 有效判定 | %d 条（进混淆矩阵） |" % report["input"]["valid"])
    add("| 非法判定 | %d 条（有记录但 verdict 落不进三档，见 §五） |" % report["input"]["invalid"])
    add("| 未答 | %d 条（真值里有、输出里一行都没有） |" % len(report["absent_ids"]))
    add("| 其余坏行 | %d 条（未知 id／缺 id／重复 id／坏 JSON，见 §五） |"
        % report["input"]["other_error_lines"])
    add("| 覆盖 | %s |" % pct(m["coverage"]["numerator"], m["coverage"]["denominator"]))
    add("| 真值 | `%s`（%d 条，sha256 `%s…`） |"
        % (report["truth"]["name"], report["truth"]["total"], report["truth"]["sha256"][:12]))
    add("")

    add("## 二 五项口径指标（README §五）")
    add("")
    add("| 指标 | 算式（分母写死） | 读数 |")
    add("| --- | --- | --- |")
    add("| 总体准确率 | 判对条数 / 100 | **%s** |"
        % pct(m["overall_accuracy"]["numerator"], m["overall_accuracy"]["denominator"]))
    add("| 矛盾召回 | 判矛盾且真值矛盾 / 42 | %s ｜ 漏检率 %s |"
        % (pct(m["contradiction_recall"]["numerator"], m["contradiction_recall"]["denominator"]),
           pct(m["miss_rate"]["numerator"], m["miss_rate"]["denominator"])))
    add("| 支持召回 | 判支持且真值支持 / 40 | %s ｜ 误报率 %s |"
        % (pct(m["support_recall"]["numerator"], m["support_recall"]["denominator"]),
           pct(m["false_alarm_rate"]["numerator"], m["false_alarm_rate"]["denominator"])))
    add("| 未知召回 | 判未知且真值未知 / 18 | %s |"
        % pct(m["unknown_recall"]["numerator"], m["unknown_recall"]["denominator"]))
    add("| **幻觉率** | 真值未知被答成支持／矛盾 / 18 | 🔴 **%s** |"
        % pct(hall["numerator"], hall["denominator"]))
    add("")
    if m["coverage"]["numerator"] < m["coverage"]["denominator"]:
        add("> 覆盖不满 100：分母仍按 §五 固定为 100／42／40／18，缺答一律算「没判对」。")
        add("> 只跑子集时（如 README §七 的 P1 60 条）请连本表一起读，别把子集准确率当全量读数。")
        add("")

    add("## 三 三档混淆矩阵（行＝真值，列＝系统判定）")
    add("")
    add("| 真值 \\ 判定 | 支持 | 矛盾 | 未知 | 真值条数 | 表外 |")
    add("| --- | --- | --- | --- | --- | --- |")
    for i, truth in enumerate(conf["labels"]):
        row = conf["matrix"][i]
        add("| **%s** | %d | %d | %d | %d | %d |"
            % (truth, row[0], row[1], row[2], conf["row_totals"][i], conf["row_no_verdict"][i]))
    add("")
    add("> 矩阵只放「有效判定」。表外 %d 条 ＝ 未答 %d ＋ 非法判定 %d，"
        % (len(report["no_verdict_ids"]), len(report["absent_ids"]), report["input"]["invalid"]))
    add("> 所以每行「支持＋矛盾＋未知＋表外」＝该档真值条数，可自行对账。")
    add("")
    diag = sum(conf["matrix"][i][i] for i in range(len(conf["labels"])))
    add("对角和 %d ＝ 判对 %d 条 ＝ 总体准确率的分子，与 §二 同源。"
        % (diag, m["overall_accuracy"]["numerator"]))
    add("")

    add("## 四 11 类分项准确率（缺类显示 0 条，不省略）")
    add("")
    add("| 类 | 条数 | 判对 | 准确率 | 真值支持 | 真值矛盾 | 真值未知 |")
    add("| --- | --- | --- | --- | --- | --- | --- |")
    for row in report["categories"]:
        add("| %s | %d | %d | %s | %d | %d | %d |"
            % (row["name"], row["total"], row["correct"], pct(row["correct"], row["total"]),
               row["truth"]["支持"], row["truth"]["矛盾"], row["truth"]["未知"]))
    add("")
    add("> 单类 7–11 条，区间很宽：只宜看趋势、不宜排名（README §九.3）。")
    add("")

    add("## 五 输入错误逐条（%d 条）" % len(report["errors"]))
    add("")
    if report["errors"]:
        add("| 行 | id | 类型 | 说明 |")
        add("| --- | --- | --- | --- |")
        for e in report["errors"]:
            add("| %s | %s | `%s` | %s |"
                % (e["line"], e["id"] or "—", e["kind"], e["detail"].replace("|", "\\|")))
    else:
        add("无。")
    add("")
    if report["absent_ids"]:
        add("未答清单（%d 条）：%s" % (len(report["absent_ids"]), "、".join(report["absent_ids"])))
        add("")
    if report["invalid_ids"]:
        add("非法判定清单（%d 条）：%s" % (len(report["invalid_ids"]),
                                     "、".join(report["invalid_ids"])))
        add("")

    add("## 六 真值告警（%d 条，不阻塞判分、不改真值）" % len(report["truth_warnings"]))
    add("")
    if report["truth_warnings"]:
        add("| id | 真值 verdict | 该条 accept | 问题 |")
        add("| --- | --- | --- | --- |")
        for w in report["truth_warnings"]:
            add("| %s | %s | %s | %s |"
                % (w["id"], w["verdict"], "／".join(w["accept"]), w["reason"]))
        add("")
        add("> 判分处理：三档本名始终自映射，等价写法只从**自洽**的条目长出；")
        add("> 因此这几条只有答对 `verdict` 才算对，`accept` 里的它档写法不会被当成本条答案。")
        add("> **真值未改**（改 `items.jsonl` 不在 792 边界内）；要修是 786 的事，修完重跑本器即可。")
    else:
        add("无。")
    add("")

    add("## 七 等价归一（按 `accept` 长出，只取自洽条目）")
    add("")
    add("| 真值档 | 接受的等价写法 |")
    add("| --- | --- |")
    for truth in report["verdicts"]:
        add("| %s | %s |" % (truth, " ／ ".join(report["alias"][truth])))
    add("")

    add("## 八 这份报告不做什么")
    add("")
    add("- 只比对三档判定，不判断被测系统的理由、证据与推理（只比对，不判定）。")
    add("- 不写回 `items.jsonl`，不改任何真值与标注；真值不自洽只告警。")
    add("- 不给改进建议、不接 CI 打分、不做标注工具。")
    add("- 不含时间戳：同一份输入跑两次，输出逐字节一致。")
    add("")

    return "\n".join(out)


# ---------------------------------------------------------------- 报告：JSON

def render_json(report):
    return json.dumps(report, ensure_ascii=False, indent=2) + "\n"


# ---------------------------------------------------------------- 主流程

def run(pred_path, out_dir, items_path, check_counts=True, quiet=False):
    truth_items = load_truth(items_path, check_counts=check_counts)
    truth_ids = {it["id"] for it in truth_items}
    alias, alias_display, truth_warnings = build_alias_map(truth_items)
    pred_records, pred_errors = load_preds(pred_path, truth_ids, alias)

    meta = {
        "input_name": pred_path.name,
        "input_sha256": sha256_of(pred_path),
        "truth_name": items_path.name,
        "truth_sha256": sha256_of(items_path),
        "alias_display": alias_display,
        "truth_warnings": truth_warnings,
    }
    report = grade(truth_items, pred_records, pred_errors, meta)

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / REPORT_MD).write_text(render_md(report), encoding="utf-8", newline="\n")
    (out_dir / REPORT_JSON).write_text(render_json(report), encoding="utf-8", newline="\n")

    if not quiet:
        m = report["metrics"]
        print("判分完成：%s ＋ %s" % (out_dir / REPORT_MD, out_dir / REPORT_JSON))
        print("  总体准确率 %s ｜ 矛盾召回 %s ｜ 支持召回 %s ｜ 未知召回 %s ｜ 幻觉率 %s"
              % (pct(m["overall_accuracy"]["numerator"], m["overall_accuracy"]["denominator"]),
                 pct(m["contradiction_recall"]["numerator"], m["contradiction_recall"]["denominator"]),
                 pct(m["support_recall"]["numerator"], m["support_recall"]["denominator"]),
                 pct(m["unknown_recall"]["numerator"], m["unknown_recall"]["denominator"]),
                 pct(m["hallucination_rate"]["numerator"], m["hallucination_rate"]["denominator"])))
        if report["errors"]:
            print("  输入有错 %d 条（逐条见报告 §五）；退出码 1" % len(report["errors"]))
        if report["truth_warnings"]:
            print("  真值告警 %d 条（accept 与 verdict 不自洽；未改真值，见报告 §六）"
                  % len(report["truth_warnings"]))
    return report


# ---------------------------------------------------------------- 自检

def _write_lines(path, lines):
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def _grade_lines(truth_items, lines, tmpdir, label="selftest"):
    """自检也走 load_preds/grade 的真实路径：不另写一套解析，免得自检与被检代码分叉。"""
    pred_path = Path(tmpdir) / ("%s.jsonl" % label)
    _write_lines(pred_path, lines)
    alias, alias_display, truth_warnings = build_alias_map(truth_items)
    records, errors = load_preds(pred_path, {it["id"] for it in truth_items}, alias)
    meta = {"input_name": pred_path.name, "input_sha256": "0" * 64,
            "truth_name": "selftest.jsonl", "truth_sha256": "0" * 64,
            "alias_display": alias_display, "truth_warnings": truth_warnings}
    return grade(truth_items, records, errors, meta), errors


def _fixture_truth():
    return [
        {"id": "T-01", "category": "人物一致", "verdict": "支持", "accept": ["支持", "一致"]},
        {"id": "T-02", "category": "人物一致", "verdict": "矛盾", "accept": ["矛盾", "冲突"]},
        {"id": "T-03", "category": "设定矛盾", "verdict": "未知", "accept": ["未知", "UNKNOWN"]},
        {"id": "T-04", "category": "设定矛盾", "verdict": "支持", "accept": ["支持", "一致"]},
        {"id": "T-05", "category": "时间线", "verdict": "矛盾", "accept": ["矛盾", "冲突"]},
        {"id": "T-06", "category": "时间线", "verdict": "未知", "accept": ["未知", "UNKNOWN"]},
    ]


def _fixture_pred_lines():
    # 一屏踩全部分支：判对／等价写法判对／幻觉／非法判定／未知 id／缺 id／重复 id／未答
    return [
        '{"id": "T-01", "verdict": "支持"}',
        '{"id": "T-02", "verdict": "冲突"}',
        '{"id": "T-03", "verdict": "支持"}',
        '{"id": "T-04", "verdict": "有可能"}',
        '{"id": "T-06", "verdict": "UNKNOWN"}',
        '{"id": "T-99", "verdict": "支持"}',
        '{"verdict": "支持"}',
        '{"id": "T-01", "verdict": "矛盾"}',
        '',
    ]


def selftest():
    failures, checks = [], 0

    def check(name, condition, detail=""):
        nonlocal checks
        checks += 1
        if condition:
            print("  [OK] %s" % name)
        else:
            failures.append("%s｜%s" % (name, detail))
            print("  [FAIL] %s｜%s" % (name, detail))

    with tempfile.TemporaryDirectory() as tmp:
        # ① 合成夹具：分支踩全，断言精确数字
        truth = _fixture_truth()
        rep, errors = _grade_lines(truth, _fixture_pred_lines(), tmp, "fixture")
        m = rep["metrics"]
        kinds = sorted(e["kind"] for e in errors)
        check("夹具 判对 3/6", m["overall_accuracy"]["numerator"] == 3
              and m["overall_accuracy"]["denominator"] == 6, str(m["overall_accuracy"]))
        check("夹具 矛盾召回 1/2（等价写法「冲突」计入）",
              (m["contradiction_recall"]["numerator"], m["contradiction_recall"]["denominator"]) == (1, 2),
              str(m["contradiction_recall"]))
        check("夹具 支持召回 1/2", (m["support_recall"]["numerator"],
                                 m["support_recall"]["denominator"]) == (1, 2), str(m["support_recall"]))
        check("夹具 未知召回 1/2（等价写法 UNKNOWN 计入）",
              (m["unknown_recall"]["numerator"], m["unknown_recall"]["denominator"]) == (1, 2),
              str(m["unknown_recall"]))
        check("夹具 幻觉率 1/2（未知被答成支持）",
              (m["hallucination_rate"]["numerator"], m["hallucination_rate"]["denominator"]) == (1, 2),
              str(m["hallucination_rate"]))
        check("夹具 漏检率＝1−矛盾召回（1/2）",
              (m["miss_rate"]["numerator"], m["miss_rate"]["denominator"]) == (1, 2), str(m["miss_rate"]))
        check("夹具 四类输入错误都抓住",
              kinds == ["duplicate-id", "invalid-verdict", "missing-id", "unknown-id"], str(kinds))
        check("夹具 矩阵逐格精确",
              rep["confusion"]["matrix"] == [[1, 0, 0], [0, 1, 0], [1, 0, 1]],
              str(rep["confusion"]["matrix"]))
        check("夹具 表外＝未答＋非法（T-04 非法、T-05 未答）",
              rep["no_verdict_ids"] == ["T-04", "T-05"],
              str(rep["no_verdict_ids"]))
        check("夹具 非法判定不冒充未答",
              rep["invalid_ids"] == ["T-04"] and rep["absent_ids"] == ["T-05"],
              "%s / %s" % (rep["invalid_ids"], rep["absent_ids"]))
        check("夹具 矩阵行和＋表外＝真值条数",
              [sum(r) for r in rep["confusion"]["matrix"]] == [1, 1, 2]
              and rep["confusion"]["row_totals"] == [2, 2, 2]
              and [a + b for a, b in zip([sum(r) for r in rep["confusion"]["matrix"]],
                                         rep["confusion"]["row_no_verdict"])] == [2, 2, 2],
              str(rep["confusion"]))
        check("夹具 11 类齐、8 类显 0 条",
              len(rep["categories"]) == 11
              and sum(1 for c in rep["categories"] if c["total"] == 0) == 8,
              str([c["total"] for c in rep["categories"]]))

        # ①b accept 与 verdict 不自洽：只告警、判分照跑，且三档本名不被它拉偏
        warn_truth = [
            {"id": "W-01", "category": "人物一致", "verdict": "矛盾", "accept": ["未知", "UNKNOWN"]},
            {"id": "W-02", "category": "人物一致", "verdict": "未知", "accept": ["未知", "UNKNOWN"]},
        ]
        warn_lines = ['{"id": "W-01", "verdict": "未知"}', '{"id": "W-02", "verdict": "UNKNOWN"}']
        rep_w, _ = _grade_lines(warn_truth, warn_lines, tmp, "warn")
        check("不自洽 accept 只告警不崩",
              len(rep_w["truth_warnings"]) == 1 and rep_w["truth_warnings"][0]["id"] == "W-01",
              str(rep_w["truth_warnings"]))
        check("不自洽条目的它档写法不被当答案（W-01 答「未知」= 错）",
              rep_w["metrics"]["overall_accuracy"]["numerator"] == 1
              and rep_w["metrics"]["contradiction_recall"]["numerator"] == 0,
              str(rep_w["metrics"]["overall_accuracy"]) + " / "
              + str(rep_w["metrics"]["contradiction_recall"]))
        check("三档本名始终自映射（W-02 答 UNKNOWN 仍算对）",
              rep_w["metrics"]["unknown_recall"]["numerator"] == 1,
              str(rep_w["metrics"]["unknown_recall"]))

        # ② 真集自洽：拿真值当被测输出，必须 100%
        items_path = Path(__file__).resolve().parent.parent / "items.jsonl"
        real = load_truth(items_path)
        lines = [json.dumps({"id": it["id"], "verdict": it["verdict"]}, ensure_ascii=False)
                 for it in real]
        rep_real, errs_real = _grade_lines(real, lines, tmp, "real")
        check("真集自洽 100/100",
              (rep_real["metrics"]["overall_accuracy"]["numerator"],
               rep_real["metrics"]["overall_accuracy"]["denominator"]) == (100, 100),
              str(rep_real["metrics"]["overall_accuracy"]))
        check("真集自洽 幻觉率 0/18",
              (rep_real["metrics"]["hallucination_rate"]["numerator"],
               rep_real["metrics"]["hallucination_rate"]["denominator"]) == (0, 18),
              str(rep_real["metrics"]["hallucination_rate"]))
        check("真集自洽 零输入错误", errs_real == [], str(errs_real))
        check("真集自洽 11 类全非空", all(c["total"] > 0 for c in rep_real["categories"]),
              str([c["total"] for c in rep_real["categories"]]))

        # ③ 退化策略：全答支持 ⇒ 40/100、矛盾召回 0/42、幻觉率 18/18
        lines_support = [json.dumps({"id": it["id"], "verdict": "支持"}, ensure_ascii=False)
                         for it in real]
        rep_s, _ = _grade_lines(real, lines_support, tmp, "all-support")
        ms = rep_s["metrics"]
        check("全答支持 40/100", ms["overall_accuracy"]["numerator"] == 40, str(ms["overall_accuracy"]))
        check("全答支持 矛盾召回 0/42", ms["contradiction_recall"]["numerator"] == 0,
              str(ms["contradiction_recall"]))
        check("全答支持 幻觉率 18/18（红线抓得到）",
              ms["hallucination_rate"]["numerator"] == 18, str(ms["hallucination_rate"]))
        check("全答支持 未知召回 0/18", ms["unknown_recall"]["numerator"] == 0,
              str(ms["unknown_recall"]))

        # ④ 全用等价写法（accept 末位）：在全部**自洽**条目上必须都对
        lines_alias = [json.dumps({"id": it["id"], "verdict": it["accept"][-1]}, ensure_ascii=False)
                       for it in real]
        rep_a, _ = _grade_lines(real, lines_alias, tmp, "alias")
        expected_ok = len(real) - len(rep_real["truth_warnings"])
        check("等价写法在全部自洽条目上生效（%d/%d，不自洽条目除外）"
              % (expected_ok, len(real)),
              rep_a["metrics"]["overall_accuracy"]["numerator"] == expected_ok,
              "%d ≠ %d" % (rep_a["metrics"]["overall_accuracy"]["numerator"], expected_ok))

        # ⑤ 三样输出齐（红线单列／3×3／11 类），MD 与 JSON 各查一遍
        md = render_md(rep_real)
        red_lines = [ln for ln in md.splitlines() if "红线指标" in ln]
        cat_rows = sum(1 for name in CATEGORIES if ("| %s |" % name) in md)
        check("MD 三样齐：红线单列 1 行＋3×3 表头＋11 类行",
              len(red_lines) == 1 and "真值 \\ 判定" in md and cat_rows == 11,
              "红线 %d 行｜类行 %d" % (len(red_lines), cat_rows))
        payload = json.loads(render_json(rep_real))
        check("JSON 三样齐：matrix 3×3＋11 类＋hallucination_rate",
              len(payload["confusion"]["matrix"]) == 3
              and all(len(row) == 3 for row in payload["confusion"]["matrix"])
              and len(payload["categories"]) == 11
              and "hallucination_rate" in payload["metrics"],
              "matrix=%d｜cats=%d" % (len(payload["confusion"]["matrix"]),
                                      len(payload["categories"])))

        # ⑥ 可复跑：同一份输入两次渲染逐字节一致
        check("渲染两次逐字节一致（Markdown）", render_md(rep_real) == render_md(rep_real))
        check("渲染两次逐字节一致（JSON）", render_json(rep_real) == render_json(rep_real))

    if failures:
        print("SELFTEST FAIL（%d/%d）" % (len(failures), checks))
        for item in failures:
            print("  - " + item)
        return 1
    print("SELFTEST PASS（%d/%d）" % (checks, checks))
    return 0


# ---------------------------------------------------------------- 入口

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="786 叙事连续性评测集判分器（口径＝README §五；只比对，不判定）")
    parser.add_argument("--pred", help="被测系统输出 JSONL（每行 {id, verdict}）")
    parser.add_argument("--out-dir", help="输出目录（写 eval-report.md／eval-report.json）")
    parser.add_argument("--items", default=None,
                        help="真值 items.jsonl（默认取本脚本上一级的 items.jsonl）")
    parser.add_argument("--allow-count-drift", action="store_true",
                        help="真值条数不再是 100/40/42/18 时也照跑（放弃口径漂移自查）")
    parser.add_argument("--selftest", action="store_true", help="跑内置自检，不读外部输入")
    args = parser.parse_args(argv)

    try:
        if args.selftest:
            return selftest()
        if not args.pred or not args.out_dir:
            raise CliError("缺参数：--pred 与 --out-dir 都必填（--help 看用法）")
        items_path = Path(args.items) if args.items else \
            Path(__file__).resolve().parent.parent / "items.jsonl"
        report = run(Path(args.pred), Path(args.out_dir), items_path,
                     check_counts=not args.allow_count_drift)
        return 1 if report["errors"] else 0
    except CliError as exc:
        print("错误：%s" % exc, file=sys.stderr)
        return 2
    except OSError as exc:
        print("环境错误：%s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
