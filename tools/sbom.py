#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sbom.py —— 生成证道 :app 的 CycloneDX 1.5 SBOM（第四十三批 · 任务 A）。

为什么
------
供应链可见的前提是知道「到底发了什么出去」。此前仓库只有三样与产物相关的门禁
（`tools/check-licenses.py` 正文逐字节、`tools/check-r8-mapping.py`、Lint 预算闸），
都答不出这个问题：**APK 里的第三方构件有哪些、什么版本、谁把谁引了进来**。
本脚本把「解析闭包」固化成 SBOM 文件，供漏洞扫描（tools/osv-scan.py）与
许可/版本报告（tools/license-report.py）消费。

主清单与分区（纪律：构建工具链、测试依赖不许混进主 SBOM）
---------------------------------------------------------
* **release 节 = 主 SBOM**：`:app` 的 `releaseRuntimeClasspath` 解析闭包
  （131+ 个构件，含传递依赖 —— okio/kotlin-stdlib 这类间接依赖同样是漏洞面），
  `scope = required`；
* debug / test / androidTest / benchmark / macrobenchmark 五节为**分区**，
  以 `scope = optional` ＋ `properties: zhengdao:section` 单列，不进主清单。

实现路径（红线合规：不动构建脚本、不升 AGP/Kotlin、不引第三方插件）
------------------------------------------------------------------
依赖闭包由 `tools/sbom-init.gradle.kts`（`-I` 注入的 init script）解析
`resolutionResult` 后导出 JSON；本脚本只消费该 JSON 并翻译成 CycloneDX。
init script 已在 Gradle 9.6 ＋ AGP 9.4 ＋ JDK 25 实测通过（2026-10-19）。
派工单备选的降级路径（解析 `gradlew :app:dependencies` 文本）未启用——
init script 方案实测一次通过，不为假设的失败留死代码。

确定性
------
serialNumber 由内容哈希派生（uuid5），同样的闭包生成同样的 SBOM 号；
构件、依赖边按坐标排序；除 `metadata.timestamp` 外无易变字段。

用法
----
    python3 tools/sbom.py [--out DIR] [--commit SHORT|--commit-sha FULL]
                          [--no-gradle-dump] [--keep-resolved]

  默认在仓库 build/sbom/ 下写：
    resolved.json                         init script 的原始闭包（中间产物）
    sbom-<commit>-<yyyymmdd>.cdx.json     CycloneDX 1.5 SBOM（CI artifact 取这个）
  `--no-gradle-dump` 表示不跑 Gradle，直接用已有的 resolved.json（CI 里 Gradle
  与 Python 分步骤跑时用）。

退出码：0 = 成功；2 = 环境错误（找不到 gradlew / resolved.json / 解析失败）。
本脚本 report-only：发现不了漏洞、不判红（见 docs/QUALITY-CONTRACT.md §2）。
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INIT_SCRIPT = ROOT / "tools" / "sbom-init.gradle.kts"
DEFAULT_BUILD_DIR = ROOT / "build" / "sbom"

# 分节 → 是否进主 SBOM。主清单只认 release（打进 release APK 的运行时闭包）。
MAIN_SECTION = "release"
# 分区顺序（写进 SBOM 的顺序，也是人读报告的顺序）
SECTION_ORDER = ["release", "debug", "test", "androidTest", "benchmark", "macrobenchmark"]

APP_GROUP = "com.example.zhengdao"
APP_NAME = "zhengdao"
# 版本号单点定义：app/build.gradle.kts:248 `val appVersionName = "2.1.2"`
APP_VERSION_FILE = ROOT / "app" / "build.gradle.kts"

SECTION_LABELS = {
    "release": "release 运行时闭包（主清单：打进 release APK 的全部第三方构件）",
    "debug": "debug 变体运行时闭包（分区）",
    "test": "debug 单元测试 classpath（分区）",
    "androidTest": "debug 仪器测试 classpath（分区）",
    "benchmark": ":app benchmark 变体运行时闭包（分区：Macrobenchmark 专用，不进 release）",
    "macrobenchmark": ":macrobenchmark 模块闭包（分区：测试 APK 自身依赖，不进 release）",
}


def git_short_commit() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            cwd=str(ROOT), check=False,
        )
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except OSError:
        pass
    return "unknown"


def today() -> str:
    return _dt.datetime.now().strftime("%Y%m%d")


def read_app_version() -> str:
    """从 app/build.gradle.kts 读版本号单点定义（不与构建脚本耦合地抄出来）。"""
    try:
        text = APP_VERSION_FILE.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return "unknown"
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("val appVersionName"):
            # val appVersionName = "2.1.2"
            _, _, rest = stripped.partition("=")
            return rest.strip().strip('"').strip("'")
    return "unknown"


def gradle_command() -> list[str]:
    """平台适配：Windows 上 ./gradlew 是 shell 脚本，CreateProcess 直不起，
    必须走 cmd /c gradlew.bat；Linux/macOS（CI runner）用 ./gradlew。"""
    if sys.platform == "win32":
        return ["cmd", "/c", "gradlew.bat"]
    return ["./gradlew"]


def run_gradle_dump(out_dir: Path) -> Path:
    """跑 init script 拿解析闭包。返回 resolved.json 路径。"""
    resolved = out_dir / "resolved.json"
    cmd = gradle_command() + [
        "-I", "tools/sbom-init.gradle.kts",
        ":sbomDump",
        f"-PsbomOut={resolved.as_posix()}",
        "--no-configuration-cache",
        "--console=plain",
    ]
    print("[sbom] 运行 Gradle 解析闭包：" + " ".join(cmd[1:]))
    proc = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", check=False)
    tail = "\n".join((proc.stdout or "").splitlines()[-8:])
    if tail:
        print(tail)
    if proc.returncode != 0 or not resolved.exists():
        print("::error title=SBOM::Gradle 闭包解析失败（退出码 %d）" % proc.returncode)
        err = "\n".join((proc.stderr or "").splitlines()[-12:])
        if err:
            print(err)
        raise SystemExit(2)
    return resolved


def bom_ref(group: str, name: str, version: str, section: str) -> str:
    """主清单构件用规范 purl 作 bom-ref；分区构件加 #section 后缀保证唯一。"""
    purl = "pkg:maven/%s/%s@%s" % (group, name, version)
    return purl if section == MAIN_SECTION else "%s#%s" % (purl, section)


def build_cyclonedx(dump: dict, commit: str, timestamp: str) -> dict:
    sections = dump.get("sections", {})
    if MAIN_SECTION not in sections or "error" in sections.get(MAIN_SECTION, {}):
        print("::error title=SBOM::闭包里没有可用的 release 节（%s）"
              % json.dumps(sections.get(MAIN_SECTION), ensure_ascii=False))
        raise SystemExit(2)

    main_components = []
    main_by_gav = {}
    for comp in sections[MAIN_SECTION]["components"]:
        gav = "%s:%s:%s" % (comp["group"], comp["name"], comp["version"])
        main_by_gav[gav] = comp
        main_components.append({
            "type": "library",
            "bom-ref": bom_ref(comp["group"], comp["name"], comp["version"], MAIN_SECTION),
            "purl": "pkg:maven/%s/%s@%s" % (comp["group"], comp["name"], comp["version"]),
            "group": comp["group"],
            "name": comp["name"],
            "version": comp["version"],
            "scope": "required",
            "properties": [{"name": "zhengdao:section", "value": MAIN_SECTION}],
        })

    # 分区构件：每个坐标一条，#supp 后缀 bom-ref 与主清单 purl bom-ref 不冲突；
    # 用多个 zhengdao:section property 列全它出现的节（debug/test 等闭包高度重叠，
    # 每条坐标只写一次、节集合完整，人读与机读都不缺）。
    supp_sections_by_gav: dict[str, list[str]] = {}
    supp_comps_by_gav: dict[str, dict] = {}
    for section in SECTION_ORDER:
        if section == MAIN_SECTION:
            continue
        info = sections.get(section)
        if not info or "error" in info:
            continue
        for comp in info["components"]:
            gav = "%s:%s:%s" % (comp["group"], comp["name"], comp["version"])
            supp_sections_by_gav.setdefault(gav, []).append(section)
            supp_comps_by_gav[gav] = comp
    supp_components = []
    for gav in sorted(supp_sections_by_gav):
        comp = supp_comps_by_gav[gav]
        section_list = supp_sections_by_gav[gav]
        supp_components.append({
            "type": "library",
            "bom-ref": "%s#supp" % bom_ref(comp["group"], comp["name"], comp["version"], MAIN_SECTION),
            "purl": "pkg:maven/%s/%s@%s" % (comp["group"], comp["name"], comp["version"]),
            "group": comp["group"],
            "name": comp["name"],
            "version": comp["version"],
            "scope": "optional",
            "properties": [{"name": "zhengdao:section", "value": s} for s in section_list],
        })

    # 依赖图：只覆盖主清单（release 闭包）——分区的边不进主图，SBOM 里注明。
    ref_by_gav = {}
    for comp in main_components:
        gav = "%s:%s:%s" % (comp["group"], comp["name"], comp["version"])
        ref_by_gav[gav] = comp["bom-ref"]
    root_ref = "%s/%s@%s" % (APP_GROUP, APP_NAME, read_app_version())
    depends_on: dict[str, set[str]] = {root_ref: set()}
    for gav, ref in ref_by_gav.items():
        depends_on.setdefault(ref, set())
    for edge in sections[MAIN_SECTION].get("edges", []):
        src, dst = edge[0], edge[1]
        src_ref = root_ref if src == "root" else ref_by_gav.get(src)
        dst_ref = ref_by_gav.get(dst)
        if src_ref is None or dst_ref is None:
            continue
        depends_on[src_ref].add(dst_ref)
    dependencies = [
        {"ref": ref, "dependsOn": sorted(children)}
        for ref, children in sorted(depends_on.items()) if children
    ]

    section_counts = {
        section: (sections.get(section) or {}).get("componentCount", 0)
        for section in SECTION_ORDER
        if sections.get(section) and "error" not in sections[section]
    }
    section_errors = {
        section: info.get("error")
        for section, info in sections.items() if "error" in info
    }

    # serialNumber 由内容派生：同样闭包 ⇒ 同样 SBOM 号（可复现比对）
    content = json.dumps([main_components, supp_components, dependencies],
                         sort_keys=True, ensure_ascii=False)
    serial = str(uuid.uuid5(uuid.NAMESPACE_URL, "zhengdao-sbom:" + hashlib.sha256(
        content.encode("utf-8")).hexdigest()))

    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:" + serial,
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "tools": {
                "components": [{
                    "type": "application",
                    "group": "zhengdao",
                    "name": "tools/sbom.py",
                    "version": "43-batch",
                }],
            },
            "component": {
                "type": "application",
                "bom-ref": root_ref,
                "group": APP_GROUP,
                "name": APP_NAME,
                "version": read_app_version(),
                "purl": "pkg:generic/%s/%s@%s" % (APP_GROUP, APP_NAME, read_app_version()),
            },
            "properties": [
                {"name": "zhengdao:baseline-commit", "value": commit},
                {"name": "zhengdao:generator", "value": "tools/sbom.py + tools/sbom-init.gradle.kts"},
                {"name": "zhengdao:gradle-version", "value": str(dump.get("gradleVersion", "?"))},
                {"name": "zhengdao:main-section", "value": MAIN_SECTION},
                {"name": "zhengdao:section-counts",
                 "value": json.dumps(section_counts, ensure_ascii=False)},
                {"name": "zhengdao:section-errors",
                 "value": json.dumps(section_errors, ensure_ascii=False)},
                {"name": "zhengdao:dependencies-scope",
                 "value": "dependencies 节只覆盖主清单（release 闭包）的依赖图"},
            ],
        },
        "components": main_components + supp_components,
        "dependencies": dependencies,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 :app 的 CycloneDX 1.5 SBOM（report-only）")
    ap.add_argument("--out", type=Path, default=DEFAULT_BUILD_DIR, help="输出目录")
    ap.add_argument("--commit", default=None,
                    help="基线 commit 短号（默认 git rev-parse --short HEAD）")
    ap.add_argument("--no-gradle-dump", action="store_true",
                    help="不跑 Gradle，直接用 --out 下已有的 resolved.json")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    resolved = args.out / "resolved.json"
    if not args.no_gradle_dump:
        resolved = run_gradle_dump(args.out)
    if not resolved.exists():
        print("::error title=SBOM::找不到闭包文件 %s（先跑一次不带 --no-gradle-dump 的命令）"
              % resolved)
        return 2
    try:
        dump = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print("::error title=SBOM::闭包文件读不了/不是 JSON：%s" % exc)
        return 2

    commit = args.commit or git_short_commit()
    timestamp = _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    sbom = build_cyclonedx(dump, commit, timestamp)

    out_name = "sbom-%s-%s.cdx.json" % (commit, today())
    out_path = args.out / out_name
    out_path.write_text(json.dumps(sbom, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    main_count = sum(1 for c in sbom["components"] if c["scope"] == "required")
    supp_count = len(sbom["components"]) - main_count
    print("[sbom] 写入 %s" % out_path)
    print("[sbom] 主清单（release 闭包）%d 个构件；分区构件 %d 个；依赖边 %d 条"
          % (main_count, supp_count, len(sbom["dependencies"])))
    print("[sbom] serialNumber=%s（内容派生，同闭包同号）" % sbom["serialNumber"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
