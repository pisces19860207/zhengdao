#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""rules.py —— 773 段②：五类转移的可执行规则（输入档 → 规则 → 输出档）。

每条规则是纯函数 apply(before, action) -> after，只读档位字典、返回新字典，
不联网、不写文件、无随机、无梯度、无 checkpoint。action 里的 op/参数即
「规则」的可执行描述；synth.py 调用它产出 stateAfter，verify.py 再独立重放
同一函数断言逐字节一致。

规则语义逐条照既有代码，不改判据：
  TUR 充血        turgorGrade±1，钳到 0..4（660 hardnessGradeCount=5，EHS）
  DES 适应脱敏    sensitivityGrade 按 734 SensitivityCurve.TABLE=[0,1,2,3,2,1]；
                  intensity>3 进饱和区，discomfortGrade=(intensity-3) 钳到 0..2
  THE 温湿提睾    scrotumSagGrade 冷收紧−1 / 热松弛+1，钳到 1..5；
                  volumeGrade 仅在「容积档」动作里 +1/−1（730 body-zones）
  FAT 疲劳累积    fatigue+1，heartRateBand 随之 +1，均钳到 0..3（658）
  INJ 受伤恢复    recoveryGrade 随 elapsedRounds 单调回落（748 RecoveryModel），
                  到点回 0（GONE）；injuryGrade/painGrade 只在「受击」动作抬升
"""

# 734 SensitivityCurve.TABLE（既有代码，不改）
SENS_TABLE = (0, 1, 2, 3, 2, 1)
SENS_SATURATION_THRESHOLD = 3
SENS_INTENSITY_MAX = 5

TURGOR_MAX = 4
SAG_MIN, SAG_MAX = 1, 5
GRADE_MAX = 3
RECOVERY_BUDGET = {1: 1, 2: 3, 3: 6}  # 748 RecoveryGrade.budgetRounds


def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


def _copy(vec):
    return dict(vec)


def apply_turgor(before, action):
    """turgor：up +1 / down -1 / hold 不变；钳 0..4。"""
    out = _copy(before)
    g = out["turgorGrade"]
    op = action["op"]
    if op == "up":
        g += int(action.get("step", 1))
    elif op == "down":
        g -= int(action.get("step", 1))
    elif op != "hold":
        raise ValueError("TUR unknown op: %s" % op)
    out["turgorGrade"] = _clamp(g, 0, TURGOR_MAX)
    return out


def apply_desens(before, action):
    """734 适应脱敏：intensity 走饱和表；sensitivity 按表值重算；discomfort 按阈。"""
    out = _copy(before)
    op = action["op"]
    intensity = int(action["intensity"])
    if not (0 <= intensity <= SENS_INTENSITY_MAX):
        raise ValueError("DES intensity out of range: %s" % intensity)
    if op in ("stimulate", "saturate", "adapt"):
        out["sensitivityGrade"] = SENS_TABLE[intensity]
        out["discomfortGrade"] = _clamp(intensity - SENS_SATURATION_THRESHOLD, 0, 2)
        out["frequencyGrade"] = int(action.get("frequencyGrade", out.get("frequencyGrade", 0)))
    elif op == "recover":
        # 停止刺激后按恢复档回升一档（方向单调向常态），钳 0..3
        out["sensitivityGrade"] = _clamp(out["sensitivityGrade"] + 1, 0, GRADE_MAX)
        out["discomfortGrade"] = 0
    else:
        raise ValueError("DES unknown op: %s" % op)
    out["frequencyGrade"] = _clamp(out["frequencyGrade"], 0, GRADE_MAX)
    return out


def apply_thermal(before, action):
    """730 温湿提睾：cold收紧 sag−1 / heat松弛 sag+1 / volume 动作改容积档。"""
    out = _copy(before)
    op = action["op"]
    sag = out["scrotumSagGrade"]
    vol = out["scrotumVolumeGrade"]
    if op == "cold":
        sag -= 1
    elif op == "heat":
        sag += 1
    elif op == "volume_up":
        vol += 1
    elif op == "volume_down":
        vol -= 1
    elif op != "hold":
        raise ValueError("THE unknown op: %s" % op)
    out["scrotumSagGrade"] = _clamp(sag, SAG_MIN, SAG_MAX)
    out["scrotumVolumeGrade"] = _clamp(vol, SAG_MIN, SAG_MAX)
    return out


def apply_fatigue(before, action):
    """658 疲劳累积：exert +1，rest −1；心率档同向跟随。"""
    out = _copy(before)
    op = action["op"]
    step = int(action.get("step", 1))
    if op == "exert":
        delta = step
    elif op == "rest":
        delta = -step
    elif op == "hold":
        delta = 0
    else:
        raise ValueError("FAT unknown op: %s" % op)
    out["fatigue"] = _clamp(out["fatigue"] + delta, 0, GRADE_MAX)
    out["heartRateBand"] = _clamp(out["heartRateBand"] + delta, 0, GRADE_MAX)
    return out


def apply_injury(before, action):
    """748 受伤恢复：impact 抬伤情/疼痛档并起恢复档；recover 单调回落。"""
    out = _copy(before)
    op = action["op"]
    if op == "impact":
        tier = int(action["tier"])
        out["injuryGrade"] = _clamp(tier, 0, GRADE_MAX)
        out["painGrade"] = _clamp(tier, 0, GRADE_MAX)
        out["recoveryGrade"] = _clamp(3 if tier >= 2 else (2 if tier == 1 else 1), 1, 3)
    elif op == "recover":
        r = out["recoveryGrade"]
        elapsed = int(action["elapsedRounds"])
        spent = max(elapsed, 0)
        if spent >= RECOVERY_BUDGET[r]:
            out["recoveryGrade"] = 0  # GONE，falls out of 1..3 三档
        else:
            left = RECOVERY_BUDGET[r] - spent
            out["recoveryGrade"] = 1 if left <= 1 else (2 if left <= 3 else 3)
        if out["recoveryGrade"] == 0:
            out["injuryGrade"] = 0
            out["painGrade"] = 0
    else:
        raise ValueError("INJ unknown op: %s" % op)
    return out


APPLY = {
    "TUR": apply_turgor,
    "DES": apply_desens,
    "THE": apply_thermal,
    "FAT": apply_fatigue,
    "INJ": apply_injury,
}


def apply(cls, before, action):
    """按转移类分派规则函数。"""
    if cls not in APPLY:
        raise ValueError("unknown transfer class: %s" % cls)
    if cls == "INJ" and isinstance(before.get("recoveryGrade"), int) and before["recoveryGrade"] == 0:
        raise ValueError("INJ before.recoveryGrade=0(GONE) 无恢复起点")
    return APPLY[cls](before, action)


def replay(sample):
    """独立重放一条样本：用规则从 stateBefore+action 重算，返回 (ok, expected_after)。"""
    from schema import class_of
    cls = class_of(sample["id"])
    try:
        expected = apply(cls, sample["stateBefore"], sample["action"])
    except (KeyError, ValueError, TypeError) as exc:
        return False, {"error": str(exc)}
    return expected == sample["stateAfter"], expected
