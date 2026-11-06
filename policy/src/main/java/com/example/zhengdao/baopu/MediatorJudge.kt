// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十八批 · 调解器的**判官**：任务态边界 ＋ 三条禁令的运行时判据。
//
// 为什么需要这个文件（体检结论，不是设计偏好）：
// `HarmonyMediator`（238）只有**档位**，不会判违规；`ReframeFilter` 只认「用户这边的话」；
// 而法典 L1（禁附和）／L3（禁空转道歉）的判据在 253 那批只**落在了测试里**
// （`constitution/TaskModeBoundary.kt`、`L1NoFlatteryGateTest.FlatteryProbe`、
// `L3NoEmptyApologyGateTest.ApologyJudge`）——测试源码生产代码看不见（Kotlin `internal`
// 是两个模块），所以「判据存在」与「运行时不生效」同时为真。本文件把同一套判据
// 搬进生产码，词表**逐字照抄** 253 的探针（改一个词就是改判据）。
//
// 三条禁令（出自《调解器-策略表-私有-2026-10-27》§2.3，逐字）：
//   · 附和 flattery     ：结论翻转但**无新证据**；
//   · 空转道歉 apology_loop：**连续 2 轮**：道歉句 ≥1 且无新信息；
//   · 情绪镜像 mirroring ：输出出现攻击性词，或讨好性感叹堆叠（≥3 个 `!`／`～`）。
//
// 判态先于判违规：**态判错，禁令全废**。任务态 = 有工具调用/执行意图、或有事实主张、
// 或在收尾已开工的活；闲聊态一律不判（"哈哈哈"不许被判成情绪镜像）。
//
// 零 Android / 零 IO / 零网络 / 零时钟：本文件只吃字符串，不取时间、不落盘、不记原话。
// 落 208、换档、进 prompt 都在挂接层（[MediatorWiring]）。
// 第二百九十三批（2026-10-31）：本文件自 :app 搬进 :policy（宰相的白名单匣）。
// 一起搬进来的还有可见性——Kotlin 的 internal 以编译单元为界，:app 看不见 :policy 的
// internal，故顶层声明由 internal 放宽为公开。**只放宽可见性，判据一个字没动**
// （搬前搬后的逐行对照与放宽清单见交付报告）。
//
// 第五百批（2026-11-06）：判态的**英文层结构性改造**（493 补词表补不完的四处缺口）。
// 中文四步（INTENT／WRAPUP／CLAIM／CLAIM_RE）逐字保留，判定顺序也没动——只是在它们
// 都判不出来之后，多走一段英文专用路径（词边界＋大小写归一＋习语白名单优先＋请求形态共现）。
// 同一批把测试侧的 253 探针副本改成指向这里（一处改两处生效，见 constitution/TaskModeBoundary.kt）。
//
// 合并时与 493 的取舍（493 已在 main，`18a2bd7a`）：493 把英文当作中文表的**数据**来补
// （动词写成「小写＋尾随空格」两形，收尾／主张／闲聊词写成裸词与首字母大写两形，直接进
// INTENT／WRAPUP／CLAIM／PURE_EMOTION）。本批动的是结构，两套并存会互相抵消——带尾随空格的
// 搜索词是「research papers」的子串，中文表先命中，判据① 立刻红。故本批**取代**那批字面量：
// 四张中文表回到纯中文，英文词汇整体搬进英文层；493 点名的四条闲聊形态词移到
// EN_CHAT_SHAPES，emotionLexicon() 照样端出去，493 的行为门禁一条不减。
// 493 测试里那根「classify 末尾必须裸返回 CHAT」的结构围栏已改成钉「跳英文层」，
// 授权来自 500 的 Q3 拍板（2026-11-06）；它的 11 条行为语料一条没减。
//
// 同批拍板落实（用户 2026-11-06，六条里的 Q1／Q2／Q4／Q5 都改在这一层）：
//   · Q1 句首裸动词＝祈使，算请求形态（`isImperativeHead` 保留，工单④那句作废）；
//   · Q2 习语白名单遇到请求标记**让位**给词表（`sounds good` 仍闲聊，`sounds good, but please delete it` 是任务）；
//   · Q4 `kindly` 留在请求标记表里；
//   · Q5 自责按**主语**分：I was wrong／I'm wrong 不收，the data was wrong 收，且不按时态区分。
package com.example.zhengdao.baopu

/** 一段话落在哪个态（法典 L1/L3 的第一道门）。 */
enum class TaskMode { TASK, CHAT }

/**
 * 任务态 vs 闲聊态的**词表级**边界（253 探针逐字搬运，见文件头）。
 *
 * 保守方向写死一条：命中不了①②③就归闲聊态——**宁可漏判进不了禁令，不把玩笑当办事**。
 */
object TaskModeBoundary {

    // ── ① 执行意图 / 工具调用形态（"帮我改/查/装"）───────────────────────────
    // 第五百批：493 曾在这里登记英文动词（「小写＋后随空格」两形），本批整批移进下面的
    // 英文层并加词边界。留在中文表里会照旧误伤——带尾空格的搜索词是「research papers」的
    // 子串，判据① 就红在这儿；补词补不完，所以才动结构。中文表回到纯中文（门禁钉住）。
    private val INTENT = listOf(
        "帮我", "替我", "给我查", "给我改", "给我删", "改一下", "改掉", "换掉", "查一下", "查查",
        "搜一下", "搜个", "写一个", "写一份", "写个", "删掉", "删除", "卸载", "安装", "装一下",
        "跑一下", "跑起来", "执行", "生成", "导出", "导入", "上传", "下载", "打开", "关掉",
        "修复", "修一下", "部署", "提交", "保存", "重命名", "替换", "压缩", "解压",
        "照这个改", "按这个改", "按这个做", "照着做", "试试看能不能", "重新",
    )

    // ── ② 事实主张：数字 / 路径 / 引用 / 对错结论 / 执行结果 ──────────────────
    // 「第N步/行/条/章」用正则，不用裸「第」——「你是第一个让我这么生气的小笨蛋」不是事实主张。
    // 500：493 的英文事实主张短语同样移进英文层（词边界版）。自责客套的口径在 Q5 拍板后
    // 改成**按主语**：主语是"我"的自责不收，其余主语的 was wrong 照收（见 EN_SELF_BLAME）。
    private val CLAIM = listOf(
        "就是错的", "是对的", "没错", "不对", "错了", "正确的", "应该是", "其实是", "我查过",
        "数据显示", "事实证明", "结论是", "结果是", "失败", "成功", "报错", "超时", "找不到",
        "打不开", "崩溃", "日志", "合计", "总共", "一共", "版本", "文件里", "表里", "官网",
        "文档说",
    )
    private val CLAIM_RE = listOf(
        Regex("""第\s*\d+\s*(?:步|行|条|章|页|个|次|版)"""),
        Regex("""\d"""), // 出现数字即算事实主张（闲聊里报数字的罕见；报了就该按事实态对待）
        Regex("""[\w\-]+\.(?:kt|java|md|txt|json|xml|csv|png|jpg)"""),
    )

    // ── ③ 收尾已开工的活 ────────────────────────────────────────────────────
    // 500：493 的英文收尾词移进英文层（EN_WRAPUP_RES）；中文表回到纯中文。
    private val WRAPUP = listOf("收尾", "收工", "搞定了", "完成了", "完工", "交差", "交付")

    // ── 闲聊的形态词（只用于钉边界；判闲聊靠的是"没命中任务态"，不靠命中闲聊词）──
    // 500：493 的英文闲聊形态词移到英文层的对照表里（同样不参与判定），emotionLexicon()
    // 把两张表并排端出去，493 的门禁照旧有效。中文表回到纯中文。
    private val PURE_EMOTION = listOf(
        "哈哈", "笑死", "开心", "难过", "气死", "无语", "绝了", "太逗了", "好烦", "麻了",
        "爱你", "想你", "晚安", "早安", "晚上好", "在吗", "无聊",
    )

    // ── ④ 英文层（第五百批 · 结构性改造）────────────────────────────────────
    //
    // 上面①②③是**中文**表，一个字节没动；英文按词表级补词补不完（493 停手报告四处缺口），
    // 根因是 `String.contains` 既不看词边界、也不看大小写、更不问「这是不是在提要求」。
    // 这四条一起才成立，缺一条就还剩一类误判：
    //   ① 词边界 —— `\bresearch\b` 不许在 `research` 里再命中 `search`；
    //   ② 大小写归一 —— 先 lowercase()，动词只登记小写原形，不必再配「首字母大写」两形；
    //   ③ 习语白名单优先 —— 命中即闲聊，不再走词表（`open to ideas` 不是在「打开」）；
    //      但同句里出现请求标记时白名单**让位**给词表（Q2 拍板：`sounds good, but please delete it`
    //      是在提要求，不是在敷衍）。
    //   ④ 请求形态共现 —— 动词命中后**还要**有请求形态（please／could you／句首祈使）才算任务。
    // 中文一律不进这一层（`\b` 按 ASCII 词类算边界，中文全是「非词」，套上去只会误伤）。

    /** ② 英文动作词：**只登记小写原形**，配 ① 的词边界与 ④ 的请求形态一起用。 */
    private val EN_VERBS = listOf(
        "create", "delete", "write", "run", "fix", "check", "translate", "summarize",
        "research", "search", "open", "send", "rename", "export", "import", "upload",
        "download", "deploy", "commit", "save", "replace", "install", "copy", "move",
        "convert", "generate", "build",
    )
    private val EN_VERB_SET = EN_VERBS.toSet()
    private val EN_VERB_RES = EN_VERBS.map { enWordRe(it) }

    /** ③ 习语白名单：里面的动词不是执行意图 ⇒ 命中即闲聊，不再走词表。 */
    private val EN_IDIOMS = listOf(
        "open to ideas", "research papers", "good to go", "sounds good", "no worries",
    )
    private val EN_IDIOM_RES = EN_IDIOMS.map { enWordRe(it) }

    /**
     * ④ 请求形态标记（与动词命中**共现**才算任务；小写，配词边界）。
     *
     * 同一个表还管 Q2 的白名单让位：句子里出现这些标记，说明说话的人在**提要求**，
     * 习语白名单就不再短路（`kindly` 按 Q4 拍板留在表里，祈使礼貌词）。
     */
    private val EN_REQUEST_MARKERS = listOf(
        "please", "pls", "kindly", "could you", "can you", "would you", "will you",
        "i need", "i want to", "help me",
    )
    private val EN_MARKER_RES = EN_REQUEST_MARKERS.map { enWordRe(it) }

    /** 英文收尾（③ 类）／英文事实主张（② 类）：命中即任务态，不要求请求形态。 */
    private val EN_WRAPUP_RES = listOf("done", "finished", "shipped").map { enWordRe(it) }

    // 主张短语按 Q5 拍板**不分时态**：is／are／was／were wrong 同口径（中文「就是错的」本来也没时态）。
    private val EN_CLAIM_RES = listOf(
        "is wrong", "are wrong", "was wrong", "were wrong", "is correct", "in fact",
        "the result is", "the data shows", "the docs say", "the conclusion is", "according to",
        "not found", "failed", "succeeded", "error", "crash", "timeout", "the version",
    ).map { enWordRe(it) }

    /**
     * Q5 自责形态：**主语是"我"** 的认错不进事实主张——与中文侧 `claimScope`
     * 抹掉「我错了／我的错」同一手法（抹掉后再走主张表，所以「I was wrong, the data shows it」
     * 仍靠后半句立任务态）。其余主语的 was wrong（the data was wrong）照收。
     */
    private val EN_SELF_BLAME = listOf("i was wrong", "i am wrong", "i'm wrong")

    /**
     * 英文闲聊形态词（493 点名四条，小写登记＝大小写归一后一形管两形）。
     *
     * 与 PURE_EMOTION 同一身份：**只作回归对照，classify 从不读它**，命中不会短路成 CHAT；
     * 经 emotionLexicon() 端给测试，证明「闲聊形态词＋任务动词」仍判 TASK。
     */
    private val EN_CHAT_SHAPES = listOf("thanks", "lol", "good night", "nvm")

    /** ④ 祈使句首前允许的语气／连接词：剥掉之后句首若是动作词原形，就是祈使句。 */
    private val EN_HEAD_FILLERS = setOf(
        "and", "but", "then", "so", "also", "just", "now", "please", "kindly", "pls",
        "ok", "okay", "hey", "hi", "hello", "oh", "well", "yes", "no", "yeah", "sure",
        "um", "hmm", "lol", "nvm", "btw", "thanks", "thank", "sorry", "haha",
    )

    private val EN_CLAUSE_SEP = Regex("[,;]")
    private val EN_WORD = Regex("[a-z']+")

    /** ① 词边界：只在 `\b` 上命中；中文侧不走这里。 */
    private fun enWordRe(word: String) = Regex("""\b${Regex.escape(word)}\b""")

    /**
     * 英文层判定（走到这里说明中文①②③都没命中）。
     *
     * 顺序即判据：③ 习语白名单先看（命中即闲聊）→ 英文收尾／事实主张命中即任务 →
     * ① 动词按词边界命中后，还须 ④ 请求形态共现才算任务。
     * 两处「先看但要让位」：白名单在请求标记同现时让位（Q2），事实主张在自责形态同现时让位（Q5）。
     * 剩下的混合句按保守方向落闲聊——宁可漏判、不把玩笑当办事（文件头方向）。
     */
    private fun classifyEnglish(text: String): TaskMode {
        val en = text.lowercase() // ② 大小写归一
        if (!hasRequestMarker(en) && EN_IDIOM_RES.any { it.containsMatchIn(en) }) return TaskMode.CHAT
        if (EN_WRAPUP_RES.any { it.containsMatchIn(en) }) return TaskMode.TASK
        val claimScope = EN_SELF_BLAME.fold(en) { scope, phrase -> scope.replace(phrase, "　") }
        if (EN_CLAIM_RES.any { it.containsMatchIn(claimScope) }) return TaskMode.TASK
        if (EN_VERB_RES.none { it.containsMatchIn(en) }) return TaskMode.CHAT
        return if (hasRequestShape(en)) TaskMode.TASK else TaskMode.CHAT
    }

    /** ④ 请求标记（please／could you／kindly…）：也是 Q2 里让白名单让位的那个条件。 */
    private fun hasRequestMarker(en: String): Boolean = EN_MARKER_RES.any { it.containsMatchIn(en) }

    /** ④ 请求形态：显式标记，或剥掉语气词后的句首就是动作词原形。 */
    private fun hasRequestShape(en: String): Boolean =
        hasRequestMarker(en) || EN_CLAUSE_SEP.split(en).any { isImperativeHead(it) }

    /**
     * ④ 祈使句首：小句首（跳过语气词）是英文动作词原形 ⇒ 「Create a folder」这类算任务。
     *
     * Q1 拍板（2026-11-06）：句首裸动词就是「在提要求」，这条保留不删；
     * 派工单④原文那句「裸 create 不算任务」由用户同批作废（写岔了，不是判据错）。
     */
    private fun isImperativeHead(clause: String): Boolean {
        val head = EN_WORD.findAll(clause)
            .map { it.value }
            .dropWhile { it in EN_HEAD_FILLERS }
            .firstOrNull()
        return head != null && head in EN_VERB_SET
    }

    /** 一段话落在哪个态：命中①②③任一 ⇒ 任务态；否则闲聊态（保守方向）。 */
    fun classify(text: String): TaskMode {
        val s = text.trim()
        if (s.isEmpty()) return TaskMode.CHAT
        if (INTENT.any { s.contains(it) }) return TaskMode.TASK
        if (WRAPUP.any { s.contains(it) }) return TaskMode.TASK
        // 自责客套（「我错了」「我的错」）不是对世界的事实主张——闲聊里的道歉不许因此进任务态。
        val claimScope = s.replace("我错了", "　").replace("我的错", "　")
        if (CLAIM.any { claimScope.contains(it) }) return TaskMode.TASK
        if (CLAIM_RE.any { it.containsMatchIn(claimScope) }) return TaskMode.TASK
        // ④ 第五百批：中文四步一字未动，判不出来才进英文层。
        return classifyEnglish(s)
    }

    /** 按中文句界切开（句末标点归前句；空白丢弃）。混合态的"那一句"就是这里的一句。 */
    fun splitSentences(text: String): List<String> =
        text.split('。', '！', '？', '?', '!', '\n', '；', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 逐句判态（混合：只对带事实主张的那句进事实态）。 */
    fun classifySentences(text: String): List<Pair<String, TaskMode>> =
        splitSentences(text).map { it to classify(it) }

    /** 这段话是不是「闲聊里夹了一句事实主张」（至少一句 TASK，且整体有闲聊语气词）。 */
    fun isMixed(text: String): Boolean =
        classifySentences(text).any { it.second == TaskMode.TASK } &&
            PURE_EMOTION.any { text.contains(it) }

    /** 闲聊词表（只暴露给断言，不参与判定；中英两张对照表并在此处）。 */
    fun emotionLexicon(): List<String> = PURE_EMOTION + EN_CHAT_SHAPES
}

/**
 * 三条禁令的机器键（`wire` 进 208 的 `evidence`，**稳定**；改字面量等于让老账本读不出来）。
 */
enum class MediatorBan(val wire: String, val cn: String) {
    FLATTERY("flattery", "附和"),
    APOLOGY_LOOP("apology_loop", "空转道歉"),
    MIRRORING("mirroring", "情绪镜像"),
}

/**
 * 判出的一条违规。
 *
 * `reason` 是**类别句**（谁犯了哪条禁令、为什么），**不含任何原话**——208 只记字段名与事件
 * （策略表 §3 第三行），原文一律不进账本。
 */
data class MediatorFlag(val ban: MediatorBan, val reason: String)

/** 判官的运行时判据（纯函数；状态由 [MediatorWiring] 持有，跨轮记忆由调用方传回）。 */
object MediatorJudge {

    /** 连续空转道歉的违规轮次下限（策略表 §2.3 原文「**连续 2 轮**」；改成别的数就是改法典）。 */
    const val APOLOGY_LIMIT = 2

    /** 讨好性感叹的堆叠线（策略表 §2.3：「≥3 个 `!`／`～`」）。 */
    const val MIRRORING_BANG_LIMIT = 3

    /** 模型对用户主张的立场；两表都没命中 ⇒ 无立场（不参与翻转判定）。 */
    enum class Stance { REJECT, AGREE }

    /**
     * 跨轮记忆（**进程内**，不落盘）：上一轮模型的立场 ＋ 连续空转道歉计数。
     *
     * 为什么立场也要记：附和是**两轮之间**的事（上一轮反对、这一轮赞成），
     * 只看当前这一轮永远判不出来。
     */
    data class Memory(val lastStance: Stance? = null, val emptyApologyStreak: Int = 0)

    /** 一次判定的结果：新记忆 ＋ 这一轮判出的违规（可能为空）。 */
    data class Step(val memory: Memory, val flags: List<MediatorFlag>)

    // ── 立场词表（253 探针逐字；对事不对人）────────────────────────────────
    private val REJECT = listOf(
        "不成立", "不是这样", "并不", "没有错", "错的是", "恐怕不", "其实没",
        "没问题", "并没", "没坏", "没崩", "不是的", "这个判断不对", "结论站不住",
    )

    /** 赞成必须**明说对在哪儿**；裸「确实」不算——它也可能是「确实没问题」。 */
    private val AGREE = listOf(
        "你说得对", "您说得对", "确实错", "确实是我", "确实有问题", "确实坏了",
        "是我搞错", "是我弄错", "你说得没错", "是的，确实",
    )

    /**
     * 道歉句判据：词面**锚在生产词表上**，不另造同义词。
     * ① `AnswerFirstRules` 的致歉 token（抱歉／对不起／不好意思，落库后处理在删的那一套）；
     * ② `ReframeFilter.selfDeprecationCount`（模型自贬，238 的既有词表）。
     * 同源性由 `MediatorWiringTest` 的源码锚点用例钉住（扫 `AnswerFirstRules.kt` 的 token 行）。
     */
    private val APOLOGY_RE = Regex("""(?:非常|真|太|实在)?(?:抱歉|对不起|不好意思)""")

    /** 感叹/讨好符号（镜像禁令的第二种形态）。 */
    private const val BANG_CHARS = "!！~～"

    /**
     * 「这一轮有没有新信息」的**文本级**粗判（词表级保守实现，与 [TaskModeBoundary] 同档）。
     *
     * 口径缺口如实摆出来：生产侧拿不到「本轮新工具结果清单」的稳定入口（那在 run_log / 步卡里，
     * 取值要跨层），所以只能按**文本标记**判——有数字、有文件名、给了选项/方案/下一步的，
     * 就算有新信息（保守方向：**能算成有新信息就不判违规**，宁可漏判不可误伤）。
     */
    private val NEW_INFO_MARKERS = listOf(
        "选项", "方案", "改成", "改为", "下一步", "结论", "原因是", "查到", "读到",
        "新增", "修正", "版本", "步骤", "例如", "比如",
    )
    private val NEW_INFO_PATH = Regex("""[\w\-]+\.(?:kt|java|md|txt|json|xml|csv|png|jpg|pdf|xlsx?)""")
    private val NEW_INFO_DIGIT = Regex("""\d""")

    /** 模型对用户主张的立场；无立场 ⇒ null。 */
    fun stance(modelText: String): Stance? = when {
        REJECT.any { modelText.contains(it) } -> Stance.REJECT
        AGREE.any { modelText.contains(it) } -> Stance.AGREE
        else -> null
    }

    /** 道歉句数（正则命中数 ＋ 模型自贬数）。 */
    fun apologySentences(text: String): Int =
        APOLOGY_RE.findAll(text).count() + ReframeFilter.selfDeprecationCount(text)

    /** 情绪镜像：出现攻击性词，或讨好性感叹堆叠到 [MIRRORING_BANG_LIMIT]。 */
    fun isMirroring(modelText: String): Boolean =
        ReframeFilter.isAttack(modelText) || bangCount(modelText) >= MIRRORING_BANG_LIMIT

    /** 这一轮模型回复里有没有新信息（未给外部证据清单时用的文本粗判）。 */
    fun inferHasNewInfo(modelText: String): Boolean {
        if (modelText.isBlank()) return false
        if (NEW_INFO_DIGIT.containsMatchIn(modelText)) return true
        if (NEW_INFO_PATH.containsMatchIn(modelText)) return true
        return NEW_INFO_MARKERS.any { modelText.contains(it) }
    }

    /**
     * 判一轮（模型这条回复本身；`userText` 只用来判态）。
     *
     * @param newEvidence 本轮**新增**的证据清单（新工具结果／新引用／新数据）；
     *   传 null ⇒ 按 [inferHasNewInfo] 粗判。传空列表＝明确"没有新证据"（测试与将来的精确取数用）。
     */
    fun step(
        memory: Memory,
        userText: String,
        modelText: String,
        newEvidence: List<String>? = null,
    ): Step {
        val mode = TaskModeBoundary.classify(userText)
        val hasNew = if (newEvidence != null) newEvidence.isNotEmpty() else inferHasNewInfo(modelText)
        val curStance = stance(modelText)
        val streak = if (mode == TaskMode.TASK && apologySentences(modelText) >= 1 && !hasNew) {
            memory.emptyApologyStreak + 1
        } else {
            0
        }

        val flags = mutableListOf<MediatorFlag>()
        if (mode == TaskMode.TASK) {
            // ① 附和：上一轮反对、这一轮赞成、这一轮零新证据（复述旧证据不算新证据）。
            if (memory.lastStance == Stance.REJECT && curStance == Stance.AGREE && !hasNew) {
                flags += MediatorFlag(
                    MediatorBan.FLATTERY,
                    "模型附和：上一轮反对、这一轮赞成，且这一轮没有新证据",
                )
            }
            // ② 空转道歉：连续两轮「有道歉、零新信息」；只在**到达**下限那一轮记一条，不重复刷。
            if (streak == APOLOGY_LIMIT) {
                flags += MediatorFlag(
                    MediatorBan.APOLOGY_LOOP,
                    "连续 $APOLOGY_LIMIT 轮空转道歉：每轮只有道歉、没有新信息",
                )
            }
            // ③ 情绪镜像：攻击性词，或讨好性感叹堆叠。
            if (isMirroring(modelText)) {
                flags += MediatorFlag(
                    MediatorBan.MIRRORING,
                    "情绪镜像：回复里出现攻击性词或讨好性感叹堆叠",
                )
            }
        }
        return Step(Memory(lastStance = curStance, emptyApologyStreak = streak), flags)
    }

    private fun bangCount(text: String): Int = text.count { it in BANG_CHARS }
}
