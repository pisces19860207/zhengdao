# -*- coding: utf-8 -*-
"""309 §三：三态收敛——演进结论只许落 已应用/已回滚/显式无变更，不许"再讨论一轮"。

判据（309 判据④）：演进结果必落三态之一（有测试）。
负例（309 派工单）：演进结束没有三态结论 ⇒ 红。
"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from _lawtest import BASELINE, CAND, LEDGER, LR, LawversionCase, _rmtree, run_tool

SCRIPT = "tools/law-replay.py"


class StatesAreClosed(LawversionCase):
    def test_exactly_three_states(self):
        """三态集合必须恰好是这三个——多一个都是给"再讨论一轮"留门。"""
        self.assertEqual(set(LR.STATES), {"已应用", "已回滚", "显式无变更"})
        self.assertEqual(len(LR.STATES), 3)

    def test_no_fourth_state_phrase(self):
        joined = " ".join(LR.STATES)
        for forbidden in ("再讨论", "讨论一轮", "待定", "pending", "discuss"):
            self.assertNotIn(forbidden, joined)


class ConcludeTruthTable(LawversionCase):
    """conclude 的定义域只有 (ok, changed) 两比特 ⇒ 全表枚举，值域必须落在三态内。"""

    EXPECT = {
        (False, False): "已回滚",   # 不达标优先于一切：先否决，再谈别的
        (False, True): "已回滚",
        (True, False): "显式无变更",
        (True, True): "已应用",
    }

    def test_full_truth_table_lands_in_states(self):
        for (ok, changed), want in self.EXPECT.items():
            state, reason = LR.conclude(ok, changed, ["理由A"] if not ok else [])
            self.assertIn(state, LR.STATES, f"ok={ok} changed={changed} 落到了三态之外")
            self.assertEqual(state, want)
            self.assertTrue(reason)  # 每个结论都带说明——"显式无变更"尤其要写明为什么不改

    def test_veto_reason_carries_underblock(self):
        """少拦否决的结论必须把否决理由写进结论说明（退回候选区要附差异清单）。"""
        state, reason = LR.conclude(False, True, ["少拦=2 ≥1：安全只能收紧，直接否决"])
        self.assertEqual(state, "已回滚")
        self.assertIn("少拦=2", reason)
        self.assertIn("退回候选区", reason)

    def test_guard_fires_if_states_widened(self):
        """谁往 STATES 里塞第四态， conclude 的守卫当场炸——防"再讨论一轮"从集合层面溜进来。"""
        with mock.patch.object(LR, "STATES", ("已应用",)):
            with self.assertRaises(RuntimeError):
                LR.conclude(True, False, [])  # 显式无变更不在被改窄的集合里 ⇒ 守卫必须红


class EvolveLandsInState(LawversionCase):
    """端到端：三个方向的候选，结论都必须是三态之一，且没有第四态字样。"""

    def test_three_candidates_land_in_three_states(self):
        seen = set()
        for name, rc in (("tighten.json", 0), ("loosen.json", 1), ("noop.json", 0)):
            r = run_tool(SCRIPT, "evolve", "--candidate", str(CAND / name),
                         "--ledger", str(LEDGER), "--baseline", str(BASELINE))
            self.assertEqual(r.returncode, rc, f"{name}: {r.stdout}{r.stderr}")
            self.assertIn("结论：", r.stdout)
            self.assertNotIn("再讨论", r.stdout)
            landed = [s for s in LR.STATES if f"结论：{s}" in r.stdout]
            self.assertEqual(len(landed), 1, f"{name} 结论不唯一：{landed}")
            seen.add(landed[0])
        self.assertEqual(seen, set(LR.STATES), "三个候选应该把三种结论各走到一遍")

    def test_record_on_disk_is_three_state(self):
        out = Path(tempfile.mkdtemp(prefix="lv309-rec-")) / "rec.json"
        self.addCleanup(lambda: _rmtree(out.parent))
        r = run_tool(SCRIPT, "evolve", "--candidate", str(CAND / "tighten.json"),
                     "--ledger", str(LEDGER), "--baseline", str(BASELINE), "--out", str(out))
        self.assertEqual(r.returncode, 0)
        rec = json.loads(out.read_text(encoding="utf-8"))
        self.assertIn(rec["state"], LR.STATES)
        self.assertEqual(set(rec), {"candidate", "state", "state_reason", "next", "anchor", "diff", "ok"})
        self.assertEqual(rec["diff"]["少拦"]["count"], 0)

    def test_bad_input_writes_no_record(self):
        """输入坏 ⇒ 退出码 2 且不落盘——宁可没有结论，不许落一个暧昧结论。"""
        out = Path(tempfile.mkdtemp(prefix="lv309-bad-")) / "rec.json"
        self.addCleanup(lambda: _rmtree(out.parent))
        r = run_tool(SCRIPT, "evolve", "--candidate", str(CAND / "badop.json"),
                     "--ledger", str(LEDGER), "--baseline", str(BASELINE), "--out", str(out))
        self.assertEqual(r.returncode, 2)
        self.assertFalse(out.exists(), "输入坏不许落演进记录")


class ApplyOpsInvariants(LawversionCase):
    """ops 语义的负例：静默吞掉坏引用等于假演进，必须红。"""

    BASE = [{"id": "R1", "law": "L8", "action": "block", "match": {"kinds": ["diary_purge"]}}]

    def _cand(self, ops):
        return {"id": "c", "title": "t", "source": "测试", "ops": ops}

    def test_add_duplicate_id_red(self):
        with self.assertRaises(LR.InputError):
            LR.apply_ops(self.BASE, self._cand(
                [{"op": "add_rule", "rule": {"id": "R1", "law": "L8", "action": "ask", "match": {}}}]))

    def test_replace_unknown_target_red(self):
        with self.assertRaises(LR.InputError):
            LR.apply_ops(self.BASE, self._cand(
                [{"op": "replace_rule", "target": "R9",
                  "rule": {"id": "R9", "law": "L8", "action": "ask", "match": {}}}]))

    def test_replace_id_mismatch_red(self):
        with self.assertRaises(LR.InputError):
            LR.apply_ops(self.BASE, self._cand(
                [{"op": "replace_rule", "target": "R1",
                  "rule": {"id": "R2", "law": "L8", "action": "ask", "match": {}}}]))

    def test_unknown_op_red(self):
        with self.assertRaises(LR.InputError):
            LR.apply_ops(self.BASE, self._cand([{"op": "merge_rule", "target": "R1"}]))

    def test_rule_without_law_red(self):
        """L23 引用义务的同款纪律：规则必须挂得住法条号。"""
        with self.assertRaises(LR.InputError):
            LR.apply_ops([], self._cand(
                [{"op": "add_rule", "rule": {"id": "R2", "action": "ask", "match": {}}}]))

    def test_add_appends_and_preserves_priority(self):
        out = LR.apply_ops(self.BASE, self._cand(
            [{"op": "add_rule", "rule": {"id": "R2", "law": "L10", "action": "block", "match": {}}}]))
        self.assertEqual([r["id"] for r in out], ["R1", "R2"])


class RulesSignature(LawversionCase):
    def test_identical_is_equal_regardless_of_source_file(self):
        base = LR.load_rules(BASELINE)
        noop_new = LR.apply_ops(base, {"id": "c", "title": "t", "source": "s", "ops": [
            {"op": "replace_rule", "target": "R3-L11-auto-skill-ask",
             "rule": {"id": "R3-L11-auto-skill-ask", "law": "L11", "action": "ask",
                      "match": {"kinds": ["skill_auto_write"]}}}]})
        self.assertEqual(LR.rules_signature(base), LR.rules_signature(noop_new))

    def test_order_matters(self):
        a = [{"id": "R1", "law": "L8", "action": "block", "match": {}},
             {"id": "R2", "law": "L5", "action": "ask", "match": {}}]
        b = list(reversed(a))
        self.assertNotEqual(LR.rules_signature(a), LR.rules_signature(b))


if __name__ == "__main__":
    unittest.main()
