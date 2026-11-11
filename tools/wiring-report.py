#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wiring-report.py —— 接线清单：装配类到底有没有人调（第六百批：阶段零只列不判 → 阶段一三栏固化）。

## 它只列不判

这份脚本**不下结论、不开红门**：默认退出码恒 0（除非扫描器自己崩）。它把
`app/src/main` 里的装配类摆进**三栏**——**有调用点** / **by-design** / **待判**。
by-design（纯常量／纯 DTO／端口接口／人工裁定）是「无调用点，但已经
知道为什么」；待判才是真正待人判的那一堆。一件东西是摆设还是故意的、该删该接，
**由人判，不由脚本判**。**「只被测试用」不是 by-design**（600 阶段二改）：测试跑得
再多，生产也是 0 处 ⇒ 归待判。

⚠ **by-design ≠ 已接线**。看「有没有接」只看第一栏。三栏互斥且铺满总数，
`unwiredTotal = byDesign + pending` 单独留着，就是为了不让 by-design 把账抹平。

## 八个口径（别猜，也别改着改着忘掉）

1. **候选**：`app/src/main/**/*.kt` 里 (a) **类名**含 `Wiring`／`Install`／`Boot` 的
   `object`/`class`/`enum`/`interface`；(b) 名字不含标记、但长在 `*Wiring.kt` 里的
   **伴随类型**（`Record`、`Verdict` 这类 DTO）。三栏只算 (a)，(b) 单列附录——否则
   「有调用点」会被一堆 `Verdict.Allow()` 撑胖，读数就废了。
2. **已接** ＝ 在**申报文件之外**的 `app/src/main` 里出现 `<类名>.某方法(` 或
   `<类名>(`（构造）——**全限定写法也算**（`com.example…TakeoverWiring.sweepStalled(`），
   这条是本仓最常见的一类接线（薄封装 + 全限定调用），漏了它「无调用点」整栏都是假的。
   同文件内自己调自己**不算**：那不是接线点，是内部实现。
3. **声明行不算调用**：`class Installed(val x: Int)` 里的 `Installed(` 是声明不是构造调用；
   `class`/`object`/`interface`/`fun` 之后那个名字一律跳过。
4. **注释与字符串不算**：先把 `//`、`/* */`（Kotlin 可嵌套）、`"..."`、`\"\"\"...\"\"\"`
   全部挖成空格（**保持字符偏移与行号不变**），再在剩下的代码上找调用点。所以
   `EvoSample.kt` 里「记账在 `EvoSampleWiring.promote`」那句注释不算已接——而裸 grep
   会把它算进去。每件的 `commentMentions` 就是给这个差异留的账。
5. **同名类各算各的**：`Installed` 在 `SeedSkills.kt` 与 `TaskGrantRuntime.kt` 各有一份，
   命中按「同文件 → 唯一 → import 精确匹配 → 同包」定位归属，定不下来就记
   `ambiguousHits`（**不硬摊给谁**），并在理由里写明「另有 N 处点名相同但归属不唯一」。
6. **端口接口不进待判**（600 裁定）：`interface` 是契约不是装配件，调用点在实现类上，
   一律归 by-design「端口接口」，理由里带「申报文件之外有没有人把它当类型/实现它」——
   有就说明它活着，没有也不进待办。**别把端口接口塞进 unwired。**
7. **人工裁定是闭集**（`RULINGS`，只减不增）：脚本不替人猜性质。表外的一律落「待判」；
   表内命中要求该类**当前无生产调用点**——一旦有人接上就变成 wired，这条裁定过期，
   `audit_rulings` 会在报告与 stderr 点名，该删行而不是留着装样子。
8. **测试里在用不算接线**（600 阶段二）：只在 `app/src/test`、`app/src/androidTest` 里出现的
   装配类，生产 0 处就是 0 处 ⇒ 落「待判」，理由写「测试里用了 N 次（测试用≠生产接上）」。
   这一档以前被算成 by-design「只被测试用」，**那是错的**：`BootCheck`（测试 37 次）、
   `EmergencyWiring`（测试 50 次）生产都一处没接，它们是真待办，不是 by-design。

## 防洗三条（R5／R6／R7，2026-11-11 用户拍板）

开红之后闸会被"更新基线"绕过，所以补三条把写口焊死：

- **R5 减账必须有对价**——基线里某条**被移出**或**被降级成 by-design**时，必须二者之一成立：
  (a) 新扫描里该件现在是 **wired**（真接上了）；(b) 该件所在文件**本次有生产改动**
  （新建／删改／改名，或 diff 里新增了**对它的调用**行）。
  都不成立 ⇒ 红：`疑似洗账：<类名> 被移出基线但代码没动`。
  对价宽严分寸：移出（最严，要看到调用）＞降级（文件动过即可）＞消失（文件被删/改名）。
  **证据只认 `git diff <--diff-base>` 与扫描结果，不看提交信息**（信息可以写假）。
  取不到 diff ⇒ 退 2（判不了就是判不了，不许当绿）。
- **R6 基线只许 `--generate` 写**——扫描结果与基线**一致时不写盘**（防无意义重写与 diff 噪音）；
  CI 用 `--check` 只读跑；`--generate` 默认**拒绝增加条目**（只减不增），确认是正当新增
  才加 `--allow-add`（会把新增清单逐条打出来）。
- **R7 写要留因**——`--generate` 必须带 `--reason "..."`，否则退 2；脚本把本次变更
  （新增／删除／翻栏）连同这句因由打成一段，供直接粘进提交信息/PR。机检**不读**这段
  （信息可以写假），真正拦洗账的是 R5。

## 用法

    python3 tools/wiring-report.py                     # 打印清单 + 写 JSON(非基线) + 与基线比对
    python3 tools/wiring-report.py --check             # CI 用：只读判红（R1–R5），不写任何文件
    python3 tools/wiring-report.py --generate --reason "为什么改"   # **唯一**写基线的入口
    python3 tools/wiring-report.py --generate --reason "..." --allow-add  # 明确放行新增条目
    python3 tools/wiring-report.py --md out.md          # 清单另存一份
    python3 tools/wiring-report.py --audit 5            # 抽查建议（默认 5 件，确定性抽取）
    python3 tools/wiring-report.py --selftest           # 合成夹具自检（失败退出 1）
    python3 tools/wiring-report.py --strict             # --check 的旧名（保留兼容）

`--update-baseline` 已废止：它不带因由、也不判"减账有没有对价"，正是 R5/R7 要堵的口子。

## 基线（阶段一固化，阶段二开红，本批防洗）

`tools/wiring-baseline.json` 只记「谁在、在哪一栏」：不带 commit、不带时间戳，所以重跑
字节不变。按 `(类名, 文件)` 比对（**不看行号**——行号会因编辑漂移，不是名册变化），把
「新增／消失／翻栏」列在报告 §4.2。默认运行只报差异不判红；`--check` 才判红（CI 用）；
写盘只有 `--generate --reason` 一条路。

⚠ 因为"一致时不写盘"，代码里插几行导致 `declLine` 漂移**不会**更新基线——基线里的行号
只作参考，判据一律用 `(类名, 文件)`。

## 幂等

输出里**没有时间戳**；条目按 `(文件, 声明行, 类名)` 排序，JSON 键序固定；抽查按
`md5(类名)` 定序（不走 random）。同一棵树跑两遍，两份产物字节完全相同。
"""
from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，不让报告自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = Path(__file__).resolve().parent.parent
MAIN_SRC = ROOT / "app/src/main"
TEST_SRCS = (ROOT / "app/src/test", ROOT / "app/src/androidTest")
DEFAULT_JSON = ROOT / "tools/wiring-report.json"

WIRED = "wired"
UNWIRED = "unwired"
BUCKET_WIRED = "有调用点"
BUCKET_BY_DESIGN = "无调用点·by-design"
BUCKET_PENDING = "无调用点·待判"

BY_CONST = "纯常量"
BY_DTO = "纯 DTO"
BY_PORT = "端口接口"
BY_RULED = "人工裁定"

# 人工裁定表（**闭集：只减不增**）。脚本不替人猜性质——表外的一律落「待判」，等人判。
# 命中条件：该类当前**无生产调用点**。一旦有人接上（变成 wired），这条裁定就过期，
# `--selftest` 与默认运行都会点名（见 audit_rulings），该删行而不是留着装样子。
RULINGS: dict[str, tuple[str, str]] = {
    "BootPolicy": (
        BY_RULED,
        "开机定策的纯算式：只吃入参（同工作区同历史⇒同一行，判据⑩），KDoc 明写不读盘；"
        "调用点在 QuietMode 内部（BootStrategy.click）。2026-11-11 用户裁定 by-design。",
    ),
    "BootStrategy": (
        BY_RULED,
        "data class 值对象：方法只读自己的 decisions（档/理由/计数/那一行/点了重算），无 IO、无写口；"
        "调用点在 QuietMode 内部（BootPolicy.of 构造）。2026-11-11 用户裁定 by-design。",
    ),
}

SCOPE_TARGET = "装配类"
SCOPE_COMPANION = "伴随类型"

NAME_MARK = re.compile(r"(Wiring|Install|Boot)")

# 一次扫描把注释与字符串挖空；顺序敏感：`//`、`/*` 先于 `"`，`"""` 先于 `"`。
TOKEN_RE = re.compile(r'//|/\*|"""|"|\'')
DECL_RE = re.compile(r"(?<![A-Za-z0-9_.])(object|class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
FUN_RE = re.compile(
    r"(?<![A-Za-z0-9_.])fun\s+(?:<[^>\n]{1,80}>\s*)?"
    r"(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)*([A-Za-z_][A-Za-z0-9_]*)\s*\("
)
PROP_RE = re.compile(r"(?<![A-Za-z0-9_.])(?:const\s+)?va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)")
WORD_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
CHAR_LIT_RE = re.compile(r"'(\\.|[^'\\\n])'")
PACKAGE_RE = re.compile(r"(?m)^[ \t]*package[ \t]+([A-Za-z0-9_.]+)")
IMPORT_RE = re.compile(r"(?m)^[ \t]*import[ \t]+([A-Za-z0-9_.`]+)")

DECL_KEYWORDS = frozenset({"class", "object", "interface", "fun", "constructor"})


# ── 一、源码预处理：注释与字符串挖空，偏移不变 ───────────────────────────────


def _blank(seg: str) -> str:
    """把一段（注释或字符串）挖成空格，只保留换行——偏移与行号因此不变。"""
    return "".join("\n" if ch == "\n" else " " for ch in seg)


def blank_noncode(src: str) -> str:
    """注释（含嵌套块注释）与字符串字面量全部挖成空格；返回值与 src 等长。"""
    out: list[str] = []
    pos = 0
    n = len(src)
    for m in TOKEN_RE.finditer(src):
        start = m.start()
        if start < pos:
            continue  # 落在已处理区段内（例如 \"\"\" 里的第二个引号）
        tok = m.group(0)
        if tok == "'":
            lit = CHAR_LIT_RE.match(src, start)
            if not lit:
                continue
            out.append(src[pos:start])
            out.append(_blank(lit.group(0)))
            pos = lit.end()
            continue
        out.append(src[pos:start])
        if tok == "//":
            end = src.find("\n", start)
            end = n if end < 0 else end
        elif tok == "/*":
            end = _block_comment_end(src, start)
        elif tok == '"""':
            end = src.find('"""', start + 3)
            end = n if end < 0 else end + 3
        else:  # '"'
            end = _string_end(src, start)
        out.append(_blank(src[start:end]))
        pos = end
    out.append(src[pos:])
    return "".join(out)


def _block_comment_end(src: str, start: int) -> int:
    """Kotlin 块注释可嵌套：返回 `/*` 对应的 `*/` 之后的位置。"""
    depth = 0
    i = start
    n = len(src)
    while i < n:
        if src.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if src.startswith("*/", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
            continue
        i += 1
    return n


def _string_end(src: str, start: int) -> int:
    """普通字符串的收尾位置（含转义；不闭合时截止到行尾止损）。"""
    i = start + 1
    n = len(src)
    while i < n:
        ch = src[i]
        if ch == "\\":
            i += 2
            continue
        if ch == '"':
            return i + 1
        if ch == "\n":
            return i
        i += 1
    return n


def _line_index(text: str) -> list[int]:
    starts = [0]
    for i, ch in enumerate(text):
        if ch == "\n":
            starts.append(i + 1)
    return starts


def _line_of(starts: list[int], offset: int) -> int:
    return bisect.bisect_right(starts, offset)


def _source_line(raw: str, line: int, width: int = 150) -> str:
    lines = raw.splitlines()
    if 1 <= line <= len(lines):
        return lines[line - 1].strip()[:width]
    return ""


def _word_before(code: str, start: int) -> str:
    """`start` 之前紧邻的标识符（用来认声明位：`class Foo(` 里的 class）。"""
    i = start - 1
    while i >= 0 and code[i] in " \t\r\n":
        i -= 1
    j = i
    while j >= 0 and (code[j].isalnum() or code[j] == "_"):
        j -= 1
    return code[j + 1:i + 1]


# ── 二、声明解析 ─────────────────────────────────────────────────────────────


@dataclass
class Decl:
    name: str
    kind: str
    package: str
    file: Path
    line: int
    by_name_mark: bool = False
    in_wiring_file: bool = False
    functions: list[str] = field(default_factory=list)
    properties: list[str] = field(default_factory=list)
    has_body: bool = False

    @property
    def scope(self) -> str:
        return SCOPE_TARGET if self.by_name_mark else SCOPE_COMPANION


def _brace_span(code: str, open_pos: int) -> tuple[int, int]:
    depth = 0
    j = open_pos
    n = len(code)
    while j < n:
        if code[j] == "{":
            depth += 1
        elif code[j] == "}":
            depth -= 1
            if depth == 0:
                return (open_pos, j)
        j += 1
    return (open_pos, n - 1)


def body_span(code: str, start: int) -> tuple[int, int] | None:
    """从声明名之后找类体 `{ ... }`；无类体（`class A(val x: Int) : B`）返回 None。"""
    i = start
    n = len(code)
    depth = 0
    while i < n:
        ch = code[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "{" and depth <= 0:
            return _brace_span(code, i)
        elif ch == "\n" and depth <= 0:
            k = i + 1
            while k < n and code[k] in " \t\r\n":
                k += 1
            # 声明还没完（`: Base` 或换行后的 `{`）就继续吃；否则这份声明没有类体。
            if k < n and code[k] in ":{":
                i = k
                continue
            return None
        i += 1
    return None


def parse_decls(path: Path, code: str, package: str) -> list[Decl]:
    """抽出本文件里所有候选声明（类名规则 ∪ `*Wiring.kt` 伴随类型）。"""
    stem_is_wiring = path.stem.endswith("Wiring")
    found: list[Decl] = []
    for m in DECL_RE.finditer(code):
        kind, name = m.group(1), m.group(2)
        if kind == "class" and _word_before(code, m.start()) == "enum":
            kind = "enum"  # `enum class X`：常量表，别混进「类」
        by_name = bool(NAME_MARK.search(name))
        if not (by_name or stem_is_wiring):
            continue
        span = body_span(code, m.end())
        decl = Decl(
            name=name,
            kind=kind,
            package=package,
            file=path,
            line=code.count("\n", 0, m.start()) + 1,
            by_name_mark=by_name,
            in_wiring_file=stem_is_wiring,
            has_body=span is not None,
        )
        if span is not None:
            body = code[span[0]:span[1] + 1]
            decl.functions = sorted(set(FUN_RE.findall(body)))
            decl.properties = sorted(set(PROP_RE.findall(body)))
        found.append(decl)
    return found


# ── 三、引用扫描 ─────────────────────────────────────────────────────────────


@dataclass
class Hit:
    owner: str     # 命中的候选类名
    kind: str      # call | ctor | prop | bare
    file: Path
    line: int
    member: str    # call/prop 的成员名；其余为空
    text: str      # 该行原文（截断 150 字），给人核对用


@dataclass
class FileCtx:
    path: Path
    raw: str
    code: str
    package: str
    imports: set[str]


def file_meta(code: str) -> tuple[str, set[str]]:
    pkg = PACKAGE_RE.search(code)
    imports = set()
    for m in IMPORT_RE.finditer(code):
        imports.add(m.group(1).replace("`", ""))
    return (pkg.group(1) if pkg else ""), imports


def scan_file(ctx: FileCtx, names: set[str]) -> tuple[list[Hit], dict[str, int]]:
    """一趟词法走完：`Name.m(`＝调用、`Name(`＝构造、`Name.x`＝属性、其余＝类型/导入。

    返回（命中表、本文件里各候选名出现次数——后者用于算「注释/字符串提及」）。
    """
    code, raw = ctx.code, ctx.raw
    hits: list[Hit] = []
    seen: dict[str, int] = {}
    starts = _line_index(code)
    n = len(code)
    for m in WORD_RE.finditer(code):
        name = m.group(0)
        if name not in names:
            continue
        seen[name] = seen.get(name, 0) + 1
        if _word_before(code, m.start()) in DECL_KEYWORDS:
            continue  # `class Installed(` 是声明，不是构造调用
        j = m.end()
        while j < n and code[j] in " \t":
            j += 1
        line = _line_of(starts, m.start())
        if j < n and code[j] == ".":
            member, k = _member_after_dot(code, j + 1)
            if member is None:
                continue
            hits.append(Hit(owner=name, kind="call" if k < n and code[k] == "(" else "prop",
                            file=ctx.path, line=line, member=member,
                            text=_source_line(raw, line)))
            continue
        if j < n and code[j] == "(":
            hits.append(Hit(owner=name, kind="ctor", file=ctx.path, line=line, member="",
                            text=_source_line(raw, line)))
            continue
        hits.append(Hit(owner=name, kind="bare", file=ctx.path, line=line, member="",
                        text=_source_line(raw, line)))
    return hits, seen


def _member_after_dot(code: str, i: int) -> tuple[str | None, int]:
    """`Name.<member>`／`Name.Companion.<member>`：返回成员名与紧跟其后的位置。"""
    n = len(code)
    k = i
    while k < n and code[k] in " \t":
        k += 1
    word = WORD_RE.match(code, k)
    if not word:
        return (None, k)
    member = word.group(0)
    k = word.end()
    if member == "Companion":
        probe = k
        while probe < n and code[probe] in " \t\r\n":
            probe += 1
        if probe < n and code[probe] == ".":
            return _member_after_dot(code, probe + 1)
    while k < n and code[k] in " \t\r\n":
        k += 1
    return (member, k)


def resolve(hit: Hit, ctx: FileCtx, by_name: dict[str, list[Decl]]) -> Decl | None:
    """这次命中属于哪一份声明：同文件 → 唯一 → import 精确 → 同包；定不下来返回 None。"""
    cands = by_name.get(hit.owner)
    if not cands:
        return None
    same_file = [d for d in cands if d.file == ctx.path]
    if len(same_file) == 1:
        return same_file[0]
    if len(cands) == 1:
        return cands[0]
    exact = [d for d in cands if ("%s.%s" % (d.package, d.name)) in ctx.imports]
    if len(exact) == 1:
        return exact[0]
    same_pkg = [d for d in cands if d.package == ctx.package]
    if len(same_pkg) == 1:
        return same_pkg[0]
    return None


def collect(src: Path):
    """扫一遍源码树：声明表、每份声明的命中、提及次数、测试引用数、归属不唯一数。"""
    files = sorted(p for p in src.rglob("*.kt") if p.is_file())
    ctxs: dict[Path, FileCtx] = {}
    decls: list[Decl] = []
    for path in files:
        raw = path.read_text(encoding="utf-8", errors="replace")
        code = blank_noncode(raw)
        package, imports = file_meta(code)
        ctxs[path] = FileCtx(path=path, raw=raw, code=code, package=package, imports=imports)
        decls.extend(parse_decls(path, code, package))

    by_name: dict[str, list[Decl]] = {}
    for d in decls:
        by_name.setdefault(d.name, []).append(d)
    names = set(by_name)

    hits_by_decl: dict[int, list[Hit]] = {id(d): [] for d in decls}
    ambiguous: dict[str, int] = {}
    raw_counts: dict[str, int] = {}
    code_counts: dict[str, int] = {}
    for path in files:
        ctx = ctxs[path]
        hits, seen = scan_file(ctx, names)
        for name, cnt in seen.items():
            code_counts[name] = code_counts.get(name, 0) + cnt
        for m in WORD_RE.finditer(ctx.raw):
            token = m.group(0)
            if token in names:
                raw_counts[token] = raw_counts.get(token, 0) + 1
        for hit in hits:
            owner = resolve(hit, ctx, by_name)
            if owner is None:
                ambiguous[hit.owner] = ambiguous.get(hit.owner, 0) + 1
            else:
                hits_by_decl[id(owner)].append(hit)

    mentions = {n: max(0, raw_counts.get(n, 0) - code_counts.get(n, 0)) for n in names}
    return decls, hits_by_decl, mentions, _test_counts(names), ambiguous


def _test_counts(names: set[str]) -> dict[str, int]:
    """测试源里每个候选名出现多少次（挖掉注释/字符串之后按词计）。"""
    counts: dict[str, int] = {name: 0 for name in names}
    for src in TEST_SRCS:
        if not src.exists():
            continue
        for path in sorted(p for p in src.rglob("*.kt") if p.is_file()):
            code = blank_noncode(path.read_text(encoding="utf-8", errors="replace"))
            for m in WORD_RE.finditer(code):
                token = m.group(0)
                if token in counts:
                    counts[token] += 1
    return counts


# ── 四、分栏（只分栏，不下对错） ─────────────────────────────────────────────


@dataclass
class Entry:
    decl: Decl
    status: str
    bucket: str
    reason: str
    by_design: str = ""
    calls: list[Hit] = field(default_factory=list)
    self_calls: list[Hit] = field(default_factory=list)
    props: list[Hit] = field(default_factory=list)
    type_refs: list[Hit] = field(default_factory=list)
    import_refs: list[Hit] = field(default_factory=list)
    comment_mentions: int = 0
    test_refs: int = 0
    ambiguous_hits: int = 0

    @property
    def name(self) -> str:
        return self.decl.name

    @property
    def scope(self) -> str:
        return self.decl.scope


def classify(decl: Decl, hits: list[Hit], comment_mentions: int, test_refs: int,
             ambiguous_hits: int) -> Entry:
    calls = [h for h in hits if h.file != decl.file and h.kind in ("call", "ctor")]
    self_calls = [h for h in hits if h.file == decl.file and h.kind in ("call", "ctor")]
    props = [h for h in hits if h.file != decl.file and h.kind == "prop"]
    bares = [h for h in hits if h.file != decl.file and h.kind == "bare"]
    import_refs = [h for h in bares if h.text.startswith("import ")]
    type_refs = [h for h in bares if not h.text.startswith("import ")]
    entry = Entry(decl=decl, status=UNWIRED, bucket=BUCKET_PENDING, reason="",
                  calls=calls, self_calls=self_calls, props=props,
                  type_refs=type_refs, import_refs=import_refs,
                  comment_mentions=comment_mentions, test_refs=test_refs,
                  ambiguous_hits=ambiguous_hits)
    if calls:
        entry.status = WIRED
        entry.bucket = BUCKET_WIRED
        entry.reason = "生产调用点 %d 处（首处 %s）" % (len(calls), _site(calls[0]))
        return entry

    # 证据计数分两组：`refs` 会被端口接口/裁定的正文自己念一遍，别重复拼。
    refs = []
    if props:
        refs.append("生产里属性引用 %d 处" % len(props))
    if type_refs:
        refs.append("生产里类型引用 %d 处" % len(type_refs))
    if import_refs:
        refs.append("只被 import %d 处" % len(import_refs))
    other = []
    if self_calls:
        other.append("同文件内调用 %d 处" % len(self_calls))
    if test_refs:
        other.append("测试里用了 %d 次（测试用≠生产接上）" % test_refs)
    if comment_mentions:
        other.append("注释/字符串提及 %d 次" % comment_mentions)
    if ambiguous_hits:
        other.append("另有 %d 处同名但归属不唯一" % ambiguous_hits)
    tail = ("；" + "，".join(refs + other)) if (refs or other) else ""
    tail_no_refs = ("；" + "，".join(other)) if other else ""

    # ① 人工裁定优先：表里点名的，按裁定归类（理由原样带出来，不重算）
    if decl.name in RULINGS:
        label, why = RULINGS[decl.name]
        entry.bucket = BUCKET_BY_DESIGN
        entry.by_design = label
        entry.reason = "生产 0 处；%s%s" % (why.rstrip("。"), tail_no_refs)
        return entry
    # ② 端口接口：接口是契约不是装配件，调用点在实现类上 —— **不进待判**（600 裁定）
    if decl.kind == "interface":
        entry.bucket = BUCKET_BY_DESIGN
        entry.by_design = BY_PORT
        entry.reason = ("生产 0 处；接口是契约不是装配件，调用点在实现类上"
                        "（申报文件之外：类型引用 %d 处、只被 import %d 处）%s"
                        % (len(type_refs), len(import_refs), tail_no_refs))
        return entry
    # ③ 形状优先：没有方法的一律是「装东西的」，比「只被测试用」更能说明它为什么没人调。
    if not decl.functions and decl.kind in ("object", "class", "enum"):
        entry.bucket = BUCKET_BY_DESIGN
        entry.by_design = BY_CONST if decl.kind in ("object", "enum") else BY_DTO
        if decl.kind == "enum":
            shape = "枚举常量表、0 个方法"
        elif decl.has_body:
            shape = "类体 %d 个常量/属性、0 个方法" % len(decl.properties)
        else:
            shape = "无类体，只有构造参数"
        entry.reason = "生产 0 处；%s ⇒ %s%s" % (shape, entry.by_design, tail)
        return entry
    # ④ 测试里在用**不算**接线（600 阶段二口径）：这类别进待判，不许被洗成 by-design。
    #    `BootCheck`／`EmergencyWiring`／`BootFinding` 就是这一档——测试跑得再多，生产也没接上。
    entry.reason = ("生产 0 处；类体 %d 个方法（%s）但无人调%s"
                    % (len(decl.functions), "、".join(decl.functions[:4]), tail))
    return entry


def _site(hit: Hit) -> str:
    member = ("." + hit.member + "()") if hit.kind == "call" else " 构造"
    return "%s:%d%s" % (rel(hit.file), hit.line, member)


def rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except Exception:  # noqa: BLE001 - 自检夹具在临时目录，不在仓内
        return Path(path).as_posix()


def build(src: Path) -> list[Entry]:
    decls, hits_by_decl, mentions, test_counts, ambiguous = collect(src)
    entries = [
        classify(d, hits_by_decl.get(id(d), []), mentions.get(d.name, 0),
                 test_counts.get(d.name, 0), ambiguous.get(d.name, 0))
        for d in decls
    ]
    entries.sort(key=lambda e: (rel(e.decl.file), e.decl.line, e.name))
    return entries


# ── 五、输出 ─────────────────────────────────────────────────────────────────


def targets(entries: list[Entry]) -> list[Entry]:
    return [e for e in entries if e.scope == SCOPE_TARGET]


def counts(entries: list[Entry]) -> dict[str, int]:
    """三栏口径（600 阶段一改）：wired / byDesign / pending 互斥且铺满 total。

    `unwiredTotal` = byDesign + pending，是**事实**（无生产调用点的总数），
    留着是为了不让 by-design 把账抹平——by-design ≠ 已接线（见 docstring 「别自欺」）。
    """
    main = targets(entries)
    wired = sum(1 for e in main if e.bucket == BUCKET_WIRED)
    by_design = sum(1 for e in main if e.bucket == BUCKET_BY_DESIGN)
    pending = sum(1 for e in main if e.bucket == BUCKET_PENDING)
    return {
        "total": len(main),
        "wired": wired,
        "byDesign": by_design,
        "pending": pending,
        "unwiredTotal": by_design + pending,
        "companion": len(entries) - len(main),
        "portInterfaces": sum(1 for e in main if e.by_design == BY_PORT),
        "ruled": sum(1 for e in main if e.by_design == BY_RULED),
    }


def to_json(entries: list[Entry], audit: list[Entry], src: Path,
            base_diff: dict | None = None, base_path: Path | None = None,
            rule_problems: list[str] | None = None) -> dict:
    return {
        "schema": "wiring-report/2",
        "src": rel(src),
        "note": "只列不判：wired/byDesign/pending 三栏互斥。by-design ≠ 已接线——"
                "要判「有没有接」只看 bucket=有调用点。scope=装配类 进三栏；"
                "scope=伴随类型（长在 *Wiring.kt 里但名字不含标记）单列。",
        "counts": counts(entries),
        "rulings": [{"name": n, "label": RULINGS[n][0], "reason": RULINGS[n][1]}
                    for n in sorted(RULINGS)],
        "rulingProblems": rule_problems or [],
        "baseline": {
            "path": rel(base_path) if base_path else "",
            "found": base_diff is not None,
            "diff": base_diff,
        },
        "entries": [
            {
                "name": e.name,
                "scope": e.scope,
                "kind": e.decl.kind,
                "package": e.decl.package,
                "file": rel(e.decl.file),
                "declLine": e.decl.line,
                "status": e.status,
                "bucket": e.bucket,
                "byDesign": e.by_design,
                "reason": e.reason,
                "members": {"functions": e.decl.functions, "properties": e.decl.properties},
                "evidence": {
                    "callSites": [
                        {"file": rel(h.file), "line": h.line, "member": h.member,
                         "kind": h.kind, "text": h.text}
                        for h in e.calls[:10]
                    ],
                    "callCount": len(e.calls),
                    "selfCallCount": len(e.self_calls),
                    "propertyRefs": [
                        {"file": rel(h.file), "line": h.line, "member": h.member}
                        for h in e.props[:10]
                    ],
                    "typeRefs": [
                        {"file": rel(h.file), "line": h.line, "text": h.text}
                        for h in e.type_refs[:5]
                    ],
                    "importRefs": [
                        {"file": rel(h.file), "line": h.line, "text": h.text}
                        for h in e.import_refs[:5]
                    ],
                    "commentMentions": e.comment_mentions,
                    "testRefs": e.test_refs,
                    "ambiguousHits": e.ambiguous_hits,
                },
            }
            for e in entries
        ],
        "audit": [{"name": e.name, "bucket": e.bucket, "command": audit_command(e)}
                  for e in audit],
    }


def audit_command(entry: Entry) -> str:
    return 'grep -rn "%s" app/src/main app/src/test app/src/androidTest --include=*.kt' % entry.name


def pick_audit(entries: list[Entry], n: int) -> list[Entry]:
    """确定性抽取：组内按 md5(类名) 定序，三个桶轮流取，保证两栏都被抽到。"""
    pool = targets(entries)
    groups = {
        BUCKET_WIRED: sorted([e for e in pool if e.bucket == BUCKET_WIRED],
                             key=lambda e: hashlib.md5(e.name.encode()).hexdigest()),
        BUCKET_BY_DESIGN: sorted([e for e in pool if e.bucket == BUCKET_BY_DESIGN],
                                 key=lambda e: hashlib.md5(e.name.encode()).hexdigest()),
        BUCKET_PENDING: sorted([e for e in pool if e.bucket == BUCKET_PENDING],
                               key=lambda e: hashlib.md5(e.name.encode()).hexdigest()),
    }
    order = [BUCKET_WIRED, BUCKET_PENDING, BUCKET_BY_DESIGN]
    picked: list[Entry] = []
    i = 0
    while len(picked) < n and any(groups.values()):
        group = groups[order[i % len(order)]]
        if group:
            picked.append(group.pop(0))
        i += 1
    return picked


def render_md(entries: list[Entry], audit: list[Entry], src: Path,
              base_diff: dict | None = None, base_path: Path | None = None,
              rule_problems: list[str] | None = None) -> str:
    c = counts(entries)
    main = targets(entries)
    out: list[str] = []
    out.append("# 接线清单（600 · 阶段一：三栏固化）\n")
    out.append("扫描根 `%s`。候选＝类名含 `Wiring`/`Install`/`Boot` 的 object/class/enum/interface"
               "（三栏只算这一类）；`*Wiring.kt` 里名字不含标记的伴随类型单列附录（%d 件）。"
               % (rel(src), c["companion"]))
    out.append("「已接」＝**申报文件之外**的 `app/src/main` 里出现 `<类名>.方法(`（**全限定写法也算**）"
               "或 `<类名>(`；声明行、同文件内自调、注释与字符串都不算。\n")
    out.append("合计 **%d** 件装配类：有调用点 **%d** ｜ by-design **%d** ｜ 待判 **%d**"
               "（无调用点合计 **%d**）。\n"
               % (c["total"], c["wired"], c["byDesign"], c["pending"], c["unwiredTotal"]))
    out.append("> **by-design ≠ 已接线**：by-design 只是「无调用点，且已知道为什么」（纯常量/纯 DTO/"
               "端口接口/人工裁定）。要判「有没有接」，只看第一栏。**测试里在用也不算接**。\n")

    out.append("## 一、有调用点（%d）\n" % c["wired"])
    out.append("| # | 类 | 声明位置 | 首个调用点 | 调用数 |")
    out.append("| --- | --- | --- | --- | --- |")
    wired = [e for e in main if e.bucket == BUCKET_WIRED]
    for i, e in enumerate(wired, 1):
        out.append("| %d | `%s` | %s:%d | %s | %d |"
                   % (i, e.name, rel(e.decl.file), e.decl.line, _site(e.calls[0]),
                      len(e.calls)))
    if not wired:
        out.append("| — | （无） | — | — | — |")
    out.append("")

    by_design = [e for e in main if e.bucket == BUCKET_BY_DESIGN]
    pending = [e for e in main if e.bucket == BUCKET_PENDING]
    out.append("## 二、无调用点（%d）\n" % c["unwiredTotal"])
    out.append("### 2.1 by-design（%d）\n" % len(by_design))
    if by_design:
        out.append("| # | 类 | 声明位置 | 归类 | 理由 |")
        out.append("| --- | --- | --- | --- | --- |")
        for i, e in enumerate(by_design, 1):
            out.append("| %d | `%s` | %s:%d | %s | %s |"
                       % (i, e.name, rel(e.decl.file), e.decl.line, e.by_design, e.reason))
    else:
        out.append("（无）")
    out.append("")
    out.append("### 2.2 待判（%d）\n" % len(pending))
    if pending:
        out.append("| # | 类 | 声明位置 | 理由（含证据计数） |")
        out.append("| --- | --- | --- | --- |")
        for i, e in enumerate(pending, 1):
            out.append("| %d | `%s` | %s:%d | %s |"
                       % (i, e.name, rel(e.decl.file), e.decl.line, e.reason))
    else:
        out.append("（无）")
    out.append("")

    out.append("## 三、抽查建议（%d 件，按 md5 定序，不随运行变化）\n" % len(audit))
    for i, e in enumerate(audit, 1):
        out.append("%d. `%s` · %s · %s:%d" % (i, e.name, e.bucket, rel(e.decl.file), e.decl.line))
        out.append("   - 报告说：%s" % e.reason)
        if e.calls:
            out.append("   - 报告记的调用点：%s" % "、".join(_site(h) for h in e.calls[:3]))
        out.append("   - 复核命令：`%s`" % audit_command(e))
    out.append("")

    out.append("## 四、裁定与基线（阶段一）\n")
    out.append("### 4.1 人工裁定（%d 条，闭集只减不增；表外一律落待判）\n" % len(RULINGS))
    out.append("| 类 | 归类 | 理由 |")
    out.append("| --- | --- | --- |")
    for name in sorted(RULINGS):
        label, why = RULINGS[name]
        out.append("| `%s` | %s | %s |" % (name, label, why))
    out.append("")
    if rule_problems:
        out.append("**裁定体检：%d 条对不上**（裁定表要人改，脚本不自己修）：\n" % len(rule_problems))
        for p in rule_problems:
            out.append("- " + p)
    else:
        out.append("裁定体检通过：%d 条都还在扫描结果里、都仍在 by-design。" % len(RULINGS))
    out.append("")

    base_name = rel(base_path) if base_path else "tools/wiring-baseline.json"
    out.append("### 4.2 与基线的差异（`%s`）\n" % base_name)
    if base_diff is None:
        out.append("还没固化：没有 `%s`。跑 `--update-baseline` 生成（阶段一只固化、不开红）。"
                   % base_name)
    elif base_diff["clean"]:
        out.append("**0 差异**：名册与三栏（wired/byDesign/pending）与基线逐条一致。")
    else:
        out.append("新增 %d ｜ 消失 %d ｜ 翻栏 %d\n"
                   % (len(base_diff["added"]), len(base_diff["gone"]),
                      len(base_diff["flipped"])))
        for x in base_diff["added"]:
            out.append("- 新增 `%s`（%s）· %s" % (x["name"], x["bucket"], x["file"]))
        for x in base_diff["gone"]:
            out.append("- 消失 `%s`（基线里是 %s）· %s" % (x["name"], x["bucket"], x["file"]))
        for x in base_diff["flipped"]:
            out.append("- 翻栏 `%s` · %s → %s · %s"
                       % (x["name"], x["was"], x["now"], x["file"]))
    out.append("")

    companions = [e for e in entries if e.scope == SCOPE_COMPANION]
    out.append("## 附：同文件伴随类型（%d 件，不计入上面三栏）\n" % len(companions))
    if companions:
        out.append("| # | 类 | 声明位置 | 状态 | 一句话（不判性质） |")
        out.append("| --- | --- | --- | --- | --- |")
        for i, e in enumerate(companions, 1):
            out.append("| %d | `%s` | %s:%d | %s | %s |"
                       % (i, e.name, rel(e.decl.file), e.decl.line, e.bucket, e.reason))
    else:
        out.append("（无）")
    out.append("")
    return "\n".join(out)


def write_json(payload: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8", newline="\n")


# ── 五之二、基线（阶段一：固化名册，**不开红**） ──────────────────────────────

BASELINE_SCHEMA = "wiring-baseline/1"
DEFAULT_BASELINE = ROOT / "tools/wiring-baseline.json"


def baseline_payload(entries: list[Entry], src: Path) -> dict:
    """基线只记「谁在、在哪一栏」——不带 commit、不带时间戳，所以重跑字节不变。"""
    return {
        "schema": BASELINE_SCHEMA,
        "src": rel(src),
        "note": "装配类名册与三栏归属（阶段一固化）。by-design ≠ 已接线：要判「有没有接」看 bucket=有调用点。",
        "counts": counts(entries),
        "entries": [
            {
                "name": e.name,
                "file": rel(e.decl.file),
                "declLine": e.decl.line,
                "kind": e.decl.kind,
                "bucket": e.bucket,
                "byDesign": e.by_design,
                "callCount": len(e.calls),
            }
            for e in targets(entries)
        ],
    }


def load_baseline(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - 基线坏了要如实说，不许当成「没有基线」
        raise SystemExit("基线读不动：%s（%s）" % (path, exc))
    if data.get("schema") != BASELINE_SCHEMA:
        raise SystemExit("基线 schema 不认识：%r ≠ %r" % (data.get("schema"), BASELINE_SCHEMA))
    return data


def diff_baseline(entries: list[Entry], base: dict) -> dict:
    """按 (类名, 文件) 比对，不看行号（行号会因编辑漂移，不是名册变化）。"""
    now = {(e.name, rel(e.decl.file)): e for e in targets(entries)}
    old = {(x["name"], x["file"]): x for x in base.get("entries", [])}
    added = sorted(k for k in now if k not in old)
    gone = sorted(k for k in old if k not in now)
    flipped = sorted((k[0], k[1], old[k]["bucket"], now[k].bucket)
                     for k in now if k in old and old[k]["bucket"] != now[k].bucket)
    return {
        "added": [{"name": n, "file": f, "bucket": now[(n, f)].bucket} for n, f in added],
        "gone": [{"name": n, "file": f, "bucket": old[(n, f)]["bucket"]} for n, f in gone],
        "flipped": [{"name": n, "file": f, "was": w, "now": v} for n, f, w, v in flipped],
        "clean": not (added or gone or flipped),
    }


STRICT_RULES = (
    ("R1", "新增待判"),
    ("R2", "翻成待判"),
    ("R3", "名册消失"),
    ("R4", "裁定过期"),
)


def strict_violations(diff: dict, problems: list[str]) -> list[tuple[str, str]]:
    """四条红标准（600 阶段二）——只红「变坏」，不红「新增了一个纯 DTO」。

    R1 新增待判：基线里没有的装配类，当前落「待判」 ⇒ 新写了 wiring 却没接。
    R2 翻成待判：基线里不是待判、现在变成待判 ⇒ 线断了，或 by-design 退化成待判。
    R3 名册消失：基线里有、现在扫不到 ⇒ 删/改名要连 baseline 一起对账，不许悄悄消失。
    R4 裁定过期：`RULINGS` 点名的件已经不再是无调用点·by-design ⇒ 表与事实不符。

    **不红**：新增 by-design（纯常量/纯 DTO/端口接口）、待判 → 有调用点（接上了是好事）。
    """
    out: list[tuple[str, str]] = []
    for x in diff["added"]:
        if x["bucket"] == BUCKET_PENDING:
            out.append(("R1", "新增待判：`%s`（%s）——新增装配件没有生产调用点"
                        % (x["name"], x["file"])))
    for x in diff["flipped"]:
        if x["now"] == BUCKET_PENDING and x["was"] != BUCKET_PENDING:
            out.append(("R2", "翻成待判：`%s`（%s）%s → %s"
                        % (x["name"], x["file"], x["was"], x["now"])))
    for x in diff["gone"]:
        out.append(("R3", "名册消失：`%s`（%s，基线里是 %s）"
                    % (x["name"], x["file"], x["bucket"])))
    for p in problems:
        out.append(("R4", p))
    return out


# ── 五之三、R5 减账必须有对价（防洗；证据只认 git diff + 扫描结果） ─────────


def git_name_status(base: str) -> dict[str, str] | None:
    """`git diff --name-status <base> -- app/src/main` ⇒ {仓内相对路径: A/M/D/R}。

    取不到（没 git／base 不认识／超时）返回 None —— 调用方必须如实说「R5 未判定」，
    **不许**当成「没问题」。
    """
    try:
        out = subprocess.run(
            ["git", "diff", "--name-status", "--no-color", base, "--", "app/src/main"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
            encoding="utf-8", errors="replace",
        )
    except Exception:  # noqa: BLE001 - git 不在／超时，都归「取不到」
        return None
    if out.returncode != 0:
        return None
    status: dict[str, str] = {}
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        code = parts[0][:1]
        if code == "R" and len(parts) >= 3:
            status[parts[2].strip()] = "R"
        else:
            status[parts[1].strip()] = code
    return status


def git_added_lines(base: str, path: str) -> list[str]:
    """该文件在本次 diff 里的新增行（去掉 `+++` 头）。取不到就空表。"""
    try:
        out = subprocess.run(
            ["git", "diff", "-U0", "--no-color", base, "--", path],
            cwd=str(ROOT), capture_output=True, text=True, timeout=60,
            encoding="utf-8", errors="replace",
        )
    except Exception:  # noqa: BLE001
        return []
    if out.returncode != 0:
        return []
    return [ln[1:] for ln in out.stdout.splitlines()
            if ln.startswith("+") and not ln.startswith("+++")]


def line_has_call(line: str, name: str) -> bool:
    """这一行里有没有对 `name` 的**调用**（声明位不算，与扫描器同一口径）。"""
    for m in re.finditer(r"(?<![A-Za-z0-9_.])%s\s*[.(]" % re.escape(name), line):
        if _word_before(line, m.start()) not in DECL_KEYWORDS:
            return True
    return False


def sovereignty_violations(diff: dict, current: dict, status: dict | None,
                           added_lookup) -> list[tuple[str, str]]:
    """R5：减账（移出基线／降级）必须有对价，否则红「疑似洗账」。

    对价按情形宽严不同（这是本规则的全部内容，别简化成一条）：

    · 条目**不在基线里**（`added`＝基线被删了／基线落后于代码）——**最严**：
      该件现在 wired，或所在文件是本次新建，或该文件本次**新增了对它的调用**。
      只要求"文件动过"会太好骗（顺手改个空行就能洗），所以这里要看到调用。
    · 条目**降级成 by-design**——中等：所在文件本次有任何改动即可（把它改成纯数据类
      也会改文件）；文件没动而基线掉了档 ⇒ 红。
    · 条目**从扫描里消失**（gone）——看文件是否被删／改名。

    证据只认 `git diff` 与扫描结果，**不看提交信息**（提交信息可以写假）。
    `status=None` 表示 git 取不到 ⇒ 不判（调用方打 warning，绝不静默当绿）。
    """
    if status is None:
        return []
    out: list[tuple[str, str]] = []

    def price(name: str, path: str, strict: bool) -> str:
        cur = current.get((name, path))
        if cur is not None and cur.bucket == BUCKET_WIRED:
            return "该件现在有生产调用点"
        code = status.get(path)
        if code is None:
            return ""
        if code == "A":
            return "该件所在文件是本次新增"
        if code in ("D", "R"):
            return "该件所在文件被删除/改名"
        if code == "M":
            if not strict:
                return "该件所在文件本次有改动"
            for line in added_lookup(path) or []:
                if line_has_call(line, name):
                    return "该件所在文件本次新增了对它的调用"
        return ""

    for x in diff["added"]:
        if price(x["name"], x["file"], strict=True):
            continue
        out.append(("R5", "疑似洗账：`%s` 被移出基线但代码没动（%s，当前是 %s）"
                    % (x["name"], x["file"], x["bucket"])))
    for x in diff["gone"]:
        if price(x["name"], x["file"], strict=False):
            continue
        out.append(("R5", "疑似洗账：`%s` 从基线里消失、代码里也没了，但所在文件没动（%s）"
                    % (x["name"], x["file"])))
    for x in diff["flipped"]:
        if x["now"] != BUCKET_BY_DESIGN or x["was"] == BUCKET_BY_DESIGN:
            continue
        if price(x["name"], x["file"], strict=False):
            continue
        out.append(("R5", "疑似洗账：`%s` 被降级 %s → %s 但代码没动（%s）"
                    % (x["name"], x["was"], x["now"], x["file"])))
    return out


def render_change_rationale(diff: dict | None, reason: str) -> str:
    """R7：把「这次基线为什么动」渲染成能直接粘进提交信息／PR 的一段。

    机检不读这段（信息可以写假），它只负责**逼人留因**：`--generate` 没有 `--reason`
    直接退 2。真正拦洗账的是 R5（只认 git diff + 扫描结果）。
    """
    body: list[str] = ["### baseline 变更因由（R7，粘进提交信息/PR）", ""]
    if diff is None:
        body.append("（首次固化：基线此前不存在）")
    elif diff["clean"]:
        body.append("（与扫描一致：无变更）")
    else:
        body.append("本次变更：新增 %d ｜ 删除 %d ｜ 翻栏 %d"
                    % (len(diff["added"]), len(diff["gone"]), len(diff["flipped"])))
        for x in diff["added"]:
            body.append("- 新增 `%s`（%s）· %s" % (x["name"], x["bucket"], x["file"]))
        for x in diff["gone"]:
            body.append("- 删除 `%s`（基线里是 %s）· %s"
                        % (x["name"], x["bucket"], x["file"]))
        for x in diff["flipped"]:
            body.append("- 翻栏 `%s` · %s → %s · %s"
                        % (x["name"], x["was"], x["now"], x["file"]))
    body.append("")
    body.append("因由：%s" % reason)
    return "\n".join(body)


def generate_baseline(entries: list[Entry], src: Path, base_path: Path,
                      base: dict | None, reason: str, allow_add: bool) -> int:
    """R6：基线的唯一写入口。

    · 与扫描一致 ⇒ **不写盘**（防无意义重写、防 diff 噪音）；
    · 默认**拒绝新增**（只减不增）；确认是正当新增才加 `--allow-add`；
    · 写之前把变更因由（R7）打到 stderr，供人粘进提交信息。
    """
    payload = baseline_payload(entries, src)
    diff = diff_baseline(entries, base) if base is not None else None
    if diff is not None and diff["clean"]:
        print("[generate] 基线与扫描一致，未写盘（R6：防无意义重写）。", file=sys.stderr)
        return 0
    if diff is not None and diff["added"] and not allow_add:
        print("[generate] 拒绝写盘：本次会新增 %d 条（R6：默认只减不增）。"
              % len(diff["added"]), file=sys.stderr)
        for x in diff["added"]:
            print("  + %s（%s）· %s" % (x["name"], x["file"], x["bucket"]), file=sys.stderr)
        print("确认这些是正当新增（新写的装配类/契约）再加 --allow-add；不确定就先别写。",
              file=sys.stderr)
        return 1
    print(render_change_rationale(diff, reason), file=sys.stderr)
    write_json(payload, base_path)
    print("[generate] 已写入：%s（%d 件）" % (rel(base_path), len(targets(entries))),
          file=sys.stderr)
    return 0


def audit_rulings(entries: list[Entry]) -> list[str]:
    """裁定表体检：每条都得还在、且仍在 by-design。过期就点名，别留僵尸条目。"""
    first: dict[str, Entry] = {}
    for e in entries:
        first.setdefault(e.name, e)
    problems: list[str] = []
    for name in sorted(RULINGS):
        entry = first.get(name)
        if entry is None:
            problems.append("`%s` 扫不到了（改名/删除）⇒ 删掉这条裁定" % name)
        elif entry.bucket == BUCKET_WIRED:
            problems.append("`%s` 已经有生产调用点了（%s）⇒ 裁定过期，删掉这条"
                            % (name, _site(entry.calls[0])))
        elif entry.bucket != BUCKET_BY_DESIGN:
            problems.append("`%s` 现在落「%s」而不是 by-design ⇒ 裁定与事实对不上"
                            % (name, entry.bucket))
    return problems


# ── 六、自检夹具（合成树，不碰仓库） ─────────────────────────────────────────


FIXTURE_SRC = {
    "a/WiredWiring.kt": """
package demo.a
internal object WiredWiring {
    fun go(): Int = 1
}
""",
    "a/Caller.kt": """
package demo.a
fun callsWired(): Int {
    return WiredWiring.go()
}
""",
    "a/QualifiedWiring.kt": """
package demo.a
internal object QualifiedWiring {
    fun go(): Int = 6
}
""",
    "a/QualifiedCaller.kt": """
package demo.other
fun callsQualified(): Int {
    return demo.a.QualifiedWiring.go()
}
""",
    "a/CommentOnlyWiring.kt": """
package demo.a
internal object CommentOnlyWiring {
    fun go(): Int = 2
}
""",
    "a/Mention.kt": """
package demo.a
// 记账在 CommentOnlyWiring.go() 那句（只是注释，不算调用）
private val TEXT = "CommentOnlyWiring.go() 也只是字符串"
private val RAW = \"\"\"CommentOnlyWiring.go()\"\"\"
""",
    "a/ConstWiring.kt": """
package demo.a
internal object ConstWiring {
    const val LIMIT: Int = 3
    val NAME: String = "x"
}
""",
    "a/DtoWiring.kt": """
package demo.a
internal class DtoWiring(val a: Int)
""",
    "a/TestOnlyWiring.kt": """
package demo.a
internal object TestOnlyWiring {
    fun go(): Int = 4
}
""",
    "a/GrantWiring.kt": """
package demo.a
internal interface GrantWiring {
    fun go(): Int
}
""",
    "a/ForeignWiring.kt": """
package demo.a
internal object ForeignWiring {
    fun go(): Int = 5
}
""",
    "p1/TwinWiring.kt": """
package demo.p1
internal class TwinWiring(val a: Int)
""",
    "p2/TwinWiring.kt": """
package demo.p2
internal class TwinWiring(val a: Int)
""",
    "p2/TwinUser.kt": """
package demo.p2
internal fun useTwin(): TwinWiring = TwinWiring(7)
""",
    "q1/AliasWiring.kt": """
package demo.q1
internal object AliasWiring {
    fun go(): Int = 9
}
""",
    "q2/AliasWiring.kt": """
package demo.q2
internal object AliasWiring {
    fun go(): Int = 10
}
""",
    "q3/User.kt": """
package demo.q3
import demo.q2.AliasWiring
internal fun u(): Int = AliasWiring.go()
""",
    "a/SelfWiredWiring.kt": """
package demo.a
internal class SelfWiredWiring(val x: Int) {
    fun go(): Int = 11
}
""",
    "a/CompanionHolderWiring.kt": """
package demo.a
internal object HolderWiring {
    fun go(): Int = 8
}
internal class HolderRecord(val x: Int)
""",
}

FIXTURE_TEST = {
    "a/TestOnlyWiringTest.kt": """
package demo.a
class TestOnlyWiringTest {
    fun t() = TestOnlyWiring.go()
}
""",
}


def selftest() -> int:
    fails: list[str] = []
    checked = 0

    def expect(cond: bool, msg: str) -> None:
        nonlocal checked
        checked += 1
        if not cond:
            fails.append(msg)

    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        src = base / "app/src/main"
        for name, body in FIXTURE_SRC.items():
            path = src / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body.lstrip("\n"), encoding="utf-8")
        for name, body in FIXTURE_TEST.items():
            path = base / "app/src/test" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body.lstrip("\n"), encoding="utf-8")

        global TEST_SRCS
        saved = TEST_SRCS
        TEST_SRCS = (base / "app/src/test", base / "app/src/androidTest")
        try:
            entries = build(src)
            # 幂等要在这里比：两遍都必须在同一个 TEST_SRCS 下，否则第二次扫的是真仓测试源，
            # 「只被测试用」那一格会翻栏——这不是幂等坏了，是夹具没关住（自检自己抓到过）。
            again = build(src)
        finally:
            TEST_SRCS = saved

        got = {(e.name, e.decl.file.parent.name): e for e in entries}

        def at(name: str, pkg_dir: str = "a") -> Entry | None:
            return got.get((name, pkg_dir))

        cases = [
            ("WiredWiring", True, BUCKET_WIRED),
            ("QualifiedWiring", True, BUCKET_WIRED),
            ("CommentOnlyWiring", False, BUCKET_PENDING),
            ("ConstWiring", False, BUCKET_BY_DESIGN),
            ("DtoWiring", False, BUCKET_BY_DESIGN),
            ("TestOnlyWiring", False, BUCKET_PENDING),
            ("GrantWiring", False, BUCKET_BY_DESIGN),
            ("ForeignWiring", False, BUCKET_PENDING),
        ]
        for name, _, want in [(n, a, b) for n, a, b in cases]:
            entry = at(name)
            if entry is None:
                fails.append("没扫到 `%s`" % name)
            elif entry.bucket != want:
                fails.append("`%s` 应落「%s」，实际「%s」（%s）"
                             % (name, want, entry.bucket, entry.reason))
        co = at("CommentOnlyWiring")
        expect(co is not None and co.comment_mentions >= 3,
               "注释/字符串提及次数没数出来（挖注释可能失效）")
        qw = at("QualifiedWiring")
        expect(qw is not None and len(qw.calls) == 1,
               "全限定调用 `demo.a.QualifiedWiring.go()` 没被算成调用点")
        # 同名双类：调用方在 p2 ⇒ 只能归 p2 那份；归给 p1（或摊给两边）就是串证据
        t1, t2 = at("TwinWiring", "p1"), at("TwinWiring", "p2")
        expect(t1 is not None and t2 is not None, "同名双类没各扫一份")
        if t1 is not None and t2 is not None:
            expect(t2.bucket == BUCKET_WIRED and len(t2.calls) == 1,
                   "同名类的调用点没归给同包那份（p2）")
            expect(t1.bucket != BUCKET_WIRED,
                   "同名类的调用点被摊给了另一份（p1）—— 归属有错")
        # 跨包同名：q3 用 import 指名 q2 那份
        a1, a2 = at("AliasWiring", "q1"), at("AliasWiring", "q2")
        if a1 is not None and a2 is not None:
            expect(a2.bucket == BUCKET_WIRED,
                   "import 指名的那份（q2/AliasWiring）没判成已接")
            expect(a1.bucket != BUCKET_WIRED,
                   "同名的另一份（q1/AliasWiring）被 import 串了证据")
        else:
            fails.append("跨包同名 AliasWiring 没各扫一份")
        # 声明行不是调用：`class SelfWiredWiring(` 不能算一次自调
        sw = at("SelfWiredWiring")
        expect(sw is not None and len(sw.self_calls) == 0,
               "`class X(` 声明行被算成了构造调用（self_calls 虚高）")
        hr = at("HolderRecord")
        expect(hr is not None and hr.scope == SCOPE_COMPANION,
               "`*Wiring.kt` 里的伴随类型没进附录")
        hw = at("HolderWiring")
        expect(hw is not None and hw.scope == SCOPE_TARGET, "装配类被误判成伴随类型")
        gw = at("GrantWiring")
        expect(gw is not None and gw.by_design == BY_PORT,
               "接口没归「端口接口」（端口不许进待判/待办）")
        # 测试里在用 ≠ 接上了：必须落待判，且理由里点明「测试用≠生产接上」
        tw = at("TestOnlyWiring")
        expect(tw is not None and tw.bucket == BUCKET_PENDING,
               "只在测试里用的装配类被判成 by-design（测试用不等于接线）")
        expect(tw is not None and "测试用≠生产接上" in tw.reason,
               "待判理由里没写「测试里用了几次」")

        # 裁定表：注入一条夹具名 ⇒ 必须压过默认待判；表外的 ForeignWiring 仍待判（闭集）
        saved_rulings = dict(RULINGS)
        try:
            RULINGS["ForeignWiring"] = (BY_RULED, "夹具：人工裁定")
            ruled = {(e.name, e.decl.file.parent.name): e for e in build(src)}
            fr = ruled.get(("ForeignWiring", "a"))
            expect(fr is not None and fr.bucket == BUCKET_BY_DESIGN
                   and fr.by_design == BY_RULED, "裁定表没生效（点了名还落待判）")
            # 裁定过期：给已接的类挂一条裁定 ⇒ 体检必须点名
            RULINGS["WiredWiring"] = (BY_RULED, "夹具：已接却挂裁定")
            probs = audit_rulings(list(ruled.values()))
            expect(any("WiredWiring" in p for p in probs),
                   "裁定过期没被发现（已接的类还挂着裁定）")
        finally:
            RULINGS.clear()
            RULINGS.update(saved_rulings)

        # 基线：往返 0 差异；人为翻一栏必须被比对抓出来；两次生成字节相同
        payload = baseline_payload(entries, src)
        expect(diff_baseline(entries, payload)["clean"], "基线与现状往返比对不是 0 差异")
        tampered = json.loads(json.dumps(payload))
        for row in tampered["entries"]:
            if row["bucket"] == BUCKET_WIRED:
                row["bucket"] = BUCKET_PENDING
                break
        tdiff = diff_baseline(entries, tampered)
        expect(not tdiff["clean"] and len(tdiff["flipped"]) == 1,
               "翻栏没被基线比对抓出来")
        expect(json.dumps(payload, ensure_ascii=False, sort_keys=True)
               == json.dumps(baseline_payload(again, src), ensure_ascii=False, sort_keys=True),
               "基线两次生成不一致（幂等坏了）")
        cnt = counts(entries)
        expect(cnt["total"] == cnt["wired"] + cnt["byDesign"] + cnt["pending"],
               "三栏没铺满总数（有件漏出了统计）")
        expect(cnt["unwiredTotal"] == cnt["byDesign"] + cnt["pending"],
               "unwiredTotal 与 byDesign+pending 对不上")

        # 四条红标准：R1/R2/R3/R4 各造一条；「新增 by-design」「待判→已接」不许红
        fake = {
            "added": [{"name": "NewPendingWiring", "file": "x/a.kt", "bucket": BUCKET_PENDING},
                      {"name": "NewDtoWiring", "file": "x/b.kt", "bucket": BUCKET_BY_DESIGN}],
            "gone": [{"name": "GoneWiring", "file": "x/c.kt", "bucket": BUCKET_WIRED}],
            "flipped": [{"name": "BrokenWiring", "file": "x/d.kt",
                         "was": BUCKET_WIRED, "now": BUCKET_PENDING},
                        {"name": "FixedWiring", "file": "x/e.kt",
                         "was": BUCKET_PENDING, "now": BUCKET_WIRED}],
        }
        bad = strict_violations(fake, ["`FooWiring` 裁定过期"])
        codes = [c for c, _ in bad]
        for code in ("R1", "R2", "R3", "R4"):
            expect(code in codes, "红标准 %s 没抓到对应情形" % code)
        expect(len(bad) == 4, "四条红标准数目不对（多红/少红：%s）" % codes)
        expect(not any("NewDtoWiring" in m for _, m in bad), "新增 by-design 被误红")
        expect(not any("FixedWiring" in m for _, m in bad), "待判→已接 被误红")
        expect(strict_violations({"added": [], "gone": [], "flipped": []}, []) == [],
               "干净的基线被误红")

        first = json.dumps(to_json(entries, pick_audit(entries, 3), src), ensure_ascii=False,
                           indent=2)
        second = json.dumps(to_json(again, pick_audit(again, 3), src), ensure_ascii=False,
                            indent=2)
        expect(first == second, "两遍输出不一致（幂等坏了）")

        # ── R5：减账必须有对价（内存级，喂假 diff/status，不依赖 git） ──────────
        import contextlib
        import io

        class _Cur:
            def __init__(self, bucket: str) -> None:
                self.bucket = bucket

        cur_map = {("A_Wiring", "a/A.kt"): _Cur(BUCKET_WIRED),
                   ("D_Wiring", "a/D.kt"): _Cur(BUCKET_PENDING)}

        def _d(added=(), gone=(), flipped=()):
            return {"added": list(added), "gone": list(gone), "flipped": list(flipped),
                    "clean": not (added or gone or flipped)}

        def _no_added(_path: str):
            return []

        row_q = {"name": "Q_Wiring", "file": "a/Q.kt", "bucket": BUCKET_PENDING}
        v = sovereignty_violations(_d(added=[row_q]), cur_map, {}, _no_added)
        expect(len(v) == 1 and v[0][0] == "R5" and "疑似洗账" in v[0][1],
               "① 洗账夹具没红：删一条基线、代码没动 ⇒ 必须报「疑似洗账」")
        v = sovereignty_violations(
            _d(added=[{"name": "A_Wiring", "file": "a/A.kt", "bucket": BUCKET_WIRED}]),
            cur_map, {}, _no_added)
        expect(v == [], "② 真接上后再删（该件当前 wired）被误红")
        v = sovereignty_violations(
            _d(added=[{"name": "Q_Wiring", "file": "a/Q.kt", "bucket": BUCKET_BY_DESIGN}]),
            cur_map, {"a/Q.kt": "A"}, _no_added)
        expect(v == [], "新增条目的文件是本次新建，被误红")
        v = sovereignty_violations(
            _d(added=[{"name": "Q_Wiring", "file": "a/Q.kt", "bucket": BUCKET_BY_DESIGN}]),
            cur_map, {"a/Q.kt": "M"}, lambda _p: ["            Q_Wiring.go()"])
        expect(v == [], "文件里真新增了对 Q_Wiring 的调用，被误红")
        v = sovereignty_violations(
            _d(added=[{"name": "Q_Wiring", "file": "a/Q.kt", "bucket": BUCKET_BY_DESIGN}]),
            cur_map, {"a/Q.kt": "M"}, lambda _p: ["    // 顺手加一行注释"])
        expect(len(v) == 1, "只改了注释（没有调用）也放过了 —— R5 太松")
        v = sovereignty_violations(
            _d(flipped=[{"name": "D_Wiring", "file": "a/D.kt",
                         "was": BUCKET_PENDING, "now": BUCKET_BY_DESIGN}]),
            cur_map, {}, _no_added)
        expect(len(v) == 1 and "降级" in v[0][1], "降级（待判→by-design）没被 R5 抓到")
        v = sovereignty_violations(
            _d(flipped=[{"name": "D_Wiring", "file": "a/D.kt",
                         "was": BUCKET_PENDING, "now": BUCKET_BY_DESIGN}]),
            cur_map, {"a/D.kt": "M"}, _no_added)
        expect(v == [], "降级但文件本次有改动，被误红")
        v = sovereignty_violations(
            _d(gone=[{"name": "G_Wiring", "file": "a/G.kt", "bucket": BUCKET_WIRED}]),
            cur_map, {}, _no_added)
        expect(len(v) == 1, "名册消失且文件没动，没被 R5 抓到")
        v = sovereignty_violations(
            _d(gone=[{"name": "G_Wiring", "file": "a/G.kt", "bucket": BUCKET_WIRED}]),
            cur_map, {"a/G.kt": "D"}, _no_added)
        expect(v == [], "文件被删了，消失条目被误红")
        expect(sovereignty_violations(_d(gone=[row_q]), cur_map, None, _no_added) == [],
               "取不到 git diff 时应「不判」而不是「判红」")

        # ── R6/R7：--generate 三态（真文件；③ 无变化不写盘） ──────────────────
        bl = base / "tools" / "wiring-baseline.json"
        bl.parent.mkdir(parents=True, exist_ok=True)
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = generate_baseline(entries, src, bl, None, "夹具：首次固化", False)
        expect(rc == 0 and bl.exists(), "generate 首次固化没写盘")
        original = bl.read_text(encoding="utf-8")
        bl.write_text(original + "\n", encoding="utf-8", newline="\n")  # 留一道"非标准"痕迹
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = generate_baseline(entries, src, bl, load_baseline(bl), "夹具：无变化", False)
        expect(rc == 0 and "未写盘" in buf.getvalue(), "③ 无变化时应打印「未写盘」")
        expect(bl.read_text(encoding="utf-8") == original + "\n",
               "③ 无变化时文件被重写了（R6 破了：痕迹被抹掉）")

        payload2 = json.loads(original)
        payload2["entries"] = [r for r in payload2["entries"] if r["name"] != "ForeignWiring"]
        write_json(payload2, bl)
        with contextlib.redirect_stderr(io.StringIO()):
            rc = generate_baseline(entries, src, bl, load_baseline(bl), "夹具：试删", False)
        expect(rc == 1, "R6 默认没拒绝「会新增条目」的写盘")
        with contextlib.redirect_stderr(io.StringIO()):
            rc = generate_baseline(entries, src, bl, load_baseline(bl), "夹具：正当新增", True)
        expect(rc == 0, "--allow-add 时仍拒绝写盘")
        # R7：--generate 不带 --reason ⇒ 退 2（写要留因）
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = main(["--generate", "--src", str(src), "--baseline", str(bl)])
        expect(rc == 2 and "必须带 --reason" in buf.getvalue(),
               "R7：--generate 不带 --reason 没被挡住（或没说清原因）")

    if fails:
        print("SELFTEST FAILED（%d/%d 条）" % (len(fails), checked))
        for f in fails:
            print("  · " + f)
        return 1
    print("SELFTEST OK：%d 条断言全过（扫描口径／端口接口／裁定表／基线往返／四条红／"
          "R5 洗账九态／R6 三态／R7 留因／幂等）" % checked)
    return 0


# ── 七、入口 ─────────────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="接线清单（只列不判；--check 判红、--generate 才写基线）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--src", default=str(MAIN_SRC), help="源码根，默认 app/src/main")
    ap.add_argument("--json", default=str(DEFAULT_JSON), help="JSON 产物路径")
    ap.add_argument("--no-json", action="store_true", help="只打印，不写 JSON")
    ap.add_argument("--md", default="", help="清单另存为 markdown 文件")
    ap.add_argument("--audit", type=int, default=5, help="抽查建议件数，默认 5")
    ap.add_argument("--baseline", default=str(DEFAULT_BASELINE), help="基线文件路径")
    ap.add_argument("--check", action="store_true",
                    help="只读判红（CI 用）：R1–R5 任一命中退 1；不写任何文件")
    ap.add_argument("--strict", action="store_true", help="--check 的旧名（保留兼容）")
    ap.add_argument("--generate", action="store_true",
                    help="按现状重写基线（**唯一**写入口；必须带 --reason）")
    ap.add_argument("--reason", default="", help="--generate 必填：这次基线变更为什么")
    ap.add_argument("--allow-add", action="store_true",
                    help="--generate 时允许新增条目（默认拒增：只减不增）")
    ap.add_argument("--diff-base", default="origin/main",
                    help="R5 取「本次改动文件集」的 git base，默认 origin/main")
    ap.add_argument("--update-baseline", action="store_true",
                    help="【已废止】旧名，现在只报错并提示改用 --generate --reason")
    ap.add_argument("--selftest", action="store_true", help="跑合成夹具自检")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()
    if args.update_baseline:
        print("--update-baseline 已废止（R6：基线只许 --generate 写，且必须带 --reason）。",
              file=sys.stderr)
        print('  换成：--generate --reason "为什么改"', file=sys.stderr)
        return 2
    check = args.check or args.strict
    if check and args.generate:
        print("--check 与 --generate 不能同用：一个只读判红、一个写盘。", file=sys.stderr)
        return 2
    if args.generate and not args.reason.strip():
        print("--generate 必须带 --reason（R7：写要留因）。", file=sys.stderr)
        return 2

    src = Path(args.src)
    if not src.exists():
        print("扫描根不存在：%s（扫描器环境有问题，不是接线问题）" % src, file=sys.stderr)
        return 2

    entries = build(src)
    if not targets(entries):
        print("::warning:: 一件装配类都没扫到——先怀疑口径或路径，别当成「全是干净」。",
              file=sys.stderr)
    base_path = Path(args.baseline)
    base = load_baseline(base_path)
    base_diff = diff_baseline(entries, base) if base is not None else None
    problems = audit_rulings(entries)
    for p in problems:
        print("::warning:: 裁定表 %s" % p, file=sys.stderr)
    audit = pick_audit(entries, max(0, args.audit))
    md = render_md(entries, audit, src, base_diff=base_diff, base_path=base_path,
                   rule_problems=problems)
    print(md)
    if args.md:
        Path(args.md).write_text(md + "\n", encoding="utf-8", newline="\n")
    if not args.no_json and not check:
        write_json(to_json(entries, audit, src, base_diff=base_diff, base_path=base_path,
                           rule_problems=problems), Path(args.json))
    if args.generate:
        return generate_baseline(entries, src, base_path, base, args.reason.strip(),
                                 args.allow_add)
    if check:
        if base_diff is None:
            print("::error:: wiring 闸没有基线（%s），无法判定名册漂移" % rel(base_path),
                  file=sys.stderr)
            return 2
        status = git_name_status(args.diff_base)
        if status is None:
            print("::error:: wiring 闸 R5 判不了：取不到 `git diff %s`（—判不了就是判不了，"
                  "不许当绿）。修法：git fetch origin main，或用 --diff-base 指一个存在的 rev。"
                  % args.diff_base, file=sys.stderr)
            return 2
        current = {(e.name, rel(e.decl.file)): e for e in targets(entries)}
        bad = strict_violations(base_diff, problems)
        bad += sovereignty_violations(base_diff, current, status,
                                      lambda p: git_added_lines(args.diff_base, p))
        for code, msg in bad:
            print("::error:: wiring %s %s" % (code, msg), file=sys.stderr)
        if bad:
            print("wiring 闸：红 %d 条（R1 新增待判／R2 翻成待判／R3 名册消失／"
                  "R4 裁定过期／R5 疑似洗账）" % len(bad), file=sys.stderr)
            return 1
        print("wiring 闸：五条红标准全过（新增待判 0／翻成待判 0／名册消失 0／"
              "裁定过期 0／疑似洗账 0）。", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
