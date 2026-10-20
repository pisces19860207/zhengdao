// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七十一批①（§B 79 / 69 批清单应修 #6）的**源码门禁**：
 * `SkillAudit.record` 返回 false 被忽略 ⇒ 审计行写失败无痕（KDoc 说「由调用方 log」，
 * 调用方没做）。修法：`apply` 里接住返回值，失败落一条 RunLog；**不回滚**技能写盘。
 *
 * 为什么按源码钉：审计失败要 JVM 造 IO 异常（目录不可写），Windows 上无法确定性造出来
 * （同 StorePartialCopyGuardTest 的理由）。负例把日志删掉 ⇒ 本门禁必须红。
 */
class SkillAuditLogGateTest {

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

    private fun auditFailLogged(body: String): Boolean =
        body.contains("val auditWritten = SkillAudit.record(") &&
            body.contains("if (!auditWritten) {") &&
            body.contains("RunLog.log(\"技能审计行写入失败：op=")

    @Test
    fun `J1_技能审计行写失败必须落日志且不回滚写盘`() {
        val body = funBody(code("src/main/java/com/example/zhengdao/baopu/SkillStore.kt"), "internal fun apply(")
        assertTrue(
            "审计写失败要有声音（SkillAudit 的 KDoc 说由调用方 log，调用方必须兑现）：\n$body",
            auditFailLogged(body),
        )
        // 失败只补日志，不回滚技能写盘：日志之后必须直达 Written，不许半路改判 Rejected
        val afterIf = body.indexOf("if (!auditWritten) {")
        val tail = body.substring(afterIf, body.indexOf("SkillWriteResult.Written", afterIf))
        assertFalse("审计失败不许把写入改判失败（Written 必须照旧返回）", tail.contains("Rejected"))
        // 负例 N1：把日志删掉（改名等效）⇒ 红
        assertFalse(
            "N1：删掉审计失败日志后判据必须变红",
            auditFailLogged(body.replace("RunLog.log(", "RunLogX.log(")),
        )
    }
}
