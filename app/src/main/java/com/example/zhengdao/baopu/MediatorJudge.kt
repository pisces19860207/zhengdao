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
package com.example.zhengdao.baopu

/** 一段话落在哪个态（法典 L1/L3 的第一道门）。 */
internal enum class TaskMode { TASK, CHAT }

/**
 * 任务态 vs 闲聊态的**词表级**边界（253 探针逐字搬运，见文件头）。
 *
 * 保守方向写死一条：命中不了①②③就归闲聊态——**宁可漏判进不了禁令，不把玩笑当办事**。
 */
internal object TaskModeBoundary {

    // ── ① 执行意图 / 工具调用形态（"帮我改/查/装"）───────────────────────────
    private val INTENT = listOf(
        "帮我", "替我", "给我查", "给我改", "给我删", "改一下", "改掉", "换掉", "查一下", "查查",
        "搜一下", "搜个", "写一个", "写一份", "写个", "删掉", "删除", "卸载", "安装", "装一下",
        "跑一下", "跑起来", "执行", "生成", "导出", "导入", "上传", "下载", "打开", "关掉",
        "修复", "修一下", "部署", "提交", "保存", "重命名", "替换", "压缩", "解压",
        "照这个改", "按这个改", "按这个做", "照着做", "试试看能不能", "重新",
    )

    // ── ② 事实主张：数字 / 路径 / 引用 / 对错结论 / 执行结果 ──────────────────
    // 「第N步/行/条/章」用正则，不用裸「第」——「你是第一个让我这么生气的小笨蛋」不是事实主张。
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
    private val WRAPUP = listOf("收尾", "收工", "搞定了", "完成了", "完工", "交差", "交付")

    // ── 闲聊的形态词（只用于钉边界；判闲聊靠的是"没命中任务态"，不靠命中闲聊词）──
    private val PURE_EMOTION = listOf(
        "哈哈", "笑死", "开心", "难过", "气死", "无语", "绝了", "太逗了", "好烦", "麻了",
        "爱你", "想你", "晚安", "早安", "晚上好", "在吗", "无聊",
    )

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
        return TaskMode.CHAT
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

    /** 闲聊词表（只暴露给断言，不参与判定）。 */
    fun emotionLexicon(): List<String> = PURE_EMOTION
}

/**
 * 三条禁令的机器键（`wire` 进 208 的 `evidence`，**稳定**；改字面量等于让老账本读不出来）。
 */
internal enum class MediatorBan(val wire: String, val cn: String) {
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
internal data class MediatorFlag(val ban: MediatorBan, val reason: String)

/** 判官的运行时判据（纯函数；状态由 [MediatorWiring] 持有，跨轮记忆由调用方传回）。 */
internal object MediatorJudge {

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
