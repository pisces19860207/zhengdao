// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百三十七批 §1（三级降级）＋ §2（求助卡四个可点选项）＋
// §2.5（四件载荷与回灌分界）＋ §2.6（审计明细落点）＋ §3（聚合一行不打扰）。
//
// 病灶（用户 2026-10-26 原话）：「执行任务中的时候某个工具使用失败可以尝试其他工具，没有
// 工具的时候就弹建议卡和提问卡，是不是没有打开技能开关，是不是允许草稿技能……」。
// 237 之前失败分支只有 87 批那一张表：连续三次失败 ⇒ 停下＋一句「换个说法再试」。既没换过
// 参数、没换过等价工具，也没告诉用户「是不是能力没开／是不是这条技能还是草稿」。本文件把
// 「先换再问」补成一条**有界**梯子，并把卡上四件（分类器/推演/审计/后果）与回灌分界算清。
//
// 三条红线在本文件里是**类型上成立**，不是靠自觉：
// ① **工具面固定**：等价候选必须先过 [ToolRegistry.find]。没注册 ⇒ 这条候选直接不成立
//    （228 才交付的 `browser_get_text`/`browser_query` 今天就是这个状态），本文件绝不生成
//    一个不存在的名子，也没有任何「造工具」的出口。
// ② **读不许降级成写**：[equivalentAllowed] 三闸（权限档不升／[ToolSpec.readOnly] 不翻／
//    风险档不升，未入档按 R2 fail-closed），外加 [ToolFallback.FORBIDDEN_TARGETS] 点名禁表
//    （JS 执行点击、shell 写文件）。
// ③ **不自动开能力、不自动转正草稿**：本文件不持有、也不接收任何能改开关或改技能状态的入口。
//    开能力只由卡片选项①让引擎发一次 `capabilities(enable=…)`——那条路内部还要再弹一张
//    御批卡（[ToolRegistry.askAndGrant]），人点才落授权；草稿②只往本轮历史加一句
//    「用户已允许本次使用」＋一条 208 账，既不 `bumpUses` 也不 `promote`
//    （见 [draftAllowedRecord]）。
//
// 边界：判据（梯子／等价／四件／回灌／聚合）零 Android、零线程，时刻一律形参，JVM 直测；
// 只有 [FailureAuditLog]（§2.6 的明细账）与 [ToolFallback.draftsFor] 读盘——照 208 的房型。
//
// ⚠️ 本批不改 [ToolRegistry]（228 在飞占用）：等价表落在这里，组名与成员一律向注册表现读。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.RiskTell
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillStore
import org.json.JSONObject
import java.io.File

// ── D · 卡片四件载荷（派工单 §2.5）────────────────────────────────────────

/**
 * 分类器那件（219 风险矩阵的结论，**完整回灌**给模型——它决定能不能做）。
 *
 * [tier] 用 `R0…R3` 字面量而不是枚举：卡面、审计行、回灌三处都要写它，枚举名一改历史审计
 * 就变哑账（同 208 的 `wire` 纪律）。未入档的工具按 R2 写（fail-closed，与
 * [com.example.zhengdao.baopu.RiskGate.tierOf] 同一口径）。
 */
internal data class RiskBrief(
    val tier: String,
    val reasonCode: String,
    val hardDeny: Boolean,
    val why: String,
) {
    /** 回灌给模型：档位＋原因码＋是否硬拒，一个都不省（§2.5 表第一行「完整回灌」）。 */
    fun feedbackLine(): String = "分类器：$tier（$reasonCode）${if (hardDeny) "硬拒" else "可问"}——$why"

    /** 卡面上给人看的那一行。 */
    fun cardLine(): String = "$tier：$why（判据 $reasonCode${if (hardDeny) "，硬拒" else ""}）"
}

/**
 * 推演那件。
 *
 * ⚠️ 派工单 §2.5 写的「234 推演」在本仓**还不存在**（实测：`推演|Inferred|likelyCause`
 * 全仓 Kotlin 零命中）。所以唯一事实来源是 115 批 [ToolRepairAdvice] 的归因——它只认
 * 错误原文里已有的结构化线索。归因不出来时调用方给 null，不硬编一句（负例⑦）。
 * 回灌必带「推测」二字（§2.5 负例：把推测当事实灌）。
 */
internal data class Inferred(val likelyCause: String, val evidence: List<String>) {
    fun feedbackLine(): String = "推测：$likelyCause"

    fun cardLines(): List<String> =
        listOf("推测：$likelyCause") + evidence.filter { it.isNotBlank() }.map { "证据：$it" }
}

/**
 * 审计那件（208/227）：卡上给人看的**只有这一句聚合**，明细在决策中心审计栏（§2.6）。
 *
 * 类型上装不下明细（只有标签、次数与上次裁决）——「台账明细灌进上下文」这条负例因此
 * 不可能由本函数写出。
 */
internal data class AuditBrief(val label: String, val count: Int, val lastVerdict: String) {
    fun feedbackLine(): String {
        val tail = if (lastVerdict.isBlank()) "" else "，上次判：$lastVerdict"
        return fit("「$label」近 7 天失败 $count 次$tail", ToolFallback.AGG_MAX_CHARS)
    }

    fun cardLine(): String = feedbackLine()
}

/**
 * 后果那件（220）：**给人看完整**（具体动作，禁空话），回灌只一句（≤40 字）。
 *
 * 为什么本批不接 [com.example.zhengdao.baopu.ImpactPreview]：它要 `LocalFacts`（真读盘的
 * 事实口），那个口现在只由控制器为确认卡建（`BaopuChatController` 一处），引擎侧没有接线点，
 * 新接一条就是本批范围外。所以后果按「动作的目标原文＋权限档＋是否只读」给**具体**句子——
 * 宁欠细节，不许写「可能有影响」。
 */
internal data class ImpactBrief(val action: String, val reversible: Boolean, val worst: String) {
    fun feedbackLine(tier: String): String =
        fit("后果：$tier·${if (reversible) "可逆" else "不可逆"}·$worst", ToolFeedback.MAX_LINE_CHARS)

    fun cardLines(): List<String> = listOf(
        "如果继续，会发生什么：$action",
        "最坏后果：$worst（${if (reversible) "可逆" else "不可逆"}）",
    )
}

/** 一张求助卡上挂的四件（派工单 §2.5 的原形状，缺一件就不算四件齐全）。 */
internal data class CardContext(
    val risk: RiskBrief,
    val inference: Inferred?,
    val audit: AuditBrief,
    val impact: ImpactBrief,
) {
    /** R3 或不可逆 ⇒ 卡上红字＋「要你确认」（§2.5 负例最后一条）。 */
    val needsRedConfirm: Boolean get() = risk.tier == RiskClass.R3.name || !impact.reversible

    /**
     * 给人看的四段（[com.example.zhengdao.ui.baopu.HelpCard] 按这个顺序画）。
     *
     * 推演缺失时那一段写「没有线索、不编原因」——不是删段：删段就变成三件卡，
     * 判据「四件齐全」红；留着这一段才是如实。
     */
    fun cardSections(): List<Pair<String, List<String>>> = listOf(
        "分类器" to listOf(risk.cardLine()),
        "推演" to (inference?.cardLines() ?: listOf("错误原文里没有可归因的线索，不编原因")),
        "审计" to listOf(
            audit.cardLine(),
            "明细在决策中心 → 记录 → 工具失败审计（点开看，再点收起）",
        ),
        "后果" to impact.cardLines(),
    )
}

// ── B · 求助卡（四个可点选项，不是一段长文）────────────────────────────────

/** 选项种类（优先级顺序就是派工单 §2 的 ①②③④）。 */
internal enum class HelpOptionKind { GROUP, DRAFT_SKILL, MAKE_SKILL, ASK }

/**
 * 一个可点选项。
 *
 * [id] 是引擎分派的锚，格式固定：`group:<组key>` / `draft:<技能名>` / `make_skill` / `ask`。
 * [tools] 只给①用（≤ [ToolFallback.MAX_GROUP_TOOL_LIST] 个工具名，§2 规则 1）；
 * [questions] 只给④用（≤ [ToolFallback.MAX_QUESTIONS] 问，每问带证据，走 203 的多问卡）。
 */
internal data class HelpOption(
    val id: String,
    val label: String,
    val detail: String,
    val kind: HelpOptionKind,
    val tools: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
)

/** 一次失败的全部已知事实（引擎在失败分支里已经算好的，原样递进来，不重算一遍）。 */
internal data class FailureFacts(
    val call: ToolCall?,
    val tool: String,
    val error: String,
    val kind: FailureKind,
    val advice: ToolRepairAdvice,
    /** 已经试过的路（留痕；§1 判据「两次尝试都留痕」）。 */
    val attempts: List<String> = emptyList(),
)

/**
 * 一张求助卡（纯数据）。
 *
 * 承载走引擎既有的阻塞口 `ask_user`：[toAskRequest] 把它折成 [AskUserRequest]
 * （`subject = "help:<工具名>"` 是给 [com.example.zhengdao.ui.baopu.HelpCard] 的判别锚），
 * 于是「点一下」天然接到 [RunEngine.answerChoice]——不新造第二套弹卡机制，也不改控制器
 * （它不在本批租约里）。四件载荷另经 [ToolHelpPending] 单槽交给卡片本体（选项文本放不下）。
 */
internal data class HelpCardSpec(
    val tool: String,
    val title: String,
    val error: String,
    val attempts: List<String>,
    val context: CardContext,
    val options: List<HelpOption>,
) {
    /** 卡上那句证据（没有尝试留痕就用错误原文——卡不许没有证据）。 */
    val evidence: String get() = attempts.lastOrNull()?.takeIf { it.isNotBlank() } ?: error

    fun toAskRequest(): AskUserRequest = AskUserRequest(
        question = title,
        options = options.map { AskUserOption(it.id, it.label, optionDetail(it)) },
        kind = AskKind.QUESTION,
        subject = "help:$tool",
        detailText = context.cardSections().joinToString("\n") { (head, lines) ->
            "【$head】" + lines.joinToString("") { "\n  $it" }
        },
    )

    /** ④点下去之后的多问卡（203 的形状；一问一句、全 TEXT、都带证据）。 */
    fun questionCardRequest(): AskUserRequest {
        val ask = options.firstOrNull { it.kind == HelpOptionKind.ASK }
        val titles = ask?.questions.orEmpty().ifEmpty { listOf("这一步该怎么做？") }
        return AskUserRequest(
            question = "告诉我该怎么做（$tool）",
            options = emptyList(),
            kind = AskKind.QUESTION,
            subject = "help:$tool",
            questions = titles.take(ToolFallback.MAX_QUESTIONS).mapIndexed { index, title ->
                AskQuestion(
                    id = "q${index + 1}",
                    title = title,
                    kind = AskQuestionKind.TEXT,
                    required = index == 0,
                )
            },
        )
    }

    /** 选项附文：本身那句 ＋ ①的工具清单。 */
    private fun optionDetail(option: HelpOption): String {
        val tools = if (option.tools.isEmpty()) "" else
            "\n组内工具（最多列 ${option.tools.size} 个）：${option.tools.joinToString("、")}"
        return option.detail + tools
    }
}

/**
 * 求助卡的**单槽交接**（引擎写、卡片读）。
 *
 * 为什么要它：`AskUserRequest` 是 203 的形状，塞不下四件分区；把四件拼成一长串文本
 * 又正是负例⑥（「写成一段长文而不是选项」）。所以选项走请求本体、四件走这一格——
 * 纯数据、不持 Context（`StaticFieldLeak` 的教训），用完即清。
 */
@Volatile
internal var ToolHelpPending: HelpCardSpec? = null

// ── A · 三级降级的梯子 ────────────────────────────────────────────────────

/** 梯子三级的**已用状态**（引擎按「这一轮」记，运行开始时复位）。 */
internal data class LadderCounters(
    val stage1Used: Boolean = false,
    val stage2Used: Boolean = false,
    val helpIssued: Boolean = false,
) {
    fun markStage1() = copy(stage1Used = true)
    fun markStage2() = copy(stage2Used = true)

    /** 弹过一张之后：本轮不再弹第二张（§2 规则 5 ＋ 负例⑥⑧）。 */
    fun markHelp() = copy(helpIssued = true)
}

/**
 * 梯子的产物（引擎按分支各自动手，本文件不动盘、不起线程、不调工具）。
 *
 * ⚠️ [LadderPlan.RetryArgs.autoRun]／[LadderPlan.SwapTool.autoRun] 的判据是同一件事：
 * 只有「免确认＋只读＋风险档 ≤R1」的目标才允许引擎自己再跑一次。CONFIRM 档（如
 * `browser_click`）即使找到合法改写也只回灌指令，让那一次调用重新过 219/确认卡——
 * 自动执行它等于绕过人批。
 */
internal sealed interface LadderPlan {
    /** ① 同工具、参数**真的换了**（同样的参数不算这一级，见 [ToolFallback.argsRewrite]）。 */
    data class RetryArgs(val tool: String, val args: JSONObject, val why: String, val autoRun: Boolean) : LadderPlan

    /** ② 换等价工具（已过三闸＋已注册＋参数搬得过去）。 */
    data class SwapTool(
        val from: String,
        val to: String,
        val args: JSONObject,
        val why: String,
        val autoRun: Boolean,
    ) : LadderPlan

    /** ①②的「不可自动执行」形态：引擎不改写、只下达**唯一一次**改写指令给模型。 */
    data class InstructRetry(val tool: String, val stage: String, val note: String) : LadderPlan

    /** ③ 停下问人（求助卡）。 */
    data class Help(val card: HelpCardSpec) : LadderPlan

    /** 梯子走完／卡已弹过：如实收尾，不再重试也不再弹。 */
    data class Stop(val line: String) : LadderPlan
}

/** 等价候选的一次扫描结果（②级与审计行用；`candidates` 已按派工单表的顺序筛过三闸）。 */
internal data class EquivalentScan(
    val from: String,
    val candidates: List<String>,
    val rejected: List<String>,
)

/** ②选项要的草稿事实（纯数据；名字/uses/用途一句）。 */
internal data class FallbackDraft(val name: String, val uses: Int, val scenario: String)

/** 工具/能力组的人话短名（聚合行与审计标签共用；组名沿用 59 批的 key）。 */
internal object ToolLabels {
    fun of(tool: String, group: ToolGroup): String = when (tool) {
        "browser_click" -> "浏览器点击"
        "browser_snapshot" -> "读页快照"
        "browser_open" -> "打开网页"
        "browser_screenshot" -> "网页截图"
        "read_file" -> "读文件"
        "search_in_files" -> "文件内搜索"
        "search_web" -> "联网检索"
        "sheet_read" -> "读表"
        "form_fill" -> "填表"
        "write_file" -> "写文件"
        "edit_file" -> "改文件"
        else -> "${group.key}／$tool"
    }
}

/**
 * 三级降级 ＋ 求助卡 ＋ 审计聚合（本批唯一判据口）。
 *
 * 引擎只用三件事：[ladder]（这一步该走哪级）、[HelpCardSpec] 的分派、[FailureAuditLog]
 * （明细落盘）。所有计数由调用方传进来——本对象**不含任何跨调用状态**，所以「同一步只重试
 * 一次」是引擎的账，不是这里的记忆（可测，也不会因复用而漏复位）。
 */
internal object ToolFallback {

    /** ①②每级**各**最多几次（派工单 §1：只重试一次）。常量：配置改大就是无限重试。 */
    const val MAX_STAGE_ATTEMPTS = 1

    /** 一轮最多几张求助卡（§2 规则 5）。同上，不放进任何配置。 */
    const val MAX_HELP_CARDS_PER_RUN = 1

    /** ①选项列几个工具名（§2 规则 1：最多 5 个）。 */
    const val MAX_GROUP_TOOL_LIST = 5

    /** ④选项的提问数上限（§2 规则 4：≤3 问）。 */
    const val MAX_QUESTIONS = 3

    /** 聚合回看窗口（与 227 [com.example.zhengdao.baopu.AuditHint.WINDOW_MS] 同一天数口径）。 */
    const val AGG_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 「上次人的裁决」在聚合句里占的字数（整句还要过 [AGG_MAX_CHARS]，这里先掐短）。 */
    const val VERDICT_MAX_CHARS = 14

    /** 几次才值得说一句（同 227：`< 3` 不打扰）。 */
    const val AGG_MIN_EVENTS = 3

    /** 顶部那一行与审计聚合的字数上限（同 227 的 40）。 */
    const val AGG_MAX_CHARS = 40

    /** 证据里错误原文的截断长度（同 115 批 [ToolFailureAdvisor.auditLine] 的 160）。 */
    const val ERROR_EVIDENCE_CHARS = 160

    /** 未分类工具按哪档算（与 [com.example.zhengdao.baopu.RiskGate] 的 EXEMPT_TIER 同一数字）。 */
    private const val FAIL_CLOSED_TIER = 2

    /**
     * 等价表（派工单 §1 初版，**保守**：只列原表点名的那几行）。
     *
     * 表里的候选今天不存在也没关系——[equivalentSwap] 先问注册表，问不到就当这条候选不成立
     * （这正是红线①的物理实现：`browser_get_text`/`browser_query` 归 228，没进 main 之前
     * 这一行永远只回「不换」）。
     *
     * 点击/填表**故意不在表里**：派工单给它们的出路是「换定位／换字段匹配顺序」，那是①级的
     * 事；写进②就等于允许「换个工具去点别的东西」。
     */
    val EQUIVALENT: Map<String, List<String>> = mapOf(
        "read_file" to listOf("sheet_read", "browser_get_text"),
        "search_in_files" to listOf("sheet_read"),
        "browser_snapshot" to listOf("browser_get_text", "browser_query"),
        "browser_open" to listOf("browser_snapshot", "browser_get_text"),
        "search_web" to listOf("browser_open", "browser_get_text"),
    )

    /**
     * 点名禁表：任何一级都**不许**落到这些目标（派工单 §1「禁止换」两列的落地）。
     *
     * `browser_evaluate`/`browser_run_js`＝「不许改成用 JS 执行点击」；`bash`/`read`＝
     * 免费通道的兼容占位，用它写文件就是「终端被关就用 shell 模拟」那句被明令禁止的绕道。
     */
    val FORBIDDEN_TARGETS: Set<String> = setOf(
        "browser_evaluate",
        "browser_run_js",
        "browser_script",
        "bash",
        "read",
    )

    /** 会改盘的工具（②级永不自动执行；也是「读 ⇒ 写」双保险的那半张表）。 */
    val WRITE_TOOLS: Set<String> = setOf(
        "write_file", "edit_file", "move_path", "delete_path", "convert_encoding",
        "image_ops", "card_build", "card_export", "library_organize", "kb_index",
        "write_skill", "append_persona", "generate_image", "schedule_task",
    )

    /** 靠「定位」工作的网页交互件（①级的建议按定位阶梯说，不按参数 schema 说）。 */
    val LOCATOR_TOOLS: Set<String> = setOf("browser_click", "browser_type", "browser_select", "form_fill")

    /** 目标参数名（证据行与后果句从这几个键里取；一个都没有就只说工具名，不编路径）。 */
    private val TARGET_KEYS = listOf("path", "target", "selector", "url", "query", "name", "from")

    /** 可用性拒绝的两条原文锚（59/97 批的桩文案；被关与未解锁是两种处置，不能并成一条）。 */
    const val REASON_GROUP_LOCKED = "该工具组未解锁"
    const val REASON_USER_DISABLED = "该工具已被用户关闭"

    /**
     * 这一步该走哪一级（**唯一入口**；除 [ToolRegistry] 的只读查询外不碰任何东西）。
     *
     * 顺序就是派工单的「先换，再问」：①→②→③。某一级**没有证据**就跳过它（不是弹卡，
     * 也不是硬试）；三级走完 ⇒ [LadderPlan.Stop]。
     */
    fun ladder(
        facts: FailureFacts,
        counters: LadderCounters,
        registry: ToolRegistry,
        sessionId: Long,
        drafts: List<FallbackDraft>,
        nowMs: Long = System.currentTimeMillis(),
    ): LadderPlan {
        // 负例⑧：这一轮已经弹过（或被用户忽略过）⇒ 绝不弹第二张，也不再自己试。
        if (counters.helpIssued) return LadderPlan.Stop(ToolFeedback.stoppedLine(facts.tool))
        if (!counters.stage1Used) stage1(facts, registry)?.let { return it }
        if (!counters.stage2Used) stage2(facts, registry, sessionId)?.let { return it }
        return LadderPlan.Help(helpCard(facts, registry, sessionId, drafts, nowMs))
    }

    /** ①：同工具换参数/换定位（只此一次；同样的参数不算换）。 */
    private fun stage1(facts: FailureFacts, registry: ToolRegistry): LadderPlan? {
        val spec = registry.find(facts.tool)
        argsRewrite(facts, spec)?.let { rewritten ->
            return LadderPlan.RetryArgs(
                tool = facts.tool,
                args = rewritten,
                why = facts.advice.reason,
                autoRun = spec != null && autoRunnable(spec) && !hasPlaceholder(rewritten),
            )
        }
        // 点击/填表类：换定位的「新定位」只有模型（它手里有快照）看得见，本批不替它编。
        if (facts.tool in LOCATOR_TOOLS) {
            return LadderPlan.InstructRetry(facts.tool, "①", ToolFeedback.locatorNote(facts))
        }
        return null
    }

    /** ②：换等价工具（三闸＋已注册＋参数不编造）。 */
    private fun stage2(facts: FailureFacts, registry: ToolRegistry, sessionId: Long): LadderPlan? {
        val from = registry.find(facts.tool) ?: return null
        val to = equivalentSwap(facts.tool, registry, sessionId) ?: return null
        val args = carryArgs(from, to, facts.call?.arguments) ?: return null
        return if (autoRunnable(to)) {
            LadderPlan.SwapTool(facts.tool, to.name, args, ToolFeedback.swapWhy(facts.tool, to.name), autoRun = true)
        } else {
            LadderPlan.InstructRetry(facts.tool, "②", ToolFeedback.swapNote(facts.tool, to.name))
        }
    }

    // ── 等价表判据 ────────────────────────────────────────────────────────

    /**
     * 等价候选里第一个合法的（顺序＝派工单表里的顺序）。
     *
     * 三条筛掉的分支：没注册（红线①）、能力组没开（那是③的①选项，不是偷偷解锁）、三闸不过
     * （红线②）。三条都在 [scanEquivalent] 里留名字，好让卡面与审计说得出「问过谁、为什么没换」。
     */
    fun equivalentSwap(from: String, registry: ToolRegistry, sessionId: Long = registry.currentSessionId): ToolSpec? {
        val fromSpec = registry.find(from) ?: return null
        val visible = registry.visibleSpecs(sessionId).map { it.name }.toSet()
        return EQUIVALENT[from].orEmpty().firstOrNull { name ->
            name in visible && registry.find(name)?.let { equivalentAllowed(fromSpec, it) } == true
        }?.let { registry.find(it) }
    }

    /** 候选与落选原因（审计行、卡面附文用）。 */
    fun scanEquivalent(from: String, registry: ToolRegistry, sessionId: Long = registry.currentSessionId): EquivalentScan {
        val fromSpec = registry.find(from)
        val ok = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        EQUIVALENT[from].orEmpty().forEach { name ->
            val to = registry.find(name)
            val visible = to != null && registry.visibleSpecs(sessionId).any { it.name == name }
            when {
                to == null -> rejected += "$name（这个工具还没注册，不能凭空换）"
                !visible -> rejected += "$name（能力组没开，要人点）"
                fromSpec != null && !equivalentAllowed(fromSpec, to) -> rejected += "$name（权限或风险档不允许）"
                else -> ok += name
            }
        }
        return EquivalentScan(from, ok, rejected)
    }

    /**
     * 三闸（派工单 §1 原话：只允许**同权限档或更低**的等价物；**读工具不许降级成写工具**）。
     *
     * 顺序不许调：先禁表（点名禁止比任何档位都硬），再权限档，再 readOnly，最后风险档。
     * 未入档工具按 R2 算（fail-closed）——「不知道多危险」永远不比「已知危险」更许。
     */
    fun equivalentAllowed(from: ToolSpec, to: ToolSpec): Boolean {
        if (to.name == from.name) return false // 同工具是①级，不是②级
        if (to.name in FORBIDDEN_TARGETS) return false
        if (to.permission.ordinal > from.permission.ordinal) return false
        if (from.readOnly && !to.readOnly) return false // 读 ⇒ 写：一律拒
        if (from.readOnly && to.name in WRITE_TOOLS) return false // 表没覆盖到的写件，双保险
        return riskTierOf(to) <= riskTierOf(from)
    }

    /** 风险档序号（R0=0…R3=3；未入档 ⇒ [FAIL_CLOSED_TIER]，只会被判「不许换」）。 */
    fun riskTierOf(name: String): Int = RiskMatrix.baseOf(name)?.ordinal ?: FAIL_CLOSED_TIER

    fun riskTierOf(spec: ToolSpec): Int = if (spec.riskTier.isBlank()) {
        riskTierOf(spec.name)
    } else {
        runCatching { RiskClass.valueOf(spec.riskTier).ordinal }.getOrDefault(FAIL_CLOSED_TIER)
    }

    /** 引擎能不能自己再跑一次（免确认＋只读＋R0/R1；三条同时成立才算能）。 */
    fun autoRunnable(spec: ToolSpec): Boolean =
        spec.permission == ToolPermission.FREE && spec.readOnly && riskTierOf(spec) <= 1

    /**
     * 参数搬运：**只按同名必填键搬**，搬不动就整个不换。
     *
     * `read_file.path` → `sheet_read.path` 搬得动；`search_web.query` → `browser_open.url`
     * 搬不动（那要编一个 URL），于是这一行等价物今天永远只回「不换」。目标没有必填项 ⇒
     * 空参数对象（合法：它本来就不接参数）。
     */
    fun carryArgs(from: ToolSpec, to: ToolSpec, args: JSONObject?): JSONObject? {
        val required = requiredKeys(to)
        if (required.isEmpty()) return JSONObject()
        val source = args ?: return null
        val out = JSONObject()
        required.forEach { key ->
            val value = source.opt(key) ?: return null
            out.put(key, value)
        }
        return out
    }

    /**
     * ①级的「换参数」：只认 115 批从错误原文里解析出的建议参数，且必须**与失败的参数不同**。
     *
     * 一样 ⇒ null（负例①：同参数重试不是「换」，是「撞同一堵墙」）。
     * 建议里出现工具 schema 不认的键也 ⇒ null：那是 115 批之外的幻觉键，
     * 不许被本批当成「换参数」再塞回去。
     */
    fun argsRewrite(facts: FailureFacts, spec: ToolSpec?): JSONObject? {
        val base = facts.call?.arguments ?: return null
        val suggested = facts.advice.suggestedArgs ?: return null
        if (suggested.length() == 0) return null
        if (suggested.toString() == base.toString()) return null
        val allowed = schemaKeys(spec)
        if (allowed.isNotEmpty() && suggested.keys().asSequence().any { it !in allowed }) {
            return null
        }
        return suggested
    }

    /** 建议参数里有没有「待填」占位（占位不是真值 ⇒ 只许回灌指令，不许自动跑）。 */
    fun hasPlaceholder(args: JSONObject): Boolean =
        args.keys().asSequence().any { args.optString(it) == ToolFailureAdvisor.PLACEHOLDER }

    private fun requiredKeys(spec: ToolSpec): List<String> {
        val arr = spec.inputSchema.optJSONArray("required") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun schemaKeys(spec: ToolSpec?): Set<String> =
        spec?.inputSchema?.optJSONObject("properties")?.keys()?.asSequence()?.toSet() ?: emptySet()

    // ── ③ 求助卡 ──────────────────────────────────────────────────────────

    /**
     * 造③的卡（四件载荷＋命中的选项）。
     *
     * 选项按派工单优先级排：①能力组（命中才给）→ ②草稿技能（命中才给）→ ③现做技能 → ④提问。
     * ③④恒在，所以卡永远 ≥2 个可点项（[AskUserTool.MIN_OPTIONS]）且 ≤4 个
     * （[AskUserTool.MAX_OPTIONS]）——「写成一段长文」在这个类型里出不来。
     */
    fun helpCard(
        facts: FailureFacts,
        registry: ToolRegistry,
        sessionId: Long,
        drafts: List<FallbackDraft>,
        nowMs: Long = System.currentTimeMillis(),
    ): HelpCardSpec {
        val spec = registry.find(facts.tool)
        val group = spec?.group ?: ToolGroup.CORE
        // ①②命中才给，③④恒在。①②最多各一条 ⇒ 前两条之后永远还留得下③④，
        // 所以既不会超 [AskUserTool.MAX_OPTIONS]，也不会出现「只有一个问题没有出路」。
        // ④排在最后：派工单说它是"最后出路"，挤掉③也不挤它。
        val options = mutableListOf<HelpOption>()
        optionGroup(facts, registry, sessionId, group)?.let { options += it }
        optionDraft(drafts, facts, group)?.let { options += it }
        if (options.size < AskUserTool.MAX_OPTIONS - 1) options += makeSkillOption()
        options += HelpOption(
            id = ToolFeedback.OPTION_ASK,
            label = "告诉我该怎么做",
            detail = askDetail(facts),
            kind = HelpOptionKind.ASK,
            questions = questionTitles(facts),
        )
        return HelpCardSpec(
            tool = facts.tool,
            title = titleOf(facts, registry, sessionId),
            error = fit(facts.error, ERROR_EVIDENCE_CHARS),
            attempts = attemptsOf(facts),
            context = cardContext(facts, registry, spec, group, nowMs),
            options = options,
        )
    }

    /** ③选项（恒在）：进 235 的观察/示教，做成草稿再跑。 */
    private fun makeSkillOption() = HelpOption(
        id = ToolFeedback.OPTION_MAKE_SKILL,
        label = "让我先做一个技能再执行",
        detail = "进玄览的观察/示教，你演示一遍，我存成草稿技能再跑这一步。" +
            "不当场编一条假技能硬跑——做不出技能就照实说没做成。",
        kind = HelpOptionKind.MAKE_SKILL,
    )

    private fun askDetail(facts: FailureFacts): String =
        "你口述这一步的正确做法（≤$MAX_QUESTIONS 问，一问一句，每问带着刚才的证据：" +
            fit(targetOf(facts), 24) + "）。"

    /** 标题那句（§2 模板头：这次没做成：…（试过…））。 */
    private fun titleOf(facts: FailureFacts, registry: ToolRegistry, sessionId: Long): String {
        val tried = facts.attempts.map { it.trim() }.filter { it.isNotEmpty() }
        val head = "这次没做成：${targetOf(facts)}（${fit(facts.error, 40)}）"
        val tail = if (tried.isEmpty()) "" else "｜试过：${fit(tried.joinToString("→"), 40)}"
        return head + tail + blockedHint(facts, registry, sessionId)
    }

    /** 被用户在设置里关掉的组不假装「点一下就开」——与 capabilities 那一条同样的实话。 */
    private fun blockedHint(facts: FailureFacts, registry: ToolRegistry, sessionId: Long): String {
        val group = registry.find(facts.tool)?.group ?: return ""
        return if (group in registry.settings().disabledGroups) {
            "｜「${group.key}」这一组被你在设置里关掉了，卡上不能替你打开"
        } else {
            ""
        }
    }

    /** ①选项：这一步要的能力组没解锁（被设置关掉/兼容组 ⇒ 不给这一项，只说实话）。 */
    private fun optionGroup(
        facts: FailureFacts,
        registry: ToolRegistry,
        sessionId: Long,
        group: ToolGroup,
    ): HelpOption? {
        val hidden = registry.filteredReasons(sessionId).any { it.first == facts.tool }
        if (!hidden || group == ToolGroup.COMPAT || group in registry.settings().disabledGroups) return null
        val names = ToolGroupTable.namesOf(group).filter { it != facts.tool }.take(MAX_GROUP_TOOL_LIST)
        return HelpOption(
            id = ToolFeedback.OPTION_GROUP_PREFIX + group.key,
            label = "开启「${group.key}」能力",
            detail = "这一步要的「${group.oneLine}」现在模型看不见。" +
                "点了还要再确认一次（本次会话/一直/取消）——我不会自己开。",
            kind = HelpOptionKind.GROUP,
            tools = names,
        )
    }

    /** ②选项：草稿技能命中这一步的能力域。 */
    private fun optionDraft(drafts: List<FallbackDraft>, facts: FailureFacts, group: ToolGroup): HelpOption? {
        val hit = matchDraft(drafts, facts.tool, group) ?: return null
        return HelpOption(
            id = ToolFeedback.OPTION_DRAFT_PREFIX + hit.name,
            label = "本条技能是草稿，允许这次使用",
            detail = "「${hit.name}」还是草稿（uses=${hit.uses}），默认不参与执行。" +
                "点了只这一次生效：uses 不变、技能仍留在草稿，不转正。",
            kind = HelpOptionKind.DRAFT_SKILL,
        )
    }

    /** ④的提问标题（≤3 问，每问都带当前这一步的证据；拿不到目标就只问一句）。 */
    private fun questionTitles(facts: FailureFacts): List<String> {
        val out = mutableListOf<String>()
        out += "这一步该用哪个目标？（现在试的是 ${fit(targetOf(facts), 30)}）"
        if (facts.tool in LOCATOR_TOOLS) {
            out += "页面上它的名字/角色是什么？（快照里的 ref 句柄也行，如 @e3）"
        }
        if (out.size < MAX_QUESTIONS) out += "错误原文：${fit(facts.error, 60)}"
        return out.take(MAX_QUESTIONS)
    }

    /** 试过什么（留痕；卡面与审计行都读这一份）。 */
    private fun attemptsOf(facts: FailureFacts): List<String> {
        val base = facts.attempts.filter { it.isNotBlank() }
        val evidence = "${facts.tool} ${targetOf(facts)} ⇒ ${fit(facts.error, ERROR_EVIDENCE_CHARS)}"
        return if (base.isEmpty()) listOf(evidence) else base + evidence
    }

    /** 目标短名（按固定几个键取参数；一个都没有就只说工具名，不编路径）。 */
    fun targetOf(facts: FailureFacts): String {
        val args = facts.call?.arguments ?: return facts.tool
        for (key in TARGET_KEYS) {
            val value = args.optString(key)
            if (value.isNotBlank()) return shortTarget(value)
        }
        return facts.tool
    }

    private fun shortTarget(raw: String): String {
        val one = raw.replace('\n', ' ').trim()
        val tail = one.substringAfterLast('/').substringAfterLast('\\')
        return if (tail.length >= 3) tail else one.take(24)
    }

    // ── 四件怎么填 ────────────────────────────────────────────────────────

    /** 分类器＋推演＋审计＋后果。 */
    fun cardContext(
        facts: FailureFacts,
        registry: ToolRegistry,
        spec: ToolSpec?,
        group: ToolGroup,
        nowMs: Long,
    ): CardContext = CardContext(
        risk = riskBrief(facts),
        inference = inferredOf(facts),
        audit = auditBrief(facts, group, FailureAuditLog.readAll(), nowMs),
        impact = impactBrief(facts, spec ?: registry.find(facts.tool)),
    )

    /** 219 的结论（走 [RiskMatrix.classify]，文案走 223 批的 [RiskTell]，不在这里另拼一句）。 */
    fun riskBrief(facts: FailureFacts): RiskBrief {
        val call = facts.call ?: ToolCall("fallback-${facts.tool}", facts.tool, JSONObject())
        val verdict = RiskMatrix.classify(call)
        val tier = verdict.risk?.name ?: RiskClass.R2.name
        return RiskBrief(
            tier = tier,
            reasonCode = verdict.reasonCode.id,
            hardDeny = verdict.hardDeny,
            why = RiskTell.cardHint(verdict) ?: "这一步按 $tier 档处理",
        )
    }

    /** 推演＝115 批的归因；归因不出来就 null（负例⑦的红线）。 */
    fun inferredOf(facts: FailureFacts): Inferred? {
        val advice = facts.advice
        if (advice.cause == ToolFailureCause.UNKNOWN) return null
        val evidence = listOf("${facts.tool}：${fit(facts.error, ERROR_EVIDENCE_CHARS)}") +
            facts.attempts.filter { it.isNotBlank() }
        return Inferred("${advice.reason}（依据：${advice.type.label}）", evidence)
    }

    /**
     * 审计＝近 7 天同类失败的**一句聚合**（明细不进卡，更不进上下文）。
     *
     * 「上次人的裁决」不另设字段：引擎在用户点卡那一刻就会落一条 `actor=user` 的明细行
     * （outcome 写的是「用户选了「…」」），再单独存一份 verdict 就是两处记账迟早分家。
     * 行的顺序按 [FailureAuditLog.readAll] 的口径（新→旧），所以「上次」＝第一条人的行。
     */
    fun auditBrief(facts: FailureFacts, group: ToolGroup, rows: List<FailureAuditRow>, nowMs: Long): AuditBrief {
        val label = ToolLabels.of(facts.tool, group)
        val inWindow = rows.filter { it.matches(facts.tool, group) && nowMs - it.atMs in 0..AGG_WINDOW_MS }
        return AuditBrief(
            label,
            inWindow.size,
            verdictOf(inWindow.firstOrNull { it.actor == DecisionActor.USER.wire }?.outcome.orEmpty()),
        )
    }

    /**
     * 明细行的 outcome 是给台账看的整句（「用户选了「开启「browser」能力」」），
     * 聚合那句只有 [VERDICT_MAX_CHARS] 个字的位置，所以剥掉外壳留里头的选择名——
     * 连着「用户选了」一起截，截出来的是工具名的一半，等于没说。
     */
    private fun verdictOf(outcome: String): String {
        val inner = if (outcome.startsWith("用户选了「") && outcome.endsWith("」")) {
            outcome.removePrefix("用户选了「").removeSuffix("」")
        } else {
            outcome
        }
        return fit(inner, VERDICT_MAX_CHARS)
    }

    /** 后果：动作原文＋可逆否＋最坏后果（禁空话；空话词表见 [ToolFeedback.isVague]）。 */
    fun impactBrief(facts: FailureFacts, spec: ToolSpec?): ImpactBrief {
        val target = targetOf(facts)
        val readOnly = spec?.readOnly ?: false
        return when {
            facts.tool in LOCATOR_TOOLS -> ImpactBrief(
                action = "点/填页面上的 $target（这一步会真的改页面状态）",
                reversible = false,
                worst = "再点一次就可能提交，提交之后撤不回",
            )
            readOnly -> ImpactBrief(
                action = "读 $target（只读，不改任何东西）",
                reversible = true,
                worst = "最多白读一次，盘上一个字节都不动",
            )
            facts.tool in WRITE_TOOLS -> ImpactBrief(
                action = "写/改 $target",
                reversible = false,
                worst = "已有内容被覆盖就找不回来",
            )
            else -> ImpactBrief(
                action = "${facts.tool} $target",
                reversible = false,
                worst = "不确认只读性质的工具一律按改得动、改不回来处理",
            )
        }
    }

    /**
     * §3 的聚合一行（同能力/同技能近 7 天失败 ≥[AGG_MIN_EVENTS] ⇒ 一句 ≤40 字，不弹窗）。
     *
     * @return null = 不到次数/窗口外（不打扰）
     */
    fun aggregateLine(
        tool: String,
        group: ToolGroup,
        rows: List<FailureAuditRow>,
        nowMs: Long,
    ): String? {
        val count = rows.count { it.matches(tool, group) && nowMs - it.atMs in 0..AGG_WINDOW_MS }
        if (count < AGG_MIN_EVENTS) return null
        val label = ToolLabels.of(tool, group)
        // 组名从 [ToolGroup.oneLine] 的前半截取（不另造一张名字表：两处名字迟早分家）。
        // 默认可见的两组本来就一直开着，"要不要开"对它是假话 ⇒ 那句改问做法。
        val unlock = if (group in ToolGroup.DEFAULT_VISIBLE) {
            "要不要换个做法"
        } else {
            "要不要开「${shortGroupName(group)}」"
        }
        return fit("「$label」近 7 天 $count 次没成——$unlock", AGG_MAX_CHARS)
    }

    /** 组短名（`oneLine` 冒号前那半截，如「网页操作」）。 */
    private fun shortGroupName(group: ToolGroup): String =
        group.oneLine.substringBefore("：").trim().ifBlank { group.key }

    /** 能力组没开／被用户关这两条拒绝原文（引擎按它决定要不要出①形状的卡）。 */
    fun isGroupLockedRejection(text: String): Boolean = text.contains(REASON_GROUP_LOCKED)

    fun isUserDisabledRejection(text: String): Boolean = text.contains(REASON_USER_DISABLED)

    // ── ②选项的执行侧：只记账＋只本轮生效（红线③）────────────────────────

    /**
     * 「本次允许草稿技能」的 208 账（派工单 §2 规则 2：写 208、可回滚栏不给按钮）。
     *
     * 为什么 `reversible=false`：本次允许只活在这一轮，没有可撤销的持久状态——标成可回滚
     * 就是骗人（同 208 对 `read_scope` 的那条口径）。
     *
     * 这里**不**出现 `bumpUses`／`promoteBody`／`commitSkillDraft` 任何调用：转正是治理器的事。
     */
    fun draftAllowedRecord(skill: String, uses: Int, sessionId: Long, nowMs: Long): DecisionDraft = DecisionDraft(
        kind = DecisionKind.AUTO_ACT,
        actor = DecisionActor.USER,
        reason = "只本次允许草稿技能「$skill」参与执行",
        evidence = "draft:$skill",
        before = "draft(uses=$uses，默认不参与)",
        after = "draft(uses=$uses，本轮允许)",
        atMs = nowMs,
        sessionId = sessionId,
        reversible = false,
    )

    /** ②生效后回灌给模型的那句（uses 不变、仍 draft 都写在里面，防它以为已转正）。 */
    fun draftAllowedNote(skill: String, uses: Int, body: String): String =
        "用户允许草稿技能「$skill」仅本次使用（uses 仍为 $uses，技能仍是草稿，没转正）。\n" +
            "这一步按它做：${fit(body, 300)}"

    /**
     * ①选项的执行：一次 `capabilities(enable=[组])` 调用（引擎拿去走 [GuardPipeline.execute]）。
     *
     * 为什么是「发一次调用」而不是直接改开关：`capabilities` 里那条 `askAndGrant` 会再弹一张
     * 御批卡，人点「本次会话/一直」才落授权——本批一个字的授权都没自己写过。
     */
    fun groupEnableCall(groupKey: String, callId: String): ToolCall = ToolCall(
        id = callId,
        name = CapabilitiesTool.NAME,
        arguments = JSONObject().put(
            CapabilitiesTool.ARG_ENABLE,
            org.json.JSONArray().put(groupKey),
        ),
    )

    // ── 草稿技能候选（②的输入；只读扫描，一个字节都不写）───────────────────

    /**
     * 项目技能目录里的草稿。
     *
     * 取项目级不是偷懒：②的语义是「这条技能就在你正在做的这件事里」，全局草稿该由技能库那页
     * 处理（233 在飞）。引擎手里只有工作区根，全局根在装配层（240 在飞）⇒ 本批不越界去拿。
     * 这条取舍写进交付报告请蓝总管裁定。
     */
    fun draftsFor(projectRoot: File): List<FallbackDraft> = runCatching {
        SkillRules.projectSkillDir(projectRoot).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.mapNotNull { sub ->
                val body = runCatching { File(sub, SkillRules.SKILL_FILE).readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@mapNotNull null
                if (SkillStore.statusOf(body) != "draft") return@mapNotNull null
                FallbackDraft(sub.name, SkillStore.usesOf(body), SkillStore.purposeOf(body))
            }.orEmpty()
    }.getOrDefault(emptyList())

    /** 名字或用途里出现这一步的领域词 ⇒ 算命中（词表来自组名与工具名，不是技能库自己报的）。 */
    fun matchDraft(drafts: List<FallbackDraft>, tool: String, group: ToolGroup): FallbackDraft? {
        val tokens = domainTokens(tool, group)
        return drafts.firstOrNull { draft ->
            val hay = "${draft.name} ${draft.scenario}".lowercase()
            tokens.any { it.isNotBlank() && hay.contains(it) }
        }
    }

    private fun domainTokens(tool: String, group: ToolGroup): List<String> {
        val en = tool.split('_').filter { it.length >= 3 }.map { it.lowercase() }
        val cn = group.oneLine.split('：', '、', '，', '。', '（', '）').map { it.trim() }.filter { it.isNotEmpty() }
        return (en + cn).distinct()
    }
}

// ── 回灌分界（§2.5 表的右列）与选项 id 常量 ────────────────────────────────

/**
 * 回灌给模型的句子（**明细在这里根本不可达**：[feedbackForModel] 只收 [AuditBrief]，
 * 不收行列表——「审计明细进上下文」这条负例在签名层就不成立，另有源码闸测钉着）。
 *
 * 四条各有负例钉：分类器完整／推演带「推测」／审计只一句聚合（≤40 字）／后果一句。
 * 删掉审计那一句 ⇒ `ToolFallbackFeedbackTest` 红（派工单 §2.5 判据倒数第二条）。
 */
internal object ToolFeedback {

    /** 选项 id 锚（①带组 key，②带技能名）。 */
    const val OPTION_GROUP_PREFIX = "group:"
    const val OPTION_DRAFT_PREFIX = "draft:"
    const val OPTION_MAKE_SKILL = "make_skill"
    const val OPTION_ASK = "ask"

    /** 回灌单行字数上限（同 220 [com.example.zhengdao.baopu.ImpactInject.MAX_CHARS]）。 */
    const val MAX_LINE_CHARS = 40

    /** 空话词表（§2.5「不许写'可能有影响'这种空话」；单测逐条钉，也是构建期自检的靶子）。 */
    val VAGUE: List<String> = listOf("可能有影响", "或有影响", "视情况", "影响未知", "可能会有一些影响")

    /** ③之后/梯子走完的收尾句：如实说「没做成」，不假装成功、不绕道。 */
    fun stoppedLine(tool: String): String =
        "这一步没做成：$tool 连续失败，能换的参数与等价工具都试过或都不成立。" +
            "求助卡也没有答案，先停下——不许硬编、不许假装成功、不许绕道。"

    /** ①级给点击类的「唯一一次改写」指令（不替模型编定位）。 */
    fun locatorNote(facts: FailureFacts): String {
        val t = ToolFallback.targetOf(facts)
        if (facts.tool == "form_fill") {
            // §1 等价表那一行：`form_fill` 的①是**换字段匹配顺序**，不是换定位；
            // 「不许点提交」这一条本批不新造闸——`FormFillTool` 自己就不做提交（它只填字段），
            // 梯子拿不到「提交」这个动作，所以这里只把实话写在指令里。
            return "同一个表单只许再试这一次：换字段名的匹配顺序（先标签文本、再 name/placeholder、" +
                "最后邻近文案），对不上就停下来问用户。form_fill 只填不提交，提交那一下由人在页面上自己点。"
        }
        return "同一个目标只许再试这一次：把定位从「$t」换成快照里的 ref 句柄（@eN）或 role+name；" +
            "拿不到就停下来问用户。不许用同样参数重撞，也不许改用 JS 执行点击。"
    }

    /** ②级给模型的换工具指令（目标已注册但引擎不能自动跑的那一档）。 */
    fun swapNote(from: String, to: String): String =
        "「$from」这条走不通，同一个目标改用「$to」再试一次（只一次）。" +
            "它也要确认卡就照旧弹卡，不许跳过。"

    /** ②级自动执行时留痕那句（审计与 trace 共用）。 */
    fun swapWhy(from: String, to: String): String = "等价降级：$from → $to（同权限档或更低、且仍是只读）"

    /** ④的答案回灌（问题卡答完原样交回模型）。 */
    fun answersLine(answers: Map<String, String>): String =
        if (answers.isEmpty()) {
            "用户没有回答提问卡，这一步按没做成处理。"
        } else {
            "用户对口述步骤的回答：" + answers.entries.joinToString("；") { (k, v) -> "$k=${v.trim()}" }
        }

    /** ①选项点开之后回灌那句（说清「还要人点御批卡」，防模型以为已经生效）。 */
    fun groupAskedLine(groupKey: String): String =
        "已向用户申请启用「$groupKey」这一组；用户点了才算数，本轮下一轮才可能看得见。"

    /** 用户忽略/取消求助卡那句（红线：不许假装成功）。 */
    fun ignoredLine(tool: String): String =
        "用户没有回应这张求助卡，这一步没做成：$tool。先停下，别再弹同一张，也不许换别的招硬凑。"

    /**
     * 四件按 §2.5 分界合成一条回灌（下一轮模型看得见的那段）。
     *
     * 顺序固定（分类器→推测→审计聚合→后果），删任何一行都有单测钉着；参数里**没有任何
     * 明细类型**，所以「审计明细进上下文」这条负例在本函数签名层就写不出来。
     */
    fun feedbackForModel(
        risk: RiskBrief,
        inference: Inferred?,
        audit: AuditBrief,
        impact: ImpactBrief,
        outcome: String,
    ): String = buildString {
        append("降级梯子结局：").append(outcome).append('\n')
        append(risk.feedbackLine()).append('\n')
        inference?.let { append(it.feedbackLine()).append('\n') }
        append(audit.feedbackLine()).append('\n')
        append(impact.feedbackLine(risk.tier))
    }

    /** 求助卡被点掉之后的回灌（四件 ＋ 「用户选了哪一项」）。 */
    fun helpFeedback(card: HelpCardSpec, pickedLabel: String): String = feedbackForModel(
        risk = card.context.risk,
        inference = card.context.inference,
        audit = card.context.audit,
        impact = card.context.impact,
        outcome = "停下问人，用户选了「$pickedLabel」",
    )

    /** 空话检查（卡面与回灌两处都用它兜底；构建期由单测跑全表）。 */
    fun isVague(text: String): Boolean = VAGUE.any { text.contains(it) }
}

// ── §2.6 · 工具失败审计明细（决策中心·审计栏的唯一账）───────────────────────

/**
 * 一行明细（§2.6 展开后的那一行：时间·动作·档位·谁定的·结果·证据）。
 *
 * 与 208 的 `决策记录.jsonl` **分文件**：那本账记「已经动过什么」（带 kind/reversible/回滚），
 * 这本记「失败与降级的过程」。混记会让 `DecisionKind` 那个闭集多出一个不是决策的类
 * （`DecisionKind.REQUIRED` 那道闸会红），而且失败本身没改盘，标成可回滚就是骗人。
 */
internal data class FailureAuditRow(
    val id: String,
    val atMs: Long,
    val tool: String,
    val groupKey: String,
    /** `①`／`②`／`③`／`忽略`（梯子走到哪一级）。 */
    val stage: String,
    val risk: String,
    val reasonCode: String,
    /** `model`／`user`／`auto`（沿用 208 的 [DecisionActor] 线值，别造第四种）。 */
    val actor: String,
    /** `换参数`／`换工具`／`成`／`败`／`问`／`忽略`。 */
    val outcome: String,
    val evidence: String,
) {
    /** 同类匹配（§3「同一能力/同一技能」）：同工具，或同一能力组。 */
    fun matches(tool: String, group: ToolGroup): Boolean = tool == this.tool || group.key == this.groupKey

    fun toJson(): JSONObject = JSONObject()
        .put("v", FailureAuditLog.VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("tool", tool)
        .put("group", groupKey)
        .put("stage", stage)
        .put("risk", risk)
        .put("reason", reasonCode)
        .put("actor", actor)
        .put("outcome", outcome)
        .put("evidence", evidence)

    companion object {
        /** 认不出 id/tool/时刻 ⇒ null（坏行跳过，不当成别的类，照 208 的解析纪律）。 */
        fun fromJson(o: JSONObject): FailureAuditRow? {
            val id = o.optString("id")
            val tool = o.optString("tool")
            val at = o.optLong("at", 0L)
            if (id.isEmpty() || tool.isEmpty() || at <= 0L) return null
            return FailureAuditRow(
                id = id,
                atMs = at,
                tool = tool,
                groupKey = o.optString("group"),
                stage = o.optString("stage"),
                risk = o.optString("risk"),
                reasonCode = o.optString("reason"),
                actor = o.optString("actor"),
                outcome = o.optString("outcome"),
                evidence = o.optString("evidence"),
            )
        }
    }
}

/**
 * 明细账（JSONL；落点跟 208 同一目录、不同文件，所以本批不新增装配口）。
 *
 * 纪律（§2.6 逐条）：
 * - **只读**：只有 [append] 与 [page]，没有任何改行/删行出口；
 * - 分页：一次 [PAGE] 行（「一次最多渲染 50 行，超出给加载更多」）；
 * - 内容**永不进模型上下文**：回灌侧只吃 [AuditBrief]（见 [ToolFeedback.feedbackForModel]）。
 */
internal object FailureAuditLog {

    /** 文件名。 */
    const val FILE_NAME = "工具失败审计.jsonl"

    /** 行格式版本（改字段必须 +1，否则老行读出来是错的）。 */
    const val VERSION = 1

    /** 一次渲染几行（§2.6）。 */
    const val PAGE = 50

    /** 一次读盘多少行（比一页多，给「加载更多」留余量；上限防住整本台账进内存）。 */
    const val READ_LIMIT = 500

    /** 落点：与 208 同一目录（装配层 [DecisionLedger.install] 注入；没装配 ⇒ 不落）。 */
    fun file(): File? = DecisionLedger.installedRoot()?.let { File(it, FILE_NAME) }

    /** 最近的写失败（只给日志与测试；写失败**不许**影响判定）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 追加一行（缺证据/缺时刻 ⇒ 不落，照 208 的「宁可无账」纪律）。 */
    @Synchronized
    fun append(row: FailureAuditRow): Boolean {
        if (row.evidence.isBlank() || row.atMs <= 0L) {
            lastError = "工具失败审计缺证据或时刻，不落"
            return false
        }
        val f = file() ?: run {
            lastError = "工具失败审计没装配落点"
            return false
        }
        return runCatching {
            f.parentFile?.mkdirs()
            f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
            lastError = null
            true
        }.getOrElse {
            lastError = "工具失败审计写失败：${it.javaClass.simpleName}: ${it.message}"
            false
        }
    }

    /** 现读（新→旧；坏行跳过，不炸解析）。 */
    fun readAll(limit: Int = READ_LIMIT): List<FailureAuditRow> = runCatching {
        val f = file() ?: return@runCatching emptyList()
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull(::parse)
            .sortedByDescending { it.atMs }
            .take(limit)
            .toList()
    }.getOrElse {
        lastError = "工具失败审计读失败：${it.javaClass.simpleName}: ${it.message}"
        emptyList()
    }

    /**
     * 分页读（§2.6 的「加载更多」）。
     *
     * @return 这一页的行 ＋ 还有没有下一页
     */
    fun page(offset: Int, limit: Int = PAGE): Pair<List<FailureAuditRow>, Boolean> {
        val safeOffset = offset.coerceAtLeast(0)
        val rows = readAll(safeOffset + limit + 1)
        return rows.drop(safeOffset).take(limit) to (rows.size > safeOffset + limit)
    }

    /** 一行 ⇒ 一条。 */
    fun parse(raw: String): FailureAuditRow? {
        if (raw.isBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return FailureAuditRow.fromJson(o)
    }

    /** 造一行（id 可 grep：`tf-<时刻>-<工具>`；同一毫秒撞车由 [atMs] 的单线程写点避免）。 */
    fun row(
        tool: String,
        group: ToolGroup,
        stage: String,
        risk: RiskBrief,
        actor: DecisionActor,
        outcome: String,
        evidence: String,
        atMs: Long,
    ): FailureAuditRow = FailureAuditRow(
        id = "tf-$atMs-$tool",
        atMs = atMs,
        tool = tool,
        groupKey = group.key,
        stage = stage,
        risk = risk.tier,
        reasonCode = risk.reasonCode,
        actor = actor.wire,
        outcome = outcome,
        evidence = fit(evidence, ToolFallback.ERROR_EVIDENCE_CHARS),
    )
}

/** 单行＋限长（227 的 `fit` 同口径；聚合与证据都靠它兜底）。 */
private fun fit(raw: String, max: Int): String {
    val one = raw.replace(Regex("""\s+"""), " ").trim()
    return if (one.length <= max) one else one.take(max - 1) + "…"
}
