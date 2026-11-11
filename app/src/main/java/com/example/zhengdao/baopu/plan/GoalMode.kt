// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百八十二批（2026-11-08）· **目标模式**：给长任务钉一个「做到什么算做完」的锚。
//
// 治的病（派工单 §问题）：长任务没有目标 ⇒ 跑偏、说不清完成没完成、军令状没有锚点。
// 军令状（347）管「计划表不许缩水」，本层管「这份计划究竟要换来什么」：
// 一句话目标 ＋ 可机械验证的成功判据 ＋ 不做清单 ＋ 预算（步数/时长）。
//
// 四条分工（照派工单 §做 ①–⑤）：
// 1. **立**：`update_plan` 带 `goal` 参数（与 `pledge` 同一次调用——同批，不新开单、
//    不新开工具 ⇒ 入口净增 0）；Goal 只有一个外键 `pledgeId`，**不存第二份清单**
//    （步骤总数与进度全从军令状快照/收口读数来）。
// 2. **对照**：每次工具调用前 `stepCheck` —— 越界（不做清单里点名的工具/路径）停并问、
//    超预算（步数/时长）停并报；`ask_user` 与 `update_plan` 任何时刻放行
//    （否则模型连「停下来问人」「把进度报上来」都做不到）。
// 3. **判**：收尾由右相的判据表判三态（[com.example.zhengdao.baopu.verify.Status]，
//    三态**不复写第二套**）；PASS 才「已达成」，UNVERIFIABLE 停「待验收」。
// 4. **看**：治理中心（322）那一屏显示「目标：… / 进度 3/5 步 / 预算用 40%」——
//    百分数在本层算好，屏幕上只抄字（322 判据：判据层不许有算术）。
//
// 落点 `<工作区>/产物/.goal.json`（点号开头 ⇒ 不计入「产物 N 件」，512/322 同口径）。
//
// 两条与 347 的边界（派工单 §边界「不改 347 立状规则」）：
// - 立状的线（≥5 步或跨会话）、开工卡、冻结时机一个字没动：本层只**读**快照，
//   目标没有状就立不起来（结构性保证「军令状只一份」）；
// - 没目标 ⇒ 本层对一切恒「放行」，今天的执行路径逐字节不变。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.verify.Status
import com.example.zhengdao.core.RunLog
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 目标的判据层（纯函数：零 Android、零 IO、零时钟——时刻一律由调用方传入）。
 *
 * 房型照 `PlanPledgeRules`（312）：判定与落盘分家，JVM 直测。
 */
internal object GoalRules {

    /** 用户说这三个字就是开了目标模式（派工单 §做②的字面触发）。 */
    const val GOAL_MODE_WORD = "目标模式"

    /** 引擎在轮首注入的一句（只是提示：立状的线一条没动，还是 347 的）。 */
    val GOAL_MODE_NOTE: String = "用户开了「目标模式」：本轮先立目标——update_plan 带 goal" +
        "（一句话目标 ＋ 至少 1 条可机械验证的判据（kind=file 加 path）＋ 预算步数），与 pledge " +
        "同一次调用；还没立状就先立状（长任务会自动问开工卡）。目标立起来之后每步前对照：" +
        "越界停下问用户、超预算停下报数。"

    /** 长任务没有目标时的那一刀（≥5 步的线是 347 的 [PlanPledgeRules.LONG_TASK_STEPS]，不另立一条）。 */
    val GOAL_MISSING_NOTE: String = "【目标缺位】这是长任务（≥5 步）但还没有目标：" +
        "下一步之前调 update_plan 带上 goal（一句话目标＋至少 1 条可机械验证的判据＋预算），" +
        "否则跑完说不清「算不算做完」。"

    /** `update_plan` 参数里目标那一件的键名。 */
    const val ARG_GOAL = "goal"

    /** 目标句字数上限（卡面一句放得下）。 */
    const val MAX_SENTENCE = 160

    /** 单条判据/边界一句话的字数上限。 */
    const val MAX_TEXT = 60

    /** 模板名里取目标句的前几个字（够认人就行，不做摘要）。 */
    const val NAME_PREVIEW = 20

    /** 判据与不做清单各自的条数上限（多了卡面放不下，也管不过来）。 */
    const val MAX_ITEMS = 8

    /** 时长预算上限（分钟）：10 小时，超出的一律按上限截（防「预算=100000 分钟」这种空话）。 */
    const val MAX_BUDGET_MINUTES = 600

    /**
     * 这三件任何时刻都放行：模型要靠它们「停下来问人」「把进度/结果报上来」。
     *
     * 预算耗尽时把 `ask_user` 也拦掉，等于把「停并报」变成「哑掉」——那是本单要治的病之一。
     */
    val ALWAYS_ALLOWED: Set<String> = setOf("ask_user", "update_plan")

    /** 工具参数里可能装着目标的键（越界判定用：不做清单可以点名路径）。 */
    private val PATH_KEYS: List<String> =
        listOf("path", "paths", "target", "targets", "dir", "root", "file")

    /** 判据的种类：能机械验 vs 只能人来判（后者让三态落到「待验收」，不许冒充 PASS）。 */
    enum class CriterionKind(val wire: String, val cn: String) {
        FILE("file", "文件在"),
        HUMAN("human", "要人判"),
        ;

        companion object {
            fun ofWire(raw: String): CriterionKind? =
                entries.firstOrNull { it.wire == raw.trim().lowercase() }
        }
    }

    /**
     * 一条成功判据。
     *
     * @param text 一句话说清「满足什么算达成」
     * @param kind FILE＝收尾时机器去查 [path] 在不在；HUMAN＝只能人来判（三态落到待验收）
     * @param path 工作区内的相对路径（`kind=file` 必填；不许写绝对路径——工作区外的盘不归这次任务管）
     */
    data class Criterion(val text: String, val kind: CriterionKind, val path: String = "") {
        /** 机械可验：文件类且给了路径（判据「可机械验证 ≥1 条」量的就是这个）。 */
        val mechanical: Boolean get() = kind == CriterionKind.FILE && path.isNotBlank()
    }

    /**
     * 「不做清单」的一条。
     *
     * [denyTools]／[denyPaths] 给了才**机械可比**（越界闸据此停）；只写一句人话就只靠模型自律——
     * 这是取舍：不做清单本来就多半是自然语言，硬把它翻译成规则只会误拦。
     */
    data class Boundary(
        val text: String,
        val denyTools: List<String> = emptyList(),
        val denyPaths: List<String> = emptyList(),
    ) {
        val mechanical: Boolean get() = denyTools.isNotEmpty() || denyPaths.isNotEmpty()
    }

    /** 预算：步数（必填）＋ 时长（可选，0＝不限时）。 */
    data class Budget(val maxSteps: Int, val maxMinutes: Int = 0) {
        val unlimited: Boolean get() = maxSteps <= 0 && maxMinutes <= 0
    }

    /** 目标的状态闭集（五值；「已达成」只由 PASS 换来，见 [stateOf]）。 */
    enum class GoalState(val wire: String, val cn: String) {
        ACTIVE("active", "进行中"),
        ACHIEVED("achieved", "已达成"),
        FAILED("failed", "未达成"),
        PENDING("pending_review", "待验收"),
        STOPPED("stopped", "已停下"),
        ;

        companion object {
            fun ofWire(raw: String): GoalState? =
                entries.firstOrNull { it.wire == raw.trim().lowercase() }
        }
    }

    /** 从参数里读出来、还没上板的一份（冻结时才拿到 `pledgeId`）。 */
    data class Draft(
        val sentence: String,
        val criteria: List<Criterion>,
        val boundaries: List<Boundary>,
        val budget: Budget,
    )

    /**
     * 目标本体（`.goal.json` 的内容）。
     *
     * **没有 items 字段**：步骤数 [total] 是冻结那一刻从军令状快照抄下来的读数，进度 [done]
     * 由收口读数更新——目标不存第二份清单（判据「军令状只一份」的结构保证）。
     */
    data class Goal(
        val sentence: String,
        val criteria: List<Criterion>,
        val boundaries: List<Boundary>,
        val budget: Budget,
        val state: GoalState,
        val pledgeId: String,
        val total: Int,
        val done: Int,
        val stepsUsed: Int,
        val atMs: Long,
        val sessionId: Long,
    ) {
        /** 稳定 id（目标句＋状 id 的 sha 前 8 位）：同一句话挂同一份状 ⇒ 同一个 id。 */
        val id: String get() = idOf(pledgeId, sentence)
    }

    /** 一次收尾的读数（三态＋人话原因＋要贴的 notice）。 */
    data class Outcome(val goal: Goal, val verdict: Verdict, val notice: String)

    /** 一条判据判定后的三态读数。 */
    data class Verdict(
        val status: Status,
        val reasons: List<String>,
        /** 逐条判据的结果（判据「判据逐条结果」那一格；与 [status] 同一把尺算出来的）。 */
        val results: List<CriterionResult> = emptyList(),
    ) {
        val passed: Boolean get() = status == Status.PASS
    }

    /** 单条判据的结局（三值：过／未兑现／待判——待判只为人判那类）。 */
    enum class CriterionState(val cn: String) {
        MET("过"),
        MISSED("未兑现"),
        UNJUDGED("待判"),
    }

    /** 一条判据的判定结果（[criterion] 原文照带，账上要能一句一句对回目标）。 */
    data class CriterionResult(val criterion: Criterion, val state: CriterionState)

    /**
     * 一次收口的**事实行**（第四百八十二批前置②：只产出、只交出去）。
     *
     * 本类型不认识任何账本：它只是一份"该记什么"的纯数据，写进哪本账、用什么键名，
     * 由装配层交给那本条线自己的实现（[com.example.zhengdao.baopu.evolution.trigger.GoalCloseout]）。
     * 这样目标这一侧永远不新增 kind、不新增文件格式——形状归既有账本所有。
     */
    data class CloseoutFact(
        val atMs: Long,
        val goalId: String,
        val sentence: String,
        /** 收口状态的中文（已达成／未达成／待验收／已停下）。 */
        val stateCn: String,
        /** 验了几条（判据总条数）。 */
        val ran: Int,
        /** 过了几条。 */
        val passed: Int,
        val total: Int,
        /** 逐条结果，一行一条（`<标记>|<判据>|<路径>`）。 */
        val cases: List<String>,
        val budgetUsed: Int,
        val budgetMax: Int,
        /** `40%` 这种形状（由 [percentText] 算，别处不重算）。 */
        val budgetPct: String,
    )

    // ── 立（判据「空判据拒立」「可机械验证 ≥1 条」的落点）────────────────────

    /** [parse] 的结局。 */
    sealed interface GoalParse {
        data class Ok(val draft: Draft) : GoalParse

        /** 没带 `goal` 参数：普通任务，本层沉默（今天的路径逐字节不变）。 */
        data object Absent : GoalParse

        /** 写法不对或没状：错误原文回灌（不静默降级——那样模型会以为目标立起来了）。 */
        data class Error(val message: String) : GoalParse
    }

    /**
     * 从 `update_plan` 参数里读目标。
     *
     * @param hasPledge 板上有状（或本次调用正要冻结）：目标**挂在状上**，没状立不起来——
     *   否则「目标一份清单、计划一份清单」就是本单要禁的两份清单。
     */
    fun parse(args: JSONObject, hasPledge: Boolean): GoalParse {
        val raw = args.opt(ARG_GOAL)
        if (raw == null || raw == JSONObject.NULL) return GoalParse.Absent
        val obj = raw as? JSONObject
            ?: return GoalParse.Error(
                "参数「$ARG_GOAL」不是对象：要 {\"sentence\": 一句话目标, \"criteria\": [...]}。",
            )
        if (!hasPledge) {
            return GoalParse.Error(
                "目标模式要先立军令状：目标是挂在状上的（步骤数与进度都走那一份，不另开第二份清单）。" +
                    "同一版计划带「${PlanPledgeRules.ARG_PLEDGE}」把状立起来，再带「$ARG_GOAL」。",
            )
        }
        return draftOf(obj)
    }

    /** 逐字段校验（错误原文都要教改法，不只说「不行」）。 */
    private fun draftOf(obj: JSONObject): GoalParse {
        val sentence = obj.optString("sentence").trim()
        if (sentence.isEmpty()) {
            return GoalParse.Error("目标是空的：一句话写清这个任务要换来什么（≤$MAX_SENTENCE 字）。")
        }
        val criteria = mutableListOf<Criterion>()
        val bad = criteriaOf(obj.optJSONArray("criteria"), criteria)
        if (bad != null) return GoalParse.Error(bad)
        if (criteria.isEmpty()) {
            return GoalParse.Error(
                "空判据拒立：至少给 1 条「怎么算达成」，其中至少 1 条要能机械验证" +
                    "（{\"kind\":\"file\",\"text\":\"…\",\"path\":\"工作区内相对路径\"}）。",
            )
        }
        if (criteria.none { it.mechanical }) {
            return GoalParse.Error(
                "判据里没有一条能机械验证：收尾时机器要能自己去查（kind=file ＋ path），" +
                    "否则这个目标判不了（只会停在待验收）。给至少 1 条 {\"kind\":\"file\",\"path\":\"…\"}。",
            )
        }
        val boundaries = mutableListOf<Boundary>()
        val badBoundary = boundariesOf(obj.optJSONArray("boundaries"), boundaries)
        if (badBoundary != null) return GoalParse.Error(badBoundary)
        val budgetBad = budgetOf(obj.optJSONObject("budget"))
        if (budgetBad is BudgetParse.Bad) return GoalParse.Error(budgetBad.message)
        val budget = (budgetBad as BudgetParse.Ok).budget
        return GoalParse.Ok(Draft(sentence.take(MAX_SENTENCE), criteria, boundaries, budget))
    }

    /** 判据数组 ⇒ [out]；坏条目回错误原文，null ＝ 全好。 */
    private fun criteriaOf(arr: JSONArray?, out: MutableList<Criterion>): String? {
        if (arr == null || arr.length() == 0) return null
        if (arr.length() > MAX_ITEMS) return "判据最多 $MAX_ITEMS 条（现在 ${arr.length()} 条）：先说清最要紧的那几条。"
        for (i in 0 until arr.length()) {
            val bad = criterionOf(arr.optJSONObject(i), out)
            if (bad != null) return "第 ${i + 1} 条判据：$bad"
        }
        return null
    }

    private fun criterionOf(o: JSONObject?, out: MutableList<Criterion>): String? {
        o ?: return "不是对象，要 {kind, text, path?}。"
        val text = o.optString("text").trim()
        if (text.isEmpty()) return "缺 text（一句话说清满足什么算达成）。"
        val kind = CriterionKind.ofWire(o.optString("kind", CriterionKind.FILE.wire))
            ?: return "kind「${o.optString("kind")}」只认 file（机器查）／human（人判）。"
        val path = o.optString("path").trim()
        if (kind == CriterionKind.FILE && path.isEmpty()) {
            return "kind=file 要给 path（工作区内相对路径，例：产物/报告.md）。"
        }
        if (path.startsWith("/") || path.contains("..")) {
            return "path 只认工作区内的相对路径（不写 / 开头、不带 ..）。"
        }
        out += Criterion(text.take(MAX_TEXT), kind, path)
        return null
    }

    /** 不做清单 ⇒ [out]；数组里每条可以是字符串（一句话），也可以是 {text, denyTools, denyPaths}。 */
    private fun boundariesOf(arr: JSONArray?, out: MutableList<Boundary>): String? {
        if (arr == null || arr.length() == 0) return null
        if (arr.length() > MAX_ITEMS) return "不做清单最多 $MAX_ITEMS 条（现在 ${arr.length()} 条）。"
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                val text = arr.optString(i).trim()
                if (text.isEmpty()) return "第 ${i + 1} 条不做清单是空的。"
                out += Boundary(text.take(MAX_TEXT))
            } else {
                val text = o.optString("text").trim()
                if (text.isEmpty()) return "第 ${i + 1} 条不做清单缺 text。"
                out += Boundary(
                    text = text.take(MAX_TEXT),
                    denyTools = stringsOf(o.optJSONArray("denyTools")),
                    denyPaths = stringsOf(o.optJSONArray("denyPaths")),
                )
            }
        }
        return null
    }

    /** [parseBudget] 的结局（内部用；外面只认「好/坏」两路）。 */
    private sealed interface BudgetParse {
        data class Ok(val budget: Budget) : BudgetParse
        data class Bad(val message: String) : BudgetParse
    }

    /**
     * 预算解析。
     *
     * `maxSteps` 必填且 ≥1：进度要看的是「用了几步 / 预算几步」，没有分母就没有百分比，
     * 那一条读数只能编——本仓不编读数（527 同口径：分母 0 不补成 0%）。
     */
    private fun budgetOf(o: JSONObject?): BudgetParse {
        o ?: return BudgetParse.Bad("缺预算：要 {\"maxSteps\": 步数上限, \"maxMinutes\": 分钟上限（可省）}。")
        val steps = o.optInt("maxSteps", 0)
        if (steps < 1) return BudgetParse.Bad("预算步数要 ≥1（现在 $steps）：没有分母就说不清「预算用了多少」。")
        if (steps > 200) return BudgetParse.Bad("预算步数最多 200（现在 $steps）：那是把「不限」写成数字。")
        val minutes = o.optInt("maxMinutes", 0).coerceIn(0, MAX_BUDGET_MINUTES)
        return BudgetParse.Ok(Budget(steps, minutes))
    }

    // ── 对照（判据「越界停问」「超预算停报」的落点）──────────────────────────

    /** 停的原因两类（停法不同：一个要问人，一个要报数）。 */
    enum class StopKind(val wire: String, val cn: String) {
        BOUNDARY("boundary", "超边界"),
        BUDGET("budget", "超预算"),
    }

    /** [stepCheck] 的结局。 */
    sealed interface StepCheck {
        data object Pass : StepCheck

        data class Stop(val kind: StopKind, val message: String) : StepCheck
    }

    /**
     * 这一步能不能走（每次工具调用前调；判完不执行＝「停」）。
     *
     * @param stepsUsed 含这一次的已用步数（由板子数，本层不数）
     * @param elapsedMs 目标立起来到现在过了多久
     */
    fun stepCheck(goal: Goal, tool: String, paths: List<String>, stepsUsed: Int, elapsedMs: Long): StepCheck {
        if (goal.state != GoalState.ACTIVE) return StepCheck.Pass
        if (tool in ALWAYS_ALLOWED) return StepCheck.Pass
        crossed(goal, tool, paths)?.let { return StepCheck.Stop(StopKind.BOUNDARY, boundaryMessage(it, tool)) }
        val overSteps = goal.budget.maxSteps > 0 && stepsUsed > goal.budget.maxSteps
        val overTime = goal.budget.maxMinutes > 0 && elapsedMs > goal.budget.maxMinutes * 60_000L
        if (!overSteps && !overTime) return StepCheck.Pass
        return StepCheck.Stop(StopKind.BUDGET, budgetMessage(goal, stepsUsed, elapsedMs))
    }

    /** 命中的那条不做清单（没有 ⇒ null）。 */
    fun crossed(goal: Goal, tool: String, paths: List<String>): Boundary? =
        goal.boundaries.firstOrNull { b ->
            tool in b.denyTools || b.denyPaths.any { deny -> paths.any { it == deny || it.startsWith(deny) } }
        }

    private fun boundaryMessage(b: Boundary, tool: String): String =
        "【超边界】这一步没有执行：「$tool」落在目标的不做清单里（${b.text}）。" +
            "先停下来问用户：这一步要不要破例？拿到明确答复再继续；" +
            "他要是说这事不做了，就按实情结项（不许绕过去偷偷做）。"

    private fun budgetMessage(goal: Goal, stepsUsed: Int, elapsedMs: Long): String {
        val why = if (goal.budget.maxSteps > 0 && stepsUsed > goal.budget.maxSteps) {
            "步数 ${stepsUsed} 已超预算 ${goal.budget.maxSteps}"
        } else {
            "已用 ${elapsedMs / 60_000L} 分钟，超预算 ${goal.budget.maxMinutes} 分钟"
        }
        return "【超预算】已停下：$why（进度 ${goal.done}/${goal.total} 步）。" +
            "把现状报给用户：已经做完什么、还差什么、再要几步；他点头加预算之后再继续。"
    }

    // ── 判（判据「PASS 才已达成 / UNVERIFIABLE 停待验收」的落点）──────────────

    /**
     * 按判据判三态。
     *
     * 顺序是硬的：先看有没有机械判据没兑现（有 ⇒ FAIL，那是确定的事实），再看有没有
     * 只能人判的（有 ⇒ UNVERIFIABLE，**不许**拿其余几条过没过替它下结论）。
     *
     * @param exists 路径在不在（生产传工作区真查，单测传假件——判据层不碰磁盘）
     */
    fun judge(goal: Goal, exists: (String) -> Boolean): Verdict {
        val results = goal.criteria.map { c ->
            CriterionResult(
                c,
                when {
                    !c.mechanical -> CriterionState.UNJUDGED
                    exists(c.path) -> CriterionState.MET
                    else -> CriterionState.MISSED
                },
            )
        }
        if (goal.criteria.isEmpty()) return Verdict(Status.UNVERIFIABLE, listOf("没有判据：判不了"), results)
        val missed = results.filter { it.state == CriterionState.MISSED }
        if (missed.isNotEmpty()) {
            return Verdict(
                Status.FAIL,
                missed.map { "「${it.criterion.text}」没兑现：${it.criterion.path} 不在" },
                results,
            )
        }
        val unjudged = results.filter { it.state == CriterionState.UNJUDGED }
        if (unjudged.isNotEmpty()) {
            return Verdict(
                Status.UNVERIFIABLE,
                unjudged.map { "「${it.criterion.text}」只能人来判" },
                results,
            )
        }
        return Verdict(Status.PASS, emptyList(), results)
    }

    /**
     * 收口事实（前置②）：把一次收口摊成一行可记的纯数据。
     *
     * 失败也是事实——**未达成/待验收/已停下都要出这一行**，字段一个不省。
     */
    fun closeoutFact(
        goal: Goal,
        verdict: Verdict,
        atMs: Long,
        stopped: Boolean = false,
    ): CloseoutFact = CloseoutFact(
        atMs = atMs,
        goalId = goal.id,
        sentence = goal.sentence,
        stateCn = outcomeState(verdict, stopped).cn,
        ran = verdict.results.size,
        passed = verdict.results.count { it.state == CriterionState.MET },
        total = goal.criteria.size,
        cases = verdict.results.map { caseLine(it) },
        budgetUsed = goal.stepsUsed,
        budgetMax = goal.budget.maxSteps,
        budgetPct = percentText(goal.stepsUsed, goal.budget.maxSteps),
    )

    /**
     * 目标模板候选（前置②：**只产候选，不建库、不启用**）。
     *
     * 用户在派工单里写死了边界：模板「只出候选（灰度后才用）」——所以本函数只**返回一个值**，
     * 不写任何文件、不登记任何能力、不打任何灰度开关、不参与下次立目标。谁要用它，
     * 先过候选通道与人点头（那是更后面的事，不在本批）。
     */
    data class TemplateCandidate(
        /** 模板名（从目标句里取一段，够认人就行）。 */
        val name: String,
        /** 适用场景（原目标句——模板是"这类活"的写法，不是这句话本身）。 */
        val scenario: String,
        /** 判据写法（照原目标逐条抄形状：`file:判据原文`，教下次怎么写判据）。 */
        val criteriaPattern: List<String>,
        /** 来源 goal id（追得回是哪一次目标提炼出来的）。 */
        val sourceGoalId: String,
    )

    fun templateCandidate(goal: Goal): TemplateCandidate = TemplateCandidate(
        name = "目标模板：" + goal.sentence.take(NAME_PREVIEW),
        scenario = goal.sentence,
        criteriaPattern = goal.criteria.map { "${it.kind.wire}:${it.text}" },
        sourceGoalId = goal.id,
    )

    /** 逐条结果的账上一行：`过|判据原文|路径`（路径只对文件类有意义，人判那类留空）。 */
    private fun caseLine(r: CriterionResult): String =
        "${r.state.cn}|${r.criterion.text}|${r.criterion.path}"

    /** 三态 ⇒ 目标状态（PASS 才「已达成」，判不了就停在「待验收」）。 */
    fun stateOf(status: Status): GoalState = when (status) {
        Status.PASS -> GoalState.ACHIEVED
        Status.FAIL -> GoalState.FAILED
        Status.UNVERIFIABLE -> GoalState.PENDING
    }

    /**
     * 一次收口的**结局状态**（settle 与 closeoutFact 共用这一把尺，不许两处各算一套）。
     *
     * @param stopped 这次收尾是被打断的 ⇒ 判据没全兑现时记「已停下」（不是「未达成」）
     */
    fun outcomeState(verdict: Verdict, stopped: Boolean): GoalState = when {
        verdict.status == Status.PASS -> GoalState.ACHIEVED
        stopped -> GoalState.STOPPED
        else -> stateOf(verdict.status)
    }

    /** 收尾要贴给人看的那一句（PASS 说达成，判不了就说判不了，不装）。 */
    fun settleNotice(goal: Goal, verdict: Verdict): String = when (verdict.status) {
        Status.PASS -> "【目标】已达成：${goal.sentence}（${goal.criteria.size} 条判据全兑现）"
        Status.FAIL -> "【目标】未达成：${verdict.reasons.joinToString("；")}"
        Status.UNVERIFIABLE -> "【目标】待验收：${verdict.reasons.joinToString("；")}——停下等你看"
    }

    /**
     * 被打断的那条收尾路（中止／停止／熔断）：说「已停下」而不是「未达成」。
     *
     * 两件事不一样：判据没兑现是事实，但「没跑完」不等于「判了不过」。中止时照样把
     * 未兑现的条目列全，读数一个不少。
     */
    fun stoppedNotice(goal: Goal, verdict: Verdict): String =
        "【目标】已停下：进度 ${goal.done}/${goal.total} 步" +
            (if (verdict.reasons.isEmpty()) "（判据全兑现）" else "；未兑现：${verdict.reasons.joinToString("；")}")

    /** 立起来时回灌的一句（模型必须知道头上有一个目标在管着）。 */
    fun frozenAck(goal: Goal): String =
        "【目标已立】${goal.sentence}｜判据 ${goal.criteria.size} 条（可机械验证 " +
            "${goal.criteria.count { it.mechanical }} 条）｜预算 ${goal.budget.maxSteps} 步" +
            (if (goal.budget.maxMinutes > 0) "／${goal.budget.maxMinutes} 分钟" else "") +
            "｜不做清单 ${goal.boundaries.size} 条。每步前会对照：越界停下问你，超预算停下报数。"

    // ── 看（判据「账本可见」的读数；百分比在本层算，屏幕只抄字）──────────────

    /** 预算用量（`40%` 这种形状；0 分母 ⇒ 「未设」，不补成 0%）。 */
    fun percentText(used: Int, max: Int): String =
        if (max <= 0) "未设" else "${used * 100 / max}%"

    /** 治理中心那一行（逐字：`目标：… / 进度 3/5 步 / 预算用 40%`）。 */
    fun progressLine(goal: Goal): String =
        "目标：${goal.sentence} / 进度 ${goal.done}/${goal.total} 步 / " +
            "预算用 ${percentText(goal.stepsUsed, goal.budget.maxSteps)}"

    /** 每步回灌的「回主线」提醒（偏题时把它拉回来；只报事实，不带判词）。 */
    fun remindLine(goal: Goal): String =
        "【目标提醒】主线：${goal.sentence}；进度 ${goal.done}/${goal.total} 步，" +
            "预算已用 ${percentText(goal.stepsUsed, goal.budget.maxSteps)}。" +
            "这一步要是偏了主线，先回主线；要改目标就明说。"

    // ── 账：`.goal.json` 的形与读回（键序固定 ⇒ 写后读回逐字一致）────────────

    fun encode(goal: Goal): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", goal.id)
        .put("sentence", goal.sentence)
        .put("criteria", JSONArray().also { arr ->
            goal.criteria.forEach { c ->
                arr.put(JSONObject().put("text", c.text).put("kind", c.kind.wire).put("path", c.path))
            }
        })
        .put("boundaries", JSONArray().also { arr ->
            goal.boundaries.forEach { b ->
                arr.put(
                    JSONObject().put("text", b.text)
                        .put("denyTools", JSONArray(b.denyTools))
                        .put("denyPaths", JSONArray(b.denyPaths)),
                )
            }
        })
        .put("budget", JSONObject().put("maxSteps", goal.budget.maxSteps).put("maxMinutes", goal.budget.maxMinutes))
        .put("state", goal.state.wire)
        .put("pledge", goal.pledgeId)
        .put("total", goal.total)
        .put("done", goal.done)
        .put("used", goal.stepsUsed)
        .put("at", goal.atMs)
        .put("session", goal.sessionId)

    /** 读回（坏文件/坏字段 ⇒ null，宁缺勿崩——账读不回来当没目标，不编一份）。 */
    fun decode(o: JSONObject): Goal? {
        val sentence = o.optString("sentence")
        val pledge = o.optString("pledge")
        if (sentence.isEmpty() || pledge.isEmpty()) return null
        val state = GoalState.ofWire(o.optString("state")) ?: return null
        val criteria = mutableListOf<Criterion>()
        val arr = o.optJSONArray("criteria") ?: return null
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: return null
            val text = c.optString("text")
            if (text.isEmpty()) return null
            criteria += Criterion(
                text,
                CriterionKind.ofWire(c.optString("kind")) ?: CriterionKind.HUMAN,
                c.optString("path"),
            )
        }
        val budget = o.optJSONObject("budget") ?: return null
        return Goal(
            sentence = sentence,
            criteria = criteria,
            boundaries = boundariesFrom(o.optJSONArray("boundaries")),
            budget = Budget(budget.optInt("maxSteps", 0), budget.optInt("maxMinutes", 0)),
            state = state,
            pledgeId = pledge,
            total = o.optInt("total", 0),
            done = o.optInt("done", 0),
            stepsUsed = o.optInt("used", 0),
            atMs = o.optLong("at", 0L),
            sessionId = o.optLong("session", -1L),
        )
    }

    private fun boundariesFrom(arr: JSONArray?): List<Boundary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text")
            if (text.isEmpty()) null
            else Boundary(text, stringsOf(o.optJSONArray("denyTools")), stringsOf(o.optJSONArray("denyPaths")))
        }
    }

    /** 工具参数里的路径（越界判定用；取不到 ⇒ 空表，不猜）。 */
    fun pathsOf(args: JSONObject): List<String> {
        val out = mutableListOf<String>()
        PATH_KEYS.forEach { key ->
            when (val v = args.opt(key)) {
                is String -> if (v.isNotBlank()) out += v.trim()
                is JSONArray -> for (i in 0 until v.length()) {
                    val s = v.optString(i).trim()
                    if (s.isNotEmpty()) out += s
                }
                else -> Unit
            }
        }
        return out
    }

    private fun stringsOf(arr: JSONArray?): List<String> =
        if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifEmpty { null } }

    private fun idOf(pledgeId: String, sentence: String): String {
        val payload = "$pledgeId|$sentence"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }
}

/**
 * 目标的落盘口（全仓唯一碰 `.goal.json` 的地方；判据层不认识磁盘）。
 *
 * 写盘先写 `产物/.goal.json.tmp` 再改名（同 `BoardStore`／`AttributionCursor` 房型）：
 * 半份目标比没有目标更糟——下一轮会把半截当全文读。
 */
internal object GoalStore {

    /** 稳定键：点号开头 ⇒ 不计入「产物 N 件」（512 口径）。改这个名字等于换一个文件。 */
    const val FILE_NAME: String = ".goal.json"

    /** 落点：`<工作区>/产物/.goal.json`。 */
    fun fileOf(wsRoot: File): File = File(MemoryLayout.productsDir(wsRoot), FILE_NAME)

    /** 读（没有/读不出 ⇒ null：没目标就是没目标，不编一份）。 */
    fun readGoal(wsRoot: File): GoalRules.Goal? {
        val f = fileOf(wsRoot)
        if (!f.isFile) return null
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val inner = root.optJSONObject("goal") ?: return null
        return GoalRules.decode(inner)
    }

    /** 整篇替换（写成 ⇒ true；盘上一个字节没动 ⇒ false）。 */
    fun write(wsRoot: File, goal: GoalRules.Goal): Boolean = runCatching {
        val f = fileOf(wsRoot)
        val dir = f.parentFile ?: return@runCatching false
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        if (f.exists() && !f.isFile) return@runCatching false
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("goal", GoalRules.encode(goal)).toString(), Charsets.UTF_8)
        if (f.exists() && !f.delete()) return@runCatching false
        val ok = tmp.renameTo(f)
        if (!ok) RunLog.log("目标账改名失败（盘上仍是旧内容）")
        ok
    }.getOrElse {
        RunLog.log("目标账写失败：${it.javaClass.simpleName}")
        false
    }
}

/**
 * 目标的进程态（装配层注入工作区；判据全在 [GoalRules]，落盘在 [GoalStore]）。
 *
 * 与军令状同一条生命周期：新请求＝新任务，旧目标不带过来（[clear] 由 PlanController 调）。
 */
internal object GoalBoard {

    @Volatile
    private var root: File? = null

    @Volatile
    private var active: GoalRules.Goal? = null

    /** 目标立起来之后用掉了几步（每次工具调用 +1；判「超预算」的分母由它给）。 */
    @Volatile
    private var used: Int = 0

    @Volatile
    private var startedAtMs: Long = 0L

    /**
     * 收口事实的出口（前置②：**只交出去、不收进来**）。
     *
     * 目标这一侧不认识任何账本：写进哪本账、用什么键名，由装配层交给那本条线自己的实现
     * （生产是 [com.example.zhengdao.baopu.evolution.trigger.GoalCloseout]，495 读的那本账）。
     * 不注入 ⇒ 只算不交（JVM 单测与早期启动都安全；不注入不等于出错）。
     */
    @Volatile
    private var closeoutSink: ((GoalRules.CloseoutFact) -> Boolean)? = null

    fun attachCloseoutSink(sink: ((GoalRules.CloseoutFact) -> Boolean)?) {
        closeoutSink = sink
    }

    /**
     * 模板候选的出口（第五百六十八批 B 域③；**只交出去、不收进来**，与 [closeoutSink] 同一手法）。
     *
     * 482 的口径不变：候选只产值（不落库、不启用、不当默认值——`GoalModeTest` 的源级门禁钉着）。
     * 出口只把**已经提炼完的候选**交给装配层注入的实现（灰度链管记不记账，不管判据写法）；
     * 不注入 ⇒ 与 482 逐字节一致（照旧只记那行日志）。
     */
    @Volatile
    private var templateSink: ((GoalRules.TemplateCandidate, Long) -> Unit)? = null

    fun attachTemplateSink(sink: ((GoalRules.TemplateCandidate, Long) -> Unit)?) {
        templateSink = sink
    }

    /** 装配（引擎 init 调一次）：给工作区，并把盘上那份目标接回来（跨轮/跨进程都认账）。 */
    fun attach(workspace: File) {
        root = workspace
        val onDisk = GoalStore.readGoal(workspace)
        active = onDisk
        if (onDisk != null) {
            used = onDisk.stepsUsed
            startedAtMs = onDisk.atMs
        }
    }

    fun active(): GoalRules.Goal? = active

    /** 目标账文件（测试与回查用；没装配 ⇒ null）。 */
    fun ledgerFile(): File? = root?.let { GoalStore.fileOf(it) }

    /**
     * 立目标（人在开工卡上点过头、状冻结之后才调）。
     *
     * @param pledge 板上现役的军令状快照；null ⇒ 立不起来（目标挂在状上，不另开清单）
     * @return null ＝没状；非 null ＝立起来的这份
     */
    fun freeze(
        draft: GoalRules.Draft,
        pledge: PlanPledgeRules.PledgeSnapshot?,
        atMs: Long,
        sessionId: Long,
    ): GoalRules.Goal? {
        pledge ?: return null
        val goal = GoalRules.Goal(
            sentence = draft.sentence,
            criteria = draft.criteria,
            boundaries = draft.boundaries,
            budget = draft.budget,
            state = GoalRules.GoalState.ACTIVE,
            pledgeId = pledge.id,
            total = pledge.entries.size,
            done = 0,
            stepsUsed = 0,
            atMs = atMs,
            sessionId = sessionId,
        )
        active = goal
        used = 0
        startedAtMs = atMs
        root?.let { GoalStore.write(it, goal) }
        RunLog.log(
            "抱朴目标：立目标（判据 ${goal.criteria.size} 条，预算 ${goal.budget.maxSteps} 步，" +
                "账行 goal:${goal.id}；状 pledge:${pledge.id}）",
        )
        return goal
    }

    /** 一次工具调用前的对照：步数 +1，然后判（越界/超预算 ⇒ 停）。 */
    fun tickAndCheck(
        goal: GoalRules.Goal,
        tool: String,
        paths: List<String>,
        nowMs: Long,
    ): GoalRules.StepCheck {
        used += 1
        val elapsed = if (startedAtMs > 0L && nowMs > startedAtMs) nowMs - startedAtMs else 0L
        return GoalRules.stepCheck(goal, tool, paths, used, elapsed)
    }

    /**
     * 收尾：右相按判据判三态、落盘、回一句要贴的 notice。
     *
     * **每条收尾路都要过这里**（正常完成／中止／停止／熔断）：只在「模型不再调工具」那条路结，
     * 被打断的任务就会永远挂在「进行中」——那正是「说不清完成没完成」的另一种形态。
     *
     * 一轮**只结一次**：已结过（状态不是进行中）⇒ 返 null，不重写账、不重复贴话
     * （军令状板子「一轮只结一次」同一条口径）。
     *
     * @param done 收口读数里有结局的项数（军令状给的；没有状 ⇒ 传 0）
     * @param stopped 这次收尾是被打断的（中止/停止/熔断）⇒ 判据没全兑现时记「已停下」而不是「未达成」
     * @return null ＝本来就没目标／已经结过（不编读数）
     */
    fun settle(
        done: Int,
        total: Int,
        exists: (String) -> Boolean,
        atMs: Long,
        stopped: Boolean = false,
    ): GoalRules.Outcome? {
        val current = active ?: return null
        if (current.state != GoalRules.GoalState.ACTIVE) return null
        val verdict = GoalRules.judge(current, exists)
        val state = GoalRules.outcomeState(verdict, stopped)
        val next = current.copy(
            state = state,
            done = done.coerceIn(0, if (total > 0) total else done),
            total = if (total > 0) total else current.total,
            stepsUsed = used,
            atMs = atMs,
        )
        active = next
        root?.let { GoalStore.write(it, next) }
        RunLog.log(
            "抱朴目标：收尾 ${next.state.cn}（判据 ${next.criteria.size} 条，进度 ${next.done}/${next.total} 步，" +
                "预算用 ${GoalRules.percentText(next.stepsUsed, next.budget.maxSteps)}）",
        )
        val notice = if (state == GoalRules.GoalState.STOPPED) {
            GoalRules.stoppedNotice(next, verdict)
        } else {
            GoalRules.settleNotice(next, verdict)
        }
        // 前置②：成功与失败**都要交出去**（失败也是事实）；交失败只记日志，不许把收口带崩
        // （与「账写失败只 log」同一条口径）。
        val fact = GoalRules.closeoutFact(next, verdict, atMs, stopped)
        closeoutSink?.let { sink ->
            val ok = runCatching { sink(fact) }.getOrDefault(false)
            if (!ok) RunLog.log("目标收口事实没交出去（${next.state.cn}）——盘上仍只有 .goal.json")
        }
        // 真收口（已达成/未达成）时提炼一次模板候选：**只记一行日志**，不落库、不启用、不当默认值。
        if (state == GoalRules.GoalState.ACHIEVED || state == GoalRules.GoalState.FAILED) {
            val candidate = GoalRules.templateCandidate(next)
            RunLog.log(
                "目标模板候选（只产不启用）：${candidate.name}｜判据写法 " +
                    "${candidate.criteriaPattern.size} 条｜来源 goal:${candidate.sourceGoalId}",
            )
            // 第五百六十八批 B 域③：候选原样交给候选出口（装配层注入；不注入＝与 482 逐字节一致）。
            // 交出去的是"已经提炼完的值"，收尾路径不许被候选拖挂（与 closeoutSink 同一条纪律）。
            templateSink?.let { sink -> runCatching { sink(candidate, atMs) } }
        }
        return GoalRules.Outcome(next, verdict, notice)
    }

    /**
     * 进度刷新（每一版计划落库成功时调）：把 [done] 与已用步数写进账。
     *
     * 为什么要落盘：`.goal.json` 是**续跑的唯一依据**——只写立目标那一次，进程一重启
     * 进度就归零、预算从 0 起算，「说不清完成没完成」原样回来。写点选在「计划落库成功」
     * 这一格：一次任务里它比工具调用稀疏得多，且进度本来就在这一刻才变。
     */
    fun noteProgress(done: Int) {
        val g = active ?: return
        if (g.state != GoalRules.GoalState.ACTIVE) return
        val next = g.copy(done = done.coerceIn(0, if (g.total > 0) g.total else done), stepsUsed = used)
        active = next
        root?.let { GoalStore.write(it, next) }
    }

    /** 新一轮（新请求＝新任务）：目标跟计划同生命周期。 */
    fun clear() {
        active = null
        used = 0
        startedAtMs = 0L
    }

    /** 直测收尾（生产不调：落点是进程态）。 */
    fun resetForTest() {
        clear()
        root = null
        closeoutSink = null
        templateSink = null
    }
}
