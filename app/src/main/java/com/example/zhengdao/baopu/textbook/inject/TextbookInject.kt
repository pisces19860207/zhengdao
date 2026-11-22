// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百九十八批 · 段 B · 分块层：命中条目 → 本轮注入块（纯函数，零 IO／零模型／零闸）。
// 四条硬纪律：
//   ① 预算：教材块 ≤300 token（TokenEstimator，全系统唯一尺子），与 722 三行互不挤占；
//   ② 分块：正例块、反例块各自连续，绝不与正例混排；反例块显式标"这是反例"；
//   ③ 可解释：每次产出一行 [teach] ids / tokens / reason；miss 也记，绝不编内容；
//   ④ 只影响本轮：本层只产文本，装配层不落库、不改规则，下一轮不注即回基线。
package com.example.zhengdao.baopu.textbook.inject

import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.baopu.state.readback.ReadbackLines

/** 教材块生成核心。常量即判据阈值；测试用派工单硬数（300/60）独立断言，不引用本常量自证。 */
internal object TextbookInject {

    const val MAX_TEACH_TOKENS = 300
    const val MAX_LESSON_CHARS = 60
    const val MAX_LESSONS = 8

    const val POSITIVE_TAG = "[教材·正例]"
    const val NEGATIVE_TAG = "[教材·反例·这是反例]"
    const val EXPLAIN_TAG = "[teach]"

    /**
     * @param lessons 命中条目（已带 side/reason）；空表 ⇒ 返回 [TeachInjection.empty]（miss 空块）。
     */
    fun build(lessons: List<TeachingLesson>): TeachInjection {
        if (lessons.isEmpty()) return TeachInjection.empty("miss（教材缺失或无命中）")
        val picked = withinBudget(lessons)
        if (picked.isEmpty()) return TeachInjection.empty("miss（预算内无可用条目）")

        val positives = picked.filter { it.side == TeachSide.POSITIVE }
        val negatives = picked.filter { it.side == TeachSide.NEGATIVE }
        val lines = ArrayList<String>()
        positives.forEach { lines += render(it, POSITIVE_TAG) }
        negatives.forEach { lines += render(it, NEGATIVE_TAG) }
        val tokens = TokenEstimator.estimate(lines.joinToString("\n"))
        val explain = "$EXPLAIN_TAG ids=${picked.joinToString(",") { it.id }} / " +
            "tokens=$tokens / reason=${picked.joinToString("；") { it.reason }}"
        return TeachInjection(
            lines = lines,
            explain = explain,
            hitIds = picked.map { it.id },
            tokenCost = tokens,
            hitCount = picked.size,
            miss = false,
            mixed = detectMixed(lines),
        )
    }

    /** 按原序取条目直到再加一条就超 300 token（超预算则跳到下一条，不截断正文、不编内容）。 */
    private fun withinBudget(lessons: List<TeachingLesson>): List<TeachingLesson> {
        val out = ArrayList<TeachingLesson>()
        var cost = 0
        for (lesson in lessons) {
            if (out.size >= MAX_LESSONS) break
            if (lesson.text.isBlank() || lesson.source.isBlank() || lesson.asOf.isBlank()) continue
            val line = render(lesson, tagOf(lesson.side))
            val next = TokenEstimator.estimate((out.map { render(it, tagOf(it.side)) } + line).joinToString("\n"))
            if (next > MAX_TEACH_TOKENS) continue
            out += lesson
            cost = next
        }
        return out
    }

    private fun tagOf(side: TeachSide): String = if (side == TeachSide.NEGATIVE) NEGATIVE_TAG else POSITIVE_TAG

    /** 单行：标签 + 正文（clip ≤60 字）+ 来源 + asOf，来源齐。 */
    private fun render(lesson: TeachingLesson, tag: String): String =
        "$tag ${ReadbackLines.clip(lesson.text, MAX_LESSON_CHARS)}｜来源:${lesson.source}｜asOf:${lesson.asOf}"

    /**
     * 混排自检：反例行出现在任一正例行之前（块序反了）⇒ true（红）。
     * 生产 build 先正后反恒 false；测试可喂混排行断言本函数咬得住。
     */
    fun detectMixed(lines: List<String>): Boolean {
        val firstNegative = lines.indexOfFirst { it.startsWith(NEGATIVE_TAG) }
        val lastPositive = lines.indexOfLast { it.startsWith(POSITIVE_TAG) }
        return firstNegative >= 0 && lastPositive >= 0 && firstNegative < lastPositive
    }
}
