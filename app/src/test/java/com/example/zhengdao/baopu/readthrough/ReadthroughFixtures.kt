// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

/**
 * 通读夹具（纯字符串构造，测试里现做现用——不塞二进制资源）。
 *
 * 行形状约定（假抽取件按这些形状对号）：
 * - 叙述行：`第N章标题行之外：叙述……`；
 * - 对话行：`「……」` 开头的一行；
 * - 行内不再有换行——一行就是一段，与中文小说排版同款。
 */
internal object ReadthroughFixtures {

    /** 每章固定骨架：标题行 + [bodyLines] 行正文。 */
    fun standardBook(chapters: Int, bodyLines: Int = 2, charsPerLine: Int = 20): String = buildString {
        for (c in 1..chapters) {
            append("第${c}章 章$c 的标题\n")
            repeat(bodyLines) { b ->
                append("叙述${c}-${b}，").append("事".repeat(charsPerLine)).append('\n')
            }
        }
    }

    /**
     * 一章的结构化正文（供切分判定用例）：先 [narr1] 段叙述（每段 [narrChars] 字），
     * 再 [dialogue] 段对话（每段 [dialogueChars] 字），再 [narr2] 段叙述收尾。
     */
    fun mixedChapter(
        narr1: Int,
        dialogue: Int,
        dialogueChars: Int,
        narr2: Int,
        narrChars: Int = 40,
    ): String = buildString {
        append("第一章 对话章\n")
        repeat(narr1) { append("叙述开头，").append("述".repeat(narrChars - 5)).append('\n') }
        repeat(dialogue) { append("「对话").append("言".repeat(dialogueChars)).append("」\n") }
        repeat(narr2) { append("叙述收尾，").append("尾".repeat(narrChars - 5)).append('\n') }
    }

    /** 一行 [chars] 字的超长单段（句号垫底，句边界可切）。 */
    fun giantLineChapter(chars: Int): String = buildString {
        append("第一章 长段章\n")
        var i = 0
        while (i < chars) {
            val chunk = minOf(40, chars - i)
            append("长".repeat(chunk - 1)).append('。')
            i += chunk
        }
        append('\n')
    }

    /** 大书（3000 章夹具等）：每章标题 + [bodyLines] 行短正文（夹具别把测试撑慢）。 */
    fun bigBook(chapters: Int, bodyLines: Int = 2): String = standardBook(chapters, bodyLines, 16)
}
