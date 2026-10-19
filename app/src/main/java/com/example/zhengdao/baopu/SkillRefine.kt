// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十九批 §2.2/§2.3/§2.4（写索引三路、提炼口径、会话封卷信号）
// 与 §4 判据 K6/K7。房型照 `MemoryRefine`：纯函数、不跑模型、确定性输出。
package com.example.zhengdao.baopu

/**
 * 会话封卷的**技能提炼**（第十九批）——纯函数：会话事实进、技能候选出。
 *
 * 与记忆提炼（[MemoryRefine]，每轮 Settled 触发）的分工（派工单 §2.4-4 定稿）：
 * **记忆候选走每轮，技能提案只走会话封卷**——封卷一次最多 3 条；「用户明说」的
 * 句子优先，确认卡上注明「这是你说的」。
 */
internal object SkillRefine {

    /** 一次封卷最多出几条技能候选（与记忆提炼 MAX_CANDIDATES=3 同口径，派工单 §2.3）。 */
    const val MAX_CANDIDATES = 3

    /** 场景一句话的字符上限（索引要短；索引行整体还受人设层 8000 tokens 约束）。 */
    const val SCENARIO_LIMIT = 80

    /**
     * 「用户明说要做技能」的标记词（派工单 §2.2-2 例句：记住这个流程）。
     * 命中 ⇒ 高置信候选（确认卡上注明「这是你说的」）。
     */
    val REQUEST_MARKERS = listOf(
        "记住这个流程", "记住流程", "记住这套流程", "沉淀成技能", "做成技能", "存成技能",
    )

    /** 技能提炼的触发点（K6 的靶子）。 */
    enum class Trigger {
        /** 点「新会话」＝旧会话封卷 ⇒ 立即提炼一次。 */
        CLOSE_AND_REFINE,

        /** 启动自检补算（封了卷没提炼成功的）。 */
        SELF_CHECK,
    }

    /**
     * 触发点判据（**K6 的钉子**）：技能提炼只挂在「会话封卷」与「启动补算」；
     * **每轮结束（Settled）留给记忆候选**——在那触发技能提案＝确认卡每轮刷屏
     * （派工单 §2.4-4 原话「禁止在每轮结束触发技能提炼」）。控制器接线必须走这里。
     */
    fun skillTriggerFor(engineEvent: String): Trigger? = when (engineEvent) {
        "settled" -> null
        "new_session" -> Trigger.CLOSE_AND_REFINE
        "startup" -> Trigger.SELF_CHECK
        else -> null
    }

    /** 一条技能候选（scope 决定进 B 层还是 C 层；fromUser = 用户明说）。 */
    data class Candidate(
        val scope: MemoryScope,
        val name: String,
        val scenario: String,
        val fromUser: Boolean,
    )

    /** 提炼结果：候选（≤[MAX_CANDIDATES]）＋ 被丢的条数（同批去重与超限；不静默）。 */
    data class Result(val candidates: List<Candidate>, val dropped: Int)

    /**
     * 封卷提炼（**纯函数、确定性**）：用户原话里命中 [REQUEST_MARKERS] 的句子 ⇒
     * 高置信候选；同批同名去重、已有同名技能索引的跳过（不是丢，是不重复提）；
     * 截到 [MAX_CANDIDATES]。
     *
     * 「值得做成流程」的确定性判据就一条：**用户明说**。模型自行脑补的「这值得沉淀」
     * 不出候选（确认卡每轮刷屏是事故，宁可漏——派工单 §2.3「用户明说优先」的落地）。
     */
    fun refine(facts: MemoryRefine.SessionFacts, existing: List<SkillRules.IndexLine>): Result {
        val all = mutableListOf<Candidate>()
        val seenNames = LinkedHashSet<String>()
        for (text in facts.userTexts) {
            for (sentence in MemoryRefine.sentences(text)) {
                if (REQUEST_MARKERS.none { sentence.contains(it) }) continue
                val scenario = sentence.take(SCENARIO_LIMIT)
                val name = MemoryRules.slugOf(sentence.take(18).ifBlank { "skill" })
                    .ifEmpty { "skill" }
                if (!seenNames.add(name)) continue // 同批同名去重（先见先立）
                if (existing.any { it.name == name }) continue // 已有同名索引 ⇒ 不重复提（不算丢）
                val scope = if (sentence.contains("全局")) MemoryScope.GLOBAL else MemoryScope.PROJECT
                all += Candidate(scope, name, scenario, fromUser = true)
            }
        }
        val kept = all.take(MAX_CANDIDATES)
        // 丢的 = 收集到的候选 − 留下的（超限截断的那截；不静默，壳层说出来）
        return Result(kept, (all.size - kept.size).coerceAtLeast(0))
    }

    // ── 「这次学到」提案卡（第三十七批 B 段）───────────────────────────────
    //
    // 与上面 `refine` 的分工（别混）：`refine` 走**会话封卷**、判据是「用户明说要做技能」
    // （K6 钉死：每轮结束不许触发技能提炼）；本段是**一次 run 正常收尾**的「这次学到」卡，
    // 判据是「本轮真的动过东西」——且**只出卡，不落盘**，用户点采纳才写（唯一落盘口走
    // SkillStore.apply）。两条链互不替换，`skillTriggerFor` 一行未动。

    /** 用户「明说要记住」的标记词（命中 ⇒ 高置信，排在最前）。 */
    val LEARN_MARKERS = listOf("记住", "记下来", "以后都这样", "以后就这么做", "记一下")

    /** 一条「可提炼信号」的来路。 */
    enum class SignalKind {
        /** 写/改/搬/删了工作区里的文件（**只有真写成功的才算**）。 */
        FILE,

        /** 用过浏览器（开页/快照/点击/读字段）。 */
        BROWSER,

        /** 改过或新建过技能。 */
        SKILL,

        /** 用户明说「记住／记下来／以后都这样」。 */
        USER_SAID,
    }

    /** 一条信号（detail 是来源的一句话：哪个工具／哪个文件／哪句原话）。 */
    data class LearnSignal(val kind: SignalKind, val detail: String)

    /** 卡上「建议」那一栏：写成草稿技能，还是转正式。 */
    enum class Suggest { DRAFT, FORMAL }

    /** 一条提案（**卡上三件套**：内容／来源／建议）。id 稳定，采纳时按 id 找回同一条。 */
    data class LearnProposal(
        val id: String,
        val content: String,
        val source: String,
        val suggest: Suggest,
        val fromUser: Boolean,
    )

    /** 一条提案最多显示多少字（卡要能一眼看完）。 */
    const val LEARN_CONTENT_LIMIT = 60

    /**
     * 该不该出卡（**同一次收尾只出一次**由调用方的 `alreadyShown` 决定）。
     *
     * 没信号 ⇒ 不出（不许每轮都弹，那是骚扰）。
     */
    fun shouldEmit(signals: List<LearnSignal>, alreadyShown: Boolean): Boolean =
        signals.isNotEmpty() && !alreadyShown

    /**
     * 生成提案（**纯函数、确定性、≤[MAX_CANDIDATES] 条**）。
     *
     * 排序：用户明说的排最前，其余按信号出现顺序；同名（同一来源）去重；
     * 已经存在的同名技能 ⇒ 建议改成「转正式」而不是再建一条草稿。
     *
     * @param existingNames 已有技能名（决定建议是草稿还是转正式）
     */
    fun learnProposals(
        signals: List<LearnSignal>,
        existingNames: Set<String> = emptySet(),
    ): List<LearnProposal> {
        if (signals.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<LearnProposal>()
        val ordered = signals.sortedByDescending { it.kind == SignalKind.USER_SAID }
        for (s in ordered) {
            val name = MemoryRules.slugOf(s.detail.take(12)).ifEmpty { "skill" }
            if (!seen.add(name)) continue
            val content = s.detail.take(LEARN_CONTENT_LIMIT)
            val suggest = if (name in existingNames) Suggest.FORMAL else Suggest.DRAFT
            out += LearnProposal(
                id = "${s.kind.name.lowercase()}-$name",
                content = content,
                source = sourceLabel(s.kind),
                suggest = suggest,
                fromUser = s.kind == SignalKind.USER_SAID,
            )
            if (out.size >= MAX_CANDIDATES) break
        }
        return out
    }

    /** 来源那一栏的人话（用户看得懂，不是工具名）。 */
    private fun sourceLabel(kind: SignalKind): String = when (kind) {
        SignalKind.FILE -> "这轮动过的文件"
        SignalKind.BROWSER -> "这轮看过的页面"
        SignalKind.SKILL -> "这轮的技能改动"
        SignalKind.USER_SAID -> "你说的原话"
    }

    /** 用户文本里有没有「记住／记下来／以后都这样」这类话（有 ⇒ 一条 USER_SAID 信号）。 */
    fun learnMarkersIn(text: String): List<LearnSignal> {
        if (text.isBlank()) return emptyList()
        val hits = LEARN_MARKERS.filter { text.contains(it) }
        if (hits.isEmpty()) return emptyList()
        return listOf(LearnSignal(SignalKind.USER_SAID, text.trim().take(LEARN_CONTENT_LIMIT)))
    }

    /**
     * 工具名 ⇒ 信号种类。**被保护闸拦下的（Rejected）根本不会走到这里**——
     * 引擎只在工具真写成功（Done）时记信号；被拒的写尝试不算「学到」，不许进卡。
     */
    fun signalKindFor(toolName: String): SignalKind? = when (toolName) {
        "write_file", "edit_file", "move_path", "delete_path" -> SignalKind.FILE
        "write_skill", "skill_lifecycle" -> SignalKind.SKILL
        else -> if (toolName.startsWith("browser_")) SignalKind.BROWSER else null
    }

    // ── C 段：采纳前的三道最小校验（第三十七批）─────────────────────────────
    //
    // 派工单 §3：**采纳前必须过三道最小校验**（危险指令 / 平台事实没标未实测 / 同名冲突），
    // 不通过 ⇒ 卡片标红、**禁止采纳**、并说清哪条不过。**零网络、零 I/O、纯函数**——
    // 所以它能在 JVM 里被直接钉死，不用起 App、不用真机。
    //
    // 为什么校验的是 [skillBodyFor] 而不是卡上的 `content`：**门禁要看的必须是「将要写进
    // SKILL.md 的那段字」**。校验 A 却写入 B 是这类闸门最经典的失效方式，所以正文只有这一个
    // 生成口，卡上显示、门禁校验、控制器落盘三处共用。

    /** 提案的名字（卡上显示、落盘文件名、冲突判定**共用这一个真相**）。 */
    fun nameOf(p: LearnProposal): String = p.id.substringAfter('-').ifEmpty { "skill" }

    /** 将要写进 `SKILL.md` 的正文（**唯一生成口**：门禁校验的就是这一段）。 */
    fun skillBodyFor(p: LearnProposal): String =
        "# 技能：${nameOf(p)}\n\n## 场景\n${p.content}\n\n## 来源\n${p.source}\n"

    /** 三道校验的编号（报错要让人看懂是哪一条不过）。 */
    enum class LearnIssue(val label: String) {
        /** 危险指令：删库/删根、格式化、管道执行远端脚本、外发凭据、把工具结果当指令。 */
        DANGEROUS("危险指令"),

        /** 平台事实（字段名/下拉值/按钮名…）写了但既没标「未实测」也没写实测日期。 */
        UNVERIFIED("平台事实没标未实测"),

        /** 同名技能已存在却建议新建草稿（应当改自己那份，或明确转正式）。 */
        CONFLICT("同名技能已存在"),
    }

    /** 一次校验的结果。[details] 是给人看的原因（多条用「；」连）。 */
    data class LearnReview(val issues: List<LearnIssue>, val details: List<String>) {
        val ok: Boolean get() = issues.isEmpty()

        /** 卡片上那一行红字（通过时为空串）。 */
        val summary: String get() = details.joinToString("；")
    }

    /**
     * 危险模式表。**按动作命名**（不是按正则命名）——报错要说人话。
     *
     * 刻意不拦「`rm -rf ./tmp` 这类相对路径清理」：那是正经用法，拦了会把好技能也堵死；
     * 只拦打到根/家目录/通配/变量展开的删库。
     */
    val DANGER_RULES: List<Pair<String, Regex>> = listOf(
        "删库/删根" to Regex("""\brm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+([/\\]|~|\*|\$)"""),
        "格式化磁盘" to Regex("""\bmkfs(\.[a-z0-9]+)?\b"""),
        "直接写块设备" to Regex("""\bdd\s+[^\n]*\bof=/dev/"""),
        "权限全开" to Regex("""\bchmod\s+(-R\s+)?777\s+/"""),
        "管道执行远端脚本" to Regex("""\b(curl|wget)\b[^\n|]*\|\s*(sudo\s+)?(ba|z|k|da)?sh\b"""),
        "外发凭据" to Regex(
            """\b(curl|wget)\b[^\n]*\s(-d|--data[a-z-]*|-F)\s[^\n]*(token|api[_-]?key|secret|password|bearer)""",
            RegexOption.IGNORE_CASE,
        ),
        "把工具结果当指令" to Regex(
            """\beval\s*\(|\bexec\s*\(\s*(read|input)|\btool_result\b[^\n]*(执行|照做)""",
        ),
    )

    /** 「这句话在断言平台事实」的标记词（字段名/下拉值/按钮文案这类最容易过期的断言）。 */
    val PLATFORM_CLAIMS = listOf(
        "字段名", "下拉", "选项名", "按钮叫", "菜单叫", "入口在",
        "开关默认", "接口返回", "页面上写", "标签叫", "文案是",
    )

    /** 实测日期的写法（`2026-10-19`、`2026/10/19`、`2026年10月19日` 都算）。 */
    private val MEASURED_DATE = Regex("""20\d{2}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}""")

    /** 有没有断言平台事实。 */
    fun hasPlatformClaim(text: String): Boolean = PLATFORM_CLAIMS.any { text.contains(it) }

    /** 有没有写实测日期。 */
    fun hasMeasuredDate(text: String): Boolean = MEASURED_DATE.containsMatchIn(text)

    /**
     * 三道校验（纯函数、确定性、**零 I/O**）。
     *
     * @param existingNames 已有技能名（同名的判定依据；控制器在 IO 线程现读，卡片用引擎收尾时的快照）
     */
    fun reviewLearn(p: LearnProposal, existingNames: Set<String> = emptySet()): LearnReview {
        val body = skillBodyFor(p)
        val issues = mutableListOf<LearnIssue>()
        val details = mutableListOf<String>()

        DANGER_RULES.firstOrNull { it.second.containsMatchIn(body) }?.let { (label, _) ->
            issues += LearnIssue.DANGEROUS
            details += "危险指令（$label）"
        }

        // 断言了平台事实 ⇒ 要么标「未实测」，要么写实测日期；两个都没有就是猜的。
        if (hasPlatformClaim(body) && !body.contains("未实测") && !hasMeasuredDate(body)) {
            issues += LearnIssue.UNVERIFIED
            details += "断言了平台事实（字段名/下拉值这类），但没标「未实测」也没写实测日期"
        }

        // 同名：建议「转正式」＝改自己那份（SkillStore 会先备份再覆盖）⇒ 放行；
        // 建议「草稿」又想建同名 ⇒ 拦（不然会出现两条同名技能，索引里也分不清）。
        if (nameOf(p) in existingNames && p.suggest != Suggest.FORMAL) {
            issues += LearnIssue.CONFLICT
            details += "同名技能已存在（${nameOf(p)}）：改自己那份，或把建议改成「转成正式技能」"
        }

        return LearnReview(issues, details)
    }
}
