#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""r8-deadcode-summary.py —— 把 R8 的 `usage.txt` / `resources.txt` 整理成「类/成员/资源」三张表。

第二百六十六批（2026-10-28）新增。**只读产物，不改任何源码。**

## 这个脚本回答一个问题

`usage.txt` 是 R8 在 release 包里**实际裁掉**的类与成员（`app/proguard-rules.pro` 的
`-printusage` 产物）—— 等于编译器替我们做了一遍可达性分析。但「R8 裁掉了」**不等于**
「源码里可以删」：

  * 编译器会内联 / 消除大量**仍在源码里被使用**的东西（Compose 的 lambda、数据类、单例），
    它们在 usage.txt 里同样算「被移除」；删了源码就编译不过。
  * `XxxKt` 这类**文件门面名**是编译器按文件名生成的，源码里根本不会出现这个名字 ⇒
    「零引用」对它恒真，是假信号。
  * 还有一批是「有实现、暂时没接线」的模块（本项目多个批次的活儿），删掉会把别批的活毁掉。

所以本脚本把 usage.txt 的每条**按能否删**归好类，并给出证据（源码里出现几次、在哪些文件）：

  A. `$` 合成类 —— 编译器生成，没有源码可删；
  B. `XxxKt` 文件门面 —— 名字不在源码里，零引用恒真，不可据此删；
  C. 源码里仍有引用 —— 删了必然编译失败（被 R8 内联 / 剪枝才有此条目）；
  D. 源码里除定义处再无出现 —— **唯一**能考虑删的一类（仍需人工判「是不是待接线」）。

## 用法

    python3 tools/r8-usage-summary.py \
        --usage app/build/outputs/mapping/release/usage.txt \
        --resources app/build/outputs/mapping/release/resources.txt \
        --out build/r8-deadcode.md
"""

from __future__ import annotations

import argparse
import os
import re
import sys

SKIP_DIRS = {"build", ".gradle", ".git", "target", "node_modules"}
OWN_PREFIX = "com.example.zhengdao"


def parse_usage(path):
    """返回 (whole_removed, partial)。

    whole_removed: [(class_name, [member, ...])] —— 整类被移除
    partial:       [(class_name, [member, ...])] —— 只移除了部分成员

    Gradle/R8 的格式：类行顶格（整类删的话行尾**没有**冒号）；成员缩进 4 空格；
    行尾带冒号的类行表示「这个类有成员被删」，本身还活着。
    """
    whole = []
    partial = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith(" ") or line.startswith("\t"):
            i += 1
            continue
        name = line.rstrip(":").strip()
        members = []
        j = i + 1
        while j < len(lines) and (lines[j].startswith("    ") or lines[j].startswith("\t")):
            members.append(lines[j].strip())
            j += 1
        (partial if line.endswith(":") else whole).append((name, members))
        i = j
    return whole, partial


def load_sources(roots):
    files = []
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dp, dn, fn in os.walk(root):
            dn[:] = [d for d in dn if d not in SKIP_DIRS]
            for f in fn:
                if f.endswith((".kt", ".java")):
                    files.append(os.path.join(dp, f))
    return {p: open(p, "r", encoding="utf-8", errors="ignore").read() for p in files}


def classify(name, sources):
    """把「整类被移除」的类归到 A/B/C/D 四档，返回 (bucket, evidence)。"""
    simple = name.rsplit(".", 1)[-1]
    if "$" in simple:
        return "A", "编译器合成（lambda / 伴生对象 / 密封类子类）"
    if simple.endswith("Kt") and name.startswith(OWN_PREFIX):
        return "B", "文件门面名（源码里不存在这个名字，「零引用」恒真）"
    hits = {p: t.count(simple) for p, t in sources.items() if simple in t}
    total = sum(hits.values())
    if total == 0:
        return "D", "全仓源码 0 次出现（连定义都没找到 ⇒ 可能是生成类，也可能是已删残留）"
    if total == 1:
        where = next(iter(hits))
        return "D", "全仓仅 1 次（仅定义处）：%s" % os.path.relpath(where).replace("\\", "/")
    top = sorted(hits.items(), key=lambda kv: -kv[1])[:3]
    return "C", "源码里仍有 %d 处引用（%s%s）" % (
        total,
        "、".join("%s×%d" % (os.path.relpath(p).replace("\\", "/"), n) for p, n in top),
        "…" if len(hits) > 3 else "",
    )


def parse_resources(path):
    """`resources.txt` 是 AGP 写的 **kept** 清单（`<type>:<name>:<id> reachable from <原因>`）。

    ⚠️ 关键结论（2026-10-28 实测）：**AGP 不产出「被裁掉的资源」清单**，
    R8 又不认 ProGuard 的 `-printresources`（写了直接 `Unknown option`、整包构建失败）。
    所以资源侧的"未使用"读数只能从 **lint 的 UnusedResources** 取，不是从这份文件。
    """
    kept = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            m = re.match(r"^([\w-]+):([^:]+):(\d+)\s+reachable from\s+(.+)$", line)
            if m:
                kept.append((m.group(1), m.group(2), m.group(3), m.group(4)))
    return kept


def main(argv=None):
    ap = argparse.ArgumentParser(description="R8 死代码清单 → 类/成员/资源三张表")
    ap.add_argument("--usage", required=True)
    ap.add_argument("--resources")
    ap.add_argument("--source", default="app/src,core/src,native-core/src,macrobenchmark/src")
    ap.add_argument("--out", default="build/r8-deadcode.md")
    args = ap.parse_args(argv)

    for p in (args.usage, args.resources):
        if p and not os.path.exists(p):
            print("::error title=r8-deadcode::找不到 %s（先跑开 R8 的 assembleRelease）" % p)
            return 1

    whole, partial = parse_usage(args.usage)
    sources = load_sources([s.strip() for s in args.source.split(",") if s.strip()])

    own_whole = [(n, m) for n, m in whole if n.startswith(OWN_PREFIX)]
    third_whole = [(n, m) for n, m in whole if not n.startswith(OWN_PREFIX)]

    buckets = {"A": [], "B": [], "C": [], "D": []}
    for name, _m in own_whole:
        b, ev = classify(name, sources)
        buckets[b].append((name, ev))

    out = []
    A = out.append
    A("# R8 死代码清单（自动生成，勿手改）")
    A("")
    A("> 数据源：`%s`（`-printusage`）" % os.path.basename(args.usage))
    if args.resources:
        A("> 　　　　`%s`（AGP 写的 **kept** 清单）" % os.path.basename(args.resources))
    A("> 生成器：`tools/r8-usage-summary.py`")
    A("")
    A("## 0. 读数")
    A("")
    A("| 项 | 数 |")
    A("|---|---|")
    A("| 整类被移除（全部，含第三方） | %d |" % len(whole))
    A("| 　其中本项目 `%s` | %d |" % (OWN_PREFIX, len(own_whole)))
    A("| 　其中第三方 / AndroidX / Kotlin | %d |" % len(third_whole))
    A("| 只移除了部分成员的类 | %d |" % len(partial))
    A("| 被移除的成员条目（方法 / 字段） | %d |" % sum(len(m) for _n, m in whole + partial))
    A("")
    A("## ① 类：能不能删（本项目 %d 个整类移除的归因）" % len(own_whole))
    A("")
    A("| 档 | 含义 | 数 |")
    A("|---|---|---|")
    A("| A | 编译器合成类（`$`）—— 没有源码可删 | %d |" % len(buckets["A"]))
    A("| B | `XxxKt` 文件门面 —— 名字不在源码里，「零引用」恒真（假信号） | %d |" % len(buckets["B"]))
    A("| C | 源码里**仍有引用** —— 删了必然编译失败（被 R8 内联 / 剪枝） | %d |" % len(buckets["C"]))
    A("| D | 源码里除定义处再无出现 —— 唯一能考虑删的一类 | %d |" % len(buckets["D"]))
    A("")
    for key, title in (("D", "D 档明细（唯一候选，仍需人工判「是不是待接线」）"),
                       ("B", "B 档明细（假信号，判据见上）")):
        rows = buckets[key]
        A("### %s —— %d 个" % (title, len(rows)))
        A("")
        if rows:
            A("| 类 | 证据 |")
            A("|---|---|")
            for n, ev in rows[:80]:
                A("| `%s` | %s |" % (n, ev))
            if len(rows) > 80:
                A("| … | 另有 %d 个（完整清单见 JSON 版） |" % (len(rows) - 80))
        else:
            A("（无）")
        A("")
    A("### C 档抽样（证明「源码仍在用」，删了就红）")
    A("")
    if buckets["C"]:
        A("| 类 | 证据 |")
        A("|---|---|")
        for n, ev in buckets["C"][:20]:
            A("| `%s` | %s |" % (n, ev))
    else:
        A("（无）")
    A("")
    A("## ② 成员：被 R8 裁掉的方法 / 字段")
    A("")
    A("按类聚合，取前 20。**这一档不做删除建议**：成员被裁同样可能是内联结果，")
    A("而删成员比删类更容易踩到反射 / 序列化（`proguard-rules.pro` 的 keep 规则优先）。")
    A("")
    ranked = sorted(((n, len(m)) for n, m in own_whole if m), key=lambda kv: -kv[1])[:20]
    if ranked:
        A("| 类 | 被移除成员数 |")
        A("|---|---|")
        for n, c in ranked:
            A("| `%s` | %d |" % (n, c))
    else:
        A("（无）")
    A("")
    A("## ③ 资源")
    A("")
    if args.resources:
        kept = parse_resources(args.resources)
        by_type = {}
        for t, _n, _i, _r in kept:
            by_type[t] = by_type.get(t, 0) + 1
        A("`resources.txt` 实际是 **kept（保留）清单**，共 %d 条：" % len(kept))
        A("")
        A("| 资源类型 | 条数 |")
        A("|---|---|")
        for t, c in sorted(by_type.items(), key=lambda kv: -kv[1]):
            A("| `%s` | %d |" % (t, c))
        A("")
    A("> ⚠️ **资源侧拿不到「被裁掉」清单**（2026-10-28 实测）：AGP 只写 kept 清单，")
    A("> 而 R8 **不认** ProGuard 的 `-printresources`（写上就是 `R8: Unknown option`，整包构建直接失败）。")
    A("> 资源侧的正解是 lint 的 `UnusedResources`（`./gradlew :app:lintDebug` → `lint-results-debug.html`），")
    A("> 本批报告的「资源」表用的是 lint 读数，不是这份文件。")

    text = "\n".join(out) + "\n"
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print(
        "[r8-deadcode] 整类移除 %d（本项目 %d）｜A %d / B %d / C %d / D %d ｜成员条目 %d"
        % (
            len(whole),
            len(own_whole),
            len(buckets["A"]),
            len(buckets["B"]),
            len(buckets["C"]),
            len(buckets["D"]),
            sum(len(m) for _n, m in whole + partial),
        )
    )
    print("[r8-deadcode] 写到 %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
