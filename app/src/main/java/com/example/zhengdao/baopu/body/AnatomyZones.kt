// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十九批 · 形轨.解剖分区表（13 区）加载器。
// 真源＝docs/facts/anatomy-zones.json（669 词表误写与 660 形体共用，不复制第二份）；
// 口径正文见 docs/facts/anatomy-zones.md（双真相不许漂，同一提交一起改）。
// 纯解析，零模型、零联网、零随机；缺字段/空表/悬空引用 ⇒ 抛错（fail-closed）。
package com.example.zhengdao.baopu.body

import java.io.File
import org.json.JSONObject

/** 与其它区的关系：相邻区 id 列表＋方位口径（背腹/前后/左右）。 */
data class ZoneRelations(val adjacent: List<String>, val position: String)

/** 一个解剖分区：教材级口径，非素材正文。 */
data class AnatomyZone(
    val zoneId: String,
    val name: String,
    val latin: String,
    val aliases: List<String>,
    val observable: List<String>,
    val commonErrors: List<String>,
    val relations: ZoneRelations,
)

/** 13 区口径表的唯一加载口。解析是纯函数；读盘只发生在 load（调用方给仓根）。 */
object AnatomyZones {

    /** 仓内相对路径——660 将来也指这里，不许另存一份。 */
    const val REL_PATH = "docs/facts/anatomy-zones.json"

    fun load(repoRoot: File): List<AnatomyZone> = parse(File(repoRoot, REL_PATH).readText())

    /** 解析＋完整性闸：schema 对、表非空、id 唯一、字段齐、相邻引用不悬空。 */
    fun parse(json: String): List<AnatomyZone> {
        val root = JSONObject(json)
        require(root.optString("schema") == "anatomy-zones/v1") { "schema 必须是 anatomy-zones/v1" }
        val arr = root.getJSONArray("zones")
        val zones = (0 until arr.length()).map { zoneOf(arr.getJSONObject(it)) }
        require(zones.isNotEmpty()) { "分区表为空，拒绝加载（fail-closed）" }
        val ids = zones.map { it.zoneId }.toSet()
        require(ids.size == zones.size) { "zone_id 重复，拒绝加载" }
        for (z in zones) {
            for (a in z.relations.adjacent) {
                require(a in ids) { "${z.zoneId} 的相邻引用悬空：$a" }
            }
        }
        return zones
    }

    private fun zoneOf(o: JSONObject): AnatomyZone {
        val rel = o.getJSONObject("relations")
        return AnatomyZone(
            zoneId = reqText(o, "zone_id"),
            name = reqText(o, "name"),
            latin = reqText(o, "latin"),
            aliases = reqList(o, "aliases"),
            observable = reqList(o, "observable"),
            commonErrors = reqList(o, "common_errors"),
            relations = ZoneRelations(reqList(rel, "adjacent"), reqText(rel, "position")),
        )
    }

    private fun reqText(o: JSONObject, key: String): String {
        val v = o.optString(key)
        require(v.isNotBlank()) { "缺字段或为空：$key" }
        return v
    }

    private fun reqList(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key)
        require(arr != null) { "缺字段：$key" }
        val items = (0 until arr.length()).map { arr.getString(it) }
        require(items.isNotEmpty() && items.all { it.isNotBlank() }) { "字段 $key 须为非空字符串列表" }
        return items
    }
}