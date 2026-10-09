package com.example.zhengdao.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资料库"该不该建目录"的判定（E-061）。
 *
 * 背景：真机上验到 —— 用户把「资料库」文件夹删掉之后，设置页那颗「重新整理」按钮**点了没反应**：
 * ensureScaffold 看到"用户已删除"标记就直接 return false，于是唯一的手动恢复入口也失效了。
 * 这里把判定抽成纯函数钉住三条规则。
 */
class KnowledgeBaseTest {

    @Test
    fun `从没建过时自动预置`() {
        assertTrue(
            "首次进终端应该自动把资料库建出来",
            KnowledgeBase.shouldCreate(everCreated = false, userDeleted = false, forced = false),
        )
    }

    @Test
    fun `用户删过之后自动流程不再重建`() {
        assertFalse(
            "建过又被删：自动流程必须尊重删除",
            KnowledgeBase.shouldCreate(everCreated = true, userDeleted = false, forced = false),
        )
        assertFalse(
            "已记下删除标记：自动流程不复活",
            KnowledgeBase.shouldCreate(everCreated = true, userDeleted = true, forced = false),
        )
        assertFalse(
            "只留了删除标记：同样不复活",
            KnowledgeBase.shouldCreate(everCreated = false, userDeleted = true, forced = false),
        )
    }

    @Test
    fun `手动整理时无论删过没有都要重建`() {
        assertTrue(
            "用户点了「重新整理」：这是他自己的要求",
            KnowledgeBase.shouldCreate(everCreated = true, userDeleted = true, forced = true),
        )
        assertTrue(
            "用户点了「重新整理」：就算没删也照常整理",
            KnowledgeBase.shouldCreate(everCreated = true, userDeleted = false, forced = true),
        )
        assertTrue(
            "用户点了「重新整理」：首次也走同一条路",
            KnowledgeBase.shouldCreate(everCreated = false, userDeleted = false, forced = true),
        )
    }
}
