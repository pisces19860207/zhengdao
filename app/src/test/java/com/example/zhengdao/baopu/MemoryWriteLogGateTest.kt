// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七十一批②（§B 79 / 69 批清单应修 #7）的**源码门禁**：
 * 记忆写失败只是静默 false——`write` 的落盘 catch 无日志；`update` 的 false 被调用方
 * 忽略（用户以为记住了）。修法：两条失败路径都在本方法内落 RunLog（不带记忆正文）；
 * 调用方 UI 提示在飞（63/64），待下一批。
 *
 * 为什么按源码钉：落盘失败要 JVM 造 IO 异常，Windows 上无法确定性造出来。
 * 负例把日志删掉 ⇒ 本门禁必须红。
 */
class MemoryWriteLogGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun funBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到函数签名：$signature（被改名了？门禁跟着改）", start >= 0)
        val end = src.indexOf("\n    }", start)
        assertTrue("找不到函数收尾：$signature", end > start)
        return src.substring(start, end)
    }

    private fun writeFailLogged(body: String): Boolean =
        body.contains("catch (e: Exception)") && body.contains("RunLog.log(\"记忆写入失败：id=")

    private fun updateFailLogged(body: String): Boolean =
        body.contains("if (!written) {") && body.contains("RunLog.log(\"记忆写入失败：id=")

    @Test
    fun `J2_记忆写入失败必须落日志且不带正文`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/MemoryStore.kt")
        val writeBody = funBody(src, "fun write(entry: MemoryEntry): WriteResult")
        val updateBody = funBody(src, "fun update(entry: MemoryEntry): Boolean")
        assertTrue("write 的落盘失败分支必须落日志：\n$writeBody", writeFailLogged(writeBody))
        assertTrue(
            "update 的失败路径必须落日志（调用方忽略返回值，方法内不落日志就真没声了）：\n$updateBody",
            updateFailLogged(updateBody),
        )
        // 不打印记忆正文：日志行里不许出现正文取值
        val logLines = (writeBody + updateBody).lineSequence()
            .filter { it.contains("RunLog.log(\"记忆写入失败") }.toList()
        assertTrue("两条失败路径都要有日志行", logLines.size >= 2)
        assertFalse(
            "记忆日志不许带正文取值（body=/content=）",
            logLines.any { Regex("body=|content=").containsMatchIn(it) },
        )
        // 负例 N2：把日志删掉（改名等效）⇒ 红
        assertFalse("N2：删掉日志后 write 判据必须变红", writeFailLogged(writeBody.replace("RunLog.log(", "RunLogX.log(")))
        assertFalse("N2：删掉日志后 update 判据必须变红", updateFailLogged(updateBody.replace("RunLog.log(", "RunLogX.log(")))
    }
}
