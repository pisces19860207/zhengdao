// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §0.5/§1/§3（证道/知识库 三目录、原件只读、
// 迁移先备份→复制→校验、旧目录不删）；157 目录收口后工作区根为唯一根。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.library.FaceIndex
import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File

/**
 * `证道/知识库/{原始,整理,索引}` 的**路径唯一真源**（第一百八十二批）。
 *
 * 三条硬口径：
 * 1. **原始/ 只读保护**：[isInsideRaw] 让所有写盘口在落笔前拒掉原件区；
 *    迁移也只在「目标不存在」时新建，绝不覆盖已有原件；
 * 2. **索引是可重建缓存**：[indexDir] 整棵可删，文件（原始/整理）才是真相；
 * 3. **外部库只索引不搬**：[externalRoots] 给出只读索引源（投递、App 私有原件区、
 *    用户在 `外部库.txt` 里点名的目录），一个字节都不往知识库里搬。
 *
 * 本对象只做纯路径与文件搬运（[File] 进出），不碰 Context、不起线程 ⇒ JVM 直测。
 */
internal object KnowledgeLayout {

    const val DIR_KNOWLEDGE = "知识库"
    const val SUB_RAW = "原始"
    const val SUB_ORGANIZED = "整理"
    const val SUB_INDEX = "索引"

    /** 索引与快照文件名（都落 `知识库/索引/`）。 */
    const val FILE_INDEX = "知识库索引.json"
    const val FILE_SNAPSHOT = "快照.json"

    /** 用户点名外部库的那一行文件（`知识库/外部库.txt`，一行一个绝对路径）。 */
    const val FILE_EXTERNAL = "外部库.txt"

    /** 迁移/整理的备份根：`<工作区>/产物/_迁移存档/知识库/`（产物区，不会被索引目录误删）。 */
    const val DIR_MIGRATED_ARCHIVE = "_迁移存档"
    const val DIR_KB_ARCHIVE = "知识库"

    /** 迁移待删清单（append-only；程序不删任何原件）。 */
    const val FILE_MIGRATION_PENDING = "知识库迁移待删.txt"

    /** 整理台账（append-only JSONL；回滚只删本类写进 整理/ 的副本）。 */
    const val FILE_ORGANIZE_AUDIT = "_整理台账.jsonl"

    /** 单次迁移/整理文件数上限（触顶必须明示，不静默）。 */
    const val MAX_MIGRATE = LibraryPaths.MAX_SCAN

    // ── 路径 ────────────────────────────────────────────────────────────────

    fun knowledgeDir(workspace: File): File = File(workspace, DIR_KNOWLEDGE)

    fun rawDir(workspace: File): File = File(knowledgeDir(workspace), SUB_RAW)

    fun organizedDir(workspace: File): File = File(knowledgeDir(workspace), SUB_ORGANIZED)

    fun indexDir(workspace: File): File = File(knowledgeDir(workspace), SUB_INDEX)

    fun indexFile(workspace: File): File = File(indexDir(workspace), FILE_INDEX)

    fun snapshotFile(workspace: File): File = File(indexDir(workspace), FILE_SNAPSHOT)

    fun externalListFile(workspace: File): File = File(knowledgeDir(workspace), FILE_EXTERNAL)

    fun backupRoot(workspace: File): File =
        File(File(MemoryLayout.productsDir(workspace), DIR_MIGRATED_ARCHIVE), DIR_KB_ARCHIVE)

    fun migrationBackupDir(workspace: File): File = File(backupRoot(workspace), "迁移")

    fun migrationPendingFile(workspace: File): File =
        File(MemoryLayout.productsDir(workspace), FILE_MIGRATION_PENDING)

    fun organizeAuditFile(workspace: File): File = File(organizedDir(workspace), FILE_ORGANIZE_AUDIT)

    fun organizeBackupDir(workspace: File, runId: String): File =
        File(File(backupRoot(workspace), "整理"), runId)

    /** 旧共享原件区（157 前 `资料库/原始/`）——只作迁移来源，不新建、不写回。 */
    fun legacyRawDir(workspace: File): File = File(File(workspace, "资料库"), SUB_RAW)

    /** 建三个目录（幂等；失败不抛，返回是否可用）。 */
    fun ensureDirs(workspace: File): Boolean = runCatching {
        rawDir(workspace).mkdirs()
        organizedDir(workspace).mkdirs()
        indexDir(workspace).mkdirs()
        rawDir(workspace).isDirectory && organizedDir(workspace).isDirectory && indexDir(workspace).isDirectory
    }.getOrDefault(false)

    // ── 只读保护 ────────────────────────────────────────────────────────────

    /** 目标是否落在 `原始/` 里（含原件区本身）。写盘口必须先过这一问。 */
    fun isInsideRaw(workspace: File, target: File): Boolean =
        PathGuard.isInside(rawDir(workspace), target)

    /** 索引目录自己要排除（否则索引文件会把自己索引进去）。 */
    fun excludedFromIndex(relPath: String): Boolean =
        relPath == SUB_INDEX || relPath.startsWith("$SUB_INDEX/")

    /** 目标在知识库的合法落点（整理/ 或 索引/），且不在 原始/。 */
    fun isWritableTarget(workspace: File, target: File): Boolean {
        val k = knowledgeDir(workspace)
        if (!PathGuard.isInside(k, target)) return false
        return !isInsideRaw(workspace, target)
    }

    // ── 外部库（只索引不搬） ─────────────────────────────────────────────────

    data class ExternalRoot(
        /** 写进索引路径前缀里的短名（`外部/<name>/`；同名的自动加序号）。 */
        val name: String,
        /** 绝对目录。 */
        val dir: File,
        /** 人读来源：`投递`／`私有原始`／`外部库.txt 第 N 行`。 */
        val source: String,
    )

    /**
     * 当前要索引的外部根：默认 `投递/` ＋ App 私有原件区，再并上 `外部库.txt` 的每一行。
     *
     * 全部**只读**：调用方只对它们 stat/读内容，绝不写。目录不存在、相对路径、含 `..`
     * 的行一律跳过（不猜、不建）。知识库自身（或其子目录）不重复纳入。
     */
    fun externalRoots(workspace: File, filesDir: File): List<ExternalRoot> {
        val knowledge = knowledgeDir(workspace)
        val out = LinkedHashMap<String, ExternalRoot>()
        fun add(name: String, dir: File, source: String) {
            if (!dir.isDirectory) return
            val canonical = runCatching { dir.canonicalFile }.getOrElse { dir }
            if (PathGuard.isInside(knowledge, canonical) || canonical == knowledge) return
            val key = canonical.absolutePath
            if (out.containsKey(key)) return
            out[key] = ExternalRoot(uniqueName(out.values.map { it.name }.toSet(), name), canonical, source)
        }
        add("投递", LibraryPaths.deliverDirUnder(workspace), "投递")
        add("私有原始", LibraryPaths.rawDirUnder(filesDir), "私有原始")
        val list = externalListFile(workspace)
        if (list.isFile) {
            runCatching {
                list.readText(Charsets.UTF_8).lineSequence().forEachIndexed { i, raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                    if (!File(line).isAbsolute || line.contains("..")) return@forEachIndexed
                    val dir = File(line.trimEnd('/'))
                    add(dir.name.ifBlank { "外部${i + 1}" }, dir, "${FILE_EXTERNAL} 第 ${i + 1} 行")
                }
            }
        }
        return out.values.toList()
    }

    /** 同名去重（`a`, `a-2`, `a-3`…）。 */
    private fun uniqueName(taken: Set<String>, base: String): String {
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    // ── 迁移（先备份→复制→校验；旧目录一个字节都不删） ─────────────────────

    data class MigrationReport(
        val copied: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val truncated: Boolean = false,
        val notes: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = copied == 0 && skipped == 0 && failed == 0 && notes.isEmpty()

        fun summary(): String =
            "知识库迁移：复制 $copied 件，跳过 $skipped 件，失败 $failed 件" +
                (if (truncated) "（达到单次上限，其余下次继续）" else "")
    }

    /**
     * 把旧 `资料库/原始/` 搬到 `知识库/原始/`：**只复制、不覆盖、不删源**。
     *
     * 每件四步：① 备份进 `产物/_迁移存档/知识库/迁移/`；② 目标不存在才复制；
     * ③ 三处（源/备份/副本）字节数＋sha256 对账；④ 待删清单登记。
     * 目标已存在且哈希一致 ⇒ 跳过（幂等）；哈希不同 ⇒ 跳过并记账（宁可不搬，不许覆盖）。
     */
    fun migrateLegacy(workspace: File): MigrationReport {
        val src = legacyRawDir(workspace)
        if (!src.isDirectory) return MigrationReport()
        ensureDirs(workspace)
        val scan = LibraryPaths.scanTree(src)
        var copied = 0
        var skipped = 0
        var failed = 0
        val notes = mutableListOf<String>()
        for (e in scan.files) {
            if (copied + skipped + failed >= MAX_MIGRATE) {
                return MigrationReport(copied, skipped, failed, true, notes)
            }
            val source = File(src, e.relPath)
            val target = File(rawDir(workspace), e.relPath)
            if (target.exists()) {
                skipped++
                continue
            }
            val sha = runCatching { FaceIndex.sha256(source) }.getOrNull()
            if (sha == null) {
                failed++
                notes += "${e.relPath} 读不出摘要，留在原处"
                continue
            }
            val backup = File(migrationBackupDir(workspace), e.relPath)
            val backed = backupIfAbsent(source, backup, sha)
            if (!backed) {
                failed++
                notes += "${e.relPath} 备份失败，整件没动"
                continue
            }
            val ok = runCatching {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = false)
                target.isFile
            }.getOrDefault(false)
            if (!ok || !bytesMatch(target, sha, source.length())) {
                runCatching { target.delete() } // 只撤自己刚写的副本
                failed++
                notes += "${e.relPath} 复制/校验失败 ⇒ 副本已撤回，原件未动"
                continue
            }
            copied++
            appendPending(workspace, e.relPath, source.length(), sha)
        }
        return MigrationReport(copied, skipped, failed, scan.truncated, notes)
    }

    /** 备份（目标已存在且内容一致 ⇒ 直接用；不一致 ⇒ 不覆盖、判失败）。 */
    private fun backupIfAbsent(source: File, backup: File, sha: String): Boolean {
        if (backup.isFile) return bytesMatch(backup, sha, source.length())
        return runCatching {
            backup.parentFile?.mkdirs()
            source.copyTo(backup, overwrite = false)
            bytesMatch(backup, sha, source.length())
        }.getOrDefault(false)
    }

    private fun appendPending(workspace: File, rel: String, bytes: Long, sha: String) {
        runCatching {
            val f = migrationPendingFile(workspace)
            if (!f.isFile) {
                f.parentFile?.mkdirs()
                f.writeText(
                    "# 旧 资料库/原始 已复制进 知识库/原始 · 旧文件一个都没删，删不删归你\n\n" +
                        "每行 `- 相对路径 | 字节 | sha256=摘要`；复制与三处对账通过才登记。\n" +
                        "备份在 产物/_迁移存档/知识库/迁移/。\n\n",
                    Charsets.UTF_8,
                )
            }
            f.appendText("- $rel | $bytes | sha256=$sha\n", Charsets.UTF_8)
        }
    }

    private fun bytesMatch(file: File, sha: String, bytes: Long): Boolean =
        file.isFile && file.length() == bytes &&
            runCatching { FaceIndex.sha256(file) == sha }.getOrDefault(false)

    /** 扫描一棵树（只给整理用；与 LibraryPaths 同一把尺子，跳软链/垃圾名）。 */
    fun scan(dir: File): LibraryPaths.ScanResult = LibraryPaths.scanTree(dir)
}
