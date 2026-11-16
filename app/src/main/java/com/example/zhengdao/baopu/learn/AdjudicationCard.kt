// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十二批 · 冲突裁定卡的**纯层**（只算不画：卡面在 `ui/baopu/LearnBoardSection`）。
//
// 一张卡＝一个冲突步，并排两套定位（判据③）：
//   · 定位描述 —— 人读的「什么元素、按哪几档找得到」（不出现 wire 名，路径殿后以免两套同字）；
//   · 命中节点数 —— 该身份面在**当时那份页面节点表**里命中几处（走 [LearnTriggers.locatorMatchCount]，
//     与「就教这一步」的复验同一条判据，不另立一份）；取不到页面结构 ⇒ null（卡面如实说
//     「命中数未知」，**不编 0**）；
//   · 一帧证据 —— 步上的 `evidence` 首条（`观察/会话-<id>.jsonl#seq<n>`，622 快照引用的同款
//     形状：文件＋序号，可点开回到对应行）；步上没有证据 ⇒ 「无证据」，不崩（判据③）。
//
// 为什么不画真截图：观察账按阶段一红线**不记正文、不记整页**，账里没有图可缩；
// 能给的「一帧」就是这条可回溯的引用。要真图得等回放侧取证（622 的 `ShotRef`）接进来，
// 本批不造假的缩略图。
package com.example.zhengdao.baopu.learn

/** 并排里的一侧（A 或 B）。 */
internal data class LocatorCandidate(
    val slot: DraftChoice,
    /** 定位描述（人读；两套之间必须可辨——路径殿后即为此）。 */
    val desc: String,
    /** 命中节点数；null＝当时没有这份页面结构（不猜 0）。 */
    val hits: Int?,
)

/** 一个冲突步的卡面读数。 */
internal data class ConflictStepCard(
    val stepIndex: Int,
    /** 这一步想做什么（意图名，草案里已有）。 */
    val intent: String,
    /** 动作（click／type／select）。 */
    val kind: String,
    val url: String,
    val a: LocatorCandidate,
    val b: LocatorCandidate,
    /** 一帧证据的引用（null＝无证据）。 */
    val evidenceRef: String?,
    /** 已裁结果（null＝未裁）。 */
    val resolved: DraftChoice?,
    /** 谁裁的（null＝未裁；判据②的第二个字段）。 */
    val by: String?,
    /** 裁定时刻（0＝未裁；判据②的第三个字段）。 */
    val atMs: Long,
) {
    /** 两侧命中数文案（缺页面结构时如实说「未知」，不编数字）。 */
    fun hitsText(side: LocatorCandidate): String =
        side.hits?.let { "命中 $it 处" } ?: AdjudicationCard.HITS_UNKNOWN

    /** 证据一行（判据③：缺证据只显示「无证据」）。 */
    fun evidenceText(): String = evidenceRef ?: AdjudicationCard.NO_EVIDENCE
}

internal object AdjudicationCard {

    /** 缺证据的定稿说法（判据③断言到这四个字上）。 */
    const val NO_EVIDENCE = "无证据"

    /** 命中数算不出来时的说法（不编 0：0 处是「确实一处都没有」，与「没看」是两件事）。 */
    const val HITS_UNKNOWN = "命中数未知（当时没取到页面结构）"

    /** 元素类别的人话（认不出就用原始标签，不硬编中文）。 */
    private val TAGS = mapOf(
        "BUTTON" to "按钮", "A" to "链接", "INPUT" to "输入框",
        "SELECT" to "下拉框", "TEXTAREA" to "文本域", "LABEL" to "标签",
    )

    /**
     * 草案 → 每个冲突步一张卡（按步号升序）。[snapshot] 是最近收口的观察会话
     * （没有 ⇒ 命中数一律 null，卡面照出）。
     */
    fun steps(
        draft: SkillDraft,
        snapshot: ObserveSnapshot?,
        adjudications: List<DraftAdjudication>,
    ): List<ConflictStepCard> {
        return draft.skeleton
            .filter { it.candidates.size >= 2 }
            .map { step ->
                val a = candidate(step, DraftChoice.A, step.candidates[0], snapshot)
                val b = candidate(step, DraftChoice.B, step.candidates[1], snapshot)
                val rec = adjudications.lastOrNull { it.stepIndex == step.index }
                ConflictStepCard(
                    stepIndex = step.index,
                    intent = step.intent,
                    kind = step.kind,
                    url = step.url,
                    a = a,
                    b = b,
                    evidenceRef = step.evidence.firstOrNull(),
                    resolved = rec?.resolved,
                    by = rec?.by,
                    atMs = rec?.atMs ?: 0L,
                )
            }
    }

    /** 定位描述：类别＋可读名＋「按哪几档找得到」＋路径（路径殿后：两套同名元素也能分开）。 */
    fun describe(f: DraftLocator): String {
        val tiers = buildList {
            if (f.role.isNotBlank() && f.name.isNotBlank()) add("角色+名称")
            if (f.testid.isNotBlank()) add("testid")
            if (f.aria.isNotBlank()) add("可及名")
            if (f.id.isNotBlank()) add("元素 id")
            if (f.inputName.isNotBlank()) add("字段名")
            if (f.placeholder.isNotBlank()) add("占位提示")
        }
        val head = (TAGS[f.tag.uppercase()] ?: f.tag.ifBlank { "元素" }) + "「" + labelOf(f) + "」"
        val how = if (tiers.isEmpty()) "没有可用定位" else "按 " + tiers.joinToString("／") + " 定位"
        return if (f.xp.isBlank()) "$head · $how" else "$head · $how · ${f.xp}"
    }

    /** 身份面在页面节点表里的命中数（与「就教这一步」的复验同一条判据）。 */
    fun hits(snapshot: ObserveSnapshot?, url: String, faces: DraftLocator): Int? {
        val page = pageOf(snapshot, url) ?: return null
        return LearnTriggers.locatorMatchCount(page, faces)
    }

    private fun candidate(
        step: DraftStep,
        slot: DraftChoice,
        faces: DraftLocator,
        snapshot: ObserveSnapshot?,
    ): LocatorCandidate = LocatorCandidate(
        slot = slot,
        desc = describe(faces),
        hits = hits(snapshot, step.url, faces),
    )

    private fun labelOf(f: DraftLocator): String = f.label()
        .ifBlank { f.name }
        .ifBlank { f.id }
        .ifBlank { f.inputName }
        .ifBlank { f.xp }
        .ifBlank { "（没有可读名字）" }

    /** 按 URL 找当时那份节点表（OBSERVE 现取的最新一份；夹具直塞带节点的页时走回退）。 */
    private fun pageOf(snapshot: ObserveSnapshot?, url: String): ObservePage? {
        if (snapshot == null) return null
        return snapshot.nodeTables.values.firstOrNull { it.url == url }
            ?: snapshot.pages.lastOrNull { it.url == url && it.nodes.isNotEmpty() }
    }
}
