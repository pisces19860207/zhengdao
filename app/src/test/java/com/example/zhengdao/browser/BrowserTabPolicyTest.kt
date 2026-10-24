// 独立开发声明：本文件为本项目原创。
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

    // ── 渲染优先级（第一百七十二批 S1 · J1） ──
    //
    // 六格表：判据只认「App 在不在前台」，**不认「标签是否活跃」**。
    // 第 2 格就是改前的病格（前台 + 非活跃 = WAIVED ⇒ 系统随时收走 ⇒ 切回就重载）。

    @Test
    fun `J1-1 前台且活跃_IMPORTANT`() {
        assertEquals(
            2,
            BrowserTabPolicy.rendererPriority(appForeground = true, isActiveTab = true),
        )
        assertEquals(
            "常量口径：IMPORTANT = 2（与 android.webkit.WebView 静态字段一致）",
            BrowserTabPolicy.RENDERER_PRIORITY_IMPORTANT,
            BrowserTabPolicy.rendererPriority(true, true),
        )
    }

    @Test
    fun `J1-2 前台且非活跃_仍然 IMPORTANT（改前的病格）`() {
        assertEquals(
            "前台的非活跃标签也必须保活 —— 它是 L1「切回不重载」的地基；" +
                "改前这里是 WAIVED（系统想收就收）",
            BrowserTabPolicy.RENDERER_PRIORITY_IMPORTANT,
            BrowserTabPolicy.rendererPriority(appForeground = true, isActiveTab = false),
        )
    }

    @Test
    fun `J1-3 后台且活跃_WAIVED`() {
        assertEquals(
            BrowserTabPolicy.RENDERER_PRIORITY_WAIVED,
            BrowserTabPolicy.rendererPriority(appForeground = false, isActiveTab = true),
        )
    }

    @Test
    fun `J1-4 后台且非活跃_WAIVED`() {
        assertEquals(
            BrowserTabPolicy.RENDERER_PRIORITY_WAIVED,
            BrowserTabPolicy.rendererPriority(appForeground = false, isActiveTab = false),
        )
    }

    @Test
    fun `J1-5 前台两种活跃态结果相同_即活跃度不参与判据`() {
        assertEquals(
            "同一可见性下活跃/非活跃必须同档 —— 否则又一次把页面交给回收器",
            BrowserTabPolicy.rendererPriority(true, true),
            BrowserTabPolicy.rendererPriority(true, false),
        )
        assertEquals(
            BrowserTabPolicy.rendererPriority(false, true),
            BrowserTabPolicy.rendererPriority(false, false),
        )
    }

    @Test
    fun `J1-6 返回值只可能是两档之一`() {
        val legal = setOf(
            BrowserTabPolicy.RENDERER_PRIORITY_WAIVED,
            BrowserTabPolicy.RENDERER_PRIORITY_IMPORTANT,
        )
        for (fg in listOf(true, false)) {
            for (active in listOf(true, false)) {
                assertTrue(
                    "rendererPriority($fg, $active) 落到了非法档位",
                    BrowserTabPolicy.rendererPriority(fg, active) in legal,
                )
            }
        }
    }
}
