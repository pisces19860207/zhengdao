// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MemoryStore / EntryCodec 单测（刀 0b · 2026-10-16）。
 *
 * 覆盖派工单 §4.2 与判据 1/3：文件往返、**reindex 重建**（删库也能从文件全回来）、
 * 未确认不落盘、来源字段、单条删改。
 */
class MemoryStoreTest {

    @Rule
    @JvmField
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var globalRoot: File
    private lateinit var projectRoot: File
    private lateinit var store: MemoryStore

    private fun setup() {
        globalRoot = tmp.newFolder("app-全局")
        projectRoot = tmp.newFolder("ws")
        store = MemoryStore(globalRoot, projectRoot)
    }

    private fun confirmed(
        id: String = "1729000000000-称呼偏好",
        scope: MemoryScope = MemoryScope.PROJECT,
        title: String = "称呼偏好",
        body: String = "用户希望被称呼为老师",
    ) = MemoryEntry(
        id = id,
        scope = scope,
        kind = MemoryKind.PREFERENCE,
        title = title,
        body = body,
        tags = listOf("称呼"),
        sourceSession = "s7",
        sourceFile = "资料库/原始/设定.md",
        status = MemoryStatus.CONFIRMED,
        createdAt = 1_729_000_000_000L,
        updatedAt = 1_729_000_000_000L,
    )

    // ── Codec 往返 ────────────────────────────────────────────────────────

    @Test
    fun `前后置码_渲染再解析逐字段一致`() {
        val e = confirmed()
        val parsed = EntryCodec.parse(EntryCodec.render(e))
        assertNotNull("解析不应为 null", parsed)
        assertEquals(e, parsed)
    }

    @Test
    fun `前后置码_正文多行不丢`() {
        val e = confirmed(body = "第一行\n第二行\n\n第三行")
        assertEquals(e.body, EntryCodec.parse(EntryCodec.render(e))!!.body)
    }

    @Test
    fun `前后置码_坏文件返回null`() {
        assertNull(EntryCodec.parse("没有 front-matter 的纯文本"))
        assertNull(EntryCodec.parse("---\n缺闭合\n"))
        assertNull(EntryCodec.parse("---\nscope: project\nkind: preference\n---\n\n缺 id 与 title"))
        assertNull(EntryCodec.parse("---\nid: x\nscope: 不存在的枚举\nkind: preference\n---\n\n正文"))
    }

    @Test
    fun `前后置码_大小写枚举与缺省状态容错`() {
        val e = confirmed().copy(status = MemoryStatus.CANDIDATE)
        val text = EntryCodec.render(e).replace("status: candidate", "status: CANDIDATE")
        assertEquals(MemoryStatus.CANDIDATE, EntryCodec.parse(text)!!.status)
        val noStatus = EntryCodec.render(e).lines().filterNot { it.startsWith("status:") }.joinToString("\n")
        assertEquals(MemoryStatus.CANDIDATE, EntryCodec.parse(noStatus)!!.status) // 缺省按候选
    }

    // ── 写入闸门 ──────────────────────────────────────────────────────────

    @Test
    fun `未确认的记忆不落盘`() {
        setup()
        val r = store.write(confirmed().copy(status = MemoryStatus.CANDIDATE))
        assertFalse("候选态不许落盘", r.ok)
        assertTrue(r.reason.contains("未确认"))
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `确认的记忆落盘且人能读`() {
        setup()
        val r = store.write(confirmed())
        assertTrue(r.ok)
        assertNotNull(r.file)
        val text = r.file!!.readText()
        assertTrue("文件是真相：人眼可读", text.contains("称呼偏好") && text.contains("source_session: s7"))
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun `重复内容不重复入库`() {
        setup()
        assertTrue(store.write(confirmed(id = "1-称呼偏好")).ok)
        val r = store.write(confirmed(id = "2-称呼偏好")) // 同 scope+kind+正文近似
        assertFalse(r.ok)
        assertTrue(r.reason.contains("重复"))
    }

    @Test
    fun `不同内容是另一条`() {
        setup()
        assertTrue(store.write(confirmed(id = "1-a", body = "用户希望被称呼为老师")).ok)
        assertTrue(store.write(confirmed(id = "2-b", body = "平台的积分刺客规则每发一条扣十积分")).ok)
        assertEquals(2, store.loadAll().size)
    }

    @Test
    fun `候选不合格拒绝落盘`() {
        setup()
        val r = store.write(confirmed().copy(body = "x".repeat(600)))
        assertFalse(r.ok)
        assertTrue(r.reason.contains("不合格"))
    }

    @Test
    fun `同名id撞车拒绝而不覆盖`() {
        setup()
        assertTrue(store.write(confirmed(id = "同一个id")).ok)
        val r = store.write(confirmed(id = "同一个id", body = "完全不同的正文内容"))
        assertFalse("撞 id 不许覆盖已有文件", r.ok)
        assertEquals("用户希望被称呼为老师", store.loadAll().first().body)
    }

    // ── reindex（判据 1：删掉 db 也能从文件重建） ─────────────────────────

    @Test
    fun `reindex_从文件重建全部条目`() {
        setup()
        store.write(confirmed(id = "1-a", scope = MemoryScope.PROJECT))
        store.write(confirmed(id = "2-b", scope = MemoryScope.GLOBAL, body = "全局的那一条内容完全不同"))
        val r = store.reindex()
        assertEquals(2, r.entries.size)
        assertTrue(r.brokenFiles.isEmpty())
        // 两条各就各位：项目在 projectRoot、全局在 globalRoot
        assertTrue(File(store.projectDir(), "1-a.md").isFile)
        assertTrue(File(store.globalDir(), "2-b.md").isFile)
    }

    @Test
    fun `reindex_坏文件报出来不静默`() {
        setup()
        store.write(confirmed(id = "1-a"))
        File(store.projectDir(), "坏文件.md").writeText("这不是一条合法记忆")
        val r = store.reindex()
        assertEquals(1, r.entries.size)
        assertEquals(listOf("坏文件.md"), r.brokenFiles)
    }

    @Test
    fun `loadAll与reindex同源`() {
        setup()
        store.write(confirmed(id = "1-a"))
        assertEquals(store.loadAll(), store.reindex().entries)
    }

    @Test
    fun `目录不存在时reindex返回空不炸`() {
        globalRoot = tmp.newFolder("app2")
        projectRoot = tmp.newFolder("ws2")
        store = MemoryStore(globalRoot, projectRoot)
        val r = store.reindex()
        assertTrue(r.entries.isEmpty())
        assertTrue(r.brokenFiles.isEmpty())
    }

    // ── 改与删 ────────────────────────────────────────────────────────────

    @Test
    fun `按id改内容文件位置不变`() {
        setup()
        store.write(confirmed(id = "1-a"))
        assertTrue(store.update(confirmed(id = "1-a", body = "改过的正文")))
        val loaded = store.loadAll().first()
        assertEquals("改过的正文", loaded.body)
        assertTrue(File(store.projectDir(), "1-a.md").isFile)
    }

    @Test
    fun `按id删单条`() {
        setup()
        store.write(confirmed(id = "1-a"))
        store.write(confirmed(id = "2-b", body = "另一条完全不同的内容"))
        assertTrue(store.delete("1-a"))
        assertEquals(listOf("2-b"), store.loadAll().map { it.id })
        assertFalse(store.delete("不存在的id"))
    }

    @Test
    fun `改不存在的id返回false`() {
        setup()
        assertFalse(store.update(confirmed(id = "幽灵")))
    }

    // ── 召回 ──────────────────────────────────────────────────────────────

    @Test
    fun `recall只召 confirmed 且按上界裁`() {
        setup()
        store.write(confirmed(id = "1-a", body = "用户希望被称呼为老师"))
        store.write(confirmed(id = "2-b", body = "另一条关于称呼的完全不同的话"))
        // 手工塞一条未确认的（不经 write——write 会拒）
        val pending = confirmed(id = "3-c", body = "未确认的那一条").copy(status = MemoryStatus.PENDING)
        File(store.projectDir(), "3-c.md").writeText(EntryCodec.render(pending))
        val r = store.recall("称呼", budgetTokens = 10_000)
        assertEquals(listOf("1-a", "2-b"), r.picked.map { it.id })
    }
}
