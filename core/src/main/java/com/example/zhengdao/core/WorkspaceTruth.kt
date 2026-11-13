// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十五批（2026-11-13）：工作区「实态」的唯一判据与唯一留痕。
// 起因是 569 真机实测：SP 与抽屉都说 `Download/男性`，IO 却走在 App 私有外部目录
// （`Android/data/<包名>/files/workspace`），用户按显示的路径放素材 ⇒ App 看不到。
// 落点在 ERRATA 那条老话上：**回落本身不是 bug，回落了不说才是**。
package com.example.zhengdao.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作区实态：声明的那条路 vs 真在走的那条路。
 *
 * ## 为什么它在 `:core` 而不是抱朴那边
 *
 * 全 App 有**两个**工作区解析器（`:app` 的 `BaopuWorkspace` 管投递／产物／附件，
 * `:core` 的 `ZhengdaoDirs` 管资料库／备份），两边各有自己的 prefs 键。判据只要
 * 写两遍，就一定会在两处漂移——`EnvironmentScope`（第二百四十批消双判）是前例。
 * 所以这里只定义一次「背离怎么判、那一行长什么样、什么时候不许再记」，两处都来调。
 *
 * ## 分工（纪律照 `KernelAudit`）
 *
 * [line]／[diverged]／[leafOf] 是**纯函数**，不碰 Android、不落盘，JVM 单测直接断言；
 * [report] 才落盘，而且落点交给调用方传进来的 [sink]（生产传 `RunLog::log`），
 * 这样「只在实态变了时记一行」这条闸能被单测穷举，不必把 `:core` 的日志真跑起来。
 */
object WorkspaceTruth {

    /** 留痕锚：可 grep（`grep '\[ws\]' run_log.txt` 就能捞出每次工作区实态变化）。 */
    const val TAG = "[ws]"

    /** 没背离时给的那句（含糊不得：「一致」和「没查过」是两回事）。 */
    const val SAME = "与声明一致"

    /** 一条路算同一个目录：只把尾斜杠不当成两种写法，其余逐字比。 */
    private fun norm(path: String): String = path.trimEnd('/')

    /**
     * 实走的那条路是不是**不是**声明的那条（回落／建目录失败都算背离）。
     *
     * 空值先关在门外：任一侧没读数就没有「两条路」可比，说背离是假账。
     */
    fun diverged(realPath: String?, declaredPath: String?): Boolean {
        if (realPath.isNullOrEmpty() || declaredPath.isNullOrEmpty()) return false
        return norm(realPath) != norm(declaredPath)
    }

    /**
     * 「当前实路径」那一行。
     *
     * `声明=` 后面缺读数时写成「（无读数）」而不是省略字段——省略会让人读成
     * 「声明的就是实走的那条」，那正是本单要治的病。
     */
    fun line(source: String, realPath: String, declaredPath: String?, reason: String): String =
        "$TAG 当前实路径（$source）${one(realPath)}｜" +
            "声明=${one(declaredPath?.takeIf { it.isNotBlank() } ?: "（无读数）")}｜$reason"

    /** 显示名那一行的唯一口径：末级文件夹名，空串退回整条路径。 */
    fun leafOf(hostAbsolutePath: String): String =
        File(hostAbsolutePath).name.ifBlank { hostAbsolutePath }

    /**
     * 实态变了才落一行（[source] 分开记，两个解析器互不顶掉，也一起印在那一行里）。
     *
     * 为什么必须有这道闸：`hostDir` 是热路径（抽屉每拉一次、每个工具调用都要读），
     * 每次读都记一行 ⇒ 日志被同一句话灌满，真正要看的那次变化反而被冲走。
     * 判据是「上次报过的实走路径」，不是「上次报过的背离」：从背离回到一致也算实态变了，
     * 必须再说一次，否则授权恢复这个动作在日志里是隐形的。
     */
    fun report(
        source: String,
        realPath: String,
        declaredPath: String?,
        reason: String,
        sink: (String) -> Unit,
    ) {
        val last = lastReported[source]
        if (last != null && norm(last) == norm(realPath)) return
        lastReported[source] = realPath
        sink(line(source, realPath, declaredPath, reason))
    }

    /** 只在内存里记「上次报过的那条路」，不落盘、不跨进程（重启后第一行一定会出现）。 */
    private val lastReported = ConcurrentHashMap<String, String>()

    /** 测试用：把内存里的那点状态清掉，免得用例之间互相顶。 */
    fun resetReportedForTest() = lastReported.clear()

    private fun one(v: String): String = v.replace('\n', ' ').replace('\r', ' ').trim()
}
