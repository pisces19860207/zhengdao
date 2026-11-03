// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · 「无人值守（L2 有限值守·离场模式）」的**圈判定**——
// 人离场之后机器替她干活的那条边界（在飞表 388 原话：圈内自动／圈外排队／红线停手）。
//
// 五条纪律（为什么长成下面这个样子）：
// 1. **判定是纯函数**：同一份输入 ⇒ 同一个裁决；时钟、锁屏、电量全由调用方喂进来，
//    本文件不摸 Context、不读盘、不读钟（照 QuietMode 330 的「一个写口都没有」）。
// 2. **圈是"范围＋类别"两层**：范围（[UnattendedAction.inScope]，这件活属不属于离场时
//    托付的会话）管"能不能自动"；类别（不可逆／外发／越权）沿用 330 免打扰的老口径，
//    **不做第二套卡类**——两套类别早晚对不上，一张卡归哪套问就成了新的官司。
// 3. **红线不看圈**：外发（含发版类，在飞表 388 明令"不自动发版"）与越权一律停手，
//    哪怕它发生在圈内——"排队一条待她授权的外发"是给未来的自己留门，这扇门不开。
//    与 QuietMode.decide 的「外发排在可回退之前」同一条法。
// 4. **不可逆照旧排队**（388 派工单原话）：不可逆动作在值守期的下场与平常一致——必须问，
//    只是"问"这件事在锁屏期间被弹卡闸压进队列；动作本身不因离场获得任何豁免。
// 5. **降级与环境暂停只收回"自动"**：B级汇总超时的降级、电量/过热/无网的暂停，
//    都不停"记账与排队"——在手与排队的事一件不丢，等条件回来或她回场再处置。
package com.example.zhengdao.unattended

/**
 * 一件待办动作在离场值守下的裁决（闭集三格）。
 *
 * "既不自动也不排队也不停手"的第四格不存在：每一件事都必须有名有姓地去处，
 * 与 QuietCardVerdict 的三格纪律同源。
 */
internal enum class UnattendedVerdict(val wire: String, val cn: String) {
    /** 圈内：托付范围内、非不可逆、环境正常 ⇒ 自动执行。 */
    AUTO("auto", "圈内自动"),

    /** 圈外（或不可逆／降级／暂停）：不自动做，进队列等她回场处置。 */
    QUEUE("queue", "圈外入队"),

    /** 红线（外发／越权）：停手。不做、不排队代做、不自动重试，只留痕。 */
    STOP("stop", "红线停手"),
}

/**
 * 一件待办动作的圈层画像（调用方逐格填；不知道的那格如实给 false，不许猜）。
 *
 * 四个格子全是布尔是故意的：本层不负责"判断一件动作是不是外发"——那是工具分类器的活
 * （391 接线批的缝），本层只负责**给定分类之后怎么裁**。职责分错了，两条链迟早打架。
 *
 * @param inScope 属不属于离场时托付的范围（托付的会话内的活 ＝ true）
 * @param irreversible 不可逆动作（R3／[CardClass.IRREVERSIBLE] 同一口径）
 * @param outbound 外发（数据出设备；发版类动作在飞表 388 明令归入这一格）
 * @param overreach 越权（超出已授的权，[CardClass.OVER_REACH] 同一口径）
 */
internal data class UnattendedAction(
    val inScope: Boolean,
    val irreversible: Boolean = false,
    val outbound: Boolean = false,
    val overreach: Boolean = false,
)

/** 裁决那一刻的环境（降级与环境暂停由服务／看门狗喂，本层不自测环境）。 */
internal data class UnattendedSituation(
    val degraded: Boolean = false,
    val paused: Boolean = false,
)

/**
 * 一次裁决：裁决格＋一句为什么（事后复的入口——不许有人只看到档、看不到理由，
 * 与 BootStrategy 的 why 纪律同一条）。
 */
internal data class UnattendedRuling(val verdict: UnattendedVerdict, val why: String) {
    init {
        require(why.isNotBlank()) { "裁决必须带为什么（${verdict.cn}也不能空手）" }
    }

    /** 落账／通知用的一行：裁决名＋理由，恒一行。 */
    val line: String get() = "${verdict.cn}｜$why"
}

/**
 * 圈判定的唯一入口（纯函数；388 判据「圈内自动／圈外入队／红线停手」各有一测钉在这里）。
 *
 * 优先级即书写序，六条不许调：
 * 1. 外发／越权 ⇒ 停手（红线不看圈，也不看降级与否）；
 * 2. 圈外 ⇒ 入队；
 * 3. 不可逆 ⇒ 照旧排队（派工单原话）；
 * 4. 降级 ⇒ 入队（停手语义＝不再自动执行；排队的事不丢，等她回场）；
 * 5. 环境暂停 ⇒ 入队（同上，复工后由她或下一轮处置）；
 * 6. 其余 ⇒ 圈内自动。
 */
internal fun judgeUnattended(action: UnattendedAction, situation: UnattendedSituation): UnattendedRuling = when {
    action.outbound -> UnattendedRuling(
        UnattendedVerdict.STOP,
        "外发（含发版）类动作值守期停手：不自动做、不排队代做（388：不自动发版）",
    )
    action.overreach -> UnattendedRuling(
        UnattendedVerdict.STOP,
        "越权请求值守期停手：超出已授的权，机器不替她扩权",
    )
    !action.inScope -> UnattendedRuling(
        UnattendedVerdict.QUEUE,
        "圈外：不在离场托付的范围内，排队等她回场处置",
    )
    action.irreversible -> UnattendedRuling(
        UnattendedVerdict.QUEUE,
        "不可逆照旧排队：动作不因离场获得豁免，锁屏期间问这件事被压进队列",
    )
    situation.degraded -> UnattendedRuling(
        UnattendedVerdict.QUEUE,
        "已降级停手：B级汇总超时，自动权收回；这件事排队等她回场，不丢",
    )
    situation.paused -> UnattendedRuling(
        UnattendedVerdict.QUEUE,
        "环境暂停中：入队不执行；复工后按序处置",
    )
    else -> UnattendedRuling(UnattendedVerdict.AUTO, "圈内：托付范围内、非不可逆、环境正常")
}
