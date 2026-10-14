// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计第 2 期 **B-2** 的验收：同一秒内重启，归档名不能撞、更不能删掉上一份。
 *
 * 旧行为：归档名只精确到秒，撞名时 `if (dest.exists()) dest.delete()` 把上一次的归档
 * **无条件删掉** —— 日志丢了，而且绕过了 [RunLog.pruneArchivesIn] 的"从最旧删、最新那份永不删"
 * 策略。新行为：撞名就加 `-1` / `-2` 唯一后缀，两份都留着，丢不丢由保留策略统一裁决。
 *
 * 只测纯函数 [RunLog.archiveNameFor]（不碰 Context / 文件系统）：它正是撞名规避的判据本体。
 * 另钉一条形状约束：带后缀的名字仍必须被 [RunLog.isArchiveName] 认作归档 —— 否则它会变成
 * 保留策略扫不到的"孤儿文件"，越积越多。
 */
class RunLogArchiveUniquenessTest {

    private val stamp = "20261014-212134"

    @Test
    fun `同秒不撞名_依次加唯一后缀`() {
        val first = RunLog.archiveNameFor(stamp, emptySet())
        assertEquals("zhengdao-log.$stamp.txt", first)

        val second = RunLog.archiveNameFor(stamp, setOf(first))
        assertEquals("zhengdao-log.$stamp-1.txt", second)

        val third = RunLog.archiveNameFor(stamp, setOf(first, second))
        assertEquals("zhengdao-log.$stamp-2.txt", third)
    }

    @Test
    fun `带后缀的名字仍被认作归档`() {
        val first = RunLog.archiveNameFor(stamp, emptySet())
        val second = RunLog.archiveNameFor(stamp, setOf(first))
        assertTrue("撞名后缀不能破坏归档名判定（否则保留策略扫不到它）", RunLog.isArchiveName(first))
        assertTrue(RunLog.isArchiveName(second))
    }

    @Test
    fun `目录里的无关文件不影响本次命名`() {
        val names = setOf("zhengdao-log.txt", "errors.log", "zhengdao-log.20261013-120000.txt")
        assertEquals("zhengdao-log.$stamp.txt", RunLog.archiveNameFor(stamp, names))
    }
}
