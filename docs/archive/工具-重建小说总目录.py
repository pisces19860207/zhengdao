#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重建小说总目录（零模型、零 token、纯标准库）。

用途：把「藏书目录」扫一遍，生成一份**给 AI 看的总目录**（每本书一行），
      替代那份已经过期的手工索引。

特点：
  - 不调用任何模型，不花任何 token
  - **不修改、不移动、不删除任何原始文件**（只读 + 只写一份新的索引文件）
  - 自动跳过备份/回收站等噪音目录（默认跳过：以 . 或 _ 开头、以及名字里含「备份」「回收站」「trash」的目录）
  - 每本书只提取：相对路径 / 大小 / 行数 / **首个非空行的前 60 字**（当"这是什么"的免费线索）

用法（在装有 python3 的地方跑，比如证道终端里的 Debian）：

    python3 gen_novel_index.py "/sdcard/Download/小说/藏书" "/sdcard/Download/小说/_总目录.md"

参数可省略，省略时用下面 DEFAULT_ROOT / DEFAULT_OUT 的默认值。
"""

import os
import sys

# ── 默认值（按你的手机实际情况写好了，直接跑也行）──────────────────────────
DEFAULT_ROOT = "/sdcard/Download/小说/藏书"
DEFAULT_OUT = "/sdcard/Download/小说/_总目录.md"

# 扫描时跳过的目录名（大小写不敏感；命中即整棵子树跳过）
SKIP_DIR_TOKENS = ("备份", "回收站", "trash", ".trash", "backup", "node_modules")
# 只收这些后缀
KEEP_EXT = (".txt", ".md", ".text")

HINT_MAX = 60          # "首个非空行"最多取多少字
SKIP_DIR_PREFIX = (".", "_")


def should_skip_dir(name: str) -> bool:
    low = name.lower()
    if name.startswith(SKIP_DIR_PREFIX):
        return True
    for tok in SKIP_DIR_TOKENS:
        if tok.lower() in low:
            return True
    return False


def human(n: int) -> str:
    if n >= 1024 * 1024:
        return "%.1fMB" % (n / 1024.0 / 1024.0)
    if n >= 1024:
        return "%.0fKB" % (n / 1024.0)
    return "%dB" % n


def first_hint(path: str) -> str:
    """读文件开头几 KB，取第一个非空行当'这是什么'的线索。读不出就返回空。"""
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for _ in range(40):                # 最多看 40 行，够找到第一个非空行了
                line = f.readline()
                if not line:
                    break
                s = line.strip().strip("\ufeff")
                if s:
                    return s[:HINT_MAX]
    except Exception:
        pass
    return ""


def count_lines(path: str) -> int:
    """数行数（按字节块扫，不做解码，快且不怕坏编码）。"""
    n = 0
    try:
        with open(path, "rb") as f:
            while True:
                buf = f.read(1 << 20)
                if not buf:
                    break
                n += buf.count(b"\n")
    except Exception:
        pass
    return n


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ROOT
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT

    if not os.path.isdir(root):
        sys.stderr.write("找不到目录：%s\n" % root)
        return 2

    # 分类 -> [(相对路径, 字节, 行数, 线索)]
    groups = {}
    total_files = 0
    total_bytes = 0
    skipped_dirs = 0

    for dirpath, dirnames, filenames in os.walk(root):
        # 原地过滤，防止 os.walk 进入噪音目录
        before = len(dirnames)
        dirnames[:] = [d for d in dirnames if not should_skip_dir(d)]
        skipped_dirs += before - len(dirnames)

        for fn in sorted(filenames):
            if not fn.lower().endswith(KEEP_EXT):
                continue
            full = os.path.join(dirpath, fn)
            if os.path.islink(full) or not os.path.isfile(full):
                continue
            rel = os.path.relpath(full, root)
            parts = rel.split(os.sep)
            cat = parts[0] if len(parts) > 1 else "(根目录)"
            try:
                size = os.path.getsize(full)
            except Exception:
                size = 0
            groups.setdefault(cat, []).append((rel, size, count_lines(full), first_hint(full)))
            total_files += 1
            total_bytes += size

    # ── 输出 ──────────────────────────────────────────────────────────────
    lines = []
    lines.append("# 📚 小说总目录（自动生成）\n")
    lines.append("> 由 `gen_novel_index.py` 生成，**不含任何模型、不花 token**。\n")
    lines.append("> 用途：让 AI **先看这份目录挑书**，再用 `grep -n \"^第.*章\" 那本书` 列出章节，最后 `sed -n` 只读那一章。\n")
    lines.append("> ⚠️ 本文件是**索引**，不是正文；正文一个字节都没动。\n")
    lines.append("")
    lines.append("**共 %d 本 / %s / %d 个分类**\n" % (total_files, human(total_bytes), len(groups)))
    lines.append("")

    # 统计表
    lines.append("| 分类 | 本数 | 大小 |")
    lines.append("|---|---:|---:|")
    for cat in sorted(groups, key=lambda c: -sum(x[1] for x in groups[c])):
        items = groups[cat]
        lines.append("| %s | %d | %s |" % (cat, len(items), human(sum(x[1] for x in items))))
    lines.append("")

    # 明细
    for cat in sorted(groups, key=lambda c: -len(groups[c])):
        items = groups[cat]
        lines.append("## %s　（%d 本 / %s）" % (cat, len(items), human(sum(x[1] for x in items))))
        lines.append("")
        for rel, size, nlines, hint in sorted(items, key=lambda x: x[0]):
            bit = "　— %s" % hint if hint else ""
            lines.append("- `%s`　(%s, %d 行)%s" % (rel, human(size), nlines, bit))
        lines.append("")

    text = "\n".join(lines)

    # 原子写：先写 .tmp 再改名，绝不产生半个文件
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, out)

    sys.stderr.write(
        "完成：%d 本 / %s / %d 分类 → %s（跳过了 %d 个噪音目录）\n"
        % (total_files, human(total_bytes), len(groups), out, skipped_dirs)
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
