// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.startup

import com.example.zhengdao.core.RunLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.io.File

/**
 * [StartupDiagnostics] 的判据用例（629 判据②③）。
 *
 * 钉四件事：文件落在 `产物/启动失败-<时间>.txt`、正文含栈与版本/commit/机型/上次成功启动、
 * 密钥形态被脱敏、**结构上不掺正文**（行数固定 = 骨架 + 栈帧）。
 */
class StartupDiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    private fun facts(
        lastOk: Long? = at(2026, 11, 13, 9, 30, 0),
        now: Long = at(2026, 11, 13, 14, 5, 1),
    ) = StartupFacts(
        versionName = "2.3.0",
        gitSha = "f5f1dd973-dirty",
        buildTime = "2026-11-13T13:00:00+08:00",
        manufacturer = "HONOR",
        model = "Magic5 Pro",
        androidRelease = "16",
        sdkInt = 36,
        lastOkAtMillis = lastOk,
        lastOkVersion = "2.3.0",
        nowMillis = now,
    )

    private fun failure(message: String? = "policy 表装不上") = StartupFailure(
        type = "IllegalStateException",
        typeFull = "java.lang.IllegalStateException",
        message = message,
        stack = listOf(
            "java.lang.IllegalStateException: $message",
            "\tat com.example.zhengdao.baopu.PolicyRuntime.install(PolicyRuntime.kt:42)",
        ),
        atMillis = at(2026, 11, 13, 14, 5, 1),
    )

    @Test
    fun `文件名_按失败时刻命名且带前缀后缀`() {
        assertEquals("20261113-140501", StartupDiagnostics.stamp(failure().atMillis, zone))
        assertEquals(
            "启动失败-20261113-140501.txt",
            StartupDiagnostics.fileName(StartupDiagnostics.stamp(failure().atMillis, zone)),
        )
    }

    @Test
    fun `正文_含栈与版本commit机型与上次成功启动`() {
        val text = StartupDiagnostics.body(facts(), failure(), zone = zone)
        assertTrue("要有异常类型", text.contains("失败类型：IllegalStateException"))
        assertTrue("要有版本名", text.contains("版本名：2.3.0"))
        assertTrue("要有 commit（561 的构建指纹）", text.contains("commit：f5f1dd973-dirty"))
        assertTrue("要有构建时刻", text.contains("构建时刻：2026-11-13T13:00:00+08:00"))
        assertTrue("要有厂商", text.contains("厂商：HONOR"))
        assertTrue("要有型号", text.contains("型号：Magic5 Pro"))
        assertTrue("要有系统版本", text.contains("系统：Android 16（API 36）"))
        assertTrue("要有上次成功启动的时刻", text.contains("时刻：2026-11-13 09:30:00（版本 2.3.0）"))
        assertTrue("要有栈帧", text.contains("PolicyRuntime.install(PolicyRuntime.kt:42)"))
    }

    @Test
    fun `正文_首次就失败时照实说没有上次成功启动`() {
        val text = StartupDiagnostics.body(facts(lastOk = null), failure(), zone = zone)
        assertTrue(text.contains("从没有成功启动过"))
    }

    @Test
    fun `正文_密钥与私有路径被脱敏（接生产那一个 redact）`() {
        val filesDir = "/data/user/0/com.example.zhengdao/files"
        val leaky = failure("请求头 Authorization: Basic c2VjcmV0 与 token=sk-live-9f8e7d 在 $filesDir/logs")
        val text = StartupDiagnostics.body(facts(), leaky, { line -> RunLog.redact(line, filesDir) }, zone)
        assertFalse("Bearer/Basic 的值不许出现：$text", text.contains("c2VjcmV0"))
        assertFalse("token 的值不许出现", text.contains("sk-live-9f8e7d"))
        assertFalse("App 私有绝对路径不许出现", text.contains("/data/user/0/"))
        assertTrue("脱敏后要留 **** 记号（看得出这里被抹过）", text.contains("****"))
    }

    @Test
    fun `正文_超长行被截断（挡整段请求体塞进异常消息）`() {
        val huge = "x".repeat(5_000)
        val text = StartupDiagnostics.body(facts(), failure(huge), zone = zone)
        val longest = text.lineSequence().maxOf { it.length }
        assertTrue(
            "最长行 ${longest} 超过上限 ${StartupDiagnostics.MAX_LINE_CHARS}",
            longest <= StartupDiagnostics.MAX_LINE_CHARS + 8,
        )
        assertTrue("截断要有记号", text.contains("（已截断）"))
    }

    @Test
    fun `正文_结构上不掺正文（行数＝骨架＋栈帧）`() {
        // 这是「不含正文」的结构判据：正文只由 StartupFacts + StartupFailure 拼成，
        // 一旦有人往里面加一段会话/账本内容，行数就对不上了 —— 那时必须有人来解释。
        val f = failure()
        val lines = StartupDiagnostics.body(facts(), f, zone = zone).lines()
        assertEquals("骨架 18 行 + 栈 ${f.stack.size} 行", 18 + f.stack.size, lines.size)
    }

    @Test
    fun `落盘_写到产物目录且文件名带时间戳`() {
        val dir = File(tmp.root, StartupDiagnostics.DIR_NAME)
        val written = mutableListOf<Pair<File, String>>()
        val file = StartupDiagnostics.export(
            dir = dir,
            facts = facts(),
            failure = failure(),
            sanitize = { it },
            zone = zone,
            write = { f, text -> written += f to text },
        )
        assertEquals("启动失败-20261113-140501.txt", file.name)
        assertEquals(dir.absolutePath, file.parentFile?.absolutePath)
        assertEquals(1, written.size)
        assertEquals(file, written.single().first)
        assertTrue(written.single().second.contains("失败类型：IllegalStateException"))
    }

    @Test
    fun `落盘_幂等（同名已存在就不再写第二份）`() {
        val dir = File(tmp.root, StartupDiagnostics.DIR_NAME)
        val existing = File(dir, StartupDiagnostics.fileName(StartupDiagnostics.stamp(failure().atMillis, zone)))
        assertTrue(dir.mkdirs())
        existing.writeText("上一份")
        var writes = 0
        val file = StartupDiagnostics.export(
            dir = dir,
            facts = facts(),
            failure = failure(),
            sanitize = { it },
            zone = zone,
            stamp = StartupDiagnostics.stamp(failure().atMillis, zone),
            write = { _, _ -> writes++ },
        )
        assertEquals(existing, file)
        assertEquals("同名已经在盘上，不该再写一次", 0, writes)
        assertEquals("上一份", file.readText())
    }
}
