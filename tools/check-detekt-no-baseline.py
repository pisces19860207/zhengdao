#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-detekt-no-baseline.py —— 324 门禁：detekt 的 baseline 冻结机制**永久退役**。

324（2026-10-31）账目修正：10-19 首跑冻结的 171 条里仍有一批因签名未变而持续匹配，
双跑对照实测它压着 **101 条真读数**（引擎 64／UI 冻结区 37）⇒ baseline 文件与指到它的
CLI 参数整条删除，预算用无参数真读数重记（raised_reason 写明「口径修正，非放宽」）。

本闸保证它**不会悄悄回来**：仓库里任何 build 脚本或 config 文件只要再出现
把 baseline 文件喂给 detekt 的那个 CLI 参数字面量（两个短横连 baseline，即
"--" + "baseline"），立刻红。扫描范围：

  * 全仓所有 `*.gradle` / `*.gradle.kts`（跳过 .git / build / .gradle 目录）；
  * `config/` 下全部文件（README、yml、json 都算——文档里也不许留，留了就会被人照抄回去）。

不扫 `.github/`（CI 不直接碰 detekt 参数，注入点在 build 脚本）；不扫 tools/ 自身与
历史存档（`证道设计报告/` 里的原文存档是证据，不是配置）。

用法（CI 与本地同一条命令）：

    python3 tools/check-detekt-no-baseline.py     # 0=干净，1=发现字面量
"""

from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 拆开写只是防本文件自我命中未来可能的目录级扫描；语义就是「--baseline」。
FORBIDDEN = "--" + "baseline"
SKIP_DIRS = {".git", "build", ".gradle", "node_modules"}
BUILD_EXTS = (".gradle", ".gradle.kts")


def iter_targets():
    """yield 需要扫描的文件绝对路径：全仓 build 脚本 + config/ 全部。"""
    config_root = os.path.join(ROOT, "config")
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = os.path.join(dirpath, name)
            if os.path.abspath(path).startswith(config_root + os.sep):
                yield path
            elif name.endswith(BUILD_EXTS):
                yield path


def main() -> int:
    hits = []
    for path in iter_targets():
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for lineno, line in enumerate(fh, 1):
                    if FORBIDDEN in line:
                        hits.append((os.path.relpath(path, ROOT), lineno, line.strip()[:120]))
        except OSError as exc:
            print("::error title=detekt no-baseline 门禁::读不了 %s：%s" % (path, exc))
            return 1

    if hits:
        print(
            "::error title=detekt no-baseline 门禁::发现 %d 处 baseline 参数字面量——"
            "324 已把它永久退役（它压着 101 条真读数＝藏账）。要接受新增违规请走预算闸"
            " --allow-raise，不许把 baseline 请回来。" % len(hits)
        )
        for rel, lineno, text in hits:
            print("::error file=%s,line=%d::%s: %s" % (rel, lineno, rel, text))
        return 1

    print("[detekt-no-baseline] 通过：build 脚本与 config 里没有 baseline 参数字面量（324 门禁）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
