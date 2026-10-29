// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十八批（派工单 §3 · 御案页签「批/驳/搁置＋批后落账」）：**御批账**。
//
// 为什么另起一本账（不塞进 208）：208 记的是"替你做过什么"；这本记的是
// **"你对一条意见怎么裁决的"**。混一本，抽检的分母就算错（与 274 分家的理由同源）。
//
// 三条裁定的语义（写死，别处不许改口）：
//   - 批（approve）：按这条办 ⇒ **永不再列**；
//   - 驳（reject）：这一步不做 ⇒ **永不再列**；
//   - 搁置（hold）：**只压到明天**（卡上也这么写"留到明天再看"）——次日它自己回来。
//     "搁一搁"要是一去不回，那就不是搁置，是删除。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** 三种裁定（机器键＋人话；人话只进日志/账本，卡面走资源）。 */
internal enum class RoyalVerdict(val wire: String, val cn: String) {
    APPROVE("approve", "已批"),
    REJECT("reject", "已驳"),
    HOLD("hold", "已搁置"),
    ;

    companion object {
        fun ofWire(wire: String): RoyalVerdict? = entries.firstOrNull { it.wire == wire }
        /** 卡上那个选项 id 就是 wire（拟稿的 `approve/reject/hold` 与本枚举同一套字面量）。 */
        fun ofOptionId(id: String): RoyalVerdict? = ofWire(id)
    }
}

/**
 * 一条御批。
 *
 * @param cardKey 卡键（＝意见判定 id）：批完之后页签靠它把这条从队列里划掉
 */
internal data class RoyalDecision(
    val opinionId: String,
    val verdict: RoyalVerdict,
    val atMs: Long,
    val cardKey: String = opinionId,
) {
    /** 当日（本地时区；"搁置只压到明天"靠它算）。 */
    val day: String get() = RoyalDecisionLedger.dayOf(atMs).toString()

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("op", opinionId)
        .put("verdict", verdict.wire)
        .put("at", atMs)
        .put("day", day)
        .put("card", cardKey)

    companion object {
        const val VERSION = 1

        fun parse(raw: String): RoyalDecision? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("op")
            val verdict = RoyalVerdict.ofWire(o.optString("verdict")) ?: return null
            if (id.isEmpty()) return null
            return RoyalDecision(
                opinionId = id,
                verdict = verdict,
                atMs = o.optLong("at", 0L),
                cardKey = o.optString("card").ifEmpty { id },
            )
        }
    }
}

/**
 * 御批账（进程内单例；append-only，落 `抱朴记忆/御批.jsonl`）。
 *
 * 与另外两本账同房型：`@Synchronized`、父目录不存在就建、**写失败不抛**只记一行日志。
 */
internal object RoyalDecisionLedger {

    /** 账本文件名（与 `意见判定.jsonl`／`追认队列.jsonl` 同一棵目录）。 */
    const val FILE_NAME = "御批.jsonl"

    private const val MEMORY_CAP = 200

    private val memory = ArrayList<RoyalDecision>()

    /** 落一条裁定。 */
    @Synchronized
    fun record(
        opinionId: String,
        verdict: RoyalVerdict,
        now: Long = System.currentTimeMillis(),
        cardKey: String = opinionId,
    ): RoyalDecision? {
        if (opinionId.isBlank()) {
            RunLog.log("抱朴御批：意见 id 为空，不落账")
            return null
        }
        val row = RoyalDecision(opinionId, verdict, now, cardKey.ifBlank { opinionId })
        memory.add(row)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                f.parentFile?.mkdirs()
                f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
            }.onFailure {
                RunLog.log("抱朴御批：账没落上（${row.opinionId}）——${it.javaClass.simpleName}")
            }
        }
        RunLog.log(logLine(row))
        return row
    }

    /** 回放（新→旧；不设时间窗）。 */
    @Synchronized
    fun replay(limit: Int = 0): List<RoyalDecision> {
        val fromFile = root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(RoyalDecision::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val all = (fromFile + memory).distinctBy { it.opinionId + ":" + it.atMs }
            .sortedByDescending { it.atMs }
        return if (limit > 0) all.take(limit) else all
    }

    /**
     * 这一刻**不该再列**的意见 id。
     *
     * 批/驳是永久的；搁置只压到**次日**（同一天内不再烦你，第二天它自己回来）。
     */
    @Synchronized
    fun suppressedIds(now: Long = System.currentTimeMillis()): Set<String> {
        val today = dayOf(now)
        return replay().filter { d ->
            when (d.verdict) {
                RoyalVerdict.APPROVE, RoyalVerdict.REJECT -> true
                RoyalVerdict.HOLD -> dayOf(d.atMs) == today
            }
        }.map { it.opinionId }.toSet()
    }

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    internal fun logLine(row: RoyalDecision): String =
        "抱朴御批：${row.verdict.cn}｜意见=${row.opinionId}｜${row.day}"

    internal fun dayOf(now: Long): LocalDate =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    @Synchronized
    fun resetForTest() {
        memory.clear()
    }

    private fun root(): File? = DecisionLedger.installedRoot()
}
