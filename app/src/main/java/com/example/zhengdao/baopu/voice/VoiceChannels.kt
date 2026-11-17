// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第七百批 · 识别通道目录与「切换识别通道」入口的判据（纯 JVM）。
 *
 * 背景：系统通道（MagicVoice）在本机是死的，而 633 留下的 LOCAL 档只有外壳
 * （模型装没装是一条线，本地引擎接没接上是**另一条**线）。第七条判据要的是：
 * **693（本地识别通道真实现）没合入之前，切换入口必须灰着并注明原因**，
 * 不许点下去什么都不发生，也不许偷偷回退到系统通道。
 *
 * 所以这里不写 `false` 散在各处，只留一个开关位 [LOCAL_CHANNEL_WIRED]：
 * 693 合入时改这一行并接上真引擎，界面自动亮起。
 */
internal object VoiceChannels {

    /**
     * 693（本地识别通道）是否已经接上。
     * 未合入 ⇒ 切换入口只做展示、点不动，旁边写明「还没接上」。
     */
    const val LOCAL_CHANNEL_WIRED = false

    /** 只有两档，所以「换一条」就是「另一档」；不新造第三档。 */
    fun other(current: VoiceSource): VoiceSource = when (current) {
        VoiceSource.SYSTEM -> VoiceSource.LOCAL
        VoiceSource.LOCAL -> VoiceSource.SYSTEM
    }

    /**
     * 切换入口的状态：能不能点、点不了是因为「没接上」还是「没装模型」。
     * 灰着必须带一句人话（[VoiceGuide] 同一条纪律：不许静默失败）。
     */
    fun switchFrom(
        current: VoiceSource,
        localInstalled: Boolean,
        localWired: Boolean = LOCAL_CHANNEL_WIRED,
    ): ChannelSwitch {
        val target = other(current)
        val block = when {
            target == VoiceSource.SYSTEM -> SwitchBlock.NONE
            !localWired -> SwitchBlock.LOCAL_NOT_WIRED
            !localInstalled -> SwitchBlock.LOCAL_NOT_INSTALLED
            else -> SwitchBlock.NONE
        }
        return ChannelSwitch(target, block == SwitchBlock.NONE, block)
    }
}

/** 切换入口的拦路原因闭集；[NONE] 才允许点。 */
internal enum class SwitchBlock { NONE, LOCAL_NOT_WIRED, LOCAL_NOT_INSTALLED }

/** 一条切换入口的读数：[target] 是切过去的那一档，[block] 是灰掉的原因。 */
internal data class ChannelSwitch(
    val target: VoiceSource,
    val enabled: Boolean,
    val block: SwitchBlock,
)

/**
 * 连续的失败 streak：派工单④——**连着两次没成就不再自动重试，只留手动入口**。
 *
 * 为什么单独拎出来：这条规则若散在界面里，就会变成「某处偷偷重起一轮」；
 * 写成纯函数之后，判据可以直接钉「第 2 次之后 autoRetryAllowed 必须是 false」，
 * 而手动入口（点麦克风 / 点切换通道）不受它限制。
 */
internal object VoiceRetryPolicy {

    /** 自动重试的上限：到 2 次就停手。 */
    const val LIMIT = 2

    /** 自动重试还允许吗；到 [LIMIT] 即止（手动入口不受影响）。 */
    fun autoRetryAllowed(streak: Int): Boolean = streak < LIMIT

    /** 一次收口后的新 streak：成功（failure = null）清零，失败累加。 */
    fun nextStreak(previous: Int, failure: VoiceFailure?): Int =
        if (failure == null) 0 else previous + 1

    /** 是否该把「不再自动重试，请手动再试」那句常驻出来。 */
    fun gaveUp(streak: Int): Boolean = !autoRetryAllowed(streak)
}
