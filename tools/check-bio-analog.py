#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-bio-analog.py —— 六体仿生登记闸(第六百二十六批·批一,2026-11-15)。

一句话
------
源码里任何带 `@BioAnalog("件名")` 标记的类,件名必须登记在
`docs/bio-analog-registry.md` 的第一列——**未登记即红**,CI 上会拦。

它补的是哪个洞
--------------
626 定稿了六体(杏仁核/海马/前额叶/纹状体/岛叶/丘脑),口径写在登记表里:
六件都无判定权、只做读数/信号、失败兜底必须等于现状。批二起会陆续建实现,
最便宜的漂移就是「代码里冒出一件没登记的器官」——表定稿了,实现却绕开表。
本闸把「先登记、后落码」变成机器判据:标记是登记承诺的锚点,表是标记的白名单。

判据(红 ⇒ 退出码 1;环境错误 ⇒ 退出码 2,**不算通过**)
--------------------------------------------------------
源码侧(扫各模块 `*.kt`/`*.java`,先剥注释再找标记):
  B1 `@BioAnalog` 不带件名(裸标记,或形状不是 `@BioAnalog("件名")`)⇒ 红;
  B2 件名在登记表第一列找不到 ⇒ 红(未登记:先改表、过评审,再落码);
登记表侧:
  B3 任一行格数 ≠ 7,或七格有空 ⇒ 红(一格不空是登记的下限);
  B4 六件缺行(杏仁核/海马/前额叶/纹状体/岛叶/丘脑少任何一行)⇒ 红(六行齐);
  B5 件名不在六件闭集(冒出第七件)⇒ 红——加新件先改口径过评审,表不接受既成事实;
  B6 同一件名两行 ⇒ 红(一件一账)。
环境错误(退出码 2):登记表被删、七列表头找不到——「删表变绿」是最便宜的假绿,
  与 634 的「删表 ⇒ 2」同款。

取证口径(只读、不联网、不写仓内文件)
------------------------------------
* 表:`docs/bio-analog-registry.md` 里**表头恰好是那七列**的那一张(列口径一节另有
  两列散文表,靠表头精确匹配区分);
* 码:仓内全部 `*.kt`/`*.java`(含测试源;跳过 build/.gradle/.git 等产物目录),
  注释(`//`、`/* */` 可嵌套、KDoc)与三引号字符串整段剥除后找 `@BioAnalog`——
  注释里提一句标记名**不算**登记承诺(133 批「跳过注释行」的同款教训);
  普通双引号字符串**不剥**,所以真标记的形状才认;若某字符串里恰好写了
  `@BioAnalog(` 被误判,改写那条字符串,别为它放宽本闸;
* 当前仓内没有任何标记类 ⇒ 本闸应绿。绿的是「没有未登记的事」,不是「闸坏了」——
  闸坏没坏,`--selftest` 的正负例各自证明一次。

用法
----
    python3 tools/check-bio-analog.py            # 门禁(CI 用这个)
    python3 tools/check-bio-analog.py --selftest # 自检:临时目录造夹具,正例+负例+五条守卫+防假绿
    python3 tools/check-bio-analog.py --root .   # 指定仓库根(本机排查用)

自检**不写仓库内任何文件**(夹具全在 `tempfile.TemporaryDirectory()` 里),不联网。
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

try:  # Windows 控制台默认 GBK:个别字符编不出来时降级替换,不让闸自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = "docs/bio-analog-registry.md"

# ── 七列表头(唯一形状;改这里等于改登记表格式)────────────────────────────
COLUMNS = ["件", "仿生自", "只做", "不做", "可测差异", "失败兜底", "状态"]

# ── 六件闭集(批一定稿;第七件出现即红,加件＝改本常量＋评审)──────────────
SIX = ("杏仁核", "海马", "前额叶", "纹状体", "岛叶", "丘脑")

MARKER = "@BioAnalog"
MARK_RE = re.compile(r"@BioAnalog\b")
NAMED_RE = re.compile(r'\(\s*"([^"]*)"\s*\)')
SEP_RE = re.compile(r"^:?-{3,}:?$")
SCAN_EXTS = {".kt", ".java"}
SKIP_DIRS = {".git", ".gradle", ".kotlin", ".github", "build", "node_modules", "__pycache__"}


class EnvError(RuntimeError):
    """环境／解析错误 ⇒ 退出码 2(不是通过)。"""


# ── 小工具 ────────────────────────────────────────────────────────────────

def clean(cell: str) -> str:
    """去掉反引号、强调星号与首尾空白。"""
    return cell.replace("`", "").replace("**", "").strip()


def split_cells(line: str) -> list[str]:
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|"):
        body = body[:-1]
    return [c.strip() for c in body.split("|")]


def strip_comments(src: str) -> str:
    """剥掉 `//`、可嵌套 `/* */` 与三引号字符串,长度不变(注释→空格,换行保留)。

    普通双引号/单引号字符串**原样保留**——真标记的件名就在双引号里;
    字符串起止仍被跟踪,所以串里的 `//` 不会被当成注释。
    """
    out = list(src)
    n = len(src)
    i = 0
    while i < n:
        ch = src[i]
        if ch == "/" and i + 1 < n and src[i + 1] == "/":  # 行注释
            j = i
            while j < n and src[j] != "\n":
                out[j] = " "
                j += 1
            i = j
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "*":  # 块注释(可嵌套)
            depth = 0
            j = i
            while j < n:
                if src.startswith("/*", j):
                    depth += 1
                    for k in (j, j + 1):
                        out[k] = " "
                    j += 2
                elif src.startswith("*/", j):
                    depth -= 1
                    for k in (j, j + 1):
                        out[k] = " "
                    j += 2
                    if depth == 0:
                        break
                else:
                    if src[j] != "\n":
                        out[j] = " "
                    j += 1
            i = j
            continue
        if src.startswith('"""', i):  # 三引号字符串:整段剥除(散文不算标记)
            j = i + 3
            while j < n and not src.startswith('"""', j):
                j += 1
            for k in range(i, min(j + 3, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 3
            continue
        if ch in ('"', "'"):  # 普通字符串:跟踪起止,内容保留
            quote = ch
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == quote:
                    break
                if src[j] == "\n":  # 未闭合的串不越过行界(Kotlin 单行串)
                    break
                j += 1
            i = j + 1
            continue
        i += 1
    return "".join(out)


# ── 登记表解析与判据(B3–B6)─────────────────────────────────────────────

def parse_registry(path: Path) -> tuple[list[tuple[int, list[str]]], int]:
    """返回 (行, 表头行号)。表头找不到 ⇒ EnvError。"""
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    head_at = -1
    for idx, line in enumerate(lines):
        if not line.lstrip().startswith("|"):
            continue
        cells = split_cells(line)
        if len(cells) == len(COLUMNS) and [clean(c) for c in cells] == COLUMNS:
            head_at = idx
            break
    if head_at < 0:
        raise EnvError(f"{REGISTRY} 里找不到七列表头({' | '.join(COLUMNS)})——表被删了或表头被改了")

    rows: list[tuple[int, list[str]]] = []
    for idx in range(head_at + 1, len(lines)):
        line = lines[idx]
        if not line.lstrip().startswith("|"):
            break  # 表格结束
        cells = split_cells(line)
        if cells and all(SEP_RE.match(c.replace(" ", "")) for c in cells if c.strip()):
            continue  # 分隔行
        rows.append((idx + 1, cells))
    return rows, head_at + 1


def registry_names(rows: list[tuple[int, list[str]]], reds: list[str]) -> set[str]:
    """校验登记表侧判据,返回「合格件名」集合(格式已判红的行不进集合)。"""
    seen: dict[str, int] = {}
    names: set[str] = set()
    for lineno, cells in rows:
        raw_first = clean(cells[0]) if cells else ""
        where = f"{REGISTRY}:{lineno} [{raw_first or '无件名'}]"

        if len(cells) != len(COLUMNS):  # B3-a 格数
            reds.append(f"{where} 格数 {len(cells)} ≠ {len(COLUMNS)}(七列缺一不可)")
            continue
        empties = [COLUMNS[i] for i, c in enumerate(cells) if not clean(c)]  # B3-b 空格
        if empties:
            reds.append(f"{where} 有空格:{'、'.join(empties)}(不适用请填 —,别留空)")
            continue
        name = clean(cells[0])
        if name not in SIX:  # B5 闭集
            reds.append(f"{where} 件名不在六件闭集({'、'.join(SIX)})——第七件要先改口径过评审,不是先落表")
            continue
        if name in seen:  # B6 一件一账
            reds.append(f"{where} 件名重复(第 {seen[name]} 行已有「{name}」)")
            continue
        seen[name] = lineno
        names.add(name)

    for missing_name in SIX:  # B4 六行齐
        if missing_name not in seen:
            reds.append(f"{REGISTRY} 缺「{missing_name}」行——六行齐是批一定稿的判据,缺一行即红")
    return names


# ── 源码扫描(B1–B2)──────────────────────────────────────────────────────

def scan_sources(root: Path) -> list[tuple[str, int, str | None]]:
    """返回 (相对路径, 行号, 件名或 None)。件名 None＝标记缺名/形状不对。"""
    hits: list[tuple[str, int, str | None]] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in SCAN_EXTS:
            continue
        rel_parts = path.relative_to(root).parts
        if any(part in SKIP_DIRS for part in rel_parts):
            continue
        try:
            src = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if MARKER not in src:
            continue
        stripped = strip_comments(src)
        for m in MARK_RE.finditer(stripped):
            line_no = stripped.count("\n", 0, m.start()) + 1
            rest = stripped[m.end():]
            lead = len(rest) - len(rest.lstrip())
            if rest[lead:lead + 1] != "(":
                hits.append((path.relative_to(root).as_posix(), line_no, None))  # B1 裸标记
                continue
            nm = NAMED_RE.match(rest, lead)
            if nm is None:
                hits.append((path.relative_to(root).as_posix(), line_no, None))  # B1 形状不对
                continue
            hits.append((path.relative_to(root).as_posix(), line_no, nm.group(1)))
    return hits


# ── 判定 ──────────────────────────────────────────────────────────────────

class Result:
    def __init__(self) -> None:
        self.reds: list[str] = []
        self.rows = 0
        self.markers = 0


def evaluate(root: Path) -> Result:
    reg_path = root / REGISTRY
    if not reg_path.is_file():
        raise EnvError(f"找不到 {REGISTRY}(登记表被删了——删表不许变成绿)")

    res = Result()
    rows, _ = parse_registry(reg_path)
    res.rows = len(rows)
    names = registry_names(rows, res.reds)

    for rel, line_no, name in scan_sources(root):
        res.markers += 1
        where = f"{rel}:{line_no}"
        if name is None:
            res.reds.append(f"{where} {MARKER} 缺件名或形状不对——只认 {MARKER}(\"件名\")")
        elif name not in names:
            res.reds.append(f"{where} 未登记:{MARKER}(\"{name}\") 在 {REGISTRY} 第一列找不到——"
                            "先改表、过评审,再落码")
    return res


# ── 输出 ──────────────────────────────────────────────────────────────────

def render(res: Result) -> str:
    lines = [f"[bio-analog] 登记 {res.rows} 行(六件闭集)｜带 {MARKER} 标记的类 {res.markers} 处"]
    for red in res.reds:
        lines.append(f"[bio-analog] ✗ 红:{red}")
    if not res.reds:
        tail = "✓ 绿:所有标记类均已登记" if res.markers else "✓ 绿:仓内无标记类,无未登记事项"
        lines.append(f"[bio-analog] {tail}")
    else:
        lines.append(f"[bio-analog] ✗ {len(res.reds)} 条红 —— 退出码 1")
    return "\n".join(lines)


# ── 自检 ──────────────────────────────────────────────────────────────────

TABLE_HEAD = "| " + " | ".join(COLUMNS) + " |"
TABLE_SEP = "| " + " | ".join(["---"] * len(COLUMNS)) + " |"


def _registry_text(drop: str | None = None, blank: tuple[str, str] | None = None,
                   extra: str | None = None) -> str:
    """生成六行齐全的夹具表;drop=删一行,blank=(件,列)留空格,extra=加第七件。"""
    rows = []
    for name in SIX:
        status = "接御医一个读数面" if name == "岛叶" else "定稿·未建"
        cells = [name, "探针机理", "只出读数", "无判定权", "对比朴素做法", "退回现状", status]
        if blank and blank[0] == name:
            cells[COLUMNS.index(blank[1])] = ""
        rows.append("| " + " | ".join(cells) + " |")
    if extra:
        cells = [extra, "探针机理", "只出读数", "无判定权", "对比朴素做法", "退回现状", "定稿·未建"]
        rows.append("| " + " | ".join(cells) + " |")
    if drop:
        rows = [r for r in rows if not r.startswith(f"| {drop} ")]
    body = ["# 夹具登记表", "", "## 二、登记表", "", TABLE_HEAD, TABLE_SEP] + rows
    body += ["", "## 三、散文表(不该被当成登记行)", "", "| 触发 | 关键词 |", "| --- | --- |", "| 缺登记 | 未登记 |"]
    return "\n".join(body) + "\n"


def _fixture(tmp: Path, registry: str, sources: dict[str, str]) -> Path:
    (tmp / "docs").mkdir(parents=True, exist_ok=True)
    (tmp / REGISTRY).write_text(registry, encoding="utf-8")
    for rel, code in sources.items():
        path = tmp / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code, encoding="utf-8")
    return tmp


def selftest() -> int:
    """正例＋负例各一,外加五条守卫与一条防假绿。返回 0＝自检通过。"""
    ok = True

    def check(name: str, registry: str, sources: dict[str, str],
              want_red: tuple[str, ...] | None = None, want_clean: bool = False,
              want_env: bool = False) -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = _fixture(Path(tmp), registry, sources)
            try:
                res = evaluate(root)
            except EnvError as exc:
                if want_env:
                    print(f"[selftest] {name}:环境错误(正确)")
                else:
                    print(f"[selftest] ✗ {name}:夹具触发环境错误 {exc}")
                    ok = False
                return
        if want_env:
            print(f"[selftest] ✗ {name}:应当环境错误,实际照常判定")
            ok = False
        elif want_clean:
            if res.reds:
                print(f"[selftest] ✗ {name}:应当绿,却红了 {res.reds}")
                ok = False
            else:
                print(f"[selftest] {name}:绿(正确)")
        else:
            blob = "\n".join(res.reds)
            lack = [needle for needle in (want_red or ()) if needle not in blob]
            if not res.reds:
                print(f"[selftest] ✗ {name}:应当红,实际绿")
                ok = False
            elif lack:
                print(f"[selftest] ✗ {name}:红了但缺要点 {lack}(实际 {res.reds})")
                ok = False
            else:
                print(f"[selftest] {name}:红(正确,{len(res.reds)} 条)")

    good = _registry_text()
    probe = "app/src/main/java/probe/Probe.kt"

    # 正例:已登记的标记类 ⇒ 绿
    check("正例·已登记", good,
          {probe: f'@BioAnalog("杏仁核")\nclass AmygdalaProbe\n'}, want_clean=True)
    # 负例:未登记的标记类 ⇒ 红(核心判据)
    check("负例·未登记", good,
          {probe: '@BioAnalog("第七件")\nclass GhostProbe\n'}, want_red=("未登记",))

    # 守卫 1:裸标记缺件名 ⇒ 红
    check("守卫·缺件名", good, {probe: "@BioAnalog\nclass BareProbe\n"}, want_red=("缺件名",))
    # 守卫 2:注释里的标记不算承诺 ⇒ 绿(133 批「跳过注释行」同款)
    check("守卫·注释不误判", good,
          {probe: '// @BioAnalog("幽灵件")\n/* @BioAnalog("幽灵件") */\n/** KDoc @BioAnalog("幽灵件") */\nclass QuietProbe\n'},
          want_clean=True)
    # 守卫 3:登记表有空格 ⇒ 红
    check("守卫·登记表空格", _registry_text(blank=("海马", "可测差异")), {}, want_red=("有空格",))
    # 守卫 4:登记表缺行 ⇒ 红(六行齐)
    check("守卫·登记表缺行", _registry_text(drop="丘脑"), {}, want_red=("缺「丘脑」行",))
    # 守卫 5:第七件进表 ⇒ 红(闭集)
    check("守卫·第七件", _registry_text(extra="小脑"), {}, want_red=("不在六件闭集",))

    # 防假绿:表被删 ⇒ 环境错误(退出码 2 的路径),不许变绿
    check("防假绿·表被删", "# 空文档,没有表\n", {}, want_env=True)

    print("[selftest] " + ("✓ 九例全过(正例＋负例＋五守卫＋防假绿)" if ok else "✗ 有用例不达预期"))
    return 0 if ok else 1


# ── 入口 ──────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(add_help=True, description="六体仿生登记闸(626 批一)")
    ap.add_argument("--selftest", action="store_true", help="临时目录夹具自检(正例＋负例＋五守卫＋防假绿)")
    ap.add_argument("--root", default=str(ROOT), help="仓库根(默认＝脚本上一级)")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = Path(args.root).resolve()
    try:
        res = evaluate(root)
    except EnvError as exc:
        print(f"[bio-analog] 环境错误:{exc}")
        return 2
    print(render(res))
    return 1 if res.reds else 0


if __name__ == "__main__":
    raise SystemExit(main())
