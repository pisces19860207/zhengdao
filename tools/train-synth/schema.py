#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""schema.py —— 773 段①：训练样本格式（六字段）与档位向量口径。

一句话
    定义每条样本必须带的六个字段、stateBefore/After 允许的档位字段白名单、
    以及 source 枚举。缺 source 或字段不齐 → 拒收。

档位向量引用既有字段，不新造（每个字段都标了既有代码出处）：
    turgorGrade        ← 660 GenitalModel 硬度档（facts.hardnessGradeCount=5，EHS 0–4）
    sensitivityGrade   ← 734 StimulusGrades.SensitivityGrade 0–3
    discomfortGrade    ← 734 StimulusGrades.DiscomfortGrade 0–2
    frequencyGrade     ← 734 StimulusGrades.FrequencyGrade 0–3
    depthGrade         ← 734 StimulusGrades.DepthGrade 0–3
    withdrawGrade      ← 734 StimulusGrades.WithdrawGrade 0–2
    scrotumSagGrade    ← 730 body-zones.json scrotum_sag_grade 1–5（松弛档，冷收紧=低档）
    scrotumVolumeGrade ← 730 body-zones.json scrotum_volume_grade 1–5
    fatigue            ← 658 PhysioGrades.Fatigue 0–3
    heartRateBand      ← 658 PhysioGrades.HeartRateBand 0–3
    bodyTempShift      ← 658 PhysioGrades.BodyTempShift 0–3
    sweatLevel         ← 658 PhysioGrades.SweatLevel 0–3
    injuryGrade        ← 748 InjuryGrades.InjuryGrade 0–3
    painGrade          ← 748 InjuryGrades.PainGrade 0–3
    recoveryGrade      ← 748 InjuryGrades.RecoveryGrade 1–3（0 是 GONE，不属三档）

ruleVersion 指向产生该转移的规则来源（批次号 + 既有符号），不是自由文本。
source ∈ {rule-synth, llm, human}，缺 source 一律拒收。

纯 Python 标准库；不联网；不引任何 ML 库。
"""

import re

# ---------------------------------------------------------------- 六字段

REQUIRED_FIELDS = (
    "id",
    "stateBefore",
    "action",
    "stateAfter",
    "ruleVersion",
    "source",
)

SOURCES = ("rule-synth", "llm", "human")

ID_PATTERN = re.compile(r"^SYN-(TUR|DES|THE|FAT|INJ)-\d{6}$")

# ---------------------------------------------------------------- 档位字段白名单
# field -> (min, max, 既有代码出处)。档是整数，不引入点值。
GRADE_FIELDS = {
    "turgorGrade":        (0, 4, "660/GenitalModel.turgorGrade; facts.hardnessGradeCount=5 (EHS 0-4)"),
    "sensitivityGrade":   (0, 3, "734/StimulusGrades.SensitivityGrade"),
    "discomfortGrade":    (0, 2, "734/StimulusGrades.DiscomfortGrade"),
    "frequencyGrade":     (0, 3, "734/StimulusGrades.FrequencyGrade"),
    "depthGrade":         (0, 3, "734/StimulusGrades.DepthGrade"),
    "withdrawGrade":      (0, 2, "734/StimulusGrades.WithdrawGrade"),
    "scrotumSagGrade":    (1, 5, "730/docs/facts/body-zones.json#scrotum_sag_grade"),
    "scrotumVolumeGrade": (1, 5, "730/docs/facts/body-zones.json#scrotum_volume_grade"),
    "fatigue":            (0, 3, "658/PhysioGrades.Fatigue"),
    "heartRateBand":      (0, 3, "658/PhysioGrades.HeartRateBand"),
    "bodyTempShift":      (0, 3, "658/PhysioGrades.BodyTempShift"),
    "sweatLevel":         (0, 3, "658/PhysioGrades.SweatLevel"),
    "injuryGrade":        (0, 3, "748/InjuryGrades.InjuryGrade"),
    "painGrade":          (0, 3, "748/InjuryGrades.PainGrade"),
    "recoveryGrade":      (0, 3, "748/InjuryGrades.RecoveryGrade 1-3 三档；0=GONE 恢复完成终态"),
}

# ---------------------------------------------------------------- 五类转移
# class_id -> (显示名, ruleVersion, 必带档位字段, 合成条数目标)
TRANSFER_CLASSES = {
    "TUR": ("充血(turgor)",      "731-GenitalModel.turgorGrade",       ("turgorGrade",), 44),
    "DES": ("适应脱敏(734)",     "734-SensitivityCurve.table+adapt",   ("sensitivityGrade", "discomfortGrade", "frequencyGrade"), 44),
    "THE": ("温湿提睾(730)",     "730-body-zones.scrotum_sag_grade",   ("scrotumSagGrade", "scrotumVolumeGrade"), 44),
    "FAT": ("疲劳累积(658)",     "658-PhysioGrades.Fatigue",           ("fatigue", "heartRateBand"), 44),
    "INJ": ("受伤恢复(748)",     "748-InjuryGrades.RecoveryModel",     ("injuryGrade", "painGrade", "recoveryGrade"), 44),
}

MIN_PER_CLASS = 30
MIN_TOTAL = 200


def class_of(sample_id):
    """从 id 取转移类前缀，如 SYN-TUR-000001 -> TUR。"""
    m = ID_PATTERN.match(sample_id) if isinstance(sample_id, str) else None
    return m.group(1) if m else None


def _err(code, msg):
    return {"code": code, "message": msg}


def validate(sample, *, strict_extra=False):
    """校验一条样本。返回错误列表；空列表＝合格。

    strict_extra=True 时禁止六字段之外的额外键（生成器默认开，防塞料）。
    错误带稳定的 code 前缀，便于变异测试精确断言，不靠整句包含。
    """
    errors = []
    if not isinstance(sample, dict):
        return [_err("NOT_OBJECT", "样本不是对象")]

    keys = set(sample)
    missing = [f for f in REQUIRED_FIELDS if f not in keys]
    for field in missing:
        code = "SOURCE_MISSING" if field == "source" else "FIELD_MISSING"
        errors.append(_err(code, "缺必带字段 %s" % field))

    if strict_extra:
        extra = sorted(keys - set(REQUIRED_FIELDS))
        if extra:
            errors.append(_err("EXTRA_FIELD", "六字段之外多出 %s" % ",".join(extra)))

    # id
    sid = sample.get("id")
    if "id" in sample and (not isinstance(sid, str) or not ID_PATTERN.match(sid)):
        errors.append(_err("ID_FORMAT", "id 格式不合规: %r" % (sid,)))
    if "id" in sample and isinstance(sid, str) and ID_PATTERN.match(sid):
        if class_of(sid) not in TRANSFER_CLASSES:
            errors.append(_err("ID_CLASS", "id 前缀不在五类内: %s" % sid))

    # source
    if "source" in sample and sample["source"] not in SOURCES:
        errors.append(_err("SOURCE_VALUE", "source 取值非法: %r" % (sample["source"],)))

    # ruleVersion
    rv = sample.get("ruleVersion")
    if "ruleVersion" in sample and (not isinstance(rv, str) or not rv.strip()):
        errors.append(_err("RULE_VERSION", "ruleVersion 必须是指向规则来源的非空字符串"))
    if "ruleVersion" in sample and isinstance(rv, str) and rv.strip():
        known = {v[1] for v in TRANSFER_CLASSES.values()}
        if rv not in known:
            errors.append(_err("RULE_VERSION_UNKNOWN", "ruleVersion 不在已知规则来源内: %s" % rv))

    # action
    act = sample.get("action")
    if "action" in sample:
        if not isinstance(act, dict):
            errors.append(_err("ACTION_TYPE", "action 必须是对象"))
        elif not isinstance(act.get("op"), str) or not act["op"].strip():
            errors.append(_err("ACTION_OP", "action.op 必须是非空字符串（可执行描述）"))

    # 档位向量 before/after
    cls = class_of(sid) if isinstance(sid, str) else None
    for which in ("stateBefore", "stateAfter"):
        vec = sample.get(which)
        if which not in sample:
            continue
        if not isinstance(vec, dict):
            errors.append(_err("%s_TYPE" % which.upper(), "%s 必须是档位向量对象" % which))
            continue
        for key, value in vec.items():
            if key not in GRADE_FIELDS:
                errors.append(_err("GRADE_FIELD_UNKNOWN", "%s 含未登记档位字段 %s（禁止新造）" % (which, key)))
                continue
            lo, hi, _src = GRADE_FIELDS[key]
            if isinstance(value, bool) or not isinstance(value, int):
                errors.append(_err("GRADE_NOT_INT", "%s.%s 必须是整数档，得 %r" % (which, key, value)))
            elif not (lo <= value <= hi):
                errors.append(_err("GRADE_OUT_OF_RANGE", "%s.%s=%s 超既有域 %d..%d" % (which, key, value, lo, hi)))
        if cls in TRANSFER_CLASSES:
            for field in TRANSFER_CLASSES[cls][2]:
                if field not in vec:
                    errors.append(_err("GRADE_CLASS_FIELD_MISSING", "%s 缺该类必带档位 %s" % (which, field)))

    return errors


def validate_all(samples, *, strict_extra=False):
    """批量校验，返回 (坏条数, 首 20 条错误字典)。"""
    bad = 0
    report = []
    for i, s in enumerate(samples):
        errs = validate(s, strict_extra=strict_extra)
        if errs:
            bad += 1
            if len(report) < 20:
                report.append({"index": i, "id": s.get("id") if isinstance(s, dict) else None, "errors": errs})
    return bad, report
