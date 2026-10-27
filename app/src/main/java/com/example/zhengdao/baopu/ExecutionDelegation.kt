// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百三十六批 · §2.5 **执行权下放**的判据层（纯函数；零 IO、零时钟、零 Android）。
//
// 用户 2026-10-26 的原话是本层的存在理由：「执行权是下放了吧？不然人得累死，人类高度集权也有坏处」。
// 派工单 §2.5.1 把现状说清了：**R0 已全放、R1 半放（判定散在权限位里，容易多问）、R2/R3 不放**。
// 本层补的就是"再放一层"——R1 里「可逆 ＋ 在工作区内」的写改成免问，并且**只在能自动留回滚点时**才免。
//
// 三条不商量（派工单 §2.5.3，本层的天花板）：
// 1. **R2/R3 永不下放**——外部写、删除、提交/发布/支付，照旧必问；无人应答 ⇒ **停并留日志**，
//    绝不"替人点"（这是本层唯一一条"不能因为方便而通融"的规则）；
// 2. **C 类永不下放**——权限位、风险档、工具白名单、确认口径本身；
// 3. **档位不由本层判**——`risk`/`mustAsk`/`rejected` 一律取自 219 的 [RiskVerdict]。
//    本层是**接线**，不是第二张表（223 给 RiskMatrix 立的规矩：表只有一张，这里再加一条也不认）。
//
// 第四条是本层自己加的，因为派工单 §2.5.4 说"没有度量的下放就是把风险藏起来"：
// **免问回滚率 >5% ⇒ 这一层自己把免问收回去**。收回的判据写在函数里、不靠调用方传一个 bool——
// 忘了传就等于没收回，那正是负例④「免问回滚率 >5% 还不收回」的形状。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolPermission

// ── 事实（调用方采集，本层只判）──────────────────────────────────────────────

/**
 * 一次「要不要免问」所需的全部事实。
 *
 * @param verdict 219 矩阵的判定（**唯一档位来源**；本层不许自己数关键词升档）
 * @param permission 注册表上的权限位（`FREE` 本来就不弹卡，本层只处理 `CONFIRM` 那一类）
 * @param insideWorkspace 目标在 `BaopuWorkspace.hostDir()` 之下（§2.5.2 条件①）
 * @param reversible 这一步做完能退回原样（覆盖无备份／删空目录**不算**可逆，§2.5.2 条件②）
 * @param rollbackPointReady 写之前那个 `.bak` **已经落成了**（不是"应该能留"，是"留下了"）
 * @param cClassTarget 目标是不是 C 类（权限位/风险档/白名单/确认口径；§2.5.2 条件③）
 * @param humanPresent 现在有没有人在屏幕前（无人 ⇒ R2/R3 只能停，不能默认通过）
 * @param scheduledTaskPreauth 定时任务建任务时人点过头（§2.5.2「预授权」；**只管 R1**，R2/R3 不受它影响）
 * @param silentActs 最近窗口内免问执行了多少次（度量用）
 * @param rollbacksAfterSilent 其中后来被回滚了几次（度量用）
 */
internal data class DelegationFacts(
    val verdict: RiskVerdict,
    val tool: String,
    val permission: ToolPermission = ToolPermission.CONFIRM,
    val insideWorkspace: Boolean = false,
    val reversible: Boolean = false,
    val rollbackPointReady: Boolean = false,
    val cClassTarget: Boolean = false,
    val humanPresent: Boolean = true,
    val scheduledTaskPreauth: Boolean = false,
    val silentActs: Int = 0,
    val rollbacksAfterSilent: Int = 0,
)

// ── 四态结论 ────────────────────────────────────────────────────────────────

/** 下放的结论（调用方逐态办事，不许把 StopAndWait 当 SilentAct 跑）。 */
internal sealed interface DelegationVerdict {
    /** 免问直行。[requireRollbackPoint] 恒 true：免问的前提是回滚点已经在，不是"回头再说"。 */
    data class SilentAct(val tool: String, val why: String, val requireRollbackPoint: Boolean) : DelegationVerdict {
        override val popsCard: Boolean get() = false
    }

    /** 照旧弹确认卡（R2/R3、C 类、三条件不齐、额度已收回）。 */
    data class Ask(val tool: String, val reasons: List<String>) : DelegationVerdict {
        override val popsCard: Boolean get() = true
    }

    /** 无人应答 ⇒ **停**，留一条日志；这是"不替人点"的落地态（负例③）。 */
    data class StopAndWait(val tool: String, val reasons: List<String>) : DelegationVerdict {
        override val popsCard: Boolean get() = false
    }

    /** 不归本层管（硬拒／终端豁免／没有档位）：交回原路，本层一个字都不改它的命运。 */
    data class NotApplicable(val tool: String, val why: String) : DelegationVerdict {
        override val popsCard: Boolean get() = false
    }

    /** 会不会弹卡（负例①②共用的那一句；StopAndWait **不弹**——没人可弹，它停）。 */
    val popsCard: Boolean
}

/**
 * §2.5.2 那三条条件的判据（**唯一入口**）。
 *
 * 顺序即优先级，别调换：矩阵的结论先于下放，下放的收回先于下放本身——
 * 先问"这步能不能免"，再问"免问这条路还在不在"。
 */
internal object DelegationRules {

    fun decide(f: DelegationFacts): DelegationVerdict {
        val v = f.verdict
        // ① 矩阵已经判死/豁免/没档 ⇒ 本层不参与（下放不等于改判）。
        if (v.rejected) return DelegationVerdict.NotApplicable(f.tool, "矩阵已硬拒（${v.reasonCode.id}），免问这条路不存在")
        if (v.exempt) return DelegationVerdict.NotApplicable(f.tool, "终端不参与四档，交回原路")
        val risk = v.risk
            ?: return DelegationVerdict.NotApplicable(f.tool, "这一档没有风险档（没分类的 fail-closed 由 223 记账）")

        // ② R0 早就全放（§2.5.1 现状），本来就不弹卡，本层不冒充"我放的"。
        if (risk == RiskClass.R0) {
            return if (f.permission == ToolPermission.FREE) {
                DelegationVerdict.NotApplicable(f.tool, "R0 免确认直行是既有口径，本层不重复下放")
            } else {
                DelegationVerdict.SilentAct(f.tool, "R0：读/列目录/搜索/快照，早已下放", requireRollbackPoint = false)
            }
        }

        // ③ R3 永远必问（红线：不得改「不可逆动作」的确认口径）。
        if (risk == RiskClass.R3) return blocked(f, "R3（花钱/外发/账号类）永不下放")

        // ④ C 类（权限位、风险档、工具白名单、确认口径）永不下放。
        if (f.cClassTarget) return blocked(f, "C 类目标（权限/风险档/白名单/确认口径）——改它就是改缰绳，只能人手动")

        // ⑤ 矩阵点名必问（未分类 fail-closed、批量超限、表单提交类…）⇒ 不因"看着像 R1"而绕过。
        if (v.mustAsk) return blocked(f, "矩阵判「无论什么模式都必须问」（${v.reasonCode.id}）")

        // ⑥ R2 照旧必问（§2.5.3 第一样：外部写、删除、移动、表单填充、MCP 调用）。
        if (risk == RiskClass.R2) return blocked(f, "R2（写工作区外/删/移动/表单填充/MCP）不下放")

        // ⑦ 收回判据（§2.5.4：免问回滚率 >5% ⇒ 说明放太宽，收回）。
        //    按**累计计数**判，不按调用方给的 bool——忘了传 bool 就等于这条闸不存在。
        if (DelegationMetrics.revokedByRollbackRate(f.silentActs, f.rollbacksAfterSilent)) {
            val rate = DelegationMetrics.rollbackRatePct(f.silentActs, f.rollbacksAfterSilent)
            return blocked(
                f,
                "免问已收回：回滚率 ${DelegationMetrics.pctText(rate)}% > ${DelegationMetrics.ROLLBACK_RATE_LIMIT_PCT}%" +
                    "（说明这一层放太宽，先退回必问）",
            )
        }

        // ⑧ R1：三条全满足才免问（①工作区内 ②可逆且回滚点已在 ③非 C 类——③在④已挡）。
        val missing = mutableListOf<String>()
        if (!f.insideWorkspace) missing += "目标不在当前工作区内"
        if (!f.reversible) missing += "这一步不可逆（覆盖无备份／删空目录都算）"
        if (!f.rollbackPointReady) missing += "写前的回滚点还没落成"
        if (missing.isNotEmpty()) return blocked(f, "R1 免问条件不齐：" + missing.joinToString("；"))

        // ⑨ FREE 本来就不弹卡——本层不冒领；CONFIRM 才是"这层省掉一次点击"的地方。
        if (f.permission == ToolPermission.FREE) {
            return DelegationVerdict.NotApplicable(f.tool, "工具本来就是 FREE，没有卡可免")
        }
        val why = if (f.scheduledTaskPreauth) {
            "R1 可逆写在工作区内 ＋ 建任务时人点过头 ⇒ 免问（写前必留回滚点）"
        } else {
            "R1 可逆写在工作区内，三条件齐 ⇒ 免问（写前必留回滚点）"
        }
        return DelegationVerdict.SilentAct(f.tool, why, requireRollbackPoint = true)
    }

    /** 该问就问；**没人可问就停**（绝不由本层默认通过——§2.5.3 第三样）。 */
    private fun blocked(f: DelegationFacts, why: String): DelegationVerdict =
        if (f.humanPresent) DelegationVerdict.Ask(f.tool, listOf(why))
        else DelegationVerdict.StopAndWait(f.tool, listOf("$why｜且当前无人应答 ⇒ 停，不替人点"))

    /**
     * 免问执行**之后**要不要真的落回滚点（§2.5.2 兜底那一条）。
     *
     * 单列成函数是因为它容易被写成"顺手复制一份"：回滚点没落成 ⇒ 这次免问作废（必须回到弹卡）。
     * 调用方拿到 false 时不许写盘。
     */
    fun rollbackPointAccepted(v: DelegationVerdict, backupWritten: Boolean): Boolean = when {
        v !is DelegationVerdict.SilentAct -> true
        !v.requireRollbackPoint -> true
        else -> backupWritten
    }
}

// ── 度量（§2.5.4：没有度量的下放就是把风险藏起来）────────────────────────────

/**
 * 三个度量 ＋ 一个收回判据（全纯函数；数据来源是 208 的 `auto_act` 与卡面计数，本层不查盘）。
 *
 * 窗口取"最近这么多条免问执行"（调用方给计数，不自己维护窗口，免得两处窗口长度打架）。
 */
internal object DelegationMetrics {

    /** 免问回滚率上限（%）：超过就收回免问（派工单 §2.5.4 的 5%）。 */
    const val ROLLBACK_RATE_LIMIT_PCT: Int = 5

    /** 典型任务的确认次数目标（派工单 §2.5.4：≤2 次）。 */
    const val CONFIRM_DENSITY_TARGET: Double = 2.0

    /** 少于这么多次免问不谈比例（3 次里 1 次回滚＝33%，那是噪声不是趋势，但也不能当"没事"）。 */
    const val MIN_SAMPLE = 20

    /** 回滚率（%）；分母 0 ⇒ 0（没有样本就没有比率，不许写成"未知"骗过收回判据）。 */
    fun rollbackRatePct(silentActs: Int, rollbacksAfterSilent: Int): Double {
        if (silentActs <= 0) return 0.0
        return (rollbacksAfterSilent.coerceAtLeast(0) * 100.0) / silentActs
    }

    /**
     * 免问该不该收回。
     *
     * 两个方向都收：样本够且超阈值 ⇒ 收回；**样本不够但已经出现回滚** ⇒ 也收回（先退回必问，
     * 攒够样本再谈放宽）。第二种是刻意的保守：新上线阶段样本一定少，
     * "样本不够就不算超标"会变成一条永远收不回的免问。
     */
    fun revokedByRollbackRate(silentActs: Int, rollbacksAfterSilent: Int): Boolean {
        if (rollbacksAfterSilent <= 0) return false
        val rate = rollbackRatePct(silentActs, rollbacksAfterSilent)
        return silentActs < MIN_SAMPLE || rate > ROLLBACK_RATE_LIMIT_PCT
    }

    /** 确认密度（每任务确认次数；拿 [CONFIRM_DENSITY_TARGET] 比，超标只出话、不自动改行为）。 */
    fun confirmDensity(asks: Int, tasks: Int): Double = if (tasks <= 0) 0.0 else asks.toDouble() / tasks

    /** 密度是否超标（>2 次/任务 ⇒ 说明"人累死"还没解决；这条不是闸，是提醒）。 */
    fun densityOverTarget(asks: Int, tasks: Int): Boolean = confirmDensity(asks, tasks) > CONFIRM_DENSITY_TARGET

    /**
     * R2/R3 拦截率（§2.5.4 第三条）。
     *
     * 分母＝本该问的 R2/R3 次数，分子＝**真的摆了卡或停了**的次数。小于 1 就是有漏，
     * 漏一次也不接受（负例①「把 R2 当 R1 免问」钉的就是这个数掉下来）。
     */
    fun interceptRate(shouldAsk: Int, actuallyAskedOrStopped: Int): Double =
        if (shouldAsk <= 0) 1.0 else (actuallyAskedOrStopped.coerceAtMost(shouldAsk).toDouble() / shouldAsk)

    /** 拦截率合格线：恒 1（差一次就是漏；本层不给 0.99 这种"差不多"）。 */
    fun interceptedAll(shouldAsk: Int, actuallyAskedOrStopped: Int): Boolean =
        shouldAsk <= 0 || interceptRate(shouldAsk, actuallyAskedOrStopped) >= 1.0

    /** 百分比读数（一位小数；度量话术里唯一的数字格式）。 */
    fun pctText(rate: Double): String = (Math.round(rate * 10) / 10.0).toString().trimEnd('0').trimEnd('.')

    /** 度量一行（报告与设置页共用；三项各一句，缺数据就明说缺）。 */
    fun line(silentActs: Int, rollbacksAfterSilent: Int, asks: Int, tasks: Int, shouldAsk: Int, stoppedOrAsked: Int): String =
        "免问 $silentActs 次／其中回滚 $rollbacksAfterSilent 次（${pctText(rollbackRatePct(silentActs, rollbacksAfterSilent))}%，" +
            "上限 $ROLLBACK_RATE_LIMIT_PCT%）｜确认密度 ${
                (Math.round(confirmDensity(asks, tasks) * 10) / 10.0)
            } 次/任务（目标 ≤$CONFIRM_DENSITY_TARGET）｜R2R3 拦截 " +
            "$stoppedOrAsked/$shouldAsk"
}

// ── 文案 ────────────────────────────────────────────────────────────────────

internal object DelegationTell {
    fun line(v: DelegationVerdict): String = when (v) {
        is DelegationVerdict.SilentAct -> "免问直行：${v.tool}（${v.why}）"
        is DelegationVerdict.Ask -> "仍要问：${v.tool}——${v.reasons.joinToString("；")}"
        is DelegationVerdict.StopAndWait -> "停下等人：${v.tool}——${v.reasons.joinToString("；")}"
        is DelegationVerdict.NotApplicable -> "不归下放管：${v.tool}（${v.why}）"
    }

    /**
     * 无人应答停下时回灌给模型的那句（**必须说"停了"，不能说"做了"**）。
     *
     * 为什么单独给一句：这一态最容易被写成"超时默认通过"（负例③）。
     * 话术里点名"没执行"，模型才不会以为动作已经落地。
     */
    fun stopText(v: DelegationVerdict.StopAndWait): String =
        "动作没执行（${v.tool}）：${v.reasons.joinToString("；")}——需要人点头，我不会替你点。"
}
