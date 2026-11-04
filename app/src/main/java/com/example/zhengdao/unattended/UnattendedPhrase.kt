// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十一单 · 一句话值守的**纯判定件**：把用户在主界面输入框敲的那句话
// 判成「进场／出场／低置信反问／无关」四类之一。
//
// 四条纪律（对应 441 派工单【安全边界】）：
// 1. **只认紧话**：进场/出场词表是**闭集**（高置信短语整段包含才算），
//    "可能要出门""准备出去"这类带犹豫的只算低置信 ⇒ 反问，不进场（判据⑦）。
//    词表宁紧勿松：误进场比漏进场严重（误报会空跑一轮值守，漏报她自己还会再说一遍）。
// 2. **出场先判**："两小时后回来"里也有"回来"两个字——判序必须是
//    出场 → 进场（带时长）→ 进场（紧词表）→ 低置信 → 无关，
//    顺序一乱"两小时后回来"就会被判成出场（判据②就是要它进场）。
// 3. **零时钟零 IO**：纯函数，JVM 单测直接钉；时刻数学归接线层。
// 4. **只做文字**（判据⑧）：本件不吃语音、不吃通知、不吃工具返回值——
//    谁能把它喂进来归接线层管（UnattendedChatTrigger，只有主界面 send 一条道）。
package com.example.zhengdao.unattended

/** 一句话的判定结论（闭集；接线层按格办事：开值守／问一声／收值守／不动）。 */
internal sealed class UnattendedPhraseVerdict {

    /** 进场：开值守。[durationMs] 是用户口述的预计时长（没说 ⇒ null）。 */
    data class Arm(val durationMs: Long?) : UnattendedPhraseVerdict()

    /** 低置信进场 ⇒ 只反问，不进场（判据⑦负例）。 */
    data object AskArm : UnattendedPhraseVerdict()

    /** 显式出场："我回来了／到家了"。 */
    data object ExitExplicit : UnattendedPhraseVerdict()

    /** 无关：不是值守的话（值守中它照样触发隐式出场——那句归接线层判，不归本件）。 */
    data object None : UnattendedPhraseVerdict()
}

/** 一句话值守的判定（唯一判口；词表与判序都钉在这，别处不自造）。 */
internal object UnattendedPhrase {

    // ── 词表（闭集；整段包含才算，宁紧勿松） ─────────────────────────────

    /** 显式出场（判据③）：必须带"了/啦"——"两小时后回来"没有"了"，不许误判成出场。 */
    private val EXIT_PHRASES = listOf("回来了", "到家了", "到家啦", "回府了")

    /** 高置信进场（判据①）：用户明说出门。 */
    private val ENTRY_STRONG = listOf(
        "我出门了", "出门了", "出门啦", "我出门", "出门",
        "我走了", "我走啦", "我出去了", "出去一下", "出去一趟", "出去",
        "出门一趟", "出门去了", "下楼一趟", "出门办事",
    )

    /**
     * 犹豫词（判据⑦）：句里带一个就把整句降级成反问——"可能要出门""别出门了"
     * 这类不是"人明说走了"。极性写法：**默认紧词表命中即进场，犹豫词一票否决**；
     * 反过来"默认弱、命中才升"会让"出门两小时"这种明话掉进反问（441 首轮测试抓过）。
     */
    private val HEDGES: Regex = Regex("""可能|大概|也许|要不要|想不想|是不是|如果|要是|别|不要|不用|准备|刚""")

    /** "（时长）后回"形状：出现即算"人要离开一段再回"的进场话（判据②的形状格）。 */
    private val DURATION_RETURN: Regex =
        Regex("""[0-9一两二三四五六七八九十半]+个?小时[^，。！？]{0,6}后[^，。！？]{0,4}(回|到家)""")

    private val HOUR: Regex = Regex("""([0-9一两二三四五六七八九十半]+)\s*个?\s*小时""")
    private val MINUTE: Regex = Regex("""([0-9一二三四五六七八九十半]+)\s*分钟""")

    /**
     * 判定（纪律 2 的判序在这里，一处钉死）。
     *
     * 注意：本件**不管**当前开没开值守——"开着值守时用户说什么都算人回来了"
     * 是接线层的规矩（值守中任何回话 ⇒ 隐式出场），判据④由接线层实现；
     * 本件只回答"这句话本身像什么"。
     */
    fun classify(text: String): UnattendedPhraseVerdict {
        val t = text.trim()
        if (t.isEmpty()) return UnattendedPhraseVerdict.None
        // ① 显式出场先判（纪律 2："两小时后回来"不能被"回来"带偏）。
        if (EXIT_PHRASES.any { t.contains(it) }) return UnattendedPhraseVerdict.ExitExplicit
        // ② 进场带时长（"两小时后回来"）。
        val duration = parseDurationMs(t)
        if (duration != null && DURATION_RETURN.containsMatchIn(t)) {
            return UnattendedPhraseVerdict.Arm(duration)
        }
        // ③ 高置信进场（"出门两小时"的时长也顺手带上）；犹豫词一票否决 ⇒ 反问（判据⑦）。
        val hedged = HEDGES.containsMatchIn(t)
        if (!hedged && ENTRY_STRONG.any { t.contains(it) }) return UnattendedPhraseVerdict.Arm(duration)
        // ④ 有出门字样但带犹豫 ⇒ 反问（判据⑦）。
        if (ENTRY_STRONG.any { t.contains(it) }) return UnattendedPhraseVerdict.AskArm
        return UnattendedPhraseVerdict.None
    }

    // ── 时长解析（判据②："两小时" ⇒ now+2h） ────────────────────────────

    /**
     * 从原话里解析预计时长；没有 ⇒ null（"我出门了"不带时长也照样进场）。
     * 小时优先于分钟（"一小时二十分钟"只取第一把尺——精确到哪种粒度就报哪种，不装聪明）。
     */
    internal fun parseDurationMs(text: String): Long? {
        HOUR.find(text)?.let { return hourMs(it.groupValues[1]) }
        MINUTE.find(text)?.let { return minuteMs(it.groupValues[1]) }
        return null
    }

    private fun hourMs(raw: String): Long {
        val v = unitCount(raw)
        return if (v == null) HALF_HOUR_MS // "半"小时
        else v * 3_600_000L
    }

    private fun minuteMs(raw: String): Long {
        val v = unitCount(raw)
        return if (v == null) 30_000L else v * 60_000L
    }

    private const val HALF_HOUR_MS: Long = 1_800_000L

    /** 一到十的中文数（"十二小时"这类不猜——表外的落 null，不装聪明）。 */
    private val CN_NUMBERS: Map<String, Long> = mapOf(
        "一" to 1L, "二" to 2L, "三" to 3L, "四" to 4L, "五" to 5L,
        "六" to 6L, "七" to 7L, "八" to 8L, "九" to 9L, "十" to 10L,
    )

    /** 中文/阿拉伯数字 → 个数；"半" ⇒ null（由调用处按单位折半）。 */
    private fun unitCount(raw: String): Long? {
        val s = raw.replace(" ", "")
        if (s == "半") return null
        if (s == "两") return 2L
        s.toLongOrNull()?.let { return it.takeIf { v -> v > 0 } }
        return CN_NUMBERS[s]
    }

    // ── 人话（通知与账面用） ─────────────────────────────────────────────

    /** 预计时长的人话："预计 2 小时"／"预计 30 分钟"／没说 ⇒ "预计时长没说"。 */
    internal fun planCn(durationMs: Long?): String = when {
        durationMs == null -> "预计时长没说"
        durationMs >= 3_600_000L && durationMs % 3_600_000L == 0L -> "预计 ${durationMs / 3_600_000L} 小时"
        else -> "预计 ${durationMs / 60_000L} 分钟"
    }
}
