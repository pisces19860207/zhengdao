// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百九十八批 · 段 B（教模型出口）：注入层数据模型。
// 边界：教材只在注入层出现——本包全部 internal，不改段 A 的 TextbookEntry schema，
//       不新增对外字段/类型，不碰判据/阈值/参数（段 C 一律不做）。
package com.example.zhengdao.baopu.textbook.inject

import com.example.zhengdao.baopu.textbook.Domain
import java.time.LocalDate

/** 教材条目的教学侧：正例 / 反例。反例块必须显式标记，禁止与正例混排。 */
internal enum class TeachSide { POSITIVE, NEGATIVE }

/** 本轮命中上下文：关键词（部位/scope 词）、限定域、今天（判过期用）。纯值，无 IO。 */
internal data class TeachContext(
    val keywords: List<String>,
    val domains: List<Domain> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

/** 一条进入注入块的教材——文字来自教材库原文，side/reason 由命中层判定，绝不编正文。 */
internal data class TeachingLesson(
    val id: String,
    val text: String,
    val source: String,
    val asOf: String,
    val side: TeachSide,
    val reason: String,
)

/**
 * 一次教材注入的可断言结果。
 * - [lines]：进本轮 ContextAssembler 的正文块（正例块 + 反例块，各自连续、不混排）。
 * - [explain]：`[teach] ids / tokens / reason` 一行可解释日志——落日志，不进 prompt。
 * - [miss]：教材缺失/无命中 ⇒ true，lines 为空（空块），explain 记 miss，不编内容。
 * - [mixed]：混排自检——恒 false 是生产不变量；测试喂混排输入时由 [TextbookInject.detectMixed] 咬红。
 */
internal data class TeachInjection(
    val lines: List<String>,
    val explain: String,
    val hitIds: List<String>,
    val tokenCost: Int,
    val hitCount: Int,
    val miss: Boolean,
    val mixed: Boolean,
) {
    companion object {
        fun empty(reason: String): TeachInjection = TeachInjection(
            lines = emptyList(),
            explain = "[teach] ids=[] / tokens=0 / reason=$reason",
            hitIds = emptyList(),
            tokenCost = 0,
            hitCount = 0,
            miss = true,
            mixed = false,
        )
    }
}
