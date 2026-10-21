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
     *
     * @param rejectedKeys 第五十七批 §1.3：被**丢弃**过的候选去重键（名称＋正文摘要）。
     *   命中 ⇒ 直接跳过——丢过的东西下轮不许再端上来。
     */
    fun refine(
        facts: MemoryRefine.SessionFacts,
        existing: List<SkillRules.IndexLine>,
        rejectedKeys: Set<String> = emptySet(),
    ): Result {
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
                if (dedupeKey(name, normalizeKeyText(scenario)) in rejectedKeys) continue // 丢过 ⇒ 不再提（§1.3）
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
     * @param rejectedKeys 第五十七批 §1.3：被**丢弃**过的候选去重键——命中 ⇒ 跳过，
     *   同一候选下轮不再重复提（丢弃留痕的另一半）。
     */
    fun learnProposals(
        signals: List<LearnSignal>,
        existingNames: Set<String> = emptySet(),
        rejectedKeys: Set<String> = emptySet(),
    ): List<LearnProposal> {
        if (signals.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<LearnProposal>()
        val ordered = signals.sortedByDescending { it.kind == SignalKind.USER_SAID }
        for (s in ordered) {
            val name = MemoryRules.slugOf(s.detail.take(12)).ifEmpty { "skill" }
            if (!seen.add(name)) continue
            if (dedupeKey(name, normalizeKeyText(s.detail.take(LEARN_CONTENT_LIMIT))) in rejectedKeys) continue // §1.3
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

    /**
     * 将要写进 `SKILL.md` 的正文（**唯一生成口**：门禁校验的就是这一段）。
     *
     * 第一百零三批 §B（EVO-D1）两处**结构**补齐，都是被 `SkillBodySelfCheck` 的硬判据逼出来的：
     * 1. 第一行必须是 `# <技能名>` —— [ensureTitle] 兜底（已有标题不重复补）；
     * 2. 正文 ≥ `MIN_BODY_CHARS`（80）——原来只有「场景＋来源」两节，真机那条信号
     *    （`技能/haiku-helper/SKILL.md`）算下来正文只有 40 多字符，自检判「一句话不算一个技能」
     *    ⇒ 采纳必败。补一节**空步骤骨架**：只补结构、不编造事实（明说「还没写下具体步骤」），
     *    与草稿「未验证」的既有口径一致。
     */
    fun skillBodyFor(p: LearnProposal): String {
        val name = nameOf(p)
        return ensureTitle(
            name,
            "# 技能：$name\n\n" +
                "## 场景\n${p.content}\n\n" +
                "## 来源\n${p.source}\n\n" +
                "## 步骤\n（还没写下具体步骤：下次真正做成时，把实际做法补在这里，并标上实测日期。）\n",
        )
    }

    /**
     * 保证正文第一行是 `# <技能名>`（第一百零三批 §B①）。
     *
     * **只补结构、不改语义**：首个非空行已经是**一级标题**（`# ` 开头，`## 场景` 这种
     * 二级标题不算）⇒ 原样返回（已有标题不重复补、不改写原句）；否则在开头补一行
     * `# <name>` 加一个空行。
     */
    fun ensureTitle(name: String, body: String): String {
        val first = body.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (first.startsWith("# ")) return body
        return "# ${name.ifBlank { "skill" }}\n\n" + body.trimStart('\n')
    }

    /**
     * 去掉 `SkillStore` 落盘时前置的**机器头**（`<!-- status=draft uses=0 -->`）。
     *
     * 为什么需要（第一百零三批 §B 现场，`SkillStore.kt` 的 `withDraftHeader`）：
     * 写草稿时机器头在最前面，而 `SkillBodySelfCheck` 把「第一个非空行」当标题
     * ⇒ 草稿永远被判「缺标题段」、采纳永远回滚（真机报错逐字如此）。
     * 机器头是**纯注释**（SkillStore 自己的 KDoc 口径），不是正文内容 ⇒
     * 喂结构自检前去掉开头连续的 HTML 注释行，**判据本体一个字不动**。
     */
    fun contentWithoutMachineHeader(text: String): String {
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("<!--") && t.endsWith("-->")) i++ else break
        }
        return lines.drop(i).joinToString("\n")
    }

    /**
     * 采纳卡的前置校验（第一百零三批 §B②）：**真跑** `SkillBodySelfCheck`（不是字符串断言）。
     *
     * @return 空 = 可采纳；非空 = 每条人话原因（卡上不出这条、落盘前再拒一次）
     *
     * 卡还没落盘 ⇒ `backups=0 / existed=false`：回滚那条判据此时没有盘上事实可判，
     * 它会在真落盘之后由 `SkillBodyGuard` 按盘上状态再判一次（两道闸各管一段）。
     */
    fun learnPrecheckIssues(p: LearnProposal): List<String> =
        when (
            val v = com.example.zhengdao.baopu.eval.SkillBodySelfCheck.check(
                com.example.zhengdao.baopu.eval.SkillBodyInput(
                    name = nameOf(p),
                    body = skillBodyFor(p),
                    backups = 0,
                    existed = false,
                ),
            )
        ) {
            is com.example.zhengdao.baopu.eval.SkillBodyVerdict.Ok -> emptyList()
            is com.example.zhengdao.baopu.eval.SkillBodyVerdict.Bad -> v.issues
        }

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

    // ── 第五十七批 §1.2：过期判据（纯函数）＋「待重验」旁挂 JSON ─────────────
    //
    // 派工单原话：技能头 `status=… uses=…` **不可再加字段**（抱朴会写歪，36 批 B 段
    // 同一条判断）⇒ 「待重验」走**旁挂 JSON**（照 36 批 `_分类.json` 房型：扁平
    // string→string、排序输出幂等、坏 JSON 当空表不抛），**SKILL.md 一个字节不动**。
    // 且**不自动降级/删除技能**——标记只是提示，处置权在人。

    /** 过期判据阈值（派工单 §1.2「阈值常量集中一处，默认值写清」——就这一处）。 */
    object SkillExpiry {
        /** 久未用：超过 14 天没被用过 ⇒ 待重验。 */
        const val STALE_DAYS = 14L
        const val STALE_MS: Long = STALE_DAYS * 24L * 60L * 60L * 1000L

        /** 失败率窗口：看最近 5 次使用。 */
        const val FAIL_WINDOW = 5

        /** 失败率阈值：窗口内失败 ≥ 60% ⇒ 待重验。 */
        const val FAIL_RATE = 0.6
    }

    /** 一次过期判据的结论。 */
    enum class ExpiryVerdict(val needsRecheck: Boolean) {
        /** 有证据说它还行（或证据不够说它不行）。 */
        FRESH(false),

        /** 久未用。 */
        STALE_UNUSED(true),

        /** 最近几次老失败。 */
        STALE_FAILING(true),

        /** 又久未用又老失败。 */
        STALE_BOTH(true),
    }

    /** 给人看的那句话（设置页技能行 / 答复提醒共用一个口径）。 */
    fun reasonOf(v: ExpiryVerdict): String = when (v) {
        ExpiryVerdict.FRESH -> ""
        ExpiryVerdict.STALE_UNUSED -> "超过 ${SkillExpiry.STALE_DAYS} 天没用过"
        ExpiryVerdict.STALE_FAILING ->
            "最近 ${SkillExpiry.FAIL_WINDOW} 次里失败≥${(SkillExpiry.FAIL_RATE * 100).toInt()}%"
        ExpiryVerdict.STALE_BOTH ->
            "超过 ${SkillExpiry.STALE_DAYS} 天没用过，且最近 ${SkillExpiry.FAIL_WINDOW} 次里失败≥${(SkillExpiry.FAIL_RATE * 100).toInt()}%"
    }

    /**
     * 过期判据（**纯函数、确定性**）。
     *
     * 两条证据、一条一条来：
     * - **久未用**：`lastUsedAtMs` 非空且距今超过 [SkillExpiry.STALE_MS]。
     *   null（记录上线后还没用过）⇒ **没有证据，不判**——宁可漏标不冤枉。
     * - **高失败率**：最近 [SkillExpiry.FAIL_WINDOW] 次（不足整窗 ⇒ 证据不够，不判）
     *   失败占比 ≥ [SkillExpiry.FAIL_RATE]。
     *
     * @param recentOks 旧→新的使用成败（多余条目取窗口尾）
     */
    fun expiryVerdict(lastUsedAtMs: Long?, recentOks: List<Boolean>, nowMs: Long): ExpiryVerdict {
        val stale = lastUsedAtMs != null && nowMs - lastUsedAtMs > SkillExpiry.STALE_MS
        val window = recentOks.takeLast(SkillExpiry.FAIL_WINDOW)
        val failing = window.size >= SkillExpiry.FAIL_WINDOW &&
            window.count { !it }.toDouble() / window.size >= SkillExpiry.FAIL_RATE
        return when {
            stale && failing -> ExpiryVerdict.STALE_BOTH
            stale -> ExpiryVerdict.STALE_UNUSED
            failing -> ExpiryVerdict.STALE_FAILING
            else -> ExpiryVerdict.FRESH
        }
    }

    /** 「待重验」旁挂文件名（与 36 批 `_分类.json` 同目录同级：`<技能目录>/_重验.json`）。 */
    const val RECHECK_FILE = "_重验.json"

    /**
     * 编码（**按键排序输出 ⇒ 幂等**，照 36 批 `encodeCategories` 口径：同一状态写两次
     * 字节必须一致）。两空格缩进、每行一键，用户在文件管理器里能直接看懂、手改。
     */
    fun encodeRecheck(map: Map<String, String>): String {
        val sb = StringBuilder("{\n")
        val keys = map.keys.sorted()
        keys.forEachIndexed { i, key ->
            sb.append("  \"").append(escapeJson(key)).append("\": \"")
                .append(escapeJson(map.getValue(key))).append('"')
            if (i < keys.size - 1) sb.append(',')
            sb.append('\n')
        }
        return sb.append("}\n").toString()
    }

    /** 解码（**坏 JSON ⇒ 空表，绝不抛**，照 36 批口径；只认扁平 string→string）。 */
    fun decodeRecheck(text: String?): Map<String, String> {
        val src = text?.trim().orEmpty()
        if (src.isEmpty()) return emptyMap()
        if (!src.startsWith("{") || !src.endsWith("}")) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val body = src.substring(1, src.length - 1)
        var i = 0
        while (i < body.length) {
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) break
            val k = readJsonString(body, i) ?: return emptyMap()
            i = skipWs(body, k.second)
            if (i >= body.length || body[i] != ':') return emptyMap()
            i = skipWs(body, i + 1)
            val v = readJsonString(body, i) ?: return emptyMap()
            out[k.first] = v.first
            i = skipWs(body, v.second)
            if (i >= body.length) break
            if (body[i] != ',') return emptyMap()
            i++
        }
        return out.filterKeys { it.isNotBlank() }
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** 读一个 JSON 字符串字面量（含 `\"` `\\` `\n` `\r` `\t`）；没闭合/不认识 ⇒ null。 */
    private fun readJsonString(s: String, from: Int): Pair<String, Int>? {
        if (from >= s.length || s[from] != '"') return null
        val sb = StringBuilder()
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return sb.toString() to (i + 1)
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= s.length) return null
            when (s[i + 1]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> return null
            }
            i += 2
        }
        return null
    }

    private fun escapeJson(raw: String): String = buildString {
        raw.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    // ── 第五十七批 §1.3：采纳/丢弃留痕的去重键 ────────────────────────────────

    /** 去重键的文本规范化（索引行往返会 trim 尾部标点，两侧用同一把尺才对得上）。 */
    internal fun normalizeKeyText(s: String): String = s.trim().trimEnd('，', ',')

    /**
     * 候选去重键 = **名称＋正文摘要的 SHA-256 前 16 位**（派工单 §1.3 原文口径）。
     *
     * 纯函数：同内容同 key（跨轮措辞一致才认得出是同一个候选）、不同内容不同 key
     * （两条不同候选不许被误并）。**纯哈希，零 I/O**——与 58 批 `SkillAudit.shaOf`
     * 的分工：那边算的是**盘上文件**的摘要（改了什么），这边算的是**卡上候选**的
     * 摘要（提没提过、丢没丢过）。
     */
    fun dedupeKey(name: String, content: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$name\n$content".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
    }

    /** 学习卡候选的去重键（卡上那条的口径：名字＋content 摘要）。 */
    fun dedupeKeyOf(p: LearnProposal): String = dedupeKey(nameOf(p), p.content)

    /**
     * 封卷确认卡候选的**名字**（PersonaProposal 只有整段索引文本，名字要从
     * 「proposed 比 current 多出的那行」里提——采纳/丢弃留痕都要用它）。
     *
     * 多于一行/提不出 ⇒ null（留痕就少记一条 hash，**不瞎猜**——猜错会把没丢过的
     * 候选也拉黑）。
     */
    fun proposalNameOf(p: PersonaProposal): String? {
        val scope = when (p.layer) {
            PersonaLayer.B_GLOBAL -> MemoryScope.GLOBAL
            else -> MemoryScope.PROJECT
        }
        val before = SkillRules.parseIndexLines(p.current, scope).map { it.name }.toSet()
        val added = SkillRules.parseIndexLines(p.proposed, scope).filter { it.name !in before }
        return added.singleOrNull()?.name
    }

    /** 封卷确认卡候选的去重键（与 [refine] 产候选时的口径同一把尺：名字＋scenario）。 */
    fun dedupeKeyOfProposal(p: PersonaProposal): String? =
        proposalNameOf(p)?.let { name ->
            val scope = when (p.layer) {
                PersonaLayer.B_GLOBAL -> MemoryScope.GLOBAL
                else -> MemoryScope.PROJECT
            }
            val before = SkillRules.parseIndexLines(p.current, scope).map { it.name }.toSet()
            val added = SkillRules.parseIndexLines(p.proposed, scope)
                .filter { it.name !in before }.singleOrNull()
            dedupeKey(name, normalizeKeyText(added?.scenario.orEmpty()))
        }
}
