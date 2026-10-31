#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""lawversion 测试入口（纯标准库 unittest，不联网、不调模型、不跑 gradle）。

    python lawversion/run_tests.py            # 全部
    python lawversion/run_tests.py -k replay  # 按关键字筛（unittest 原生参数透传）

红 = 有判据失守；绿 = 三批判据（md↔门禁两向／回放三组数与否决／三态收敛）全部演练过且守得住。
"""

import sys
import unittest
from pathlib import Path

sys.dont_write_bytecode = True  # 别往 tools/ 与本目录落 __pycache__

if __name__ == "__main__":
    here = Path(__file__).resolve().parent
    suite = unittest.defaultTestLoader.discover(start_dir=str(here), pattern="test_*.py")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
