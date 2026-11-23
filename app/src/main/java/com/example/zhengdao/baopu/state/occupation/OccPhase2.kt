// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 第二期四类字段：⑤客观居住条件 ⑥常见痕迹 ⑦职业气味来源 ⑧少数派处境。
//
// 本期（第一期）**只把字段与档名铺齐、内容留占位**（派工单「先留字段占位，可后补」）。
// 但「占位」不等于「没有」：四槽一个都不能缺，缺了判据①就红（C11 机检每条行的四槽与状态位）。
//
// 三条硬口径同时写进档名，不给后来人留解释空间：
// · ⑤是**客观条件**，不是阶层判断；它只作为 883 条件档的输入之一，同条件的人各选各的。
// · ⑥一律是**痕迹**与**体感／实测事实档**，不许写成病名——本包不做诊断（C10 禁词表守着这一条）。
// · ⑧只许写比例、目光、称谓三类**事实**，出现贬损或玩笑即红（C9）。
package com.example.zhengdao.baopu.state.occupation

/** 槽位状态：占位＝字段已就位、内容待第二期补；填了就转 SET。 */
enum class SlotStatus(val label: String) {
    PLACEHOLDER("占位（第一期未填）"),
    SET("已填"),
    ;

    companion object {
        const val COUNT = 2
    }
}

/** ⑤居住形态类别（客观条件，不是阶层）。 */
enum class Habitat(val label: String) {
    DORM("集体宿舍"),
    SHARED_RENT("合租"),
    OWN_HOME("自住"),
    TRIPS("常出差在外"),
    STATIONED("驻勤用房"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** ⑤有无独立卫浴——883 条件档的一个客观输入，不含意愿、不含清洁档。 */
enum class BathPrivacy(val label: String) {
    EN_SUITE("套内独立卫浴"),
    IN_UNIT_SHARED("同住所共用"),
    PUBLIC_FACILITY("公共设施"),
    NONE_FIXED("无固定洗浴点"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** ⑤客观居住条件槽。 */
data class HabitatSlot(
    val status: SlotStatus?,
    val habitat: Habitat?,
    val bath: BathPrivacy?,
)

/**
 * ⑥常见痕迹档：派工单第一段的六项加 ERRATA 给军警消的五项，去重后九条。
 * 全部是**看得见的痕迹**，不是病、不是诊断、不是卫生状况。
 */
enum class TraceKind(val label: String) {
    HAND_CALLUS("手茧"),
    OIL_RESIDUE("油污附着"),
    BURN_MARK("烫疤"),
    SUN_LINE("晒痕分界"),
    ROPE_MARK("绳磨（手）"),
    POWDER_RESIDUE("枪油与火药残迹"),
    GEAR_PRESSURE("装备压痕（肩／腰）"),
    FOOT_BLISTER("脚泡与老茧"),
    SMOKED_MARK("烟熏痕（面）"),
    ;

    companion object {
        const val COUNT = 9
    }
}

/** ⑥腰背**体感**档：主观感受分级，不是劳损诊断（判据：不许写成病名）。 */
enum class AcheTier(val label: String) {
    NONE("无"),
    OCCASIONAL("偶发"),
    OFTEN("经常"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** ⑥听力**事实**档：只登记测没测、测出什么，不下结论。 */
enum class HearingFact(val label: String) {
    UNTESTED("未测"),
    WITHIN_RANGE("实测在通常范围"),
    MEASURED_DROP("实测有下降"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** ⑥痕迹槽。 */
data class TraceSlot(
    val status: SlotStatus?,
    val kinds: List<TraceKind>?,
    val backAche: AcheTier?,
    val hearing: HearingFact?,
)

/** ⑦职业气味来源（接 827）：派工单点名的油烟／消毒水／金属／溶剂／汗五类。 */
enum class OdorSource(val label: String) {
    COOKING_FUME("油烟"),
    DISINFECTANT("消毒水"),
    METAL("金属"),
    SOLVENT("溶剂"),
    SWEAT("汗"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** ⑦气味来源槽。 */
data class OdorSlot(
    val status: SlotStatus?,
    val sources: List<OdorSource>?,
)

/** ⑧少数派处境的类别：同岗性别少数、年龄偏大、族别少数。只作事实分类。 */
enum class MinorityKind(val label: String) {
    MALE_IN_FEMALE_POST("同岗男性少"),
    FEMALE_IN_UNIT("同岗女性少"),
    OLDER_IN_UNIT("同岗中年龄偏大"),
    ETHNIC_MINORITY("同岗中族别少数"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** ⑧占比档：派工单只要「同岗中人少」这一句事实，所以只给两档加一个未设定。 */
enum class ShareTier(val label: String) {
    FEW("少"),
    VERY_FEW("极少"),
    UNSET("未设定"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** ⑧注视事实档：中性描述，不含褒贬。 */
enum class GazeFact(val label: String) {
    NOTED("会被多看一眼"),
    UNNOTED("无特殊注视"),
    UNSET("未设定"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** ⑧称谓档：岗位对他的称呼，属事实不是玩笑。 */
enum class AddressForm(val label: String) {
    TEACHER("称老师"),
    MASTER("称师傅"),
    DOCTOR("称医生"),
    OFFICER("称队长／警官"),
    GENERIC("通用称谓"),
    UNSET("未设定"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/** ⑧少数派处境槽。三个事实字段齐了才算「只作事实」，缺任一都由 C9 报。 */
data class MinoritySlot(
    val status: SlotStatus?,
    val kinds: List<MinorityKind>?,
    val share: ShareTier?,
    val gaze: GazeFact?,
    val address: AddressForm?,
)

/**
 * 第二期四槽合体。第一期全部走 [placeholders]：四槽齐、状态位齐、内容全空。
 * 这个形状本身就是要交付的东西——第二期补内容时不必再改结构，也不必碰判据。
 */
data class Phase2Slots(
    val habitat: HabitatSlot?,
    val traces: TraceSlot?,
    val odor: OdorSlot?,
    val minority: MinoritySlot?,
) {
    fun missingNames(): List<String> {
        val slots: List<Pair<String, Any?>> = listOf(
            "⑤居住条件" to habitat, "⑥痕迹" to traces, "⑦气味来源" to odor, "⑧少数派处境" to minority,
        )
        return slots.filter { it.second == null }.map { it.first }
    }

    /** 四槽的状态位是不是都在（缺状态位＝没法判断这块是占位还是漏填）。 */
    fun statusMissingNames(): List<String> {
        val marks: List<Pair<String, SlotStatus?>> = listOf(
            "⑤居住条件" to habitat?.status, "⑥痕迹" to traces?.status,
            "⑦气味来源" to odor?.status, "⑧少数派处境" to minority?.status,
        )
        return marks.filter { it.second == null }.map { it.first }
    }

    /** 状态填了 SET 却一格内容都没有＝假填，判红用。 */
    fun falselySet(): List<String> {
        val out = mutableListOf<String>()
        val h = habitat
        val t = traces
        val o = odor
        val m = minority
        if (h != null && h.status == SlotStatus.SET && h.habitat == null) out.add("⑤居住条件")
        if (t != null && t.status == SlotStatus.SET && t.kinds.isNullOrEmpty()) out.add("⑥痕迹")
        if (o != null && o.status == SlotStatus.SET && o.sources.isNullOrEmpty()) out.add("⑦气味来源")
        if (m != null && m.status == SlotStatus.SET && m.kinds.isNullOrEmpty()) out.add("⑧少数派处境")
        return out
    }

    companion object {
        /** 第一期的统一占位：四槽齐、状态＝占位、内容全空。 */
        fun placeholders(): Phase2Slots = Phase2Slots(
            habitat = HabitatSlot(SlotStatus.PLACEHOLDER, null, null),
            traces = TraceSlot(SlotStatus.PLACEHOLDER, null, null, null),
            odor = OdorSlot(SlotStatus.PLACEHOLDER, null),
            minority = MinoritySlot(SlotStatus.PLACEHOLDER, null, null, null, null),
        )
    }
}
