// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2 §1（存储：本地索引文件，不把原文
// 复制到云端；索引损坏/缺失可重建，不阻塞对话）。设计页 §3。
// 房型与 `LibraryPaths.atomicWrite`（原子写）对齐；Context 只出现在壳里。
package com.example.zhengdao.baopu.kb

import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File

/**
 * 索引文件的读写壳（第一百二十八批）——**薄壳**：落点计算与读写在这里，
 * 格式与内容在 [KbIndexCodec]／[KbIndexer]，Context 到壳为止。
 *
 * ## 落点
 * `filesDir/baopu/kb/index.json`（App 私有 ⇒ 天然不上云、终端 agent 也够不着；
 * 卸载/清数据会丢，但索引是**可重建缓存**，丢了就重建，不是资料丢失）。
 *
 * ## 损坏恢复
 * [load] 读不出/JSON 坏/格式版本不符 ⇒ 返回 null（＝没有索引）。**不删损坏文件**——
 * 留着给人看是怎么坏的；重建只是下一次 [save] 覆盖它。
 *
 * ## 进程内缓存
 * [loadCached] 按 `大小:mtime` 失效：自动注入每轮都要读一次，全量读 JSON 太费；
 * 索引被 [save] 改写后 stamp 变，下一轮自然读到新的。
 */
internal class KbIndexStore(private val filesDir: File) {

    private val dir: File = File(File(filesDir, DIR_BAOPU), DIR_KB)
    private val file: File = File(dir, FILE_NAME)

    @Volatile
    private var cache: KbIndex? = null

    @Volatile
    private var cacheStamp: String? = null

    /** 读索引（损坏/缺失 ⇒ null）。 */
    fun load(): KbIndex? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return KbIndexCodec.decode(text)
    }

    /**
     * 带进程内缓存的读（自动注入链路用；文件被改写后自动失效）。
     *
     * 第一百八十二批：再并一份**知识库自动刷新索引**（`证道/知识库/索引/知识库索引.json`，
     * 由装配层通过 [installExtra] 注入路径闭包）。只在**读侧**合并，[load] 仍只读主索引——
     * 这样 `kb_index` 拿去当增量 `previous` 的仍是它自己那份，不会把知识库文档抄进主索引。
     * 额外索引缺失/损坏 ⇒ 静默回落主索引（可重建，不阻塞对话）。
     */
    fun loadCached(): KbIndex? {
        val extra = runCatching { extraProvider?.invoke() }.getOrNull()
        val extraStamp = extra?.let { runCatching { "${it.length()}:${it.lastModified()}" }.getOrNull() } ?: "-"
        val stamp = runCatching { "${file.length()}:${file.lastModified()}|$extraStamp" }.getOrNull() ?: run {
            cache = null
            cacheStamp = null
            return null
        }
        if (stamp == cacheStamp) return cache
        val merged = merge(load(), extra?.let { read(it) })
        cache = merged
        cacheStamp = stamp
        return merged
    }

    /** 读一份索引文件（损坏/缺失 ⇒ null）。 */
    private fun read(f: File): KbIndex? {
        if (!f.isFile) return null
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return KbIndexCodec.decode(text)
    }

    /** 原子写（`.tmp` + rename；失败返回 false，调用方如实报账，不静默当成功）。 */
    fun save(index: KbIndex): Boolean =
        LibraryPaths.atomicWrite(file, KbIndexCodec.encode(index))

    /** 索引文件路径（日志/排查用）。 */
    fun indexPath(): File = file

    /**
     * 合并主索引与知识库自动索引（读侧）。两份都以 relPath 去重（主索引先到先得），
     * 缺失一份就用另一份；两份都缺 ⇒ null。
     */
    private fun merge(primary: KbIndex?, extra: KbIndex?): KbIndex? {
        if (primary == null) return extra
        if (extra == null) return primary
        val seen = primary.docs.mapTo(HashSet()) { it.relPath }
        val docs = primary.docs + extra.docs.filter { it.relPath !in seen }
        val s1 = primary.stats
        val s2 = extra.stats
        return primary.copy(
            rootLabel = if (extra.docs.isEmpty()) primary.rootLabel else primary.rootLabel + "＋知识库",
            updatedAt = maxOf(primary.updatedAt, extra.updatedAt),
            docs = docs,
            stats = KbStats(
                filesIndexed = s1.filesIndexed + s2.filesIndexed,
                filesSkipped = s1.filesSkipped + s2.filesSkipped,
                chunksTotal = s1.chunksTotal + s2.chunksTotal,
                truncated = s1.truncated || s2.truncated,
                skippedBig = s1.skippedBig + s2.skippedBig,
                skippedBinary = s1.skippedBinary + s2.skippedBinary,
                skippedUnclaimed = s1.skippedUnclaimed + s2.skippedUnclaimed,
                skippedExcluded = s1.skippedExcluded + s2.skippedExcluded,
                reused = s1.reused + s2.reused,
            ),
        )
    }

    companion object {
        const val DIR_BAOPU = "baopu"
        const val DIR_KB = "kb"
        const val FILE_NAME = "index.json"

        /**
         * 知识库自动索引的路径闭包（装配层注入；晚绑定 ⇒ 换工作区后自动跟新）。
         * 只存函数类型，不存 Context（StaticFieldLeak 的教训，与 MediaPublisher 同房型）。
         */
        @Volatile
        private var extraProvider: (() -> File?)? = null

        /** 装/卸额外索引（幂等；传 null 卸掉）。 */
        fun installExtra(provider: (() -> File?)?) {
            extraProvider = provider
        }
    }
}
