// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ⑥：轮末接线——**只读上下文，不回写任何状态**。
// 依据：定稿 §五（隔离三条缺一不可）§六（为什么只做影子）。
//
// 一条轮末的完整动作：读一行既有读数 → 影子自己跑一轮 → 追加一行对照账 → 返回写了几个字。
// **没有**第二件事：不改上下文、不改档位、不写库、不触发任何后续动作、不返回值给判定链
// （返回值只是"这轮真写了几行账"，给日志与测试用）。
//
// 静默开关：默认开（定稿 §五③），关掉时**一个字节都不写**、不碰盘、不留空文件。
//
// 挂线（属装配单，本单按落点约束不动机既有文件）：在 `TriggerWiring.onRunSettled` 末尾
// 加一行，形状与 714 六体读数同款——
//     safe("脑影") { BrainShadow.onRoundEnd(账根, 轮号, 只读上下文) }
// 账根＝抱朴记忆根（`EvolutionShadowLog.under` 同一个根），上下文＝每物类一行的
// [ShadowFeed]。挂线不需要改本文件一个字符。
package com.example.zhengdao.baopu.brain

import java.io.File

/**
 * 喂给影子的一行"只读上下文"：物类 id、既有档、刺激档、本轮时长。
 *
 * 这四个字段全部来自调用方**已经算好**的读数——影子不自己推导代理档（否则就成了
 * 第二套代理模型，判据⑤红）。[elapsedMs] 只用来定步数，不参与任何档位判定。
 */
internal data class ShadowFeed(
    val zoneId: String,
    /** 既有档（0–3）：只用来记 agree，不参与影子的任何计算。 */
    val proxyTier: Int,
    /** 刺激档（0–3）：编码成输入电流。 */
    val stimulusTier: Int,
    /** 本轮时长（毫秒）；步数＝时长/5，夹在 20..1200。 */
    val elapsedMs: Long = LifParams.MAX_STEPS * LifParams.DT_MS.toLong(),
)

/**
 * 一个物类跑完的影子读数（中间量，不外传）。
 * 三档都在这里，但**只有 turgor 那一对进账本**（定稿 §七的 7 个键里只有一对档）：
 * 适应/兴奋度两套映射先在段 B 内跑通并逐例验证，等段 C 的对照实验再决定要不要落账。
 */
internal data class ZoneShadow(
    val turgorTier: Int,
    val adaptTier: Int,
    val arousalTier: Int,
    val spikeRate: Double,
    val steps: Int,
    val frame: String,
)

/** 影子接线件：轮末调用，只写日志。 */
internal object BrainShadow {

    /** 静默开关的出厂值：开（定稿 §五③）。开＝只写账，不是进主链。 */
    const val ENABLED_DEFAULT = true

    /**
     * 轮末入口。[enabled] 为假 ⇒ 立刻返回 0，**不建目录、不碰盘**。
     * 返回值为本轮成功追加的账行数（0..feeds.size）；任一行失败只跳过该行并返回少一行，
     * 绝不把异常抛给收工链（65/89 口径：收尾不许被后台活带下去）。
     */
    fun onRoundEnd(
        globalRoot: File,
        round: Long,
        feeds: List<ShadowFeed>,
        enabled: Boolean = ENABLED_DEFAULT,
    ): Int {
        if (!enabled || feeds.isEmpty()) return 0
        val recorder = ShadowRecorder.under(globalRoot)
        return feeds.count { feed ->
            runCatching { recordOne(recorder, round, feed) }.getOrDefault(false)
        }
    }

    /**
     * 纯计算入口（无盘、无 IO）：跑一个物类的一轮影子，给三档与帧快照。
     * 测试直接打这里；接线层只经 [onRoundEnd]。
     */
    fun runZone(feed: ShadowFeed): ZoneShadow {
        val steps = stepsOf(feed.elapsedMs)
        val net = ShadowNet(feed.zoneId)
        val round = net.runRound(Encode.currentOf(feed.stimulusTier), steps)
        return ZoneShadow(
            turgorTier = MapToTier.turgor(round.spikeRate),
            adaptTier = MapToTier.adaptation(round.adaptSlope),
            arousalTier = MapToTier.arousal(round.meanPotential),
            spikeRate = round.spikeRate,
            steps = steps,
            frame = round.frame(),
        )
    }

    /** 时长 ⇒ 步数：夹在「至少一窗 20 步」与「定稿上限 1200 步」之间。 */
    fun stepsOf(elapsedMs: Long): Int {
        val raw = (elapsedMs / LifParams.DT_MS.toLong()).toInt()
        return raw.coerceIn(LifParams.STEPS_PER_WINDOW, LifParams.MAX_STEPS)
    }

    /** 写一行账；返回是否真写成。 */
    private fun recordOne(recorder: ShadowRecorder, round: Long, feed: ShadowFeed): Boolean {
        val shadow = runZone(feed)
        recorder.record(
            round = round,
            zone = feed.zoneId,
            proxyTier = feed.proxyTier,
            snnTier = shadow.turgorTier,
            spikeRate = shadow.spikeRate,
        )
        return true
    }
}
