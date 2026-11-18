// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 杏仁核·突显（六体第①件，登记表第一行）。
//
// 机理：威胁/突显快速通路——先于慢思考的**粗标**。只做：给事件打威胁强度（0–4）
// 与新异性（0–2）读数，即发即弃、不落权威账。不做：不拦截、不判定、不写库——
// 判定权在御案/调解器/既有门禁，库是 BaopuDb 的地盘（这两句是登记表"不做"列的原文口径）。
//
// 四条硬规矩（派工单①逐字落码）：
//   ① 输入只有四个信号：文本长度/否定词/感叹/危险词命中（词表可配，见 [DangerWords]）；
//   ② 输出只有 {threat: 0..4, novelty: 0..2, note}——档位封顶封底，越界不可能；
//   ③ 即发即弃：本 object 无状态、无落盘——同输入两次读数逐字一致（判据④）；
//   ④ 只 log：每次打分走 [BioLog] 一行 stdout，不落权威账（判据③，BioSourceAssertTest 钉）。
//
// 本件无判定权（523 三件套②）：不产生 RiskDecision/PROCEED/REJECT，不改档，不写权威账。
package com.example.zhengdao.baopu.bio

/** 杏仁核·突显读数（纯函数器官，零状态零落盘）。 */
@BioAnalog("杏仁核")
internal object Amygdala {

    /** 读数形状。threat 0–4 档、novelty 0–2 档；note 是给人看的一句定性话（无时间戳）。 */
    data class Reading(val threat: Int, val novelty: Int, val note: String)

    /** 打分主入口。同一 event＋words 调两次，[Reading] 逐字相等（判据④）。 */
    fun salience(event: BioEvent, words: DangerWords = DangerWords.DEFAULT): Reading {
        val text = event.text
        val hits = words.dangerHits(text)
        val threat = threatOf(
            hits = hits,
            exclaims = text.count { it == '！' || it == '!' },
            long = text.length > LONG_TEXT,
            negated = words.hasNegation(text),
        )
        val novelty = noveltyOf(text, words)
        val reading = Reading(threat = threat, novelty = novelty, note = noteOf(threat, novelty))
        BioLog.line(TAG, "threat=$threat novelty=$novelty hits=$hits source=${event.source}")
        return reading
    }

    /**
     * 威胁分档（0–4）。底档由危险词命中数（去重）定：0/1/2/≥3 → 0/1/2/3；
     * 感叹（≥2 个）、长文（>[LONG_TEXT] 字）、否定词在场各 +1，封顶 4。
     * 全是机械计数，没有一处"我觉得"。
     */
    private fun threatOf(hits: Int, exclaims: Int, long: Boolean, negated: Boolean): Int {
        var t = when {
            hits >= 3 -> 3
            hits == 2 -> 2
            hits == 1 -> 1
            else -> 0
        }
        if (exclaims >= EXCLAIM_ESCALATE) t += 1
        if (long) t += 1
        if (negated) t += 1
        return t.coerceAtMost(MAX_THREAT)
    }

    /**
     * 新异分档（0–2，丘脑同款共用）：样式新异 > 疑问/请求 > 常规。
     * 样式新异＝链接（"://"）或路径（以"/"开头）或引号专名——粗标眼里的"没见过的东西"。
     */
    internal fun noveltyOf(text: String, words: DangerWords): Int = when {
        hasNovelShape(text) -> 2
        words.noveltyHits(text) > 0 -> 1
        else -> 0
    }

    /** 机械形状判别：三选一命中即算样式新异。无正则、无联网、无随机。 */
    private fun hasNovelShape(text: String): Boolean =
        text.contains("://") || text.startsWith("/") || text.contains('"')

    /** note 只有一句定性话＋档位词——不含数值（数值在字段里，别说两遍）。 */
    private fun noteOf(threat: Int, novelty: Int): String = when (threat) {
        in 3..MAX_THREAT -> "威胁高档（读数仅供参考，判定权在既有门禁）"
        in 1..2 -> "威胁低档（读数仅供参考）"
        else -> "无威胁信号"
    } + "；" + when (novelty) {
        2 -> "样式新异"
        1 -> "新请求"
        else -> "常规"
    }

    private const val TAG = "杏仁核"
    private const val MAX_THREAT = 4

    /** 长文门槛（字符数）：超过算"长文铺陈"加一档。 */
    private const val LONG_TEXT = 400

    /** 感叹升级门槛：感叹号 ≥2 个算一次升级（一个不算，别把普通语气当突显）。 */
    private const val EXCLAIM_ESCALATE = 2
}
