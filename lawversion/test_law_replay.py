# -*- coding: utf-8 -*-
"""309 §二：法条回放闸——三组数（多拦/少拦/多问）、阈值否决、离线纪律。

判据：
    ②回放能出三组数；③"少拦 ≥1"的候选 ⇒ 直接否决；⑤回放不联网、不调模型。
负例（309 派工单）：
    少拦的候选被放行 ⇒ 红；回放时联网或调模型 ⇒ 红（源码扫描盯着）。
"""
import json
import re
import tempfile
import unittest
from pathlib import Path

from _lawtest import BASELINE, CAND, LEDGER, LR, ROOT, LawversionCase, _rmtree, run_tool

SCRIPT = "tools/law-replay.py"


def _one_rule(action: str, match: dict, rid="R-t", law="L5"):
    return [{"id": rid, "law": law, "action": action, "match": match}]


def _rec(rid="x1", kind="diary_purge", rev=False):
    return {"id": rid, "kind": kind, "reversible": rev, "at": 0}


class LadderDiff(LawversionCase):
    """严格度阶梯 allow<ask<block：每上一步多拦（allow→ask 记多问），每下一步少拦。"""

    def _bucket(self, old_action, new_action):
        rules_old = _one_rule(old_action, {"kinds": ["diary_purge"]}, rid="old")
        rules_new = _one_rule(new_action, {"kinds": ["diary_purge"]}, rid="new")
        return LR.replay(rules_old, rules_new, [_rec()])["多拦"]["count"], \
            LR.replay(rules_old, rules_new, [_rec()])["少拦"]["count"], \
            LR.replay(rules_old, rules_new, [_rec()])["多问"]["count"]

    def test_tighten_is_duo_lan(self):
        for old, new in (("allow", "block"), ("ask", "block")):
            z, s, w = self._bucket(old, new)
            self.assertEqual((z, s, w), (1, 0, 0), f"{old}→{new} 应记多拦")

    def test_allow_to_ask_is_duo_wen(self):
        z, s, w = self._bucket("allow", "ask")
        self.assertEqual((z, s, w), (0, 0, 1))

    def test_loosen_is_shao_lan(self):
        """block→ask 也算少拦：把"拦"降成"问"就是放松了一道（安全只能收紧）。"""
        for old, new in (("block", "ask"), ("block", "allow"), ("ask", "allow")):
            z, s, w = self._bucket(old, new)
            self.assertEqual((z, s, w), (0, 1, 0), f"{old}→{new} 应记少拦")

    def test_same_is_silent(self):
        for a in ("allow", "ask", "block"):
            z, s, w = self._bucket(a, a)
            self.assertEqual((z, s, w), (0, 0, 0))

    def test_first_match_wins(self):
        """序即法：现行规则在前优先，候选 add_rule 排到末尾盖不掉旧法（要盖就 replace）。"""
        old = [{"id": "R-old", "law": "L5", "action": "ask", "match": {"reversible": False}}]
        new = old + [{"id": "R-new", "law": "L10", "action": "block", "match": {"reversible": False}}]
        d = LR.replay(old, new, [_rec()])
        self.assertEqual(d["多拦"]["count"], 0, "候选 add 在末尾，现行 ask 先命中，候选根本没机会开火")


class FixtureReplay(LawversionCase):
    """真 fixtures：14 条 208 形状账本，11 条在窗、3 条窗外。"""

    def setUp(self):
        self.old_rules = LR.load_rules(BASELINE)
        self.records, self.newest, self.out_n = LR.window_records(LR.load_ledger(LEDGER), 30)
        self.assertEqual((len(self.records), self.out_n), (11, 3))

    def test_tighten_counts(self):
        """r09（skill_retire）旧=放行、新=拦 ⇒ 多拦 1；现行规则没碰它，少拦/多问都是 0。"""
        new_rules = LR.apply_ops(self.old_rules, LR.load_candidate(CAND / "tighten.json"))
        d = LR.replay(self.old_rules, new_rules, self.records)
        self.assertEqual(d["多拦"]["count"], 1)
        self.assertEqual(d["少拦"]["count"], 0)
        self.assertEqual(d["多问"]["count"], 0)
        self.assertEqual(d["多拦"]["records"], ["r09"])

    def test_loosen_veto_numbers(self):
        """判据③：少拦 ≥1 直接否决。松 L8 ⇒ 窗内 r01/r08 两条日记真删从拦变放 ⇒ 少拦=2。"""
        new_rules = LR.apply_ops(self.old_rules, LR.load_candidate(CAND / "loosen.json"))
        d = LR.replay(self.old_rules, new_rules, self.records)
        self.assertEqual(d["少拦"]["count"], 2, f"实得 {d['少拦']}")
        self.assertEqual(sorted(d["少拦"]["records"]), ["r01", "r08"])
        ok, reasons, _ = LR.gate_verdict(d, 30, 1.0)
        self.assertFalse(ok)
        self.assertTrue(any("安全只能收紧" in r for r in reasons))
        self.assertTrue(any("少拦=2" in r for r in reasons))

    def test_window_keeps_replay_deterministic(self):
        """窗外账（35/40/44 天前）不参与三组数——同一份账本任何时候跑结果一致。"""
        new_rules = LR.apply_ops(self.old_rules, LR.load_candidate(CAND / "loosen.json"))
        d = LR.replay(self.old_rules, new_rules, self.records)
        self.assertNotIn("r12", d["少拦"]["records"], "35 天前的账不许混进 30 天回放")


class GateCli(LawversionCase):
    """端到端：真跑脚本、真退出码。0=达标／1=否决／2=输入坏。"""

    def test_replay_tighten_passes(self):
        r = run_tool(SCRIPT, "replay", "--candidate", str(CAND / "tighten.json"),
                     "--ledger", str(LEDGER), "--baseline", str(BASELINE))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("多拦 1", r.stdout)
        self.assertIn("少拦 0", r.stdout)
        self.assertIn("多问 0", r.stdout)
        self.assertIn("回放达标", r.stdout)

    def test_replay_loosen_red(self):
        r = run_tool(SCRIPT, "replay", "--candidate", str(CAND / "loosen.json"),
                     "--ledger", str(LEDGER), "--baseline", str(BASELINE))
        self.assertEqual(r.returncode, 1, "少拦候选必须红")
        self.assertIn("少拦 2", r.stdout)
        self.assertIn("安全只能收紧", r.stdout)

    def test_ask_cap_red(self):
        """多问 ≤ 每日 1 次（初值）：--ask-cap-per-day 0.01 ⇒ 0.3 次预算，2 条多问必红。"""
        r = run_tool(SCRIPT, "replay", "--candidate", str(CAND / "askheavy.json"),
                     "--ledger", str(LEDGER), "--baseline", str(BASELINE),
                     "--ask-cap-per-day", "0.01")
        self.assertEqual(r.returncode, 1)
        self.assertIn("多问 2", r.stdout)
        self.assertIn("多问=2 > 0.3", r.stdout)

    def test_evolve_writes_record_and_states(self):
        cases = {
            "tighten.json": ("已应用", 0, "御案排队"),
            "loosen.json": ("已回滚", 1, "退回候选区"),
            "noop.json": ("显式无变更", 0, "零改动"),
        }
        for name, (state, rc, marker) in cases.items():
            out = Path(tempfile.mkdtemp(prefix="lv309-evolve-")) / "rec.json"
            self.addCleanup(lambda p=out.parent: _rmtree(p))
            r = run_tool(SCRIPT, "evolve", "--candidate", str(CAND / name),
                         "--ledger", str(LEDGER), "--baseline", str(BASELINE), "--out", str(out))
            self.assertEqual(r.returncode, rc, f"{name}: {r.stdout}{r.stderr}")
            self.assertIn(f"结论：{state}", r.stdout)
            rec = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(rec["state"], state)
            self.assertIn(state, LR.STATES)
            self.assertIn(marker, rec["state_reason"] + str(rec["next"]))

    def test_bad_input_never_produces_state(self):
        """输入坏 ⇒ 退出码 2，stdout 里不许出现任何三态字样（不许拿暧昧状态交差）。"""
        for name in ("badop.json",):
            r = run_tool(SCRIPT, "evolve", "--candidate", str(CAND / name),
                         "--ledger", str(LEDGER), "--baseline", str(BASELINE))
            self.assertEqual(r.returncode, 2)
            self.assertIn("输入坏", r.stderr)
            for state in LR.STATES:
                self.assertNotIn(state, r.stdout)

    def test_bad_ledger_shapes_are_red(self):
        bad = Path(tempfile.mkdtemp(prefix="lv309-led-")) / "bad.jsonl"
        self.addCleanup(lambda: _rmtree(bad.parent))
        bad.write_text("{\"id\": \"x\"}\n", encoding="utf-8")  # 缺 at
        r = run_tool(SCRIPT, "replay", "--candidate", str(CAND / "tighten.json"),
                     "--ledger", str(bad), "--baseline", str(BASELINE))
        self.assertEqual(r.returncode, 2)
        self.assertIn("缺 id/at", r.stderr)
        empty = bad.parent / "empty.jsonl"
        empty.write_text("\n\n", encoding="utf-8")
        r = run_tool(SCRIPT, "replay", "--candidate", str(CAND / "tighten.json"),
                     "--ledger", str(empty), "--baseline", str(BASELINE))
        self.assertEqual(r.returncode, 2)
        self.assertIn("没账可回放", r.stderr)

    def test_ledger_keeps_208_wire_fields(self):
        """fixture 就是 208 决策记录流原样（DecisionLedger.kt 的字段名），不许私改形状。"""
        first = json.loads(LEDGER.read_text(encoding="utf-8").splitlines()[0])
        for key in ("v", "id", "kind", "group_if_known", "actor", "reason", "evidence",
                    "before", "after", "at", "session", "reversible", "rollback_ref"):
            self.assertIn(key, first, f"208 形状缺 {key}")


class OfflinePurity(LawversionCase):
    """判据⑤：回放不联网、不调模型——用源码扫描钉死，不是口头承诺。"""

    FORBIDDEN_IMPORT = (
        r"(?m)^\s*(?:import|from)\s+(urllib|socket|http|requests|httpx|aiohttp|subprocess|"
        r"ssl|ftplib|telnetlib|smtplib|xmlrpc|asyncio|openai|anthropic|google\.generativeai)\b"
    )
    FORBIDDEN_CALL = r"\bos\.system|\bpopen\s*\(|\beval\s*\(|\bexec\s*\("

    def test_neither_script_talks_or_shells_out(self):
        for rel in ("tools/law-replay.py", "tools/check-law-gates.py"):
            src = (ROOT / rel).read_text(encoding="utf-8")
            self.assertIsNone(re.search(self.FORBIDDEN_IMPORT, src), f"{rel} 出现禁用 import")
            self.assertIsNone(re.search(self.FORBIDDEN_CALL, src), f"{rel} 出现禁用调用")

    def test_law_replay_imports_are_stdlib_allowlist(self):
        src = (ROOT / "tools/law-replay.py").read_text(encoding="utf-8")
        imports = set(re.findall(r"(?m)^(?:import|from)\s+([a-z_][a-z0-9_.]*)", src))
        self.assertTrue(imports <= {"argparse", "json", "sys", "datetime", "pathlib", "__future__"},
                        f"law-replay 只许标准库解析白名单，实得 {imports}")


if __name__ == "__main__":
    unittest.main()
