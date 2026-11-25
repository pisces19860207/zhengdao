// GZAG · 抱朴 · :policy · 第881批（2026-11-24）· 原创纯判定。
package com.example.zhengdao.baopu.state.garment

internal enum class FlyType(val label: String) {
    ZIPPER("拉链"),
    BUTTON("纽扣"),
    TIE("系带"),
    NONE("无门襟"),
    SIDE_OPEN("侧开"),
}

internal enum class InnerStyle(val label: String) {
    TRIANGLE("三角"),
    BOXER("平角"),
    NONE("无"),
}

internal enum class Tightness(val label: String) {
    LOOSE("松"),
    REGULAR("常规"),
    TIGHT("紧"),
    NOT_APPLICABLE("不适用"),
}

internal enum class ZipBehavior(val label: String) {
    SELF_LOCK("自锁"),
    SMOOTH("顺滑"),
    TEETH_GRIP("卡齿"),
    NOT_APPLICABLE("不适用"),
}

internal enum class FabricStiffness(val teethCanReachSkin: Boolean) {
    SOFT(true),
    MEDIUM(true),
    STIFF(false),
}

internal enum class OpeningStyle(val label: String) {
    UNZIP("拉拉链"),
    PULL_EDGE("只拉边"),
    WAISTBAND_REACH("从腰头掏"),
    WAISTBAND_DOWN("拉下裤腰"),
}

internal enum class Wornness(val level: Int, val label: String) {
    NEW(0, "新"),
    SOFT(1, "旧软"),
    HOLED(2, "破洞"),
    SAGGING(3, "松垮失效"),
}

internal enum class HoleSite {
    CROTCH,
    SIDE_SEAM,
    WAISTBAND,
}

internal enum class WearDriver {
    OCCUPATION,
    HABIT,
    REPLACEMENT_CYCLE,
}

internal sealed interface InnerWear {
    data object Absent : InnerWear

    data class Present(
        val level: Wornness,
        val hole: HoleSite? = null,
        val drivers: Set<WearDriver> = emptySet(),
    ) : InnerWear {
        val sagging: Boolean get() = level == Wornness.SAGGING
    }
}

internal data class InnerLayer(
    val exists: Boolean,
    val style: InnerStyle,
    val frontOpening: Boolean,
    val tightness: Tightness,
    val wear: InnerWear,
)

internal data class GarmentState(
    val flyType: FlyType,
    val innerLayer: InnerLayer,
    val zipBehavior: ZipBehavior,
    val fabricStiffness: FabricStiffness,
    val openingStyle: OpeningStyle,
) {
    fun snapshotFields(): Map<String, String> = linkedMapOf(
        GarmentFields.LAYER to "interface",
        GarmentFields.FLY_TYPE to flyType.name,
        GarmentFields.INNER_EXISTS to innerLayer.exists.toString(),
        GarmentFields.INNER_STYLE to innerLayer.style.name,
        GarmentFields.INNER_FRONT_OPENING to innerLayer.frontOpening.toString(),
        GarmentFields.INNER_TIGHTNESS to innerLayer.tightness.name,
        GarmentFields.INNER_WORNNESS to wearName(innerLayer.wear),
        GarmentFields.INNER_HOLE to holeName(innerLayer.wear),
        GarmentFields.INNER_SAGGING to saggingName(innerLayer.wear),
        GarmentFields.INNER_WEAR_DRIVERS to driverNames(innerLayer.wear),
        GarmentFields.ZIP_BEHAVIOR to zipBehavior.name,
        GarmentFields.FABRIC_STIFFNESS to fabricStiffness.name,
        GarmentFields.OPENING_STYLE to openingStyle.name,
    )

    private fun wearName(wear: InnerWear): String = when (wear) {
        InnerWear.Absent -> "absent"
        is InnerWear.Present -> wear.level.name
    }

    private fun holeName(wear: InnerWear): String = when (wear) {
        InnerWear.Absent -> "none"
        is InnerWear.Present -> wear.hole?.name ?: "none"
    }

    private fun saggingName(wear: InnerWear): String = when (wear) {
        InnerWear.Absent -> "false"
        is InnerWear.Present -> wear.sagging.toString()
    }

    private fun driverNames(wear: InnerWear): String = when (wear) {
        InnerWear.Absent -> "none"
        is InnerWear.Present -> wear.drivers.sortedBy { it.name }.joinToString(",")
            .ifEmpty { "unspecified" }
    }
}

internal data class InnerLayerDraft(
    val exists: Boolean? = null,
    val style: InnerStyle? = null,
    val frontOpening: Boolean? = null,
    val tightness: Tightness? = null,
    val wear: InnerWear? = null,
)

internal data class GarmentDraft(
    val flyType: FlyType? = null,
    val innerLayer: InnerLayerDraft? = null,
    val zipBehavior: ZipBehavior? = null,
    val fabricStiffness: FabricStiffness? = null,
    val openingStyle: OpeningStyle? = null,
)

internal enum class ExposurePath {
    COVERED,
    NO_UNDERWEAR,
    FRONT_OPENING,
    FORESKIN_EXPOSED,
    ERECTION_SHIFT,
    LOCAL_HOLE,
}

internal enum class EntanglementPath {
    SKIN,
    UNDERWEAR_EDGE,
}

internal enum class ZipPhase {
    CLOSING,
    OPENING,
    IDLE,
}

internal enum class ReleaseMethod {
    NONE,
    BACK_SLIDER,
    FORCE_PULL,
    CUT,
    TEAR,
}

internal enum class Consequence(val withinRare: Boolean) {
    NONE(true),
    REDNESS(true),
    BRIEF_PAIN(true),
    MARK(true),
    TEARING(false),
    BLEEDING(false),
    INFECTION(false),
    MEDICAL_CARE(false),
}

internal data class PinchScene(
    val phase: ZipPhase,
    val exposure: ExposurePath,
    val entanglement: EntanglementPath = EntanglementPath.SKIN,
    val release: ReleaseMethod = ReleaseMethod.NONE,
    val consequence: Consequence = Consequence.NONE,
)

internal enum class ActionMode {
    ROUTINE,
    NON_ROUTINE,
}

internal enum class NarrativeDomain {
    REALISTIC,
    COMEDY,
}

internal enum class NonRoutineTrigger {
    RUSHED,
    INTOXICATED,
    CLOTH_DEFORMED,
    STIFF_NEW_EDGE,
    CRAMPED,
    INJURED_OR_POST_SURGERY,
    COLD_HANDS,
    FIRST_TIME,
}

internal data class GarmentAction(
    val mode: ActionMode = ActionMode.ROUTINE,
    val domain: NarrativeDomain = NarrativeDomain.REALISTIC,
    val trigger: NonRoutineTrigger? = null,
    val pinch: PinchScene? = null,
    val text: String = "",
)

internal enum class GarmentIssueSeverity {
    ERROR,
    WARNING,
}

internal data class GarmentIssue(
    val code: String,
    val message: String,
    val severity: GarmentIssueSeverity = GarmentIssueSeverity.ERROR,
)

internal data class ResolvedGarment(
    val state: GarmentState?,
    val issues: List<GarmentIssue>,
) {
    val accepted: Boolean get() = state != null && issues.none { it.severity == GarmentIssueSeverity.ERROR }
}

internal object GarmentFields {
    const val LAYER = "garment.layer"
    const val FLY_TYPE = "garment.flyType"
    const val INNER_EXISTS = "garment.innerLayer.exists"
    const val INNER_STYLE = "garment.innerLayer.style"
    const val INNER_FRONT_OPENING = "garment.innerLayer.frontOpening"
    const val INNER_TIGHTNESS = "garment.innerLayer.tightness"
    const val INNER_WORNNESS = "garment.innerLayer.wornness"
    const val INNER_HOLE = "garment.innerLayer.hole"
    const val INNER_SAGGING = "garment.innerLayer.sagging"
    const val INNER_WEAR_DRIVERS = "garment.innerLayer.wearDrivers"
    const val ZIP_BEHAVIOR = "garment.zipBehavior"
    const val FABRIC_STIFFNESS = "garment.fabricStiffness"
    const val OPENING_STYLE = "garment.openingStyle"
}
