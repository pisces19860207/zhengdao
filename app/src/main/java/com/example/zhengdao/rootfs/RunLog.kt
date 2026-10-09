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
 * 位置：**`Download/证道/logs/`**（`Store.logsDir`），四类文件：
 * - `zhengdao-log.txt`：**本轮运行**的日志（App 每次启动时，上一轮的会被**归档**，见下）；
 * - `zhengdao-log.<yyyyMMdd-HHmmss>.txt`：**历次运行的存档**（每次启动归档一份，保留最近
 *   `KEEP_ARCHIVES` 份、总量不超过 `ARCHIVE_TOTAL_MAX`，超了从最旧的删）；
 * - `zhengdao-log.prev.txt`：**旧版**"只留一代错误日志"的产物（仍会被读取，不再新写）；
 * - `errors.log`：跨轮次的**错误汇总**（只追加含错误标记的行，用户不用在几千行里翻）。
 *
 * 为什么从 `cacheDir/runlog` 搬出来：那在应用私有目录里，用户**根本打不开**——
 * 报障时只能靠截图，而截图里没有日志。搬进公共区之后，用户/Agent 都能直接读，
 * 也能在我们说"往上翻"的时候真的翻到。
 *
 * 保留规则（**2026-10-08 用户拍板后改过语义**）：
 * 用户原话（2026-10-08）：「那些日志都是方便给你们这些 agent 看查哪里有问题的，所以要留着」。
 * ⇒ 旧行为"启动时上一轮**没出错就直接删掉**"与这句话直接冲突（干净的那一轮往往正是
 *   要对照的"正常长什么样"），已改成 **每次启动都把上一轮整份归档**，永不因为"没错误"而丢弃；
 *   只有体积/份数的上限会让你丢日志，且是从**最旧的一份**开始。
 * - ⚠️ **同一进程只归档一次**：`init()` 在三处被调用（ZhengdaoApp / MainActivity /
 *   TerminalActivity），旧实现每次 init 都会"善后一遍"，于是第二个 init 会把**本轮
 *   刚开始写的日志**当成上一轮的、发现它还没错误就删掉——正是"日志动不动就没了"的
 *   隐藏原因。现在用一个进程内标志兜住。
 * - 错误标记：行内含「错误 / 失败 / 异常 / FATAL / Exception / error」。
 *
 * 私有兜底：公共目录不可用（没存储权限 / 仅私有模式）时退回 `cacheDir/runlog`，
 * 功能不断；一旦公共目录可用，下次 init 会把私有那份**搬过去**（幂等，不丢老日志）。
 */
object RunLog {

    /** 单个日志文件的上限；超出丢最早的一半（保留后半段 + 一行"已截断"提示）。 */
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val ERRORS_MAX_BYTES = 1024L * 1024
    private const val NAME = "zhengdao-log.txt"
    private const val PREV_NAME = "zhengdao-log.prev.txt"
    private const val ERRORS_NAME = "errors.log"
    private const val ARCHIVE_PREFIX = "zhengdao-log."
    private const val ARCHIVE_SUFFIX = ".txt"

    /** 历史日志保留上限：份数与总量，先到先限（都从最旧的开始删）。 */
    private const val KEEP_ARCHIVES = 20
    private const val ARCHIVE_TOTAL_MAX = 20L * 1024 * 1024
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
            archivePrevious(d)
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

    /**
     * 上一轮日志的善后：**整份归档**成 `zhengdao-log.<时间戳>.txt`（用户 2026-10-08：
     * 「日志都是方便给你们这些 agent 看查哪里有问题的，所以要留着」）。
     *
     * 与旧实现的区别：旧版"没错误标记就 `delete()`"，等于每轮把正常运行的记录擦掉——
     * 而排查问题时"正常那轮长什么样"恰恰是最有用的对照。现在只有 [pruneArchivesIn]
     * 的体积/份数上限会让你丢日志，且从**最旧**的一份开始。
     */
    private fun archivePrevious(d: File) {
        try {
            val current = File(d, NAME)
            if (!current.isFile) return
            if (current.length() == 0L) {
                // 空文件没有任何信息量（比如刚启动就被杀），别占一个历史位
                current.delete()
                return
            }
            val at = current.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
            val dest = File(d, "$ARCHIVE_PREFIX$stamp$ARCHIVE_SUFFIX")
            if (dest.exists()) dest.delete()
            if (!current.renameTo(dest)) {
                // 跨文件系统 / 被占用时退回复制（宁可多一份，也别把日志丢了）
                current.copyTo(dest, overwrite = true)
                current.delete()
            }
            pruneArchivesIn(d)
        } catch (_: Throwable) {
        }
    }

    /** 历史日志（新 → 旧）。 */
    fun archives(): List<File> {
        val ctx = appContext ?: return emptyList()
        return listArchives(dir(ctx))
    }

    /** 最新一份历史日志（"上次运行"）。 */
    fun latestArchive(): File? = archives().firstOrNull()

    private fun listArchives(d: File): List<File> =
        d.listFiles { f -> f.isFile && isArchiveName(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 归档文件名判定（单测直接调）。 */
    internal fun isArchiveName(name: String): Boolean =
        name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX) &&
            name != NAME && name != PREV_NAME && name != ERRORS_NAME &&
            name.length > ARCHIVE_PREFIX.length + ARCHIVE_SUFFIX.length

    /**
     * 保留策略（纯文件操作，单测直接调）：最多 [keep] 份、总量不超过 [maxBytes]，
     * 超出的**从最旧的删**，返回删掉的份数。
     *
     * 两份约束的先后：先按份数砍掉 `drop(keep)` 那段（更旧的），再在剩下的里按总量
     * **继续从最旧的**删——于是无论哪条先触发，"留给人看的永远是最近几轮"。
     * 唯一的例外：最新那一份永远不删（哪怕它自己就超过 [maxBytes]），
     * 否则会退化成"一份都不剩"。
     */
    internal fun pruneArchivesIn(
        d: File,
        keep: Int = KEEP_ARCHIVES,
        maxBytes: Long = ARCHIVE_TOTAL_MAX,
    ): Int {
        val all = listArchives(d) // 新 → 旧
        if (all.isEmpty()) return 0
        val doomed = LinkedHashSet<File>()
        doomed += all.drop(keep.coerceAtLeast(1))
        var bytes = all.take(keep.coerceAtLeast(1)).sumOf { it.length() }
        for (f in all.take(keep.coerceAtLeast(1)).reversed()) { // 剩余里从最旧开始
            if (bytes <= maxBytes) break
            if (f == all.first()) break // 最新那份留着
            doomed += f
            bytes -= f.length()
        }
        var deleted = 0
        for (f in doomed) if (f.delete()) deleted++
        return deleted
    }

    /** 设置页的手动清理：只留最近 [keep] 份（用户主动点，不从后台偷偷删）。 */
    fun pruneArchivesKeep(context: Context, keep: Int): Int =
        runCatching { pruneArchivesIn(dir(context), keep = keep.coerceAtLeast(1)) }.getOrDefault(0)

    private fun hasError(f: File): Boolean = runCatching {
        f.useLines { lines -> lines.any { line -> ERROR_MARKERS.any { line.contains(it, true) } } }
    }.getOrDefault(false)

    /**
     * 上一次会话是否留下了错误（设置页提示「上次运行检测到错误」用）。
     * 读**最新一份归档**；老用户可能只有旧版留下的 `.prev.txt`，那条也认。
     */
    fun lastRunHadErrors(context: Context): Boolean {
        val d = runCatching { dir(context) }.getOrNull() ?: return false
        val latest = listArchives(d).firstOrNull()
        if (latest != null) return latest.length() > 0L && hasError(latest)
        val legacy = File(d, PREV_NAME)
        return legacy.isFile && legacy.length() > 0L && hasError(legacy)
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
            // #5（2026-10-09）：**同步镜像一份进 logcat**。
            // 为什么：进程被杀时文件里最后一行往往是"正常跑着"，而 logcat 环形缓冲里
            // 还留着死前那几十行。App 没有 READ_LOGS，只能读自己 UID 的日志——但只要
            // 我们把每一行都镜像进去，"自己那份"就是完整的。下一轮启动时
            // KeepaliveArchive.onStartup 会把这段抓成 logcat-boot-*.txt 存下来。
            android.util.Log.i("zhengdao", line)
            // 错误汇总：跨轮次保留，用户在设置页点开就能看到"最近出过什么事"
            if (ERROR_MARKERS.any { line.contains(it, ignoreCase = true) }) {
                appendRotated(File(d, ERRORS_NAME), "[$ts] $line\n", ERRORS_MAX_BYTES)
            }
        } catch (e: Throwable) {
            // 落盘失败同样不能静默——否则又是一个黑洞
            android.util.Log.w("zhengdao/RunLog", "[落盘失败] $line | ${e.message}")
        }
    }

    /** 追加 + 超限轮转（保留后半段）。 */
    private fun appendRotated(f: File, text: String, max: Long = MAX_BYTES) {
        if (f.length() > max) {
            val keep = f.readText().takeLast((max / 2).toInt())
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
