// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百一十四批 · 六体读数接线（708 §五·B 的收工链落点，G-708-1）。
//
// 做什么：一轮收工（EngineEvent.Settled 的触发链，TriggerWiring.onRunSettled）后，
// 把刚收尾那轮的输入喂给六件读数源里的四件——杏仁核 salience／丘脑 relay／
// 前额叶 readPlan／海马 key，每件走自己的 BioLog 一行 stdout。只读、只 log、
// 不参与判定：读数即弃（不外流、不落账、不改档），判定权在既有裁决面。
// 另两件的生产调用点在 521 的消费层（habit/HabitWiring 的纹状体 rpe、
// insula/InsulaWiring 的岛叶 snapshot，同一条收工链），本件不重复喂——
// 六件各 ≥1 生产调用点的账见交付报告。
//
// 三条硬规矩（523 三件套在本件的落码）：
//   ① 可测差异：同输入两次读数逐字一致（判据⑥）——BioLog 无时间戳、器官无状态，
//     喂入全来自只读库读（单行/限量查询）；BioReadoutWiringTest 钉 stdout 逐字节相等；
//   ② 不越权：本文件零判定词、零档写、零权威账写（源码断言钉）；唯一的写是
//     兜底时往 RunLog 记一行失败（65/89 口径，与 TriggerWiring 同款）；
//   ③ 失败兜底：喂入缝或器官抛异常 ⇒ 那一件记一行后按"没有它"跳过，其余件照常；
//     整段再被 TriggerWiring.safe 包住——主链路与"没有它"逐字节一致（判据③）。
//     超时无单独机关：全部喂入是单行/限量只读查询，且整段在 IO 协程里跑
//     （wireBOnSettle），最慢也只是后台慢一行，不碰用户路径。
//
// 数据从哪来（全部只读）：
//   · 本轮用户消息：`message` role='user' 最新一行＋首个 text part（杏仁核/丘脑/海马共用）；
//   · 计划快照：`part(kind='plan')` 该会话最后一版（SessionPlanStore.latest → PlanRules.fromJson）；
//   · 近 3 条失败：run_log ok=0 的行——755 起每行带**当场**归因线 `step_ref`
//     （attributed/ambiguous/unattributed，引擎发射那一刻落账，非事后回扫）：
//     attributed 的喂真步号；ambiguous（候选≥2）/unattributed（原因码）/755 前的 NULL 行
//     记 null——前额叶口径"判不了就不猜"（694 四条判据一字不动，喂的只是不再恒 null）。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.SessionPlanStore
import com.example.zhengdao.baopu.StepRef
import com.example.zhengdao.baopu.bio.Amygdala
import com.example.zhengdao.baopu.bio.BioEvent
import com.example.zhengdao.baopu.bio.DangerWords
import com.example.zhengdao.baopu.bio.Hippocampus
import com.example.zhengdao.baopu.bio.PrefrontalCortex
import com.example.zhengdao.baopu.bio.Thalamus
import com.example.zhengdao.baopu.pack.FactPackWords
import com.example.zhengdao.core.RunLog

/** 本轮用户消息的只读投影：seq 给海马键（Int，G-708-2 口径），text 给杏仁核/丘脑。 */
internal data class BioUserLine(val seq: Int, val text: String)

/**
 * 喂入缝（全部只读）。默认构造走 [BioFeeds.production]（生产库＋719 词表缝）；
 * 测试直接构造替身——含抛异常夹具（判据③：喂入炸 ＝ 那一件按没有它走）。
 */
internal class BioFeeds(
    /** 收工会话号（海马键的地址半边；随触发链传来，不在本件里猜"最近会话"）。 */
    val sid: Long,
    /** 本轮用户消息（没有 ＝ 这轮没有用户输入 ⇒ 三件输入件整路跳过，合法兜底）。 */
    val latestUserMessage: () -> BioUserLine?,
    /** 最后一版计划条目（空表 ＝ 该会话没有计划快照 ⇒ 前额叶那路整路静默，不出空话）。 */
    val planItems: () -> List<PlanRules.PlanItem>,
    /** 近几条失败（元素＝失败行**当场**归因出的计划步号；判不出单一落点记 null——不猜）。 */
    val recentFailures: () -> List<Int?>,
    /** 词表（719 缝：包开关开着读 fact 片并集，关/缺/坏 ⇒ 原样默认表，零磁盘）。 */
    val words: () -> DangerWords,
) {
    companion object {

        /** 生产喂入：三路只读库读＋词表缝；每个喂入调用时现查（一轮各 ≤1 次）。 */
        fun production(ctx: Context, sessionId: Long): BioFeeds {
            val db = BaopuRuntime.db(ctx)
            return BioFeeds(
                sid = sessionId,
                latestUserMessage = { latestUserLineOf(db, sessionId) },
                planItems = { planItemsOf(db, sessionId) },
                recentFailures = { recentFailureRowsOf(db, sessionId) },
                words = { FactPackWords.load().words },
            )
        }

        /**
         * 本轮用户消息（只读单行）：role='user' 最新一行＋首个 text part。
         * seq 超 Int 范围 ⇒ 整行按"没有"走（海马键收 Int，G-708-2——宁缺不截断）。
         */
        internal fun latestUserLineOf(db: BaopuDb, sessionId: Long): BioUserLine? = runCatching {
            db.read { d ->
                d.rawQuery(
                    "SELECT m.seq, p.payload FROM message m " +
                        "JOIN part p ON p.message_id = m.id AND (p.kind IS NULL OR p.kind = 'text') " +
                        "WHERE m.session_id = ? AND m.role = 'user' " +
                        "ORDER BY m.id DESC, p.seq ASC LIMIT 1",
                    arrayOf(sessionId.toString()),
                ).use { c ->
                    if (!c.moveToFirst()) null else userLineOf(c.getLong(0), c.getString(1))
                }
            }
        }.getOrNull()

        /** 该会话最后一版计划（part kind='plan'，只读；没有/解析不了 ⇒ 空表）。 */
        internal fun planItemsOf(db: BaopuDb, sessionId: Long): List<PlanRules.PlanItem> {
            val payload = runCatching { SessionPlanStore(db).latest(sessionId) }.getOrNull()
                ?: return emptyList()
            return runCatching { PlanRules.fromJson(payload) }.getOrDefault(emptyList())
        }

        /**
         * 近 [RECENT_FAILURES_MAX] 条失败行（run_log ok=0，时间倒序）。每行读**当场**
         * 归因线（755 起引擎发射时落 `step_ref`）：attributed:N 喂 N；
         * ambiguous（候选≥2）/unattributed（原因码）/NULL（755 前的历史行）喂 null——
         * 本侧只**读已记录的归因**，一个步号都不推（禁事后回扫；判不了就不猜）。
         */
        internal fun recentFailureRowsOf(db: BaopuDb, sessionId: Long): List<Int?> = runCatching {
            db.read { d ->
                d.rawQuery(
                    "SELECT step_ref FROM run_log WHERE session_id = ? AND ok = 0 " +
                        "ORDER BY created_at DESC LIMIT $RECENT_FAILURES_MAX",
                    arrayOf(sessionId.toString()),
                ).use { c ->
                    val rows = ArrayList<Int?>(c.count)
                    while (c.moveToNext()) rows.add(attributedStepOf(c.getString(0)))
                    rows
                }
            }
        }.getOrDefault(emptyList())

        /**
         * 归因线 → 前额叶喂入步号（internal 供单测）：attributed 给 N；
         * ambiguous/unattributed/NULL/坏线一律 null——器官判据四条一字不动，
         * 喂入只是不再恒 null（694 口径：判不了就不猜）。
         */
        internal fun attributedStepOf(wire: String?): Int? =
            (wire?.let { StepRef.of(it) } as? StepRef.Attributed)?.step

        /** Long → Int 收敛（G-708-2）：超界返回 null，不截断造一把假键/假行。 */
        private fun userLineOf(rawSeq: Long, text: String?): BioUserLine? {
            val seq = rawSeq.takeIf { it in SEQ_INT_MIN..SEQ_INT_MAX }?.toInt() ?: return null
            return BioUserLine(seq = seq, text = text.orEmpty())
        }
    }
}

/**
 * 器官调用缝：生产缺省直呼六件里的四件；测试注入替身（记录读数／抛异常夹具）。
 * 返回类型就是各器官的读数形状——接线层只收不存（读数即弃）。
 */
internal class BioOrganCalls(
    val salience: (BioEvent, DangerWords) -> Amygdala.Reading = { e, w -> Amygdala.salience(e, w) },
    val relay: (BioEvent, DangerWords) -> Thalamus.Relay = { e, w -> Thalamus.relay(e, w) },
    val key: (Long, Int, BioEvent) -> Hippocampus.EpisodeKey = { s, q, c -> Hippocampus.key(s, q, c) },
    val readPlan: (PrefrontalCortex.PlanView) -> PrefrontalCortex.PlanReading = { v ->
        PrefrontalCortex.readPlan(v)
    },
)

/**
 * 六体读数的收工发射器（TriggerWiring.onRunSettled 的最后一站）。
 * 不建线程/协程/定时器（触发线程由调用方给，676 同款纪律）；返回值只是
 * "这轮真出了几行 BioLog"（0..4），给日志与测试用，不进任何判定。
 */
internal object BioReadoutWiring {

    /** 收工链入口（TriggerWiring.safe 包外层兜底；sessionId 没有就整轮静默）。 */
    fun onRunSettled(ctx: Context, sessionId: Long?): Int {
        val sid = sessionId ?: return 0
        return emitAll(BioFeeds.production(ctx, sid), BioOrganCalls())
    }

    /** 逐件发射（测试直达：喂入与器官缝都可注入）。 */
    internal fun emitAll(feeds: BioFeeds, calls: BioOrganCalls): Int =
        eventReadings(feeds, calls) + planReading(feeds, calls)

    /**
     * 本轮用户消息那一路：海马键→杏仁核突显→丘脑中继（同一输入，各一行 BioLog）。
     * 没有本轮消息（null，非异常）＝ 这轮没有用户输入 ⇒ 三件输入件整路静默（合法兜底，
     * 不是失败）；词表炸只牺牲突显/中继两件——键不吃词表，单独兜底（逐件口径）。
     */
    private fun eventReadings(feeds: BioFeeds, calls: BioOrganCalls): Int {
        val line = runCatching { feeds.latestUserMessage() }
            .getOrElse { skip(FEED_MESSAGE, it); return 0 } ?: return 0
        val event = BioEvent(text = line.text, source = SOURCE_USER_MSG)
        var emitted = guarded(ORGAN_HIPPOCAMPUS) { calls.key(feeds.sid, line.seq, event) }
        val words = runCatching { feeds.words() }
            .getOrElse { skip(FEED_WORDS, it); return emitted }
        emitted += guarded(ORGAN_AMYGDALA) { calls.salience(event, words) }
        emitted += guarded(ORGAN_THALAMUS) { calls.relay(event, words) }
        return emitted
    }

    /** 计划那一路：前额叶 readPlan。该会话没有计划快照（空表）＝无可读之物 ⇒ 静默跳过——
     * 不出"剩 0/0 步"的空话（读数缺位＝兜底，与器官不在场同形）。 */
    private fun planReading(feeds: BioFeeds, calls: BioOrganCalls): Int {
        val items = runCatching { feeds.planItems() }
            .getOrElse { skip(FEED_PLAN, it); return 0 }
        if (items.isEmpty()) return 0
        val view = runCatching {
            PrefrontalCortex.PlanView(
                steps = items.mapIndexed { index, item ->
                    PrefrontalCortex.PlanStepView(no = index + 1, done = item.status == PlanRules.STATUS_DONE)
                },
                recentFailures = feeds.recentFailures(),
            )
        }.getOrElse { skip(FEED_PLAN, it); return 0 }
        return guarded(ORGAN_PREFRONTAL) { calls.readPlan(view) }
    }

    /** 单件兜底壳：炸了记一行按没有它走（返回 0）；成了算 1——读数即弃。 */
    private fun guarded(organ: String, block: () -> Any): Int =
        runCatching(block).fold(onSuccess = { 1 }, onFailure = { skip(organ, it); 0 })

    /** 兜底只记一行（65/89 口径）；不外抛、不落账、不重试。 */
    private fun skip(what: String, t: Throwable) {
        RunLog.log("六体读数：$what 抛出 ${t.javaClass.simpleName}——按没有它走（这轮少一行读数）")
    }

    private const val SOURCE_USER_MSG = "用户消息"
    private const val ORGAN_HIPPOCAMPUS = "海马"
    private const val ORGAN_AMYGDALA = "杏仁核"
    private const val ORGAN_THALAMUS = "丘脑"
    private const val ORGAN_PREFRONTAL = "前额叶"
    private const val FEED_MESSAGE = "喂入·本轮用户消息"
    private const val FEED_WORDS = "喂入·词表"
    private const val FEED_PLAN = "喂入·计划快照"
}

/** 近失败行数上限（前额叶自己只看最后 3 条——喂多了它也截，这里少查一点）。 */
internal const val RECENT_FAILURES_MAX = 3

/** Long seq → Int 收敛的两个界（G-708-2；压线内收、超界弃，与 keysOfLedger 同口径）。 */
private const val SEQ_INT_MIN = Int.MIN_VALUE.toLong()
private const val SEQ_INT_MAX = Int.MAX_VALUE.toLong()
