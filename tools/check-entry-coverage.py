#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""check-entry-coverage.py —— 入口覆盖清点（373，2026-11-02）。

目的
----
把「有功能没入口」从**靠人肉发现**变成**可重复的清单**。本脚本只清点、只写
报告，**不修改任何生产代码、不删任何文件**——它是清点单，不是改造单。

三张清单
--------
A  **有定义无调用**：app/src/main/java 里顶层公开 object/class/interface，
   生产侧（含声明文件自身，含 Manifest/res XML）**零引用**（词边界计数，剥注释/字符串后）。
   查法按三步（不许只 grep 类名就判零引用）：
     第 1 步 类名：全生产语料词边界检索（排除声明文件本身）；
     第 2 步 公开方法名：类名零命中时，逐个公开成员名再检索（防 import
             别名 / 同包裸调 / 反射式字符串拼名被类名检索漏掉）；
     第 3 步 薄封装名：检索「函数体只委托到该类」的薄封装（工厂/别名函数），
             封装自身是否有人调一并记入证据。
B  **只有测试在调**：生产侧零外部引用，但 app/src/test / app/src/androidTest
   有引用 ⇒ 生产不可达。
C  **已接线可断电**：布尔开关默认关（SharedPreferences getBoolean 默认 false
   / getOrDefault(false)），找到读点与被闸的代码面；「开启路径」＝生产里是否
   存在写 true 的写点或设置页拨动——找不到即入 C。

结论三选一（写在 tools/entry-coverage-conclusions.json，脚本合并进报告）
    wired      接线（谁接）——欠账表，排后续接线批；
    not_urgent 不急（理由）；
    delete     该删（理由）。
**不许留空、不许写待定**：`--strict` 下任何条目缺结论 / 注册表里出现已消失
的符号即退出 1（交付前自证用）；默认模式只标「未裁决」不失败——CI 只报不拦。

口径与边界（写在明处）
--------------------
* 只清点 app/src/main/java 顶层声明（列 0），成员级不进本清单（与
  check-public-surface.py 同一口径）；`internal`/`private` 不算公开面。
* 语料＝app/src/main 下全部 .kt ＋ AndroidManifest.xml ＋ res/**/*.xml；
  proguard-rules.pro 命中只作证据（keep ≠ 可达）；app/src/debug 命中单独记
  「仅 debug 变体引用」，**不计入**生产引用（release 不可达仍应入清单）。
* 注释与字符串里的名字**不算引用**（先剥注释/字符串再计数，防文档性提及
  假绿）；行号与原文按剥前位置保留，证据可点开核对。
* 同名符号（不同包同名类）会互相「救活」——宁可漏报不误杀，结论侧人工复核。
* 可重复：排序全部固定、输出无时间戳，两次运行 diff 必须为空。

用法
----
    python3 tools/check-entry-coverage.py               # 刷新：按现码重算并写 docs/ 两份产物（唯一写盘路径）
    python3 tools/check-entry-coverage.py --strict      # 只读自证：缺结论/陈旧注册/计数漂移即红（不写盘）
    python3 tools/check-entry-coverage.py --selftest    # 负例自测（临时目录，不动仓库）
    python3 tools/check-entry-coverage.py --check       # 只读：逐字节核对 docs 产物与现扫描一致

454 计数锁（2026-11-05）：`--strict` 拿**磁盘上** md/json 的四个计数（顶层公开面／
生产可达）逐个与**当场重算值**对账，不等即红并打印「盘中＝X／重算＝Y」；且
`--strict` 不写盘，跑完工作树必须干净。只读的原因：原实现先 write_text 覆盖两份
产物再判 problems，人手改过的计数当场被洗成正确值 ⇒ 既检不出、又不留证据，还
把工作树改脏（454 实测：旧名册 369/364 下 --strict 绿，却把产物写成 389/384）。
**刷新请走默认模式**（CI 亦走这条）。

退出码：0 = 完成（默认模式永远 0，CI 只报不拦）；1 = --check 不一致，或
        --strict 有未裁决/陈旧/计数漂移；2 = 环境/用法错误。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- 基础工具

KT_RE = re.compile(r"\.kt$", re.IGNORECASE)


def repo_root(explicit: str | None) -> Path:
    root = Path(explicit) if explicit else Path(__file__).resolve().parent.parent
    if not (root / "app" / "src" / "main").is_dir():
        raise SystemExit("[entry-coverage] 不像仓库根：%s（找不到 app/src/main）" % root)
    return root


def strip_kotlin(text: str) -> str:
    """剥掉注释与字符串字面量内容，保持长度与换行不变（行号不漂移）。

    处理：行注释 //、块注释 /* */（Kotlin 可嵌套）、行字符串（含转义）、
    三引号原始字符串、字符字面量。剥掉的内容替换为空格。
    """
    out = []
    i, n = 0, len(text)
    NORMAL, LINE, BLOCK, STR, RAW, CHAR = range(6)
    state = NORMAL
    block_depth = 0
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == NORMAL:
            if c == "/" and nxt == "/":
                state = LINE
                out.append("  ")
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = BLOCK
                block_depth = 1
                out.append("  ")
                i += 2
                continue
            if c == '"' and nxt == '"' and text[i + 2: i + 3] == '"':
                state = RAW
                out.append("   ")
                i += 3
                continue
            if c == '"':
                state = STR
                out.append(" ")
                i += 1
                continue
            if c == "'":
                state = CHAR
                out.append(" ")
                i += 1
                continue
            out.append(c)
            i += 1
        elif state == LINE:
            if c == "\n":
                state = NORMAL
                out.append("\n")
            else:
                out.append(" ")
            i += 1
        elif state == BLOCK:
            if c == "/" and nxt == "*":
                block_depth += 1
                out.append("  ")
                i += 2
                continue
            if c == "*" and nxt == "/":
                block_depth -= 1
                out.append("  ")
                i += 2
                if block_depth == 0:
                    state = NORMAL
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
        elif state == STR:
            if c == "\\":
                out.append("  ")
                i += 2
                continue
            if c == '"':
                state = NORMAL
            out.append("\n" if c == "\n" else " ")
            i += 1
        elif state == RAW:
            if c == '"' and nxt == '"' and text[i + 2: i + 3] == '"':
                state = NORMAL
                out.append("   ")
                i += 3
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
        else:  # CHAR
            if c == "\\":
                out.append("  ")
                i += 2
                continue
            if c == "'":
                state = NORMAL
            out.append("\n" if c == "\n" else " ")
            i += 1
    return "".join(out)


DECL_RE = re.compile(
    r"^(?:(?:public|internal|private|protected|abstract|open|final|sealed|"
    r"data|enum|annotation|value|expect|actual|fun)\s+)*(class|object|interface)\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)",
)
NONPUBLIC_RE = re.compile(r"\b(internal|private|protected)\b")
MEMBER_RE = re.compile(
    r"^\s+(?:public\s+)?(?:(?:internal|private|protected|abstract|open|final|"
    r"sealed|data|enum|override|inline|operator|infix|suspend|const|external|"
    r"lateinit|value)\s+)*(?:fun|val|var)\s+`?([A-Za-z_][A-Za-z0-9_]*)`?",
)


def strip_xml(text: str) -> str:
    """XML 语料专用：只剥 <!-- --> 注释，**属性值原样保留**——AndroidManifest 的
    Activity/Service/Provider 注册、layout 里的自定义 View 全在属性字符串里，
    按 Kotlin 口径剥字符串会把真实引用抹掉（自测负例钉住这一条）。"""
    return re.sub(r"<!--.*?-->", lambda m: " " * len(m.group(0)), text, flags=re.DOTALL)


def top_level_decls(stripped: str):
    """列 0 的顶层 class/object/interface 声明。返回 [(line_no(1基), kind, name, nonpublic?)]。"""
    found = []
    for idx, line in enumerate(stripped.split("\n"), start=1):
        if not line or line[0] in " \t":
            continue
        m = DECL_RE.match(line)
        if not m:
            continue
        head = line[: m.start(1)]
        found.append((idx, m.group(1), m.group(2), bool(NONPUBLIC_RE.search(head))))
    return found


def body_slice(stripped: str, start_line: int) -> str:
    """从声明行到下一个列 0 非空行（下一顶层声明）之间的文本。"""
    lines = stripped.split("\n")
    buf = [lines[start_line - 1]]
    for line in lines[start_line:]:
        if line and line[0] not in " \t":
            break
        buf.append(line)
    return "\n".join(buf)


def public_members(stripped: str, start_line: int) -> list[str]:
    body = body_slice(stripped, start_line)
    seen: list[str] = []
    for m in MEMBER_RE.finditer(body):
        name = m.group(1)
        line_head = body[max(0, m.start() - 200): m.start()]
        if NONPUBLIC_RE.search(line_head.split("\n")[-1] if line_head else ""):
            continue
        if name not in seen:
            seen.append(name)
    return seen


# ---------------------------------------------------------------- 语料收集


def collect_files(root: Path, base: str, pattern: str) -> list[Path]:
    d = root / base
    if not d.is_dir():
        return []
    return sorted(p for p in d.rglob(pattern) if p.is_file())


def rel(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


class Corpus:
    """全部语料一次性读入并剥注释；name→[(relpath, line, snippet)]。"""

    def __init__(self, root: Path):
        self.root = root
        self.files: dict[str, tuple[str, str]] = {}  # relpath -> (orig, stripped)
        for p in collect_files(root, "app/src/main/java", "*.kt"):
            self._add(p)
        self.prod_rel: set[str] = set(self.files)
        # Manifest / res XML（生产可达面：Manifest 注册、自定义 View 等）
        self.extra_prod: list[str] = []
        for p in collect_files(root, "app/src/main", "*.xml"):
            self._add(p, prod=False)
            self.extra_prod.append(rel(p, root))
        # debug 变体源（单独证据桶，不计生产引用）
        self.debug_rel: set[str] = set()
        for p in collect_files(root, "app/src/debug", "*.kt"):
            self._add(p, prod=False)
            self.debug_rel.add(rel(p, root))
        # 测试语料
        self.test_rel: set[str] = set()
        for base in ("app/src/test", "app/src/androidTest"):
            for p in collect_files(root, base, "*.kt"):
                self._add(p, prod=False)
                self.test_rel.add(rel(p, root))
        # proguard（只作证据）
        self.proguard_rel: list[str] = []
        pg = root / "app" / "proguard-rules.pro"
        if pg.is_file():
            self._add(pg, prod=False)
            self.proguard_rel.append(rel(pg, root))

    def _add(self, p: Path, prod: bool = True) -> None:
        r = rel(p, self.root)
        try:
            orig = p.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            orig = p.read_text(encoding="utf-8", errors="replace")
        stripper = strip_xml if p.suffix.lower() == ".xml" else strip_kotlin
        self.files[r] = (orig, stripper(orig))

    def index(self, relpaths) -> dict[str, list[tuple[str, int, str]]]:
        """词索引：裸名字 → 命中 [(file, line, 原文行)]。"""
        idx: dict[str, list[tuple[str, int, str]]] = {}
        for r in relpaths:
            stripped = self.files[r][1]
            for ln, line in enumerate(stripped.split("\n"), start=1):
                for m in re.finditer(r"[A-Za-z_][A-Za-z0-9_]*", line):
                    idx.setdefault(m.group(0), []).append((r, ln, ln))
        return idx

    def hits(self, name: str, index, exclude_file: str | None = None,
             exclude_relset: set[str] | None = None) -> list[tuple[str, int, str]]:
        res = []
        for (r, ln, _o) in index.get(name, []):
            if exclude_file and r == exclude_file:
                continue
            if exclude_relset and r in exclude_relset:
                continue
            orig_line = self.files[r][0].split("\n")[ln - 1].strip()
            res.append((r, ln, orig_line))
        return res


# ---------------------------------------------------------------- A/B 判定


def classify_ab(corp: Corpus, prod_index: dict, full_index: dict, test_index: dict,
                debug_index: dict, pg_index: dict) -> tuple[list[dict], int]:
    wired = 0
    findings: list[dict] = []
    wired_files: set[str] = set()
    decls_by_file: dict[str, list[tuple[int, str, str, bool]]] = {}
    # 第一遍：谁有生产引用（外部 OR 同文件），按文件记录——供第二遍判「同文件有活邻居」
    for r in sorted(corp.prod_rel):
        _orig, stripped = corp.files[r]
        for d in top_level_decls(stripped):
            (ln, kind, name, nonpublic) = d
            decls_by_file.setdefault(r, []).append(d)
            if nonpublic:
                continue
            has_ref = bool(corp.hits(name, prod_index, exclude_file=r)) or \
                len(re.findall(r"\b%s\b" % re.escape(name), stripped)) > 1
            if has_ref:
                wired += 1
                wired_files.add(r)
    # 第二遍：A/B 清点
    for r in sorted(corp.prod_rel):
        orig, stripped = corp.files[r]
        for (ln, kind, name, nonpublic) in decls_by_file.get(r, []):
            if nonpublic:
                continue
            # 生产闸：外部引用 OR 同文件引用（声明行以外的词命中）都算「生产侧有引用」。
            # 同文件使用是真的用（调用方经类型推断不必点名，如 RiskMatrix 内部用 RiskDecision）；
            # 口径字面「生产侧零引用」就该把它排除。同文件引用数进证据字段备查。
            ext = corp.hits(name, prod_index, exclude_file=r)
            self_refs = len(re.findall(r"\b%s\b" % re.escape(name), stripped)) - 1
            if ext or self_refs > 0:
                continue  # wired 计数已在第一遍累加（第一遍已含同文件命中？否——见下）
            # 第 1 步零命中 ⇒ 第 2 步：公开成员名（证据扫描用全语料，供人工复核）
            members = public_members(stripped, ln)
            member_hits = []
            for mem in members:
                for h in corp.hits(mem, full_index, exclude_file=r):
                    member_hits.append({"member": mem, "file": h[0], "line": h[1],
                                        "snippet": h[2][:160]})
            # 第 3 步：薄封装（他文件里「名含类名」的顶层 fun，函数体首 8 行内真委托到该类）
            wrapper_hits = []
            wrap_pat = re.compile(
                r"^\s*(?:public\s+)?(?:internal\s+)?fun\s+`?(\w*%s\w*|new%s)`?\s*[(<]"
                % (re.escape(name[0].lower() + name[1:]) if name else name, name),
                re.IGNORECASE,
            )
            for wr in sorted(corp.prod_rel):
                if wr == r:
                    continue
                w_orig, w_strip = corp.files[wr]
                for wi, wline in enumerate(w_strip.split("\n"), start=1):
                    wm = wrap_pat.match(wline)
                    if not wm:
                        continue
                    body = "\n".join(w_strip.split("\n")[wi - 1: wi + 8])
                    wrapper_hits.append({
                        "wrapper": wm.group(1), "file": wr, "line": wi,
                        "delegates_to_class": name in body,
                        "wrapper_external_refs": len(corp.hits(wm.group(1), prod_index, exclude_file=wr)),
                    })
            tests = corp.hits(name, test_index)
            debug = corp.hits(name, debug_index)
            pg = corp.hits(name, pg_index)
            kind_out = "B" if tests else "A"
            findings.append({
                "list": kind_out, "kind": kind, "symbol": name,
                "package": package_of(orig, stripped, ln),
                "file": r, "line": ln,
                "self_file_refs": max(self_refs, 0),
                "file_mates_wired": r in wired_files,
                "test_refs": [{"file": t[0], "line": t[1]} for t in tests[:20]],
                "debug_only_refs": [{"file": t[0], "line": t[1]} for t in debug[:10]],
                "proguard_hits": [{"file": t[0], "line": t[1]} for t in pg[:5]],
                "member_scan": member_hits[:20],
                "member_scan_more": max(len(member_hits) - 20, 0),
                "wrapper_scan": wrapper_hits[:20],
            })
    return findings, wired


def package_of(orig: str, stripped: str, decl_line: int) -> str:
    for line in stripped.split("\n")[:decl_line]:
        m = re.match(r"^\s*package\s+([\w.]+)", line)
        if m:
            return m.group(1)
    return ""


# ---------------------------------------------------------------- C 判定

# 键可以是裸常量、限定常量（Gate.PREF_KEY）或字符串字面量；默认值可以是字面量或常量。
GETBOOL_RE = re.compile(r"getBoolean\(\s*([\w.\"]+)\s*,\s*([\w.]+)\s*\)")
CONST_FALSE_RE = re.compile(r"const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*\w+)?\s*=\s*false")
CONST_STR_RE = re.compile(r"const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*\w+)?\s*=\s*\"([^\"]*)\"")
PUTBOOL_RE = re.compile(r"putBoolean\(\s*([\w.\"]+)\s*,\s*([^)]*)\)")

KEY_EXPRS = {"PREF_KEY"}  # 见下：哪些常量名要参与「同名异值」解析告警


def _resolve_key(corp: Corpus, const_table: dict, sym_file: dict, file: str,
                 expr: str) -> str | None:
    """把键表达式解析成真实 prefs 键值。解析不出返回 None（按未配对处理，宁可漏不误杀）。

    限定名（Gate.PREF_KEY）按**符号表**找宿主文件——object 名与文件名不必一致
    （真仓实证：ProdEvalGate 声明在 ProdEval.kt）。
    """
    if expr.startswith('"'):
        return expr.strip('"') or None
    if "." in expr:
        stem, name = expr.rsplit(".", 1)
        host = sym_file.get(stem)
        if host is None:
            return None
        return const_table.get((host, name))
    return const_table.get((file, expr))


def _enclosing_writer(corp: Corpus, file: str, line_no: int) -> str | None:
    """putBoolean 写点的「宿主调用面」：最近的 fun 名，加上最近的列 0 顶层符号名。

    开启路径要能被人拨动，前提是**这个写口本身有人调**——调用方必须点到
    `ObjectName.fun`（或顶层 fun 名）。返回 None＝判不出（按死口处理）。
    """
    lines = corp.files[file][1].split("\n")
    fun_name = None
    top_name = None
    for i in range(min(line_no, len(lines)) - 1, -1, -1):
        line = lines[i]
        if fun_name is None:
            m = re.search(r"\bfun\s+`?([A-Za-z_][A-Za-z0-9_]*)`?", line)
            if m:
                fun_name = m.group(1)
        if i < len(lines) and line and line[0] not in " \t":
            m = DECL_RE.match(line)
            if m:
                top_name = m.group(2)
                break
    if fun_name is None:
        return None
    return ("%s.%s" % (top_name, fun_name)) if top_name else fun_name


def classify_c(corp: Corpus) -> tuple[list[dict], int]:
    """布尔闸：读点 getBoolean(key, 默认 false)。

    键身份按**解析出的字符串值**配对（三把闸都叫 PREF_KEY，按常量名配对会互连）。
    开启路径＝生产里存在该键值的**活**写点：putBoolean 所在的写口（ObjectName.fun）
    本身要能在生产语料里被点到（剥注释后词命中）——写口存在但零调用方 ＝ 拨不动，
    仍是 C。写口判不出的按死口处理。返回 (C 清单, 有活写口被排除的闸数)。"""
    const_table: dict[tuple[str, str], str] = {}
    sym_file: dict[str, str] = {}  # 顶层符号名 → 声明文件（限定名解析用）
    reads: list[dict] = []
    writes: dict[str, list[tuple[str, int, str]]] = {}
    for r in sorted(corp.prod_rel):
        orig, stripped = corp.files[r]
        for m in CONST_STR_RE.finditer(orig):
            const_table[(r, m.group(1))] = m.group(2)
        for (_ln, _kind, sname, _np) in top_level_decls(stripped):
            sym_file.setdefault(sname, r)
        consts_false_here = {m.group(1) for m in CONST_FALSE_RE.finditer(stripped)}
        for m in GETBOOL_RE.finditer(stripped):
            key_expr, default = m.group(1), m.group(2)
            line_no = stripped[: m.start()].count("\n") + 1
            line_txt = orig.split("\n")[line_no - 1].strip()
            is_false = default == "false" or (
                "." not in default and default in consts_false_here)
            if not is_false:
                continue
            value = _resolve_key(corp, const_table, sym_file, r, key_expr)
            reads.append({"key_expr": key_expr, "value": value, "file": r,
                          "line": line_no, "snippet": line_txt[:160]})
        for m in PUTBOOL_RE.finditer(stripped):
            key_expr, val = m.group(1), m.group(2).strip()
            line_no = stripped[: m.start()].count("\n") + 1
            value = _resolve_key(corp, const_table, sym_file, r, key_expr)
            if value is None:
                continue
            writer = _enclosing_writer(corp, r, line_no)
            writes.setdefault(value, []).append(
                (r, line_no, orig.split("\n")[line_no - 1].strip()[:160], writer))
    # 写口活性：写口符号（ObjectName.fun）在生产语料（剥注释）里被他文件点到才算活
    pidx = corp.index(sorted(corp.prod_rel))
    live_writes: dict[str, list[tuple[str, int, str]]] = {}
    for value, ws in writes.items():
        for (wr, wln, wtxt, writer) in ws:
            if writer is None:
                continue
            host = writer.split(".")[0] if "." in writer else writer
            if corp.hits(host, pidx, exclude_file=wr):
                live_writes.setdefault(value, []).append((wr, wln, wtxt))
    out = []
    excluded = 0
    for rd in reads:
        value = rd["value"]
        if value is None:
            continue  # 键值解析不出 ⇒ 不进清单（宁可漏报不误杀，注释里说明口径）
        w = live_writes.get(value, [])
        if w:
            excluded += 1  # 有活写口＝有开启路径，不入 C（计进摘要）
            continue
        out.append({
            "key": value, "key_expr": rd["key_expr"],
            "read_file": rd["file"], "read_line": rd["line"],
            "snippet": rd["snippet"],
            "write_sites": [{"file": a, "line": b, "snippet": c, "writer": d}
                            for (a, b, c, d) in writes.get(value, [])[:20]],
            "can_enable": False,
        })
    return out, excluded


# ---------------------------------------------------------------- 计数锁（454）

# 只认汇总表里这两行的整数；按行首口径定位，不会误吃 NOT_WIRED 等别的行。
MD_PUBLIC_RE = re.compile(r"^\|\s*顶层公开面[^\n|]*\|\s*(\d+)\s*\|", re.MULTILINE)
MD_WIRED_RE = re.compile(r"^\|\s*生产可达[^\n|]*\|\s*(\d+)\s*\|", re.MULTILINE)


def read_counts_from_disk(root: Path) -> tuple[dict[str, int | None], list[str]]:
    """只读解析**磁盘上**两份产物的四个计数。

    铁律：绝不拿本次 render 出来的内存字符串自证——那是「自己验自己」，恒绿。
    解析不出一律记 error，不许静默跳过（验证方式缺失的约束等于没有约束）。
    """
    errors: list[str] = []
    counts: dict[str, int | None] = {
        "md_public": None, "md_wired": None,
        "js_public": None, "js_wired": None,
    }
    md_path = root / "docs" / "entry-coverage.md"
    if not md_path.is_file():
        errors.append("docs/entry-coverage.md 不存在（无法对账）")
    else:
        text = md_path.read_text(encoding="utf-8")
        for key, label, rx in (("md_public", "顶层公开面", MD_PUBLIC_RE),
                               ("md_wired", "生产可达", MD_WIRED_RE)):
            m = rx.search(text)
            if m is None:
                errors.append("md 汇总表「%s」计数行缺失/不可解析" % label)
            else:
                counts[key] = int(m.group(1))
    js_path = root / "docs" / "entry-coverage.json"
    if not js_path.is_file():
        errors.append("docs/entry-coverage.json 不存在（无法对账）")
    else:
        try:
            summary = json.loads(js_path.read_text(encoding="utf-8"))["summary"]
            counts["js_public"] = summary["production_top_level_public"]
            counts["js_wired"] = summary["wired"]
            for key in ("js_public", "js_wired"):
                if not isinstance(counts[key], int) or isinstance(counts[key], bool):
                    errors.append("json summary 计数非整数：%s=%r" % (key, counts[key]))
                    counts[key] = None
        except (ValueError, KeyError, TypeError) as exc:
            errors.append("docs/entry-coverage.json 解析失败：%s" % exc)
    return counts, errors


def lock_counts(root: Path, recomputed_public: int, recomputed_wired: int) -> list[str]:
    """两条断言（md 一组／json 一组）＋ 交叉一致性；只读，绝不写盘。

    md 的「顶层公开面／生产可达」与 json 的 production_top_level_public／wired
    四个落盘位置，逐个与当场重算值对账；不等即红，并打印「盘中＝X／重算＝Y」两数。
    """
    counts, problems = read_counts_from_disk(root)
    for key, label, expected in (
        ("md_public", "md 顶层公开面", recomputed_public),
        ("md_wired", "md 生产可达", recomputed_wired),
        ("js_public", "json production_top_level_public", recomputed_public),
        ("js_wired", "json wired", recomputed_wired),
    ):
        got = counts.get(key)
        if got is None:
            continue  # 解析阶段已记 error，不重复报
        if got != expected:
            problems.append("[计数锁] %s：盘中=%d / 重算=%d ⇒ 不一致" % (label, got, expected))
    for a, b, label in (("md_public", "js_public", "顶层公开面"),
                        ("md_wired", "js_wired", "生产可达")):
        x, y = counts.get(a), counts.get(b)
        if x is not None and y is not None and x != y:
            problems.append("[计数锁] %s：md=%d / json=%d ⇒ 两份产物互相不一致"
                            % (label, x, y))
    return problems


# ---------------------------------------------------------------- 主流程


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=None)
    ap.add_argument("--strict", action="store_true",
                    help="只读自证：缺结论/陈旧注册/计数漂移即退出 1（不写盘）")
    ap.add_argument("--check", action="store_true", help="核对 docs/ 产物与现扫描一致")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = repo_root(args.root)
    corp = Corpus(root)
    prod_index = corp.index(sorted(set(corp.prod_rel) | set(corp.extra_prod)))
    full_index = corp.index(sorted(set(corp.prod_rel) | set(corp.extra_prod) | set(corp.debug_rel)
                                   | set(corp.test_rel) | set(corp.proguard_rel)))
    test_index = corp.index(sorted(corp.test_rel))
    debug_index = corp.index(sorted(corp.debug_rel))
    pg_index = corp.index(sorted(corp.proguard_rel))

    ab, wired = classify_ab(corp, prod_index, full_index, test_index, debug_index, pg_index)
    c_list, gates_with_write = classify_c(corp)

    # 结论注册表
    reg_path = root / "tools" / "entry-coverage-conclusions.json"
    registry: dict = {}
    if reg_path.is_file():
        registry = json.loads(reg_path.read_text(encoding="utf-8"))

    problems: list[str] = []
    for f in ab:
        verdict = registry.get(f["list"], {}).get(f["symbol"])
        if verdict is None:
            problems.append("%s/%s 缺结论" % (f["list"], f["symbol"]))
            f["verdict"], f["note"] = "UNRESOLVED", "（未裁决）"
        else:
            f["verdict"], f["note"] = verdict.get("verdict", "UNRESOLVED"), verdict.get("note", "")
            if f["verdict"] not in ("wired", "not_urgent", "delete"):
                problems.append("%s/%s 非法 verdict：%s" % (f["list"], f["symbol"], f["verdict"]))
    for f in c_list:
        verdict = registry.get("C", {}).get(f["key"])
        if verdict is None:
            problems.append("C/%s 缺结论" % f["key"])
            f["verdict"], f["note"] = "UNRESOLVED", "（未裁决）"
        else:
            f["verdict"], f["note"] = verdict.get("verdict", "UNRESOLVED"), verdict.get("note", "")
    # 注册表陈旧项
    known_a = {f["symbol"] for f in ab if f["list"] == "A"}
    known_b = {f["symbol"] for f in ab if f["list"] == "B"}
    known_c = {f["key"] for f in c_list}
    for lst, known in (("A", known_a), ("B", known_b), ("C", known_c)):
        for sym in registry.get(lst, {}):
            if sym not in known:
                problems.append("%s/%s 注册表陈旧（现扫描已无此项）" % (lst, sym))

    report = {
        "lists": {"A": [f for f in ab if f["list"] == "A"],
                  "B": [f for f in ab if f["list"] == "B"],
                  "C": c_list},
        "summary": {
            "production_top_level_public": len(known_a | known_b) + wired,
            "wired": wired,
            "A": len(known_a), "B": len(known_b), "C": len(c_list),
            "gates_with_write_path": gates_with_write,
            "not_wired": sum(1 for f in ab if f.get("verdict") == "wired")
                         + sum(1 for f in c_list if f.get("verdict") == "wired"),
        },
    }

    md = render_md(report)
    js = json.dumps(report, ensure_ascii=False, indent=1, sort_keys=True) + "\n"

    if args.check:
        # 只读：逐字节核对已生成产物与现扫描（原行为不变）
        docs_md = (root / "docs" / "entry-coverage.md")
        docs_js = (root / "docs" / "entry-coverage.json")
        ok = (docs_md.read_text(encoding="utf-8") == md if docs_md.is_file() else False) and \
             (docs_js.read_text(encoding="utf-8") == js if docs_js.is_file() else False)
        print("[entry-coverage] --check：%s" % ("一致" if ok else "不一致（重新生成）"))
        return 0 if ok else 1

    if args.strict:
        # 454：只读自证。先按现码重算，再拿**磁盘上**的四个计数对账；绝不 write_text。
        # 原实现先写盘再判 problems ⇒ 手改过的计数被当场洗成正确值，既检不出、
        # 又不留证据，还把工作树改脏。故这里是唯一不落盘的自证路径。
        s = report["summary"]
        problems += lock_counts(root, s["production_top_level_public"], s["wired"])
        print("[entry-coverage] --strict（只读）：当场重算 顶层公开面 %d ／ 生产可达 %d"
              % (s["production_top_level_public"], s["wired"]))
        for p in problems:
            print("[entry-coverage] 问题：%s" % p)
        if problems:
            print("[entry-coverage] --strict：%d 条未决，退出 1（未写任何文件）" % len(problems))
            return 1
        print("[entry-coverage] --strict：绿（计数锁 4/4 命中 ＋ 结论/注册全齐），未写任何文件")
        return 0

    # 默认模式＝刷新：唯一写盘路径（CI 走这条），按现码重算并覆盖两份产物。
    (root / "docs" / "entry-coverage.md").write_text(md, encoding="utf-8")
    (root / "docs" / "entry-coverage.json").write_text(js, encoding="utf-8")

    s = report["summary"]
    print("[entry-coverage] 顶层公开面 %d：接线中 %d ｜ A 无入口 %d ｜ B 仅测试 %d ｜ C 默认关 %d ｜ NOT_WIRED（结论=接线）%d"
          % (s["production_top_level_public"], s["wired"], s["A"], s["B"], s["C"], s["not_wired"]))
    for p in problems:
        print("[entry-coverage] 问题：%s" % p)
    return 0


# ---------------------------------------------------------------- 渲染


def render_md(report: dict) -> str:
    s = report["summary"]
    lines: list[str] = []
    a = lines.append
    a("# 入口覆盖清点（373）")
    a("")
    a("> 机器清点，不判生死：结论列只有三值——**接线（谁接）／不急（理由）／该删（理由）**，")
    a("> 由 tools/entry-coverage-conclusions.json 登记后合并进来（不许留空、不许待定）。")
    a("> 本文件与 entry-coverage.json 由 tools/check-entry-coverage.py 生成，**两次运行逐字节一致**；")
    a("> 清点脚本只读不写生产代码。")
    a("")
    a("## 汇总")
    a("")
    a("| 口径 | 数 |")
    a("|---|---:|")
    a("| 顶层公开面（app/src/main/java，剥注释后计数） | %d |" % s["production_top_level_public"])
    a("| 生产可达（有生产引用，含同文件） | %d |" % s["wired"])
    a("| A 有定义无调用 | %d |" % s["A"])
    a("| B 只有测试在调 | %d |" % s["B"])
    a("| C 已接线默认关、无开启路径 | %d |" % s["C"])
    a("| **NOT_WIRED（结论=接线，接线欠账）** | **%d** |" % s["not_wired"])
    a("")
    a("## A 有定义无调用（生产侧零引用，含同文件）")
    a("")
    a("查法三步：① 类名全语料词边界检索（剥注释/字符串后计数）→ ② 公开成员名逐个检索 →")
    a("③ 薄封装（工厂/别名函数）检索。**同文件的使用也算引用**（调用方经类型推断不必点名），")
    a("但「只被本文件死代码引用」的隐藏链仍在 A 清单邻居里可见。**不许只 grep 类名就判零引用**——")
    a("注释/字符串里的名字不算（曾实测：XML 属性值算引用、`\"\"` 空串卡死剥除器，均已钉负例）。")
    a("")
    if not report["lists"]["A"]:
        a("本版 A 清单为 **0**。查法如上三步（类名→公开方法名→薄封装名），扫描范围")
        a("app/src/main/java 全部顶层公开 class/object/interface（internal/private 不算面）。")
    else:
        a("| 符号 | 文件:行 | 结论 | 说明/证据 |")
        a("|---|---|---|---|")
        for f in report["lists"]["A"]:
            a("| `%s` | `%s:%d` | %s | %s |" % (f["symbol"], f["file"], f["line"],
                                                verdict_cn(f), f["note"]))
    a("")
    a("## B 只有测试在调（生产不可达）")
    a("")
    if not report["lists"]["B"]:
        a("本版 B 清单为 **0**（生产零外部引用且测试也零引用的已归 A；生产有引用的不算）。")
    else:
        a("| 符号 | 文件:行 | 测试引用 | 结论 | 说明/证据 |")
        a("|---|---|---|---|---|")
        for f in report["lists"]["B"]:
            t = "、".join("`%s:%d`" % (x["file"], x["line"]) for x in f["test_refs"][:3])
            a("| `%s` | `%s:%d` | %s | %s | %s |" % (f["symbol"], f["file"], f["line"],
                                                     t, verdict_cn(f), f["note"]))
    a("")
    a("## C 已接线默认关、无开启路径")
    a("")
    a("判据：`getBoolean(key, 默认 false)` 读点存在；生产侧**找不到活的开启路径**——")
    a("没有 putBoolean 写点，或写口（ObjectName.fun）自身零调用方（写口在但没人拨＝拨不开）。")
    a("设置页能拨的不算——那是有开启路径。键身份按解析出的**真实 prefs 键值**配对。")
    a("")
    if not report["lists"]["C"]:
        a("本版 C 清单为 **0**（所有默认 false 的闸都能找到写 true 的路径）。")
    else:
        a("| 开关 key | 读点 | 开启路径 | 结论 | 说明/证据 |")
        a("|---|---|---|---|---|")
        for f in report["lists"]["C"]:
            ws = f.get("write_sites") or []
            if ws:
                path_txt = "；".join("写口 `%s`（`%s:%d`）零调用方" % (w["writer"], w["file"], w["line"])
                                    for w in ws if w.get("writer")) or "写点判不出"
            else:
                path_txt = "无写点"
            a("| `%s` | `%s:%d` | %s | %s | %s |" % (f["key"], f["read_file"], f["read_line"],
                                                     path_txt, verdict_cn(f), f["note"]))
    a("")
    a("## 口径与边界")
    a("")
    a("- 语料：app/src/main 全部 .kt ＋ AndroidManifest.xml ＋ res/**/*.xml；注释/字符串不计数。")
    a("- app/src/debug 命中单独记「仅 debug 变体引用」（release 不可达仍入清单）；proguard keep 只作证据。")
    a("- 同名符号互相「救活」：宁可漏报不误杀；结论侧人工复核后登记。")
    a("- CI 只报不拦（先观察一版）；重新生成：`python3 tools/check-entry-coverage.py --check` 核对一致。")
    a("")
    return "\n".join(lines)


def verdict_cn(f: dict) -> str:
    return {"wired": "接线", "not_urgent": "不急", "delete": "该删"}.get(f.get("verdict"), "（未裁决）")


# ---------------------------------------------------------------- 自测


def selftest() -> int:
    """负例自测：临时目录造样例，钉住判定不误杀、不漏报。"""
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        pkg = root / "app" / "src" / "main" / "java" / "com" / "example" / "t"
        pkg.mkdir(parents=True)
        (pkg / "Dead.kt").write_text(
            "package com.example.t\n"
            "// 注释里提到 Dead 不算引用\n"
            "class Dead { fun ping() = 1 }\n"
            "\n"
            "// 回归钉（373）：`\"\"` 空串不得把剥除器卡进三引号态，吞掉后半文件\n"
            "val blank: String = \"\"\n"
            "val after = \"含/*假块注释*/也不开块\"\n"
            "val raw = \"\"\"raw 里提到 Lonely 不算引用\"\"\"\n"
            "\n"
            "// 空串之后声明的类：若剥除器卡死，这里会被吞掉 ⇒ 漏判\n"
            "class AfterBlank\n", encoding="utf-8")
        (pkg / "DeadObj.kt").write_text(
            "package com.example.t\n"
            "object DeadObj { fun pong() = 2 }\n"
            "fun makeDeadObj() = DeadObj\n", encoding="utf-8")
        (pkg / "Alive.kt").write_text(
            "package com.example.t\n"
            "class Alive { fun ok() = 3 }\n"
            "class Lonely\n", encoding="utf-8")
        (pkg / "Use.kt").write_text(
            "package com.example.t\n"
            "val x = Alive().ok()\n", encoding="utf-8")
        (pkg / "ManifestOnly.kt").write_text(
            "package com.example.t\n"
            "class ManifestOnly\n", encoding="utf-8")
        # 回归钉（373）：空串之后的真代码必须仍被计入（剥除器不得卡死）
        (pkg / "Use2.kt").write_text(
            "package com.example.t\n"
            "val note: String = \"\"\n"
            "val w = ManifestOnly()\n"
            "val a2 = AfterBlank()\n", encoding="utf-8")
        (pkg / "TestOnly.kt").write_text(
            "package com.example.t\n"
            "class TestOnly { fun t() = 4 }\n", encoding="utf-8")
        (root / "app" / "src" / "main" / "AndroidManifest.xml").write_text(
            "<manifest><activity android:name=\".t.ManifestOnly\"/>\n"
            "<activity android:name=\".MainActivity\"/></manifest>", encoding="utf-8")
        tdir = root / "app" / "src" / "test" / "java"
        tdir.mkdir(parents=True)
        (tdir / "T.kt").write_text(
            "package com.example.t\n"
            "val y = TestOnly().t()\n", encoding="utf-8")
        # 闸样例：默认关、有写 true 的路径（不算 C）＋ 默认关、无写点（C）
        (pkg / "Gate.kt").write_text(
            "package com.example.t\n"
            "object G1 {\n  const val KEY = \"k1\"\n  fun on(c: android.content.Context) =\n"
            "    c.getSharedPreferences(\"p\", 0).getBoolean(KEY, false)\n}\n", encoding="utf-8")
        (pkg / "Gate2.kt").write_text(
            "package com.example.t\n"
            "object G2 {\n  const val KEY2 = \"k2\"\n  fun on(c: android.content.Context) =\n"
            "    c.getSharedPreferences(\"p\", 0).getBoolean(KEY2, false)\n"
            "  fun set(c: android.content.Context, v: Boolean) =\n"
            "    c.getSharedPreferences(\"p\", 0).edit().putBoolean(KEY2, true).commit()\n}\n",
            encoding="utf-8")
        # 默认值走常量（真仓常见：getBoolean(KEY, PREF_DEFAULT)），且写点用限定名
        (pkg / "Gate3.kt").write_text(
            "package com.example.t\n"
            "object G3 {\n  const val KEY3 = \"k3\"\n  const val DEF3 = false\n"
            "  fun on(c: android.content.Context) =\n"
            "    c.getSharedPreferences(\"p\", 0).getBoolean(KEY3, DEF3)\n}\n", encoding="utf-8")
        (pkg / "Gate3Settings.kt").write_text(
            "package com.example.t\n"
            "object G3Settings {\n  fun set(c: android.content.Context, on: Boolean) =\n"
            "    c.getSharedPreferences(\"p\", 0).edit().putBoolean(G3.KEY3, on).commit()\n}\n",
            encoding="utf-8")
        # G2 的写口有活调用方（UI 拨动）⇒ k2 不入 C
        (pkg / "Ui.kt").write_text(
            "package com.example.t\n"
            "val z = G2.set(null, true)\n", encoding="utf-8")

        corp = Corpus(root)
        prod_index = corp.index(sorted(set(corp.prod_rel) | set(corp.extra_prod)))
        full_index = corp.index(sorted(set(corp.prod_rel) | set(corp.extra_prod) | set(corp.test_rel)))
        test_index = corp.index(sorted(corp.test_rel))
        debug_index: dict = {}
        pg_index: dict = {}
        ab, wired = classify_ab(corp, prod_index, full_index, test_index, debug_index, pg_index)
        cs, gates_with_write = classify_c(corp)
        names = {(f["list"], f["symbol"]) for f in ab}
        fails: list[str] = []
        if ("A", "Dead") not in names:
            fails.append("负例失败：Dead 应入 A（注释提及不算引用）")
        if any(f["symbol"] == "DeadObj" for f in ab):
            fails.append("误报：DeadObj 有同文件薄封装引用（makeDeadObj）——按口径「生产侧有引用」不入清单")
        if ("B", "TestOnly") not in names:
            fails.append("负例失败：TestOnly 应入 B（仅测试引用）")
        if any(f["symbol"] == "Alive" for f in ab):
            fails.append("误杀：Alive 已接线不应入清单")
        if any(f["symbol"] == "ManifestOnly" for f in ab):
            fails.append("误杀：ManifestOnly 在 Manifest 属性里注册（XML 属性值必须算引用）")
        if wired != 6:
            fails.append("wired 计数应为 6（Alive＋ManifestOnly＋AfterBlank＋G3＋G2＋DeadObj"
                         "——DeadObj 被同文件薄封装引用，按口径算引用），实得 %d" % wired)
        lonely = [f for f in ab if f["symbol"] == "Lonely"]
        if not lonely or not lonely[0]["file_mates_wired"]:
            fails.append("负例失败：Lonely 应入 A 且 file_mates_wired=True（同文件 Alive 已接线）")
        else:
            if lonely[0]["package"] != "com.example.t":
                fails.append("负例失败：Lonely 的 package 解析错：%s" % lonely[0]["package"])
        keys = {c["key"] for c in cs}
        if "k1" not in keys:
            fails.append("漏报：G1 默认关且无写点应入 C（键按解析值 k1 配对）")
        if "k3" not in keys:
            fails.append("漏报：G3 写口 G3Settings 零调用方（死口）应入 C")
        if "k2" in keys:
            fails.append("误报：G2 写口有活调用方（Ui.kt 点 G2.set）不入 C")
        if gates_with_write != 1:
            fails.append("有活写口闸计数应为 1（k2），实得 %d" % gates_with_write)
    if fails:
        for f in fails:
            print("[entry-coverage][selftest] %s" % f)
        return 1
    print("[entry-coverage][selftest] 全过（A/B/C 判定＋负例＋wired 计数＋写口活性）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
