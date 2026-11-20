// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 判据②④：折叠夹具 + 旋转夹具 —— 配置变更（半折/展开/合上/旋转/分屏）来回切换后，
 * 输入框文字、滚动位置、当前文档、未提交编辑逐字逐位置保持。
 *
 * 为什么要变异：只对"正确 codec 往返"断言是恒真自证。这里额外让 5 个故意损坏的 codec
 * 跑同一条判据链，断言它们**必红** —— 证明这条判据真能抓住"丢一个字/挪一个位置"。
 */
class FoldFixtureStateTest {

    /** 被测内容刻意含中文、换行、空格、emoji、标点：任何截断/归一化都会露馅。 */
    private val original = EditorSnapshot(
        draftText = "折叠夹具 · 第一千二百三十四个字\n第二行 with spaces  和 emoji 🀄 ，末行。",
        scrollOffset = 1379,
        documentUri = "content://com.android.externalstorage/documents/doc%2F笔记-甲.md",
        pendingEdit = "未提交：把「甲」改成「乙」　(草稿区)",
    )

    /** 判据主体：给定 codec，走一串配置变更，逐步逐字对拍；任何一步丢字段即抛 AssertionError。 */
    private fun assertSurvivesAll(codec: EditorSnapshotCodec, seed: EditorSnapshot) {
        var cur = seed
        // 半折 → 展开 → 合上 → 旋转 → 分屏 → 再半折：来回 12 次
        val transitions = listOf(
            "半折", "展开", "合上", "旋转90", "分屏", "展开",
            "半折", "展开", "旋转270", "合上", "分屏", "半折",
        )
        transitions.forEach { step ->
            cur = codec.roundTrip(cur)
            assertEquals("$step：输入框文字必须逐字保持", seed.draftText, cur.draftText)
            assertEquals("$step：滚动位置必须逐位置保持", seed.scrollOffset, cur.scrollOffset)
            assertEquals("$step：当前文档不得变", seed.documentUri, cur.documentUri)
            assertEquals("$step：未提交编辑不得丢", seed.pendingEdit, cur.pendingEdit)
        }
    }

    @Test
    fun `真codec_12次配置变更后四项逐字逐位置保持`() {
        assertSurvivesAll(EditorSnapshotCodec.Real, original)
    }

    @Test
    fun `空草稿与null字段往返后仍是null不被拍成空串`() {
        val seed = EditorSnapshot(draftText = "", scrollOffset = 0)
        val back = EditorSnapshotCodec.Real.roundTrip(seed)
        assertEquals("", back.draftText)
        assertEquals(null, back.documentUri)
        assertEquals(null, back.pendingEdit)
        assertFalse("null 字段不该写进 Bundle", EditorSnapshotCodec.Real.serialize(seed).containsKey(EditorSnapshot.KEY_DOC))
    }

    // ── 变异：以下 5 个坏 codec 必须被上面的判据判红 ────────────────────────

    @Test
    fun `变异1_丢一个字_判据必红`() {
        val dropOneChar = object : EditorSnapshotCodec() {
            override fun serialize(s: EditorSnapshot) = super.serialize(s).toMutableMap().apply {
                put(EditorSnapshot.KEY_DRAFT, (s.draftText.dropLast(1)))  // 少最后一个字「。」
            }.toMap()
        }
        assertThrows(AssertionError::class.java) { assertSurvivesAll(dropOneChar, original) }
    }

    @Test
    fun `变异2_滚动位置挪一格_判据必红`() {
        val shiftScroll = object : EditorSnapshotCodec() {
            override fun serialize(s: EditorSnapshot) = super.serialize(s).toMutableMap().apply {
                put(EditorSnapshot.KEY_SCROLL, s.scrollOffset - 1)
            }.toMap()
        }
        assertThrows(AssertionError::class.java) { assertSurvivesAll(shiftScroll, original) }
    }

    @Test
    fun `变异3_丢当前文档_判据必红`() {
        val dropDoc = object : EditorSnapshotCodec() {
            override fun serialize(s: EditorSnapshot) = super.serialize(s).toMutableMap().apply {
                remove(EditorSnapshot.KEY_DOC)
            }.toMap()
        }
        assertThrows(AssertionError::class.java) { assertSurvivesAll(dropDoc, original) }
    }

    @Test
    fun `变异4_丢未提交编辑_判据必红`() {
        val dropPending = object : EditorSnapshotCodec() {
            override fun serialize(s: EditorSnapshot) = super.serialize(s).toMutableMap().apply {
                remove(EditorSnapshot.KEY_PENDING)
            }.toMap()
        }
        assertThrows(AssertionError::class.java) { assertSurvivesAll(dropPending, original) }
    }

    @Test
    fun `变异5_文字被归一化去换行_判据必红`() {
        val normalized = object : EditorSnapshotCodec() {
            override fun serialize(s: EditorSnapshot) = super.serialize(s).toMutableMap().apply {
                put(EditorSnapshot.KEY_DRAFT, s.draftText.replace("\n", " "))  // 抹掉换行
            }.toMap()
        }
        assertNotEquals(original.draftText, normalized.roundTrip(original).draftText)
        assertThrows(AssertionError::class.java) { assertSurvivesAll(normalized, original) }
    }
}
