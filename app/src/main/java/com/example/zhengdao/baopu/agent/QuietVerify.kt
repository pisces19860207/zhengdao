// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十九批 · 「验卡豁免」——免打扰期内，**显式验证动作**仍然出卡。
//
// 用户 2026-11-13 派工单（569 缺陷批 · 619，P3）：
//   现象：机制正常，但夜里做"验卡"会被大量压下（验证场景被免打扰吞了）。
//   ① 给"验卡/自检"一类**显式验证动作**留一条豁免：它在免打扰期内仍然出卡
//      （或改为"只弹一次、标自检"）；
//   ② **不许把免打扰整体放宽**（那不是修，是撤）。
// 判据：① 免打扰期内做一次验卡 ⇒ 确认卡出现（夹具）；② 普通打扰仍被压（负例）；
//       ③ 豁免只对显式验证动作生效（断言：其他 AskKind 不受影响）。
//
// 569 的现场是 02:14「凭=夜间时段」——那天夜里根本没有免打扰授权，
// 是**夜间形状**（[SessionShape.atNight]）把卡压掉的。所以本批的豁免只能挂在
// "这张卡是谁要的"上，不能挂在"现在几点"上：挂在时段上就等于把夜间免打扰撤了。
//
// 三条纪律（为什么长成下面这个样子）：
// 1. **豁免凭据是"这一次是谁要验"，不是"哪一类卡"**：[AskUserRequest.selfCheck]
//    只在下面 [request] 这一处置真——而且只有用户亲口说的那几句话才走得到这里
//    （[triggerOf] 闭集解析）。模型没有这个口子：`ask_user` 的入参里没有这一格，
//    所以模型再怎么说"这是自检"也变不出豁免。
// 2. **只弹一次**（派工单①的括号）：同一段静默里同一张自检卡只放一次（计数在闸里，
//    见 `QuietCardGate.exemptSelfCheck`）——豁免是一张通行证，不是一条常开的门。
// 3. **不许替用户说那句话**：[triggerOf] 认不出就 null ⇒ 什么都不弹。与免打扰授权
//    （`QuietMode.grantFromText`）同一条纪律：机器不能自己给自己批一次"这算验卡"。
package com.example.zhengdao.baopu.agent

/**
 * 开一次"验卡"的那句话（闭集；与免打扰那四个触发词同一个玩法，只是各管各的事）。
 *
 * 为什么是闭集而不是"包含'验'就算"：这个开关决定**免打扰让不让路**，
 * 谁来都能宣布一句"这算验卡"，那张豁免就成了免打扰上的一个后门。
 */
internal enum class VerifyTrigger(val wire: String, val cn: String) {
    VERIFY_CARD("verify_card", "验卡"),
    VERIFY_ONE("verify_one", "验一张卡"),
    SELF_CHECK_CARD("self_check_card", "自检一张卡"),
    ;

    companion object {
        /** 那句话里有哪个触发词就算哪个（按声明序取第一个；空句／没词 ⇒ null）。 */
        fun ofText(text: String): VerifyTrigger? =
            if (text.isBlank()) null else entries.firstOrNull { text.contains(it.cn) }
    }
}

/** 自检卡的身份锚（补问账、豁免账、卡面分类都用它；改它＝换一张卡， exemptions 会重新计）。 */
internal const val SELF_CHECK_SUBJECT_PREFIX = "selfcheck:"

/**
 * 显式验证动作的**唯一**构造口（纯函数，没有时钟、没有 IO）。
 *
 * 弹卡、落账、闸里的豁免判定全从这里拿同一份事实：卡是本文件造的，
 * `selfCheck=true` 也是本文件盖的——别处想造一张"免打扰也照弹"的卡，
 * 只能过这一格，而这一格只认人说的那几句话。
 */
internal object QuietVerify {

    /** 触发词 ⇒ 这次是哪种验法（只是记账用，弹出来的卡是同一张）。 */
    fun triggerOf(text: String): VerifyTrigger? = VerifyTrigger.ofText(text)

    /**
     * 验卡要弹的那张卡（ask_user 的旧单问题形状：2 个选项、点一下即完）。
     *
     * 为什么走选项卡而不是"只弹一条通知"：派工单①验的是**卡路**——闸让不让、
     * 卡画不画得出来、点得动点不动。少一样都不算验过，所以必须是一张能点的真卡。
     */
    fun request(trigger: VerifyTrigger): AskUserRequest = AskUserRequest(
        question = "自检卡：点一下任意一项，卡路就算通（这张卡不受免打扰影响）",
        options = listOf(
            AskUserOption("card_ok", "卡正常"),
            AskUserOption("card_bad", "哪里不对", detail = "点了会在账上记一条"),
        ),
        kind = AskKind.QUESTION,
        subject = SELF_CHECK_SUBJECT_PREFIX + trigger.wire,
        selfCheck = true,
    )

    /**
     * 弹之前那一行账（谁要验、凭什么话、此刻静默是凭什么）。
     *
     * 为什么先记一行再弹：569 那天夜里的账上只有"因免打扰延后"，没有"有人喊过验卡"——
     * 事后要复的人分不清"没人验"和"验了被吞"。这一行把两者分开。
     */
    fun armLine(
        trigger: VerifyTrigger,
        taskId: Long,
        grant: QuietGrant?,
        shape: SessionShape,
        nowMs: Long,
    ): String {
        val state = QuietMode.stateOf(grant, nowMs)
        val hushed = hushedWhy(grant, shape, nowMs)
        return "验卡｜任务 session:$taskId｜由「${trigger.cn}」要的一次显式验证｜" +
            "此刻=${state.cn}${if (hushed.isEmpty()) "" else "（静默凭=$hushed）"}｜自检卡免打扰也照弹（同张只放一次）"
    }

    /**
     * 静默是凭什么（授权／夜间／专注；命中几个给几个）。
     *
     * **唯一一份**：免打扰闸的落账行（`QuietCardGate.quietWhy`）与这里都用这一份，
     * 不在两个文件里各抄一遍条件——免打扰以后多一个源，抄两份就迟早只改一处。
     * 离场锁屏不在这儿：它不是免打扰（人在不在），闸自己那一格加。
     */
    fun hushedReasons(grant: QuietGrant?, shape: SessionShape, nowMs: Long): List<String> = buildList {
        if (QuietMode.active(grant, nowMs)) add("免打扰授权中")
        if (shape.atNight) add("夜间时段")
        if (shape.busy) add("专注期（连发指令中）")
    }

    /** [hushedReasons] 的一行版（落账行里那一格：`免打扰授权中＋夜间时段`）。 */
    fun hushedWhy(grant: QuietGrant?, shape: SessionShape, nowMs: Long): String =
        hushedReasons(grant, shape, nowMs).joinToString("＋")
}
