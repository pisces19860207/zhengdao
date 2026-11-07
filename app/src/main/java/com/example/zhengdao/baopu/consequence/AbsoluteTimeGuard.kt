// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十七批 · 时刻词禁令（U5）的**判据件**——账本/判据里的时间只许两种形状：
// 绝对时刻（ISO-8601 带时区偏移，或 epoch 毫秒），不许出现相对时刻词。
//
// 用户原话（派工单追加⑦）：**账本/判据禁「明天/刚刚/过会儿」，一律绝对时间（ISO/epoch）＋时区**。
// 为什么这句要用代码钉而不是用自觉守：账是给**事后回放**看的——「最近撤销：过会儿」这种行
// 过一天读就不知所云，相对词是回放的敌人；而"话是活的"这个理由在这里不成立，
// 因为落账的那一时刻，说话的机器**手里就有绝对时刻**（调用方传的 nowMs），没有借口。
//
// 三件东西（本文件零 IO、零时钟——时刻由调用方传，房型同 [IdentityRules]）：
//   ① [hits]：正则扫一段文本里的相对时刻词（判据⑩的门禁齿：命中即不许落账）；
//   ② [iso]：epoch 毫秒 ⇒ ISO-8601 带时区偏移的绝对时刻串（渲染「最近撤销」这类格子用）；
//   ③ [absoluteOrBlank]：渲染格子的统一口——0/负数＝没有这个时刻 ⇒ 空串（不渲染假时刻），
//      有值就必走 [iso]。体检单/回执上所有"最近撤销 <时间>"都从这一个口出去，
//      谁想手拼一个「明天 3 点」就得先绕过这个函数——形状上没有这条通道。
//
// 词表为什么是这几个：派工单点名的三个（明天/刚刚/过会儿）＋同一族的高频替身
// （昨天/后天/刚才/稍后/待会儿/等会儿/一会儿/马上/回头）。**收窄词表是安全方向**——
// 门禁漏一个词顶多是那条词继续被禁不住（还有人工抽检），禁错一个常用词会把
// 无辜的正文行也拦掉。所以词表只收"作为时刻使用的相对词"，不收"明后天"这种合成词。
package com.example.zhengdao.baopu.consequence

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object AbsoluteTimeGuard {

    /**
     * 相对时刻词表（正则逐词匹配；命中＝这段文本不许进账本/判据的时间格子）。
     *
     * 稳定键说明：词表只**增**不改字面——改字面＝老门禁用例读不出自己钉的那条词。
     */
    val RELATIVE_WORDS: List<String> = listOf(
        "明天",
        "昨天",
        "后天",
        "刚刚",
        "刚才",
        "过会儿",
        "稍后",
        "待会儿",
        "等会儿",
        "一会儿",
        "马上",
        "回头",
    )

    private val RELATIVE_REGEX: Regex = Regex(RELATIVE_WORDS.joinToString("|"))

    /** 这段文本里命中的相对时刻词（去重保序；空＝干净）。 */
    fun hits(text: String): List<String> =
        RELATIVE_REGEX.findAll(text).map { it.value }.distinct().toList()

    /** 有没有相对时刻词（写侧闸用的短答）。 */
    fun hasRelative(text: String): Boolean = RELATIVE_REGEX.containsMatchIn(text)

    /**
     * epoch 毫秒 ⇒ **ISO-8601 带时区偏移**的绝对时刻（U5 那句「ISO/epoch ＋时区」的渲染口）。
     *
     * 时区＝进程启动时的系统默认时区（同 `core/TimeFormats` 的口径：一台机器上的账
     * 半程不换时区）。输出形如 `2026-11-07T22:41:00+08:00`——有日期、有偏移，
     * 过一年读仍然知道是哪一刻。
     */
    fun iso(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /**
     * 渲染「<时刻>」格子的统一口：0/负数＝这一格没有时刻（没撤销过／没到期过）⇒ 空串。
     *
     * 为什么不渲染「无」或「从未」：调用方拿到空串自己决定那句怎么说；
     * 本函数只保证一件事——**非空的返回值一定是绝对时刻**，相对词没有入口。
     */
    fun absoluteOrBlank(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (epochMs <= 0L) "" else iso(epochMs, zone)
}
