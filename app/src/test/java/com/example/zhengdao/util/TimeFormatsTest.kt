package com.example.zhengdao.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * B-补-24（2026-10-16）：时间戳格式化的共享 formatter。
 *
 * 三层断言：
 *  1. **逐字等价**：对若干固定时刻，`TimeFormats` 的输出必须与旧写法
 *     `SimpleDateFormat(图案, Locale).format(Date(ts))` 完全一致（旧写法 = 判定基准）；
 *  2. **并发安全**：多线程同时格式化同一时刻，结果必须全部一致且不抛异常
 *     （`SimpleDateFormat` 会在这一步翻车，`DateTimeFormatter` 不会 —— 这正是换掉它的理由）；
 *  3. **源码级门禁**：主源码里不再出现 `SimpleDateFormat(`（注释不算），防悄悄回潮。
 */
class TimeFormatsTest {

    /** 固定时刻：两个不同日期 ＋ 一个跨毫秒，覆盖「带秒/不带秒/带毫秒」三种图案。 */
    private val instants = listOf(
        0L,                          // 1970-01-01 00:00:00（时区相关，基准与实现同机同区，等价即可）
        1_767_225_600_000L,          // 2026-01-01（UTC），便于看「年」是否错位
        1_767_225_600_123L,          // 同一秒内 +123ms，验证 SSS
        1_726_000_000_000L,          // 另一个时刻，避开整点
    )

    private fun oracle(pattern: String, locale: Locale, ts: Long): String =
        SimpleDateFormat(pattern, locale).format(Date(ts))

    @Test
    fun `B_补_24_日志时间戳与旧写法逐字一致`() {
        instants.forEach { ts ->
            assertEquals(
                "MM-dd HH:mm:ss @$ts",
                oracle("MM-dd HH:mm:ss", Locale.US, ts),
                TimeFormats.logStamp(ts),
            )
        }
    }

    @Test
    fun `B_补_24_归档时间戳与旧写法逐字一致`() {
        instants.forEach { ts ->
            assertEquals(
                "yyyyMMdd-HHmmss @$ts",
                oracle("yyyyMMdd-HHmmss", Locale.US, ts),
                TimeFormats.fileStamp(ts),
            )
            assertEquals(
                "yyyyMMdd-HHmm @$ts",
                oracle("yyyyMMdd-HHmm", Locale.US, ts),
                TimeFormats.fileStampMinute(ts),
            )
        }
    }

    @Test
    fun `B_补_24_导出时间与知识库时间戳与旧写法逐字一致`() {
        instants.forEach { ts ->
            assertEquals(
                "yyyy-MM-dd HH:mm:ss @$ts",
                oracle("yyyy-MM-dd HH:mm:ss", Locale.US, ts),
                TimeFormats.isoSeconds(ts),
            )
            assertEquals(
                "yyyy-MM-dd HH:mm @$ts（旧写法用 Locale.CHINA）",
                oracle("yyyy-MM-dd HH:mm", Locale.CHINA, ts),
                TimeFormats.minuteStamp(ts),
            )
        }
    }

    @Test
    fun `B_补_24_毫秒与时分时间戳与旧写法逐字一致`() {
        instants.forEach { ts ->
            assertEquals(
                "HH:mm:ss.SSS @$ts",
                oracle("HH:mm:ss.SSS", Locale.US, ts),
                TimeFormats.clockMillis(ts),
            )
            assertEquals(
                "HH:mm @$ts",
                oracle("HH:mm", Locale.getDefault(), ts),
                TimeFormats.hourMinute(ts),
            )
        }
    }

    @Test
    fun `B_补_24_多线程同时格式化结果一致且不抛异常`() {
        val ts = 1_767_225_600_123L
        val expected = listOf(
            TimeFormats.logStamp(ts),
            TimeFormats.fileStamp(ts),
            TimeFormats.fileStampMinute(ts),
            TimeFormats.isoSeconds(ts),
            TimeFormats.minuteStamp(ts),
            TimeFormats.clockMillis(ts),
            TimeFormats.hourMinute(ts),
        )
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map {
                Callable {
                    repeat(200) {
                        val now = listOf(
                            TimeFormats.logStamp(ts),
                            TimeFormats.fileStamp(ts),
                            TimeFormats.fileStampMinute(ts),
                            TimeFormats.isoSeconds(ts),
                            TimeFormats.minuteStamp(ts),
                            TimeFormats.clockMillis(ts),
                            TimeFormats.hourMinute(ts),
                        )
                        if (now != expected) return@Callable "第 $it 轮不一致：$now"
                    }
                    "ok"
                }
            }
            val results = pool.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals("并发格式化结果必须全部一致：$results", List(8) { "ok" }, results)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `门禁_B_补_24_主源码里不再有 SimpleDateFormat`() {
        val root = mainSourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val stripped = stripComments(f.readText())
            if (stripped.contains("SimpleDateFormat(")) {
                offenders += f.relativeTo(root).path
            }
        }
        assertTrue(
            "主源码里不该再逐次新建 SimpleDateFormat（B-补-24）——请改用 util/TimeFormats.kt；命中：$offenders",
            offenders.isEmpty(),
        )
        // 真源存在性：TimeFormats 自己的并发安全由上一个用例保证，这里只防「文件被删」
        assertTrue("util/TimeFormats.kt 必须在主源码里", File(root, "util/TimeFormats.kt").isFile)
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/example/zhengdao"),
            File("app/src/main/java/com/example/zhengdao"),
        )
        val hit = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到主源码目录（cwd=${File(".").absolutePath}）：$candidates", hit != null)
        return hit!!
    }

    /** 剥掉行注释与块注释，避免「注释里提到 SimpleDateFormat」被当成命中。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src, "")
        return noBlock.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }
}
