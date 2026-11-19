// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十三批 · 变异谱接入：变体数据模型与唯一真源解析。
// 只存维度名/档位名/概率/reality，不存谱内正文（隐私与体积边界）。
// 与 731 分工：本包管"这个人天生的变体"，731 管"此刻的状态"。
package com.example.zhengdao.baopu.state.variant

import org.json.JSONObject
import java.io.File

/** 现实性标记：real（真实变异）／fiction（虚构设定）。 */
enum class Reality(val tag: String) {
    REAL("real"),
    FICTION("fiction"),
    ;

    companion object {
        fun of(tag: String): Reality = values().firstOrNull { it.tag == tag }
            ?: throw IllegalArgumentException("reality 取值非法：$tag（只许 real/fiction）")
    }
}

/** 一个档位：名 + 人群概率(0..100 的整数) + 现实性 + 可选提示。缺 probability/reality 即拒收。 */
data class Level(
    val key: String,
    val name: String,
    val probability: Int,
    val reality: Reality,
    val note: String? = null,
) {
    init {
        require(key.isNotBlank()) { "档位 key 不得为空" }
        require(name.isNotBlank()) { "档位名不得为空" }
        require(probability in 0..100) { "档位概率须在 0..100：$key=$probability" }
    }
}

/**
 * 一个维度：名 + 档位表 + 是否病理 + 默认是否参与采样。
 * [defaultEnabled]=false 的维度（病理）默认不参与采样，须显式开启。
 */
data class VariantDim(
    val key: String,
    val name: String,
    val levels: List<Level>,
    val pathological: Boolean = false,
    val defaultEnabled: Boolean = !pathological,
) {
    init {
        require(key.isNotBlank()) { "维度 key 不得为空" }
        require(name.isNotBlank()) { "维度名不得为空" }
        require(levels.size in MIN_LEVELS..MAX_LEVELS) {
            "维度 $key 档位数须在 $MIN_LEVELS..$MAX_LEVELS，得 ${levels.size}"
        }
        require(levels.map { it.key }.toSet().size == levels.size) { "维度 $key 档位 key 重复" }
        require(levels.sumOf { it.probability } == 100) { "维度 $key 概率未归一到 100" }
    }

    companion object {
        const val MIN_LEVELS = 3
        const val MAX_LEVELS = 5
    }
}

/** 整张变体表：带来源与时点（可追溯），维度按 key 排序保证确定性。 */
data class VariantTable(
    val source: String,
    val asOf: String,
    val dimensions: List<VariantDim>,
) {
    init {
        require(source.isNotBlank()) { "变体表缺 source，拒收" }
        require(asOf.isNotBlank()) { "变体表缺 asOf，拒收" }
        require(dimensions.size >= MIN_DIMS) { "维度数须 ≥ $MIN_DIMS，得 ${dimensions.size}" }
        require(dimensions.map { it.key }.toSet().size == dimensions.size) { "维度 key 重复" }
    }

    val byKey: Map<String, VariantDim> = dimensions.associateBy { it.key }
    val defaultDims: List<VariantDim> = dimensions.filter { it.defaultEnabled }

    companion object {
        const val REL_PATH = "docs/facts/variants.json"
        const val SCHEMA = "variant-spectrum/v1"
        const val MIN_DIMS = 8

        fun load(repoRoot: File): VariantTable = parse(File(repoRoot, REL_PATH).readText(Charsets.UTF_8))

        /** 严格解析：schema/source/asOf 齐全；每维档位 3–5；每档 probability+reality 缺一拒收。 */
        fun parse(json: String): VariantTable {
            val root = JSONObject(json)
            require(root.optString("schema") == SCHEMA) { "schema 不符：${root.optString("schema")}" }
            val source = root.optString("source")
            val asOf = root.optString("asOf")
            require(source.isNotBlank()) { "变体表缺 source，拒收" }
            require(asOf.isNotBlank()) { "变体表缺 asOf，拒收" }
            val arr = root.getJSONArray("dimensions")
            val dims = (0 until arr.length()).map { i -> parseDim(arr.getJSONObject(i)) }
            return VariantTable(source, asOf, dims)
        }

        private fun parseDim(o: JSONObject): VariantDim {
            val key = o.getString("key")
            val name = o.getString("name")
            val pathological = o.optBoolean("pathological", false)
            val defaultEnabled = if (o.has("defaultEnabled")) o.getBoolean("defaultEnabled") else !pathological
            val levelsArr = o.getJSONArray("levels")
            val levels = (0 until levelsArr.length()).map { i -> parseLevel(levelsArr.getJSONObject(i)) }
            return VariantDim(key, name, levels, pathological, defaultEnabled)
        }

        private fun parseLevel(o: JSONObject): Level {
            val key = o.getString("key")
            val name = o.getString("name")
            // 判据①：probability 与 reality 缺一拒收——用 get 而非 opt，缺字段直接抛。
            val probability = o.getInt("probability")
            val reality = Reality.of(o.getString("reality"))
            val note = if (o.has("note")) o.getString("note") else null
            return Level(key, name, probability, reality, note)
        }
    }
}
