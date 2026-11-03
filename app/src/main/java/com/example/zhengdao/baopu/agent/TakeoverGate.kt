// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-02-第三百六十六批-分片与接管 §一（三类检测＋接管五步）＋ §二（硬规矩）
// ＋ §三（判据①②③④），以及协议-分片与接管-2026-11-01 §二（片留痕三样齐）§三（检测表）
// §四（接管五步）§五（纪律）。消费 374 的 ProgressNote（ProcessObjects.kt 文件头裁定 Q1：
// 消费归 366，不并本单）。
//
// 本文件是**判定层**：零 Android / 零 IO / 零时钟（时刻一律由调用方传入），
// 与 `ProcessObjects.kt`（374）/ `PlanContract.kt`（242）同一个房型，JVM 直测。
//
// §一①「检测（只判状态，不动手）」三类闭集，按「谎报 > 耗尽 > 停滞」的序判，一中即返：
//   · INCOMPLETE：报「完成」但片不齐——自报 done > 影子核验的片数（三样齐才算片完成）。
//     协议 §三：这不是 STALLED，是谎报，按谎报处理（谎报改变性质，所以判序最前）；
//   · EXHAUSTED：承接方自报余量不足——额度耗尽是资源事实不是错误（§二 不追责）；
//   · STALLED 三条触发（任一即中，照 §一① 的行序）：
//       a) 无新提交**且**无日志超阈（小单 2h／大单 6h——阈由派单层按单的大小传入毫秒数，
//          两样都哑才算：只哑一样说明人还在场）；
//       b) 连续 2 次挂起（等裁决）且无进展；
//       c) 374 的 stallOf 判 STALLED（连续两次读数同进度——裁定 Q1 留给 366 的消费口）。
//   三类都不中 ⇒ null（「看不出来」不是一种状态，照 374 的口径）。
//
// §一②「接管五步（顺序固定）」——[TakeoverGate.run] 按步执行，回执里 steps 逐字可查：
//   ① 冻结现场：入口读一次头 sha、出口复读一次，前后不变才是冻结（判据②）。
//     **句柄类型 [FrozenBranch] 上只有 headSha() 一个口子**——「不许 force-push／reset 别人的
//     分支」不是一句纪律，是类型约束：想违规，编译都过不去；
//   ② 读留痕：三样（提交＋报告＋读数）全缺 ⇒ 只能重做，单独记一笔「无痕接管」（判据③）；
//   ③ 影子先验：**不信任自报**，逐片核三样得出已验片集（否则会接着错的往下干）；
//   ④ 接管剩余片：已验之外全算剩余，从最小未验片号接（「优先换会话」记在账行里）；
//   ⑤ 记账：谁/原因/从哪片接/已验片数/损失估算一行；无痕再单独一行。
//
// 前置（消费顺序照 374 文件头）：读数须先过 progressRejection 与 progressRegressionRejection，
// 被拒的读数不进本层（classify 不替调用方重拦）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.plan.ProgressNote
import com.example.zhengdao.baopu.plan.ProcessObjectRules
import com.example.zhengdao.baopu.plan.StallState

/**
 * 接管三信号（闭集，§一① 点名三类；没有第四类）。账上写枚举名，与派工单逐字一致。
 */
internal enum class TakeoverSignal(val cn: String) {
    STALLED("停滞"),
    EXHAUSTED("额度耗尽"),
    INCOMPLETE("谎报完成"),
    ;

    /** 谎报才是错误（§二：接管不追责，谎报除外）——账行要不要补「按谎报处理」由它定。 */
    val blameworthy: Boolean get() = this == INCOMPLETE
}

/**
 * 一片留痕：提交＋报告＋读数**三样齐才算片完成**（协议 §二「三样齐才算片完成」原话）；
 * 缺一样＝这片没做完，影子先验不认。
 */
internal data class ShardTrace(
    val shardIndex: Int,
    val hasCommit: Boolean,
    val hasReport: Boolean,
    val hasReadings: Boolean,
) {
    val complete: Boolean get() = hasCommit && hasReport && hasReadings
}

/**
 * 一段时间的活动痕迹。判停滞要的是「提交与日志**都**哑了」，所以两样时刻一起带；
 * 挂起计数只算**无进展**的挂起（有进展的挂起是正常等裁决，不算）。
 */
internal data class ActivityTrail(
    val lastCommitAtMs: Long,
    val lastLogAtMs: Long,
    val suspensionsWithoutProgress: Int,
)

/**
 * 检测的现场快照。阈由调用方传（小单 2h＝7_200_000 毫秒／大单 6h＝21_600_000 毫秒，
 * 派工单只定这两档，谁大谁小归派单层）；traces 是各片的留痕（没留痕的片根本不在表里＝零留痕）。
 */
internal data class SceneSnapshot(
    val orderId: String,
    val stallThresholdMs: Long,
    val trail: ActivityTrail,
    val selfReportedExhausted: Boolean,
    val previousNote: ProgressNote?,
    val note: ProgressNote,
    val traces: List<ShardTrace>,
)

/** 检测结论：信号＋触发原因。原因原样进账行（⑤记账），人不许再猜是哪条触发的。 */
internal data class SignalVerdict(val signal: TakeoverSignal, val trigger: String)

/**
 * 冻结现场的只读句柄：类型上只有「读头 sha」一个口子，push／reset 无处安放。
 * 接管方在真实场景里把它接到原分支上；测试里接到内存假分支上——两头都只读。
 */
fun interface FrozenBranch {
    fun headSha(): String
}

/** ① 冻结的凭证：前后各读一次头 sha；不变＝冻结真的是冻结（判据②）。 */
internal data class FreezeProof(val shaBefore: String, val shaAfter: String) {
    val unchanged: Boolean get() = shaBefore == shaAfter
}

/** 接管五步的输入。状态已由 [TakeoverGate.classify] 判完，这里只带结论与人名，不再判。 */
internal data class TakeoverScene(
    val orderId: String,
    val verdict: SignalVerdict,
    val origin: String,
    val successor: String,
    val totalShards: Int,
    val traces: List<ShardTrace>,
)

/** 五步的回执。判据②（冻结凭证）与判据③（无痕独立账行）都落在这里。 */
internal data class TakeoverRecord(
    val orderId: String,
    val steps: List<String>,
    val freeze: FreezeProof,
    val verifiedShards: List<Int>,
    val remainingShards: List<Int>,
    val resumeFromShard: Int,
    val ledgerLine: String,
    val tracelessLine: String?,
)

internal object TakeoverGate {

    /** 五步的固定顺序（§一②「顺序固定」；回执按它回报，审计对得上） */
    val STEPS: List<String> = listOf("①冻结现场", "②读留痕", "③影子先验", "④接剩余片", "⑤记账")

    /** 「连续 2 次挂起（等裁决）且无进展」——§一① 行原话的 2 */
    private const val SUSPENSION_LIMIT = 2

    /** 三样齐的片号（升序）。②读留痕与③影子先验共用的地面真值：留痕在，片才算在。 */
    fun verifiedOf(traces: List<ShardTrace>): List<Int> =
        traces.filter { it.complete }.map { it.shardIndex }.sorted()

    /**
     * 检测（§一①：只判状态，不动手）。三类按「谎报 > 耗尽 > 停滞」的序判，一中即返；
     * 都不中 ⇒ null（看不出来不是一种状态）。
     */
    fun classify(scene: SceneSnapshot, nowMs: Long): SignalVerdict? {
        val verified = verifiedOf(scene.traces).size
        if (scene.note.done > verified) {
            return SignalVerdict(
                TakeoverSignal.INCOMPLETE,
                "自报完成 ${scene.note.done} 片，影子核验只认 $verified 片（谎报，按谎报处理）",
            )
        }
        if (scene.selfReportedExhausted) {
            return SignalVerdict(
                TakeoverSignal.EXHAUSTED,
                "承接方自报余量不足——额度耗尽是资源事实，不是错误",
            )
        }
        stalledTrigger(scene, nowMs)?.let { return SignalVerdict(TakeoverSignal.STALLED, it) }
        return null
    }

    /** STALLED 的三条触发（照 §一① 的行序：超阈 ⇒ 挂起 ⇒ 374 同进度）。 */
    private fun stalledTrigger(scene: SceneSnapshot, nowMs: Long): String? {
        val trail = scene.trail
        val bothSilent = nowMs - trail.lastCommitAtMs > scene.stallThresholdMs &&
            nowMs - trail.lastLogAtMs > scene.stallThresholdMs
        if (bothSilent) {
            return "无新提交且无日志超阈（阈 ${scene.stallThresholdMs / 60_000L} 分钟）"
        }
        if (trail.suspensionsWithoutProgress >= SUSPENSION_LIMIT) {
            return "连续 ${trail.suspensionsWithoutProgress} 次挂起无进展"
        }
        if (ProcessObjectRules.stallOf(scene.previousNote, scene.note) == StallState.STALLED) {
            return "连续两次读数同进度（done=${scene.note.done}）"
        }
        return null
    }

    /**
     * 接管前的拒因（null＝可以接管）。人名缺一样账行就写不全（协议 §五.1：不追责，**但要记账**）；
     * 没片的单没有「片与片之间」，全验完的单没有「剩余片」——都不存在接管点。
     */
    fun takeoverRejection(scene: TakeoverScene): String? {
        val verified = verifiedOf(scene.traces)
        return when {
            scene.orderId.isBlank() -> "接管单号缺失——账行没地方挂"
            scene.origin.isBlank() -> "原承接方缺失——不追责但得记账，不知道记谁接的谁"
            scene.successor.isBlank() -> "接管方缺失——没人接的单不能只在账上「被接管」"
            scene.totalShards <= 0 -> "单 ${scene.orderId} 没有片——片与片之间才是接管点，无片无接管"
            verified.size >= scene.totalShards ->
                "单 ${scene.orderId} 的 ${scene.totalShards} 片已全部影子核验通过——没有剩余片，无需接管"
            else -> null
        }
    }

    /**
     * 接管五步（§一②，顺序固定）。先过 [takeoverRejection] 再调——剩余片为空属调用方违约。
     * 全程对分支只有两次**读**（①的入口与出口），没有任何写口。
     */
    fun run(scene: TakeoverScene, branch: FrozenBranch): TakeoverRecord {
        val shaBefore = branch.headSha() // ① 冻结现场（入口读）
        val traceless = scene.traces.none { it.complete } // ② 读留痕：三样全缺 ⇒ 无痕
        val verified = verifiedOf(scene.traces) // ③ 影子先验：不信任自报，逐片核三样
        val remaining = (1..scene.totalShards).filter { it !in verified } // ④ 接剩余片
        require(remaining.isNotEmpty()) { "单 ${scene.orderId} 没有剩余片——先过 takeoverRejection" }
        val ledger = ledgerLineOf(scene, verified, remaining) // ⑤ 记账
        val tracelessLine = if (traceless) tracelessLineOf(scene) else null // ⑤ 无痕单独记
        val shaAfter = branch.headSha() // ① 冻结现场（出口复读——前后不变才证明没动过）
        return TakeoverRecord(
            orderId = scene.orderId,
            steps = STEPS,
            freeze = FreezeProof(shaBefore, shaAfter),
            verifiedShards = verified,
            remainingShards = remaining,
            resumeFromShard = remaining.first(),
            ledgerLine = ledger,
            tracelessLine = tracelessLine,
        )
    }

    /** ⑤ 账行：谁/原因/从哪片接/已验片数/损失估算（协议 §四.⑤ 点名五样）逐字在行里。 */
    private fun ledgerLineOf(scene: TakeoverScene, verified: List<Int>, remaining: List<Int>): String {
        val base = "接管 单 ${scene.orderId}：${scene.verdict.signal.cn}" +
            " · 原承接方 ${scene.origin} → 接管方 ${scene.successor}（优先换会话）" +
            " · 从第 ${remaining.first()} 片接" +
            " · 已验 ${verified.size}/${scene.totalShards} 片" +
            " · 损失 ${remaining.size} 片" +
            " · 原因：${scene.verdict.trigger}"
        return if (scene.verdict.signal.blameworthy) {
            "$base · 谎报（按谎报处理）"
        } else {
            base
        }
    }

    /** 无痕接管单独记（判据③；协议 §五.3 原话口径：是流程问题，不是个人问题）。 */
    private fun tracelessLineOf(scene: TakeoverScene): String =
        "无痕接管 单 ${scene.orderId}：${scene.totalShards} 片零留痕（提交/报告/读数三样全缺）" +
            "——只能重做；留痕规矩没被执行（流程问题，不是个人问题）"
}
