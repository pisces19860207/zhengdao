package com.example.zhengdao.baopu

import java.io.File

/**
 * 最小版「项目记忆」（2026-10-19）：工作区根下**一个** `项目记忆.md`，每轮开工自动读。
 *
 * 为什么只做一个文件（而不是 qoder 建议的三件套目录）：
 * - 抱朴真正的短板是「没有执行的手」（跑不了 gradle/adb），记忆层只是连续性；
 * - 一个文件足够装「口径／这批留在哪／取证要点」，还能被普通 `write_file` 更新
 *   （走确认卡），**不需要给引擎加写路径、不碰全局技能与人设保护闸**。
 *
 * 上限：注入最多 [MAX_TOKENS] tokens（按 [TokenEstimator] 估），超了截断并留一句
 * 「已截断，完整内容见文件」——段本身永不进压缩。
 */
internal object ProjectMemory {

    const val FILE_NAME = "项目记忆.md"

    /** 注入上限（2k tokens；与记忆召回同一量级）。 */
    const val MAX_TOKENS = 2_000

    /** 工作区根下的文件（不存在 ⇒ null）。 */
    fun fileIn(workspaceRoot: File): File = File(workspaceRoot, FILE_NAME)

    /** 读并裁剪；文件不存在/读不了/空文件 ⇒ null（不报错，静默当没有）。 */
    fun load(workspaceRoot: File): String? =
        runCatching { fileIn(workspaceRoot).readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { trim(it) }

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
        return kept + "\n\n（项目记忆已截断：只注入前 ${MAX_TOKENS} tokens，完整内容见工作区根 `$FILE_NAME`。）"
    }
}
