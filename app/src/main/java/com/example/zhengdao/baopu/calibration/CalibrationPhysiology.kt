// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批 · 校准装配②（PhysiologyProvider）：658 四状态 → 722 状态行。
//
// 纪律（判据② + 工单追加）：
//   · 658 未合入 ⇒ provider 为 null ⇒ 状态行**逐字节等于 722 的 statusLine()**（四项"未接入＋658"）；
//   · 有读数但缺 source/asOf ⇒ 当没有这条读数用——**严禁填默认值**（"疲劳=清醒"就是从这里来的）；
//   · 只替换状态行里对应的那一项，永不碰不变量行（两行不许互相污染）；
//   · 状态行可逐场变：本对象每次都现读 provider，不缓存。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.state.readback.BodyReadback
import com.example.zhengdao.baopu.state.readback.ReadbackLines

/** 658 的四个状态位（键名与 722 状态行里的标签一一对应）。 */
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

/** 状态行的装配侧：以 722 的 statusLine() 为唯一格式源，只替换有出处的项。 */
internal object CalibrationPhysiology {

    /** 658 未合入 ⇒ 生产端就是 null；测试可注入假 provider 验证"缺源不替换"。 */
    @Volatile
    var provider: (() -> Map<PhysiologyKey, PhysiologyReading>)? = null

    /** 现读状态行。全部缺位 ⇒ 与 722 逐字节一致（判据②的硬形状）。 */
    fun statusLine(): String {
        val base = BodyReadback.statusLine()
        val readings = provider?.invoke() ?: return base
        val wired = PhysiologyKey.values().mapNotNull { key ->
            readings[key]?.takeIf { it.usable }?.let { key to it.value.trim() }
        }
        if (wired.isEmpty()) return base
        return replace(base, wired)
    }

    /** 逐项替换 `标签:未接入` → `标签:读数`；四项都有出处时去掉"658未合"的旧话头。 */
    private fun replace(base: String, wired: List<Pair<PhysiologyKey, String>>): String {
        var out = base
        wired.forEach { (key, value) ->
            val slot = "${key.label}:${ReadbackLines.UNWIRED}"
            out = out.replace(slot, "${key.label}:$value")
        }
        val cleared = PhysiologyKey.values().none { "${it.label}:${ReadbackLines.UNWIRED}" in out }
        if (cleared) out = out.replace(SUFFIX_658, "")
        return ReadbackLines.clip(out)
    }

    /** 状态行当前是否仍全为留位（治理只读用；不改任何取行逻辑）。 */
    fun allUnwired(): Boolean {
        val readings = provider?.invoke() ?: return true
        return PhysiologyKey.values().any { readings[it]?.usable != true }
    }

    private const val SUFFIX_658 = "（658未合）"
}
