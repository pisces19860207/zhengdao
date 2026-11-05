#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-rules-ledger.py —— 制度总账门禁（460，2026-11-05）：规矩一条一行，条条有出处、条条有着落。

## 为什么要有这道闸

制度散在 `AGENTS.md`、`docs/协作规约.md`、私有侧 ERRATA 里，写在纸上的规矩会烂：
- 「现在靠什么挡」没人记 ⇒ 同一个坑换一批字再踩一遍（协作规约 §8 那张事故表就是这么来的）；
- 账上引用的门禁/文件路径悄悄改名、搬走 ⇒ 账上写着"有闸"，实际是裸奔；
- 外部事实过了 90 天没人知道 ⇒ 过期的"真理"照用。
本脚本把《制度总账.md》当输入做四件事（红＝这条账不合格，提交/交付不收）：
  1. **生效**条目必须有对应机制（门禁/测试/路径）；既无机制又不标"靠人" ⇒ **红**；
  2. 机制列里引用的路径必须真实存在 ⇒ 不存在即 **红**；
  3. **⭐（交过学费）**条目的依据必须含 提交号/事故号/真机读数 三者之一 ⇒ 否则 **红**；
  4. 复核周期到期 ⇒ **notice**（不红，但提醒标"待复核"）。

## 用法（纯标准库；不联网；只读账本与文件系统）

    python tools/check-rules-ledger.py                    # 检仓根的 制度总账.md
    python tools/check-rules-ledger.py --file 路径.md     # 检别的账（路径相对账本所在目录解析）
    python tools/check-rules-ledger.py --min-rows 25      # 行数下限（默认 25，防越修越薄）
    python tools/check-rules-ledger.py --selftest         # 内置正负例自证，不读真账

## 退出码

    0 = 无红（允许有 notice）
    1 = 有红（逐条打印：T-xx 哪一列坏）
    2 = 用法/文件问题（账本不存在、没找到账本表、一条数据行都没有）

## 账本规约（表头六列；格子内不写竖线，路径用反引号或裸写均可）

    | 编号 | 条文 | 依据（单号/日期） | 对应机制（门禁/测试/路径） | 状态 | 复核周期 |

- 编号：`T-xx`；交过学费的在编号前加 **⭐**。「方法纪律（最贵）」一节必须是**第一张账**且条条 ⭐。
- 状态四态：**生效**（必须有机制）／**废止**（留痕不删行）／**待复核**（已知要重看）／
  **靠人**（必须写清「靠：谁；碰：多久碰一次」）。
- 复核周期：`N天·上次YYYY-MM-DD`（到期出 notice）；`每单`／`每次…`（事件驱动，不算过期）；`—`。
- 依据列（⭐ 行）的实证口径：提交号＝7 位以上十六进制短号（含"记录教训的提交"——
  `git show <短号>` 能回查到当时的记录，同样算实证）；事故号＝`E-xxx`；真机读数＝含"真机/读数/截图/adb"。
- 机制列的路径怎么写才被当路径查：仓内以已知目录开头（tools/ docs/ app/ config/ licenses/
  rust/ .github/ gradle/ scripts/），仓外以 `../` 开头，或以已知扩展名结尾
  （.py .ps1 .json .md .yml .yaml .sh .txt .kt .toml .cfg）以及 `.gitignore`。
  别的写法（CI、步骤名、纯文字）不当路径查。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from datetime import date, timedelta
from pathlib import Path

try:  # 与 tools/ 其余脚本同款：cp936 控制台下中文不炸（见 ERRATA 施工回写 2026-10-21）
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

_REPO = Path(__file__).resolve().parent.parent
DEFAULT_LEDGER = _REPO / "制度总账.md"

REQUIRED_COLS = ("编号", "条文", "依据", "对应机制", "状态", "复核周期")
STATUSES = ("生效", "废止", "待复核", "靠人")
METHOD_KEYWORD = "方法纪律"
MIN_ROWS_DEFAULT = 25

# 机制列里什么才算"路径"：已知仓内目录前缀 / 仓外 ../ / 已知扩展名
PATH_DIR_PREFIXES = {
    "tools", ".github", "config", "docs", "app", "licenses", "rust",
    "gradle", "scripts", "licenses",
}
PATH_EXTS = (
    ".py", ".ps1", ".kts", ".json", ".md", ".yml", ".yaml",
    ".sh", ".txt", ".kt", ".toml", ".cfg",
)

RE_ID = re.compile(r"(⭐?)\s*T-(\d+)")
RE_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
RE_ERRATA = re.compile(r"\bE-\d{1,3}\b")
RE_COMMIT = re.compile(r"\b[0-9a-f]{7,40}\b")
RE_ORDER = re.compile(r"\d{1,4}\s*(?:单|批)")
RE_DEVICE = re.compile(r"真机|读数|截图|adb")
RE_PERIOD = re.compile(r"(\d+)\s*天")
RE_ANCHOR = re.compile(r"上次\s*(\d{4}-\d{2}-\d{2})")
RE_OWNER = re.compile(r"靠[:：]\s*([^；;]{1,40})")
RE_CADENCE = re.compile(r"碰[:：]\s*([^；;）)]{1,40})")
RE_SEP_ROW = re.compile(r":?-{3,}:?")
# 格子内分隔：全半角标点＋引号＋空白（不含 - 和 . ，免得拆碎 safe-worktree-remove.py 这类路径）
CELL_SPLIT = re.compile(r"[`'\"，,、;；＋+（）()「」『』\s]+")


def split_row(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def is_sep_row(cells: list[str]) -> bool:
    return bool(cells) and all(RE_SEP_ROW.fullmatch(c) for c in cells)


def parse_ledger(text: str) -> tuple[list[tuple[str | None, dict[str, str]]], str | None, int]:
    """返回 ([(节名, 行dict)], 第一张账所在节名, 表数)。表头行必须六列名齐才认账。"""
    rows: list[tuple[str | None, dict[str, str]]] = []
    section: str | None = None
    first_section: str | None = None
    tables = 0
    pending: dict[str, int] | None = None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("## "):
            section, pending = s[3:].strip(), None
            continue
        if not s.startswith("|"):
            if not s:
                pending = None  # 空行＝表格结束；下一段 `|` 行要自带表头
            continue
        cells = split_row(s)
        if is_sep_row(cells):
            continue
        idx = {}
        for name in REQUIRED_COLS:
            for j, c in enumerate(cells):
                if name in c:
                    idx[name] = j
                    break
        if pending is None:
            if all(name in idx for name in REQUIRED_COLS):
                pending = idx
                tables += 1
                if first_section is None:
                    first_section = section
            continue  # 无表头的散表（口径说明等）不进账
        if all(name in idx for name in REQUIRED_COLS) and len(idx) == len(REQUIRED_COLS):
            pending = idx  # 紧跟的第二张表头
            continue
        row = {}
        malformed = [n for n, j in pending.items() if j >= len(cells)]
        if malformed:
            row["_malformed"] = "、".join(malformed)  # 交给调用方报红
        else:
            for n, j in pending.items():
                row[n] = cells[j]
        rows.append((section, row))
    return rows, first_section, tables


def path_candidates(cell: str) -> list[str]:
    """从机制格里挑出"看起来是路径"的 token（规约见文件头 docstring）。"""
    out: list[str] = []
    for raw in CELL_SPLIT.split(cell):
        p = raw.strip("。，、；！）)…").strip()
        if not p or p.startswith("--"):
            continue
        norm = p.replace("\\", "/")
        seg0 = norm.split("/", 1)[0]
        if "/" in norm and (seg0 == ".." or seg0 in PATH_DIR_PREFIXES):
            out.append(norm)
            continue
        low = norm.lower()
        if low.endswith(PATH_EXTS) and "." in Path(norm).name:
            out.append(norm)
            continue
        if norm == ".gitignore":
            out.append(norm)
    return out


def check_ledger(text: str, base_dir: Path, today: date, min_rows: int = MIN_ROWS_DEFAULT):
    """核心判账。返回 (errors, notices, stats)；红进 errors、提醒进 notices。"""
    errors: list[str] = []
    notices: list[str] = []
    rows, first_section, tables = parse_ledger(text)
    if tables == 0 or not rows:
        errors.append("账本里没找到六列账本表（表头：编号｜条文｜依据｜对应机制｜状态｜复核周期）")
        return errors, notices, {"total": 0, "生效": 0, "靠人": 0, "待复核": 0,
                                 "废止": 0, "star": 0, "star_ok": 0}
    if first_section is None or METHOD_KEYWORD not in first_section:
        errors.append(f"第一张账表必须在「{METHOD_KEYWORD}」一节（现在在：{first_section}）")

    seen_ids: set[str] = set()
    stats = {"生效": 0, "靠人": 0, "待复核": 0, "废止": 0, "star": 0, "star_ok": 0}

    for section, row in rows:
        if "_malformed" in row:
            errors.append(f"列数不够（缺：{row['_malformed']}）：{row}")
            continue

        # ── 编号 ────────────────────────────────────────────────
        m = RE_ID.search(row["编号"])
        tid = m.group(0) if m else row["编号"][:20]
        star = bool(m and m.group(1)) or "⭐" in row["条文"]
        if not m:
            errors.append(f"{tid}（{section}）：编号不合 T-xx 规约")
        elif tid in seen_ids:
            errors.append(f"{tid}：编号重复")
        seen_ids.add(tid)

        # ── 依据：每条都要有出处 ─────────────────────────────────
        src = row["依据"]
        if not src.strip() or src.strip() in {"—", "-", "–"}:
            errors.append(f"{tid}：依据为空——每条必须有出处（单号/日期/提交号/事故号）")
        elif not (RE_DATE.search(src) or RE_ERRATA.search(src) or RE_COMMIT.search(src)
                  or RE_ORDER.search(src)):
            errors.append(f"{tid}：依据找不到可回查的出处（日期/单号批次/E-事故号/提交短号）")

        # ── ⭐：实证出处（提交号/事故号/真机读数 三者其一）──────────
        if star:
            stats["star"] += 1
            if RE_COMMIT.search(src) or RE_ERRATA.search(src) or RE_DEVICE.search(src):
                stats["star_ok"] += 1
            else:
                errors.append(f"{tid}：⭐ 交过学费的条目必须有实证出处"
                              f"（提交短号 / E-事故号 / 真机读数），现在依据是：「{src[:60]}」")

        # ── 方法纪律节条条 ⭐ ────────────────────────────────────
        if section and METHOD_KEYWORD in section and not star:
            errors.append(f"{tid}：在「{METHOD_KEYWORD}」一节却不带 ⭐——最贵的方法论必须标学费")

        # ── 状态 ────────────────────────────────────────────────
        scell = row["状态"]
        present = [s for s in STATUSES if s in scell]
        if len(present) != 1:
            errors.append(f"{tid}：状态必须恰好是四态之一（生效/废止/待复核/靠人），现在是「{scell}」")
            continue
        status = present[0]
        stats[status] += 1

        mech = row["对应机制"]
        cand = path_candidates(mech)

        if status == "生效" and not cand and "靠人" not in scell:
            errors.append(f"{tid}：状态「生效」但对应机制里没有任何门禁/测试/路径，"
                          f"也没标「靠人」——无机制又未标靠人 ⇒ 红")
        if status == "靠人":
            owner, cadence = RE_OWNER.search(scell), RE_CADENCE.search(scell)
            if not owner or not cadence:
                errors.append(f"{tid}：靠人条目必须写清「靠：谁；碰：多久碰一次」，现在是「{scell}」")

        # ── 路径存在性（含废止行：账上写的路径就得在）──────────────
        for p in cand:
            if not (base_dir / p).exists():
                errors.append(f"{tid}：引用路径不存在 ⇒ 红：{p}")

        # ── 复核周期：过期 ⇒ notice ──────────────────────────────
        if status != "废止":
            pv = row["复核周期"]
            per, anchor = RE_PERIOD.search(pv), RE_ANCHOR.search(pv)
            if per and anchor:
                due = date.fromisoformat(anchor.group(1)) + timedelta(days=int(per.group(1)))
                if due <= today:
                    notices.append(f"{tid}：复核已到期（{per.group(1)}天·上次{anchor.group(1)}，"
                                   f"到期 {due}）⇒ 请复核或标「待复核」")
            elif per and not anchor:
                notices.append(f"{tid}：复核周期写了「{per.group(1)}天」但没有「上次YYYY-MM-DD」锚点，"
                               f"无法判定是否过期")

    if len(rows) < min_rows:
        errors.append(f"账本只有 {len(rows)} 条（下限 {min_rows}）——制度总账不许越修越薄")
    stats["total"] = len(rows)
    return errors, notices, stats


def _fmt_stats(stats: dict) -> str:
    return (f"共 {stats['total']} 条：生效 {stats['生效']}｜靠人 {stats['靠人']}｜"
            f"待复核 {stats['待复核']}｜废止 {stats['废止']}；⭐ {stats['star']} 条"
            f"（实证 {stats['star_ok']}/{stats['star']}）")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="制度总账门禁（460）")
    ap.add_argument("--file", default=str(DEFAULT_LEDGER), help="账本路径（默认仓根 制度总账.md）")
    ap.add_argument("--min-rows", type=int, default=MIN_ROWS_DEFAULT, help="条数下限（默认 25）")
    ap.add_argument("--selftest", action="store_true", help="内置正负例自证（不读真账）")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    path = Path(args.file)
    if not path.is_file():
        print(f"✗ 找不到账本：{path}", file=sys.stderr)
        return 2
    errors, notices, stats = check_ledger(
        path.read_text(encoding="utf-8-sig"), path.resolve().parent, date.today(), args.min_rows)
    print(f"账本：{path}")
    print(_fmt_stats(stats))
    for e in errors:
        print(f"✗ 红：{e}")
    for n in notices:
        print(f"⚠ notice：{n}")
    if errors:
        print(f"结论：{len(errors)} 红 / {len(notices)} notice ⇒ 不合格（退出码 1）")
        return 1
    print(f"结论：0 红 / {len(notices)} notice ⇒ 合格")
    return 0


# ─────────────────────────────── selftest ────────────────────────────────
#
# 正例＝四态各来一条、路径真实存在；负例 3 条各必红（无机制／路径不存在／⭐无实证）
# 外加负例 4（靠人没写清谁靠多久碰）与 notice 例（过期只提醒不红）。

_POS = """# 账
## 方法纪律（最贵）
| 编号 | 条文 | 依据（单号/日期） | 对应机制（门禁/测试/路径） | 状态 | 复核周期 |
| --- | --- | --- | --- | --- | --- |
| ⭐T-01 | 好规矩要有闸 | E-001（2026-10-05） | tools/fake-gate.py | 生效 | 90天·上次{TODAY} |

## 其他（四态的另外三态）
| 编号 | 条文 | 依据（单号/日期） | 对应机制（门禁/测试/路径） | 状态 | 复核周期 |
| --- | --- | --- | --- | --- | --- |
| T-02 | 纯约定要写清谁靠 | 2026-11-05 单 460 | —（无技术闸） | 靠人（靠：蓝总管；碰：每单验收时） | 每单 |
| T-03 | 旧规矩留痕 | 废止于 2026-10-27 | —（已随终端出仓） | 废止（留痕） | — |
| T-04 | 已知要重看 | 2026-11-05 | tools/fake-gate.py | 待复核（JDK 口径冲突） | 7天·上次{TODAY} |
"""


def selftest() -> int:
    today = date.today()
    pos = _POS.replace("{TODAY}", today.isoformat())
    cases: list[tuple[str, str, bool, str | None, bool]] = []
    # (名字, 账本文本, 期待无红, 期待错误关键词, 期待有 notice)
    cases.append(("正例（四态齐全、路径真实）", pos, True, None, False))
    cases.append(("负例1：生效无机制又未标靠人 ⇒ 必红",
                  pos.replace("| tools/fake-gate.py | 生效 |", "| —（还没接闸） | 生效 |"),
                  False, "无机制", False))
    cases.append(("负例2：引用路径不存在 ⇒ 必红",
                  pos.replace("tools/fake-gate.py", "tools/不存在的门禁.py"),
                  False, "不存在", False))
    cases.append(("负例3：⭐ 无实证出处 ⇒ 必红",
                  pos.replace("| E-001（2026-10-05） |", "| 我记得好像是这么回事 |"),
                  False, "实证", False))
    cases.append(("负例4：靠人没写「靠谁、多久碰」⇒ 必红",
                  pos.replace("靠人（靠：蓝总管；碰：每单验收时）", "靠人（就靠大家自觉）"),
                  False, "碰", False))
    cases.append(("notice例：复核过期只提醒不红",
                  pos.replace(f"90天·上次{today.isoformat()}", "7天·上次2020-01-01"),
                  True, None, True))

    failed = 0
    with tempfile.TemporaryDirectory() as td:
        base = Path(td)
        (base / "tools").mkdir()
        (base / "tools" / "fake-gate.py").write_text("# 正例用的真路径\n", encoding="utf-8")
        for name, text, want_clean, want_kw, want_notice in cases:
            errors, notices, _ = check_ledger(text, base, today, min_rows=1)
            ok = (not errors) if want_clean else bool(errors)
            if want_kw is not None:
                ok = ok and any(want_kw in e for e in errors)
            if want_notice:
                ok = ok and bool(notices) and not errors
            else:
                ok = ok and not notices
            print(f"{'✓ PASS' if ok else '✗ FAIL'}｜{name}"
                  + ("" if ok else f"｜errors={errors} notices={notices}"))
            failed += 0 if ok else 1
    print(f"selftest：{len(cases) - failed}/{len(cases)} 过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
