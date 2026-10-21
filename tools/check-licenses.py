#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""许可证正文门禁：APK 里的 `assets/licenses/` 必须与仓库里的正文逐字节一致。

为什么要有这一步（docs/ERRATA.md E-105）
--------------------------------------
E-105 实测推翻了「第三方正文会随构件一起分发」这个想当然的承诺：
  * `commons-compress-1.26.2.jar` 里明明有 `META-INF/LICENSE.txt`（11,357 B）与
    `META-INF/NOTICE.txt`（178 B），**APK 里一个都没有** —— AGP 打包默认剥掉构件顶层的
    `META-INF/LICENSE*` / `NOTICE*`；活下来的只有带子目录的（androidx 那几条）与嵌套路径的
    （okhttp 那条）。
  * `zstd-jni` 的 `lib/arm64-v8a/libzstd-jni-*.so`（约 600 KB）**确实在 APK 里**，而它的
    POM 声明 BSD-2-Clause、构件内一个许可证文本都没有 ⇒ BSD 的「二进制分发须复现版权声明、
    条件列表与免责声明」从未履行过。
  * `okhttp` 内置的 `publicsuffixes.gz` 是 MPL-2.0 数据，同样随包分发。

结论：**「会随构件发出去」是别人的打包行为，不是我们的承诺。** 凡是「分发物必须含 X」的义务，
只能由我们自己把 X 放进 `assets/licenses/`（v2.0.16 起就是这么做的），并让它**在流水线里会红**。

这一步断言什么
--------------
1. 仓库 `licenses/` 下的每一份正文都在 APK 的 `assets/licenses/` 里，且内容一致——比对前**两侧都按
   `StageLicenseAssetsTask.normalizeToLf()` 的同一口径把 CRLF / 裸 CR 归一成 LF**（`app/build.gradle.kts`）。
   构建期就是这么归一进去的（免得随包正文的哈希跟着本机 `core.autocrlf` 漂移），所以门禁必须用同一把尺子：
   只对行尾归一，不对内容宽容 —— 归一之后仍然不同就是「忘了进包 / 包里的旧版本没跟着改」。
2. 仓库根 `LICENSE`（第一方声明）在包内名为 `LICENSE-zhengdao.txt`，同口径比对。
3. 条件义务：包内出现 `libzstd-jni-*.so` ⇒ 必须有 `BSD-3-Clause-zstd.txt`；
   出现 `publicsuffixes.gz` ⇒ `NOTICES.txt` 必须存在且真的提到 `publicsuffix`。

用法：`python3 tools/check-licenses.py [APK路径]`（不给路径就取 `app/build/outputs/apk/` 下最新的包）。
"""

import glob
import hashlib
import os
import sys
import zipfile


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_DIRS = ("app/build/outputs/apk/release", "app/build/outputs/apk/debug")
ASSET_PREFIX = "assets/licenses/"


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


def main(argv):
    apk = pick_apk(argv)
    if not apk or not os.path.exists(apk):
        print("::error::找不到 APK（先跑 assembleDebug/assembleRelease，或把路径作为第一个参数传进来）")
        return 1
    print("被验的产物: %s (%d 字节)" % (os.path.relpath(apk, ROOT).replace("\\", "/"), os.path.getsize(apk)))

    # 期望进包的清单：仓库 licenses/ 下全部文件 + 根 LICENSE（包内改名）
    expected = {}
    lic_dir = os.path.join(ROOT, "licenses")
    for name in sorted(os.listdir(lic_dir)):
        p = os.path.join(lic_dir, name)
        if os.path.isfile(p):
            expected[name] = p
    expected["LICENSE-zhengdao.txt"] = os.path.join(ROOT, "LICENSE")

    problems = []
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        assets = sorted(n for n in names if n.startswith(ASSET_PREFIX))
        lib_so = sorted(n for n in names if n.endswith(".so") and "libzstd-jni" in n)
        psg = sorted(n for n in names if n.endswith("publicsuffixes.gz"))
        print("包内 assets/licenses/ 条目: %d；libzstd-jni .so: %d；publicsuffixes.gz: %d"
              % (len(assets), len(lib_so), len(psg)))

        for name, src in sorted(expected.items()):
            entry = ASSET_PREFIX + name
            if entry not in names:
                problems.append("缺少 %s（仓库里有 %s，%d 字节）" % (entry, os.path.relpath(src, ROOT).replace("\\", "/"), os.path.getsize(src)))
                continue
            in_apk = digest(z.read(entry))
            with open(src, "rb") as f:
                on_disk = digest(f.read())
            if in_apk != on_disk:
                problems.append("%s 与仓库副本不一致（包内 %s… / 仓库 %s…，已按构建口径把行尾归一成 LF）"
                                % (entry, in_apk[:12], on_disk[:12]))

        # 条件义务 1：zstd 的原生库在包里 ⇒ BSD 正文必须在
        if lib_so and ASSET_PREFIX + "BSD-3-Clause-zstd.txt" not in names:
            problems.append("包里有 %s，但缺 %sBSD-3-Clause-zstd.txt（BSD 要求二进制分发复现声明）"
                            % (lib_so[0], ASSET_PREFIX))

        # 条件义务 2：okhttp 的 publicsuffixes.gz 是 MPL-2.0 数据 ⇒ NOTICES 必须提到它
        if psg:
            if ASSET_PREFIX + "NOTICES.txt" not in names:
                problems.append("包里有 %s，但缺 %sNOTICES.txt" % (psg[0], ASSET_PREFIX))
            else:
                text = z.read(ASSET_PREFIX + "NOTICES.txt").decode("utf-8", "replace")
                if "publicsuffix" not in text.lower():
                    problems.append("%sNOTICES.txt 里没有提到 publicsuffix（E-105 要求记下这处 MPL-2.0 数据）" % ASSET_PREFIX)

        # 包内多出来的 licenses 条目只提示，不算失败（例如将来多一份 NOTICE）
        extra = [n for n in assets if n[len(ASSET_PREFIX):] not in expected]
        if extra:
            print("提示：包内还有仓库 licenses/ 之外的条目：" + ", ".join(sorted(extra)))

    if problems:
        for p in problems:
            print("::error title=许可证正文门禁::" + p)
        print("\n失败：%d 处（E-105：义务只能由我们自己随包履行，改完 assets 再跑一次）" % len(problems))
        return 1
    print("通过：%d 份正文与仓库一致（按构建口径把 CRLF/CR 归一成 LF 后逐字节比对）" % len(expected))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
