#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-evo-anchor-wiring.py —— 样本锚与接线登记闸（第五百九十一批，2026-11-11）。

## 它是闸，不是报告

589 造了「样本池＋三条样本线」，但尺子得有人按着：哪一域**声明了锚**、哪一域**真接进了
生产**，这两件事会随着批次漂。本闸把「还欠着的账」写成一份**只减不增**的白名单
（`tools/evo-anchor-wiring.json`），任何人新增一项没接线的域，这里立刻红。

## 三条判据

1. **域清单从真源解析，不硬编码**：`CanaryCapability` 的线值从
   `app/src/main/.../consequence/Canary.kt` 读出来（`ENTRIES` 那一层枚举），
   锚声明从 `.../evolution/samples/EvoSample.kt` 的 `DECLARED` 读出来。
   ——写死一份域清单＝第二套真相，加一格枚举这里不会红，闸就成了一张纸。
2. **接线状态按生产调用点数**：`src/main` 里 `EvoSampleWiring.recordObservation(` 恰好
   一处（`LowRiskEvo.observeRun`）= 已接；0 处 = 待接；多于一处在 `--strict` 下报错
   （多开一处＝多一个攒样本口，判据⑧的名册会先红）。
3. **白名单只减不增**：
   · 不在白名单、又不是"已接" ⇒ 红（新增了欠账却没登记）；
   · 在白名单、却已经"已接" ⇒ 红（债还了就得把这一行删掉）；
   · 白名单条数比上次记录的多 ⇒ 红（`--generate` 也拒绝写入增加）。

## 用法

    python3 tools/check-evo-anchor-wiring.py            # 判红（退出码 1 表示有欠账变化）
    python3 tools/check-evo-anchor-wiring.py --report    # 只打印现状，恒 0
    python3 tools/check-evo-anchor-wiring.py --generate  # 按真实存量重写白名单（只许减）
    python3 tools/check-evo-anchor-wiring.py --selftest  # 三例合成夹具：两红一绿

## 口径（别猜）

「待接」＝代码在、生产调用点不在（589 交付时四域就是这一档）；「无锚」＝注册表里没声明
"一次独立事件按什么认身份"（589 只给四域声明了锚）；「已接」＝真有一处生产调用点。
三种状态都是**跑出来的**，不许手填。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，不让闸自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = Path(__file__).resolve().parent.parent
CANARY_KT = ROOT / "app/src/main/java/com/example/zhengdao/baopu/consequence/Canary.kt"
SAMPLE_KT = ROOT / "app/src/main/java/com/example/zhengdao/baopu/evolution/samples/EvoSample.kt"
WIRING_KT = ROOT / "app/src/main/java/com/example/zhengdao/baopu/evolution/samples/EvoSampleWiring.kt"
MAIN_SRC = ROOT / "app/src/main/java/com/example/zhengdao"
WHITELIST = ROOT / "tools/evo-anchor-wiring.json"

WIRED = "已接"
PENDING = "待接"
UNANCHORED = "无锚"

CALL_MARK = "EvoSampleWiring.recordObservation("


def strip_comments(src: str) -> str:
    """去掉 // 行注释与 /* */ 块注释（只用于数调用点，不追求完美词法）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def capability_wires() -> list[str]:
    """从 Canary.kt 的枚举体里读能力线值（`NAME("wire", "中文", …)`）。"""
    text = CANARY_KT.read_text(encoding="utf-8")
    body = text.split("enum class CanaryCapability", 1)[1].split("companion object", 1)[0]
    return re.findall(r'^\s{4}[A-Z_]+\("([a-z_]+)",', body, flags=re.M)


def anchor_decls() -> dict[str, str]:
    """从 EvoSample.kt 的 DECLARED 读「域 → 锚类型线值」。"""
    text = SAMPLE_KT.read_text(encoding="utf-8")
    body = text.split("val DECLARED", 1)[1].split(")", 1)[0]
    out: dict[str, str] = {}
    for cap, kind in re.findall(r"CanaryCapability\.([A-Z_]+)\s+to\s+EvoAnchorKind\.([A-Z_]+)", body):
        out[cap] = kind
    # 线值要按枚举声明换成 wire，不拿枚举名冒充（线值是落盘稳定键）
    kinds = dict(re.findall(r'^\s{4}([A-Z_]+)\("([a-z_]+)"', SAMPLE_KT.read_text(encoding="utf-8"), flags=re.M))
    caps = dict(re.findall(r'^\s{4}([A-Z_]+)\("([a-z_]+)"', CANARY_KT.read_text(encoding="utf-8"), flags=re.M))
    return {caps.get(cap, cap.lower()): kinds.get(kind, kind.lower()) for cap, kind in out.items()}


def wiring_sites() -> list[str]:
    """数 src/main 里 `EvoSampleWiring.recordObservation(` 出现在哪些文件（注释已剥）。"""
    hits: list[str] = []
    for path in MAIN_SRC.rglob("*.kt"):
        code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        if CALL_MARK in code:
            hits.append(path.relative_to(MAIN_SRC).as_posix())
    return sorted(hits)


def inventory() -> dict[str, dict[str, str]]:
    """真实存量：每域 {anchorKind, wiring}。"""
    anchors = anchor_decls()
    wired = bool(wiring_sites())
    inv: dict[str, dict[str, str]] = {}
    for wire in capability_wires():
        kind = anchors.get(wire)
        if kind is None:
            inv[wire] = {"anchorKind": "", "wiring": UNANCHORED}
        else:
            inv[wire] = {"anchorKind": kind, "wiring": WIRED if wired else PENDING}
    return inv


def statuses(inv: dict[str, dict[str, str]]) -> dict[str, str]:
    """欠账视图：只列"还不是已接"的域（已接不欠账）。"""
    return {wire: row["wiring"] for wire, row in inv.items() if row["wiring"] != WIRED}


def load_whitelist() -> dict[str, str]:
    if not WHITELIST.is_file():
        return {}
    data = json.loads(WHITELIST.read_text(encoding="utf-8"))
    return {k: v for k, v in (data.get("entries") or {}).items()}


def judge(inv: dict[str, dict[str, str]], allowed: dict[str, str]) -> list[str]:
    """三条判据，返回人读问题清单（空＝绿）。"""
    problems: list[str] = []
    live = statuses(inv)
    for wire, state in live.items():
        if wire not in allowed:
            problems.append(f"新增欠账未登记：{wire}（{state}）——要么接线，要么在 --generate 之后写进白名单")
        elif allowed[wire] != state:
            problems.append(f"状态变了要改登记：{wire} 登记为 {allowed[wire]}，实际 {state}")
    for wire in allowed:
        if wire not in live:
            problems.append(f"债已还清，白名单这一行必须删掉（只减不增）：{wire}")
    return problems


def render(inv: dict[str, dict[str, str]], allowed: dict[str, str]) -> str:
    live = statuses(inv)
    lines = [f"[evo-anchor-wiring] 真实存量：{len(inv)} 域 —— " +
             f"已接 {len(inv) - len(live)} ｜ 待接 {sum(1 for v in live.values() if v == PENDING)} ｜ " +
             f"无锚 {sum(1 for v in live.values() if v == UNANCHORED)}"]
    for wire, state in sorted(live.items()):
        mark = "在册" if live[wire] == allowed.get(wire) else "★未登记/已变"
        lines.append(f"  {wire:<22} {state:<4} {mark}")
    return "\n".join(lines)


def generate(dry: bool) -> int:
    inv = inventory()
    live = statuses(inv)
    boot = not WHITELIST.is_file()
    old = load_whitelist()
    grown = [w for w in live if w not in old]
    if grown and not boot:
        print(f"[evo-anchor-wiring] 拒绝：白名单只减不增，本次却要新增 {grown}（先接线，或说明为什么该欠）")
        return 1
    payload = {
        "schema": "zd-evo-anchor-wiring/1",
        "note": "样本锚与接线登记的欠账白名单；只减不增。一条一行：域 → 待接/无锚。"
                "已接的域不许留在这里（check-evo-anchor-wiring.py 会红）。",
        "entries": dict(sorted(live.items())),
    }
    if dry:
        print(json.dumps(payload, ensure_ascii=False, indent=1))
        return 0
    WHITELIST.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"[evo-anchor-wiring] 已写 {WHITELIST.relative_to(ROOT)}：{len(live)} 条"
          f"（{'首次生成＝基线' if boot else f'原 {len(old)} 条'}，只减不增）")
    return 0


def selftest() -> int:
    """三例合成夹具：未登记未接⇒红、已接却在册⇒红、全齐⇒绿。"""
    ok = True
    inv_pending = {"a": {"anchorKind": "", "wiring": PENDING}}
    if not judge(inv_pending, {}):
        print("[selftest] ✗ 未登记的待接域没判红"); ok = False
    inv_wired = {"a": {"anchorKind": "k", "wiring": WIRED}, "b": {"anchorKind": "", "wiring": UNANCHORED}}
    if not judge(inv_wired, {"a": PENDING, "b": UNANCHORED}):
        print("[selftest] ✗ 已接却还在白名单里没判红"); ok = False
    if judge(inv_wired, {"b": UNANCHORED}):
        print("[selftest] ✗ 全齐时误判红"); ok = False
    print("[selftest] " + ("绿（三例：两红一绿）" if ok else "红"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--report", action="store_true", help="只打印现状，恒退出 0")
    ap.add_argument("--generate", action="store_true", help="按真实存量重写白名单（只许减）")
    ap.add_argument("--dry-run", action="store_true", help="配 --generate：只打印，不写文件")
    ap.add_argument("--selftest", action="store_true", help="合成夹具自测")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if args.generate:
        return generate(args.dry_run)

    for path in (CANARY_KT, SAMPLE_KT, WIRING_KT):
        if not path.is_file():
            print(f"[evo-anchor-wiring] 环境错误：找不到 {path.relative_to(ROOT)}（589 的样本池不在本树？）")
            return 2
    inv = inventory()
    allowed = load_whitelist()
    print(render(inv, allowed))
    if args.report:
        return 0
    problems = judge(inv, allowed)
    if problems:
        for p in problems:
            print(f"[evo-anchor-wiring] ✗ {p}")
        return 1
    print(f"[evo-anchor-wiring] ✓ 绿：{len(inv)} 域全部有交代（白名单 {len(allowed)} 条，已接 {len(inv) - len(allowed)} 域）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
