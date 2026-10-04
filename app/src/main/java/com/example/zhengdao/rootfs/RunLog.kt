// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志（用户第三批调整）：
 * - 位置移到 cacheDir/runlog/（设置 → 存储占用里的 cache 分类，用户可整体清理）；
 * - 自动清理规则：App 启动时检查上一次的日志——**无错误标记 → 直接删除**，
 *   有错误 → 保留为 zhengdao-log.prev.txt 供查看（只留一代，新的覆盖旧的）；
 * - 错误标记：行内含「错误 / 失败 / 异常 / FATAL / Exception / error」。
 */
object RunLog {

    private const val MAX_BYTES = 512 * 1024
    private const val NAME = "zhengdao-log.txt"
    private const val PREV_NAME = "zhengdao-log.prev.txt"
    private val ERROR_MARKERS = listOf("错误", "失败", "异常", "FATAL", "Exception", "error")

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        migrateLegacy(context)
        cleanupIfClean()
    }

    private fun dir(context: Context): File = File(context.cacheDir, "runlog").apply { mkdirs() }

    /** 上一次日志的善后：干净 → 删除；有错 → 挪到 .prev（覆盖旧 .prev）。 */
    private fun cleanupIfClean() {
        val ctx = appContext ?: return
        try {
            val current = File(dir(ctx), NAME)
            if (!current.isFile) return
            if (current.length() == 0L) {
                current.delete()
                return
            }
            val hasError = current.readLines().any { line ->
                ERROR_MARKERS.any { line.contains(it, ignoreCase = true) }
            }
            if (hasError) {
                File(dir(ctx), PREV_NAME).delete()
                current.renameTo(File(dir(ctx), PREV_NAME))
            } else {
                current.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /** 上一次会话是否保留了错误日志（设置页提示「上次运行有错误」用）。 */
    fun lastRunHadErrors(context: Context): Boolean {
        val prev = File(dir(context), PREV_NAME)
        return prev.isFile && prev.length() > 0L
    }

    fun log(line: String) {
        val ctx = appContext ?: return
        try {
            val f = File(dir(ctx), NAME)
            if (f.length() > MAX_BYTES) {
                // 简单轮转：保留后半段
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText("…（旧日志已截断）…\n$keep")
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $line\n")
        } catch (_: Throwable) {
        }
    }

    fun file(): File? = appContext?.let { File(dir(it), NAME) }?.takeIf { it.isFile }

    fun prevFile(): File? = appContext?.let { File(dir(it), PREV_NAME) }?.takeIf { it.isFile }

    fun readAll(): String = file()?.readText() ?: "（暂无日志）"

    /** 迁移旧位置（files/zhengdao-log.txt）到新家：老用户升级后旧错误日志不丢。 */
    fun migrateLegacy(context: Context) {
        try {
            val legacy = File(context.filesDir, NAME)
            if (legacy.isFile) {
                val target = File(dir(context), PREV_NAME)
                if (!target.isFile) legacy.renameTo(target)
                legacy.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
