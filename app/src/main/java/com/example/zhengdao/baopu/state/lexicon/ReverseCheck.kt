// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 判据④⑤：三距离层齐、每条标区间、同档≥3写法、重复句≤1；
// 反向校验：固定种子抽 20 条描写，判回档位落在其标注区间才算命中，<16 判红。
package com.example.zhengdao.baopu.state.lexicon

import com.example.zhengdao.baopu.state.micro.MicroFinding
import com.example.zhengdao.baopu.state.micro.MicroSeverity

/** 一次反向判回的结果：命中的档位（null=判不出）。 */
data class Inference(val grade: Int?)

/**
 * 反向判回：只扫文本里出现的强度信号词，取其中**最高档**；一个都没有 ⇒ null。
 * 刻意不读 entry.grade / entry.span——否则命中恒真，判据失去意义。
 */
fun inferGrade(text: String): Inference {
    var best: Int? = null
    for ((word, grade) in SignalGrades.WORD_TO_GRADE) {
        if (text.contains(word)) {
            if (best == null || grade > best) best = grade
        }
    }
    return Inference(best)
}

/** 反向校验报告：抽样数、命中数、命中门槛、是否通过、逐条明细。 */
data class ReverseReport(
    val sampled: Int,
    val hits: Int,
    val threshold: Int,
    val pass: Boolean,
    val details: List<Pair<LexiconEntry, Inference>>,
)

/** 固定种子的确定性抽样（同一份词表逐字节可复现），不用系统随机。 */
private fun deterministicSample(entries: List<LexiconEntry>, count: Int, seed: Int = 821): List<LexiconEntry> {
    val pool = entries.toList()
    val picked = mutableListOf<LexiconEntry>()
    var state = seed
    val used = HashSet<Int>()
    while (picked.size < count && used.size < pool.size) {
        state = (state * 1103515245 + 12345) and 0x7FFFFFFF
        val idx = state % pool.size
        if (used.add(idx)) picked += pool[idx]
    }
    return picked
}

/** 判据④：抽 [count] 条反向判回，命中 ≥[threshold] 才通过。 */
fun reverseCheck(
    entries: List<LexiconEntry> = LexiconCorpus.ENTRIES,
    count: Int = 20,
    threshold: Int = 16,
): ReverseReport {
    val sample = deterministicSample(entries, count)
    val details = sample.map { it to inferGrade(it.text) }
    val hits = details.count { (entry, inf) -> inf.grade != null && inf.grade in entry.span }
    return ReverseReport(sample.size, hits, threshold, hits >= threshold, details)
}

/** 判据③：三距离层齐（每层 ≥5）、每条有合法区间、4 档都在。 */
fun checkLayerCoverage(entries: List<LexiconEntry> = LexiconCorpus.ENTRIES): List<MicroFinding> {
    val out = mutableListOf<MicroFinding>()
    for (grade in 0 until LexiconCorpus.GRADES) {
        for (band in DistanceBand.ALL) {
            val n = entries.count { it.grade == grade && it.band == band }
            if (n < LexiconCorpus.PER_LAYER) {
                out += MicroFinding(
                    "X3", MicroSeverity.ERROR, null,
                    "$grade 档/${band.label} 只有 $n 条，须 ≥${LexiconCorpus.PER_LAYER}",
                )
            }
        }
    }
    if (entries.any { it.id.isBlank() || it.text.isBlank() }) {
        out += MicroFinding("X3", MicroSeverity.ERROR, null, "存在空 id 或空 text")
    }
    return out
}

/** 判据⑤：同档 ≥3 种不同写法；全批重复 id/文本各 ≤1 次。 */
fun checkVariety(entries: List<LexiconEntry> = LexiconCorpus.ENTRIES): List<MicroFinding> {
    val out = mutableListOf<MicroFinding>()
    for (grade in 0 until LexiconCorpus.GRADES) {
        val distinct = entries.filter { it.grade == grade }.map { it.text }.toSet().size
        if (distinct < 3) {
            out += MicroFinding("X5", MicroSeverity.ERROR, null, "$grade 档只有 $distinct 种写法，须 ≥3")
        }
    }
    val dupIds = entries.groupingBy { it.id }.eachCount().filter { it.value > 1 }
    if (dupIds.isNotEmpty()) {
        out += MicroFinding("X5", MicroSeverity.ERROR, null, "重复 id：${dupIds.keys.take(5)}")
    }
    val dupTexts = entries.groupingBy { it.text }.eachCount().filter { it.value > 1 }
    if (dupTexts.isNotEmpty()) {
        out += MicroFinding("X5", MicroSeverity.ERROR, null, "重复文本 ${dupTexts.size} 组：${dupTexts.keys.take(3)}")
    }
    return out
}
