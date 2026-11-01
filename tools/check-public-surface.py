#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-public-surface.py —— public 面清单闸（352③，2026-11-01）。

目的
----
:policy / :core 是「匣子」模块：它们对 :app 暴露什么，就该**登记在册**。
Kotlin 默认 public ⇒ 293 抽 :policy（ffdf911b）、240 抽 :core（5a63c0f0）时顶层
声明整批放宽；此后谁再往里加一个顶层 public 声明（新 object / 新 fun / 把
internal 改回默认），都会悄悄变成对外 API。本闸把它变成会红的：

    未登记的顶层 public 面 ⇒ ::error ＋ 退出 1；
    登记了但源码里已消失 ⇒ ::notice（改名/删除后顺手清表，不拦）。

注册表：tools/public-surface.json（谁放宽、为什么、能不能收紧都在 verdict/origin 字段；
:policy 20 处、:core 12 处，352 交付报告附表）。

扫描口径（务实边界，写在明处）
----------------------------
* 只看**顶层声明**（depth 0）：class / object / interface / fun / val / var，
  含 sealed/abstract/open/data/annotation/enum/value 修饰组合；
* 无修饰符 ＝ public（Kotlin 默认），`internal`/`private`/`protected` 不算面；
* **成员级不进本闸**（类/对象内部的 public 成员走 code review）——这是刻意的口径，
  不是漏；要收成员级另立批；
* 深度计数处理了行注释、块注释、字符串与三引号字符串（:policy/:core 实测 13 处
  三引号），`--selftest` 带负例（注释里的 "fun f()"、字符串里的 "{" 不得误报）。

用法
----
    python3 tools/check-public-surface.py               # 判闸（CI 接线见 ci.yml）
    python3 tools/check-public-surface.py --selftest    # 负例自测（临时目录，不动仓库）

退出码：0 = 面与注册表一致；1 = 未登记的新面；2 = 环境/解析错误。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import tempfile

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "tools", "public-surface.json")
MODULES = ("policy", "core")

DECL_RE = re.compile(
    r"^(?:(public|internal|private|protected)\s+)?"
    r"((?:sealed|abstract|open|data|annotation|enum|value|fun)\s+)*"
    r"(class|object|interface|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
LINE_STRING_RE = re.compile(r"\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'")
TRIPLE_RE = re.compile(r"\"\"\"")


def strip_line(line: str) -> str:
    """去掉字符串字面量与 // 注释（块注释/三引号由调用方的状态机管）。"""
    line = LINE_STRING_RE.sub("\"\"", line)
    idx = line.find("//")
    return line if idx < 0 else line[:idx]


def scan_module_sources(base: str, module: str) -> list[dict]:
    """扫描 <base>/<module>/src/main/java 下全部 .kt 的顶层 public 声明。"""
    out: list[dict] = []
    src_root = os.path.join(base, module, "src", "main", "java")
    if not os.path.isdir(src_root):
        raise FileNotFoundError("模块源根不存在：%s（防「没扫到当没面」）" % src_root)
    for dirpath, _dirs, files in os.walk(src_root):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, fn), src_root).replace("\\", "/")
            depth = 0
            paren = 0
            in_block = False
            in_triple = False
            with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as fh:
                for ln, raw in enumerate(fh, 1):
                    line = raw
                    if in_block:
                        end = line.find("*/")
                        if end < 0:
                            continue
                        line = line[end + 2:]
                        in_block = False
                    if in_triple:
                        if line.count("\"\"\"") % 2 == 1:
                            in_triple = False
                        continue
                    # 三引号开关：本行开启且未闭合 ⇒ 后半段进三引号态
                    if TRIPLE_RE.search(line):
                        pre = line.split("\"\"\"", 1)[0]
                        if line.count("\"\"\"") % 2 == 1:
                            line = pre
                            in_triple = True
                    if "/*" in line and "*/" not in line.split("/*", 1)[1]:
                        line = line.split("/*", 1)[0]
                        in_block = True
                    code = strip_line(line)
                    braces = code.count("{") - code.count("}")
                    parens = code.count("(") - code.count(")")
                    if depth == 0 and paren == 0:
                        m = DECL_RE.match(code.strip())
                        if m:
                            vis, _mods, kind, name = m.groups()
                            if vis in (None, "public"):
                                out.append(
                                    {
                                        "module": module,
                                        "file": rel,
                                        "line": ln,
                                        "kind": ((_mods or "") + kind).strip(),
                                        "name": name,
                                    }
                                )
                    depth += braces
                    paren += parens
    return out


def judge(scanned: list[dict], registry: dict) -> int:
    """扫描结果 vs 注册表：未登记 ⇒ 红；已消失 ⇒ notice。返回退出码。"""
    reg: dict[tuple[str, str], dict] = {}
    for mod in MODULES:
        for d in registry.get(mod, {}).get("declarations", []):
            reg[(mod, d["name"])] = d

    seen = {(s["module"], s["name"]): s for s in scanned}
    code = 0
    extras = [k for k in seen if k not in reg]
    for mod, name in sorted(extras):
        s = seen[(mod, name)]
        print(
            "::error title=public 面清单闸（352③）::未登记的顶层 public 面：%s 的 `%s`（%s:%d，%s）。"
            "先在 tools/public-surface.json 登记（谁/为什么/能不能收紧），再合。" % (mod, name, s["file"], s["line"], s["kind"])
        )
        code = 1
    for key in sorted(reg):
        if key not in seen:
            print(
                "::notice title=public 面清单闸::注册表条目已不在源码（改名/删除后请清表）：%s 的 `%s`" % key
            )
    print(
        "[public 面清单闸] %s"
        % " ｜ ".join(
            "%s %d/%d" % (mod, sum(1 for k in seen if k[0] == mod), len(registry.get(mod, {}).get("declarations", [])))
            for mod in MODULES
        )
        + "（未登记即红；消失即提示）"
    )
    return code


def selftest() -> int:
    """临时目录里造一个假模块树：正例＋四类负例，不动仓库。"""
    tmp = tempfile.mkdtemp(prefix="pssn-")
    try:
        src = os.path.join(tmp, "policy", "src", "main", "java")
        os.makedirs(src)
        with open(os.path.join(src, "Good.kt"), "w", encoding="utf-8") as fh:
            fh.write(
                "// 注释里的 fun fake() 不算\n"
                "internal class Hidden\n"
                "class Real {\n"
                "    fun member() = 1   // 成员级不算\n"
                "    val s = \"{\" + '}' // 字符串里的括号不许破深度\n"
                "    val t = \"\"\"\n"
                "        { fun fake2() }\n"
                "    \"\"\"\n"
                "}\n"
                "data class Draft(\n"
                "    val kind: String = \"{\",  // 主构造器 val 不算顶层（paren 深度）\n"
                ")\n"
                "object Registered\n"
            )
        scanned = scan_module_sources(tmp, "policy")
        names = sorted(s["name"] for s in scanned)
        ok = names == ["Draft", "Real", "Registered"]
        print("  [%s] 扫描只留顶层 public（got=%s）" % ("✔" if ok else "✘", names))
        registry = {"policy": {"declarations": [{"name": "Real"}, {"name": "Registered"}, {"name": "Draft"}, {"name": "Gone"}]}}
        rc = judge(scanned, registry)
        ok2 = rc == 0  # Gone 只出 notice 不拦
        print("  [%s] 登记齐全＋1 条 stale ⇒ 绿（got=%d）" % ("✔" if ok2 else "✘", rc))
        with open(os.path.join(src, "Good.kt"), "a", encoding="utf-8") as fh:
            fh.write("\nfun sneaky() = 2\n")
        scanned2 = scan_module_sources(tmp, "policy")
        rc2 = judge(scanned2, registry)
        ok3 = rc2 == 1
        print("  [%s] 未登记的顶层 fun ⇒ 红（got=%d）" % ("✔" if ok3 else "✘", rc2))
        all_ok = ok and ok2 and ok3
        print("  结果：%s" % ("全部通过 ✔" if all_ok else "有失败 ✘"))
        return 0 if all_ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(description="public 面清单闸（:policy/:core 顶层声明）")
    ap.add_argument("--registry", default=REGISTRY)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    try:
        with open(args.registry, encoding="utf-8") as fh:
            registry = json.load(fh)
        scanned: list[dict] = []
        for mod in MODULES:
            scanned += scan_module_sources(ROOT, mod)
    except (OSError, json.JSONDecodeError, FileNotFoundError) as exc:
        print("::error title=public 面清单闸::环境错误：%s" % exc)
        return 2
    return judge(scanned, registry)


if __name__ == "__main__":
    raise SystemExit(main())
