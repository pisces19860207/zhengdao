// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 查询的一句话入口（面板、工具、播报三处都调这里，判据只有一条路径）。
//
// ## 「今天」是哪天，由手机日历说，不由表说
// 查询默认落在 `dayKey(now)`（今天）。她补导了昨天的表，那些行就归昨天，
// 今天问「还有哪些没发」不会把它们列进来（理由见 [LogisticsImport] 文件头）。
// 面板上可以切日期，切的就是这个 `day`。
//
// ## 数据时间从行里取，不从墙上时钟取
// [exportedAtOf] 拿的是**这一天最晚那份表**的时间与来历（文件名/表里/文件时间）。
// 用 `System.currentTimeMillis()` 当数据时间是派工单 §3.5 明令禁止的「装作最新」。
//
// ## 隐私（派工单 §7 沿用 195 那条）
// 查询原话**不进日志、不进库**，只在这一次调用里活着；日志只写条数与「口径可疑」这种
// 不含单据内容的句子。真实单号也不写日志——它们只留在她自己的 产物/物流/ 里。
package com.example.zhengdao.baopu.logistics

import com.example.zhengdao.rootfs.RunLog
import java.util.TimeZone

/** 查询入口。 */
object LogisticsAsk {

    /** 答案第 3 行里那个名词（派工单 §3 原话是「09:12 导出的配货单」，真实文件名进面板与台账）。 */
    const val FILE_LABEL = "配货单"

    fun dayKeyOf(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        LogisticsExportedAt.dayKey(nowMillis, zone)

    /**
     * 一句话回答（固定三行纪律在这里成形）。
     *
     * @param zone 时区由调用方传（面板传默认时区，测试传固定值）——
     *   测试里用默认时区会让「09:12」这种读数随机器变，那条判据就白钉了
     */
    fun answer(
        store: LogisticsStore,
        text: String,
        basis: LogisticsBasis,
        day: String,
        nowMillis: Long,
        zone: TimeZone,
    ): String = answerOf(LogisticsQuery.ask(store, day, text, basis), store.rowsFor(day), nowMillis, zone)

    /**
     * 同一次查询的完整回答：[text] 是给眼睛的三行话，[phones] 是给手指的。
     *
     * 号码**只从命中行的手机号那一列来**（[LogisticsPhone.forRows]）——单号与车牌永远不是号码来源，
     * 这是「把单号车牌当号码」那条负例在结构上的解：答案文本里哪怕长得像号码的段，
     * 也不进这仨链接。判据相同所以不存在两套答案；工具与播报不用这个入口（号码不离开本机界面）。
     */
    data class Reply(val text: String, val phones: List<DialNumber>)

    fun reply(
        store: LogisticsStore,
        text: String,
        basis: LogisticsBasis,
        day: String,
        nowMillis: Long,
        zone: TimeZone,
    ): Reply {
        val outcome = LogisticsQuery.ask(store, day, text, basis)
        val rows = store.rowsFor(day)
        return Reply(
            text = answerOf(outcome, rows, nowMillis, zone),
            phones = if (outcome is QueryOutcome.Found) LogisticsPhone.forRows(outcome.agg.rows) else emptyList(),
        )
    }

    /**
     * 结论 → 三行话（面板与工具共用这一条出话路径；[answer] 只是它前面多一次查询）。
     *
     * 拆出来是因为工具那边已经把行读好了（它要同一批行算 JSON 里的条数）：
     * 两处各调一次 `answer()` 就会各读一次库，读数能差一行——出话只许有一条路径。
     */
    fun answerOf(
        outcome: QueryOutcome,
        rows: List<StoredRow>,
        nowMillis: Long,
        zone: TimeZone,
    ): String {
        if (outcome is QueryOutcome.Found && outcome.agg.suspicious) {
            // 裁定 5：违反一致性自检要写日志（只写条数，单号与车牌不进日志）
            RunLog.log("物流查询：口径可疑（车数 ${outcome.agg.vehicles} > 单数 ${outcome.agg.orders}，${outcome.agg.basis.name}）")
        }
        return LogisticsAnswer.of(outcome, exportedAtOf(rows), FILE_LABEL, zone, nowMillis)
    }

    /** 这一天的数据时间（没有任何行 ⇒ [ExportedAtSource.NONE]，答案里就写「取不到」）。 */
    fun exportedAtOf(rows: List<StoredRow>): ExportedAt {
        if (rows.isEmpty()) return ExportedAt(null, ExportedAtSource.NONE, hasClock = false)
        val newest = rows.maxByOrNull { it.exportedAt } ?: rows.first()
        return newest.exported()
    }

    /**
     * 面板与工具顶栏用的「数据时间 = X（取自…）」一句（派工单 §1 要求界面明示）。
     *
     * 吃**已经读出来的行**，不吃 store：这两处调用点本来就都握着当天那批行，
     * 再传 store＋day 等于每次重组／每次工具调用多跑一条整天扫描的 SELECT
     * （面板上打字都会触发重组）。
     */
    fun dataTimeNote(rows: List<StoredRow>, zone: TimeZone): String =
        LogisticsExportedAt.sourceNote(exportedAtOf(rows), zone)

    /** 这份表缺哪些可降级列（面板的降级提示；跨文件是并集）。 */
    fun absentOf(rows: List<StoredRow>): Set<LogisticsField> {
        val out = HashSet<LogisticsField>()
        rows.forEach { out += it.missingFields() }
        return out
    }

    /** 这一天有哪些来源文件（证据要指到文件，不能只说「表里」）。 */
    fun sourcesOf(rows: List<StoredRow>): List<String> = rows.map { it.srcFile }.filter { it.isNotEmpty() }.distinct()
}
