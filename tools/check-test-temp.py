#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-test-temp.py —— 测试自建的临时目录不许攒（564 立闸，607 改口径）。

## 为什么要有这道闸

单测里有大量 `Files.createTempDirectory(…)` / `createTempFile(…)`，另有一批直接写
`File("build/tmp/…")` 的相对路径。其中相当一部分**跑完不删**。它们落在 Gradle 给测试
JVM 的临时根（`app/build/tmp/testDebugUnitTest`）或模块 `build/tmp/` 下，于是：

  * 每跑一轮就多一棵树，`app/build` 里的文件数上万地涨；
  * `clean`/`rebuild`/CI 缓存上传都跟着变慢、变大；
  * **编译、单测、detekt、lint 全绿** —— 这是「磁盘卫生」的裂缝，没有任何断言盯着它。

## 口径（607 改法：**只数测试自建的**）

旧口径数的是「`app/build` 下 `tmp*` 区域里的目录总数」，把构建系统自己的临时目录
（`.cache`、`compileDebugJavaWithJavac/compileTransaction`、`kapt…`、JDK 的
`jar_extract_*`）一起算进了水位。于是读数会跟着「这轮跑没跑 detekt / 中间产物有没有被
清」上下浮动 —— 那不是测试泄漏，是别人的噪声。

新口径一句话：**只数测试建出来的沙盒根，构建系统的 tmp 一个都不数。**
判定用两条一起看（缺一条就会误伤，或者把排除扩成全排除）：

  ① **名字**：Gradle 任务目录名是「小写起驼峰、无连字符/下划线、无长数字串」
     （`testDebugUnitTest`、`compileDebugJavaWithJavac`、`kaptGenerateStubsDebugKotlin`）；
     测试沙盒几乎都带 `-`/`_` 或 6 位以上连续数字（`zd549-…12345678901234`、`junit860…`）。
  ② **产生者（位置）**：Gradle 只会在 `<module>/build/tmp/<task>` 这一层建自己的任务目录；
     同一层里出现「不像任务名」的目录 ⇒ 那是测试用相对路径写的，照样算。

两条合起来的判决表（`classify_tmp_child`）：

  | 实测形态                                   | 判成   | 计不计数            |
  |--------------------------------------------|--------|---------------------|
  | `build/tmp/.cache`、`kotlin`               | build  | 不计                |
  | `build/tmp/jar_extract_<长数字>_tmp`       | build  | 不计（JDK ZipFS）   |
  | `build/tmp/compileDebugJavaWithJavac`      | task   | 整枝不计（构建内部）|
  | `build/tmp/testDebugUnitTest`              | task   | 容器：**其直接子项逐个计 1** |
  | `build/tmp/repair408-aaa`                  | test   | 计 1                |
  | `build/tmp/testDebugUnitTest/<沙盒>`        | test   | 每个沙盒根计 1      |

**一个沙盒 = 1**（不再数沙盒内部层级）：一次泄漏就是一个沙盒，里面的 `a/b/c` 是它的
内部结构，跟着它一起删；数层级只会让读数随「测试在沙盒里挖了几层」抖动。

边界（607 派工单的原话）：**不许把「排除」扩成「全排除」**。所以：
  * 排除只认 `build/tmp` 这一层上「名字像 Gradle 任务」的目录，其余一律计数；
  * `testDebugUnitTest` 之下不判名字 —— 测试根里叫 `compileDebugJavaWithJavac` 的目录
    是测试建的，照样计 1（`--selftest` 有这一条负例）。

## 判红规则（607 改法：本地只提示，CI 才判红）

预算 = **CI 单轮实测基线 + 固定余量 20**，由脚本自己算（`measured + MARGIN`），
不写死在文件里（双真相必然漂）。

  * **CI**（`GITHUB_ACTIONS=true`）超预算 ⇒ exit 1；
  * **本地**超预算 ⇒ 只打 WARN、exit 0 —— 本地环境差异（要不要跑 detekt、中间产物清没清、
    JDK 版本）不作判据。本地想按 CI 判就加 `--strict`。

## 趋势留档（607 ③）

`--record` 把本轮读数追加一行到 `tools/test-temp-trend.jsonl`：

    {"date": "2026-11-12", "measured": 224, "budget": 244, "commit": "8e6b63771",
     "commitFull": "…", "mode": "ci", "run": "12345", "baseline": 224, "platform": "linux"}

`measured` = 本轮实测，`budget` = 本轮用的预算，`baseline` = 预算文件里的基线。
CI 里同一个 run 重跑不会重复落行（`run` 做去重键）。

## 用法

    python3 tools/check-test-temp.py                 # 查真仓（CI 判红／本地只提示）
    python3 tools/check-test-temp.py --strict         # 本地也按 CI 判红（演练）
    python3 tools/check-test-temp.py --record         # 顺带记一行趋势
    python3 tools/check-test-temp.py --list           # 只打印读数与逐锚点明细
    python3 tools/check-test-temp.py --scan-tests     # 列「建临时目录却没清理痕迹」的测试
    python3 tools/check-test-temp.py --os-temp        # 数系统临时区（只报读数）
    python3 tools/check-test-temp.py --selftest       # 合成树：判据①②③④的夹具

`--selftest` 全程用临时目录里的合成树，**不读真仓**（否则「仓库恰好干净」会把闸的自测也染绿）。

## `--scan-tests` 的口径（这是清单，不是门禁）

只按**文字痕迹**判：文件里出现过建临时目录/文件的调用，且**一处清理痕迹都没有**
（`deleteRecursively` / `deleteOnExit` / `deleteIfExists` / `@After` / `TempSandbox` / `rmTree`）。
启发式，只用来排改造顺序。它**不参与判红**。

## `--os-temp`：为什么还要看系统临时目录

Gradle 的 Test 任务默认**不设置** `java.io.tmpdir`，`Files.createTempDirectory` 一律落到
系统临时区 ⇒ 只看 `build` 的闸一个都数不到。`app/build.gradle.kts` 的
`tasks.withType<Test>` 把 `java.io.tmpdir` 钉到 `app/build/tmp/testDebugUnitTest`，
`--os-temp` 做回归读数：钉住了 ⇒ 每轮增量 ≈ 0；涨了说明钉住被拆了。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，而不是让门禁自己崩
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

# 固定余量（607 定死 20）：预算是「CI 单轮实测 + 20」，不是手写的一个数。
# 为什么固定 20：本地干净单轮与 CI 单轮实测差在 ±1–2（549/570/571 的历史读数），
# 20 足以吸收「测试并行/条件写盘」的抖动，又不至于让一次真泄漏（几十上百）藏进去。
MARGIN = 20

# 没有水位文件时的兜底预算（全新仓库/文件被删）。正常路径永远走 measured + MARGIN。
DEFAULT_BUDGET = 50

# 水位文件：只放 measured（CI 单轮实测基线）与出处，**不放 budget**（派生值，双真相必漂）。
BUDGET_FILE = "tools/test-temp-budget.json"
TREND_FILE = "tools/test-temp-trend.jsonl"

# 「建临时目录/文件」的写法（--scan-tests 用）
CREATE_RE = re.compile(
    r"createTempDirectory|createTempFile|createTempDir\b|TemporaryFolder|File\(\"build/tmp"
)
# 「清理痕迹」（--scan-tests 用）。缺一个都不算 —— 宁可把干净文件列进清单让人看一眼，
# 也不要把漏删的文件放过：清单多一条只是多读一行，漏一条就是几千个文件。
CLEAN_RE = re.compile(
    r"deleteRecursively|deleteOnExit|deleteIfExists|@After\b|@AfterEach\b|TempSandbox|rmTree"
)
SCAN_SKIP = {"TempSandbox.kt", "TempSandboxTest.kt"}

# 本仓测试建临时目录时用的前缀（`--os-temp` 用；名字形如 `baopu-242-ledger<19位随机数>`）。
OS_TEMP_PREFIXES = (
    "baopu", "zd", "opinion", "persona", "novel", "learn", "royal", "pledge",
    "self", "ws", "factory", "safety", "wk2",
)
OS_TEMP_RE = re.compile(r"^(?:%s)[-_].*\d{8,}$" % "|".join(OS_TEMP_PREFIXES))

# ── 607 口径：Gradle 任务目录名 vs 测试沙盒名 ─────────────────────────────────
# Gradle 的任务目录名（build/tmp/<task>）实测形态：小写起驼峰、无 `-`/`_`/`.`、无长数字。
# 不带前导点是有意的：`.ssh` 这种无数字短名在本仓是测试沙盒（实测存在于测试根下），
# 若允许前导点，它会与 `.cache` 混在一起被误判成构建目录。`.cache` 走 BUILD_ONLY_NAMES。
GRADLE_TASK_RE = re.compile(r"^[a-z][A-Za-z0-9]*$")
# 6 位以上连续数字：本仓测试沙盒几乎都带（`junit8602780560656064424`），
# Gradle 任务名从不带 ⇒ 用它把「驼峰但带随机数」的沙盒从任务名里挑出来。
LONG_DIGITS_RE = re.compile(r"\d{6,}")
# 明确属于构建系统、但不是任务名的固定项
BUILD_ONLY_NAMES = {"kotlin", "kapt", "hc", ".cache"}
JAR_EXTRACT_PREFIX = "jar_extract_"
# Gradle 的 Test 任务名都以 test 开头（testDebugUnitTest / testReleaseUnitTest / test）
TEST_TASK_PREFIX = "test"


def count_os_temp(root: Path) -> tuple[int, int]:
    """返回 (系统临时区里本仓测试前缀的目录数, 全部条目数)。只数不删。"""
    if not root.is_dir():
        return 0, 0
    entries = list(root.iterdir())
    mine = sum(1 for p in entries if p.is_dir() and OS_TEMP_RE.match(p.name))
    return mine, len(entries)


def load_baseline(repo: Path) -> tuple[int, int, int, str]:
    """返回 (基线 measured, 余量, 预算, 出处)。

    预算 = measured + MARGIN，**由脚本算**。文件里的 `budget` 字段（旧 schema）不再采信，
    只说一句「已弃用」——否则两边打架时没人知道该信哪个。
    """
    f = repo / BUDGET_FILE
    if not f.is_file():
        return 0, MARGIN, DEFAULT_BUDGET, "默认（无水文件：%d）" % DEFAULT_BUDGET
    try:
        data = json.loads(f.read_text(encoding="utf-8"))
    except (ValueError, OSError) as exc:
        return 0, MARGIN, DEFAULT_BUDGET, "默认（水文件读不出：%s）" % exc

    measured = data.get("measured")
    if not isinstance(measured, int) or isinstance(measured, bool) or measured < 0:
        return 0, MARGIN, DEFAULT_BUDGET, "默认（水文件 schema 不对：measured 不是非负整数）"

    budget = measured + MARGIN
    source = "%s（基线 %d + 余量 %d；基线实测于 %s）" % (
        BUDGET_FILE, measured, MARGIN, data.get("measuredAt"),
    )
    if "budget" in data:
        source += "｜文件里的 budget 字段已弃用（607 改为派生）"
    return measured, MARGIN, budget, source


def tmp_anchors(build_root: Path) -> list[Path]:
    """`build_root` 下所有 `<module>/build/tmp*` 锚点。

    为什么只认父目录名为 `build`：Gradle 的暂存区就在这一层；`intermediates/.../tmp`
    是 AGP 的中间产物目录（数量随版本/平台浮动），不是暂存区。
    """
    if not build_root.is_dir():
        return []
    found: list[Path] = []
    for p in build_root.rglob("tmp*"):
        if not p.is_dir():
            continue
        if "intermediates" in p.parts:
            continue
        if p.parent.name != "build":
            continue
        found.append(p)
    found.sort(key=lambda p: len(p.parts))
    # 去嵌套：沙盒内部若又造出 `build/tmp` 结构，它属于那个沙盒，不单独当锚点
    anchors: list[Path] = []
    for p in found:
        if any(p.is_relative_to(a) for a in anchors):
            continue
        anchors.append(p)
    return sorted(anchors)


def classify_tmp_child(path: Path) -> str:
    """判一个 `build/tmp` 直接子目录的**产生者**：`build` / `task` / `test`。

    名字 + 位置双重判定，见模块头判决表。返回 `test` 就计数。
    """
    name = path.name
    if name.startswith(JAR_EXTRACT_PREFIX):
        return "build"          # JDK ZipFS 解包目录，由 JDK 自己回收
    if name in BUILD_ONLY_NAMES:
        return "build"          # Gradle 自己的缓存/脚本目录
    if GRADLE_TASK_RE.match(name) and not LONG_DIGITS_RE.search(name):
        return "task"           # 驼峰任务名 ⇒ Gradle 的任务目录
    return "test"


def collect_sandboxes(build_root: Path) -> tuple[list[Path], list[tuple[Path, int, int]]]:
    """返回 (沙盒根列表, [(锚点, 沙盒数, 跳过的构建目录数)])。一个沙盒 = 一个路径。"""
    sandboxes: list[Path] = []
    per_anchor: list[tuple[Path, int, int]] = []
    for anchor in tmp_anchors(build_root):
        mine = 0
        skipped = 0
        try:
            children = sorted(anchor.iterdir())
        except OSError:
            continue
        for child in children:
            if not child.is_dir():
                continue
            kind = classify_tmp_child(child)
            if kind == "build":
                skipped += 1
                continue
            if kind == "task":
                skipped += 1
                if not child.name.startswith(TEST_TASK_PREFIX):
                    continue        # 非测试任务：整枝是构建系统内部产物
                # 测试任务的临时根：**其直接子项逐个算沙盒**，这里不判名字
                try:
                    for grand in sorted(child.iterdir()):
                        if grand.is_dir() and not grand.name.startswith(JAR_EXTRACT_PREFIX):
                            sandboxes.append(grand)
                            mine += 1
                except OSError:
                    continue
                continue
            sandboxes.append(child)  # 测试用相对路径直接建在 build/tmp 下
            mine += 1
        per_anchor.append((anchor, mine, skipped))
    return sandboxes, per_anchor


def evaluate(
    total: int,
    budget: int,
    source: str = "",
    mode: str = "local",
    newest: list[tuple[str, float]] | None = None,
    baseline: int | None = None,
) -> tuple[int, list[str]]:
    """核心判据。返回 (exit_code, 输出行)。

    **CI 超预算 ⇒ 1；本地超预算 ⇒ 0（只提示）**。本地环境差异不作判据（607 ②）。
    """
    lines = [
        "读数: 测试自建临时目录 %d 个（预算 %d%s）" % (total, budget, ("｜" + source) if source else ""),
        "口径: 只数测试建的沙盒根；构建系统 tmp（.cache/compile*/kapt*/jar_extract_*）不计",
    ]
    if baseline is not None:
        lines.append("预算算式: 基线 %d + 余量 %d = %d" % (baseline, MARGIN, budget))

    if total > budget:
        if mode == "ci":
            tail = "结果: FAIL（超预算 %d 个）" % (total - budget)
        else:
            tail = "结果: WARN（超预算 %d 个；本地只提示不判红，CI 上会红）" % (total - budget)
        lines.append(
            tail + " —— 有测试跑完不删临时目录；改用 testkit/TempSandbox 的 use{} 收口，"
            "或 --scan-tests 找出强嫌疑文件"
        )
        if newest:
            lines.append("最新建的沙盒（越靠前越新；先查它们对应的测试）：")
            for rel, ts in newest:
                stamp = time.strftime("%m-%d %H:%M:%S", time.localtime(ts)) if ts else "?"
                lines.append("  %s  %s" % (stamp, rel))
        return (1 if mode == "ci" else 0), lines

    if baseline is not None and total < baseline:
        lines.append(
            "提示: 比基线低 %d 个 —— 若这是干净单轮实测，把 %s 的 measured 改成 %d（基线只降不升）"
            % (baseline - total, BUDGET_FILE, total)
        )
    lines.append("结果: PASS（%d ≤ %d｜%s 模式）" % (total, budget, mode))
    return 0, lines


def scan_tests(repo: Path) -> list[tuple[str, int]]:
    """启发式：返回 [(相对路径, 命中建目录的行数)]，只列**零清理痕迹**的文件。"""
    suspects: list[tuple[str, int]] = []
    for base in ("app/src/test", "app/src/androidTest"):
        d = repo / base
        if not d.is_dir():
            continue
        for kt in sorted(d.rglob("*.kt")):
            if kt.name in SCAN_SKIP:
                continue
            text = kt.read_text(encoding="utf-8", errors="replace")
            hits = len(CREATE_RE.findall(text))
            if hits == 0:
                continue
            if CLEAN_RE.search(text):
                continue
            suspects.append((kt.relative_to(repo).as_posix(), hits))
    return suspects


def newest_sandboxes(sandboxes: list[Path], limit: int = 8) -> list[tuple[str, float]]:
    """按 mtime 取最新的 limit 个沙盒（点名用，判红时给线索）。"""
    items: list[tuple[str, float]] = []
    for d in sandboxes:
        try:
            items.append((d.as_posix(), d.stat().st_mtime))
        except OSError:
            continue
    items.sort(key=lambda kv: kv[1], reverse=True)
    return items[:limit]


def module_build_dirs(repo: Path) -> list[Path]:
    """仓库里的模块构建目录（`app/build`、`core/build`…）。只看一到两层，不深挖。"""
    dirs: list[Path] = []
    for pat in ("*/build", "*/*/build"):
        for p in sorted(repo.glob(pat)):
            if p.is_dir() and p.name == "build":
                dirs.append(p)
    return dirs


def measure(build_roots: list[Path]) -> tuple[int, list[Path], list[tuple[Path, int, int]]]:
    """对所有构建目录取并集计数（一个沙盒只算一次）。"""
    all_sandboxes: list[Path] = []
    per_anchor: list[tuple[Path, int, int]] = []
    seen: set[Path] = set()
    for root in build_roots:
        sandboxes, anchors = collect_sandboxes(root)
        per_anchor.extend(anchors)
        for sb in sandboxes:
            try:
                key = sb.resolve()
            except OSError:
                key = sb
            if key in seen:
                continue
            seen.add(key)
            all_sandboxes.append(sb)
    return len(all_sandboxes), all_sandboxes, per_anchor


def evaluate_mode(force_strict: bool) -> str:
    """`ci` 判红；`local` 只提示。CI 判定只认 GitHub Actions 的显式变量。"""
    if force_strict:
        return "ci"
    return "ci" if os.environ.get("GITHUB_ACTIONS", "").lower() == "true" else "local"


def git_commit(repo: Path) -> tuple[str, str]:
    """返回 (短号, 全号)；取不到就诚实写 unknown（不猜）。"""
    try:
        full = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=10, check=False,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return "unknown", "unknown"
    if not full:
        return "unknown", "unknown"
    return full[:9], full


def _today_cn() -> str:
    """日期按 UTC+8 记（本仓所有报告/台账都用北京时间）。

    为什么不直接用 `time.strftime`：CI runner 是 UTC，凌晨跑会记成前一天，
    与 commit 日期、报告日期错位一格 —— 趋势是拿来对账的，错一天就没法对。
    """
    return time.strftime("%Y-%m-%d", time.gmtime(time.time() + 8 * 3600))


def record_trend(
    repo: Path, total: int, budget: int, baseline: int | None, mode: str,
) -> tuple[bool, str]:
    """追加一行趋势。返回 (是否写入, 说明)。

    去重只针对 CI 的同一 `run`（重跑不重复落行）；本地不加去重 —— 「每轮一条」。
    """
    short, full = git_commit(repo)
    run_id = os.environ.get("GITHUB_RUN_ID", "").strip()
    row = {
        "date": _today_cn(),
        "measured": total,
        "budget": budget,
        "commit": short,
        "commitFull": full,
        "baseline": baseline,
        "mode": mode,
        "run": run_id or None,
        "platform": sys.platform,
    }
    f = repo / TREND_FILE
    if run_id and f.is_file():
        try:
            for line in f.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                old = json.loads(line)
                if old.get("run") == run_id and old.get("commit") == short:
                    return False, "跳过：run %s + commit %s 已有记录（重跑不重复落行）" % (run_id, short)
        except (ValueError, OSError):
            pass  # 坏行不影响追加：趋势是账，不是判据
    f.parent.mkdir(parents=True, exist_ok=True)
    with f.open("a", encoding="utf-8", newline="\n") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    return True, "趋势已记: %s ← date=%s measured=%d budget=%d commit=%s mode=%s" % (
        TREND_FILE, row["date"], total, budget, short, mode,
    )


def run(
    build_roots: list[Path],
    budget: int,
    source: str = "",
    mode: str = "local",
    baseline: int | None = None,
    verbose: bool = True,
) -> tuple[int, list[str], int, list[Path]]:
    if not any(r.is_dir() for r in build_roots):
        return 0, [
            "读数: 没有构建目录（尚未构建）—— 本闸只在单测之后有意义，此处按 0 计",
            "结果: PASS（跳过判红）",
        ], 0, []
    total, sandboxes, per_anchor = measure(build_roots)
    code, lines = evaluate(total, budget, source, mode, newest_sandboxes(sandboxes), baseline)
    if verbose:
        for anchor, n, skipped in per_anchor:
            lines.append("  %-46s 沙盒 %3d｜构建系统目录(不计) %d" % (anchor.as_posix(), n, skipped))
    return code, lines, total, sandboxes


SYNTH_BUDGET = 20   # selftest 用小预算：造 21 个沙盒就能测「超一条」


def selftest() -> int:
    """合成树夹具：判据①②③④ + 反例（防把排除扩成全排除）。不读真仓。"""
    results: list[tuple[bool, str, list[str]]] = []

    def build_tree(td: str) -> Path:
        build = Path(td) / "app" / "build"
        tmp = build / "tmp"
        # 判据①：Gradle 式 tmp（非测试创建）—— 全都不许计数
        (tmp / "compileDebugJavaWithJavac" / "compileTransaction" / "stash-1").mkdir(parents=True)
        (tmp / "compileReleaseJavaWithJavac").mkdir()
        (tmp / "kaptGenerateStubsDebugKotlin").mkdir()
        (tmp / ".cache" / "reports").mkdir(parents=True)
        (tmp / "jar_extract_15213301627954921517_tmp").mkdir()
        (tmp / "testDebugUnitTest").mkdir()
        (build / "intermediates" / "merged" / "tmp" / "foo").mkdir(parents=True)
        return build

    with tempfile.TemporaryDirectory(prefix="zd607-selftest-") as td:
        build = build_tree(td)

        # ① 判据①：只有构建系统 tmp ⇒ 计数必须是 0
        code, out, total, _ = run([build], SYNTH_BUDGET, "合成树", "ci")
        ok = (total == 0 and code == 0)
        results.append((
            ok,
            "[①] 造 6 个 Gradle 式 tmp（compile*/kapt*/.cache/jar_extract/intermediates）"
            "→ 期望计数 0，实得 %d（exit=%d）" % (total, code),
            out,
        ))

        # ② 判据②：造一个测试沙盒 ⇒ 计数 +1（且只算根，不算内部层级）
        sb = build / "tmp" / "testDebugUnitTest" / "zd607-demo123456789"
        (sb / "inner" / "deeper").mkdir(parents=True)
        code, out, total, _ = run([build], SYNTH_BUDGET, "合成树", "ci")
        ok = (total == 1 and code == 0)
        results.append((
            ok,
            "[②] 再加 1 个测试沙盒（内部两层）→ 期望计数 +1 = 1，实得 %d（exit=%d）" % (total, code),
            out,
        ))

        # ②bcd 反例：伪装成任务名 / 相对路径 / 驼峰带长数字 —— 三种都必须计数
        (build / "tmp" / "testDebugUnitTest" / "compileDebugJavaWithJavac").mkdir()
        (build / "tmp" / "repair408-aaa").mkdir()
        (build / "tmp" / "junit8602780560656064424").mkdir()
        code, out, total, _ = run([build], SYNTH_BUDGET, "合成树", "ci")
        ok = (total == 4)
        results.append((
            ok,
            "[②bcd] 再塞 3 个「像任务名/相对路径/长数字」的测试目录 → 期望计数 4，实得 %d" % total,
            out,
        ))

        # ③ 判据③：超预算在 CI 模式必须红，在本地模式必须只是 WARN（exit 0）
        for i in range(SYNTH_BUDGET - 1):
            (build / "tmp" / "testDebugUnitTest" / ("bulk-%02d-123456789" % i)).mkdir()
        code_ci, out_ci, total, _ = run([build], SYNTH_BUDGET, "合成树", "ci")
        code_local, out_local, _, _ = run([build], SYNTH_BUDGET, "合成树", "local")
        hit_fail = [x for x in out_ci if x.startswith("结果: FAIL") and "超预算" in x]
        hit_warn = [x for x in out_local if x.startswith("结果: WARN") and "超预算" in x]
        ok = (
            total > SYNTH_BUDGET and code_ci == 1 and len(hit_fail) == 1
            and code_local == 0 and len(hit_warn) == 1
        )
        results.append((
            ok,
            "[③] 计数 %d > 预算 %d → 期望 CI 红(1) 且本地 WARN(0)，"
            "实得 ci=%d local=%d / 命中 %d/%d"
            % (total, SYNTH_BUDGET, code_ci, code_local, len(hit_fail), len(hit_warn)),
            out_ci + ["--local--"] + out_local,
        ))

        # ④ 判据④：预算 = 基线 + 余量，脚本自算（防止有人把 budget 写死回去）
        fake_repo = Path(td) / "repo"
        (fake_repo / "tools").mkdir(parents=True)
        # 4a 无水文件 ⇒ 退回默认线
        b0, m0, budget0, _ = load_baseline(fake_repo)
        # 4b 有基线 7 ⇒ 预算必须自己算出 27（不是文件里写的数字）
        baseline_file = fake_repo / BUDGET_FILE
        baseline_file.write_text(
            json.dumps({"measured": 7, "measuredAt": "2026-11-12", "budget": 999}),
            encoding="utf-8",
        )
        b1, m1, budget1, source1 = load_baseline(fake_repo)
        ok = (
            m0 == m1 == MARGIN == 20
            and budget0 == DEFAULT_BUDGET
            and b1 == 7 and budget1 == 27
            and "已弃用" in source1   # 旧 budget 字段（999）不许被采信
        )
        results.append((
            ok,
            "[④] 预算自算：无水文件 %d；基线 7 + 余量 %d 期望 27，实得 %d"
            "（文件里的旧 budget=999 必须被忽略）" % (budget0, MARGIN, budget1),
            ["余量常量 MARGIN=%d" % MARGIN, "算式: %d + %d = %d" % (b1, m1, budget1)],
        ))

    ok = all(r[0] for r in results)
    print("=== --selftest（全程合成树，不读真仓） ===")
    for good, headline, out in results:
        print(("  [OK] " if good else "  [FAIL] ") + headline)
        for line in out:
            if line.startswith(("结果:", "读数:", "预算算式:", "--local--", "余量", "算式")):
                print("      " + line)
    print("自测结论:", "PASS 判据①②③④ 全对" if ok else "FAIL")
    return 0 if ok else 1


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", type=Path, default=repo, help="仓库根（默认脚本上级的上级）")
    ap.add_argument("--build", type=Path, help="只量这个构建目录（默认自动发现 <module>/build）")
    ap.add_argument("--budget", type=int, default=None,
                    help="覆盖预算（演练用；正常走 measured+%d）" % MARGIN)
    ap.add_argument("--list", action="store_true", help="只打印读数与逐锚点明细，不判红")
    ap.add_argument("--scan-tests", action="store_true", help="列零清理痕迹的测试文件（启发式）")
    ap.add_argument("--os-temp", action="store_true", help="数系统临时区里本仓测试前缀的目录（只报不判）")
    ap.add_argument("--selftest", action="store_true", help="合成树夹具（判据①②③④）")
    ap.add_argument("--strict", action="store_true", help="本地也按 CI 判红（默认本地只提示）")
    ap.add_argument("--record", action="store_true", help="把本轮读数追加到 %s" % TREND_FILE)
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    repo_root = args.repo.resolve()
    if args.build:
        build_roots = [args.build.resolve()]
    else:
        build_roots = module_build_dirs(repo_root) or [(repo_root / "app" / "build")]

    if args.os_temp:
        mine, total = count_os_temp(Path(tempfile.gettempdir()))
        print("系统临时区：本仓测试前缀目录 %d 个（条目总数 %d）" % (mine, total))
        print("口径：只报不判——钉住 java.io.tmpdir 后，这个数每轮增量应≈0；涨了说明钉住被拆了。")
        return 0

    if args.scan_tests:
        suspects = scan_tests(repo_root)
        print("零清理痕迹、却建临时目录的测试文件: %d 个" % len(suspects))
        for rel, hits in suspects:
            print("  %s  （建目录 %d 处）" % (rel, hits))
        return 0

    baseline, _margin, budget, source = load_baseline(repo_root)
    if args.budget is not None:
        # 命令行覆盖时不再显示「基线 + 余量」算式 —— 否则算式与预算自相矛盾
        budget, source, baseline = args.budget, "命令行覆盖（--budget）", None
    mode = evaluate_mode(args.strict)

    if args.list:
        total, sandboxes, per_anchor = measure(build_roots)
        print("读数: 测试自建临时目录 %d 个（预算 %d｜%s｜%s 模式）" % (total, budget, source, mode))
        print("口径: 只数测试建的沙盒根；构建系统 tmp 不计")
        for anchor, n, skipped in per_anchor:
            print("  %-46s 沙盒 %3d｜构建系统目录(不计) %d" % (anchor.as_posix(), n, skipped))
        for sb in sandboxes[:20]:
            print("    · %s" % sb.as_posix())
        if len(sandboxes) > 20:
            print("    … 另有 %d 个" % (len(sandboxes) - 20))
        return 0

    code, lines, total, _ = run(build_roots, budget, source, mode, baseline)
    for line in lines:
        print(line)
    if args.record:
        _wrote, note = record_trend(repo_root, total, budget, baseline, mode)
        print("趋势: " + note)
    return code


if __name__ == "__main__":
    sys.exit(main())
