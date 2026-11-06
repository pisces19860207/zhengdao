// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · GuardPipeline 的**监督缝**（469 外部监督＋471 停机在环在管线上的挂点）。
//
// 为什么是接口而不是让管线直接摸 HaltGate/保险箱：管线是执行层，全局单例进它的参数表
// 就是"偷偷读世界"（照 contracts/grantWiring 同一条老规矩）；排在参数表最后且带缺省
// ⇒ 存量调用点（含测试）零改、行为逐字节不变。缺省 [SafetyHooks.NONE] 三口全静默。
//
// 三个口的落位（都在 GuardPipeline 里一行调用）：
// - [intercept]：prepareImpl 最顶上——停机期任何新动作都被这里拒（软停/硬停都拦）；
//   "模型调解除"形状的工具名在此给原话拒绝（判据⑦）。
// - [bigFileNote]：确认卡拼行前——>10MB 原件"不整份备份"的卡上说明（470①）。
// - [beforeRun]：真要执行前——回滚保险箱入库（470①"写类动作前记"）＋记在飞。
// - [afterRun]：执行收口——起居注一行（判据①）＋关键动作通知（469②）＋
//   硬停期间收尾的在飞写动作自动回滚（471① 硬停的 470 兜底，L3：自动触发不要人批）。
package com.example.zhengdao.baopu.safety

/** 管线监督缝（缺省全静默；生产实现归 SafetyWiring）。 */
interface SafetyHooks {

    /** 停机/解除拦截：null＝放行；非 null＝拒绝理由（原话回灌给模型）。 */
    fun intercept(toolName: String): String? = null

    /** >10MB 原件的卡上说明（null＝没有要说明的；只 stat，不动盘）。 */
    fun bigFileNote(tool: String, paths: List<String>): String? = null

    /** 真要执行前：保险箱入库＋记在飞（动作还没发生——此刻的字节就是"已知好状态"）。 */
    fun beforeRun(tool: String, opId: String, paths: List<String>) = Unit

    /** 执行收口：起居注＋关键通知＋硬停在飞的自动回滚。 */
    fun afterRun(
        tool: String,
        readOnly: Boolean,
        riskTier: String,
        opId: String,
        paths: List<String>,
        ok: Boolean,
        toolRejected: Boolean,
        brief: String,
    ) = Unit

    companion object {
        /** 缺省实现（三口全静默——不装监督的调用方逐字节回到今天）。 */
        val NONE: SafetyHooks = object : SafetyHooks {}
    }
}

/** 关键动作的格（469②；闭集三格＋null）。 */
enum class KeyActionKind(val cn: String) {
    /** 不可逆动作真执行成功（riskTier R3）。 */
    IRREVERSIBLE("不可逆动作"),

    /** 批量动作（批量四件套/一次删多个）。 */
    BULK("批量动作"),

    /** 连续失败跨过 3 的倍数（第 3、6、9…次连败）。 */
    FAIL_STREAK("连续失败"),
}

/**
 * 关键动作的判则（纯函数；469②「不可逆/批量/连续失败≥3」三格的机器判据）。
 * 通知是否真的弹归开关与通知层，这里只裁"算不算关键动作"。
 */
object KeyActionRules {

    /** 批量四件套（工具名口径；一次删多个的 delete_path 走 [kindOf] 的 paths 判）。 */
    private val BULK_TOOLS = setOf("bulk_move", "bulk_copy", "organize", "empty_trash")

    /** R3（有真实世界后果）的工具自报档——与 218 注释同一口径。 */
    private const val TIER_IRREVERSIBLE = "R3"

    /** 连败的报信间隔（第 3 次报一条，此后每再连败 3 次再报）。 */
    const val STREAK_STEP = 3

    /**
     * 这一格算不算关键动作。
     *
     * @param paths 这次动作的目标数（delete_path 一次删多个 ⇒ 批量）。
     */
    fun kindOf(tool: String, riskTier: String, paths: Int): KeyActionKind? = when {
        riskTier == TIER_IRREVERSIBLE -> KeyActionKind.IRREVERSIBLE
        tool in BULK_TOOLS || (tool == "delete_path" && paths > 1) -> KeyActionKind.BULK
        else -> null
    }

    /** 连败计数该不该报（第 3 次报；此后每 3 次再报；[previousStreak] 是这次之前的连败数）。 */
    fun streakShouldNotify(previousStreak: Int): Boolean =
        (previousStreak + 1) % STREAK_STEP == 0
}
