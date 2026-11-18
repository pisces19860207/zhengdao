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
// 校验点只出观察证据能推的东西，每步 evidence 指回 `观察/会话-<id>.jsonl#seq<n>`。
// goal 由人给（观察本身没有意图，不猜）。
//
// 第二百九十七批（697，裁 G-690-5）：**导航步的校验点**。以前点一条会跳转的链接，
// 录出来的步只有 `nc`（节点数）、没有 expect ⇒ 转正 G2 恒不过 ⇒ 「点开某条链接」这类
// 技能永远入不了库。现在的规矩（蓝总管裁定，主路 B ＋ 窄口 A）：
//   ① 观察里看得到「跳转前后 url 不同」⇒ 该步 expect 填 `urlChanged`（真证据，不是猜）；
//   ② 看得到页面变了、**但 url 没变**（同页锚点／SPA 没改地址）⇒ 该步标
//      `expectlessNavigate`＋节点数前后值（窄口），转正门按「带证据的导航步」放行，
//      技能正文标「未校验跳转」；
//   ③ **不许凭空造 expect**：观察没有 url 证据就一个断言都不写。节点数变化也**不**翻译成
//      `rowCountChange`——观察面的 nc 是「整页节点个数」，回放端的 rowCount 是「表格行数」，
//      两者不是一个量（把它写成行数断言，回放时多半永远不满足），那是造断言，不是归纳。
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
            val facts = factsOf(snapshot, ev)
            steps += DraftStep(
                index = i + 1,
                intent = ev.faces.label().ifBlank { ev.kind.wire },
                kind = ev.kind.wire,
                url = ev.url,
                locator = ev.faces,
                param = paramName,
                optional = false,
                candidates = emptyList(),
                expect = facts.expect,
                evidence = listOf("观察/" + ObserveSession.fileNameFor(sessionId) + "#seq" + ev.seq),
                navigate = facts.navigate,
                expectlessNavigate = facts.expectless,
                nodeCount = facts.nodeCount,
                nodeCountAfter = facts.nodeCountAfter,
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

    /** 一步的观察事实（697）：这四样决定 expect／是不是导航步／窄口开不开。 */
    private data class StepFacts(
        val expect: List<String>,
        val navigate: Boolean,
        val expectless: Boolean,
        val nodeCount: Int,
        val nodeCountAfter: Int,
    )

    /**
     * 归纳一步的「之后发生了什么」。
     *
     * ⚠️ 只认「时间在事件之后」的页事实，**不按文档过滤**：整页跳转会换文档（docId 变），
     * 旧口径 `docId == ev.docId` 恰好把「跳转」这件事本身滤掉——690 那一单录出来的步
     * 一条 expect 都没有，根因就在这（加上页行 `t` 恒为 0，等于「永远没有之后」）。
     * 页事实按时序排，第一份就是这一步之后页面长什么样。
     */
    private fun factsOf(snapshot: ObserveSnapshot, ev: ObserveEvent): StepFacts {
        val later = snapshot.pages.filter { it.ts > ev.ts }.sortedBy { it.ts }
        if (later.isEmpty()) return StepFacts(emptyList(), false, false, ev.nodeCount, 0)
        val next = later.first()
        val urlChanged = later.any { it.url.isNotBlank() && it.url != ev.url }
        val docChanged = next.docId.isNotBlank() && ev.docId.isNotBlank() && next.docId != ev.docId
        val rowsChanged = later.any { it.nodeCount != ev.nodeCount }
        val navigate = ev.kind == ObserveKind.CLICK && (urlChanged || docChanged || rowsChanged)
        // 有 url 证据 ⇒ 就写 urlChanged（闭集内的真断言）；没有 ⇒ 一个断言都不写（不造）
        val expect = if (urlChanged) listOf("urlChanged") else emptyList()
        return StepFacts(
            expect = expect,
            navigate = navigate,
            expectless = navigate && expect.isEmpty(),
            nodeCount = ev.nodeCount,
            nodeCountAfter = next.nodeCount,
        )
    }
}
