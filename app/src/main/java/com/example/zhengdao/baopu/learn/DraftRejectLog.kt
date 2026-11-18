// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百九十八批 · 草案拒收的**账**（`草案/拒收.jsonl`，一行一条）。
//
// 为什么要有这本账：690 报的 G-690-6 里最贵的不是「取错会话源」，是**失败不发声**——
// 人点了「建草案」，屏幕上什么都没发生，分不清是「没点着」「归纳坏了」还是「被当重复
// 吞掉了」。所以本轮把「拒收」变成一个**可核对的事实**：
//   · UI 侧一次性可见反馈（toast：为什么、跟哪条草案撞了）——人当场知道；
//   · 这本账一行一条（时间／原因／会话 id／草案 id）——事后能查、测试能读。
//
// 房型照 VoiceLedger／未学会.json：单追加点、写失败 false 由调用方说（不静默装作记上了）。
// 与 `草案-*.json` 同目录但前缀不同（`拒收-`），草稿区扫盘只认 `草案-`，不会把它当草案读。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

internal class DraftRejectLog(private val file: File) {

    /**
     * 记一行拒收。
     *
     * @param reason 人话原因（与 toast 同一句；不含 goal／正文，只有计数与 id）
     * @param sessionId 观察会话 id（null＝没有会话可指，例如「还没有观察」）
     * @param draftId 撞上的既有草案 id（重复拒收时有；其余为 null）
     */
    fun append(reason: String, sessionId: String?, draftId: String?, at: Long): Boolean = runCatching {
        val line = JSONObject()
            .put("t", at)
            .put("reason", reason)
            .put("session", sessionId.orEmpty())
            .put("draft", draftId.orEmpty())
            .toString()
        file.parentFile?.mkdirs()
        if (file.length() > MAX_BYTES) trimLocked()
        file.appendText(line + "\n", Charsets.UTF_8)
        true
    }.onFailure { RunLog.log("学习板块：拒收账落盘失败 ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** 账里的全部行（测试／诊断读；坏行跳过——半条 JSON 不是账）。 */
    fun lines(): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** 封顶：文件过大只留最近 [KEEP_LINES] 行（账是排障线索，不是无限账本）。 */
    private fun trimLocked() {
        val keep = runCatching {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(KEEP_LINES)
        }.getOrDefault(emptyList())
        file.writeText(keep.joinToString("") { it + "\n" }, Charsets.UTF_8)
    }

    companion object {
        const val FILE_NAME = "拒收-账.jsonl"
        const val MAX_BYTES = 64L * 1024
        const val KEEP_LINES = 200
    }
}
