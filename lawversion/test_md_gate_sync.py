# -*- coding: utf-8 -*-
"""309 §一：md ↔ 门禁 L 号集合必须相等——两向演练都要红。

判据（309 判据①）：md 与门禁 L 号集合不一致 ⇒ 红。
    方向一：只加 md（门禁没跟）⇒ 红；
    方向二：只加门禁（md 没跟）⇒ 红。
    演练分三层：解析器（真 md 形状）→ 纯函数（集合代数）→ 端到端（真跑脚本、真退出码）。
"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from _lawtest import CLG, CODEX_SAMPLE, ROOT, LawversionCase, _rmtree, call_main, codex_md_equal_to_gate, run_tool

SCRIPT = "tools/check-law-gates.py"


class ParseCodex(LawversionCase):
    def test_fixture_shapes(self):
        """缩样同时喂了两种条文写法＋三种不许收的写法，解析必须不多不少。"""
        ids = CLG.parse_codex_ids(CODEX_SAMPLE.read_text(encoding="utf-8"))
        self.assertEqual(ids, {"L2", "L4", "L30b"})

    def test_real_md_if_present(self):
        """真《法典》（私有侧）在本机就该被找到；CI 没有就跳过（这不是通过，是没跑——明说）。"""
        md = CLG.find_codex_md(None)
        if md is None:
            self.skipTest("本机没有《法典》md（GitHub CI 路径）——本地必须能找到，否则门禁形同虚设")
        ids = CLG.parse_codex_ids(md.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(ids), 30, f"真《法典》条文数不对劲：{sorted(ids)}")
        self.assertIn("L30b", ids, "带后缀法条（L30b）必须单独成号，不能并进 L30")
        # 安全方向：门禁账里每一条都必须在 md 里（门禁独有 = 真漂移，立即红）
        gate_ids = set(CLG.GATES) | set(CLG.SPECS)
        self.assertEqual(gate_ids - ids, set(), f"门禁账里有 md 没有的法条：{sorted(gate_ids - ids)}")

    def test_find_codex_md_prefers_explicit(self):
        self.assertEqual(CLG.find_codex_md(str(CODEX_SAMPLE)), CODEX_SAMPLE)


class SyncFunction(LawversionCase):
    """md_gate_sync 纯函数：集合代数＋文案钉死（演练同款判据在脚本 selftest 里还有一份）。"""

    def test_equal_is_silent(self):
        self.assertEqual(CLG.md_gate_sync({"L2", "L4", "L30b"}, {"L2", "L4", "L30b"}), [])

    def test_md_only_red(self):
        """方向一：只在 md 加一条 ⇒ 红，且要点名那个号。"""
        problems = CLG.md_gate_sync({"L2", "L4", "L32"}, {"L2", "L4"})
        self.assertEqual(len(problems), 1)
        self.assertIn("L32", problems[0])
        self.assertIn("md", problems[0])
        self.assertIn("同笔提交", problems[0])

    def test_gate_only_red(self):
        """方向二：只在门禁加一条 ⇒ 红，且要点名那个号。"""
        problems = CLG.md_gate_sync({"L2", "L4"}, {"L2", "L4", "L33"})
        self.assertEqual(len(problems), 1)
        self.assertIn("L33", problems[0])
        self.assertIn("门禁覆盖", problems[0])

    def test_both_sides_reported_sorted(self):
        """md 独有两条（L30b、L32）＋门禁独有两条（L2、L33），四条都要点名。"""
        problems = CLG.md_gate_sync({"L4", "L32", "L30b"}, {"L4", "L2", "L33"})
        joined = "\n".join(problems)
        for x in ("L2", "L30b", "L32", "L33"):
            self.assertIn(x, joined)
        self.assertEqual(len(problems), 4)


class SelftestDrill(LawversionCase):
    def test_selftest_green_and_contains_both_drills(self):
        r = run_tool(SCRIPT, "--selftest")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("方向一演练", r.stdout)
        self.assertIn("方向二演练", r.stdout)
        self.assertIn("自测通过", r.stdout)


class EndToEnd(LawversionCase):
    """端到端演练：真跑脚本、真退出码、真 md 文件。"""

    def test_direction_one_md_only_is_red(self):
        md = self.tmp_md(codex_md_equal_to_gate() + "| **L34** | 只加在 md 里的新条 | x | x | ❌ |\n")
        r = run_tool(SCRIPT, "--md", str(md))
        self.assertEqual(r.returncode, 1, "只加 md 不加门禁必须红（309 §一·3）")
        self.assertIn("L34 在《法典》md 里有条文", r.stdout)
        rj = run_tool(SCRIPT, "--md", str(md), "--json")
        payload = json.loads(rj.stdout)
        self.assertIn("L34", payload["md_sync"]["md_only"])
        self.assertFalse(payload["ok"])

    def test_direction_two_gate_only_is_red(self):
        md = self.tmp_md(codex_md_equal_to_gate())
        gate_dir = Path(tempfile.mkdtemp(prefix="lv309-gate-"))
        self.addCleanup(lambda: _rmtree(gate_dir))
        (gate_dir / "L99ExtraGateTest.kt").write_text(
            "package com.example.zhengdao.law\n"
            "import com.example.zhengdao.baopu.RiskMatrix\n"
            "class L99ExtraGateTest {\n"
            "    @Test fun `L99_正例_演练`() { RiskMatrix.classify(null) }\n"
            "    @Test fun `L99_负例_演练`() { }\n"
            "}\n",
            encoding="utf-8",
        )
        r = run_tool(SCRIPT, "--dir", str(gate_dir), "--spec-dir", str(ROOT / "app/src/test/java/com/example/zhengdao/law/spec"), "--md", str(md))
        self.assertEqual(r.returncode, 1, "只加门禁不加 md 必须红（309 §一·3）")
        self.assertIn("L99 在门禁覆盖", r.stdout)
        rj = run_tool(SCRIPT, "--dir", str(gate_dir), "--spec-dir", str(ROOT / "app/src/test/java/com/example/zhengdao/law/spec"), "--md", str(md), "--json")
        payload = json.loads(rj.stdout)
        self.assertIn("L99", payload["md_sync"]["gate_only"])

    def test_equal_is_green(self):
        """与门禁账同号的 md ⇒ md↔门禁这一条不添问题（其余判据照核）。"""
        md = self.tmp_md(codex_md_equal_to_gate())
        r = run_tool(SCRIPT, "--md", str(md), "--json")
        payload = json.loads(r.stdout)
        self.assertEqual(payload["md_sync"]["status"], "ok")
        self.assertTrue(payload["ok"], payload["problems"])
        self.assertEqual(r.returncode, 0)

    def test_real_state_is_honest(self):
        """不指 --md 跑真发现：本机必有真《法典》⇒ 集合不等 ⇒ 红（309 要的就是这个红）；
        CI 没私有侧 ⇒ md 不可得 ⇒ 其余判据照跑且绿（明打 ⚠/落 JSON 状态，不是静默）。"""
        rj = run_tool(SCRIPT, "--json")
        payload = json.loads(rj.stdout)
        sync = payload["md_sync"]
        if sync["status"] == "md_not_found":
            self.assertEqual(rj.returncode, 0, "CI 路径：md 不可得只告警，不替其余判据假红")
            return
        self.assertEqual(sync["status"], "drift")
        drifted = bool(sync["md_only"]) or bool(sync["gate_only"])
        self.assertEqual(rj.returncode, 1 if drifted else 0, "退出码必须与集合是否相等一致")
        self.assertGreater(len(sync["md_only"]), 0, "当前真漂移：法典 34 条 vs 门禁账 23 条，md 独有不可能是空")

    def test_md_missing_strict_is_red(self):
        """--md-strict：md 找不到也红（本地验收口径）。mock 掉发现函数模拟 CI 缺私有侧。"""
        with mock.patch.object(CLG, "find_codex_md", return_value=None):
            rc, out = call_main(CLG, ["--json", "--md-strict"])
            self.assertEqual(rc, 1)
            self.assertIn("md↔门禁漂移无法核验", out)
            payload = json.loads(out)
            self.assertEqual(payload["md_sync"]["status"], "md_not_found")
            # 非 strict：明说"没跑"，但不替其余判据假红
            rc2, out2 = call_main(CLG, ["--json"])
            self.assertEqual(rc2, 0)
            self.assertEqual(json.loads(out2)["md_sync"]["status"], "md_not_found")

    def test_json_stdout_stays_single_json_when_md_missing(self):
        with mock.patch.object(CLG, "find_codex_md", return_value=None):
            rc, out = call_main(CLG, ["--json"])
            json.loads(out)  # ⚠ 行只进人读通道，机器通道必须是干净 JSON
            self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
