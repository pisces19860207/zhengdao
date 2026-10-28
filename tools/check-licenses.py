#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""许可证正文门禁：APK 的 `assets/licenses/` 必须**恰好等于**当前随包清单。

为什么要有这一步（docs/ERRATA.md E-105）
--------------------------------------
E-105 实测推翻了「第三方正文会随构件一起分发」这个想当然的承诺：
  * `commons-compress-1.26.2.jar` 里明明有 `META-INF/LICENSE.txt`（11,357 B）与
    `META-INF/NOTICE.txt`（178 B），**APK 里一个都没有** —— AGP 打包默认剥掉构件顶层的
    `META-INF/LICENSE*` / `NOTICE*`；活下来的只有带子目录的（androidx 那几条）与嵌套路径的
    （okhttp 那条）。
  * `zstd-jni` 的 `lib/arm64-v8a/libzstd-jni-*.so`（约 600 KB）曾**确实在 APK 里**，而它的
    POM 声明 BSD-2-Clause、构件内一个许可证文本都没有 ⇒ BSD 的「二进制分发须复现版权声明、
    条件列表与免责声明」从未履行过。
  * `okhttp` 内置的 `publicsuffixes.gz` 是 MPL-2.0 数据，同样随包分发。

结论：**「会随构件发出去」是别人的打包行为，不是我们的承诺。** 凡是「分发物必须含 X」的义务，
只能由我们自己把 X 放进 `assets/licenses/`（v2.0.16 起就是这么做的），并让它**在流水线里会红**。

这一步断言什么（2026-10-28 第二百六十六批：从"只查缺"改成"双向"）
---------------------------------------------------------------
1. **缺一份就红**：`PACKAGED` 里每一份都在包内，且内容与仓库副本逐字节一致 —— 比对前两侧
   都按 `StageLicenseAssetsTask.normalizeToLf()` 的同一口径把 CRLF / 裸 CR 归一成 LF。
2. **多带一份也红**：包内 `assets/licenses/` 的每个条目都必须在 `PACKAGED` 里。
   以前是"多出来的只提示"，那等于允许旧正文悄悄跟着新包走 —— 用户 2026-10-28 的原话是
   「再发新版别把那些许可证也带进去了」，所以这条必须会红。
3. **历史留档必须不在包里**：`ARCHIVED` 那 5 份（proot / libtalloc / libandroid-shmem /
   zstd-jni 包装代码 / 第一方旧版 GPL-3.0）当前 APK 里已经没有对应组件，正文留在仓库给
   旧版本（≤ v2.2.1）留档，**一个字节都不许进新包**。
4. 条件义务：包里出现 `libzstd-jni-*.so` ⇒ 必须有 `BSD-3-Clause-zstd.txt`（老包形态）；
   出现 `publicsuffixes.gz` ⇒ `NOTICES.txt` 必须存在且真的提到 `publicsuffix`；
   出现任何 `libzhengdao_core.so` ⇒ `BSD-3-Clause-zstd.txt` 必须在（Rust 核静态编了 zstd 本体，
   2026-10-28 实测 .so 内含 `zstd-safe-7.3.0/src/lib.rs` 路径串与 `ZSTD_trace_decompress_*` 符号）。

两份清单与 `app/build.gradle.kts` 的 `StageLicenseAssetsTask.PACKAGED / ARCHIVED` 必须一致
（那份是装配时的真源，这里是"产物侧"的复核；两边不一致就是账不平行，本脚本会红）。

用法：`python3 tools/check-licenses.py [APK路径]`（不给路径就取 `app/build/outputs/apk/` 下最新的包）。
"""

import glob
import hashlib
import os
import re
import sys
import zipfile


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_DIRS = ("app/build/outputs/apk/release", "app/build/outputs/apk/debug")
ASSET_PREFIX = "assets/licenses/"

# 随包清单（真源 = app/build.gradle.kts 的 PACKAGED；这里复写一遍是为了本脚本能独立跑，
# 两边不一致时由 _assert_lists_match_build() 判红）。
PACKAGED = [
    "Apache-2.0.txt",
    "BSD-3-Clause-zstd.txt",
    "LGPL-2.1.txt",
    "NOTICES.txt",
    "README.md",
]
# 仅历史留档、不随包（真源 = ARCHIVED）。
ARCHIVED = [
    "BSD-2-Clause.txt",
    "BSD-3-Clause.txt",
    "GPL-2.0.txt",
    "GPL-3.0.txt",
    "LGPL-3.0.txt",
]


def pick_apk(argv):
    if len(argv) > 1:
        return argv[1]
    cands = []
    for d in APK_DIRS:
        cands += glob.glob(os.path.join(ROOT, d, "*.apk"))
    if not cands:
        return None
    return max(cands, key=os.path.getmtime)


def normalize_to_lf(b):
    """与 app/build.gradle.kts 的 StageLicenseAssetsTask.normalizeToLf() 同一口径。"""
    return b.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def digest(b):
    return hashlib.sha256(normalize_to_lf(b)).hexdigest()


def _assert_lists_match_build():
    """本脚本的两份清单 ↔ 装配任务的两份清单，逐项一致，否则装配与复核各说各话。"""
    path = os.path.join(ROOT, "app", "build.gradle.kts")
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()

    problems = []
    for name, expect in (("PACKAGED", PACKAGED), ("ARCHIVED", ARCHIVED)):
        m = re.search(name + r"\s*=\s*listOf\((.*?)\)", text, re.S)
        if not m:
            problems.append("app/build.gradle.kts 里找不到 StageLicenseAssetsTask.%s 清单" % name)
            continue
        found = re.findall(r'"([^"]+)"', m.group(1))
        if sorted(found) != sorted(expect):
            problems.append(
                "%s 两份不一致：build.gradle.kts=%s / check-licenses.py=%s"
                % (name, sorted(found), sorted(expect))
            )
    return problems


def main(argv):
    apk = pick_apk(argv)
    if not apk or not os.path.exists(apk):
        print("::error::找不到 APK（先跑 assembleDebug/assembleRelease，或把路径作为第一个参数传进来）")
        return 1
    print("被验的产物: %s (%d 字节)" % (os.path.relpath(apk, ROOT).replace("\\", "/"), os.path.getsize(apk)))

    problems = _assert_lists_match_build()

    expected = {}
    for name in PACKAGED:
        expected[name] = os.path.join(ROOT, "licenses", name)
    expected["LICENSE-zhengdao.txt"] = os.path.join(ROOT, "LICENSE")

    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        assets = sorted(n for n in names if n.startswith(ASSET_PREFIX))
        lib_so = sorted(n for n in names if n.endswith(".so") and "libzstd-jni" in n)
        core_so = sorted(n for n in names if n.endswith("/libzhengdao_core.so"))
        psg = sorted(n for n in names if n.endswith("publicsuffixes.gz"))
        print(
            "包内 assets/licenses/ 条目: %d；libzstd-jni .so: %d；libzhengdao_core.so: %d；publicsuffixes.gz: %d"
            % (len(assets), len(lib_so), len(core_so), len(psg))
        )

        # 1) 缺一份就红 + 内容逐字节一致
        for name, src in sorted(expected.items()):
            entry = ASSET_PREFIX + name
            if entry not in names:
                problems.append(
                    "缺少 %s（仓库里有 %s，%d 字节）"
                    % (entry, os.path.relpath(src, ROOT).replace("\\", "/"), os.path.getsize(src))
                )
                continue
            in_apk = digest(z.read(entry))
            with open(src, "rb") as f:
                on_disk = digest(f.read())
            if in_apk != on_disk:
                problems.append(
                    "%s 与仓库副本不一致（包内 %s… / 仓库 %s…，已按构建口径把行尾归一成 LF）"
                    % (entry, in_apk[:12], on_disk[:12])
                )

        # 2) 多带一份也红
        for entry in assets:
            name = entry[len(ASSET_PREFIX):]
            if name not in expected:
                if name in ARCHIVED:
                    problems.append(
                        "%s 是「仅历史留档」的正文，当前 APK 已不含对应组件（proot/talloc/shmem/zstd-jni），"
                        "不许随新包（留档在仓库 licenses/ 即可，旧版本 ≤ v2.2.1 的义务已由那版自己的包履行）"
                        % entry
                    )
                else:
                    problems.append("%s 不在随包清单里 —— 多带一份也要红（先判「当前包里有这个组件吗」）" % entry)

        # 3) 条件义务 1：zstd 的原生库在包里 ⇒ BSD 正文必须在
        if lib_so and ASSET_PREFIX + "BSD-3-Clause-zstd.txt" not in names:
            problems.append(
                "包里有 %s，但缺 %sBSD-3-Clause-zstd.txt（BSD 要求二进制分发复现声明）"
                % (lib_so[0], ASSET_PREFIX)
            )
        # 3b) Rust 核静态编了 zstd 本体 ⇒ 同一份 BSD-3 正文的义务同样成立
        if core_so:
            text = z.read(ASSET_PREFIX + "BSD-3-Clause-zstd.txt").decode("utf-8", "replace") if (
                ASSET_PREFIX + "BSD-3-Clause-zstd.txt" in names
            ) else None
            if text is None:
                problems.append(
                    "包里有 %s（Rust 核，静态编进了 zstd 本体），但缺 %sBSD-3-Clause-zstd.txt"
                    % (core_so[0], ASSET_PREFIX)
                )
            elif not re.search(r"zstd|zstandard", text, re.I):
                problems.append("%sBSD-3-Clause-zstd.txt 里没有 zstd / Zstandard 字样，装错了正文" % ASSET_PREFIX)

        # 4) 条件义务 2：okhttp 的 publicsuffixes.gz 是 MPL-2.0 数据 ⇒ NOTICES 必须提到它
        if psg:
            if ASSET_PREFIX + "NOTICES.txt" not in names:
                problems.append("包里有 %s，但缺 %sNOTICES.txt" % (psg[0], ASSET_PREFIX))
            else:
                text = z.read(ASSET_PREFIX + "NOTICES.txt").decode("utf-8", "replace")
                if "publicsuffix" not in text.lower():
                    problems.append(
                        "%sNOTICES.txt 里没有提到 publicsuffix（E-105 要求记下这处 MPL-2.0 数据）" % ASSET_PREFIX
                    )

    if problems:
        for p in problems:
            print("::error title=许可证正文门禁::" + p)
        print("\n失败：%d 处（E-105：义务只能由我们自己随包履行，改完 assets 再跑一次）" % len(problems))
        return 1
    print(
        "通过：随包 %d 份正文与仓库一致（CRLF 归一后逐字节），且包内没有多余条目"
        "（历史留档 %d 份确认不在包内）" % (len(expected), len(ARCHIVED))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
