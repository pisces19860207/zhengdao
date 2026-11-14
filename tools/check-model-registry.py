#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-model-registry.py —— 模型权重的登记与许可闸（第六百三十四批，2026-11-14）。

一句话
------
把「模型权重能不能进这个仓、进来之前该钉住什么」写成机器判据，**在 CI 上会红**。

它补的是哪个洞
--------------
我们已经有两道许可门禁，但它们都不管模型权重本身：
  * `check-license-policy.py`（423）判的是**代码依赖**（Maven POM / Rust crate）；
  * `check-license-policy.py --assets`（433／440）判的是「`assets/` 下每个文件许可
    是否被允许」，但它**判不了**「这个权重能不能商用、能不能再分发、身份有没有钉住」。
模型权的义务形态不一样：许可、可商用、可再分发、体积、sha256、来源，六件事各自成立，
而且没有 POM 可查。597 之前我们靠用户自己的 API key，一个权重都没进过包——所以还没踩坑；
一旦引入就是裸奔。本脚本把 `docs/models-registry.md`（十列登记表）变成会红的闸。

四条口径（与 `docs/models-registry.md` 第二节同一份，改口径＝改两处＋过评审）
------------------------------------------------------------
1. 默认不内置权重（能用用户的 API key 就不打包模型）；
2. 必须内置时只能 Apache-2.0 / MIT / BSD 类，**CC BY-NC 一律不许**（NC＝不可商用）；
3. 按需下载要登记 sha256 ＋ 来源 ＋ 许可，下载后校验，用户可删；
4. 许可不明（查不到 LICENSE）⇒ 不许进；「查不到」不等于「没问题」。

判据（红 ⇒ 退出码 1；黄 ⇒ 只提示、退出码仍 0）
--------------------------------------------
红：
  R1 磁盘上有权重文件（`app/src/main/assets/**` 命中 `MODEL_EXTS`），登记表里没有这一行；
  R2 任一行 `license` 空 / `—` / 「未核」「不明」「unknown」「tbd」「待定」；
  R3 任一行 `可商用?` = 否（或不是「是」）；
  R4 端侧行 `可再分发?` ≠ 是（随包与提供下载都算再分发）；
  R5 端侧行 `license` 不在四个允许家族内（CC BY-NC / GPL / OFL 全部红）；
  R6 端侧行 `sha256` 不是 64 位十六进制，或与磁盘真文件不一致；
  R7 端侧行 `状态=候选`，但该文件确实躺在 `assets/` 里（状态撒谎）；
  R8 格式类：格数≠10、十格有空、`云/端`／`状态` 不在闭集、`id` 路径非法、同一 id 两行。
黄：
  Y1 `状态=内置` 的行文件找不到（登记表过期，删了文件就删行）；
  Y2 端侧行 `体积` 与真实大小差 > 2%（且 > 64 KiB，避免四舍五入噪声）；
  Y3 云端行把 `体积`／`sha256` 填成了非 `—`（云端没有包体）。
环境错误（退出码 2，**不算通过**）：登记表被删、十列表头找不到。
  ——「删表变绿」是最便宜的假绿，所以这里是 2 不是 0，与 440 的「名册被删 ⇒ 2」同款。

取证口径（只读、不联网、不写仓内文件）
------------------------------------
* 表：`docs/models-registry.md` 里**表头恰好是那十列**的那一张（本文件另有散文表，
  靠表头精确匹配区分，不靠章节名）；
* 盘：`app/src/main/assets/` 递归，按 `MODEL_EXTS` 判「这是不是权重」——
  与 440 资产门禁同范围（不扫 androidTest 的 assets、不扫构建产物、不扫 APK 内容）；
* sha256：对真文件按块读（大文件不炸内存），**按原始字节**算，不做行尾归一
  （权重是二进制，不像 rust 源码那样有 CRLF 问题）。

用法
----
    python3 tools/check-model-registry.py            # 门禁（CI 用这个）
    python3 tools/check-model-registry.py --selftest # 自检：临时目录造夹具，三例判据＋九条守卫＋一条防假绿
    python3 tools/check-model-registry.py --root .   # 指定仓库根（本机排查用）

自检**不写仓库内任何文件**（夹具全在 `tempfile.TemporaryDirectory()` 里），不联网。
它证的是三件事：缺登记会红、非商用会红、齐全会绿——外加九条守卫（许可空会红、
sha256 不符会红、内置缺文件只黄不红、非权重扩展名不误判、按需下载未安装是绿、
一行「云端」登记洗不白随包权重、云端行齐全为绿、云端许可不明为红、端侧不可再分发性红），以及一条防假绿（表头被删 ⇒ 环境错误，不许变绿）。
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import tempfile
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，不让闸自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = "docs/models-registry.md"
ASSETS = "app/src/main/assets"

# ── 十列表头（唯一形状；改这里等于改登记表格式）────────────────────────────────
# COLUMNS 是人写给人看的原名（登记表照抄这一行），HEADER 是归一后的比对键（拉丁小写、
# 全角问号折半角、去空白）——两者都由这一个常量派生，不维护第二份列表。
COLUMNS = ["id", "名称", "来源URL", "license", "可商用?", "可再分发?", "体积", "sha256", "云/端", "状态"]

# ── 模型扩展名闭集（脚本判「这是不是权重」的唯一依据；扩表＝改脚本，进评审）──
MODEL_EXTS = {
    ".gguf", ".onnx", ".tflite", ".litertlm", ".task", ".ort", ".safetensors",
    ".pt", ".pth", ".bin", ".mlmodel", ".model", ".param", ".npy", ".npz",
}

# ── 端侧允许的许可家族（口径 2）：四个，多一个都不行 ──────────────────────────
LICENSE_OK = {"apache-2.0", "mit", "bsd-2-clause", "bsd-3-clause"}

# ── 云/端 与 状态的闭集（端侧不许「在用」，云端不许「内置」）──────────────────
SIDE_STATES = {"端": {"内置", "按需下载", "候选"}, "云": {"在用", "停用", "候选"}}

# 许可「不明」的写法：这些都不算填写（口径 4）
LICENSE_UNKNOWN = {"", "—", "-", "未核", "未核实", "不明", "未知", "unknown", "tbd", "tba", "待定", "n/a", "na"}
# 「不适用」记号（体积／sha256 两列，云端行）
NA_MARKS = {"—", "-", "－"}

SIZE_UNITS = {"b": 1, "kib": 1024, "mib": 1024 ** 2, "gib": 1024 ** 3}
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
SIZE_RE = re.compile(r"^(\d+(?:\.\d+)?)\s*(B|KiB|MiB|GiB)$", re.I)
SEP_RE = re.compile(r"^:?-{3,}:?$")


class EnvError(RuntimeError):
    """环境／解析错误 ⇒ 退出码 2（不是通过）。"""


# ── 小工具 ────────────────────────────────────────────────────────────────

def clean(cell: str) -> str:
    """去掉反引号、强调星号与首尾空白（登记表允许用反引号包裹路径）。"""
    return cell.replace("`", "").replace("**", "").strip()


def norm_head(cell: str) -> str:
    """表头归一：去星号反引号、全角问号→半角、拉丁小写。"""
    text = clean(cell).replace("？", "?").lower()
    return re.sub(r"\s+", "", text)


def norm_license(cell: str) -> str:
    """许可名归一：小写、空格→连字符（`Apache 2.0` 与 `Apache-2.0` 同一件）。"""
    return re.sub(r"\s+", "-", clean(cell).lower())


# 表头比对键：由 COLUMNS 派生，登记表写成全角问号或带反引号也认
HEADER = [norm_head(c) for c in COLUMNS]


def is_na(cell: str) -> bool:
    return clean(cell) in NA_MARKS


def parse_size(text: str) -> int | None:
    m = SIZE_RE.match(clean(text))
    if not m:
        return None
    return int(float(m.group(1)) * SIZE_UNITS[m.group(2).lower()])


def human_size(n: int) -> str:
    if n < 1024:
        return f"{n} B"
    for unit, factor in (("GiB", 1024 ** 3), ("MiB", 1024 ** 2), ("KiB", 1024)):
        if n >= factor:
            return f"{n / factor:.1f} {unit}"
    return f"{n} B"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def split_cells(line: str) -> list[str]:
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|"):
        body = body[:-1]
    return [c.strip() for c in body.split("|")]


# ── 登记表解析 ────────────────────────────────────────────────────────────

def parse_registry(path: Path) -> tuple[list[tuple[int, list[str]]], int]:
    """返回 (行, 表头行号)。行＝(文件行号, 十格原文)。表头找不到 ⇒ EnvError。"""
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    head_at = -1
    for idx, line in enumerate(lines):
        if not line.lstrip().startswith("|"):
            continue
        cells = split_cells(line)
        if len(cells) == len(HEADER) and [norm_head(c) for c in cells] == HEADER:
            head_at = idx
            break
    if head_at < 0:
        raise EnvError(f"{REGISTRY} 里找不到十列表头（{' | '.join(COLUMNS)}）——表被删了或表头被改了")

    rows: list[tuple[int, list[str]]] = []
    for idx in range(head_at + 1, len(lines)):
        line = lines[idx]
        if not line.lstrip().startswith("|"):
            break  # 表格结束（散文表都在别处，靠表头精确匹配已经排除）
        cells = split_cells(line)
        if cells and all(SEP_RE.match(c.replace(" ", "")) for c in cells if c.strip()):
            continue  # 分隔行 | --- | --- |
        rows.append((idx + 1, cells))
    return rows, head_at + 1


# ── 盘上扫描 ──────────────────────────────────────────────────────────────

def scan_assets(root: Path) -> list[str]:
    """`app/src/main/assets/**` 里命中模型扩展名的文件（相对 assets 的 POSIX 路径，已排序）。"""
    base = root / ASSETS
    if not base.is_dir():
        return []
    found = [
        p.relative_to(base).as_posix()
        for p in base.rglob("*")
        if p.is_file() and p.suffix.lower() in MODEL_EXTS
    ]
    return sorted(found)


# ── 判定 ─────────────────────────────────────────────────────────────────

class Result:
    def __init__(self) -> None:
        self.reds: list[str] = []
        self.yellows: list[str] = []
        self.rows = 0
        self.bundled = 0
        self.disk: list[str] = []

    def red(self, where: str, why: str) -> None:
        self.reds.append(f"{where} {why}")

    def yellow(self, where: str, why: str) -> None:
        self.yellows.append(f"{where} {why}")


def _bad_id(cell: str) -> str | None:
    """端侧 id 必须是 assets 下的相对 POSIX 路径；返回错误原因或 None。"""
    if not cell:
        return "id 空"
    if cell.startswith("/") or re.match(r"^[A-Za-z]:", cell):
        return "id 不许是绝对路径（要相对 app/src/main/assets/）"
    if ".." in Path(cell).parts:
        return "id 不许含 .."
    if any(ch in cell for ch in "*?[]"):
        return "id 不许通配（一个文件一行）"
    if cell.endswith("/"):
        return "id 不许是目录"
    return None


def evaluate(root: Path) -> Result:
    reg_path = root / REGISTRY
    if not reg_path.is_file():
        raise EnvError(f"找不到 {REGISTRY}（登记表被删了——删表不许变成绿）")

    rows, head_line = parse_registry(reg_path)
    res = Result()
    res.disk = scan_assets(root)
    res.rows = len(rows)

    seen: dict[str, int] = {}
    registered: set[str] = set()
    assets_base = root / ASSETS

    for lineno, cells in rows:
        where = f"{REGISTRY}:{lineno}"

        # R8-a 格数
        if len(cells) != len(HEADER):
            res.red(where, f"格数 {len(cells)} ≠ {len(HEADER)}（十列缺一不可，空格子不算登记）")
            continue

        raw = {name: cells[i] for i, name in enumerate(COLUMNS)}
        row_id = clean(raw["id"])
        where = f"{REGISTRY}:{lineno} [{row_id or '无 id'}]"

        # R8-b 十格不得为空（`—` 是显式「不适用」，算填写）
        empties = [name for name in COLUMNS if not clean(raw[name])]
        if empties:
            res.red(where, f"有空格子：{'、'.join(empties)}（不适用请填 —，别留空）")
            continue

        # R8-c 云/端 与 状态 的闭集
        side = clean(raw["云/端"])
        status = clean(raw["状态"])
        if side not in SIDE_STATES:
            res.red(where, f"`云/端` 只能是 云 或 端，现在是「{side}」")
            continue
        if status not in SIDE_STATES[side]:
            res.red(where, f"{side}侧 `状态` 只能是 {'／'.join(sorted(SIDE_STATES[side]))}，现在是「{status}」")
            continue

        # 同一 id 两行（一物一账）
        if row_id in seen:
            res.red(where, f"id 重复（第 {seen[row_id]} 行已有同一 id）")
            continue
        seen[row_id] = lineno

        # R2 许可不明
        lic_raw = clean(raw["license"])
        lic = norm_license(lic_raw)
        if lic in {norm_license(x) for x in LICENSE_UNKNOWN} or not lic:
            res.red(where, f"许可不明/空（license=「{lic_raw}」）——查不到许可就不许进")
        # R3 不可商用
        commercial = clean(raw["可商用?"])
        if commercial != "是":
            res.red(where, f"不可商用（可商用?=「{commercial}」）——本项目商用分发，这一档不存在")
        # 来源 URL：非空（上面已判）＋ 端侧必须是 http(s)
        url = clean(raw["来源URL"])
        if side == "端" and not re.match(r"^https?://", url, re.I):
            res.red(where, f"来源URL 必须是 http(s) 地址（现在是「{url}」）")

        if side == "云":
            # Y3：云端没有包体
            for col in ("体积", "sha256"):
                if not is_na(raw[col]):
                    res.yellow(where, f"云端行的 `{col}` 应填 —（云端不复制模型文件，现在是「{clean(raw[col])}」）")
            continue

        # ── 以下全是端侧 ────────────────────────────────────────────────
        bad = _bad_id(row_id)
        if bad:
            res.red(where, bad)
            continue
        registered.add(row_id)

        # R4 可再分发
        redistributable = clean(raw["可再分发?"])
        if redistributable != "是":
            res.red(where, f"不可再分发（可再分发?=「{redistributable}」）——随包与提供下载都算再分发")

        # R5 许可白名单（NC 单独措辞：口径 2 点名禁它）
        if lic and lic not in LICENSE_OK:
            if "nc" in re.split(r"[-.]", lic) or "非商业" in lic_raw or "不可商用" in lic_raw:
                res.red(where, f"CC BY-NC 类许可一律不许（license=「{lic_raw}」）——NC 就是不可商用")
            else:
                res.red(where, f"许可不在允许表（license=「{lic_raw}」）——端侧只认 Apache-2.0／MIT／BSD-2／BSD-3")

        # R6 sha256 格式与真值
        sha = clean(raw["sha256"]).lower()
        if not SHA_RE.match(sha):
            res.red(where, f"sha256 必须是 64 位小写十六进制（现在是「{clean(raw['sha256'])}」）")

        target = assets_base / row_id
        exists = target.is_file()
        if not exists:
            if status == "内置":
                res.yellow(where, "标了内置但 assets 里找不到这个文件（登记表过期？删了文件就删行）")
            continue

        if status == "候选":
            res.red(where, "状态=候选，但文件确实在 assets 里随包——应写「内置」")
        if SHA_RE.match(sha):
            actual = sha256_of(target)
            if actual != sha:
                res.red(where, f"sha256 不符：登记 …{sha[-8:]}／真文件 …{actual[-8:]}（登记不实或文件被换过）")
        # Y2 体积
        want = parse_size(raw["体积"])
        if want is None:
            res.red(where, f"体积格式不对（现在是「{clean(raw['体积'])}」，单位只认 B／KiB／MiB／GiB）")
        else:
            real = target.stat().st_size
            if abs(real - want) > max(real * 0.02, 64 * 1024):
                res.yellow(where, f"体积登记 {human_size(want)}，真文件 {human_size(real)}（差 >2%）")

    state_at = COLUMNS.index("状态")
    res.bundled = sum(
        1 for _, cells in rows if len(cells) == len(COLUMNS) and clean(cells[state_at]) == "内置"
    )

    # R1 盘上有、表里没有 ⇒ 红（未登记）
    # `registered` 只收「端侧且 id 合格」的行：盘上的文件必须有一行**端侧**登记，
    # 光有一行同 id 的云端登记（或格式已判红的那一行）不算——否则「写一行云」就能洗白随包权重。
    for path in res.disk:
        if path in registered:
            continue
        if path in seen:
            res.red(f"{ASSETS}/{path}",
                    "盘上随包的权重，对应登记行却不是端侧（或该行已因格式判红）"
                    "——随包的权重必须有一行 `云/端=端` 的合格登记")
        else:
            res.red(f"{ASSETS}/{path}",
                    "未登记：这是权重文件（扩展名在 MODEL_EXTS 内），登记表里没有这一行。"
                    "确认不是权重的，把文件挪出 assets 或改扩展名，别为它编一行登记")
    return res


# ── 输出 ─────────────────────────────────────────────────────────────────

def render(res: Result) -> str:
    lines = [
        f"[model-registry] 登记 {res.rows} 行（内置 {res.bundled}）｜盘上权重 {len(res.disk)} 个",
    ]
    for where in res.reds:
        lines.append(f"[model-registry] ✗ 红：{where}")
    for where in res.yellows:
        lines.append(f"[model-registry] ! 黄：{where}")
    if not res.reds:
        tail = f"✓ 绿：{res.rows} 行登记全部合规" + (f"，{len(res.disk)} 个盘上权重都已登记" if res.disk else "，盘上没有权重文件")
        if res.yellows:
            tail += f"（{len(res.yellows)} 条提示，不拦 CI）"
        lines.append(f"[model-registry] {tail}")
    else:
        lines.append(f"[model-registry] ✗ {len(res.reds)} 条红、{len(res.yellows)} 条黄 —— 退出码 1")
    return "\n".join(lines)


# ── 自检 ─────────────────────────────────────────────────────────────────

TABLE_HEAD = "| id | 名称 | 来源URL | license | 可商用? | 可再分发? | 体积 | sha256 | 云/端 | 状态 |"
TABLE_SEP = "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"


def _fixture(tmp: Path, rows: list[str], files: dict[str, bytes]) -> Path:
    """在临时目录造一棵最小树：docs/models-registry.md ＋ app/src/main/assets/**。"""
    (tmp / "docs").mkdir(parents=True, exist_ok=True)
    body = ["# 夹具", "", "## 四、登记表", "", TABLE_HEAD, TABLE_SEP]
    body += rows if rows else []
    body += ["", "## 五、判据（散文表，不该被当成登记行）", "", "| 触发 | 关键词 |", "| --- | --- |", "| 缺登记 | 未登记 |"]
    (tmp / "docs/models-registry.md").write_text("\n".join(body) + "\n", encoding="utf-8")
    for rel, data in files.items():
        path = tmp / ASSETS / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return tmp


def _row(row_id: str, lic: str, commercial: str, redist: str, size: str, sha: str,
         side: str, status: str, url: str = "https://example.com/model") -> str:
    return f"| `{row_id}` | 探针模型 | {url} | {lic} | {commercial} | {redist} | {size} | {sha} | {side} | {status} |"


def selftest() -> int:
    """三例判据（缺登记／非商用／齐全）＋ 九条守卫 ＋ 一条防假绿。返回 0＝自检通过。"""
    ok = True
    payload = b"zhengdao-probe" * 128  # 1792 B
    good_sha = hashlib.sha256(payload).hexdigest()
    good_size = human_size(len(payload))

    def missing(items: list[str], needles: tuple[str, ...]) -> list[str]:
        """`needles` 里每一条都得在**红/黄消息合起来的正文**里出现（字面包含）。

        刻意用「全都要在」而不是「有一条在」：断言只认一条时，换一条措辞含同一个词的
        红消息就能骗过自检（本脚本 634 首版就被自己的 NC 消息「NC 就是不可商用」骗过——
        「不可商用」四个字同时出现在两条不同判据的消息里，禁用判据 M2 竟然不红）。
        """
        blob = "\n".join(items)
        return [n for n in needles if n not in blob]

    def case(name: str, rows: list[str], files: dict[str, bytes],
             want_red: tuple[str, ...] | None = None,
             want_yellow: tuple[str, ...] | None = None,
             want_clean: bool = False) -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = _fixture(Path(tmp), rows, files)
            try:
                res = evaluate(root)
            except EnvError as exc:  # 夹具本身造坏了，算自检失败
                print(f"[selftest] ✗ {name}：夹具触发环境错误 {exc}")
                ok = False
                return
        if want_clean:
            if res.reds:
                print(f"[selftest] ✗ {name}：应当绿，却红了 {res.reds}")
                ok = False
            elif want_yellow and missing(res.yellows, want_yellow):
                print(f"[selftest] ✗ {name}：应当有黄提示 {want_yellow}，实际 {res.yellows}")
                ok = False
            elif not want_yellow and res.yellows:
                print(f"[selftest] ✗ {name}：应当零提示，实际 {res.yellows}")
                ok = False
        else:
            if not res.reds:
                print(f"[selftest] ✗ {name}：应当红，实际绿")
                ok = False
            elif want_red and missing(res.reds, want_red):
                lack = missing(res.reds, want_red)
                print(f"[selftest] ✗ {name}：红了但不是要点（缺 {lack}；实际 {res.reds}）")
                ok = False
        print(f"[selftest] {name}：{'红' if res.reds else '绿'}（红 {len(res.reds)}／黄 {len(res.yellows)}）")

    probe = {"models/probe.gguf": payload}

    # 例 1：盘上有权重、表里零行 ⇒ 红（缺登记）
    case("缺登记", [], probe, want_red=("未登记",))
    # 例 2：登记为 CC BY-NC 且 可商用=否 ⇒ 红（非商用）
    case("非商用",
         [_row("models/probe.gguf", "CC-BY-NC-4.0", "否", "是", good_size, good_sha, "端", "内置")],
         probe, want_red=("不可商用（可商用?=「否」", "CC BY-NC 类许可一律不许"))
    # 例 3：齐全（MIT／可商用／可再分发／sha256 与真文件一致／体积一致）⇒ 绿
    case("齐全",
         [_row("models/probe.gguf", "MIT", "是", "是", good_size, good_sha, "端", "内置")],
         probe, want_clean=True)

    # 守卫 1：许可列空（`—`）⇒ 红，而且红的是「许可不明」不是别的
    case("许可空",
         [_row("models/probe.gguf", "—", "是", "是", good_size, good_sha, "端", "内置")],
         probe, want_red=("许可不明/空",))
    # 守卫 2：sha256 与真文件不符 ⇒ 红（登记不实／文件被换）
    case("sha256 不符",
         [_row("models/probe.gguf", "Apache-2.0", "是", "是", good_size, "a" * 64, "端", "内置")],
         probe, want_red=("sha256 不符：",))
    # 守卫 3：标内置但文件不在 ⇒ 只黄不红（登记表过期是提示，不是拦）
    case("内置缺文件",
         [_row("models/gone.onnx", "MIT", "是", "是", "1.0 MiB", good_sha, "端", "内置")],
         {}, want_clean=True, want_yellow=("标了内置",))
    # 守卫 4：非权重扩展名（显示名映射）不算权重 ⇒ 绿（防「扫整个 assets」误杀）
    case("非权重不误判", [], {"models/model-labels.json": b'[{"modelId":"x"}]'}, want_clean=True)
    # 守卫 5：按需下载的行，本机没有文件是**正常态**（口径 3：用户可删／未下载）⇒ 绿，连黄都不该有
    case("按需下载未安装",
         [_row("models/asr-tiny.tflite", "Apache-2.0", "是", "是", "12.0 MiB", good_sha, "端", "按需下载")],
         {}, want_clean=True)
    # 守卫 6：随包权重只写了一行「云端」登记 ⇒ 仍红（不许用一行云把盘上文件洗白）
    case("云端行洗白随包权重",
         [_row("models/probe.gguf", "MIT", "是", "—", "—", "—", "云", "在用")],
         probe, want_red=("却不是端侧",))

    # 守卫 7：云端行齐全（MIT／可商用／体积与 sha256 填 —）⇒ 绿（云端不复制权重，不判再分发）
    case("云端行齐全",
         [_row("deepseek-flash", "MIT", "是", "—", "—", "—", "云", "在用", url="https://api.example.com/pricing")],
         {}, want_clean=True)
    # 守卫 8：云端行的许可不明 ⇒ 红（R2 对云/端两侧都成立，端侧被白名单兜住、云端只有这一条）
    case("云端许可不明",
         [_row("deepseek-flash", "未核", "是", "—", "—", "—", "云", "在用", url="https://api.example.com/pricing")],
         {}, want_red=("许可不明/空",))

    # 守卫 9：端侧「可再分发=否」⇒ 红（随包与提供下载都算再分发，这一档没有例外）
    case("端侧不可再分发",
         [_row("models/probe.gguf", "MIT", "是", "否", good_size, good_sha, "端", "内置")],
         probe, want_red=("不可再分发",))

    # 防假绿：表头被删 ⇒ 环境错误（删表不许变绿）
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "repo"
        (root / "docs").mkdir(parents=True)
        (root / "docs/models-registry.md").write_text("# 空文档，没有表\n", encoding="utf-8")
        try:
            evaluate(root)
            print("[selftest] ✗ 表头被删：应当环境错误，实际照常判定")
            ok = False
        except EnvError:
            print("[selftest] 表头被删：环境错误（正确）")

    print("[selftest] " + ("✓ 十三例全过（三例判据＋九条守卫＋防假绿）" if ok else "✗ 有用例不达预期"))
    return 0 if ok else 1


# ── 入口 ─────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(add_help=True, description="模型权重登记与许可闸（634）")
    ap.add_argument("--selftest", action="store_true", help="临时目录夹具自检（三例判据＋九条守卫＋防假绿）")
    ap.add_argument("--root", default=str(ROOT), help="仓库根（默认＝脚本上一级）")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = Path(args.root).resolve()
    try:
        res = evaluate(root)
    except EnvError as exc:
        print(f"[model-registry] 环境错误：{exc}")
        return 2
    print(render(res))
    return 1 if res.reds else 0


if __name__ == "__main__":
    raise SystemExit(main())
