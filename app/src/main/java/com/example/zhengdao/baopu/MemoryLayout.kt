// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.rootfs.PathGuard
import java.io.File

/**
 * 记忆与工作区目录布局（157 批，用户 2026-10-23 拍板）——**路径唯一真源**。
 *
 * 目录名本身在 [BaopuKernel.Data]（内核单一真相源，`KernelGuardTest` 盯着）；
 * 本对象只做三件不许进内核的事（内核禁 Android/IO）：
 * ① 路径派生（工作区侧 + App 私有侧）；
 * ② 写入边界 [allowedWrite]（只许 `记忆/` 整棵目录树——用户「写入边界」原话）；
 * ③ 初始化 [ensureDirs] 与旧布局迁移 [migrate]（幂等、不丢文件）。
 *
 * ## 目录（用户「目录收口」口径，验收＝工作区可见目录恰四个）
 * - `<工作区>/产物/` —— 抱朴唯一对外输出口（旧 `资料库/整理`）；
 * - `<工作区>/投递/` —— 用户丢原件处（旧 `资料库/投递`，退回工作区根）；
 * - `<工作区>/备份/` —— App 管的改前原文件快照，**模型只读**（旧 `.baopu-bak`）；
 * - `<工作区>/记忆/` —— 每日日记＋项目记忆＋归档；回收站 `.回收站/` 隐藏保留不动。
 *
 * ## 记忆三层（用户「记忆目录约定」口径）
 * - 工作区级：`<工作区>/记忆/日记/YYYY-MM-DD.md`（跟工作区走，147 已有）；
 * - 项目级：`<工作区>/记忆/项目记忆.md`（旧根只读回退，见 [legacyProjectMemoryFile]）；
 * - 归档：`<工作区>/记忆/归档/`（147 维护三连）。
 * - 全局一份在 App 私有：`filesDir/抱朴记忆/全局记忆.md`＋`filesDir/抱朴记忆/备份/`
 *   （**不进工作区**；全局与工作区不许互相覆盖——两个根不同，边界判据钉在 [GlobalMemory]）。
 *
 * 纯 [File] 进出、不碰 Context、不建线程 ⇒ 临时目录 JVM 直测。
 */
internal object MemoryLayout {

    /**
     * `项目记忆.md` 不存在时给的一句可读提示（157：用户「给一句可读提示，
     * 让抱朴在收尾时自己写」）——由组装层注入上下文，抱朴收尾时补写。
     */
    const val MISSING_HINT =
        "（这个项目还没有项目记忆。收尾时把「本工作区的口径／这批留在哪／取证要点」" +
            "写进 <工作区>/记忆/项目记忆.md——只写这一处，别写到别处。）"

    // ── 工作区侧 ──────────────────────────────────────────────────────────────

    /** 记忆根（`<工作区>/记忆/`）。 */
    fun memoryDir(workspace: File): File = File(workspace, BaopuKernel.Data.DIR_MEMORY)

    /** 每日日记目录（`<工作区>/记忆/日记/`）。 */
    fun diaryDir(workspace: File): File = File(memoryDir(workspace), BaopuKernel.Data.DIR_DIARY)

    /** 归档目录（`<工作区>/记忆/归档/`；157 起从 `日记/归档/` 上移一级）。 */
    fun archiveDir(workspace: File): File = File(memoryDir(workspace), BaopuKernel.Data.DIR_ARCHIVE)

    /** 项目记忆（`<工作区>/记忆/项目记忆.md`；写入只认这一处）。 */
    fun projectMemoryFile(workspace: File): File =
        File(memoryDir(workspace), BaopuKernel.Data.FILE_PROJECT_MEMORY)

    /** 旧根回退（147 前写在 `<工作区>/项目记忆.md`）：**只读兼容**，新写入不回这里。 */
    fun legacyProjectMemoryFile(workspace: File): File =
        File(workspace, BaopuKernel.Data.FILE_PROJECT_MEMORY)

    /** 抱朴唯一对外输出口（`<工作区>/产物/`）。 */
    fun productsDir(workspace: File): File = File(workspace, BaopuKernel.Data.DIR_PRODUCTS)

    /** 用户投递目录（`<工作区>/投递/`）。 */
    fun deliverDir(workspace: File): File = File(workspace, BaopuKernel.Data.DIR_DELIVER)

    /** 改前快照目录（`<工作区>/备份/`；App 管、模型只读）。 */
    fun backupDir(workspace: File): File = File(workspace, BaopuKernel.Data.DIR_BACKUP)

    // ── App 私有侧（filesDir；不进工作区） ────────────────────────────────────

    /** 全局记忆根（`filesDir/抱朴记忆/`）。 */
    fun globalMemoryDir(filesDir: File): File = File(filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY)

    /** 全局记忆（`filesDir/抱朴记忆/全局记忆.md`）。 */
    fun globalMemoryFile(filesDir: File): File =
        File(globalMemoryDir(filesDir), BaopuKernel.Data.FILE_GLOBAL_MEMORY)

    /** 记忆写前快照（`filesDir/抱朴记忆/备份/`；轮转见 [MemoryBackup]）。 */
    fun memoryBackupDir(filesDir: File): File =
        File(globalMemoryDir(filesDir), BaopuKernel.Data.DIR_MEMORY_BACKUP)

    /** 私有原件区（与 [LibraryPaths.rawDirUnder] 同一处；旧 `资料库/原始` 的迁移目的地）。 */
    fun rawPrivateDir(filesDir: File): File = LibraryPaths.rawDirUnder(filesDir)

    // ── 判据 ──────────────────────────────────────────────────────────────────

    /**
     * 写入边界（用户原话「写入边界只许 `<工作区>/记忆/` 整棵和 项目记忆.md」）：
     * 项目记忆已在 `记忆/` 下 ⇒ 判据就是「目标在 `记忆/` 内」。
     *
     * 组件级比较（[PathGuard.isInside]，防 `记忆X/` 这种同前缀兄弟目录蹭过）。
     * 纯函数：喂 [File] 进，出结论 ⇒ JVM 直测（正例：日记/项目记忆/归档；负例：
     * 产物/投递/备份/旧根/工作区根本身/`记忆X/`）。
     */
    fun allowedWrite(workspace: File, target: File): Boolean =
        PathGuard.isInside(memoryDir(workspace), target)

    /**
     * 初始化（用户「进入工作区自动建空 `记忆/日记/`，不弹卡、不写内容」）。
     * 只建目录、不写任何文件；幂等；失败不抛（调用方记日志）。
     */
    fun ensureDirs(workspace: File) {
        runCatching { diaryDir(workspace).mkdirs() }
    }

    // ── 旧布局迁移（用户「旧路径要有迁移或兼容，不能把已有的资料库文件弄丢」） ────

    /** 迁移报告（落 RunLog 一行摘要；[isEmpty] 供「二遍全零」幂等判据）。 */
    data class MigrationReport(
        val movedProducts: Int = 0,
        val movedDeliver: Int = 0,
        val movedBackup: Int = 0,
        val movedRawPrivate: Int = 0,
        val movedRevisedArchive: Int = 0,
        val movedProjectMemory: Boolean = false,
        /** 冲突/失败跳过的文件（目标已存在 ⇒ 跳过，绝不覆盖）。 */
        val skipped: List<String> = emptyList(),
        /** 搬空后删掉的旧目录名。 */
        val removedDirs: List<String> = emptyList(),
    ) {
        val totalMoved: Int
            get() = movedProducts + movedDeliver + movedBackup + movedRawPrivate +
                movedRevisedArchive + (if (movedProjectMemory) 1 else 0)

        val isEmpty: Boolean get() = totalMoved == 0 && skipped.isEmpty() && removedDirs.isEmpty()

        /** 人读的一行摘要（RunLog 用）。 */
        fun summary(): String =
            "记忆目录迁移：搬 $totalMoved 项" +
                (if (movedProjectMemory) "（含项目记忆）" else "") +
                (if (skipped.isEmpty()) "" else "，跳过 ${skipped.size} 项") +
                (if (removedDirs.isEmpty()) "" else "，清掉旧目录 ${removedDirs.joinToString("、")}")
    }

    /**
     * 旧布局 → 新四目录（**幂等、不丢文件**）：
     * 1. `资料库/整理/` 整棵 → `产物/`；`资料库/投递/` 整棵 → `投递/`；
     *    `资料库/原始/` 整棵 → App 私有原件区；`资料库/改版/` 整棵 → `产物/_迁移存档/改版/`；
     *    `.baopu-bak/` 整棵 → `备份/`；根 `项目记忆.md` → `记忆/项目记忆.md`；
     * 2. **冲突不覆盖**：目标同名已存在 ⇒ 跳过并记账（保新的也不丢旧的）；
     * 3. **只删空目录**：搬空后 `资料库/`、`.baopu-bak/`（含其下搬空的子目录）才删；非空留着下遍接着搬；
     * 4. 跨卷 rename 失败 ⇒ 复制后删源；复制也失败 ⇒ 留在原处并记账（绝不丢）；
     * 5. 结尾 [ensureDirs]（顺带保证 `记忆/日记/` 在）。
     *
     * 调用点：`BaopuRuntime` 构造处（进抱朴＝进工作区）；失败只记日志不阻塞。
     */
    fun migrate(workspace: File, filesDir: File): MigrationReport {
        val skipped = mutableListOf<String>()
        val legacyLibrary = File(workspace, BaopuKernel.Data.LEGACY_LIBRARY_DIR)
        val legacyBackup = File(workspace, BaopuKernel.Data.LEGACY_BACKUP_DIR)

        var movedProducts = 0
        var movedDeliver = 0
        var movedBackup = 0
        var movedRawPrivate = 0
        var movedRevisedArchive = 0
        var movedProjectMemory = false

        // ① 整理 → 产物（抱朴唯一对外输出口）
        movedProducts = moveTreeInto(
            File(legacyLibrary, "整理"), productsDir(workspace), skipped,
        )
        // ② 投递 → 工作区根（不再挂「资料库」层）
        movedDeliver = moveTreeInto(
            File(legacyLibrary, BaopuKernel.Data.LEGACY_LIBRARY_DELIVER.substringAfterLast('/')),
            deliverDir(workspace), skipped,
        )
        // ③ 原始 → App 私有原件区（定版设计：原件只增不减、终端 agent 读不到）
        movedRawPrivate = moveTreeInto(
            File(legacyLibrary, "原始"), rawPrivateDir(filesDir), skipped,
        )
        // ④ 改版（死目录，revisedDir 全仓无引用）→ 产物/_迁移存档/改版/
        movedRevisedArchive = moveTreeInto(
            File(legacyLibrary, "改版"),
            File(File(productsDir(workspace), BaopuKernel.Data.DIR_MIGRATED_ARCHIVE), "改版"),
            skipped,
        )
        // ⑤ .baopu-bak → 备份/
        movedBackup = moveTreeInto(legacyBackup, backupDir(workspace), skipped)
        // ⑥ 根 项目记忆.md → 记忆/项目记忆.md（目标已存在 ⇒ 跳过并记账）
        val legacyPm = legacyProjectMemoryFile(workspace)
        val pm = projectMemoryFile(workspace)
        if (legacyPm.isFile) {
            if (pm.isFile) {
                skipped += "${legacyPm.name}（工作区根）→ 记忆/项目记忆.md 已存在，跳过（旧根文件留着，只读回退）"
            } else {
                pm.parentFile?.mkdirs()
                movedProjectMemory = legacyPm.renameTo(pm) ||
                    runCatching {
                        legacyPm.copyTo(pm, overwrite = false)
                        pm.isFile && legacyPm.delete()
                    }.getOrDefault(false)
                if (!movedProjectMemory) {
                    skipped += "${legacyPm.name}（工作区根）搬移失败，留在原处（只读回退仍生效）"
                }
            }
        }

        // ⑦ 只删空目录（非空一律留——下遍接着搬）
        val removedDirs = mutableListOf<String>()
        if (removeIfEmpty(legacyLibrary)) removedDirs += BaopuKernel.Data.LEGACY_LIBRARY_DIR
        if (removeIfEmpty(legacyBackup)) removedDirs += BaopuKernel.Data.LEGACY_BACKUP_DIR

        ensureDirs(workspace)
        return MigrationReport(
            movedProducts = movedProducts,
            movedDeliver = movedDeliver,
            movedBackup = movedBackup,
            movedRawPrivate = movedRawPrivate,
            movedRevisedArchive = movedRevisedArchive,
            movedProjectMemory = movedProjectMemory,
            skipped = skipped,
            removedDirs = removedDirs,
        )
    }

    /**
     * 把 [src] 目录树的**内容**（递归）搬进 [dst]；冲突跳过并记账，绝不覆盖。
     * 搬空后 [src] 自己如果是空目录也删掉（只删空的——用户「只删空目录」口径），
     * 这样祖父目录（`资料库/`）才可能被 [removeIfEmpty] 收掉。
     * @return 搬移成功的文件数
     */
    private fun moveTreeInto(src: File, dst: File, skipped: MutableList<String>): Int {
        if (!src.isDirectory) return 0
        var moved = 0
        val children = src.listFiles() ?: return 0
        for (f in children) {
            val target = File(dst, f.name)
            when {
                f.isDirectory -> moved += moveTreeInto(f, target, skipped)
                f.isFile -> {
                    if (target.exists()) {
                        skipped += "${f.path} → 目标已存在，跳过（不覆盖）"
                        continue
                    }
                    target.parentFile?.mkdirs()
                    if (f.renameTo(target)) {
                        moved++
                    } else {
                        // 跨卷/权限 ⇒ 复制后删源；复制失败 ⇒ 原样留下并记账（不丢）
                        val copied = runCatching {
                            f.copyTo(target, overwrite = false)
                            target.isFile && f.delete()
                        }.getOrDefault(false)
                        if (copied) moved++ else skipped += "${f.path} 搬移失败，留在原处"
                    }
                }
            }
        }
        // 搬空了才删自己（还有冲突跳过/失败留下的文件 ⇒ 非空，留着下遍接着搬）
        val left = src.listFiles()
        if (left != null && left.isEmpty()) runCatching { src.delete() }
        return moved
    }

    /** 空了才删（返回是否删掉）；非空/不存在 ⇒ false。 */
    private fun removeIfEmpty(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val left = dir.listFiles() ?: return false
        if (left.isNotEmpty()) return false
        return dir.delete()
    }
}
