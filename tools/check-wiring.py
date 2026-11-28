#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-wiring.py —— 装配登记表（docs/装配登记表.md）三法扫描校验（894 批）。

做什么（对表逐行做结构校验＋三法扫描复核）：
  W1  六列齐：表头与每行恰为 `生产方|产出|计划消费方|实际消费方|接通证据(file:行)|状态`，格子不留空。
  W2  状态四值闭集：`已接通`／`计划中·已实现待开`／`未接通`／`不接（理由）`——出现第五值即红（「待定」也是第五值）。
  W3  三法都实现且都跑（缺一 ⇒ 自检红）：
        法① 类名扫描   —— 符号全语料词边界检索（剥注释/字符串后计数，词边界，不撞子串）；
        法② 公开方法扫描 —— 取锚符号声明块内的公开方法名（private/protected 除外），逐个查外部消费；
        法③ 薄封装扫描  —— 声明文件内的 XxxLog／XxxStore／XxxRecorder／recordXxx 形薄封装及其外部消费点。
      查法三步与 docs/entry-coverage.md §A 同源；对象不同：那边是符号级入口清点，
      这里是批次级产出→消费链登记。W6：entry-coverage 的行不许拿来当装配证据（证据列出现即红）。
  W4  零引用必须写明查法：实际消费方含零引用语义（或状态＝未接通）的行，证据列须含「查法」
      且带三法词（类名/方法/薄封装/检索）——「没找到」三个字不算查法。
  W5  「不接（理由）」理由不得为空。
  另：证据列每个 file:行 都做存在性核验（文件在、行号在范围内）；同一锚符号不许两行登记
     （同一条不许两处登记，③）；表头找不到＝W1 红。

判读规则（三法读数 → 行判定）：
  已接通＋机器消费  ⇒ 法②或法③必须在外部文件命中——**只 grep 类名不算接线（F2 的口子）**。
  已接通＋人工消费  ⇒ 消费方含「人工/外部评估者」即免机器命中要求，三法读数照出。
  计划中·已实现待开 ⇒ 锚符号必须能定位到声明（产出得真实存在）；外部命中只记读数不做门槛
                     （默认关/待激活的链路允许有调用点，如 895）。
  未接通            ⇒ 类名+公开方法+薄封装的生产侧外部命中必须全 0——扫出消费点即判表失实，红。
  不接（理由）      ⇒ 只查理由非空，读数照出。

用法：
  python tools/check-wiring.py                # 校验本仓 docs/装配登记表.md
  python tools/check-wiring.py --table <路径> --root <仓库根>
  python tools/check-wiring.py --selftest     # 夹具 F1–F6 ＋ 三法探针自检（含变异敏感性），全过才退出 0

本脚本只读不写（除 --selftest 在系统临时目录搭探针仓）；std lib only。
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import tempfile

DEFAULT_TABLE = "docs/装配登记表.md"

HEADERS = ["生产方", "产出", "计划消费方", "实际消费方", "接通证据(file:行)", "状态"]

STATUS_WIRED = "已接通"
STATUS_PLANNED = "计划中·已实现待开"
STATUS_UNWIRED = "未接通"
STATUS_ALLOWED = frozenset((
    "已接通",
    "计划中·已实现待开",
    "未接通",
))

ZERO_MARKERS = ("零引用", "无生产消费", "无生产调用", "零生产消费", "无消费", "零消费")
HUMAN_MARKERS = ("人工", "外部评估者", "人读")
ZERO_KEYWORDS = ("类名", "方法", "薄封装", "检索")
BARE_NONE = ("没找到", "未找到", "没有找到")

SRC_GLOBS = (
    "app/src/main/**/*.kt",
    "app/src/main/**/*.java",
    "core/src/main/**/*.kt",
    "tools/*.py",
    "scripts/**/*.py",
    ".github/workflows/*.yml",
)
TEST_GLOBS = (
    "app/src/test/**/*.kt",
    "core/src/test/**/*.kt",
)

ANCHOR_RE = re.compile(r"([A-Za-z0-9_\-]+)（([^（）]+?):(\d+)）")
SCAN_ANCHOR_SEG_RE = re.compile(r"扫描锚：([^；]*)")
EVIDENCE_RE = re.compile(r"([A-Za-z0-9_][A-Za-z0-9_\-\./\\]*?):(\d+)(?:-(\d+))?")
NEVER_RE = re.compile(r"^不接[（(]([^）)]+)[）)]$")

WRAPPER_FORM_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*(?:Log|Store|Recorder)|record[A-Z][A-Za-z0-9_]*)\b")
KT_DECL_RE_TMPL = r"\b(?:class|object|interface|enum\s+class|value\s+class|data\s+class|sealed\s+(?:class|interface)|val|fun)\s+"


# ---------------------------------------------------------------- 语料装载

def strip_code(text: str, ext: str) -> str:
    """剥注释与字符串字面量，位置按行保留（行号不漂移）。空串立即闭合，不卡剥除器。

    注释/字符串里的名字一律不算引用（同 entry-coverage 口径：不许只 grep 类名、注释不算数）。
    Kotlin 字符串插值 ${...} 连同字符串一起剥——插值引用不计，读数里如实显示。
    """
    kt_like = ext in ("kt", "kts", "java")
    nested_blocks = ext in ("kt", "kts")
    py_like = ext in ("py", "yml", "yaml")
    out = []
    i, n = 0, len(text)
    block_depth = 0
    while i < n:
        ch = text[i]
        if block_depth > 0:
            out.append(" " if ch != "\n" else "\n")
            if kt_like and ch == "*" and i + 1 < n and text[i + 1] == "/":
                out[-1] = " "; out.append(" "); i += 2; block_depth -= 1; continue
            if kt_like and nested_blocks and ch == "/" and i + 1 < n and text[i + 1] == "*":
                out.append(" "); i += 2; block_depth += 1; continue
            i += 1
            continue
        if kt_like and ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" "); i += 1
            continue
        if (kt_like or py_like) and ch == "/" and i + 1 < n and text[i + 1] == "*" and kt_like:
            out.append("  "); i += 2; block_depth += 1; continue
        if py_like and ch == "#":
            while i < n and text[i] != "\n":
                out.append(" "); i += 1
            continue
        if kt_like and text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n - 3 if j < 0 else j
            out.append(" " * (j + 3 - i)); i = j + 3
            continue
        if ch == '"' or (kt_like and ch == "'"):
            quote = ch
            out.append(" "); i += 1
            while i < n:
                c = text[i]
                if c == "\\" and i + 1 < n:
                    out.append("  "); i += 2; continue
                if c == quote:
                    out.append(" "); i += 1; break
                if c == "\n" and quote == "'":
                    out.append("\n"); i += 1; break  # 未闭合字符常量：退让不卡死
                out.append("\n" if c == "\n" else " "); i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


class SourceFile:
    __slots__ = ("rel", "text", "n_lines")

    def __init__(self, rel: str, raw: str):
        self.rel = rel
        self.text = strip_code(raw, rel.rsplit(".", 1)[-1].lower())
        self.n_lines = raw.count("\n") + 1


def load_corpus(root: str):
    prod, test = {}, {}
    for patterns, bucket in ((SRC_GLOBS, prod), (TEST_GLOBS, test)):
        for pattern in patterns:
            for path in glob.glob(os.path.join(root, pattern), recursive=True):
                if not os.path.isfile(path):
                    continue
                rel = os.path.relpath(path, root).replace("\\", "/")
                if rel in bucket:
                    continue
                try:
                    with open(path, "r", encoding="utf-8", errors="replace") as fh:
                        bucket[rel] = SourceFile(rel, fh.read())
                except OSError:
                    continue
    return prod, test


# ---------------------------------------------------------------- 三法（W3）

def scan_class_names(symbol: str, files, exclude_rels) -> list:
    """法① 类名扫描：全语料词边界检索（剥注释/字符串后）。"""
    pat = re.compile(r"\b" + re.escape(symbol) + r"\b")
    hits = []
    for rel, sf in sorted(files.items()):
        if rel in exclude_rels:
            continue
        for m in pat.finditer(sf.text):
            hits.append((rel, sf.text.count("\n", 0, m.start()) + 1))
    return hits


def extract_public_methods(symbol: str, decl: SourceFile) -> list:
    """法② 前置：锚符号声明块内的公开方法名（private/protected 除外；py 取模块级 def）。"""
    if decl.rel.endswith(".py"):
        names = re.findall(r"^def\s+([A-Za-z_][A-Za-z0-9_]*)", decl.text, re.M)
        return [x for x in names if not x.startswith("_")]
    m = re.search(KT_DECL_RE_TMPL + re.escape(symbol) + r"\b", decl.text)
    if not m:
        return []
    if m.group(0).split()[0] in ("val", "fun"):
        return []  # val/fun 锚没有方法块（消费点本来就是引用本身，归法①类名读数）
    start = decl.text.find("{", m.end())
    if start < 0:
        return []
    depth, end = 0, start
    for idx in range(start, len(decl.text)):
        if decl.text[idx] == "{":
            depth += 1
        elif decl.text[idx] == "}":
            depth -= 1
            if depth == 0:
                end = idx
                break
    body = decl.text[start:end]
    names = []
    for fm in re.finditer(r"\bfun\b\s*(?:<[^>()]*>)?\s*([A-Za-z_][A-Za-z0-9_]*)", body):
        window = body[max(0, fm.start() - 90):fm.start()].rsplit("}", 1)[-1]
        mods = re.findall(r"\b(private|protected|internal|override|inline|suspend|open|abstract|operator|infix)\b", window)
        if mods and mods[-1] in ("private", "protected"):
            continue
        names.append(fm.group(1))
    return names


def scan_public_methods(names: list, files, exclude_rels, anchor_symbol: str) -> dict:
    """法② 公开方法扫描：逐名查声明文件之外的消费点。

    同名方法全仓撞（detach/parameter 这类名字别处也有）——计数加一条限定：
    消费点文件必须同时引用锚符号（认得生产方），否则只是撞名，不算这条链的消费点。
    """
    sym_pat = re.compile(r"\b" + re.escape(anchor_symbol) + r"\b")
    out = {}
    for name in names:
        pat = re.compile(r"\b" + re.escape(name) + r"\b")
        hits = []
        for rel, sf in sorted(files.items()):
            if rel in exclude_rels:
                continue
            if not sym_pat.search(sf.text):
                continue
            for m in pat.finditer(sf.text):
                hits.append((rel, sf.text.count("\n", 0, m.start()) + 1))
        out[name] = hits
    return out


def scan_thin_wrappers(files, exclude_rels, decl_texts) -> list:
    """法③ 薄封装扫描：声明文件内 XxxLog/XxxStore/XxxRecorder/recordXxx 形薄封装及其外部消费点。

    薄封装自己的定义文件不算消费点（封装在谁家声明，谁家就排除）——这正是薄封装的意义：
    消费方只认封装不认生产方，消费点照样能被法③咬住（F5/V2 的靶心）。
    """
    # [scan3-core] 薄封装形匹配（V2 变异靶：删此法 ⇒ 自检必红）
    candidates = set()
    for text in decl_texts:
        candidates.update(WRAPPER_FORM_RE.findall(text))
    out = []
    for wrapper in sorted(candidates):
        home = set()
        decl_pat = re.compile(r"\b(?:class|object|interface|val|fun)\s+" + re.escape(wrapper) + r"\b")
        for rel, sf in files.items():
            if decl_pat.search(sf.text):
                home.add(rel)
        pat = re.compile(r"\b" + re.escape(wrapper) + r"\b")
        hits = []
        for rel, sf in sorted(files.items()):
            if rel in exclude_rels or rel in home:
                continue
            for m in pat.finditer(sf.text):
                hits.append((rel, sf.text.count("\n", 0, m.start()) + 1))
        out.append((wrapper, hits))
    return out


# ---------------------------------------------------------------- 证据与行校验

def evidence_items(evidence: str) -> list:
    items = []
    for m in EVIDENCE_RE.finditer(evidence):
        items.append((m.group(1), int(m.group(2)), int(m.group(3)) if m.group(3) else int(m.group(2))))
    return items


def verify_evidence_paths(items, root) -> list:
    errs = []
    for path, a, b in items:
        full = os.path.join(root, path.replace("/", os.sep))
        if not os.path.isfile(full):
            errs.append("证据文件不存在：%s" % path)
            continue
        try:
            with open(full, "r", encoding="utf-8", errors="replace") as fh:
                n = fh.read().count("\n") + 1
        except OSError:
            errs.append("证据文件不可读：%s" % path)
            continue
        if a > b:
            errs.append("证据行区间倒置：%s:%d-%d" % (path, a, b))
        elif b > n:
            errs.append("证据行越界：%s:%d-%d（文件仅 %d 行）" % (path, a, b, n))
    return errs


def check_zeroref_evidence(evidence: str) -> list:
    errs = []
    if "查法" not in evidence:
        errs.append("W4 零引用未写查法（「没找到」不算查法）：证据列须写明三法（类名/公开方法/薄封装）怎么查的")
    elif not any(k in evidence for k in ZERO_KEYWORDS):
        errs.append("W4 查法不含三法词（类名/方法/薄封装/检索任一）")
    return errs


KIND_BY_STATUS = {STATUS_WIRED: "wired", STATUS_PLANNED: "planned", STATUS_UNWIRED: "unwired"}


def parse_status(cell: str):
    """返回 (kind, reason, err)。kind ∈ wired/planned/unwired/never。

    闭集内但无显式判读的值（V1 变异「加第五状态」会造出这种值）按 计划中·待开 放行——
    这正是变异要咬的点：F1「待定」从判红翻成判绿，自检必红。
    """
    st = cell.strip()
    if st in STATUS_ALLOWED:
        return KIND_BY_STATUS.get(st, "planned"), "", []
    m = NEVER_RE.match(st)
    if m and m.group(1).strip():
        return "never", m.group(1).strip(), []
    return "", "", ["W2/W5 状态非法：%r（四值闭集 已接通/计划中·已实现待开/未接通/不接（理由）；「不接」必须带非空理由）" % st]


def verify_row(cells, corpus_prod, corpus_test, root, seen_anchors, rowno):
    errs, reading = [], []
    producer, output, planned, actual, evidence, status = [c.strip() for c in cells]

    kind, reason, st_errs = parse_status(status)
    errs.extend(st_errs)

    if "entry-coverage" in evidence.lower():
        errs.append("W6 证据列引用 entry-coverage——那是符号级入口清点，不许当装配证据（两处口径不许互顶）")

    items = evidence_items(evidence)
    if not items:
        errs.append("W1 证据列没有 file:行（接通证据必须落到文件与行）")
    errs.extend(verify_evidence_paths(items, root))

    zero = (kind == "unwired") or any(marker in actual for marker in ZERO_MARKERS)
    if zero:
        errs.extend(check_zeroref_evidence(evidence))

    # 锚解析（扫描锚：Symbol（path:line）＋Symbol（path:line））
    anchors = []
    seg = SCAN_ANCHOR_SEG_RE.search(evidence)
    if seg:
        for am in ANCHOR_RE.finditer(seg.group(1)):
            anchors.append((am.group(1), am.group(2).replace("\\", "/")))
    if not anchors:
        errs.append("证据列没有「扫描锚：Symbol（path:行）」——三法扫描没有落点（生产方符号怎么定位？）")

    union = dict(corpus_prod)
    for rel, sf in corpus_test.items():
        union.setdefault(rel, sf)

    decl_rels, verified, s1_ext, s2_ext, s3_ext, test_hits = set(), [], [], [], [], []
    for sym, path in anchors:
        sf = union.get(path)
        if sf is None:
            errs.append("扫描锚文件不在代码语料：%s（%s）" % (sym, path))
            continue
        decl_rels.add(path)
        if path.endswith(".py"):
            ok = os.path.splitext(os.path.basename(path))[0] == sym
        else:
            ok = re.search(KT_DECL_RE_TMPL + re.escape(sym) + r"\b", sf.text) is not None
        if not ok:
            errs.append("锚符号在声明文件里找不到声明：%s @ %s" % (sym, path))
        else:
            verified.append(sym)
        s1_ext.extend(scan_class_names(sym, corpus_prod, decl_rels))
        s1_test = scan_class_names(sym, corpus_test, decl_rels)
        test_hits.extend(("类名",) + h for h in s1_test)

        methods = extract_public_methods(sym, sf)
        usage = scan_public_methods(methods, corpus_prod, decl_rels, sym)
        for name, hits in usage.items():
            s2_ext.extend((name,) + h for h in hits)
        usage_t = scan_public_methods(methods, corpus_test, decl_rels, sym)
        for name, hits in usage_t.items():
            test_hits.extend(("方法:" + name,) + h for h in hits)

    if decl_rels:
        decl_texts = [union[p].text for p in decl_rels if p in union]
        for wrapper, hits in scan_thin_wrappers(corpus_prod, decl_rels, decl_texts):
            s3_ext.extend((wrapper,) + h for h in hits)
        for wrapper, hits in scan_thin_wrappers(corpus_test, decl_rels, decl_texts):
            test_hits.extend(("薄封装:" + wrapper,) + h for h in hits)

    for sym, _path in anchors:
        if sym in seen_anchors:
            errs.append("③ 同一条产出链两处登记：锚符号 %s 已在前面行登记过" % sym)
        seen_anchors.add(sym)

    # 判读
    human = any(marker in actual for marker in HUMAN_MARKERS)
    if kind == "wired" and not human:
        if not s2_ext and not s3_ext:
            errs.append("F2 口径：已接通但公开方法/薄封装的外部消费命中为 0——只 grep 类名不算接线，请降为 计划中·已实现待开 或补真实消费点")
    if kind == "planned" and not verified:
        errs.append("计划中·已实现待开，但锚符号一个都没定位到声明——产出不存在却报计划中")
    if kind == "unwired" and (s1_ext or s2_ext or s3_ext):
        errs.append("声称未接通，但三法扫描在生产语料发现消费点（%d 处）——请改状态或复核" % len(s1_ext + s2_ext + s3_ext))

    reading.append("[%d行 %s] 状态=%s%s" % (rowno, producer.split("（")[0].strip(), status, "（理由：%s）" % reason if kind == "never" else ""))
    for sym, path in anchors:
        s1n = len([h for h in s1_ext])
        reading.append("    锚 %s（%s）声明%s | 类名外部命中 %d | 方法外部命中 %s | 薄封装外部命中 %s | 测试面命中 %d" % (
            sym, path, "✓" if sym in verified else "✗",
            s1n,
            _summarize(s2_ext), _summarize(s3_ext), len(test_hits)))
    verdict = {None: "红", "wired": "已接通", "planned": "计划中·已实现待开", "unwired": "未接通", "never": "不接"}[kind if kind else None]
    reading.append("    判定：%s" % ("✗ " + "；".join(errs) if errs else "✓ " + verdict))
    return errs, reading


def _summarize(hits) -> str:
    if not hits:
        return "0"
    shown = ["%s@%s:%d" % (h[0], h[1], h[2]) for h in hits[:3]]
    return "%d（%s%s）" % (len(hits), "；".join(shown), "…" if len(hits) > 3 else "")


# ---------------------------------------------------------------- 表级校验

def check_table(table_path: str, root: str):
    errs, readings = [], []
    corpus_prod, corpus_test = load_corpus(root)
    try:
        with open(table_path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError as e:
        return ["登记表不可读：%s（%s）" % (table_path, e)], readings

    header_idx = None
    for i, line in enumerate(lines):
        if [c.strip() for c in line.strip().strip("|").split("|")] == HEADERS:
            header_idx = i
            break
    if header_idx is None:
        return ["W1 表头找不到六列标准头：%s" % " | ".join(HEADERS)], readings

    rows = []
    for line in lines[header_idx + 1:]:
        if not line.strip().startswith("|"):
            break
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if all(set(c) <= set("-: ") and c for c in cells):
            continue  # 分隔行
        rows.append(cells)

    if not rows:
        errs.append("W1 表头之后没有数据行")

    seen_anchors = set()
    for rowno, cells in enumerate(rows, 1):
        if len(cells) != len(HEADERS):
            errs.append("W1 第 %d 行列数 %d ≠ 6（六列齐）" % (rowno, len(cells)))
            readings.append("[%d行] 判定：✗ 列数 %d ≠ 6" % (rowno, len(cells)))
            continue
        if any(not c for c in cells):
            errs.append("W1 第 %d 行有空格子（六列齐且不留空）" % rowno)
        row_errs, row_reading = verify_row(cells, corpus_prod, corpus_test, root, seen_anchors, rowno)
        errs.extend(row_errs)
        readings.extend(row_reading)
    return errs, readings


# ---------------------------------------------------------------- 自检（夹具＋探针）

PROBE_FILES = {
    "app/src/main/java/probe/BarGauge.kt": (
        "package probe\n"
        "internal object BarGauge {\n"
        "    fun tick(): Int = 1\n"
        "}\n"
    ),
    "app/src/main/java/probe/FooSensor.kt": (
        "package probe\n"
        "internal object FooSensor {\n"
        "    fun emit(): Int = 1\n"
        "}\n"
        "object FooLog {\n"
        "    fun write(): Int = FooSensor.emit()\n"
        "}\n"
    ),
    "app/src/main/java/probe/Main.kt": (
        "package probe\n"
        "import probe.BarGauge\n"
        "import probe.FooLog\n"
        "fun drive(): Int {\n"
        "    val a = BarGauge.tick()\n"
        "    val b = FooLog.write()\n"
        "    return a + b\n"
        "}\n"
    ),
    "app/src/main/java/probe/BazKit.kt": (
        "package probe\n"
        "internal object BazKit { fun ready(): Int = 2 }\n"
    ),
    "app/src/main/java/probe/QuxLib.kt": (
        "package probe\n"
        "internal object QuxLib { fun idle(): Int = 3 }\n"
    ),
    "app/src/main/java/probe/DeadSpec.kt": (
        "package probe\n"
        "internal object DeadSpec { val NOTE = \"d\" }\n"
    ),
    "tools/gizmo.py": (
        "\"\"\"gizmo tool\"\"\"\n"
        "def main():\n"
        "    pass\n"
    ),
    "tools/IDX.md": (
        "| tools/gizmo.py | gizmo 工具行 |\n"
    ),
}


def _good_table() -> str:
    return (
        "| 生产方 | 产出 | 计划消费方 | 实际消费方 | 接通证据(file:行) | 状态 |\n"
        "|---|---|---|---|---|---|\n"
        "| BarGauge | tick 读数 | Main.drive | Main.kt 经公开方法 tick 直调 | 扫描锚：BarGauge（app/src/main/java/probe/BarGauge.kt:2）；app/src/main/java/probe/Main.kt:5 | 已接通 |\n"
        "| FooSensor | 影子写入（经薄封装 FooLog） | Main.drive | Main.kt 只经 FooLog.write 薄封装消费（FooSensor 名字不出现在消费文件） | 扫描锚：FooSensor（app/src/main/java/probe/FooSensor.kt:2）；app/src/main/java/probe/Main.kt:6 | 已接通 |\n"
        "| BazKit | 待开能力 | 后续装配单 | 零生产消费（实现先行） | 扫描锚：BazKit（app/src/main/java/probe/BazKit.kt:2）；零引用查法：三法（类名/公开方法/薄封装）检索 0 外部命中 | 计划中·已实现待开 |\n"
        "| QuxLib | 空转库 | 无 | 零引用 | 扫描锚：QuxLib（app/src/main/java/probe/QuxLib.kt:2）；零引用查法：三法（类名/公开方法/薄封装）对生产语料检索全 0 | 未接通 |\n"
        "| DeadSpec | 边界声明件 | 按设计零消费 | 零消费（判据钉在测试） | 扫描锚：DeadSpec（app/src/main/java/probe/DeadSpec.kt:2）；零引用查法：三法检索 0 命中，按设计不接生产 | 不接（理由：口径件按设计零消费，只被对账） |\n"
        "| gizmo | 工具产物 | 人工对账 | 人工（机器零引用） | 扫描锚：gizmo（tools/gizmo.py:1）；tools/IDX.md:1；机器零引用查法：全仓词检索仅命中登记行 | 已接通 |\n"
    )


def _build_probe(root: str):
    for rel, text in PROBE_FILES.items():
        full = os.path.join(root, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as fh:
            fh.write(text)


def _write_table(root: str, name: str, text: str) -> str:
    docs = os.path.join(root, "docs")
    os.makedirs(docs, exist_ok=True)
    path = os.path.join(docs, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def selftest() -> int:
    results = []
    failures = 0

    def expect(tag, cond, detail=""):
        nonlocal failures
        results.append((tag, cond, detail))
        if not cond:
            failures += 1

    with tempfile.TemporaryDirectory() as td:
        _build_probe(td)
        corpus_prod, corpus_test = load_corpus(td)

        # 三法探针（W3：三法缺一 ⇒ 自检红；V2 靶）
        p1 = scan_class_names("BarGauge", corpus_prod, set())
        expect("探针①类名扫描", any(h[0].endswith("Main.kt") for h in p1), "类名外部命中读数=%s" % (p1,))
        methods = extract_public_methods("BarGauge", corpus_prod["app/src/main/java/probe/BarGauge.kt"])
        expect("探针②公开方法提取", "tick" in methods, "methods=%s" % methods)
        usage = scan_public_methods(["tick"], corpus_prod, {"app/src/main/java/probe/BarGauge.kt"}, "BarGauge")
        expect("探针②公开方法外部消费", len(usage.get("tick", [])) == 1, "tick 外部=%s" % usage.get("tick"))
        p3 = scan_thin_wrappers(corpus_prod, {"app/src/main/java/probe/FooSensor.kt"},
                                [corpus_prod["app/src/main/java/probe/FooSensor.kt"].text])
        expect("探针③薄封装扫描", any(w == "FooLog" and h for w, h in p3), "薄封装=%s" % (p3,))

        # F1 状态写「待定」 ⇒ RED
        f1 = _write_table(td, "f1.md", _good_table().replace("计划中·已实现待开 |", "待定 |"))
        errs, _ = check_table(f1, td)
        expect("F1 状态写待定 ⇒ RED", any("W2" in e for e in errs) and len(errs) == 1, "errs=%s" % errs)

        # F2 只 grep 类名 ⇒ RED（QuxLib 谎报已接通，仅类名证据）
        f2 = _write_table(td, "f2.md", _good_table().replace(
            "| QuxLib | 空转库 | 无 | 零引用 | 扫描锚：QuxLib（app/src/main/java/probe/QuxLib.kt:2）；零引用查法：三法（类名/公开方法/薄封装）对生产语料检索全 0 | 未接通 |",
            "| QuxLib | 空转库 | Main.drive | Main.kt（类名检索有命中） | 扫描锚：QuxLib（app/src/main/java/probe/QuxLib.kt:2）；app/src/main/java/probe/QuxLib.kt:1；类名检索有命中 | 已接通 |"))
        errs, _ = check_table(f2, td)
        expect("F2 只 grep 类名 ⇒ RED", any("F2 口径" in e for e in errs), "errs=%s" % errs)

        # F3 零引用写「没找到」 ⇒ RED
        f3 = _write_table(td, "f3.md", _good_table().replace(
            "零引用查法：三法（类名/公开方法/薄封装）对生产语料检索全 0", "没找到"))
        errs, _ = check_table(f3, td)
        expect("F3 零引用写没找到 ⇒ RED", any("W4" in e for e in errs), "errs=%s" % errs)

        # F4 「不接」无理由 ⇒ RED
        f4 = _write_table(td, "f4.md", _good_table().replace(
            "不接（理由：口径件按设计零消费，只被对账）", "不接（）"))
        errs, _ = check_table(f4, td)
        expect("F4 不接无理由 ⇒ RED", any("W2/W5" in e for e in errs), "errs=%s" % errs)

        # F5 六列齐、四值、三法、零引用写清查法 ⇒ GREEN
        f5 = _write_table(td, "f5.md", _good_table())
        errs, readings = check_table(f5, td)
        expect("F5 良表 ⇒ GREEN", not errs, "errs=%s" % errs)
        expect("F5 薄封装行经法③证实", any("FooLog@app/src/main/java/probe/Main.kt" in r for r in readings),
               "薄封装行读数见上")

        # F6 895 预填行并进真表（file:行 在当前基线逐条存在） ⇒ GREEN（对真仓跑）
        real_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        real_table = os.path.join(real_root, DEFAULT_TABLE.replace("/", os.sep))
        errs, readings = check_table(real_table, real_root)
        expect("F6 真表（含 895 预填行）⇒ GREEN", not errs, "errs=%s" % errs)
        expect("F6 895 行在表", "CompoundWiring（app/src/main/java/com/example/zhengdao/baopu/state/cavity/CompoundWiring.kt:12）" in
               open(real_table, encoding="utf-8").read(), "895 行缺失")

    for tag, ok, detail in results:
        print(("  PASS " if ok else "  RED  ") + tag + ("" if ok else "  —— " + detail))
    print("SELFTEST %s（%d/%d 过）" % ("GREEN" if failures == 0 else "RED", len(results) - failures, len(results)))
    return 0 if failures == 0 else 1


# ---------------------------------------------------------------- 入口

def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description="装配登记表三法扫描校验（894）")
    ap.add_argument("--table", default=None, help="登记表路径（默认 <仓>/docs/装配登记表.md）")
    ap.add_argument("--root", default=None, help="仓库根（默认 tools/ 的上一级）")
    ap.add_argument("--selftest", action="store_true", help="跑夹具 F1–F6 与三法探针")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    root = args.root or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    table = args.table or os.path.join(root, DEFAULT_TABLE.replace("/", os.sep))
    corpus_prod, corpus_test = load_corpus(root)
    errs, readings = check_table(table, root)
    for line in readings:
        print(line)
    print()
    if errs:
        print("check-wiring: RED —— %d 条问题" % len(errs))
        for e in errs:
            print("  ✗ " + e)
        return 1
    print("check-wiring: GREEN —— 六列齐·四值·三法读数齐·证据 file:行 全部落点")
    return 0


if __name__ == "__main__":
    sys.exit(main())
