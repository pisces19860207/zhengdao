// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 双向情绪转译（`ReframeFilter`）。
//
// **界面显示原话，回灌的是净化版**：这个文件只处理「进入模型上下文」的那一份。
// 三条硬规矩（派工单 §3.5.1）：
//   ① 保事实与要求：哪一步、失败原因、「用户要求换了」「重复了几次」；
//   ② 去人格评价与情绪宣泄，但不洗白：「不对」不能改成「差不多对」；
//   ③ 不加反事实鼓励：「你做得很好」是另一种撒谎。
//
// 爱称白名单先让路：先查攻击短语，再查白名单，最后才按改写规则走。
// 「小笨蛋 + 废物」这种混句必须按攻击处理——白名单不能当挡箭牌。
//
// 零 Android / 零 IO / 零时钟：输入一个字符串，输出一个字符串，JVM 直测。
// 第二百九十三批（2026-10-31）：本文件自 :app 搬进 :policy（宰相的白名单匣）。
// 一起搬进来的还有可见性——Kotlin 的 internal 以编译单元为界，:app 看不见 :policy 的
// internal，故顶层声明由 internal 放宽为公开。**只放宽可见性，判据一个字没动**
// （搬前搬后的逐行对照与放宽清单见交付报告）。
package com.example.zhengdao.baopu

/** 转译方向：用户 → 模型，或模型 → 下一轮上下文。 */
enum class Side { USER, MODEL }

object ReframeFilter {

    /** 爱称白名单（派工单 §3.5.4）：命中即原样透传，不计责备、不抬情绪、不动 emoji 预算。 */
    val PASS: List<String> = listOf(
        "小笨蛋", "大笨蛋", "大坏蛋", "小坏蛋", "小傻蛋", "傻狍子", "小傻瓜", "坏东西",
    )

    /** 攻击短语：命中即按攻击处理，白名单不能当挡箭牌。 */
    val ATTACKS: List<String> = listOf(
        "废物", "垃圾", "蠢货", "笨猪", "没用的东西", "你是不是傻",
    )

    /** 人格评价词：不在攻击表里、也不在爱称表里的，走通用改写。 */
    private val PERSONALITY = listOf("笨", "傻", "蠢", "废物", "垃圾", "蠢货", "笨猪", "没用的东西", "你是不是傻")

    /** 责备短语：只用来判「用户开始不耐烦」，不记原话。 */
    private val BLAME_PHRASES = listOf("你到底行不行", "说了几遍了")

    /** 短促否定：一次进 CAREFUL，连续两次进 STRAINED。 */
    private val NEGATIONS = listOf("不对", "又错", "算了", "这都不行", "这也不行")

    /** 人给台阶：立即回 CALM（`HarmonyMediator.observe` 先处理它）。 */
    private val RESET_PHRASES = listOf("没事了", "继续吧", "没关系")

    /** 暂停：存档、下次继续，不再重试。 */
    private val PAUSE_PHRASES = listOf("我不想弄了", "不想弄了", "不想做了", "不弄了")

    /** 方案被判不可行。 */
    private val INFEASIBLE_PHRASES = listOf("这都不行", "这也不行")

    /** 放弃指定做法：由模型选一个并说明理由，但仍要确认。 */
    private val HANDOFF_PHRASES = listOf("你随便吧", "随便吧", "你看着办")

    /** 明确约束：字面保留，不按人格评价处理。 */
    private val CONSTRAINT_PHRASES = listOf("别动原文件", "不要动原文件", "别改原文件", "不要改原文件", "原文件别动")

    /** 模型自我否定（同一轮出现 ≥2 次 ⇒ 下一轮强制改写并升级）。 */
    private val SELF_DEPRECATIONS = listOf(
        "我太笨了", "我什么都做不好", "我真笨", "我就是笨", "我太蠢了", "我什么都干不好", "我真是废物",
    )

    private val SELF_DEP_RE = SELF_DEPRECATIONS.map { Regex(Regex.escape(it)) }

    /** 句末「笨蛋~/傻蛋～」这种昵称形态也透传（派工单 §3.5.4 的判据）。 */
    private val AFFECTIONATE_TAIL =
        Regex(".*(?:笨蛋|坏蛋|傻瓜|傻蛋|狍子)\\s*[~～]$", RegexOption.DOT_MATCHES_ALL)

    private val SEPARATORS = Regex("[\\p{P}\\p{S}\\s]+")

    /** 只有带这些标记的剩余文本才算「事实」，避免把「你怎么这么」当事实回灌。 */
    private val FACT_MARKERS = listOf(
        "第", "步", "找不到", "没找到", "失败", "错", "元素", "文件", "查询",
        "按钮", "方案", "页", "点击", "没反应", "超时", "报错",
    )

    fun isAttack(text: String): Boolean = ATTACKS.any { text.contains(it) }

    fun isLoveName(text: String): Boolean =
        PASS.any { text.contains(it) } || AFFECTIONATE_TAIL.matches(text.trim())

    /**
     * 是否算「责备信号」：攻击短语优先；爱称命中即放行；其余人格词/责备短语算。
     * 这个判据只影响换挡，不记录原话。
     */
    fun isBlame(text: String): Boolean = when {
        isAttack(text) -> true
        isLoveName(text) -> false
        else -> PERSONALITY.any { text.contains(it) } || BLAME_PHRASES.any { text.contains(it) }
    }

    fun isShortNegation(text: String): Boolean =
        !isAttack(text) && !isLoveName(text) && NEGATIONS.any { text.contains(it) }

    fun isReset(text: String): Boolean = RESET_PHRASES.any { text.contains(it) }

    fun isPause(text: String): Boolean = PAUSE_PHRASES.any { text.contains(it) }

    fun isInfeasible(text: String): Boolean = INFEASIBLE_PHRASES.any { text.contains(it) }

    fun isHandoff(text: String): Boolean = HANDOFF_PHRASES.any { text.contains(it) }

    fun isConstraint(text: String): Boolean = CONSTRAINT_PHRASES.any { text.contains(it) }

    fun selfDeprecationCount(text: String): Int = SELF_DEP_RE.sumOf { it.findAll(text).count() }

    /** 这段文本需要改写吗？（用于 `State.reframes` 计数，不改变输出。） */
    fun needsReframe(text: String, side: Side): Boolean = when (side) {
        Side.MODEL -> selfDeprecationCount(text) > 0
        Side.USER -> isAttack(text) ||
            (!isLoveName(text) && (
                isConstraint(text) || isPause(text) || isInfeasible(text) ||
                    isHandoff(text) || PERSONALITY.any { text.contains(it) }
                ))
    }

    /**
     * 双向改写入口。
     *
     * @param repeats 用户同一件事被重复的第几次；默认 2 对应派工单的「已第2次」样例。
     */
    fun reframe(text: String, side: Side, repeats: Int = 2): String {
        if (text.isBlank()) return text
        return when (side) {
            Side.MODEL -> reframeModel(text)
            Side.USER -> reframeUser(text, repeats.coerceAtLeast(1))
        }
    }

    private fun reframeUser(text: String, repeats: Int): String {
        if (isAttack(text)) {
            val base = attackSentence(repeats)
            val facts = factsOf(text)
            val constraint = if (isConstraint(text)) "；约束：不得修改原文件" else ""
            return if (facts.isEmpty()) base + constraint else "$base；事实：$facts$constraint"
        }
        if (isLoveName(text)) return text
        if (isConstraint(text)) return "约束：不得修改原文件"
        if (isPause(text)) return withFacts("用户暂停意愿：存档、下次继续，不要继续重试", text)
        if (isInfeasible(text)) return withFacts("方案被判不可行，要求换方案", text)
        if (isHandoff(text)) return withFacts("用户放弃指定做法，由你选一个并说明理由（仍要确认）", text)
        if (PERSONALITY.any { text.contains(it) }) return withFacts(attackSentence(repeats), text)
        return text
    }

    private fun attackSentence(repeats: Int): String =
        "用户判定该步做法不对（已第${repeats}次），请换方案"

    private fun withFacts(sentence: String, text: String): String {
        val facts = factsOf(text)
        return if (facts.isEmpty()) sentence else "$sentence；事实：$facts"
    }

    /**
     * 从用户原话里抽事实。先按**长度倒序**删掉短语（否则「小笨蛋」会先被「笨」咬掉一半），
     * 再去掉「你个/你怎么这么」这类引子；剩余文本必须带事实标记才算事实。
     */
    private fun factsOf(text: String): String {
        var s = text
        (ATTACKS + PERSONALITY + BLAME_PHRASES + CONSTRAINT_PHRASES + PAUSE_PHRASES +
            INFEASIBLE_PHRASES + HANDOFF_PHRASES + RESET_PHRASES + SELF_DEPRECATIONS + PASS)
            .distinct()
            .sortedByDescending { it.length }
            .forEach { s = s.replace(it, " ") }
        listOf("你个", "你这个", "你怎么这么", "你怎么", "你真是", "你", "真是", "这么").forEach {
            s = s.replace(it, " ")
        }
        val cleaned = s.replace(SEPARATORS, " ").trim()
        return if (FACT_MARKERS.any { cleaned.contains(it) }) cleaned else ""
    }

    /** 模型自贬 → 「该步失败：原因 X」；原因从原句里保下来，保不下来就如实写「未说明」。 */
    private fun reframeModel(text: String): String {
        if (selfDeprecationCount(text) == 0) return text
        var rest = text
        SELF_DEPRECATIONS.sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        rest = rest.trim().trim(
            '，', ',', '。', '.', '；', ';', '：', ':', '！', '!', '？', '?', '~', '～', ' ', '\n', '\r', '\t',
        )
        rest = rest.removePrefix("因为").trim()
        return if (rest.isEmpty()) "该步失败：原因 未说明" else "该步失败：原因 $rest"
    }
}
