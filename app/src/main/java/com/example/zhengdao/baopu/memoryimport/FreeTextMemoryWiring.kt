// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百五十五批《收编接线》——451 交付的唯一生产入口
// `FreeTextMemoryStore.importFromFiles` 此前「有定义无调用」（373 入口盘点口径）；
// 本批把它接进生产：①冷启首读（上次导入早于今天 ⇒ 后台静默扫运行时目录一次，
// 失败只落诊断，不弹卡不阻断）②可执行入口＝DUMP 权限保护的一次性广播（UI 归 U 批）。
package com.example.zhengdao.baopu.memoryimport

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.FreeTextMemoryImport
import com.example.zhengdao.baopu.FreeTextMemoryStore
import com.example.zhengdao.baopu.GlobalMemory
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.core.AppPrefs
import com.example.zhengdao.core.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 纯判据（不碰 Context／线程 ⇒ JVM 直测；[FreeTextMemoryWiring.Outcome] 是同文件的数据形状）。
 */
internal object MemoryImportRules {

    /** 「上次导入」的落点（`AppPrefs`／zhengdao-settings）。 */
    const val KEY_LAST_IMPORT_AT = "memory_import_last_at"

    /**
     * 本地零点（「今天」的边界）。用日历字段清零而不是 now − 24h：跨时区/夏令时的
     * 「今天」照用户当地日历算，减法会漂。
     */
    fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 冷启闸：上次**导入**早于今天 ⇒ 该扫（从没导过 ⇒ 0 < 今天 ⇒ 该扫；今天已导 ⇒ 不扫）。 */
    fun dueByDay(lastImportAt: Long, now: Long): Boolean = lastImportAt < startOfDay(now)

    /**
     * 这一轮算不算「导过了」（要不要落戳）：全部在场的源都读得动、且没有失败，才算。
     * 读不动／有失败 ⇒ 不落戳 ⇒ 下次冷启再试（幂等，重扫无害）；两份源都不在也算导过
     * （「没有」是如实读数，不是失败）。
     */
    fun shouldStamp(outcome: FreeTextMemoryWiring.Outcome): Boolean =
        outcome.unreadable.isEmpty() && outcome.failures.isEmpty()

    /**
     * 判据要的那行读数（`导入 N 行 / 跳过 M 行 / 耗时`）：
     * 导入 ＝ 收编成条目的源行数；跳过 ＝ 没收编的源行数（未解析＋空白，逐行明细在回执）。
     */
    fun summaryLine(trigger: String, o: FreeTextMemoryWiring.Outcome): String = buildString {
        append("记忆收编[$trigger]：导入 ${o.entries} 行 / 跳过 ${o.skippedLines} 行（源共 ${o.sourceLines} 行）/ ")
        append("落盘：新写 ${o.written}＋原样 ${o.unchanged}＋保留人工版 ${o.kept}")
        if (o.unreadable.isNotEmpty()) append(" / 读不动：${o.unreadable.joinToString("、")}")
        if (o.failures.isNotEmpty()) append(" / 失败 ${o.failures.size} 项")
        append(" / 耗时 ${o.elapsedMs} ms")
    }
}

/**
 * 451 收编入口的生产接线（455 批）。
 *
 * ## 落点（为什么两个作用域各调一次）
 * [FreeTextMemoryStore.importFromFiles] 的产出是一个 [FreeTextMemoryImport.ImportResult]，
 * 而 [com.example.zhengdao.baopu.MemoryStore] 的目录跟作用域走：全局条目进
 * `filesDir/抱朴记忆/记忆/`，项目条目进 `<工作区>/记忆/`。所以接线把两份源**拆开各调一次**
 * （一次 `importFromFiles(全局, null, …)`、一次 `importFromFiles(null, 项目, …)`）——
 * 入口仍是 451 那一个，只是按作用域各走一遍；每份的回执落在自己的条目目录旁边
 * （`.txt`，不会被 `MemoryStore.scan()` 当条目，451 已定）。
 *
 * ## 不覆盖人工版（红线）
 * 收编条目是 PENDING，用户可以在记忆页确认它（`BaopuChatController` 的确认走
 * `store.update(CONFIRMED)`）。明天冷启重扫同一行会算出**同一个 id**（451：id 由
 * 作用域＋行内容哈希决定，不掺时钟）⇒ 若无条件落盘，会把人工确认悄悄冲回 PENDING——
 * 这正是 `MemoryStore.write` 拒绝重名的理由（覆盖＝自动改掉用户记忆）。所以真目录里的
 * 条目文件按三态处理：不在 ⇒ 写；逐字一致 ⇒ 不动（幂等重跑的常态）；**内容不同 ⇒ 保留
 * 人工版、一个字节不碰**并记一行账。回执是机器件，每轮覆盖。
 *
 * ## 中转（为什么先落暂存）
 * `importFromFiles` 是「解析＋落盘」一体、落盘无守门。要保住上面的红线，就得先让它
 * 落进 `cacheDir/memory-import/<作用域>/`，再由这里逐文件发往真目录——这样「保不保」
 * 的判据发生在**写真目录之前**，而不是写坏了再回滚。暂存在 cacheDir：系统随时可清，
 * 清了只影响正在跑的这一轮，下轮照常。
 */
internal object FreeTextMemoryWiring {

    /** 一次收编的完整读数（判据「导入 N／跳过 M／耗时」与幂等对账都从这里出）。 */
    data class Outcome(
        /** 解析收编出的条目总数（＝收编成条目的源行数；含保留人工版对应的那条）。 */
        val entries: Int,
        /** 本次新写盘的条目文件数。 */
        val written: Int,
        /** 目标已在且逐字一致（幂等重跑的常态；一个字节没动）。 */
        val unchanged: Int,
        /** 目标已存在但内容不同 ⇒ 保留人工版（不覆盖；每条各落一行账）。 */
        val kept: Int,
        /** 在场源文件的总行数（对账：= 条目数 ＋ [skippedLines]）。 */
        val sourceLines: Int,
        /** 没收编的源行数（未解析＋空白；逐行明细在回执）。 */
        val skippedLines: Int,
        /** 在但读不动的源文件（只落诊断；这轮跳过它，不落戳，下次冷启重试）。 */
        val unreadable: List<String>,
        /** 收编／落盘本身的失败（只落诊断，不外抛）。 */
        val failures: List<String>,
        /** 用时（ms）。 */
        val elapsedMs: Long,
    )

    /** 一轮里累积的账（[Outcome] 的可变底稿；作用域之间共用一份）。 */
    private class Tally {
        var entries = 0
        var written = 0
        var unchanged = 0
        var kept = 0
        var sourceLines = 0
        var skippedLines = 0
        val unreadable = mutableListOf<String>()
        val failures = mutableListOf<String>()

        fun outcome(elapsedMs: Long): Outcome = Outcome(
            entries = entries,
            written = written,
            unchanged = unchanged,
            kept = kept,
            sourceLines = sourceLines,
            skippedLines = skippedLines,
            unreadable = unreadable.toList(),
            failures = failures.toList(),
            elapsedMs = elapsedMs,
        )
    }

    /** 触发方标记（进日志行，方便区分冷启／手动广播）。 */
    const val TRIGGER_COLD_START = "冷启"
    const val TRIGGER_MANUAL = "手动"

    /** 暂存根（cacheDir 下；系统可清，清了只影响正在跑的一轮）。 */
    private const val STAGING_DIR = "memory-import"

    /** 全局源文件的人读名（诊断/失败账里用它）。 */
    private const val LABEL_GLOBAL = "全局"
    private const val LABEL_PROJECT = "项目"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    /** 冷启装配（幂等；晚两秒再跑，别和冷启动抢 IO——KnowledgeRefresh 同款纪律）。 */
    fun install(context: Context) {
        val appCtx = context.applicationContext
        if (!installed.compareAndSet(false, true)) return
        scope.launch {
            delay(2_000L)
            runCatching { autoRun(appCtx) }.onFailure {
                RunLog.log("记忆收编[冷启]：失败（${it.javaClass.simpleName}: ${it.message}）——不阻断，下次冷启重试")
            }
        }
    }

    /**
     * 冷启首读：上次导入早于今天 ⇒ 后台静默扫运行时目录一次。
     * @return true＝这轮真的扫了；false＝今天已导过（闸没放行）。
     */
    fun autoRun(context: Context): Boolean {
        val appCtx = context.applicationContext
        val last = runCatching {
            AppPrefs.prefs(appCtx).getLong(MemoryImportRules.KEY_LAST_IMPORT_AT, 0L)
        }.getOrDefault(0L)
        if (!MemoryImportRules.dueByDay(last, System.currentTimeMillis())) return false
        runOnce(appCtx, TRIGGER_COLD_START)
        return true
    }

    /** 手动可执行入口（广播用）：不看日闸，直接跑一次。收编幂等，连发几条也只是重复同一结果。 */
    fun runNow(context: Context) {
        runOnce(context.applicationContext, TRIGGER_MANUAL)
    }

    /** 生产落点与源文件解析（Context 胶水；判据全在 [runImportOnce] 与 [MemoryImportRules]）。 */
    fun runOnce(context: Context, trigger: String = TRIGGER_MANUAL): Outcome {
        val appCtx = context.applicationContext
        val ws = BaopuRuntime.workspaceRoot(appCtx)
        val store = BaopuRuntime.memoryStore(appCtx)
        val outcome = runImportOnce(
            globalSource = GlobalMemory.fileIn(appCtx.filesDir),
            projectSource = pickProjectSource(ws),
            globalOut = store.globalDir(),
            projectOut = store.projectDir(),
            stagingRoot = File(appCtx.cacheDir, STAGING_DIR),
        )
        RunLog.log(MemoryImportRules.summaryLine(trigger, outcome))
        if (MemoryImportRules.shouldStamp(outcome)) {
            stampLastImport(appCtx)
        }
        return outcome
    }

    /**
     * 项目记忆源：`记忆/项目记忆.md` 优先，旧根 `项目记忆.md` 只读回退
     * （与 `ProjectMemory.load` 同一优先序；都没有 ⇒ null，按「没有」算）。
     */
    fun pickProjectSource(workspace: File): File? {
        MemoryLayout.projectMemoryFile(workspace).takeIf { it.isFile }?.let { return it }
        return MemoryLayout.legacyProjectMemoryFile(workspace).takeIf { it.isFile }
    }

    /**
     * 跑一次收编（同步；调用方选线程）。**Context 无关**：给文件跑文件 ⇒ JVM 直测。
     *
     * @param readOk 源文件可读性探针（生产＝整读一遍；单测传 `{ false }` 造「读不动」）。
     */
    fun runImportOnce(
        globalSource: File?,
        projectSource: File?,
        globalOut: File,
        projectOut: File,
        stagingRoot: File,
        log: (String) -> Unit = { RunLog.log(it) },
        now: () -> Long = System::currentTimeMillis,
        readOk: (File) -> Boolean = { f -> runCatching { f.readText(Charsets.UTF_8) }.isSuccess },
    ): Outcome {
        val startedAt = now()
        val t = Tally()
        // 源探针：在但读不动的文件不能假装「没有」——importFromFiles 会把它按空读数收编，
        // 回执就撒谎（「0 行」）。所以只落诊断、这轮跳过它（不落戳 ⇒ 下次冷启重试）。
        val g = pickReadable(globalSource, "全局记忆.md", t, log, readOk)
        val p = pickReadable(projectSource, "项目记忆.md", t, log, readOk)
        // 并发护栏：上一条还没跑完又来一条 ⇒ 后到的直接跳过（两边都幂等，少一轮竞态）。
        if (!running.compareAndSet(false, true)) {
            log("记忆收编：上一轮还没跑完，这次跳过（收编幂等，不排队）")
            return t.outcome(now() - startedAt)
        }
        try {
            // 两个作用域各走一遍唯一入口；哪份源不在就按「没有」算（不建目录、不写空回执）。
            importScope(LABEL_GLOBAL, g, globalOut, stagingRoot, t, log)
            importScope(LABEL_PROJECT, p, projectOut, stagingRoot, t, log)
        } finally {
            running.set(false)
        }
        return t.outcome(now() - startedAt)
    }

    /** 一个作用域走一遍 451 唯一入口：暂存 → 对账 → 逐文件发往真目录（[publish]）。 */
    private fun importScope(
        label: String,
        source: File?,
        out: File,
        stagingRoot: File,
        t: Tally,
        log: (String) -> Unit,
    ) {
        if (source == null) return
        runCatching {
            val staging = File(stagingRoot, label)
            runCatching { staging.deleteRecursively() }
            val result = if (label == LABEL_GLOBAL) {
                FreeTextMemoryStore.importFromFiles(source, null, staging)
            } else {
                FreeTextMemoryStore.importFromFiles(null, source, staging)
            }
            // importFromFiles 不透出 importInto 的失败（写坏了也返回读数）——
            // 这里自己验一遍：回执没落下来就说明暂存没写成，如实记账，不发往真目录。
            if (!File(staging, FreeTextMemoryImport.RECEIPT_NAME).isFile) {
                t.failures += "「$label」暂存写盘失败（条目与回执都没落下来）"
                return
            }
            t.entries += result.entries.size
            t.sourceLines += result.sourceLineCount
            t.skippedLines += result.unresolvedCount + result.blankCount
            publish(staging = staging, rendered = result.rendered, out = out, label = label, t = t, log = log)
            out.mkdirs()
            File(out, FreeTextMemoryImport.RECEIPT_NAME).writeText(result.receipt, Charsets.UTF_8)
        }.getOrElse { t.failures += "「$label」收编失败：${it.javaClass.simpleName}: ${it.message}" }
    }

    /** 把暂存里的条目发往真目录（三态：不在 ⇒ 写；逐字一致 ⇒ 不动；不同 ⇒ 保留人工版）。 */
    private fun publish(staging: File, rendered: Map<String, String>, out: File, label: String, t: Tally, log: (String) -> Unit) {
        out.mkdirs()
        for ((name, text) in rendered) {
            val target = File(out, name)
            val payload = text.toByteArray(Charsets.UTF_8)
            when {
                !target.isFile -> {
                    runCatching { target.writeBytes(payload) }
                        .onSuccess { t.written++ }
                        .onFailure { log("记忆收编：「$label」条目 $name 写不进真目录（${it.javaClass.simpleName}）") }
                }
                runCatching { target.readBytes().contentEquals(payload) }.getOrDefault(false) -> t.unchanged++
                else -> {
                    t.kept++
                    log("记忆收编：「$label」条目 ${name.removeSuffix(".md")} 已有人工改动（比如确认过），保留人工版不覆盖")
                }
            }
        }
    }

    /** 源可读性：不在 ⇒ null（按「没有」算）；在但读不动 ⇒ 记诊断后 null（这轮跳过它）。 */
    private fun pickReadable(src: File?, label: String, t: Tally, log: (String) -> Unit, readOk: (File) -> Boolean): File? {
        if (src == null || !src.isFile) return null
        if (readOk(src)) return src
        t.unreadable += label
        log("记忆收编：$label 在（${runCatching { src.length() }.getOrDefault(0L)} 字节）但读不动——这轮跳过它（只落诊断，不落「上次导入」戳，下次冷启重试）")
        return null
    }

    /** 落「上次导入」戳；写不进去也要有声音（否则用户看不到为什么天天重扫）。 */
    private fun stampLastImport(appCtx: Context) {
        runCatching {
            AppPrefs.prefs(appCtx).edit {
                putLong(MemoryImportRules.KEY_LAST_IMPORT_AT, System.currentTimeMillis())
            }
        }.onFailure {
            RunLog.log("记忆收编：「上次导入」戳没写进去（${it.javaClass.simpleName}）——下次冷启会再扫一遍（幂等，无害）")
        }
    }
}
