#!/usr/bin/env python3
"""账本重复检测（防"合并时并列保留双方"）。

用法：
    python tools/check-ledger-dupe.py            # 查 docs/FEATURE-LEDGER.md
    python tools/check-ledger-dupe.py <path>

判据（错误 => rc=1）：
  1. 章节头重复：`## ` / `#### ` 同一标题出现 >1 次（这是"整段被重复"的铁证）；
  2. 完全重复行：同一段里，编号行（`| 123 |` / `| 173x |`）**内容逐字相同**出现 >1 次；
  3. 文件行数 > 3200（正常约 2600–3000，超了说明可能又拼了一份）。

警告（rc 仍为 0，只打印）：
  - 同一段里同编号但内容不同（历史长表里可能有合法的多表复用）；
  - 连续 3 行以上空行。

背景：2026-10-26 发现 `docs/FEATURE-LEDGER.md` 被 1a084009 的合并并列保留了两份（5000 行、63 个章节头），
本脚本就是为这类事故设的闸。纯标准库，无依赖。
"""
import hashlib
import re
import sys
from collections import Counter, OrderedDict

DEFAULT = "docs/FEATURE-LEDGER.md"
MAX_LINES = 3200
HEAD_RE = re.compile(r"^(#{2,4})\s")
ROW_RE = re.compile(r"^\|\s*([0-9]{1,4}[a-z]{0,2})\s*\|")


def norm(line: str) -> str:
    return line.rstrip("\r\n").strip()


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    try:
        raw = open(path, encoding="utf-8", newline="").read()
    except FileNotFoundError:
        print(f"[ledger-dupe] 找不到 {path}")
        return 1

    lines = [norm(l) for l in raw.split("\n")]
    errors, warns = [], []

    # 1) 章节头重复
    heads = [l for l in lines if HEAD_RE.match(l)]
    dup_heads = [(h, n) for h, n in Counter(heads).items() if n > 1]
    for h, n in dup_heads:
        errors.append(f"章节头重复 x{n}: {h[:70]}")
    if not heads:
        errors.append("没检测到任何章节头（## / ####），文件可能已损坏")

    # 2) 同一段内完全重复的编号行 + 同号不同内容（警告）
    cur = None
    exact = Counter()
    by_id = {}
    for l in lines:
        if HEAD_RE.match(l):
            cur = l
            continue
        m = ROW_RE.match(l)
        if not m or cur is None:
            continue
        rid = m.group(1)
        digest = hashlib.sha256(l.encode()).hexdigest()[:12]
        exact[(cur, rid, digest)] += 1
        by_id.setdefault((cur, rid), []).append(l)

    dup_rows = [(sec, rid, n) for (sec, rid, _), n in exact.items() if n > 1]
    if dup_rows:
        warns.append(
            f"同段内完全重复的编号行共 {len(dup_rows)} 组（最多一组 x{max(n for _, _, n in dup_rows)}）；"
            f"多为历史表复用或追加残留，建议抽查：{dup_rows[0][0][:30]} / id={dup_rows[0][1]}"
        )
    for (sec, rid), rows in by_id.items():
        if len({hashlib.sha256(r.encode()).hexdigest() for r in rows}) > 1:
            warns.append(f"同段同编号但内容不同: {sec[:36]} / id={rid}（{len(rows)} 行，可能是历史多表复用）")

    # 3) 行数上限
    if len(lines) > MAX_LINES:
        errors.append(f"文件 {len(lines)} 行 > {MAX_LINES}（正常约 2600–3000，疑似又拼了一份）")

    # 4) 连续空行（警告）
    run = 0
    for l in lines:
        run = run + 1 if not l else 0
        if run == 4:
            warns.append("出现连续 4 行以上空行（可能合并残留）")
            break

    print(f"[ledger-dupe] {path}: {len(lines)} 行 / {len(heads)} 个章节头")
    for w in warns:
        print(f"  WARN {w}")
    if errors:
        for e in errors:
            print(f"  ERR  {e}")
        print(f"[ledger-dupe] 失败：{len(errors)} 个错误（修复见 _zd-private/docs/账本修复-2026-10-26.md）")
        return 1
    print("[ledger-dupe] OK 无重复章节、无完全重复行、行数正常")
    return 0


if __name__ == "__main__":
    sys.exit(main())
