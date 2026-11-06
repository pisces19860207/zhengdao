// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 475 可靠升级的**升级本体**（派工单 §二 ③④⑤）。
//
// 一次可靠的升级只有三件事，缺一件就不叫升级、叫「把活丢给人」：
//   **停**：这一轮怎么收的场（安全中止／正常收尾）——不许一边问人一边继续改盘；
//   **说**：要什么／为什么／建议——三样都在结构里，构造期 `require` 拦住半截的请求；
//   **等**：进等待态。超时没人答 ⇒ **保持等待并记「无人应答」**，不许自行绕过
//        （[tick] 里没有一条放行分支：到了线只写账、状态照旧等）。
//
// 可靠性要测得出来，所以「到达」与「被看到」是两笔账，不是一句话：
//   到达＝这行请求落进了人可读的那本账（落盘成功才是到达，失败就说没到达，不静默）；
//   被看到＝它被渲染进给人看的那张体检单（幂等，一件只记一次）。
//   ——两件都落账之后，「升级丢了」才分得清是**没递到**还是**递到了没人看**。
//
// 越界才升级（[EscalationTrigger] 是闭集，没有「不确定」这一格）：
// 派工单 §二② 那句「不许把『不确定』当越界」在本层的执行方式是物理的——
// 触发枚举里根本没有 UNCERTAIN 这个值，说不准的事连升级的资格都没有。
//
// 「拒绝升级 ⇒ 该能力本轮禁用」（判据⑤）禁用挂在进程态上，理由写在 [disableForRound] 上。
package com.example.zhengdao.baopu.escalate

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 升级的触发（闭集，只有两种；没有"不确定"这一格，见文件头）。 */
internal enum class EscalationTrigger(val wire: String, val cn: String) {
    OUT_OF_SCOPE("out_of_scope", "越界"),
    TRACE_OVER_LIMIT("trace_over_limit", "轨迹异常达阈"),
    ;

    companion object {
        fun ofWire(wire: String): EscalationTrigger? = entries.firstOrNull { it.wire == wire }
    }
}

/** 停：这一轮怎么收的场（闭集——"我停了"要说得清是干净收尾还是安全中止）。 */
internal enum class StopMode(val wire: String, val cn: String) {
    WRAP_UP("wrap_up", "正常收尾"),
    SAFE_ABORT("safe_abort", "安全中止"),
}

/** 人的答复（闭集）。 */
internal enum class EscalationAnswer(val wire: String, val cn: String) {
    ACCEPT("accept", "照建议做"),
    REJECT("reject", "不许"),
}

/** 升级请求里「停」那一格。 */
internal data class StopFact(val mode: StopMode, val runId: String, val note: String) {
    init {
        require(runId.isNotBlank()) { "停要说得出停在哪一轮" }
        require(note.isNotBlank()) { "停要一句话说清怎么收的场" }
    }
}

/** 升级请求里「说」那一格：要什么／为什么／建议（三样缺一不可，构造期就拦）。 */
internal data class SayFact(val what: String, val why: String, val suggestion: String) {
    init {
        require(what.isNotBlank()) { "升级要说清要什么" }
        require(why.isNotBlank()) { "升级要说清为什么" }
        require(suggestion.isNotBlank()) { "升级要给一句建议" }
    }
}

/** 升级请求里「等」那一格：进等待的时刻＋到线口径（超时不放行，只记账）。 */
internal data class WaitFact(val enteredAtMs: Long, val replyTimeoutMs: Long) {
    init {
        require(replyTimeoutMs > 0L) { "等要有个到线口径" }
    }

    fun dueAtMs(): Long = enteredAtMs + replyTimeoutMs
}

/**
 * 一次升级请求（三要素齐了才构造得出来——判据④的结构断言钉的就是这三个字段）。
 */
internal data class EscalationRequest(
    val id: String,
    val atMs: Long,
    val capability: String,
    val trigger: EscalationTrigger,
    val stop: StopFact,
    val say: SayFact,
    val wait: WaitFact,
)

/** 升级账的一行（k＝事件：raised／seen／answered／no_reply）。 */
internal data class EscalationRow(
    val id: String,
    val atMs: Long,
    val event: String,
    val capability: String,
    val runId: String,
    val trigger: String,
    val note: String,
    val arrived: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("at", atMs)
        .put("k", event)
        .put("cap", capability)
        .put("run", runId)
        .put("trg", trigger)
        .put("note", note)
        .put("arrived", arrived)
        .toString()

    companion object {
        fun parse(raw: String): EscalationRow? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val event = o.optString("k")
            if (id.isBlank() || event.isBlank()) return null
            return EscalationRow(
                id = id,
                atMs = o.optLong("at", 0L),
                event = event,
                capability = o.optString("cap"),
                runId = o.optString("run"),
                trigger = o.optString("trg"),
                note = o.optString("note"),
                arrived = o.optBoolean("arrived", true),
            )
        }
    }
}

/**
 * 升级台账（进程内状态＋一本 `升级请求.jsonl`）。
 *
 * 落点与灾异事实、决策记录同一棵目录（208 装配的 `DecisionLedger.installedRoot()`）；
 * 没装配 ⇒ 只进内存，账不假装有。写失败 ⇒ `arrived=false` 照样落内存＋一行日志，
 * 「没到达」这件事本身必须看得见（金吾卫装配账同一条纪律）。
 */
internal object Escalation {

    const val FILE_NAME = "升级请求.jsonl"

    /** 事件名（账上的稳定键；文案会改键不许改）。 */
    const val EVENT_RAISED = "raised"
    const val EVENT_SEEN = "seen"
    const val EVENT_ANSWERED = "answered"
    const val EVENT_NO_REPLY = "no_reply"

    /** 体检单那一行最多列几件（超过就说「另有 N 件」，不把单子撑爆）。 */
    const val SHEET_ITEM_MAX = 3

    /** 禁用表最多留几轮（兜底出清用；和轨迹黑匣子的轮次上限同量级）。 */
    private const val ROUND_CAP = 32

    private val requests = LinkedHashMap<String, EscalationRequest>()

    /** 已记过「无人应答」的 id（一件只记一次，不每 tick 刷一行）。 */
    private val noReplyLogged = HashSet<String>()

    /** 已记过「被看到」的 id（同上，幂等）。 */
    private val seenLogged = HashSet<String>()

    /**
     * 本轮禁用表（runId → 被拒绝的能力）。
     *
     * 为什么是进程态：一条「本轮」的生命周期就是引擎那一圈——进程都没了，
     * 本轮也就结束了，把禁用写进盘只会让它跨到下一轮去（那是越权，不是谨慎）。
     */
    private val disabled = LinkedHashMap<String, MutableSet<String>>()

    private var seq = 0

    // ── 三要素：停 · 说 · 等 ────────────────────────────────────────────────

    /**
     * 发起一次升级（幂等：同一个 (runId, capability, trigger) 只开一件，重复来只累计）。
     *
     * @return 开出来的那件（已存在则返回在册的那件）
     */
    @Synchronized
    fun raise(
        capability: CapabilityKind,
        trigger: EscalationTrigger,
        stop: StopFact,
        say: SayFact,
        nowMs: Long = System.currentTimeMillis(),
        replyTimeoutMs: Long = BaopuKernel.Auth.CONFIRM_TIMEOUT_MS,
    ): EscalationRequest {
        val key = "${stop.runId}|${capability.wire}|${trigger.wire}"
        requests[key]?.let { return it }
        val req = EscalationRequest(
            id = "es:$nowMs:${++seq}",
            atMs = nowMs,
            capability = capability.wire,
            trigger = trigger,
            stop = stop,
            say = say,
            wait = WaitFact(nowMs, replyTimeoutMs),
        )
        requests[key] = req
        val written = append(EscalationRow(
            id = req.id, atMs = nowMs, event = EVENT_RAISED, capability = req.capability,
            runId = stop.runId, trigger = trigger.wire, note = say.what,
        ))
        if (!written) RunLog.log("抱朴升级：账没落上（${req.id}）——这单没到达，界面之外没人看得见它")
        RunLog.log("抱朴升级：${trigger.cn}｜能力=${req.capability}｜要什么=${say.what}")
        return req
    }

    /** 被看到（渲染进体检单时调；一件只记一次）。 */
    @Synchronized
    fun markSeen(id: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val req = requests.values.firstOrNull { it.id == id } ?: return false
        if (!seenLogged.add(id)) return false
        return append(EscalationRow(
            id = id, atMs = nowMs, event = EVENT_SEEN, capability = req.capability,
            runId = req.stop.runId, trigger = req.trigger.wire, note = "",
        ))
    }

    /**
     * 人回复（判据⑤：拒绝 ⇒ 该能力本轮禁用）。
     *
     * 答复只认这一条路；[tick] 里没有"到点当作默许"的分支——绕过这条口的任何写法
     * 都算违反派工单 §二④（负例 M6 咬的就是这个）。
     */
    @Synchronized
    fun respond(id: String, answer: EscalationAnswer, nowMs: Long = System.currentTimeMillis()): Boolean {
        val req = requests.values.firstOrNull { it.id == id } ?: return false
        if (answer == EscalationAnswer.REJECT) disableForRound(req.stop.runId, req.capability)
        val written = append(EscalationRow(
            id = id, atMs = nowMs, event = EVENT_ANSWERED, capability = req.capability,
            runId = req.stop.runId, trigger = req.trigger.wire, note = answer.wire,
        ))
        requests.entries.firstOrNull { it.value.id == id }?.let { requests.remove(it.key) }
        return written
    }

    /**
     * 到线检查（判据④后半）：无人应答 ⇒ **仍在等待**＋记一行「无人应答」，只记一次。
     *
     * @return 本次新记的无人应答 id
     */
    @Synchronized
    fun tick(nowMs: Long = System.currentTimeMillis()): List<String> {
        val newly = requests.values
            .filter { it.wait.dueAtMs() <= nowMs && it.id !in noReplyLogged }
            .map { it.id }
        newly.forEach { id ->
            noReplyLogged.add(id)
            val req = requests.values.first { it.id == id }
            append(EscalationRow(
                id = id, atMs = nowMs, event = EVENT_NO_REPLY, capability = req.capability,
                runId = req.stop.runId, trigger = req.trigger.wire, note = "无人应答：仍在等待",
            ))
            RunLog.log("抱朴升级：$id 到点无人应答——保持等待，不自行绕过")
        }
        return newly
    }

    /** 在册的升级（等待态的都在；按发起时刻升序）。 */
    @Synchronized
    fun open(): List<EscalationRequest> = requests.values.sortedBy { it.atMs }

    /** 该能力这一轮是否因「拒绝升级」被禁用。 */
    @Synchronized
    fun isDisabled(runId: String, capability: String): Boolean =
        disabled[runId]?.contains(capability) == true

    /** 引擎起新一轮时清掉上一轮的禁用（禁的是本轮，不是这台机器）。 */
    @Synchronized
    fun endRun(runId: String) {
        disabled.remove(runId)
    }

    private fun disableForRound(runId: String, capability: String) {
        disabled.getOrPut(runId) { mutableSetOf() }.add(capability)
        // 引擎收工时的 [endRun] 是正路；这一格兜底是给"没人调 endRun"的那条路（长会话
        // 不许把禁用表攒成无底洞）——按最老的轮次先出，跨轮的禁用本来就无效。
        while (disabled.size > ROUND_CAP) disabled.remove(disabled.keys.first())
    }

    // ── 体检单那一行（475 的出口，与 474 同一张单子） ─────────────────────────

    /**
     * 渲染＝被看到：这一行进了体检单，就把列出来的那几件记成 seen。
     *
     * 为什么把 markSeen 放在渲染里而不是单独一个口：没有第二个读的人界面——
     * 这张单子就是"人看到"的那个时刻本身。没列出来的（超过 [SHEET_ITEM_MAX] 的）不假装有记。
     */
    @Synchronized
    fun sheetLines(nowMs: Long = System.currentTimeMillis()): List<String> {
        val pending = open()
        if (pending.isEmpty()) return listOf("升级：本轮没有在等人的升级")
        tick(nowMs)
        val shown = pending.take(SHEET_ITEM_MAX)
        shown.forEach { markSeen(it.id, nowMs) }
        val rest = pending.size - shown.size
        val tail = if (rest > 0) "，另有 $rest 件" else ""
        return listOf(
            "升级：在等 ${pending.size} 件（前 ${shown.size} 件：" +
                shown.joinToString("、") { "${it.capability}/${it.trigger.wire}" } + "$tail）",
        )
    }

    /** 回放（读账；没装配落点 ⇒ 空表，不编历史）。 */
    fun replay(): List<EscalationRow> {
        val file = fileOrNull() ?: return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull(EscalationRow::parse)
        }.getOrDefault(emptyList())
    }

    fun fileOrNull(): File? = DecisionLedger.installedRoot()?.let { File(it, FILE_NAME) }

    private fun append(row: EscalationRow): Boolean {
        val file = fileOrNull() ?: return false
        return runCatching {
            if (!file.parentFile.isDirectory) file.parentFile.mkdirs()
            file.appendText(row.toJson() + "\n")
        }.isSuccess
    }

    /** 直测收尾（生产不调）：只清内存，磁盘账不动——「重启后还在等」靠的就是那本账。 */
    @Synchronized
    fun resetForTest() {
        requests.clear()
        noReplyLogged.clear()
        seenLogged.clear()
        disabled.clear()
        seq = 0
    }
}
