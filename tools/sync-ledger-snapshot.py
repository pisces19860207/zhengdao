#!/usr/bin/env python3
"""把仓内活账单向同步成私有只读快照（**不合并、不双向**）。

用法：
    python tools/sync-ledger-snapshot.py            # 同步（同步前先跑账本检测，红了就拒绝）
    python tools/sync-ledger-snapshot.py --check    # 只比对，不写盘（落后就 rc=1）
    python tools/sync-ledger-snapshot.py --dest <路径>

规则（2026-10-26 起因：多会话合并账本时"并列保留"过一次，2691→5375 行）：
  1. **唯一活账在仓内** `docs/FEATURE-LEDGER.md`；私有那份只是只读快照；
  2. 同步前必须过 `tools/check-ledger-dupe.py`（章节/重复行），**红了不许同步**；
  3. 快照头部写基线 `origin/main = <sha>` 与时间，**每次覆盖**——落后一眼可见；
  4. 单向覆盖（读仓内 → 写私有），**永不做双向合并**。

私有快照默认路径：`../_zd-private/docs/FEATURE-LEDGER.md`（相对仓库根）。
"""
from __future__ import annotations

import argparse
import datetime as dt
import io
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "FEATURE-LEDGER.md"
DEFAULT_DEST = ROOT.parent / "_zd-private" / "docs" / "FEATURE-LEDGER.md"
HEADER_RE = re.compile(r"^> \*\*私有快照（只读）\*\*.*$", re.M)
TITLE = "# 功能台账（FEATURE-LEDGER）"


def git(*args: str) -> str:
    r = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout.strip() if r.returncode == 0 else ""


def baseline() -> str:
    sha = git("rev-parse", "--short", "origin/main") or git("rev-parse", "--short", "HEAD")
    if not sha:
        return "unknown"
    dirty = git("status", "--porcelain", "--", "docs/FEATURE-LEDGER.md")
    return sha + ("（工作树有未提交改动）" if dirty else "")


def dupe_ok() -> tuple[bool, str]:
    r = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "check-ledger-dupe.py"), str(SRC)],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    out = (r.stdout or "").strip().splitlines()
    tail = out[-1] if out else ""
    return r.returncode == 0, tail


def snapshot_body(text: str, sha: str) -> str:
    """把仓内账本改成快照正文：换掉标题后的快照说明行，其余逐字保留。"""
    lines = text.split("\n")
    if lines and lines[0].startswith("# "):
        lines[0] = TITLE
    stamp = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
    note = (
        f"> **私有快照（只读）**：由 `tools/sync-ledger-snapshot.py` 从仓内 `docs/FEATURE-LEDGER.md` 单向同步，"
        f"基线 `origin/main = {sha}`（{stamp}）。**唯一活账在仓内**；本副本只供离线查阅，改它没用。"
    )
    # 去掉旧的说明行（如果有），插在标题后的第一个空行位置
    body = [l for l in lines if not HEADER_RE.match(l)]
    out: list[str] = []
    inserted = False
    for i, l in enumerate(body):
        out.append(l)
        if not inserted and i == 0:
            out.append("")
            out.append(note)
            inserted = True
    return "\n".join(out)


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description="仓内活账 → 私有只读快照（单向）")
    ap.add_argument("--dest", type=Path, default=DEFAULT_DEST)
    ap.add_argument("--check", action="store_true", help="只比对是否落后，不写盘")
    args = ap.parse_args()

    if not SRC.is_file():
        print(f"[ledger-sync] 找不到活账：{SRC}")
        return 2
    dest: Path = args.dest
    if not dest.parent.is_dir():
        print(f"[ledger-sync] 目标目录不存在：{dest.parent}（私有仓没克隆？用 --dest 指定）")
        return 2

    src_text = io.open(SRC, encoding="utf-8", newline="").read()
    sha = baseline()
    want = snapshot_body(src_text, sha)

    if args.check:
        if not dest.is_file():
            print(f"[ledger-sync] 快照不存在：{dest} -> 落后")
            return 1
        have = io.open(dest, encoding="utf-8", newline="").read()
        # 比正文（忽略快照说明行与行尾差异）
        norm = lambda s: [l.rstrip() for l in s.split("\n") if not HEADER_RE.match(l)]
        if norm(have) == norm(want):
            print(f"[ledger-sync] 一致（基线 {sha}）")
            return 0
        print(f"[ledger-sync] 落后：快照与仓内活账不一致（当前基线 {sha}）-> 跑一次不带 --check 的同步")
        return 1

    ok, tail = dupe_ok()
    if not ok:
        print(f"[ledger-sync] 拒绝同步：账本检测未过（{tail}）——先修重复，再同步")
        return 1

    io.open(dest, "w", encoding="utf-8", newline="").write(want)
    n = want.count("\n") + 1
    print(f"[ledger-sync] 已同步 → {dest}")
    print(f"[ledger-sync] 基线 origin/main = {sha}；{n} 行；检测：{tail or 'OK'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
