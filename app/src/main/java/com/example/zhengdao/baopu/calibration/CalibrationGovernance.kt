// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批 · 校准装配④（开关与治理，按工单追加三执行）：
//   · **形神不做用户开关**——设置页里没有"形神开关/敏感注入开关"，全新安装零设置即可读到；
//   · 唯一能关掉注入的是**治理级应急关闭**：一次性、需确认、留痕（沿 621/457 的人批形态，不是日常开关）；
//   · 治理中心可只读看到"本场注入了什么"：**只给条数与话题类别，不给任何具体数值**（工单追加·日志脱敏）。
//
// 边界：本文件不调模型、不读盘（留痕经装配层注入的 sink）、不进任何闸、不改判定。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.state.readback.BodyReadback
import org.json.JSONObject

/** 一次注入的脱敏回执：条数 + 命中的话题类别 + 三行是否在场。 */
internal data class InjectionReceipt(
    val entryCount: Int,
    val categories: List<String>,
    val hasThreeLines: Boolean,
) {
    fun describe(): String = buildString {
        append(if (hasThreeLines) "三行已注" else "三行缺位")
        append(" · 已注入 $entryCount 条")
        if (categories.isNotEmpty()) append("（命中类别：${categories.joinToString("、")}）")
    }
}

/** 治理侧：应急关闭（一次性＋需确认＋留痕）与只读快照。 */
internal object CalibrationGovernance {

    /** 唯一有权按应急关闭的角色——不是用户日常开关，也不给模型这条路。 */
    const val WHO = "治理"
    const val CONFIRM = "确认应急关闭"

    private const val NOTE_PREFIX = "[校准·"

    @Volatile
    private var halted = false

    /** 本场最近一次注入回执（只读；未命中时不更新 ⇒ 不进日志）。 */
    @Volatile
    var lastReceipt: InjectionReceipt? = null
        private set

    /** 装配层写回执的唯一入口（值只能是条数与类别，正文与数值进不来）。 */
    fun setReceipt(receipt: InjectionReceipt) {
        lastReceipt = receipt
    }

    /** 留痕出口：由装配层注入（写工作区日志），未注入时只在内存留回执。 */
    @Volatile
    var traceSink: (String) -> Unit = {}

    /**
     * 工作区开关位（由装配层注入读取口，文件态、重启仍生效）。
     * 未注入 ⇒ 视为未关 ⇒ 与"没有本单"一致；注入但文件不在 ⇒ 同样未关。
     */
    @Volatile
    var fileHaltProvider: (() -> Boolean)? = null

    /** 注入是否生效：默认开（全新安装零设置），内存态或文件态应急关闭期间关。 */
    fun on(): Boolean = !halted && !haltedByFile()

    /** 文件位读取失败按"未关"处理——开关坏掉不许把功能一起打死。 */
    private fun haltedByFile(): Boolean = runCatching { fileHaltProvider?.invoke() == true }.getOrDefault(false)

    fun isHalted(): Boolean = halted || haltedByFile()

    /** 开关位密封校验：角色与确认词都对上才算治理按下去的（不是谁都能改的普通文件）。 */
    fun sealOk(json: String): Boolean = runCatching {
        val o = JSONObject(json)
        o.optString(F_WHO) == WHO && o.optString(F_CONFIRM) == CONFIRM
    }.getOrDefault(false)

    /** 开关位生效/解除时留痕（装配层只在状态真变了时调）。 */
    fun traceHalt(on: Boolean) = trace("$FILE_VERB＝${if (on) "生效" else "解除"}")

    /** 一次性应急关闭：角色与确认词都对上才生效，并且无论成败都留痕。 */
    fun halt(who: String, confirm: String): Boolean =
        switch(who = who, confirm = confirm, target = true, verb = "应急关闭")

    /** 解除应急关闭：同样要角色＋确认，也留痕。 */
    fun release(who: String, confirm: String): Boolean =
        switch(who = who, confirm = confirm, target = false, verb = "解除应急关闭")

    private fun switch(who: String, confirm: String, target: Boolean, verb: String): Boolean {
        val ok = who == WHO && confirm == CONFIRM
        if (ok) {
            halted = target
            CalibrationAssembly.rebuild()
        }
        val line = if (ok) "$verb＝生效（$who）" else "$verb＝被拒（角色或确认词不符）"
        trace(line)
        return ok
    }

    private fun trace(line: String) = runCatching { traceSink(line) }

    /** 从最终 notes 抽脱敏回执：条目 id 映射到话题类别，正文与数值一律不带。 */
    fun receiptOf(notes: List<String>): InjectionReceipt {
        val ids = notes.mapNotNull { line ->
            val head = line.substringAfter(NOTE_PREFIX, "").substringBefore(']')
            head.takeIf { line.startsWith(NOTE_PREFIX) && it.isNotBlank() }
        }
        val cats = ids.map { categoryOf(it) }.distinct().sorted()
        return InjectionReceipt(
            entryCount = ids.size,
            categories = cats,
            hasThreeLines = notes.any { it.startsWith(BodyReadback.INVARIANT_TAG) },
        )
    }

    /** 话题类别：只到"哪一类"，不到"哪一值"。 */
    private fun categoryOf(id: String): String = when {
        id.startsWith("SENS-ZONE") -> "区态"
        id.startsWith("SENS-RANGE") || id.startsWith("SENS-GIRTH") -> "下体区间"
        id.startsWith("SENS-SHAPE") -> "下体形态"
        id.startsWith("SENS-PREPUCE") -> "包皮"
        id.startsWith("SENS-VESSEL") -> "青筋"
        id.startsWith("SENS-") -> "形神"
        else -> "索引事实"
    }

    /** 治理中心只读一行：装配状态 + 最近回执 + 应急关闭，全是计数与原因，没有身体数值。 */
    fun snapshot(): String {
        val receipt = lastReceipt?.describe() ?: "本场未注入"
        val mode = if (isHalted()) "应急关闭中" else "开（默认）"
        return "校准装配：$mode · $receipt · ${CalibrationAssembly.statusLineSummary()}"
    }

    /** 测试/重启用：清内存态（不落盘、不改设定文件）。 */
    fun resetForTest() {
        halted = false
        lastReceipt = null
        traceSink = {}
        fileHaltProvider = null
    }

    private const val F_WHO = "who"
    private const val F_CONFIRM = "confirm"
    private const val FILE_VERB = "应急关闭（工作区开关位）"
}
