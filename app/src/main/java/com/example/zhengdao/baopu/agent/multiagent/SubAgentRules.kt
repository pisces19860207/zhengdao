// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§1「模型」与开工闸门
// 《多Agent协作v1-协作模型-2026-10-21.md》§2/§3/§4/§5/§6。
// 本文件是**纯判据层**（无 IO、无线程、无 Android 依赖），JVM 直测——
// 「模型只能收窄不能放大」「并发上限 2」「重试至多 1 次」这些安全口径
// 全部落成可测的函数，而不是注释里的口号。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmMessage
import org.json.JSONArray
import org.json.JSONObject

internal object SubAgentRules {

    // ── 口径常量（派工单原文级） ───────────────────────────────────────────

    /** 一次分解最多几条子任务（派工单：父任务拆成 N 个；v1 取 5，防「拆成 50 条」烧 token）。 */
    const val MAX_SUBTASKS = 5

    /** 并行子 Agent 上限（派工单原文「默认最多 2 个子 Agent」）。 */
    const val MAX_PARALLEL_SUBAGENTS = 2

    /**
     * 单子任务步数上限（父循环 120 步）。
     *
     * 2026-10-22（133 真机验收后用户问「8 步是不是不够」）：那条「用尽」不是工具不够，
     * 是模型在一轮工具循环里没收敛（32588ms / 8 步）。8 步对「读几处⇒给结论」本够，
     * 但验收口径宁松半档：**8→16**；墙钟 90s 不变，仍是有界小循环。
     */
    const val MAX_STEPS = 16

    /** 单子任务默认墙钟（秒口径由工具参数进，这里存 ms）。 */
    const val DEFAULT_TIMEOUT_MS = 90_000L

    /** 超时钳制范围（模型申请 600s ⇒ 钳到 300s；申请 1s ⇒ 钳到 10s）。 */
    const val MIN_TIMEOUT_MS = 10_000L
    const val MAX_TIMEOUT_MS = 300_000L

    /** 整个分解的全局截止（工具 spec 超时 [TOOL_TIMEOUT_MS] 略大于它，作兜底）。 */
    const val GLOBAL_DEADLINE_MS = 300_000L

    /** 工具层兜底超时（比全局截止多 30s：收口/聚合/落账的时间）。 */
    const val TOOL_TIMEOUT_MS = GLOBAL_DEADLINE_MS + 30_000L

    /** 失败重试上限（每条子任务至多试 2 次＝首战 + 1 次重试；87 批「可重试」的有界版）。 */
    const val MAX_ATTEMPTS = 2

    /** 参数字符上限（防一条 goal 把父上下文撑爆）。 */
    const val MAX_GOAL_CHARS = 200
    const val MAX_INPUT_CHARS = 2000
    const val MAX_ACCEPTANCE_CHARS = 200
    const val MAX_OVERALL_GOAL_CHARS = 500

    /** 聚合文本里单条结果摘要截断（总长另见 [AGGREGATE_CAP]）。 */
    const val RESULT_TEXT_CAP = 600

    /** 聚合文本总长上限（回灌给父模型的工具结果，不能把父上下文撑爆）。 */
    const val AGGREGATE_CAP = 4000

    /**
     * 子 Agent 工具**硬天花板**（协作模型 §3）：六只读工具。
     *
     * 为什么只有这六个：v1 子 Agent 是「只读研究工」——写类/浏览器交互/资料库整理/
     * 技能人设/卡片/删除一律够不着。「不共享未提交的工作区写入」这条红线用
     * **结构上没有写通路**保证，比隔离目录更强（隔离目录还要防 `..` 折叠）。
     * `ask_user` 也不在：子任务不弹卡、不问用户（它要么自己读完给结论，要么失败报因）。
     */
    val DEFAULT_SUBAGENT_TOOLS: List<String> = listOf(
        "read_file",
        "list_dir",
        "search_in_files",
        "find_files",
        "file_info",
        "book_outline",
    )

    /** 状态名（审计表存这个；与 [SubTaskStatus.name] 一致）。 */
    fun statusName(s: SubTaskStatus): String = s.name

    // ── 解析（准入） ──────────────────────────────────────────────────────

    /** 解析结果（成功＝可开跑的分解；失败＝带人话理由，原样回灌给父模型）。 */
    sealed interface ParseResult {
        data class Ok(val decomposition: TaskDecomposition) : ParseResult
        data class Rejected(val reason: String) : ParseResult
    }

    /**
     * 解析父模型的 `run_subtasks` 参数（**纯函数**：只吃 JSON 与可用工具集）。
     *
     * @param args 工具入参
     * @param available 父会话**当前实际可用**的工具名（注册表可见集 ∩ 用户开关）
     * @param sessionId 父会话 id（审计归属）
     * @param now 当前时刻（分解 id 用）
     * @param attemptSeq 同会话内第几笔分解（同毫秒两笔也能区分）
     */
    fun parseDecomposition(
        args: JSONObject,
        available: Set<String>,
        sessionId: Long,
        now: Long,
        attemptSeq: Int,
    ): ParseResult {
        val raw = args.optJSONArray("subtasks")
            ?: return ParseResult.Rejected("缺少 subtasks 数组（要 1-$MAX_SUBTASKS 条子任务）")
        if (raw.length() < 1) {
            return ParseResult.Rejected("subtasks 至少要 1 条（不拆就别调本工具）")
        }
        if (raw.length() > MAX_SUBTASKS) {
            return ParseResult.Rejected(
                "subtasks 最多 $MAX_SUBTASKS 条（收到 ${raw.length()} 条；" +
                    "先把相关的小任务并成一条，或分几次调用）",
            )
        }
        val parentId = decompositionId(sessionId, now, attemptSeq)
        val overallGoal = args.optString("goal").trim().let { if (it.length > MAX_OVERALL_GOAL_CHARS) it.take(MAX_OVERALL_GOAL_CHARS) else it }
        val tasks = mutableListOf<SubTask>()
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i)
                ?: return ParseResult.Rejected("第 ${i + 1} 条不是对象（每条要 {goal, ...}）")
            val goal = item.optString("goal").trim()
            if (goal.isEmpty()) return ParseResult.Rejected("第 ${i + 1} 条缺少 goal（每条子任务必须说清要做什么）")
            if (goal.length > MAX_GOAL_CHARS) {
                return ParseResult.Rejected("第 ${i + 1} 条的 goal 超过 $MAX_GOAL_CHARS 字（说重点，细节放 input）")
            }
            val input = item.optString("input").trim()
            if (input.length > MAX_INPUT_CHARS) {
                return ParseResult.Rejected("第 ${i + 1} 条的 input 超过 $MAX_INPUT_CHARS 字（子任务不是搬全文的地方）")
            }
            val acceptance = item.optString("acceptance").trim()
            if (acceptance.length > MAX_ACCEPTANCE_CHARS) {
                return ParseResult.Rejected("第 ${i + 1} 条的 acceptance 超过 $MAX_ACCEPTANCE_CHARS 字")
            }
            val requested = toStringList(item.optJSONArray("tools"))
            val timeoutMs = clampTimeoutMs(item.optInt("timeout_seconds", 0))
            tasks += SubTask(
                id = subTaskId(parentId, i + 1),
                parentId = parentId,
                seq = i + 1,
                goal = goal,
                input = input,
                acceptance = acceptance,
                tools = effectiveWhitelist(requested, available),
                timeoutMs = timeoutMs,
                maxSteps = MAX_STEPS,
            )
        }
        return ParseResult.Ok(
            TaskDecomposition(
                parentId = parentId,
                sessionId = sessionId,
                goal = overallGoal,
                subtasks = tasks,
                createdAt = now,
            ),
        )
    }

    /** 分解 id：`sg-<会话>-<时刻36进制>-<同会话第几笔>`（可读、可 grep、同毫秒不撞）。 */
    fun decompositionId(sessionId: Long, now: Long, attemptSeq: Int): String =
        "sg-$sessionId-${now.toString(36)}-$attemptSeq"

    /** 子任务 id：`<分解id>-<序号>`（序号从 1 起，与人看的条号一致）。 */
    fun subTaskId(parentId: String, seq: Int): String = "$parentId-$seq"

    /** `timeout_seconds`（秒）⇒ ms；0/缺省 ⇒ 默认；越界钳制（不拒绝——模型估不准时长是常态）。 */
    fun clampTimeoutMs(seconds: Int): Long = when {
        seconds <= 0 -> DEFAULT_TIMEOUT_MS
        else -> (seconds.toLong() * 1000L).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    private fun toStringList(arr: JSONArray?): List<String>? {
        if (arr == null) return null
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    // ── 白名单（只能收窄不能放大） ─────────────────────────────────────────

    /**
     * 生效白名单 ＝ 模型申请 ∩ 硬天花板 ∩ 父会话实际可用。
     *
     * - 申请为空/没给 ⇒ 天花板 ∩ 可用（默认全给只读六件，用户关掉的不给）；
     * - 申请了天花板外的（写文件/浏览器/…）⇒ **静默丢弃**（不是拒绝整笔分解——
     *   模型顺手多要一个不该给的，不该让整件事失败；丢弃记录进审计的 tools 列）；
     * - 用户把读工具全关了 ⇒ 空名单合法（子任务纯凭 input 作答，执行层不再拦）。
     *
     * 顺序恒按 [DEFAULT_SUBAGENT_TOOLS]（稳定 ⇒ 审计与 tools 数组顺序不乱）。
     */
    fun effectiveWhitelist(requested: List<String>?, available: Set<String>): List<String> {
        val wanted = requested?.filter { it in DEFAULT_SUBAGENT_TOOLS }?.toSet() ?: emptySet()
        return DEFAULT_SUBAGENT_TOOLS.filter { it in available && (wanted.isEmpty() || it in wanted) }
    }

    // ── 并发（纯判据） ────────────────────────────────────────────────────

    /** 此刻还能起几个（pending 不够就起 pending 个；门满了就 0）。 */
    fun nextStartable(running: Int, pending: Int, maxParallel: Int): Int =
        (maxParallel - running.coerceAtLeast(0)).coerceAtLeast(0).coerceAtMost(pending.coerceAtLeast(0))

    // ── 重试（接 87：失败不静默、可重试、有界） ───────────────────────────

    /**
     * 失败了要不要自动重试一次。
     *
     * - 只重试 **FAILED/TIMEOUT**（真没干成）；CANCELLED 不重试（父都停了，
     *   重试等于背着用户烧 token）；DONE 无所谓重试。
     * - [attempts] 是**已经试过**的次数；≥ [MAX_ATTEMPTS] 不再试。
     */
    fun shouldRetry(status: SubTaskStatus, attempts: Int): Boolean = when (status) {
        SubTaskStatus.FAILED, SubTaskStatus.TIMEOUT -> attempts < MAX_ATTEMPTS
        else -> false
    }

    // ── 聚合（给父模型看的汇总文本） ──────────────────────────────────────

    /**
     * 把结果列表汇成一段回灌给父模型的文本（**纯函数**）。
     *
     * 口径（协作模型 §4）：
     * - 头部一行总览（x/y 成功；有失败/取消照实写）；
     * - 逐条：序号/状态/耗时/步数/用了哪些工具/目标一句/结果摘要（截断）；
     * - 失败条带原因与尝试次数；截断总长 [AGGREGATE_CAP]，超出补省略标记——
     *   汇总文本是工具结果，不是给用户的最终答复（父模型自己会组织语言）。
     */
    fun aggregate(results: List<SubTaskResult>, goalOf: (String) -> String? = { null }): String {
        if (results.isEmpty()) return "子任务汇总：一条都没有（不该发生；分解至少 1 条）"
        val done = results.count { it.ok }
        val b = StringBuilder()
        b.append("子任务汇总：$done/${results.size} 成功")
        val failed = results.count { it.status == SubTaskStatus.FAILED || it.status == SubTaskStatus.TIMEOUT }
        val cancelled = results.count { it.status == SubTaskStatus.CANCELLED }
        if (failed > 0) b.append("，$failed 条失败")
        if (cancelled > 0) b.append("，$cancelled 条取消")
        b.append("。\n")
        for (r in results) {
            val goal = goalOf(r.id)?.take(MAX_GOAL_CHARS).orEmpty()
            b.append("\n[#${seqOf(r.id)}] ")
            b.append(
                when (r.status) {
                    SubTaskStatus.DONE -> "完成"
                    SubTaskStatus.FAILED -> "失败"
                    SubTaskStatus.TIMEOUT -> "超时"
                    SubTaskStatus.CANCELLED -> "取消"
                    SubTaskStatus.PENDING, SubTaskStatus.RUNNING -> "未完结"
                },
            )
            b.append("｜${r.durationMs / 1000.0}s｜${r.steps} 步｜试了 ${r.attempts} 次")
            if (r.failureReason.isNotBlank()) b.append("｜原因：${r.failureReason}")
            if (goal.isNotBlank()) b.append("\n  目标：$goal")
            if (r.status == SubTaskStatus.DONE) {
                b.append("\n  结果：${truncate(r.text.ifBlank { "（子 Agent 没给出文字结论）" }, RESULT_TEXT_CAP)}")
            }
        }
        b.append("\n\n以上是各子任务的原始结果。失败的条目你可以选择重派或放弃；")
        b.append("子结果不会自动写进任何文件，需要落成产物时由你决定写什么、写在哪。")
        return truncate(b.toString(), AGGREGATE_CAP)
    }

    /** 从子任务 id 取条号（`…-<n>`；取不到回 0——聚合不靠它活着）。 */
    fun seqOf(subTaskId: String): Int = subTaskId.substringAfterLast('-', "").toIntOrNull() ?: 0

    /** 截断（纯函数）：超长补省略标记，短于上限原样返。 */
    fun truncate(text: String, cap: Int): String =
        if (text.length <= cap) text else text.take(cap) + "……（截断，原长 ${text.length} 字）"

    // ── 子任务提示词（上下文隔离的结构保证） ──────────────────────────────

    /**
     * 子 Agent 的两条开局消息（system + user）——**纯函数**，JVM 直测。
     *
     * 隔离口径（协作模型 §3）：只有「目标/输入/验收」＋固定工人提示词；
     * **不含父会话历史、不含人设、不含用户原话**——子 Agent 是短期研究工，
     * 不是抱朴本体。它用完即弃，背景知识进不了它的上下文，正因如此
     * 「子 Agent 看不到父上下文」是结构事实而不是约定。
     */
    fun subTaskMessages(task: SubTask): List<LlmMessage> {
        val system = buildString {
            append("你是抱朴派出的子 Agent，只负责完成交给你的一个子任务。\n")
            append("纪律：\n")
            append("1. 你只能使用发给你的这几个只读工具（读文件/列目录/搜内容）；没有写工具，不要试图写任何文件。\n")
            append("2. 你不认识用户、也没有对话历史——判断只凭下面给的目标与输入。\n")
            append("3. 工具连续报错就换路径或换说法，别硬试；确实做不到就明说做不到。\n")
            append("4. 完成后用一段话给出结论（事实+出处路径），不要客套，不要复述任务。\n")
            // 2026-10-22（133 真机：子任务 8 步用尽 = 不收敛，不是工具不够）：
            // 工具结果以【工具结果】消息回灌；拿到足够信息就当场收尾，别反复调同一个工具。
            append("5. 工具结果会以【工具结果】消息给你。**足够回答目标时立刻给结论**；")
            append("同一个工具同一组参数最多调一次（结果不会变），重复调用只会浪费步数。")
        }
        val user = buildString {
            append("【目标】${task.goal}")
            if (task.input.isNotBlank()) append("\n\n【输入材料】${task.input}")
            if (task.acceptance.isNotBlank()) append("\n\n【验收标准】${task.acceptance}")
            append("\n\n请先用工具查证，再给结论。")
        }
        return listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, system),
            LlmMessage(LlmMessage.ROLE_USER, user),
        )
    }
}
