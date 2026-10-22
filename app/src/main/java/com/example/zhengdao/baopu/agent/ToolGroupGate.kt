// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第九十七批 §2/§2.5（用户 2026-10-20 口径：
// 「工具全都打开的，能不能默认关/连续用到 N 次再开」⇒ 御批卡把关）。
//
// 口径变更登记（第五十九批 → 本批）：
// - 59 批：`capabilities(enable=…)` 是 FREE 自解锁——调了就当场解锁，不弹卡；
// - 本批：同一入口改成**向用户申请**——弹一张卡（复用 ask_user 那张卡），
//   用户选「本次会话 / 一直 / 取消」，同意才落解锁；被拒后按 N 次限流再问。
//   59 批那套「自解锁」口径作废（交付报告里附改写前后对照）。
//
// 三条设计口径：
// 1. **卡就是 ask_user 那张卡**（零新 UI）：[GroupApprovalCard] 只产出
//    [AskUserRequest]，落点是 `AskUserTool.sink`（引擎在 BaopuRuntime 里挂的
//    `engine.askUser`）。不新造卡片组件，也不改任何 Composable。
// 2. **计数只在本进程**（[ToolGroupGate]）：它管的是「被拒后别每次都弹」这一件事，
//    不是安全边界——真正的安全边界仍是 [isVisibleTool] 与 A 内核的权限确认。
//    进程重启后计数归零＝再问一次，代价只是多一张卡，不是多一次越权。
// 3. **审计只产出文本**（[ToolGroupAudit]）：落盘由装配层注入（`RunLog.log`），
//    注册表继续零 Android 依赖（JVM 直测）。行首 `[group]` 是可 grep 的锚。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.policy.Policy
import com.example.zhengdao.baopu.policy.PolicyRuntime

/** 一次组授权的范围（卡片上两个「同意」按钮的 id）。 */
internal enum class GroupGrantScope(val key: String, val label: String, val detail: String) {
    /** 只在这个会话里可用（59 批的原有语义）。 */
    SESSION("session", "本次会话", "只在这个会话里可用；新会话回到默认"),

    /** 长期可用（本批新增：写进持久档，设置页组开关同步打开）。 */
    ALWAYS("always", "一直", "以后都可用（随时能在设置里关）"),
}

/**
 * 御批卡的三选一（复用 ask_user 的卡片形状）。
 *
 * 为什么选项 id 用 [GroupGrantScope.key] 而不是中文：引擎侧只认 id，
 * 中文标签随时能改；[ID_CANCEL] 是本文件唯一一个「不同意」的 id。
 */
internal object GroupApprovalCard {

    /** 取消（不同意）。 */
    const val ID_CANCEL = "cancel"

    /** 卡片问题里给组起个短名（`oneLine` 是「网页操作：点击、输入…」这种，冒号前就是名字）。 */
    fun titleOf(group: ToolGroup): String = group.oneLine.substringBefore("：")

    /**
     * 构造这一次的卡片请求。
     *
     * SKILL 组单独加一句风险说明（§3「永远不自动开」——它本来就只有人点同意才开，
     * 这里再把风险写到卡面上，避免用户在不知情的情况下把「改人设/写技能」放出去）。
     */
    fun request(group: ToolGroup): AskUserRequest {
        val risk = if (group == ToolGroup.SKILL) "（写技能、改人设，风险较高）" else ""
        return AskUserRequest(
            question = "启用「${titleOf(group)}」组？$risk",
            options = listOf(
                AskUserOption(
                    id = GroupGrantScope.SESSION.key,
                    label = GroupGrantScope.SESSION.label,
                    detail = GroupGrantScope.SESSION.detail,
                    recommended = true,
                ),
                AskUserOption(
                    id = GroupGrantScope.ALWAYS.key,
                    label = GroupGrantScope.ALWAYS.label,
                    detail = GroupGrantScope.ALWAYS.detail,
                ),
                AskUserOption(
                    id = ID_CANCEL,
                    label = "取消",
                    detail = "这次不用，先用现有的工具",
                ),
            ),
            // 第一百零四批：盖上「授权」种＋组 key——壳层据此渲染决策卡·授权变体
            // 并按 (session, group) 分格；执行路径（askAndGrant）一个字没动。
            kind = AskKind.GROUP_AUTHORIZATION,
            subject = group.key,
        )
    }
}

/** 闸门给 `capabilities` 的回答：弹卡 / 这次别弹（限流或已有卡在等）。 */
internal sealed interface GroupGateDecision {
    /** 该弹卡了（[attempt] = 本会话对该组的第几次申请）。 */
    data class Ask(val attempt: Int) : GroupGateDecision

    /** 这次不弹，直接把原因回给模型（限流/合并）。 */
    data class Suppress(val reason: String) : GroupGateDecision
}

/**
 * 组启用的**限流闸**（§2「连续 N 次」与「同类建议限流/合并，不每次任务弹」）。
 *
 * 语义（写死，报告里同口径）：
 * - 本会话第一次申请某个组 ⇒ 弹卡（`Ask`）；
 * - 用户拒绝/无人应答 ⇒ 记下「下次要再攒 [threshold] 次申请才弹」，期间 `Suppress`；
 * - 用户同意 ⇒ 清掉该组计数（此后再走 `capabilities` 也到不了这里，因为它已经可见/已授权）；
 * - 同一组已有一张卡在等 ⇒ 合并（`Suppress`），不叠卡。
 *
 * 全部状态**按 (sessionId, group) 分格**（§4 打回第三条：不许全局共享）。
 */
internal class ToolGroupGate(
    val threshold: Int = PolicyRuntime.current().approval.groupReaskThreshold,
) {

    companion object {
        /**
         * 代码默认 N＝3（§2「默认 3，写死可配」）。
         *
         * 第一百二十二批：生效值改从政策表读（[PolicyRuntime]）；这里保留常量作为
         * 「表缺失时同一份默认」与 KDoc 锚，两处同源（[Policy.GROUP_REASK_THRESHOLD]）。
         */
        const val DEFAULT_THRESHOLD = Policy.GROUP_REASK_THRESHOLD
    }

    private data class Key(val sessionId: Long, val group: ToolGroup)

    /** 本会话对该组的累计申请次数（被拒后不清零：下一次弹卡的阈值按它算）。 */
    private val attempts = HashMap<Key, Int>()

    /** 下一次弹卡要等到第几次申请（默认 1＝第一次就弹）。 */
    private val nextAskAt = HashMap<Key, Int>()

    /** 已经有一张卡在等用户点（合并同类请求，不叠卡）。 */
    private val pending = HashSet<Key>()

    private val lock = Any()

    /** 申请启用一个组：要么给一张卡，要么给一个「这次别弹」的理由。 */
    fun decide(sessionId: Long, group: ToolGroup): GroupGateDecision = synchronized(lock) {
        val k = Key(sessionId, group)
        if (k in pending) {
            return GroupGateDecision.Suppress("已经有一张「${group.key}」的卡片在等用户点，同类请求合并")
        }
        val n = (attempts[k] ?: 0) + 1
        attempts[k] = n
        val target = nextAskAt[k] ?: 1
        if (n >= target) {
            pending += k
            GroupGateDecision.Ask(n)
        } else {
            GroupGateDecision.Suppress(
                "用户上次没有同意（这是第 $n 次申请，攒到第 $target 次才会再问）：先用现有工具做，别反复要同一组",
            )
        }
    }

    /** 用户同意了（任何一种 scope）：清掉该组的计数与「卡在等」。 */
    fun noteApproved(sessionId: Long, group: ToolGroup) {
        synchronized(lock) {
            val k = Key(sessionId, group)
            attempts.remove(k)
            nextAskAt.remove(k)
            pending.remove(k)
        }
    }

    /** 用户点了取消：下次要再攒 [threshold] 次申请才弹。 */
    fun noteDeclined(sessionId: Long, group: ToolGroup) {
        synchronized(lock) {
            val k = Key(sessionId, group)
            val n = attempts[k] ?: 1
            nextAskAt[k] = n + threshold
            pending.remove(k)
        }
    }

    /** 没人应答（超时/没有界面）：与拒绝同一条限流，但审计来源不同。 */
    fun noteAbandoned(sessionId: Long, group: ToolGroup) {
        synchronized(lock) {
            val k = Key(sessionId, group)
            val n = attempts[k] ?: 1
            nextAskAt[k] = n + threshold
            pending.remove(k)
        }
    }

    /** 本会话对该组累计申请了几次（测试/报告用；不是判据本体）。 */
    fun attemptCount(sessionId: Long, group: ToolGroup): Int =
        synchronized(lock) { attempts[Key(sessionId, group)] ?: 0 }

    /** 清空（单测用；生产不调）。 */
    fun reset() {
        synchronized(lock) {
            attempts.clear()
            nextAskAt.clear()
            pending.clear()
        }
    }
}

/**
 * 组授权的留痕（第三段「审计」：谁触发、哪个组、同意范围、来源）。
 *
 * 形制照 [com.example.zhengdao.baopu.kernel.KernelAudit]：**只产出那一行文本**，
 * 不写盘、不 import Android；落盘由装配层把 [sink] 接到 `RunLog.log`。
 * 行首 [TAG] 是 grep 锚：`grep '\[group\]'` 能捞出所有组授权动作。
 *
 * 时间不写在这行里：`RunLog.log` 落盘时自带时间戳（与 `[kernel]` 行同一口径）。
 */
internal object ToolGroupAudit {

    /** 日志锚。 */
    const val TAG = "[group]"

    /** 落盘点（装配层注入；null = 没人听，只返回文本）。 */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** 用户同意（卡片来源）。[scope] 是「本次会话」/「长期」。 */
    fun grant(group: ToolGroup, scope: String, source: String = "卡片"): String =
        "$TAG 批准 ${group.key}（$scope）来源=$source".also { emit(it) }

    /** 用户取消 / 无人应答（卡片来源）。 */
    fun deny(group: ToolGroup, source: String = "卡片"): String =
        "$TAG 未批准 ${group.key} 来源=$source".also { emit(it) }

    /** 设置页手动开/关（来源固定写「设置页」，与卡片区分）。 */
    fun manual(group: ToolGroup, on: Boolean): String =
        "$TAG 手动${if (on) "开启" else "关闭"} ${group.key} 来源=设置页".also { emit(it) }

    /**
     * 第一百零四批：决策中心·记录页撤销授权（来源＝决策中心）。
     *
     * 与 [manual] 分开：manual 是「用户开关优先」那个硬档（关掉连卡都不弹），
     * undo 只是收回「同意」这件事——组还回「已锁」，模型下次要再申请。
     */
    fun undo(group: ToolGroup, scope: String): String =
        "$TAG 撤销 ${group.key}（$scope）来源=决策中心".also { emit(it) }

    private fun emit(line: String) {
        sink?.invoke(line)
    }
}
