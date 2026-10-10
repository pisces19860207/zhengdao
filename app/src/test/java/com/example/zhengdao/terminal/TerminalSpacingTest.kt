// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 间距档位（阶段二「舒适档」）的规格锁。
 *
 * 这些数字是交付标准的一部分（行高 1.15× / 上下内边距 4dp / 单键 48dp），
 * 改动它们等于改视觉基线——所以用测试钉住，改的时候必须是有意识的。
 * 真机上的实际效果由 `docs/acceptance/terminal-font-subset-spacing-2026-10-10.md` 记录。
 */
class TerminalSpacingTest {

    @Test
    fun 默认档是舒适档() {
        assertSame(TerminalPrefs.Spacing.COMFORTABLE, TerminalPrefs.Spacing.DEFAULT)
    }

    @Test
    fun 舒适档规格() {
        val s = TerminalPrefs.Spacing.COMFORTABLE
        assertEquals(1.15f, s.lineHeightMultiplier, 0.0001f)
        assertEquals(4, s.paddingVerticalDp)
        assertEquals(48, s.keyHeightDp)
    }

    @Test
    fun 紧凑档回到字体原生行高与零边距() {
        val s = TerminalPrefs.Spacing.COMPACT
        assertEquals(1.0f, s.lineHeightMultiplier, 0.0001f)
        assertEquals(0, s.paddingVerticalDp)
        assertEquals(48, s.keyHeightDp)
    }

    @Test
    fun 未知档位回落默认且紧凑档仍认得出() {
        assertSame(TerminalPrefs.Spacing.COMFORTABLE, TerminalPrefs.Spacing.of(null))
        assertSame(TerminalPrefs.Spacing.COMFORTABLE, TerminalPrefs.Spacing.of("nonsense"))
        assertSame(TerminalPrefs.Spacing.COMPACT, TerminalPrefs.Spacing.of("compact"))
    }
}
