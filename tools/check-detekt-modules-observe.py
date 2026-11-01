#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-detekt-modules-observe.py —— detekt 观察档闸（352，2026-11-01）。

背景
----
352 把 detekt 的视野从「只扫 app/src/main/java」扩到 :policy 与 :core。当时这两个模块
有 **8 条存量**（policy 5 ＋ core 3，2026-11-01 以 JDK 21.0.13 ＋ detekt 1.23.8 实测，
与 341 记账同源；app 本体 402 与账本分毫不差，交叉验证通过）。预算一个字不动
（那是 354/359 的账）⇒ 当时走「观察档」：

  * 主 lane（:app:detektWall / :app:detektReport / check-detekt-budget.py）**原样不动**，
    仍只扫 app；
  * 本闸消费 :app:detektModulesReport（新任务，只扫 ../policy ＋ ../core）的 XML：
      - 把每条存量打成 ::notice 注解（Actions 注解匿名可读，仓库惯例见 E-028）；
      - **只增即红**：某模块条数 > CEILING（下常量）⇒ ::error ＋ 退出 1。

**359 清债后（2026-11-02）**：8 条已全部拆掉、实测归 0，`CEILING` 由 5/3 置 **0/0**——
本闸从此对 :policy/:core 是**零容忍**（冒一条就是新增）。strict 切换（把 detektCommonArgs
的 --input 切全模块）属 352，切完后本脚本与观察任务一并退役。

防「路径不存在被当成 0 条」（派工单明令）
----------------------------------------
三道防呆，缺一不可：
  1. CI 步骤在跑 Gradle 前先 `test -d` 两个输入目录（shell 侧，最直白）；
  2. detekt 对不存在的输入路径**启动即抛异常**（实测 1.23.8，Runner.<init> 抛），不静默；
  3. 本闸要求 XML 必须存在且可解析——任务「成功」却没产出报告就地红。
另外 detekt 的 checkstyle XML 只列**有发现**的文件（实测 210 个 file 节点全是带错的），
干净模块在 XML 里查无此人是**正常态**（359 清完后的目标态），不作为失败依据。

用法
----
    ./gradlew :app:detektModulesReport          # CI「detekt 观察档」步骤里先跑这个
    python3 tools/check-detekt-modules-observe.py               # 判闸
    python3 tools/check-detekt-modules-observe.py --selftest    # 负例自测（不依赖仓库状态）

退出码：0 = 存量在上限内；1 = 只增即红（超上限）；2 = 环境/解析错误。
"""

from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_XML = os.path.join(ROOT, "app", "build", "reports", "detekt", "detekt-modules.xml")

# 观察档上限 = 0（**359 已清，不再放行存量**；2026-11-02 由清债批 359 从 5/3 置 0）。
#   policy/core 原本的 8 条存量（HarmonyMediator.observe/decay/decode、PatienceProfile.decode、
#   DocxTextExtractor.unescapeXml、KnowledgeBaseSummaries.kindOf/keywords）已在 359 拆到
#   ≤59 行 / ≤14 复杂度，detekt 实测归 0。从此这两个模块**一条都不许再冒**：
#   冒一条就是新增，红——与主 lane（app）「只降不升」同一个口径。
CEILING = {"policy": 0, "core": 0}

# XML 里的路径可能是绝对路径（本机/CI 工作目录不同）——按「模块源根子串」归模块。
MODULE_MARKERS = {"policy": "policy/src/main/java", "core": "core/src/main/java"}


def module_of(path: str) -> str:
    """一条告警属于哪个模块；认不出来就返回 '?'（judge 会把它当环境错误）。"""
    norm = path.replace("\\", "/")
    for mod, marker in MODULE_MARKERS.items():
        if marker in norm:
            return mod
    return "?"


def read_issues(xml_path: str) -> list[dict]:
    """从 detekt checkstyle XML 读告警：[{module, file, line, rule, message}]。"""
    tree = ET.parse(xml_path)
    issues: list[dict] = []
    for node in tree.getroot():
        if node.tag != "file":
            continue
        path = node.get("name", "")
        for err in node:
            if err.tag != "error":
                continue
            issues.append(
                {
                    "module": module_of(path),
                    "file": os.path.basename(path.replace("\\", "/")),
                    "line": err.get("line", "0"),
                    "rule": err.get("source", "").split(".")[-1] or "?",
                    "message": err.get("message", ""),
                }
            )
    return issues


def judge(issues: list[dict], ceiling: dict[str, int]) -> int:
    """打印注解 ＋ 按模块对上限判闸。返回退出码（0 在限内 / 1 超限 / 2 环境错误）。"""
    unknown = [i for i in issues if i["module"] == "?"]
    if unknown:
        for i in unknown:
            print(
                "::error title=detekt 观察档::路径认不出模块（不在 %s 下）：%s:%s"
                % ("/".join(MODULE_MARKERS.values()), i["file"], i["line"])
            )
        return 2

    per_mod: dict[str, int] = {}
    for i in issues:
        per_mod[i["module"]] = per_mod.get(i["module"], 0) + 1
        head = i["message"]
        head = head if len(head) <= 110 else head[:107] + "..."
        print(
            "::notice title=detekt 观察档（352）::%s/%s:%s %s — %s"
            % (i["module"], i["file"], i["line"], i["rule"], head)
        )

    code = 0
    parts = []
    for mod, limit in sorted(ceiling.items()):
        n = per_mod.get(mod, 0)
        parts.append("%s %d/%d" % (mod, n, limit))
        if n > limit:
            print(
                "::error title=detekt 观察档::%s 新增 %d 条（上限 %d）。"
                "这两个模块只降不升（359 已把存量清零）；新增请同批清掉，否则本闸红。"
                % (mod, n - limit, limit)
            )
            code = 1
        elif n < limit:
            print(
                "::notice title=detekt 观察档::%s 读数 %d < 上限 %d —— 清债有进展，"
                "清零后把主 lane --input 切全模块（strict）并退役观察档。" % (mod, n, limit)
            )
    tail = "（上限 0：这两个模块一条都不许冒）" if all(v == 0 for v in ceiling.values()) \
        else "（存量不拦闸，只增即红）"
    print("[detekt 观察档] " + " ｜ ".join(parts) + tail)
    return code


def selftest() -> int:
    """负例 ＋ 正例，不依赖仓库状态（仿 check-radius-tokens-selftest 惯例）。

    359 置 0 后分两组测：①判闸逻辑本身用一张**演示上限**（5/3，历史口径）测边界，
    否则上限是 0，边界永远只测得着"一条就红"这一种；②再对**真实 CEILING** 测
    0/0 的两条：一条都没有 ⇒ 0、冒出一条 ⇒ 1（这是 359 后的生产态）。
    """
    ok = True

    def case(name: str, got: int, want: int) -> None:
        nonlocal ok
        mark = "✔" if got == want else "✘"
        if got != want:
            ok = False
        print("  [%s] %s：got=%d want=%d" % (mark, name, got, want))

    def issue(mod: str, name: str = "Foo.kt") -> dict:
        return {
            "module": mod,
            "file": name,
            "line": "1",
            "rule": "LongMethod",
            "message": "The function observe is too long (96). The maximum length is 60.",
        }

    demo = {"policy": 5, "core": 3}  # 352 的存量口径，只用来测边界
    print("[detekt 观察档 --selftest]")
    case("演示口径限内（5/3）", judge([issue("policy") for _ in range(5)] + [issue("core") for _ in range(3)], demo), 0)
    case("演示口径 policy 超 1 ⇒ 红", judge([issue("policy") for _ in range(6)] + [issue("core") for _ in range(3)], demo), 1)
    case("演示口径 core 超 1 ⇒ 红", judge([issue("policy") for _ in range(5)] + [issue("core") for _ in range(4)], demo), 1)
    case("359 生产口径：一条都没有 ⇒ 0", judge([], CEILING), 0)
    case("359 生产口径：冒出一条 ⇒ 红", judge([issue("core")], CEILING), 1)
    case("认不出的路径 ⇒ 环境错", judge([{"module": "?", "file": "X.kt", "line": "1", "rule": "LongMethod", "message": "m"}], CEILING), 2)
    print("  结果：%s" % ("全部通过 ✔" if ok else "有失败 ✘"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="detekt 观察档闸（:policy/:core，只增即红）")
    ap.add_argument("--xml", default=DEFAULT_XML, help="detekt-modules.xml 路径")
    ap.add_argument("--selftest", action="store_true", help="跑负例自测后退出")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if not os.path.exists(args.xml):
        print(
            "::error title=detekt 观察档::找不到 %s —— 先跑 :app:detektModulesReport；"
            "任务「成功」却没有报告本身就是环境错误（防呆 3/3）" % args.xml
        )
        return 2
    try:
        issues = read_issues(args.xml)
    except ET.ParseError as exc:
        print("::error title=detekt 观察档::XML 解析失败：%s" % exc)
        return 2
    return judge(issues, CEILING)


if __name__ == "__main__":
    raise SystemExit(main())
