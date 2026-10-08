// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import android.content.Context
import com.example.zhengdao.terminal.Store
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志（用户第三批调整；**2026-10-08 搬到公共区**）。
 *
 * 用户原话（2026-10-08）：
 * 「还有运行日志，错误日志，下载的东西都放到 download 证道 文件夹里……」
 *
 * 位置：**`Download/证道/logs/`**（`Store.logsDir`），三个文件：
 * - `zhengdao-log.txt`：**本轮运行**的日志（App 每次启动时上一轮的会被善后，见下）；
 * - `zhengdao-log.prev.txt`：上一轮**有错误**才会保留的那一份（只留一代）；
 * - `errors.log`：跨轮次的**错误汇总**（只追加含错误标记的行，用户不用在几千行里翻）。
 *
 * 为什么从 `cacheDir/runlog` 搬出来：那在应用私有目录里，用户**根本打不开**——
 * 报障时只能靠截图，而截图里没有日志。搬进公共区之后，用户/Agent 都能直接读，
 * 也能在我们说"往上翻"的时候真的翻到。
 *
 * 自动清理规则（保持原语义，不放松也不收紧）：
 * - App 启动时检查上一轮的日志——**无错误标记 → 直接删除**，有错误 → 保留为 `.prev`
 *   （只留一代，新的覆盖旧的）；
 * - ⚠️ **同一进程只善后一次**：`init()` 在三处被调用（ZhengdaoApp / MainActivity /
 *   TerminalActivity），旧实现每次 init 都会"善后一遍"，于是第二个 init 会把**本轮
 *   刚开始写的日志**当成上一轮的、发现它还没错误就删掉——正是"日志动不动就没了"的
 *   隐藏原因。现在用一个进程内标志兜住。
 * - 错误标记：行内含「错误 / 失败 / 异常 / FATAL / Exception / error」。
 *
 * 私有兜底：公共目录不可用（没存储权限 / 仅私有模式）时退回 `cacheDir/runlog`，
 * 功能不断；一旦公共目录可用，下次 init 会把私有那份**搬过去**（幂等，不丢老日志）。
 */
object RunLog {

    private const val MAX_BYTES = 512 * 1024
    private const val NAME = "zhengdao-log.txt"
    private const val PREV_NAME = "zhengdao-log.prev.txt"
    private const val ERRORS_NAME = "errors.log"
    private val ERROR_MARKERS = listOf("错误", "失败", "异常", "FATAL", "Exception", "error")

    private var appContext: Context? = null

    /** 已解析的日志目录（只在公共目录可用时缓存——不可用时每次重试，拿到权限就切过去）。 */
    @Volatile
    private var resolvedDir: File? = null

    /** 上一轮日志的善后只做一次（见类注释：多次 init 会把本轮日志误删）。 */
    @Volatile
    private var settled = false

    fun init(context: Context) {
        appContext = context.applicationContext
        val ctx = appContext ?: return
        val d = dir(ctx)
        migrateLegacy(ctx, d)
        if (!settled) {
            settled = true
            cleanupIfClean(d)
        }
    }

    /**
     * 日志目录：公共区优先（`Download/证道/logs`），不可用时私有 `cacheDir/runlog`。
     * 切到公共区时把私有那份老日志搬过来（幂等）。
     */
    private fun dir(context: Context): File {
        resolvedDir?.let { if (it.isDirectory) return it }
        val pub = runCatching { Store.logsDir(context) }.getOrNull()?.takeIf { it.isDirectory }
        if (pub == null) return File(context.cacheDir, "runlog").apply { mkdirs() }
        adoptPrivate(context, pub)
        resolvedDir = pub
        return pub
    }

    /** 把私有目录里的历史日志搬进公共区（一次成功即完成；失败下次再试）。 */
    private fun adoptPrivate(context: Context, pub: File) {
        try {
            Store.adoptDir(pub, File(context.cacheDir, "runlog"))
            Store.adoptFile(File(pub, PREV_NAME), File(context.filesDir, NAME))
        } catch (_: Throwable) {
        }
    }

    /** 上一次日志的善后：干净 → 删除；有错 → 挪到 .prev（覆盖旧 .prev）。 */
    private fun cleanupIfClean(d: File) {
        try {
            val current = File(d, NAME)
            if (!current.isFile) return
            if (current.length() == 0L) {
                current.delete()
                return
            }
            val hasError = current.readLines().any { line ->
                ERROR_MARKERS.any { line.contains(it, ignoreCase = true) }
            }
            if (hasError) {
                File(d, PREV_NAME).delete()
                current.renameTo(File(d, PREV_NAME))
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
        val ctx = appContext
        if (ctx == null) {
            // ⚠️ 绝不静默丢弃：拿不到 context 时至少进 logcat。
            //    「失败可见」的前提是日志存在——丢进黑洞**比不记日志更糟**，
            //    因为它会让人误判成"这段分支没执行 / 异常没触发"，
            //    本次就因此把 SSE 断连归因错了两轮。
            android.util.Log.w("zhengdao/RunLog", "[未落盘] $line")
            return
        }
        try {
            val d = dir(ctx)
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            appendRotated(File(d, NAME), "[$ts] $line\n")
            // 错误汇总：跨轮次保留，用户在设置页点开就能看到"最近出过什么事"
            if (ERROR_MARKERS.any { line.contains(it, ignoreCase = true) }) {
                appendRotated(File(d, ERRORS_NAME), "[$ts] $line\n")
            }
        } catch (e: Throwable) {
            // 落盘失败同样不能静默——否则又是一个黑洞
            android.util.Log.w("zhengdao/RunLog", "[落盘失败] $line | ${e.message}")
        }
    }

    /** 追加 + 超限轮转（保留后半段）。 */
    private fun appendRotated(f: File, text: String) {
        if (f.length() > MAX_BYTES) {
            val keep = f.readText().takeLast(MAX_BYTES / 2)
            f.writeText("…（旧日志已截断）…\n$keep")
        }
        f.appendText(text)
    }

    fun file(): File? = appContext?.let { File(dir(it), NAME) }?.takeIf { it.isFile }

    fun prevFile(): File? = appContext?.let { File(dir(it), PREV_NAME) }?.takeIf { it.isFile }

    /** 跨轮次错误汇总（可能不存在——没有任何错误时本来也不该有）。 */
    fun errorsFile(): File? = appContext?.let { File(dir(it), ERRORS_NAME) }?.takeIf { it.isFile }

    fun readAll(): String = file()?.readText() ?: "（暂无日志）"

    /** 日志目录的真实路径（设置页展示用：告诉用户"东西在哪"，而不是让他去猜）。 */
    fun dirPath(context: Context): String = dir(context).absolutePath

    /** 迁移旧位置（`files/zhengdao-log.txt`）到新家：老用户升级后旧错误日志不丢。 */
    fun migrateLegacy(context: Context, d: File = dir(context)) {
        try {
            val target = File(d, PREV_NAME)
            Store.adoptFile(target, File(context.filesDir, NAME))
        } catch (_: Throwable) {
        }
    }
}
