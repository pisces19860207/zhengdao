// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十四批（第二段）· 治理中心「账本」块的**判据层直测**（纯函数：零 Android、零 IO）。
//
// 判据逐条（派工单第二段 ①–⑦ 里能在这层断言的）：
//   ② 与待办块共用"一次只展开一块"——[TodoBoardRules.toggle] 同一个函数、两个键；
//   ③ 行只有一句话标题（≤40 字）——[LedgerRowView] 的 title 就是那句话，没有别的文本字段；
//   ④ 分组顺序固定（在飞＞待验收＞已收＞冻结＞撤），空组不占位；
//   ⑤ 归档项默认不出现，"全部"才多出末尾那一组；
//   跳转：来源号解析不出 ⇒ 那一行不可点（没有可跳的来源就不给跳）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ledger.LedgerCounts
import com.example.zhengdao.baopu.ledger.LedgerItem
import com.example.zhengdao.baopu.ledger.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerBoardRulesTest {

    private fun item(
        id: String,
        title: String,
        state: LedgerState = LedgerState.FLYING,
        source: String = "",
        archivedAtMs: Long = 0L,
    ) = LedgerItem(
        id = id,
        title = title,
        sourceMsgId = source,
        createdAtMs = 1_000L,
        status = state,
        archivedAtMs = archivedAtMs,
    )

    // ── 收起态那一行 ──────────────────────────────────────────────────────────

    @Test
    fun `收起态一行_账本三个数`() {
        val counts = LedgerCounts(flying = 2, pending = 1, accepted = 3, frozen = 4, withdrawn = 5, archived = 6)
        assertEquals("账本 · 在飞 2 / 待验收 1 / 已收 3", LedgerBoardRules.header(counts))
    }

    // ── ② 一次只展开一块（与待办块共用同一套） ────────────────────────────────

    @Test
    fun `展开槽位与待办块共用_点另一块换它_再点收回`() {
        // 开待办
        var slot: String? = TodoBoardRules.toggle(null, TodoBoardRules.BLOCK_TODO)
        assertEquals(TodoBoardRules.BLOCK_TODO, slot)
        // 点账本 ⇒ 待办自动收回（不是两块同开）
        slot = TodoBoardRules.toggle(slot, LedgerBoardRules.BLOCK_LEDGER)
        assertEquals(LedgerBoardRules.BLOCK_LEDGER, slot)
        // 再点账本 ⇒ 全收起
        assertNull(TodoBoardRules.toggle(slot, LedgerBoardRules.BLOCK_LEDGER))
        // 两个键必须不同（同键就变成"点账本把待办关了又开"）
        assertTrue(TodoBoardRules.BLOCK_TODO != LedgerBoardRules.BLOCK_LEDGER)
    }

    // ── ③ 行只有一句话标题 ────────────────────────────────────────────────────

    @Test
    fun `行只带标题_不带正文与来源文本`() {
        val rows = LedgerBoardRules.rows(listOf(item("L1", "把交付报告写完", source = "")), { null })
        val row = rows.single()
        assertEquals("把交付报告写完", row.title)
        assertEquals("没有来源号 ⇒ 不给跳", false, row.canJump)
        assertNull(row.jump)
        // 标题就是写入侧那句话本身（写入侧 ≤40 字由 LedgerRules.titleRefusal 保证）
        assertTrue("行标题不许超 40 字：${row.title}", row.title.length <= 40)
    }

    @Test
    fun `能跳的行_来源号解析成真消息才算`() {
        val ok = LedgerBoardRules.rows(listOf(item("L1", "有来源的一条", source = "12:34"))) {
            LedgerJump(12L, 34L)
        }.single()
        assertTrue("解析出来了就能点", ok.canJump)
        assertEquals(12L, ok.jump?.sessionId)
        assertEquals(34L, ok.jump?.messageId)

        // 有来源号但解析不出（比如"缺口-544"）⇒ 不给跳
        val bad = LedgerBoardRules.rows(listOf(item("L2", "来源号不是消息号", source = "缺口-544"))) { null }
            .single()
        assertFalse("解析不出就不给跳", bad.canJump)
    }

    @Test
    fun `空来源号连解析都不调`() {
        var calls = 0
        val rows = LedgerBoardRules.rows(listOf(item("L1", "没有来源号", source = "  "))) {
            calls++
            LedgerJump(1L, 2L)
        }
        assertFalse(rows.single().canJump)
        assertEquals("空来源号不该去查库", 0, calls)
    }

    // ── ④/⑤ 分组与归档 ──────────────────────────────────────────────────────

    @Test
    fun `分组顺序固定_空组不占位`() {
        val rows = LedgerBoardRules.rows(
            listOf(
                item("L1", "在飞的一件", LedgerState.FLYING),
                item("L2", "已收的一件", LedgerState.ACCEPTED),
                item("L3", "待验收的一件", LedgerState.PENDING),
            ),
            { null },
        )
        val groups = LedgerBoardRules.groups(rows, includeArchived = false)
        assertEquals(
            "顺序必须是 在飞＞待验收＞已收（冻结/撤在这批里没有条目 ⇒ 不占位）",
            listOf("flying", "pending", "accepted"),
            groups.map { it.key },
        )
        assertEquals(1, groups.first { it.key == "pending" }.rows.size)
    }

    @Test
    fun `冻结与撤排在最后_两组都占位`() {
        val rows = LedgerBoardRules.rows(
            listOf(
                item("L1", "冻结的", LedgerState.FROZEN),
                item("L2", "撤掉的", LedgerState.WITHDRAWN),
            ),
            { null },
        )
        assertEquals(
            listOf("frozen", "withdrawn"),
            LedgerBoardRules.groups(rows, includeArchived = false).map { it.key },
        )
    }

    @Test
    fun `归档的默认不露_切全部才见且排在最后`() {
        val rows = LedgerBoardRules.rows(
            listOf(
                item("L1", "露着的", LedgerState.ACCEPTED),
                item("L2", "归档的", LedgerState.ACCEPTED, archivedAtMs = 9_000L),
            ),
            { null },
        )
        val live = LedgerBoardRules.groups(rows, includeArchived = false)
        assertEquals("默认视图只有一组", listOf("accepted"), live.map { it.key })
        assertEquals("默认视图只有一条", 1, live.single().rows.size)
        assertEquals("露着的那条是 L1", "L1", live.single().rows.single().id)

        val all = LedgerBoardRules.groups(rows, includeArchived = true)
        assertEquals("「全部」多出末尾那一组", listOf("accepted", LedgerBoardRules.GROUP_ARCHIVED), all.map { it.key })
        assertEquals("归档那条在最后一组", "L2", all.last().rows.single().id)
    }

    // ── 来源号格式 ────────────────────────────────────────────────────────────

    @Test
    fun `来源号两种写法_别的写法一律不认`() {
        assertEquals("纯数字＝消息 id（会话待查）", 0L to 7L, LedgerBoardRules.parseJump("7"))
        assertEquals("会话:消息", 3L to 7L, LedgerBoardRules.parseJump(" 3:7 "))
        assertNull("中文编号不是消息号", LedgerBoardRules.parseJump("缺口-544"))
        assertNull("空", LedgerBoardRules.parseJump("   "))
        assertNull("会话 id 不许是 0", LedgerBoardRules.parseJump("0:7"))
        assertNull("三段不是合法形状", LedgerBoardRules.parseJump("1:2:3"))
    }

    // ── 跳转落点的下标（与消息列表同一套分组） ────────────────────────────────

    private fun msg(id: Long, role: String, toolPart: Boolean = false) = BaopuUiMessage(
        id = id,
        role = role,
        parts = listOf(BaopuUiPart(kind = if (toolPart) "tool" else "text", text = "x")),
        createdAt = id * 1000L,
    )

    @Test
    fun `纯聊天轮_一条消息一个 item`() {
        val messages = listOf(msg(10, "user"), msg(11, "assistant"), msg(12, "user"), msg(13, "assistant"))
        assertEquals(0, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 10L))
        assertEquals(1, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 11L))
        assertEquals(2, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 12L))
        assertEquals(3, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 13L))
    }

    @Test
    fun `有过程数据的轮只占一个 item`() {
        val messages = listOf(
            msg(10, "user"),
            msg(11, "assistant", toolPart = true), // 这一轮有工具卡 ⇒ 整轮折成一个 item
            msg(12, "user"),
            msg(13, "assistant"),
        )
        assertEquals("第一轮那个 item", 0, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 10L))
        assertEquals("轮里的助手消息也指向同一个 item", 0, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 11L))
        assertEquals("第二轮从 1 开始", 1, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 12L))
        assertEquals(2, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 13L))
    }

    @Test
    fun `找不到那条消息_回-1`() {
        val messages = listOf(msg(10, "user"), msg(11, "assistant"))
        assertEquals(-1, LedgerBoardRules.messageItemIndex(messages, emptyList(), null, 999L))
        assertEquals("空列表也不崩", -1, LedgerBoardRules.messageItemIndex(emptyList(), emptyList(), null, 1L))
    }
}
