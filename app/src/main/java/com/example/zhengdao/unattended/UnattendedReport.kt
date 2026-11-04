// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · **离场报告**（388 派工单：离场报告；判据：被中断要补报）。
// 纯函数：从事件账＋收场时刻算出整篇报告，JVM 单测逐节钉。落盘与发通知归装配层。
//
// 四条纪律：
// 1. **分级只有 A/B 两级**（388 派工单口径）：A 级＝要她处理/知道的事（红线停手、降级、
//    环境暂停、圈外入队），B 级＝例行进展（每小时汇总）。不发明第三级。
// 2. **中断必须补报**：事件账里有 INTERRUPT 就逐条列（什么时候发现的、断了多久）；
//    没有也要写一行"值守期间无中断"——沉默不是"没有事"，是"查不了"。
// 3. **空手不许交白卷**：一件 A 级都没有、一条汇总也没有的报告是合法的（值守期间真没出事），
//    但每一节都要**显式写"无"**——"没写"与"没有"必须分得清。
// 4. **时刻只算时长**：报告里的人话部分只出现时长（x 小时 y 分钟）与条数，
//    绝对时刻交给事件账（报告被转发出去时，epoch 不泄漏她的作息）。
package com.example.zhengdao.unattended

/** 离场报告的输入（事件账由调用方读好交进来，本层不摸盘）。 */
internal data class UnattendedReportInput(

    /** 开值守时刻。 */
    val armedAtMs: Long,

    /** 收场时刻；null ＝ 还在值守（期中报告）。 */
    val closedAtMs: Long? = null,

    /** 值守窗口内的事件（按时刻升序；乱序由调用方负责排，本层不再排——账的序就是账）。 */
    val events: List<UnattendedEvent> = emptyList(),

    /** 收场时仍未授权的事（弹卡闸队列快照；期中报告也带上当前积压）。 */
    val queueLines: List<String> = emptyList(),

    /**
     * 收场理由的人话（441 加法；null ＝ 不写——388 老口径保持逐字节不变）。
     * 隐式出场的留痕（"因用户回话而解除"）从这进报告头：报告是回场第一眼，
     * "为什么收了值守"必须在头一行，不许翻账才查得到。
     */
    val closedReasonCn: String? = null,
)

/** 报告成品（[lines] 一行一项；装配层原样落盘／上屏，不再二次加工）。 */
internal data class UnattendedReportDoc(val lines: List<String>) {

    /** 整篇文本（\n 连接；落盘用）。 */
    fun text(): String = lines.joinToString("\n") + "\n"
}

/** 离场报告的唯一算口（纯函数）。 */
internal object UnattendedReport {

    /** 整篇报告：头／A 级／入队／B 级汇总／中断补报／未做项，六节顺序固定。 */
    fun build(input: UnattendedReportInput): UnattendedReportDoc = UnattendedReportDoc(
        listOf(
            header(input),
            *gradeA(input.events).toTypedArray(),
            *queueSection(input.events).toTypedArray(),
            *summarySection(input.events).toTypedArray(),
            *interruptSection(input.events).toTypedArray(),
            *undoneSection(input).toTypedArray(),
        ),
    )

    /** 头一节：值守窗口与时长（"进行中"也是合法的收场态——期中报告）。 */
    private fun header(input: UnattendedReportInput): String {
        val end = input.closedAtMs
        val span = (end ?: 0L) - input.armedAtMs
        val window = if (end == null) {
            "值守中（自开值守起 ${formatSpan(span)}）"
        } else {
            "值守窗口 ${formatSpan(span)}"
        }
        val reason = input.closedReasonCn?.takeIf { it.isNotBlank() && end != null }
        val tail = reason?.let { "｜$it" }.orEmpty()
        return "离场报告｜$window$tail｜事件 ${input.events.size} 条"
    }

    /** A 级节：停手／降级／暂停／复工逐条，一条不少。 */
    private fun gradeA(events: List<UnattendedEvent>): List<String> {
        val rows = events.filter {
            it.kind in setOf(
                UnattendedEventKind.STOP,
                UnattendedEventKind.DEGRADE,
                UnattendedEventKind.PAUSE,
                UnattendedEventKind.RESUME,
            )
        }
        if (rows.isEmpty()) return listOf("A 级（要她处理/知道）：无")
        return listOf("A 级（要她处理/知道）${rows.size} 条：") + rows.map { "· ${it.kind.cn}｜${it.text}" }
    }

    /** 入队节：值守期间压进队列的事（事件账口径；她回场第一眼看这里）。 */
    private fun queueSection(events: List<UnattendedEvent>): List<String> {
        val rows = events.filter { it.kind == UnattendedEventKind.QUEUE }
        if (rows.isEmpty()) return listOf("入队（等她授权）：无")
        return listOf("入队（等她授权）${rows.size} 件：") + rows.map { "· ${it.text}" }
    }

    /** B 级汇总节：条数＋最近一条原文（例行进展，出过几条、最后说了什么）。 */
    private fun summarySection(events: List<UnattendedEvent>): List<String> {
        val rows = events.filter { it.kind == UnattendedEventKind.SUMMARY }
        if (rows.isEmpty()) return listOf("B 级汇总：无（没出过一条——若值守超 2h，见降级/看门狗账）")
        val last = rows.last()
        return listOf("B 级汇总 ${rows.size} 条，最近一条：", "· ${last.text}")
    }

    /** 中断补报节（判据原话"被中断要补报"；无中断也显式说一行）。 */
    private fun interruptSection(events: List<UnattendedEvent>): List<String> {
        val rows = events.filter { it.kind == UnattendedEventKind.INTERRUPT }
        if (rows.isEmpty()) return listOf("中断补报：值守期间无中断")
        return listOf("中断补报 ${rows.size} 次：") + rows.map { "· ${it.text}" }
    }

    /** 未做项节：收场时仍在队列里的快照（与"入队"节不同源：那边是事件账，这边是闸的现况）。 */
    private fun undoneSection(input: UnattendedReportInput): List<String> {
        val head = if (input.closedAtMs == null) "当前积压" else "未做项"
        return if (input.queueLines.isEmpty()) {
            listOf("$head：无")
        } else {
            listOf("$head ${input.queueLines.size} 件：") + input.queueLines.map { "· $it" }
        }
    }

    /** 时长的人话（x 小时 y 分钟；不满一分钟写"不足 1 分钟"）。 */
    internal fun formatSpan(spanMs: Long): String {
        val minutes = spanMs / 60_000L
        return when {
            minutes < 1L -> "不足 1 分钟"
            minutes < 60L -> "$minutes 分钟"
            else -> "${minutes / 60L} 小时 ${minutes % 60L} 分钟"
        }
    }
}
