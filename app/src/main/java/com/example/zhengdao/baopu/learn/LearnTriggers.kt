// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · E 提问的结构校验器（**不靠模型自觉**）。
//
// 输入＝一个观察会话的快照（结构化事件＋页面语义节点表），输出＝零到四条触发，
// 每条自带一张合法的 `AskUserCard`（203 房型：恰好一个推荐项排第一＋必留空白行，
// `AskUserCardRules.validate` 全过）。四类触发（派工单逐字）与阶段一的单次观察代理判据：
//   ① 定位不唯一＝点击步的身份面在该页节点表命中 >1 个节点（阶段二升级为两次归并判据）；
//   ② 该步缺证据＝点击之后收割过同一文档、URL 与节点数均无变化（「看过，没变化」才算，
//      没看过不算——不冤枉最后一次点击）；
//   ③ 参数不确定＝输入步的值形状 ∈ {digits, date}（形状出页、值不出页）；
//   ④ 意图不确定＝会话里 ≥2 个不同的搜索形输入框（placeholder/可及名/字段名可辨才计）。
// 每类每会话只出**一条**卡（不刷屏，231 口径）；文案全部由调用方注入（纯层不碰 R.string）。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserOption

/** 卡片文案模板（纯层不碰资源：UI 用 stringResource 填，测试用字面量填）。 */
internal data class LearnCardTexts(
    val qAmbiguous: String,
    val qNoEvidence: String,
    val qParam: String,
    val qIntent: String,
    val optParam: String,
    val optFixed: String,
    val optTeach: String,
    val optDrop: String,
    val freeText: String,
)

/** 一条触发（校验产物；转清单条目在 [LearnTriggers.toItem]）。 */
internal data class LearnTrigger(
    val id: String,
    val type: LearnTriggerType,
    val sessionId: String,
    val stepSeq: Int,
    val url: String,
    val pageName: String,
    val kind: String,
    val faces: ObserveFaces?,
    val candidates: List<String>,
)

internal object LearnTriggers {

    /** 四类各出一条（封顶不刷屏）；触发 id＝会话 id#步序，天然去重。 */
    fun validate(snapshot: ObserveSnapshot): List<LearnTrigger> {
        val triggers = ArrayList<LearnTrigger>(4)
        ambiguous(snapshot)?.let { triggers += it }
        noEvidence(snapshot)?.let { triggers += it }
        paramUncertain(snapshot)?.let { triggers += it }
        intentUncertain(snapshot)?.let { triggers += it }
        return triggers
    }

    /** 触发 → 合法问题卡（203 房型硬形状在这里钉：恰好一个推荐项排第一）。 */
    fun card(trigger: LearnTrigger, texts: LearnCardTexts): AskUserCard {
        val (question, options) = when (trigger.type) {
            LearnTriggerType.AMBIGUOUS_LOCATOR -> format(texts.qAmbiguous, trigger) to
                trigger.candidates.take(4).mapIndexed { i, label -> option("c$i", label, i == 0) }
            LearnTriggerType.NO_EVIDENCE -> format(texts.qNoEvidence, trigger) to listOf(
                option("t1", texts.optTeach, true),
                option("t2", texts.optDrop, false),
            )
            LearnTriggerType.PARAM_UNCERTAIN -> format(texts.qParam, trigger) to listOf(
                option("p1", texts.optParam, true),
                option("p2", texts.optFixed, false),
            )
            LearnTriggerType.INTENT_UNCERTAIN -> format(texts.qIntent, trigger) to
                trigger.candidates.take(4).mapIndexed { i, label -> option("i$i", label, i == 0) }
        }
        return AskUserCard(question, options, texts.freeText)
    }

    /** 触发 → 清单条目（id＝会话#步序；卡片的题面与选项随条目落盘，重启后照读）。 */
    fun toItem(trigger: LearnTrigger, card: AskUserCard, createdAt: Long): NotLearnedItem = NotLearnedItem(
        id = trigger.id,
        type = trigger.type,
        createdAt = createdAt,
        sessionId = trigger.sessionId,
        stepSeq = trigger.stepSeq,
        url = trigger.url,
        pageName = trigger.pageName,
        kind = trigger.kind,
        faces = trigger.faces,
        question = card.question,
        options = card.options.map { it.label },
        freeTextPrompt = card.freeTextPrompt,
    )

    // ── 四类校验器（纯函数；每类返回第一条命中）──────────────────────────────

    private fun ambiguous(snapshot: ObserveSnapshot): LearnTrigger? = snapshot.events
        .firstOrNull { ev -> ev.kind == ObserveKind.CLICK && matches(snapshot, ev) > 1 }
        ?.let { ev ->
            trigger(snapshot, ev, LearnTriggerType.AMBIGUOUS_LOCATOR, distinctLabels(snapshot, ev))
        }

    private fun noEvidence(snapshot: ObserveSnapshot): LearnTrigger? {
        snapshot.events.forEachIndexed { index, ev ->
            if (ev.kind != ObserveKind.CLICK) return@forEachIndexed
            val later = snapshot.pages.filter { it.docId == ev.docId && it.ts > ev.ts }
            // 「看过，没变化」：之后收割过同一文档，且 URL 与节点数都和点击那一刻相同
            val looked = later.isNotEmpty() && later.all { it.url == ev.url && it.nodeCount == ev.nodeCount }
            val navigated = snapshot.events.drop(index + 1).any { it.url != ev.url }
            if (looked && !navigated) return trigger(snapshot, ev, LearnTriggerType.NO_EVIDENCE, emptyList())
        }
        return null
    }

    private fun paramUncertain(snapshot: ObserveSnapshot): LearnTrigger? = snapshot.events
        .firstOrNull { ev ->
            (ev.kind == ObserveKind.TYPE || ev.kind == ObserveKind.SELECT) &&
                (ev.shape == ValueShape.DIGITS || ev.shape == ValueShape.DATE)
        }
        ?.let { ev -> trigger(snapshot, ev, LearnTriggerType.PARAM_UNCERTAIN, emptyList()) }

    private fun intentUncertain(snapshot: ObserveSnapshot): LearnTrigger? {
        val searchLike = snapshot.events
            .filter { ev -> ev.kind == ObserveKind.TYPE || ev.kind == ObserveKind.SELECT }
            .filter { ev -> isSearchLike(ev.faces) && labelOf(ev.faces).isNotBlank() }
            .distinctBy { ev -> ev.url + "|" + labelOf(ev.faces) }
        if (searchLike.size < 2) return null
        return trigger(
            snapshot,
            searchLike.first(),
            LearnTriggerType.INTENT_UNCERTAIN,
            // 选项必须两两不同（AskUserCard 只查 label 非空不查重，id 我们按序编保证唯一）
            searchLike.mapIndexed { i, ev -> labelOf(ev.faces) + "（第 " + (i + 1) + " 个）" },
        )
    }

    // ── 判据小件 ─────────────────────────────────────────────────────────────

    /** 「就教这一步」的复验口径：补录事件在该页节点表的命中数（1＝唯一定位）。 */
    fun locatorMatchCount(page: ObservePage?, faces: ObserveFaces): Int = page?.nodes
        ?.filter { n -> !n.sensitive && sameIdentity(n.faces, faces) }
        ?.distinctBy { it.faces.xp }
        ?.size
        ?: 0

    /** 身份面在节点表的命中数（同 tag 且 name/id/testid/xpath 任一相等 ⇒ 同一身份）。 */
    private fun matches(snapshot: ObserveSnapshot, ev: ObserveEvent): Int =
        locatorMatchCount(latestPage(snapshot, ev.docId), ev.faces)

    private fun sameIdentity(n: ObserveFaces, e: ObserveFaces): Boolean = n.tag == e.tag && (
        (n.name.isNotBlank() && n.name == e.name) ||
            (n.id.isNotBlank() && n.id == e.id) ||
            (n.testid.isNotBlank() && n.testid == e.testid) ||
            (n.xp.isNotBlank() && n.xp == e.xp)
        )

    private fun latestPage(snapshot: ObserveSnapshot, docId: String): ObservePage? =
        snapshot.pages.lastOrNull { it.docId == docId && it.nodes.isNotEmpty() }

    private fun isSearchLike(f: ObserveFaces): Boolean = f.inputType.equals("search", true) ||
        SEARCHISH.containsMatchIn("${f.placeholder} ${f.name} ${f.inputName}")

    private fun labelOf(f: ObserveFaces): String = f.placeholder.ifBlank { f.name.ifBlank { f.inputName } }

    private fun distinctLabels(snapshot: ObserveSnapshot, ev: ObserveEvent): List<String> {
        val page = latestPage(snapshot, ev.docId) ?: return emptyList()
        val labels = page.nodes
            .filter { n -> !n.sensitive && sameIdentity(n.faces, ev.faces) }
            .map { n -> n.faces.name.ifBlank { n.faces.placeholder.ifBlank { n.faces.id } } }
            .filter { it.isNotBlank() }
        val distinct = labels.distinct()
        if (distinct.size >= 2) return distinct
        // 同名节点（例如两个都叫「查询」的按钮）：走法标签带序号——卡面选项必须两两可辨，
        // 否则 203 房型（选项 2–4、文案非空）拼不出合法卡
        return labels.mapIndexed { i, l -> "$l（第 ${i + 1} 处）" }
    }

    private fun trigger(
        snapshot: ObserveSnapshot,
        ev: ObserveEvent,
        type: LearnTriggerType,
        candidates: List<String>,
    ): LearnTrigger = LearnTrigger(
        id = snapshot.openedAtMs.toString(36) + "#" + ev.seq,
        type = type,
        sessionId = snapshot.openedAtMs.toString(36),
        stepSeq = snapshot.events.indexOf(ev) + 1,
        url = ev.url,
        pageName = latestPage(snapshot, ev.docId)?.title.orEmpty(),
        kind = ev.kind.wire,
        faces = ev.faces,
        candidates = candidates,
    )

    private fun format(template: String, trigger: LearnTrigger): String = template
        .replace("{步}", trigger.stepSeq.toString())
        .replace("{目标}", labelOf(trigger.faces ?: BLANK_FACES).ifBlank { trigger.kind })
        .replace("{页}", trigger.pageName.ifBlank { trigger.url })
        .replace("{条数}", trigger.candidates.size.coerceAtLeast(2).toString())

    /** 意图/参数卡没有「目标」可填时的占位（不显示，只为模板替换不留 {目标} 字样）。 */
    private val BLANK_FACES = ObserveFaces(
        tag = "", role = "", name = "", aria = "", testid = "", id = "", inputName = "",
        placeholder = "", inputType = "", auto = "", formLabel = "", xp = "", cls = emptyList(),
    )

    private fun option(id: String, label: String, recommended: Boolean) =
        AskUserOption(id = id, label = label.ifBlank { "-" }, recommended = recommended)

    private val SEARCHISH: Regex = Regex("(?i)搜索|search|查询|find")
}
