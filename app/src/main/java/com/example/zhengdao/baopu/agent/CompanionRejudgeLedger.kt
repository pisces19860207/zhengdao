// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十一批（2026-11-12）· 相处模式**改判账**（法典 L34 的"账在哪"那一格）。
//
// 为什么独立一本而不是复用 208／打扰治理：208 的 kind 是闭集（红线：不加项），打扰治理账
// 记的是"问不问"；改判记的是"上一轮判什么口吻、这一轮改成什么口吻、凭什么"——**原判/新判/
// 依据**三列是法典 L34 点名的，塞进别本的自由文本列里就成了"有账但按列读不出"。
// 读口也是结构性的：`governance_state` 的 `companion` 段（当日次数＋最近一次）与自治理中心
// 读数区那一行，都从 [replay] 一处取数（不另算第二遍）。住 agent/ 与 AskGovLedger 同乡：
// 落账要取钟要写盘，verify/ 纯判定区（302 源级门禁）进不来。
//
// 三条纪律：
//   1. 行是**追加式**，只增不改（与 AskGovLedger 同口径）；落点与 208 同一棵目录
//      （[DecisionLedger.installedRoot]）；没装配落点（纯 JVM 单测）⇒ 只进内存。
//   2. **谁纠**＝只有用户点名（[com.example.zhengdao.baopu.CompanionDecision.explicit]）；
//      自动信号换档不落这本账——那不是误判，是"这一轮输入不一样"（550 §六 同一条裁定）。
//   3. **依据必填**：没有依据的改判复盘不了（与 CompanionDecision 的 require 同一句话）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CompanionDecision
import com.example.zhengdao.baopu.CompanionMode
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/**
 * 改判账的一行（法典 L34 点名的三列：原判／新判／依据；线键写死，md 对拍引用的就是它们）。
 *
 * @param from 原判的机器键（work/study）；[fromCn] 给人看的说法（工作/学习），行自足可回放
 * @param to 新判的机器键与说法
 * @param basis 依据（本轮判档点名命中的那句，逐字来自 [CompanionDecision.reason]）
 */
internal data class CompanionRejudgeRow(
    val id: String,
    val atMs: Long,
    val sessionId: Long,
    val turn: Int,
    val from: String,
    val fromCn: String,
    val to: String,
    val toCn: String,
    val basis: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", CompanionRejudgeLedger.VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("session", sessionId)
        .put("turn", turn)
        .put(CompanionRejudgeLedger.COL_FROM, from)
        .put("fromCn", fromCn)
        .put(CompanionRejudgeLedger.COL_TO, to)
        .put("toCn", toCn)
        .put(CompanionRejudgeLedger.COL_BASIS, basis)

    companion object {
        fun parse(raw: String): CompanionRejudgeRow? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val from = o.optString(CompanionRejudgeLedger.COL_FROM)
            val to = o.optString(CompanionRejudgeLedger.COL_TO)
            if (id.isEmpty() || from.isEmpty() || to.isEmpty()) return null
            return CompanionRejudgeRow(
                id = id,
                atMs = o.optLong("at", 0L),
                sessionId = o.optLong("session", 0L),
                turn = o.optInt("turn", 0),
                from = from,
                fromCn = o.optString("fromCn"),
                to = to,
                toCn = o.optString("toCn"),
                basis = o.optString(CompanionRejudgeLedger.COL_BASIS),
            )
        }
    }
}

/** 相处改判账（`相处改判.jsonl`，与 208 同一棵目录；行追加式，只增不改）。 */
internal object CompanionRejudgeLedger {

    const val FILE_NAME = "相处改判.jsonl"
    const val VERSION = 1

    /** 行的三列线键（法典 L34：原判（from）／新判（to）／依据（basis））。 */
    const val COL_FROM = "from"
    const val COL_TO = "to"
    const val COL_BASIS = "basis"

    private const val MEMORY_CAP = 300

    private val memory = ArrayDeque<CompanionRejudgeRow>()
    private var seq = 0

    /**
     * 落一行改判（**只有用户点名才落**——调用方 [CompanionLawWiring]
     * 已按 correction 非空过滤，这里再防一道：非显式不给落）。
     *
     * @param prevMode 上一轮判的档（原判列的来源）；`null`＝第一轮，没有可纠的判，直接拒
     * @param decided 本轮判档（新判列＋依据列的来源；依据列逐字取 [CompanionDecision.reason]）
     * @return 落成的那行；被拒（非显式／第一轮／同档）⇒ `null`——拒了就是拒了，不静默改写成别的行
     */
    @Synchronized
    fun append(
        prevMode: CompanionMode?,
        decided: CompanionDecision,
        turn: Int,
        sessionId: Long,
        atMs: Long = System.currentTimeMillis(),
    ): CompanionRejudgeRow? {
        if (prevMode == null || !decided.explicit || prevMode == decided.mode) return null
        seq += 1
        val row = CompanionRejudgeRow(
            id = "cr:$atMs:$seq",
            atMs = atMs,
            sessionId = sessionId,
            turn = turn,
            from = prevMode.wire,
            fromCn = prevMode.cn,
            to = decided.mode.wire,
            toCn = decided.mode.cn,
            basis = decided.reason,
        )
        require(row.basis.isNotBlank()) { "改判必须带依据（法典 L34：没有依据的改判复盘不了）" }
        memory.addLast(row)
        while (memory.size > MEMORY_CAP) memory.removeFirst()
        val root = DecisionLedger.installedRoot() ?: return row
        runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴相处改判：账没落上（${row.id}）——${it.javaClass.simpleName}")
        }
        return row
    }

    /** 回放（盘上 ＋ 内存，去重按 id，新→旧）。 */
    @Synchronized
    fun replay(): List<CompanionRejudgeRow> {
        val fromFile = DecisionLedger.installedRoot()?.let { root ->
            runCatching {
                val f = fileIn(root)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(CompanionRejudgeRow::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        return (fromFile + memory.toList()).distinctBy { it.id }.sortedByDescending { it.atMs }
    }

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 清空（测试专用：谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
        seq = 0
    }
}
