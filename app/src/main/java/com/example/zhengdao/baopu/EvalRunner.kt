// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百零六批 · 评测器日常化（把 163 的架子变成能跑的）：**改完技能跑一次对拍**。
//
// 一句话形状（206 实现细化 §0，照抄不发明）：
//   fixture（同原话＋初始快照＋同模型参数）
//     ├─ run A：旧 harness（改前技能正文）──┐
//     └─ run B：新 harness（改后技能正文）──┴→ 两张执行轨迹 → 对比表（行为列＋结果列）
//        → ADOPT / DEFER / REJECT → 进化档案 append 一行。
//
// 「只差那一个改动」的机制（复用 163 既有形状，不改 eval/**）：两次跑走 `EvalHarness.runCase`，
// 同一 `EvalCase`（同 prompt＝用户原话、同种子文件、同预算）、同一 `EvalConfig`（同模型/参数/
// 温度），**只差 `persona=C_PROJECT` 的 personaText**——A 用改前技能正文、B 用改后正文。
// `EvalPersona.systemPromptOf` 会把那一段拼成项目层，A 内核照常在场。
//
// 三块（实现细化 §7 的三个纯逻辑件；`EvalRun` 与 `eval/RuntimeEval.EvalRun` 撞名，取 [EvalAttempt]）：
//   · [EvalFixture]：同原话＋关键文件快照哈希＋同模型参数＋预算——快照不一致 ⇒ 不许开跑；
//   · [EvalAttempt]：一轮执行轨迹（步数/工具序列/失败点/耗时/token；失败也算一轮，不许丢弃）；
//   · [EvalCompare]：对比表（行为列＋**结果列分开**）＋结论（纯函数只产 ADOPT/DEFER/REJECT 三值）。
//
// 隔离（细化 §2，全部在形状上钉死）：EvalHarness 每跑自建临时目录撒种（工作区副本），
// 不碰真会话库、不落任何消息——本文件没有 engine/db 的引用，想污染用户会话也没有路；
// 两次跑之间不共享任何状态（两张 CaseResult 各自独立）。
//
// UNRUN（细化 §4）：没 key 时**唯一**合法输出＝整表一行
//   `UNRUN｜原因：…｜时间｜fixture id`；禁止填 0、禁止"预计会更好"、禁止搬历史结论；
// 进化档案也记一条 UNRUN（让"没跑"和"跑坏了"分得清）。
//
// 去重与额度（细化 §5）：同 fixture＋同改动 24 小时内只跑一次；每日 ≤20 次，超了「明天再说」；
// 结论未变不重复写档案。判据全在 [EvalGate]（纯函数）；状态（上次时刻/当日计数）由调用方持有。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.eval.CaseResult
import com.example.zhengdao.baopu.eval.EvalCase
import com.example.zhengdao.baopu.eval.EvalCases
import com.example.zhengdao.baopu.eval.EvalConfig
import com.example.zhengdao.baopu.eval.EvalDeterminism
import com.example.zhengdao.baopu.eval.EvalFile
import com.example.zhengdao.baopu.eval.EvalHarness
import com.example.zhengdao.baopu.eval.RuntimeEval
import com.example.zhengdao.core.RunLog
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ── fixture（实现细化 §1 的形状；不可比就整轮作废）─────────────────────────────

internal data class EvalBudget(
    val maxSteps: Int = 30,
    val timeoutMs: Long = 180_000L,
)

internal data class EvalFixture(
    val id: String,
    val userText: String,
    /** 被对拍的技能名（两次跑只差它的正文）。 */
    val skillName: String,
    /** 改前正文（旧 harness；新技能没有旧版 ⇒ 空串＝「无技能」的基线跑）。 */
    val skillBodyOld: String,
    /** 改后正文（新 harness）。 */
    val skillBodyNew: String,
    /** 初始工作区**关键文件**快照哈希（产物台账＋两层技能账，排序后流式 sha256）。 */
    val workspaceSnapshotSha: String,
    val preconditions: List<String>,
    /** `provider/model@params` 摘要——两次跑必须完全一致，不一致＝不可比。 */
    val model: String,
    val budget: EvalBudget,
    /** 撒进两轮临时工作区的种子文件（改动类任务在工作区**副本**里跑）。 */
    val seedFiles: List<EvalFile>,
    val createdAt: Long,
) {
    /** 两次跑必须同一 fixture 的三件（原话/快照/模型）＋只差技能正文这一件。 */
    fun comparableWith(other: EvalFixture): Boolean =
        userText == other.userText &&
            workspaceSnapshotSha == other.workspaceSnapshotSha &&
            model == other.model &&
            budget == other.budget &&
            skillName == other.skillName
}

internal object EvalSnapshot {

    /**
     * 关键文件快照（**流式** sha256，大文件不进内存；JDK 原语与 `FaceIndex.sha256` 同款）。
     * 关键文件集合＝产物台账＋两层技能目录的正文与变更账——模型行为依赖的就是这些；
     * 算法字节稳定：按绝对路径排序后逐个 `路径:内容哈希` 拼接再哈希。
     */
    fun of(wsRoot: File, globalRoot: File): String {
        val files = mutableListOf<File>()
        File(MemoryLayout.productsDir(wsRoot), ReflectionRunner.PRODUCT_LEDGER_NAME)
            .let { if (it.isFile) files += it }
        for (dir in listOf(SkillRules.projectSkillDir(wsRoot), SkillRules.globalSkillDir(globalRoot))) {
            File(dir, SkillAudit.FILE_NAME).let { if (it.isFile) files += it }
            dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { sub ->
                File(sub, SkillRules.SKILL_FILE).let { if (it.isFile) files += it }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (f in files.sortedBy { it.absolutePath }) {
            digest.update(f.relativeToOrNull(wsRoot)?.path?.replace('\\', '/')?.toByteArray(Charsets.UTF_8)
                ?: f.absolutePath.toByteArray(Charsets.UTF_8))
            digest.update(":".toByteArray())
            digest.update(SkillAudit.shaOf(f).toByteArray())
            digest.update("\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}

// ── 一轮执行轨迹（细化 §3 的行；失败/超时也算一轮，不许丢弃）────────────────────

internal data class EvalAttempt(
    /** ok=false ＝ 失败/超时/跑崩——照记，不许丢弃（负例⑦）。 */
    val ok: Boolean,
    val steps: Int,
    val toolCalls: List<String>,
    /** 失败点：`第 N 步｜工具｜错`（没失败 ⇒ null）。 */
    val failurePoint: String?,
    val elapsedMs: Long,
    val tokensIn: Int,
    val tokensOut: Int,
    /** 本轮临时工作区的文件清单（`名:sha12`，含种子现状与 spill；结果列的原料）。 */
    val products: List<String>,
    val error: String? = null,
)

internal object EvalAttemptReader {

    /** `CaseResult` ⇒ [EvalAttempt]（计时由调用方包住 `runCase`；文件清单现读临时目录）。 */
    fun of(result: CaseResult, elapsedMs: Long): EvalAttempt {
        val dir = result.dir
        val products = buildList {
            if (dir != null && dir.isDirectory) {
                dir.listFiles()?.filter { it.isFile }?.forEach { add("${it.name}:${SkillAudit.shaOf(it)}") }
                File(dir, ".baopu-spill").listFiles()?.filter { it.isFile }?.forEach { add("spill/${it.name}:${SkillAudit.shaOf(it)}") }
            }
        }.sorted()
        val failure = result.error ?: result.failures.firstOrNull()
        val failurePoint = failure?.let {
            "第 ${result.steps} 步｜${result.toolCalls.lastOrNull() ?: "-"}｜${it.take(80)}"
        }
        return EvalAttempt(
            ok = result.ok,
            steps = result.steps,
            toolCalls = result.toolCalls,
            failurePoint = failurePoint,
            elapsedMs = elapsedMs,
            tokensIn = result.tokensIn,
            tokensOut = result.tokensOut,
            products = products,
            error = result.error,
        )
    }
}

// ── 对比表＋结论（纯函数；行为列与结果列分开，负例③的闸在这）────────────────────

/** 结论只有三个值（负例③：OPEN/GOOD 之类越界值从类型上就不存在）。 */
internal enum class EvalVerdict { ADOPT, DEFER, REJECT }

internal data class EvalCompare(
    val fixtureId: String,
    val skillName: String,
    val old: EvalAttempt,
    val new: EvalAttempt,
    /** 行为差异（自动 diff：工具换了/步数少了/多问一次）。 */
    val behaviorDiff: List<String>,
    /** 结果差异（**客观判据**：成败/失败点/产物清单 diff；判不了的写「需人工判定」）。 */
    val resultDiff: List<String>,
    val verdict: EvalVerdict,
    val reason: String,
)

internal object EvalCompareRules {

    /**
     * 行为差异（**只描述，不判结论**——负例③「只比行为列就判 ADOPT」的闸：
     * [verdictOf] 根本不收这个参数）。
     */
    fun behaviorDiff(old: EvalAttempt, new: EvalAttempt): List<String> {
        val out = mutableListOf<String>()
        if (old.toolCalls != new.toolCalls) {
            val firstDiff = old.toolCalls.zip(new.toolCalls).firstOrNull { it.first != it.second }
            out += when {
                firstDiff != null -> "工具换了：第 ${old.toolCalls.indexOf(firstDiff.first) + 1} 步 ${firstDiff.first}→${firstDiff.second}"
                old.toolCalls.size > new.toolCalls.size ->
                    "步骤少了：${old.toolCalls.size}→${new.toolCalls.size}"
                else -> "步骤多了：${old.toolCalls.size}→${new.toolCalls.size}"
            }
        }
        if (old.failurePoint != new.failurePoint) out += "失败点变了"
        return out
    }

    /** 结果差异（**客观判据**：成败/失败点/产物清单；机器判不了的不含糊，列出来供人工）。 */
    fun resultDiff(old: EvalAttempt, new: EvalAttempt): List<String> {
        val out = mutableListOf<String>()
        if (old.ok != new.ok) {
            out += if (new.ok) "结果变好：旧跑失败（${old.failurePoint ?: "?"}）、新跑通过" else "结果变差：旧跑通过、新跑失败（${new.failurePoint ?: "?"}）"
        } else if (!old.ok && old.failurePoint != new.failurePoint) {
            out += "两轮都失败、失败点不同：旧 ${old.failurePoint} → 新 ${new.failurePoint}（需人工判定）"
        }
        if (old.products != new.products) {
            val onlyOld = old.products - new.products.toSet()
            val onlyNew = new.products - old.products.toSet()
            if (onlyOld.isNotEmpty()) out += "产物少了：${onlyOld.take(3).joinToString("、")}"
            if (onlyNew.isNotEmpty()) out += "产物多了：${onlyNew.take(3).joinToString("、")}"
        }
        return out
    }

    /**
     * 结论（**纯函数；签名里没有行为差异，函数体也不读工具序列**——负例③的执行点：
     * 行为变了不构成 ADOPT/REJECT 的依据，步数变少但产物错了 ⇒ REJECT，不许写"更高效"）：
     * - 旧挂新过 ⇒ ADOPT（结果变好，有客观判据）；
     * - 旧过新挂 ⇒ REJECT（改坏了）；
     * - 其余（结果无可判差异，含两轮全同）⇒ DEFER——行为变了≠变好，不许下 ADOPT。
     */
    fun verdictOf(resultDiff: List<String>, old: EvalAttempt, new: EvalAttempt): Pair<EvalVerdict, String> = when {
        !old.ok && new.ok -> EvalVerdict.ADOPT to "结果变好：旧跑失败、新跑通过"
        old.ok && !new.ok -> EvalVerdict.REJECT to "结果变差：新跑失败（${new.failurePoint ?: "未知"}），行为再高效也不采纳"
        resultDiff.isEmpty() -> EvalVerdict.DEFER to "两轮无可判差异——这次改动没起作用"
        else -> EvalVerdict.DEFER to "行为变了但结果没有可判变好——需人工判定（行为≠变好）"
    }

    fun compare(fixture: EvalFixture, old: EvalAttempt, new: EvalAttempt): EvalCompare {
        val result = resultDiff(old, new)
        val (v, why) = verdictOf(result, old, new)
        return EvalCompare(
            fixtureId = fixture.id,
            skillName = fixture.skillName,
            old = old,
            new = new,
            behaviorDiff = behaviorDiff(old, new),
            resultDiff = result,
            verdict = v,
            reason = why,
        )
    }

    /** 一轮的可读行（固定列齐全——缺列＝未完成）。 */
    fun rowOf(a: EvalAttempt): String =
        "ok=${a.ok}｜步数 ${a.steps}｜工具 ${a.toolCalls.joinToString("→").ifEmpty { "无" }}" +
            "｜失败 ${a.failurePoint ?: "无"}｜${a.elapsedMs} ms｜token ${a.tokensIn}+${a.tokensOut}" +
            "｜产物 ${a.products.take(3).joinToString("、").ifEmpty { "无" }}"

    /** 对比表（渲染；固定列齐全）。 */
    fun table(c: EvalCompare): String = buildString {
        appendLine("对拍 ${c.skillName}（fixture ${c.fixtureId}）｜旧 vs 新 harness")
        appendLine("旧：${rowOf(c.old)}")
        appendLine("新：${rowOf(c.new)}")
        appendLine("行为差异：${c.behaviorDiff.ifEmpty { listOf("无") }.joinToString("；")}")
        appendLine("结果差异：${c.resultDiff.ifEmpty { listOf("无") }.joinToString("；")}")
        append("结论：${c.verdict.name}——${c.reason}")
    }

    /** UNRUN 的唯一合法形状（细化 §4；不许填 0、不许预计、不许搬历史）。 */
    fun unrunLine(reason: String, nowMs: Long, fixtureId: String): String =
        "UNRUN｜原因：$reason｜${Instant.ofEpochMilli(nowMs)}｜$fixtureId"
}

// ── 去重与额度（纯判据；状态由调用方持有）────────────────────────────────────

internal object EvalGate {

    const val DEDUP_WINDOW_MS = 24L * 60 * 60 * 1000
    const val DAILY_QUOTA = 20

    /** 改动指纹键：fixture id ＋ 技能名 ＋ 新正文 sha12（「同 fixture＋同改动」）。 */
    fun changeKey(fixture: EvalFixture): String =
        "evalrun:${fixture.id}:${fixture.skillName}:${digestOf(fixture.skillBodyNew)}"

    fun digestOf(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)

    /** 24 小时内跑过 ⇒ 压（负例⑥：不许重复烧额度）。 */
    fun dedupBlocked(lastRunMs: Long, nowMs: Long): Boolean =
        lastRunMs > 0 && nowMs - lastRunMs < DEDUP_WINDOW_MS

    /** 当日已跑满 ⇒ 压（负例⑧：超每日上限仍继续 ⇒ 红）。 */
    fun quotaBlocked(runsToday: Int): Boolean = runsToday >= DAILY_QUOTA

    /** 结论未变不重复写档案：同 fixture 同技能已有同结论行 ⇒ 不再 append。 */
    fun archiveNeedsAppend(existingText: String?, fixtureId: String, skillName: String, verdict: String): Boolean {
        if (existingText.isNullOrBlank()) return true
        return existingText.lineSequence().none { line ->
            line.startsWith("|") && line.contains("fixture:$fixtureId") &&
                line.contains("| $skillName |") && line.contains("| $verdict")
        }
    }

    /** 档案一行（技能/时间/结论/读数；对拍行与 205 的候选行同表不同「类型」——解析互不咬）。 */
    fun archiveLineOf(fixtureId: String, skillName: String, verdict: String, reason: String, readout: String, now: LocalDate): String =
        "| $now | 对拍 | $skillName | $verdict——$reason | fixture:$fixtureId 读数:$readout | $verdict |"

    /** UNRUN 也进档案（细化 §4：不是空白）。 */
    fun unrunArchiveLine(fixtureId: String, skillName: String, reason: String, now: LocalDate): String =
        "| $now | 对拍 | $skillName | UNRUN——$reason | fixture:$fixtureId 读数:无 | UNRUN |"
}

// ── IO 编排（provider/状态由调用方给；本文件不碰 Context/库/真会话）──────────────

/** 一次对拍的产出：能跑 ⇒ 对比表；没 key ⇒ UNRUN 行。两种都进进化档案。 */
internal sealed interface EvalOutcome {
    /** 双跑完成（两次都算数，失败轮也是结果）。 */
    data class Ran(val compare: EvalCompare, val table: String) : EvalOutcome

    /** 没 key／通道不可用：唯一合法输出（细化 §4）。 */
    data class NotRun(val line: String, val reason: String) : EvalOutcome

    /** 快照不一致／去重／额度：整轮作废，不许出结论。 */
    data class Invalid(val reason: String) : EvalOutcome
}

internal class EvalRunner(
    private val wsRoot: File,
    private val globalRoot: File,
    private val log: (String) -> Unit = { RunLog.log(it) },
    /** 双跑的执行口（默认真 harness；测试注入假结果，好断言「UNRUN 时不许开跑」）。 */
    private val runCase: (EvalCase, EvalConfig) -> CaseResult = { c, cfg -> EvalHarness.runCase(c, cfg) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 构造 fixture（跑前一刻）：用户原话＋技能新旧正文＋快照＋模型摘要。
     * 快照在构造时算一次——两次跑**必须**用这一份；跑前再校验一次（见 [runOnce]）。
     */
    fun fixtureOf(
        skillName: String,
        userText: String,
        skillBodyOld: String,
        skillBodyNew: String,
        modelSummary: String,
        seedFiles: List<EvalFile> = emptyList(),
        budget: EvalBudget = EvalBudget(),
    ): EvalFixture = EvalFixture(
        id = "eval-${LocalDate.ofInstant(Instant.ofEpochMilli(clock()), ZoneId.systemDefault())}-${EvalGate.digestOf("$skillName$userText").take(6)}",
        userText = userText,
        skillName = skillName,
        skillBodyOld = skillBodyOld,
        skillBodyNew = skillBodyNew,
        workspaceSnapshotSha = EvalSnapshot.of(wsRoot, globalRoot),
        preconditions = emptyList(),
        model = modelSummary,
        budget = budget,
        seedFiles = seedFiles,
        createdAt = clock(),
    )

    /**
     * 对拍一次（细化 §0）：先跑 baseline（旧正文），再跑 candidate（新正文），
     * 两次同一 fixture；失败/超时也算一轮；**只差 personaText 那一段**。
     *
     * @param provider 当前通道（null＝没启用通道 ⇒ UNRUN）
     * @param lastRunMs 同键上次跑的时刻（去重；SP 由调用方读写）
     * @param runsToday 当日已跑次数（额度；SP 由调用方读写）
     */
    fun runOnce(
        fixture: EvalFixture,
        provider: com.example.zhengdao.baopu.Provider?,
        lastRunMs: Long,
        runsToday: Int,
    ): EvalOutcome {
        // 负例⑧：超每日上限仍继续 ⇒ 压（「明天再说」）
        if (EvalGate.quotaBlocked(runsToday)) {
            return EvalOutcome.Invalid("今日对拍已满 ${EvalGate.DAILY_QUOTA} 次，明天再说")
        }
        // 负例⑥：24h 内同 fixture＋同改动重复跑 ⇒ 压
        if (EvalGate.dedupBlocked(lastRunMs, clock())) {
            return EvalOutcome.Invalid("同 fixture＋同改动 24 小时内已跑过，明天再试")
        }
        // UNRUN：没 key／免费档／协议不符 ⇒ 唯一合法输出（负例④：绝不开跑、绝不编数）
        // ⚠️ 判序别合写：`provider?.let{...} ?: 兜底` 会把「通道可用（unusableReason=null）」
        // 误判成没 key（let-null 陷阱）——分两步判，各回各的 UNRUN 原因。
        if (provider == null) {
            val line = EvalCompareRules.unrunLine("未配置模型密钥（当前没有启用通道）", clock(), fixture.id)
            log("对拍：$line")
            return EvalOutcome.NotRun(line, "未配置模型密钥（当前没有启用通道）")
        }
        val unusable = RuntimeEval.unusableReason(provider)
        if (unusable != null) {
            val line = EvalCompareRules.unrunLine(unusable, clock(), fixture.id)
            log("对拍：$line")
            return EvalOutcome.NotRun(line, unusable)
        }
        // 快照校验（负例①：环境变了还出结论 ⇒ 红）：跑前再算一次，与 fixture 对不上 ⇒ 作废
        val nowSha = EvalSnapshot.of(wsRoot, globalRoot)
        if (nowSha != fixture.workspaceSnapshotSha) {
            return EvalOutcome.Invalid("环境已变，重建 fixture（快照 ${fixture.workspaceSnapshotSha} → $nowSha）")
        }
        val cfg = EvalConfig(
            apiKey = provider.apiKey,
            model = provider.model,
            temperature = EvalDeterminism.EVAL_TEMPERATURE,
            seed = null,
            timeoutSeconds = (fixture.budget.timeoutMs / 1000).coerceAtLeast(30),
            maxOutputTokens = 2_048,
            providerName = provider.id,
        )
        // 隔离：两次跑同一形状的 EvalCase、各自独立临时目录、不碰真会话——
        // 「只差那一个改动」＝personaText 一段（A=旧正文 / B=新正文）。
        val startedA = clock()
        val attemptA = EvalAttemptReader.of(runCase(caseOf(fixture, fixture.skillBodyOld, "old"), cfg), clock() - startedA)
        val startedB = clock()
        val attemptB = EvalAttemptReader.of(runCase(caseOf(fixture, fixture.skillBodyNew, "new"), cfg), clock() - startedB)
        val compare = EvalCompareRules.compare(fixture, attemptA, attemptB)
        return EvalOutcome.Ran(compare, EvalCompareRules.table(compare))
    }

    private fun caseOf(fixture: EvalFixture, skillBody: String, tag: String): EvalCase =
        EvalCase(
            id = "${fixture.id}-$tag",
            note = "206 对拍：${fixture.skillName}",
            prompt = fixture.userText,
            maxSteps = fixture.budget.maxSteps,
            files = fixture.seedFiles,
            asserts = emptyList(),
            persona = EvalCases.C_PROJECT,
            // 只差这一段：A=旧正文、B=新正文（EvalPersona 把它当项目层拼进系统提示）
            personaText = skillBody,
        )
}
