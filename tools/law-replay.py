#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""law-replay.py —— 法条回放闸（第三百零九批 §二＋§三）。

为什么要有它
    《法典》只回答"应该怎样"，回答不了"这一条新法条**换在过去的账上**会怎样"。
    候选法条（308 生成或人提）进御案之前，先拿**过去 30 天的 208 账本**把新旧法条各跑一遍，
    量出三组数：

      · **多拦**（新拦旧放）——新法条比旧法条更严的地方；
      · **少拦**（新放旧拦）——新法条比旧法条**更松**的地方；
      · **多问**（新增打扰）——以前不打扰、现在要弹卡的地方。

    准入阈值（309 §二·3，初值；改阈值须御批并同步 lawversion/README）：
      **少拦 ≥1 直接否决**——安全只能收紧，不能放松；多问 ≤ 每日 1 次。
    通过 ⇒ 进御案排队（仍要人批）；不通过 ⇒ 退回候选区，附差异清单。

三态收敛（309 §三）
    每轮规则演进必须落三态之一：`已应用`／`已回滚`／`显式无变更`（写明为什么不改）。
    **不许出现"再讨论一轮"这种第四态**——evolve 子命令的结论函数只从这三个里出，
    输入坏了就报错退出（退出码 2），**绝不产出一个暧昧状态**。

回放的判定模型（诚实声明：这是**账本级**回放，不是模型级）
    法条被表达成对 208 账本记录字段的显式规则（kind／group／actor／reversible），
    按序求值、首条命中生效、无命中＝放行。严格度阶梯 allow < ask < block：
    新旧判定沿阶梯每上一步记一条多拦（allow→ask 记多问）、每下一步记一条少拦
    （**block→ask 也算少拦**——把"拦"降成"问"就是放松了一道）。
    它量不了"语义上更严"，只量"行为上更严"——这正是要机器量得准的那部分。

用法（纯标准库；不联网、不调模型、不跑 gradle）
    python tools/law-replay.py replay --candidate C.json --ledger 决策记录.jsonl [--baseline B.json]
    python tools/law-replay.py evolve --candidate C.json --ledger 决策记录.jsonl [--baseline B.json] [--out R.json]
    python tools/law-replay.py replay ... --days 30 --ask-cap-per-day 1.0 --json

退出码
    0 = 回放达标（replay）／演进结论为 已应用 或 显式无变更（evolve）
    1 = 回放不达标（含 少拦≥1 的否决）／演进结论为 已回滚
    2 = 输入坏（文件读不到／格式不对／op 指向不存在的规则）——**不产出演进状态**

账本格式
    就是 208 决策记录流的原样 JSONL（`抱朴记忆/决策记录.jsonl`，见 baopu/DecisionLedger.kt）：
    每行一个对象，必须有 `id` 与 `at`（毫秒时间戳）；kind/actor/group_if_known/reversible
    等字段按原值参与匹配。回放窗口锚定**账本里最新一条**的时间往回 30 天——
    不取挂钟：同一份账本今天跑、下个月跑，三组数必须一致（可复现，也是"不联网"的一部分）。

为什么回放不联网、不调模型（判据⑤）
    回放是准入闸：闸如果依赖外部服务或模型判断，闸本身就成了新的不可信源；
    且同一份账本必须永远量出同一组数。所以本脚本只 import 标准库解析与求值，
    lawversion/ 的测试里有一条**源码扫描**盯着这条纪律（出现网络/子进程/模型客户端 import 即红）。
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

try:  # Windows 控制台 cp936 下中文容易炸，统一按 utf-8 写（与 check-law-gates 同款）
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

_REPO = Path(__file__).resolve().parent.parent

# 准入阈值（初值；改这里必须同步 lawversion/README.md 并御批）
REPLAY_DAYS = 30
ASK_CAP_PER_DAY = 1.0
UNDERBLOCK_VETO = 1  # 少拦 ≥1 直接否决：安全只能收紧

# 三态收敛（309 §三）：evolve 的结论只能从这里出，第四态不存在
STATES = ("已应用", "已回滚", "显式无变更")

# 严格度阶梯：沿梯下移 = 放松 = 少拦；上移 = 收紧 = 多拦（allow→ask 例外记多问）
LADDER = {"allow": 0, "ask": 1, "block": 2}

# 规则 match 只认这四个字段——多一个都是打错字静默不命中，宁可红
MATCH_FIELDS = {"kinds", "groups", "actors", "reversible"}


class InputError(Exception):
    """输入坏（文件/格式/引用）。调用方统一转成退出码 2，不产出演进状态。"""


def _now_iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat(timespec="seconds")


# ---------------------------------------------------------------------------
# 规则集：现行法条（baseline）与候选（candidate + ops）
# ---------------------------------------------------------------------------

def _validate_rule(rule: dict, where: str) -> dict:
    if not isinstance(rule, dict):
        raise InputError(f"{where}：规则必须是对象，实得 {type(rule).__name__}")
    rid = rule.get("id")
    if not isinstance(rid, str) or not rid:
        raise InputError(f"{where}：规则缺 id（回滚与差异清单要靠它定位）")
    action = rule.get("action")
    if action not in LADDER:
        raise InputError(f"{where}：规则 {rid} 的 action 只能是 {sorted(LADDER)}，实得 {action!r}")
    law = rule.get("law")
    if not isinstance(law, str) or not law.strip():
        raise InputError(f"{where}：规则 {rid} 缺 law（每条规则必须挂得住法条号——L23 引用义务的同款纪律）")
    match = rule.get("match", {})
    if not isinstance(match, dict):
        raise InputError(f"{where}：规则 {rid} 的 match 必须是对象")
    unknown = set(match) - MATCH_FIELDS
    if unknown:
        raise InputError(f"{where}：规则 {rid} 的 match 有不认识的字段 {sorted(unknown)}（打错字会静默不命中，宁可红）")
    for k in ("kinds", "groups", "actors"):
        if k in match and not (isinstance(match[k], list) and all(isinstance(x, str) for x in match[k])):
            raise InputError(f"{where}：规则 {rid} 的 match.{k} 必须是字符串列表")
    if "reversible" in match and not isinstance(match["reversible"], bool):
        raise InputError(f"{where}：规则 {rid} 的 match.reversible 必须是布尔")
    return {"id": rid, "law": law, "action": action, "match": dict(match)}


def load_rules(path: "Path | None") -> list:
    """读现行规则集。path 为 None = 现状没有显式规则（全部默认放行）。"""
    if path is None:
        return []
    if not path.is_file():
        raise InputError(f"现行规则集读不到：{path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise InputError(f"现行规则集读不出（{path}：{e}）")
    if not isinstance(data, dict) or not isinstance(data.get("rules", []), list):
        raise InputError(f"现行规则集形制不对（{path}）：要 {{\"rules\": [...]}}")
    rules = [_validate_rule(r, f"现行规则集 {path.name}") for r in data.get("rules", [])]
    ids = [r["id"] for r in rules]
    if len(ids) != len(set(ids)):
        raise InputError(f"现行规则集 {path.name}：规则 id 重复（replace/remove 会指错对象）")
    return rules


def load_candidate(path: Path) -> dict:
    if not path.is_file():
        raise InputError(f"候选法条读不到：{path}")
    try:
        cand = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise InputError(f"候选法条读不出（{path}：{e}）")
    if not isinstance(cand, dict) or not isinstance(cand.get("ops", []), list):
        raise InputError(f"候选法条形制不对（{path}）：要 {{\"id\":…, \"ops\": [...]}}")
    if not isinstance(cand.get("id"), str) or not cand["id"]:
        raise InputError(f"候选法条缺 id（{path}）")
    return cand


def apply_ops(rules: list, cand: dict) -> list:
    """把候选的 ops 按序落到现行规则集的**副本**上（不碰入参）。
    add_rule 追加到末尾（现行规则优先级更高，候选不能悄悄盖掉旧法）；replace 原位换；remove 删。
    指向不存在的 target 是输入坏（退出码 2），**不是**显式无变更——静默吞掉等于假演进。"""
    out = [dict(r) for r in rules]
    ids = {r["id"] for r in out}
    for i, op in enumerate(cand.get("ops", [])):
        where = f"候选 {cand['id']} ops[{i}]"
        if not isinstance(op, dict) or "op" not in op:
            raise InputError(f"{where}：op 必须是带 op 字段的对象")
        kind = op["op"]
        if kind == "add_rule":
            rule = _validate_rule(op.get("rule"), where)
            if rule["id"] in ids:
                raise InputError(f"{where}：add_rule 的 id {rule['id']} 已存在于现行规则集（要改用 replace_rule）")
            out.append(rule)
            ids.add(rule["id"])
        elif kind == "replace_rule":
            target = op.get("target")
            if target not in ids:
                raise InputError(f"{where}：replace_rule 指向的 {target!r} 不在现行规则集里")
            rule = _validate_rule(op.get("rule"), where)
            if rule["id"] != target:
                raise InputError(f"{where}：replace_rule 的新 id（{rule['id']}）必须等于 target（{target}）——换 id 请 remove+add，让账对得上")
            out = [rule if r["id"] == target else r for r in out]  # 原位换：优先级不变
        elif kind == "remove_rule":
            target = op.get("target")
            if target not in ids:
                raise InputError(f"{where}：remove_rule 指向的 {target!r} 不在现行规则集里")
            out = [r for r in out if r["id"] != target]
            ids.discard(target)
        else:
            raise InputError(f"{where}：不认识的 op {kind!r}（只认 add_rule/replace_rule/remove_rule）")
    return out


def rules_signature(rules: list) -> list:
    """规则集的逐字段指纹（列表有序）——判断"有没有改"用，不看命中不看账本。"""
    return [(r["id"], r["law"], r["action"], json.dumps(r["match"], sort_keys=True, ensure_ascii=False)) for r in rules]


# ---------------------------------------------------------------------------
# 账本：208 决策记录流原样 JSONL
# ---------------------------------------------------------------------------

def load_ledger(path: Path) -> list:
    if not path.is_file():
        raise InputError(f"账本读不到：{path}")
    records = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
        except ValueError as e:
            raise InputError(f"账本 {path.name} 第 {lineno} 行不是合法 JSON（{e}）——坏行会让回放悄悄少看账，宁可红")
        if not isinstance(rec, dict) or "at" not in rec or "id" not in rec:
            raise InputError(f"账本 {path.name} 第 {lineno} 行缺 id/at（不是 208 形状）")
        if not isinstance(rec["at"], int):
            raise InputError(f"账本 {path.name} 第 {lineno} 行的 at 不是毫秒整数")
        records.append(rec)
    if not records:
        raise InputError(f"账本 {path.name} 一条记录都没有——没账可回放就直说，不许当空窗通过")
    return records


def window_records(records: list, days: int) -> tuple:
    """窗口 = [账本最新一条 − days 天, 最新一条]，闭区间。返回 (窗内, 最新时间戳, 窗外条数)。"""
    newest = max(r["at"] for r in records)
    lo = newest - days * 24 * 3600 * 1000
    inside = [r for r in records if lo <= r["at"] <= newest]
    return inside, newest, len(records) - len(inside)


# ---------------------------------------------------------------------------
# 判定与三组数
# ---------------------------------------------------------------------------

def rule_matches(rule: dict, rec: dict) -> bool:
    m = rule["match"]
    if "kinds" in m and rec.get("kind") not in m["kinds"]:
        return False
    if "groups" in m and rec.get("group_if_known") not in m["groups"]:
        return False
    if "actors" in m and rec.get("actor") not in m["actors"]:
        return False
    if "reversible" in m:
        rv = rec.get("reversible")
        if rv is None or bool(rv) != m["reversible"]:
            return False  # 账本没写可不可回滚 ⇒ 不命中：不许拿未知当证据
    return True


def judge_record(rules: list, rec: dict) -> str:
    """首条命中生效；无命中 = 放行（allow）。"""
    for r in rules:
        if rule_matches(r, rec):
            return r["action"]
    return "allow"


def replay(old_rules: list, new_rules: list, records: list) -> dict:
    """新旧法条各跑一遍窗内账本，产出多拦/少拦/多问三组数（每条记录恰好归一组或无差异）。"""
    diff = {
        "多拦": {"count": 0, "records": []},
        "少拦": {"count": 0, "records": []},
        "多问": {"count": 0, "records": []},
    }
    for rec in records:
        old_v = judge_record(old_rules, rec)
        new_v = judge_record(new_rules, rec)
        o, n = LADDER[old_v], LADDER[new_v]
        if n == o:
            continue
        if n < o:
            bucket = "少拦"  # 沿梯下移：block→ask 与 ask→allow 都算放松（安全只能收紧）
        elif new_v == "ask":
            bucket = "多问"  # allow→ask：新增打扰
        else:
            bucket = "多拦"  # 新=block：收紧
        diff[bucket]["count"] += 1
        diff[bucket]["records"].append(str(rec.get("id")))
    return diff


def gate_verdict(diff: dict, days: int, ask_cap_per_day: float) -> tuple:
    """准入阈值：少拦 ≥1 直接否决；多问 ≤ 每日 ask_cap_per_day 次（默认 1）。"""
    reasons = []
    under = diff["少拦"]["count"]
    if under >= UNDERBLOCK_VETO:
        reasons.append(
            f"少拦={under} ≥{UNDERBLOCK_VETO}：新法条比旧法条松了 {under} 处"
            f"（{', '.join(diff['少拦']['records'])}）——安全只能收紧，直接否决"
        )
    cap = days * ask_cap_per_day
    asked = diff["多问"]["count"]
    if asked > cap:
        reasons.append(f"多问={asked} > {cap:g}（{days} 天 × 每日 {ask_cap_per_day:g} 次）：打扰超预算")
    return (not reasons), reasons, cap


# ---------------------------------------------------------------------------
# 三态收敛（309 §三）
# ---------------------------------------------------------------------------

def conclude(ok: bool, changed: bool, reasons: list) -> tuple:
    """演进结论：只从 STATES 里出。输入坏走 InputError（退出码 2），不落第四态。"""
    if not ok:
        state = "已回滚"
        reason = "；".join(reasons) + "。退回候选区，附差异清单"
    elif not changed:
        state = "显式无变更"
        reason = "候选对现行规则集零改动（逐字段指纹相等）：没有可应用的差异，也就没有演进；本候选就此结案"
    else:
        state = "已应用"
        reason = "回放达标（少拦=0 且多问在阈值内）——进御案排队，仍要人批"
    if state not in STATES:  # 恒真守卫：三态集合若被人改宽，这里先红
        raise RuntimeError(f"演进状态 {state!r} 不在三态收敛集合 {STATES} 里（309 §三不许第四态）")
    return state, reason


# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------

def run_replay(cand_path: Path, ledger_path: Path, baseline_path: "Path | None",
               days: int, ask_cap: float, as_json: bool) -> int:
    cand = load_candidate(cand_path)
    old_rules = load_rules(baseline_path)
    new_rules = apply_ops(old_rules, cand)
    records, newest, out_n = window_records(load_ledger(ledger_path), days)
    diff = replay(old_rules, new_rules, records)
    ok, reasons, cap = gate_verdict(diff, days, ask_cap)
    result = {
        "candidate": cand["id"],
        "window_days": days,
        "anchor": _now_iso(newest),
        "records_in_window": len(records),
        "records_out_of_window": out_n,
        "diff": diff,
        "cap": {"少拦_max": UNDERBLOCK_VETO - 1, "多问_max": cap},
        "ok": ok,
        "reasons": reasons,
    }
    if as_json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if ok else 1
    print("==== 法条回放闸 ====")
    print(f"候选：{cand['id']}（ops {len(cand.get('ops', []))} 条；现行规则 {len(old_rules)} 条）")
    print(
        f"账本：{ledger_path.name}；窗口 {days} 天（锚定账本最新一条 {_now_iso(newest)}，不取挂钟）"
    )
    print(f"在窗记录 {len(records)} 条（窗外 {out_n} 条）")
    for name in ("多拦", "少拦", "多问"):
        d = diff[name]
        ids = ", ".join(d["records"]) if d["records"] else "无"
        print(f"{name} {d['count']}：{ids}")
    print(f"阈值：少拦 ≥{UNDERBLOCK_VETO} 否决（安全只能收紧）／多问 ≤ {cap:g}（{days} 天 × 每日 {ask_cap:g}）")
    if ok:
        print("✓ 结论：回放达标——可进御案排队（仍要人批）")
        return 0
    print("✗ 结论：不达标——")
    for r in reasons:
        print("   - " + r)
    return 1


def run_evolve(cand_path: Path, ledger_path: Path, baseline_path: "Path | None",
               days: int, ask_cap: float, out_path: "Path | None", as_json: bool) -> int:
    cand = load_candidate(cand_path)
    old_rules = load_rules(baseline_path)
    new_rules = apply_ops(old_rules, cand)
    records, newest, out_n = window_records(load_ledger(ledger_path), days)
    diff = replay(old_rules, new_rules, records)
    ok, reasons, _cap = gate_verdict(diff, days, ask_cap)
    changed = rules_signature(old_rules) != rules_signature(new_rules)
    state, state_reason = conclude(ok, changed, reasons)
    record = {
        "candidate": cand["id"],
        "state": state,
        "state_reason": state_reason,
        "next": {"已应用": "御案排队（仍要人批）", "已回滚": "退回候选区（308），附差异清单",
                 "显式无变更": None}[state],
        "anchor": _now_iso(newest),
        "diff": diff,
        "ok": ok,
    }
    if out_path is not None:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if as_json:
        print(json.dumps(record, ensure_ascii=False, indent=2))
    else:
        print("==== 法条演进（三态收敛）====")
        print(f"候选：{cand['id']}；账本窗口 {days} 天（在窗 {len(records)} 条，窗外 {out_n} 条）")
        print(
            f"三组数：多拦 {diff['多拦']['count']}／少拦 {diff['少拦']['count']}／多问 {diff['多问']['count']}"
        )
        print(f"结论：{state} —— {state_reason}")
        if record["next"]:
            print(f"下一步：{record['next']}")
    return 0 if state != "已回滚" else 1


def main(argv: list) -> int:
    ap = argparse.ArgumentParser(description="法条回放闸：候选法条 × 过去 30 天账本 → 多拦/少拦/多问 ＋ 三态收敛")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p, with_out: bool):
        p.add_argument("--candidate", required=True, help="候选法条 JSON（308 生成或人提）")
        p.add_argument("--ledger", required=True, help="208 决策记录 JSONL（原样，无需导出加工）")
        p.add_argument("--baseline", default=None, help="现行规则集 JSON（缺省 = 现状无显式规则，全放行）")
        p.add_argument("--days", type=int, default=REPLAY_DAYS, help=f"回放窗口天数（默认 {REPLAY_DAYS}）")
        p.add_argument("--ask-cap-per-day", type=float, default=ASK_CAP_PER_DAY,
                       help=f"多问每日上限（默认 {ASK_CAP_PER_DAY}）")
        p.add_argument("--json", action="store_true", help="输出 JSON")
        if with_out:
            p.add_argument("--out", default=None, help="演进记录落盘路径（缺省只打印）")

    common(sub.add_parser("replay", help="只回放，出三组数与达标与否"), with_out=False)
    common(sub.add_parser("evolve", help="回放 ＋ 三态收敛结论"), with_out=True)
    args = ap.parse_args(argv)

    baseline = Path(args.baseline) if args.baseline else None
    try:
        if args.cmd == "replay":
            return run_replay(Path(args.candidate), Path(args.ledger), baseline,
                              args.days, args.ask_cap_per_day, args.json)
        out = Path(args.out) if args.out else None
        return run_evolve(Path(args.candidate), Path(args.ledger), baseline,
                          args.days, args.ask_cap_per_day, out, args.json)
    except InputError as e:
        print(f"✗ 输入坏（退出码 2，不产出演进状态）：{e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
