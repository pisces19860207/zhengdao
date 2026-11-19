// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百三十九批 · 三化抑制：风格阈值基线加载器。
// 真源＝docs/facts/anti-slop-baseline.json（带 source/asOf）；同一份阈值供三条规则共用，
// 检测器内不许写死任何阈值或词表。纯解析，零模型、零联网、零随机、零时钟；
// schema 不符 / 字段缺 / 空表 / 阈值非正 / source 或 asOf 空 ⇒ 抛错（fail-closed）。
package com.example.zhengdao.baopu.antislop

import java.io.File
import org.json.JSONObject

/** 统计窗口（字数基数）：hedge 每 N 字、blacklist 每 N 字、四字词每 N 字。 */
data class SlopWindow(val hedgeChars: Int, val blacklistChars: Int, val fourCharChars: Int)

/** A 尺寸通胀：夸大词 / 稀有度标记 / 来源 id 标记 / 默认值区间与单位换算。 */
data class ARules(
    val inflationMarkers: List<String>,
    val rarityMarkers: List<String>,
    val sourceMarkers: List<String>,
    val defaultCues: List<String>,
    val defaultValuePattern: String,
    val mmPerCm: Double,
    val mmPerMm: Double,
    val defaultBandLowMm: Double,
    val defaultBandHighMm: Double,
    val source: String,
    val asOf: String,
)

/** B 医学免责腔：hedge 词表 / 密度上限 / 连续段数 / 建议禁词 / 全局免责句。 */
data class BRules(
    val hedges: List<String>,
    val maxHedgePer100: Double,
    val consecutiveHedgeParagraphs: Int,
    val adviceBanWords: List<String>,
    val globalDisclaimer: String,
    val maxGlobalDisclaimer: Int,
    val source: String,
    val asOf: String,
)

/** C 模板腔：黑名单 / 段尾总结标记 / 四字格清单。 */
data class CRules(
    val blacklist: List<String>,
    val maxBlacklistPer500: Double,
    val summaryMarkers: List<String>,
    val maxSummaryRate: Double,
    val fourCharLexicon: List<String>,
    val fourCharMaxPer100: Double,
    val source: String,
    val asOf: String,
)

/** 一份完整的三化抑制基线。source/asOf 必填，随阈值一起走。 */
data class AntiSlopBaseline(
    val schema: String,
    val source: String,
    val asOf: String,
    val window: SlopWindow,
    val a: ARules,
    val b: BRules,
    val c: CRules,
)

/** 基线唯一加载口。parse 为纯函数；读盘只发生在 load（调用方给仓根/目录）。 */
object AntiSlopBaselines {

    const val REL_PATH = "docs/facts/anti-slop-baseline.json"
    private const val SCHEMA = "anti-slop-baseline/v1"

    fun load(repoRoot: File): AntiSlopBaseline = parse(File(repoRoot, REL_PATH).readText())

    fun parse(json: String): AntiSlopBaseline {
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "schema 必须是 $SCHEMA" }
        val source = reqText(root, "source")
        val asOf = reqText(root, "asOf")
        val window = windowOf(root.getJSONObject("window"))
        val a = aOf(root.getJSONObject("a"))
        val b = bOf(root.getJSONObject("b"))
        val c = cOf(root.getJSONObject("c"))
        require(window.hedgeChars > 0 && window.blacklistChars > 0 && window.fourCharChars > 0) {
            "统计窗口必须为正"
        }
        require(a.defaultBandLowMm <= a.defaultBandHighMm) { "默认值区间倒置" }
        return AntiSlopBaseline(SCHEMA, source, asOf, window, a, b, c)
    }

    private fun windowOf(o: JSONObject) = SlopWindow(
        hedgeChars = reqInt(o, "hedgeChars"),
        blacklistChars = reqInt(o, "blacklistChars"),
        fourCharChars = reqInt(o, "fourCharChars"),
    )

    private fun aOf(o: JSONObject) = ARules(
        inflationMarkers = reqList(o, "inflationMarkers"),
        rarityMarkers = reqList(o, "rarityMarkers"),
        sourceMarkers = reqList(o, "sourceMarkers"),
        defaultCues = reqList(o, "defaultCues"),
        defaultValuePattern = reqText(o, "defaultValuePattern"),
        mmPerCm = reqNum(o, "mmPerCm"),
        mmPerMm = reqNum(o, "mmPerMm"),
        defaultBandLowMm = reqNum(o, "defaultBandLowMm"),
        defaultBandHighMm = reqNum(o, "defaultBandHighMm"),
        source = reqText(o, "source"),
        asOf = reqText(o, "asOf"),
    )

    private fun bOf(o: JSONObject) = BRules(
        hedges = reqList(o, "hedges"),
        maxHedgePer100 = reqNum(o, "maxHedgePer100"),
        consecutiveHedgeParagraphs = reqInt(o, "consecutiveHedgeParagraphs"),
        adviceBanWords = reqList(o, "adviceBanWords"),
        globalDisclaimer = reqText(o, "globalDisclaimer"),
        maxGlobalDisclaimer = reqInt(o, "maxGlobalDisclaimer"),
        source = reqText(o, "source"),
        asOf = reqText(o, "asOf"),
    )

    private fun cOf(o: JSONObject) = CRules(
        blacklist = reqList(o, "blacklist"),
        maxBlacklistPer500 = reqNum(o, "maxBlacklistPer500"),
        summaryMarkers = reqList(o, "summaryMarkers"),
        maxSummaryRate = reqNum(o, "maxSummaryRate"),
        fourCharLexicon = reqList(o, "fourCharLexicon"),
        fourCharMaxPer100 = reqNum(o, "fourCharMaxPer100"),
        source = reqText(o, "source"),
        asOf = reqText(o, "asOf"),
    )

    private fun reqText(o: JSONObject, key: String): String {
        val v = o.optString(key)
        require(v.isNotBlank()) { "缺字段或为空：$key" }
        return v
    }

    private fun reqNum(o: JSONObject, key: String): Double {
        require(o.has(key)) { "缺字段：$key" }
        val v = o.getDouble(key)
        require(v.isFinite()) { "字段非有限：$key" }
        return v
    }

    private fun reqInt(o: JSONObject, key: String): Int {
        require(o.has(key)) { "缺字段：$key" }
        return o.getInt(key)
    }

    private fun reqList(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: error("缺字段：$key")
        val items = (0 until arr.length()).map { arr.getString(it) }
        require(items.isNotEmpty() && items.all { it.isNotBlank() }) { "字段 $key 须为非空字符串列表" }
        return items
    }
}
