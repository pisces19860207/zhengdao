// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AboutVersionLine] 的判据用例（683，2026-11-16）。
 *
 * 这一行是用户判断「手机里装的是不是刚构建的那个包」的第一眼，所以每个分支都要有**反例**：
 * 只断言"拼出来的串里含某几个字"是恒真的假绿灯，这里断言的是**整串相等**与**边界该退化**。
 */
class AboutVersionLineTest {

    @Test
    fun `一行式_三段齐全且顺序固定`() {
        assertEquals(
            "2.2.6-debug+9501f76 (3631427) · 9501f76 · 2026-11-16 21:24",
            AboutVersionLine.of("2.2.6-debug+9501f76", 3631427, "9501f7603", "2026-11-16T21:24:57.123+08:00"),
        )
    }

    @Test
    fun `一行式_正式版无后缀时只有版本号与构建号`() {
        assertEquals(
            "2.2.6 (3631427) · 9501f76 · 2026-11-16 21:24",
            AboutVersionLine.of("2.2.6", 3631427, "9501f7603", "2026-11-16T21:24:57+08:00"),
        )
    }

    @Test
    fun `短号只留七位_第八位起不显示`() {
        assertEquals("9501f76", AboutVersionLine.shortSha("9501f7603"))
        // 反例：给 7 位，不许被"补"或"截"成别的长度
        assertEquals("abc1234", AboutVersionLine.shortSha("abc1234"))
        assertEquals("abc123", AboutVersionLine.shortSha("abc123"))
    }

    @Test
    fun `短号保留dirty后缀_脏包必须显形`() {
        assertEquals("9501f76-dirty", AboutVersionLine.shortSha("9501f7603-dirty"))
        assertTrue(AboutVersionLine.suspect("9501f7603-dirty"))
    }

    @Test
    fun `号不可信的三种形态`() {
        assertTrue("空号也算不可信", AboutVersionLine.suspect(""))
        assertTrue("local 表示没有 git 号", AboutVersionLine.suspect("local"))
        assertTrue(AboutVersionLine.suspect("abcd1234-dirty"))
        // 反例：正常短号不许被标红
        assertFalse(AboutVersionLine.suspect("9501f7603"))
        assertFalse(AboutVersionLine.suspect("localised")) // 前缀相同但不是 local
    }

    @Test
    fun `时间截到分钟_秒与偏移量不显示`() {
        assertEquals("2026-11-16 21:24", AboutVersionLine.shortTime("2026-11-16T21:24:57.123+08:00"))
        assertEquals("2026-11-16 09:05", AboutVersionLine.shortTime("2026-11-16T09:05:00Z"))
        assertEquals("2026-11-16 21:24", AboutVersionLine.shortTime("  2026-11-16T21:24:57-05:00 "))
    }

    @Test
    fun `时间认不出就原样返回_不许吞掉`() {
        assertEquals("", AboutVersionLine.shortTime(""))
        assertEquals("runtime", AboutVersionLine.shortTime("runtime"))
        // 位置不对（缺 T / 长度不够）⇒ 原样，而不是拼出半截"2026-11-16 21"
        assertEquals("2026-11-16 21:24", AboutVersionLine.shortTime("2026-11-16 21:24"))
        assertEquals("2026-11-16T21", AboutVersionLine.shortTime("2026-11-16T21"))
    }

    @Test
    fun `空版本名给问号_不拼出空括号`() {
        assertEquals("? (7) · abc1234 · runtime", AboutVersionLine.of("  ", 7, "abc1234", "runtime"))
    }
}
