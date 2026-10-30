// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候两本账的**落点扇出**（一处实现，[ScoutLedger] 与 [UnfinishedLedger] 共用）。
//
// 为什么要两个落点：派工单要"工作区账，重装不丢"，可**存储权限被关时工作区根本写不进去**——
// 而"用户一路驳回"恰恰就发生在权限被关的那段时间。只写工作区的话：
//   · 关掉存储权限 ⇒ [com.example.zhengdao.baopu.BaopuWorkspace.hostDir] 回落到 App 私有目录，
//     账本换了一本，别的能力的驳回次数全变回"还没问过" ⇒ 斥候会**再问一遍**（正好是要防的）；
//   · 只写 App 私有 ⇒ 卸载重装后账没了（派工单明确不许）。
// 所以两本都写：**首目录＝权威**（App 私有，任何权限状态下都写得进），
// 其余＝镜像（工作区 `记忆/`，卸载重装后还能找回）。读侧两本都读，同一能力取 `lastAtMs` 更新的那行。
//
// 本文件只管"行进哪几个文件"，不解析内容——解析各账各管（两本账的行格式不同）。
package com.example.zhengdao.baopu.scout

import com.example.zhengdao.core.RunLog
import java.io.File

internal class LedgerFanout(
    /** 落点目录：**首个是权威**，其余是镜像（见文件头）。 */
    dirs: List<File>,
    private val fileName: String,
) {
    private val roots: List<File> = dirs.distinctBy { it.absolutePath }

    init {
        require(roots.isNotEmpty()) { "ledger needs at least one directory" }
    }

    fun files(): List<File> = roots.map { File(it, fileName) }

    /** 权威那份（报告里报的"这本账在这"）。 */
    fun primary(): File = files().first()

    fun paths(): List<String> = files().map { it.absolutePath }

    /**
     * 追加一行到**每一个**落点。
     *
     * 返回**权威那份**的成败：镜像写不进去（没存储权限）不算失败，只记日志。
     */
    @Synchronized
    fun appendLine(line: String): Boolean {
        var primaryOk = false
        files().forEachIndexed { idx, f ->
            val ok = runCatching {
                f.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
                f.appendText(line + "\n", Charsets.UTF_8)
                true
            }.onFailure {
                RunLog.log("账本写入失败（${f.absolutePath}）：${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)
            if (idx == 0) primaryOk = ok
        }
        return primaryOk
    }

    /**
     * 读所有落点的**尾部**行（每个文件最多 [limit] 行）。
     *
     * 顺序：**镜像在前、权威在后**——调用方按顺序折叠、后者覆盖前者，冲突时权威胜。
     * 每个文件只读尾部：账本会越写越长，但每行都是绝对快照，截断只丢历史不丢当前值。
     */
    fun readTail(limit: Int): List<String> {
        val out = ArrayList<String>()
        files().reversed().forEach { f ->
            if (!f.isFile) return@forEach
            val tail = ArrayDeque<String>(limit)
            runCatching {
                f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (tail.size == limit) tail.removeFirst()
                        tail.addLast(line)
                    }
                }
            }.onFailure {
                RunLog.log("账本读取失败（${f.absolutePath}）：${it.javaClass.simpleName}: ${it.message}")
            }
            out += tail
        }
        return out
    }

    /** 全文写进每个落点（覆盖式派生视图用：一页能力清单）。首目录成败为准。 */
    @Synchronized
    fun writeAllText(text: String): Boolean {
        var primaryOk = false
        files().forEachIndexed { idx, f ->
            val ok = runCatching {
                f.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
                f.writeText(text, Charsets.UTF_8)
                true
            }.onFailure {
                RunLog.log("账本视图写入失败（${f.absolutePath}）：${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)
            if (idx == 0) primaryOk = ok
        }
        return primaryOk
    }

    /** 读**第一个存在**的那份全文（清单是覆盖式的：哪份都一样新）。 */
    fun readFirstText(): String? {
        files().forEach { f ->
            if (f.isFile) {
                return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            }
        }
        return null
    }
}
