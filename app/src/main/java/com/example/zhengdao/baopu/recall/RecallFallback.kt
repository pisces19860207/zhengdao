// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **索引未命中时的直读兜底**（纯组合，不碰 IO）。
//
// 为什么不是「索引查不到就直接说没找到」：533 的索引是**增量**的（水位往后补，
// ≤500 条/轮），升级后的头几次唤回只铺到最旧一批。这时索引空命中 ≠ 真的没有——
// 直读主库再确认一次，两条路都空才叫没找到。兜底复用**同一批线索组**（不新增线索），
// 所以「多线索检索 ≤3 次」仍成立（每轮各自 ≤3，兜底只是同题换一条读法）。
package com.example.zhengdao.baopu.recall

internal object RecallFallback {

    /** 一次唤回的最终结果 ＋ 走没走兜底（进日志读数，不影响卡面）。 */
    data class Result(
        val outcome: RecallEngine.Outcome,
        val usedFallback: Boolean,
    )

    /**
     * 索引结果优先；**只有零命中**才调 [fallback]（找到就不再多读一遍主库）。
     * [fallback] 抛异常不在这里吞——它内部（`RecallDbSearch`）本来就 fail-open 成空表，
     * 两层兜底各管一段，不互相掩盖。
     */
    fun run(primary: RecallEngine.Outcome, fallback: () -> RecallEngine.Outcome): Result =
        if (primary.found) {
            Result(primary, usedFallback = false)
        } else {
            Result(fallback(), usedFallback = true)
        }
}
