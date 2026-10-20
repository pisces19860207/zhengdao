#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-radius-tokens-selftest.py —— 圆角令牌门禁的自测（第七十批 · UI 第六批）。

它钉死的是「门禁本身会不会误杀／会不会放水」，不是业务代码：
  [1/5] 派工单 §4 的四个判据（裸 8.dp ⇒ 1；rChip/ShapeCard ⇒ 0；999.dp 进白名单 ⇒ 0；28.dp 未进白名单 ⇒ 1）
  [2/5] 胶囊与百分比写法（`50` 百分比 ⇒ 0、`999.dp` 不写白名单也 ⇒ 0、`20.dp` 半高圆 ⇒ 1）
  [3/5] 不误杀与不放过（整行注释/KDoc 里的反例词 ⇒ 0；多角写法逐角报红；常量参数＝已知盲区 ⇒ 0）
  [4/5] 白名单语义（目录前缀按 / 分段匹配：ui/demo/ 放行 ui/demo/，不放行 ui/demo2/；
        值 `*` ＝任意值；过期条目打提示但不改退出码；格式错行被跳过）
  [5/5] 真实仓读数（扫 ui/ 全量 ⇒ 0 报红，并把命中/胶囊/白名单三个计数打在结果里）

用法：
  python tools/check-radius-tokens-selftest.py [--keep]
退出码：0 = PASS；1 = FAIL。合成文件都写在临时目录，跑完自动删（--keep 保留）。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

HERE = Path(__file__).resolve().parent
TOOL = HERE / "check-radius-tokens.py"
REPO = HERE.parent
REAL_WHITELIST = HERE / "radius-whitelist.txt"

STATS = {"pass": 0, "fail": 0}
FAILURES: list[str] = []


def log(msg: str = "") -> None:
    sys.stdout.write(msg + "\n")
    sys.stdout.flush()


def step(idx: int, title: str) -> None:
    log("")
    log(f"[{idx}/5] {title}")


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        STATS["pass"] += 1
        log(f"  ✓ {name}")
    else:
        STATS["fail"] += 1
        FAILURES.append(name)
        log(f"  ✗ {name}  {detail}")


def run(tool_args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(TOOL), *tool_args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )


def gate_rc(paths: list[Path], whitelist: Path | None) -> tuple[int, dict]:
    """跑门禁（--json），返回退出码与解析后的结果。"""
    args = ["--paths", ",".join(str(p) for p in paths), "--json"]
    if whitelist is not None:
        args += ["--whitelist", str(whitelist)]
    p = run(args)
    try:
        data = json.loads(p.stdout)
    except Exception:
        data = {"raw": p.stdout, "stderr": p.stderr}
    return p.returncode, data


def make(work: Path, rel: str, body: str) -> Path:
    fp = work / rel
    fp.parent.mkdir(parents=True, exist_ok=True)
    fp.write_text(body, encoding="utf-8", newline="\n")
    return fp


def wl(work: Path, name: str, rows: list[str]) -> Path:
    p = work / name
    p.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    return p


# ---------------------------------------------------------------- 用例


def case_order_four(work: Path) -> None:
    """派工单 §4 的四个判据。"""
    f8 = make(work, "demo/Raw8.kt", "val a = RoundedCornerShape(8.dp)\n")
    rc, _ = gate_rc([f8], None)
    check("§4-1 合成文件裸写 8.dp ⇒ 1", rc == 1, f"实际 rc={rc}")

    ftk = make(work, "demo/Token.kt",
               "val a = RoundedCornerShape(rChip)\nval b = ShapeCard\n")
    rc, _ = gate_rc([ftk], None)
    check("§4-2 写 rChip / ShapeCard ⇒ 0", rc == 0, f"实际 rc={rc}")

    f999 = make(work, "demo/Pill999.kt", "val a = RoundedCornerShape(999.dp)\n")
    w999 = wl(work, "wl999.txt", [f"demo/Pill999.kt | 999 | 胶囊占位"])
    rc, _ = gate_rc([f999], w999)
    check("§4-3 写 999.dp 且进白名单 ⇒ 0", rc == 0, f"实际 rc={rc}")

    f28 = make(work, "demo/Custom28.kt", "val a = RoundedCornerShape(28.dp)\n")
    rc, _ = gate_rc([f28], None)
    check("§4-4 写 28.dp 未进白名单 ⇒ 1", rc == 1, f"实际 rc={rc}")
    w28 = wl(work, "wl28.txt", ["demo/Custom28.kt | 28 | 自定义：8 档之外"])
    rc, _ = gate_rc([f28], w28)
    check("§4-4b 同一条进白名单后 ⇒ 0", rc == 0, f"实际 rc={rc}")


def case_capsule(work: Path) -> None:
    """胶囊与百分比写法。"""
    f50 = make(work, "demo/Percent50.kt", "val a = RoundedCornerShape(50)\n")
    rc, data = gate_rc([f50], None)
    kinds = [h["kind"] for h in data.get("hits", [])]
    check("百分比 RoundedCornerShape(50) ⇒ 0 且判成胶囊", rc == 0 and kinds == ["胶囊(≥50)"],
          f"rc={rc} kinds={kinds}")

    f999 = make(work, "demo/Pill999free.kt", "val a = RoundedCornerShape(999.dp)\n")
    rc, _ = gate_rc([f999], None)
    check("999.dp 不写白名单也自动放行 ⇒ 0", rc == 0, f"实际 rc={rc}")

    f20 = make(work, "demo/Half20.kt", "val a = RoundedCornerShape(20.dp)\n")
    rc, _ = gate_rc([f20], None)
    check("20.dp（半高圆，<50）未登记 ⇒ 1", rc == 1, f"实际 rc={rc}")


def case_false_and_miss(work: Path) -> None:
    """不误杀（注释里的反例词）＋ 不放过（多角写法）＋ 已知盲区（常量参数）。"""
    fc = make(work, "demo/Comments.kt",
              "// 反例：RoundedCornerShape(8.dp) 不许回潮\n"
              "/* KDoc 里也写 RoundedCornerShape(16.dp) */\n"
              "/**\n * 用 RoundedCornerShape(24.dp) 举例\n */\n"
              "val a = ShapeChip\n")
    rc, data = gate_rc([fc], None)
    check("整行注释/块注释/KDoc 里的裸值不误杀 ⇒ 0", rc == 0 and data["counts"]["hits"] == 0,
          f"rc={rc} hits={data['counts'].get('hits')}")

    fm = make(work, "demo/MultiCorner.kt",
              "val a = RoundedCornerShape(topStart = 16.dp, bottomEnd = 4.dp)\n")
    rc, data = gate_rc([fm], None)
    reds = [h for h in data.get("hits", []) if h["verdict"] == "报红"]
    vals = sorted(h["value"] for h in reds)
    check("多角写法逐角报红（16 与 4 各一条）", rc == 1 and vals == [4.0, 16.0],
          f"rc={rc} vals={vals}")

    fk = make(work, "demo/ConstantArg.kt",
              "private val MY_R = 8.dp\nval a = RoundedCornerShape(MY_R)\n")
    rc, data = gate_rc([fk], None)
    check("常量参数＝已知盲区 ⇒ 0（对账表里点名）", rc == 0 and data["counts"]["hits"] == 0,
          f"rc={rc} hits={data['counts'].get('hits')}")


def case_whitelist(work: Path) -> None:
    """目录前缀分段匹配、`*` 任意值、过期提示、格式错行。"""
    a = make(work, "demo/A.kt", "val a = RoundedCornerShape(12.dp)\n")
    b = make(work, "demo2/B.kt", "val a = RoundedCornerShape(12.dp)\n")
    wdir = wl(work, "wl_dir.txt", ["demo/ | 12 | 目录前缀放行"])
    rc_a, _ = gate_rc([a], wdir)
    rc_b, _ = gate_rc([b], wdir)
    check("目录前缀 demo/ 放行 demo/A.kt ⇒ 0", rc_a == 0, f"实际 rc={rc_a}")
    check("相邻目录 demo2/ 不被 demo/ 误放行 ⇒ 1", rc_b == 1, f"实际 rc={rc_b}")

    wstar = wl(work, "wl_star.txt", ["demo/Astar.kt | * | 该文件任意值"])
    astar = make(work, "demo/Astar.kt", "val a = RoundedCornerShape(12.dp)\nval b = RoundedCornerShape(28.dp)\n")
    rc, _ = gate_rc([astar], wstar)
    check("白名单值 `*` ⇒ 该文件任意值都放行", rc == 0, f"实际 rc={rc}")

    gone = wl(work, "wl_gone.txt", ["demo/Gone.kt | 8 | 这条已经没有对应裸值了"])
    okfile = make(work, "demo/Ok.kt", "val a = ShapeCard\n")
    p = run(["--paths", str(okfile), "--whitelist", str(gone)])
    check("过期白名单条目：打「过期」提示但 rc 仍 0",
          p.returncode == 0 and "过期" in p.stdout, f"rc={p.returncode}")

    bad = wl(work, "wl_bad.txt", ["这一行忘了竖线", "demo/A.kt | 12 | 正常行"])
    rc, _ = gate_rc([a], bad)
    check("白名单格式错行：跳过并提示，正常行仍生效", rc == 0, f"实际 rc={rc}")


def case_real_repo() -> None:
    """真实仓读数：扫 ui/ 全量必须 0 报红，并把三个计数带进结果。"""
    p = run(["--json"])
    ok = p.returncode == 0
    counts = {}
    if ok:
        try:
            counts = json.loads(p.stdout)["counts"]
        except Exception:
            ok = False
    check("真实仓 ui/ 全量扫描 ⇒ 0 报红", ok, f"rc={p.returncode} stderr={p.stderr[:120]}")
    log(f"     读数：files={counts.get('files')} hits={counts.get('hits')} "
        f"胶囊={counts.get('token_pass')} 白名单={counts.get('whitelist_pass')} 报红={counts.get('red')}")
    wl_text = REAL_WHITELIST.read_text(encoding="utf-8-sig", errors="replace")
    rows = [l for l in wl_text.splitlines() if l.strip() and not l.strip().startswith("#")]
    bad_rows = [l for l in rows if len([x for x in l.split("|") if x.strip()]) < 3]
    check(f"白名单 {len(rows)} 行全部是「路径|值|理由」三段式", not bad_rows, f"坏行={bad_rows}")


# ---------------------------------------------------------------- 主流程


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="圆角令牌门禁自测")
    ap.add_argument("--keep", action="store_true", help="保留临时目录（默认删）")
    ns = ap.parse_args(argv)

    if not TOOL.is_file():
        log(f"✗ 找不到被验工具：{TOOL}")
        return 1

    work = Path(tempfile.mkdtemp(prefix="radius70-selftest-"))
    log(f"圆角令牌门禁自测 —— 合成目录 {work}")
    try:
        step(1, "派工单 §4 的四个判据")
        case_order_four(work)
        step(2, "胶囊与百分比写法")
        case_capsule(work)
        step(3, "不误杀 / 不放过 / 已知盲区")
        case_false_and_miss(work)
        step(4, "白名单语义")
        case_whitelist(work)
        step(5, "真实仓读数")
        case_real_repo()
    finally:
        if ns.keep:
            log(f"（--keep）临时目录保留：{work}")
        else:
            import shutil
            shutil.rmtree(work, ignore_errors=True)

    log("")
    log(f"结果：PASS {STATS['pass']} / FAIL {STATS['fail']}"
        + ("；失败项：" + "、".join(FAILURES) if FAILURES else ""))
    return 0 if not FAILURES else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
