// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十三批 · 金吾卫采样——过程监控的**触发式**采样（不扫全量）。
//
// 背景（派工单 313）：只看最终结果时工具误用查不出来。金吾卫原有职责是用户点名的
// 全量巡检（GoldenGuard.sweep）；本批给它加一条**旁路**：每步工具动作喂进来，
// 四个触发条件命中才采样出 GapItem，没命中只记步——
//   T1 参数与 schema 不符（结果码 SCHEMA_INVALID）；
//   T2 同一动作重复失败 ≥2（同一 tool+参数摘要再败）；
//   T3 计划项跳号（上一步 plan:<n>，这一步直接 plan:<n+k>，k＞1）；
//   T4 工具与任务类型明显不匹配（只读任务里动了写工具；本批的闭集规则，其余形状后批再收）。
//
// 采样内容（判据⑤，源级门禁钉）：最近 N 步的**工具名＋参数摘要＋结果码**——
// 没有对话正文，也没有承载正文的字段（[ToolStep] 字段集封死，想带也带不进来）。
// 参数摘要唯一口径 [argsDigestOf]：键名列表＋规范化串的哈希前 16 位，值原文进哈希不进文案。
//
// 纯观察：不写盘、不弹卡、不占御案额度（inspect 只读门禁照扫本文件）；
// 产出的 GapItem 交给 GapFeedback.record（回灌归右相那条线）。
// 喂入口（引擎每步调 observe）是接线留白——GuardPipeline 在 292-P1 名下，本批不碰。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.verify.GapItem
import com.example.zhengdao.baopu.verify.GapKind
import com.example.zhengdao.baopu.verify.GapSource
import java.security.MessageDigest
import org.json.JSONObject

/** 一步工具动作的过程元数据（只有 id／名字／摘要／结果码——正文进不来，见源级门禁）。 */
internal data class ToolStep(
    val atMs: Long,
    val callId: String,
    val tool: String,
    val argsDigest: String,
    val resultCode: StepResultCode,
    val planStep: Int? = null,
    val writeTool: Boolean = false,
    val taskReadOnly: Boolean = false,
)

/** 结果码（闭集；喂入口从管线 Result 映射——接线留白）。 */
internal enum class StepResultCode(val wire: String) {
    DONE("done"),
    FAILED("failed"),
    REJECTED("rejected"),
    NEEDS_CONFIRM("needs_confirm"),
    SCHEMA_INVALID("schema_invalid"),
}

internal object JinwuPatrol {

    /** 采样窗口：触发时带最近多少步的元数据（派工单「最近 N 步」）。 */
    const val SAMPLE_WINDOW = 8

    /** 重复失败线：同一动作（tool+参数摘要）败到第几次算「重复失败」。 */
    const val REPEAT_FAIL_MIN = 2

    private const val DIGEST_CHARS = 16

    /** 环形步账（只留窗口内；进程态，跨进程续跑归接线批次）。 */
    private val steps = ArrayDeque<ToolStep>()

    /** 同一动作的失败计数（tool|argsDigest → 次数）。 */
    private val failCounts = LinkedHashMap<String, Int>()

    /** 最近一次带计划项的步（跳号检测用）。 */
    private var lastPlanStep: Int? = null

    /**
     * 参数摘要（唯一口径）：顶层键名（排序）＋「键=值」规范化串的 sha256 前 16 位。
     * 同一份参数永远同一个摘要（键序无关），值原文只进哈希、不进任何文案。
     */
    fun argsDigestOf(args: JSONObject): String {
        val keys = args.names()
            ?.let { names -> (0 until names.length()).map { names.getString(it) } }
            ?: emptyList()
        val canonical = keys.joinToString("|") { key -> "$key=${args.opt(key)}" }
        return "${keys.joinToString("+")}:${sha256(canonical).take(DIGEST_CHARS)}"
    }

    /**
     * 喂一步，命中触发才采样（判据①的四触发；没命中只记步——全量扫描是负例①）。
     * @return 本步触发的差距（0～多条；交给 GapFeedback.record）
     */
    fun observe(step: ToolStep): List<GapItem> = synchronized(this) {
        steps.addLast(step)
        while (steps.size > SAMPLE_WINDOW) steps.removeFirst()
        val gaps = mutableListOf<GapItem>()
        gaps += schemaTrigger(step)
        gaps += repeatFailTrigger(step)
        gaps += planSkipTrigger(step)
        gaps += mismatchTrigger(step)
        gaps
    }

    /** 直测收尾（生产不调：进程态）。 */
    fun resetForTest() = synchronized(this) {
        steps.clear()
        failCounts.clear()
        lastPlanStep = null
    }

    // ── 四触发（判据①，各配一测） ────────────────────────────────────────────

    /** T1：参数与 schema 不符（喂入口在 Schema 校验失败时标 SCHEMA_INVALID）。 */
    private fun schemaTrigger(step: ToolStep): List<GapItem> {
        if (step.resultCode != StepResultCode.SCHEMA_INVALID) return emptyList()
        return listOf(
            GapItem(
                source = GapSource.JINWU,
                kind = GapKind.SCHEMA_VIOLATION,
                target = "action:${step.callId}",
                evidence = sampleLines() + "触发:参数与 schema 不符 tool=${step.tool}",
                minimalFix = "按 schema 补齐参数再调",
            ),
        )
    }

    /** T2：同一动作重复失败 ≥2（target 用 tool+参数摘要——retries 合并进同项，attempts 才累得起来）。 */
    private fun repeatFailTrigger(step: ToolStep): List<GapItem> {
        if (step.resultCode != StepResultCode.FAILED) return emptyList()
        val key = "${step.tool}|${step.argsDigest}"
        val count = (failCounts[key] ?: 0) + 1
        failCounts[key] = count
        if (count < REPEAT_FAIL_MIN) return emptyList()
        return listOf(
            GapItem(
                source = GapSource.JINWU,
                kind = GapKind.TOOL_MISUSE,
                target = "action:${step.tool}:${step.argsDigest}",
                evidence = sampleLines() + "触发:同一动作重复失败第 $count 次",
                minimalFix = "先查明这个动作反复失败的原因，再换做法",
            ),
        )
    }

    /** T3：计划项跳号（plan:<prev> 直接跳到 plan:<cur>，中间项一个 GapItem 一个）。 */
    private fun planSkipTrigger(step: ToolStep): List<GapItem> {
        val current = step.planStep ?: return emptyList()
        val previous = lastPlanStep
        lastPlanStep = current
        val previousConfirmed = previous ?: return emptyList()
        if (current <= previousConfirmed + 1) return emptyList()
        return (previousConfirmed + 1 until current).map { skipped ->
            GapItem(
                source = GapSource.JINWU,
                kind = GapKind.GOAL_DRIFT,
                target = "plan:$skipped",
                evidence = sampleLines() + "触发:计划项跳号 plan:$previousConfirmed→plan:$current，plan:$skipped 没做",
                minimalFix = "先补 plan:$skipped 这一步再继续",
            )
        }
    }

    /** T4：只读任务里动了写工具（本批的「明显不匹配」闭集规则）。 */
    private fun mismatchTrigger(step: ToolStep): List<GapItem> {
        if (!step.writeTool || !step.taskReadOnly) return emptyList()
        return listOf(
            GapItem(
                source = GapSource.JINWU,
                kind = GapKind.TOOL_MISUSE,
                target = "action:${step.callId}",
                evidence = sampleLines() + "触发:只读任务里动了写工具 tool=${step.tool}",
                minimalFix = "这步从只读任务里收回，或把任务改判为可写再继续",
            ),
        )
    }

    /** 采样窗口（判据⑤：工具名＋参数摘要＋结果码，一行一步；没有承载正文的字段）。 */
    private fun sampleLines(): List<String> = steps.takeLast(SAMPLE_WINDOW).mapIndexed { index, s ->
        "step${index + 1} tool=${s.tool} args=${s.argsDigest} code=${s.resultCode.wire}"
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
