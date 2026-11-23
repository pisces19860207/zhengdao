// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 词表↔档：每个档位配「近景/中景/远景」三层描写语料，每条标档位区间。
// 挂 669（词表与误写）：共用 docs/facts/anatomy-zones.json 的分区口径，不复制第二份词表。
// 只做「档→可用写法」的查表与反向判回，不做整段生成、不做文学评价、不装配、不进闸。
package com.example.zhengdao.baopu.state.lexicon

/** 描写距离三档：近景（贴身细节）/ 中景（姿态可见）/ 远景（氛围轮廓）。 */
enum class DistanceBand(val label: String) {
    NEAR("近景"),
    MID("中景"),
    FAR("远景"),
    ;

    companion object {
        const val COUNT = 3
        val ALL = values().toList()
    }
}

/** 档位区间 [lo, hi]（含端点）。判回档位落在此闭区间即算命中。 */
data class GradeSpan(val lo: Int, val hi: Int) {
    init {
        require(lo in 0..3 && hi in 0..3 && lo <= hi) { "档位区间非法：$lo..$hi" }
    }

    operator fun contains(grade: Int): Boolean = grade in lo..hi

    val label: String get() = if (lo == hi) "${lo}档" else "$lo–${hi}档"

    companion object {
        val G0 = GradeSpan(0, 1)
        val G1 = GradeSpan(1, 2)
        val G2 = GradeSpan(2, 3)
        val G3 = GradeSpan(3, 3)
    }
}

/** 一条词表语料：档位 + 区间 + 距离层 + 归属轴 + 描写文本。text 只做查表，不参与生成。 */
data class LexiconEntry(
    val id: String,
    val grade: Int,
    val span: GradeSpan,
    val band: DistanceBand,
    val axis: String,
    val text: String,
)

/**
 * 强度信号词 → 档位。反向判回的**独立**真相源：与语料的 grade 标注分离——
 * 判回函数只认这张表 + 文本里的词，绝不偷看 entry.grade，否则「判回命中」是恒真。
 */
object SignalGrades {
    val WORD_TO_GRADE: Map<String, Int> = mapOf(
        "平复" to 0, "毫无" to 0,
        "一丝" to 1, "隐隐" to 1, "微微" to 1,
        "明显" to 2, "渐盛" to 2, "加重" to 2,
        "剧烈" to 3, "猛地" to 3, "绷紧" to 3,
    )

    /** 判不出档的文本走这里：返回 null，算一次未命中。 */
    fun gradeOf(word: String): Int? = WORD_TO_GRADE[word]
}
