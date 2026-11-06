// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批①「导出到共享存储」②「一键导入」。本文件是**唯一的 Android 壳**：
// 把 Context 翻译成挂载表、把设置读成快照、把 zip 放到用户看得见的目录。
//
// 纪律（判据「导出里没有 key 与正文」靠这一层守）：
// 1. 设置只读 [AppPrefs.FILE_NAME] **一个**文件——API Key 住在 `zhengdao-baopu-provider`，
//    本文件根本不碰它，所以"忘了过滤"也不会漏；
// 2. 逐键再过一道 [BackupRules.isSecretPrefKey]（双保险，键名像密钥的一律不进包）；
// 3. 聊天原文默认不导出；勾了才用 `VACUUM INTO` 快照一份（只导出、**不还原**）。
package com.example.zhengdao.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.search.PhoneSearch
import com.example.zhengdao.core.AppPrefs
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.core.ZhengdaoDirs
import java.io.File

/** 导出的读数（界面上要说的都在这里，不另存状态）。 */
internal data class ExportReport(
    val ok: Boolean,
    val file: File?,
    val entryCount: Int,
    val bytes: Long,
    val skipped: Int,
    val droppedPrefKeys: Int,
    val reason: String?,
)

/**
 * 一次完整导入的读数（工具入口与界面共用；`renderImportReport` 在工具层把它翻成人话）。
 *
 * @param conflicts 本机已有且**没被覆盖**的条目路径（一条都不许悄悄替换）
 * @param written 实际写进去的条目路径
 */
internal data class ImportRun(
    val source: String,
    val applied: Int,
    val unchanged: Int,
    val conflicts: List<String>,
    val written: List<String>,
    val settingsInPackage: Boolean,
    val settingsApplied: Int,
    val reload: ReloadReport?,
    val ok: Boolean = true,
    val reason: String? = null,
    val rolledBack: Boolean = true,
)

/**
 * 备份的 Android 壳。
 *
 * 方法都在 IO 线程上跑（调用方 `withContext(Dispatchers.IO)`），本类不自己开线程——
 * 线程归属只有一处，出问题好定位。
 */
internal object BackupShell {

    /** 落点目录名（共享存储 `Download/证道/backups/`；没存储权限时落私有兜底）。 */
    private const val DIR_BACKUPS = "backups"

    /** 目录名：抱朴记忆（账本＋人设与记忆＋全局技能都在这一棵下面）。 */
    private const val DIR_MEMORY = "抱朴记忆"

    /** 目录名：技能（工作区侧的项目技能）。 */
    private const val DIR_SKILLS = "技能"

    /**
     * 会被导出的设置文件**白名单**（只有这两个，别的一个都不读）。
     *
     * - `zhengdao-settings`：App 设置的真源（[AppPrefs.FILE_NAME]）；
     * - `zhengdao-appearance`：外观（`ui/theme/AppearancePrefs` 的 `SP`，那边是 private，
     *   这里写同一个字面量；`BackupSourceGateTest` 会去那边读出来比对，改了一处就红）。
     *
     * **API Key 住在 `zhengdao-baopu-provider`，不在这张表里** —— 白名单本身就是第一道防线，
     * 逐键的 [BackupRules.isSecretPrefKey] 是第二道。
     */
    private val SETTING_FILES = listOf(AppPrefs.FILE_NAME, "zhengdao-appearance")

    /** 冲突清单进日志的最多条数（日志是文本文件，几百行会淹掉别的东西）。 */
    private const val LOG_CONFLICT_LINES = 200

    /** 聊天库快照的体积上限：整份读进内存再写 zip，超过这个数就不塞（记跳过原因，不静默）。 */
    private const val CHAT_SNAPSHOT_MAX = 64L * 1024 * 1024

    /** 导出目录（保证存在；公共区不可用时由 [ZhengdaoDirs.root] 退到私有目录）。 */
    fun backupsDir(ctx: Context): File =
        File(ZhengdaoDirs.root(ctx), DIR_BACKUPS).apply { runCatching { mkdirs() } }

    /**
     * 四个挂载点——顺序即优先级（`技能/` 必须在 `抱朴记忆` 之前被排除，见 skipDirNames）。
     *
     * 注意：工作区可能被用户设成任意目录，也可能落在"仅私有"模式（App 外部目录），
     * 两种都由 [ZhengdaoDirs.hostDir] 统一解析，这里不另判。
     */
    fun mounts(ctx: Context): List<BackupMount> {
        val mem = File(ctx.filesDir, DIR_MEMORY)
        val ws = ZhengdaoDirs.hostDir(ctx)
        return listOf(
            BackupMount("memory/global", mem, BackupGroup.MEMORY, splitLedger = true, skipDirNames = setOf(DIR_SKILLS)),
            BackupMount("memory/project", File(ws, DIR_MEMORY), BackupGroup.MEMORY, splitLedger = true),
            BackupMount("skills/global", File(mem, DIR_SKILLS), BackupGroup.SKILLS),
            BackupMount("skills/project", File(ws, DIR_SKILLS), BackupGroup.SKILLS),
        )
    }

    /** 默认文件名：`证道备份-20261106-142010.zip`（分钟级重复由 [BackupExporter.uniqueDest] 顺延）。 */
    fun defaultBackupFile(ctx: Context, now: Long): File =
        File(backupsDir(ctx), "证道备份-${TimeFormats.fileStamp(now)}.zip")

    /**
     * 一键导出。
     *
     * @param includeChat 默认 false；勾了才把聊天库快照一份进 `chat/`
     */
    fun export(ctx: Context, includeChat: Boolean, now: Long = System.currentTimeMillis()): ExportReport {
        val mounts = mounts(ctx)
        val walk = BackupWalker.walk(mounts)
        val dump = BackupRules.dumpSettings(readSettings(ctx))
        val extra = ArrayList<InMemoryFile>()
        extra += InMemoryFile(BackupImporter.SETTINGS_PATH, BackupGroup.SETTINGS, dump.json.toByteArray(Charsets.UTF_8))
        val skipped = ArrayList(walk.skipped)
        if (includeChat) addChatSnapshot(ctx, extra, skipped)
        val meta = ExportMeta(BuildConfig.VERSION_NAME, includeChat, now)
        val outcome = BackupExporter.export(defaultBackupFile(ctx, now), walk.files, extra, skipped, meta)
        return when (outcome) {
            is ExportOutcome.Failed -> ExportReport(false, null, 0, 0, skipped.size, dump.dropped.size, outcome.reason)
            is ExportOutcome.Ok -> {
                RunLog.log("备份导出：${outcome.file.absolutePath}（${outcome.manifest.entryCount} 条）")
                ExportReport(
                    ok = true,
                    file = outcome.file,
                    entryCount = outcome.manifest.entryCount,
                    bytes = outcome.manifest.totalBytes,
                    skipped = outcome.manifest.skipped.size,
                    droppedPrefKeys = dump.dropped.size,
                    reason = null,
                )
            }
        }
    }

    /**
     * 可选的聊天库快照（**只在勾了"含聊天原文"时**走这里）。
     *
     * 两个必须点：
     * 1. 快照落在**私有缓存**而不是 `backups/`（那是用户可见的共享存储）——中途失败也不会把
     *    聊天原文留在共享区；
     * 2. 超过 [CHAT_SNAPSHOT_MAX] 就**不塞进包**（整份读进内存会 OOM），只记一条跳过原因。
     */
    private fun addChatSnapshot(ctx: Context, extra: MutableList<InMemoryFile>, skipped: MutableList<BackupSkip>) {
        val snapshot = File(File(ctx.cacheDir, "backup"), "chat-snapshot.db")
        snapshot.parentFile?.let { runCatching { it.mkdirs() } }
        try {
            if (!snapshotChat(ctx, snapshot)) {
                skipped += BackupSkip("chat/baopu.db", "聊天库快照失败（库正忙或不在这台设备上）")
            } else if (snapshot.length() > CHAT_SNAPSHOT_MAX) {
                skipped += BackupSkip("chat/baopu.db", "聊天库太大（${snapshot.length()} 字节 > $CHAT_SNAPSHOT_MAX）")
            } else {
                extra += InMemoryFile("chat/baopu.db", BackupGroup.CHAT, snapshot.readBytes())
            }
        } finally {
            runCatching { snapshot.delete() }
        }
    }

    /**
     * 把用户从系统文件选择器挑中的 zip 复制进缓存目录（回调里拿到的只有 `content://`）。
     *
     * 为什么要落一份：导入要做两遍随机读（清单 + 逐条 hash），`content://` 流多半不支持
     * 随机访问，重复 open 还可能拿到不同的快照。
     */
    fun pickToCache(ctx: Context, uri: Uri): File? = runCatching {
        val dir = File(ctx.cacheDir, "backup").apply { runCatching { mkdirs() } }
        val out = File(dir, "picked-${System.currentTimeMillis()}.zip")
        val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        if (out.isFile && out.length() > 0) out else null
    }.getOrNull()

    /** 打开并校验（**不改任何东西**；冲突等用户勾）。 */
    fun open(ctx: Context, zip: File): OpenResult =
        BackupImporter.open(zip, mounts(ctx), File(ctx.cacheDir, "backup"))

    /**
     * 一步到位的导入（**工具入口用**：校验已在 [open] 做完，这里落盘 + 热加载 + 出读数）。
     *
     * 顺序有讲究：先落文件、再写设置、最后热加载。倒过来会出现"索引里已经有、文件还没写"的
     * 中间态——那正是"同一会话内查得到却打不开"的来源。
     */
    fun importNow(
        ctx: Context,
        preview: ImportPreview,
        overwrite: Set<String>,
        applySettings: Boolean,
        source: String = "",
    ): ImportRun {
        // 冲突 = 本机已有且内容不同**且这次没被点名覆盖**的那几条。
        // 点了名要覆盖的就不该再挂"未覆盖"的标签——回执说的必须与实际发生的逐条对得上。
        val conflicts = preview.conflicts.map { it.entry.path }.filter { it !in overwrite }
        val hasSettings = preview.settingsJson != null
        val result = BackupImporter.apply(preview, overwrite)
        preview.cleanup()
        if (result is ApplyOutcome.Failed) {
            return ImportRun(
                source = source, applied = 0, unchanged = 0, conflicts = conflicts, written = emptyList(),
                settingsInPackage = hasSettings, settingsApplied = 0, reload = null,
                ok = false, reason = result.reason, rolledBack = result.rolledBack,
            )
        }
        val ok = result as ApplyOutcome.Ok
        val settingsWritten = if (applySettings) writeSettings(ctx, ok.settingsJson) else 0
        val touched = preview.manifest.entries.mapTo(HashSet()) { it.group }
        val reload = BackupHotReload.afterImport(touched, reloadPorts(ctx))
        // 冲突清单**逐条落日志**（派工单四②：清单落日志，UI 卡归 U 批）。
        // 只写条数不够——用户事后要查"到底哪几条没导进去"，日志得答得上来。
        logConflicts(source, conflicts)
        return ImportRun(
            source = source, applied = ok.applied, unchanged = preview.same.size, conflicts = conflicts,
            written = ok.written, settingsInPackage = hasSettings, settingsApplied = settingsWritten,
            reload = reload,
        )
    }

    /** 冲突逐条进日志（条数从几十条到几百条都可能，只截头 200 条并注明总数）。 */
    private fun logConflicts(source: String, conflicts: List<String>) {
        if (conflicts.isEmpty()) return
        RunLog.log("备份导入：冲突未覆盖 ${conflicts.size} 条（来源 ${source.ifBlank { "未知" }}）")
        conflicts.take(LOG_CONFLICT_LINES).forEach { RunLog.log("  冲突：$it") }
        if (conflicts.size > LOG_CONFLICT_LINES) {
            RunLog.log("  …另有 ${conflicts.size - LOG_CONFLICT_LINES} 条未列出")
        }
    }

    /**
     * 两本派生索引的对齐端口（导入完**当场**刷新，同一会话立刻查得到）。
     *
     * 记忆那一路复用 [MemoryReloadPort]（真 [MemoryStore]，JVM 也能跑）；
     * 检索那一路只做**增量**同步，且**不建库**——建库是用户在搜索卡点「扫描本机」的事，
     * 这里替用户建一个空库，会让"我明明没开搜索"变成一句假话。
     */
    fun reloadPorts(ctx: Context): ReloadPorts = object : ReloadPorts {
        private val memory = MemoryReloadPort(
            MemoryStore(File(ctx.filesDir, DIR_MEMORY), File(ZhengdaoDirs.hostDir(ctx), DIR_MEMORY)),
        )

        override fun reindexMemory(): Int = memory.reindexMemory()

        override fun resyncSearch(): Boolean {
            if (!PhoneSearch.dbExists(ctx)) return false
            PhoneSearch.scan(ctx)
            return true
        }
    }

    /** 设置快照与本机的差异（界面那一行"设置：改 N 项 / 新 M 项"就靠它）。 */
    fun settingsDiff(ctx: Context, preview: ImportPreview): BackupRules.SettingsDiff {
        val incoming = preview.settingsJson?.let { BackupRules.parseSettings(it) } ?: return BackupRules.SettingsDiff(0, 0, 0)
        return BackupRules.diffSettings(readSettings(ctx), incoming)
    }

    /**
     * 把设置写回去（**一次 edit 一次 commit** ⇒ 要么全成要么全不成）。
     *
     * 写前再按 [BackupRules.isSecretPrefKey] 过滤一遍：包可能来自旧版本或被人改过，
     * "不写密钥"这条不该只依赖导出那一侧。
     */
    private fun writeSettings(ctx: Context, json: String?): Int {
        val files = json?.let { BackupRules.parseSettings(it) } ?: return 0
        var written = 0
        for ((fileName, values) in files) {
            // 不在白名单里的文件一律不写（包可能来自旧版本或被人改过）
            if (fileName !in SETTING_FILES) continue
            written += writeOneFile(ctx, fileName, values)
        }
        return written
    }

    /** 一个设置文件一次 commit（SP 的单文件写入本身是原子的）。写前再按密钥形状筛一遍。 */
    private fun writeOneFile(ctx: Context, fileName: String, values: Map<String, Any>): Int {
        val safe = values.filterKeys { !BackupRules.isSecretPrefKey(it) }
        if (safe.isEmpty()) return 0
        val editor = ctx.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit()
        for ((k, v) in safe) {
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
                is String -> editor.putString(k, v)
            }
        }
        // 用 commit（不是 apply）：调用方在 IO 线程上，要的是"写完了再报成功"
        return if (editor.commit()) safe.size else 0
    }

    /** 白名单内几个设置文件的当前快照（文件 → 键 → 值）。 */
    private fun readSettings(ctx: Context): Map<String, Map<String, Any?>> =
        SETTING_FILES.associateWith { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all }

    /**
     * 聊天库快照（只在用户勾了"含聊天原文"时走这里）。
     *
     * 用 `VACUUM INTO`：它由 SQLite 自己写出一份**一致**的副本（不受 WAL/正在写影响），
     * 比直接拷 `.db` 文件可靠得多——后者在事务中途拷出来就是废库。
     */
    private fun snapshotChat(ctx: Context, dest: File): Boolean = runCatching {
        runCatching { if (dest.exists()) dest.delete() }
        val src = BaopuDb.dbPathUnder(ctx.filesDir)
        if (!src.isFile) return@runCatching false
        val db = SQLiteDatabase.openDatabase(src.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val escaped = dest.absolutePath.replace("'", "''")
            db.execSQL("VACUUM INTO '$escaped'")
        } finally {
            db.close()
        }
        dest.isFile
    }.getOrDefault(false)
}
