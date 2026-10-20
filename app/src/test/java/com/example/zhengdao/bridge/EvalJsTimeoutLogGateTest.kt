// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七十一批④（§B 79 / 69 批清单应修 #9）的**源码门禁**：
 * `evalJs` 的闩等待超时归一为 null 无日志——同文件 onMain 的超时已有日志，两处漂移。
 * 修法：照 onMain 同款补一条 RunLog（含 command 脚本指纹与耗时），返回 null 语义不变。
 *
 * 为什么按源码钉：真超时要主线程卡 8 秒，JVM 单测造不出来。负例把日志删掉 ⇒ 必须红。
 * 注意：本门禁与 BridgeEvalAndUrlGateTest 的「onMain 块内不得阻塞」判据互补——
 * 新日志在 evalJs 自己的等待段，不许有人借机把 await 挪回 onMain 块里。
 */
class EvalJsTimeoutLogGateTest {

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

    private fun evalJsTimeoutLogged(body: String): Boolean =
        body.contains("if (!done) {") &&
            // 消息必须真挂在 RunLog.log( 上（负例把调用改名后这里必须失配变红）
            Regex("RunLog\\.log\\(\\s*\"浏览器桥：JS 回调超时").containsMatchIn(body) &&
            body.contains("command=") &&
            body.contains("EVAL_TIMEOUT_MS") &&
            body.contains("return null")

    @Test
    fun `J4_evalJs 回调超时必须落日志含 command 与耗时`() {
        val body = funBody(
            code("src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt"),
            "private fun evalJs(",
        )
        assertTrue(
            "evalJs 的闩等待超时分支必须落日志（与 onMain 的超时日志同款，两处不许漂移）：\n$body",
            evalJsTimeoutLogged(body),
        )
        // 超时语义不变：日志之后仍然归一为 null，不许把超时改成抛异常
        val branch = body.substring(body.indexOf("if (!done) {"), body.indexOf("return jsResultToString"))
        assertTrue("超时分支必须仍返回 null", branch.contains("return null"))
        // 负例 N4：把日志删掉（改名等效）⇒ 红
        assertFalse(
            "N4：删掉超时日志后判据必须变红",
            evalJsTimeoutLogged(body.replace("RunLog.log(", "RunLogX.log(")),
        )
    }
}
