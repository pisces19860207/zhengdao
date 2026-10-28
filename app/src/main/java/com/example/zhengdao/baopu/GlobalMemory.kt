// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog
import java.io.File

/**
 * **全局记忆**（157 批，用户「App 私有、全局一份」）：
 * `filesDir/抱朴记忆/全局记忆.md`，装跨工作区的偏好/人设事实。
 *
 * 三条口径（用户「记忆目录约定」原话）：
 * ① **不进工作区**——根在 App 私有（[MemoryLayout.globalMemoryDir]），
 *    工作区可见四目录（产物/回收站/备份/记忆）一个字节不沾；
 * ② **全局与工作区不互覆**——两个根不同；边界判据由
 *    [MemoryLayout.allowedWrite] 负例钉死（全局文件路径不得作为工作区写入目标放行）；
 * ③ **本批只做存管，不接注入**——接线（拼进组装层）另批，账上留痕；
 *    现在没有任何调用方读它，模型也看不见它（先有存管，再接注入）。
 */
internal object GlobalMemory {

    /** 全局记忆文件（`filesDir/抱朴记忆/全局记忆.md`）。 */
    fun fileIn(filesDir: File): File = MemoryLayout.globalMemoryFile(filesDir)

    /**
     * 「不存在 / 空 / 读不动 / 有内容」四态（B 段 #10）。
     *
     * 改前 [load] 一句 `runCatching.getOrNull()` 把四态全折成 null：磁盘上的全局记忆**读坏了**
     * 在调用方和日志里跟「还没写过」一模一样——而下一条 [save] 会拿整份覆盖（数据丢失放大点）。
     */
    sealed interface GlobalMemoryRead {
        /** 没写过（本来就没有）。 */
        data object Absent : GlobalMemoryRead

        /** 文件在，但全是空白 ⇒ 按没有（沿用 [load] 的原口径）。 */
        data object Blank : GlobalMemoryRead

        /** 读到了内容。 */
        data class Ok(val text: String) : GlobalMemoryRead

        /** 文件在却读不动（权限/磁盘/被别的进程占着）——这才是原缺陷吞掉的那一格。 */
        data class Unreadable(val why: String, val bytes: Long) : GlobalMemoryRead
    }

    /** 四态读盘（[read] 可注入 ⇒ JVM 直测：真实故障在本机造不出来，注入一条异常就是）。 */
    fun loadState(
        filesDir: File,
        read: (File) -> String = { it.readText(Charsets.UTF_8) },
    ): GlobalMemoryRead {
        val f = fileIn(filesDir)
        if (!f.isFile) return GlobalMemoryRead.Absent
        val r = runCatching { read(f) }
        val text = r.getOrNull()
            ?: return GlobalMemoryRead.Unreadable(
                r.exceptionOrNull()?.javaClass?.simpleName ?: "未知",
                runCatching { f.length() }.getOrDefault(0L),
            )
        return if (text.isBlank()) GlobalMemoryRead.Blank else GlobalMemoryRead.Ok(text)
    }

    /**
     * 读；**不存在/空 ⇒ null（原口径一字不改）**，读不动 ⇒ null ＋ 说清楚是读坏了。
     *
     * 降级是允许的（157 §0：本批只做存管，没人依赖这一份），但不许**悄悄**降级。
     */
    fun load(
        filesDir: File,
        log: (String) -> Unit = { RunLog.log(it) },
        read: (File) -> String = { it.readText(Charsets.UTF_8) },
    ): String? = when (val r = loadState(filesDir, read)) {
        GlobalMemoryRead.Absent, GlobalMemoryRead.Blank -> null
        is GlobalMemoryRead.Ok -> r.text
        is GlobalMemoryRead.Unreadable -> {
            log(
                "抱朴全局记忆：文件在但读不动（${r.why}，${r.bytes} 字节）——本轮按「没有」跑，" +
                    "但别在这上面直接写回：save 是整份覆盖。",
            )
            null
        }
    }

    /**
     * 写（原子：`.tmp` + rename，失败不静默当成功）。
     * @return true = 落盘；false = 失败（调用方记日志）
     */
    fun save(filesDir: File, text: String): Boolean {
        val f = fileIn(filesDir)
        f.parentFile?.mkdirs()
        return runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                runCatching { tmp.delete() }
            }
            f.isFile
        }.getOrDefault(false)
    }
}
