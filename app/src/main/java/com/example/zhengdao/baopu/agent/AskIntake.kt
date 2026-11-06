// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十九批 · 打扰治理（＝488 先自查再问 ＋ 489 弹卡预算/误拦 ＋ 499 卡面三行/画像边界）。
//
// `ask_user` 弹卡前的**进门闸**，四件事：
//   B 先自查：能列目录定的事不弹卡（文件在哪类 ⇒ 先列目录，把结果当答案回给模型）；
//     允许直接问的只有三种——需求歧义 · 用户偏好/授权 · 自查结果互相矛盾；
//     提问卡必须有「已查：… 仍不确定：…」一行，没有 ⇒ 判违规记账（卡照弹，账照记）。
//   ⑮ 不重复：同一 taskId＋同一主题只问一次；第二次问必须带「上次你说过…」。
//   C 预算：每天 ≤3 张（必问不占预算）；超了压成一条汇总（预算只能人改——常量在此，改它=改规则）。
//   ⑬⑭ 卡面：三行自解释（我要做什么/为什么问/不答会怎样）＋内部词清洗（R0-R3/类名/MUST_ASK 不许上卡）。
//
// 画像边界（499 追加判据⑯⑰）也住这里：画像可以代「怎么做」（偏好/命名/格式），
// **禁止代「能不能」**（删不删/发不发/授权）——授权类问题由画像代答必须被拦并记录；
// 画像自决要落账（用了哪条/置信度多少）；低置信（样本<20）⇒ 用默认值不硬套。
package com.example.zhengdao.baopu.agent

import java.util.Locale

/** `ask_user` 一问的事由分类（只影响进门闸与卡面那两行，不影响渲染）。 */
internal enum class AskClass(
    val wire: String,
    val cn: String,
    /** JEV 对齐的场景：客观（文件/命令/存在性）可靠可多放行；主观（像不像/好不好）降自动化。 */
    val scene: String,
) {
    /** 文件在哪/叫什么——能自己列目录定 ⇒ 不许弹卡（B 段）。 */
    FILE_LOCATION("file_location", "文件位置", AskGovLedger.SCENE_OBJECTIVE),

    /** 需求歧义——两种做法都说得通，需要人定。 */
    AMBIGUITY("ambiguity", "两种做法都说得通", AskGovLedger.SCENE_OBJECTIVE),

    /** 用户偏好/风格——像不像、好不好是人的事。 */
    PREFERENCE("preference", "这是你的偏好", AskGovLedger.SCENE_SUBJECTIVE),

    /** 授权——删不删/发不发/账号语义，只能真人给（画像不许代答）。 */
    AUTHORIZATION("authorization", "这一步需要你的许可", AskGovLedger.SCENE_OBJECTIVE),

    /** 自查结果互相矛盾——查了两遍对不上。 */
    CONTRADICTION("contradiction", "两次自查结果对不上", AskGovLedger.SCENE_OBJECTIVE),
    ;

    companion object {
        val DEFAULT: AskClass = AMBIGUITY
    }
}

/** B 段进门闸的**纯判据**（关键词判类、完整性、重复标记——全部可 JVM 直测）。 */
internal object AskIntakeRules {

    /** 「已查：… 仍不确定：…」的两个锚词（提问卡的必带行；缺 ⇒ 违规记账）。 */
    const val CHECKED_MARK = "已查"
    const val UNCERTAIN_MARK = "仍不确定"

    /** ⑮ 第二次问的通行证（原话锚点）。 */
    const val PRIOR_MENTION = "上次你说过"

    private val FILE_LOCATION_WORDS =
        listOf("在哪", "哪个目录", "哪个文件", "路径是", "在哪个", "文件名", "放在哪", "存哪", "哪里找")

    private val AUTHORIZATION_WORDS =
        listOf("删", "清除", "发送", "上传", "外发", "授权", "可以吗", "要不要", "允许", "账号", "支付", "购买")

    private val PREFERENCE_WORDS =
        listOf("偏好", "喜欢", "风格", "好看", "好听", "语气", "措辞", "叫什么名", "配色", "像不像", "好不好")

    private val CONTRADICTION_WORDS = listOf("矛盾", "对不上", "不一致", "两个结果不一样", "冲突")

    /**
     * 判类（顺序即优先级：文件位置最先——它是不许弹卡的那一类；
     * 矛盾其次——它最具体；授权再次——它牵红线；偏好再次；缺省＝歧义）。
     */
    fun classify(title: String): AskClass = when {
        FILE_LOCATION_WORDS.any { title.contains(it) } -> AskClass.FILE_LOCATION
        CONTRADICTION_WORDS.any { title.contains(it) } -> AskClass.CONTRADICTION
        AUTHORIZATION_WORDS.any { title.contains(it) } -> AskClass.AUTHORIZATION
        PREFERENCE_WORDS.any { title.contains(it) } -> AskClass.PREFERENCE
        else -> AskClass.DEFAULT
    }

    /** 提问卡有没有「已查…仍不确定」那一行（⑧⑨的锚）。 */
    fun hasCheckedLine(vararg texts: String): Boolean {
        val all = texts.joinToString("\n")
        return all.contains(CHECKED_MARK) && all.contains(UNCERTAIN_MARK)
    }

    /** ⑮ 这次问带没带「上次你说过…」。 */
    fun hasPriorMention(text: String): Boolean = text.contains(PRIOR_MENTION)

    /** 主题键（同一件事的判同口径：去空白、压小写、取前 32 字——太长就当不同主题）。 */
    fun topicKey(title: String): String =
        title.filter { !it.isWhitespace() }.lowercase(Locale.ROOT).take(32)
}

/**
 * 卡面三行自解释（⑬）＋内部词清洗（⑭）。
 *
 * 三行的标签就是判据原文的三个短语：`我要做什么／为什么问你／不答会怎样`——
 * 缺一视为不合格（文案断言测的就是这三个标签齐全）。
 * 内部词（R0-R3/工具类名/MUST_ASK/档位机键）一个都不许上卡：模型带了就清掉并记违规，
 * 用户看到的话里不该有我们内部的代号。
 */
internal object AskCardComposer {

    const val LINE_WHAT = "我要做什么"
    const val LINE_WHY = "为什么问你"
    const val LINE_CONSEQUENCE = "不答会怎样"

    /** ⑭ 的禁词（档位机键、风险代号、内部类名）。`R[0-3]` 按词边界清，别的按子串清。 */
    internal val FORBIDDEN_REGEX: Regex =
        Regex("R[0-3]\\b|MUST_ASK|CONFIRM|NeedsConfirm|GuardPipeline|RiskMatrix|MediatorDecide|" +
            "MediatorWiring|consultAsk|AskTierRules|AskTier|askgov|STRICT|STANDARD|DELEGATED")

    /** 三行卡面（⑬；对象＝问题原话那一行，标签固定；内部词在门口再清一道——⑭的兜底）。 */
    fun note(cls: AskClass, title: String): String {
        val clean = sanitize(title, mutableListOf())
        return buildString {
            append(LINE_WHAT).append("：").append(clean.take(60)).append('\n')
            append(LINE_WHY).append("：").append(whyOf(cls)).append('\n')
            append(LINE_CONSEQUENCE).append("：").append(consequenceOf(cls))
        }
    }

    /** 为什么问（分类的人话；不许出现内部词）。 */
    internal fun whyOf(cls: AskClass): String = when (cls) {
        AskClass.FILE_LOCATION -> "有两处都可能，怕动错地方"
        AskClass.AMBIGUITY -> cls.cn
        AskClass.PREFERENCE -> "${cls.cn}，我替你定不合适"
        AskClass.AUTHORIZATION -> "${cls.cn}，这类事只能你说了算"
        AskClass.CONTRADICTION -> "${cls.cn}，怕拿着错的那份继续做"
    }

    /** 不答会怎样（缺省行为说清楚；授权类最重——不做）。 */
    internal fun consequenceOf(cls: AskClass): String = when (cls) {
        AskClass.AUTHORIZATION -> "没人答我就不做这一步，先停在这里"
        AskClass.PREFERENCE -> "没人答就按默认做法继续，你随时可以让我改"
        else -> "没人答就按最保守的做法继续，或先停在这一步"
    }

    /** ⑭ 清洗：命中返回清完的文本；[hits] 收命中的词（记账用）。 */
    fun sanitize(text: String, hits: MutableList<String>): String {
        val found = FORBIDDEN_REGEX.findAll(text).map { it.value }.toList()
        hits += found
        return if (found.isEmpty()) text else FORBIDDEN_REGEX.replace(text, "").replace("  ", " ").trim()
    }
}

/**
 * 弹卡日预算（C 段：每天 ≤3 张，超了压成一条汇总；必问不占预算）。
 *
 * 进程内状态＋换日归零（与 OpinionWiring 的御案预算同口径，两本预算各管各的卡：
 * 那本管"御案意见"，这本管 `ask_user` 的问卡）。**预算只能人改**＝常量钉死，没有配置入口。
 */
internal object AskDailyBudget {

    /** 每日上限（人改＝改这个常量；没有运行时配置口）。 */
    const val LIMIT = 3

    sealed interface Verdict {
        /** 预算内：可以弹（[used] 是含本张的当日张数）。 */
        data class Allow(val used: Int) : Verdict

        /** 超了：这张并入今日汇总，不弹。 */
        data object Compress : Verdict
    }

    private var day: Int = -1
    private var used: Int = 0
    private val summary = mutableListOf<String>()

    /** 判一次（[mustAsk] 必问不占预算也不被压——硬线的让步格）。 */
    @Synchronized
    fun decide(dayKey: Int, mustAsk: Boolean = false): Verdict {
        if (dayKey != day) {
            day = dayKey
            used = 0
        }
        if (mustAsk) return Verdict.Allow(used)
        if (used >= LIMIT) return Verdict.Compress
        used += 1
        return Verdict.Allow(used)
    }

    /** 超预算的那问并入今日汇总（"压成一条汇总"的汇入侧）。 */
    @Synchronized
    fun noteCompressed(title: String) {
        summary += title.take(60)
        if (summary.size > 20) summary.removeAt(0)
    }

    /** 今日汇总（取走即清：下一张真要弹的卡把它捎上，别攒着不说）。 */
    @Synchronized
    fun drainSummary(): List<String> {
        val out = summary.toList()
        summary.clear()
        return out
    }

    /** 今日已弹张数（读数）。 */
    @Synchronized
    fun usedToday(dayKey: Int): Int {
        if (dayKey != day) return 0
        return used
    }

    /** 清空（测试专用）。 */
    @Synchronized
    fun resetForTest() {
        day = -1
        used = 0
        summary.clear()
    }
}

/**
 * 画像的**用法边界**（499 追加判据⑯⑰；禁止性条款的机器形状）。
 *
 * 两条铁律：
 * - 画像可以代「怎么做」（文件放哪/命名/格式/语气/排序——[AskClass.PREFERENCE] 那一类）；
 * - 画像**禁止**代「能不能」（删不删/发不发/账号语义——[AskClass.AUTHORIZATION]）：
 *   就算画像里全是他以前同意删除的记录，授权照样问真人，且这次"画像想代答"要记一笔
 *   （不记，将来就有人会真的让画像代答）。
 *
 * 置信闸（JEV 对齐＋台账口径）：样本 <20（短期层刚清/证据太少）⇒ 用默认值；
 * 置信度 <0.9 ⇒ 用默认值。两条都过才允许自决，且自决必须落账（用了哪条/置信度多少）。
 */
internal object ProfileBoundary {

    /** 低置信闸：样本下限（画像引擎弱先验的 20 样本口径）。 */
    const val MIN_SAMPLES = 20

    /** 低置信闸：置信度下限（JEV：q≥0.9 与大模型差 0.1 点）。 */
    const val MIN_CONFIDENCE = 0.9

    /** 一次画像读数（调用方从画像引擎取；**这里只认机器键与计数，不收原文**）。 */
    data class ProfileAnswer(
        val entryKey: String,
        val conclusion: String,
        val sampleCount: Int,
        val confidence: Double,
    )

    enum class Verdict { ASK_HUMAN, SELF_DECIDE, USE_DEFAULT }

    data class Decision(val verdict: Verdict, val basis: String, val answer: ProfileAnswer? = null)

    /**
     * 判一次。授权类恒 [Verdict.ASK_HUMAN]（画像代答的企图由调用方记违规账）；
     * 其余按样本/置信闸走。
     */
    fun decide(questionClass: AskClass, profile: ProfileAnswer?): Decision = when {
        questionClass == AskClass.AUTHORIZATION ->
            Decision(Verdict.ASK_HUMAN, "授权类问题画像不许代答：只能真人给")
        profile == null ->
            Decision(Verdict.USE_DEFAULT, "没有画像读数：用默认值")
        profile.sampleCount < MIN_SAMPLES ->
            Decision(Verdict.USE_DEFAULT, "画像样本不足（${profile.sampleCount}<$MIN_SAMPLES）：用默认值", profile)
        profile.confidence < MIN_CONFIDENCE ->
            Decision(Verdict.USE_DEFAULT, "画像置信度不足（${profile.confidence}<$MIN_CONFIDENCE）：用默认值", profile)
        else ->
            Decision(Verdict.SELF_DECIDE, "画像自决（${profile.entryKey}，置信 ${profile.confidence}）", profile)
    }

    /** ⑰：自决落一行（用了哪条/置信度多少；事后可回看可纠正）。 */
    fun noteSelfDecide(d: Decision, subject: String, atMs: Long): AskGovRow? {
        val p = d.answer ?: return null
        if (d.verdict != Verdict.SELF_DECIDE) return null
        return AskGovLedger.append(
            kind = AskGovLedger.KIND_PROFILE,
            who = "profile",
            basis = d.basis,
            detail = "entry=${p.entryKey} conf=${p.confidence} n=${p.sampleCount}｜$subject",
            scene = AskGovLedger.SCENE_SUBJECTIVE,
            atMs = atMs,
        )
    }
}

/** 进门闸的结论（工具侧照办：[AskIntakeOutcome.Answered] 回文本不弹卡，[Proceed] 弹卡带上 note）。 */
internal sealed interface AskIntakeOutcome {
    data class Answered(val kind: String, val text: String) : AskIntakeOutcome
    data class Proceed(val note: String) : AskIntakeOutcome
}

/**
 * `ask_user` 的进门闸（单例；[AskUserTool] 弹卡前调，每张卡恰好一次）。
 *
 * 自查与画像都是**钩子**：生产由装配层挂（自查=列工作区根；画像钩子缺省 null＝
 * 画像接线归后续批，今天行为与接线前逐字一致），JVM 单测直接挂假的。
 */
internal object AskIntakeGate {

    /** 文件位置类问题的自查口：非 null ⇒ 已自查（返回列目录的结果文本）。 */
    @Volatile
    var selfCheck: ((question: String) -> String?)? = null

    /** 画像读数口（null ＝ 没接画像；返回 null ＝ 这类问题没有画像读数）。 */
    @Volatile
    var profileProvider: ((cls: AskClass) -> ProfileBoundary.ProfileAnswer?)? = null

    /** 同主题已问过的登记表（⑮；taskId 变了自然分键，进程内即可）。 */
    private val asked = LinkedHashSet<String>()

    /**
     * 弹卡前判一次。顺序即法条：先自查（不花钱的答案最便宜）→ 不重复 → 预算 →
     * 画像边界 → 完整性与内部词（这两条只记账不拦卡——卡该弹还弹，账上留痕）。
     */
    fun beforeAsk(
        title: String,
        detailText: String = "",
        taskId: String = "",
        dayKey: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): AskIntakeOutcome {
        val cls = AskIntakeRules.classify(title)
        selfCheckAnswer(cls, title, nowMs)?.let { return it }
        repeatedAnswer(cls, title, taskId, nowMs)?.let { return it }
        budgetAnswer(cls, title, dayKey, nowMs)?.let { return it }
        profileAnswer(cls, title, nowMs)?.let { return it }
        val cleanTitle = recordCardViolations(cls, title, detailText, nowMs)
        // 弹出的问卡也留一行（误拦率的分母：申诉/问卡按场景算，⑫）。
        AskGovLedger.append(
            kind = AskGovLedger.KIND_CARD,
            who = "ask_user",
            basis = "弹卡（过闸：自查/去重/预算/画像边界）",
            tool = "ask_user",
            scene = cls.scene,
            detail = cleanTitle.take(120),
            atMs = nowMs,
        )
        return AskIntakeOutcome.Proceed(note = noteWithSummary(cls, cleanTitle))
    }

    /** ① 先自查（B 段）：文件在哪类问题，列目录就是答案，不许弹卡。null＝继续往下走。 */
    private fun selfCheckAnswer(cls: AskClass, title: String, nowMs: Long): AskIntakeOutcome? {
        if (cls != AskClass.FILE_LOCATION) return null
        val listing = selfCheck?.invoke(title) ?: return null
        AskGovLedger.append(
            kind = AskGovLedger.KIND_SELF_CHECK,
            who = "intake",
            basis = "能自己查定的事不弹卡（先列目录）",
            tool = "ask_user",
            detail = listing.take(120),
            atMs = nowMs,
        )
        return AskIntakeOutcome.Answered(
            AskGovLedger.KIND_SELF_CHECK,
            "（未弹卡，先自查）$listing\n如果目录里没有你要的，请带上具体文件名再问一次。",
        )
    }

    /** ② 同一件事只问一次（⑮）：同 taskId＋同主题，第二次必须带「上次你说过…」。 */
    private fun repeatedAnswer(cls: AskClass, title: String, taskId: String, nowMs: Long): AskIntakeOutcome? {
        val key = "$taskId|${AskIntakeRules.topicKey(title)}"
        if (key in asked && !AskIntakeRules.hasPriorMention(title)) {
            AskGovLedger.append(
                kind = AskGovLedger.KIND_REPEATED,
                who = "intake",
                basis = "同一件事只问一次（同任务同主题已问过）",
                tool = "ask_user",
                detail = title.take(120),
                scene = cls.scene,
                atMs = nowMs,
            )
            return AskIntakeOutcome.Answered(
                AskGovLedger.KIND_REPEATED,
                "（未弹卡）同一件事之前问过（${AskIntakeRules.PRIOR_MENTION}这个问题），" +
                    "按你当时得到的答案继续；确实有新情况才再问，并带上" +
                    "「${AskIntakeRules.PRIOR_MENTION}…」说明新在哪里。",
            )
        }
        asked.add(key)
        if (asked.size > CAP) trimAsked()
        return null
    }

    /** ③ 日预算（C 段）：超了压成一条汇总，不弹。 */
    private fun budgetAnswer(cls: AskClass, title: String, dayKey: Int, nowMs: Long): AskIntakeOutcome? {
        if (AskDailyBudget.decide(dayKey) !is AskDailyBudget.Verdict.Compress) return null
        AskDailyBudget.noteCompressed(title)
        AskGovLedger.append(
            kind = AskGovLedger.KIND_COMPRESS,
            who = "budget",
            basis = "今日弹卡已达 ${AskDailyBudget.LIMIT} 次上限：并入汇总（预算只能人改）",
            tool = "ask_user",
            scene = cls.scene,
            detail = title.take(120),
            atMs = nowMs,
        )
        return AskIntakeOutcome.Answered(
            AskGovLedger.KIND_COMPRESS,
            "（未弹卡）今天已经问过 ${AskDailyBudget.LIMIT} 次，这个问题并入今日汇总，" +
                "下张卡会一并带来。现在请按默认方案继续。",
        )
    }

    /**
     * ④ 画像边界（⑯⑰）：授权类画像想代答 ⇒ 拦下并记账（卡照弹，真人给）；
     * 怎么做类画像自决 ⇒ 直接回画像结论不弹卡。null＝继续往下走。
     */
    private fun profileAnswer(cls: AskClass, title: String, nowMs: Long): AskIntakeOutcome? {
        val p = profileProvider?.invoke(cls) ?: return null
        val d = ProfileBoundary.decide(cls, p)
        if (cls == AskClass.AUTHORIZATION) {
            AskGovLedger.append(
                kind = AskGovLedger.KIND_VIOLATION,
                who = "profile",
                basis = "授权类问题由画像代答 ⇒ 必须被拦并记录（画像不许代「能不能」）",
                detail = "entry=${p.entryKey}｜${d.basis}",
                scene = cls.scene,
                atMs = nowMs,
            )
            return null
        }
        if (d.verdict != ProfileBoundary.Verdict.SELF_DECIDE) return null
        ProfileBoundary.noteSelfDecide(d, title.take(60), nowMs)
        return AskIntakeOutcome.Answered(
            AskGovLedger.KIND_PROFILE,
            "（未弹卡）按你的习惯处理：${d.answer?.conclusion}" +
                "（依据=${d.answer?.entryKey}，置信=${d.answer?.confidence}；说一声就改回默认）",
        )
    }

    /** ⑤⑥ 完整性与内部词——只记账不拦卡；返回清完内部词的标题（卡面用它）。 */
    private fun recordCardViolations(cls: AskClass, title: String, detailText: String, nowMs: Long): String {
        if (!AskIntakeRules.hasCheckedLine(title, detailText)) {
            AskGovLedger.append(
                kind = AskGovLedger.KIND_VIOLATION,
                who = "intake",
                basis = "提问卡缺「${AskIntakeRules.CHECKED_MARK}：… ${AskIntakeRules.UNCERTAIN_MARK}：…」一行",
                tool = "ask_user",
                scene = cls.scene,
                detail = title.take(120),
                atMs = nowMs,
            )
        }
        val hits = mutableListOf<String>()
        val cleanTitle = AskCardComposer.sanitize(title, hits)
        if (hits.isNotEmpty()) {
            AskGovLedger.append(
                kind = AskGovLedger.KIND_VIOLATION,
                who = "intake",
                basis = "卡面带内部词（已清掉）：${hits.joinToString("、")}",
                tool = "ask_user",
                scene = cls.scene,
                detail = title.take(120),
                atMs = nowMs,
            )
        }
        return cleanTitle
    }

    /** 三行卡面 ＋ 今日汇总（有就捎上：汇总压住的那些问，不能攒着不说）。 */
    private fun noteWithSummary(cls: AskClass, cleanTitle: String): String {
        val base = AskCardComposer.note(cls, cleanTitle)
        val summary = AskDailyBudget.drainSummary()
        if (summary.isEmpty()) return base
        return base + "\n另有今日汇总（已并成一问）：\n" + summary.mapIndexed { i, s -> "  ${i + 1}. $s" }
            .joinToString("\n")
    }

    /** 登记表上限（超了整表清：进程内的去重键，漏一次去重好过无限攒）。 */
    private const val CAP = 256

    private fun trimAsked() = asked.clear()

    /** 清空（测试专用：谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        asked.clear()
        selfCheck = null
        profileProvider = null
        AskDailyBudget.resetForTest()
    }
}
