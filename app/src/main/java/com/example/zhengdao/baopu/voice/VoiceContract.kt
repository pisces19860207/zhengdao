// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 语音输入的闭集契约。
 *
 * 口径（派工单 §一，不许加档）：识别来源只有两档——系统自带 ASR（默认）与本地模型
 * （可选、未装则灰掉）；不引入第三档、不引入云端通道、不申请设备租约。
 * 音频永不离开设备：App 侧不上传、不落盘、不写进账或日志。
 */

/** 识别来源闭集。 */
internal enum class VoiceSource(val wire: String) {
    SYSTEM("system"),
    LOCAL("local"),
}

/** 交互两模式（设置里选）。 */
internal enum class VoiceMode(val wire: String) {
    HOLD("hold"),
    TAP("tap"),
}

/** 不可用原因闭集：按钮灰掉时必须有话说，不许静默失败。 */
internal enum class VoiceGuide {
    NONE,
    PERMISSION_DENIED,
    SYSTEM_UNAVAILABLE,
    LOCAL_NOT_INSTALLED,
}

internal data class VoiceDecision(val enabled: Boolean, val guide: VoiceGuide)

internal object VoiceGate {

    /**
     * 启动前的唯一判据：权限先过，再按来源查能力。
     * 系统档必须先过 SpeechRecognizer.isRecognitionAvailable（生产实现负责传入真读数）。
     */
    fun decide(
        source: VoiceSource,
        permissionGranted: Boolean,
        systemAvailable: Boolean,
        localInstalled: Boolean,
    ): VoiceDecision {
        if (!permissionGranted) return VoiceDecision(false, VoiceGuide.PERMISSION_DENIED)
        return decideBySource(source, systemAvailable, localInstalled)
    }

    private fun decideBySource(
        source: VoiceSource,
        systemAvailable: Boolean,
        localInstalled: Boolean,
    ): VoiceDecision = when (source) {
        VoiceSource.SYSTEM -> decideReady(systemAvailable, VoiceGuide.SYSTEM_UNAVAILABLE)
        VoiceSource.LOCAL -> decideReady(localInstalled, VoiceGuide.LOCAL_NOT_INSTALLED)
    }

    private fun decideReady(ready: Boolean, guide: VoiceGuide): VoiceDecision =
        if (ready) VoiceDecision(true, VoiceGuide.NONE) else VoiceDecision(false, guide)

    /** 本地模型未装 ⇒ 设置项灰掉；不是隐藏，也不是静默回退到系统档。 */
    fun localOptionEnabled(localInstalled: Boolean): Boolean = localInstalled

    /** 判据②的判别器：取消后输入框必须严格等于开始前内容（留半截即 false）。 */
    fun cancelRestores(before: String, after: String): Boolean = before == after
}
