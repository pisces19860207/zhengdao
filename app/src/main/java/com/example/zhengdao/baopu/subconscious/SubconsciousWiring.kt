// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十六批 · 下意识执行器的**生产接线**（判据一行不落在这里）。
//
// 三条纪律（照 635 `ClaimEvidenceWiring` 的房型）：
// 1. **对被读的东西只读**：项目账（`LedgerStore.items`）只读当候选输入；
//    自身产出只写自己的 `回响.jsonl`（起居注**同形** ACTION 行）与一个
//    一行状态文件 `回响-状态.json`（天闸的 last-run 桶——不是 ACTION 行，
//    不掺进行数对账）。504/469 判据①「每次自动动作一行」的唯一写口仍在
//    `GuardPipeline.finish`，本单**不碰** `SupervisionDiary`。
// 2. **不执行、不改档**（判据⑥）：本文件对项目账没有任何写调用
//    （`LedgerStore.append/moveTo/recordDone/archive` 一个都不出现——源码门禁
//    `SubconsciousGateTest` 盯着全包）；模型那条路也只收 id 列表。
// 3. **崩了只记一行**：收工触发不许把会话收尾带下去（65/89 口径），
//    模型不可用 ⇒ 退回规则版（判据④：不崩、不空转——没有重试）。
package com.example.zhengdao.baopu.subconscious

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.CompletionResult
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.agent.QuietGrant
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.ledger.LedgerItem
import com.example.zhengdao.baopu.ledger.LedgerState
import com.example.zhengdao.baopu.ledger.LedgerStore
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.core.RunLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

/** 回响账（自身产出；与被读的项目账、起居注三本互不改写）。 */
internal const val ECHO_LEDGER_FILE = "回响.jsonl"

/** 天闸的 last-run 桶住的状态文件（不是账、不是 ACTION 行——一行 JSON）。 */
internal const val ECHO_STATE_FILE = "回响-状态.json"

/** 模型挑选的超时（一次请求、砍得动；超时＝不可用 ⇒ 规则版）。 */
internal const val ECHO_MODEL_TIMEOUT_MS = 20_000L

/** 项目账上右相结论的「FAIL」字面（闭集 PASS/FAIL/UNVERIFIABLE，见 `LedgerRules` §verdict KDoc）。 */
internal const val ECHO_VERDICT_FAIL = "FAIL"

/** 挑选请求的系统句（模型只许挑、不许造——口径一句话说死）。 */
private const val ECHO_PICK_SYSTEM =
    "你是挑选器不是创作器：只能从给定的候选里挑 id，一个 id 都不许自己发明。"

/** 挑选请求的问题句。 */
private const val ECHO_PICK_ASK =
    "这些是用户账上值得想起的事（id｜标题）。挑最值得提醒的至多 3 条，每行一个 id，别的什么都不要写："

/** 挑选回复的 token 上限（只要 id，不需要长篇）。 */
private const val ECHO_PICK_MAX_TOKENS = 120

/**
 * 候选挑选（625 §一①的三类来源 ⇒ [SubconsciousCandidate]；纯函数，JVM 直测）。
 *
 * 同一条账行只会命中一类（账状态机保证：FAIL ⇒ 待验收、在飞 ⇒ 没验过、
 * 冻结/撤 ⇒ 不在飞），候选 id 稳定 ＝ `echo:<账行id>`——同一条账一行一候选。
 * 已归档的行不回响（用户已经收拾过它了）。
 */
internal fun echoCandidatesOf(items: List<LedgerItem>, nowMs: Long): List<SubconsciousCandidate> =
    items.filter { it.archivedAtMs == 0L }.mapNotNull { item ->
        val kind = when {
            item.verdict == ECHO_VERDICT_FAIL &&
                nowMs - item.verifiedAtMs in 1..EchoLimits.FAIL_RECENT_MS -> EchoKind.FAIL_RECENT

            item.status == LedgerState.FLYING &&
                nowMs - item.createdAtMs in EchoLimits.STEP_STALE_MS..EchoLimits.STEP_TOO_OLD_MS ->
                EchoKind.STEP_UNFINISHED

            item.sourceMsgId.isNotEmpty() &&
                (item.status == LedgerState.FROZEN || item.status == LedgerState.WITHDRAWN) &&
                nowMs - item.createdAtMs in 1..EchoLimits.MENTIONED_RECENT_MS -> EchoKind.MENTIONED_NOT_DONE

            else -> null
        } ?: return@mapNotNull null
        SubconsciousCandidate(
            id = "echo:${item.id}",
            kind = kind,
            title = item.title,
            ledgerId = item.id,
            atMs = item.createdAtMs,
            verdictOfLedger = item.verdict,
        )
    }

/** 钟点换算（本地时区；与 635 `hourOfDayOf` 同一套日历口径——钟点是给 [SessionShape] 的）。 */
internal fun echoHourOfDay(nowMs: Long): Int =
    Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour

/**
 * 下意识执行器的生产装配（收工触发；调用方在 IO 上串行驱动）。
 *
 * @return 这轮真跑了吗（过了天闸且左相放行 ⇒ 非 null，可能 0 信号）；
 *         null ＝ 这趟什么都没发生（没账根／今天已跑过／免打扰延后）
 */
internal fun runSubconsciousEcho(
    ctx: Context,
    nowMs: Long,
    grant: QuietGrant?,
    picker: EchoPicker?,
): EchoOutcome? {
    val root = DecisionLedger.installedRoot() ?: run {
        RunLog.log("回响：账根没装配，这轮不跑（不是没账行，是没地方落自己的账）")
        return null
    }
    val lastRunDayKey = readEchoState(root)
        ?.optLong("lastRunDayKey", -1L)
        ?.takeIf { it >= 0 }
    val outcome = SubconsciousRunner.run(
        candidates = echoCandidatesOf(LedgerStore.items(BaopuRuntime.workspaceRoot(ctx)), nowMs),
        nowMs = nowMs,
        grant = grant,
        shape = SessionShape(hourOfDay = echoHourOfDay(nowMs)),
        lastRunDayKey = lastRunDayKey,
        suggestedAtByKey = suggestedAtByKey(root),
        picker = picker,
    )
    if (!outcome.ran) {
        RunLog.log("回响：这轮没跑（${outcome.skipWhy}）")
        return null
    }
    // 自身产出可写：信号逐条落自己的账（0 信号 ⇒ 0 行——行数判据结构保证）。
    outcome.actionRows.forEach { row ->
        runCatching { echoLedgerFile(root).appendText(DiaryCodec.encode(row) + "\n") }
            .onFailure { RunLog.log("回响：ACTION 行没落上——${it.javaClass.simpleName}") }
    }
    writeEchoState(root, outcome, nowMs)
    SubconsciousRowStore.publish(outcome.signals)
    RunLog.log(
        "回响：本轮 ${if (outcome.modelPicked) "模型挑" else "规则版"}浮 ${outcome.signals.size} 枚" +
            outcome.signals.take(1).joinToString("") { "（${it.kind.label}·${it.ledgerId}）" },
    )
    return outcome
}

/** 本单账的落点（memoryRoot；与 635 `对账结果.jsonl` 同一层，互不相干）。 */
internal fun echoLedgerFile(root: File): File = File(root, ECHO_LEDGER_FILE)

/** 天闸状态文件（一行 JSON；坏文件当没跑过——重跑一轮比丢一轮便宜）。 */
internal fun echoStateFile(root: File): File = File(root, ECHO_STATE_FILE)

/** 读 last-run 桶（坏文件/不存在 ⇒ null＝从没跑过）。 */
internal fun readEchoState(root: File): JSONObject? = runCatching {
    val f = echoStateFile(root)
    if (!f.isFile) return null
    JSONObject(f.readText(Charsets.UTF_8))
}.getOrNull()

/** 写 last-run 桶（唯一写口；失败只记日志——下轮顶多再跑一次，不致命）。 */
internal fun writeEchoState(root: File, outcome: EchoOutcome, nowMs: Long) {
    runCatching {
        val o = JSONObject()
            .put("v", 1)
            .put("lastRunDayKey", outcome.dayKey)
            .put("at", nowMs)
            .put("modelPicked", outcome.modelPicked)
        echoStateFile(root).writeText(o.toString(), Charsets.UTF_8)
    }.onFailure { RunLog.log("回响：状态没落上——${it.javaClass.simpleName}") }
}

/**
 * 各账行上次被浮出的时刻（右相冷却的输入；从自己的账读回，坏行跳过）。
 * 行的依据格 ＝ `676 回响 · echo:<账行id>`（[SubconsciousRunner.ACTION_WHY]）。
 */
internal fun suggestedAtByKey(root: File): Map<String, Long> {
    val f = echoLedgerFile(root)
    if (!f.isFile) return emptyMap()
    return runCatching {
        f.readLines(Charsets.UTF_8)
            .mapNotNull(DiaryCodec::parse)
            .filter { it.kind == DiaryKind.ACTION && it.why.startsWith(SubconsciousRunner.ACTION_WHY) }
            .groupBy { it.why.substringAfterLast('·').trim().removePrefix("echo:") }
            .mapValues { (_, rows) -> rows.maxOf { it.atMs } }
    }.getOrDefault(emptyMap())
}

/**
 * 生产模型挑选口（「选不造」；判据④⑤）。没配通道/没钥匙 ⇒ null（规则版，
 * 连请求都不发——模型不可用不是错误，是另一条路）；请求失败/解析不出 ⇒ null。
 */
internal fun echoModelPickerOrNull(ctx: Context): EchoPicker? {
    val provider = runCatching { ProviderPrefs.current(ctx) }.getOrNull() ?: return null
    if (provider.apiKey.isBlank()) return null
    return EchoPicker { pool ->
        if (pool.isEmpty()) return@EchoPicker null
        val listing = pool.joinToString("\n") { "${it.id}｜${it.title}" }
        val messages = listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, ECHO_PICK_SYSTEM),
            LlmMessage(LlmMessage.ROLE_USER, "$ECHO_PICK_ASK\n$listing"),
        )
        when (val r = LlmClient(provider).completeOnce(
            messages,
            maxTokens = ECHO_PICK_MAX_TOKENS,
            temperature = 0.0,
            timeoutMs = ECHO_MODEL_TIMEOUT_MS,
        )) {
            is CompletionResult.Ok -> parseModelPicks(r.text, pool)
            else -> null
        }
    }
}

/** 收工触发的那一行（`TriggerWiring` 调；免打扰授权从进程级引擎快照读，只读不写）。 */
internal fun subconsciousOnRunSettled(ctx: Context, nowMs: Long): EchoOutcome? = runSubconsciousEcho(
    ctx = ctx,
    nowMs = nowMs,
    grant = BaopuRuntime.engineIfBuilt()?.quietGrantSnapshot(),
    picker = echoModelPickerOrNull(ctx),
)

/**
 * 挑选回复的解析（纯函数）：模型回的 id 不论带不带 `echo:` 前缀都先归一，
 * 凡是候选集里没有的一律扔——选不造（判据②的结构保证）。一个合法 id 都没有 ⇒ null。
 */
internal fun parseModelPicks(text: String, pool: List<SubconsciousCandidate>): List<String>? {
    val valid = pool.map { it.id }.toSet()
    val ids = text.split('\n', ' ', '，', ',', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { if (it.startsWith("echo:")) it else "echo:$it" }
        .filter { it in valid }
    return ids.ifEmpty { null }
}
