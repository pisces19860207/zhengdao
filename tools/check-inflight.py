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
    python tools/check-inflight.py --selftest       # 跑内置判据自测（385 批），不读在飞表

退出码
    0 = 无相交（或表为空/表不存在）
    1 = 有相交（打印冲突两行的批次、会话、相交路径）

相交判断（按 / 分段做**目录前缀**判断，不是字符串 startsWith：ui/a 不会误判 ui/ab）
    比较前先归一（2026-11-02 · 385 批）：去包裹符与 *强调*、截掉（注解尾巴、反斜杠→/、
    折叠 //、丢 ./ 与 . 段、去前导仓库名（zhengdao/、zhengdao-term/）、去尾随 /。
    修掉盲区：一行写 zhengdao/tools/a.py、另一行写 tools/a.py，此前判「不相交」＝假绿
    （352 那行的 zhengdao/tools/ 就是这么看不见的）。比较用归一键，**报告仍显原文**。
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

_REPO = Path(__file__).resolve().parent.parent
# 2026-10-28：租约表已移到私有侧（在途批次名与文件级租约不公开）。
# 优先读私有侧；不存在（CI / 他人克隆）再回落到公开仓占位。
_PRIVATE = _REPO.parent / "_zd-private" / "docs" / "在飞.md"
DEFAULT_TABLE = _PRIVATE if _PRIVATE.is_file() else _REPO / "docs" / "在飞.md"
_CELL_SPLIT = re.compile(r"[`'\",，、;；\s]+")  # 占用一格内的多个路径分隔符
_SEP_CELL = re.compile(r":?-{2,}:?")           # 表格分隔行 |---|---|
_REPO_NAMES = {"zhengdao", "zhengdao-term"}    # 前导仓库名（385 批）：zhengdao/tools/x ≡ tools/x


def norm(path: str) -> list[str]:
    """路径归一＝比较键（报告仍显原文）：去包裹符与 *强调*、截掉（注解尾巴、反斜杠→/、
    折叠 //、丢 ./ 与 . 段、去前导仓库名段、空段不留（＝去尾随 /）。
    385 批注记：中文括号紧贴路径（tools/x.py（只许加…））曾整段成一个不可分前缀、
    目录整片拦不住（在飞表 2026-10-22 注记），故把（…）当注解尾巴截掉；
    注解碎片归一成空 ⇒ 不参与比较（也不会被当成整仓租约）。"""
    p = path.strip().strip("`'\"*").replace("\\", "/")
    p = p.split("（", 1)[0]
    p = re.sub(r"/{2,}", "/", p)
    segs = [seg for seg in p.split("/") if seg and seg != "."]
    while segs and segs[0] in _REPO_NAMES:
        segs.pop(0)
    return segs


def intersects(a: list[str], b: list[str]) -> bool:
    """按段前缀：相等 / 一方是另一方的前缀（目录含文件或目录套目录）⇒ 相交。"""
    if not a or not b:
        return False
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    return longer[: len(shorter)] == shorter


def parse_lines(lines: list[str]) -> list[dict]:
    """解析在飞表 markdown 行 → [{batch, session, paths, status}]（selftest 免落盘复用）。"""
    rows: list[dict] = []
    for raw in lines:
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


def parse_table(md: Path) -> list[dict]:
    if not md.is_file():
        return []
    return parse_lines(md.read_text(encoding="utf-8-sig", errors="replace").splitlines())


def active_rows(rows: list[dict]) -> list[dict]:
    return [r for r in rows if "已收" not in r["status"]]


def _common(a: list[str], b: list[str]) -> str:
    """相交路径：两者中较短的一方；若它是另一方的真前缀（目录），补一个尾斜杠示意。"""
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    s = "/".join(shorter)
    return s + "/" if len(shorter) < len(longer) else s


def find_conflicts(act: list[dict]) -> list[tuple]:
    """活动行两两求相交（比较用归一键；冲突元组保留两行原文 ⇒ 报告显原文）。"""
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
    return conflicts


def check_table(rows: list[dict], table: Path) -> int:
    act = active_rows(rows)
    if not act:
        print(f"✓ 在飞表 {table} 无活动行（空表或已全部销账），无需核对")
        return 0
    conflicts = find_conflicts(act)
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


def selftest() -> int:
    """385 批判据自测（纯内存、不读表不落盘）：归一后比较，报告仍显原文。"""
    ok = True

    def expect(cond: bool, msg: str) -> None:
        nonlocal ok
        ok = ok and bool(cond)
        print(f"  {'✓' if cond else '✗'} {msg}")

    print("自测（385 批：在飞表路径归一）")
    # 判据①②：一行带前导仓库名、一行不带 ⇒ 必须判相交（此前是假绿）
    expect(intersects(norm("zhengdao/tools/a.py"), norm("tools/a.py")), "zhengdao/tools/a.py × tools/a.py ⇒ 相交")
    expect(intersects(norm("zhengdao/app/x.kt"), norm("app/x.kt")), "zhengdao/app/x.kt × app/x.kt ⇒ 相交")
    expect(intersects(norm("zhengdao-term/x.py"), norm("x.py")), "zhengdao-term/x.py × x.py ⇒ 相交")
    # 判据③：真不同文件不误报；相邻名不误判（老规矩不回退）
    expect(not intersects(norm("zhengdao/tools/a.py"), norm("tools/b.py")), "zhengdao/tools/a.py × tools/b.py ⇒ 不相交")
    expect(not intersects(norm("tools/a.py"), norm("tools/ab.py")), "tools/a.py × tools/ab.py ⇒ 不相交")
    expect(not intersects(norm("ui/a"), norm("ui/ab")), "ui/a × ui/ab ⇒ 不相交")
    # 归一细节：./、重复斜杠、尾随 /、反斜杠、包裹符、*强调*、（注解尾巴 ⇒ 归一后同键
    for raw in ("./tools/a.py", "tools//a.py", "tools/a.py/", "tools\\a.py", "`tools/a.py`", "**tools/a.py", "tools/a.py（只改输入）"):
        expect(norm(raw) == norm("tools/a.py"), f"{raw} ≡ tools/a.py")
    # 注解尾巴杂物不得被当成整仓租约
    expect(not intersects(norm("（config/detekt/"), norm("config/detekt/detekt.yml")), "（注解杂物 ⇒ 不相交（不得当整仓）")
    # 表级（判据④⑤内存版）：一行 zhengdao/tools/ 一行 tools/… ⇒ 判相交，且冲突行保留两行原文
    rows = parse_lines([
        "| 批次 | 承接会话 | 占用文件或目录 | 基线 commit | 开工时间 | 状态 |",
        "|---|---|---|---|---|---|",
        "| 352 | zcode 丁 | zhengdao/tools/ | 89d3db4a | 2026-11-01 | 在飞 |",
        "| 364 | qoder 戊 | tools/check-criteria-gates.py | 09d0359c | 2026-11-02 | 在飞 |",
        "| 385 | zcode 癸 | zhengdao/tools/check-inflight.py | 09d0359c | 2026-11-02 | 在飞 |",
    ])
    conflicts = find_conflicts(active_rows(rows))
    triples = {(c[0], c[2], c[5]) for c in conflicts}
    expect(("352", "zhengdao/tools/", "tools/check-criteria-gates.py") in triples,
           "表级：352（zhengdao/tools/）× 364（tools/…）判相交——352 那行看得见")
    expect(("352", "zhengdao/tools/", "zhengdao/tools/check-inflight.py") in triples,
           "表级：352 × 385（zhengdao/tools/check-inflight.py）判相交")
    expect(len(conflicts) == 2, f"表级：恰好 2 处冲突（实测 {len(conflicts)}）")
    print("自测" + ("：全部通过" if ok else "：存在失败"))
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="在飞表占用相交核对（纯标准库，只读）")
    ap.add_argument("--paths", action="append", default=[], metavar="A,B",
                    help="给定路径（逗号分隔，可多次给出）：检查它们是否与表中活动行相交")
    ap.add_argument("--table", default=None, metavar="FILE", help="在飞表文件（默认 docs/在飞.md）")
    ap.add_argument("--selftest", action="store_true", help="跑内置判据自测（385 批），不读在飞表")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

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
