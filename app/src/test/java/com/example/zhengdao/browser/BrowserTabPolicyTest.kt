// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrowserTabPolicy] 纯逻辑单测（第⑨刀 B）。
 *
 * 覆盖三组判定：标签上限、关闭后的活跃接棒（右邻优先/无右邻取左邻/单标签归空/
 * 关非活跃不变）、外部打开的落点策略。全部表驱动、零 Android 依赖。
 */
class BrowserTabPolicyTest {

    // ── 上限 ──

    @Test
    fun `未满上限可开新标签`() {
        assertTrue(BrowserTabPolicy.canOpenMore(0))
        assertTrue(BrowserTabPolicy.canOpenMore(BrowserTabPolicy.MAX_TABS - 1))
    }

    @Test
    fun `满 20 拒新标签`() {
        assertFalse(BrowserTabPolicy.canOpenMore(BrowserTabPolicy.MAX_TABS))
        assertFalse(BrowserTabPolicy.canOpenMore(BrowserTabPolicy.MAX_TABS + 5))
    }

    @Test
    fun `上限就是 20`() {
        assertEquals(20, BrowserTabPolicy.MAX_TABS)
    }

    // ── 关闭后的活跃接棒 ──

    @Test
    fun `关中间标签_右邻接棒`() {
        val ids = listOf(1, 2, 3, 4)
        assertEquals("关 2 ⇒ 右邻 3 接棒", 3, BrowserTabPolicy.nextActiveAfterClose(ids, 2, 2))
    }

    @Test
    fun `关最后一个_左邻接棒`() {
        val ids = listOf(1, 2, 3, 4)
        assertEquals("关 4（无右邻）⇒ 左邻 3 接棒", 3, BrowserTabPolicy.nextActiveAfterClose(ids, 4, 4))
    }

    @Test
    fun `关唯一标签_归空`() {
        assertEquals("只剩一个 ⇒ null（空态）", null, BrowserTabPolicy.nextActiveAfterClose(listOf(7), 7, 7))
    }

    @Test
    fun `关非活跃标签_活跃不变`() {
        val ids = listOf(1, 2, 3)
        assertEquals("关 1 而活跃是 3 ⇒ 3 不动", 3, BrowserTabPolicy.nextActiveAfterClose(ids, 1, 3))
    }

    @Test
    fun `关不在列表里的标签_活跃不变`() {
        val ids = listOf(1, 2)
        assertEquals("关 99（不存在）⇒ 活跃 2 不动", 2, BrowserTabPolicy.nextActiveAfterClose(ids, 99, 2))
    }

    @Test
    fun `关活跃且不在列表_归空`() {
        assertEquals(null, BrowserTabPolicy.nextActiveAfterClose(listOf(1, 2), 99, 99))
    }

    // ── 外部打开落点 ──

    @Test
    fun `外部打开默认开新标签`() {
        assertTrue(BrowserTabPolicy.externalOpenShouldCreateTab(0))
        assertTrue(BrowserTabPolicy.externalOpenShouldCreateTab(5))
    }

    @Test
    fun `外部打开满员时复用当前标签`() {
        assertFalse(BrowserTabPolicy.externalOpenShouldCreateTab(BrowserTabPolicy.MAX_TABS))
    }

    // ── 渲染优先级 ──

    @Test
    fun `活跃与后台的渲染优先级不同_后台是 WAIVED`() {
        assertEquals(
            "后台标签必须 WAIVED（§4.2）",
            BrowserTabPolicy.RENDERER_PRIORITY_WAIVED,
            BrowserTabPolicy.rendererPriority(false)
        )
        assertEquals(
            "活跃标签 IMPORTANT（框架默认档，javap 实读同值）",
            BrowserTabPolicy.RENDERER_PRIORITY_IMPORTANT,
            BrowserTabPolicy.rendererPriority(true)
        )
        assertFalse("两个档位不能相同", BrowserTabPolicy.rendererPriority(true) == BrowserTabPolicy.rendererPriority(false))
    }
}
