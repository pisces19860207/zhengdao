// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 七条机制的唯一方向表：每条给"输入档 → 输出档方向"。
// 表是唯一真相，逐条可查、分段单调；求解器只查表不另算，禁连续量。
package com.example.zhengdao.baopu.state.thermo

/** 机制 id 码册：七条齐全，测试逐码点名（缺一条即红）。 */
internal object MechanismIds {
    const val M1_REFLEX = "M1"
    const val M2_COLD = "M2"
    const val M3_HEAT = "M3"
    const val M4_HUMID = "M4"
    const val M5_DRY_WIND = "M5"
    const val M6_MEDIUM = "M6"
    const val M7_TURGOR = "M7"
    val ALL = listOf(M1_REFLEX, M2_COLD, M3_HEAT, M4_HUMID, M5_DRY_WIND, M6_MEDIUM, M7_TURGOR)
}

/**
 * 七条方向表。每条的输入条件由输入档判定，输出是 Effect 列表（只给升/降/平）。
 * 方向口径全部来自派工单 730 §一，不新增自造规则。
 */
internal object MechanismTable {

    /** ① 提睾/收缩反射：冷/触碰/惊吓 ⇒ 短暂上提＋皱缩。 */
    private val m1 = MechanismSpec(MechanismIds.M1_REFLEX, "提睾收缩反射") { input ->
        if (input.trigger == CremasterTrigger.NONE) emptyList()
        else listOf(
            Effect(OutDim.CREMASTERIC, Direction.UP),
            Effect(OutDim.RUGOSITY, Direction.UP),
        )
    }

    /** ② 冷：体积↓ 垂度↓ 皱缩↑。 */
    private val m2 = MechanismSpec(MechanismIds.M2_COLD, "冷") { input ->
        if (input.thermal == ThermalBand.COOL) listOf(
            Effect(OutDim.VOLUME, Direction.DOWN),
            Effect(OutDim.HANG, Direction.DOWN),
            Effect(OutDim.RUGOSITY, Direction.UP),
        ) else emptyList()
    }

    /** ③ 热：体积↑ 垂度↑ 皱缩↓ 湿膜↑。 */
    private val m3 = MechanismSpec(MechanismIds.M3_HEAT, "热") { input ->
        if (input.thermal == ThermalBand.HOT) listOf(
            Effect(OutDim.VOLUME, Direction.UP),
            Effect(OutDim.HANG, Direction.UP),
            Effect(OutDim.RUGOSITY, Direction.DOWN),
            Effect(OutDim.WET_FILM, Direction.UP),
        ) else emptyList()
    }

    /** ④ 湿：蒸发慢 ⇒ 湿膜持续↑、贴合↑。 */
    private val m4 = MechanismSpec(MechanismIds.M4_HUMID, "湿") { input ->
        if (input.humidity.level >= HumidityBand.HUMID.level) listOf(
            Effect(OutDim.WET_FILM, Direction.UP),
            Effect(OutDim.CLING, Direction.UP),
        ) else emptyList()
    }

    /** ⑤ 干/风：湿膜消退快、起皱/摩擦感↑。 */
    private val m5 = MechanismSpec(MechanismIds.M5_DRY_WIND, "干风") { input ->
        val dryOrWind = input.humidity == HumidityBand.DRY || input.wind.level >= WindBand.WINDY.level
        if (dryOrWind) listOf(
            Effect(OutDim.WET_FILM, Direction.DOWN),
            Effect(OutDim.RUGOSITY, Direction.UP),
        ) else emptyList()
    }

    /** ⑥ 接触介质：水(浮力+冷) / 沙(束缚+摩擦) / 湿衣(贴合+摩擦)。 */
    private val m6 = MechanismSpec(MechanismIds.M6_MEDIUM, "接触介质") { input ->
        when (input.medium) {
            Medium.WATER -> listOf(
                Effect(OutDim.HANG, Direction.DOWN), Effect(OutDim.RUGOSITY, Direction.UP)
            )
            Medium.SAND -> listOf(
                Effect(OutDim.HANG, Direction.DOWN), Effect(OutDim.RUGOSITY, Direction.UP)
            )
            Medium.WET_CLOTH -> listOf(
                Effect(OutDim.CLING, Direction.UP), Effect(OutDim.RUGOSITY, Direction.UP)
            )
            Medium.NONE -> emptyList()
        }
    }

    /** ⑦ turgor 联动：方向只引用 658 给的方向，本单不自定升降含义。 */
    private val m7 = MechanismSpec(MechanismIds.M7_TURGOR, "turgor联动") { input ->
        if (input.turgor == Direction.FLAT) emptyList()
        else listOf(
            Effect(OutDim.VOLUME, input.turgor),
            Effect(OutDim.HANG, input.turgor),
        )
    }

    val ALL: List<MechanismSpec> = listOf(m1, m2, m3, m4, m5, m6, m7)

    fun byId(id: String): MechanismSpec = ALL.first { it.id == id }

    /** 给定输入，取七条各自的方向表（含空表＝该机制当前未触发）。 */
    fun effectsFor(input: ThermoInput): Map<String, List<Effect>> = ALL.associate { it.id to it.direction(input) }
}
