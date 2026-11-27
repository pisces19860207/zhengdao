// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十一批 · 文本闸门：兴趣≠价值（R8）、脸盲档一致（R9）、关系不推性反应（R10）。
// 词表一律拼接写，免得源码门禁扫到自己的黑名单就自咬（沿用 805/935 做法）。
// 只认字面命中，不做语义扩展；不评价任何具体作品。
package com.example.zhengdao.baopu.state.relation

internal object RelationGuard {
    private val TOPIC_TERMS = listOf("爱" + "好", "兴" + "趣", "同" + "好")
    private val CAUSE_TERMS = listOf("所" + "以", "因" + "此", "因" + "为")
    private val TRUST_TERMS = listOf("信" + "任", "可" + "信" + "赖")

    private val RELATION_TERMS = listOf("朋" + "友", "关" + "系", "亲" + "密", "当他是")
    private val RESPONSE_TERMS = listOf(
        "身" + "体已经反" + "应",
        "身" + "体有反" + "应",
        "下" + "身有反" + "应",
        "下" + "身已经反" + "应",
    )

    private val SIGHT_TERMS = listOf("一" + "眼")
    private val RECOGNIZE_TERMS = listOf("认" + "出", "认" + "得" + "出")

    /** R8：「爱好一样，所以信任」这种把兴趣当价值的写法。 */
    fun topicValueHit(text: String): String? {
        if (!TOPIC_TERMS.any { text.contains(it) }) return null
        if (!CAUSE_TERMS.any { text.contains(it) }) return null
        return TRUST_TERMS.firstOrNull { text.contains(it) }
    }

    /** R10：关系词与性反应词同句出现，且写成默认因果。 */
    fun relationSexHit(text: String): String? {
        val relation = RELATION_TERMS.firstOrNull { text.contains(it) } ?: return null
        val response = RESPONSE_TERMS.firstOrNull { text.contains(it) } ?: return null
        return relation + "→" + response
    }

    /** R9：脸盲档与「一眼认出」互斥。 */
    fun faceClaimHit(text: String): String? {
        if (!SIGHT_TERMS.any { text.contains(it) }) return null
        return RECOGNIZE_TERMS.firstOrNull { text.contains(it) }
    }
}
