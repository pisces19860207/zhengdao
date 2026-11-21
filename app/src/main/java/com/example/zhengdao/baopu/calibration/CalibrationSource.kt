// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十八批 · 校准取数层（**不是检索**：只把 711/719/723 已有真源解析成带来源的条目）。
//
// 三条纪律：
//   ① 来源齐：每条必带 source + asOf，缺一即丢（判据③，缺源不硬凑）；
//   ② 复用：facts 走 719 FactPackWords.parse（它已实现缺源跳过），变体走 723 VariantTable.parse；
//      本文件不新写任何打分/召回/RAG 检索实现（判据⑦）；
//   ③ 不造内容：正文 clip 只借 722 ReadbackLines.clip，文字原样来自真源，不编。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.pack.FactPackWords
import com.example.zhengdao.baopu.state.readback.ReadbackLines
import com.example.zhengdao.baopu.state.variant.VariantTable
import org.json.JSONObject

/** 一条可注入条目：来源 id + 正文（会被 clip）+ source + asOf。 */
internal data class SourceEntry(
    val id: String,
    val text: String,
    val source: String,
    val asOf: String,
) {
    /** 来源齐：source 与 asOf 都非空才算可用（判据③）。 */
    val hasSource: Boolean get() = source.isNotBlank() && asOf.isNotBlank()
}

/** 取数出口：把真源 JSON 解析成条目；不做 query 检索、不做排序召回。 */
internal object CalibrationSource {

    /** 711 observable-index.json：entries[] 每条带 id/topic/source/asOf。 */
    fun fromObservableIndex(json: String): List<SourceEntry> {
        val arr = runCatching { JSONObject(json).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        val out = ArrayList<SourceEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                SourceEntry(
                    id = id,
                    text = o.optString("topic").trim(),
                    source = o.optString("source").trim(),
                    asOf = o.optString("asOf").trim(),
                ),
            )
        }
        return out
    }

    /** 719 fact-slices.json：直接复用其解析器（kind/source/asOf/body 校验已在那里）。 */
    fun fromFactSlices(json: String): List<SourceEntry> =
        runCatching { FactPackWords.parse(json) }.getOrDefault(emptyList()).map {
            SourceEntry(id = it.id, text = it.body, source = it.source, asOf = it.asOf)
        }

    /** 723 variants.json：取一个默认维度的档位名（塌缩请求用，确保多档不齐给同一档）。 */
    fun variantLevels(json: String, min: Int = 2): List<String> {
        val table = runCatching { VariantTable.parse(json) }.getOrNull() ?: return emptyList()
        val dim = table.defaultDims.firstOrNull() ?: return emptyList()
        val names = dim.levels.map { it.name }
        return if (names.distinct().size >= min) names.distinct().take(4) else emptyList()
    }

    /** 来源过滤：丢掉 source/asOf 缺失的条目；判据③靠它，负例是"无源仍注入"。 */
    fun usable(entries: List<SourceEntry>): List<SourceEntry> = entries.filter { it.hasSource }

    /** 正文 clip：复用 722 唯一裁法，不另造截断逻辑。 */
    fun clip(text: String): String = ReadbackLines.clip(text, MAX_ENTRY_CHARS)

    const val MAX_ENTRY_CHARS = 60
}
