// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * §B 顺序 2 的 P2-2（切走丢输入草稿）与 P2-3（切 Tab 会话被换）验收（2026-10-14）。
 *
 * 两个缺陷是**同一根**：会话 id 原先用 `rememberSaveable`，只跨转屏；切到底栏别的 Tab 时
 * BaopuScreen 离开组合、id 一丢 ⇒ 回来 `repo.open(scope, null)` 新建会话 ⇒ 历史消息没了，
 * 输入框也跟着被清空（草稿丢失是次生现象）。修法是把会话 id 与草稿都搬进进程级的
 * [BaopuStateHolder]（与 `ui/browser/BrowserHolder` 同口径）。
 *
 * 派工单要求覆盖：① 应用内 Tab 切换 ② 转屏 ③ 从终端页回到抱朴页 —— 三者都只是
 * "离开/重建 Composition"，状态在进程级 holder 里就都保得住（本类测的就是这一点）。
 */
class BaopuStateHolderTest {

    @Before
    fun reset() = BaopuStateHolder.resetForTest()

    // ── 行为：会话 id（P2-3） ─────────────────────────────────────────────

    @Test
    fun `初始无会话无草稿`() {
        assertNull(BaopuStateHolder.sessionId)
        assertEquals(0, BaopuStateHolder.draftCount())
        assertNull(BaopuStateHolder.draft(null))
    }

    @Test
    fun `会话 id 设了就能读回_等价于切 Tab 再回来`() {
        BaopuStateHolder.setSession("ses-1")
        assertEquals("ses-1", BaopuStateHolder.sessionId)
        BaopuStateHolder.setSession(null)
        assertNull(BaopuStateHolder.sessionId)
    }

    // ── 行为：按会话各留一份草稿（P2-2） ─────────────────────────────────

    @Test
    fun `草稿按会话各留一份_互不覆盖`() {
        BaopuStateHolder.saveDraft("a", "草稿A")
        BaopuStateHolder.saveDraft("b", "草稿B")
        assertEquals("草稿A", BaopuStateHolder.draft("a"))
        assertEquals("草稿B", BaopuStateHolder.draft("b"))
        assertEquals(2, BaopuStateHolder.draftCount())
    }

    @Test
    fun `同一会话后写的草稿覆盖前一次`() {
        BaopuStateHolder.saveDraft("a", "第一版")
        BaopuStateHolder.saveDraft("a", "第二版")
        assertEquals("第二版", BaopuStateHolder.draft("a"))
        assertEquals(1, BaopuStateHolder.draftCount())
    }

    @Test
    fun `空文本不存_避免堆积无意义条目`() {
        BaopuStateHolder.saveDraft("a", "x")
        BaopuStateHolder.saveDraft("a", "")
        assertNull(BaopuStateHolder.draft("a"))
        assertEquals(0, BaopuStateHolder.draftCount())
    }

    @Test
    fun `null 会话不存不取`() {
        BaopuStateHolder.saveDraft(null, "x")
        assertEquals(0, BaopuStateHolder.draftCount())
    }

    // ── 接线（文本门禁）：钉住"会话 id 真的搬进 holder 了" ─────────────────

    private fun baopuScreenSource(): String {
        val rel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `BaopuScreen_会话 id 走 holder_不再用 rememberSaveable`() {
        val s = baopuScreenSource()
        assertFalse(
            "savedSession 不该再是 rememberSaveable（P2-3：那只保转屏，切 Tab 照丢）",
            s.contains("var savedSession by rememberSaveable")
        )
        assertTrue("初值必须从 holder 取（切回来才有东西可恢复）", s.contains("mutableStateOf(BaopuStateHolder.sessionId)"))
        assertTrue("写入必须走 syncSession（漏写 holder 它就白建）", s.contains("BaopuStateHolder.setSession(id)"))
        assertTrue("输入变化要按会话存草稿（P2-2）", s.contains("BaopuStateHolder.saveDraft(savedSession, t)"))
        assertTrue("切回时要灌回草稿（P2-2）", s.contains("BaopuStateHolder.draft(savedSession)?.let { repo.setInput(it) }"))
    }
}
