// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.keepalive

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.example.zhengdao.core.IssueCenter
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.Store
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「被杀留档」（#5 后台被杀日志留档，2026-10-09）。
 *
 * ## 为什么要有它
 *
 * 用户报告「终端用着用着就没了」时，我们手上**什么都没有**：进程死掉的那一刻不留任何痕迹，
 * 杀掉它的又不是我们（厂商后台清理 / LMK / 冻结），`RunLog` 只记得到死之前正常跑着。
 * 于是"到底是被谁、因为什么、在什么状态下杀掉的"全靠猜——M2 保活数据也就无从谈起。
 *
 * 三件事实在真机上是**可以查到的**，只是要主动去取：
 * 1. `ActivityManager.getHistoricalProcessExitInfos()`（API 30+）＝系统替我们记的**死亡证明**：
 *    原因码（内存不足 / 资源超限 / 崩溃 / ANR / 被信号杀 / 厂商清理…）、时间、重要性、RSS/PSS；
 *    崩溃与 ANR 还能取到系统侧的 **trace/tombstone 原文**；
 * 2. **心跳**：进程活着时定期往 `heartbeat.log` 里写"我还活着 + 当时的内存水位 / 前台服务状态"，
 *    死后再看这个文件，最后一行就是**遇难现场**；
 * 3. **logcat 片段**：`logcat -d` 在我们自己的 UID 下能读到**本进程**的日志（`RunLog.log`
 *    现在会同步镜像一份进 logcat），所以启动时抓一段、巡检时再抓一段，就留住了"死前那几十行"。
 *
 * ## 放在哪
 *
 * **App 私有目录** `files/keepalive/`（`exits.log` / `heartbeat.log` / `logcat-*.txt` /
 * `crash-*.log` / `trace-*.txt`）——它比 `RunLog` 的公共区日志更"内部"，用户不必天天看见；
 * 需要给 Agent 看时用设置页那个「导出到 Download/证道/logs/keepalive」按钮拷进公共区
 * （卸载 App 才会丢，这正是 #5 说的"本地可导出"）。
 *
 * ## 与 #4 的关系
 *
 * 上次不是正常结束（[isUnclean]）就顺手报进 [IssueCenter]，主页「最近问题」卡会亮一条，
 * 用户至少知道"上次是被系统结束的"，而不是以为 App 自己崩了。这条在下次正常退出时自动撤下。
 *
 * ## 可测性
 *
 * 除了 `onStartup` / `export` / `clear`（要 Context），其余都是 `internal` 的纯函数或纯文件函数，
 * 单测直接调（`KeepaliveArchiveTest`）。
 */
object KeepaliveArchive {

    /** 私有留档目录名（`filesDir` 下）。 */
    internal const val DIR = "keepalive"

    internal const val EXITS = "exits.log"
    internal const val HEARTBEAT = "heartbeat.log"

    /** 上限：超了丢最早的一半（只留后半段），与 [RunLog] 的做法一致。 */
    internal const val EXITS_MAX_BYTES = 256L * 1024
    internal const val HEARTBEAT_MAX_BYTES = 512L * 1024
    internal const val SNIPPET_MAX_BYTES = 64 * 1024

    /** logcat 片段最多留几份（每次启动一份 + 每次巡检一份，久了会堆）。 */
    internal const val SNIPPET_KEEP = 5

    /** 崩溃 / ANR / native 崩溃：值得把系统侧 trace 原文也抠出来。 */
    internal val TRACE_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
    )

    /** [IssueCenter] 里的问题 id：上次运行不是正常结束。 */
    internal const val ISSUE_LAST_EXIT = "last-exit"

    /** 一次进程退出的记录（[ApplicationExitInfo] 的可测投影）。 */
    data class ExitRecord(
        val pid: Int,
        val reason: Int,
        val timestamp: Long,
        val importance: Int,
        val rssKb: Long,
        val pssKb: Long,
        val description: String? = null,
    )

    // ── 纯函数（单测直接调）──

    internal fun timeText(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    internal fun stamp(ts: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ts))

    /**
     * 原因码 → 人话。
     *
     * 用常量而不是数字：这些码是 Android 官方的，符号名比 `8` 自解释（也避免我记错值）。
     */
    internal fun reasonText(reason: Int, description: String? = null): String = when {
        // 先看描述：`am kill` / 厂商后台管理杀进程时，系统给的原因码也是「用户主动结束」，
        // 光看码会把**最要紧的那种死法**说成用户自己关的（真机实测：
        // `reason=10 … 描述=[KILL BACKGROUND] kill background`）。
        isBackgroundKill(description) -> "被当后台进程清理（KILL BACKGROUND／厂商后台管理）"
        reason == ApplicationExitInfo.REASON_EXIT_SELF -> "进程自己退出"
        reason == ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死"
        reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统清理（LMK）"
        reason == ApplicationExitInfo.REASON_CRASH -> "崩溃（Java/Kotlin 异常）"
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> "native 崩溃"
        reason == ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
        reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        reason == ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更导致进程结束"
        reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过多被系统清理"
        reason == ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动结束"
        reason == ApplicationExitInfo.REASON_USER_STOPPED -> "用户强制停止（Force Stop）"
        reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程死亡"
        reason == ApplicationExitInfo.REASON_FREEZER -> "被冻结（cached 进程冻结）"
        reason == ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "包状态变化"
        reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用被更新"
        reason == ApplicationExitInfo.REASON_OTHER -> "其它（厂商后台清理最常见）"
        else -> "未知原因（$reason）"
    }

    /**
     * 描述里带不带「后台被杀」的记号。
     *
     * 真机上 `am kill`、以及厂商「后台管理」清进程时，系统给的是
     * `reason=10（REASON_USER_REQUESTED）+ 描述=[KILL BACKGROUND] kill background`——
     * 最要紧的那种死法，光看原因码会被说成「用户自己关的」。
     */
    internal fun isBackgroundKill(description: String?): Boolean =
        description?.contains("KILL BACKGROUND", ignoreCase = true) == true

    /**
     * 这次退出算不算「不正常」——只有它值得打扰用户（[IssueCenter] 报一条）。
     *
     * 自退 / 用户主动结束 / 用户强制停止 / 被更新 / 包状态变化 = 预期内，
     * 其余（后台清理、LMK、资源超限、冻结、被信号杀、崩溃、ANR…）都不是我们安排的死法。
     */
    internal fun isUnclean(reason: Int, description: String? = null): Boolean {
        // 描述里写了 KILL BACKGROUND 就不算「用户自己关的」——那正是要抓的后台清理。
        if (isBackgroundKill(description)) return true
        return when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            -> false
            else -> true
        }
    }

    /** 一行退出记录（同时是**可解析**的：`keys` 靠 `pid=` / `ts=` 两个字段去重）。 */
    internal fun recordLine(r: ExitRecord): String {
        val desc = r.description?.takeIf { it.isNotBlank() }?.let { " 描述=$it" } ?: ""
        return "[${timeText(r.timestamp)}] pid=${r.pid} ts=${r.timestamp} reason=${r.reason} " +
            "原因=${reasonText(r.reason, r.description)} importance=${r.importance} " +
            "rss=${r.rssKb / 1024}MB pss=${r.pssKb / 1024}MB$desc\n"
    }

    /** 从 `exits.log` 原文里取出已记过的键（`ts-pid`），用来判断哪些是新的。 */
    internal fun keys(text: String): Set<String> {
        val re = Regex("pid=(\\d+)\\s+ts=(\\d+)")
        return re.findAll(text).map { "${it.groupValues[2]}-${it.groupValues[1]}" }.toSet()
    }

    internal fun key(r: ExitRecord): String = "${r.timestamp}-${r.pid}"

    /** 系统给的列表里，哪些还没进过档（保持原有顺序）。 */
    internal fun fresh(existing: Set<String>, all: List<ExitRecord>): List<ExitRecord> =
        all.filter { key(it) !in existing }

    /** 追加 + 超限轮转（保留后半段）。 */
    internal fun appendCapped(f: File, text: String, maxBytes: Long) {
        f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        if (f.length() > maxBytes) {
            val keep = f.readText().takeLast((maxBytes / 2).toInt())
            f.writeText("…（旧记录已截断）…\n$keep")
        }
        f.appendText(text)
    }

    /** 取文本最后 [maxBytes] 字节（截断在前，说明在后）。 */
    internal fun truncate(text: String, maxBytes: Int): String =
        if (text.length <= maxBytes) text
        else "…（前 ${text.length - maxBytes} 字符已截断）…\n" + text.takeLast(maxBytes)

    /** RSS：`/proc/self/statm` 第一列是页数。 */
    internal fun rssMb(pages: Long, pageSize: Int): Long = pages * pageSize / 1024 / 1024

    /** trim 级别 → 人话（真机上厂商给的级别不一定标准，所以是 when-else）。 */
    internal fun trimText(level: Int): String = when (level) {
        5 -> "TRIM_MEMORY_RUNNING_MODERATE（内存开始紧张）"
        10 -> "TRIM_MEMORY_RUNNING_LOW（内存很低）"
        15 -> "TRIM_MEMORY_RUNNING_CRITICAL（内存危急，随时可能被杀）"
        20 -> "TRIM_MEMORY_UI_HIDDEN（界面已不可见）"
        40 -> "TRIM_MEMORY_BACKGROUND（进入后台队列）"
        60 -> "TRIM_MEMORY_MODERATE（后台队列中段）"
        80 -> "TRIM_MEMORY_COMPLETE（后台队列末尾，下一个就是我）"
        else -> "TRIM_MEMORY_$level"
    }

    // ── 目录与采集 ──

    /** 留档目录（私有）：`files/keepalive`。 */
    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { runCatching { mkdirs() } }

    /** 设置页展示用：告诉用户东西在哪。 */
    fun dirPath(ctx: Context): String = runCatching { dir(ctx).absolutePath }.getOrDefault("（私有目录不可用）")

    /** 旧片段清理：按修改时间只留最新的 [SNIPPET_KEEP] 份。 */
    internal fun pruneSnippets(dir: File, keep: Int = SNIPPET_KEEP): Int {
        val all = dir.listFiles { f -> f.isFile && f.name.startsWith("logcat-") }
            ?.sortedByDescending { it.lastModified() } ?: return 0
        var n = 0
        for (f in all.drop(keep.coerceAtLeast(1))) if (f.delete()) n++
        return n
    }

    /**
     * 抓一份本进程的 logcat 片段（`logcat -d -t N`）。
     *
     * 权限现实：没有 `READ_LOGS` 时只能读**自己 UID** 的日志——正好够用，因为
     * `RunLog.log` 现在会把每一行镜像进 logcat（tag `zhengdao`）。厂商杀掉我们的那几行
     * 是系统 UID 写的，读不到也不影响：那件事由 [ApplicationExitInfo] 负责说清楚。
     */
    internal fun captureLogcat(dir: File, tag: String, lines: Int, now: Long): File? = runCatching {
        val p = ProcessBuilder("/system/bin/logcat", "-d", "-t", lines.toString(), "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        runCatching { p.waitFor() }
        if (text.isBlank()) return@runCatching null
        val f = File(dir, "logcat-$tag-${stamp(now)}.txt")
        f.writeText(truncate(text, SNIPPET_MAX_BYTES))
        pruneSnippets(dir)
        f
    }.getOrNull()

    private fun activityManager(ctx: Context): ActivityManager? =
        ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    /**
     * 系统记的那份"死亡证明"。
     *
     * 用的是**公开** API `getHistoricalProcessExitReasons(packageName, pid, maxNum)`
     * （`pid = 0` = 本包所有进程、`maxNum = 20` = 最近 20 次；`getHistoricalProcessExitInfos()`
     * 是 @SystemApi，普通应用调不到——第一版就是踩了这个才编译失败）。传自己的包名，
     * 既拿得到数据，也不会因为越权抛 SecurityException。
     */
    private fun exitInfos(ctx: Context): List<ApplicationExitInfo> =
        runCatching {
            activityManager(ctx)?.getHistoricalProcessExitReasons(ctx.packageName, 0, 20)
        }.getOrNull().orEmpty()

    private fun record(info: ApplicationExitInfo): ExitRecord = ExitRecord(
        pid = info.pid,
        reason = info.reason,
        timestamp = info.timestamp,
        importance = info.importance,
        rssKb = info.rss,
        pssKb = info.pss,
        description = runCatching { info.description }.getOrNull(),
    )

    /** 崩溃 / ANR 的系统侧 trace 原文（拿不到就算了，别让归档失败）。 */
    private fun dumpTrace(ctx: Context, info: ApplicationExitInfo): String? = runCatching {
        val text = info.traceInputStream?.bufferedReader()?.use { it.readText() } ?: return null
        if (text.isBlank()) return null
        val f = File(dir(ctx), "trace-${stamp(info.timestamp)}-${info.pid}.txt")
        f.writeText(truncate(text, SNIPPET_MAX_BYTES))
        f.name
    }.getOrNull()

    /**
     * 启动时归档一次（**要读 logcat / 写文件，请在后台线程调**）。
     *
     * 顺序有讲究：**先抓 logcat 片段**，再归档退出原因——片段里那几行正是上一轮死前的现场，
     * 抓到之后才轮到我们写自己的启动日志去冲淡它。
     */
    fun onStartup(ctx: Context) {
        val d = runCatching { dir(ctx) }.getOrNull() ?: return
        runCatching { captureLogcat(d, "boot", 800, System.currentTimeMillis()) }
        val exits = File(d, EXITS)
        val known = runCatching { keys(exits.readText()) }.getOrDefault(emptySet())
        val infos = exitInfos(ctx)
        val news = fresh(known, infos.map { record(it) })
        if (news.isEmpty()) return
        for (r in news) appendCapped(exits, recordLine(r), EXITS_MAX_BYTES)
        // 崩溃 / ANR：连系统侧 trace 一起留下
        for (info in infos.filter { it.reason in TRACE_REASONS }) {
            if (key(record(info)) in news.map { key(it) }) dumpTrace(ctx, info)
        }
        val latest = news.maxByOrNull { it.timestamp } ?: return
        val mem = "rss ${latest.rssKb / 1024}MB / pss ${latest.pssKb / 1024}MB"
        RunLog.log(
            "上次退出：${reasonText(latest.reason, latest.description)}（${timeText(latest.timestamp)}，$mem，" +
                "importance=${latest.importance}）" +
                (latest.description?.takeIf { it.isNotBlank() }?.let { "，描述=$it" } ?: "")
        )
        if (isUnclean(latest.reason, latest.description)) {
            IssueCenter.report(
                id = ISSUE_LAST_EXIT,
                title = "上次运行不是正常结束：${reasonText(latest.reason, latest.description)}",
                detail = "${timeText(latest.timestamp)} · $mem。留档在设置页「保活记录」里" +
                    "（可导出给作者分析清理策略）。",
                actionLabel = "去设置看记录",
                actionId = IssueCenter.ACTION_VIEW_LOGS,
            )
        } else {
            IssueCenter.resolve(ISSUE_LAST_EXIT)
        }
    }

    /** 最近 [limit] 条退出记录（新 → 旧，给设置页直接渲染）。 */
    fun history(ctx: Context, limit: Int = 3): List<String> = runCatching {
        File(dir(ctx), EXITS).useLines { it.toList() }
            .filter { it.startsWith("[") }
            .takeLast(limit.coerceAtLeast(1))
            .reversed()
    }.getOrDefault(emptyList())

    /** 最近 [limit] 条心跳（新 → 旧）。 */
    fun heartbeatTail(ctx: Context, limit: Int = 6): List<String> = runCatching {
        File(dir(ctx), HEARTBEAT).useLines { it.toList() }
            .filter { it.startsWith("[") }
            .takeLast(limit.coerceAtLeast(1))
            .reversed()
    }.getOrDefault(emptyList())

    /** 已有的 logcat 片段（新 → 旧）。 */
    fun snippets(ctx: Context): List<File> = runCatching {
        dir(ctx).listFiles { f -> f.isFile && f.name.startsWith("logcat-") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 崩溃留档（新 → 旧）。 */
    fun crashes(ctx: Context): List<File> = runCatching {
        dir(ctx).listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 导出到公共区（`Download/证道/logs/keepalive`），返回目标目录；失败返回 null。 */
    fun export(ctx: Context): File? = runCatching {
        val src = dir(ctx)
        val dst = File(Store.logsDir(ctx), DIR).apply { mkdirs() }
        src.listFiles()?.forEach { f ->
            if (f.isFile) runCatching { f.copyTo(File(dst, f.name), overwrite = true) }
        }
        dst
    }.getOrNull()

    /** 清空留档（用户主动点；不影响 RunLog）。 */
    fun clear(ctx: Context): Int = runCatching {
        var n = 0
        dir(ctx).listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        n
    }.getOrDefault(0)
}
