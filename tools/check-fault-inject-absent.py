#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-fault-inject-absent.py —— 684 判据③的**产物侧**断言：release 里没有故障注入。

## 为什么要有这一道
684 的注入开关只在 debug 源集（`app/src/debug/java`），release 编译期就不参与 ——
这条"结构上成立"由源码门禁 `FaultInjectExclusionGateTest` 钉住。但用户拿到的是 **APK**，
所以还要在**产物**上再验一遍：正式包的 dex / 清单里不该出现注入的特征串。

两半都要：
  * 源码干净只证明"没写在 main 里"（R8 之前）；
  * 产物干净才证明"用户手上那个包真的没有"（R8 之后，含资源收缩与混淆）。

## 查什么
把 APK 当 zip 打开，逐条读 `classes*.dex`、`AndroidManifest.xml`、`resources.arsc` 的**原始字节**，
按 UTF-8 与 UTF-16LE 两种编码找下面这些特征串（dex 的字符串池是 MUTF-8，ASCII 部分与 UTF-8 同形；
二进制 XML / arsc 里的字符串是 UTF-16LE）：

    fault-inject    配置文件名的片段（`files/baopu/fault-inject.txt`）
    FaultInject     注入实现类名（FaultInjection / FaultInjector / FaultInjectProbe / FaultInjectInstaller）
    faultinject     provider 的 authority（`${applicationId}.faultinject`）

**故意不查 `ToolFaultSeam`**：那是 main 里的那根针（一个接口＋一个可空引用，零字面量），
它是「注入关闭＝原样执行」的落点，release 里留着它是设计的一部分，不是泄漏。

## 用法
    python3 tools/check-fault-inject-absent.py app/build/outputs/apk/release/app-release.apk
    python3 tools/check-fault-inject-absent.py <apk> --json      # 机器读数
    python3 tools/check-fault-inject-absent.py --selftest        # 合成数据正/负例各一条

退出码：0＝干净；1＝命中（判据③红）；2＝用法/文件问题。
"""
import argparse
import json
import sys
import zipfile

MARKERS = [
    b"fault-inject",
    b"FaultInject",
    b"faultinject",
]

# 只看这几类 entry：代码、清单、资源表。assets/ 里不会有注入（本单没有新增 asset）。
TARGET_PREFIXES = ("classes", "AndroidManifest.xml", "resources.arsc")


def encodings(needle: bytes):
    """同一个特征串的两种落地编码（dex 是 MUTF-8，二进制 XML/arsc 是 UTF-16LE）。"""
    return [needle, needle.decode("ascii").encode("utf-16-le")]


def scan_bytes(data: bytes):
    """返回命中的特征串（bytes 形态，便于打印）。"""
    hits = []
    for marker in MARKERS:
        for enc in encodings(marker):
            if enc in data:
                hits.append(marker.decode("ascii"))
                break
    return hits


def scan_apk(path):
    """逐 entry 扫，返回 {entry: [marker...]}（只列命中的）。"""
    found = {}
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if not name.startswith(TARGET_PREFIXES):
                continue
            hits = scan_bytes(zf.read(name))
            if hits:
                found[name] = hits
    return found


def selftest():
    """合成数据：脏样本必须被咬住，干净样本必须放行（防恒真绿灯）。"""
    dirty_dex = b"\x00\x01" + b"files/baopu/fault-inject.txt" + b"\x00"
    dirty_xml = "com.example.zhengdao.debug.FaultInjectInstaller".encode("utf-16-le")
    clean_dex = b"\x00\x01" + b"ToolFaultSeam" + b"\x00" + b"read_file" + b"\x00"
    cases = [
        ("dex 里的配置文件名", dirty_dex, True),
        ("清单里的安装器类名（UTF-16LE）", dirty_xml, True),
        ("只有那根针的干净 dex", clean_dex, False),
    ]
    bad = 0
    for what, data, expect in cases:
        got = bool(scan_bytes(data))
        mark = "OK" if got == expect else "FAIL"
        if got != expect:
            bad += 1
        print("%s %s：期望命中=%s 实际=%s" % (mark, what, expect, got))
    print("selftest %s" % ("PASS" if bad == 0 else "FAIL（%d 条不符）" % bad))
    return 0 if bad == 0 else 1


def main():
    ap = argparse.ArgumentParser(description="684：release 产物里不该有故障注入的特征串")
    ap.add_argument("apk", nargs="?", help="release APK 路径")
    ap.add_argument("--json", action="store_true", help="输出 JSON 读数")
    ap.add_argument("--selftest", action="store_true", help="合成数据自测")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.apk:
        ap.print_help()
        return 2

    try:
        found = scan_apk(args.apk)
    except (OSError, zipfile.BadZipFile) as e:
        print("✗ 读不了 APK：%s" % e)
        return 2

    if args.json:
        print(json.dumps({"apk": args.apk, "markers": [m.decode() for m in MARKERS], "found": found},
                         ensure_ascii=False, indent=2))
    else:
        print("APK：%s" % args.apk)
        print("特征串：%s" % " / ".join(m.decode() for m in MARKERS))
        if found:
            for entry, hits in sorted(found.items()):
                print("  ✗ %s ⇒ %s" % (entry, ", ".join(hits)))
        else:
            print("  ✓ 一处都没有（判据③产物侧通过）")

    if found:
        print("✗ 判据③红：release 产物里出现了注入特征串")
        return 1
    print("✓ 判据③产物侧通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
