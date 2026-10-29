// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十七批（派工单 §3 · L33 自决单的最小版）：**"不打扰"要留理由**。
//
// 调解器每判一条"不弹"，都要能回答一句"凭什么判为不必打扰"。这条理由落进
// `追认队列.jsonl` —— 名字取路线图 §11.2 那个"追认队列"（你可随时翻），
// 但本批**只落最小版**：做了什么 / 为什么判为不必打扰 / 哪一轮 / 指向哪条意见。
// 完整的追认单（回滚点／花了多少／暗卫复核）是 M5 的活，本批不抢。
//
// 三条纪律（与本仓其它账本一致）：
//   1. **append-only**：一行一条，改了不覆盖（追认的意义就是"当时这么想的"）；
//   2. **不设时间窗**：30 天后仍可读——重放时不许拿"太久没看"当过滤条件；
//   3. **写失败不抛**：留痕丢了不算事故，但要有一行日志（照 208/影子账）。
//
// 落点＝208 已装配的那棵目录（`抱朴记忆/`），与 `意见判定.jsonl` 同目录不同文件：
// 一本记"该不该打扰"，一本记"不打扰的理由"，混在一起会让抽检的分母算错。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.core.RunLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/**
 * 一条自决单（"这次没打扰你，因为……"）。
 *
 * @param why 机器键（`already_refused` / `no_law` 这类）——回放按它分组；**文案会改，键不许改**
 * @param reason 一句人话（就是账上"为什么判为不必打扰"那一列；空 ⇒ 不落账）
 * @param opinionId 指向 `意见判定.jsonl` 的那一条（两本账靠它对齐）
 */
internal data class SelfRuling(
    val id: String,
    val atMs: Long,
    val round: String,
    val tool: String,
    val gist: String,
    val lawRef: String,
    val why: String,
    val reason: String,
    val opinionId: String,
) {
    /** 当日（本地时区，与御案日额同一把尺）。 */
    val day: String get() = SelfRulingLedger.dayOf(atMs).toString()

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("day", day)
        .put("round", round)
        .put("tool", tool)
        .put("gist", gist)
        .put("law", lawRef)
        .put("why", why)
        .put("reason", reason)
        .put("op", opinionId)

    companion object {
        /** 行格式版本（改形状才 +1；加字段不算）。 */
        const val VERSION = 1

        fun parse(raw: String): SelfRuling? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val reason = o.optString("reason")
            // 缺理由的行当坏行跳过：自决单的意义就是那句理由，没有它这条账没有信息
            if (id.isEmpty() || reason.isBlank()) return null
            return SelfRuling(
                id = id,
                atMs = o.optLong("at", 0L),
                round = o.optString("round"),
                tool = o.optString("tool"),
                gist = o.optString("gist"),
                lawRef = o.optString("law"),
                why = o.optString("why"),
                reason = reason,
                opinionId = o.optString("op"),
            )
        }
    }
}

/**
 * 追认队列（进程内单例）：判了"不打扰"就往这里落一条。
 *
 * 为什么落盘也留内存：单测（纯 JVM，没装配落点）要能读回来；生产上盘是权威。
 */
internal object SelfRulingLedger {

    /** 账本文件名（与 `意见判定.jsonl` 同一棵目录）。 */
    const val FILE_NAME = "追认队列.jsonl"

    /** 内存里最多留多少条（回放以盘为准；这条只是"没装配落点"时的兜底）。 */
    private const val MEMORY_CAP = 200

    private val memory = ArrayList<SelfRuling>()
    private var seq = 0

    /**
     * 落一条自决单；**理由为空 ⇒ 不落**（返回 null，并留一行日志）。
     *
     * 为什么"缺理由就不落"：没有理由的自决单等于把 L33 变成一句空话——
     * 负例点名"自决单缺理由"，所以这个口子必须在入口堵住，而不是渲染时兜。
     */
    @Synchronized
    fun note(
        opinion: Opinion,
        opinionId: String,
        round: String,
        tool: String,
        why: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): SelfRuling? {
        val text = reason.trim()
        if (text.isEmpty() || opinionId.isBlank()) {
            RunLog.log("抱朴自决单：缺理由，不落账（$opinionId｜$why）")
            return null
        }
        seq += 1
        val row = SelfRuling(
            id = "sr:$now:$seq",
            atMs = now,
            round = round,
            tool = tool,
            gist = opinion.gist,
            lawRef = opinion.lawRef.name,
            why = why,
            reason = text,
            opinionId = opinionId,
        )
        record(row)
        RunLog.log(logLine(row))
        return row
    }

    /**
     * 回放（**不设时间窗**：30 天后仍可读）。
     *
     * 新→旧；`limit > 0` 时只取最新这么多条（读全本是为了种子化，不是给人翻）。
     */
    @Synchronized
    fun replay(limit: Int = 0): List<SelfRuling> {
        val fromFile = root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(SelfRuling::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val all = (fromFile + memory).distinctBy { it.id }.sortedByDescending { it.atMs }
        return if (limit > 0) all.take(limit) else all
    }

    /** 落点文件（与 208 同一棵目录）。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 日志那一行（格式也是判据，埋进 RunLog 里就抓不到）。 */
    internal fun logLine(row: SelfRuling): String =
        "抱朴自决单：不打扰（${row.why}）${row.gist}｜轮=${row.round}｜${row.reason}"

    /** 本地时区的"哪一天"（与御案日额同一口径）。 */
    internal fun dayOf(now: Long): LocalDate =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    /** 清空（测试专用：单例状态会跨用例污染，谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
        seq = 0
    }

    private fun record(row: SelfRuling) {
        memory.add(row)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val r = root() ?: return
        runCatching {
            val f = fileIn(r)
            f.parentFile?.mkdirs()
            f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴自决单：账没落上（${row.id}）——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 账本落点（与 208 同一棵目录；没装配 ⇒ null，只进内存）。 */
    private fun root(): File? = DecisionLedger.installedRoot()
}
