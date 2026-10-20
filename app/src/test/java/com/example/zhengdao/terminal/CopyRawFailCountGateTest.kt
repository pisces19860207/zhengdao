// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七十一批⑤（§B 79 / 69 批清单应修 #10）的**源码门禁**：
 * `copyRawInto` 单文件复制失败静默 continue，调用方只看到成功数、不知道漏了失败数。
 * 修法：统计 failed ＋记住首个失败，结束落一条汇总「成功 N，失败 M（首个失败：…）」；
 * 成功项的复制行为与返回类型（Int＝成功数）不变——调用方在 ui/SettingsDataActivity，
 * 本批红线不碰 UI，返回类型不改（报告偏差清单已记）。
 *
 * 为什么按源码钉：单条复制失败要造 IO 异常，Windows 上无法确定性造出来。
 * 负例把汇总日志删掉 ⇒ 本门禁必须红。
 */
class CopyRawFailCountGateTest {

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

    private fun copyFailCounted(body: String): Boolean =
        body.contains("var failed = 0") &&
            body.contains("failed++") &&
            body.contains("firstFail") &&
            body.contains("RunLog.log(\"资料复制：成功 \$copied，失败")

    @Test
    fun `J5_资料复制失败必须计数并落汇总日志`() {
        val body = funBody(
            code("src/main/java/com/example/zhengdao/terminal/KnowledgeBase.kt"),
            "fun copyRawInto(",
        )
        assertTrue(
            "单条复制失败必须计数（failed++）并记住首个失败、结束落汇总：\n$body",
            copyFailCounted(body),
        )
        // 成功路径不变：成功分支仍是 copied++，成功日志口径保留
        assertTrue("成功路径不许动（copyTo 照旧）", body.contains("src.copyTo(dst, overwrite = false)"))
        assertTrue("全成功时的原口径日志要保留", body.contains("资料库：从 \$srcAbs 复制了"))
        // 负例 N5：把汇总日志删掉（改名等效）⇒ 红
        assertFalse(
            "N5：删掉失败汇总日志后判据必须变红",
            copyFailCounted(body.replace("RunLog.log(", "RunLogX.log(")),
        )
    }
}
