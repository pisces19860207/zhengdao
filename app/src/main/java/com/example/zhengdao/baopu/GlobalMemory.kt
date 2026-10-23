// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

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

    /** 读；不存在/空/读不了 ⇒ null（不报错，静默当没有）。 */
    fun load(filesDir: File): String? =
        runCatching { fileIn(filesDir).takeIf { it.isFile }?.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

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
