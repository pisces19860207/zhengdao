// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/**
 * 第六百三十三批 · 语音输入账（append-only JSONL，落在 App 私有 filesDir）。
 *
 * **普通行只记三样**：时长、识别来源、是否成功。不记文本、不记路径、不记音频、不记模型输出
 * ——「音频不进产物/账/日志」这条硬线在数据结构上就封死：普通行最多三个键。
 * 房型照 trigger/TriggerLedger.kt：一处追加口、坏行跳过、写失败返回 false 由调用方说出来。
 *
 * 第七百批加了两列**只给探测行**：[Row.callbacks] 与 [Row.conclusion]。
 * 派工单要求「账里记 {来源, 时长, 回调计数0, 结论}」——它们仍然是元数据，
 * 不含一个字节的音频、也不含识别文本；普通行照旧只有三列，旧读数不被改写。
 */
internal class VoiceLedger(private val file: File) {

    data class Row(
        val durationMs: Long,
        val source: VoiceSource,
        val success: Boolean,
        /** 探测行才有：窗口内收到的回调数（0 = 一个都没来）。 */
        val callbacks: Int? = null,
        /** 探测行才有：结论闭集词（见 [VoiceProbe.SILENT] / [VoiceProbe.ANSWERED]）。 */
        val conclusion: String? = null,
    ) {
        /** 这一行是不是探测记录（判据③要看的就是「有且只有一行探测记录」）。 */
        val isProbe: Boolean get() = callbacks != null && conclusion != null

        fun toJson(): String {
            val o = JSONObject()
                .put("durationMs", durationMs)
                .put("source", source.wire)
                .put("success", success)
            callbacks?.let { o.put("callbacks", it) }
            conclusion?.let { o.put("conclusion", it) }
            return o.toString()
        }

        companion object {
            /** 普通行的键闭集：新增任何一列都必须先过派工单口径。 */
            val KEYS = setOf("durationMs", "source", "success")

            /** 探测行 = 普通三列 ＋ 回调计数 ＋ 结论；除此之外没有第六列。 */
            val PROBE_KEYS = KEYS + setOf("callbacks", "conclusion")

            fun fromJson(line: String): Row? = runCatching {
                val o = JSONObject(line)
                val src = when (o.optString("source")) {
                    VoiceSource.SYSTEM.wire -> VoiceSource.SYSTEM
                    VoiceSource.LOCAL.wire -> VoiceSource.LOCAL
                    else -> return null
                }
                val callbacks = if (o.has("callbacks")) o.optInt("callbacks", 0) else null
                val conclusion = if (o.has("conclusion")) o.optString("conclusion") else null
                Row(o.optLong("durationMs", 0L), src, o.optBoolean("success", false), callbacks, conclusion)
            }.getOrNull()?.takeIf { it.durationMs >= 0L && (it.callbacks ?: 0) >= 0 }
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
