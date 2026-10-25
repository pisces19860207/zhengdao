// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批 §2／§2.5／§2.7：决策记录页的**文案与跳转判据**（纯函数层）。
//
// 为什么单独一个文件：`DecisionGroups`／`DecisionSampling` 在 `baopu` 包（零 Android、
// 零 R），而「组叫什么、chip 叫什么、跳回哪儿」是界面语言——归 UI 层。
// 纪律与其它 `*Rules` 一致：不许 @Composable、不许拿 Context、不许 IO。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionFilter
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionKind

/** 组卡标题（五个对象组＋待抽检组；组名与 §2.5 表格逐字一致）。 */
internal fun decisionGroupTitle(g: DecisionGroup): Int = when (g) {
    DecisionGroup.SAMPLING -> R.string.decision_group_sampling
    DecisionGroup.SKILL -> R.string.decision_group_skill
    DecisionGroup.GRANT -> R.string.decision_group_grant
    DecisionGroup.ARTIFACT -> R.string.decision_group_artifact
    DecisionGroup.EVAL -> R.string.decision_group_eval
    DecisionGroup.DIARY -> R.string.decision_group_diary
}

/** 筛选 chip 文案（§2.5 规则④的四个）。 */
internal fun decisionFilterTitle(f: DecisionFilter): Int = when (f) {
    DecisionFilter.ALL -> R.string.decision_filter_all
    DecisionFilter.TODAY -> R.string.decision_filter_today
    DecisionFilter.THIS_SESSION -> R.string.decision_filter_session
    DecisionFilter.REVERSIBLE -> R.string.decision_filter_reversible
}

/** 记录行上的「谁做的」。 */
internal fun decisionActorLabel(a: DecisionActor): Int = when (a) {
    DecisionActor.USER -> R.string.decision_record_actor_user
    DecisionActor.MODEL -> R.string.decision_record_actor_model
    DecisionActor.AUTO -> R.string.decision_record_actor_auto
}

/** 相对时间的桶（组头的「最近 X 前」用它；文案在 strings，一个数字都不许在 UI 里拼）。 */
internal enum class DecisionTimeBucket { NOW, MINUTES, HOURS, DAYS, OLDER }

/** 一条记录有多久（[n] 只在 MINUTES／HOURS／DAYS 有意义）。 */
internal data class DecisionAge(val bucket: DecisionTimeBucket, val n: Int = 0)

/** 现算相对时间（负数按「刚刚」处理——时钟回拨不至于显示「-3 分钟前」）。 */
internal fun decisionAgeOf(atMs: Long, nowMs: Long): DecisionAge {
    val d = nowMs - atMs
    return when {
        d < 60_000L -> DecisionAge(DecisionTimeBucket.NOW)
        d < 3_600_000L -> DecisionAge(DecisionTimeBucket.MINUTES, (d / 60_000L).toInt())
        d < 86_400_000L -> DecisionAge(DecisionTimeBucket.HOURS, (d / 3_600_000L).toInt())
        d < 30L * 86_400_000L -> DecisionAge(DecisionTimeBucket.DAYS, (d / 86_400_000L).toInt())
        else -> DecisionAge(DecisionTimeBucket.OLDER)
    }
}

/**
 * 点一行跳回**原始上下文**（派工单 §2 第三条）。
 *
 * 「跳哪儿」由 kind 决定，不看组：评测结论跳设置页的评测区，产物删除跳回收站，
 * 日记跳日记页，其余（技能/授权）跳设置页——都是**既有页面**，不新开页。
 */
internal enum class DecisionJump {
    SETTINGS,
    ARTIFACT_TRASH,
}

/** 这条记录该跳哪（§2「点一行能跳回原始上下文」）。 */
internal fun decisionJumpOf(kind: DecisionKind): DecisionJump = when (kind) {
    DecisionKind.ARTIFACT_DELETE, DecisionKind.AUTO_ACT -> DecisionJump.ARTIFACT_TRASH
    else -> DecisionJump.SETTINGS
}
