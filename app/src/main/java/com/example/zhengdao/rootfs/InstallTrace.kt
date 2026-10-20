// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

/**
 * 一次安装的分阶段计时（第六十五批 · 安装体验）。
 *
 * 用户原话：「验证和解压的过程很慢」——**先量后治**。本类只做三件事：
 * 1. 记录每个阶段的真实耗时（纳秒时钟，阶段内可嵌套记录多条）；
 * 2. 汇总成**一行** RunLog（成功也写、失败也写），带包大小与条目数；
 * 3. 记下「这次安装对同一个包做了几遍整包哈希」，让去重复校验有可断言的读数。
 *
 * 不参与任何安装决策：计时失败、日志写不进去都不能影响安装本身。
 *
 * 阶段口径（与派工单 §1 一一对应）：
 * - [Stage.DOWNLOAD]：下载（调用方记录；本地包拷贝也算这一档，note 里写明）；
 * - [Stage.VERIFY]：**独立**整包校验（下载后重读、修复前的 sha256Of 等）。
 *   与解包同遍的校验不单独记，`EXTRACT` 的 note 会写「含校验」，避免把同一段耗时算两次；
 * - [Stage.PREPARE]：清旧残局 + 空间预检 + 建临时树；
 * - [Stage.EXTRACT]：解包（Rust 快路径时含它内部的整包对账）；
 * - [Stage.MARKER]：写完成标记；
 * - [Stage.SWAP]：旧树让位 + 新树就位（第六十五批起不再含删旧树）；
 * - [Stage.CLEAN_OLD]：删旧树（前台）或后台清理完成时的实测耗时。
 */
class InstallTrace(
    private val nanoTime: () -> Long = System::nanoTime,
) {

    enum class Stage(val label: String) {
        DOWNLOAD("下载"),
        VERIFY("校验"),
        PREPARE("准备"),
        EXTRACT("解包"),
        MARKER("写标记"),
        SWAP("换树"),
        CLEAN_OLD("清旧树"),
    }

    data class Record(val stage: Stage, val ms: Long, val note: String = "")

    private val records = ArrayList<Record>()
    private var openStage: Stage? = null
    private var openAtNanos = 0L

    /** 归档字节数（调用方在安装开始时给）。 */
    @Volatile var archiveBytes: Long = 0L

    /** 解包条目数（Rust 报告或 Java 计数）。 */
    @Volatile var entries: Long = 0L

    /** 解包写出的字节数；拿不到（Java 回退）时保持 0，不编。 */
    @Volatile var bytesWritten: Long = 0L

    /**
     * 本次安装/更新的整包哈希次数。
     *
     * 为什么记它：派工单 §1-④ 要求「同一次安装对同一包只做一遍哈希」。调用方在做
     * **独立**校验时显式 +1（下载后重读、修复前 sha256Of），Rust 解包流水线内部对账
     * 再 +1。汇总行会把它打出来，重复校验一眼可见——这比 grep 源码更难骗。
     */
    @Volatile var hashPasses: Int = 0

    /** 本进程内实际走了 Rust 还是 Java 回退（写进汇总，排障用）。 */
    @Volatile var extractionPath: String = ""

    @Synchronized
    fun begin(stage: Stage) {
        // 上一次没关（异常路径）就先收掉，别让计时器悄悄串台
        end()
        openStage = stage
        openAtNanos = nanoTime()
    }

    /** 结束当前阶段；没有打开的阶段时什么也不做。 */
    @Synchronized
    fun end(note: String = "") {
        val stage = openStage ?: return
        val ms = ((nanoTime() - openAtNanos) / 1_000_000L).coerceAtLeast(0L)
        openStage = null
        records += Record(stage, ms, note)
    }

    /** 直接记一段（调用方已经自己计时，例如下载线程）。 */
    @Synchronized
    fun record(stage: Stage, ms: Long, note: String = "") {
        records += Record(stage, ms.coerceAtLeast(0L), note)
    }

    /** 异常路径：收掉还开着的阶段并返回它（给汇总标「失败于 X」）。 */
    @Synchronized
    fun abortOpen(): Stage? {
        val stage = openStage ?: return null
        end()
        return stage
    }

    @Synchronized
    fun records(): List<Record> = records.toList()

    @Synchronized
    fun stageMs(stage: Stage): Long = records.filter { it.stage == stage }.sumOf { it.ms }

    /**
     * 汇总行（纯函数，JVM 单测直接断言）。
     *
     * 例：
     * `安装阶段汇总（成功）：包 192.3MB（201632517B）/ 条目 12345 | 下载 — | 校验 — |
     *  准备 35ms | 解包 28456ms（Rust，含校验） | 写标记 3ms | 换树 22ms | 清旧树 9876ms |
     *  合计 38392ms | 整包哈希 2 次`
     */
    fun summary(ok: Boolean, failedAt: Stage? = null, reason: String? = null): String {
        val total = records().sumOf { it.ms }
        val head = when {
            ok -> "安装阶段汇总（成功）"
            failedAt != null -> "安装阶段汇总（失败于${failedAt.label}）"
            else -> "安装阶段汇总（失败）"
        }
        val size = if (archiveBytes > 0) {
            "包 ${mbText(archiveBytes)}（${archiveBytes}B）"
        } else "包大小未知"
        val entryText = if (entries > 0) {
            val written = if (bytesWritten > 0) "，写出 ${mbText(bytesWritten)}" else ""
            "条目 $entries$written"
        } else "条目未记录"
        val stageText = Stage.entries.joinToString(" | ") { stage ->
            val rs = records().filter { it.stage == stage }
            if (rs.isEmpty()) {
                "${stage.label} —"
            } else {
                val notes = rs.mapNotNull { it.note.takeIf(String::isNotEmpty) }.distinct()
                "${stage.label} ${rs.sumOf { it.ms }}ms" +
                    if (notes.isEmpty()) "" else "（${notes.joinToString("·")}）"
            }
        }
        val tail = buildString {
            append("合计 ${total}ms")
            append(" | 整包哈希 $hashPasses 次")
            if (extractionPath.isNotEmpty()) append(" | 解包路径 $extractionPath")
            if (!reason.isNullOrBlank()) append(" | 原因 ${reason.take(200)}")
        }
        return "$head：$size / $entryText | $stageText | $tail"
    }

    /** 写一行汇总进 RunLog；任何异常都不得把安装本身炸掉。 */
    fun log(ok: Boolean, failedAt: Stage? = null, reason: String? = null) {
        runCatching { RunLog.log(summary(ok, failedAt, reason)) }
    }

    private fun mbText(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
}
