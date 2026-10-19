#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""license-report.py —— 许可证分布与依赖版本新鲜度报告（第四十三批 · 任务 C）。

为什么
------
E-104/E-105 已证明「第三方正文会随分发物出去」是别人的打包行为、不是我们的承诺；
`tools/check-licenses.py` 只保证「仓库 licenses/ 有的正文逐字节进包」。但**哪些依赖
用什么许可证、有没有正文、版本落后多少**一直没有机器可读的汇总。本脚本把这两件事
做成报告（**只报告，不自动升级、不判红**，见 docs/QUALITY-CONTRACT.md §2）：

  1. **许可证分布**：对 SBOM 主清单（release 闭包）逐构件拉 POM（含 parent 链继承），
     把 `<licenses>` 归一到 SPDX id，与仓库 `licenses/<SPDX>.txt` 的口径对齐；
  2. **未知/冲突清单**：POM 链拉不到许可声明的、多许可（dual licensing）的、
     无法归一的，逐条列出（含拉取失败与 SBOM 分区来源标注）；
  3. **版本新鲜度**：逐构件拉 maven-metadata.xml 取最新稳定版，**落后主版本 ≥1**
     的单列（只报告；`GradleDependency`/`NewerVersionAvailable` 类 Lint 告警不计预算
     的那个坑，见 check-lint-budget.py 注释，与此同理：网络/时间相关只进报告）。

数据源与仓库声明一致（settings.gradle.kts）：google() 管 com.android.*、com.google.*、
androidx.*；其余走 mavenCentral()；两边互为兜底。全部请求有超时与失败兜底，
单构件失败不影响整体报告。

用法
----
    python3 tools/license-report.py [--sbom build/sbom/sbom-*.cdx.json] [--out DIR]

产物（--out 下，CI 进 artifact）：
    license-release-report-<commit>-<date>.md    人读报告
    license-release-report-<commit>-<date>.json  机读数据

退出码：0 = 报告已生成（哪怕有未知/落后项——本脚本 report-only）；2 = 用法/环境错误。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as _dt
import glob
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SBOM_GLOB = "build/sbom/sbom-*-*.cdx.json"
DEFAULT_OUT = ROOT / "build" / "supply-chain"
LICENSES_DIR = ROOT / "licenses"
HTTP_TIMEOUT = 20
POM_DEPTH_LIMIT = 5
WORKERS = 8

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"


def google_groups(group: str) -> bool:
    """settings.gradle.kts 里 google{} 的 content 过滤：com.android.* / com.google.* / androidx.*。"""
    return group.startswith(("com.android.", "com.google.", "androidx."))


# POM <licenses><license><name> 的常见写法 → SPDX id。
# 查无此名的进「未知/冲突」清单（原文照抄），绝不静默归到近似许可证上。
LICENSE_NAME_TO_SPDX = {
    "the apache license, version 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "apache license version 2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "apache software license, version 2.0": "Apache-2.0",
    "the apache software license, version 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache license 2": "Apache-2.0",
    "public domain": "PublicDomain",
    "unlicense": "Unlicense",
    "bsd 2-clause license": "BSD-2-Clause",
    "bsd-2-clause": "BSD-2-Clause",
    "bsd 2 clause": "BSD-2-Clause",
    "bsd-2-clause license": "BSD-2-Clause",
    "bsd 3-clause license": "BSD-3-Clause",
    "bsd-3-clause": "BSD-3-Clause",
    "bsd 3 clause": "BSD-3-Clause",
    "bsd-3-clause license": "BSD-3-Clause",
    "eclipse public license, version 1.0": "EPL-1.0",
    "eclipse public license v1.0": "EPL-1.0",
    "eclipse public license - v 1.0": "EPL-1.0",
    "eclipse public license 1.0": "EPL-1.0",
    "epl 1.0": "EPL-1.0",
    "eclipse public license v. 1.0": "EPL-1.0",
    "mozilla public license, version 2.0": "MPL-2.0",
    "mozilla public license version 2.0": "MPL-2.0",
    "mpl 2.0": "MPL-2.0",
    "mit license": "MIT",
    "the mit license": "MIT",
    "mit license (mit)": "MIT",
    "mit": "MIT",
    "gnu lesser general public license, version 2.1": "LGPL-2.1",
    "gnu lesser general public license v2.1": "LGPL-2.1",
    "gnu lesser general public license": "LGPL-2.1+",
    "gnu general public license, version 2.0": "GPL-2.0",
    "gnu general public license v2.0": "GPL-2.0",
    "gnu general public license": "GPL-2.0+",
    "common development and distribution license (cddl) v1.0": "CDDL-1.0",
    "cddl 1.0": "CDDL-1.0",
    "bouncy castle license": "BouncyCastle",
}


def git_short_commit() -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True, encoding="utf-8",
                             errors="replace", cwd=str(ROOT), check=False)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except OSError:
        pass
    return "unknown"


def today() -> str:
    return _dt.datetime.now().strftime("%Y%m%d")


def fetch(url: str):
    """GET（带 UA），失败返回 None。只读公网，不送任何仓库内数据。"""
    req = urllib.request.Request(url, headers={"User-Agent": "zhengdao-license-report/43"})
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            return resp.read()
    except (urllib.error.URLError, OSError, ValueError):
        return None


def pom_urls(group: str, name: str, version: str) -> list[str]:
    path = "%s/%s/%s" % (group.replace(".", "/"), name, version)
    fname = "%s-%s.pom" % (name, version)
    order = [GOOGLE, CENTRAL] if google_groups(group) else [CENTRAL, GOOGLE]
    return ["%s/%s/%s" % (repo, path, fname) for repo in order]


def _local(tag) -> str:
    """XML 标签的本地名（剥掉 `{namespace}`）。

    ElementTree 的 `{*}` 通配**匹配不了无命名空间的元素**：maven-metadata.xml
    不带 xmlns（`{*}version` 静默匹配 0 条），而 Maven POM 带 xmlns（POM/4.0.0）。
    两种混在一起时只有按本地名匹配才通吃。"""
    if not isinstance(tag, str):
        return ""
    return tag.rsplit("}", 1)[-1]


def parse_pom(data: bytes) -> dict:
    """取 <licenses> 与 <parent>（本地名匹配，不怕有没有 xmlns）。"""
    out: dict = {"licenses": [], "parent": None}
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        return out
    for elem in root.iter():
        if _local(elem.tag) == "licenses":
            for lic in elem:
                if _local(lic.tag) != "license":
                    continue
                name = url = None
                for f in lic:
                    if _local(f.tag) == "name":
                        name = (f.text or "").strip()
                    elif _local(f.tag) == "url":
                        url = (f.text or "").strip()
                if name:
                    out["licenses"].append({"name": name, "url": url or ""})
            break
    for elem in root.iter():
        if _local(elem.tag) == "parent":
            g = a = v = None
            for f in elem:
                if _local(f.tag) == "groupId":
                    g = (f.text or "").strip()
                elif _local(f.tag) == "artifactId":
                    a = (f.text or "").strip()
                elif _local(f.tag) == "version":
                    v = (f.text or "").strip()
            if g and a and v:
                out["parent"] = (g, a, v)
            break
    return out


def pom_license_chain(group: str, name: str, version: str) -> dict:
    """POM 自身 <licenses> 优先；否则顺 parent 链继承（深度 ≤5，防环靠版本三元组去重）。"""
    seen = set()
    queue = [(group, name, version, 0)]
    while queue:
        g, n, v, depth = queue.pop(0)
        key = (g, n, v)
        if key in seen or depth > POM_DEPTH_LIMIT:
            continue
        seen.add(key)
        data = None
        for url in pom_urls(g, n, v):
            data = fetch(url)
            if data:
                break
        if not data:
            return {"licenses": [], "fetched": False,
                    "error": "POM 拉取失败（%s:%s:%s 两个仓库都 404/超时）" % (g, n, v)}
        pom = parse_pom(data)
        if pom["licenses"]:
            return {"licenses": pom["licenses"], "fetched": True, "error": ""}
        if pom["parent"]:
            queue.append((*pom["parent"], depth + 1))
    return {"licenses": [], "fetched": False, "error": "POM 链上都没有 <licenses> 声明"}


def to_spdx(license_entries: list[dict]) -> tuple[list[str], list[str]]:
    """许可条目 → (SPDX 列表, 无法归一的原文列表)。一构件多许可 = dual licensing。"""
    spdx: list[str] = []
    unknown: list[str] = []
    for entry in license_entries:
        raw = entry["name"]
        key = " ".join(raw.lower().replace("(", " ").replace(")", " ").split())
        spdx_id = LICENSE_NAME_TO_SPDX.get(key)
        if spdx_id:
            if spdx_id not in spdx:
                spdx.append(spdx_id)
        else:
            unknown.append(raw)
    return spdx, unknown


def metadata_latest(group: str, name: str) -> tuple[str | None, str]:
    """从两个仓库取 maven-metadata.xml 的 <latest>/<release> 最高版本，返回 (版本, 来源)。"""
    path = "%s/%s" % (group.replace(".", "/"), name)
    order = [GOOGLE, CENTRAL] if google_groups(group) else [CENTRAL, GOOGLE]
    best = None
    for repo in order:
        data = fetch("%s/%s/maven-metadata.xml" % (repo, path))
        if not data:
            continue
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            continue
        # maven-metadata.xml 无 xmlns：按本地名收集 version（见 _local 注释）
        raw_versions = [e.text.strip() for e in root.iter()
                        if _local(e.tag) == "version" and e.text and e.text.strip()]
        # 只认**纯点分数字**稳定版：排除预发布（2.5.0-Beta1）与 Maven 占位版
        # （com.google.guava:listenablefuture 的 9999.0-empty-to-avoid-conflict-with-guava
        # 是防冲突空占位，当成「最新版」会造出 lag=9998 的假发现）。
        versions = [v for v in raw_versions if re.fullmatch(r"\d+(\.\d+)*", v)]
        # 用版本向量取最大（不是字符串最大：1.10.0 > 1.9.0）
        def vec(x: str):
            nums = re.findall(r"\d+", x)
            return tuple(int(p) for p in nums) if nums else (0,)
        if versions:
            candidate = max(versions, key=vec)
            if best is None or vec(candidate) > vec(best):
                best = candidate
    return best, ""


def version_vec(version: str) -> tuple[int, ...]:
    nums = re.findall(r"\d+", version)
    return tuple(int(p) for p in nums) if nums else (0,)


def find_latest_sbom() -> Path | None:
    cands = sorted(glob.glob(str(ROOT / SBOM_GLOB)), key=os.path.getmtime)
    return Path(cands[-1]) if cands else None


def analyze_component(comp: dict) -> dict:
    group, name, version = comp["group"], comp["name"], comp["version"]
    chain = pom_license_chain(group, name, version)
    spdx, unknown = to_spdx(chain["licenses"])
    latest, _ = metadata_latest(group, name)
    entry = {
        "group": group, "name": name, "version": version,
        "purl": "pkg:maven/%s/%s@%s" % (group, name, version),
        "licenses": spdx,
        "license_names_raw": [e["name"] for e in chain["licenses"]],
        "unknown_license_names": unknown,
        "pom_fetched": chain["fetched"],
        "pom_error": chain["error"],
        "latest_version": latest,
        "major_lag": None,
        "notes": [],
    }
    if latest:
        cur_major, new_major = version_vec(version)[0], version_vec(latest)[0]
        if new_major - cur_major >= 1:
            entry["major_lag"] = new_major - cur_major
    if chain["licenses"] and len(spdx) + len(unknown) > 1:
        entry["notes"].append("多许可（dual licensing）")
    if not chain["licenses"]:
        entry["notes"].append("许可证未知：%s" % chain["error"])
    if unknown:
        entry["notes"].append("许可证名无法归一：%s" % "；".join(unknown))
    return entry


def render_markdown(components: list[dict], sbom_path: Path, commit: str,
                    generated_at: str) -> str:
    lines: list[str] = []
    ap = lines.append
    ap("# 许可证与版本新鲜度报告（release 主清单）")
    ap("")
    ap("- 生成：`tools/license-report.py`（report-only，不判红、不自动升级）")
    ap("- SBOM：`%s`（基线 %s，%d 个构件）"
       % (os.path.relpath(sbom_path, ROOT).replace("\\", "/"), commit, len(components)))
    ap("- 生成时间：%s（版本新鲜度随时间推移，落后项会变多）" % generated_at)
    ap("")
    ap("## 1. 许可证分布（SPDX，口径对齐仓库 licenses/ 正文字文件名）")
    ap("")
    dist: dict[str, list[str]] = {}
    for c in components:
        for spdx in c["licenses"]:
            dist.setdefault(spdx, []).append("%s:%s" % (c["group"], c["name"]))
    if not dist:
        ap("（无：所有构件的许可证都拉取失败或未声明）")
    for spdx in sorted(dist):
        text_file = LICENSES_DIR / ("%s.txt" % spdx)
        cover = "正文在库" if text_file.exists() else "**正文不在库**"
        ap("- **%s**：%d 个（%s）— %s" % (spdx, len(dist[spdx]), cover,
                                          ", ".join(sorted(dist[spdx])[:8])
                                          + (" …" if len(dist[spdx]) > 8 else "")))
    ap("")
    ap("## 2. 未知或冲突许可证清单（人读后处置）")
    ap("")
    rows = [c for c in components if c["notes"]]
    if not rows:
        ap("（无）")
    else:
        ap("| 构件 | 版本 | 情况 | 原始许可名 | POM 状态 |")
        ap("| --- | --- | --- | --- | --- |")
        for c in sorted(rows, key=lambda x: (x["group"], x["name"])):
            ap("| %s:%s | %s | %s | %s | %s |"
               % (c["group"], c["name"], c["version"], "；".join(c["notes"]) or "-",
                  "；".join(c["license_names_raw"]) or "无声明",
                  "拉到" if c["pom_fetched"] else "未拉到"))
    ap("")
    ap("## 3. 版本新鲜度（落后主版本 ≥1；只报告，不自动升级）")
    ap("")
    lag = [c for c in components if c["major_lag"]]
    if not lag:
        ap("（无：全部构件的当前大版本与仓库最新大版本一致，或元数据拉取失败）")
    else:
        ap("| 构件 | 当前版本 | 仓库最新 | 落后主版本数 |")
        ap("| --- | --- | --- | --- |")
        for c in sorted(lag, key=lambda x: (-x["major_lag"], x["group"], x["name"])):
            ap("| %s:%s | %s | %s | %d |"
               % (c["group"], c["name"], c["version"], c["latest_version"], c["major_lag"]))
        ap("")
        ap("> 说明：与 Lint 的 `NewerVersionAvailable`/`GradleDependency` 同理，"
          "本表只作升级决策输入；升不升、何时升是架构判断，不在本报告职责内。")
    ap("")
    ap("## 附：口径与边界")
    ap("")
    ap("- 许可证取自各构件 POM 的 `<licenses>`（POM 自己没写则顺 parent 链继承，深度 ≤%d）；"
       % POM_DEPTH_LIMIT)
    ap("- 「正文在库」指 `licenses/<SPDX>.txt` 存在；正文是否真的逐字节进 APK 归 "
      "`tools/check-licenses.py` 门禁管，本报告不重复判定；")
    ap("- 未拉到 POM/元数据的构件只如实标注，不猜许可证、不猜最新版。")
    ap("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="许可证分布与版本新鲜度报告（report-only）")
    ap.add_argument("--sbom", type=Path, default=None)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    sbom_path = args.sbom or find_latest_sbom()
    if not sbom_path or not sbom_path.exists():
        print("::error title=许可报告::找不到 SBOM（先跑 python3 tools/sbom.py）")
        return 2
    try:
        doc = json.loads(sbom_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print("::error title=许可报告::SBOM 读不了：%s" % exc)
        return 2

    main_comps = [c for c in doc.get("components", []) if c.get("scope") == "required"]
    if not main_comps:
        print("::error title=许可报告::SBOM 里没有 scope=required 的主清单构件")
        return 2

    print("[license] 分析 %d 个主清单构件（POM/元数据并行拉取，仓库与 settings.gradle.kts 一致）"
          % len(main_comps))
    results: list[dict] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {pool.submit(analyze_component, c): c for c in main_comps}
        done = 0
        for fut in concurrent.futures.as_completed(futures):
            results.append(fut.result())
            done += 1
            if done % 25 == 0:
                print("[license] 进度 %d/%d" % (done, len(main_comps)))
    results.sort(key=lambda x: (x["group"], x["name"]))

    args.out.mkdir(parents=True, exist_ok=True)
    commit = git_short_commit()
    generated_at = _dt.datetime.now().strftime("%Y-%m-%d %H:%M %z")
    md = render_markdown(results, sbom_path, commit, generated_at)
    base = "license-release-report-%s-%s" % (commit, today())
    (args.out / (base + ".md")).write_text(md, encoding="utf-8")
    payload = {
        "generator": "tools/license-report.py",
        "sbom": os.path.relpath(sbom_path, ROOT).replace("\\", "/"),
        "baseline_commit": commit,
        "generated_at": generated_at,
        "component_count": len(results),
        "components": results,
    }
    (args.out / (base + ".json")).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lag = sum(1 for r in results if r["major_lag"])
    unknown = sum(1 for r in results if r["notes"])
    print("[license] 写入 %s.md / .json" % base)
    print("[license] 主清单 %d 构件：落后主版本 ≥1 的 %d 个；需人看的许可事项 %d 条"
          % (len(results), lag, unknown))
    for r in results:
        if r["notes"]:
            print("::warning title=许可事项::%s:%s@%s — %s"
                  % (r["group"], r["name"], r["version"], "；".join(r["notes"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
