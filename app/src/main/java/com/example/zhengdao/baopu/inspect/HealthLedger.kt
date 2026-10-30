// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批（第二刀）· 体检自己的账：`体检记录.jsonl`，一行一份体检单。
//
// 为什么另立一本，不塞巡按记录：巡按记的是"六项查出来什么"，体检记的是"谁没在干活"——
// 两者分母不同（巡按的分母是被检记录，体检的分母是部位与工具）。混一本会让两边的读数互相污染。
//
// 三条纪律（与 279 那三本同款）：
//   1. **append-only**：一行一份，改了不覆盖（"当时它是这么判的"本身是证据）；
//   2. **留痕不留正文**：只存计数、状态、命中键与那一页单子的文本，不存任何用户内容；
//   3. **写失败不抛**：落账失败只记一行日志（照 208／影子账的口径），体检不能因为写账失败而崩。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一份体检单的账行。
 *
 * @param counts 四态各几条（键是 [OrganState.wire]；`unmeasured` 另记未取样）
 * @param organs 部位名 → 态线值（空串＝未取样）——**下一趟靠它判"连续两次零产出"**
 * @param hits 命中的键（`signal:wiring` / `blocker:ledger` 这种；没命中的不写）
 * @param headline 一句话结论（进决策中心那一条意见的 gist 就从这里来）
 * @param lines 单子全文（≤40 行；体检的产物就是它）
 */
internal data class HealthEntry(
    val id: String,
    val atMs: Long,
    val windowDays: Int,
    val counts: Map<String, Int>,
    val organs: Map<String, String>,
    val hits: List<String>,
    val headline: String,
    val lines: List<String>,
) {

    fun toJson(): JSONObject = JSONObject()
        .put("v", HealthLedger.VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("window", windowDays)
        .put("counts", mapJson(counts))
        .put("organs", mapJson(organs))
        .put("hits", JSONArray(hits))
        .put("headline", headline)
        .put("lines", JSONArray(lines))

    private fun mapJson(map: Map<String, *>): JSONObject {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    companion object {

        /** 认不出的行 ⇒ null（坏行不许当"上一趟没事"用）。 */
        fun parse(raw: String): HealthEntry? = runCatching {
            val o = JSONObject(raw)
            HealthEntry(
                id = o.getString("id"),
                atMs = o.getLong("at"),
                windowDays = o.optInt("window", HealthLimits.SPAN_DAYS),
                counts = o.optJSONObject("counts").toIntMap(),
                organs = o.optJSONObject("organs").toStringMap(),
                hits = o.optJSONArray("hits").toStringList(),
                headline = o.optString("headline"),
                lines = o.optJSONArray("lines").toStringList(),
            )
        }.getOrNull()

        private fun JSONObject?.toIntMap(): Map<String, Int> {
            if (this == null) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            keys().forEach { k -> out[k] = optInt(k) }
            return out
        }

        private fun JSONObject?.toStringMap(): Map<String, String> {
            if (this == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            keys().forEach { k -> out[k] = optString(k) }
            return out
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it) }
        }
    }
}

/** 体检账的读写（**只追加**；读侧带上界）。 */
internal object HealthLedger {

    const val FILE_NAME = "体检记录.jsonl"

    const val VERSION = 1

    /** 一次最多读几份（一个月一份，60 份＝5 年；再多也没人看）。 */
    const val READ_LIMIT = 60

    fun fileIn(root: File): File = File(root, FILE_NAME)

    /** 追加一份；返回有没有落上（落不上只记日志，不抛）。 */
    fun write(root: File, entry: HealthEntry): Boolean = runCatching {
        val f = fileIn(root)
        f.parentFile?.mkdirs()
        f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.onFailure {
        RunLog.log("御医体检：账没落上（${entry.id}）——${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    /** 读账（新→旧，最多 [limit] 份）。 */
    fun read(root: File, limit: Int = READ_LIMIT): List<HealthEntry> = runCatching {
        val f = fileIn(root)
        if (!f.isFile) return emptyList()
        f.readLines(Charsets.UTF_8)
            .mapNotNull(HealthEntry::parse)
            .takeLast(limit.coerceAtLeast(1))
            .reversed()
    }.getOrDefault(emptyList())

    /** 最近一次体检的时刻（null ＝ 从来没跑过）。 */
    fun lastAt(root: File): Long? = read(root, 1).firstOrNull()?.atMs

    /** 上一趟各部位的态（判"连续两次零产出"用；没账 ⇒ 空表＝第一次）。 */
    fun previousStates(root: File): Map<String, String> = read(root, 1).firstOrNull()?.organs.orEmpty()
}
