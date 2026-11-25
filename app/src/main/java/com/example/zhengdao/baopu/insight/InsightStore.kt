// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 534「曾经捕获过的念头」的落盘（房型照 677 InspirationStore：
// 文件是真相、只追加、坏行不静默带计数）。支撑判据②的「曾经捕获过」——
// associate 读回本库，再用既有索引的命中去关联，不自建第二套索引。
package com.example.zhengdao.baopu.insight

import com.example.zhengdao.baopu.kernel.BaopuKernel
import java.io.File

/** 读回结果：好念头 + 坏行计数（坏行说出来，不冒充没有）。 */
internal data class InsightScan(val thoughts: List<CapturedThought>, val broken: Int)

/**
 * 念头库：`<工作区>/产物/洞察念头.jsonl`，一行一念头（append-only，无删除 API）。
 * 只依赖注入的文件，无 Android、无时钟——JVM 直测。
 */
internal class InsightStore(private val file: File) {

    /** 追加一条（成功 true；失败 false 不抛——没落上要说）。 */
    fun append(t: CapturedThought): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.appendText(encode(t) + "\n")
        true
    }.getOrDefault(false)

    /** 全量读回（写入顺序＝捕获顺序）。 */
    fun scan(): InsightScan {
        if (!file.isFile) return InsightScan(emptyList(), 0)
        var broken = 0
        val list = mutableListOf<CapturedThought>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            val t = decode(line)
            if (t == null) broken += 1 else list.add(t)
        }
        return InsightScan(list.toList(), broken)
    }

    companion object {
        const val FILE_NAME = "洞察念头.jsonl"

        fun fileOf(workspace: File): File = File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), FILE_NAME)

        // 列序：atype｜id｜at｜ws｜msgId｜sessionId｜ledgerId｜quote｜body（9 列，同起居注制表符编码）。
        // msgId/sessionId 只给 msg 锚点用（ledger 锚点留空）；不拼复合串，避免分隔符歧义。
        private const val COLS = 9

        /** 转义：\ → \\、制表符 → \t、换行 → \n（与 DiaryCodec 同款，零 Android 依赖）。 */
        private fun esc(s: String): String = s
            .replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

        private fun unesc(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> out.append('\\')
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                } else {
                    out.append(s[i]); i += 1
                }
            }
            return out.toString()
        }

        fun encode(t: CapturedThought): String {
            val a = t.anchor
            val atype = if (a is InsightAnchor.Message) "msg" else "ledger"
            val msgId = if (a is InsightAnchor.Message) a.messageId.toString() else ""
            val sessionId = if (a is InsightAnchor.Message) a.sessionId.toString() else ""
            val ledgerId = if (a is InsightAnchor.Ledger) a.ledgerId else ""
            return listOf(atype, t.id, t.atMs.toString(), t.workspace, msgId, sessionId, ledgerId, a.quote, t.body)
                .joinToString("\t") { esc(it) }
        }

        /** 列数不对/类型不认/锚点重建不出来 ⇒ null（由 [scan] 计入 broken）。 */
        fun decode(line: String): CapturedThought? {
            val c = line.split('\t')
            if (c.size != COLS) return null
            val at = c[2].toLongOrNull() ?: return null
            val quote = unesc(c[7])
            val anchor = when (c[0]) {
                "msg" -> {
                    val msgId = c[4].toLongOrNull() ?: return null
                    val sessionId = c[5].toLongOrNull() ?: return null
                    InsightAnchor.Message(msgId, sessionId, quote)
                }
                "ledger" -> InsightAnchor.Ledger(unesc(c[6]), quote)
                else -> return null
            }
            return CapturedThought(unesc(c[1]), unesc(c[8]), anchor, at, unesc(c[3]))
        }
    }
}
