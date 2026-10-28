#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dependency-report.py —— 把 `./gradlew :app:dependencies` 的文本整理成「依赖体检三档表」。

第二百六十六批（2026-10-28）新增。**只整理与取证，不改任何构建文件**。

用法（在仓库根）：

    ./gradlew :app:dependencies --configuration releaseRuntimeClasspath > build/deps.txt
    python3 tools/dependency-report.py --deps build/deps.txt

判据（本脚本**不猜**，三档清单里每条都带证据）：

  ① 未使用的直接依赖（候选删）—— 对每个直接依赖的 group/artifact 在**指定源码根**里 grep，
     命中 0 次才进这一档；grep 范围与关键词原样写进表里，读者可自己复核。
     ⚠️ 这一档只是**候选**。最终判据是「删了能不能编译＋单测全绿」（见派工单 D2）。
  ② 重复声明的版本（候选收口）—— 同一 group:artifact 在树里出现 ≥2 个不同版本。
  ③ 体积/体积风险 —— 已知带 native 库（.so）或已知大件的模块，以及同 group 的多份构件。

所以本脚本**只是把 gradle 的原话变成表格**，不做任何"大概吧"的推断。
"""

from __future__ import annotations

import argparse
import os
import re
import sys

# 解析 `+--- a:b:1.2 [+ 箭头]` 这一行里的坐标。
# Gradle 的写法举例：
#     +--- org.apache.commons:commons-compress:1.26.2
#     |    +--- org.tukaani:xz:1.9
#     +--- androidx.core:core-ktx:1.15.0 (*)
#     \--- com.squareup.okhttp3:okhttp:5.5.1 -> 5.5.2
LINE_RE = re.compile(r"^(?P<prefix>(?:[| ]\s{0,3})*)[+\\]--- (?P<body>.+)$")
CONFIG_RE = re.compile(r"^(?P<cfg>[\w:]+)\s+-\s+")
COORD_RE = re.compile(r"^(?P<group>[^:\s]+):(?P<name>[^:\s]+):(?P<ver>[^:\s()]+)")


def parse_deps(text: str):
    """返回 (configurations, nodes)。

    nodes: list of dict(group, name, ver, resolved, depth, direct, expanded)
    """
    configs = []
    nodes = []
    cfg = None
    for raw in text.splitlines():
        if not raw.strip():
            continue
        m = CONFIG_RE.match(raw)
        if m:
            cfg = m.group("cfg")
            configs.append(cfg)
            continue
        m = LINE_RE.match(raw)
        if not m:
            continue
        body = m.group("body").strip()
        depth = len(m.group("prefix")) // 5
        expanded = body.endswith("(*)")
        if expanded:
            body = body[: -len("(*)")].strip()
        parts = body.split(" -> ")
        ver = parts[0]
        resolved = parts[-1].strip() if len(parts) > 1 else parts[0]
        mc = COORD_RE.match(ver)
        if not mc:
            continue
        rv = resolved.split(":")[-1].strip()
        # Gradle 会给「同版本但声明了 constraint」的行加 ` (c)` 后缀，它不是版本的一部分。
        rv = re.sub(r"\s*\(c\)\s*$", "", rv)
        nodes.append(
            {
                "cfg": cfg,
                "group": mc.group("group"),
                "name": mc.group("name"),
                "ver": mc.group("ver"),
                "resolved": rv,
                "depth": depth,
                "direct": depth == 0,
                "expanded": expanded,
            }
        )
    return configs, nodes


# ── 源码 grep ────────────────────────────────────────────────────────────────
SKIP_DIRS = {"build", ".gradle", ".git", "target", "node_modules"}


def iter_sources(roots):
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for fn in filenames:
                if fn.endswith((".kt", ".java", ".kts")):
                    yield os.path.join(dirpath, fn)


def keywords_for(group: str, name: str):
    """一组「如果代码真的用了它，就一定会出现」的字面量。

    说明（给复核的人）：只给两个 —— group 原样（最常见的包前缀，如 `com.squareup.okhttp3`）
    与 artifact 名（如 `commons-compress`）。

    **刻意不用 group 的最后一段**（`commons`、`okhttp3`）：实测那样会把
    `org.apache.commons:commons-compress` 判成「有人引用」——因为源码注释里出现一个
    "commons" 就命中了。宽松的关键词会让这一档永远为空，等于把结论做成了恒真。
    """
    return [group, name]


def grep_hits(roots, keywords):
    """返回 (总数, {keyword: 次数}, 前几条位置)。"""
    per = {k: 0 for k in keywords}
    samples = []
    files = list(iter_sources(roots))
    for path in files:
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as fh:
                for lineno, line in enumerate(fh, 1):
                    for k in keywords:
                        if k and k in line:
                            per[k] += 1
                            if len(samples) < 5:
                                samples.append("%s:%d" % (os.path.relpath(path).replace("\\", "/"), lineno))
        except OSError:
            continue
    return sum(per.values()), per, samples, len(files)


# ── native / 大件风险 ────────────────────────────────────────────────────────
# 已知「只服务桌面平台」或「带 .so」的 group 前缀。判据写在这里而不是临时猜：
#   · 这些坐标在 Android 上要么只被用来挑平台分支，要么整包 .so 只有一个 ABI。
DESKTOP_ONLY_HINTS = (
    "org.jetbrains.kotlin:kotlin-compiler",
    "org.jetbrains:annotations-java5",
)


def main(argv=None):
    ap = argparse.ArgumentParser(description="依赖体检：把 gradle 依赖树整理成三档清单")
    ap.add_argument("--deps", required=True, help="`gradlew dependencies` 的输出文件")
    ap.add_argument(
        "--source",
        default="app/src/main/java,core/src/main/java,native-core/src/main/java",
        help="零引用候选的 grep 范围（逗号分隔的相对路径）",
    )
    ap.add_argument("--out", help="summary 输出路径（默认与 --deps 同目录的 deps-summary.md）")
    ap.add_argument("--config", help="只看某个 configuration（默认取文件中出现的第一个）")
    args = ap.parse_args(argv)

    with open(args.deps, "r", encoding="utf-8", errors="ignore") as fh:
        text = fh.read()

    configs, nodes = parse_deps(text)
    if args.config:
        nodes = [n for n in nodes if n["cfg"] == args.config]
    if not nodes:
        print("::error title=dependency-report::没解析到任何坐标（%s）" % args.deps)
        return 2

    roots = [p.strip() for p in args.source.split(",") if p.strip()]

    # 去重（同一 group:name 可能在多个 configuration / 多处展开里出现）
    modules = {}
    for n in nodes:
        key = (n["group"], n["name"])
        cur = modules.setdefault(key, {"versions": set(), "resolved": set(), "direct": False, "expanded": False})
        cur["versions"].add(n["ver"])
        cur["resolved"].add(n["resolved"])
        cur["direct"] = cur["direct"] or n["direct"]
        cur["expanded"] = cur["expanded"] or n["expanded"]

    direct = sorted([k for k, v in modules.items() if v["direct"]])
    transitive = sorted([k for k, v in modules.items() if not v["direct"]])

    # ① 零引用候选
    unused = []
    for group, name in direct:
        kws = keywords_for(group, name)
        total, per, samples, nfiles = grep_hits(roots, kws)
        if total == 0:
            unused.append({"coord": "%s:%s" % (group, name), "resolved": ",".join(sorted(modules[(group, name)]["resolved"])), "kw": kws, "files": nfiles})

    # ② 重复版本：区分「Gradle 已收敛（最终解析唯一）」与「真的多版本共存（要收口）」
    dup = []
    dup_unresolved = []
    for (group, name), v in sorted(modules.items()):
        if len(v["versions"]) > 1:
            row = {
                "coord": "%s:%s" % (group, name),
                "versions": ",".join(sorted(v["versions"])),
                "resolved": ",".join(sorted(v["resolved"])),
                "direct": "是" if v["direct"] else "否（传递）",
            }
            if len(v["resolved"]) > 1:
                dup_unresolved.append(row)
            else:
                dup.append(row)

    # ③ 体积风险：带 native 的、已知桌面平台的、同 group 构件多的
    big = []
    for (group, name), v in sorted(modules.items()):
        if any("%s:%s" % (group, name) == h for h in DESKTOP_ONLY_HINTS):
            big.append({"coord": "%s:%s" % (group, name), "why": "桌面平台件（Android 上用不到）"})
    by_group = {}
    for (group, name) in modules:
        by_group.setdefault(group, []).append(name)
    for group, names in sorted(by_group.items()):
        if len(names) >= 4:
            big.append({"coord": group + ":*", "why": "同 group 构件 %d 个（%s）" % (len(names), ", ".join(sorted(names)))})

    def pct(part, whole):
        return "%.1f%%" % (100.0 * part / whole) if whole else "-"

    out = args.out or os.path.join(os.path.dirname(os.path.abspath(args.deps)), "deps-summary.md")
    lines = []
    A = lines.append
    A("# 依赖体检（自动生成，勿手改）")
    A("")
    A("> 数据源：`%s`（`./gradlew :app:dependencies` 原样输出）" % os.path.basename(args.deps))
    A("> 生成器：`tools/dependency-report.py`")
    A("> configurations：%s" % (", ".join("`%s`" % c for c in configs) if configs else "-"))
    A("")
    A("## 0. 读数")
    A("")
    A("| 项 | 数 |")
    A("|---|---|")
    A("| 直接依赖（树的顶层） | %d |" % len(direct))
    A("| 传递依赖 | %d |" % len(transitive))
    A("| 已解析模块（去重 group:artifact） | %d |" % len(modules))
    A("| 其中版本不唯一的（Gradle 已收敛） | %d |" % len(dup))
    A("| 其中**最终解析仍多版本**（要收口） | %d |" % len(dup_unresolved))
    A("| 零引用候选 | %d |" % len(unused))
    A("")
    A("## ① 未使用的直接依赖（候选删）")
    A("")
    A("> grep 范围：%s（共扫到 %d 个 `.kt`/`.java`/`.kts` 文件；`build/`、`.gradle/`、`target/` 不扫）。"
      % ("、".join("`%s`" % r for r in roots), grep_hits(roots, ["class "])[3]))
    A("> 判据：把 group 原样与 artifact 名当两个字面量去这些文件里找，**命中 0 次**才进这一档。")
    A("> ⚠️ 这是**候选**：能删的唯一判据是「删完 assembleDebug ＋ 全量单测仍绿」（派工单 D2）。")
    A("")
    if unused:
        A("| 坐标 | 已解析版本 | grep 关键词 | 命中 |")
        A("|---|---|---|---|")
        for u in unused:
            A("| `%s` | %s | %s | 0 |" % (u["coord"], u["resolved"] or "-", "、".join("`%s`" % k for k in u["kw"])))
    else:
        A("（无：每个直接依赖都能在源码里找到至少一处引用）")
    A("")
    A("## ② 重复声明的版本（候选收口）")
    A("")
    A("> 先说结论口径：**树里出现过多个版本 ≠ 真的要收口**。Gradle 的冲突解决会把同一")
    A("> group:artifact 收敛到一个版本（`a:1.0 -> a:1.2` 的箭头后那个），表里「最终解析」")
    A("> 唯一就说明**已经收敛**，那是 Gradle 的正常工作，不用人管；只有「最终解析」也出现")
    A("> 两个以上版本的才需要人工收口（那才会真的把两份 jar 塞进包）。")
    A("")
    A("### ②-1 需要人工收口：最终解析仍有多个版本（%d 条）" % len(dup_unresolved))
    A("")
    if dup_unresolved:
        A("| 坐标 | 树里出现过的版本 | 最终解析 | 是否直接依赖 |")
        A("|---|---|---|---|")
        for d in dup_unresolved:
            A("| `%s` | %s | %s | %s |" % (d["coord"], d["versions"], d["resolved"], d["direct"]))
    else:
        A("（无 —— 同一 group:artifact 在最终解析里只剩一个版本，没有两份 jar 同时进包）")
    A("")
    A("### ②-2 Gradle 已自动收敛（只登记，不用改；%d 条）" % len(dup))
    A("")
    if dup:
        A("| 坐标 | 树里出现过的版本 | 最终解析 |")
        A("|---|---|---|")
        for d in dup:
            A("| `%s` | %s | %s |" % (d["coord"], d["versions"], d["resolved"]))
    else:
        A("（无）")
    A("")
    A("## ③ 体积/体积风险")
    A("")
    if big:
        A("| 坐标 | 为什么算风险 |")
        A("|---|---|")
        for b in big:
            A("| `%s` | %s |" % (b["coord"], b["why"]))
    else:
        A("（本档没有命中已知风险模式；native 库体积请看报告里的 APK 逐目录对比）")

    body = "\n".join(lines) + "\n"
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(body)

    print("[dependency-report] 直接 %d / 传递 %d / 模块 %d" % (len(direct), len(transitive), len(modules)))
    print("[dependency-report] ① 零引用候选 %d　② 重复版本 %d　③ 风险件 %d" % (len(unused), len(dup), len(big)))
    print("[dependency-report] 写到 %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
