#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""impact-check.py —— 影响面反查（437，2026-11-04）。

给一个文件 / 类 / 方法名，输出四块：
    ① 定义        哪个文件、第几行、类还是函数
    ② 调用点      「三步规矩」：类名 → 该类的公开方法名 → 薄封装
                  （XxxLog / XxxStore / record* 等），并做最多 4 跳的反向间接链
    ③ 受影响测试  哪些测试文件提到它
    ④ 风险提示    反射 / 字符串拼接 / 常量表（登记表、预算表）间接引用
                  ⇒ 单独标「可能有暗引用」

判据案例（origin/main）：
    a FileOpLedger（287）⇒ 报出经 FileOpLog.record → GuardPipeline.recordFileOps 的间接调用
    b QuietMode（379）   ⇒ 报出 RunEngine.askUser 处（四跳：askUser → quietDefer
                            → QuietCardGate.onAsk → hushed → QuietMode.active）
    c MediatorDecide（406）⇒ 报出零外部引用 + 「可能是 internal 候选」

「零引用」的口径（写在明处，不许只输出 0）
------------------------------------------
* 查名清单 = 目标展开出的类名、公开方法名、类名.方法名组合、薄封装名、
  以及（当目标只是文件名时）文件概念名；每个名字逐个列出命中数。
* 「外部引用」= 定义所在 Gradle 模块之外的 src/main Kotlin 代码里，按名字的
  **强命中**（类名 / 限定方法名 / 薄封装名）。裸方法名算弱命中，只统计不计数；
  概念名只算提及。
* 排除：定义文件自身（单列）、定义声明行（防「定义行恒真」）、注释、字符串字面量；
  测试源码（src/test、src/androidTest）单列 ③，不混进 ② 的外部计数。
* ②-B 的薄封装间接链**不参与**外部引用计数：Kotlin `internal` 只约束按名访问，
  别的模块调 MediatorWiring 不等于调 MediatorDecide。链照列，但口径分开写清。
* 目标既不是文件也不是顶层声明 ⇒ 退出 2 并明说「未找到定义，拒绝下零引用结论」。

边界（诚实标注未知区）
----------------------
这是**文本级**反查，不是 LSP / 编译器语义级 find-usages：不做类型解析、
不解析 import 别名、不追踪泛型/扩展函数的重载决议、不读 R8 mapping。
凡静态检索不可达的（反射按名取类、字符串拼名、登记表/常量表钉名），
一律落到 ④「可能有暗引用」，由人复核；工具不假装能证明「绝对没有引用」。
字符串拼名目前只识别字面量直加（"A" + "B"）；模板串 $var、StringBuilder、
变量拼接不在覆盖范围，属已知未知区。反射特征按仓级汇总，不冒充目标专属引用。
「零外部引用 ⇒ internal 候选」只是**候选**：Kotlin 的 internal 以编译单元为界，
跨模块测试引用、public 签名暴露目标类型、登记表另有 verdict 都会推翻收窄，
这三类反证会并列进 verdict.conflicts，不藏在口径背后。

运行性质：主流程只读（只 open 读取）、不联网（无网络/子进程调用）、不改仓库；
--selftest 仅写系统临时目录（tempfile.TemporaryDirectory，用完即删），不碰仓库。

用法
----
    python3 tools/impact-check.py FileOpLedger
    python3 tools/impact-check.py QuietMode --json
    python3 tools/impact-check.py --selftest
    python3 tools/impact-check.py MediatorDecide --root <仓库根> --max-hops 4

退出码：0 = 完成 / 自测全绿；1 = 自测有红；2 = 环境错误或目标未找到。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from dataclasses import dataclass, field

try:  # Windows 控制台个别字符编不出时降级替换，不让工具自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MAX_HOPS = 4

SKIP_DIRS = {
    ".git", ".gradle", ".idea", "build", "node_modules", "__pycache__",
    "证道设计报告", ".workbuddy", "_zd-private", "captures", ".cxx",
}
KT_EXT = ".kt"
DATA_EXTS = {".json", ".txt", ".xml", ".gradle", ".kts", ".yml", ".yaml", ".properties", ".toml", ".cfg", ".ini"}
DATA_NAME_HINTS = ("allowlist", "whitelist", "baseline", "budget", "surface", "registry", "ledger", "assets")

# 薄封装命名模式（用户点名的 XxxLog / XxxStore / record* 三类 + 项目惯例后缀）。
# 只用于标注与查名，不参与可达性判定；刻意不写任何具体案例的函数名。
THIN_PATTERNS = (
    re.compile(r".*(?:Log|Store|Sql|Rules|Gate|Wiring|Board|Registry|Dao|Cache|Table)$"),
    re.compile(r"^record\w*"),
)

# 顶层声明：class / object / interface / fun / val / var（含修饰符组合）
DECL_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"((?:(?:public|private|protected|internal|open|sealed|abstract|data|enum|annotation|"
    r"value|final|companion|inline|const|lateinit|external|expect|actual|operator|override|suspend)\s+)*)"
    r"(class|object|interface|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
# fun 定义（成员级与顶层都要），第 1 组修饰符、第 2 组名字
FUN_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"((?:(?:public|private|protected|internal|open|override|suspend|inline|operator|"
    r"abstract|final|external|tailrec|const|infix)\s+)*)"
    r"fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_]\w*\s*\.\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\("
)
REFLECTION_RE = re.compile(
    r"Class\.forName|loadClass\(|getDeclared(?:Method|Field|Constructor)|::class|javaClass|"
    r"kotlin\.reflect|MethodHandles|\.invoke\(|declaredMember"
)
CONCAT_RE = re.compile(r"\"([^\"\\\n]{1,40})\"\s*\+\s*\"([^\"\\\n]{1,40})\"")

STOPWORDS = {
    "get", "set", "run", "log", "add", "map", "let", "also", "apply", "use",
    "take", "size", "put", "call", "list", "build", "make", "read", "write",
    "create", "contains", "indexOf", "first", "last", "count", "value", "name",
    # 通用动词/资源口：文本级 BFS 无法判类型，让它们进 frontier 只会把链末端刷屏
    # （437 复审发现 hop4 被 "open" 霸榜，挤掉了真正的上游节点）。
    "open", "close", "start", "stop", "send", "resume", "execute", "handle",
    "dispatch", "invoke", "process", "check", "init", "load", "save", "copy",
    "move", "delete", "insert", "update", "clear", "reset", "notify", "observe",
    "render", "parse", "format", "toString", "equals", "hashCode", "wait", "join",
}

SCOPE_NOTE = (
    "外部引用 = 定义模块之外的 src/main Kotlin 代码中按名字的强命中；"
    "排除定义行/注释/字符串/定义文件自身；测试单列 ③；间接链单列 ②-B 且不计数。"
)


# ────────────────────────────── 源码模型 ──────────────────────────────

@dataclass
class SourceFile:
    rel: str
    path: str
    module: str | None
    scope: str                     # main / test / androidTest / data
    lines: list[str]
    code: list[str] = field(default_factory=list)
    literal: list[str] = field(default_factory=list)
    comment: list[str] = field(default_factory=list)


@dataclass
class Decl:
    name: str
    kind: str
    visibility: str
    file: str
    line: int                       # 1-based
    body_start: int = 0             # 0-based
    body_end: int = 0               # 0-based inclusive


@dataclass
class Function:
    simple: str
    owner: str
    file: str
    line: int                       # 1-based
    body_start: int                 # 0-based
    body_end: int                   # 0-based inclusive
    visibility: str = "public"

    @property
    def qualified(self) -> str:
        return "%s.%s" % (self.owner, self.simple)


@dataclass
class NameSpec:
    name: str
    tier: str                       # class / qualified / weak / thin / concept
    ext: int = 0
    inner: int = 0
    test: int = 0
    selffile: int = 0
    mention: int = 0                # 注释/字符串里的提及


def split_line(raw: str, state: tuple) -> tuple[str, str, str, tuple]:
    """把一行拆成代码 / 字符串字面量 / 注释；状态机处理块注释嵌套、三引号、转义。"""
    block_depth, in_triple, in_str, in_char = state
    code, literal, comment = [], [], []
    i, n = 0, len(raw)
    while i < n:
        c = raw[i]
        nxt = raw[i + 1] if i + 1 < n else ""
        if block_depth > 0:
            if c == "/" and nxt == "*":
                block_depth += 1
                comment.append("/*")
                i += 2
                continue
            if c == "*" and nxt == "/":
                block_depth -= 1
                comment.append("*/")
                i += 2
                continue
            comment.append(c)
            i += 1
            continue
        if in_triple:
            if raw[i:i + 3] == '"""':
                in_triple = False
                i += 3
            else:
                literal.append(c)
                i += 1
            continue
        if in_str:
            if c == "\\":
                literal.append(c)
                if nxt:
                    literal.append(nxt)
                i += 2
                continue
            if c == '"':
                in_str = False
                i += 1
                continue
            literal.append(c)
            i += 1
            continue
        if in_char:
            if c == "\\":
                literal.append(c)
                if nxt:
                    literal.append(nxt)
                i += 2
                continue
            if c == "'":
                in_char = False
                i += 1
                continue
            literal.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            comment.append(raw[i:])
            break
        if c == "/" and nxt == "*":
            block_depth = 1
            comment.append("/*")
            i += 2
            continue
        if raw[i:i + 3] == '"""':
            in_triple = True
            i += 3
            continue
        if c == '"':
            in_str = True
            i += 1
            continue
        if c == "'":
            in_char = True
            i += 1
            continue
        code.append(c)
        i += 1
    return "".join(code), "".join(literal), "".join(comment), (block_depth, in_triple, in_str, in_char)


def classify(rel: str) -> tuple[str | None, str]:
    parts = rel.split("/")
    module = parts[0] if len(parts) > 2 else None
    scope = "data"
    if "src" in parts:
        i = parts.index("src")
        if i + 1 < len(parts) and parts[i + 1] in ("main", "test", "androidTest"):
            scope = parts[i + 1]
    return module, scope


def read_text(path: str) -> list[str]:
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read().splitlines()


def collect_files(root: str) -> tuple[list[SourceFile], list[tuple[str, list[str]]]]:
    """只读遍历。返回 (kt 源码, 登记表候选)。不存在/无源码 ⇒ 抛错，防「没扫到当零引用」。"""
    kt_files: list[SourceFile] = []
    data_files: list[tuple[str, list[str]]] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in sorted(filenames):
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root).replace("\\", "/")
            ext = os.path.splitext(fn)[1].lower()
            if ext == KT_EXT:
                lines = read_text(path)
                sf = SourceFile(rel=rel, path=path, module=classify(rel)[0], scope=classify(rel)[1], lines=lines)
                state = (0, False, False, False)
                for raw in lines:
                    c, l, m, state = split_line(raw, state)
                    sf.code.append(c)
                    sf.literal.append(l)
                    sf.comment.append(m)
                kt_files.append(sf)
            elif ext in DATA_EXTS or (ext == ".md" and any(h in fn.lower() for h in DATA_NAME_HINTS)):
                if os.path.getsize(path) <= 2 * 1024 * 1024:
                    data_files.append((rel, read_text(path)))
    if not kt_files:
        raise FileNotFoundError("源码根下没有 .kt 文件：%s（拒绝把「没扫到」当「零引用」）" % root)
    return kt_files, data_files


def brace_span(code: list[str], start: int, lookahead: int) -> tuple[int, int]:
    """从 start 起 lookahead 行内找第一个 '{'，大括号配对求结束行（0-based, inclusive）。"""
    for j in range(start, min(len(code), start + lookahead)):
        if "{" not in code[j]:
            continue
        depth = 0
        begun = False
        for i in range(j, min(len(code), j + 6000)):
            for ch in code[i]:
                if ch == "{":
                    depth += 1
                    begun = True
                elif ch == "}":
                    depth -= 1
            if begun and depth <= 0:
                return start, i
        return start, min(len(code) - 1, j)
    return start, start


def function_body_span(sf: SourceFile, start: int) -> tuple[int, int]:
    """函数体范围：块状体用大括号配对；表达式体 / 多行签名覆盖到下一个声明之前。

    Kotlin 里 `private fun hushed(...): Boolean =` 换行之后才写真正的调用，
    只看定义行会把证据行漏掉（437 实仓 B2 的根因，QuietCardGate.kt:235-236）。
    """
    code = sf.code
    n = len(code)
    first_brace = None
    for j in range(start, min(n, start + 20)):
        if j > start and (FUN_RE.match(code[j]) or DECL_RE.match(code[j])
                          or code[j].strip().startswith("}")):
            break
        if "{" in code[j]:
            first_brace = j
            break
    if first_brace is not None:
        depth = 0
        for i in range(first_brace, min(n, first_brace + 6000)):
            depth += code[i].count("{") - code[i].count("}")
            if depth <= 0:
                return start, i
        return start, first_brace
    end = start
    for j in range(start + 1, min(n, start + 12)):
        if FUN_RE.match(code[j]) or DECL_RE.match(code[j]) or code[j].strip().startswith("}"):
            break
        end = j
    return start, end


def parse_declarations(files: list[SourceFile]) -> list[Decl]:
    """顶层声明：花括号深度 0 且不在主构造括号里。

    Kotlin `data class FileOpEntry(` 用圆括号主构造、不带 {...}，
    只数花括号会把它的成员 val 误判成顶层声明 —— 圆括号深度一并追踪。
    """
    out: list[Decl] = []
    for sf in files:
        depth = 0
        paren = 0
        for idx, code in enumerate(sf.code):
            m = DECL_RE.match(code)
            if m and depth == 0 and paren == 0:
                mods, kind, name = m.group(1), m.group(2), m.group(3)
                vis = "internal" if "internal" in mods else (
                    "private" if "private" in mods else ("protected" if "protected" in mods else "public")
                )
                bs, be = brace_span(sf.code, idx, 3)
                out.append(Decl(name=name, kind=kind, visibility=vis, file=sf.rel,
                                line=idx + 1, body_start=bs, body_end=be))
            depth += code.count("{") - code.count("}")
            paren = max(0, paren + code.count("(") - code.count(")"))
    return out


def public_methods(sf: SourceFile, decl: Decl) -> list[str]:
    """声明体内 depth==1 的非 private/internal/protected fun。"""
    out: list[str] = []
    if decl.body_end <= decl.body_start:
        return out
    depth = 1
    for i in range(decl.body_start + 1, decl.body_end + 1):
        code = sf.code[i]
        m = FUN_RE.match(code)
        if m and depth == 1:
            mods = m.group(1)
            if not any(w in mods for w in ("private", "internal", "protected")):
                out.append(m.group(2))
        depth += code.count("{") - code.count("}")
    return out


def parse_functions(files: list[SourceFile], decls: list[Decl]) -> list[Function]:
    by_file: dict[str, list[Decl]] = {}
    for d in decls:
        by_file.setdefault(d.file, []).append(d)
    out: list[Function] = []
    for sf in files:
        owners = by_file.get(sf.rel, [])
        for idx, code in enumerate(sf.code):
            m = FUN_RE.match(code)
            if not m:
                continue
            mods, simple = m.group(1), m.group(2)
            owner = os.path.splitext(os.path.basename(sf.rel))[0]
            for d in owners:
                if d.body_start < idx <= d.body_end and d.kind in ("class", "object", "interface"):
                    owner = d.name
                    break
            bs, be = function_body_span(sf, idx)
            vis = "internal" if "internal" in mods else ("private" if "private" in mods else "public")
            out.append(Function(simple=simple, owner=owner, file=sf.rel, line=idx + 1,
                                body_start=bs, body_end=be, visibility=vis))
    return out


# ────────────────────────────── 目标与查名 ──────────────────────────────

def resolve_target(target: str, files: list[SourceFile], decls: list[Decl]) -> dict:
    """目标可以是文件名、类名、限定方法名；找不到 ⇒ 抛错（不许报假零引用）。"""
    clean = target.strip().replace("\\", "/")
    stem = os.path.splitext(os.path.basename(clean))[0]
    is_path = ("." in os.path.basename(clean)) or "/" in clean

    # ① 精确类名 / 限定方法名
    if not is_path and "." in clean:
        owner, method = clean.rsplit(".", 1)
        matches = [d for d in decls if d.name == owner]
        if matches:
            return {"mode": "method", "owner": owner, "method": method,
                    "defs": [decl_dict(d) for d in matches], "def_files": {d.file for d in matches},
                    "def_module": classify(matches[0].file)[0], "stem": stem}
    if not is_path:
        matches = [d for d in decls if d.name == clean]
        if matches:
            return {"mode": "class", "defs": [decl_dict(d) for d in matches],
                    "def_files": {d.file for d in matches},
                    "def_module": classify(matches[0].file)[0], "stem": clean}

    # ② 文件名 / 概念名（a 案例：FileOpLedger 无同名类）
    cands = [sf for sf in files if os.path.basename(sf.rel) == os.path.basename(clean)
             or sf.rel.endswith(clean) or (not is_path and os.path.splitext(os.path.basename(sf.rel))[0] == clean)]
    if not cands and not is_path:
        cands = [sf for sf in files if clean in sf.rel]
    if not cands:
        raise LookupError(
            "未找到定义：%s —— 既不是顶层声明，也不是源码文件；拒绝给出「零引用」结论" % target
        )
    cands.sort(key=lambda s: (s.scope != "main", len(s.rel)))
    chosen = cands[0]
    inner = [d for d in decls if d.file == chosen.rel]
    if not inner:
        raise LookupError("文件 %s 内没有顶层声明，无法展开三步规矩" % chosen.rel)
    return {"mode": "file", "defs": [decl_dict(d) for d in inner],
            "def_files": {chosen.rel, }, "def_module": classify(chosen.rel)[0],
            "stem": stem, "fallback_note": "目标无同名顶层声明，按同名源文件展开" if not is_path else ""}


def decl_dict(d: Decl) -> dict:
    return {"name": d.name, "kind": d.kind, "visibility": d.visibility,
            "file": d.rel if hasattr(d, "rel") else d.file, "line": d.line}


def is_thin(name: str) -> bool:
    return any(p.match(name) for p in THIN_PATTERNS)


def build_names(target: dict, files: list[SourceFile], all_decls: list[Decl]) -> list[NameSpec]:
    """三步规矩的查名清单：类名 → 公开方法名 → 薄封装。"""
    by_rel = {sf.rel: sf for sf in files}
    by_file: dict[str, list[Decl]] = {}
    for d in all_decls:
        by_file.setdefault(d.file, []).append(d)
    specs: dict[str, NameSpec] = {}

    def add(name: str, tier: str) -> None:
        if not name or name in specs:
            return
        specs[name] = NameSpec(name=name, tier=tier)
        if tier in ("class", "qualified", "thin") and is_thin(name.split(".")[-1]):
            specs[name].tier = "thin"

    for d in target["defs"]:
        add(d["name"], "class")
    if target["mode"] == "method":
        add("%s.%s" % (target["owner"], target["method"]), "qualified")
        add(target["method"], "weak")

    for d in target["defs"]:
        sf = by_rel.get(d["file"])
        if not sf:
            continue
        decl = next((x for x in by_file.get(d["file"], [])
                     if x.name == d["name"] and x.line == d["line"]), None)
        if not decl:
            continue
        for m in public_methods(sf, decl):
            add(m, "weak")
            add("%s.%s" % (d["name"], m), "qualified")

    # 顶层 fun 也进清单
    for d in target["defs"]:
        if d["kind"] == "fun":
            add(d["name"], "qualified")

    concept = target.get("stem", "")
    if concept and concept not in specs:
        add(concept, "concept")
    return list(specs.values())


# ────────────────────────────── 扫描与判定 ──────────────────────────────

def build_regex(specs: list[NameSpec], include_concept: bool) -> tuple[re.Pattern | None, dict[str, NameSpec]]:
    names = [s.name for s in specs if include_concept or s.tier != "concept"]
    if not names:
        return None, {}
    names.sort(key=len, reverse=True)  # 长名优先，防 FileOpLog 抢掉 FileOpLog.record
    pat = re.compile(r"\b(?:" + "|".join(re.escape(n) for n in names) + r")\b")
    return pat, {s.name: s for s in specs}


def scan_hits(files: list[SourceFile], specs: list[NameSpec], target: dict) -> tuple[list[dict], list[dict]]:
    """返回 (强命中 code 行清单, 暗引用候选：字符串/注释提及)。"""
    code_re, smap = build_regex(specs, include_concept=False)
    all_re, amap = build_regex(specs, include_concept=True)
    def_lines = {(d["file"], d["line"]) for d in target["defs"]}
    def_files = set(target["def_files"])
    def_module = target["def_module"]
    hits: list[dict] = []
    mentions: list[dict] = []

    for sf in files:
        for idx in range(len(sf.lines)):
            ln = idx + 1
            is_def = (sf.rel, ln) in def_lines
            code, literal, comment = sf.code[idx], sf.literal[idx], sf.comment[idx]
            if code_re:
                for m in code_re.finditer(code):
                    spec = smap.get(m.group(0))
                    if not spec:
                        continue
                    tier = spec.tier
                    if tier in ("weak", "concept") or is_def:
                        spec.mention += 1
                        continue
                    if sf.rel in def_files:
                        bucket = "selffile"
                        spec.selffile += 1
                    elif sf.scope in ("test", "androidTest"):
                        bucket = "test"
                        spec.test += 1
                    elif sf.scope == "main" and sf.module != def_module:
                        bucket = "external"
                        spec.ext += 1
                    elif sf.scope == "main":
                        bucket = "inner"
                        spec.inner += 1
                    else:
                        bucket = "other"
                    hits.append({"name": m.group(0), "tier": tier, "file": sf.rel, "line": ln,
                                 "module": sf.module, "scope": sf.scope, "bucket": bucket,
                                 "text": sf.lines[idx].strip()[:160]})
            if all_re:
                for m in all_re.finditer(literal):
                    spec = amap.get(m.group(0))
                    mentions.append({"type": "string", "name": m.group(0), "file": sf.rel,
                                     "line": ln, "scope": sf.scope,
                                     "tier": spec.tier if spec else "?",
                                     "text": sf.lines[idx].strip()[:160]})
                for m in all_re.finditer(comment):
                    spec = amap.get(m.group(0))
                    mentions.append({"type": "comment", "name": m.group(0), "file": sf.rel,
                                     "line": ln, "scope": sf.scope,
                                     "tier": spec.tier if spec else "?",
                                     "text": sf.lines[idx].strip()[:160]})
    return hits, mentions


MAX_FRONTIER = 120  # 每跳候选名上限，防 hop 爆炸（体感：全仓分析必须秒级）


def build_indirect(files: list[SourceFile], funcs: list[Function], specs: list[NameSpec],
                   target: dict, max_hops: int) -> list[dict]:
    """反向 BFS：函数体内含目标名 ⇒ 该函数是薄封装（hop1）；再找谁调它，逐跳上溯。

    每跳把候选名合成两条大正则（限定名 / 调用形），每行只扫两次，
    避免「候选名 × 函数 × 行」的三重循环（初版实仓跑到 9m49s 的根因）。
    """
    by_file = {sf.rel: sf for sf in files}
    class_names = {s.name for s in specs if s.tier in ("class", "thin")}
    strong = [s.name for s in specs if s.tier in ("class", "qualified", "thin")]
    def_lines = {(d["file"], d["line"]) for d in target["defs"]}
    frontier: dict[str, list[int]] = {n: [] for n in strong}
    seen: set[str] = set()
    items: list[dict] = []

    for hop in range(1, max_hops + 1):
        if not frontier:
            break
        names = sorted(frontier.keys(), key=len, reverse=True)[:MAX_FRONTIER]
        qnames = [n for n in names if "." in n or n in class_names]
        snames = [n for n in names if n not in qnames]
        q_re = re.compile(r"\b(" + "|".join(re.escape(n) for n in qnames) + r")\b") if qnames else None
        s_re = re.compile(r"(?:\b|\.)(" + "|".join(re.escape(n) for n in snames) + r")\s*\(") if snames else None
        new_frontier: dict[str, list[int]] = {}
        for f in funcs:
            if f.qualified in seen or f.file in target["def_files"]:
                # 定义文件自身的函数不算「薄封装」：它已在 ② 直接命中里列出，
                # 混进间接链只会把关键节点淹没（独立评审 S5）。
                continue
            sf = by_file.get(f.file)
            if sf is None:
                continue
            evidence: list[dict] = []
            for i in range(f.body_start, min(f.body_end + 1, len(sf.code))):
                if (f.file, i + 1) in def_lines:
                    continue
                line = sf.code[i]
                found: set[str] = set()
                if q_re:
                    found.update(m.group(1) for m in q_re.finditer(line))
                if s_re:
                    found.update(m.group(1) for m in s_re.finditer(line))
                for name in found:
                    if name not in frontier:
                        continue
                    evidence.append({"line": i + 1, "via": name})
                    for owner_idx in frontier[name]:
                        items[owner_idx].setdefault("callers", []).append(
                            {"file": f.file, "line": i + 1, "func": f.qualified, "hop": hop}
                        )
                if len(evidence) >= 12:
                    break
            if not evidence:
                continue
            idx = len(items)
            items.append({"hop": hop, "func": f.qualified, "simple": f.simple,
                          "owner": f.owner, "file": f.file, "line": f.line,
                          "evidence": evidence, "via_names": sorted({e["via"] for e in evidence}),
                          "callers": []})
            seen.add(f.qualified)
            if f.simple not in STOPWORDS and len(f.simple) >= 4:
                new_frontier.setdefault(f.simple, []).append(idx)
                new_frontier.setdefault(f.qualified, []).append(idx)
        frontier = new_frontier
    for it in items:
        it["callers"] = it["callers"][:8]
    return items


def scan_dark(files: list[SourceFile], data_files: list[tuple[str, list[str]]],
              specs: list[NameSpec], target: dict, mentions: list[dict]) -> list[dict]:
    """暗引用只认高辨识度名字。

    裸方法名（decide / active / record 这类英文常用词）**不进**暗引用匹配：
    否则许可证文本、strings.xml 里的普通单词会被当成「登记表钉名」，
    报出一堆假阳性（437 首次实跑在 GPL-3.0.txt:579 抓到 "decide"）。
    """
    out: list[dict] = []
    precise = [s.name for s in specs if s.tier in ("class", "qualified", "thin", "concept")]
    precise += [os.path.basename(f) for f in target["def_files"]]
    precise += [os.path.splitext(os.path.basename(f))[0] for f in target["def_files"]]
    names = sorted({n for n in precise if n}, key=len, reverse=True)
    name_set = set(names)

    for m in mentions:
        if m["type"] == "string" and m["name"] in name_set:
            where = ("测试门禁断言" if m.get("scope") in ("test", "androidTest")
                     else "生产代码字符串（日志文案 / 按名索引）")
            out.append({"type": "string", "file": m["file"], "line": m["line"],
                        "detail": "%s 钉住「%s」：改名或收窄可见性会让这里变红/编译失败"
                                  % (where, m["name"]),
                        "text": m["text"]})
    # 常量表 / 登记表（机器读的非源码文件）
    table_re = re.compile(r"|".join(re.escape(n) for n in names)) if names else None
    if table_re:
        for rel, lines in data_files:
            for i, line in enumerate(lines, 1):
                m = table_re.search(line)
                if m:
                    out.append({"type": "table", "file": rel, "line": i,
                                "detail": "常量表/登记表按名钉住「%s」：改名要同步改这张表" % m.group(0),
                                "text": line.strip()[:160]})
    # 反射：全仓特征汇总成一条（仓级通用风险，不等于本目标被引用）；
    # 同行与目标名共现的才单独升为「高度可疑」。
    refl_total = 0
    refl_samples: list[str] = []
    for sf in files:
        for idx, code in enumerate(sf.code):
            if not REFLECTION_RE.search(code):
                continue
            refl_total += 1
            if len(refl_samples) < 3:
                refl_samples.append("%s:%d" % (sf.rel, idx + 1))
            if any(n in code for n in names):
                out.append({"type": "reflection", "file": sf.rel, "line": idx + 1,
                            "detail": "反射特征与目标同名共现：高度可疑暗引用",
                            "text": sf.lines[idx].strip()[:160]})
    if refl_total:
        out.insert(0, {"type": "reflection", "file": "(全仓汇总)", "line": 0,
                       "detail": "全仓 %d 处反射/按名取类特征（例：%s）；静态检索不可达 ⇒ 可能有暗引用。"
                                 "这是仓级通用风险，不表示这些行引用了本目标"
                                 % (refl_total, ", ".join(refl_samples)),
                       "text": ""})
    # 字符串拼接拼名
    for sf in files:
        for idx, code in enumerate(sf.code):
            for m in CONCAT_RE.finditer(code):
                joined = m.group(1) + m.group(2)
                if any(n and n in joined for n in names):
                    out.append({"type": "concat", "file": sf.rel, "line": idx + 1,
                                "detail": "字符串拼接拼出「%s」：按名 grep 查不到" % joined,
                                "text": sf.lines[idx].strip()[:160]})
    # 去重（同文件同行同类型只留一条）
    seen: set[tuple] = set()
    uniq: list[dict] = []
    for d in out:
        key = (d["type"], d["file"], d["line"])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(d)
    return uniq


def summarize_dark(dark: list[dict]) -> dict:
    c: dict[str, int] = {}
    for d in dark:
        c[d["type"]] = c.get(d["type"], 0) + 1
    return c


def collect_conflicts(groups: dict, target: dict,
                      data_files: list[tuple[str, list[str]]]) -> list[str]:
    """把「零外部引用 ⇒ internal 候选」的反证找齐，不许把候选说成可直接收窄。

    437 独立评审 S1：Kotlin 的 internal 以编译单元为界，三类证据会推翻收窄——
    ① :app 测试跨模块按名调用；② 同模块 public 签名暴露目标嵌套类型；
    ③ public 面登记表另有权威 verdict。三者都在本工具自己的 ②③④ 输出里，
    必须在「判读」里并列，不能只留口径字面。
    """
    out: list[str] = []
    def_module = target["def_module"]
    cross = [h for h in groups.get("test", []) if h.get("module") != def_module]
    if cross:
        out.append("跨模块测试按名引用 %d 处（%d 个文件）：把这些测试编译单元排除在 internal "
                   "之外不成立，收窄会让 :app 测试编译失败"
                   % (len(cross), len({h["file"] for h in cross})))
    for d in target["defs"]:
        tname = d["name"]
        for h in groups.get("inner", []) + groups.get("external", []):
            text = h.get("text", "")
            if "fun " in text and (tname + ".") in text:
                out.append("同模块 public 签名暴露目标嵌套类型：%s:%d  %s"
                           % (h["file"], h["line"], text[:90]))
                break
    for rel, lines in data_files:
        if not rel.endswith("public-surface.json"):
            continue
        blob = "\n".join(lines)
        if any(d["name"] in blob for d in target["defs"]) and (
            "external_refs" in blob or "不能直接改 internal" in blob or "public 必要" in blob
        ):
            out.append("登记表 %s 另有权威判定（external_refs / public 必要 / 不能直接改 internal）："
                       "与本次候选结论冲突，收窄可见性前须人工复核" % rel)
            break
    return out


# ────────────────────────────── 主分析 ──────────────────────────────

_REPO_CACHE: dict[str, tuple] = {}


def invalidate(root: str) -> None:
    """夹具变异后必须显式失效缓存，否则会拿旧源码判绿。"""
    _REPO_CACHE.pop(root, None)


def load_repo(root: str, fresh: bool = False) -> tuple:
    """一次扫描复用给同一 root 的多个目标（省掉重复全仓遍历）。"""
    if not fresh and root in _REPO_CACHE:
        return _REPO_CACHE[root]
    files, data_files = collect_files(root)
    decls = parse_declarations(files)
    funcs = parse_functions(files, decls)
    value = (files, data_files, decls, funcs)
    _REPO_CACHE[root] = value
    return value


def analyze(root: str, target: str, max_hops: int = DEFAULT_MAX_HOPS, fresh: bool = False) -> dict:
    files, data_files, decls, funcs = load_repo(root, fresh=fresh)
    t = resolve_target(target, files, decls)
    specs = build_names(t, files, decls)
    hits, mentions = scan_hits(files, specs, t)
    indirect = build_indirect(files, funcs, specs, t, max_hops)
    dark = scan_dark(files, data_files, specs, t, mentions)

    groups = {"external": [], "inner": [], "selffile": [], "test": [], "other": []}
    for h in hits:
        groups.setdefault(h["bucket"], []).append(h)
    tests: dict[str, dict] = {}
    for h in groups["test"]:
        row = tests.setdefault(h["file"], {"file": h["file"], "hits": 0, "names": set()})
        row["hits"] += 1
        row["names"].add(h["name"])
    tests_out = [{"file": r["file"], "hits": r["hits"], "names": sorted(r["names"])}
                 for r in sorted(tests.values(), key=lambda r: (-r["hits"], r["file"]))]
    # 「提到」还有弱的一半：测试里把名字写进字符串/注释（src.contains("X.") 这类门禁锚点）
    tests_weak = sorted({m["file"] for m in mentions
                         if m.get("scope") in ("test", "androidTest")
                         and m.get("tier") not in (None, "weak", "?")})

    ext_count = len(groups["external"])
    public_defs = [d for d in t["defs"] if d["visibility"] == "public"]
    internal_candidate = ext_count == 0 and bool(public_defs) and bool(specs)
    conflicts = collect_conflicts(groups, t, data_files)
    hints: list[str] = []
    if ext_count == 0:
        if public_defs:
            hints.append("零外部引用（按名字；%s）" % SCOPE_NOTE)
            if internal_candidate:
                hints.append("可能是 internal 候选：顶层 public 声明 %d 个，模块外按名引用 0；"
                             "同模块内部 %d 处引用不受 internal 影响"
                             % (len(public_defs), len(groups["inner"])))
                if conflicts:
                    hints.append("⚠ 但存在 %d 条冲突证据：「候选」≠「可直接收 internal」，"
                                 "收窄前必须人工复核（逐条见 verdict.conflicts）" % len(conflicts))
        else:
            hints.append("按名字外部引用 0（该目标本就是 internal/private，不进入 internal 候选判定）")
    else:
        hints.append("外部生产引用 %d 处：收 internal 会编译失败" % ext_count)
    if t.get("fallback_note"):
        hints.append(t["fallback_note"])

    return {
        "target": target, "root": root, "mode": t["mode"],
        "definitions": t["defs"], "def_files": sorted(t["def_files"]),
        "def_module": t["def_module"], "fallback_note": t.get("fallback_note", ""),
        "names_checked": [{"name": s.name, "tier": s.tier, "external": s.ext,
                           "inner": s.inner, "test": s.test, "selffile": s.selffile}
                          for s in specs],
        "direct": {k: v for k, v in groups.items()},
        "indirect": indirect,
        "tests": tests_out,
        "tests_weak": tests_weak,
        "dark": dark,
        "dark_summary": summarize_dark(dark),
        "verdict": {"external_refs": ext_count,
                    "internal_candidate": internal_candidate,
                    "candidate_status": "contested（有冲突证据，需人工复核）" if conflicts else "clean",
                    "conflicts": conflicts,
                    "names_checked_count": len(specs),
                    "scope_note": SCOPE_NOTE,
                    "hints": hints},
        "scanned": {"kt_files": len(files), "declarations": len(decls),
                    "functions": len(funcs), "data_files": len(data_files),
                    "max_hops": max_hops},
    }


# ────────────────────────────── 文本输出 ──────────────────────────────

def render_text(r: dict, limit: int = 22) -> str:
    out: list[str] = []
    add = out.append
    add("=" * 78)
    add("影响面反查｜目标：%s    基线根：%s" % (r["target"], r["root"]))
    add("扫描：%d 个 .kt / %d 个顶层声明 / %d 个函数 / %d 个登记表候选；间接链 ≤%d 跳" % (
        r["scanned"]["kt_files"], r["scanned"]["declarations"],
        r["scanned"]["functions"], r["scanned"]["data_files"], r["scanned"]["max_hops"]))
    add("只读 · 不联网 · 不改仓库（临时目录仅 --selftest 使用）")
    add("=" * 78)

    add("")
    add("① 定义")
    if r["fallback_note"]:
        add("  ⚠ %s" % r["fallback_note"])
    add("  模式：%s ｜ 定义模块：%s ｜ 定义文件：%s" % (
        r["mode"], r["def_module"], ", ".join(r["def_files"])))
    for d in r["definitions"]:
        add("    %s:%d  %s %s  (%s)" % (d["file"], d["line"], d["kind"], d["name"], d["visibility"]))
    strong = [n["name"] for n in r["names_checked"] if n["tier"] in ("class", "qualified", "thin")]
    weak = [n["name"] for n in r["names_checked"] if n["tier"] == "weak"]
    thin = [n["name"] for n in r["names_checked"] if n["tier"] == "thin"]
    add("  公开方法名（弱命中，只统计）：%s" % (", ".join(weak) if weak else "（无）"))
    add("  薄封装（XxxLog/XxxStore/record* 等）：%s" % (", ".join(thin) if thin else "（无匹配）"))

    add("")
    add("② 调用点 —— 三步规矩：类名 → 公开方法名 → 薄封装")
    add("  口径：%s" % SCOPE_NOTE)
    add("  【查了哪些名字】共 %d 个，逐个列出（不许只输出 0）：" % len(r["names_checked"]))
    add("    %-34s %-10s %6s %6s %6s" % ("名字", "档位", "外部", "同模块", "测试"))
    for n in r["names_checked"]:
        add("    %-34s %-10s %6d %6d %6d" % (
            n["name"][:34], n["tier"], n["external"], n["inner"], n["test"]))
    d = r["direct"]
    add("  【直接命中】外部生产 %d ｜ 同模块内部 %d ｜ 定义文件自引用 %d ｜ 测试 %d" % (
        len(d["external"]), len(d["inner"]), len(d["selffile"]), len(d["test"])))
    for label, key in (("外部生产", "external"), ("同模块内部", "inner")):
        rows = d[key]
        if not rows:
            add("    %s：0 条" % label)
            continue
        for h in rows[:limit]:
            add("    %s  %s:%d  [%s]  %s" % (label, h["file"], h["line"], h["name"], h["text"][:90]))
        if len(rows) > limit:
            add("    … 还有 %d 条（完整清单见 --json）" % (len(rows) - limit))
    def emit_chain(it: dict) -> None:
        add("    hop%d  %s  %s:%d  经 %s" % (
            it["hop"], it["func"], it["file"], it["line"], ", ".join(it["via_names"][:4])))
        for ev in it["evidence"][:2]:
            add("          证据 %s:%d  ← %s" % (it["file"], ev["line"], ev["via"]))
        for c in it["callers"][:3]:
            add("          被调用于 %s:%d  (%s)" % (c["file"], c["line"], c["func"]))

    by_hop: dict[int, list[dict]] = {}
    for it in r["indirect"]:
        by_hop.setdefault(it["hop"], []).append(it)
    max_hop = max(by_hop) if by_hop else 0
    add("  【②-B 间接链（薄封装，不参与外部引用计数）】共 %d 个函数、%d 跳" % (
        len(r["indirect"]), max_hop))
    # 第 1 跳按「薄封装优先」（通用规则，不写死任何案例函数名）；
    # 链末端单独列出，保证多跳上游函数不会被前面的节点淹没（独立评审 S3）。
    first = sorted(by_hop.get(1, []),
                   key=lambda x: (0 if is_thin(x["simple"]) else 1, x["line"]))
    add("    ── 第 1 跳（直接包装目标，薄封装优先）：%d 个，展示前 12 ──" % len(first))
    for it in first[:12]:
        emit_chain(it)
    for hop in range(2, max_hop):
        rows = sorted(by_hop.get(hop, []), key=lambda x: -len(x["callers"]))[:2]
        if rows:
            add("    ── 第 %d 跳代表（按下游调用点数）──" % hop)
            for it in rows:
                emit_chain(it)
    if max_hop > 1:
        last = sorted(by_hop.get(max_hop, []), key=lambda x: -len(x["callers"]))
        add("    ── 链末端（第 %d 跳）：%d 个，展示前 8 ──" % (max_hop, len(last)))
        for it in last[:8]:
            emit_chain(it)
    # 完整可达链：从第 1 跳沿 callers 正向走，输出「目标 → 薄封装 → … → 上游」。
    # 保底展示：多跳上游节点不会因为「每跳代表前 2」被截断（独立复审的条件项）。
    by_q: dict[str, dict] = {}
    for it in r["indirect"]:
        by_q.setdefault(it["func"], it)
    paths: list[list[dict]] = []

    def walk(item: dict, path: list[dict], visited: set[str]) -> None:
        nxt = [by_q[c["func"]] for c in item.get("callers", [])
               if c.get("func") in by_q and c["func"] not in visited]
        if not nxt or len(path) >= 5:
            paths.append(path)
            return
        for n in nxt[:2]:
            walk(n, path + [n], visited | {n["func"]})

    for root in first[:6]:
        walk(root, [root], {root["func"]})
    uniq_paths: list[list[dict]] = []
    seen_sig: set[tuple] = set()
    for p in paths:
        sig = tuple(x["func"] for x in p)
        if sig in seen_sig:
            continue
        seen_sig.add(sig)
        uniq_paths.append(p)
    add("    ── 完整可达链（目标 → 薄封装 → 上游；最多 6 条，每链≤5 节点）──")
    if not uniq_paths:
        add("      （无可达链）")
    for p in uniq_paths[:6]:
        add("      " + "  →  ".join(
            "%s(%s:%d)" % (x["func"], os.path.basename(x["file"]), x["line"]) for x in p))
    add("  完整清单见 --json（单节点证据≤12 条、调用点≤8 条；不是无限全量）")

    add("")
    add("③ 受影响测试（提到这些名字的测试文件）")
    if not r["tests"]:
        add("    （无）")
    for t in r["tests"][:limit]:
        add("    %s  %d 处  %s" % (t["file"], t["hits"], ", ".join(t["names"][:5])))
    if len(r["tests"]) > limit:
        add("    … 还有 %d 个（完整清单见 --json）" % (len(r["tests"]) - limit))
    weak = r.get("tests_weak", [])
    if weak:
        add("    仅字符串/注释提及（弱命中，但改名同样会让门禁变红）：%d 个文件" % len(weak))
        for f in weak[:8]:
            add("      %s" % f)
        if len(weak) > 8:
            add("      … 还有 %d 个（完整清单见 --json）" % (len(weak) - 8))

    add("")
    add("④ 风险提示 —— 可能有暗引用")
    target_dark = [x for x in r["dark"]
                   if not (x["type"] == "reflection" and x["file"] == "(全仓汇总)")]
    global_dark = [x for x in r["dark"]
                   if x["type"] == "reflection" and x["file"] == "(全仓汇总)"]
    own_summary: dict[str, int] = {}
    for x in target_dark:
        own_summary[x["type"]] = own_summary.get(x["type"], 0) + 1
    add("  目标专属命中分布：%s" % (
        json.dumps(own_summary, ensure_ascii=False) if own_summary else "（无）"))
    labels = {"string": "字符串钉名", "table": "常量表/登记表",
              "reflection": "反射特征", "concat": "字符串拼名"}
    for typ in ("string", "table", "concat", "reflection"):
        rows = [x for x in target_dark if x["type"] == typ]
        if not rows:
            continue
        add("  [%s] %d 条" % (labels[typ], len(rows)))
        for x in rows[:8]:
            add("    %s:%d  %s" % (x["file"], x["line"], x["detail"]))
        if len(rows) > 8:
            add("    … 还有 %d 条（完整清单见 --json）" % (len(rows) - 8))
    if not target_dark:
        add("  （未发现目标专属暗引用特征；注意：静态检索无法证明「绝对没有引用」）")
    if global_dark:
        add("  [仓级背景 · 非目标专属，不参与上面的命中数]")
        for x in global_dark:
            add("    %s" % x["detail"])

    add("")
    add("判读")
    v = r["verdict"]
    add("  外部引用（按名字）：%d ｜ 查名数：%d ｜ internal 候选：%s ｜ 候选状态：%s" % (
        v["external_refs"], v["names_checked_count"],
        "是" if v["internal_candidate"] else "否", v.get("candidate_status", "clean")))
    for h in v["hints"]:
        add("  · %s" % h)
    if v.get("conflicts"):
        add("  ⚠ 冲突证据（「候选」≠「可直接收 internal」，收窄前须人工复核）：")
        for i, c in enumerate(v["conflicts"], 1):
            add("     %d) %s" % (i, c))
    add("=" * 78)
    return "\n".join(out)


# ────────────────────────────── 离线自测 ──────────────────────────────

FIXTURE: dict[str, str] = {
    "app/src/main/java/demo/FileOpLedger.kt": """package demo
internal object FileOpStore {
    fun record(e: String): String = e
}
internal object FileOpLog {
    fun record(tool: String): String = FileOpStore.record(tool)
    fun recent(): Int = 0
}
internal object FileOpRules {
    fun kindOf(tool: String): String = tool
}
""",
    "app/src/main/java/demo/GuardPipeline.kt": """package demo
internal class GuardPipeline {
    private fun finish(spec: String) {
        recordFileOps(spec)
    }
    private fun recordFileOps(spec: String) {
        val kind = FileOpRules.kindOf(spec)
        val entry = FileOpLog.record(spec)
    }
}
""",
    "app/src/main/java/demo/QuietMode.kt": """package demo
internal object QuietMode {
    fun active(grant: String?, now: Long): Boolean = grant != null
    fun stateOf(grant: String?, now: Long): String = "OFF"
    fun cardAllowed(): Boolean = true
}
""",
    "app/src/main/java/demo/QuietCardGate.kt": """package demo
internal class QuietCardGate {
    private fun hushed(grant: String?): Boolean = QuietMode.active(grant, 0L)
    fun onAsk(grant: String?): String {
        if (!hushed(grant)) return "ASK"
        return "DEFER"
    }
    fun stateLine(grant: String?): String = if (QuietMode.active(grant, 0L)) "quiet" else "normal"
}
""",
    "app/src/main/java/demo/RunEngine.kt": """package demo
internal class RunEngine {
    private val quietGate = QuietCardGate()
    fun askUser(req: String): String? {
        if (quietDefer(req)) return null
        return req
    }
    private fun quietDefer(req: String): Boolean {
        val outcome = quietGate.onAsk(null)
        return outcome == "DEFER"
    }
}
""",
    "policy/src/main/java/demo/MediatorDecide.kt": """package demo
object MediatorDecide {
    data class AskAdvice(val allowed: Boolean)
    fun shouldAsk(state: Int): AskAdvice = AskAdvice(true)
    fun budgetFor(state: Int): Int = 3
    fun decide(weights: List<Int>): List<Int> = weights
}
""",
    "policy/src/main/java/demo/MediatorWiring.kt": """package demo
object MediatorWiring {
    fun consultAsk(): MediatorDecide.AskAdvice = MediatorDecide.shouldAsk(0)
    fun orderAdvice(w: List<Int>): List<Int> = MediatorDecide.decide(w)
}
""",
    "app/src/test/java/demo/MediatorDecideGateTest.kt": """package demo
class MediatorDecideGateTest {
    fun g6() {
        assertTrue(src.contains("MediatorDecide."))
    }
    fun directUse() {
        val advice = MediatorDecide.shouldAsk(1)
    }
}
""",
    "app/src/test/java/demo/FileOpLedgerTest.kt": """package demo
class FileOpLedgerTest {
    fun t() { FileOpLog.record("read_file") }
}
""",
    "app/src/main/java/demo/CommentOnly.kt": """package demo
// 这里只在注释里提到 FileOpLedger 与 MediatorDecide，不是调用点
""",
    "tools/public-surface.json": """{
  "registry": [ {"name": "MediatorDecide", "file": "MediatorDecide.kt"} ],
  "verdict": "public 必要 / 不能直接改 internal"
}
""",
    "config/detekt/issue-budget.json": """{
  "entries": ["CyclomaticComplexMethod|FileOpLedger.kt|purposeOf#1"]
}
""",
    # 负例：纯英文文本里的 decide / shouldAsk 是通用词，不许被当成「登记表钉名」
    "docs/plain-english.txt": """The user may decide to continue, or shouldAsk later.
""",
}


def write_fixture(root: str, files: dict[str, str]) -> None:
    for rel, body in files.items():
        path = os.path.join(root, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(body)


def chain_has(indirect: list[dict], func_suffix: str, via: str | None = None) -> bool:
    for it in indirect:
        if it["func"].endswith(func_suffix) and (via is None or via in it["via_names"]):
            return True
    return False


def selftest() -> int:
    results: list[tuple[str, bool, str]] = []

    def check(label: str, cond: bool, detail: str = "") -> None:
        results.append((label, bool(cond), detail))

    with tempfile.TemporaryDirectory(prefix="impact-selftest-") as tmp:
        # ── A 组：离线夹具（自包含，不读真实仓库） ──────────────────────
        write_fixture(tmp, FIXTURE)
        a = analyze(tmp, "FileOpLedger")
        b = analyze(tmp, "QuietMode")
        c = analyze(tmp, "MediatorDecide")

        check("A1 夹具·a 按文件展开（无同名类）",
              a["mode"] == "file" and len(a["definitions"]) == 3 and bool(a["fallback_note"]),
              "defs=%d mode=%s" % (len(a["definitions"]), a["mode"]))
        check("A2 夹具·a 报出 FileOpLog.record → GuardPipeline.recordFileOps 间接调用",
              chain_has(a["indirect"], "GuardPipeline.recordFileOps", "FileOpLog.record")
              and any(x["file"].endswith("GuardPipeline.kt") and x["line"] == 4
                      for it in a["indirect"] if it["func"].endswith("recordFileOps")
                      for x in it["callers"]),
              "indirect=%s" % [i["func"] for i in a["indirect"]])
        check("A3 夹具·b 报出 RunEngine.askUser（四跳）",
              chain_has(b["indirect"], "RunEngine.askUser"),
              "hops=%s" % [(i["hop"], i["func"]) for i in b["indirect"]])
        check("A4 夹具·c 零外部引用 + 查名清单非空 + internal 候选",
              c["verdict"]["external_refs"] == 0
              and c["verdict"]["names_checked_count"] >= 6
              and c["verdict"]["internal_candidate"]
              and any("internal 候选" in h for h in c["verdict"]["hints"]),
              "ext=%d names=%d" % (c["verdict"]["external_refs"], c["verdict"]["names_checked_count"]))
        check("A5 夹具·负例：注释里的名字不算外部引用（CommentOnly.kt）",
              c["verdict"]["external_refs"] == 0
              and not any("CommentOnly.kt" in h["file"] for h in c["direct"]["external"]))
        check("A6 夹具·暗引用：字符串钉名 + 登记表 + 预算表都在",
              any(x["type"] == "string" and "MediatorDecideGateTest" in x["file"] for x in c["dark"])
              and any(x["type"] == "table" and "public-surface.json" in x["file"] for x in c["dark"])
              and any(x["type"] == "table" and "issue-budget.json" in x["file"] for x in a["dark"]),
              json.dumps(c["dark_summary"], ensure_ascii=False))
        try:
            analyze(tmp, "NoSuchSymbolXYZ")
            check("A7 夹具·负例：未找到定义不得报零引用", False, "竟然没抛错")
        except LookupError as exc:
            check("A7 夹具·负例：未找到定义不得报零引用", "未找到定义" in str(exc), str(exc)[:80])
        check("A8 夹具·负例：英文通用词 decide/shouldAsk 不算登记表钉名",
              not any(x["type"] == "table" and "plain-english.txt" in x["file"]
                      for x in c["dark"]),
              "tables=%s" % [x["file"] for x in c["dark"] if x["type"] == "table"])
        check("A9 夹具·c 候选不得是干净结论（三类反证必须并列）",
              c["verdict"]["candidate_status"].startswith("contested")
              and len(c["verdict"]["conflicts"]) >= 3
              and any("跨模块测试" in x for x in c["verdict"]["conflicts"])
              and any("签名暴露" in x for x in c["verdict"]["conflicts"])
              and any("登记表" in x for x in c["verdict"]["conflicts"]),
              "conflicts=%d" % len(c["verdict"]["conflicts"]))

        # ── M 组：变异（每个变异都必须让对应判据翻转，防恒真） ──────────
        mutations = [
            ("M1 删掉 recordFileOps 内的 FileOpLog.record ⇒ a 的关键链必须消失",
             "app/src/main/java/demo/GuardPipeline.kt",
             "val entry = FileOpLog.record(spec)", "val entry = spec",
             lambda: not chain_has(analyze(tmp, "FileOpLedger")["indirect"],
                                   "GuardPipeline.recordFileOps", "FileOpLog.record")),
            ("M2 把 hushed 内的 QuietMode.active 改成注释 ⇒ b 的 askUser 链必须断",
             "app/src/main/java/demo/QuietCardGate.kt",
             "private fun hushed(grant: String?): Boolean = QuietMode.active(grant, 0L)",
             "private fun hushed(grant: String?): Boolean = true /* QuietMode.active */",
             lambda: not chain_has(analyze(tmp, "QuietMode")["indirect"], "RunEngine.askUser")),
            ("M3 在 app 生产代码加一处 MediatorDecide 调用 ⇒ c 的零引用必须翻红",
             "app/src/main/java/demo/CommentOnly.kt",
             "// 这里只在注释里提到 FileOpLedger 与 MediatorDecide，不是调用点",
             "fun externalUse(): Boolean = MediatorDecide.shouldAsk(1)",
             lambda: analyze(tmp, "MediatorDecide")["verdict"]["external_refs"] > 0
             and not analyze(tmp, "MediatorDecide")["verdict"]["internal_candidate"]),
            ("M4 改掉登记表里的名字 ⇒ c 的 table 暗引用必须消失",
             "tools/public-surface.json",
             '"name": "MediatorDecide", "file": "MediatorDecide.kt"',
             '"name": "SomethingElse", "file": "SomethingElse.kt"',
             lambda: not any(x["type"] == "table" and "public-surface.json" in x["file"]
                             for x in analyze(tmp, "MediatorDecide")["dark"])),
            ("M5 把 MediatorDecide 改成 internal ⇒ 不再提示 internal 候选",
             "policy/src/main/java/demo/MediatorDecide.kt",
             "object MediatorDecide {", "internal object MediatorDecide {",
             lambda: not analyze(tmp, "MediatorDecide")["verdict"]["internal_candidate"]),
            ("M6 删掉 askUser 里的 quietDefer 调用 ⇒ b 的 askUser 不再可达",
             "app/src/main/java/demo/RunEngine.kt",
             "if (quietDefer(req)) return null", "if (false) return null",
             lambda: not chain_has(analyze(tmp, "QuietMode")["indirect"], "RunEngine.askUser")),
        ]
        for label, rel, old, new, assertion in mutations:
            original = FIXTURE[rel]
            if old not in original:
                check(label, False, "变异锚点没找到：%s" % old)
                continue
            write_fixture(tmp, {rel: original.replace(old, new, 1)})
            invalidate(tmp)  # 变异后必须让源码缓存失效，否则会拿旧夹具判绿
            try:
                flipped = assertion()
            except Exception as exc:  # noqa: BLE001
                flipped = False
                label += "（异常 %s）" % type(exc).__name__
            check(label, flipped)
            write_fixture(tmp, {rel: original})  # 还原，保证各变异互不影响
            invalidate(tmp)
        # 还原后基准必须重新全绿（证明变异没污染夹具）
        check("M7 还原后基准三案例仍全绿",
              chain_has(analyze(tmp, "FileOpLedger")["indirect"], "GuardPipeline.recordFileOps", "FileOpLog.record")
              and chain_has(analyze(tmp, "QuietMode")["indirect"], "RunEngine.askUser")
              and analyze(tmp, "MediatorDecide")["verdict"]["external_refs"] == 0)

    # ── B 组：真实仓库三案例（只读回归；不存在则显式 SKIP，不冒充绿） ──
    live_marker = os.path.join(ROOT, "app", "src", "main", "java", "com", "example",
                               "zhengdao", "baopu", "FileOpLedger.kt")
    if os.path.isfile(live_marker):
        la = analyze(ROOT, "FileOpLedger")
        lb = analyze(ROOT, "QuietMode")
        lc = analyze(ROOT, "MediatorDecide")
        # 独立从源码数调用行：427 接线 A 域让 GuardPipeline.kt 整体下移 23 行
        # （649→672、979→1002、992→1015），写死行号会随基线漂移误报。
        gp_path = os.path.join(ROOT, "app", "src", "main", "java", "com",
                               "example", "zhengdao", "baopu", "agent", "GuardPipeline.kt")
        gp_lines = open(gp_path, encoding="utf-8", errors="replace").read().splitlines()
        gp_call = next((i + 1 for i, s in enumerate(gp_lines)
                        if "recordFileOps(" in s and "private fun" not in s), None)
        check("B1 实仓·a FileOpLog.record → GuardPipeline.recordFileOps",
              chain_has(la["indirect"], "GuardPipeline.recordFileOps", "FileOpLog.record")
              and gp_call is not None
              and any("GuardPipeline.kt" in x["file"] and x["line"] == gp_call
                      for it in la["indirect"] if it["func"].endswith("recordFileOps")
                      for x in it["callers"]),
              "独立源码调用行=%s indirect=%d" % (gp_call, len(la["indirect"])))
        check("B2 实仓·b 报出 RunEngine.askUser",
              chain_has(lb["indirect"], "RunEngine.askUser"),
              "hops=%s" % [(i["hop"], i["func"]) for i in lb["indirect"] if "askUser" in i["func"]])
        check("B3 实仓·c 零外部引用 + 查名清单 + internal 候选",
              lc["verdict"]["external_refs"] == 0
              and lc["verdict"]["names_checked_count"] >= 6
              and lc["verdict"]["internal_candidate"]
              and any("internal 候选" in h for h in lc["verdict"]["hints"]),
              "ext=%d names=%d inner=%d" % (lc["verdict"]["external_refs"],
                                            lc["verdict"]["names_checked_count"],
                                            len(lc["direct"]["inner"])))
        check("B4 实仓·c 暗引用实证（登记表/预算表/字符串钉名）",
              any(x["type"] == "table" and "public-surface.json" in x["file"] for x in lc["dark"])
              and any(x["type"] == "table" and "issue-budget.json" in x["file"] for x in la["dark"]))
        check("B5 实仓·c 通用词假阳性已清除（GPL 文本/strings.xml 的 decide）",
              not any(("GPL-3.0.txt" in x["file"] or "values-en/strings" in x["file"])
                      for x in lc["dark"] if x["type"] == "table"),
              "tables=%s" % [x["file"] for x in lc["dark"] if x["type"] == "table"])
        check("B6 实仓·c 反射只汇总成仓级风险，不冒充目标专属引用",
              sum(1 for x in lc["dark"] if x["type"] == "reflection") <= 5
              and any("全仓" in x["detail"] for x in lc["dark"] if x["type"] == "reflection"))
        # 独立评审 S2：B 组不能只复述工具自算字段，必须引入独立 ground truth 对照。
        wiring_path = os.path.join(ROOT, "policy", "src", "main", "java", "com",
                                   "example", "zhengdao", "baopu", "MediatorWiring.kt")
        registry_path = os.path.join(ROOT, "tools", "public-surface.json")
        wiring_src = open(wiring_path, encoding="utf-8", errors="replace").read()
        registry_src = open(registry_path, encoding="utf-8", errors="replace").read()
        check("B7 独立 ground truth：签名暴露 / 登记表权威判定 / 三类反证并列",
              "MediatorDecide.AskAdvice" in wiring_src
              and "external_refs" in registry_src
              and "不能直接改 internal" in registry_src
              and len(lc["verdict"]["conflicts"]) >= 3
              and lc["verdict"]["candidate_status"].startswith("contested")
              and any("跨模块测试" in x for x in lc["verdict"]["conflicts"]),
              "conflicts=%d" % len(lc["verdict"]["conflicts"]))
        test_root = os.path.join(ROOT, "app", "src", "test")
        independent = 0
        for dp, _dn, fns in os.walk(test_root):
            for fn in fns:
                if fn.endswith(".kt"):
                    with open(os.path.join(dp, fn), encoding="utf-8", errors="replace") as fh:
                        independent += fh.read().count("MediatorDecide.")
        tool_tests = sum(t["hits"] for t in lc["tests"])
        check("B8 独立计数对照：跨模块测试按名引用确实存在，③ 强命中数不是工具自报",
              independent >= 30 and tool_tests >= 30 and lc["verdict"]["external_refs"] == 0,
              "独立源码计数=%d 工具③=%d" % (independent, tool_tests))
    else:
        # 独立评审 S2：静默 SKIP 会让「夹具绿」冒充「实仓判据绿」，必须计入结果并判红。
        results.append(("B 组·实仓三案例只读回归（不允许静默 SKIP）", False,
                        "未找到 %s；夹具组绿不等于实仓判据绿" % live_marker))

    passed = sum(1 for _, ok, _ in results if ok)
    print("=" * 78)
    print("impact-check.py --selftest｜离线夹具 + 变异 + 实仓只读回归")
    for label, ok, detail in results:
        print("  [%s] %s%s" % ("PASS" if ok else "FAIL", label,
                               "" if not detail or ok else "  ← " + detail))
    print("-" * 78)
    print("结果：%d/%d 通过%s" % (passed, len(results), "" if passed == len(results) else "  ⇒ 有红"))
    print("=" * 78)
    return 0 if passed == len(results) else 1


# ────────────────────────────── CLI ──────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description="影响面反查（437）：定义 / 调用点 / 受影响测试 / 暗引用")
    ap.add_argument("target", nargs="?", help="文件名 / 类名 / 限定方法名（如 FileOpLedger、QuietMode、MediatorDecide.shouldAsk）")
    ap.add_argument("--root", default=ROOT, help="仓库根（默认脚本所在仓）")
    ap.add_argument("--json", action="store_true", help="输出结构化 JSON（供自测/CI 断言）")
    ap.add_argument("--max-hops", type=int, default=DEFAULT_MAX_HOPS, help="间接链最大跳数（默认 4）")
    ap.add_argument("--selftest", action="store_true", help="离线自测：内置夹具 + 变异 + 实仓只读回归")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.target:
        ap.error("请给一个目标，或用 --selftest")
    try:
        result = analyze(os.path.abspath(args.root), args.target, max(1, min(8, args.max_hops)))
    except (LookupError, FileNotFoundError, OSError) as exc:
        print("::error 影响面反查失败：%s" % exc)
        return 2
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(render_text(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
