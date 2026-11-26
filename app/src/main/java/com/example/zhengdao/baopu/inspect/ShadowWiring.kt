// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.inspect

import java.io.File

internal object ShadowWiring {

    fun <T> onRunSettled(
        enabledProvider: () -> Boolean,
        root: () -> File? = { null },
        round: () -> Long? = { null },
        feeds: () -> List<T>? = { null },
        run: (File, Long, List<T>) -> Int,
        log: (String) -> Unit = ::println,
    ): Int {
        if (!enabledProvider()) return 0
        val settledRound = round()
        val settledFeeds = feeds()
        if (settledRound == null || settledFeeds == null) {
            val missing = listOfNotNull(
                if (settledRound == null) "round" else null,
                if (settledFeeds == null) "feeds" else null,
            )
            log("[shadow] outcome=degraded missing=[${missing.joinToString(", ")}] wrote=0")
            return 0
        }
        return guarded(true, root(), settledRound, settledFeeds, run, log)
    }

    internal fun <T> guarded(
        enabled: Boolean,
        root: File?,
        round: Long?,
        feeds: List<T>?,
        run: (File, Long, List<T>) -> Int,
        log: (String) -> Unit = ::println,
    ): Int {
        if (!enabled) return 0
        if (root == null || round == null || feeds == null) {
            val missing = listOfNotNull(
                if (root == null) "root" else null,
                if (round == null) "round" else null,
                if (feeds == null) "feeds" else null,
            )
            log("[shadow] outcome=degraded missing=[${missing.joinToString(", ")}] wrote=0")
            return 0
        }
        val wrote = run(root, round, feeds)
        log("[shadow] ran wrote=$wrote")
        return wrote
    }
}
