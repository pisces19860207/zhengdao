// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 B（阶段二）· 意图归纳的**程序侧**：一个观察会话 → 一条技能草案。
//
// 分工（派工单口径）：归纳**可以**由模型做（「模型可归纳」），也可以由本类做——
// 两条路都必须过 [SkillDraftRules.validate] 这同一道结构门（定位全空／无证据 ⇒ 拒收）；
// 产物一律进草稿区（[SkillDraftStore]），不转正。模型提交口留待后续单（见交付报告挂账）。
//
// 本类只做**结构**归纳：轨迹里的 click/type/select 行按序成步（scroll 不成步——不是
// 可回放动作），意图名取身份面 label，输入步自动成参数（231 原例：输入单号 ⇒ param），
// 校验点只出观察证据能推的两种（urlChanged／rowCountChange），每步 evidence 指回
// `观察/会话-<id>.jsonl#seq<n>`。goal 由人给（观察本身没有意图，不猜）。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.bridge.BridgePolicy

/** 会话快照 → 技能草案（纯函数；goal 必须由调用方给——观察数据里没有意图）。 */
internal object DraftInducer {

    /** 一次归纳的产物（或拒收原因）。 */
    sealed interface Outcome {
        data class Drafted(val draft: SkillDraft) : Outcome
        data class Rejected(val problems: List<String>) : Outcome
        /** 轨迹里没有可成步的操作（只有滚动/空会话）——如实说，不出空草案。 */
        object NoSteps : Outcome
    }

    fun induce(snapshot: ObserveSnapshot, sessionId: String, goal: String, draftId: String): Outcome {
        if (goal.isBlank()) return Outcome.Rejected(listOf("goal 不能为空（观察数据里没有意图，意图由人给）"))
        val actionable = snapshot.events.filter { it.kind != ObserveKind.SCROLL }
        if (actionable.isEmpty()) return Outcome.NoSteps
        val steps = ArrayList<DraftStep>(actionable.size)
        val params = ArrayList<DraftParam>()
        val variableSteps = ArrayList<Int>()
        actionable.forEachIndexed { i, ev ->
            val isInput = ev.kind == ObserveKind.TYPE || ev.kind == ObserveKind.SELECT
            val paramName = if (isInput) paramNameOf(ev) else null
            if (paramName != null && params.none { it.name == paramName }) {
                params += DraftParam(paramName, "step${i + 1}.input")
                variableSteps += i + 1
            }
            steps += DraftStep(
                index = i + 1,
                intent = ev.faces.label().ifBlank { ev.kind.wire },
                kind = ev.kind.wire,
                url = ev.url,
                locator = ev.faces,
                param = paramName,
                optional = false,
                candidates = emptyList(),
                expect = expectsOf(snapshot, ev),
                evidence = listOf("观察/" + ObserveSession.fileNameFor(sessionId) + "#seq" + ev.seq),
            )
        }
        val draft = SkillDraft(
            id = draftId,
            goal = goal.trim(),
            targetSite = BridgePolicy.hostOf(actionable.first().url).orEmpty(),
            params = params,
            skeleton = steps,
            variableSteps = variableSteps,
            mergedFrom = 1,
            sourceSessions = listOf(sessionId),
        )
        return when (val check = SkillDraftRules.validate(draft)) {
            is DraftCheck.Ok -> Outcome.Drafted(draft)
            is DraftCheck.Invalid -> Outcome.Rejected(check.problems) // 理论上到不了：源头闸已滤
        }
    }

    /** 输入步的参数名：取身份面 label（值不出页——名字是身份，不是内容）。 */
    private fun paramNameOf(ev: ObserveEvent): String? =
        ev.faces.label().takeIf { it.isNotBlank() && it.length <= 20 }?.replace(Regex("\\s+"), "")

    /** 校验点只出证据能推的两种：点击后 URL 变 ⇒ urlChanged；节点数变 ⇒ rowCountChange。 */
    private fun expectsOf(snapshot: ObserveSnapshot, ev: ObserveEvent): List<String> {
        val later = snapshot.pages.filter { it.docId == ev.docId && it.ts > ev.ts }
        val urlChanged = later.any { it.url != ev.url }
        val rowsChanged = later.any { it.nodeCount != ev.nodeCount }
        return buildList {
            if (urlChanged) add("urlChanged")
            if (rowsChanged) add("rowCountChange")
        }
    }
}
