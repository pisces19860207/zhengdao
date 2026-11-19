// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十三批 · 变异谱接入：确定性加权采样器。
// 同 seed + 同参数 ⇒ 逐字节一致；固定维度作者指定则不抽样；病理维默认关。
package com.example.zhengdao.baopu.state.variant

import java.util.Random

/**
 * 一次采样结果。[chosen] 按维度 key 排序（LinkedHashMap 保序）⇒ 确定性遍历。
 * [advisories] 为随结果附带的提示（病理开启抽到 real 档时含"少见（reality=real）"）。
 */
data class VariantSample(
    val chosen: Map<String, Level>,
    val advisories: List<String>,
) {
    /** 逐字节一致的规范串：键序固定、档位 key 固定、提示原序。 */
    fun canonical(): String = buildString {
        chosen.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v.key).append(';') }
        append('|')
        advisories.forEach { append(it).append(';') }
    }

    fun levelOf(dimKey: String): Level? = chosen[dimKey]
}

/**
 * 确定性加权采样器。
 *
 * 随机源用 java.util.Random(seed)：其线性同余算法由 Java 规范固定，同 seed 跨 JVM 逐字节一致。
 * 每维由主种子派生独立子种子，故固定某维不影响其他维的抽样序列。
 * 每个维度按 [VariantDim.levels] 的 probability 落入累计区间；[fixed] 指定的维度直接取档不抽样。
 */
object VariantSampler {

    /** 固定维度：dimKey → levelKey。作者指定则此维不抽样，但会校验档位存在。 */
    data class Options(
        val seed: Long = DEFAULT_SEED,
        val fixed: Map<String, String> = emptyMap(),
        val includePathological: Boolean = false,
    ) {
        companion object {
            const val DEFAULT_SEED = 0L
        }
    }

    /**
     * 对一张表采样。默认只采 [VariantTable.defaultDims]；[Options.includePathological]=true 时
     * 连病理维一起采。固定维度不存在 / 档位不存在 / 档位不属于该维 ⇒ 拒收。
     */
    fun sample(table: VariantTable, options: Options = Options()): VariantSample {
        val dims = table.dimensions
            .filter { options.includePathological || it.defaultEnabled }
            .sortedBy { it.key }
        // 主种子为每维派生独立子种子（按维度 key 的确定性哈希）：固定某维不影响其他维的序列。
        val master = Random(options.seed)
        val subSeeds = dims.associate { it.key to master.nextLong() }

        val chosen = LinkedHashMap<String, Level>()
        for (dim in dims) {
            val fixedKey = options.fixed[dim.key]
            val level: Level
            if (fixedKey != null) {
                level = dim.levels.firstOrNull { it.key == fixedKey }
                    ?: throw IllegalArgumentException("固定档位不存在：${dim.key}=$fixedKey")
            } else {
                val draw = Random(subSeeds.getValue(dim.key)).nextDouble()
                level = pickWeighted(dim.levels, draw)
            }
            chosen[dim.key] = level
        }
        validateFixedCovered(options.fixed, chosen.keys)
        val advisories = advisoriesOf(chosen, options)
        return VariantSample(chosen, advisories)
    }

    /** 固定维度若指向未参与采样的维（如默认关的病理维），拒收——防止"指定了个寂寞"。 */
    private fun validateFixedCovered(fixed: Map<String, String>, chosenKeys: Set<String>) {
        val missing = fixed.keys - chosenKeys
        require(missing.isEmpty()) { "固定维度未参与采样（病理维需显式开启）：${missing.sorted()}" }
    }

    private fun advisoriesOf(chosen: Map<String, Level>, options: Options): List<String> {
        val out = ArrayList<String>()
        if (options.includePathological) {
            val rareReal = chosen.values.filter { it.reality == Reality.REAL && it.note != null }
            rareReal.mapNotNull { it.note }.distinct().forEach { out.add(it) }
        }
        return out
    }

    /** 概率落入累计区间；draw∈[0,1)。表概率已校验归一到 100。 */
    private fun pickWeighted(levels: List<Level>, draw: Double): Level {
        val target = draw * 100.0
        var acc = 0.0
        for (level in levels) {
            acc += level.probability
            if (target < acc) return level
        }
        return levels.last() // 浮点兜底：draw 极接近 1 时落末档
    }
}
