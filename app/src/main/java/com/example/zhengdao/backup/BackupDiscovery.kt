// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批（设计修订，用户 2026-11-06）：「导入以模型为主，UI 只是第三入口」
// ——① 用户唯一动作＝把 zip 丢在手机任意位置；② 抱朴自己发现并执行（启动自动发现＋工具入口）。
//
// 两条边界（都是"不许更主动"）：
// 1. **只扫默认导入目录**（`Download/证道/导入/`），不全盘搜——全盘是 `find_files` 的事，
//    由模型按用户那句话去定，见 `import_data` 的路径解析；
// 2. **发现≠合并**：本单一律先提示（最保守档）。499（授权档）落地后，改的只有
//    [BackupInbox.refresh] 里那一个分支点。
package com.example.zhengdao.backup

import android.content.Context
import com.example.zhengdao.core.IssueCenter
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.ZhengdaoDirs
import java.io.File

/**
 * 默认导入目录的**判据层**（纯函数，无 Android、无 IO 副作用，JVM 直测）。
 *
 * 「发现哪个」这件事必须算得清：同目录里可能躺着好几个历史备份，
 * 挑错一个就是把用户旧数据导回来——所以判据写死在这里，不散在扫盘代码里。
 */
internal object BackupDiscoveryRules {

    /** 默认导入目录名（`Download/证道/导入/`）。 */
    const val DIR_IMPORT = "导入"

    /** 一次最多列几个候选（给提示/工具回执用，不是导入上限）。 */
    const val MAX_CANDIDATES = 5

    /** 认作备份包的门槛：`.zip` 且非隐藏（`.证道备份.zip` 这类临时/隐藏文件不算）。 */
    fun eligible(name: String): Boolean =
        name.lowercase().endsWith(".zip") && !name.startsWith(".") && name.length > 4

    /**
     * 从一堆文件里挑候选：**按修改时间倒序**（最近下载/最近放进去的在前），同时间按名字。
     *
     * 为什么按 mtime 而不是名字：用户刚拖进去那个 zip 的名字可能毫无规律（"证道备份 (1).zip"），
     * 而"最近动过的"是唯一稳定信号。
     */
    fun pick(files: List<File>, limit: Int = MAX_CANDIDATES): List<File> =
        files.filter { it.isFile && eligible(it.name) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            .take(limit.coerceAtLeast(1))

    /** 一句话提示（发现且校验通过时给用户看的那句）。 */
    fun noteOf(fileName: String, entryCount: Int, createdAtText: String): String =
        "发现备份包「$fileName」（$entryCount 项，导出于 $createdAtText）。" +
            "要还原就跟我说一句「把刚下载那个 zip 导进来」；我不会自己动。" +
            "不想要的话把 zip 从「证道/导入」挪走，就不会再提示。"
}

/** 校验结果（一句话：包在哪、几项、过没过）。 */
internal data class InboxSnapshot(
    val file: File,
    val entries: Int,
    val createdAtText: String,
    val ok: Boolean,
    val reason: String?,
)

/**
 * 导入收件箱：**启动时扫一次默认目录、校验一遍、留一个候选**（进程级）。
 *
 * 为什么要有它：用户把 zip 丢进 `Download/证道/导入/` 之后不会有任何动作，
 * 而模型也无从知道"来了个包"。这一层就是那个发现点——扫到就校验、就提示一句，
 * 但**绝不自己合并**（合并要用户说一句，或 UI 点一下）。
 */
internal object BackupInbox {

    /** IssueCenter 的稳定 id（同 id 报到就替换，不会堆成一片）。 */
    private const val ISSUE_ID = "backup-inbox"

    /** 「这条已提示过」的记号文件名（放在 App 私有 `filesDir/backup/`，不污染用户可见目录）。 */
    private const val MARKER = "inbox-handled.txt"

    private const val MAX_MARK_LINES = 200

    @Volatile
    private var cached: InboxSnapshot? = null

    /** 上一次扫到的候选（没扫过／没有 ⇒ null）。工具的**无参调用**用它。 */
    fun peek(): InboxSnapshot? = cached

    /** 默认导入目录（**不建目录**：没这个目录就是"没有待导入的东西"）。 */
    fun importDir(root: File): File = File(root, BackupDiscoveryRules.DIR_IMPORT)

    /**
     * 扫一遍并校验（IO 线程调用）。
     *
     * @return 第一个"没提示过"的合格候选；没有 ⇒ null（[cached] 同时清空）
     */
    fun refresh(ctx: Context): InboxSnapshot? {
        val dir = importDir(ZhengdaoDirs.root(ctx))
        val found = BackupDiscoveryRules.pick(runCatching { dir.listFiles() }.getOrNull().orEmpty().toList())
        val handled = handledKeys(ctx)
        val candidate = found.firstOrNull { keyOf(it) !in handled }
        if (candidate == null) {
            cached = null
            return null
        }
        val snap = validate(ctx, candidate)
        cached = snap
        announce(ctx, snap)
        return snap
    }

    /** 校验一个包（**不动任何用户文件**：open 只解到临时目录并核对 hash，然后删掉临时目录）。 */
    private fun validate(ctx: Context, file: File): InboxSnapshot {
        val opened = BackupImporter.open(file, BackupShell.mounts(ctx), File(ctx.cacheDir, "backup"))
        return when (opened) {
            is OpenResult.Rejected -> InboxSnapshot(file, -1, "", ok = false, reason = opened.reason)
            is OpenResult.Ready -> {
                val m = opened.preview.manifest
                opened.preview.cleanup()
                InboxSnapshot(file, m.entryCount, m.createdAtText, ok = true, reason = null)
            }
        }
    }

    /** 提示一句（不合格也要说：用户以为放进去了却没反应，比不提示更糟）。 */
    private fun announce(ctx: Context, snap: InboxSnapshot) {
        val line = if (snap.ok) {
            BackupDiscoveryRules.noteOf(snap.file.name, snap.entries, snap.createdAtText)
        } else {
            "发现备份包「${snap.file.name}」，但校验没过：${snap.reason}（这个包不会被动）。"
        }
        RunLog.log("[备份收件箱] $line")
        IssueCenter.report(
            IssueCenter.Issue(
                id = ISSUE_ID,
                title = if (snap.ok) "发现备份包" else "备份包校验没过",
                detail = line,
            ),
        )
        markHandled(ctx, snap.file)
    }

    /** 记下"这条已经提示过"（下一次启动不再念同一句）。 */
    private fun markHandled(ctx: Context, file: File) {
        runCatching {
            val f = markerFile(ctx)
            f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val keep = handledKeys(ctx) + keyOf(file)
            f.writeText(keep.toList().takeLast(MAX_MARK_LINES).joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    /** 已经提示/处理过的键（文件路径｜mtime）。 */
    private fun handledKeys(ctx: Context): Set<String> {
        val f = markerFile(ctx)
        if (!f.isFile) return emptySet()
        return runCatching { f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.toSet() }
            .getOrDefault(emptySet())
    }

    private fun markerFile(ctx: Context): File = File(File(ctx.filesDir, "backup"), MARKER)

    /** 同一个文件被重新下载/覆盖（mtime 变了）⇒ 当成新的，会再提示一次。 */
    private fun keyOf(file: File): String = "${file.absolutePath}|${file.lastModified()}"

    /** 用户导完／明确不要了 ⇒ 清掉进程内缓存（下次启动重新扫盘）。 */
    fun dismiss() {
        cached = null
    }
}
