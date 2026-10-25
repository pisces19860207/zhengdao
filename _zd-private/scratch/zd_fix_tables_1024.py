# -*- coding: utf-8 -*-
"""修 v2 总案两张表的表头列数缺陷（P1/P2 表头缺「用户视角」列），并把 P2-2 的第五列并回修法列。"""
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

# 1) P1 表头补「用户视角」列（18 行数据全是 5 列，表头只有 4 列）
rep("| # | 问题 | 定位 | 修法（钉死） |\n|---|---|---|---|",
    "| # | 问题 | 定位 | 用户视角 | 修法（钉死） |\n|---|---|---|---|---|",
    "P1-header")

# 2) P2 表头补「用户视角」列
rep("| # | 问题 | 定位 | 修法 |\n|---|---|---|---|",
    "| # | 问题 | 定位 | 用户视角 | 修法 |\n|---|---|---|---|---|",
    "P2-header")

# 3) P2-2：第五列「原案：扩 48dp」并回修法列，用户视角位补 —
rep("② | 原案：扩 48dp | **已裁决（2026-10-24 用户观感）**",
    "② | — | **已裁决（2026-10-24 用户观感；原案为扩 48dp）**",
    "P2-2-merge")

if fails:
    for x in fails:
        print("FAIL", x)
    sys.exit(1)

# 4) P2 表内 4 列行（P2-1/3/4/5/6/7/8）在末列前插 — 位
lines = text.split("\n")
out = []
in_p2 = False
fixed = []
for ln in lines:
    s = ln.strip()
    if s.startswith("| # | 问题 | 定位 | 用户视角 | 修法 |"):
        in_p2 = True
        out.append(ln)
        continue
    if in_p2 and s.startswith("|") and not s.startswith("|---"):
        cells = s.count("|")
        if cells == 5:  # 4 列数据行 ⇒ 补 — 成 5 列
            body = s.strip("|")
            parts = [c.strip() for c in body.split("|")]
            parts = parts[:3] + ["—"] + parts[3:]
            ln = "| " + " | ".join(parts) + " |"
            fixed.append(parts[0])
            in_p2 = False  # 只处理本表
    elif in_p2 and not s.startswith("|"):
        in_p2 = False
    out.append(ln)

text = "\n".join(out)
print("fixed 4-col rows:", fixed)

with io.open(PATH, "w", encoding="utf-8", newline="\r\n") as f:
    f.write(text)
print("OK")
