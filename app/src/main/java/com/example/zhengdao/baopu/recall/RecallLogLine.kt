// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **一行读数**（纯函数：只允许计数，用户原话一个字都不许进日志）。
//
// 为什么单独成件：日志行写在控制器里时，最容易顺手把 `text`/`keywords` 插进去——
// 那是用户亲口说的话。这里把可插的值收成四类计数（线索组数／检索次数／要点条数／
// 索引行数）＋一个布尔，`RecallSourceGateTest` 用源码扫描钉「本包日志插值只许计数」。
package com.example.zhengdao.baopu.recall

internal object RecallLogLine {

    /**
     * 一次唤回的读数行。索引未建/读不出时 [indexMessages] 传 -1（如实说「不知道」，
     * 不写 0 冒充「索引里一条都没有」）。
     */
    fun of(outcome: RecallEngine.Outcome, indexMessages: Long, usedFallback: Boolean): String =
        "模糊唤回：线索 ${outcome.clueGroupCount} 组·检索 ${outcome.searchCalls} 次" +
            "·要点 ${outcome.points.size} 条·索引 $indexMessages 条" +
            "·兜底 ${if (usedFallback) "是" else "否"}"
}
