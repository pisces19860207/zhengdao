// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 丘脑·中继（六体第③件，登记表第六行）。
//
// 机理：感觉中继与门控——信号先过中继再达皮层。只做：把输入事件路由到对应下游，
// 并打"新异性"读数（分档表读杏仁核的只读函数，一套口径两处用）。只中继不解读。
// 不做：不做内容过滤（过滤是既有门禁的事）、不决定是否处理、**不丢弃事件**——
// routeOf 的 when 穷尽四路，任何输入都有出路，没有"扔了"这个分支。
//
// 三条硬规矩（派工单③逐字落码）：
//   ① 输出只有 {route, novelty, note}——route 是机械路由标签，不是裁量；
//   ② 危险词在场 ⇒ 中继到门禁**门口**：送，不拦（拦不拦是门禁的权，本件无从触碰）；
//   ③ 只 log：每次中继走 [BioLog] 一行 stdout，不落权威账（判据③，BioSourceAssertTest 钉）。
package com.example.zhengdao.baopu.bio

/** 丘脑·中继（纯函数器官，零状态零落盘）。 */
@BioAnalog("丘脑")
internal object Thalamus {

    /**
     * 下游名册（登记表口径里的"对应下游"）。这是**机械路由标签**不是裁量结论：
     * GUARD=送到门禁门口、TOOLS=送去工具面、MEMORY=送去记忆面、DIRECT=送去直答。
     */
    enum class Route(val label: String) {
        GUARD("门禁"),
        TOOLS("工具"),
        MEMORY("记忆"),
        DIRECT("直答"),
    }

    /** 中继读数：route ＋ novelty（0–2，与杏仁核同表）＋一句 note。 */
    data class Relay(val route: Route, val novelty: Int, val note: String)

    /** 中继主入口。同一 event＋words 调两次，[Relay] 逐字相等（判据④）。 */
    fun relay(event: BioEvent, words: DangerWords = DangerWords.DEFAULT): Relay {
        val text = event.text
        val novelty = Amygdala.noveltyOf(text, words)
        val route = routeOf(text, words)
        val relay = Relay(route = route, novelty = novelty, note = noteOf(route, novelty))
        BioLog.line(TAG, "route=${route.name} novelty=$novelty source=${event.source}")
        return relay
    }

    /**
     * 路由表：纯文本形状的机械映射，优先级 危险词 > 工具样式 > 记忆标记 > 直答。
     * 无网络、无模型、无副作用；判不了的一律落直答——事件不会被丢（登记表"不做"列）。
     */
    internal fun routeOf(text: String, words: DangerWords): Route = when {
        words.dangerHits(text) > 0 -> Route.GUARD
        text.contains('/') -> Route.TOOLS
        MEMORY_MARKS.any { text.contains(it) } -> Route.MEMORY
        else -> Route.DIRECT
    }

    /** note 只说"送到哪"，不说"该怎么处理"——解读不是本件的活。 */
    private fun noteOf(route: Route, novelty: Int): String =
        "已中继至${route.label}（如何处置由下游定）；${noveltyWord(novelty)}"

    private fun noveltyWord(novelty: Int): String = when (novelty) {
        2 -> "样式新异"
        1 -> "新请求"
        else -> "常规"
    }

    /** 记忆标记：提到"之前的事"就送去记忆面。机械词表，不猜语义。 */
    private val MEMORY_MARKS = setOf("上次", "之前", "记得", "昨天", "历史", "回顾")

    private const val TAG = "丘脑"
}
