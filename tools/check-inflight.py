#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-inflight.py —— 读 docs/在飞.md，做跨会话占用（文件租约）相交机检。

为什么要有它
    同仓库多会话并行，最近三次真实相撞：RunEngine 改动被换回、两份都叫「第四十九批」、
    55/56 抢 BrowserScreen。根因不是 git，是没有「谁在改哪些文件」的租约。
    本脚本只做登记表核对：两两判断占用范围是否相交，不看代码语义、不改任何文件。

用法（纯标准库；不联网、不写文件；`zd.py preflight` 第 5 段会自动调用它）
    python tools/check-inflight.py                  # 表内活动行两两核对
    python tools/check-inflight.py --paths a,b      # 给定路径是否与表中活动行相交（手工开工用）
    python tools/check-inflight.py --table X.md     # 换一张表（默认 docs/在飞.md；测试用）

退出码
    0 = 无相交（或表为空/表不存在）
    1 = 有相交（打印冲突两行的批次、会话、相交路径）

相交判断（按 / 分段做**目录前缀**判断，不是字符串 startsWith：ui/a 不会误判 ui/ab）
    完全相同 ／ 一方是另一方的目录前缀 ／ 文件落在目录下 ⇒ 相交；相邻（ui/a vs ui/ab）不相交。

表规约（docs/在飞.md）
    表头：| 批次 | 承接会话 | 占用文件或目录 | 基线 commit | 开工时间 | 状态 |（至少 6 列）
    状态不含「已收」＝活动行；含「已收」＝已销账，不参与核对。
    占用一格可写多个路径（空格/逗号/顿号/分号分隔，可用反引号包路径），支持目录前缀。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:  # 与 tools/zd.py 同款：Windows 控制台 cp936 下中文容易炸，统一按 utf-8 写
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

DEFAULT_TABLE = Path(__file__).resolve().parent.parent / "docs" / "在飞.md"
_CELL_SPLIT = re.compile(r"[`'\",，、;；\s]+")  # 占用一格内的多个路径分隔符
_SEP_CELL = re.compile(r":?-{2,}:?")           # 表格分隔行 |---|---|


def norm(path: str) -> list[str]:
    """路径归一：去引号/反引号、反斜杠→斜杠、折叠多余斜杠、去 ./ 前缀，按 / 分段。"""
    p = path.strip().strip("`'\"").replace("\\", "/")
    p = re.sub(r"/{2,}", "/", p)
    if p.startswith("./"):
        p = p[2:]
    return [seg for seg in p.split("/") if seg and seg != "."]


def intersects(a: list[str], b: list[str]) -> bool:
    """按段前缀：相等 / 一方是另一方的前缀（目录含文件或目录套目录）⇒ 相交。"""
    if not a or not b:
        return False
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    return longer[: len(shorter)] == shorter


def parse_table(md: Path) -> list[dict]:
    """解析在飞表 markdown 表格 → [{batch, session, paths, status}]。"""
    rows: list[dict] = []
    if not md.is_file():
        return rows
    for raw in md.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        line = raw.strip()
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 6:
            continue
        nonempty = [c for c in cells if c]
        if nonempty and all(_SEP_CELL.fullmatch(c) for c in nonempty):
            continue  # |---|---| 分隔行
        if cells[0] == "批次":
            continue  # 表头行
        rows.append(
            {
                "batch": cells[0] or "(未写批次)",
                "session": cells[1] or "(未写会话)",
                "paths": [t for t in _CELL_SPLIT.split(cells[2]) if t],
                "status": cells[5],
            }
        )
    return rows


def active_rows(rows: list[dict]) -> list[dict]:
    return [r for r in rows if "已收" not in r["status"]]


def _common(a: list[str], b: list[str]) -> str:
    """相交路径：两者中较短的一方；若它是另一方的真前缀（目录），补一个尾斜杠示意。"""
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    s = "/".join(shorter)
    return s + "/" if len(shorter) < len(longer) else s


def check_table(rows: list[dict], table: Path) -> int:
    act = active_rows(rows)
    if not act:
        print(f"✓ 在飞表 {table} 无活动行（空表或已全部销账），无需核对")
        return 0
    conflicts: list[tuple] = []
    for i in range(len(act)):
        for j in range(i + 1, len(act)):
            ra, rb = act[i], act[j]
            for pa in ra["paths"]:
                ca = norm(pa)
                for pb in rb["paths"]:
                    cb = norm(pb)
                    if intersects(ca, cb):
                        conflicts.append((ra["batch"], ra["session"], pa, rb["batch"], rb["session"], pb, _common(ca, cb)))
    if not conflicts:
        print(f"✓ 在飞表 {len(act)} 行活动记录，两两占用无相交")
        return 0
    print(f"✗ 在飞表存在占用相交，共 {len(conflicts)} 处；相交范围不许同时开工：")
    for c in conflicts:
        print(f"  - [{c[0]}｜{c[1]}] {c[2]}  ×  [{c[3]}｜{c[4]}] {c[5]}  → 相交于 {c[6]}")
    return 1


def check_paths(rows: list[dict], given: list[str], table: Path) -> int:
    act = active_rows(rows)
    conflicts: list[tuple] = []
    for g in given:
        cg = norm(g)
        for r in act:
            for p in r["paths"]:
                cp = norm(p)
                if intersects(cg, cp):
                    conflicts.append((r["batch"], r["session"], p, "（本次要动的）", "手工给定", g, _common(cp, cg)))
    if not act:
        print(f"✓ 在飞表 {table} 无活动行（空表或已全部销账），无需核对")
        return 0
    if not conflicts:
        print(f"✓ 给定 {len(given)} 个路径与在飞表 {len(act)} 行活动记录无相交")
        return 0
    print(f"✗ 给定路径与在飞表存在占用相交，共 {len(conflicts)} 处；相交范围不许同时开工：")
    for c in conflicts:
        print(f"  - [{c[0]}｜{c[1]}] {c[2]}  ×  [{c[3]}｜{c[4]}] {c[5]}  → 相交于 {c[6]}")
    return 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="在飞表占用相交核对（纯标准库，只读）")
    ap.add_argument("--paths", action="append", default=[], metavar="A,B",
                    help="给定路径（逗号分隔，可多次给出）：检查它们是否与表中活动行相交")
    ap.add_argument("--table", default=None, metavar="FILE", help="在飞表文件（默认 docs/在飞.md）")
    args = ap.parse_args(argv)

    table = Path(args.table).resolve() if args.table else DEFAULT_TABLE
    rows = parse_table(table)

    paths: list[str] = []
    for chunk in args.paths:
        paths.extend(t for t in re.split(r"[,，;；\s]+", chunk) if t)

    if paths:
        return check_paths(rows, paths, table)
    return check_table(rows, table)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
