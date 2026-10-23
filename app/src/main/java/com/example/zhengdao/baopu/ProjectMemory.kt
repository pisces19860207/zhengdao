// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import java.io.File

/**
 * 最小版「项目记忆」（2026-10-19 立；**157 批移入 `记忆/`**）。
 *
 * 位置变迁：`<工作区根>/项目记忆.md`（147 前）→ **`<工作区>/记忆/项目记忆.md`**
 * （用户 2026-10-23「记忆目录约定」：工作区可见目录收口成 产物/回收站/备份/记忆 四个，
 * 项目记忆归 `记忆/`）。旧根位置**只读回退**（[legacyFile]）——迁移没跑到、
 * 或用户手动放回根上的老文件都还读得到，但**新写入只认 `记忆/` 这一处**。
 *
 * 为什么只做一个文件（而不是 qoder 建议的三件套目录）：
 * - 抱朴真正的短板是「没有执行的手」（跑不了 gradle/adb），记忆层只是连续性；
 * - 一个文件足够装「口径／这批留在哪／取证要点」，收尾时抱朴自己写
 *   （[MISSING_HINT] 提示语进组装层，157 新增 [save] 才让「自己写」有落点）。
 *
 * 上限：注入最多 [MAX_TOKENS] tokens（按 [TokenEstimator] 估），超了截断并留一句
 * 「已截断，完整内容见文件」——段本身永不进压缩。
 */
internal object ProjectMemory {

    /** 文件名（真源在内核 [BaopuKernel.Data.FILE_PROJECT_MEMORY]）。 */
    const val FILE_NAME: String = BaopuKernel.Data.FILE_PROJECT_MEMORY

    /** 注入上限（2k tokens；与记忆召回同一量级）。 */
    const val MAX_TOKENS = 2_000

    /** 新位置（写入只认这一处）：`<工作区>/记忆/项目记忆.md`。 */
    fun fileIn(workspaceRoot: File): File = MemoryLayout.projectMemoryFile(workspaceRoot)

    /** 旧根位置（只读回退）：`<工作区>/项目记忆.md`（147 前）。 */
    fun legacyFile(workspaceRoot: File): File = MemoryLayout.legacyProjectMemoryFile(workspaceRoot)

    /**
     * 读并裁剪：新位置优先；没有 ⇒ 旧根只读回退；都没有 ⇒ null
     * （不报错、静默当没有——提示语由组装层的 [missingHint] 负责）。
     */
    fun load(workspaceRoot: File): String? {
        val text = runCatching { fileIn(workspaceRoot).readText(Charsets.UTF_8) }.getOrNull()
            ?: runCatching { legacyFile(workspaceRoot).takeIf { it.isFile }?.readText(Charsets.UTF_8) }
                .getOrNull()
        return text?.takeIf { it.isNotBlank() }?.let { trim(it) }
    }

    /** 缺失提示（157：用户「给一句可读提示，让抱朴在收尾时自己写」）。 */
    fun missingHint(): String = MemoryLayout.MISSING_HINT

    /**
     * 写（157 新增）：**只许写 `记忆/项目记忆.md`**——
     * [MemoryLayout.allowedWrite] 判据（旧根/产物/别处一律拒，返回 false）。
     * 原子写（`.tmp` + rename），失败不静默当成功。
     *
     * @return true = 落盘；false = 越界被拒或写失败（调用方记日志）
     */
    fun save(workspaceRoot: File, text: String): Boolean {
        val f = fileIn(workspaceRoot)
        if (!MemoryLayout.allowedWrite(workspaceRoot, f)) return false
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

    /** 按 token 预算截断；返回正文（不含段标题——标题在 ContextAssembler 里加）。 */
    fun trim(text: String): String {
        if (TokenEstimator.estimate(text) <= MAX_TOKENS) return text.trimEnd()
        // 按字符二分找最大可放前缀（简单可靠；只在超限时走一次）
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (TokenEstimator.estimate(text.substring(0, mid)) <= MAX_TOKENS) lo = mid else hi = mid - 1
        }
        val kept = text.substring(0, lo).trimEnd()
        return kept + "\n\n（项目记忆已截断：只注入前 ${MAX_TOKENS} tokens，完整内容见 `记忆/$FILE_NAME`。）"
    }
}
