#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""安全清工作树：先抢救交付报告，再删树（2026-10-20 蓝总管自做）。

背景：62 批、85 批两次把工作树里的 `证道设计报告/*.md` 跟着目录一起删了。
教训不能只写在账上——所以把「先移出报告、再删树」做成一个脚本，删树只走它。

用法：
  python tools/safe-worktree-remove.py <worktree>                 # 默认报告目录：<父目录>/zhengdao/证道设计报告
  python tools/safe-worktree-remove.py <worktree> --reports-dir D:\...\证道设计报告
  python tools/safe-worktree-remove.py <worktree> --dry-run        # 只列会拷/会删什么
  python tools/safe-worktree-remove.py --selftest                  # 自测（临时目录，不碰真实工作树）

行为：
1. 扫 `<worktree>/证道设计报告/` 与工作树根下文件名含「交付」的 .md；
2. 拷到 `--reports-dir`；**同名同内容跳过，同名不同内容另存 `*.conflict-<时间戳>.md`**（绝不静默覆盖）；
3. 任何一份拷失败 ⇒ **拒绝删树**，非 0 退出；
4. 全部拷完才 `git worktree remove --force`；失败走 `rd /s /q \\?\`（Windows）或 `rm -rf` 兜底；
5. 删完复查路径确实消失，否则非 0。
"""
from __future__ import annotations

import argparse
import datetime as _dt
import os
import shutil
import subprocess
import sys
from pathlib import Path


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

REPORT_DIRNAME = "证道设计报告"


def _md_files(worktree: Path) -> list[Path]:
    out: list[Path] = []
    reports = worktree / REPORT_DIRNAME
    if reports.is_dir():
        out += sorted(p for p in reports.rglob("*.md") if p.is_file())
    for p in sorted(worktree.glob("*.md")):
        if p.is_file() and "交付" in p.name and p not in out:
            out.append(p)
    return out


def _same(a: Path, b: Path) -> bool:
    if not b.exists():
        return False
    return a.read_bytes() == b.read_bytes()


def rescue(worktree: Path, reports_dir: Path, dry_run: bool = False) -> int:
    """把报告拷出来；返回拷成功数，失败抛 RuntimeError。"""
    files = _md_files(worktree)
    if not files:
        print("[worktree] 没有要抢救的报告文件")
        return 0
    reports_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    for src in files:
        rel = src.relative_to(worktree)
        dst = reports_dir / rel.name
        if _same(src, dst):
            print(f"[worktree] 已存在同内容，跳过：{dst.name}")
            continue
        if dst.exists():
            stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
            alt = reports_dir / f"{dst.stem}.conflict-{stamp}{dst.suffix}"
            print(f"[worktree] ⚠ 同名不同内容，另存：{alt.name}（原 {dst.name} 未动）")
            dst = alt
        if dry_run:
            print(f"[worktree] [dry-run] 会拷：{rel} → {dst}")
        else:
            shutil.copy2(src, dst)
            print(f"[worktree] 拷出：{rel} → {dst}")
        copied += 1
    return copied


def remove_worktree(worktree: Path, dry_run: bool = False) -> None:
    if dry_run:
        print(f"[worktree] [dry-run] 会删：{worktree}")
        return
    try:
        r = subprocess.run(
            ["git", "worktree", "remove", "--force", str(worktree)],
            capture_output=True,
            text=True,
        )
        if r.returncode != 0:
            print(f"[worktree] git worktree remove 失败：{(r.stderr or '').strip()[:200]}")
    except Exception as e:  # noqa: BLE001
        print(f"[worktree] git worktree remove 异常：{e.__class__.__name__}")

    if worktree.exists():
        # Windows 长路径：cmd 的 \\?\ 前缀；其它平台用 shutil.rmtree
        if os.name == "nt":
            subprocess.run(["cmd", "/c", "rd", "/s", "/q", f"\\\\?\\{worktree}"], capture_output=True)
        else:
            shutil.rmtree(worktree, ignore_errors=True)
    if worktree.exists():
        print(f"[worktree] ✗ 删不掉：{worktree}", file=sys.stderr)
        raise SystemExit(1)
    # 清掉 git 里残留的 worktree 元数据
    subprocess.run(["git", "worktree", "prune"], capture_output=True)
    print(f"[worktree] 已删：{worktree}")


def selftest() -> int:
    import tempfile

    with tempfile.TemporaryDirectory(prefix="wt-selftest-") as td:
        root = Path(td)
        wt = root / "wt-x"
        (wt / REPORT_DIRNAME).mkdir(parents=True)
        (wt / REPORT_DIRNAME / "报告-交付-2026-01-01.md").write_text("hello", encoding="utf-8")
        (wt / "根级-交付-2026-01-01.md").write_text("root", encoding="utf-8")
        reports = root / "reports"
        n = rescue(wt, reports)
        assert n == 2, n
        assert (reports / "报告-交付-2026-01-01.md").read_text(encoding="utf-8") == "hello"
        assert (reports / "根级-交付-2026-01-01.md").read_text(encoding="utf-8") == "root"
        # 再跑一次：同内容跳过，不重复拷
        assert rescue(wt, reports) == 0
        # 同名不同内容：另存冲突副本
        (wt / REPORT_DIRNAME / "报告-交付-2026-01-01.md").write_text("changed", encoding="utf-8")
        assert rescue(wt, reports) == 1
        assert (reports / "报告-交付-2026-01-01.md").read_text(encoding="utf-8") == "hello"
        conflicts = list(reports.glob("报告-交付-2026-01-01.conflict-*.md"))
        assert len(conflicts) == 1 and conflicts[0].read_text(encoding="utf-8") == "changed"
        # 没有报告时也应返回 0
        (wt / REPORT_DIRNAME / "报告-交付-2026-01-01.md").write_text("hello", encoding="utf-8")
        shutil.rmtree(wt / REPORT_DIRNAME)
        (wt / "根级-交付-2026-01-01.md").unlink()
        assert rescue(wt, reports) == 0
    print("[worktree] ✓ selftest 通过（拷出/跳过/冲突另存/空表）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="先抢救报告再删工作树")
    ap.add_argument("worktree", nargs="?", help="工作树路径")
    ap.add_argument("--reports-dir", type=Path, help="报告落点（默认 <父目录>/zhengdao/证道设计报告）")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.worktree:
        ap.error("缺 worktree 路径")

    wt = Path(args.worktree).resolve()
    if not wt.is_dir():
        print(f"[worktree] 不是目录：{wt}", file=sys.stderr)
        return 2
    reports = args.reports_dir
    if reports is None:
        reports = wt.parent / "zhengdao" / REPORT_DIRNAME
    reports = reports.resolve()
    try:
        n = rescue(wt, reports, dry_run=args.dry_run)
    except Exception as e:  # noqa: BLE001
        print(f"[worktree] ✗ 报告抢救失败（拒绝删树）：{e}", file=sys.stderr)
        return 1
    print(f"[worktree] 抢救 {n} 个报告 → {reports}")
    remove_worktree(wt, dry_run=args.dry_run)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
