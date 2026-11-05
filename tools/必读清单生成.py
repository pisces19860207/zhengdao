#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""必读清单生成.py —— `/init` 式的「开工先读哪几页」清单生成器（第四百三十六批 A · ②）。

为什么是脚本、不是自觉
----------------------
用户 2026-11-04 拆了外部执行体 ZCode 的机制：它的「先读」不是模型自觉，是三个件——
系统提示里固定那段 `# Harness`、`AGENTS.md` 是一等公民、**还有一条 `/init` 命令专门扫工作区
去创建或更新它**。本脚本就是那条 `/init` 在本仓的对应物（派工单 436 追加二 ⑥）。
原话照抄一条：`Existing AGENTS.md files should be edited rather than overwritten`
——**已存在则增改不覆盖**，这是本脚本的硬判据，不是风格。

一条命令
--------
    python tools/必读清单生成.py            # 扫工作区 ⇒ 两份一起产（公开版＋全量版）
    python tools/必读清单生成.py --public   # 只写公开版（<仓>/docs/必读清单.md）
    python tools/必读清单生成.py --private  # 只写全量版（<_zd-private>/docs/必读清单全量.md）
    python tools/必读清单生成.py --leak-check  # 只查不写：公开文件里出现私有原文特征 ⇒ rc=1
    python tools/必读清单生成.py --selftest # 内置判据自测（临时目录，不碰真文件）
    python tools/必读清单生成.py --dry-run  # 只打印，不落盘

两份产物（用户 2026-11-05 裁定：按「两份清单」处理，不撤回也不留原文）
--------------------------------------------------------------------
* **公开版**（进仓）只放指针，一行四列：`主题 ｜ 类型 ｜ 指向 ｜ 为什么`。
  来源在仓外（`_zd-private`／`_zd-toolroom`）的那 41 条**原文一字不留**——
  主题只留机器标识（坑账留 `E-177`、派工单留 `第四百四十七批`、其余留**文件名**，路径可以留）；
  同一来源里挑不出标识的（名册那张工具表）⇒ 整本收成一行「`_索引（16 条）`」。
  「为什么」是本脚本写死的一句（按节给），**不是**从来源摘的。
* **全量版**（私有侧）＝原来那一份：主题＋摘要＋来源＋最后更新＋新鲜度，一条不少。
* **不许手改产物**：两份都由这一条命令重写；要长期自己加条目，写在生成段之外。
* **写公开版之前先过泄露自检**（`--leak-check` 单跑也一样，命中就 fail-closed 两份都不写）：
  ① 特征串＝私有侧的原文（本轮条目的标题与摘要 ＋ ERRATA／工具房索引／协议正文行 ＋
     **全部**派工单与台账的标题；不按 `--max-*` 截断，否则上一版留下的旧行没人认得住）；
  ② 扫之前先把「指向／来源」那一格挖掉——裁定明写**路径可留**，原文才是不许留的东西；
  ③ 再加一道形状闸：公开文件里出现「全量形状（`— 摘要 ｜ 来源：`）＋ 来源在仓外」的整行一律红。
* **迁移**：仓里已躺着一版**全量形状**的公开产物时，脚本认得自己旧行（两种形状都匹配），
  把来源在仓外的那些行**重画成指针**（并如实报扔了几行），表头是本脚本署名的（认得出 `GEN_MARKER`
  且没被人插过行）⇒ 跟着换成公开版那句；真·人加的行照旧逐字保留。

扫的五类（派工单点名的五类，一类一节）
------------------------------------
    一、开工规矩   `<仓>/AGENTS.md` 的每个 `## ` 小节
    二、协作与协议 `<仓>/docs/协作规约.md` ＋ `<工作区>/_zd-private/docs/协议-*.md`
    三、坑账       `_zd-private/docs/ERRATA.md` 最近 N 条 `## E-xxx · 日期 · 标题`
    四、名册       `_zd-toolroom/_索引.md` 的工具表（一个工具一行）
    五、最近裁定   `_zd-private/docs/派工单-*.md` 最近 N 单 ＋ 全量台账那一页

判据（`--selftest` 逐条钉住）
----------------------------
    ① 一条命令能生成                → T1
    ② 只增不毁：人工条目原样保留     → T2（段外、段内两种位置都测）＋ 负例（覆盖式写法必然毁掉它）
    ③ 幂等：连跑两次逐字节相同       → T3（两份各测一遍）
    ④ 过期要看得出来（>90 天 ⇒ 标「待复核」，AGENTS.md §稳定层与易变层同口径）→ T4
    ⑤ 缺件不许静默（没扫到就明写「未列」，不假装清单完整）→ T5
    ⑥ 来源是表格时的形状：每张表各自丢表头/分隔行、摘要不许借下一节的正文 → T1d/T1e
    ⑦ 公开版只放指针：仓外来源的标题与摘要一律不进公开文件（路径除外），名册整本收成一行 → T6／T6b／T6c
    ⑧ 泄露自检有牙：把私有侧那一行原样抄进公开版必须红 → T7b（正例）；真产物扫得过 → T7（负例）
    ⑨ 拆分不是删内容：全量版里那些原文照在 → T8
    ⑩ 迁移那一趟不毁人加的：旧全量行重画成指针、表头跟着换、真·人加的行逐字留 → T9／T9b／T9c（＋T9d 迁移后幂等）

写盘的规矩
----------
* 只重写 `<!-- ⚙ 生成段 BEGIN … END -->` 之间的内容；段外的字节一个不动。
* 生成段里凡不是本脚本产出的行（人加的条目、人加的小节）⇒ 逐字收进段尾的「### 人工条目」，
  顺序不变。⇒ 想长期自己维护条目，请写在**段外**（文件末尾「## 人工补充」那一节最稳）。
* 纯标准库、不联网；账龄只按**天**比 ⇒ 同一天内连跑结果逐字节相同（判据③的前提）。
* 产物按 **CRLF** 写（本仓工作树全量是 CRLF；`core.autocrlf=true` ⇒ 入库 blob 仍是 LF）。
* 时间来源：仓库内文件问 git（提交日，跨机器稳），问不到退到文件 mtime。

退出码：0 成功；1 判据不满足（含 `--selftest` 失败）；2 用法/环境错误（不像仓库根）。
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

try:  # 本机控制台默认 GBK：中文与 ✔/✘ 会被吃成问号
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - 尽力而为
    pass

TITLE = "必读清单生成（436-A ②）"
BEGIN = "<!-- ⚙ 必读清单·生成段 BEGIN（tools/必读清单生成.py 重写这一段；段内非脚本产出的行逐字保留） -->"
END = "<!-- ⚙ 必读清单·生成段 END -->"
HUMAN_HEAD = "### 人工条目（自动收拢，未改动一个字）"
HUMAN_NOTE = "> 下面这些不是本脚本产出的（人加的条目／人加的小节），逐字保留、顺序不变。"
STALE_DAYS = 90  # AGENTS.md §稳定层与易变层：默认 90 天过期
GIST_MAX = 100
MISSING_MARK = "**未列**"
# 产物按本仓工作树的行尾写（全仓 checkout 是 CRLF，`core.autocrlf=true` ⇒ 入库 blob 仍是 LF）。
# 写成 LF 会让下一次 re-run 把整篇行尾换一遍，"逐字节无变化"就成了假话。
NL = "\r\n"

DATE_RE = re.compile(r"(20\d{2})-(\d{2})-(\d{2})")
H2_RE = re.compile(r"^##\s+(.*\S)\s*$")
ERRATA_RE = re.compile(r"^##\s+E-(\d+)\s*·\s*(20\d{2}-\d{2}-\d{2})\s*·\s*(.*\S)\s*$")
ENTRY_RE = re.compile(r"^- \*\*(.+?)\*\* — .*｜ 来源：`(.+?)`")
# 公开版那一行的形状（指针四列）：harvest 必须也认它，否则「上一轮自己的产出」会被当人加的重复收
POINTER_RE = re.compile(r"^- \*\*(.+?)\*\* ｜ 类型：.+?｜ 指向：`(.+?)`")
# 泄露自检的最短特征串：短于这个的（`E-177`、`L21`）是标识不是原文
LEAK_MIN = 8

# 公开版「为什么」那一列：本脚本写死的一句话，**不从来源摘**（摘了就等于把原文搬进公开仓）
WHY = {
    "开工规矩": "开工前必须照做的入口规矩",
    "协作与协议": "多会话共改同一面，动手前先对这一节",
    "坑账": "已经记过一次的坑，不许踩第二次（内容看那本账）",
    "名册": "本机工具在哪、谁在用（逐个看索引那张表）",
    "最近裁定": "最近拍板的方向，别照旧结论干活",
}
# 这一节挑不出机器标识（工具名本身就是私有内容）⇒ 公开版按来源整本收成一行
COLLAPSE_SECTIONS = {"名册"}

FULL = "full"        # 全量版（私有侧）：主题＋摘要＋来源＋最后更新＋新鲜度
POINTER = "pointer"  # 公开版（进仓）：只放指针，来源在仓外的原文一字不留
PRIVATE_PREFIXES = ("_zd-private/", "_zd-toolroom/")
# 表头里这句是本脚本自己的署名：段外那块只要还认得出是脚本写的，换形状时就由脚本改写
GEN_MARKER = "这一页由 `python tools/必读清单生成.py` 扫工作区生成"

SECTIONS = [
    ("开工规矩", "一、开工规矩（AGENTS.md）"),
    ("协作与协议", "二、协作与协议"),
    ("坑账", "三、坑账（ERRATA 最近条目，全量在那本账）"),
    ("名册", "四、名册（工具房索引）"),
    ("最近裁定", "五、最近裁定（派工单与全量台账）"),
]
EMPTY_LINE = "（这一类没有条目——不是没有规矩，是本机没扫到源文件）｜ " + MISSING_MARK
MACHINE_HEADS = ({f"## {h}" for _s, h in SECTIONS} | {HUMAN_HEAD, HUMAN_NOTE, f"- {EMPTY_LINE}"})


def is_private(source: str) -> bool:
    """来源在仓外 ⇒ 这一条的正文不许进公开仓（AGENTS.md：工程文档已移出公开仓，2026-10-20）。"""
    return source.startswith(PRIVATE_PREFIXES)


def handle(entry):
    """公开版「主题」那一格：只留机器标识；挑不出标识 ⇒ None（该来源收成一行）。"""
    if entry.note == MISSING_MARK or not is_private(entry.source):
        return entry.title  # 缺件行本来没正文；仓内来源（AGENTS.md／协作规约）本来就在公开仓里
    m = re.match(r"^(E-\d+)", entry.title)
    if m:
        return m.group(1)
    if entry.section in COLLAPSE_SECTIONS:
        return None
    b = re.search(r"(第[0-9一二三四五六七八九十百零两]+批)", Path(entry.source).name)
    return b.group(1) if b else None


class Entry:
    """一条清单条目。机器条目的身份由 (节, 标题, 来源) 决定——合并就按这个键认。"""

    __slots__ = ("section", "title", "gist", "source", "updated", "note")

    def __init__(self, section, title, gist, source, updated=None, note=""):
        self.section = section
        self.title = title
        self.gist = gist
        # 来源里不许有反引号：条目的身份靠「来源」那一格解析，多一个反引号就解析错位
        # ⇒ 下一轮把它当人加的条目重复收（实测破坏判据③幂等）。
        self.source = source.replace("`", "").strip()
        self.updated = updated  # "YYYY-MM-DD" 或 None
        self.note = note

    @property
    def key(self):
        return (self.section, self.title, self.source)

    def line(self, shape=FULL):
        stamp = self.updated or "无日期"
        note = self.note or "无状态"
        if shape == POINTER:
            kind = f"{self.section}（{'仓外' if is_private(self.source) else '仓内'}）"
            return (f"- **{self.title}** ｜ 类型：{kind} ｜ 指向：`{self.source}`"
                    f" ｜ 为什么：{WHY[self.section]} ｜ 最后更新：{stamp} ｜ {note}")
        gist = clip(self.gist) or "（这一条没有摘要，点开来源文件看）"
        return f"- **{self.title}** — {gist} ｜ 来源：`{self.source}` ｜ 最后更新：{stamp} ｜ {note}"


def clip(text: str) -> str:
    """把来源里的第一行正文折成一行摘要：去 markdown 记号、压空白、限长。"""
    t = re.sub(r"^[>\-\*\s]+", "", (text or "").strip())
    t = re.sub(r"[`*_#]", "", t)
    t = t.replace("|", "／")  # 表格行的摘要：竖线留着会把这一行读成另一张表
    t = re.sub(r"\s+", " ", t)
    return t[:GIST_MAX].rstrip() + "…" if len(t) > GIST_MAX else t


def parse_day(text):
    m = DATE_RE.search(text or "")
    if not m:
        return None
    try:
        return dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def freshness(updated, today: dt.date) -> str:
    """判据④那一列：新鲜／待复核／缺日期。"""
    if updated is None:
        return "无日期（不许当真理用）"
    age = (today - updated).days
    if age > STALE_DAYS:
        return f"待复核（已 {age} 天，超过 {STALE_DAYS} 天阈值）"
    return f"新鲜（{age} 天前）"


def file_stamp(path: Path, root: Path) -> str:
    """一个文件「最后更新」哪天：先问 git（提交日，跨机器稳），问不到退到 mtime。"""
    try:
        rel = Path(path).resolve().relative_to(Path(root).resolve()).as_posix()
        out = subprocess.run(
            ["git", "-C", str(root), "log", "-1", "--format=%ad", "--date=short", "--", rel],
            capture_output=True, text=True, timeout=10,
        )
        day = parse_day(out.stdout or "")
        if day:
            return day.isoformat()
    except Exception:  # noqa: BLE001 - 不在 git 里 / git 不可用，都算正常回退
        pass
    try:
        return dt.date.fromtimestamp(path.stat().st_mtime).isoformat()
    except OSError:
        return None


def is_table_sep(s: str) -> bool:
    """`|---|---|` 这种分隔行。"""
    return s.startswith("|") and set(s) <= set("|-—: \t")


def table_blocks(lines) -> list:
    """连续以 `|` 开头的行切成若干表格块（一个文件里可以有好几张表）。"""
    blocks, cur = [], []
    for raw in lines:
        s = raw.strip()
        if s.startswith("|"):
            cur.append(s)
        elif cur:
            blocks.append(cur)
            cur = []
    if cur:
        blocks.append(cur)
    return blocks


def data_rows(rows) -> list:
    """一张表 ⇒ 数据行。

    有分隔行就说明第一行是表头 ⇒ 表头和分隔行都不许当条目（真实 `_索引.md` 有好几张表，
    整份文件切 `rows[2:]` 会把第二张表的表头当成数据）。
    """
    if any(is_table_sep(r) for r in rows):
        return [r for r in rows[1:] if not is_table_sep(r)]
    return rows


def first_body_line(lines, after):
    """本节里第一行像正文的东西。

    越过下一个二级小节头就停——早先不封顶，`## 7`（整节是表格）会一路找到第九节，
    于是三节的摘要一模一样，清单看着齐其实是假话。
    整节只有表格 ⇒ 退回那张表的第一行数据。
    """
    fence = False
    section_lines = []
    for raw in lines[after + 1:]:
        s = raw.strip()
        if H2_RE.match(s):
            break
        section_lines.append(s)
        if s.startswith("```"):
            fence = not fence
            continue
        if fence or not s or s.startswith("#") or s.startswith("|") or is_table_sep(s):
            continue
        if set(s) <= set("-—_*: \t"):  # 分隔线/装饰行不是正文
            continue
        return s
    for block in table_blocks(section_lines):
        rows = data_rows(block)
        if rows:
            return rows[0].strip().strip("|").strip()
    return ""


def first_line(lines):
    for l in lines:
        if l.strip():
            return l
    return ""


def missing(section, source, why):
    """缺件条目：宁可写「没这本账」，也不静默少一节（判据⑤）。"""
    return [Entry(section, f"（缺件）{source}", why, source, None, note=MISSING_MARK)]


def section_entries(text, section, source, updated, limit=None):
    lines = text.splitlines()
    out = []
    for i, line in enumerate(lines):
        m = H2_RE.match(line)
        if not m:
            continue
        out.append(Entry(section, m.group(1), first_body_line(lines, i), source, updated))
        if limit and len(out) >= limit:
            break
    return out


# ── 五类来源 ────────────────────────────────────────────────────────────


def collect_agents(repo: Path) -> list:
    f = repo / "AGENTS.md"
    if not f.is_file():
        return missing("开工规矩", "AGENTS.md", "本仓没有 AGENTS.md")
    return section_entries(read(f), "开工规矩", "AGENTS.md", file_stamp(f, repo))


def collect_collab(repo: Path, workspace: Path) -> list:
    out = []
    f = repo / "docs" / "协作规约.md"
    if f.is_file():
        out += section_entries(read(f), "协作与协议", "docs/协作规约.md", file_stamp(f, repo))
    else:
        out += missing("协作与协议", "docs/协作规约.md", "本仓没有这一页")
    priv = workspace / "_zd-private" / "docs"
    protocols = sorted(priv.glob("协议-*.md"))
    if not protocols:
        out += missing(
            "协作与协议", "_zd-private/docs/协议-*.md",
            "本机没有私有侧协议目录（换台机器就这样，不是协议没了）",
        )
    for p in protocols:
        lines = read(p).splitlines()
        head = first_line(lines)
        title = re.sub(r"^#+\s*", "", head).strip() if head.startswith("#") else p.stem
        day = parse_day(p.stem)
        at = lines.index(head) if head in lines else 0
        out.append(
            Entry("协作与协议", title or p.stem, first_body_line(lines, at),
                  f"_zd-private/docs/{p.name}", day.isoformat() if day else file_stamp(p, workspace))
        )
    return out


def collect_pits(workspace: Path, limit: int) -> list:
    f = workspace / "_zd-private" / "docs" / "ERRATA.md"
    src = "_zd-private/docs/ERRATA.md"
    if not f.is_file():
        return missing("坑账", src, f"本机没有这本账 ⇒ 最近 {limit} 条没列，不是坑清了")
    lines = read(f).splitlines()
    hits = [(i, ERRATA_RE.match(l)) for i, l in enumerate(lines)]
    tail = [(i, m) for i, m in hits if m][-limit:]
    if not tail:
        return missing("坑账", src, "这本账里一条 E 号都没解析出来（形状变了，请改本脚本）")
    return [
        Entry("坑账", f"E-{m.group(1)} {m.group(3)}", first_body_line(lines, i), src, m.group(2))
        for i, m in tail
    ]


def collect_roster(workspace: Path) -> list:
    for name in ("_索引.md", "_index.md"):
        f = workspace / "_zd-toolroom" / name
        if not f.is_file():
            continue
        src = f"_zd-toolroom/{name}"
        out = []
        for block in table_blocks(read(f).splitlines()):
            for r in data_rows(block):  # 每张表各自丢表头与分隔行
                cells = [c.strip() for c in r.strip().strip("|").split("|")]
                if len(cells) < 3:
                    continue
                tool = re.sub(r"[`*_]", "", cells[0])
                out.append(
                    Entry("名册", tool,
                          f"{cells[2]}（位置：{cells[1]}；谁在用：{cells[3] if len(cells) > 3 else '—'}）",
                          src, file_stamp(f, workspace))
                )
        return out or missing("名册", src, "索引里那张工具表没解析出行（形状变了）")
    return missing("名册", "_zd-toolroom/_索引.md", "本机没有工具房目录 ⇒ 名册没列")


def collect_rulings(workspace: Path, limit: int) -> list:
    priv = workspace / "_zd-private" / "docs"
    if not priv.is_dir():
        return missing("最近裁定", "_zd-private/docs/", "本机没有私有侧目录 ⇒ 最近裁定没列")
    out = []
    for p in sorted(priv.glob("派工单-2*.md"))[-limit:]:
        lines = read(p).splitlines()
        head = first_line(lines)
        day = parse_day(p.name)
        out.append(
            Entry("最近裁定", re.sub(r"^#+\s*", "", head).strip() or p.stem,
                  first_body_line(lines, lines.index(head) if head in lines else 0),
                  f"_zd-private/docs/{p.name}", day.isoformat() if day else None)
        )
    ledgers = sorted(priv.glob("FEATURE-LEDGER-全量-*.md"))
    if ledgers:
        g = ledgers[-1]
        out.append(
            Entry("最近裁定", "全量台账 · 结账总表（新会话先读这个）",
                  "已做完／待做／明确不做的总表；§2 待做与 §3 不做在同一个文件里",
                  f"_zd-private/docs/{g.name}", file_stamp(g, workspace))
        )
    return out or missing("最近裁定", "_zd-private/docs/派工单-*.md", "私有目录里没有派工单")


# ── 生成与合并 ──────────────────────────────────────────────────────────


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")


def gather(repo: Path, workspace: Path, max_pits: int, max_rulings: int) -> dict:
    return {
        "开工规矩": collect_agents(repo),
        "协作与协议": collect_collab(repo, workspace),
        "坑账": collect_pits(workspace, max_pits),
        "名册": collect_roster(workspace),
        "最近裁定": collect_rulings(workspace, max_rulings),
    }


def pointer_rows(entries) -> list:
    """公开版的行集合：挑得出标识的一条一行；挑不出的（名册那张表）同一来源收成一行。

    工具名／标题句子本身都是私有内容 ⇒ 公开版只留 `E-177`、`第四百四十七批`、文件名这三种标识。
    """
    rows, groups = [], {}
    for e in entries:
        h = handle(e)
        if h is None:
            g = groups.setdefault(e.source, [len(rows), 0, e.updated, e.section])
            g[1] += 1
            if e.updated and (g[2] is None or e.updated > g[2]):
                g[2] = e.updated
            continue
        rows.append(Entry(e.section, h, "", e.source, e.updated, e.note))
    for source, (pos, n, day, section) in sorted(groups.items(), key=lambda kv: kv[1][0]):
        rows.insert(pos, Entry(section, f"{Path(source).stem}（{n} 条）", "", source, day))
    return rows


def render_region(groups: dict, today, shape=FULL) -> tuple:
    """生成段本体（不含哨兵）。返回 (行, 本轮机器条目键集合)。"""
    out, keys = [], set()
    for section, heading in SECTIONS:
        out.append(f"## {heading}")
        entries = groups.get(section, [])
        if not entries:
            out.append(f"- {EMPTY_LINE}")
        if shape == POINTER:
            # 迁移那一趟用的：旧的「全量形状」行也是本脚本产出的 ⇒ 认下来重画，别把原文留在仓里
            for e in entries:
                keys.add(e.key)
        for e in (pointer_rows(entries) if shape == POINTER else entries):
            if not e.note:
                e.note = freshness(parse_day(e.updated), today)
            out.append(e.line(shape))
            keys.add(e.key)
        out.append("")
    return out, keys


def private_full_lines(text: str) -> list:
    """全量形状（`— 摘要 ｜ 来源：`）且来源在仓外的行 ⇒ 公开版里一条都不许有（判据⑦）。"""
    hits = []
    for line in text.split("\n"):
        m = ENTRY_RE.match(line)
        if m and is_private(m.group(2)):
            hits.append(line)
    return hits


# 脚本自己写得出来的加粗标识（人写的行不长这样：真·人加的那行是长标题＋状态／承接／分支／工作树）
MACHINE_HANDLES = (re.compile(r"^E-\d+$"),
                   re.compile(r"^第[零一二三四五六七八九十百千万两\d]+批$"),
                   re.compile(r"^_索引（\d+ 条）$"))
TYPE_WHY_RE = re.compile(r"^- \*\*.+?\*\* ｜ 类型：(.+?)（(?:仓内|仓外)） ｜ 指向：`.*?` ｜ 为什么：(.+?) ｜ 最后更新：")


def is_machine_line(line: str) -> bool:
    """这一行是不是本脚本（任何一版）写得出来的形状 ⇒ 淘汰它不算「毁掉人工条目」。

    两条签名任一命中就够了，两条都是**只有脚本才会写**的东西：
      ① 加粗那一格正好落在脚本那三种短标识语法里（`E-177`／`第四百四十二批`／`_索引（16 条）`）；
      ② 公开形状里「类型：X（仓内／仓外）」＋「为什么：<WHY 表原句>」这两格是脚本写死的。
    认不出的一律当人加的逐字留下——判据②「只增不毁」优先于清库存。
    不这么认，本轮不再产出的旧行（`_索引` 的条数变了、某批掉出 --max-rulings 窗口）会被洗成
    「人工条目」：同一页留下自相矛盾的两条，而且窗口每轮只增不减。
    """
    m = ENTRY_RE.match(line) or POINTER_RE.match(line)
    if not m:
        return False
    if any(rx.match(m.group(1)) for rx in MACHINE_HANDLES):
        return True
    w = TYPE_WHY_RE.match(line)
    return bool(w and w.group(1) in WHY and w.group(2) == WHY[w.group(1)])


def harvest(block: list, keys, shape=FULL) -> tuple:
    """生成段里「不是本脚本这一轮产出的」行 ⇒ 逐字保留（含它的缩进续行）。

    返回 (留下的行, 重画的旧全量形状行数, 淘汰的脚本自己旧行数)——
    扔的那些行内容都在来源里，人加的一行不少。
    """
    keep, dropped, superseded, last_kept = [], 0, 0, False
    for line in block:
        if not line.strip():
            continue
        if line.strip() in MACHINE_HEADS:
            last_kept = False
            continue
        if line.startswith(("  ", "\t")):
            if last_kept:
                keep.append(line)
            continue
        m = ENTRY_RE.match(line) or POINTER_RE.match(line)  # 两种形状都得认（全量版／公开版）
        if m and any((sec, m.group(1), m.group(2)) in keys for sec, _h in SECTIONS):
            last_kept = False  # 机器条目：这一轮会重画，不重复收
            continue
        if shape == POINTER and m and ENTRY_RE.match(line) and is_private(m.group(2)):
            dropped += 1  # 上一版全量形状留下的私有原文行 ⇒ 重画成指针，不许当人加的留在公开仓
            last_kept = False
            continue
        if m and is_machine_line(line):
            superseded += 1  # 脚本自己上一版的产出、本轮不再列出 ⇒ 淘汰，不许洗成「人工条目」
            last_kept = False
            continue
        keep.append(line)
        last_kept = True
    return keep, dropped, superseded


def merge(existing: str, region: list, keys, shape=FULL) -> tuple:
    """已存在则增改不覆盖：只换两个哨兵之间；段外原样，段内人工行收进段尾。

    返回 (文本, 重画的旧全量行数, 淘汰的脚本自己旧行数)。表头若还认得出是本脚本署名的（有 GEN_MARKER、
    且没被人插进非 `#`／`>` 的行）⇒ 换形状时跟着换；认不出（人改过）就一个字不动。
    """
    lines = existing.split("\n") if existing else []
    if BEGIN in lines and END in lines:
        i, j = lines.index(BEGIN), lines.index(END)
        preserved, dropped, superseded = harvest(lines[i + 1:j], keys, shape)
        head = lines[:i]
        if (GEN_MARKER in "\n".join(head)
                and all(not l.strip() or l.startswith(("#", ">")) for l in head)):
            head = header_lines(shape)
        body = region + render_preserved(preserved)
        return "\n".join(head + [BEGIN] + body + lines[j:]), dropped, superseded
    # 老文件没有生成段（别的批次手写过）⇒ 一个字不删，把段追加到文件末尾。
    tail = ["", BEGIN] + region + [END, ""]
    return "\n".join(lines + tail), 0, 0


def render_preserved(preserved: list) -> list:
    if not preserved:
        return []
    return [HUMAN_HEAD, HUMAN_NOTE, ""] + preserved + [""]


def header_lines(shape) -> list:
    if shape == POINTER:
        return [
            "# 必读清单（开工先读这一页·公开版＝只放指针）",
            "",
            "> 第四百三十六批 A ②：这一页由 `python tools/必读清单生成.py` 扫工作区生成，**只增不毁**——",
            "> 生成段每次重写，段外的、以及段内不是脚本产出的条目一个字不动（派工单原话：已存在则增改不覆盖）。",
            "> **本仓是公开仓 ⇒ 这一份只有指针**：主题｜类型｜指向｜为什么，来源在仓外（`_zd-private`／`_zd-toolroom`）的",
            "> 那些条目**原文一个字都不在这里**（只留编号、批号或文件名）；**全文在私有侧** `_zd-private/docs/必读清单全量.md`。",
            f"> 每条带 **指向**（来源）与 **最后更新**；超过 {STALE_DAYS} 天没复核的标「待复核」（AGENTS.md §稳定层与易变层同口径）。",
            "> 扫的五类：开工规矩／协作与协议／坑账／名册／最近裁定。某一类没扫到就明写「未列」，不假装清单完整。",
            "",
        ]
    return [
        "# 必读清单全量（开工先读这一页·私有版＝一条不落）",
        "",
        "> 第四百三十六批 A ②：这一页由 `python tools/必读清单生成.py` 扫工作区生成，**只增不毁**——",
        "> 生成段每次重写，段外的、以及段内不是脚本产出的条目一个字不动（派工单原话：已存在则增改不覆盖）。",
        "> 这一份带原文摘要，**放私有侧不进公开仓**；公开那份（`<仓>/docs/必读清单.md`）只有指针，两份同一条命令产出。",
        f"> 每条带 **来源** 与 **最后更新**；超过 {STALE_DAYS} 天没复核的标「待复核」（AGENTS.md §稳定层与易变层同口径）。",
        "> 扫的五类：开工规矩／协作与协议／坑账／名册／最近裁定。某一类没扫到就明写「未列」，不假装清单完整。",
        "",
    ]


TAIL_LINES = [
    "",
    "## 人工补充（脚本不碰这一节）",
    "",
    "· 要加自己那份必读条目，写在这一节里（生成段之外）——脚本永远不会动这一节。",
    "· 写在生成段**里面**的也会被逐字保留，只是会被收进段尾的「人工条目」那一小节。",
]


def fresh_file(shape) -> str:
    return "\n".join(header_lines(shape) + [BEGIN, "", END] + TAIL_LINES) + "\n"


def build(repo: Path, workspace: Path, today, max_pits: int, max_rulings: int,
          out: Path, shape=FULL) -> str:
    text, _dropped, _superseded = merge_into(gather(repo, workspace, max_pits, max_rulings), today, out, shape)
    return text


def merge_into(groups: dict, today, out: Path, shape) -> tuple:
    """把本轮 groups 合进 out 现有内容。返回 (文本, 重画的旧全量行数, 淘汰的脚本自己旧行数)。"""
    region, keys = render_region(groups, today, shape)
    existing = read(out) if out.is_file() else fresh_file(shape)
    return merge(existing, region, keys, shape)


# ── 泄露自检（写公开版之前必过；命中就不许落盘）──────────────────────────


def private_features(groups: dict, workspace: Path) -> list:
    """拿去公开文件里搜的特征串：本轮每条仓外来源的标题与摘要 ＋ 私有正文行。

    不只用「本轮进了清单的那几条」——上一版进过、这一版被 `--max-*` 截掉的行也曾经是脚本写的，
    所以派工单与坑账的**全量**标题都要进特征集，否则旧行没人认得住。
    """
    feats = []
    for entries in groups.values():
        for e in entries:
            if not is_private(e.source):
                continue
            for probe in (e.title, clip(e.gist)):
                p = (probe or "").strip()
                if len(p) >= LEAK_MIN and p not in feats:
                    feats.append(p)
    for rel in ("_zd-private/docs/ERRATA.md", "_zd-toolroom/_索引.md"):
        f = workspace / rel
        if f.is_file():
            for raw in read(f).splitlines():
                s = re.sub(r"[`*_#>|]", "", raw).strip()
                if len(s) >= 16 and s not in feats:
                    feats.append(s)
    docs = workspace / "_zd-private" / "docs"
    for p in list(docs.glob("协议-*.md")):
        for raw in read(p).splitlines():
            s = re.sub(r"[`*_#>|]", "", raw).strip()
            if len(s) >= 16 and s not in feats:
                feats.append(s)
    for p in list(docs.glob("派工单-*.md")) + list(docs.glob("FEATURE-LEDGER-*.md")):
        head = first_line(read(p).splitlines())
        t = re.sub(r"^#+\s*", "", head).strip()
        if len(t) >= LEAK_MIN and t not in feats:
            feats.append(t)
    return feats


PATH_FIELD_RE = re.compile(r"(指向|来源)：`[^`]*`")


def scannable(text: str) -> str:
    """把「指向／来源」那一格的路径挖掉再扫——裁定明写路径可留，原文不许留。"""
    return PATH_FIELD_RE.sub(r"\1：`·`", text)


def leak_hits(public_text: str, feats: list) -> list:
    """命中列表：公开文件里出现了私有原文特征 ⇒ 非空（判据⑧）。路径格按裁定放行。"""
    body = scannable(public_text)
    return [f for f in feats if f in body]


# ── CLI ─────────────────────────────────────────────────────────────────


def main(argv=None) -> int:
    # 本机控制台默认 GBK（实测：'⇒' 一出现就 UnicodeEncodeError）⇒ 读数一律按 UTF-8 出
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=TITLE)
    ap.add_argument("--repo", default=None, help="仓库根（默认脚本所在目录的上一级）")
    ap.add_argument("--workspace", default=None, help="工作区根（默认仓库根的上一级；_zd-private／_zd-toolroom 在这儿）")
    ap.add_argument("--out", default=None, help="公开版输出（默认 <仓>/docs/必读清单.md）")
    ap.add_argument("--private-out", default=None,
                    help="全量版输出（默认 <工作区>/_zd-private/docs/必读清单全量.md）")
    ap.add_argument("--today", default=None, help="按哪天算账龄（YYYY-MM-DD；自测用固定日期保幂等）")
    ap.add_argument("--max-pits", type=int, default=10, help="坑账进清单的条数")
    ap.add_argument("--max-rulings", type=int, default=8, help="最近裁定进清单的单数")
    ap.add_argument("--public", action="store_true", help="只写公开版（进仓那份，只有指针）")
    ap.add_argument("--private", action="store_true", help="只写全量版（私有侧那份，一条不落）")
    ap.add_argument("--leak-check", action="store_true", help="只查不写：公开那份里出现私有原文特征 ⇒ rc=1")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不落盘")
    ap.add_argument("--selftest", action="store_true", help="跑内置判据自测（临时目录，不碰真文件）")
    ns = ap.parse_args(argv)

    if ns.selftest:
        return selftest()

    today = parse_day(ns.today) or dt.date.today()
    repo = Path(ns.repo) if ns.repo else Path(__file__).resolve().parent.parent
    if not (repo / "AGENTS.md").is_file():
        print(f"[{TITLE}] 环境错误：不像仓库根（找不到 {repo}/AGENTS.md）；要指路请加 --repo", file=sys.stderr)
        return 2
    workspace = Path(ns.workspace) if ns.workspace else repo.parent
    pub_out = Path(ns.out) if ns.out else repo / "docs" / "必读清单.md"
    priv_out = Path(ns.private_out) if ns.private_out else workspace / "_zd-private" / "docs" / "必读清单全量.md"
    only = POINTER if ns.public else (FULL if ns.private else None)

    groups = gather(repo, workspace, ns.max_pits, ns.max_rulings)
    feats = private_features(groups, workspace)

    def red_flags(text):
        """公开那份的两道闸：① 命中私有原文特征 ② 出现「全量形状＋仓外来源」的整行。"""
        return leak_hits(text, feats) + private_full_lines(text)

    if ns.leak_check:
        fresh, _d, _s = merge_into(groups, today, pub_out, POINTER)
        probe = read(pub_out) if pub_out.is_file() else ""
        hits, would = red_flags(probe), red_flags(fresh)
        # 现文件里有的、本轮将要写的行不会有 ⇒ 分开报，免得把「旧文件没重画」说成「产物漏了」
        print(f"[{TITLE}] 泄露自检：扫 {len(feats)} 条私有特征串；现文件 `{pub_out}` 命中 {len(hits)} 处，本轮产物命中 {len(would)} 处")
        for h in (hits + would)[:12]:
            print(f"  ::error title=必读清单泄露::公开那份里出现私有原文 ⇒ {h[:80]}")
        return 1 if (hits or would) else 0

    pub_text, pub_dropped, pub_superseded = merge_into(groups, today, pub_out, POINTER)
    priv_text, priv_dropped, priv_superseded = (merge_into(groups, today, priv_out, FULL)
                                                if only in (None, FULL) else (None, 0, 0))
    hits = red_flags(pub_text)
    if hits and only != FULL:
        print(f"[{TITLE}] 拒绝落盘：公开那份里有 {len(hits)} 处私有原文（fail-closed，两份都没写）", file=sys.stderr)
        for h in hits[:12]:
            print(f"  ::error title=必读清单泄露::{h[:80]}", file=sys.stderr)
        return 1
    if hits:
        print(f"[{TITLE}] 注意：公开那份仍有 {len(hits)} 处私有原文，但这一趟只写私有侧那份 ⇒ 放行（别拿这趟当自检过了）",
              file=sys.stderr)
    if pub_dropped and only != FULL:
        print(f"[{TITLE}] 公开那份重画掉 {pub_dropped} 行旧全量形状（内容都在私有侧全量版里，没删）")
    for label, s in (("公开那份", pub_superseded), ("全量那份", priv_superseded)):
        if s:
            print(f"[{TITLE}] {label}淘汰 {s} 行脚本自己上一版的旧行（本轮不再列出；"
                  f"逐字保留的判据只管人加的行，人加的一行没少）")
    if ns.dry_run:
        print(pub_text)
        if priv_text is not None:
            print("\n\n".join(["", "=" * 20, "全量版（私有侧）", "=" * 20, "", priv_text]))
        return 0

    jobs = []
    if only in (None, POINTER):
        jobs.append((pub_out, pub_text, POINTER))
    if priv_text is not None:
        jobs.append((priv_out, priv_text, FULL))
    for out, text, shape in jobs:
        old = read(out) if out.is_file() else None
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8", newline=NL)
        n = sum(1 for l in text.split("\n") if l.startswith("- **"))
        tag = "公开版（只指针）" if shape == POINTER else "全量版（私有侧）"
        same = "逐字节无变化＝幂等" if old == text else "内容有变化"
        print(f"[{TITLE}] 写出 {tag} {out}（条目 {n} 个；{same}）")
    return 0


# ── 判据自测 ────────────────────────────────────────────────────────────


def selftest() -> int:
    fails = []

    def check(name, ok, detail=""):
        print(f"[{TITLE}] {'PASS' if ok else 'FAIL'} · {name}" + (f"（{detail}）" if detail and not ok else ""))
        if not ok:
            fails.append(name)

    root = Path(tempfile.mkdtemp(prefix="zd-preread-")).resolve()
    repo, workspace = root / "zhengdao", root
    (repo / "docs").mkdir(parents=True)
    (workspace / "_zd-private" / "docs").mkdir(parents=True)
    (workspace / "_zd-toolroom").mkdir(parents=True)

    (repo / "AGENTS.md").write_text(
        "# 给 AI agent 的开工须知\n\n## 四条铁律\n\n先同步，再动手：`git fetch origin`。\n\n"
        "## 硬闸：提不上去，说明你做错了\n\n> 三种情况拒绝提交。\n", encoding="utf-8",
    )
    (repo / "docs" / "协作规约.md").write_text(
        "# 协作规约\n\n## 一、三行规矩\n\n先登记再动手。\n\n"
        "## 二、高发文件（整节是表格）\n\n| 文件 | 为什么撞 |\n|---|---|\n"
        "| `AGENTS.md` | 人人都想加规矩 |\n\n---\n\n## 三、下一节的正文\n\n"
        "这一句属于第三节，不许被当成第二节的摘要。\n", encoding="utf-8",
    )
    (workspace / "_zd-private" / "docs" / "协议-派工六步-2026-11-01.md").write_text(
        "# 协议 · 派工六步\n\n交付要带构建计划。\n", encoding="utf-8",
    )
    (workspace / "_zd-private" / "docs" / "ERRATA.md").write_text(
        "# 勘误账\n\n## E-001 · 2026-01-01 · 老坑\n\n旧的。\n\n"
        "## E-177 · 2026-11-04 · 清 manifest 误伤两个页面\n\n批量清 manifest。\n", encoding="utf-8",
    )
    (workspace / "_zd-toolroom" / "_索引.md").write_text(
        "# 工具房索引（2026-11-01）\n\n| 工具 | 位置 | 干什么 | 谁在用 |\n|---|---|---|---|\n"
        "| build-lock | `02-build/build-lock.ps1` | 构建锁 | 所有会话 |\n\n"
        "## 新增\n\n| 工具 | 位置 | 作用 |\n|---|---|---|\n"
        "| new-worktree | `02-build/new-worktree.ps1` | 建工作树并补 local.properties | 开工 |\n",
        encoding="utf-8",
    )
    (workspace / "_zd-private" / "docs" / "派工单-2026-11-05-第四百三十六批-预读回执闸.md").write_text(
        "# 第四百三十六批 · 预读回执闸\n\n- 状态：待派\n", encoding="utf-8",
    )

    today = dt.date(2026, 11, 5)
    target = repo / "docs" / "必读清单.md"                        # 公开版落点（进仓）
    priv = workspace / "_zd-private" / "docs" / "必读清单全量.md"  # 全量版落点（私有侧）

    # T1 一条命令能生成 ＋ 两份各自的形状
    pub = build(repo, workspace, today, 10, 8, target, POINTER)
    first = build(repo, workspace, today, 10, 8, priv, FULL)
    check("T1 一条命令能生成（两份都五类段落齐）",
          all(f"## {h}" in pub for _s, h in SECTIONS) and all(f"## {h}" in first for _s, h in SECTIONS))
    check("T1b 公开版一行四列（主题｜类型｜指向｜为什么）＋ 全量版带来源与最后更新",
          "｜ 类型：开工规矩（仓内） ｜ 指向：`AGENTS.md` ｜ 为什么：" in pub
              and "｜ 来源：`AGENTS.md`" in first and "｜ 最后更新：" in first)
    one = build(repo, workspace, today, 1, 8, priv, FULL)
    check("T1c 坑账条数上限真的生效（--max-pits 1 ⇒ 只留最新那条）",
          "E-177" in one and "E-001" not in one)

    # T1d/T1e 表格形状（真实 `_索引.md` 有好几张表、真实「协作规约」有整节是表格的节）——全量版才带摘要
    check("T1d 每张表各自丢表头与分隔行（表头不许变成条目）",
          "- **---**" not in first and "- **工具**" not in first and "- **new-worktree**" in first)
    row2 = next((l for l in first.split("\n") if l.startswith("- **二、高发文件")), "")
    check("T1e 摘要不出节（整节是表格⇒取本节的表格行，不借下一节的正文、也不交分隔线）",
          "人人都想加规矩" in row2
              and "这一句属于第三节" not in row2
              and "没有摘要" not in row2)

    # T2/T3 两份都要「只增不毁」＋ 幂等
    human_outside = "· 我自己加的：改 RunEngine 前先看行段并集。"
    human_inside = "- **我自己数的一条** — 人加的，脚本不许动 ｜ 来源：`我脑子`"
    for shape, tgt, text, label in ((POINTER, target, pub, "公开版"), (FULL, priv, first, "全量版")):
        tgt.write_text(text.replace(END, human_inside + "\n" + END) + human_outside + "\n",
                       encoding="utf-8", newline=NL)
        second = build(repo, workspace, today, 10, 8, tgt, shape)
        check(f"T2 只增不毁·段外条目原样保留（{label}）", human_outside in second)
        check(f"T2b 只增不毁·段内条目收进「人工条目」（{label}）", human_inside in second and HUMAN_HEAD in second)
        check(f"T2c 机器条目没被复制成两份（{label}）", second.count("- **四条铁律**") == 1)
        tgt.write_text(second, encoding="utf-8", newline=NL)
        third = build(repo, workspace, today, 10, 8, tgt, shape)
        diff = sum(1 for a, b in zip(second.split("\n"), third.split("\n")) if a != b)
        check(f"T3 幂等（{label}：连跑两次逐字节相同）", second == third, f"差 {diff} 行")
    pub, first = (build(repo, workspace, today, 10, 8, target, POINTER),
                  build(repo, workspace, today, 10, 8, priv, FULL))

    # T6 公开版只放指针：仓外来源的标题句子与摘要一律不进仓，名册整本收成一行（判据⑦）
    # 「原文不留」按裁定口径扫＝把「指向／来源」那一格的路径挖掉再找（路径本身可留）
    pub_body = scannable(pub)
    check("T6 公开版里仓外来源只剩标识（E-177／第四百三十六批／文件名／`_索引（N 条）`），原文不出现在路径格之外",
          "**E-177**" in pub and "**第四百三十六批**" in pub and "**_索引（2 条）**" in pub
              and "清 manifest 误伤两个页面" not in pub_body and "预读回执闸" not in pub_body
              and "派工单-2026-11-05-第四百三十六批-预读回执闸.md" in pub)
    check("T6b 公开版里没有工具名（build-lock／new-worktree 都是私有侧的内容）",
          "build-lock" not in pub_body and "new-worktree" not in pub_body)
    check("T6c 公开版没有一句私有正文（协议与坑账的正文都只在私有侧）",
          "交付要带构建计划" not in pub_body and "批量清 manifest" not in pub_body)
    check("T6d 仓内来源（AGENTS.md／协作规约）在两份里都按原样给标题",
          "- **四条铁律**" in pub and "- **一、三行规矩**" in pub)

    # T9 迁移那一趟：仓里已有的是**旧全量形状** ⇒ 公开版必须把它重画成指针（原文不能当人加的留下），
    # 表头跟着换成公开版那份；真正人加的（来源在仓内／不是条目行）一个字不动。
    old_head = ["# 必读清单（开工先读这一页）", "",
                "> 第四百三十六批 A ②：这一页由 `python tools/必读清单生成.py` 扫工作区生成，**只增不毁**——",
                "> 每条带 **来源** 与 **最后更新**。", ""]
    old_private = "- **第四百四十三批 · 模型名映射表 ＋ 旧模型裁剪** — 状态：待派｜分支 feat/model-labels ｜ 来源：`_zd-private/docs/派工单-2026-11-04-第四百四十三批.md` ｜ 最后更新：2026-11-04"
    old_human = "- **我自己数的一条** — 人加的，脚本不许动 ｜ 来源：`我脑子`"
    target.write_text("\n".join(old_head + [BEGIN, "## 五、最近裁定（派工单与全量台账）",
                                            old_private, old_human, END] + TAIL_LINES) + "\n",
                      encoding="utf-8", newline=NL)
    migrated = build(repo, workspace, today, 10, 8, target, POINTER)
    check("T9 迁移_旧全量形状的私有行被重画成指针（不当人加的留在公开仓）",
          old_private not in migrated and private_full_lines(migrated) == [])
    check("T9b 迁移_表头换成公开版那句（脚本自己的署名行，认得出才换）",
          "公开版＝只放指针" in migrated.split("\n")[0] and "# 必读清单（开工先读这一页）" not in migrated)
    check("T9c 迁移_真·人加的那行照旧逐字保留", old_human in migrated)
    target.write_text(migrated, encoding="utf-8", newline=NL)
    check("T9d 迁移后幂等（再跑一次逐字节相同）",
          migrated == build(repo, workspace, today, 10, 8, target, POINTER))

    # T11 淘汰脚本自己的旧行：同一页不许留两条自相矛盾的「自己的产出」，也不许把它们洗成「人工条目」
    # （实测迁移那一趟：工具房条数 16→17、第四百四十二批掉出 --max-rulings 窗口 ⇒ 两行都被留在段尾当人加的）
    # 公开那份里人加的行只许用非私有来源：全量形状＋仓外来源那一格归泄露自检管（见 T7b／T9）
    old_pub_human = ("- **第四百三十七批 · 人自己记的一条** — 状态：待派｜承接：待定 ｜ "
                     "来源：`我脑子` ｜ 最后更新：2026-11-01")
    old_priv_human = ("- **第四百三十七批 · 人自己记的一条** — 状态：待派｜承接：待定｜分支 feat/x ｜ "
                      "来源：`_zd-private/docs/派工单-2026-11-01-第四百三十七批-人记的.md` ｜ 最后更新：2026-11-01")
    stale_pub = [
        "## 四、名册（工具房索引）",
        ("- **_索引（1 条）** ｜ 类型：名册（仓外） ｜ 指向：`_zd-toolroom/_索引.md` ｜ 为什么："
         + WHY["名册"] + " ｜ 最后更新：2026-11-04 ｜ 新鲜（1 天前）"),
        "## 五、最近裁定（派工单与全量台账）",
        ("- **第四百三十五批** ｜ 类型：最近裁定（仓外） ｜ "
         "指向：`_zd-private/docs/派工单-2026-11-01-第四百三十五批-老的.md` ｜ 为什么："
         + WHY["最近裁定"] + " ｜ 最后更新：2026-11-01 ｜ 新鲜（4 天前）"),
        old_pub_human]
    stale_full = [
        "## 五、最近裁定（派工单与全量台账）",
        ("- **第四百三十五批** — 老的摘要 ｜ "
         "来源：`_zd-private/docs/派工单-2026-11-01-第四百三十五批-老的.md` ｜ 最后更新：2026-11-01 ｜ 新鲜（4 天前）"),
        old_priv_human]
    # 「上一版的产出」＝脚本自己写的整页（表头＋生成段＋段外那节都在），只往里塞几行旧条目
    target.write_text(fresh_file(POINTER).replace(BEGIN + "\n", BEGIN + "\n" + "\n".join(stale_pub) + "\n", 1),
                      encoding="utf-8", newline=NL)
    nxt = build(repo, workspace, today, 10, 8, target, POINTER)
    check("T11 淘汰_脚本上一版的旧行（条数变了／掉出窗口）不许当人加的留下",
          "_索引（1 条）**" not in nxt and "第四百三十五批** ｜ 类型" not in nxt)
    check("T11b 淘汰不许吃掉人加的那行（长标题＋状态/承接不是脚本写得出的形状）",
          old_pub_human in nxt and HUMAN_HEAD in nxt)
    check("T11c 本轮新行照在（淘汰≠不写）",
          "**_索引（2 条）**" in nxt and "**第四百三十六批**" in nxt)
    # 全量那份同理：短标识的旧行是脚本自己的 ⇒ 淘汰；长标题带状态/分支/来源在仓外的 ⇒ 逐字留
    priv.write_text(fresh_file(FULL).replace(BEGIN + "\n", BEGIN + "\n" + "\n".join(stale_full) + "\n", 1),
                    encoding="utf-8", newline=NL)
    nxt_full = build(repo, workspace, today, 10, 8, priv, FULL)
    check("T11d 全量那份也淘汰脚本自己的旧行，人加的那行照旧逐字在",
          "- **第四百三十五批** —" not in nxt_full and old_priv_human in nxt_full)
    pub = build(repo, workspace, today, 10, 8, target, POINTER)
    first = build(repo, workspace, today, 10, 8, priv, FULL)
    target.write_text(pub, encoding="utf-8", newline=NL)   # 文件与内存对齐，后面几组测试拿的是同一份
    priv.write_text(first, encoding="utf-8", newline=NL)

    # T7 泄露自检有牙（判据⑧）：本轮真产物 ⇒ 绿（负例）；把私有侧一整行抄进公开版 ⇒ 红（正例）
    feats = private_features(gather(repo, workspace, 10, 8), workspace)
    check("T7 负例 · 真产物过得了泄露自检（特征串扫过＝私有侧一条都没漏进公开版）",
          leak_hits(pub, feats) == [] and private_full_lines(pub) == [],
          f"命中 {(leak_hits(pub, feats) + private_full_lines(pub))[:2]}")
    leaked_line = next(l for l in first.split("\n") if l.startswith("- **E-177"))
    check("T7b 正例 · 把私有侧那一行原样抄进公开版必须红（自检不是恒绿）",
          (leak_hits(pub + "\n" + leaked_line + "\n", feats)
           + private_full_lines(pub + "\n" + leaked_line + "\n")) != [])

    # T10 CLI 那道 fail-closed 闸真的拦：私有原文躺在**生成段之外**（人粘进去的，脚本不重画）时，
    # --public 必须 rc=1 且一个字都不落盘；--private 那趟不碰公开那份 ⇒ 放行。
    leaky = pub.replace("· 要加自己那份必读条目", leaked_line + "\n· 要加自己那份必读条目", 1)
    target.write_text(leaky, encoding="utf-8", newline=NL)
    cli = ["--repo", str(repo), "--workspace", str(workspace), "--today", str(today)]
    keep_err, sys.stderr = sys.stderr, open(os.devnull, "w", encoding="utf-8", errors="replace")
    try:
        rc_pub, rc_priv = main(cli + ["--public"]), main(cli + ["--private"])
    finally:
        sys.stderr = keep_err
    check("T10 段外有人粘进私有原文 ⇒ --public rc=1 且拒写（两份都没动）",
          rc_pub == 1 and read(target) == leaky, f"rc={rc_pub} 没被改写={read(target) == leaky}")
    check("T10b --private 那趟不碰公开那份 ⇒ rc=0（明说这趟没做自检）",
          rc_priv == 0 and read(target) == leaky)
    target.write_text(pub, encoding="utf-8", newline=NL)

    # T8 拆分不是删内容（判据⑨）：那些原文一条不少，全在私有侧那份里
    check("T8 全量版原文照在（坑账标题＋工具名＋协议正文）",
          "E-177 清 manifest 误伤两个页面" in first and "build-lock" in first and "交付要带构建计划" in first)

    # T4 过期能看出：AGENTS.md 改成 200 天前 ⇒ 标「待复核」（两份都得看得见）
    st = (repo / "AGENTS.md").stat()
    os.utime(repo / "AGENTS.md", (st.st_atime, st.st_mtime - 200 * 86400))
    stale_pub = build(repo, workspace, today, 10, 8, target, POINTER)
    stale_full = build(repo, workspace, today, 10, 8, priv, FULL)
    check("T4 过期条目要标「待复核」（两份都标）",
          "待复核" in stale_pub and "已 200 天" in stale_pub
              and "已 200 天" in stale_full)

    # T5 缺件不许静默
    bare = root / "bare"
    (bare / "docs").mkdir(parents=True)
    (bare / "AGENTS.md").write_text("# 须知\n\n## 四条铁律\n\n先同步。\n", encoding="utf-8")
    gone = build(bare, root / "nowhere", today, 10, 8, bare / "docs" / "必读清单.md", FULL)
    gone_pub = build(bare, root / "nowhere", today, 10, 8, bare / "docs" / "必读清单.md", POINTER)
    check("T5 缺件明写「未列」（不假装完整）", gone.count(MISSING_MARK) >= 3,
          f"只出现 {gone.count(MISSING_MARK)} 处未列")
    check("T5b 公开版同样明写「未列」（不许指针版装作扫全了）", gone_pub.count(MISSING_MARK) >= 3,
          f"只出现 {gone_pub.count(MISSING_MARK)} 处未列")

    # 负例自证：旧形态（整文件覆盖式写法）必然毁掉人工条目 ⇒ 证明 T2 真的有牙
    naive = "\n".join(header_lines(FULL) + [BEGIN] + render_region(
        gather(repo, workspace, 10, 8), today, FULL)[0] + [END]) + "\n"
    check("负例 · 覆盖式旧写法会毁掉人工条目（T2 不是恒绿）",
          human_outside not in naive and human_inside not in naive)

    print(f"[{TITLE}] 结论：" + ("全绿" if not fails else f"红 {len(fails)} 条 → " + "，".join(fails)))
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
