// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import android.content.Context
import androidx.core.content.edit

/**
 * 第六百三十三批 · 语音输入的两项设置（自建 SharedPreferences，房型照 AppearancePrefs）。
 *
 * 默认：系统 ASR ＋ 点按开始结束。非法值一律回落到默认，不新造第三档。
 * 本地模型未装时若存档仍是 LOCAL，也不在这里偷偷改成 SYSTEM —— 由 [VoiceGate] 明确
 * 灰掉并给原因，避免「看着选了本地、其实走系统」的静默降级。
 */
internal object VoicePrefs {
    const val FILE_NAME = "zhengdao-voice"
    const val KEY_MODE = "voice_mode"
    const val KEY_SOURCE = "voice_source"

    fun normalizeMode(raw: String?): VoiceMode = when (raw) {
        VoiceMode.HOLD.wire -> VoiceMode.HOLD
        VoiceMode.TAP.wire -> VoiceMode.TAP
        else -> VoiceMode.TAP
    }

    fun normalizeSource(raw: String?): VoiceSource = when (raw) {
        VoiceSource.LOCAL.wire -> VoiceSource.LOCAL
        VoiceSource.SYSTEM.wire -> VoiceSource.SYSTEM
        else -> VoiceSource.SYSTEM
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): VoiceMode = normalizeMode(
        runCatching { prefs(ctx).getString(KEY_MODE, null) }.getOrNull(),
    )

    fun setMode(ctx: Context, mode: VoiceMode) {
        runCatching { prefs(ctx).edit { putString(KEY_MODE, mode.wire) } }
    }

    fun getSource(ctx: Context): VoiceSource = normalizeSource(
        runCatching { prefs(ctx).getString(KEY_SOURCE, null) }.getOrNull(),
    )

    fun setSource(ctx: Context, source: VoiceSource) {
        runCatching { prefs(ctx).edit { putString(KEY_SOURCE, source.wire) } }
    }
}
