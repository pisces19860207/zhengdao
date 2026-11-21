// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 虚构题材模式的开关与装配（五件之①）。
//
// 开关口径（2026-11-20 用户定：默认改"用户可开"）：
// · 设置里可开可关，**默认关**；读失败也按关（fail closed，照 649 ContentPackGate 房型）；
// · **即时生效**：开关状态每次调用现读、不缓存——拨完下一句就是新行为；
// · 关掉后零注入：[FictionMode.lines] 直接返回空表（token 增量 0），
//   与"没有本单"逐字节一致——因为关着时本包一行字都不进任何上下文。
//
// 边界（写死）：不进 738 默认注入、不改判定面、不做行为指导（步骤/体位/技巧）、
// 医学名只进术语层、输出只出机制与现象；判"不做"的那份素材不进本模式任何清单。
package com.example.zhengdao.baopu.state.fiction

import android.content.Context
import androidx.core.content.edit

/**
 * 设置项「虚构题材模式」开关（唯一读写口；照 649 房型：屏上显示与闸门读同一个键）。
 *
 * 默认**关**：不许改成 true——派工单 759「默认关、显式开启」。
 */
internal object FictionModeGate {
    const val PREFS_NAME = "baopu_fiction_mode"
    const val KEY_ON = "fiction_mode_on"

    /** 默认值。**不许改成 true**：派工单「设置里可开关（默认关）」。 */
    const val DEFAULT_ON = false

    fun on(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ON, DEFAULT_ON)
    }.getOrDefault(DEFAULT_ON)

    /** 拨动（设置页唯一写口；写失败按没写处理，下次读到的还是旧值）。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_ON, on) }
        }
    }
}

/**
 * 装配（纯函数，零 IO、零 Android）：给设定谱记录 + 开关现值，出要注入的行。
 *
 * 判据⑥⑦的落点：`switchOn=false` ⇒ 空表——零注入、零 token 增量、
 * 与"没有本单"时逐字节一致（拼接后长度 0，不产生任何"空行/空戳"）。
 */
internal object FictionMode {

    /** 锁结设定谱 → 六行机制描述（只出机制与现象，不出步骤体位技巧）。 */
    fun lockLines(lock: LockCanon): List<String> {
        val d = lock.depthStage ?: error("init 已保证成对非空")
        val p = lock.lockPoint ?: error("init 已保证成对非空")
        return listOf(
        FictionOutput.canonLine("depthStage", "${d.stage}·${d.cn}"),
        FictionOutput.canonLine("lockPoint", p.cn),
        FictionOutput.canonLine("lockSource", lock.lockSource.cn),
        FictionOutput.canonLine("lockDuration", lock.lockDuration.cn),
        FictionOutput.canonLine("releaseCondition", lock.releaseCondition.cn),
        FictionOutput.canonLine("firmnessDuringLock", lock.firmnessDuringLock.cn),
        )
    }

    /** 双套型拓扑 → 四约束读数行。 */
    fun topologyLines(t: DualSuiteTopology): List<String> = listOf(
        FictionOutput.canonLine("outlets", t.outlets.joinToString(";") { "${it.id}@${it.opening}" }),
        FictionOutput.canonLine("supplyClaims", t.claims.joinToString(";") { "${it.system}-${it.channel}-${it.segment}" }),
        FictionOutput.canonLine("heightBand.male", t.maleBand.cn),
        FictionOutput.canonLine("heightBand.female", t.femaleBand.cn),
    )

    /**
     * 题材模式这一轮要注入的行（唯一装配口）。
     *
     * 开关关 ⇒ 恒空表（判据⑥：零注入、零行为变化；判据⑦：逐字节一致）。
     * 开关开 ⇒ 只出带设定戳的行（判据④：与事实可区分），并整体过一遍缺戳自检。
     */
    fun lines(
        lock: LockCanon?,
        topology: DualSuiteTopology?,
        switchOn: Boolean,
    ): List<String> {
        if (!switchOn) return emptyList()
        val out = ArrayList<String>(10)
        lock?.let { out += lockLines(it) }
        topology?.let { out += topologyLines(it) }
        FictionOutput.assertAllMarked(out)
        return out
    }

    /** 注入字节数（判据⑥的"token 增量 0"用行数与字节数双口径自证）。 */
    fun injectedBytes(lines: List<String>): Int =
        lines.fold(0) { acc, l -> acc + l.toByteArray(Charsets.UTF_8).size }
}
