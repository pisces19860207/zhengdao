// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十八批 P0-3（审计 §一）：压缩摘要被同名局部变量遮蔽。
//
// 病灶：字段 `RunEngine.compressionSummary`（:323）与 `runLoop` 内的同名局部
// `var compressionSummary`（:757）：`restoreCompression()`（:346）写的是字段，
// 而 `runLoop` 读写的全是局部 ⇒ 重启后 restore 塞回来的摘要 `runLoop` 看不见；
// 同进程第二轮 run 又从头起 null，但 `compressedIds` 字段还在 ⇒ 组装时跳过被压消息
// 却没有摘要替身，模型**永久丢掉那段上下文**（不可恢复：摘要只有在压缩那一刻能生成）。
//
// 为什么不做行为级用例：本仓无 Robolectric，`runLoop` 要真 SQLite ＋ 网络
// （RunEngineTest 头部已声明「归真机」）；派工单 §3 明确允许降级为
// 「纯函数/状态层用例 ＋ 一条源码形状门禁」，并要求说明原因。
// 形状门禁证明的是「没人把局部变量加回去」——正是这个 bug 的唯一成因。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompressionSummaryShadowGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$it") }.readText()
            .replace("\r\n", "\n")
            // 只剥注释（保留字符串字面量）——注释里写一句示例不该让门禁变绿或误报
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    @Test
    fun `S1_runLoop 内不得声明同名局部 var compressionSummary`() {
        val s = code(engine)
        // 字段声明是 `private var compressionSummary`（行首是 private）；
        // 局部声明是 `var compressionSummary …`（行首直接是 var）——只抓后者。
        val local = Regex("""(?m)^[ \t]*var\s+compressionSummary\b""").find(s)
        assertFalse(
            "RunEngine 里又出现了局部 var compressionSummary（第 ${local?.range?.first} 字符附近）——" +
                "它会遮蔽字段：restoreCompression 写的摘要 runLoop 看不见，压缩后上下文永久丢失",
            local != null,
        )
    }

    @Test
    fun `S2_字段仍是唯一存储_restoreCompression 仍写字段`() {
        val s = code(engine)
        assertTrue(
            "字段 private var compressionSummary 不见了（存储被搬走会破坏 restore 语义）",
            Regex("""(?m)^[ \t]*private\s+var\s+compressionSummary\b""").containsMatchIn(s),
        )
        val restoreBlock = s.substringAfter("fun restoreCompression(", "")
        assertTrue("找不到 restoreCompression", restoreBlock.isNotEmpty())
        val body = restoreBlock.substringBefore("\n    }")
        assertTrue(
            "restoreCompression 必须把摘要写回字段（compressionSummary = summary）",
            Regex("""compressionSummary\s*=\s*summary""").containsMatchIn(body),
        )
    }
}
