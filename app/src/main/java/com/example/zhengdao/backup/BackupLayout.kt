// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批①「账本 / 人设与记忆 / 技能 / 设置」四样的落点，以及②「带 manifest」
// 之后**导入要能把每个条目放回原处**——所以路径与落点必须是同一张表算出来的，
// 不能导出时一张表、导入时再写一遍（两遍必然漂）。
package com.example.zhengdao.backup

import java.io.File

/**
 * 一个挂载点：本机某个目录 ⇄ zip 里的某个前缀。
 *
 * [splitLedger] 只对 `抱朴记忆/` 这棵树用：同一棵目录里既有账本（`.jsonl` / `.json`）也有
 * 人设与记忆（`.md` 等），派工单要它们分成两组，但**落点只有一个**。于是路径照落点走
 * （`memory/global/…`），分类由扩展名判（[groupOf]）——路径管"放回哪"，分组管"数了几条"。
 */
internal data class BackupMount(
    /** zip 内前缀，不含首尾斜杠（如 `memory/global`）。 */
    val prefix: String,
    /** 本机目录（不存在＝这一组这次没有内容，不算错）。 */
    val liveRoot: File,
    /** 默认分组（[splitLedger] 为 true 时只对非账本文件生效）。 */
    val group: BackupGroup,
    /** true ⇒ `.jsonl` / `.json` 归 [BackupGroup.LEDGER]，其余归 [group]。 */
    val splitLedger: Boolean = false,
    /** 遍历时整棵跳过的子目录名（`抱朴记忆/技能` 由专门的挂载点负责，别收两遍）。 */
    val skipDirNames: Set<String> = emptySet(),
)

/** 一个 zip 条目在本机对应的落点。 */
internal data class BackupTarget(val live: File, val group: BackupGroup)

/**
 * 挂载表与双向映射（纯函数，JVM 直测）。
 *
 * 两条不变量（[BackupLayoutTest] 钉住）：
 * 1. 前缀互不吞并——`memory/global` 不会命中 `memory/globalx/…`；
 * 2. 同一张表正反算一致——[zipPathOf] 出来的路径，[resolve] 必须指回同一个文件。
 */
internal object BackupLayout {

    /** 账本的扩展名（同一条目录里靠它分辨"账"与"人设/记忆"）。 */
    private val LEDGER_EXTENSIONS = setOf("jsonl", "json")

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun isLedgerFile(relPath: String): Boolean = extensionOf(relPath) in LEDGER_EXTENSIONS

    /** 相对路径（`/` 分隔；[rel] 必须是 [root] 的后代。 */
    fun relUnder(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    fun zipPathOf(mount: BackupMount, rel: String): String =
        if (mount.prefix.isEmpty()) rel else "${mount.prefix}/$rel"

    fun groupOf(mount: BackupMount, rel: String): BackupGroup =
        if (mount.splitLedger && isLedgerFile(rel)) BackupGroup.LEDGER else mount.group

    /**
     * 由 zip 内路径反查本机落点（**最长前缀优先**；都不匹配 ⇒ null，例如 `chat/` 与 `settings/`）。
     */
    fun resolve(mounts: List<BackupMount>, zipPath: String): BackupTarget? {
        var best: BackupMount? = null
        for (m in mounts) {
            if (!zipPath.startsWith("${m.prefix}/")) continue
            if (best == null || m.prefix.length > best.prefix.length) best = m
        }
        val m = best ?: return null
        val rel = zipPath.removePrefix("${m.prefix}/")
        if (!BackupRules.isSafeEntryPath(rel)) return null
        return BackupTarget(File(m.liveRoot, rel), groupOf(m, rel))
    }
}

/** 遍历产出的一个待打包文件（[source] 是本机真文件，[zipPath] 是包内路径）。 */
internal data class PlannedFile(
    val zipPath: String,
    val source: File,
    val group: BackupGroup,
)

/** 遍历结果：收下的 ＋ 跳过的（跳过必须带原因，见 [BackupSkip]）。 */
internal data class WalkResult(val files: List<PlannedFile>, val skipped: List<BackupSkip>) {
    val totalBytes: Long get() = files.sumOf { it.source.length() }
}

/**
 * 按挂载表扫盘（**只读**：不打时间戳、不建目录、不改权限）。
 *
 * 跳过规则（每条都留原因）：
 * - 文件名/目录名是密钥形状（[BackupRules.isSecretName]）⇒ 整条不进包；
 * - 隐藏项（`.` 开头）⇒ 不进包（`.nomedia`、`.DS_Store` 之类）；
 * - 单文件超 [BackupRules.MAX_ENTRY_BYTES]、或累计超 [BackupRules.MAX_TOTAL_BYTES] ⇒ 不硬塞；
 * - 读不出来的（权限/IO）⇒ 记原因，不当空文件。
 */
internal object BackupWalker {

    fun walk(mounts: List<BackupMount>): WalkResult {
        val files = ArrayList<PlannedFile>()
        val skipped = ArrayList<BackupSkip>()
        var budget = BackupRules.MAX_TOTAL_BYTES
        for (m in mounts) {
            if (!m.liveRoot.isDirectory) continue
            budget = collect(m, m.liveRoot, files, skipped, budget)
        }
        return WalkResult(files.sortedBy { it.zipPath }, skipped)
    }

    private fun collect(
        mount: BackupMount,
        dir: File,
        out: MutableList<PlannedFile>,
        skipped: MutableList<BackupSkip>,
        budgetLeft: Long,
    ): Long {
        val children = listDir(mount, dir, skipped) ?: return budgetLeft
        var left = budgetLeft
        for (f in children) left = visit(mount, f, out, skipped, left)
        return left
    }

    /** 列目录：列不出来是**异常**（权限/IO），不是"空目录"——所以记一条再返回 null。 */
    private fun listDir(mount: BackupMount, dir: File, skipped: MutableList<BackupSkip>): List<File>? {
        val raw = runCatching { dir.listFiles() }.getOrNull()
        if (raw == null) {
            skipped += BackupSkip(BackupLayout.relUnder(mount.liveRoot, dir), "目录列不出来（权限或 IO）")
            return null
        }
        return raw.sortedBy { it.name }
    }

    /** 一个子项的走向：隐藏项与密钥名直接跳，目录递归，文件走 [acceptFile]。 */
    private fun visit(
        mount: BackupMount,
        f: File,
        out: MutableList<PlannedFile>,
        skipped: MutableList<BackupSkip>,
        left: Long,
    ): Long {
        if (f.name.startsWith(".")) return left
        if (BackupRules.isSecretName(f.name)) {
            skipped += BackupSkip(BackupLayout.relUnder(mount.liveRoot, f), "名字像密钥，不进包")
            return left
        }
        if (f.isDirectory) {
            if (f.name in mount.skipDirNames) return left
            return collect(mount, f, out, skipped, left)
        }
        if (!f.isFile) return left
        return acceptFile(mount, f, out, skipped, left)
    }

    /** 配额两道：单文件上限、整包剩余量。超了只记原因，不硬塞也不静默。 */
    private fun acceptFile(
        mount: BackupMount,
        f: File,
        out: MutableList<PlannedFile>,
        skipped: MutableList<BackupSkip>,
        left: Long,
    ): Long {
        val rel = BackupLayout.relUnder(mount.liveRoot, f)
        val size = f.length()
        if (size > BackupRules.MAX_ENTRY_BYTES) {
            skipped += BackupSkip(rel, "单文件太大（$size 字节 > ${BackupRules.MAX_ENTRY_BYTES}）")
            return left
        }
        if (size > left) {
            skipped += BackupSkip(rel, "整包配额已满（上限 ${BackupRules.MAX_TOTAL_BYTES} 字节）")
            return left
        }
        out += PlannedFile(BackupLayout.zipPathOf(mount, rel), f, BackupLayout.groupOf(mount, rel))
        return left - size
    }
}
