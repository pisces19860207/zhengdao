// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十一批（2026-11-12）· 右相「三态达成」：学习档一轮收口的**独立判定**（法典 L34）。
//
// ## 判什么
// 左相（550）只判"用什么口吻"；这一轮讲完、用户回了话，**学没学进去**归右相判（法典 L34：
// 判档权归左相、达成判定归右相——同一轮里两个判定各管各的，不许互相代劳）。
// 工作档的达成不在这里：交付／契约／目标那三态早有闸（302／332／378／482），本件**不重复**。
//
// ## 三态（fail-closed；词面逐字＝法典 L34）
//   PASS         讲清了且按步骤做完——收口时用户确认跟上／答对／做出来了；
//   FAIL         讲了但没做/做错了——用户说没懂／不会／做错／报错；
//   UNVERIFIABLE 没法判断（要问或留观）——没有达成证据，或证据自相矛盾。
// 三态**复用 302 的既有闭集** [ChancellorVerdict]（不新造第四态）；UNVERIFIABLE **不许当
// PASS**——没证据就是没证据，判不了就说判不了（负例钉在 `L34CompanionLawGateTest`）。
//
// ## 纪律（与左相判档同一条：确定性、零模型、闭集）
//   1. 只吃**证据词表**（闭集两列，与 550 的显式词表同一收词规矩：中文原话，加词先过派工单）；
//      "讲清了"的证据是收口时的用户确认——本件不读上一轮的回复原文、不做语义分析、不判卷
//      （右相不评价，只对证据）。
//   2. 两列同时命中 ⇒ 矛盾不猜，UNVERIFIABLE（550 `explicitOf` 同款裁定）。
//   3. 词表避开能互相误伤的词：PASS 列的「跟上了」与 FAIL 列的「跟不上」一不一双、互不包含；
//      光杆词必须给出"只有这一种用法"的理由才许登记（550 §四⑥ 的「啰嗦／废话」同款口径）。
//
// 零 Android／零 IO／零时钟：判定是纯函数，[CompanionAchievement.reason] 由判定自己产出。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.CompanionMode

/** 一次学习档收口的达成判定（[verdict] 复用右相既有闭集；[reason] 必填——复盘与账行都要）。 */
internal data class CompanionAchievement(
    val verdict: ChancellorVerdict,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "达成判定必须带一句依据：没有依据的三态事后复盘不了" }
    }
}

/** 右相达成闸（纯判定：上一轮的档＋这一轮的回复原话，其余零输入）。 */
internal object CompanionAchievementGate {

    /** PASS 的证据词：用户确认跟上／答对／做出来了（加词先过派工单，L34 的收词规矩）。 */
    private val CONFIRM = listOf(
        "跟上了", "明白了", "学会了", "答对了", "做对了", "做出来了", "成功了", "讲清楚了",
    )

    /**
     * FAIL 的证据词：讲了但没做/做错了。
     * 「跟不上」与 PASS 的「跟上了」一不一双、互不包含；「不对」在收口回复里只有
     * "你讲的/你给的不对"一种用法，光杆登记不误伤（口径同 550 的「啰嗦／废话」）。
     */
    private val MISS = listOf(
        "没懂", "不懂", "听不懂", "还是不会", "不会做", "做错了", "报错了", "没做出来", "跟不上", "不对",
    )

    /**
     * 学习档一轮的收口判定。
     *
     * @param prevMode 上一轮判的档（[com.example.zhengdao.baopu.MediatorWiring] 的既成事实）；
     *   非学习档（工作／没判过）⇒ `null`：不判、不落行——达成判定只对学习档收口（法典 L34）。
     * @param reply 这一轮用户回复原话（判定的唯一证据面）。
     */
    fun judge(prevMode: CompanionMode?, reply: String): CompanionAchievement? {
        if (prevMode != CompanionMode.STUDY) return null
        val confirm = CONFIRM.firstOrNull { reply.contains(it) }
        val miss = MISS.firstOrNull { reply.contains(it) }
        return when {
            confirm != null && miss != null -> CompanionAchievement(
                verdict = ChancellorVerdict.UNVERIFIABLE,
                reason = "证据自相矛盾（同时命中「$confirm」与「$miss」）：矛盾不猜，要问或留观",
            )
            miss != null -> CompanionAchievement(
                verdict = ChancellorVerdict.FAIL,
                reason = "讲了但没做/做错了：用户说「$miss」",
            )
            confirm != null -> CompanionAchievement(
                verdict = ChancellorVerdict.PASS,
                reason = "讲清了且按步骤做完：用户确认跟上（「$confirm」）",
            )
            else -> CompanionAchievement(
                verdict = ChancellorVerdict.UNVERIFIABLE,
                reason = "没有达成证据：没法判断，要问或留观——判不了就说判不了",
            )
        }
    }

    /** 运行日志里那一行（三态词面上账；UNVERIFIABLE 恒带"不当通过"，不许翻成 PASS）。 */
    fun line(turn: Int, sessionId: Long, a: CompanionAchievement): String =
        "抱朴右相达成：第 $turn 轮学习档收口判成 ${verdictWord(a.verdict)}" +
            "｜依据：${a.reason}｜session=$sessionId"

    /** 三态的账面词（与 [ChancellorReport.render] 同一族字面；UNVERIFIABLE 必带那半句）。 */
    fun verdictWord(v: ChancellorVerdict): String = when (v) {
        ChancellorVerdict.PASS -> "PASS"
        ChancellorVerdict.FAIL -> "FAIL"
        ChancellorVerdict.UNVERIFIABLE -> "UNVERIFIABLE（判不了，不当通过）"
    }
}
