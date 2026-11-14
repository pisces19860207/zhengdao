#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-metric-labels.py — 读数标签门禁（535 B 段，2026-11-08）。

为什么有它
----------
535 A 段把 `AskIntake.kt` 账行里的 `n=`（闸下限）改成 `n>=`。这类错会反复出现：
**把"闸下限/请求上限"写成"计数读数"**，账上看起来像实测值，后续所有分析都会跟着失真。
本闸只查一件窄事：Kotlin **字符串字面量**里出现的 `n=` / `count=` / `samples=` 是否带下限语义
（`>=` / `_lb` / `lowerBound`），否则要求显式登记理由。

不查什么（避免误杀）
--------------------
1. 只扫 `.kt`；注释与 KDoc 不算（字面量之外的 `n=` 不报）。
2. **JS/表达式赋值不算**：`var n=`、`,n=`、`?n=` 这类是语言赋值，不是日志标签。
3. 已有 `n>=`、`n_lb`、`lowerBound`、`count>=`、`samples>=` 一律放行。
3b. **XML/HTML 属性形态不算读数标签**：`count="2"`、`count=\"2\"`（Kotlin 转义）这类是
    OOXML/HTML 的**标记属性**（如 xlsx 的 `<fonts count="2">`），不是给人看的计数读数；
    判据是「`=` 后面紧跟引号」——读数标签按约定不带引号（`count=5`、`n>=20`）。
    2026-11-14 632 的 docx/xlsx 写出件撞出来的假红（ci #2000–#2002）。
4. 登记表 `config/metric-labels-allowlist.json`：每条必须写 reason 与 expires（默认 30 天），
   **过期即红**；只许收窄，不许清空。

用法
----
    python3 tools/check-metric-labels.py                 # 查全仓（app/policy/core 的 main 源）
    python3 tools/check-metric-labels.py --selftest      # 内置正负例
    python3 tools/check-metric-labels.py --paths a.kt    # 只查指定文件（试运行/调试）
"""

import argparse
import datetime
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SCAN_DIRS = [
    os.path.join("app", "src", "main"),
    os.path.join("policy", "src", "main"),
    os.path.join("core", "src", "main"),
]

ALLOWLIST = os.path.join("config", "metric-labels-allowlist.json")

# 裸标签：前面不是标识符字符，后面不是 '>'（已带下限语义的放行）
BARE = re.compile(r"(?<![A-Za-z0-9_.\\\\])(n|count|samples)=(?!>)")
# 下限语义（同一字面量内出现即放行）
LOWER_BOUND = re.compile(r"(>=|_lb\b|lowerBound|lower_bound|下限)")
# 语言赋值形态（JS/表达式），不算日志标签
ASSIGN = re.compile(r"(?:\bvar\s+|\bval\s+|[,?]\s*)(n|count|samples)=")
# XML/HTML 标记属性：`count="2"` / Kotlin 转义写法 `count=\"2\"` ⇒ 不是读数标签
XML_ATTR = re.compile(r'(?<![A-Za-z0-9_.\\])(n|count|samples)=\\?["\']')
# 粗略字符串字面量抽取（支持 \" 转义；不做三引号跨行合并，够用）
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')


def iter_kotlin(root):
    for base in SCAN_DIRS:
        d = os.path.join(root, base)
        if not os.path.isdir(d):
            continue
        for dp, _, fs in os.walk(d):
            if os.sep + "build" in dp:
                continue
            for f in fs:
                if f.endswith(".kt"):
                    yield os.path.join(dp, f)


def scan_text(text):
    """返回 [(行号, 字面量片段)] —— 只在字符串字面量里找。"""
    hits = []
    for m in STRING.finditer(text):
        lit = m.group(1)
        line_no = text.count("\n", 0, m.start()) + 1
        if not BARE.search(lit):
            continue
        # 先把 XML/HTML 属性形态剥掉（`count=\"2\"`），剩下的才算读数标签候选
        bare_lit = XML_ATTR.sub("", lit)
        if not BARE.search(bare_lit):
            continue
        if LOWER_BOUND.search(lit):
            continue
        if ASSIGN.search(lit):
            continue
        hits.append((line_no, lit.strip()[:120]))
    return hits


def load_allowlist(root):
    p = os.path.join(root, ALLOWLIST)
    if not os.path.isfile(p):
        return []
    with io.open(p, encoding="utf-8") as f:
        data = json.load(f)
    return data.get("entries", [])


def allowed(root, rel, snippet, entries, today):
    for e in entries:
        if e.get("file") != rel:
            continue
        if e.get("contains", "") not in snippet:
            continue
        exp = e.get("expires", "")
        try:
            exp_date = datetime.date.fromisoformat(exp)
        except ValueError:
            return None, "登记项 %s 的 expires 不是 YYYY-MM-DD：%r" % (rel, exp)
        if exp_date < today:
            return None, "登记已过期：%s（%s）" % (rel, exp)
        if not e.get("reason"):
            return None, "登记项缺 reason：%s" % rel
        return e, None
    return False, None


def run(root, paths=None, today=None):
    today = today or datetime.date.today()
    entries = load_allowlist(root)
    problems = []
    targets = []
    if paths:
        for p in paths:
            targets.append(os.path.abspath(p))
    else:
        targets = list(iter_kotlin(root))

    for path in targets:
        if not os.path.isfile(path):
            continue
        with io.open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
        rel = os.path.relpath(path, root).replace(os.sep, "/")
        for line_no, snippet in scan_text(text):
            ok, err = allowed(root, rel, snippet, entries, today)
            if err:
                problems.append("%s:%d  %s\n    → %s" % (rel, line_no, snippet, err))
            elif ok is False:
                problems.append(
                    "%s:%d  裸计数标签（未见 >= / _lb / lowerBound，也未登记）\n    %s"
                    % (rel, line_no, snippet)
                )
    return problems


SELFTEST_CASES = [
    ('val s = "detail = \\"n=5\\""', True, "裸 n= 要报"),
    ('val s = "detail = \\"n>=5\\""', False, "n>= 放行"),
    ('val s = "entry=x conf=0.9 n_lb=20"', False, "n_lb 放行"),
    ('val s = "count=0 / 预算"', True, "裸 count= 要报"),
    ('val s = "var n=document.querySelectorAll(x)"', False, "JS 赋值不算"),
    ('val s = "samples>=10"', False, "samples>= 放行"),
    ('val s = "tools.count=` + n + `"', False, "结构化键 tools.count= 放行（带点前缀=非裸标签）"),
    ('val s = "<fonts count=\\"2\\">"', False, "XML 属性 count= 不算读数标签（632 的 xlsx）"),
    ('val s = " count=\\""', False, "XML 属性片段（拼串写法）也不算"),
    ('val s = "detail count=5"', True, "无引号的 count= 仍是裸读数标签（别把口子开太大）"),
]


def selftest():
    bad = 0
    for src, should_flag, why in SELFTEST_CASES:
        got = bool(scan_text(src))
        status = "OK" if got == should_flag else "FAIL"
        if got != should_flag:
            bad += 1
        print("[selftest] %s  %s  (%s)" % (status, why, src))
    print("[selftest] %d/%d 通过" % (len(SELFTEST_CASES) - bad, len(SELFTEST_CASES)))
    return 1 if bad else 0


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--paths", nargs="*", default=None)
    ap.add_argument("--root", default=ROOT)
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    problems = run(os.path.abspath(args.root), args.paths)
    if problems:
        print("[metric-labels] 发现 %d 处裸计数标签（疑似把闸下限/请求上限写成计数）：" % len(problems))
        for p in problems:
            print("  " + p)
        print("\n处理：改成 n>= / n_lb= / 写明 lowerBound；确属计数或非读数语义，"
              "在 %s 登记 reason + expires。" % ALLOWLIST.replace(os.sep, "/"))
        return 1
    print("[metric-labels] 通过：未发现未登记的裸计数标签")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
