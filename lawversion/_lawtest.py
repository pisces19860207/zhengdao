# -*- coding: utf-8 -*-
"""lawversion 测试场的公共支撑（第三百零九批）。

⚠ 本文件名以 `_` 开头，**曾被 .gitignore 的 `_*.py` 规则挡在提交之外**（309 验收时真踩过：
  辅助文件没进库 ⇒ 合并后主树 `lawversion/run_tests.py` 直接 ImportError）。现在
  `.gitignore` 里对本路径有显式例外 `!lawversion/_lawtest.py`；删除/改名之前先想清楚。

这里只放"三个测试文件共用"的路径、模块加载与子进程调用，不放任何判据——
判据在 `tools/check-law-gates.py` 的 selftest 与三个 test_*.py 里。
"""
import contextlib
import importlib.util
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# 仓根（本文件在 <repo>/lawversion/ 下）
ROOT = Path(__file__).resolve().parent.parent
FIX = ROOT / "lawversion" / "fixtures"
BASELINE = FIX / "baseline.json"
LEDGER = FIX / "ledger-sample.jsonl"
CAND = FIX / "candidates"
CODEX_SAMPLE = FIX / "codex-sample.md"


def _load(name: str, rel: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / rel)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# 两个被反复导入的模块（测试直接调它们的纯函数/常量，不重复起进程）
LR = _load("lv309_law_replay", "tools/law-replay.py")
CLG = _load("lv309_check_law_gates", "tools/check-law-gates.py")


def _rmtree(path):
    shutil.rmtree(str(path), ignore_errors=True)


def run_tool(script: str, *args):
    """跑仓内脚本（真进程、真退出码）；中文输出统一按 utf-8 读，本机/CI 一致。"""
    return subprocess.run(
        [sys.executable, str(ROOT / script), *[str(a) for a in args]],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def call_main(mod, argv):
    """进程内调 `mod.main(argv)` 并取回 stdout（--json 的机器通道必须干净）。"""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = mod.main(list(argv))
    return rc, buf.getvalue()


def codex_md_equal_to_gate() -> str:
    """造一份与门禁账**同号**的《法典》md（方向一/方向二演练的对照组）。"""
    ids = sorted(set(CLG.GATES) | set(CLG.SPECS), key=CLG.law_key)
    rows = "\n".join("| **%s** | 演练条款 | 演练后果 |" % i for i in ids)
    return "# 演练用《法典》\n\n| 条 | 文 | 则 |\n|---|---|---|\n" + rows + "\n"


class LawversionCase(unittest.TestCase):
    """测试基类：只提供临时文件，不提供判据。"""

    def tmp_md(self, text: str) -> Path:
        d = Path(tempfile.mkdtemp(prefix="lv309-md-"))
        self.addCleanup(lambda: _rmtree(d))
        p = d / "codex.md"
        p.write_text(text, encoding="utf-8")
        return p
