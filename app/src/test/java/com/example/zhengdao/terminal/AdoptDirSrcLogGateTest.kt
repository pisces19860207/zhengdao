// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七十一批③（§B 79 / 69 批清单应修 #8）的**源码门禁**：
 * `Store.adoptDir` 源目录列不出内容（IO 错误）时 `?: return 0` 静默放弃，迁移无声、
 * 旧数据滞留无提示。修法：return 0 前落一条「迁移源不可读，跳过（src=…）」；
 * 返回值/行为不变（源目录不存在走 isDirectory 正常路径，不打日志）。
 *
 * 为什么按源码钉：listFiles 失败要造 IO 错误，Windows 上无法确定性造出来。
 * 负例把日志删掉 ⇒ 本门禁必须红。
 */
class AdoptDirSrcLogGateTest {

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

    /** 判据③：listFiles 拿不到的分支里，先落日志再按原样 return 0。 */
    private fun adoptSrcLogged(body: String): Boolean {
        val listAt = body.indexOf("legacy.listFiles()")
        if (listAt < 0) return false
        val window = body.substring(listAt, minOf(body.length, listAt + 240))
        return window.contains("?: run {") &&
            window.contains("RunLog.log(\"迁移源不可读，跳过（src=") &&
            window.contains("return 0")
    }

    @Test
    fun `J3_迁移源不可读必须落日志且返回值不变`() {
        val body = funBody(code("src/main/java/com/example/zhengdao/terminal/Store.kt"), "fun adoptDir(")
        assertTrue(
            "源目录列不出内容时要先落一条日志再按原样 return 0：\n$body",
            adoptSrcLogged(body),
        )
        // 返回值/行为不变：正常路径（源目录不存在）依旧不打日志
        val early = body.substring(0, body.indexOf("legacy.listFiles()"))
        assertFalse("源目录不存在是正常路径，不许在 listFiles 之前抢跑日志", early.contains("RunLog.log("))
        // 负例 N3：把日志删掉（改名等效）⇒ 红
        assertFalse(
            "N3：删掉迁移源日志后判据必须变红",
            adoptSrcLogged(body.replace("RunLog.log(", "RunLogX.log(")),
        )
    }
}
