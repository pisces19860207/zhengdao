#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-sbom-direct-deps.py —— 断言 SBOM 覆盖了全部直接依赖（第四十三批 · 任务 A 验收）。

为什么
------
派工单验收下限是「SBOM 至少列出项目全部直接依赖」。直接依赖以两种形态散落在
两处：`app/build.gradle.kts` 的 dependencies 块（字面坐标 `implementation("g:n:v")`
与 catalog 访问器 `implementation(libs.xxx.yyy)` 混用）与 `gradle/libs.versions.toml`
的 [libraries]/[versions]。人眼对一遍当时对、下次改依赖就对不上了——本脚本把它
做成机器断言：

  1. 从 app/build.gradle.kts ＋ libs.versions.toml 提取每条直接依赖及其 configuration；
  2. 按 configuration 映射到 SBOM 分区（implementation→release、
     benchmarkImplementation→benchmark、testImplementation→test、
     androidTestImplementation→androidTest、debugImplementation→debug）；
  3. 逐条检查坐标是否出现在对应分区的 components 里；catalog 里钉了版本的
     （非 BOM 托管）还比版本号。

已知边界（写进输出，不静默）
----------------------------
* `add("detektCli", libs.detekt.cli)`（io.gitlab.arturbosch.detekt:detekt-cli）是
  构建期工具，不进任何运行时 classpath，SBOM 不含它是**正确的**——断言跳过并单列；
* BOM 托管版本（compose 的 ui/material3/ui-graphics/ui-tooling-preview 在 catalog 里
  没有 version）：只断言 group:name 存在，版本以 SBOM 解析值为准；
* kotlin-stdlib 是 Gradle 注入的（闭包里是 root 直连边，构建文件里没写），
  「SBOM 有而构建文件没写」不算缺失，不在断言范围。

用法
----
    python3 tools/check-sbom-direct-deps.py [--sbom build/sbom/sbom-*.cdx.json]

退出码：0 = 全部直接依赖在 SBOM 里且版本一致；1 = 有缺失/版本不符；2 = 环境错误。
CI 里 report-only（continue-on-error），失败是给维护者看的信号，不拦合并。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from pathlib import Path


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = Path(__file__).resolve().parent.parent
APP_BUILD = ROOT / "app" / "build.gradle.kts"
MACRO_BUILD = ROOT / "macrobenchmark" / "build.gradle.kts"
CATALOG = ROOT / "gradle" / "libs.versions.toml"
SBOM_GLOB = "build/sbom/sbom-*-*.cdx.json"

# configuration → SBOM 分节。没列进来的 configuration 不在任何运行时闭包里。
CONFIG_TO_SECTION = {
    "implementation": "release",
    "api": "release",
    "runtimeOnly": "release",
    "compileOnly": None,  # 编译期私有，不进 SBOM（本项目未使用）
    "benchmarkImplementation": "benchmark",
    "testImplementation": "test",
    "androidTestImplementation": "androidTest",
    "debugImplementation": "debug",
    "releaseImplementation": "release",
}

# 构建期工具 configuration：闭包里本来就不该有，跳过并单列说明。
BUILD_TOOL_CONFIGS = {"detektCli"}


def parse_catalog(path: Path) -> dict:
    """最小 TOML 解析：只要 [versions] 与 [libraries] 两张表（工具仓习惯，不引 tomllib 依赖树外的东西）。"""
    versions: dict[str, str] = {}
    libraries: dict[str, dict] = {}
    section = None
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            continue
        if "=" not in line or section not in ("versions", "libraries"):
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        if section == "versions":
            versions[key] = value.strip('"').strip("'")
        else:
            # alias = { group = "g", name = "n", version.ref = "v" } 或 version = "x"
            entry: dict[str, str] = {}
            for m in re.finditer(r'(\w+(?:\.\w+)*)\s*=\s*"([^"]*)"', value):
                entry[m.group(1)] = m.group(2)
            libraries[key] = entry
    return {"versions": versions, "libraries": libraries}


def alias_to_coordinates(alias: str, catalog: dict) -> tuple[str, str, str | None]:
    """libs.androidx.activity.compose → 别名 androidx-activity-compose → (group, name, version)。

    版本三种来源：entry 自带 version、version.ref 指向 [versions]、都没有（BOM 托管）。
    """
    entry = catalog["libraries"].get(alias)
    if entry is None:
        return "", "", None
    group = entry.get("group", "")
    name = entry.get("name") or alias.rsplit("-", 1)[-1]
    version = entry.get("version")
    if version is None and "version.ref" in entry:
        version = catalog["versions"].get(entry["version.ref"])
    return group, name, version


def parse_dependencies_block(path: Path) -> list[tuple[str, str]]:
    """提取 (configuration, 表达式) 列表。只认 dependencies { … } 顶层块内的行。"""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    out: list[tuple[str, str]] = []
    in_block = False
    depth = 0
    for raw in lines:
        stripped = raw.strip()
        if not in_block:
            if re.match(r"^dependencies\s*\{", stripped):
                in_block = True
                depth = 1
            continue
        if stripped.startswith("}"):
            depth -= 1
            if depth == 0:
                in_block = False
            continue
        depth += stripped.count("{") - stripped.count("}")
        # 三种形态：
        #   add("detektCli", libs.detekt.cli)
        #   implementation("g:n:v")
        #   implementation(libs.some.alias) / implementation(platform(libs.xxx))
        m = re.match(r'^add\("([^"]+)",\s*(.+)\)\s*$', stripped)
        if m:
            out.append((m.group(1), m.group(2).strip()))
            continue
        m = re.match(r'^(\w+)\s*\(\s*(.+)\s*\)\s*$', stripped)
        if m:
            out.append((m.group(1), m.group(2).strip()))
    return out


def resolve_expression(expr: str, catalog: dict) -> tuple[str, str, str | None] | None:
    """把依赖表达式解析成 (group, name, version)。不认识返回 None。"""
    expr = expr.strip()
    if expr.startswith("platform(") and expr.endswith(")"):
        expr = expr[len("platform("):-1].strip()
    m = re.match(r'^libs\.([A-Za-z0-9_.]+)$', expr)
    if m:
        alias = m.group(1).replace(".", "-")
        group, name, version = alias_to_coordinates(alias, catalog)
        if not group:
            return None
        return group, name, version
    m = re.match(r'^"([^":]+):([^":]+)(?::([^":]+))?"$', expr)
    if m:
        return m.group(1), m.group(2), m.group(3)
    return None


def load_sbom_sections(sbom_path: Path) -> dict[str, set[str]]:
    """分节 → {group:name:version} 集合。主清单 = scope=required 的 release 节。"""
    doc = json.loads(sbom_path.read_text(encoding="utf-8"))
    sections: dict[str, set[str]] = {}
    for comp in doc.get("components", []):
        gav = "%s:%s:%s" % (comp.get("group"), comp.get("name"), comp.get("version"))
        if comp.get("scope") == "required":
            sections.setdefault("release", set()).add(gav)
        for prop in comp.get("properties", []) or []:
            if prop.get("name") == "zhengdao:section":
                sections.setdefault(prop["value"], set()).add(gav)
    return sections


def find_latest_sbom() -> Path | None:
    cands = sorted(glob.glob(str(ROOT / SBOM_GLOB)), key=os.path.getmtime)
    return Path(cands[-1]) if cands else None


def check_module(build_file: Path, sections: dict[str, set[str]], catalog: dict,
                 label: str, default_section: str) -> tuple[list[str], list[str], list[str]]:
    """返回 (缺失, 版本不符, 跳过说明)。default_section：本模块 implementation 类依赖归哪个分节。"""
    problems: list[str] = []
    mismatches: list[str] = []
    skipped: list[str] = []
    for config, expr in parse_dependencies_block(build_file):
        if config in BUILD_TOOL_CONFIGS:
            resolved = resolve_expression(expr, catalog)
            if resolved:
                skipped.append("%s（构建期工具，不进运行时闭包，SBOM 不应含它）"
                               % ":".join(x for x in resolved if x))
            continue
        section = CONFIG_TO_SECTION.get(config)
        if section is None:
            skipped.append("configuration=%s 未映射到 SBOM 分节：%s" % (config, expr))
            continue
        if section == "release" and label == ":macrobenchmark":
            # macrobenchmark 的 implementation 依赖在本模块自己的分节，不归 :app 主清单
            section = default_section
        resolved = resolve_expression(expr, catalog)
        if resolved is None:
            problems.append("[%s] %s 表达式解析不了：%s" % (label, config, expr))
            continue
        group, name, expected_version = resolved
        members = sections.get(section, set())
        same_ga = [gav for gav in members if gav.startswith("%s:%s:" % (group, name))]
        if not same_ga:
            problems.append("[%s] %s 的直接依赖 %s:%s 不在 SBOM 的 %s 分节（%d 个构件）"
                            % (label, config, group, name, section, len(members)))
            continue
        if expected_version:
            want = "%s:%s:%s" % (group, name, expected_version)
            if want not in same_ga:
                mismatches.append("[%s] %s 的 %s 在 SBOM 里是 %s，构建文件写的是 %s"
                                  % (label, config, ":".join([group, name]),
                                     same_ga[0], want))
    return problems, mismatches, skipped


def main() -> int:
    ap = argparse.ArgumentParser(description="断言 SBOM 包含全部直接依赖（report-only 验收件）")
    ap.add_argument("--sbom", type=Path, default=None, help="SBOM 路径（默认取 build/sbom 下最新）")
    ap.add_argument("--app-build", type=Path, default=APP_BUILD)
    ap.add_argument("--macro-build", type=Path, default=MACRO_BUILD)
    ap.add_argument("--catalog", type=Path, default=CATALOG)
    args = ap.parse_args()

    sbom_path = args.sbom or find_latest_sbom()
    if not sbom_path or not sbom_path.exists():
        print("::error title=SBOM 直连依赖断言::找不到 SBOM（先跑 python3 tools/sbom.py）")
        return 2
    print("[sbom-direct] SBOM：%s" % sbom_path)
    sections = load_sbom_sections(sbom_path)
    for name in sorted(sections):
        print("[sbom-direct] 分节 %s：%d 个构件" % (name, len(sections[name])))

    catalog = parse_catalog(args.catalog)
    problems: list[str] = []
    mismatches: list[str] = []
    skipped: list[str] = []

    for label, build_file, default_section in (
        (":app", args.app_build, "release"),
        (":macrobenchmark", args.macro_build, "macrobenchmark"),
    ):
        if not build_file.exists():
            continue
        p, m, s = check_module(build_file, sections, catalog, label, default_section)
        problems += p
        mismatches += m
        skipped += s

    for line in skipped:
        print("[sbom-direct] 跳过：%s" % line)
    for line in mismatches:
        print("::warning title=SBOM 版本不符::" + line)
    for line in problems:
        print("::error title=SBOM 缺直接依赖::" + line)

    if problems:
        print("[sbom-direct] ✗ %d 条直接依赖不在 SBOM 里（先跑 tools/sbom.py 再断言）"
              % len(problems))
        return 1
    print("[sbom-direct] ✓ 构建文件声明的直接依赖全部在对应 SBOM 分节中"
          "（另有 %d 处版本提示，见 warning）" % len(mismatches))
    return 0


if __name__ == "__main__":
    sys.exit(main())
