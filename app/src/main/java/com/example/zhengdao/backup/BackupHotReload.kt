// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批（设计修订，用户 2026-11-06）三「导入后必须热加载」：
// 「导入成功 ⇒ invalidate 索引与记忆缓存 ⇒ 当前会话立刻能查到；不许要求重启 App 或新开会话」。
//
// 这一条最容易漏，因为**文件写对了不等于读到的是新的**：证道有两本派生账
// ① `baopu.db` 的 memory 表（记忆索引）② `search.db`（检索索引）。
// 本类就是"导入完立刻把那两本账对齐到盘上现状"的那一步。
//
// 纪律：本文件不收 Context、不 import Android ⇒ 记忆那一路可以真跑单测（真临时目录、
// 真 [MemoryStore]）；检索那一路在 [BackupShell.reloadPorts] 里接生产实现，由源码门禁钉调用点。
package com.example.zhengdao.backup

import com.example.zhengdao.baopu.MemoryStore

/**
 * 导入后要刷的两本索引（端口注入：生产走 Android，测试走真 [MemoryStore]／假件）。
 *
 * 两个方法都必须**幂等**：同一个包导两次、或用户手点两次，结果一样。
 */
internal interface ReloadPorts {

    /** 记忆索引：按盘上现状重建，返回重建到的条目数。 */
    fun reindexMemory(): Int

    /** 检索索引：增量同步一次；`false` ＝ 本机还没有索引库（**不在这里建库**，建库是用户点"扫描本机"的事）。 */
    fun resyncSearch(): Boolean
}

/** 一次热加载的读数（工具回执与日志都要贴，好让"到底刷没刷"这件事有据可查）。 */
internal data class ReloadReport(
    val memoryEntries: Int,
    val searchResynced: Boolean,
    val skippedReason: String? = null,
) {
    /** 一句话（进工具回执）。 */
    fun line(): String = when {
        skippedReason != null -> "热加载：跳过（$skippedReason）"
        else -> "热加载：记忆索引 $memoryEntries 条；" +
            if (searchResynced) "检索索引已同步（本会话立刻可搜到）" else "本机没有检索索引，跳过（不在此处建库）"
    }
}

/**
 * 导入成功之后**立刻**跑一次索引对齐。
 *
 * 为什么只在这几组上跑：设置的还原不产生任何需要索引的东西（只动 SharedPreferences），
 * 白跑一趟只会在导入回执里写一句没意义的话。
 */
internal object BackupHotReload {

    /** 会产生"需要重读的东西"的分组（账本／人设与记忆／技能）。 */
    private val INDEXED_GROUPS = setOf(BackupGroup.LEDGER, BackupGroup.MEMORY, BackupGroup.SKILLS)

    fun afterImport(touched: Set<BackupGroup>, ports: ReloadPorts): ReloadReport {
        if (touched.none { it in INDEXED_GROUPS }) {
            return ReloadReport(0, false, skippedReason = "这次只改设置，没有要重建的索引")
        }
        val entries = runCatching { ports.reindexMemory() }.getOrDefault(-1)
        val synced = runCatching { ports.resyncSearch() }.getOrDefault(false)
        return when {
            entries < 0 -> ReloadReport(0, synced, skippedReason = "记忆索引重建失败（见日志），文件已经写进去了")
            else -> ReloadReport(entries, synced)
        }
    }
}

/**
 * 只带记忆那一路的端口（JVM 可直接跑：真目录、真 [MemoryStore]）。
 *
 * 生产侧在 `BackupShell.reloadPorts` 里包一层检索同步，两件事分开是为了**能单测**：
 * 检索那本账要 `search.db`（Android SQLite），JVM 里跑不起来。
 */
internal class MemoryReloadPort(private val store: MemoryStore) : ReloadPorts {

    override fun reindexMemory(): Int = store.reindex().entries.size

    override fun resyncSearch(): Boolean = false
}
