// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段二）· 多次归并（231 切片 C）：
//
//   同意图两次观察 ⇒ **固定步合并**、**偶发步降级为可选**、**每次都变的字段自动参数化**；
//   **定位冲突 ⇒ 两套候选并存，不擅自选**（判据⑤：自动选 ⇒ 红）；
//   产物标「N 次观察归并」（判据③），仍进草稿区，**不自动转正**。
//
// 同意图判定（231 给的两条路）：goal 归一化后相等＝本类认的「同一意图」（相似度阈值的
// 确定性代理；「她手动说这两次是同一件事」＝调用方把两条草案的 goal 对齐后传入）。
// 步配对三层（按序）：①同 kind＋身份同判 ⇒ 固定步合并（输入步自动参数化）；
// ②同 kind 不同身份 ⇒ **定位冲突**——两套候选并存（主位留空＝「没选定」），由人裁；
// ③只在一侧出现 ⇒ 偶发步降级为可选。
package com.example.zhengdao.baopu.learn

/** 两次归并的产物（或拒绝原因——goal 不同意图不给合）。 */
internal sealed interface MergeOutcome {
    /** 合成一条（标 N 次观察归并）。 */
    data class Merged(val draft: SkillDraft) : MergeOutcome

    /** goal 不同 ⇒ 不合（把两次当两条技能是负例；硬合也是错）。 */
    data class NotSameIntent(val reason: String) : MergeOutcome
}

internal object SessionMerger {

    /** 归并两条草案（调用方保证都过过 [SkillDraftRules.validate]——本类不再校验来源）。 */
    fun merge(a: SkillDraft, b: SkillDraft, newId: String): MergeOutcome {
        if (normalize(a.goal) != normalize(normalize(b.goal))) {
            return MergeOutcome.NotSameIntent("goal 不同（「${a.goal}」≠「${b.goal}」）——不同意图不合")
        }
        val params = ArrayList<DraftParam>(a.params)
        val steps = ArrayList<DraftStep>(a.skeleton.size + b.skeleton.size)
        val aLeft = a.skeleton.toMutableList()
        val bLeft = b.skeleton.toMutableList()
        while (aLeft.isNotEmpty()) {
            val x = aLeft.removeFirst()
            when (val y = pairFor(x, bLeft)) {
                is PairKind.Fix -> {
                    bLeft.remove(y.step)
                    steps += mergedStep(x, y.step, params)
                }
                is PairKind.Conflict -> {
                    bLeft.remove(y.step)
                    steps += conflictStep(x, y.step)
                }
                PairKind.None -> steps += x.asOccasional()
            }
        }
        bLeft.forEach { steps += it.asOccasional() }
        val merged = SkillDraft(
            id = newId,
            goal = a.goal,
            targetSite = a.targetSite.ifBlank { b.targetSite },
            params = params,
            skeleton = steps,
            variableSteps = steps.filter { it.param != null }.map { it.index },
            mergedFrom = a.sourceSessions.size + b.sourceSessions.size,
            sourceSessions = a.sourceSessions + b.sourceSessions,
        )
        return MergeOutcome.Merged(renumber(merged))
    }

    /** 配对三层（按序）：同身份 ⇒ 合并；同 kind 不同身份 ⇒ 冲突；都找不到 ⇒ 无配。 */
    private fun pairFor(x: DraftStep, pool: List<DraftStep>): PairKind {
        pool.firstOrNull { it.kind == x.kind && it.locator.sameIdentityAs(x.locator) }
            ?.let { return PairKind.Fix(it) }
        pool.firstOrNull { it.kind == x.kind && it.locatorBlank().not() }
            ?.let { return PairKind.Conflict(it) }
        return PairKind.None
    }

    private sealed interface PairKind {
        data class Fix(val step: DraftStep) : PairKind
        data class Conflict(val step: DraftStep) : PairKind
        object None : PairKind
    }

    /** 固定步合并：输入步自动参数化（判据④；字面量本来就拔掉了），证据两边并集。 */
    private fun mergedStep(x: DraftStep, y: DraftStep, params: MutableList<DraftParam>): DraftStep {
        val isInput = x.param != null || y.param != null ||
            x.kind == ObserveKind.TYPE.wire || x.kind == ObserveKind.SELECT.wire ||
            y.kind == ObserveKind.TYPE.wire || y.kind == ObserveKind.SELECT.wire
        val paramName = if (isInput) {
            val name = x.param ?: y.param ?: x.locator.label().takeIf { it.isNotBlank() }?.replace(Regex("\\s+"), "")
            if (name != null && params.none { it.name == name }) params += DraftParam(name, "step${x.index}.input")
            name
        } else x.param
        return DraftStep(
            index = x.index,
            intent = x.intent.ifBlank { y.intent },
            kind = x.kind,
            url = x.url,
            locator = x.locator,
            param = paramName,
            optional = x.optional && y.optional,
            candidates = emptyList(),
            expect = (x.expect + y.expect).distinct(),
            evidence = x.evidence + y.evidence,
        )
    }

    /** 定位冲突（判据⑤的落点）：主位留空＝「没选定」；两套候选并存，由人裁。
     *  链式归并（冲突草案再合）时 x 的主位本来就是空的 ⇒ 候选是「x 的候选＋y 的定位」。 */
    private fun conflictStep(x: DraftStep, y: DraftStep): DraftStep = DraftStep(
        index = x.index,
        intent = x.intent.ifBlank { y.intent },
        kind = x.kind,
        url = x.url,
        locator = blankLocator(),
        param = null,
        optional = false,
        candidates = if (x.candidates.isNotEmpty()) x.candidates + listOf(y.locator) else listOf(x.locator, y.locator),
        expect = (x.expect + y.expect).distinct(),
        evidence = x.evidence + y.evidence,
    )

    /** 偶发步：只在一侧出现 ⇒ 降级为可选（231 切片 C）。 */
    private fun DraftStep.asOccasional(): DraftStep = copy(optional = true)

    private fun blankLocator(): DraftLocator = DraftLocator(
        tag = "", role = "", name = "", aria = "", testid = "", id = "", inputName = "",
        placeholder = "", inputType = "", auto = "", formLabel = "", xp = "", cls = emptyList(),
    )

    /** 合并后重排步号（1..n 连续；variableSteps 跟着新号走）。 */
    private fun renumber(draft: SkillDraft): SkillDraft = draft.copy(
        skeleton = draft.skeleton.mapIndexed { i, s -> s.copy(index = i + 1) },
        variableSteps = draft.skeleton.mapIndexed { i, s -> if (s.param != null) i + 1 else null }
            .filterNotNull().distinct(),
    )

    private fun normalize(goal: String): String = goal.trim().replace(Regex("\\s+"), "")
}
