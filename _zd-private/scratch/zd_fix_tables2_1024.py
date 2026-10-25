# -*- coding: utf-8 -*-
"""归一化 P1/P2 两表列数（缺「用户视角」列的行补 —），并转义 P1-11 行内字面管道符。"""
import io, sys

PATH = r"C:\Users\guoli\AndroidStudioProjects\zhengdao\证道设计报告\证道-界面优化与美化总案-v2-2026-10-23.md"
with io.open(PATH, encoding="utf-8") as f:
    text = f.read()

fails = []

def rep(old, new, tag):
    global text
    n = text.count(old)
    if n != 1:
        fails.append("%s count=%d" % (tag, n))
        return
    text = text.replace(old, new)

# 1) P1-11 行内字面管道符转义（否则 markdown 会把单元格劈开）
rep("grep `uncheckedThumb|thumbColor` 零命中",
    "grep `uncheckedThumb\\|thumbColor` 零命中",
    "escape-pipe")

if fails:
    for x in fails:
        print("FAIL", x)
    sys.exit(1)

# 2) 列数归一：两张表的行若比表头少一列，在末列前插 —
lines = text.split("\n")
out = []
mode = None  # None / 'p1' / 'p2'
fixed = []
for ln in lines:
    s = ln.strip()
    if s.startswith("| # | 问题 | 定位 | 用户视角 | 修法（钉死） |"):
        mode = "p1"
        out.append(ln)
        continue
    if s.startswith("| # | 问题 | 定位 | 用户视角 | 修法 |"):
        mode = "p2"
        out.append(ln)
        continue
    if mode and s.startswith("|") and not s.startswith("|---") and not s.startswith("| # |"):
        if s.count("|") == 5:  # 4 列 ⇒ 补 —
            parts = [c.strip() for c in s.strip("|").split("|")]
            parts = parts[:3] + ["—"] + parts[3:]
            fixed.append("%s:%s" % (mode, parts[0]))
            ln = "| " + " | ".join(parts) + " |"
    elif mode and not s.startswith("|"):
        mode = None
    out.append(ln)

text = "\n".join(out)
print("normalized:", fixed)

with io.open(PATH, "w", encoding="utf-8", newline="\r\n") as f:
    f.write(text)
print("OK")
