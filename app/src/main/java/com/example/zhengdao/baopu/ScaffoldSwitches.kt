package com.example.zhengdao.baopu

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.core.RunLog

internal enum class ScaffoldSwitch(val key: String) {
    HISTORY_PREFILTER("zhengdao-scaffold/history-prefilter"),
    COMPRESSION_PROMPT("zhengdao-scaffold/compression-prompt"),
}

internal object ScaffoldSwitches {

    private const val PREFS_NAME = "zhengdao-scaffold"

    fun historyPrefilterEnabled(context: Context): Boolean =
        preferences(context).getBoolean(ScaffoldSwitch.HISTORY_PREFILTER.key, true)

    fun compressionPromptEnabled(context: Context): Boolean =
        preferences(context).getBoolean(ScaffoldSwitch.COMPRESSION_PROMPT.key, true)

    fun historySkipIds(enabled: Boolean, skipIds: Set<String>): Set<String> =
        if (enabled) skipIds else emptySet()

    fun shouldRunCompression(enabled: Boolean, needsCompression: Boolean): Boolean =
        enabled && needsCompression

    fun set(
        context: Context,
        switch: ScaffoldSwitch,
        enabled: Boolean,
        actor: String,
        reason: String,
    ) {
        require(actor.isNotBlank()) { "脚手架开关变更必须记录操作者" }
        require(reason.isNotBlank()) { "脚手架开关变更必须记录原因" }
        // 写后读回校验：比 commit() 的布尔更实在（KTX edit 不返回结果，读回才能证明真的落了）。
        preferences(context).edit(commit = true) { putBoolean(switch.key, enabled) }
        check(preferences(context).getBoolean(switch.key, !enabled) == enabled) {
            "脚手架开关写入失败：${switch.key}"
        }
        RunLog.log(
            "脚手架开关变更：${switch.key}=$enabled；操作者=${oneLine(actor)}；" +
                "时间=${System.currentTimeMillis()}；原因=${oneLine(reason)}",
        )
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun oneLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ').trim()
}
