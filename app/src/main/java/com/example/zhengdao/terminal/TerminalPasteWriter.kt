// 独立开发声明：本文件为本项目从零编写。会话引擎为 Termux 官方
// terminal-emulator（Apache-2.0，v0.119.0-beta.3，聚合分发见 PROVENANCE.md）。
package com.example.zhengdao.terminal

import java.util.concurrent.Executor

/**
 * 把大段文本写进 pty 的调度器：**绝不占用调用线程**。
 *
 * ## 为什么需要它（2026-10-12 修 🔴3 主线程 ANR）
 * `SessionManager.write(text)` 最终落到 `TerminalSession.write()` → `ByteQueue(4096)`；
 * 队列满时 `ByteQueue.write()` 会 `wait()` 到 guest 把数据读走为止 —— **这是 pty 背压，
 * 不是异常**。于是"粘一整篇文档"在主线程上就是几秒到几十秒的阻塞，系统直接判 ANR。
 * 只有把这段等待挪到后台线程，界面才可能全程有响应。
 *
 * ## 取舍（为什么是这么小一个类）
 * ① **单线程串行**：写 pty 必须与用户操作同序（同一会话并发写会错序），所以调用方给的
 *    `Executor` 应是单线程实现 —— 本类只保证"不占用调用线程"，不做并发控制；
 * ② **按 UTF-8 字节预算分块**（默认 4096 B = `ByteQueue` 容量）：每块单独一次 `write`，
 *    块与块之间可检查会话是否还活着 —— 会话中途死掉就立刻停手，而不是把剩下的几 MB
 *    硬塞进一条已经没有读者的队列；
 * ③ **不持任何 Android 类型**（只有 `Executor` 与两个 lambda）⇒ 纯 JVM 单测可以钉死
 *    "不在调用线程上写""中文不切碎""中途死掉就停"这些行为，不必上真机也不必 Robolectric；
 * ④ `onResult` 在**执行线程**上回调：调用方若要弹 Toast，请自己 `runOnUiThread`。
 */
class TerminalPasteWriter(
    private val executor: Executor,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
) {

    init {
        require(chunkBytes > 0) { "chunkBytes 必须为正数，收到 $chunkBytes" }
    }

    /**
     * 提交一次写入。
     *
     * @param text           待写入文本（空串会立即以 `success(0)` 回调，不占用线程）
     * @param isSessionAlive 每块写之前问一次"会话还在吗"；返回 false 即停手并报失败
     * @param writeChunk     真正的出口，逐块调用（生产实现是 `SessionManager::write`）
     * @param onResult       成功 = 已写入的**字符数**；失败 = 原因（会话没了 / 出口抛异常）
     */
    fun submit(
        text: String,
        isSessionAlive: () -> Boolean,
        writeChunk: (String) -> Unit,
        onResult: (Result<Int>) -> Unit,
    ) {
        if (text.isEmpty()) {
            onResult(Result.success(0))
            return
        }
        try {
            executor.execute {
                var written = 0
                try {
                    for (chunk in splitByByteBudget(text, chunkBytes)) {
                        if (!isSessionAlive()) {
                            onResult(
                                Result.failure(
                                    IllegalStateException(
                                        "会话已结束，粘贴未写完（已写入 $written / ${text.length} 个字符）"
                                    )
                                )
                            )
                            return@execute
                        }
                        writeChunk(chunk)
                        written += chunk.length
                    }
                    onResult(Result.success(written))
                } catch (t: Throwable) {
                    // 出口抛异常（pty 已关闭 ⇒ IOException、线程被中断 ⇒ InterruptedException…）
                    // 一律转成"失败"回给调用方：调用方在界面上有一条人话提示，而不是静默丢字。
                    onResult(Result.failure(t))
                }
            }
        } catch (t: Throwable) {
            // executor 已 shutdown（页面销毁后用户在别处触发粘贴）⇒ 同样别把异常抛给调用方。
            onResult(Result.failure(t))
        }
    }

    companion object {
        /** 与 `TerminalSession` 里 `new ByteQueue(4096)` 同值：一块正好填满一次队列。 */
        const val DEFAULT_CHUNK_BYTES = 4096

        /**
         * 按 **UTF-8 字节**预算切分，且**不在码点中间切**。
         *
         * 注意：按 `Char` 切会把代理对（emoji 等 4 字节字符）劈成两半，每半单独编码都成
         * `?`（UTF-8 里孤立代理项不可表示）⇒ 粘贴出来的正文会坏字。所以这里按码点走。
         * 单个码点若本身就超过预算（预算被调得极小），仍会单独成块 —— 那种情况下"不切"
         * 优先于"不超预算"，否则会死循环。
         */
        internal fun splitByByteBudget(text: String, budgetBytes: Int): List<String> {
            val chunks = ArrayList<String>(text.length / budgetBytes + 1)
            val sb = StringBuilder()
            var usedBytes = 0
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val charCount = Character.charCount(codePoint)
                val cpBytes = utf8Length(codePoint)
                if (usedBytes + cpBytes > budgetBytes && sb.isNotEmpty()) {
                    chunks.add(sb.toString())
                    sb.setLength(0)
                    usedBytes = 0
                }
                sb.append(text, i, i + charCount)
                usedBytes += cpBytes
                i += charCount
            }
            if (sb.isNotEmpty()) chunks.add(sb.toString())
            return chunks
        }

        private fun utf8Length(codePoint: Int): Int = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
    }
}
