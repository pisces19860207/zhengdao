// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.browser.offscreen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 每格读数的结局。工单钉死的三类失败（回调超时／尺寸为 0／白图判定失败）各有独立值，
 * 另加三类"诚实补格"：LOAD_ERROR（主框架加载失败——原因进 detail，不冒充白图）、
 * EXCEPTION（draw 等环节抛了异常，原文进 detail）、DEBUG_DISABLED（非 debug 构建被拒）。
 */
enum class ProbeOutcome {
    OK,
    ONPAGE_TIMEOUT,
    VISUAL_TIMEOUT,
    RESIZE_TIMEOUT,
    SIZE_0,
    UNIFORM_WHITE,
    UNIFORM_BLANK,
    LOAD_ERROR,
    EXCEPTION,
    DEBUG_DISABLED,
}

/** 一格读数：一个模式 × 一个 URL 的实测结果。 */
data class ProbeModeResult(
    val mode: String,
    val url: String,
    val outcome: ProbeOutcome,
    val detail: String?,
    val viewW: Int,
    val viewH: Int,
    val bmpW: Int,
    val bmpH: Int,
    val elapsedMs: Long,
    val pngName: String?,
    val pngSha256: String?,
    val dominantColor: String?,
    val dominantRatio: Double?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("url", url)
        put("outcome", outcome.name)
        detail?.let { put("detail", it) }
        put("viewPx", "$viewW x $viewH")
        put("bmpPx", "$bmpW x $bmpH")
        put("elapsedMs", elapsedMs)
        pngName?.let { put("png", it) }
        pngSha256?.let { put("pngSha256", it) }
        dominantColor?.let { put("dominantColor", it) }
        dominantRatio?.let { put("dominantRatio", it) }
    }

    fun line(): String =
        "[$mode] $url -> $outcome ${detail ?: ""} view=${viewW}x$viewH bmp=${bmpW}x$bmpH ${elapsedMs}ms png=$pngName"
}

/**
 * 整份台账：机型指纹 + 全部格子。每落一格就重写一次 probe_result.json——
 * 中途崩了，已落的格不丢（证据纪律：先落盘再谈结论）。
 */
data class ProbeReport(
    val batch: String,
    val startedAtIso: String,
    val device: JSONObject,
    val outputDir: File,
) {
    private val rows = mutableListOf<ProbeModeResult>()

    val results: List<ProbeModeResult> get() = rows

    fun add(row: ProbeModeResult) {
        rows.add(row)
        write()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("batch", batch)
        put("startedAt", startedAtIso)
        put("writtenAt", isoNow())
        put("device", device)
        put("outputDir", outputDir.absolutePath)
        val arr = JSONArray()
        rows.forEach { arr.put(it.toJson()) }
        put("results", arr)
    }

    fun write() {
        File(outputDir, "probe_result.json").writeText(toJson().toString(2))
    }

    companion object {
        /** JSON 报告的时间戳（ISO-8601 带时区偏移）。B-补-24：不 new SimpleDateFormat，用 java.time。 */
        private val isoFmt: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.US)

        fun isoNow(): String =
            isoFmt.withZone(ZoneId.systemDefault()).format(Instant.now())
    }
}
