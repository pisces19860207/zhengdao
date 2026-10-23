// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.util.TimeFormats
import java.io.File

/**
 * 记忆**写前快照**（157 批，用户原话「修正前备份到 `filesDir/抱朴记忆/备份/`」）。
 *
 * 收尾流程四步（用户「记忆目录约定」）：收尾 → 更新/修正本工作区记忆 →
 * **写前备份** → 重建索引。本类就是第三步：把 `记忆/` 整树复制成一份带戳快照，
 * 只留最近 [BaopuKernel.Data.MEMORY_BACKUP_KEEP] 份（轮转）。
 *
 * 两条硬口径：
 * ① **绝不写进工作区**——备份根在 App 私有（`filesDir/抱朴记忆/备份/`），
 *    工作区里看得见的那四个目录一个字节不碰（用户「备份不得写进工作区」）；
 * ② **保留份数至少 1**（构造器 `require`）——keep=0 等于每次备份完就把自己
 *    删干净，安全网报废；改这个数必须在账上留理由。
 *
 * 原子口径同 [LibraryPaths.atomicWrite]：先写 `.tmp` 再 rename，失败清场，
 * 不留半个快照（半份快照比没有更坏——会被当成能回滚的）。
 */
internal class MemoryBackup(
    /** 快照根（`filesDir/抱朴记忆/备份/`；由 [MemoryLayout.memoryBackupDir] 派生）。 */
    private val backupRoot: File,
    /** 保留份数（默认内核值 5；只许 ≥1）。 */
    private val keep: Int = BaopuKernel.Data.MEMORY_BACKUP_KEEP,
) {
    init {
        require(keep >= 1) {
            "记忆快照保留份数至少 1（keep=$keep ＝ 备份全删，安全网报废）"
        }
    }

    /** 快照目录名（`TimeFormats.fileStamp` 秒级；字典序＝时间序 ⇒ 轮转按名排即可）。 */
    private fun stampOf(nowMs: Long): String = TimeFormats.fileStamp(nowMs)

    /**
     * 快照 [memoryRoot]（递归复制）成 `<backupRoot>/<stamp>/`。
     * 同秒第二份 ⇒ 名字追 `-2`/`-3`（**不覆盖**旧快照——覆盖等于没有备份）。
     *
     * @return 快照目录；[memoryRoot] 不是目录/复制失败 ⇒ null（调用方放弃本次修正）
     */
    fun snapshot(memoryRoot: File, nowMs: Long): File? {
        if (!memoryRoot.isDirectory) return null
        runCatching { backupRoot.mkdirs() }
        val base = File(backupRoot, stampOf(nowMs))
        var target = base
        var n = 2
        while (target.exists()) {
            target = File(backupRoot, "${base.name}-$n")
            n++
        }
        val tmp = File(backupRoot, target.name + ".tmp")
        runCatching { tmp.deleteRecursively() }
        val ok = runCatching { memoryRoot.copyRecursively(tmp, overwrite = true) }
            .getOrDefault(false)
        if (!ok || !tmp.isDirectory) {
            runCatching { tmp.deleteRecursively() }
            return null
        }
        if (tmp.renameTo(target)) return target
        // rename 失败（极端：同卷内不该发生）⇒ tmp 留着当这一份，轮转照样管它
        return tmp.takeIf { it.isDirectory }
    }

    /** 现有快照（**新→旧**；含 rename 失败留下的 `.tmp` 份——轮转不分名字）。 */
    fun snapshots(): List<File> =
        backupRoot.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    /**
     * 轮转：只留最近 [keep] 份，其余删掉。
     * @return 删掉的份数
     */
    fun rotate(): Int {
        val all = snapshots()
        if (all.size <= keep) return 0
        var deleted = 0
        all.drop(keep).forEach { f ->
            if (runCatching { f.deleteRecursively() }.getOrDefault(false)) deleted++
        }
        return deleted
    }
}
