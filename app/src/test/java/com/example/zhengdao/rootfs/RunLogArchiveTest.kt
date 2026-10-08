// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 运行日志"归档式保留"的单测（2026-10-08 用户拍板后改的语义）。
 *
 * 用户原话：「那些日志都是方便给你们这些 agent 看查哪里有问题的，所以要留着」。
 * 旧实现是**启动时上一轮没出错就 `delete()`**——正常那轮的日志（排查时最需要的对照）
 * 每轮都被擦掉。现在的契约：
 * 1. 归档文件名能被 [RunLog.isArchiveName] 认出，且**绝不能**把本轮日志/错误汇总/旧版
 *    `.prev.txt` 当成归档文件（否则会被轮转策略误删）；
 * 2. 保留策略**从最旧的删**：先卡份数、再卡总量；
 * 3. 任何情况下都不越过上限去删"最新的若干份"——留给人看的永远是最近几轮。
 */
class RunLogArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(name: String, bytes: Int, at: Long = 0L): File {
        val f = File(tmp.root, name)
        f.writeText("x".repeat(bytes))
        if (at > 0L) f.setLastModified(at)
        return f
    }

    @Test
    fun `归档文件名判定：只认带时间戳的历史日志`() {
        assertTrue(RunLog.isArchiveName("zhengdao-log.20261008-164157.txt"))
        // 本轮日志、错误汇总、旧版只留一代的 .prev 都不是归档
        assertFalse(RunLog.isArchiveName("zhengdao-log.txt"))
        assertFalse(RunLog.isArchiveName("zhengdao-log.prev.txt"))
        assertFalse(RunLog.isArchiveName("errors.log"))
        // 前缀后缀都在但中间是空的（长度不够）——认不出就不认，别误删
        assertFalse(RunLog.isArchiveName("zhengdao-log..txt"))
        // 别的日志（比如 Agent 自己的）不能被我们的轮转策略扫到
        assertFalse(RunLog.isArchiveName("hermes.log"))
    }

    @Test
    fun `份数超限时从最旧的删，保留最近 N 份`() {
        val base = 1_700_000_000_000L
        // 5 份，越晚的时间戳越大（越新）
        repeat(5) { i -> touch("zhengdao-log.2026100$i-120000.txt", bytes = 10, at = base + i * 1000L) }

        val deleted = RunLog.pruneArchivesIn(tmp.root, keep = 2, maxBytes = Long.MAX_VALUE)

        assertEquals(3, deleted)
        val left = tmp.root.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf("zhengdao-log.20261003-120000.txt", "zhengdao-log.20261004-120000.txt"),
            left,
        )
    }

    @Test
    fun `总量超限时也从最旧的删，且不碰本轮日志与错误汇总`() {
        val base = 1_700_000_000_000L
        repeat(4) { i -> touch("zhengdao-log.2026100$i-120000.txt", bytes = 100, at = base + i * 1000L) }
        // 这两个名字绝不该被轮转删掉：一个是本轮，一个是跨轮错误汇总
        touch("zhengdao-log.txt", bytes = 500)
        touch("errors.log", bytes = 500)

        // 上限 250 字节 ⇒ 4×100 里只能留下最近 2 份（最新 100 + 次新 100）
        val deleted = RunLog.pruneArchivesIn(tmp.root, keep = 99, maxBytes = 250L)

        assertEquals(2, deleted)
        val left = tmp.root.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "errors.log",
                "zhengdao-log.20261002-120000.txt",
                "zhengdao-log.20261003-120000.txt",
                "zhengdao-log.txt",
            ),
            left,
        )
    }

    @Test
    fun `没有归档文件时是纯空操作`() {
        touch("zhengdao-log.txt", bytes = 10)
        assertEquals(0, RunLog.pruneArchivesIn(tmp.root, keep = 1, maxBytes = 1L))
        assertEquals(1, tmp.root.listFiles()!!.size)
    }
}
