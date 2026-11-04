#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-license-policy.py —— 许可政策门禁（第四百二十三批）。

一句话
------
把「哪些许可准用、哪些绝不许进来」写成机器判据，**在 CI 上会红**。

为什么要有这一步
----------------
已有的两个脚本都不回答「这个许可我们准不准用」：
  * `tools/check-licenses.py` 管的是「仓库 `licenses/` 里的正文有没有逐字节进 APK」；
  * `tools/license-report.py` 管的是「分布与新鲜度报告」，report-only、不判红。
于是第二百五十二批为读旧版 .xls 引入 jxl（LGPL-2.1，聚合分发）时，没有任何一处机器
判据拦过它——只能事后在 `licenses/README.md` 里补一段解释。本脚本把政策落成代码，
让「先斩后奏」从流程上变成红的。

政策（写死在下面的常量里；改政策 = 改这个文件 + 过评审，不许在单子里口头放宽）
------------------------------------------------------------------------
* **允许**：Apache-2.0 / MIT / BSD-2-Clause / BSD-3-Clause / ISC / Zlib / 0BSD /
  Unlicense / MPL-2.0
* **禁止**：GPL-2.0 / GPL-3.0 / AGPL / LGPL-2.1 / LGPL-3.0 / SSPL —— 按**许可家族**判，
  任意版本（含 `-only` / `-or-later` / `+` 后缀）都算禁止
* **未登记**：既不在允许表也不在禁止表（EPL / CDDL / BouncyCastle 这类）、POM 里没有
  许可声明、本地缓存里根本没有这份 POM —— 一律**不算通过**，必须先在
  `licenses/EXCEPTIONS.md` 逐条登记

取证口径（**不联网**，全部读本地）
--------------------------------
1. **直接依赖**：`gradle/libs.versions.toml` 的 `[libraries]` ＋ 各模块 `build.gradle.kts`
   的 `dependencies { }` 块。四种写法都认：catalog 访问器 `libs.foo.bar`、字面坐标
   `"g:n:v"`、`add("cfg", …)`、以及 `platform(…)`（BOM 不进分发物，只用来解析别人的
   版本，本身不判许可）；`project(":core")` 是模块内依赖，不是外部构件，跳过并计数；
2. **许可**：从本地 Gradle 缓存 `<gradle-home>/caches/modules-2/files-2.1/<group>/<name>/
   <version>/<hash>/<name>-<version>.pom` 取 `<licenses>`；自己没写就顺 `<parent>` 链继承
   （深度 ≤ 5，父 POM 也要在缓存里）；
3. **BOM 托管版本**（catalog 里没写 version 的 compose 系）：读同一份缓存里的 BOM POM 的
   `<dependencyManagement>` 解析出版本，再按版本找 POM；
4. 任何一步取不到 ⇒ 状态是「未取证（缓存缺）」，**不是通过**。这不是洁癖：把「查不到」
   当成「没问题」，门禁就只在你已经知道答案时才有效。

许可名的归一表与 POM 解析**复用 `tools/license-report.py`**（`LICENSE_NAME_TO_SPDX` /
`parse_pom`），保证同一份报告和门禁不会有第二套口径；本脚本只加「政策判据」这一层。

判据与退出码
------------
    0 = 全部允许，或已在登记表里逐条豁免
    1 = 有禁止 / 未登记 / 未取证，且没有对应豁免（CI 这一步红）
    2 = 环境或解析错误（目录结构变了、catalog 里的别名解析不出、登记表不合格到没法读）

**判谁不判谁**：许可义务挂在「分发」这个动作上，所以只判**进分发物**的 configuration
（`implementation` / `api` / `runtimeOnly` / `releaseImplementation` /
`debugImplementation`，以及任何不认识的 configuration——不认识的按分发物保守处理）。
`testImplementation` / `androidTestImplementation` / `compileOnly` / 构建工具（`detektCli`）
一个字节都不进 APK，不判，但**每次都会逐条列出来**（含许可），要看全量加 `--all-scopes`。
不这么切的话，junit（EPL-1.0）这种人人都在用的测试库会让门禁常红，红点就没人看了。

`--selftest` 不需要网络、不需要真缓存：自己在临时目录里造树跑 20 组夹具，覆盖
允许 / 禁止 / 未知三类，外加豁免五条边界、范围三条、两条「假绿」防线与一条离线断言。

用法
----
    python3 tools/check-license-policy.py               # 门禁（CI 用这个）
    python3 tools/check-license-policy.py --verbose     # 逐条列出每条直接依赖的判定
    python3 tools/check-license-policy.py --selftest    # 自检
    python3 tools/check-license-policy.py --gradle-home D:/gd   # 换缓存位置（本机排查用）
    python3 tools/check-license-policy.py --assets      # 资产登记与漏登检查（不读 Gradle 缓存）
    python3 tools/check-license-policy.py --assets --selftest   # 资产模式的独立自检

资产口径（第四百三十三批）
------------------------
读 licenses/ASSETS.md 六列表：资产名 | 版本 | 许可 | 随包？ | 署名要求 | 来源。
资产名用相对 app/src/main/assets/ 的精确 POSIX 文件路径（可用反引号包裹），
一文件一行，不认目录或通配；随包？只填「是/否」。六格不得为空，明确无署名要求填「无」。
登记不是豁免：所有登记行都用同一套 ALLOWED / DENY_FAMILIES / classify 判定，
未知许可也红；不把 Maven 坐标豁免套到资产。许可允许只代表政策通过，不代表义务已履行。
递归核对目录内所有文件，不按后缀猜第三方、不暗中排除第一方文件；未登记即红。
初始空表合法，无文件则绿，有既存未登记文件则红；不替用户编造登记或许可。
本模式仅检查源码 assets 目录，构建生成资产、其它 source set 和 APK 内容不在此取证范围。
"""

from __future__ import annotations

import argparse
import datetime as _dt
import importlib.util
import os
import re
import sys
import tempfile
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass


ROOT = Path(__file__).resolve().parent.parent

# ── 政策（本文件的真源；「为什么是这几个」见模块 docstring）─────────────────────────
ALLOWED = (
    "Apache-2.0",
    "MIT",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "ISC",
    "Zlib",
    "0BSD",
    "Unlicense",
    "MPL-2.0",
)
# 禁止按**家族**判：GPL-2.0+ / GPL-3.0-only / LGPL-2.1-or-later / AGPL-3.0 / SSPL-1.0
# 都必须落网。用前缀而不是逐版本枚举，是因为 Maven 上的写法比 SPDX 粗得多
# （jxl 的 POM 只写「GNU Lesser General Public License」，一个版本号都没有）。
DENY_FAMILIES = ("GPL", "AGPL", "LGPL", "SSPL")

EXCEPTIONS_REL = "licenses/EXCEPTIONS.md"
CACHE_REL = Path("caches") / "modules-2" / "files-2.1"
PARENT_DEPTH = 5

# 哪些 configuration 的依赖会**进分发物**。只判这些，理由写在 reports 与下面注释里：
# 许可义务挂在「分发」这个动作上——testImplementation / androidTestImplementation /
# compileOnly / 构建工具（detektCli）一个字节都不进 APK，拿它们判红等于让 junit
# （EPL-1.0）这种人人都在用的测试库把门禁常红，红点就没人看了。
# 但「不判」≠「不说」：这些条目每次都会在输出里逐条列出（连许可一起），
# 想看全量就加 `--all-scopes`。未知的新 configuration 一律按**进分发物**保守处理。
NOT_DISTRIBUTED_CONFIGS = frozenset({
    "compileOnly", "compileOnlyApi",
    "testImplementation", "testRuntimeOnly", "testCompileOnly",
    "androidTestImplementation", "androidTestRuntimeOnly", "androidTestCompileOnly",
    "benchmarkImplementation", "benchmarkRuntimeOnly",
    "detektCli", "lintChecks", "lintPublish",
})

STATUS_ZH = {
    "allowed": "允许",
    "exempted": "已豁免",
    "denied": "禁止",
    "unlisted": "未登记",
    "unevidenced": "未取证",
}


def _local(tag) -> str:
    """XML 标签的本地名（剥掉 `{namespace}`）。

    ElementTree 的 `{*}` 通配**匹配不了无命名空间的元素**：`maven-metadata.xml` 不带
    xmlns，而 POM 带 `POM/4.0.0`。两种混在一起时只有按本地名匹配才通吃（同一坑
    `license-report.py` 已踩过一次）。"""
    if not isinstance(tag, str):
        return ""
    return tag.rsplit("}", 1)[-1]


# ── 归一表：唯一来源是 tools/license-report.py ───────────────────────────────────
_SHARED = None


class GateError(RuntimeError):
    """环境/解析错误 —— 对应退出码 2。绝不用它表示「发现违规」。"""


def shared():
    """加载同目录的 license-report.py（只执行常量与函数定义，无副作用）。"""
    global _SHARED
    if _SHARED is None:
        path = Path(__file__).resolve().parent / "license-report.py"
        if not path.is_file():
            raise GateError("找不到 tools/license-report.py（它是许可名归一表与 POM 解析的唯一来源）")
        spec = importlib.util.spec_from_file_location("zd_license_report", path)
        if spec is None or spec.loader is None:
            raise GateError("tools/license-report.py 加载不了（importlib 拿不到 loader）")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _SHARED = mod
    return _SHARED


# 没有 <name>、只有 <url> 的 POM：按 URL 归一。只认死 URL，不做模糊匹配
# （模糊匹配等于替对方猜许可，比「未知」更糟）。顺序有意义：先具体后笼统。
URL_HINTS = (
    ("apache.org/licenses/license-2.0", "Apache-2.0"),
    ("opensource.org/licenses/apache2.0", "Apache-2.0"),
    ("opensource.org/licenses/mit", "MIT"),
    ("opensource.org/licenses/bsd-2-clause", "BSD-2-Clause"),
    ("opensource.org/licenses/bsd-3-clause", "BSD-3-Clause"),
    ("opensource.org/licenses/isc", "ISC"),
    ("opensource.org/licenses/zlib", "Zlib"),
    ("opensource.org/licenses/0bsd", "0BSD"),
    ("unlicense.org", "Unlicense"),
    ("mozilla.org/mpl/2.0", "MPL-2.0"),
    ("gnu.org/licenses/agpl", "AGPL-3.0"),
    ("gnu.org/licenses/lgpl-3.0", "LGPL-3.0"),
    ("gnu.org/licenses/lgpl-2.1", "LGPL-2.1"),
    ("gnu.org/licenses/gpl-3.0", "GPL-3.0"),
    ("gnu.org/licenses/gpl-2.0", "GPL-2.0"),
    ("opensource.org/licenses/lgpl-license", "LGPL-2.1"),  # jxl 用的就是这个老 URL
    ("opensource.org/licenses/gpl-license", "GPL-2.0"),
)


def canon(lic: str) -> str:
    """SPDX id 归一：去 `-only` / `-or-later` / 尾部 `+`，保留大小写原样比对用。

    `LGPL-2.1+`、`LGPL-2.1-only`、`LGPL-2.1-or-later` 都归一成 `LGPL-2.1`。"""
    s = (lic or "").strip()
    s = re.sub(r"-(?:only|or-later)$", "", s, flags=re.IGNORECASE)
    s = s.rstrip("+").strip()
    return s


def family_of(lic: str) -> str:
    """许可家族：`LGPL-2.1` → `LGPL`；`Apache-2.0` → `Apache`。"""
    return canon(lic).split("-")[0].upper()


def is_allowed_id(lic: str) -> bool:
    c = canon(lic).lower()
    return any(c == a.lower() for a in ALLOWED)


def classify(ids: list[str]) -> str:
    """SPDX id 列表 → allowed / denied / unlisted。

    多许可（dual licensing）取**最保守**：只要有一个落在禁止家族里就算禁止。
    理由：判据是「不许把 GPL 类带进分发物」，而 dual 的另一条腿能不能用取决于
    法务怎么选，不是脚本能替用户决定的。"""
    if any(family_of(i) in DENY_FAMILIES for i in ids):
        return "denied"
    if ids and all(is_allowed_id(i) for i in ids):
        return "allowed"
    return "unlisted"


def url_hint(url: str) -> str:
    low = (url or "").lower()
    for needle, spdx in URL_HINTS:
        if needle in low:
            return spdx
    return ""


# 补充归一表：`license-report.py` 的 `LICENSE_NAME_TO_SPDX` 是为「出报告」攒的，GPL 家族只
# 收了 v2.0 一种写法；门禁要认的是**家族**，所以这里补上现实 POM 里真实出现的拼法
# （v3.0 / v3 / GPLv3 / 「仅 SSPL」等）。查到就用，查不到仍旧落「未取证 ⇒ 红」，
# 不因为补了这张表就把未知放过去。
# 键 = 去掉括号/逗号、压平空白后的小写名（与 `_loose_key()` 一致）。
EXTRA_LICENSE_NAMES = {
    "gnu general public license version 3.0": "GPL-3.0",
    "gnu general public license version 3": "GPL-3.0",
    "gnu general public license v3.0": "GPL-3.0",
    "gnu general public license v3": "GPL-3.0",
    "gnu general public license 3.0": "GPL-3.0",
    "gpl 3.0": "GPL-3.0",
    "gpl-3.0": "GPL-3.0",
    "gplv3": "GPL-3.0",
    "gnu general public license version 2.0": "GPL-2.0",
    "gnu general public license version 2": "GPL-2.0",
    "gnu general public license v2.0": "GPL-2.0",
    "gnu general public license v2": "GPL-2.0",
    "gpl 2.0": "GPL-2.0",
    "gpl-2.0": "GPL-2.0",
    "gplv2": "GPL-2.0",
    "gnu affero general public license version 3.0": "AGPL-3.0",
    "gnu affero general public license version 3": "AGPL-3.0",
    "gnu affero general public license v3.0": "AGPL-3.0",
    "gnu affero general public license v3": "AGPL-3.0",
    "affero general public license version 3.0": "AGPL-3.0",
    "agpl 3.0": "AGPL-3.0",
    "agpl-3.0": "AGPL-3.0",
    "agplv3": "AGPL-3.0",
    "gnu lesser general public license version 3.0": "LGPL-3.0",
    "gnu lesser general public license version 3": "LGPL-3.0",
    "gnu lesser general public license v3.0": "LGPL-3.0",
    "gnu lesser general public license v3": "LGPL-3.0",
    "lgpl 3.0": "LGPL-3.0",
    "lgpl-3.0": "LGPL-3.0",
    "lgplv3": "LGPL-3.0",
    "gnu lesser general public license version 2.1": "LGPL-2.1",
    "gnu lesser general public license v2.1": "LGPL-2.1",
    "lgpl 2.1": "LGPL-2.1",
    "lgpl-2.1": "LGPL-2.1",
    "lgplv2.1": "LGPL-2.1",
    "server side public license": "SSPL-1.0",
    "server side public license sspl version 1": "SSPL-1.0",
    "sspl": "SSPL-1.0",
    "sspl 1.0": "SSPL-1.0",
    "sspl-1.0": "SSPL-1.0",
    "eclipse public license version 2.0": "EPL-2.0",
    "eclipse public license v2.0": "EPL-2.0",
    "eclipse public license 2.0": "EPL-2.0",
    "epl 2.0": "EPL-2.0",
    "eclipse public license version 1.0": "EPL-1.0",
    "eclipse public license v1.0": "EPL-1.0",
    "epl 1.0": "EPL-1.0",
    "common development and distribution license cddl version 1.1": "CDDL-1.1",
    "common development and distribution license 1.1": "CDDL-1.1",
    "cddl 1.1": "CDDL-1.1",
    "bouncy castle licence": "BouncyCastle",
    "bouncy castle license": "BouncyCastle",
}


def _loose_key(name: str) -> str:
    """POM 许可名 → 查表键：去括号、去逗号、压平空白、小写。"""
    s = (name or "").lower().replace("(", " ").replace(")", " ").replace(",", " ")
    return re.sub(r"\s+", " ", s).strip()


def to_spdx(entries: list[dict]) -> tuple[list[str], list[str]]:
    """POM `<license>` 列表 → (SPDX id 列表, 归一不了的原文列表)。"""
    table = shared().LICENSE_NAME_TO_SPDX
    ids: list[str] = []
    unknown: list[str] = []
    for entry in entries:
        name = (entry.get("name") or "").strip()
        url = (entry.get("url") or "").strip()
        spdx = ""
        if name:
            # 先按「出报告那边」的原键查（含逗号的那套写法），查不到再用放宽键查补充表
            key = " ".join(name.lower().replace("(", " ").replace(")", " ").split())
            spdx = table.get(key, "") or EXTRA_LICENSE_NAMES.get(_loose_key(name), "")
        if not spdx and url:
            spdx = url_hint(url)
        if spdx:
            if spdx not in ids:
                ids.append(spdx)
        else:
            unknown.append(name or url or "(空)")
    return ids, unknown


# ── 读构建文件 ────────────────────────────────────────────────────────────────
def parse_catalog(path: Path) -> dict:
    """最小 TOML 解析：只要 [versions] 与 [libraries]（不引第三方解析器）。"""
    versions: dict[str, str] = {}
    libraries: dict[str, dict] = {}
    section = ""
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
        if section == "versions":
            versions[key] = value.strip().strip('"').strip("'")
        else:
            entry: dict[str, str] = {}
            for m in re.finditer(r'(\w+(?:\.\w+)*)\s*=\s*"([^"]*)"', value):
                entry[m.group(1)] = m.group(2)
            libraries[key] = entry
    return {"versions": versions, "libraries": libraries}


def alias_coords(alias: str, catalog: dict) -> tuple[str, str, str | None] | None:
    """`androidx.core.ktx` → `androidx-core-ktx` → (group, name, version|None)。"""
    entry = catalog["libraries"].get(alias)
    if entry is None:
        return None
    group = entry.get("group", "")
    name = entry.get("name") or alias.rsplit("-", 1)[-1]
    version = entry.get("version")
    if version is None and "version.ref" in entry:
        version = catalog["versions"].get(entry["version.ref"])
    if not group or not name:
        return None
    return group, name, version


def parse_modules(root: Path) -> list[str]:
    """从 settings.gradle.kts 的 include(…) 取模块名（模块列表的真源在这，不在 glob）。"""
    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        raise GateError("找不到 settings.gradle.kts —— 不知道该扫哪些模块")
    text = settings.read_text(encoding="utf-8", errors="replace")
    mods: list[str] = []
    for m in re.finditer(r"^\s*include\s*\(([^)]*)\)", text, re.MULTILINE):
        for name in re.findall(r'"(:[^"]+)"', m.group(1)):
            mod = name.lstrip(":")
            if mod and mod not in mods:
                mods.append(mod)
    if not mods:
        raise GateError("settings.gradle.kts 里一个 include(\":xxx\") 都没解析到 —— 解析器与仓库结构脱节了")
    return mods


_DECL = re.compile(r"^([A-Za-z_]\w*)\s*\((.*)\)\s*\{?$")


def _strip_comment(line: str) -> str:
    idx = line.find("//")
    return line[:idx] if idx >= 0 else line


def parse_dependencies(path: Path) -> list[tuple[str, str, int]]:
    """`dependencies { }` 顶层的 (configuration, 表达式, 行号)。

    只取**块的第一层**：`implementation(libs.jxl) { exclude(...) }` 里 exclude 那几行
    是这条依赖的属性，不是新依赖（brace 深度 >1 时跳过，正好也挡住 `exclude { }` 嵌套）。
    行号带出来是给报告用的：红了要能直接指到哪一行，不然维护者还得自己找。
    """
    out: list[tuple[str, str, int]] = []
    depth = 0
    in_block = False
    for lineno, raw in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = _strip_comment(raw).strip()
        if not line:
            continue
        if not in_block:
            if re.match(r"^dependencies\s*\{", line):
                in_block = True
                depth = line.count("{") - line.count("}")
            continue
        if depth == 1:
            m = _DECL.match(line)
            if m:
                fn, args = m.group(1), m.group(2).strip()
                if fn == "add":
                    am = re.match(r'^"([^"]+)"\s*,\s*(.+)$', args)
                    if am:
                        out.append((am.group(1), am.group(2).strip(), lineno))
                elif fn not in ("exclude", "because"):
                    out.append((fn, args, lineno))
        depth += line.count("{") - line.count("}")
        if depth <= 0:
            in_block = False
            depth = 0
    return out


def resolve_expr(expr: str, catalog: dict) -> tuple[str, str, str, str | None] | None:
    """表达式 → (kind, group, name, version|None)。

    kind: dep（普通依赖）/ bom（platform(...) 的 BOM）/ None（模块内 project(...) 依赖）。
    解析不了抛 GateError：这说明脚本与构建文件的写法脱节了，宁可红得响亮，也不静默少查一条。
    """
    e = expr.strip()
    kind = "dep"
    while True:
        m = re.match(r"^(platform|enforcedPlatform|testFixtures)\s*\((.*)\)$", e)
        if not m:
            break
        if m.group(1) in ("platform", "enforcedPlatform"):
            kind = "bom"
        e = m.group(2).strip()
    if e.startswith(("project(", "files(", "fileTree(")):
        return None
    m = re.match(r"^libs\.([A-Za-z0-9_.]+)$", e)
    if m:
        alias = m.group(1).replace(".", "-")
        coords = alias_coords(alias, catalog)
        if coords is None:
            raise GateError("catalog 里没有别名 %s（表达式：%s）—— 版本目录与构建脚本脱节了"
                            % (alias, expr))
        g, n, v = coords
        return kind, g, n, v
    m = re.match(r'^"([^"]+)"$', e)
    if m:
        gav = m.group(1).split(":")
        if len(gav) == 3:
            return kind, gav[0], gav[1], gav[2]
        if len(gav) == 2:
            return kind, gav[0], gav[1], None
        raise GateError("字面坐标看不懂：%s" % expr)
    raise GateError("依赖表达式解析不了：%s —— 脚本需要认识这种新写法" % expr)


# ── 本地缓存取证 ──────────────────────────────────────────────────────────────
def cache_root(gradle_home: Path) -> Path:
    return Path(gradle_home) / CACHE_REL


def pom_path(gradle_home: Path, g: str, n: str, v: str) -> Path | None:
    """`<gh>/caches/modules-2/files-2.1/<group>/<name>/<version>/<sha1>/<name>-<version>.pom`。

    缓存路径里 group 是**带点的整串目录**（`net.sourceforge.jexcelapi/jxl/...`），
    不是 Maven 仓库那种 com/google 分层——写成分层就永远查不到。"""
    base = cache_root(gradle_home) / g / n / v
    if not base.is_dir():
        return None
    hits = sorted(base.glob("*/%s-%s.pom" % (n, v)))
    return hits[0] if hits else None


def bom_versions(gradle_home: Path, boms: list[tuple[str, str, str]]) -> dict[tuple[str, str], str]:
    """BOM POM 的 `<dependencyManagement>` → {(group, name): version}。

    catalog 里 compose 系（runtime / ui / material3 …）没写 version，版本是 BOM 在解析期
    给的；离线要按版本找 POM，只能先把 BOM 自己读开。"""
    table: dict[tuple[str, str], str] = {}
    for g, n, v in boms:
        path = pom_path(gradle_home, g, n, v)
        if path is None:
            continue
        try:
            root = ET.fromstring(path.read_bytes())
        except (ET.ParseError, OSError):
            continue
        for dm in root.iter():
            if _local(dm.tag) != "dependencyManagement":
                continue
            for dep in dm.iter():
                if _local(dep.tag) != "dependency":
                    continue
                fields = {}
                for f in dep:
                    if _local(f.tag) in ("groupId", "artifactId", "version"):
                        fields[_local(f.tag)] = (f.text or "").strip()
                if fields.get("groupId") and fields.get("artifactId") and fields.get("version"):
                    table[(fields["groupId"], fields["artifactId"])] = fields["version"]
    return table


def licenses_for(gradle_home: Path, g: str, n: str, v: str) -> dict:
    """取一个构件的许可声明，自己 POM 没写就顺 parent 链继承（离线）。"""
    seen = set()
    queue = [(g, n, v, 0)]
    while queue:
        cg, cn, cv, depth = queue.pop(0)
        key = (cg, cn, cv)
        if key in seen or depth > PARENT_DEPTH:
            continue
        seen.add(key)
        path = pom_path(gradle_home, cg, cn, cv)
        if path is None:
            where = "缓存缺父 POM（%s:%s:%s）" % (cg, cn, cv) if depth else \
                    "缓存缺 POM（%s:%s:%s）" % (cg, cn, cv)
            return {"licenses": [], "origin": "", "error": where}
        try:
            pom = shared().parse_pom(path.read_bytes())
        except OSError as exc:
            return {"licenses": [], "origin": "", "error": "POM 读不了：%s" % exc}
        if pom["licenses"]:
            origin = "" if depth == 0 else "继承自父 POM %s:%s:%s" % (cg, cn, cv)
            return {"licenses": pom["licenses"], "origin": origin, "error": ""}
        if pom["parent"]:
            queue.append((*pom["parent"], depth + 1))
    return {"licenses": [], "origin": "", "error": "POM 与父链都没有 <licenses> 声明（无声明）"}


# ── 豁免登记表 ────────────────────────────────────────────────────────────────
COORD_RE = re.compile(r"^[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+(?::[A-Za-z0-9_.\-]+)?$")


def _cells(line: str) -> list[str]:
    parts = line.strip().strip("|").split("|")
    return [p.strip() for p in parts]


def parse_exceptions(path: Path) -> tuple[dict, list[str], int]:
    """`licenses/EXCEPTIONS.md` 表格 → ({坐标: 条目}, [不合格行说明], 条目数)。

    表格五列固定：坐标 | 许可 | 谁批 | 为什么 | 复查时间。
    任何一格空着、坐标不成形、复查时间不是 YYYY-MM-DD —— 这一行**不算登记**，
    并且记一条不合格（门禁会红）。理由：豁免表是「谁批的」的书面凭证，
    表本身坏了比没有表更危险（看起来有豁免，实际谁也没批）。
    """
    if not path.is_file():
        raise GateError("找不到豁免登记表 %s（本批应当已建）" % path)
    entries: dict[str, dict] = {}
    problems: list[str] = []
    rows = 0
    for lineno, raw in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = raw.strip()
        if not line.startswith("|"):
            continue
        cells = _cells(line)
        if len(cells) < 5:
            continue  # 不是五列表格（比如说明性的小表），忽略
        if cells[0] in ("坐标", "----", "---") or set(cells[0]) <= set("-: "):
            continue  # 表头与分隔行
        rows += 1
        coord, lic, who, why, review = cells[0], cells[1], cells[2], cells[3], cells[4]
        if not COORD_RE.match(coord):
            problems.append("第 %d 行：坐标「%s」不成形（要 group:name 或 group:name:version）"
                            % (lineno, coord))
            continue
        if not lic or not who or not why or not review:
            problems.append("第 %d 行（%s）：五列有空格 —— 坐标/许可/谁批/为什么/复查时间缺一不可"
                            % (lineno, coord))
            continue
        try:
            due = _dt.date.fromisoformat(review)
        except ValueError:
            problems.append("第 %d 行（%s）：复查时间「%s」不是 YYYY-MM-DD"
                            % (lineno, coord, review))
            continue
        if coord in entries:
            problems.append("第 %d 行（%s）：坐标重复，第 %d 行已登记过 —— 两条不许打架"
                            % (lineno, coord, entries[coord]["line"]))
            continue
        entries[coord] = {
            "coord": coord,
            "licenses": [t.strip() for t in re.split(r"[,，、/\s]+", lic) if t.strip()],
            "who": who,
            "why": why,
            "review": due,
            "line": lineno,
        }
    return entries, problems, rows


def _entry_matches(entry: dict, ids: list[str]) -> bool:
    """登记表写的许可，必须真的对得上实测的许可（家族级比较）。

    防的是「换了依赖版本、许可变了，但表里那行是照旧抄的」——那种表比没有表更糟。"""
    tokens = {canon(t).upper() for t in entry["licenses"]}
    actual = {canon(i).upper() for i in ids}
    for tok in tokens:
        for act in actual:
            if tok == act or act.startswith(tok + "-") or tok.startswith(act + "-"):
                return True
    return False


# ── 资产类第三方：登记表与漏登检查 ──────────────────────────────────────────────
ASSETS_REL = "licenses/ASSETS.md"
ASSET_DIR_REL = "app/src/main/assets"
ASSET_COLUMNS = ("资产名", "版本", "许可", "随包？", "署名要求", "来源")
ASSET_YES = ("是", "yes", "y", "true")
ASSET_NO = ("否", "no", "n", "false")


def asset_cells(line: str) -> list[str]:
    """切六列表格的一行：只去掉边界管道符，保留末尾空字段（空格子必须能被看见）。"""
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    return [c.replace("\\|", "|").strip().strip("`").strip()
            for c in re.split(r"(?<!\\)\|", body)]


def asset_name_error(name: str) -> str:
    """资产名必须是相对 assets/ 的精确 POSIX 文件路径（一文件一行，不认目录/通配）。"""
    if not name:
        return "资产名为空"
    if "\\" in name:
        return "资产名要用正斜杠（%s）" % name
    if name.startswith("/") or PurePosixPath(name).is_absolute():
        return "资产名要相对 assets/，不许绝对路径（%s）" % name
    if ".." in PurePosixPath(name).parts:
        return "资产名不许越出 assets/（%s）" % name
    if any(ch in name for ch in "*?[]"):
        return "资产名不许用通配（%s）—— 登记是逐文件的" % name
    if name.endswith("/"):
        return "资产名要指到文件，不能是目录（%s）" % name
    return ""


def asset_license_id(token: str) -> str:
    """资产表里的许可写法 → SPDX id：先认 SPDX 原样，再查 423 那张唯一的归一表。"""
    t = canon(token)
    if is_allowed_id(t) or family_of(t) in DENY_FAMILIES:
        return t
    key = " ".join(t.lower().replace("(", " ").replace(")", " ").split())
    hit = shared().LICENSE_NAME_TO_SPDX.get(key, "") or EXTRA_LICENSE_NAMES.get(_loose_key(t), "")
    if not hit and "/" in t:
        hit = url_hint(t)
    return hit or t


def parse_assets(path: Path) -> tuple[list[dict], list[str]]:
    """`licenses/ASSETS.md` → ([资产行], [不合格说明])。

    六列缺一不可（空格子不算登记），资产名不成形 / 重复也记红。表头或分隔行缺失是
    解析错误(2) —— 没有表头的表等于没登记，静默按空表放行就是假绿。
    """
    if not path.is_file():
        raise GateError("找不到资产登记表 %s（资产类第三方必须逐条登记）" % path)
    rows: list[dict] = []
    problems: list[str] = []
    seen: dict[str, int] = {}
    header = separator = False
    for lineno, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not raw.strip().startswith("|"):
            continue
        cells = asset_cells(raw)
        if tuple(cells) == ASSET_COLUMNS:
            if header:
                problems.append("第 %d 行：表头重复" % lineno)
            header = True
            continue
        if not header:
            continue  # 表头之前的说明性小表（政策表、列说明表），不是登记行
        if len(cells) == len(ASSET_COLUMNS) and all(set(c) <= set("-: ") for c in cells):
            separator = True
            continue
        if not separator:
            raise GateError("%s 第 %d 行：表头后缺分隔行（| --- | …）" % (path.name, lineno))
        row, bad = _asset_row(dict(zip(ASSET_COLUMNS, cells)), lineno, seen)
        if bad:
            problems.extend(bad)
        else:
            rows.append(row)
    if not header:
        raise GateError("%s 里没有六列表头（%s）—— 没有表头的表等于没登记"
                        % (path.name, " | ".join(ASSET_COLUMNS)))
    return rows, problems


def _asset_row(row: dict, lineno: int, seen: dict) -> tuple[dict, list[str]]:
    """校验一行；返回 (行, 问题列表)。问题非空时这一行不算登记。"""
    name = row["资产名"]
    bad = asset_name_error(name)
    if bad:
        return {}, ["第 %d 行：%s" % (lineno, bad)]
    if name in seen:
        return {}, ["第 %d 行（%s）：与第 %d 行重复 —— 同一资产只许登记一次"
                    % (lineno, name, seen[name])]
    tokens = [t for t in re.split(r"[,，、/;；\s]+", row["许可"]) if t]
    problems = []
    if not tokens:
        problems.append("第 %d 行（%s）：许可为空 —— 缺字段不算登记" % (lineno, name))
    if not row["署名要求"]:
        problems.append("第 %d 行（%s）：署名要求为空（确实没有就填「无」）" % (lineno, name))
    if row["随包？"].lower() not in ASSET_YES + ASSET_NO:
        problems.append("第 %d 行（%s）：随包？只能填「是」或「否」，现在是「%s」"
                        % (lineno, name, row["随包？"]))
    for col in ("版本", "来源"):
        if not row[col]:
            problems.append("第 %d 行（%s）：%s为空（查不到就填「—」并说明）" % (lineno, name, col))
    if problems:
        return {}, problems
    seen[name] = lineno
    ids = [asset_license_id(t) for t in tokens]
    return {
        "name": name,
        "version": row["版本"],
        "raw": row["许可"],
        "ids": ids,
        "shipped": row["随包？"].lower() in ASSET_YES,
        "attribution": row["署名要求"],
        "source": row["来源"],
        "line": lineno,
        "status": classify(ids),
    }, []


def asset_files(assets_dir: Path) -> list[str]:
    """assets/ 下全部文件（相对 POSIX 路径，排序）。目录不存在 ⇒ 空列表，不是错误。"""
    if not assets_dir.is_dir():
        return []
    return sorted(p.relative_to(assets_dir).as_posix()
                  for p in assets_dir.rglob("*") if p.is_file())


def collect_assets(root: Path) -> dict:
    rows, problems = parse_assets(root / ASSETS_REL)
    on_disk = asset_files(root / ASSET_DIR_REL)
    registered = {r["name"] for r in rows}
    return {
        "rows": rows,
        "problems": problems,
        "on_disk": on_disk,
        "missing": [p for p in on_disk if p not in registered],
        "stale": [r["name"] for r in rows if r["name"] not in on_disk],
    }


def render_assets(result: dict) -> tuple[str, int]:
    """→ (文本, 退出码)。纯函数、幂等；红 = 禁止 + 未登记 + 表不合格 + 漏登记。"""
    rows = result["rows"]
    counts = {k: 0 for k in ("allowed", "denied", "unlisted")}
    for row in rows:
        counts[row["status"]] += 1
    out: list[str] = []
    ap = out.append
    ap("[license-policy/assets] 登记 %d 行（assets/ 下 %d 个文件）｜允许 %d｜禁止 %d｜未登记 %d"
       "｜表不合格 %d｜漏登记 %d"
       % (len(rows), len(result["on_disk"]), counts["allowed"], counts["denied"],
          counts["unlisted"], len(result["problems"]), len(result["missing"])))

    for row in rows:
        if row["status"] == "allowed":
            continue
        ap("[license-policy/assets] ✗ %s %s —— %s（第 %d 行，随包：%s）"
           % (STATUS_ZH[row["status"]], row["name"], "/".join(row["ids"]), row["line"],
              "是" if row["shipped"] else "否"))
        ap("                  表里写的是：%s" % row["raw"])
        ap("                  来源：%s" % row["source"])

    if result["problems"]:
        ap("[license-policy/assets] 登记表不合格 %d 条（这些行不算登记）：" % len(result["problems"]))
        for p in result["problems"]:
            ap("                  · %s" % p)
    if result["missing"]:
        ap("[license-policy/assets] 漏登记 %d 个（在 %s/ 里，但 %s 没有它）："
           % (len(result["missing"]), ASSET_DIR_REL, ASSETS_REL))
        for p in result["missing"]:
            ap("                  · %s" % p)
    if result["stale"]:
        ap("[license-policy/assets] 提醒：%d 行登记了但 assets/ 里没有对应文件（不影响判定）：%s"
           % (len(result["stale"]), "、".join(result["stale"])))

    for row in rows:
        if row["status"] == "denied":
            ap("::error title=资产许可门禁::%s 为 %s（禁止类）" % (row["name"], "/".join(row["ids"])))
        elif row["status"] == "unlisted":
            ap("::warning title=资产许可门禁::%s 的许可 %s 不在允许表内（未登记 ⇒ 判红）"
               % (row["name"], "/".join(row["ids"])))
    for p in result["missing"]:
        ap("::warning title=资产漏登::%s/%s 没在 %s 登记" % (ASSET_DIR_REL, p, ASSETS_REL))

    red = counts["denied"] + counts["unlisted"] + len(result["problems"]) + len(result["missing"])
    if red:
        ap("[license-policy/assets] 结论：红 —— 禁止 %d、未登记 %d、表不合格 %d、漏登记 %d；"
           "换资产，或在 %s 逐条登记（六列缺一不可）"
           % (counts["denied"], counts["unlisted"], len(result["problems"]),
              len(result["missing"]), ASSETS_REL))
        return "\n".join(out), 1
    ap("[license-policy/assets] 结论：绿 —— assets/ 下的文件都已登记，登记的许可都在允许表内")
    return "\n".join(out), 0


def run_assets(root: Path = ROOT) -> tuple[str, int]:
    try:
        return render_assets(collect_assets(root))
    except GateError as exc:
        return "[license-policy/assets] 环境错误：%s" % exc, 2


# ── 汇总与输出 ────────────────────────────────────────────────────────────────
def collect(root: Path, gradle_home: Path) -> dict:
    catalog_path = root / "gradle" / "libs.versions.toml"
    if not catalog_path.is_file():
        raise GateError("找不到 gradle/libs.versions.toml")
    catalog = parse_catalog(catalog_path)

    modules = parse_modules(root)
    findings: dict[tuple[str, str, str], dict] = {}
    project_deps = 0
    bom_count = 0
    declared = 0

    for mod in modules:
        build_file = root / mod / "build.gradle.kts"
        if not build_file.is_file():
            raise GateError("模块 :%s 在 settings.gradle.kts 里，但没有 %s/build.gradle.kts"
                            % (mod, mod))
        raw_deps = parse_dependencies(build_file)
        declared += len(raw_deps)
        resolved: list[tuple[str, str, str, str | None, str, int]] = []
        boms: list[tuple[str, str, str]] = []
        for config, expr, lineno in raw_deps:
            got = resolve_expr(expr, catalog)
            if got is None:
                project_deps += 1
                continue
            kind, g, n, v = got
            if kind == "bom":
                # BOM 只是版本托管，一个字节都不进分发物 ⇒ 不判许可，只拿来解析别人的版本
                if v:
                    boms.append((g, n, v))
                    bom_count += 1
                continue
            resolved.append((kind, g, n, v, config, lineno))

        managed = bom_versions(gradle_home, boms) if boms else {}

        for kind, g, n, v, config, lineno in resolved:
            if v is None:
                v = managed.get((g, n), "")
            coord = "%s:%s:%s" % (g, n, v or "?")
            site = "%s/build.gradle.kts:%d（%s）" % (mod, lineno, config)
            if coord not in findings:
                findings[coord] = {"coord": coord, "g": g, "n": n, "v": v or "",
                                   "kind": kind, "sites": [site], "configs": [config]}
            else:
                findings[coord]["sites"].append(site)
                findings[coord]["configs"].append(config)

    if not findings:
        raise GateError("一条直接依赖都没解析出来（%d 条声明）—— 解析器与构建脚本脱节了，"
                        "这种「空集合全绿」比红更危险" % declared)

    rows: list[dict] = []
    for coord in sorted(findings):
        item = findings[coord]
        g, n, v = item["g"], item["n"], item["v"]
        row = {"coord": coord, "sites": item["sites"], "kind": item["kind"],
               "configs": item["configs"],
               "scope": "nondist" if all(c in NOT_DISTRIBUTED_CONFIGS for c in item["configs"])
                        else "dist",
               "ids": [], "raw": [], "origin": "", "note": "", "status": "unevidenced"}
        if not v:
            row["note"] = "版本没解析出来（catalog 没写、BOM 也没托管）"
        else:
            got = licenses_for(gradle_home, g, n, v)
            row["origin"] = got["origin"]
            if got["error"]:
                row["note"] = got["error"]
            else:
                ids, unknown = to_spdx(got["licenses"])
                row["ids"] = ids
                row["raw"] = [e.get("name") or e.get("url") or "" for e in got["licenses"]]
                if ids:
                    row["status"] = classify(ids)
                else:
                    row["status"] = "unevidenced"
                    row["note"] = "许可名归一不了：%s（POM 原文照抄，不猜）" % "；".join(unknown)
                if unknown and ids:
                    row["note"] = "部分许可名归一不了：%s" % "；".join(unknown)
        rows.append(row)

    exemptions, problems, n_rows = parse_exceptions(root / EXCEPTIONS_REL)
    used: set[str] = set()
    for row in rows:
        if row["status"] == "allowed":
            continue
        found = None
        coord_parts = row["coord"].split(":")
        for key in (row["coord"], ":".join(coord_parts[:2])):
            if key in exemptions:
                found = exemptions[key]
                used.add(key)
                break
        if found is None:
            continue
        if found["review"] < _dt.date.today():
            row["note"] = "豁免已过复查时间（%s）—— 重批或换依赖，不许默认续期" % found["review"]
            continue
        if not _entry_matches(found, row["ids"]):
            row["note"] = ("登记表写的许可（%s）与实测（%s）不符 —— 表里那行过期了"
                           % ("/".join(found["licenses"]), "/".join(row["ids"]) or "无"))
            continue
        row["status"] = "exempted"
        row["exempt"] = found

    return {"rows": rows, "projects": project_deps, "declared": declared, "boms": bom_count,
            "modules": modules, "exemptions": exemptions, "problems": problems,
            "unused_exemptions": sorted(set(exemptions) - used), "n_exempt_rows": n_rows}


def render(result: dict, verbose: bool, all_scopes: bool = False) -> tuple[str, int, int]:
    """→ (文本, 退出码, 红条目数)。纯函数：同样的输入永远得到同样的字节（幂等）。"""
    rows = result["rows"]
    judged = [r for r in rows if all_scopes or r["scope"] == "dist"]
    skipped = [] if all_scopes else [r for r in rows if r["scope"] == "nondist"]
    counts = {k: 0 for k in STATUS_ZH}
    for row in judged:
        counts[row["status"]] += 1

    out: list[str] = []
    ap = out.append
    ap("[license-policy] 直接依赖 %d 条（%d 个模块；%d 条模块内 project 依赖、%d 个 BOM 只做版本托管，"
       "都不判许可）｜判 %d 条：允许 %d｜已豁免 %d｜禁止 %d｜未登记 %d｜未取证 %d"
       % (len(rows), len(result["modules"]), result["projects"], result["boms"],
          len(judged), counts["allowed"], counts["exempted"], counts["denied"],
          counts["unlisted"], counts["unevidenced"]))

    # 逐条明细：默认只列需要人看的（非 allowed），--verbose 才全列
    for row in judged:
        if verbose or row["status"] != "allowed":
            lic = "/".join(row["ids"]) or "—"
            raw = "；".join([r for r in row["raw"] if r])
            origin = ("，%s" % row["origin"]) if row["origin"] else ""
            head = "[license-policy] %s %s %s —— %s%s" % (
                "✓" if row["status"] == "allowed" else "✗", STATUS_ZH[row["status"]],
                row["coord"], lic, origin)
            ap(head)
            if raw and row["status"] != "allowed":
                ap("                  POM 原文：%s" % raw)
            for site in row["sites"][:4]:
                ap("                  引入处：%s" % site)
            if len(row["sites"]) > 4:
                ap("                  引入处：…（另 %d 处）" % (len(row["sites"]) - 4))
            if row["note"]:
                ap("                  说明：%s" % row["note"])
            if row["status"] == "exempted":
                ent = row["exempt"]
                ap("                  豁免：%s 批（%s），复查 %s —— %s"
                   % (ent["who"], ent["why"], ent["review"], EXCEPTIONS_REL))

    # 不进分发物的：**逐条列出**（含许可），不判 —— 「不判」不等于「不说」
    if skipped:
        ap("[license-policy] 不进分发物 %d 条（测试/编译期/构建工具，不判许可；"
           "要一起判加 --all-scopes）：" % len(skipped))
        for row in skipped:
            ap("                  · %s —— %s（%s）"
               % (row["coord"], "/".join(row["ids"]) or row["note"] or "—",
                  "、".join(dict.fromkeys(row["configs"]))))

    # 登记表自身的毛病
    if result["problems"]:
        ap("[license-policy] 登记表不合格 %d 条（这些行不算豁免，改好或删掉）："
           % len(result["problems"]))
        for p in result["problems"]:
            ap("                  · %s" % p)
    if result["unused_exemptions"]:
        ap("[license-policy] 登记表里有 %d 条已无对应依赖（可删）：%s"
           % (len(result["unused_exemptions"]), ", ".join(result["unused_exemptions"])))

    # GitHub 注解：禁止用 error，未登记/未取证用 warning（黄），但都判红 —— 「不许静默放过」
    for row in judged:
        if row["status"] == "denied":
            ap("::error title=许可门禁::%s 为 %s（禁止类），且未在 %s 登记"
               % (row["coord"], "/".join(row["ids"]), EXCEPTIONS_REL))
        elif row["status"] == "unlisted":
            ap("::warning title=许可门禁::%s 的许可 %s 不在允许表内（未登记 ⇒ 判红）：%s"
               % (row["coord"], "/".join(row["ids"]) or "?", row["note"] or "先登记再合"))
        elif row["status"] == "unevidenced":
            ap("::warning title=许可门禁::%s 许可未取证（%s）—— 按政策不算通过"
               % (row["coord"], row["note"] or "缓存缺"))

    red = counts["denied"] + counts["unlisted"] + counts["unevidenced"] + len(result["problems"])
    if red:
        ap("[license-policy] 结论：红 —— 禁止 %d、未登记 %d、未取证 %d、登记表不合格 %d 条；"
           "换依赖，或在 %s 逐条登记（坐标/许可/谁批/为什么/复查时间）"
           % (counts["denied"], counts["unlisted"], counts["unevidenced"],
              len(result["problems"]), EXCEPTIONS_REL))
        return "\n".join(out), 1, red
    ap("[license-policy] 结论：绿 —— 进分发物的直接依赖都在允许表内（或已逐条登记豁免）")
    return "\n".join(out), 0, 0


def run(root: Path = ROOT, gradle_home: Path | None = None, verbose: bool = False,
        all_scopes: bool = False):
    """跑一次门禁 → (文本, 退出码)。root / gradle_home 可注入，给 --selftest 用。"""
    gh = Path(gradle_home) if gradle_home else default_gradle_home()
    try:
        if not cache_root(gh).is_dir():
            raise GateError("本地 Gradle 缓存不存在：%s\n"
                            "  本脚本离线取证，POM 只能从这里读。先跑一次解析（例：\n"
                            "  ./gradlew :app:dependencies）让缓存落盘，或用 --gradle-home 指过去。"
                            % cache_root(gh))
        result = collect(root, gh)
    except GateError as exc:
        return "[license-policy] 环境错误：%s" % exc, 2
    text, code, _ = render(result, verbose, all_scopes)
    return text, code


def default_gradle_home() -> Path:
    env = os.environ.get("GRADLE_USER_HOME")
    if env:
        return Path(env)
    return Path.home() / ".gradle"


# ── 自检 ──────────────────────────────────────────────────────────────────────
def _pom(lic_name: str = "", lic_url: str = "", parent: tuple[str, str, str] | None = None) -> str:
    body = ['<?xml version="1.0" encoding="UTF-8"?>',
            '<project xmlns="http://maven.apache.org/POM/4.0.0">',
            "  <modelVersion>4.0.0</modelVersion>"]
    if parent:
        body += ["  <parent>",
                 "    <groupId>%s</groupId>" % parent[0],
                 "    <artifactId>%s</artifactId>" % parent[1],
                 "    <version>%s</version>" % parent[2],
                 "  </parent>"]
    if lic_name or lic_url:
        body += ["  <licenses>", "    <license>"]
        if lic_name:
            body.append("      <name>%s</name>" % lic_name)
        if lic_url:
            body.append("      <url>%s</url>" % lic_url)
        body += ["    </license>", "  </licenses>"]
    body.append("</project>")
    return "\n".join(body)


def _bom_pom(managed: list[tuple[str, str, str]]) -> str:
    body = ['<?xml version="1.0" encoding="UTF-8"?>',
            '<project xmlns="http://maven.apache.org/POM/4.0.0">',
            "  <modelVersion>4.0.0</modelVersion>",
            "  <dependencyManagement>", "    <dependencies>"]
    for g, n, v in managed:
        body += ["      <dependency>", "        <groupId>%s</groupId>" % g,
                 "        <artifactId>%s</artifactId>" % n,
                 "        <version>%s</version>" % v, "      </dependency>"]
    body += ["    </dependencies>", "  </dependencyManagement>", "</project>"]
    return "\n".join(body)


EXC_HEADER = """# 许可豁免登记表

| 坐标 | 许可 | 谁批 | 为什么 | 复查时间 |
| --- | --- | --- | --- | --- |
"""


def _build_tree(tmp: Path, deps: list[str], catalog: dict[str, tuple], poms: dict[tuple, str],
                exceptions: str = "", settings: str = 'include(":app")\n') -> Path:
    """造一棵最小可判的树：settings + catalog + app/build.gradle.kts + 缓存 POM + 登记表。"""
    (tmp / "gradle").mkdir(parents=True)
    (tmp / "app").mkdir(parents=True)
    (tmp / "licenses").mkdir(parents=True)
    (tmp / "settings.gradle.kts").write_text(settings, encoding="utf-8")
    lines = ["[versions]", ""]
    lib_lines = ["[libraries]"]
    for alias, (g, n, v) in catalog.items():
        lib_lines.append('%s = { group = "%s", name = "%s", version = "%s" }' % (alias, g, n, v))
    (tmp / "gradle" / "libs.versions.toml").write_text(
        "\n".join(lines + lib_lines) + "\n", encoding="utf-8")
    (tmp / "app" / "build.gradle.kts").write_text(
        "dependencies {\n" + "".join("    %s\n" % d for d in deps) + "}\n", encoding="utf-8")
    (tmp / "licenses" / "EXCEPTIONS.md").write_text(EXC_HEADER + exceptions, encoding="utf-8")
    cache = tmp / "gh" / CACHE_REL
    cache.mkdir(parents=True, exist_ok=True)  # 缓存根必须存在：不存在是「环境错误(2)」而不是「未取证(1)」
    for (g, n, v), content in poms.items():
        d = tmp / "gh" / CACHE_REL / g / n / v / "0123456789abcdef"
        d.mkdir(parents=True, exist_ok=True)
        (d / ("%s-%s.pom" % (n, v))).write_text(content, encoding="utf-8")
    return tmp


def _case(name: str, fn) -> bool:
    try:
        ok, detail = fn()
    except Exception as exc:  # noqa: BLE001 - 自检要报错不要崩
        ok, detail = False, "%s: %s" % (type(exc).__name__, exc)
    print("  %s %s%s" % ("PASS" if ok else "FAIL", name, "" if ok else " —— " + detail))
    return ok


def selftest() -> int:
    print("[license-policy] 自检（不联网、不碰真仓库、不碰真缓存）")
    failures = 0
    MIT = _pom("The MIT License")
    APACHE = _pom("The Apache Software License, Version 2.0")
    GPL3 = _pom("GNU General Public License, version 3.0")
    LGPL21 = _pom("GNU Lesser General Public License")  # jxl 的原文
    EPL = _pom("Eclipse Public License, Version 1.0")

    def tree(tmp, deps, catalog, poms, exceptions="", settings='include(":app")\n'):
        return _build_tree(Path(tmp), deps, catalog, poms, exceptions, settings)

    def case_allow():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.mit.lib)"],
                        {"mit-lib": ("org.example", "mit", "1.0")},
                        {("org.example", "mit", "1.0"): MIT})
            text, code = run(root, root / "gh")
            return code == 0 and "绿" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_deny():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3})
            text, code = run(root, root / "gh")
            return code == 1 and "GPL-3.0" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_deny_lgpl():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.jxl)"],
                        {"jxl": ("net.sourceforge.jexcelapi", "jxl", "2.6.12")},
                        {("net.sourceforge.jexcelapi", "jxl", "2.6.12"): LGPL21})
            text, code = run(root, root / "gh")
            return code == 1 and "LGPL-2.1" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_missing_pom():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.ghost)"],
                        {"ghost": ("org.example", "ghost", "9.9")}, {})
            text, code = run(root, root / "gh")
            return code == 1 and "未取证" in text and "缓存缺" in text, \
                "code=%d %s" % (code, text.splitlines()[-1])

    def case_no_declaration():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.bare)"],
                        {"bare": ("org.example", "bare", "1.0")},
                        {("org.example", "bare", "1.0"): _pom()})
            text, code = run(root, root / "gh")
            return code == 1 and "无声明" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_unlisted():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.epl)"],
                        {"epl": ("org.example", "epl", "1.0")},
                        {("org.example", "epl", "1.0"): EPL})
            text, code = run(root, root / "gh")
            return code == 1 and "未登记" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_exempt_ok():
        with tempfile.TemporaryDirectory() as tmp:
            exc = "| org.example:gpl | GPL-3.0 | 张三 | 法务结论：仅测试期使用 | 2099-12-31 |\n"
            root = tree(tmp, ["implementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3}, exc)
            text, code = run(root, root / "gh")
            return code == 0 and "已豁免" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_exempt_incomplete():
        with tempfile.TemporaryDirectory() as tmp:
            exc = "| org.example:gpl | GPL-3.0 | 张三 | 仅测试期使用 |  |\n"
            root = tree(tmp, ["implementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3}, exc)
            text, code = run(root, root / "gh")
            return code == 1 and "缺一不可" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_exempt_expired():
        with tempfile.TemporaryDirectory() as tmp:
            exc = "| org.example:gpl | GPL-3.0 | 张三 | 法务结论：仅测试期使用 | 2020-01-01 |\n"
            root = tree(tmp, ["implementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3}, exc)
            text, code = run(root, root / "gh")
            return code == 1 and "复查时间" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_exempt_mismatch():
        with tempfile.TemporaryDirectory() as tmp:
            exc = "| org.example:gpl | MIT | 张三 | 法务结论：仅测试期使用 | 2099-12-31 |\n"
            root = tree(tmp, ["implementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3}, exc)
            text, code = run(root, root / "gh")
            return code == 1 and "不符" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_exempt_duplicate():
        with tempfile.TemporaryDirectory() as tmp:
            exc = ("| org.example:gpl | GPL-3.0 | 张三 | 法务结论：仅测试期使用 | 2099-12-31 |\n"
                   "| org.example:gpl | GPL-3.0 | 李四 | 同上 | 2099-12-31 |\n")
            root = tree(tmp, ["implementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3}, exc)
            text, code = run(root, root / "gh")
            return code == 1 and "坐标重复" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_idempotent():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.mit.lib)", "add(\"cfg\", \"org.example:gpl:1.0\")"],
                        {"mit-lib": ("org.example", "mit", "1.0")},
                        {("org.example", "mit", "1.0"): MIT,
                         ("org.example", "gpl", "1.0"): GPL3})
            a = run(root, root / "gh")
            b = run(root, root / "gh")
            return a == b and a[1] == 1, "两次运行不同" if a != b else "code=%d" % a[1]

    def case_empty_is_error():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(project(\":core\"))"], {}, {})
            text, code = run(root, root / "gh")
            return code == 2 and "脱节" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_missing_cache():
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["implementation(libs.mit.lib)"],
                        {"mit-lib": ("org.example", "mit", "1.0")},
                        {("org.example", "mit", "1.0"): MIT})
            text, code = run(root, root / "no-such-gh")
            return code == 2 and "缓存不存在" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_bom_managed():
        with tempfile.TemporaryDirectory() as tmp:
            deps = ["implementation(platform(libs.bom))", "implementation(libs.managed)"]
            catalog = {"bom": ("org.example", "bom", "2.0"),
                       "managed": ("org.example", "managed", "1.5")}
            poms = {("org.example", "bom", "2.0"): _bom_pom([("org.example", "managed", "1.5")]),
                    ("org.example", "managed", "1.5"): APACHE}
            # catalog 里 managed 写了 version，这里改为「无 version」以走 BOM 路径
            root = tree(tmp, deps, catalog, poms)
            toml = (root / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
            toml = toml.replace(
                'managed = { group = "org.example", name = "managed", version = "1.5" }',
                'managed = { group = "org.example", name = "managed" }')
            (root / "gradle" / "libs.versions.toml").write_text(toml, encoding="utf-8")
            text, code = run(root, root / "gh", verbose=True)
            return code == 0 and "1.5" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_parent_inherit():
        with tempfile.TemporaryDirectory() as tmp:
            deps = ["implementation(libs.child)"]
            catalog = {"child": ("org.example", "child", "1.0")}
            poms = {("org.example", "child", "1.0"):
                    _pom(parent=("org.example", "parent", "1.0")),
                    ("org.example", "parent", "1.0"): APACHE}
            root = tree(tmp, deps, catalog, poms)
            text, code = run(root, root / "gh", verbose=True)
            return code == 0 and "继承自父 POM" in text, \
                "code=%d %s" % (code, text.splitlines()[-1])

    def case_no_network_import():
        src = Path(__file__).read_text(encoding="utf-8", errors="replace")
        banned = [ln for ln in src.splitlines()
                  if re.match(r"^\s*(import|from)\s+(urllib|socket|http\.client|requests)\b", ln)]
        return not banned, "查到联网 import：%s" % banned[:3]

    def case_test_scope_skipped():
        """测试期 GPL 不判，但必须**列出来**（不是静默放过）。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["testImplementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3})
            text, code = run(root, root / "gh")
            return (code == 0 and "不进分发物" in text and "GPL-3.0" in text), \
                "code=%d %s" % (code, text.splitlines()[-1])

    def case_test_scope_all_scopes():
        """同一个依赖，加上 --all-scopes 就该红：证明「不判」是范围选择，不是看不见。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["testImplementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3})
            text, code = run(root, root / "gh", all_scopes=True)
            return code == 1 and "禁止" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_mixed_scope():
        """同一个坐标同时出现在 test 与 implementation ⇒ 只要有一条进分发物就判。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = tree(tmp, ["testImplementation(libs.gpl.lib)",
                              "implementation(libs.gpl.lib)"],
                        {"gpl-lib": ("org.example", "gpl", "1.0")},
                        {("org.example", "gpl", "1.0"): GPL3})
            text, code = run(root, root / "gh")
            return code == 1 and "禁止" in text, "code=%d %s" % (code, text.splitlines()[-1])

    cases = [
        ("允许类：MIT ⇒ 绿", case_allow),
        ("禁止类：GPL-3.0 ⇒ 红", case_deny),
        ("禁止类：LGPL-2.1（jxl 同形，无版本号）⇒ 红", case_deny_lgpl),
        ("未知类：缓存缺 POM ⇒ 未取证/红（不是通过）", case_missing_pom),
        ("未知类：POM 无 <licenses> ⇒ 红", case_no_declaration),
        ("未知类：EPL 不在允许表 ⇒ 未登记/红", case_unlisted),
        ("豁免：五列齐全且在期内 ⇒ 绿", case_exempt_ok),
        ("豁免：缺复查时间 ⇒ 不算登记/红", case_exempt_incomplete),
        ("豁免：复查时间已过 ⇒ 失效/红", case_exempt_expired),
        ("豁免：表里许可与实测不符 ⇒ 不算豁免/红", case_exempt_mismatch),
        ("豁免：同坐标两行 ⇒ 不合格/红", case_exempt_duplicate),
        ("幂等：同一棵树跑两次，文本与退出码全同", case_idempotent),
        ("防假绿：一条 Maven 依赖都解析不出 ⇒ 环境错误(2)", case_empty_is_error),
        ("防假绿：缓存目录不存在 ⇒ 环境错误(2)", case_missing_cache),
        ("BOM 托管：catalog 无 version，按 BOM 的 dependencyManagement 取版本", case_bom_managed),
        ("父 POM 继承：自己没声明，从 parent 链取", case_parent_inherit),
        ("范围：test 期 GPL 不判，但逐条列出", case_test_scope_skipped),
        ("范围：--all-scopes 下同一条 GPL 判红", case_test_scope_all_scopes),
        ("范围：同坐标既 test 又 implementation ⇒ 判", case_mixed_scope),
        ("离线：脚本里没有任何联网 import", case_no_network_import),
    ]
    for name, fn in cases:
        if not _case(name, fn):
            failures += 1
    total = len(cases)
    print("[license-policy] 自检 %d/%d 通过" % (total - failures, total))
    return 0 if failures == 0 else 1


def _asset_tree(tmp: Path, files: list[str], rows: list[list[str]], header: bool = True) -> Path:
    """造最小树：assets/ 下的文件 ＋ licenses/ASSETS.md（可无表头，给解析错误用）。"""
    root = Path(tmp)
    for rel in files:
        p = root / ASSET_DIR_REL / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("stub", encoding="utf-8")
    md = root / ASSETS_REL
    md.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    if header:
        lines.append("| " + " | ".join(ASSET_COLUMNS) + " |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    md.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return root


def assets_selftest() -> int:
    """`--assets --selftest`：三类必测（允许/禁止/漏登记）＋ 缺字段与解析边界。"""
    print("[license-policy/assets] 自检（不联网、临时目录里造最小树）")
    failures = 0
    ok_row = ["models/tiny.onnx", "1.0", "Apache-2.0", "是", "无", "https://example.org/tiny"]

    def case_allow():
        with tempfile.TemporaryDirectory() as tmp:
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [ok_row])
            text, code = run_assets(root)
            return code == 0 and "结论：绿" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_deny():
        with tempfile.TemporaryDirectory() as tmp:
            row = ["fonts/Bad.ttf", "2.0", "GPL-3.0", "是", "见 LICENSE", "https://example.org"]
            root = _asset_tree(Path(tmp), ["fonts/Bad.ttf"], [row])
            text, code = run_assets(root)
            # 断言必须打在**条目行**上：「禁止」在汇总行里也会出现，只查它会假绿（变异 M3 抓到过）
            return code == 1 and "✗ 禁止 fonts/Bad.ttf" in text and "GPL-3.0" in text, \
                "code=%d %s" % (code, text.splitlines()[-1])

    def case_unregistered():
        with tempfile.TemporaryDirectory() as tmp:
            root = _asset_tree(Path(tmp), ["models/ghost.onnx"], [])
            text, code = run_assets(root)
            return code == 1 and "漏登记" in text and "models/ghost.onnx" in text, \
                "code=%d %s" % (code, text.splitlines()[-1])

    def case_empty_table():
        """初始空表头 ＋ assets/ 为空 ⇒ 绿（新批次解冻前的合法状态）。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = _asset_tree(Path(tmp), [], [])
            text, code = run_assets(root)
            return code == 0 and "登记 0 行" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_no_attribution():
        with tempfile.TemporaryDirectory() as tmp:
            row = ["models/tiny.onnx", "1.0", "Apache-2.0", "是", "", "https://example.org"]
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [row])
            text, code = run_assets(root)
            return code == 1 and "署名要求为空" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_no_license():
        with tempfile.TemporaryDirectory() as tmp:
            row = ["models/tiny.onnx", "1.0", "", "是", "无", "https://example.org"]
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [row])
            text, code = run_assets(root)
            return code == 1 and "许可为空" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_unlisted():
        with tempfile.TemporaryDirectory() as tmp:
            row = ["fonts/Open.ttf", "1.1", "OFL-1.1", "是", "随包附 OFL.txt", "https://example.org"]
            root = _asset_tree(Path(tmp), ["fonts/Open.ttf"], [row])
            text, code = run_assets(root)
            return code == 1 and "✗ 未登记 fonts/Open.ttf" in text and "OFL-1.1" in text, \
                "code=%d %s" % (code, text.splitlines()[-1])

    def case_bad_name():
        with tempfile.TemporaryDirectory() as tmp:
            row = ["models/*.onnx", "1.0", "Apache-2.0", "是", "无", "https://example.org"]
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [row])
            text, code = run_assets(root)
            return code == 1 and "通配" in text and "漏登记" in text, \
                "code=%d %s" % (code, text.splitlines()[-1])

    def case_no_header():
        with tempfile.TemporaryDirectory() as tmp:
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [ok_row], header=False)
            text, code = run_assets(root)
            return code == 2 and "六列表头" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_duplicate():
        with tempfile.TemporaryDirectory() as tmp:
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [ok_row, ok_row])
            text, code = run_assets(root)
            return code == 1 and "重复" in text, "code=%d %s" % (code, text.splitlines()[-1])

    def case_idempotent():
        with tempfile.TemporaryDirectory() as tmp:
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [ok_row])
            a = run_assets(root)
            b = run_assets(root)
            return a == b, "两次结果不同"

    def case_missing_table():
        """登记表被删/改名 ⇒ 环境错误(2)，不是「0 条登记所以绿」。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = _asset_tree(Path(tmp), ["models/tiny.onnx"], [ok_row])
            (root / ASSETS_REL).unlink()
            text, code = run_assets(root)
            return code == 2 and "找不到资产登记表" in text, \
                "code=%d %s" % (code, text.splitlines()[-1])

    cases = [
        ("允许资产：Apache-2.0 模型 ⇒ 绿", case_allow),
        ("禁止资产：GPL-3.0 字体 ⇒ 红", case_deny),
        ("漏登记：assets/ 里有文件、表里没有 ⇒ 红", case_unregistered),
        ("空表头 ＋ 空 assets/ ⇒ 绿（初始合法状态）", case_empty_table),
        ("缺字段：署名要求为空 ⇒ 红", case_no_attribution),
        ("缺字段：许可为空 ⇒ 红", case_no_license),
        ("未登记许可：OFL-1.1 不在允许表 ⇒ 红", case_unlisted),
        ("资产名通配 ⇒ 红（且通配不算登记 ⇒ 漏登记）", case_bad_name),
        ("表头缺失 ⇒ 环境错误(2)，不按空表放行", case_no_header),
        ("登记表被删 ⇒ 环境错误(2)，不按空表放行", case_missing_table),
        ("同一资产两行 ⇒ 红", case_duplicate),
        ("幂等：同一棵树跑两次，文本与退出码全同", case_idempotent),
    ]
    for name, fn in cases:
        if not _case(name, fn):
            failures += 1
    total = len(cases)
    print("[license-policy/assets] 自检 %d/%d 通过" % (total - failures, total))
    return 0 if failures == 0 else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="许可政策门禁（离线；政策见脚本 docstring）")
    ap.add_argument("--verbose", action="store_true", help="逐条列出全部直接依赖的判定")
    ap.add_argument("--all-scopes", action="store_true",
                    help="连测试/编译期/构建工具的依赖一起判（默认只判进分发物的）")
    ap.add_argument("--assets", action="store_true",
                    help="资产类模式：读 licenses/ASSETS.md，并核对 assets/ 下的文件是否漏登")
    ap.add_argument("--selftest", action="store_true", help="自检（不联网、不需真缓存）")
    ap.add_argument("--gradle-home", type=Path, default=None, help="Gradle 用户目录（默认取环境变量或 ~/.gradle）")
    args = ap.parse_args()
    if args.selftest:
        return assets_selftest() if args.assets else selftest()
    if args.assets:
        text, code = run_assets(ROOT)
    else:
        text, code = run(ROOT, args.gradle_home, args.verbose, args.all_scopes)
    print(text)
    return code


if __name__ == "__main__":
    sys.exit(main())
