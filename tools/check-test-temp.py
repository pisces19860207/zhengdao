#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-test-temp.py —— 测试临时目录不许无限攒（564，2026-11-09）。

## 为什么要有这道闸

单测里有 163 处 `Files.createTempDirectory(…)`、60 处 `createTempFile(…)`，另有一批直接写
`File("build/tmp/…")` 的相对路径（例：`baopu/inspect/DataRepairSweepTest.kt:28`）。
其中相当一部分**跑完不删**。它们落在 Gradle 给测试 JVM 的临时根
（`app/build/tmp/testDebugUnitTest`）或模块 `build/tmp/` 下，于是：

  * 每跑一轮就多一棵树，`app/build` 里的文件数上万地涨；
  * `clean`/`rebuild`/CI 缓存上传都跟着变慢、变大；
  * **编译、单测、detekt、lint 全绿** —— 这是「磁盘卫生」的裂缝，没有任何断言盯着它。

本闸把「攒到多少算失控」变成一条会红的数字，配合 `testkit/TempSandbox.kt`
（建了必删，删不干净必响）把这笔账从「靠自觉」变成「靠读数」。

## 判据（只有一条）

单测跑完后，`app/build` 下**名字以 `tmp` 开头**的目录区（含嵌套，如
`app/build/tmp/testDebugUnitTest`）里的**目录总数** `> 预算`（默认 50）⇒ 退出 1。

为什么要算整片 `tmp*` 而不是只算 `testDebugUnitTest`：漏出来的临时目录不止一条来路——
Kotlin 的 `Files.createTempDirectory` 走 `java.io.tmpdir`，手写相对路径走 `build/tmp/`，
还有 androidTest 的 `build/tmp/<task>`。按**区域**数，来路变了也漏不掉。

预算取 50 而不是 0：Gradle 自己也在 `app/build/tmp` 下放东西（`.cache`、
`compileDebugJavaWithJavac` 的事务目录…），实测一次干净构建后约 16 个目录；
50 留出「一两个用例的沙盒同时在跑」的余量，又不至于让一棵漏树的几百个目录藏进去。
真要把预算收紧，改 `DEFAULT_BUDGET` 并在报告里附一次实测读数，不许凭感觉调。

## 用法

    python3 tools/check-test-temp.py                 # 查真仓；超预算 exit 1
    python3 tools/check-test-temp.py --budget 30     # 临时收紧（演练用）
    python3 tools/check-test-temp.py --list          # 只打印每片 tmp* 的目录数，不判红
    python3 tools/check-test-temp.py --scan-tests    # 列「建临时目录却没清理痕迹」的测试文件
    python3 tools/check-test-temp.py --os-temp       # 数系统临时区（只报读数，验证 tmpdir 钉住了）
    python3 tools/check-test-temp.py --selftest      # 合成树：预算内绿、超一条红（各一例）

`--selftest` 全程用临时目录里的合成树，**不读真仓**（否则「仓库恰好干净」会把闸的自测也染绿）。

## `--scan-tests` 的口径（这是清单，不是门禁）

只按**文字痕迹**判：文件里出现过建临时目录/文件的调用，且**一处清理痕迹都没有**
（`deleteRecursively` / `deleteOnExit` / `deleteIfExists` / `@After` / `TempSandbox` / `rmTree`）。
启发式，只用来排改造顺序：有痕迹的相对干净（不等于删对了），没痕迹的进改造清单。
它**不参与判红** —— 静态猜「删没删对」必然误判，真正的判据永远是上一条目录计数。

CI：`.github/workflows/ci.yml` 的 verify job 在「全量单元测试」之后、注解步骤之前跑本脚本。

## `--os-temp`：为什么还要看系统临时目录

564 动手前在本机实测（2026-11-09）：`%TEMP%` 里躺着 **84,594** 个本仓测试前缀的目录
（`baopu-*`／`zd-*`／`opinion-*`／`persona-*`…），条目总数 105,970，清理掉 79,765 个
（其余是 3 小时内动过的在飞用例）。根因：Gradle 的 Test 任务**默认不设置** `java.io.tmpdir`，
`Files.createTempDirectory` 一律落到系统临时区 ⇒ 只看 `app/build` 的闸一个都数不到。

本批的处置：`app/build.gradle.kts` 的 `tasks.withType<Test>` 里把 `java.io.tmpdir` 钉到
`app/build/tmp/testDebugUnitTest`（钉住之后 CI 的目录计数才有意义），并用 `--os-temp`
做**回归读数**：钉住了 ⇒ 这个数每轮增量 ≈ 0；哪天有人把那条 systemProperty 删了，
这个数会重新开始涨——它是"闸被人拆了"的报警器。

`--os-temp` 只报读数、不判红：CI runner 的 `/tmp` 是共享的，别人留下的东西不该算在我们头上。
"""

import argparse
import json
import re
import shutil
import sys
import tempfile
import time
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，而不是让门禁自己崩
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

# 预算：见模块头「为什么是 50」。改它必须附一次实测读数（报告里写清哪棵树、跑了几轮）。
DEFAULT_BUDGET = 50

# 水位文件：实测存量比 50 大得多（564 交付时一轮就是 1,746），所以按「只降不升」冻结现水位——
# 与 detekt 预算闸同一房型（266）。文件不存在 ⇒ 退回 DEFAULT_BUDGET（全新仓库的干净线）。
BUDGET_FILE = "tools/test-temp-budget.json"

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


def count_os_temp(root: Path) -> tuple[int, int]:
    """返回 (系统临时区里本仓测试前缀的目录数, 全部条目数)。只数不删。"""
    if not root.is_dir():
        return 0, 0
    entries = list(root.iterdir())
    mine = sum(1 for p in entries if p.is_dir() and OS_TEMP_RE.match(p.name))
    return mine, len(entries)


def load_budget(repo: Path) -> tuple[int, str]:
    """返回 (预算, 出处)。水位文件读不出 ⇒ 退回 DEFAULT_BUDGET，并且**说清是退回的**。"""
    f = repo / BUDGET_FILE
    if not f.is_file():
        return DEFAULT_BUDGET, "默认（无水文件）"
    try:
        data = json.loads(f.read_text(encoding="utf-8"))
        value = data.get("budget")
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
            return value, "%s（实测 %s @ %s）" % (
                BUDGET_FILE, data.get("measured"), data.get("measuredAt"),
            )
        return DEFAULT_BUDGET, "默认（水文件 schema 不对：budget 不是非负整数）"
    except (ValueError, OSError) as exc:
        return DEFAULT_BUDGET, "默认（水文件读不出：%s）" % exc


def tmp_anchors(root: Path) -> list[Path]:
    """`root` 下所有以 tmp 开头的目录（锚点）。不存在 root ⇒ 空。

    2026-11-10（ci #1936 红出来的口径问题）：**构建系统自己的 tmp 不算数**。
    `intermediates/.../tmp` 是 AGP 的中间产物目录，数量随 Gradle 版本/平台浮动，
    不是「测试跑完不删」的泄漏；把它算进水位数，会让水位在换平台时凭空 +1（424→425）。
    这里只留真正的暂存区锚点（如 `<build>/tmp`、`<build>/tmp/testDebugUnitTest/...`）。"""
    if not root.is_dir():
        return []
    return sorted(
        p
        for p in root.rglob("*")
        if p.is_dir() and p.name.startswith("tmp") and "intermediates" not in p.parts
    )


def _is_build_tool_dir(path: Path) -> bool:
    """构建系统自己的 tmp（不是测试泄漏）：javac 事务目录、Gradle 的 .cache 等。"""
    parts = path.parts
    if ".cache" in parts:
        return True
    return any(p.startswith("compileDebug") or p.startswith("compileRelease") for p in parts)


def count_dirs(root: Path) -> tuple[int, list[tuple[Path, int]]]:
    """返回 (总目录数, [(锚点, 该锚点新增计入的目录数)])。

    嵌套锚点（`tmp/xxx/tmp`）只计一次：用 seen 集合去重，避免上层锚点把下层重复数一遍。
    """
    seen: set[Path] = set()
    per_anchor: list[tuple[Path, int]] = []
    for anchor in tmp_anchors(root):
        added = 0
        for path in [anchor, *anchor.rglob("*")]:
            # JDK 的 `jar_extract_*_tmp`：ZipFS 解包时的临时目录，由 JDK 自己回收，
            # 不是测试写的临时目录（数量随 JDK/平台浮动）⇒ 不计入水位。
            # 549 验收（蓝总管 2026-11-12）：口径补漏——`compileDebug*`（含 `compileTransaction`
            # 事务目录）与 `.cache` 是**构建系统自己的** tmp，跑不跑 detekt/编译会让它们出现/消失；
            # 573/ci#1936 的原口径就是「构建系统自己的 tmp 不算数」，这里把这两个也排除（不改预算）。
            if _is_build_tool_dir(path):
                continue
            if path.name.startswith("jar_extract_"):
                continue
            if path.is_dir() and path not in seen:
                seen.add(path)
                added += 1
        per_anchor.append((anchor, added))
    return len(seen), per_anchor


def evaluate(
    total: int,
    budget: int,
    source: str = "",
    newest: list[tuple[str, float]] | None = None,
) -> tuple[int, list[str]]:
    """核心判据。返回 (exit_code, 输出行)。判红只看 total > budget。

    2026-11-11：判红时把 `newest`（最新建的若干个目录，路径＋时间戳）印出来。
    起因是 ci #1945 报「超预算 2 个」却只有总数、没有名字，只能靠"猜哪条测试漏了"；
    迁移水位这种事，名字比总数有用得多。"""
    lines = [
        "读数: app/build 下 tmp* 目录区共 %d 个目录（预算 %d%s）"
        % (total, budget, ("｜" + source) if source else "")
    ]
    if total > budget:
        lines.append(
            "结果: FAIL（超预算 %d 个）—— 有测试跑完不删临时目录；改用 "
            "testkit/TempSandbox 的 use{} 收口，或 --scan-tests 找出强嫌疑文件"
            % (total - budget)
        )
        if newest:
            lines.append("最新建的目录（越靠前越新；先查它们对应的测试）：")
            for rel, ts in newest:
                stamp = time.strftime("%m-%d %H:%M:%S", time.localtime(ts)) if ts else "?"
                lines.append("  %s  %s" % (stamp, rel))
        return 1, lines
    if total < budget:
        lines.append("提示: 比水位低 %d 个——迁移有进展就把 %s 的 budget 降下来（只降不升）"
                     % (budget - total, BUDGET_FILE))
    lines.append("结果: PASS（%d ≤ %d）" % (total, budget))
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


def newest_dirs(build: Path, limit: int = 8) -> list[tuple[str, float]]:
    """按 mtime 取最新的 limit 个目录（相对 build 的路径）。判红时用来直接点名。"""
    items: list[tuple[str, float]] = []
    try:
        for d in build.rglob("*"):
            if not d.is_dir() or d.name.startswith("jar_extract_"):
                continue
            try:
                items.append((d.relative_to(build).as_posix(), d.stat().st_mtime))
            except OSError:
                continue
    except OSError:
        return []
    items.sort(key=lambda kv: kv[1], reverse=True)
    return items[:limit]


NOISE_DIR_NAMES = {
    "reports",
    "test-results",
    "outputs",
    "logs",
    "intermediates",
    "testDebugUnitTest",
    ".cache",
}


def leaked_hint(build: Path, limit: int = 10) -> list[tuple[str, float]]:
    """判红时点名用：只留「像测试沙盒」的目录。

    `newest_dirs` 的第一版把 `reports/`、`test-results/` 这些**测试跑完之后才生成**的
    产物目录排在最前（ci #1947 实测），等于没线索。这里跳过构建/报告产物，
    只看 mtime 最新的疑似泄漏目录（名字里带随机数后缀的优先）。"""
    items: list[tuple[str, float]] = []
    try:
        for d in build.rglob("*"):
            if not d.is_dir():
                continue
            parts = set(d.relative_to(build).parts)
            if parts & NOISE_DIR_NAMES:
                continue
            if d.name.startswith("jar_extract_") or d.name.startswith("compileDebug"):
                continue
            try:
                items.append((d.relative_to(build).as_posix(), d.stat().st_mtime))
            except OSError:
                continue
    except OSError:
        return []
    items.sort(key=lambda kv: kv[1], reverse=True)
    return items[:limit]


def run(repo: Path, build: Path, budget: int, source: str = "") -> tuple[int, list[str]]:
    if not build.is_dir():
        return 0, [
            "读数: %s 不存在（尚未构建）—— 本闸只在单测之后有意义，此处按 0 计"
            % build.as_posix(),
            "结果: PASS（跳过判红）",
        ]
    total, per_anchor = count_dirs(build)
    code, lines = evaluate(total, budget, source, leaked_hint(build))
    for anchor, added in per_anchor:
        lines.append("  %-58s %4d" % (anchor.as_posix(), added))
    return code, lines


SYNTH_POSITIVE_TOTAL = DEFAULT_BUDGET          # 恰好等于预算 ⇒ 必须绿（判据是「>」）
SYNTH_NEGATIVE_TOTAL = DEFAULT_BUDGET + 1      # 超一条 ⇒ 必须红


def selftest() -> int:
    """合成树正例＋负例各一条；不读真仓。"""
    results: list[tuple[bool, str, list[str]]] = []
    with tempfile.TemporaryDirectory(prefix="zd564-selftest-") as td:
        build = Path(td) / "app" / "build"

        def make_tree(n: int) -> None:
            """造 n 个目录：锚点自身与 Gradle 风格子目录占 4 个，其余当泄漏项补齐。"""
            if build.exists():
                shutil.rmtree(build)
            anchor = build / "tmp" / "testDebugUnitTest"
            anchor.mkdir(parents=True)
            (build / "tmp" / ".cache").mkdir()
            (build / "tmp" / "compileDebugJavaWithJavac").mkdir()
            # 锚点自身＋.cache＋compile 已占 5 个（tmp / testDebugUnitTest / .cache /
            # compile / 各自的下级按需），剩下的用泄漏项补齐到 n。
            total, _ = count_dirs(build)
            for i in range(n - total):
                (anchor / ("junit%04d" % i)).mkdir()

        # 正例：刚好等于预算 ⇒ 绿
        make_tree(SYNTH_POSITIVE_TOTAL)
        total, _ = count_dirs(build)
        code, out = run(Path(td), build, DEFAULT_BUDGET)
        ok_pos = code == 0 and total <= DEFAULT_BUDGET
        results.append((
            ok_pos,
            "[正例] 合成 %d 个目录（= 预算）→ 期望绿(0)，实得 %d / 计数 %d"
            % (SYNTH_POSITIVE_TOTAL, code, total),
            out,
        ))

        # 负例：多一个 ⇒ 必须红，且红在「超预算」这句上
        make_tree(SYNTH_NEGATIVE_TOTAL)
        total, _ = count_dirs(build)
        code, out = run(Path(td), build, DEFAULT_BUDGET)
        hit = [line for line in out if line.startswith("结果: FAIL") and "超预算" in line]
        ok_neg = code == 1 and total > DEFAULT_BUDGET and len(hit) == 1
        results.append((
            ok_neg,
            "[负例] 合成 %d 个目录（= 预算 + 1）→ 期望红(1) 且命中超预算一行，"
            "实得 exit=%d / 计数 %d / 命中 %d"
            % (SYNTH_NEGATIVE_TOTAL, code, total, len(hit)),
            out,
        ))

    ok = all(r[0] for r in results)
    print("=== --selftest（全程合成树，不读真仓） ===")
    for good, headline, out in results:
        print(("  [OK] " if good else "  [FAIL] ") + headline)
        for line in out:
            if line.startswith(("结果:", "读数:")):
                print("      " + line)
    print("自测结论:", "PASS 正例绿、负例红" if ok else "FAIL")
    return 0 if ok else 1


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", type=Path, default=repo, help="仓库根（默认脚本上级的上级）")
    ap.add_argument("--build", type=Path, help="构建目录（默认 <repo>/app/build）")
    ap.add_argument("--budget", type=int, default=None,
                    help="目录数上限（默认读 %s，缺文件时 %d）" % (BUDGET_FILE, DEFAULT_BUDGET))
    ap.add_argument("--list", action="store_true", help="只打印读数，不判红")
    ap.add_argument("--scan-tests", action="store_true", help="列零清理痕迹的测试文件（启发式）")
    ap.add_argument("--os-temp", action="store_true", help="数系统临时区里本仓测试前缀的目录（只报不判）")
    ap.add_argument("--selftest", action="store_true", help="合成树正例＋负例各一条")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    repo_root = args.repo.resolve()
    build = (args.build or (repo_root / "app" / "build")).resolve()

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

    if args.list:
        budget, source = load_budget(repo_root) if args.budget is None else (args.budget, "命令行")
        total, per_anchor = count_dirs(build)
        print("读数: %d 个目录（预算 %d｜%s）" % (total, budget, source))
        for anchor, added in per_anchor:
            print("  %-58s %4d" % (anchor.as_posix(), added))
        return 0

    budget, source = load_budget(repo_root) if args.budget is None else (args.budget, "命令行")
    code, lines = run(repo_root, build, budget, source)
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
