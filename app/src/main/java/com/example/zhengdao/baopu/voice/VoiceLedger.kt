// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/**
 * 第六百三十三批 · 语音输入账（append-only JSONL，落在 App 私有 filesDir）。
 *
 * **账只记三样**：时长、识别来源、是否成功。不记文本、不记路径、不记音频、不记模型输出
 * ——「音频不进产物/账/日志」这条硬线在数据结构上就封死：一行最多三个键，没有第四列。
 * 房型照 trigger/TriggerLedger.kt：一处追加口、坏行跳过、写失败返回 false 由调用方说出来。
 */
internal class VoiceLedger(private val file: File) {

    data class Row(val durationMs: Long, val source: VoiceSource, val success: Boolean) {
        fun toJson(): String = JSONObject()
            .put("durationMs", durationMs)
            .put("source", source.wire)
            .put("success", success)
            .toString()

        companion object {
            /** 允许的键闭集：新增任何一列都必须先过派工单口径。 */
            val KEYS = setOf("durationMs", "source", "success")

            fun fromJson(line: String): Row? = runCatching {
                val o = JSONObject(line)
                val src = when (o.optString("source")) {
                    VoiceSource.SYSTEM.wire -> VoiceSource.SYSTEM
                    VoiceSource.LOCAL.wire -> VoiceSource.LOCAL
                    else -> return null
                }
                Row(o.optLong("durationMs", 0L), src, o.optBoolean("success", false))
            }.getOrNull()?.takeIf { it.durationMs >= 0L }
        }
    }

    /** 追加一条；false = 没落成（调用方要落日志，不许静默）。 */
    fun append(row: Row): Boolean = synchronized(LOCK) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(row.toJson() + "\n", Charsets.UTF_8)
        }.onFailure { RunLog.log("语音输入：账追加失败 ${it.javaClass.simpleName}") }.isSuccess
    }

    fun rows(): List<Row> = synchronized(LOCK) {
        if (!file.isFile) return@synchronized emptyList()
        runCatching { file.readLines(Charsets.UTF_8) }
            .getOrDefault(emptyList())
            .takeLast(MAX_ROWS)
            .mapNotNull { Row.fromJson(it) }
    }

    companion object {
        const val FILE_NAME = "语音输入.jsonl"
        const val DIR_NAME = "voice"
        const val MAX_ROWS = 5_000
        private val LOCK = VoiceLedger::class.java
    }
}
