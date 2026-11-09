// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.testkit

import java.io.File
import java.nio.file.Files

/**
 * 564（2026-11-09）：测试临时目录的**唯一出口**——建了必删，删不干净必响。
 *
 * ## 为什么要有它
 *
 * 全仓测试里有 163 处以 `Files.createTempDirectory(…)` 建目录、60 处以 `createTempFile(…)`
 * 建文件，另有一批直接用 `File("build/tmp/…")` 写相对路径（如
 * `baopu/inspect/DataRepairSweepTest.kt:28`，每跑一次就留一棵树在 `app/build/tmp/` 下）。
 * 这些目录落在 Gradle 给测试 JVM 的临时根（`app/build/tmp/testDebugUnitTest`）或模块
 * 的 `build/tmp/` 里：单次无害，几轮之后 `app/build` 就攒出成千上万个小文件，
 * 磁盘吃紧、`clean` 变慢、CI 缓存变大，而且**没有任何一道闸会因此变红**。
 *
 * 本类的做法是把它变成一条**有退出条件的约定**：
 *
 * ```kotlin
 * TempSandbox.use("zd491-disco") { dir ->
 *     val db = File(dir, "ws.db")
 *     // …把被测逻辑指向 dir，随便造文件…
 * }   // 到这里 dir 连同下面的一切都被递归删掉；删不掉就抛异常
 * ```
 *
 * ## 纪律
 *
 * - **`use` 结束即删**：正常返回、抛异常、`AssumptionViolatedException`（跳过）三条路
 *   都走 `finally` 语义，全部会删（见 [useResult] 的 `Result` 收口）。
 * - **删不干净就抛**：还剩哪怕一项也不静默通过——静默正是这条账长期没人还的原因。
 *   若 block 自身抛了异常，清理异常以 `addSuppressed` 附上，**原异常仍是主因**，
 *   不改变「哪个断言失败」的判读。
 * - **不碰生产**：本类只在 `app/src/test`，Android 仪器测试（`androidTest`）看不到它，
 *   那边用设备侧目录，不走这套。
 * - **不猜路径**：不做「清空某个父目录」这种危险动作，只删自己 [create] 出来的那一个目录。
 */
object TempSandbox {

    /** 默认前缀；`Files.createTempDirectory` 要求前缀不短于 3 个字符，见 [sanitize]。 */
    const val DEFAULT_PREFIX: String = "zd-test"

    /** 删不掉时的最大重试轮数（Windows 上被杀毒/索引短暂占用是常态，不是缺陷）。 */
    private const val DELETE_ATTEMPTS = 3

    /** 重试之间的等待（毫秒）。取值够短，不拖慢测试；够长，能等过瞬时句柄释放。 */
    private const val DELETE_RETRY_SLEEP_MS = 30L

    /**
     * 建一个沙盒目录交给 [block]，退出时**递归删干净**。
     *
     * 返回值被丢弃版本；需要把 block 的返回值带出来用 [useResult]。
     */
    fun use(prefix: String = DEFAULT_PREFIX, block: (File) -> Unit) {
        useResult(prefix, block)
    }

    /**
     * 同 [use]，但把 [block] 的返回值带出来。
     *
     * 顺序是刻意的：**先跑完 block，再删，最后才抛异常**——
     * 这样「block 自己失败」与「清理失败」两件事不会互相掩盖，
     * 且无论哪条路，磁盘上都不会留下这个沙盒。
     */
    fun <T> useResult(prefix: String = DEFAULT_PREFIX, block: (File) -> T): T {
        val dir = create(prefix)
        val outcome: Result<T> = try {
            Result.success(block(dir))
        } catch (t: Throwable) {
            // 捕获后在下方统一收口：不改写异常类型、不包装，只补 suppressed（若有）。
            Result.failure(t)
        }
        val leftover = deleteTree(dir)
        if (leftover > 0) {
            val leak = IllegalStateException(
                "TempSandbox 没删干净：${dir.absolutePath} 还剩 $leftover 项（564 测试沙盒）"
            )
            val original = outcome.exceptionOrNull()
            if (original != null) {
                original.addSuppressed(leak)
            } else {
                throw leak
            }
        }
        return outcome.getOrThrow()
    }

    /**
     * 在系统临时根下建一个新目录。
     *
     * 测试 JVM 的系统临时根由 Gradle 指向 `app/build/tmp/<task>`，所以这里建出来的目录
     * 就是 `tools/check-test-temp.py` 盯的那片区域——建了不删会被它数出来。
     */
    fun create(prefix: String = DEFAULT_PREFIX): File =
        Files.createTempDirectory(sanitize(prefix)).toFile()

    /**
     * 前缀清洗：只留 `[A-Za-z0-9._-]`，其余替换成 `-`；空／过短回落到 [DEFAULT_PREFIX]。
     *
     * 为什么不用中文前缀：临时目录名会出现在失败日志与 CI 注解里，
     * 而 Windows 控制台与部分日志通道对非 ASCII 文件名不友好（564 实测：中文前缀能建，
     * 但在失败输出里会变成乱码，读不出是哪个用例漏的）。**清洗而不是报错**：
     * 前缀是可读性辅助，不该让一个测试因为名字不合规而红。
     */
    fun sanitize(prefix: String): String {
        val cleaned = prefix.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")
        val trimmed = cleaned.trim('-', '.').take(40)
        if (trimmed.length >= 3) return trimmed
        val padded = (trimmed + "---").take(3)
        return if (padded.isNotEmpty()) padded else DEFAULT_PREFIX
    }

    /**
     * 递归删 [root]，返回**删除后仍存在**的项数（含 root 自身；0 ＝ 删干净了）。
     *
     * 只做三件事：删、把只读项改可写再删、最多重试 [DELETE_ATTEMPTS] 轮。
     * 不跟随符号链接去删外部内容——`File.deleteRecursively` 遇链接按文件删。
     */
    fun deleteTree(root: File): Int {
        var attempt = 0
        while (root.exists() && attempt < DELETE_ATTEMPTS) {
            root.deleteRecursively()
            if (root.exists()) {
                root.walkBottomUp().forEach { item ->
                    if (item.exists() && !item.canWrite()) item.setWritable(true)
                }
                if (attempt + 1 < DELETE_ATTEMPTS) {
                    Thread.sleep(DELETE_RETRY_SLEEP_MS)
                }
            }
            attempt++
        }
        if (!root.exists()) return 0
        return root.walkTopDown().count()
    }
}
