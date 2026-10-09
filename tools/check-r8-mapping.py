#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-r8-mapping.py —— 对 R8 **构建产物**的"承重墙"断言（防 E-022 类闪退复发）。

为什么要它
    R8 看不见 JNI：native 侧按**名字**查 Java/Kotlin 的调用点（下行 `Java_包_类_方法` 符号、
    上行 `find_class` + `call_static_method`），R8 的静态分析完全看不见这些动态引用，
    于是可能把类/方法改名或裁掉 —— 而 **debug 包不混淆、看不出**，只有 release 包真机跑才炸。
    历史事故见 docs/ERRATA.md E-022（一装环境就 SIGABRT 退回主页）。

    已有两道门禁，但都够不到"R8 实际改成了什么"：
      · tools/check-native-so.py —— 验的是 **.so 里的符号**（源码侧事实），不是 R8 产物；
      · R8KeepRuleGuardTest      —— 验的是 proguard-rules.pro 的**文本**，不是 R8 产物。
    R8 到底改了什么名，**唯一的地面真值**是构建产物
        app/build/outputs/mapping/<variant>/mapping.txt
    本脚本就是把它接成一条"会红"的断言（2026-10-09 加，见 docs/ERRATA.md E-072）。

断言（默认 strict，见下方 --allow-missing）
    1. mapping.txt 必须存在。
       ⚠️ 本脚本**必须跑在 assembleRelease 之后**（`app/build` 里才有 mapping）。
          别挂在纯跑单测的 job 上 —— 那样它永远找不到产物，就成了"假门禁"。
    2. 关键类**没有被改名**：类行必须是 `原名 -> 原名:`。
       · com.example.zhengdao.rust.CoreNative （Rust 侧 find_class 反向回调的落点）
       · com.termux.terminal.JNI              （静态注册，libtermux.so 的 Java_ 符号依赖它）
       · com.github.luben.zstd.Zstd           （第三方 JNI，靠 AGP 默认规则保住）
    3. 反向回调成员**没有被改名**：onProgress(String,String) -> onProgress。
       **这一条是核心**：onProgress 不是 native 方法，默认规则
       `keepclasseswithmembernames class * { native <methods>; }` **结构上就管不到它**，
       唯一护身符是 app/proguard-rules.pro 里那条显式 keep。删了它 ⇒ E-022 复发。
    4. seeds.txt（存在时）：CoreNative 的每个 `external fun` 按**原名**被保留。

用法
    python tools/check-r8-mapping.py [仓库根] [--allow-missing]

    --allow-missing：没有 mapping.txt 时不算失败（本机还没构建 release 时自测用）。
    ⚠️ CI 里**不要**带它 —— 带了就把"必有产物"降级成"可静默跳过"，等于自废门禁。

退出码
    0 = 通过（或 --allow-missing 下确实没有产物）；1 = 有 BLOCKER；2 = 用法/环境错误

只依赖标准库。
"""

import re
import sys
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

CORE_KT_REL = "app/src/main/java/com/example/zhengdao/rust/CoreNative.kt"
MAPPING_DIR = "app/build/outputs/mapping"
# 优先 release（分发产物）；本机只构建过 benchmark 时用它兜底（两者 R8 配置同源）。
VARIANTS = ("release", "benchmark")

# 名字必须原样保留的关键类：(全限定名, 为什么要它)
KEPT_CLASSES = (
    (
        "com.example.zhengdao.rust.CoreNative",
        'Rust 侧 find_class("com/example/zhengdao/rust/CoreNative") 反向回调的落点',
    ),
    (
        "com.termux.terminal.JNI",
        "libtermux.so 静态注册符号 Java_com_termux_terminal_JNI_* 依赖它",
    ),
    (
        "com.github.luben.zstd.Zstd",
        "第三方 JNI；靠 AGP 默认 keepclasseswithmembernames 保住，不该被改名",
    ),
)

# 反向回调成员：(mapping.txt 里的原名签名, 期望的混淆后名, 为什么要它)
KEPT_MEMBERS = (
    (
        "onProgress(java.lang.String,java.lang.String)",
        "onProgress",
        'Rust 侧 call_static_method("onProgress") —— 全仓唯一的非 native 反向回调',
    ),
)

blockers = []
infos = []


def blocker(msg):
    blockers.append(msg)


def info(msg):
    infos.append(msg)


def find_mapping(repo):
    """返回 (variant, mapping_path)，找不到则 (None, None)。"""
    for variant in VARIANTS:
        p = repo / MAPPING_DIR / variant / "mapping.txt"
        if p.is_file():
            return variant, p
    return None, None


def native_methods(repo):
    """从 CoreNative.kt 现读所有 external fun 名（加方法自动纳入门禁）。"""
    f = repo / CORE_KT_REL
    if not f.is_file():
        return None
    text = f.read_text(encoding="utf-8", errors="replace")
    return re.findall(r"external\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", text)


def scan_mapping(path):
    """一次遍历 mapping.txt，取出：{类原名: 混淆名} 与 {成员签名: 是否保住原名}。

    mapping.txt 的形态（行首无缩进 = 类声明行；有缩进 = 成员行）：
        com.example.zhengdao.rust.CoreNative -> com.example.zhengdao.rust.CoreNative:
            7:34:void onProgress(java.lang.String,java.lang.String):82:82 -> onProgress
    """
    classes = {}
    member_ok = {sig: False for sig, _new, _why in KEPT_MEMBERS}
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line:
                continue
            if not line[0].isspace():  # 类声明行
                if not line.endswith(":"):
                    continue
                i = line.find(" -> ")
                if i <= 0:
                    continue
                classes.setdefault(line[:i], line[i + 4 : -1])
                continue
            stripped = line.strip()  # 成员行
            for sig, new_name, _why in KEPT_MEMBERS:
                if sig in stripped and stripped.endswith("-> " + new_name):
                    member_ok[sig] = True
    return classes, member_ok


def seeds_has_member(seeds_text, name):
    """seeds.txt 形态：`com.example.zhengdao.rust.CoreNative: java.lang.String nativeExtractSkip(...)`。

    用 `\\b名\\s*\\(` 精确匹配，避免 `nativeExtract` 命中 `nativeExtractSkip`（子串误判）。
    """
    return re.search(r"\b" + re.escape(name) + r"\s*\(", seeds_text) is not None


def main(argv):
    flags = [a for a in argv[1:] if a.startswith("--")]
    args = [a for a in argv[1:] if not a.startswith("--")]
    allow_missing = "--allow-missing" in flags
    unknown = [f for f in flags if f != "--allow-missing"]
    if unknown or len(args) > 1:
        print("用法: python tools/check-r8-mapping.py [仓库根] [--allow-missing]")
        return 2

    repo = Path(args[0]).resolve() if args else Path(__file__).resolve().parents[1]
    if not (repo / "app").is_dir():
        print(f"[错误] 找不到仓库根：{repo}（app/ 不存在）")
        return 2

    variant, mapping = find_mapping(repo)
    if mapping is None:
        msg = (
            f"没有 R8 产物（{MAPPING_DIR}/<variant>/mapping.txt 不存在）—— "
            "本断言必须在 assembleRelease 之后跑"
        )
        if allow_missing:
            print(f"[跳过] {msg}")
            return 0
        print(f"::error title=R8 mapping 门禁失败::{msg}")
        print("\n[失败] 1 项 —— 见上")
        return 1

    info(f"读 {mapping.relative_to(repo).as_posix()}（variant={variant}）")
    classes, member_ok = scan_mapping(mapping)

    for cls, why in KEPT_CLASSES:
        got = classes.get(cls)
        if got is None:
            blocker(f"{cls}: 在 mapping 里找不到（可能被整体裁剪 / 改了包名）—— {why}")
        elif got != cls:
            blocker(f"{cls} 被改名为 `{got}` —— {why}")
        else:
            info(f"{cls} 未被改名 \u2713")

    for sig, new_name, why in KEPT_MEMBERS:
        if member_ok.get(sig):
            info(f"{sig} -> {new_name} \u2713")
        else:
            blocker(
                f"{sig} 未保住原名（应为 `-> {new_name}`）—— {why}"
                "（检查 app/proguard-rules.pro 里 CoreNative 的显式 keep 是否被删）"
            )

    seeds = repo / MAPPING_DIR / variant / "seeds.txt"
    funs = native_methods(repo)
    if funs is None:
        blocker(f"找不到 {CORE_KT_REL}（native 方法清单的真相来源）")
    elif seeds.is_file():
        seeds_text = seeds.read_text(encoding="utf-8", errors="replace")
        missing = [f for f in funs if not seeds_has_member(seeds_text, f)]
        if missing:
            blocker(
                "seeds.txt 里缺少 native 方法：" + ", ".join(missing)
                + "（被 R8 裁掉 -> 运行时 UnsatisfiedLinkError）"
            )
        else:
            info(f"seeds.txt: {len(funs)} 个 native 方法按原名全部保留")
    else:
        info(f"没有 seeds.txt —— 跳过 native 方法保留性检查（本有 {len(funs)} 个）")

    for line in infos:
        print(f"  · {line}")
    if blockers:
        print("")
        for line in blockers:
            print(f"::error title=R8 mapping 门禁失败::{line}")
        print(
            f"\n[失败] {len(blockers)} 项 —— 见上。\n"
            f"  排查：{MAPPING_DIR}/<variant>/mapping.txt 是 R8 改名的唯一地面真值；\n"
            "  修法：改回 app/proguard-rules.pro 的 keep 规则后重跑 assembleRelease。"
        )
        return 1
    print("\n[通过] R8 产物里关键类 / 回调名均未被改名")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
