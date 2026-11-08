// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约账的形状（`.leases.jsonl`：点号、不计产物、坏行不炸、按 id/op 回放）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.TEXT_ARTIFACT_EXTENSIONS
import com.example.zhengdao.baopu.isTextArtifactPath
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaseLedgerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("baopu467-ledger").toFile()
        LeaseLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        LeaseStore.resetForTest()
        root.deleteRecursively()
    }

    @Test
    fun `落点是 产物 下的点号文件`() {
        val f = LeaseLedger.fileIn(root)
        assertEquals("产物", f.parentFile.name)
        assertEquals(".leases.jsonl", f.name)
        assertTrue("点号开头（产物整理/删除不碰它）", f.name.startsWith("."))
    }

    @Test
    fun `不计产物数_jsonl 不在文本产物白名单_点号也不在白名单含义里`() {
        assertFalse("jsonl", "jsonl" in TEXT_ARTIFACT_EXTENSIONS)
        assertFalse(isTextArtifactPath("产物/.leases.jsonl"))
    }

    @Test
    fun `坏行不炸_只跳过`() {
        val f = LeaseLedger.fileIn(root)
        f.parentFile?.mkdirs()
        f.appendText("{不是 json\n", Charsets.UTF_8)
        val g = LeaseStore.grant(
            LeaseOffer("files/delete", LeaseScope(LeaseScope.Kind.WORKSPACE, root.path), 5, 1000L),
            1L,
            "c1",
            1_700_000_000_000L,
        )
        val rows = LeaseLedger.replay()
        assertEquals("只有一行可读", 1, rows.size)
        assertEquals(g.id, rows.first().optString("id"))
    }

    @Test
    fun `按 id 与按 op 都回放得出_且时序单调`() {
        val g = LeaseStore.grant(
            LeaseOffer("files/delete", LeaseScope(LeaseScope.Kind.WORKSPACE, root.path), 5, 3_600_000L),
            1L,
            "c1",
            1_700_000_000_000L,
        )
        val hit = LeaseStore.hit(1L, "files/delete", emptyList(), org.json.JSONObject(), 1, "op-1", 1_700_000_001_000L)
        assertTrue(hit is LeaseVerdict.Hit)
        LeaseStore.noteUsed(LeaseStore.takePending("op-1")!!, "delete_path", "ok", 1_700_000_002_000L)
        val byId = LeaseLedger.rowsFor(g.id)
        assertEquals(3, byId.size)
        assertEquals(byId.map { it.optLong("at") }.sorted(), byId.map { it.optLong("at") })
        assertEquals(2, LeaseLedger.rowsForOp("op-1").size)
        assertEquals(LeaseLedger.VERSION, byId.first().optInt("v"))
    }

    @Test
    fun `没装落点_只进内存_不抛`() {
        LeaseLedger.resetForTest()
        val g = LeaseStore.grant(
            LeaseOffer("files/delete", LeaseScope(LeaseScope.Kind.WORKSPACE, root.path), 5, 1000L),
            1L,
            "c1",
            1_700_000_000_000L,
        )
        assertTrue("内存里照样有", LeaseStore.active(1L).any { it.id == g.id })
        assertTrue(LeaseLedger.replay().isEmpty())
    }
}
