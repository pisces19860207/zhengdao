// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批 · 能力路由漏斗 L1 的 **IO 壳**（读数本体；房型照 400 的 LearnRead：
// 判据在 [FunnelL1Rules] 纯函数，本壳只做「读好输入 → 判 → 记一行」）。
//
// 三条边界（派工单 §五＋判据⑤）：
//   1. **只记不装**：本壳不写任何装配状态——不解锁组、不改 tools 数组、不碰上下文拼装。
//      「建议装载工具数／预计注入段数」只是**投影读数**，供「开/关 L1」对照比 token 差；
//      源级门禁 `FunnelReadGateTest` 扫本包两个文件禁出现写口字样。
//   2. **会话状态不落盘**：上一轮去向只活在进程内（与调解器档位同一口径）——
//      重启后从空开始，R4 自然不命中，行为可预期。
//   3. **读失败不静默**：脚本目录读不动 ⇒ 日志里说清「拿不到脚本表」，判定照记未命中，
//      不假装什么都没发生（69 批静默异常清单的口径）。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** L1 每轮判一次＋记一行的壳（调用点唯一：BaopuChatController.send，见判据④）。 */
internal object FunnelRead {

    /** 读数行前缀（验收 grep 用；判据④「每轮一行」就数这个前缀）。 */
    const val LOG_PREFIX = "抱朴漏斗L1："

    /** 会话 → 上一轮去向（进程内，R4 的输入；未命中的轮次不覆盖旧值——那件事可能还在做）。 */
    private val lastRouteBySession = ConcurrentHashMap<String, String>()

    /** 只看快照（单测与门禁用：证明会话状态按 sessionId 分格，不是一格共用）。 */
    internal fun snapshot(): Map<String, String> = lastRouteBySession.toMap()

    /** 清一格（单测用；生产没有清除口——进程没了自然没了）。 */
    internal fun clearForTest(sessionId: String) {
        lastRouteBySession.remove(sessionId)
    }

    /** 组名闭集（route 合法性校验的输入；组表只有一份，就在 agent 包，这里只读不写）。 */
    fun validGroupKeys(): Set<String> = ToolGroup.values().map { it.key }.toSet()

    /**
     * 读工作区里所有**可回放的脚本**（`技能/<名>/回放.json` 在才算）。
     * 目录序不稳定 ⇒ 按名字排序后进判据层——同盘两次读出同一张表（判据④的确定性延伸到 IO 壳）。
     */
    fun scriptsOf(workspaceRoot: File): List<FunnelScript> {
        val dir = SkillRules.projectSkillDir(workspaceRoot)
        val names = dir.listFiles()
            ?.filter { it.isDirectory && File(it, TeachReplayStore.REPLAY_FILE).isFile }
            ?.map { it.name }
            ?.sorted()
            ?: return emptyList()
        return names.mapNotNull { TeachReplayStore.load(workspaceRoot, it) }.map { skill ->
            FunnelScript(
                name = skill.name,
                purpose = skill.purpose,
                targetSite = skill.targetSite.ifBlank { FunnelL1Rules.hostOf(skill.startUrl).orEmpty() },
            )
        }
    }

    /** 装载数投影的三张静态表（全从组表读，零装配动作）。 */
    private fun countInputs(): CountInputs = CountInputs(
        groupTools = ToolGroupTable.GROUP_OF.entries
            .groupBy({ it.value.key }, { it.key })
            .mapValues { it.value.toSet() },
        defaultVisibleGroups = ToolGroup.DEFAULT_VISIBLE.map { it.key }.toSet(),
        defaultVisibleTools = ToolGroup.DEFAULT_VISIBLE_TOOLS,
        unlockFree = ToolGroup.UNLOCK_FREE_SILENT,
    )

    /** 投影读数的输入包（判据层的五个参数，壳层一次读齐）。 */
    private data class CountInputs(
        val groupTools: Map<String, Set<String>>,
        val defaultVisibleGroups: Set<String>,
        val defaultVisibleTools: Set<String>,
        val unlockFree: Set<String>,
    )

    /**
     * 读数行（判据④「每轮一行、字段齐」的本体；纯函数，单测直接钉四个字段）。
     * 字段序固定：route → 命中 → 建议装载工具数 → 预计注入段数 → 依据。
     */
    fun readingLine(sessionId: String, decision: FunnelDecision, toolCount: Int, segments: Int): String {
        val hit = FunnelL1Rules.hitLabel(decision)
        return "${LOG_PREFIX}会话=$sessionId route=${decision.route} 命中=$hit " +
            "建议装载工具数=$toolCount 预计注入段数=$segments 依据=${decision.reason}"
    }

    /**
     * 每轮一次：读脚本表（IO）→ 判（纯）→ 记一行读数 → 更新会话去向。
     * **不改装配、不拦消息**：这句话照常进引擎（本单只记读数）。
     */
    suspend fun onUserTurn(sessionId: String, text: String, hasImage: Boolean, workspaceRoot: File) {
        val scripts = withContext(Dispatchers.IO) {
            runCatching { scriptsOf(workspaceRoot) }
                .onFailure { RunLog.log("${LOG_PREFIX}脚本表读不动：${it.javaClass.simpleName}: ${it.message}（本轮按无脚本判）") }
                .getOrDefault(emptyList())
        }
        val decision = FunnelL1Rules.judge(
            FunnelTurnInput(text = text, hasImage = hasImage, scripts = scripts, previousRoute = lastRouteBySession[sessionId]),
            validGroupKeys = validGroupKeys(),
        )
        val inputs = countInputs()
        val toolCount = FunnelL1Rules.suggestedToolCount(
            route = decision.route,
            groupTools = inputs.groupTools,
            defaultVisibleGroups = inputs.defaultVisibleGroups,
            defaultVisibleTools = inputs.defaultVisibleTools,
            unlockFree = inputs.unlockFree,
        )
        val segments = FunnelL1Rules.expectedSegmentCount(decision.route)
        if (decision.route != FunnelL1Rules.ROUTE_MISS) {
            lastRouteBySession[sessionId] = decision.route
        }
        RunLog.log(readingLine(sessionId, decision, toolCount, segments))
    }
}
