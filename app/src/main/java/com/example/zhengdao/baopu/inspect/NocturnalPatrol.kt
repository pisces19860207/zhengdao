// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百七十九批 §1：不良人（自动抽检）——「这一趟该不该跑、跑哪几条、跑完记什么」。
//
// 只做四件事：
// 1. **双闸配额**：滚动 7 天 30 条＝硬总闸（满即停、不补、不降级）；每晚 5＝单次闸；
// 2. **四条前提**（缺一条就不跑）：充电才跑（非充电**顺延**、不叠加）／零模型调用／
//    不写业务状态／可一键停。零模型调用那条由 `NocturnalPatrolTest` 从源码层钉；
// 3. **「条」＝一条被检记录**（208 决策记录／`run_trace` 行）。额度按被检记录数算，
//    **不按 Opinion 条数**——拿命中数当额度等于「少报就少抽」；
// 4. **只出意见不打扰**：非 CRITICAL 一律走 [PatrolDelivery.QUEUE]，不进界面。
//
// 2026-10-29 裁定②（对账）：抽中的几条必须**投回六项判据的输入侧**，且池子与判据
// 读的是**同一份输入**（208 决策记录／run_log 痕迹行）——见 [NocturnalPatrol.poolOf]
// 与 [NocturnalPatrol.scopeTo]。抽到的和看到的成了两批东西，这一趟就是白抽。
//
// 三条口径（2026-10-29 用户裁）：①没跑成不补；②超额即停、不降级；
// ③CRITICAL 不占额度（灾异走 L21／卷十，不能因为当周额度用尽而漏掉）。
// 一键停连灾异通道一起停——停不掉的停是假的。
//
// 本层零 Android、零时钟（时刻与「今晚」的桶由调用方给）、零模型、不碰业务状态：
// 写盘只有自己那本 `暗卫记录.jsonl`（只记条数与证据引用，不记正文）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionRecord
import java.io.File
import java.util.Random
import org.json.JSONArray
import org.json.JSONObject

/** 单里的数（别处不许再抄一份）。 */
internal object NocturnalLimits {

    /** 单次闸：任何一轮最多 5 条。 */
    const val NIGHTLY_MAX = 5

    /** 硬总闸：滚动 7 天内最多 30 条，满即停（不是日历周）。 */
    const val WEEKLY_MAX = 30

    /**
     * 总闸的窗口（天）。
     *
     * 为什么是**滚动 7 天**而不是「周一到周日」：本层不读时钟，日历周要时区与夏令时；
     * 滚动窗还不能被调用方给错一个「周桶」骗过去（给错桶＝额度无限）。
     * 副作用可接受：连跑 6 晚刚好 30 条，第 7 晚必停——正是单里那句「第 7 晚想跑也没额度」。
     */
    const val WEEK_DAYS = 7

    /** 一条留痕最多带几个证据 id（照勘验层既有的明细上限）。 */
    const val MAX_EVIDENCE = InspectDetailLimits.MAX_EVIDENCE_PER_FINDING

    /** 一条证据引用的长度上限：引用是 `decision:<id>` / `trace:<runId>#<seq>` 这种短形，长的一律钳掉。 */
    const val MAX_EVIDENCE_CHARS = 120
}

/** 一条被检记录（**「条」的定义住在这里**，不散落到调用方）。 */
internal data class PatrolRecord(
    /** 证据 id（**只记引用，不记正文**）。 */
    val id: String,
    /** 计数表用：`decision` ／ `trace`。 */
    val kind: String,
) {
    init {
        require(id.isNotBlank()) { "被检记录必须有证据 id" }
    }

    companion object {
        const val KIND_DECISION = "decision"
        const val KIND_TRACE = "trace"
    }
}

/** 一轮夜巡的留痕（[NocturnalLedger] 的一行）。 */
internal data class NightRound(
    val id: String,
    val atMs: Long,
    /** 「今晚」的桶（调用方给；本层不读时钟，也就没有时区口径）。 */
    val bucket: Long,
    /** 这一轮抽了几条（额度按它扣）。 */
    val inspected: Int,
    /** 命中几条（计数表用，**不是**额度）。 */
    val hits: Int,
    /** 查不了几条（**不进**额度，也不进误报率分母）。 */
    val noData: Int,
    /** 灾异通道那一趟：不占额度，也不受配额闸拦（但受一键停拦）。 */
    val emergency: Boolean,
    val evidence: List<String> = emptyList(),
)

/** 「这趟该不该跑」的判定（[why] 永远有字——不跑也要能一眼看出为什么不跑）。 */
internal data class PatrolGate(val run: Boolean, val why: String, val allowance: Int = 0)

/** 暗卫结果的两条出路（没有第三种：**不进界面**）。 */
internal enum class PatrolDelivery(val wire: String) {
    /** 进延后队列（不打扰）。 */
    QUEUE("queue"),

    /** CRITICAL：上奏候选（走 L21／卷十通道）。 */
    EMERGENCY("emergency"),
}

/**
 * 抽样池的一项：一条被检记录 ＋ 它在判据输入里的**同源键**。
 *
 * [key] 不是另起的名字，是 [NocturnalPatrol.keyOf] 从判据读的那一条对象上算出来的；
 * 抽到的与判据看到的因此能对上（2026-10-29 裁定②：两批东西就算白抽）。
 */
internal data class PatrolSlot(val record: PatrolRecord, val atMs: Long, val key: String)

/** 对账读数：抽了几条、几条在判据的真输入里找得到、哪几条找不到。 */
internal data class PatrolCoverage(val picked: Int, val found: Int, val missing: List<String>) {
    /** 一条不少才算同一批（找不到的＝抽了却没喂给判据）。 */
    val reconciled: Boolean get() = missing.isEmpty()
}

/** 抽中的几条投回判据输入侧的结果（只从判据真读的两个列表里取，一条也不新造）。 */
internal data class PatrolScoped(
    val acts: List<WriteTrace>,
    val rows: List<DecisionRecord>,
    val coverage: PatrolCoverage,
)

internal object NocturnalPatrol {

    fun idOf(atMs: Long): String = "night:$atMs"

    private fun windowMs(): Long = NocturnalLimits.WEEK_DAYS * 24L * 3_600_000L

    /** 滚 7 天窗已用条数（不是日历周；灾异那几趟不占）。 */
    fun windowUsed(rounds: List<NightRound>, nowMs: Long): Int {
        val from = nowMs - windowMs()
        return rounds.asSequence()
            .filter { !it.emergency && it.atMs in from..nowMs }
            .sumOf { it.inspected }
    }

    /** 今晚已用条数（同一桶；灾异那几趟不占）。 */
    fun tonightUsed(rounds: List<NightRound>, bucket: Long): Int =
        rounds.asSequence().filter { !it.emergency && it.bucket == bucket }.sumOf { it.inspected }

    /**
     * 闸门。
     *
     * 顺序就是优先级：**一键停永远第一**（连灾异一起停），然后才是四条前提与两道闸。
     * 灾异通道（[emergency]）跳过配额与充电——它是「漏了会出事」的那一类；
     * 但它**不跳过一键停**：停不掉的那一档等于没停。
     */
    fun gate(
        nowMs: Long,
        bucket: Long,
        rounds: List<NightRound>,
        charging: Boolean,
        stopped: Boolean = false,
        running: Boolean = false,
        emergency: Boolean = false,
    ): PatrolGate {
        if (stopped) return PatrolGate(false, "已一键停（暗卫与灾异通道都停）")
        if (emergency) return PatrolGate(true, "灾异通道：不占额度", NocturnalLimits.NIGHTLY_MAX)
        if (running) return PatrolGate(false, "有任务在跑，今晚不抽")
        if (!charging) return PatrolGate(false, "不在充电：顺延，不叠加")
        val weekUsed = windowUsed(rounds, nowMs)
        if (weekUsed >= NocturnalLimits.WEEKLY_MAX) {
            return PatrolGate(false, "滚 7 天窗已满 ${NocturnalLimits.WEEKLY_MAX} 条，顺延不补")
        }
        val tonight = tonightUsed(rounds, bucket)
        if (tonight >= NocturnalLimits.NIGHTLY_MAX) {
            return PatrolGate(false, "今晚已抽 $tonight 条（单次闸 ${NocturnalLimits.NIGHTLY_MAX}）")
        }
        val allowance = minOf(
            NocturnalLimits.NIGHTLY_MAX - tonight,
            NocturnalLimits.WEEKLY_MAX - weekUsed,
        )
        return PatrolGate(true, "该抽 $allowance 条（滚 7 天已用 $weekUsed／${NocturnalLimits.WEEKLY_MAX}）", allowance)
    }

    /**
     * 这一轮抽哪几条：**随机起点**，不重复。
     *
     * 等距抽样（[InspectSample.spread]）适合「每次巡同一段、可复现」；夜巡要的是
     * 一周下来把整段覆盖到，所以起点随机、条数由 [PatrolGate.allowance] 封顶。
     * [random] 由调用方给，用例才钉得住「起点真的换」。
     */
    fun plan(pool: List<PatrolRecord>, allowance: Int, random: Random): List<PatrolRecord> {
        if (allowance <= 0 || pool.isEmpty()) return emptyList()
        val distinct = pool.distinctBy { it.id }
        if (distinct.size <= allowance) return distinct
        val start = random.nextInt(distinct.size)
        return (distinct.drop(start) + distinct.take(start)).take(allowance)
    }

    /** 抽样（[plan] 的 Slot 版）：抽中哪几条，连它的同源键一起带回来。 */
    fun pick(pool: List<PatrolSlot>, allowance: Int, random: Random): List<PatrolSlot> {
        val byId = pool.distinctBy { it.record.id }.associateBy { it.record.id }
        return plan(byId.values.map { it.record }, allowance, random).mapNotNull { byId[it.id] }
    }

    /** 痕迹行的同源键（`trace:<会话>#<步>`；`WriteTrace` 的 (会话, 步) 就是它的身份）。 */
    fun keyOf(act: WriteTrace): String = "trace:${act.sessionId}#${act.step}"

    /** 账行的同源键（`decision:<id>`，与留痕里的证据引用同一形）。 */
    fun keyOf(row: DecisionRecord): String = "decision:${row.id}"

    /**
     * 从**六项判据读的同一份输入**建池（2026-10-29 裁定②的硬要求）。
     *
     * 调用方必须把喂给 `check(...)` 的那两个列表原样递进来。另抓一份（另写一条 SQL、
     * 换一个窗口）就会变成「抽到的」和「看到的」是两批东西——[scopeTo] 的对账读数会把这种事露出来。
     */
    fun poolOf(ledger: List<DecisionRecord>, acts: List<WriteTrace>): List<PatrolSlot> =
        acts.map { PatrolSlot(PatrolRecord(keyOf(it), PatrolRecord.KIND_TRACE), it.atMs, keyOf(it)) } +
            ledger.map { PatrolSlot(PatrolRecord(keyOf(it), PatrolRecord.KIND_DECISION), it.atMs, keyOf(it)) }

    /**
     * 把抽中的几条**投回判据输入侧**（裁定②）。
     *
     * 两侧处理不同，不是笔误：
     * - **痕迹侧只跑抽中的那几条**：I1／I6 的输入列表缩到 5 条，判的就是这 5 条；
     * - **账本侧不缩**：账本是「对手」，一缩，没抽中的痕迹全成了「有动作无账」的假漏行。
     *   所以账行只核「它在判据读的那份账里」（[PatrolCoverage]），不改给判据的那份。
     *
     * 返回的元素全部取自 [acts]／[ledger] 本人（不按 [PatrolSlot] 另造一份）——
     * 换了对象，就等于又开了第二份真相。
     */
    fun scopeTo(
        picked: List<PatrolSlot>,
        ledger: List<DecisionRecord>,
        acts: List<WriteTrace>,
    ): PatrolScoped {
        val actByKey = acts.associateBy { keyOf(it) }
        val rowByKey = ledger.associateBy { keyOf(it) }
        val scopedActs = picked.mapNotNull { actByKey[it.key] }
        val scopedRows = picked.mapNotNull { rowByKey[it.key] }
        val missing = picked.map { it.key }.filter { it !in actByKey && it !in rowByKey }
        return PatrolScoped(
            acts = scopedActs,
            rows = scopedRows,
            coverage = PatrolCoverage(picked.size, scopedActs.size + scopedRows.size, missing),
        )
    }

    /** 一条意见走哪条路（CRITICAL 才可能上奏；其余进队列）。 */
    fun deliveryOf(opinion: Opinion): PatrolDelivery =
        if (opinion.severity == Severity.CRITICAL) PatrolDelivery.EMERGENCY else PatrolDelivery.QUEUE

    /** 一轮 = 这次抽了哪些、命中几条、归到哪个桶。 */
    fun roundOf(
        atMs: Long,
        bucket: Long,
        inspected: List<PatrolRecord>,
        findings: List<InspectFinding>,
        emergency: Boolean = false,
    ): NightRound {
        val hits = findings.count { it.verdict == InspectVerdict.HIT }
        val noData = findings.count { it.verdict == InspectVerdict.NO_DATA }
        return NightRound(
            id = idOf(atMs),
            atMs = atMs,
            bucket = bucket,
            // 抽了几条照实记（灾异那趟也是真抽了）；「不占额度」由 windowUsed 跳过 emergency 实现，
            // 不靠把数字改小——账上写 0 就是把留痕写好看了。
            inspected = inspected.size,
            hits = hits,
            noData = noData,
            emergency = emergency,
            evidence = inspected.map { InspectText.fit(it.id, NocturnalLimits.MAX_EVIDENCE_CHARS) }
                .take(NocturnalLimits.MAX_EVIDENCE),
        )
    }
}

/**
 * 勘验结论 ⇒ 意见（只出「建议级」，不替御批定严重度）。
 *
 * 两条刻意的缺省：
 * - `severity=MINOR`、`lawRef=NONE` ⇒ [Opinion.suggestionOnly] 恒真（只出意见不打扰）；
 *   「多严重」是御批的活（239 §3 的排序纪律：纠察不替人定严重度），
 *   法条映射是 276／法典的活。CRITICAL 只从 L28 连续不一致那条路来，不从这里造。
 * - 没证据的命中**不出意见**（L14：没有证据的结论不许出）。
 */
internal object PatrolOpinions {

    /** 一句话（≤8 字：`审计漏行×3`）。 */
    fun gistOf(finding: InspectFinding): String {
        val count = if (finding.count > 1) "×${finding.count}" else ""
        return InspectText.fit(finding.check.cn + count, 8)
    }

    fun of(finding: InspectFinding): Opinion? {
        if (finding.verdict != InspectVerdict.HIT) return null
        if (finding.evidence.isEmpty()) return null
        return OpinionAdapter.PATROL.adapt(
            severity = Severity.MINOR,
            gist = gistOf(finding),
            action = ActionCode.REVIEW,
            lawRef = LawRef.NONE,
            evidence = finding.evidence.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING),
        )
    }

    fun ofAll(findings: List<InspectFinding>): List<Opinion> = findings.mapNotNull { of(it) }
}

/** 暗卫台账（`暗卫记录.jsonl`，追加式一行一条；**只记条数与证据引用**）。 */
internal object NocturnalLedger {

    const val FILE_NAME = "暗卫记录.jsonl"

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    @Synchronized
    fun append(globalRoot: File, round: NightRound): Boolean = runCatching {
        val f = fileIn(globalRoot)
        f.parentFile?.mkdirs()
        f.appendText(toJson(round).toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 现读（新→旧；坏行跳过——读不出来的行渲染出来就是假账）。 */
    fun read(globalRoot: File, limit: Int = 200): List<NightRound> = runCatching {
        val f = fileIn(globalRoot)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull { parse(it) }
            .sortedByDescending { it.atMs }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())

    fun toJson(round: NightRound): JSONObject = JSONObject()
        .put("id", round.id)
        .put("at", round.atMs)
        .put("bucket", round.bucket)
        .put("inspected", round.inspected)
        .put("hits", round.hits)
        .put("no_data", round.noData)
        .put("emergency", round.emergency)
        .put("evidence", JSONArray(round.evidence))

    fun parse(raw: String): NightRound? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val a = o.optJSONArray("evidence") ?: JSONArray()
        return NightRound(
            id = id,
            atMs = o.optLong("at", 0L),
            bucket = o.optLong("bucket", 0L),
            inspected = o.optInt("inspected", 0),
            hits = o.optInt("hits", 0),
            noData = o.optInt("no_data", 0),
            emergency = o.optBoolean("emergency", false),
            evidence = (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } },
        )
    }
}
