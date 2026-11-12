#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""run-redline-tests.py — L3 红线底账聚合入口（612 批，2026-11-12）。

一条命令跑完 docs/L3-红线底账.md 登记的全部负例：**只按名字筛既有用例**
（gradle ``--tests`` 过滤，不复制任何判定逻辑）。底账里状态=缺负例 的行不跑、如实列出，
绝不补写冒充有。本脚本只读底账＋跑测试，不写任何文件（gradle 自身 build/ 产物除外）⇒ 幂等。

判据（与 612 派工单一一对应）：
  ① 底账三字段（实现落点/负例测试/状态）不许空——空一格就红，点名行号与字段；
  ② 聚合入口总数 = 底账条数——总数/有负例/缺负例三个数全打印，表格里任何一行
    不许被静默丢掉（陌生表格行、编号重复都红）；
  ③ 某条负例红（或 ``--tests`` 筛不到 = 底账名字漂了）⇒ 本命令红并点名
    「哪条红线、哪个用例」，另发 ``::error`` annotation（CI 匿名可读）；
  ④ 幂等：连跑两次结果一致；脚本不改任何仓内文件。

用法：
    python3 tools/run-redline-tests.py                 # 真跑（本地多会话先按 AGENTS.md 抢构建锁）
    python3 tools/run-redline-tests.py --selftest      # 解析器/映射自检（不跑 gradle）
    python3 tools/run-redline-tests.py --dry-run       # 只打印将筛的 gradle 过滤器，不跑

CI 挂点：ci.yml verify job「全量单元测试」之后一步（612）。

模块映射（按测试文件路径判，不猜）：
    app/src/test/java/**  → ``:app:testDebugUnitTest``
    core/src/test/java/** → ``:core:testDebugUnitTest``

纯标准库，无依赖。
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

DEFAULT_LEDGER = os.path.join("docs", "L3-红线底账.md")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 编号行：| R1-1 | ...；表头与分隔行在解析时单独识别
ROW_RE = re.compile(r"^\|\s*(R\d+-\d+)\s*\|")
SEP_RE = re.compile(r"^\|[\s:\-|]+\|\s*$")
STATUS_OK = ("有负例", "缺负例")

# 测试文件路径前缀 → (gradle 任务, 结果 XML 目录)
MODULES = [
    ("app/src/test/java/", ":app:testDebugUnitTest",
     os.path.join("app", "build", "test-results", "testDebugUnitTest")),
    ("core/src/test/java/", ":core:testDebugUnitTest",
     os.path.join("core", "build", "test-results", "testDebugUnitTest")),
]


class Red:
    """底账一行。"""

    def __init__(self, no, redline, impl, negtest, status, lineno):
        self.no = no
        self.redline = redline
        self.impl = impl
        self.negtest = negtest
        self.status = status
        self.lineno = lineno
        self.testfile = None
        self.case = None
        self.task = None
        self.results_dir = None
        self.fqcn = None
        self.error = None


def gh_error(title, msg):
    """GitHub annotation：匿名可读，红了不用登录也能看到点名。"""
    print("::error title=%s::%s" % (title, msg))


def parse_ledger(text, root):
    """解析底账全文。返回 (rows, errors)。任何以 | 开头的行都算表格行，不许静默丢。"""
    rows, errors = [], []
    seen_no = {}
    for i, raw in enumerate(text.splitlines(), 1):
        s = raw.strip()
        if not s.startswith("|"):
            continue
        if SEP_RE.match(s):
            continue
        if "编号" in s and "红线" in s:  # 表头
            continue
        m = ROW_RE.match(s)
        if not m:
            errors.append("第 %d 行：表格里出现底账认不出的行（编号必须是 R<组>-<序>）：%s"
                          % (i, s[:60]))
            continue
        cells = [c.strip() for c in s.strip("|").split("|")]
        if len(cells) != 5:
            errors.append("第 %d 行：列数应是 5（编号/红线/实现落点/负例测试/状态），实得 %d" % (i, len(cells)))
            continue
        no, redline, impl, negtest, status = cells
        row = Red(no, redline, impl, negtest, status, i)
        if no in seen_no:
            errors.append("第 %d 行：编号重复（%s 已在第 %d 行登记）" % (i, no, seen_no[no]))
        else:
            seen_no[no] = i
        if not impl:
            row.error = row.error or "实现落点为空——底账三字段不许空"
        if not negtest:
            row.error = row.error or "负例测试为空——底账三字段不许空"
        if status not in STATUS_OK:
            row.error = row.error or "状态必须是「有负例/缺负例」之一，实得「%s」" % status
        if row.error is None:
            if status == "缺负例":
                if negtest != "缺负例":
                    row.error = "状态=缺负例 的行，负例测试格必须如实填「缺负例」，不许填别的"
            else:  # 有负例：负例测试必须是 文件::用例名
                if "::" not in negtest:
                    row.error = "负例测试格式应是「测试文件路径::用例名」，实得「%s」" % negtest[:60]
                else:
                    tf, case = negtest.rsplit("::", 1)
                    if not tf or not case:
                        row.error = "负例测试的文件名与用例名都不许空"
                    else:
                        task, results_dir = module_for(tf)
                        if task is None:
                            row.error = "测试文件认不出模块（只认 app/core 的 src/test/java）：%s" % tf
                        else:
                            row.testfile = tf
                            row.case = case
                            row.task = task
                            row.results_dir = results_dir
                            row.fqcn = tf.split(task_prefix(tf))[1][:-3].replace("/", ".")
                            if not os.path.exists(os.path.join(root, tf.replace("/", os.sep))):
                                row.error = "负例测试文件在仓库里不存在（底账不许指向不存在的用例）：%s" % tf
        if row.error:
            errors.append("第 %d 行（%s %s）：%s" % (i, no, redline[:18], row.error))
        rows.append(row)
    return rows, errors


def task_prefix(testfile):
    for prefix, _, _ in MODULES:
        if testfile.startswith(prefix):
            return prefix
    return ""


def module_for(testfile):
    for prefix, task, results_dir in MODULES:
        if testfile.startswith(prefix):
            return task, results_dir
    return None, None


def gradlew_path(root):
    exe = "gradlew.bat" if os.name == "nt" else "gradlew"
    p = os.path.join(root, exe)
    return p if os.path.exists(p) else None


def run_module(root, task, filters):
    """一个模块一次 gradle：只筛名字，不复制逻辑。返回退出码。"""
    gradlew = gradlew_path(root)
    if gradlew is None:
        gh_error("L3 红线聚合·环境", "找不到 %s（repo 根认成 %s）" % ("gradlew(.bat)", root))
        return 3
    cmd = [gradlew, task, "--console=plain"]
    for f in filters:
        cmd += ["--tests", f]
    print("\n===== gradle %s（筛 %d 个用例）=====" % (task, len(filters)))
    sys.stdout.flush()
    try:
        return subprocess.run(cmd, cwd=root).returncode
    except OSError as e:
        gh_error("L3 红线聚合·环境", "gradle 起不来：%s" % e)
        return 3


def read_results(root, results_dir):
    """读 JUnit XML：返回 {(classname, name): [failed, errored, skipped]}。"""
    d = os.path.join(root, results_dir)
    out = {}
    if not os.path.isdir(d):
        return None  # 目录都没有 ⇒ 结果读不到，不许当绿
    for fn in os.listdir(d):
        if not (fn.startswith("TEST-") and fn.endswith(".xml")):
            continue
        try:
            tree = ET.parse(os.path.join(d, fn))
        except ET.ParseError as e:
            print("!! 结果 XML 解析失败 %s：%s" % (fn, e))
            return None
        for tc in tree.getroot().iter("testcase"):
            bad = 0
            for child in tc:
                if child.tag in ("failure", "error", "skipped"):
                    bad += 1
            key = (tc.get("classname") or "", tc.get("name") or "")
            out.setdefault(key, []).append(bad)
    return out


def main(argv=None):
    # 不改编码（本地 GBK / CI UTF-8 各用各的），只兜住不可编码字符，不因打印而崩。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(errors="replace")
            except Exception:
                pass
    ap = argparse.ArgumentParser(description="L3 红线底账聚合入口（612）")
    ap.add_argument("--ledger", default=DEFAULT_LEDGER, help="底账路径（默认 %(default)s）")
    ap.add_argument("--repo", default=REPO, help="仓库根（默认按脚本位置推）")
    ap.add_argument("--selftest", action="store_true", help="解析器自检，不跑 gradle")
    ap.add_argument("--dry-run", action="store_true", help="只打印 gradle 过滤器，不跑")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    root = os.path.abspath(args.repo)
    ledger = os.path.join(root, args.ledger.replace("/", os.sep)) if not os.path.isabs(args.ledger) else args.ledger
    if not os.path.exists(ledger):
        gh_error("L3 红线聚合", "底账不存在：%s" % ledger)
        return 2
    with open(ledger, encoding="utf-8") as f:
        rows, errors = parse_ledger(f.read(), root)

    total = len(rows)
    runnable = [r for r in rows if r.status == "有负例"]
    missing = [r for r in rows if r.status == "缺负例"]
    print("底账：%s" % os.path.relpath(ledger, root))
    # 总数就是逐行解析出的行数：任何以 | 开头的行都必须是合法条目（否则 errors 已红），
    # 所以「聚合入口总数 = 底账条数」由解析器结构性保证，不是靠事后对数。
    print("条目总数 %d ｜ 有负例 %d（本次执行 %d）｜ 缺负例 %d" % (total, len(runnable), len(runnable), len(missing)))
    for r in missing:
        print("  缺负例：%s %s —— %s" % (r.no, r.redline[:24], "交付报告须列出，不许补写"))

    if errors:
        print("\n-- 底账解析红 %d 处 --" % len(errors))
        for e in errors:
            print("  红：" + e)
            gh_error("L3 红线底账·格式", e)
        return 1

    if not runnable:
        print("底账里没有可跑的负例——这本身就是红（六条红线一条都没钉住）")
        return 1

    by_module = {}
    for r in runnable:
        by_module.setdefault(r.task, []).append(r)

    if args.dry_run:
        for task, rs in by_module.items():
            print("\n%s：" % task)
            for r in rs:
                print("  --tests %s.%s   （%s）" % (r.fqcn, r.case, r.no))
        return 0

    # ── 真跑 ────────────────────────────────────────────────────────────────
    failed = []  # (row, 原因)
    for task, rs in by_module.items():
        filters = ["%s.%s" % (r.fqcn, r.case) for r in rs]
        code = run_module(root, task, filters)
        results = read_results(root, rs[0].results_dir)
        # gradle 判红也要逐例归因：test 任务失败是把全部筛到的用例跑完后在收尾时判红，
        # XML 里有逐例结果——真凶点名「用例红」，其余不许蹭红也不许装绿。
        if code != 0:
            print("\n!! gradle %s 退出码 %d —— 逐例归因如下" % (task, code))
        if results is None:
            for r in rs:
                failed.append((r, "结果 XML 读不到（%s）——不许当绿" % rs[0].results_dir))
            continue
        for r in rs:
            hits = results.get((r.fqcn, r.case))
            if hits is None:
                if code == 0:
                    failed.append((r, "结果里找不到 %s#%s —— 筛了却没跑到（含「No tests found」＝底账名字漂了），不许当绿"
                                   % (r.fqcn, r.case)))
                else:
                    failed.append((r, "gradle 判红，本用例没跑到——不许当绿"))
            elif any(h for h in hits):
                failed.append((r, "用例红：%s#%s（gradle %s 退出码 %d）" % (r.fqcn, r.case, task, code)))

    print("\n-- 逐条结论 --")
    for r in runnable:
        mark = "绿" if all(x[0] is not r for x in failed) else "红"
        print("  %s %s %s ｜ %s#%s" % (mark, r.no, r.redline[:26], r.fqcn.rsplit(".", 1)[-1], r.case))

    if failed:
        print("\n-- 红线点名 --")
        failed.sort(key=lambda x: 0 if x[1].startswith("用例红") else 1)
        for r, why in failed:
            line = "%s（%s）红：/%s/" % (r.no, r.redline[:30], why)
            print("  " + line)
            gh_error("L3 红线", line)
        print("聚合结论：红 %d / 总 %d（缺负例 %d 已如实列出）" % (len(failed), total, len(missing)))
        return 1

    print("\n聚合结论：%d/%d 全绿（有负例全跑过；缺负例 %d 条如实列出，未补写）" % (len(runnable), total, len(missing)))
    return 0


# ── selftest：只测解析与映射，不跑 gradle ────────────────────────────────────
SELFTEST_TABLE = """\
| 编号 | 红线（人话） | 实现落点 | 负例测试（文件::用例名） | 状态 |
| --- | --- | --- | --- | --- |
| R9-1 | 假红线甲：不批不许做 | gate/fake.py:1 | app/src/test/java/FakeTest.kt::用例甲 | 有负例 |
| R9-2 | 假红线乙：真没负例如实记 | gate/fake.py:2 | 缺负例 | 缺负例 |
"""


def selftest():
    cases = []

    def check(name, fn):
        fn()
        cases.append(name)
        print("selftest OK %s" % name)

    def touch(root, rel):
        p = os.path.join(root, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write("// fake test\n")

    def t1_happy():
        with tempfile.TemporaryDirectory() as d:
            touch(d, "app/src/test/java/FakeTest.kt")
            rows, errors = parse_ledger(SELFTEST_TABLE, d)
            assert not errors, errors
            assert len(rows) == 2
            assert len([r for r in rows if r.status == "有负例"]) == 1
            assert len([r for r in rows if r.status == "缺负例"]) == 1
            r1 = rows[0]
            assert r1.task == ":app:testDebugUnitTest", r1.task
            assert r1.fqcn == "FakeTest", r1.fqcn
            assert r1.case == "用例甲"
            assert r1.results_dir.endswith("testDebugUnitTest")

    def t2_empty_impl():
        bad = SELFTEST_TABLE.replace("gate/fake.py:1 |", " |", 1)
        with tempfile.TemporaryDirectory() as d:
            rows, errors = parse_ledger(bad, d)
            assert any("实现落点为空" in e for e in errors), errors

    def t3_dup_no():
        bad = SELFTEST_TABLE.replace("R9-2", "R9-1")
        with tempfile.TemporaryDirectory() as d:
            touch(d, "app/src/test/java/FakeTest.kt")
            _, errors = parse_ledger(bad, d)
            assert any("编号重复" in e for e in errors), errors

    def t4_bad_status():
        bad = SELFTEST_TABLE.replace("| 有负例 |", "| 有负例呀 |")
        with tempfile.TemporaryDirectory() as d:
            touch(d, "app/src/test/java/FakeTest.kt")
            _, errors = parse_ledger(bad, d)
            assert any("状态必须是" in e for e in errors), errors

    def t5_unknown_module():
        bad = SELFTEST_TABLE.replace("app/src/test/java/FakeTest.kt", "mystery/src/test/java/FakeTest.kt")
        with tempfile.TemporaryDirectory() as d:
            _, errors = parse_ledger(bad, d)
            assert any("认不出模块" in e for e in errors), errors

    def t6_negtest_without_case():
        bad = SELFTEST_TABLE.replace("FakeTest.kt::用例甲", "FakeTest.kt")
        with tempfile.TemporaryDirectory() as d:
            touch(d, "app/src/test/java/FakeTest.kt")
            _, errors = parse_ledger(bad, d)
            assert any("::用例名" in e for e in errors), errors

    def t7_stray_row():
        bad = SELFTEST_TABLE + "| X1 | 混进来的陌生行 | x:1 | x::y | 有负例 |\n"
        with tempfile.TemporaryDirectory() as d:
            _, errors = parse_ledger(bad, d)
            assert any("认不出的行" in e for e in errors), errors

    def t8_core_mapping():
        table = SELFTEST_TABLE.replace("app/src/test/java/FakeTest.kt", "core/src/test/java/FakeCoreTest.kt")
        with tempfile.TemporaryDirectory() as d:
            touch(d, "core/src/test/java/FakeCoreTest.kt")
            rows, errors = parse_ledger(table, d)
            assert not errors, errors
            assert rows[0].task == ":core:testDebugUnitTest", rows[0].task
            assert rows[0].fqcn == "FakeCoreTest"

    def t9_missing_testfile():
        with tempfile.TemporaryDirectory() as d:
            _, errors = parse_ledger(SELFTEST_TABLE, d)  # 没建 FakeTest.kt
            assert any("不存在" in e for e in errors), errors

    check("解析 happy path（总数=条数、模块映射 app）", t1_happy)
    check("实现落点为空 ⇒ 红", t2_empty_impl)
    check("编号重复 ⇒ 红", t3_dup_no)
    check("状态越界 ⇒ 红", t4_bad_status)
    check("模块认不出 ⇒ 红", t5_unknown_module)
    check("负例测试缺「::用例名」⇒ 红", t6_negtest_without_case)
    check("表格混入陌生行 ⇒ 红（总数=条数的守卫）", t7_stray_row)
    check("模块映射 core ⇒ :core:testDebugUnitTest", t8_core_mapping)
    check("负例测试文件不存在 ⇒ 红", t9_missing_testfile)
    print("selftest：%d/%d 通过" % (len(cases), len(cases)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
