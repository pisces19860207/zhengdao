// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批 · 校准装配②（PhysiologyProvider）：658 状态 → 722 状态行。
//
// 纪律（判据② + 工单追加）：
//   · 722 的 statusLine() 是唯一格式源；本文件只替换"标签:未接入"里的那一项，永不碰不变量行；
//   · 一条读数要进状态行，必须同时带**出处**（source）与**时点**（asOf）——缺任一即当没有这条读数；
//   · 严禁默认值：658 的 `PhysioInputs.BASELINE` 是"温和/适中/静息/短时/平静/典型"的合成基线，
//     拿它跑出来的"疲劳=清醒"不是读数而是编的 ⇒ 本文件不构造、不引用它（源码扫描断言钉住）；
//   · 658 已合入，但逐场档位在本单允许的数据源（660/665/719/711）里没有 ⇒ 生产端 sceneProvider 留空，
//     留位话头按事实写成"未接＋前置单"，不再沿用 722 里那句已过时的"（658未合）"；
//   · turgor 不是 658 的产出（658 四状态＝体温偏移/心率档/出汗/疲劳），它属 660 的下体信息，
//     按工单追加走"命中才注入"的敏感层，不进 L0 状态行 ⇒ 状态行这一项恒挂前置单。
//   · 状态行可逐场变：每次现读 provider，不进指纹、不缓存。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.state.physiology.LightPhysiologySolver
import com.example.zhengdao.baopu.state.physiology.PhysioInputs
import com.example.zhengdao.baopu.state.readback.BodyReadback
import com.example.zhengdao.baopu.state.readback.ReadbackLines

/** 722 状态行的四项（键名与 722 里的标签一一对应）。 */
internal enum class PhysiologyKey(val label: String) {
    TURGOR("turgor"),
    SWEAT("出汗"),
    HEART("心率档"),
    FATIGUE("疲劳"),
}

/** 一条状态读数：值与出处都得齐，缺任一即视为"没有这条读数"。 */
internal data class PhysiologyReading(
    val value: String,
    val source: String,
    val asOf: String,
) {
    val usable: Boolean get() = value.isNotBlank() && source.isNotBlank() && asOf.isNotBlank()
}

/**
 * 一场的 658 入参。档位由谁给的、什么时候给的，必须和档位一起交进来——
 * 只交档位不交出处的，状态行一条都不收。
 */
internal data class PhysioScene(
    val inputs: PhysioInputs,
    val source: String,
    val asOf: String,
)

/** 状态行的装配侧。 */
internal object CalibrationPhysiology {

    /** 722 原样留位话头（658 已合入，这句在装配层按事实改写）。 */
    private const val STALE_SUFFIX = "（658未合）"

    /** 658 已合、但没有合法逐场档位来源 ⇒ 前置单挂这里；turgor 另挂一张（不是 658 的产出）。 */
    private const val SUFFIX_PENDING = "（658已合·档源与turgor各挂单）"

    /**
     * 逐场档位来源。生产端＝null（见文件头纪律）；真源接上后不需要再改本文件。
     */
    @Volatile
    var sceneProvider: (() -> PhysioScene?)? = null

    /** 现读状态行：读不到任何逐场档 ⇒ 722 的留位形状（话头按事实改写）。 */
    fun statusLine(): String {
        val have = solveReadings()
        var out = BodyReadback.statusLine()
        PhysiologyKey.values().forEach { key ->
            have[key]?.takeIf { it.usable }?.let { r ->
                out = out.replace("${key.label}:${ReadbackLines.UNWIRED}", "${key.label}:${r.value.trim()}")
            }
        }
        return ReadbackLines.clip(out.replace(STALE_SUFFIX, suffixOf(have)))
    }

    /** 治理只读用：状态行现在拿到几条真读数（不改任何取行逻辑）。 */
    fun wiredCount(): Int = PhysiologyKey.values().count { solveReadings()[it]?.usable == true }

    private fun suffixOf(have: Map<PhysiologyKey, PhysiologyReading>): String =
        if (have.size == PhysiologyKey.values().size) "" else SUFFIX_PENDING

    /**
     * 658 真解：有逐场档位**且带出处**才给读数，否则空表（宁留位不编）。
     * 档名全部取自 658 自己的 label，本文件不写任何一个档名字面量。
     */
    private fun solveReadings(): Map<PhysiologyKey, PhysiologyReading> {
        val scene = sceneProvider?.invoke() ?: return emptyMap()
        if (scene.source.isBlank() || scene.asOf.isBlank()) return emptyMap()
        val state = runCatching { LightPhysiologySolver.solve(scene.inputs) }.getOrNull() ?: return emptyMap()
        return mapOf(
            PhysiologyKey.SWEAT to PhysiologyReading(state.sweatLevel.grade.label, scene.source, scene.asOf),
            PhysiologyKey.HEART to PhysiologyReading(state.heartRateBand.grade.label, scene.source, scene.asOf),
            PhysiologyKey.FATIGUE to PhysiologyReading(state.fatigue.grade.label, scene.source, scene.asOf),
        )
    }
}
