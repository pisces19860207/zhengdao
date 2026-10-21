// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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

    /** 带进程内缓存的读（自动注入链路用；文件被改写后自动失效）。 */
    fun loadCached(): KbIndex? {
        val stamp = runCatching { "${file.length()}:${file.lastModified()}" }.getOrNull() ?: run {
            cache = null
            cacheStamp = null
            return null
        }
        if (stamp == cacheStamp) return cache
        val idx = load()
        cache = idx
        cacheStamp = stamp
        return idx
    }

    /** 原子写（`.tmp` + rename；失败返回 false，调用方如实报账，不静默当成功）。 */
    fun save(index: KbIndex): Boolean =
        LibraryPaths.atomicWrite(file, KbIndexCodec.encode(index))

    /** 索引文件路径（日志/排查用）。 */
    fun indexPath(): File = file

    companion object {
        const val DIR_BAOPU = "baopu"
        const val DIR_KB = "kb"
        const val FILE_NAME = "index.json"
    }
}
