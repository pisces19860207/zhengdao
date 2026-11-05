#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gen-notices.py —— 从依赖真源生成 THIRD-PARTY-LICENSES.md 的「依赖各节」（第四百二十四批）。

一句话
------
手写清单必然漂移（420 批实测：漏 32 / 陈旧 7 / 不一致 3）。本脚本把**依赖条目**交给
真源，人只维护两块：**资产类**与**说明文字**。

真源与取证（全部离线，零网络请求）
--------------------------------
* 坐标 / 版本 / 来源：`gradle/libs.versions.toml` ＋ `settings.gradle.kts` 列出的各模块
  `build.gradle.kts` 的 `dependencies { }` ＋ `Cargo.lock`。
  ⚠️ 派工单写的 `rust/core/Cargo.lock` 在本仓**不存在**：workspace 的锁文件是
  `rust/Cargo.lock`（`rust/Cargo.toml` 里 `members = ["core"]`），421 批已记过同一处。
* Maven 许可：本地 Gradle 缓存 POM 的 `<licenses>`（自己没写就顺 `<parent>` 链上溯），
  口径**复用 `tools/check-license-policy.py`**（423 批），不另造第二套归一表。
* Rust 许可：`tools/notices-evidence.json` 里的离线登记表（421 批逐条下载 `.crate`、
  校「包 SHA256 == Cargo.lock checksum」后才取 `license` 字段）。本机
  `~/.cargo/registry/{cache,src}` 已被清理，sparse index 的 JSON **没有 license 字段**
  ⇒ 这里能拿到的只有那张已取证的登记表，别无它物。
* 缓存缺 POM（且登记表里也没有该坐标）⇒ 写 **「未取证」**，不按 group 猜、不按
  「androidx 一般都是 Apache」猜。420 批 §1.2 那 7 个 POM 无 `<licenses>` 的模块同理。

为什么要一张登记表（而不是每次都从本机缓存重取）
------------------------------------------------
`--check` 要在 CI 上稳定复现：CI runner 的 Gradle 缓存是 restore 来的，未必覆盖全部
POM。所以许可取证的优先级是 **① 本机缓存 POM（实时）→ ② 登记表（上一次离线取证，
带 POM 原文 / .crate checksum 绑定）→ ③ 未取证**。登记表只对「本机没有证据」的坐标
兜底，绝不覆盖本机已经读到的 POM 原文；`--refresh` 可从本机缓存重建 Maven 那半张。
`--check` 还会校验登记表条目自身带不带取证依据（没有 `origin` / `raw` 的一律作废，
不许在登记表里手写一句「应该是 MIT」当证据）。

生成器不碰什么
--------------
文档里两对 HTML 注释标记之间才是生成区，**标记之外一律逐字节原样写回**：
文首聚合分发声明与「随包 / 留档」表（check-licenses.py 要读）、红线声明、§5 zstd
人工取证、资产类、§8/§9 的说明文字、以及文末「历史节」里 420 指出的 7 节
（标 `[已迁出：zhengdao-term]` 归档保留、不删）。手改说明文字后重生成仍在（判据③）。

用法
----
    python3 tools/gen-notices.py                 # 生成（原地只替换两个标记块）
    python3 tools/gen-notices.py --check        # CI：清单与依赖不一致 ⇒ 退出 1
    python3 tools/gen-notices.py --stats        # 只打印条目统计，不写文件
    python3 tools/gen-notices.py --refresh      # 从本机 Gradle 缓存重建 Maven 登记表
    python3 tools/gen-notices.py --selftest     # 夹具自检（幂等 / 人工区 / 未取证 / --check）
    python3 tools/gen-notices.py --gradle-home D:/gd   # 换缓存位置（本机排查用）

退出码：0 = 一致 / 已生成；1 = --check 不一致（或夹具失败）；2 = 环境或解析错误。
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DOC_REL = "THIRD-PARTY-LICENSES.md"
EVIDENCE_REL = "tools/notices-evidence.json"
CATALOG_REL = "gradle/libs.versions.toml"
SETTINGS_REL = "settings.gradle.kts"
LOCK_REL = "rust/Cargo.lock"
LOCK_FALLBACK_REL = "rust/core/Cargo.lock"
CARGO_MANIFEST_REL = "rust/core/Cargo.toml"

# 两对标记：生成器只替换「begin 与 end 之间」，其余字节原样保留。
MARKERS = {
    "maven": ("<!-- gen-notices:begin:maven -->", "<!-- gen-notices:end:maven -->"),
    "rust": ("<!-- gen-notices:begin:rust -->", "<!-- gen-notices:end:rust -->"),
}
UNEVIDENCED = "未取证"
# 取证列写成**环境无关**的固定串：本机缓存命中 POM 与冷缓存命中登记表，都是同一类
# 离线证据；若把「本地缓存 POM」/「登记表：…」分别写进清单，CI 与本机就会差一个字、
# --check 误红。优先级与依据写在生成块的说明里，不写进逐行单元格。
EVIDENCE_OK = "离线取证（本地 Gradle 缓存 POM 或登记表，不联网）"
EVIDENCE_NONE = "无（缓存缺 POM 且登记表无该坐标，不猜）"
TABLE_HEAD = "| 坐标 | 版本 | 许可 | 随包？ | 来源 | 许可取证 |"
TABLE_SEP = "|---|---|---|---|---|---|"


class NoticesError(RuntimeError):
    """环境 / 解析错误 —— 对应退出码 2，绝不拿它表示「发现不一致」。"""


# ── 复用 423 的解析与归一口径 ────────────────────────────────────────────────
_POLICY = None


def policy():
    """加载 tools/check-license-policy.py（只执行常量与函数定义，无副作用）。"""
    global _POLICY
    if _POLICY is None:
        path = HERE / "check-license-policy.py"
        if not path.is_file():
            raise NoticesError("找不到 tools/check-license-policy.py（依赖解析与许可归一复用它）")
        spec = importlib.util.spec_from_file_location("zd_notices_policy", path)
        if spec is None or spec.loader is None:
            raise NoticesError("tools/check-license-policy.py 加载不了")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _POLICY = mod
    return _POLICY


def default_gradle_home() -> Path:
    env = os.environ.get("GRADLE_USER_HOME")
    return Path(env) if env else (Path.home() / ".gradle")


def load_evidence(path: Path) -> dict:
    if not path.is_file():
        return {"schema": 1, "maven": {}, "rust": {"in_so": [], "not_in_so": [], "licenses": {}}}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise NoticesError("登记表读不了：%s" % exc) from exc
    if not isinstance(data, dict) or data.get("schema") != 1:
        raise NoticesError("登记表 schema 不是 1（拒绝用它兜底，避免读到旧格式瞎猜）")
    data.setdefault("maven", {})
    data.setdefault("rust", {})
    for key in ("in_so", "not_in_so", "licenses"):
        data["rust"].setdefault(key, [] if key != "licenses" else {})
    return data


def _valid_evidence(rec: object) -> bool:
    """登记表条目必须自带取证依据；光写一个许可名不算证据。"""
    if not isinstance(rec, dict):
        return False
    lic = rec.get("license")
    origin = rec.get("origin")
    return bool(lic) and isinstance(lic, str) and bool(origin) and isinstance(origin, str) and bool(rec.get("raw"))


# ── Maven：真源 = 版本目录 ＋ 各模块 build.gradle.kts ─────────────────────────
def maven_rows(root: Path, gradle_home: Path, evidence: dict) -> tuple[list[dict], list[dict], list[str]]:
    P = policy()
    catalog_path = root / CATALOG_REL
    settings_path = root / SETTINGS_REL
    if not catalog_path.is_file():
        raise NoticesError("找不到 %s" % catalog_path)
    if not settings_path.is_file():
        raise NoticesError("找不到 %s（模块列表的真源）" % settings_path)
    catalog = P.parse_catalog(catalog_path)
    modules = P.parse_modules(root)

    rows: dict[str, dict] = {}
    boms: dict[str, dict] = {}
    problems: list[str] = []
    declared = 0

    for mod in modules:
        build_file = root / mod / "build.gradle.kts"
        if not build_file.is_file():
            problems.append("模块 :%s 没有 build.gradle.kts，跳过" % mod)
            continue
        raws = P.parse_dependencies(build_file)
        declared += len(raws)
        module_boms: list[tuple[str, str, str]] = []
        pending: list[tuple[str, str, str | None, str, int, str]] = []
        for config, expr, lineno in raws:
            got = P.resolve_expr(expr, catalog)
            if got is None:  # project(":core") 等模块内依赖，不是外部构件
                continue
            kind, g, n, v = got
            if kind == "bom" and v:
                module_boms.append((g, n, v))
                key = "%s:%s:%s" % (g, n, v)
                # 来源只写「文件（配置）」，**不写行号**（2026-11-05 用户定）：
                # 行号没有合规价值，且天然会衰减——`build.gradle.kts` 里改一行注释就整体位移，
                # 26 条来源全对不上 ⇒ 424 门禁必红（#1839/#1840 就是这么连红两轮的）。
                site = "%s/build.gradle.kts（platform）" % mod
                item = boms.setdefault(key, {"coord": key, "g": g, "n": n, "v": v, "sites": []})
                item["sites"].append(site)
                continue
            pending.append((g, n, v, config, lineno, mod))
        managed = P.bom_versions(gradle_home, module_boms) if module_boms else {}
        # 冷缓存 runner 可能没有 BOM POM ⇒ 用登记表里的托管版本兜底；标注统一写「BOM 托管」，
        # 不区分来源，否则本机与 CI 的输出会差一个字、--check 误红。
        fallback_bom = evidence.get("bom_versions", {})
        for g, n, v, config, lineno, mod in pending:
            version = v
            version_src = "版本目录"
            if version is None:
                version = managed.get((g, n)) or fallback_bom.get("%s:%s" % (g, n), "")
                version_src = "BOM 托管" if version else "未解析"
            key = "%s:%s:%s" % (g, n, version or "?")
            site = "%s/build.gradle.kts（%s）" % (mod, config)  # 同上：不写行号，见上一条注释
            item = rows.setdefault(key, {
                "coord": key, "g": g, "n": n, "v": version or "",
                "configs": [], "pairs": [], "sites": [], "version_src": version_src,
            })
            item["sites"].append(site)
            item["configs"].append(config)
            item["pairs"].append((mod, config))
            if version_src != item["version_src"]:
                item["version_src"] = "%s / %s" % (item["version_src"], version_src)

    if not rows and not boms:
        raise NoticesError("一条外部依赖都没解析出来（%d 条声明）—— 空集合全绿比红更危险" % declared)

    # 「是否随包」按 **release 分发物** 判。423 的 NOT_DISTRIBUTED_CONFIGS 是「许可政策」
    # 的从严口径（未知 configuration 一律当进包，多判不漏判）；这里多排除两类事实明确的：
    #   · debug* —— 只进 debug APK，不进 release；
    #   · :macrobenchmark —— com.android.test 模块，产物是独立测试 APK，不是主分发物。
    non_dist_configs = frozenset(P.NOT_DISTRIBUTED_CONFIGS) | frozenset({
        "debugImplementation", "debugRuntimeOnly", "debugCompileOnly", "debugApi",
    })
    non_dist_modules = frozenset({"macrobenchmark"})
    out: list[dict] = []
    for key in sorted(rows):
        item = rows[key]
        dist_pairs = [(m, c) for m, c in item["pairs"]
                      if m not in non_dist_modules and c not in non_dist_configs]
        if dist_pairs:
            scope = "是（%s）" % "/".join(sorted({c for _m, c in dist_pairs}))
        else:
            why = sorted({(":%s 是独立测试模块" % m) if m in non_dist_modules else c
                          for m, c in item["pairs"]})
            scope = "否（%s）" % "/".join(why)
        lic = maven_license(gradle_home, evidence, item["g"], item["n"], item["v"])
        out.append({
            "coord": key, "version": item["v"] or "?", "license": lic["license"],
            "scope": scope, "source": "；".join(sorted(set(item["sites"]))) +
                     "；版本=" + item["version_src"],
            "evidence": lic["evidence"], "why": lic["why"],
        })
    bom_rows = [
        {"coord": k, "version": b["v"], "source": "；".join(sorted(set(b["sites"])))}
        for k, b in sorted(boms.items())
    ]
    return out, bom_rows, problems


def maven_license(gradle_home: Path, evidence: dict, g: str, n: str, v: str) -> dict:
    P = policy()
    key = "%s:%s:%s" % (g, n, v)
    if not v:
        return {"license": UNEVIDENCED, "why": "版本未解析（catalog 无版本且缓存缺 BOM POM）", "evidence": "无"}
    got = P.licenses_for(gradle_home, g, n, v)
    if not got["error"] and got["licenses"]:
        ids, unknown = P.to_spdx(got["licenses"])
        if ids:
            note = "；部分许可名归一不了：%s" % "；".join(unknown) if unknown else ""
            return {"license": " / ".join(sorted(set(ids))) + note, "why": "", "evidence": EVIDENCE_OK}
        why = "POM 原文归一不了：%s" % "；".join(unknown)
    else:
        why = got["error"] or "POM 无 <licenses> 声明"

    rec = evidence.get("maven", {}).get(key)
    if _valid_evidence(rec):
        return {"license": rec["license"], "why": "", "evidence": EVIDENCE_OK}
    return {"license": UNEVIDENCED, "why": why, "evidence": EVIDENCE_NONE}


# ── Rust：真源 = Cargo.lock（＋ Cargo.toml 判直接 / 传递） ────────────────────
_PKG_BLOCK = re.compile(r"(?ms)^\[\[package\]\]\s*$")


def parse_cargo_lock(text: str) -> list[dict]:
    blocks = _PKG_BLOCK.split(text)[1:]
    out: list[dict] = []
    for block in blocks:
        name = re.search(r'(?m)^name = "([^"]+)"', block)
        version = re.search(r'(?m)^version = "([^"]+)"', block)
        source = re.search(r'(?m)^source = "([^"]+)"', block)
        checksum = re.search(r'(?m)^checksum = "([^"]+)"', block)
        if not (name and version):
            continue
        deps: list[str] = []
        dm = re.search(r"(?ms)^dependencies = \[\n(.*?)^\]", block)
        if dm:
            deps = re.findall(r'"([^"]+)"', dm.group(1))
        out.append({
            "name": name.group(1), "version": version.group(1),
            "source": source.group(1) if source else "",
            "checksum": checksum.group(1) if checksum else "",
            "deps": deps,
        })
    return out


def cargo_direct_names(text: str) -> set[str]:
    """`[dependencies]` 与 `[target.'cfg(...)'.dependencies]` 两段里的键名。"""
    names: set[str] = set()
    section = ""
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            continue
        if "dependencies" not in section:
            continue
        m = re.match(r"^([A-Za-z0-9_-]+)\s*=", line)
        if m:
            names.add(m.group(1))
    return names


def _dep_tokens(dep: str) -> tuple[str, str]:
    parts = dep.split()
    return parts[0], (parts[1] if len(parts) > 1 else "")


def rust_rows(root: Path, evidence: dict) -> tuple[list[dict], dict]:
    lock_path = root / LOCK_REL
    if not lock_path.is_file():
        lock_path = root / LOCK_FALLBACK_REL
    if not lock_path.is_file():
        raise NoticesError("找不到 %s（也试了 %s）" % (LOCK_REL, LOCK_FALLBACK_REL))
    pkgs = parse_cargo_lock(lock_path.read_text(encoding="utf-8", errors="replace"))
    third = [p for p in pkgs if p["source"] and p["name"] != "zhengdao_core"]
    if not third:
        raise NoticesError("Cargo.lock 里一个第三方 package 都没有 —— 解析器与锁文件脱节了")

    manifest = root / CARGO_MANIFEST_REL
    direct = cargo_direct_names(manifest.read_text(encoding="utf-8", errors="replace")) if manifest.is_file() else set()

    # 反查引入方：`dependencies = ["a", "b 1.2.3"]` ⇒ b-1.2.3 的来源是 a@... 的父包
    by_name: dict[str, list[str]] = {}
    for p in pkgs:
        by_name.setdefault(p["name"], []).append(p["version"])
    reverse: dict[str, list[str]] = {}
    for p in pkgs:
        parent = "%s-%s" % (p["name"], p["version"])
        for dep in p["deps"]:
            dn, dv = _dep_tokens(dep)
            if dn == p["name"] or dn == "zhengdao_core":
                continue
            versions = [dv] if dv else by_name.get(dn, [])
            for ver in versions:
                reverse.setdefault("%s-%s" % (dn, ver), []).append(parent)

    rust_ev = evidence.get("rust", {})
    in_so = set(rust_ev.get("in_so", []))
    not_in_so = set(rust_ev.get("not_in_so", []))
    licenses = rust_ev.get("licenses", {})

    out: list[dict] = []
    for p in third:
        key = "%s-%s" % (p["name"], p["version"])
        at = "%s@%s" % (p["name"], p["version"])
        if key in in_so:
            scope = "是（进 .so，dep-info）"
        elif key in not_in_so:
            scope = "否（dep-info 未出现）"
        else:
            scope = UNEVIDENCED + "（dep-info 未覆盖该包）"

        rec = licenses.get(at)
        if _valid_evidence(rec) and rec.get("checksum") and rec.get("checksum") == p["checksum"]:
            lic, ev = rec["license"], "登记表：" + str(rec["origin"])
            why = ""
        elif _valid_evidence(rec) and not rec.get("checksum"):
            lic, ev, why = UNEVIDENCED, "登记表条目缺 checksum 绑定", "登记表没绑 Cargo.lock checksum"
        elif key in not_in_so:
            lic = UNEVIDENCED
            ev = "无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据）"
            why = "该包不进 .so，421 批未对非随包件取证"
        else:
            lic = UNEVIDENCED
            ev = "无（本机无 .crate 元数据；登记表缺该包或 checksum 与 Cargo.lock 不符）"
            why = "登记表缺该包 / checksum 不符"

        if p["name"] in direct:
            src = "直接（%s）" % CARGO_MANIFEST_REL
        else:
            parents = sorted(set(reverse.get(key, [])))
            if parents:
                show = ", ".join(parents[:3]) + (" ＋%d" % (len(parents) - 3) if len(parents) > 3 else "")
                src = "传递（← %s；Cargo.lock 依赖边）" % show
            else:
                src = "传递（Cargo.lock 依赖边，引入方未取证）"
        out.append({
            "coord": at, "version": p["version"], "license": lic, "scope": scope,
            "source": src, "evidence": ev, "why": why, "key": key,
        })
    out.sort(key=lambda r: (r["coord"].lower(), r["version"]))
    stats = {
        "packages": len(pkgs), "third": len(third),
        "in_so": sum(1 for r in out if r["scope"].startswith("是")),
        "not_in_so": sum(1 for r in out if r["scope"].startswith("否")),
        "unlicensed": sum(1 for r in out if r["license"].startswith(UNEVIDENCED)),
    }
    return out, stats


# ── 渲染（纯函数：同样输入永远同样的字节） ──────────────────────────────────
def _cell(text: str) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def render_maven(rows: list[dict], boms: list[dict]) -> str:
    dist = sum(1 for r in rows if r["scope"].startswith("是"))
    unev = sum(1 for r in rows if r["license"].startswith(UNEVIDENCED))
    lines = [
        "",
        "> **本节由 `tools/gen-notices.py` 生成**（标记 `gen-notices:maven` 之间），",
        "> 人工请勿改这一段；依赖变了跑一次生成，或让 CI 的 `--check` 把它判红。",
        "> 许可优先从**本地 Gradle 缓存 POM** 取（含 `<parent>` 上溯）；冷缓存时由",
        "> `tools/notices-evidence.json` 的离线登记表兜底（带 POM 原文与 sha256）。全程不联网；",
        "> 两处都没有证据的坐标 ⇒ 写「未取证」，不按 group 猜。",
        "",
        "**表 8-A · 直接声明的 Maven 依赖**（%d 条；随包 %d / 不随包 %d / 许可未取证 %d）"
        % (len(rows), dist, len(rows) - dist, unev),
        "",
        TABLE_HEAD,
        TABLE_SEP,
    ]
    for r in rows:
        lic = _cell(r["license"])
        if r["why"]:
            lic = "%s（%s）" % (lic, _cell(r["why"]))
        lines.append("| `%s` | `%s` | %s | %s | %s | %s |" % (
            _cell(r["coord"]), _cell(r["version"]), lic, _cell(r["scope"]),
            _cell(r["source"]), _cell(r["evidence"])))
    lines += [
        "",
        "**表 8-B · 版本托管（BOM，platform() 声明，本身不进分发物）**",
        "",
        "| 坐标 | 版本 | 用途 | 来源 |",
        "|---|---|---|---|",
    ]
    for b in boms:
        lines.append("| `%s` | `%s` | 只给别的坐标供版本，不产生 4(a)/4(d) 义务 | %s |" % (
            _cell(b["coord"]), _cell(b["version"]), _cell(b["source"])))
    if not boms:
        lines.append("| （无） | — | — | — |")
    lines += [
        "",
        "> 口径说明：`test*` / `androidTest*` / `debug*` / `benchmark*` / `detektCli` 的构件，",
        "> 以及 `:macrobenchmark`（`com.android.test` 模块，独立测试 APK）的全部依赖，",
        "> 都不进主 app 的 release 分发物，随包？列写「否」；其余按「进分发物」保守登记。",
        "> 传递闭包不在本节登记范围——",
        "> 它由 Gradle 冲突解析决定，离线按 POM 递归只能得到**推定**集合（420 批标 B 级），",
        "> 那张逐组表与正文覆盖方式见上面 §8 的人工说明；本节只登记构建脚本里**硬证明**的直接声明。",
        "",
    ]
    return "\n".join(lines)


def render_rust(rows: list[dict], stats: dict) -> str:
    lines = [
        "",
        "> **本节由 `tools/gen-notices.py` 生成**（标记 `gen-notices:rust` 之间）。",
        "> 坐标 / 版本 / 来源来自 `Cargo.lock`（workspace 根；派工单写的 `rust/core/Cargo.lock` 不存在）；",
        "> 「是否随包」取 421 批 dep-info 取证集合，许可取带 checksum 绑定的离线登记表；",
        "> 本机 `~/.cargo/registry/{cache,src}` 已清理、sparse index JSON 无 license 字段 ⇒",
        "> 登记表缺该包（或 checksum 与锁文件不符）一律「未取证」，不按 crate 名字猜许可。",
        "",
        "**表 9-A · `Cargo.lock` 第三方 package**（%d 条；进 .so %d / 不进 %d / 许可未取证 %d；"
        "锁文件总 package %d）"
        % (stats["third"], stats["in_so"], stats["not_in_so"], stats["unlicensed"], stats["packages"]),
        "",
        TABLE_HEAD,
        TABLE_SEP,
    ]
    for r in rows:
        lic = _cell(r["license"])
        if r["why"]:
            lic = "%s（%s）" % (lic, _cell(r["why"]))
        lines.append("| `%s` | `%s` | %s | %s | %s | %s |" % (
            _cell(r["coord"]), _cell(r["version"]), lic, _cell(r["scope"]),
            _cell(r["source"]), _cell(r["evidence"])))
    lines += [
        "",
        "> 边界：Rust 标准库自带的 `rust-deps`（addr2line / gimli / rustc-demangle 等）",
        "> **不在 `Cargo.lock` 里**，本表不覆盖；逐条理由见上面 §9.1 的人工取证说明。",
        "",
    ]
    return "\n".join(lines)


def splice(text: str, key: str, body: str) -> str:
    begin, end = MARKERS[key]
    i, j = text.find(begin), text.find(end)
    if i < 0 or j < 0 or j < i:
        raise NoticesError(
            "THIRD-PARTY-LICENSES.md 里 %s 的标记对不完整/顺序不对（生成器不会整篇重写）" % key)
    return text[: i + len(begin)] + "\n" + body.strip("\n") + "\n" + text[j:]


def render_doc(text: str, root: Path, gradle_home: Path, evidence: dict) -> tuple[str, dict]:
    mv, boms, problems = maven_rows(root, gradle_home, evidence)
    rs, stats = rust_rows(root, evidence)
    out = splice(text, "maven", render_maven(mv, boms))
    out = splice(out, "rust", render_rust(rs, stats))
    return out, {
        "maven": mv, "boms": boms, "rust": rs, "stats": stats, "problems": problems,
    }


# ── 差异统计（判据②：增 / 删 / 改版本各几条） ────────────────────────────────
_ROW_RE = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|", re.M)


def table_entries(body: str) -> dict[str, str]:
    return {m.group(1): m.group(2) for m in _ROW_RE.finditer(body)}


def block_body(text: str, key: str) -> str:
    begin, end = MARKERS[key]
    i, j = text.find(begin), text.find(end)
    if i < 0 or j < 0 or j < i:
        return ""
    return text[i + len(begin): j]


def diff_summary(old_text: str, new_text: str) -> str:
    out: list[str] = []
    total_add = total_del = total_ver = 0
    for key in ("maven", "rust"):
        old, new = table_entries(block_body(old_text, key)), table_entries(block_body(new_text, key))
        added, removed, changed = sorted(set(new) - set(old)), sorted(set(old) - set(new)), []
        for k in sorted(set(old) & set(new)):
            if old[k] != new[k]:
                changed.append((k, old[k], new[k]))
        total_add += len(added)
        total_del += len(removed)
        total_ver += len(changed)
        out.append("[%s] 增 %d / 删 %d / 改版本 %d" % (key, len(added), len(removed), len(changed)))
        for k in added:
            out.append("    + %s@%s" % (k, new[k]))
        for k in removed:
            out.append("    - %s@%s" % (k, old[k]))
        for k, a, b in changed:
            out.append("    ~ %s: %s -> %s" % (k, a, b))
    out.insert(0, "合计：增 %d / 删 %d / 改版本 %d" % (total_add, total_del, total_ver))
    return "\n".join(out)


# ── --refresh：从本机 Gradle 缓存重建 Maven 半张登记表 ───────────────────────
def refresh_maven(root: Path, gradle_home: Path, evidence: dict) -> tuple[dict, int, int]:
    P = policy()
    catalog = P.parse_catalog(root / CATALOG_REL)
    modules = P.parse_modules(root)
    fresh: dict[str, dict] = {}
    hit = miss = 0
    all_boms: list[tuple[str, str, str]] = []
    for mod in modules:
        bf = root / mod / "build.gradle.kts"
        if not bf.is_file():
            continue
        raws = P.parse_dependencies(bf)
        module_boms: list[tuple[str, str, str]] = []
        pending: list[tuple[str, str, str | None]] = []
        for config, expr, _lineno in raws:
            got = P.resolve_expr(expr, catalog)
            if got is None:
                continue
            kind, g, n, v = got
            if kind == "bom" and v:
                module_boms.append((g, n, v))
                all_boms.append((g, n, v))
            else:
                pending.append((g, n, v))
        managed = P.bom_versions(gradle_home, module_boms) if module_boms else {}
        for g, n, v in pending:
            if v is None:
                v = managed.get((g, n), "")
            if not v:
                continue
            key = "%s:%s:%s" % (g, n, v)
            if key in fresh:
                continue
            got = P.licenses_for(gradle_home, g, n, v)
            if got["error"] or not got["licenses"]:
                miss += 1
                continue
            ids, unknown = P.to_spdx(got["licenses"])
            if not ids or unknown:
                miss += 1
                continue
            pom = P.pom_path(gradle_home, g, n, v)
            raw = "；".join(str(e.get("name") or e.get("url") or "") for e in got["licenses"])
            # 登记表要进仓，路径写成相对 gradle-home 的形态：不把本机用户名 / 盘符带进仓库，
            # 也不让 CI 拿着 Windows 路径去找证据（证据本身是 pom_sha256 + raw）。
            try:
                pom_rel = pom.relative_to(gradle_home).as_posix() if pom else ""
            except ValueError:
                pom_rel = pom.name if pom else ""
            fresh[key] = {
                "license": " / ".join(sorted(set(ids))),
                "raw": raw,
                "origin": "本地 Gradle 缓存 POM " + pom_rel +
                          ("（" + got["origin"] + "）" if got["origin"] else ""),
                "pom_sha256": hashlib.sha256(pom.read_bytes()).hexdigest() if pom and pom.is_file() else "",
            }
            hit += 1
    evidence["maven"] = fresh
    evidence["bom_versions"] = {
        "%s:%s" % k: v for k, v in sorted(P.bom_versions(gradle_home, all_boms).items())
    }
    return evidence, hit, miss


# ── 主流程 ──────────────────────────────────────────────────────────────────
def run(root: Path, gradle_home: Path, evidence_path: Path, doc_path: Path,
        check: bool, stats_only: bool) -> int:
    if not doc_path.is_file():
        raise NoticesError("找不到 %s" % doc_path)
    evidence = load_evidence(evidence_path)
    old = doc_path.read_text(encoding="utf-8")
    new, data = render_doc(old, root, gradle_home, evidence)

    if stats_only:
        mv, rs, st = data["maven"], data["rust"], data["stats"]
        print("Maven 直接依赖 %d 条（随包 %d / 许可未取证 %d）；BOM %d 条" % (
            len(mv), sum(1 for r in mv if r["scope"].startswith("是")),
            sum(1 for r in mv if r["license"].startswith(UNEVIDENCED)), len(data["boms"])))
        print("Rust 第三方 package %d 条（进 .so %d / 不进 %d / 许可未取证 %d）" % (
            len(rs), st["in_so"], st["not_in_so"], st["unlicensed"]))
        for p in data["problems"]:
            print("::warning::" + p)
        return 0

    if check:
        if new == old:
            print("通过：THIRD-PARTY-LICENSES.md 的两个生成块与依赖真源一致（--check）")
            return 0
        print("::error title=第三方声明生成门禁::清单与依赖真源不一致 —— 跑一次 "
              "`python3 tools/gen-notices.py` 再把产物提交")
        print(diff_summary(old, new))
        for line in list(difflib.unified_diff(
                old.splitlines(), new.splitlines(), fromfile="清单现存", tofile="应生成", lineterm=""))[:80]:
            print(line)
        return 1

    if new != old:
        # 显式 newline="\n"：Path.write_text 的 newline 形参要 3.10+，且 Windows 默认
        # 会把 \n 转成 CRLF；清单在 git 里存的是 LF，写出来必须逐字节同一口径。
        with open(doc_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(new)
        print("已生成：%s（只替换两对标记之间；人工区逐字节未动）" % doc_path.name)
    else:
        print("无变化：生成块已是最新（幂等）")
    mv, rs = data["maven"], data["rust"]
    print("Maven %d 条 / BOM %d 条 / Rust %d 条；未取证 Maven %d、Rust %d" % (
        len(mv), len(data["boms"]), len(rs),
        sum(1 for r in mv if r["license"].startswith(UNEVIDENCED)),
        sum(1 for r in rs if r["license"].startswith(UNEVIDENCED))))
    return 0


# ── --selftest：不依赖真仓、不联网、不跑 Gradle 的夹具 ───────────────────────
SELF_CATALOG = """[versions]
coreKtx = "1.19.1"
poi = "5.5.1"
[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
poi = { group = "org.apache.poi", name = "poi", version.ref = "poi" }
newthing = { group = "com.example", name = "newthing", version.ref = "poi" }
"""
SELF_SETTINGS = 'include(":app")\n'
SELF_BUILD = """dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.poi)
    testImplementation("junit:junit:4.13.2")
}
"""
SELF_LOCK = """version = 4

[[package]]
name = "zhengdao_core"
version = "0.1.0"
dependencies = [
 "alpha",
 "beta",
]

[[package]]
name = "alpha"
version = "1.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "aaa"

[[package]]
name = "beta"
version = "2.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "bbb"
dependencies = [
 "alpha",
]
"""
SELF_MANIFEST = """[package]
name = "zhengdao_core"
[dependencies]
alpha = "1.0"
"""
SELF_EVIDENCE = {
    "schema": 1,
    "maven": {},
    "rust": {
        "in_so": ["alpha-1.0.0", "beta-2.0.0"],
        "not_in_so": [],
        "licenses": {
            "alpha@1.0.0": {"license": "MIT OR Apache-2.0", "checksum": "aaa",
                            "raw": "MIT OR Apache-2.0", "origin": "421 .crate 校验绑定"},
            "beta@2.0.0": {"license": "MIT", "checksum": "bbb",
                           "raw": "MIT", "origin": "421 .crate 校验绑定"},
        },
    },
}
SELF_DOC_HEAD = """# 第三方组件声明（THIRD-PARTY NOTICES）

> 人工说明区第一段：**手改我，生成器不许碰**。

## 资产类（人工维护）

- Maple Mono：OFL 1.1（资产类，义务形态与代码依赖不同）

## 8. Maven 运行时依赖（人工说明文字）

"""
SELF_DOC_TAIL = """
## 9. Rust 核（人工说明文字）

### 9.1 取证说明（人工写，生成器不许碰）

"""
SELF_DOC_ARCHIVE = """
## 历史节（已迁出：zhengdao-term）

### [已迁出：zhengdao-term] 1. proot

保留原文以保追溯，不删。

### [已迁出：zhengdao-term] 2. libtalloc

同上。
"""


def _self_tree(tmp: Path) -> tuple[Path, Path, Path, Path]:
    (tmp / "tools").mkdir(parents=True, exist_ok=True)
    (tmp / "gradle").mkdir(parents=True, exist_ok=True)
    (tmp / "rust" / "core").mkdir(parents=True, exist_ok=True)
    (tmp / "app").mkdir(parents=True, exist_ok=True)
    (tmp / "gradle" / "libs.versions.toml").write_text(SELF_CATALOG, encoding="utf-8")
    (tmp / "settings.gradle.kts").write_text(SELF_SETTINGS, encoding="utf-8")
    (tmp / "app" / "build.gradle.kts").write_text(SELF_BUILD, encoding="utf-8")
    (tmp / "rust" / "Cargo.lock").write_text(SELF_LOCK, encoding="utf-8")
    (tmp / "rust" / "core" / "Cargo.toml").write_text(SELF_MANIFEST, encoding="utf-8")
    ev = tmp / "tools" / "notices-evidence.json"
    ev.write_text(json.dumps(SELF_EVIDENCE, ensure_ascii=False, indent=2), encoding="utf-8")
    doc = tmp / "THIRD-PARTY-LICENSES.md"
    body = SELF_DOC_HEAD
    for key in ("maven", "rust"):
        b, e = MARKERS[key]
        body += b + "\n" + e + "\n"
    doc.write_text(body + SELF_DOC_TAIL + SELF_DOC_ARCHIVE, encoding="utf-8")
    gh = tmp / "gradle-home"
    cache = gh / "caches" / "modules-2" / "files-2.1"
    pom = ("<project><modelVersion>4.0.0</modelVersion>"
           "<licenses><license><name>The Apache Software License, Version 2.0</name>"
           "</license></licenses></project>")
    for g, n, v in (("androidx.core", "core-ktx", "1.19.1"),
                    ("org.apache.poi", "poi", "5.5.1"),
                    ("junit", "junit", "4.13.2")):
        d = cache / g / n / v / "deadbeef"
        d.mkdir(parents=True, exist_ok=True)
        (d / ("%s-%s.pom" % (n, v))).write_text(pom, encoding="utf-8")
    return tmp, gh, ev, doc


def _row_for(body: str, coord: str) -> str:
    """自检专用：取生成块里某坐标的那一行（避免「正文里出现过就算过」的恒真断言）。"""
    for line in body.splitlines():
        if line.startswith("| `%s` |" % coord):
            return line
    return ""


def selftest() -> int:
    cases: list[tuple[str, bool, str]] = []

    def case(name: str, ok: bool, detail: str = "") -> None:
        cases.append((name, bool(ok), detail))

    tmp = Path(tempfile.mkdtemp(prefix="zd-notices-selftest-"))
    try:
        root, gh, ev, doc = _self_tree(tmp)

        # ① 幂等：连跑两次逐字节相同
        rc1 = run(root, gh, ev, doc, check=False, stats_only=False)
        first = doc.read_bytes()
        rc2 = run(root, gh, ev, doc, check=False, stats_only=False)
        second = doc.read_bytes()
        case("幂等：连跑两次输出逐字节相同", rc1 == 0 and rc2 == 0 and first == second,
             "第一次 %d B / 第二次 %d B" % (len(first), len(second)))

        # ② --check 基线绿
        case("--check 在一致时返回 0", run(root, gh, ev, doc, check=True, stats_only=False) == 0)

        # ②·补 来源列不许带行号（2026-11-05 用户定）：`build.gradle.kts:NNN` 会被注释位移带崩，
        # #1839/#1840 就是这么连红两轮的；产物里只留「文件（配置）」。
        text_first = first.decode("utf-8")
        case("来源列不带行号（不许出现 build.gradle.kts:数字）",
             "build.gradle.kts:" not in text_first and "build.gradle.kts（" in text_first,
             "带冒号行号的来源数：%d" % text_first.count("build.gradle.kts:"))

        # ③ 手改说明文字 / 资产类 / 历史节 ⇒ 再生成仍在，且生成块逐字节不变
        text = doc.read_text(encoding="utf-8")
        marker_before = "<!-- HAND-EDITED-说明文字-424 -->\n"
        asset_before = "<!-- HAND-EDITED-资产类-424 -->\n"
        archive_before = "<!-- HAND-EDITED-历史节-424 -->\n"
        text = text.replace("> 人工说明区第一段", marker_before + "> 人工说明区第一段")
        text = text.replace("## 资产类（人工维护）", "## 资产类（人工维护）\n" + asset_before)
        text = text.replace("## 历史节（已迁出：zhengdao-term）",
                            "## 历史节（已迁出：zhengdao-term）\n" + archive_before)
        doc.write_text(text, encoding="utf-8")
        gen_before = (block_body(text, "maven"), block_body(text, "rust"))
        run(root, gh, ev, doc, check=False, stats_only=False)
        after = doc.read_text(encoding="utf-8")
        gen_after = (block_body(after, "maven"), block_body(after, "rust"))
        case("手改说明文字后再生成仍在", marker_before.strip() in after)
        case("资产类人工区不被覆盖", asset_before.strip() in after)
        case("历史节（已迁出）不被覆盖", archive_before.strip() in after)
        case("人工区改动不影响生成块（幂等固定点）", gen_before == gen_after)
        case("--check 对手改说明文字仍绿", run(root, gh, ev, doc, check=True, stats_only=False) == 0)

        # ④ 缓存缺 POM ⇒ 未取证，不许猜
        cold = tmp / "cold-home"
        cold.mkdir()
        run(root, cold, ev, doc, check=False, stats_only=False)
        cold_text = doc.read_text(encoding="utf-8")
        cold_maven = block_body(cold_text, "maven")
        cold_rows = [_row_for(cold_maven, c) for c in (
            "androidx.core:core-ktx:1.19.1", "org.apache.poi:poi:5.5.1", "junit:junit:4.13.2")]
        case("空缓存下 3 条直接依赖逐行都写「未取证」",
             all(UNEVIDENCED in r for r in cold_rows)
             and all("Apache-2.0" not in r for r in cold_rows),
             " | ".join(r[:60] for r in cold_rows))
        case("空缓存下 Rust 登记表仍生效（不误伤已取证）",
             "MIT OR Apache-2.0" in _row_for(block_body(cold_text, "rust"), "alpha@1.0.0"))

        # ⑤ 新坐标 + 空缓存 + 登记表无该坐标 ⇒ 未取证；登记表有但手写入无证据 ⇒ 仍不猜
        (root / "app" / "build.gradle.kts").write_text(
            SELF_BUILD.replace("    testImplementation", "    implementation(libs.newthing)\n    testImplementation"),
            encoding="utf-8")
        run(root, cold, ev, doc, check=False, stats_only=False)
        new_body = block_body(doc.read_text(encoding="utf-8"), "maven")
        new_row = _row_for(new_body, "com.example:newthing:5.5.1")
        case("新增依赖且缓存/登记表都缺 POM ⇒ 未取证",
             bool(new_row) and UNEVIDENCED in new_row and "Apache-2.0" not in new_row,
             new_row[:100])
        data = json.loads(ev.read_text(encoding="utf-8"))
        data["maven"]["com.example:newthing:5.5.1"] = {"license": "MIT"}  # 无 origin/raw = 不算证据
        ev.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        run(root, cold, ev, doc, check=False, stats_only=False)
        fake_row = _row_for(block_body(doc.read_text(encoding="utf-8"), "maven"),
                            "com.example:newthing:5.5.1")
        case("登记表里手写许可（无取证依据）不算证据 ⇒ 仍「未取证」",
             bool(fake_row) and UNEVIDENCED in fake_row and "MIT" not in fake_row,
             fake_row[:100])

        # ⑥ --check 能咬住：改真源 / 改生成块 / 删标记
        (root / "app" / "build.gradle.kts").write_text(
            SELF_BUILD.replace("    testImplementation",
                               '    implementation("com.example:extra:9.9.9")\n    testImplementation'),
            encoding="utf-8")
        case("--check 咬住「依赖真源变了但清单没重生成」",
             run(root, gh, ev, doc, check=True, stats_only=False) == 1)
        (root / "app" / "build.gradle.kts").write_text(SELF_BUILD, encoding="utf-8")
        run(root, gh, ev, doc, check=False, stats_only=False)
        before_tamper = doc.read_text(encoding="utf-8")
        tampered = before_tamper.replace("| `androidx.core:core-ktx:1.19.1` |",
                                         "| `androidx.core:core-ktx-FAKE:1.19.1` |")
        doc.write_text(tampered, encoding="utf-8")
        case("--check 咬住「生成块被手改」",
             tampered != before_tamper and run(root, gh, ev, doc, check=True, stats_only=False) == 1)
        doc.write_text(tampered.replace(MARKERS["rust"][1], ""), encoding="utf-8")
        try:
            run(root, gh, ev, doc, check=True, stats_only=False)
            missing_marker_ok = False
        except NoticesError:
            missing_marker_ok = True
        case("标记对缺失 ⇒ 报错退出（不整篇重写）", missing_marker_ok)

        # ⑦ 登记表 checksum 与 Cargo.lock 不符 ⇒ 该 crate 许可变「未取证」
        root2 = tmp / "tree2"
        root2.mkdir()
        r2, gh2, ev2, doc2 = _self_tree(root2)
        d2 = json.loads(ev2.read_text(encoding="utf-8"))
        d2["rust"]["licenses"]["alpha@1.0.0"]["checksum"] = "TAMPERED"
        ev2.write_text(json.dumps(d2, ensure_ascii=False, indent=2), encoding="utf-8")
        run(r2, gh2, ev2, doc2, check=False, stats_only=False)
        alpha_line = [ln for ln in block_body(doc2.read_text(encoding="utf-8"), "rust").splitlines()
                      if "alpha@1.0.0" in ln]
        case("登记表 checksum 与 Cargo.lock 不符 ⇒ 许可写「未取证」",
             bool(alpha_line) and UNEVIDENCED in alpha_line[0], alpha_line[0] if alpha_line else "无 alpha 行")

        # ⑧ diff 统计能给出增 / 删 / 改版本三类
        a = SELF_DOC_HEAD
        for key in ("maven", "rust"):
            b, e = MARKERS[key]
            a += b + "\n| `x:y:1` | `1` | MIT | 是 | s | e |\n" + e + "\n"
        bm, br = MARKERS["maven"][0], MARKERS["rust"][0]
        btxt = a.replace(bm + "\n| `x:y:1` | `1` |", bm + "\n| `x:y:1` | `2` |")
        btxt = btxt.replace(br + "\n| `x:y:1` | `1` |",
                            br + "\n| `z:w:3` | `3` | MIT | 是 | s | e |\n| `x:y:1` | `1` |")
        summary = diff_summary(a, btxt)
        case("差异统计含增 / 删 / 改版本三栏",
             "合计：增 1 / 删 0 / 改版本 1" in summary
             and "    + z:w:3@3" in summary
             and "    ~ x:y:1: 1 -> 2" in summary,
             summary.replace("\n", " / "))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    bad = [c for c in cases if not c[1]]
    for name, ok, detail in cases:
        print(("  PASS  " if ok else "  FAIL  ") + name + (("  | " + detail) if detail else ""))
    print("\n夹具：%d 项，通过 %d，失败 %d" % (len(cases), len(cases) - len(bad), len(bad)))
    return 1 if bad else 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="从依赖真源生成 THIRD-PARTY-LICENSES.md 的依赖各节")
    ap.add_argument("--check", action="store_true", help="清单与依赖不一致 ⇒ 退出 1（CI 用）")
    ap.add_argument("--stats", action="store_true", help="只打印条目统计")
    ap.add_argument("--refresh", action="store_true", help="从本机 Gradle 缓存重建 Maven 登记表")
    ap.add_argument("--selftest", action="store_true", help="夹具自检（不联网）")
    ap.add_argument("--gradle-home", default=None, help="Gradle 缓存根（默认 GRADLE_USER_HOME 或 ~/.gradle）")
    ap.add_argument("--root", default=None, help="仓根（自检 / 跨工作树排查用）")
    args = ap.parse_args(argv[1:])

    if args.selftest:
        return selftest()
    try:
        root = Path(args.root).resolve() if args.root else ROOT
        gh = Path(args.gradle_home).resolve() if args.gradle_home else default_gradle_home()
        ev = root / EVIDENCE_REL
        doc = root / DOC_REL
        if args.refresh:
            evidence, hit, miss = refresh_maven(root, gh, load_evidence(ev))
            ev.write_text(json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                          encoding="utf-8")
            print("登记表已刷新：%s（Maven 取证 %d 条 / 缺 POM 或归一不了 %d 条；Rust 半张原样保留）"
                  % (ev.name, hit, miss))
            return 0
        return run(root, gh, ev, doc, args.check, args.stats)
    except NoticesError as exc:
        print("::error::" + str(exc))
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
