// GZAG · 抱朴 · :policy · 第881批（2026-11-24）· 原创纯判定。
package com.example.zhengdao.baopu.state.garment

internal object GarmentDefaults {
    fun draft(withUnderwear: Boolean): GarmentDraft = GarmentDraft(
        flyType = FlyType.ZIPPER,
        innerLayer = InnerLayerDraft(
            exists = withUnderwear,
            style = if (withUnderwear) InnerStyle.BOXER else InnerStyle.NONE,
            frontOpening = false,
            tightness = if (withUnderwear) Tightness.REGULAR else Tightness.NOT_APPLICABLE,
            wear = if (withUnderwear) {
                InnerWear.Present(Wornness.SOFT, drivers = setOf(WearDriver.HABIT))
            } else {
                InnerWear.Absent
            },
        ),
        zipBehavior = ZipBehavior.SELF_LOCK,
        fabricStiffness = FabricStiffness.MEDIUM,
        openingStyle = OpeningStyle.UNZIP,
    )

    fun withUnderwear(): GarmentState = requireNotNull(GarmentChecks.resolve(draft(true)).state)

    fun withoutUnderwear(): GarmentState = requireNotNull(GarmentChecks.resolve(draft(false)).state)

    fun default(): GarmentState = withUnderwear()
}

internal data class PinchConditions(
    val exposed: Boolean,
    val closing: Boolean,
    val retained: Boolean,
    val edgeAvailable: Boolean,
) {
    val met: Boolean get() = exposed && closing && retained && edgeAvailable
}

internal data class PinchAssessment(
    val conditions: PinchConditions,
    val issues: List<GarmentIssue>,
) {
    val possible: Boolean get() = conditions.met && issues.none { it.severity == GarmentIssueSeverity.ERROR }
}

internal enum class RiskSource {
    NONE,
    GARMENT_FORM,
}

internal object GarmentChecks {
    const val MISSING_FIELD = "garment.missingField"
    const val INNER_SHAPE = "garment.innerLayer.shape"
    const val WEAR_HOLE = "garment.wear.hole"
    const val WEAR_DRIVER = "garment.wear.driver"
    const val ZIP_SHAPE = "garment.zip.shape"
    const val OPENING_SHAPE = "garment.opening.shape"
    const val PINCH_REQUIRES_ZIPPER = "garment.pinch.requiresZipper"
    const val PINCH_REQUIRES_CLOSING = "garment.pinch.requiresClosing"
    const val PINCH_REQUIRES_EXPOSURE = "garment.pinch.requiresExposure"
    const val PINCH_REQUIRES_RETENTION = "garment.pinch.requiresRetention"
    const val PINCH_EDGE_REQUIRES_SAGGING = "garment.pinch.edgeRequiresSagging"
    const val RELEASE_METHOD = "garment.pinch.releaseMethod"
    const val CONSEQUENCE_CEILING = "garment.pinch.consequenceCeiling"
    const val ROUTINE_PINCH = "garment.routine.pinch"
    const val TRIGGER_REQUIRED = "garment.nonRoutine.triggerRequired"
    const val PINCH_SCENE_REQUIRED = "garment.pinch.sceneRequired"
    const val RELEASE_TEXT_REQUIRED = "garment.text.releaseRequired"
    const val GARMENT_RISK_SOURCE = "garment.riskSource"

    fun resolve(draft: GarmentDraft): ResolvedGarment {
        val issues = buildList {
            addAll(missingIssues(draft))
            addAll(innerIssues(draft.innerLayer))
            addAll(zipIssues(draft))
            addAll(wearIssues(draft.innerLayer?.wear))
        }
        if (issues.any { it.severity == GarmentIssueSeverity.ERROR }) {
            return ResolvedGarment(null, issues)
        }
        val inner = requireNotNull(draft.innerLayer)
        return ResolvedGarment(
            GarmentState(
                flyType = requireNotNull(draft.flyType),
                innerLayer = InnerLayer(
                    exists = requireNotNull(inner.exists),
                    style = requireNotNull(inner.style),
                    frontOpening = requireNotNull(inner.frontOpening),
                    tightness = requireNotNull(inner.tightness),
                    wear = requireNotNull(inner.wear),
                ),
                zipBehavior = requireNotNull(draft.zipBehavior),
                fabricStiffness = requireNotNull(draft.fabricStiffness),
                openingStyle = requireNotNull(draft.openingStyle),
            ),
            issues,
        )
    }

    fun assessPinch(state: GarmentState, scene: PinchScene): PinchAssessment {
        val conditions = PinchConditions(
            exposed = exposureAvailable(state, scene.exposure),
            closing = scene.phase == ZipPhase.CLOSING,
            retained = retainsSkin(state),
            edgeAvailable = scene.entanglement == EntanglementPath.SKIN || sagging(state),
        )
        return PinchAssessment(conditions, pinchIssues(state, scene, conditions))
    }

    fun auditAction(
        state: GarmentState,
        action: GarmentAction,
        riskSource: RiskSource = RiskSource.NONE,
    ): List<GarmentIssue> = buildList {
        val claimsPinch = action.pinch != null || GarmentTextChecks.claimsPinch(action.text)
        addAll(pinchClaimIssues(action, claimsPinch))
        releaseTextIssue(action, claimsPinch)?.let { add(it) }
        action.pinch?.let { addAll(pinchIssues(state, it, conditionsOf(state, it))) }
        if (riskSource == RiskSource.GARMENT_FORM) {
            add(GarmentIssue(GARMENT_RISK_SOURCE, "衣物形态是习惯差异，不是风险源"))
        }
        addAll(GarmentTextChecks.audit(action.text, action.mode, action.domain))
    }

    private fun pinchClaimIssues(
        action: GarmentAction,
        claimsPinch: Boolean,
    ): List<GarmentIssue> = buildList {
        if (action.mode == ActionMode.ROUTINE && action.pinch != null) {
            add(GarmentIssue(ROUTINE_PINCH, "日常熟练动作默认无伤，不许写成夹到"))
        }
        if (action.mode == ActionMode.NON_ROUTINE && claimsPinch && action.trigger == null) {
            add(GarmentIssue(TRIGGER_REQUIRED, "非常规动作写夹到必须给触发条件"))
        }
        if (action.mode == ActionMode.NON_ROUTINE && action.pinch == null && claimsPinch) {
            add(GarmentIssue(PINCH_SCENE_REQUIRED, "文本声称夹到时必须同时给 PinchScene 才能核三条件"))
        }
    }

    private fun releaseTextIssue(
        action: GarmentAction,
        claimsPinch: Boolean,
    ): GarmentIssue? = if (
        claimsPinch && action.text.isNotBlank() && !GarmentTextChecks.claimsBackSlider(action.text)
    ) {
        GarmentIssue(RELEASE_TEXT_REQUIRED, "文本描写夹到必须正面写出回拉滑块")
    } else {
        null
    }

    private fun conditionsOf(state: GarmentState, scene: PinchScene): PinchConditions = PinchConditions(
        exposed = exposureAvailable(state, scene.exposure),
        closing = scene.phase == ZipPhase.CLOSING,
        retained = retainsSkin(state),
        edgeAvailable = scene.entanglement == EntanglementPath.SKIN || sagging(state),
    )

    private fun pinchIssues(
        state: GarmentState,
        scene: PinchScene,
        conditions: PinchConditions,
    ): List<GarmentIssue> = buildList {
        if (state.flyType != FlyType.ZIPPER) {
            add(GarmentIssue(PINCH_REQUIRES_ZIPPER, "只有拉链门襟存在齿列卷入路径"))
        }
        if (!conditions.closing) {
            add(GarmentIssue(PINCH_REQUIRES_CLOSING, "卷入只发生在上拉闭合，不是拉开"))
        }
        if (!conditions.exposed) {
            add(GarmentIssue(PINCH_REQUIRES_EXPOSURE, "包皮或皮肤必须实际暴露在齿列路径上"))
        }
        if (!conditions.retained) {
            add(GarmentIssue(PINCH_REQUIRES_RETENTION, "必须自锁或卡齿确实咬住皮"))
        }
        if (!conditions.edgeAvailable) {
            add(GarmentIssue(PINCH_EDGE_REQUIRES_SAGGING, "内裤前缘卷入需要松垮失效，且仍要暴露＋闭合"))
        }
        if (scene.release != ReleaseMethod.BACK_SLIDER) {
            add(GarmentIssue(RELEASE_METHOD, "解除只许回拉滑块，不许硬拽、剪开或撕裂"))
        }
        if (!scene.consequence.withinRare) {
            add(GarmentIssue(CONSEQUENCE_CEILING, "后果上限 rare，只到红、痛一下或留痕"))
        }
    }

    private fun exposureAvailable(state: GarmentState, path: ExposurePath): Boolean = when (path) {
        ExposurePath.COVERED -> false
        ExposurePath.NO_UNDERWEAR -> !state.innerLayer.exists
        ExposurePath.FRONT_OPENING -> state.innerLayer.exists && state.innerLayer.frontOpening
        ExposurePath.FORESKIN_EXPOSED, ExposurePath.ERECTION_SHIFT -> true
        ExposurePath.LOCAL_HOLE -> {
            val wear = state.innerLayer.wear
            state.innerLayer.exists && wear is InnerWear.Present && wear.hole == HoleSite.CROTCH
        }
    }

    private fun retainsSkin(state: GarmentState): Boolean = when (state.zipBehavior) {
        ZipBehavior.SELF_LOCK -> true
        ZipBehavior.TEETH_GRIP -> state.fabricStiffness.teethCanReachSkin
        ZipBehavior.SMOOTH, ZipBehavior.NOT_APPLICABLE -> false
    }

    private fun sagging(state: GarmentState): Boolean {
        val wear = state.innerLayer.wear
        return state.innerLayer.exists && wear is InnerWear.Present && wear.sagging
    }

    private fun missingIssues(draft: GarmentDraft): List<GarmentIssue> = buildList {
        if (draft.flyType == null) add(missing("flyType"))
        if (draft.innerLayer == null) add(missing("innerLayer"))
        if (draft.zipBehavior == null) add(missing("zipBehavior"))
        if (draft.fabricStiffness == null) add(missing("fabricStiffness"))
        if (draft.openingStyle == null) add(missing("openingStyle"))
        draft.innerLayer?.let { inner ->
            if (inner.exists == null) add(missing("innerLayer.exists"))
            if (inner.style == null) add(missing("innerLayer.style"))
            if (inner.frontOpening == null) add(missing("innerLayer.frontOpening"))
            if (inner.tightness == null) add(missing("innerLayer.tightness"))
            if (inner.wear == null) add(missing("innerLayer.wornness"))
        }
    }

    private fun innerIssues(inner: InnerLayerDraft?): List<GarmentIssue> {
        if (inner == null || inner.exists == null) return emptyList()
        return if (inner.exists) presentInnerIssues(inner) else absentInnerIssues(inner)
    }

    private fun presentInnerIssues(inner: InnerLayerDraft): List<GarmentIssue> = buildList {
        if (inner.style == null || inner.style == InnerStyle.NONE) {
            add(GarmentIssue(INNER_SHAPE, "有内裤必须给三角或平角"))
        }
        if (inner.tightness == null || inner.tightness == Tightness.NOT_APPLICABLE) {
            add(GarmentIssue(INNER_SHAPE, "有内裤必须给紧度"))
        }
        if (inner.wear != null && inner.wear !is InnerWear.Present) {
            add(GarmentIssue(INNER_SHAPE, "有内裤必须给 wornness 0–3"))
        }
    }

    private fun absentInnerIssues(inner: InnerLayerDraft): List<GarmentIssue> = buildList {
        if (inner.style != null && inner.style != InnerStyle.NONE) {
            add(GarmentIssue(INNER_SHAPE, "无内裤时 style 必须为无"))
        }
        if (inner.frontOpening == true) {
            add(GarmentIssue(INNER_SHAPE, "无内裤不存在前开口"))
        }
        if (inner.tightness != null && inner.tightness != Tightness.NOT_APPLICABLE) {
            add(GarmentIssue(INNER_SHAPE, "无内裤时紧度不适用"))
        }
        if (inner.wear != null && inner.wear !is InnerWear.Absent) {
            add(GarmentIssue(INNER_SHAPE, "无内裤时 wornness 必须显式缺席"))
        }
    }

    private fun zipIssues(draft: GarmentDraft): List<GarmentIssue> = buildList {
        val fly = draft.flyType ?: return emptyList()
        val zip = draft.zipBehavior
        if (fly == FlyType.ZIPPER && zip == ZipBehavior.NOT_APPLICABLE) {
            add(GarmentIssue(ZIP_SHAPE, "拉链门襟必须给自锁、顺滑或卡齿"))
        }
        if (fly != FlyType.ZIPPER && zip != null && zip != ZipBehavior.NOT_APPLICABLE) {
            add(GarmentIssue(ZIP_SHAPE, "非拉链门襟的 zipBehavior 必须为不适用"))
        }
        if (fly != FlyType.ZIPPER && draft.openingStyle == OpeningStyle.UNZIP) {
            add(GarmentIssue(OPENING_SHAPE, "拉拉链只适用于拉链门襟"))
        }
    }

    private fun wearIssues(wear: InnerWear?): List<GarmentIssue> {
        if (wear !is InnerWear.Present) return emptyList()
        return buildList {
            val hole = wear.hole
            if (wear.level == Wornness.HOLED && hole == null) {
                add(GarmentIssue(WEAR_HOLE, "破洞档必须点明局部破洞位置"))
            }
            if (wear.level != Wornness.HOLED && wear.level != Wornness.SAGGING && hole != null) {
                add(GarmentIssue(WEAR_HOLE, "新或旧软档不能同时带破洞"))
            }
            if (wear.drivers.isEmpty()) {
                add(GarmentIssue(WEAR_DRIVER, "磨损档未给职业、习惯或更换周期驱动", GarmentIssueSeverity.WARNING))
            }
        }
    }

    private fun missing(field: String): GarmentIssue =
        GarmentIssue(MISSING_FIELD, "缺少必填字段：$field")
}
