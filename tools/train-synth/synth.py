#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""synth.py —— 773 段②：规则合成集生成器（≥200 条，五类转移各 ≥30）。

用法
    python3 synth.py --out data/train-synth.jsonl
    python3 synth.py --count-only          # 只打印各类计数

设计
    · 全部 source=rule-synth（本集只做规则合成，llm/human 是格式预留，不生产）
    · 每条 stateAfter 由 rules.apply(stateBefore, action) 算出 → 可复现
    · 纯枚举、无随机、无时间、无 hash 种子依赖 → 同输入两次逐字节一致
    · id 用 SYN- 前缀，与 786 的 NAR- 前缀天然异域；语料指纹断言在 verify.py
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import rules
import schema

# 每类 44 条 × 5 类 = 220 条（≥200，且每类 ≥30）
TARGET_PER_CLASS = {
    "TUR": 44, "DES": 44, "THE": 44, "FAT": 44, "INJ": 44,
}


def _sample(cls, seq, before, action):
    rv = schema.TRANSFER_CLASSES[cls][1]
    after = rules.apply(cls, before, action)
    return {
        "id": "SYN-%s-%06d" % (cls, seq),
        "stateBefore": before,
        "action": action,
        "stateAfter": after,
        "ruleVersion": rv,
        "source": "rule-synth",
    }


def _even_pick(combos, target):
    """从组合全集按等距下标取 target 条——确定性，且均匀覆盖每个维度。

    为什么不用「外层穷尽」的顺序：初版按外层 g/intensity 穷尽到 44 条就停，
    DES 只覆盖 intensity 0..3，把 734 最关键的饱和反转点 4/5 漏在集外
    （变异测试把 SENS_TABLE[4] 改坏竟零样本被咬）。等距取样保证每个起点档、
    每个强度档都进集。n<=target 时全取，不重复。
    """
    n = len(combos)
    if n == 0:
        raise ValueError("empty combos")
    if n <= target:
        return list(combos)
    return [combos[(i * n) // target] for i in range(target)]


def gen_turgor():
    combos = [(g, op, step) for g in range(0, 5)
              for op in ("up", "down", "hold") for step in (1, 2, 3)]
    out = []
    for i, (g, op, step) in enumerate(_even_pick(combos, TARGET_PER_CLASS["TUR"]), 1):
        out.append(_sample("TUR", i, {"turgorGrade": g}, {"op": op, "step": step}))
    return out


def gen_desens():
    combos = [(intensity, op, freq) for intensity in range(0, 6)
              for op in ("stimulate", "saturate", "adapt", "recover") for freq in (1, 2, 3)]
    out = []
    for i, (intensity, op, freq) in enumerate(_even_pick(combos, TARGET_PER_CLASS["DES"]), 1):
        before = {"sensitivityGrade": 2, "discomfortGrade": 0, "frequencyGrade": freq}
        out.append(_sample("DES", i, before, {"op": op, "intensity": intensity}))
    return out


def gen_thermal():
    combos = [(sag, vol, op) for sag in range(1, 6) for vol in (2, 3, 4)
              for op in ("cold", "heat", "volume_up", "volume_down", "hold")]
    out = []
    for i, (sag, vol, op) in enumerate(_even_pick(combos, TARGET_PER_CLASS["THE"]), 1):
        out.append(_sample("THE", i, {"scrotumSagGrade": sag, "scrotumVolumeGrade": vol}, {"op": op}))
    return out


def gen_fatigue():
    combos = [(f, hr, op) for f in range(0, 4) for hr in range(0, 4)
              for op in ("exert", "rest", "hold")]
    out = []
    for i, (f, hr, op) in enumerate(_even_pick(combos, TARGET_PER_CLASS["FAT"]), 1):
        out.append(_sample("FAT", i, {"fatigue": f, "heartRateBand": hr}, {"op": op}))
    return out


def gen_injury():
    target = TARGET_PER_CLASS["INJ"]
    impact_n = 15  # tier 0..4 × 起点 r 1..3，impact 组合全取
    recover_n = target - impact_n
    out = []
    seq = 0
    # 748 ImpactLink：冲击档 0..4 经 INJURY_START=[0,0,1,2,3] 映射到观察档 0..3。
    # stateBefore 装的是观察档（0..3），不是冲击档；tier 只作 action 入参。
    impact_to_grade = (0, 0, 1, 2, 3)
    for tier, r in [(t, r) for t in range(0, 5) for r in (1, 2, 3)]:
        seq += 1
        g = impact_to_grade[tier]
        before = {"injuryGrade": g, "painGrade": g, "recoveryGrade": r}
        out.append(_sample("INJ", seq, before, {"op": "impact", "tier": tier}))
    recover_combos = [(r, elapsed, tier) for r in (1, 2, 3) for elapsed in (0, 1, 2, 3, 4, 5)
                      for tier in (0, 1, 2, 3)]
    for r, elapsed, tier in _even_pick(recover_combos, recover_n):
        seq += 1
        before = {"injuryGrade": tier, "painGrade": tier, "recoveryGrade": r}
        out.append(_sample("INJ", seq, before, {"op": "recover", "elapsedRounds": elapsed}))
    return out


GENERATORS = {
    "TUR": gen_turgor,
    "DES": gen_desens,
    "THE": gen_thermal,
    "FAT": gen_fatigue,
    "INJ": gen_injury,
}


def generate_all():
    samples = []
    for cls in ("TUR", "DES", "THE", "FAT", "INJ"):
        samples.extend(GENERATORS[cls]())
    return samples


def counts(samples):
    out = {}
    for s in samples:
        cls = schema.class_of(s["id"])
        out[cls] = out.get(cls, 0) + 1
    return out


def source_mix(samples):
    out = {}
    for s in samples:
        out[s["source"]] = out.get(s["source"], 0) + 1
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/train-synth.jsonl")
    ap.add_argument("--count-only", action="store_true")
    args = ap.parse_args()

    samples = generate_all()
    print("total=%d" % len(samples))
    print("counts=%s" % json.dumps(counts(samples), ensure_ascii=False, sort_keys=True))
    print("source_mix=%s" % json.dumps(source_mix(samples), ensure_ascii=False, sort_keys=True))
    if args.count_only:
        return

    out_path = args.out
    parent = os.path.dirname(os.path.abspath(out_path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False, sort_keys=True) + "\n")
    print("written=%s" % os.path.abspath(out_path))


if __name__ == "__main__":
    main()
