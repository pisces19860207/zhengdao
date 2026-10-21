// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：派工单-2026-10-21-第一百二十八批 §3 的工具层与负例：
// - 负例 N3「全盘自动扫描」：kb_index 缺省只索引 资料库/投递（绝不是工作区根），
//   越界（../、绝对路径、盘符）一律拒；
// - 负例 N2 行为侧：kb_search 每段输出都带来源；
// - 没索引/索引损坏：明说先建索引，不抛不阻塞；
// - Schema 契约：未知参数拒、必填缺失拒、取值范围拒（与 ToolSchemaAllTest 同一条链）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.kb.KbDoc
import com.example.zhengdao.baopu.kb.KbChunk
import com.example.zhengdao.baopu.kb.KbIndex
import com.example.zhengdao.baopu.kb.KbIndexStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KbToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 用完必须还原：KbTools.store 是进程级接线点，别污染同 JVM 的别的用例。 */
    @After
    fun cleanup() {
        KbTools.store = null
    }

    private fun ws(): File = tmp.newFolder("ws")

    private fun useStore(filesDir: File = tmp.newFolder("files")): KbIndexStore =
        KbIndexStore(filesDir).also { KbTools.store = it }

    private fun write(dir: File, rel: String, text: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun args(vararg pairs: Pair<String, Any?>): org.json.JSONObject =
        org.json.JSONObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    // ── 缺省目录（负例 N3 的行为靶子） ────────────────────────────────────

    @Test
    fun `缺省目录就是资料库投递_绝不是工作区根`() {
        assertEquals("资料库/投递", KbTools.DEFAULT_DIR_REL)
        val root = ws()
        write(root, "根目录的噪声.md", "根上的文件不该被默认索引")
        write(root, "资料库/投递/书.txt", "投递里的书")

        useStore()
        val out = KbTools.index(args(), root)
        assertTrue("应成功：${out}", out is ToolOutcome.Ok)
        val index = KbTools.store!!.load()!!
        assertEquals("资料库/投递", index.rootLabel)
        val names = index.docs.map { it.relPath }
        assertTrue("投递里的书要进索引", names.contains("书.txt"))
        assertFalse("根目录的噪声绝不进索引（N3）", names.any { it.contains("噪声") })
    }

    @Test
    fun `显式目录_只索引指定目录`() {
        val root = ws()
        write(root, "资料库/投递/投递的书.txt", "投递")
        write(root, "小说/我的书.txt", "小说")
        useStore()
        val out = KbTools.index(args("path" to "小说"), root)
        assertTrue(out is ToolOutcome.Ok)
        val index = KbTools.store!!.load()!!
        assertEquals(listOf("我的书.txt"), index.docs.map { it.relPath })
        assertEquals("小说", index.rootLabel)
    }

    // ── 越界拒（负例 N3 的形态侧） ────────────────────────────────────────

    @Test
    fun `越界路径一律拒`() {
        val root = ws()
        useStore()
        for (bad in listOf("../外面", "/绝对路径", "C:\\Windows", "资料库/../../ escape")) {
            val out = KbTools.index(args("path" to bad), root)
            assertTrue("$bad 应被拒", out is ToolOutcome.Rejected)
        }
    }

    // ── UI-D9：工作区内绝对路径必须能索引（真机 2026-10-22） ──────────────

    @Test
    fun `工作区内绝对路径_可以索引`() {
        val root = ws()
        write(root, "资料库/整理/书.txt", "整理区里的书")
        useStore()
        val abs = File(root, "资料库/整理").canonicalPath
        val out = KbTools.index(args("path" to abs), root)
        assertTrue("工作区内绝对路径应被接受：$out", out is ToolOutcome.Ok)
        val index = KbTools.store!!.load()!!
        assertTrue("整理区里的书要进索引", index.docs.any { it.relPath.contains("书.txt") })
    }

    @Test
    fun `工作区外绝对路径_仍然拒`() {
        val root = ws()
        val outside = tmp.newFolder("outside")
        useStore()
        val out = KbTools.index(args("path" to File(outside, "x").canonicalPath), root)
        assertTrue("工作区外绝对路径必须拒", out is ToolOutcome.Rejected)
    }

    // ── UI-D12：卡面要写「将要索引哪个目录」，不许回显空对象 ────────────────

    @Test
    fun `卡面目录_没传path时给缺省_传了照抄`() {
        assertEquals(KbTools.DEFAULT_DIR_REL, KbTools.effectiveTarget(args()))
        assertEquals("小说", KbTools.effectiveTarget(args("path" to "小说")))
    }

    @Test
    fun `目录不存在_明说`() {
        val root = ws()
        useStore()
        val out = KbTools.index(args("path" to "不存在的目录"), root)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("目录不存在"))
    }

    @Test
    fun `指向文件不是目录_明说`() {
        val root = ws()
        write(root, "单个文件.txt", "内容")
        useStore()
        val out = KbTools.index(args("path" to "单个文件.txt"), root)
        assertTrue(out is ToolOutcome.Failed)
    }

    // ── 增量与 rebuild 语义 ───────────────────────────────────────────────

    @Test
    fun `默认增量_rebuild全量`() {
        val root = ws()
        write(root, "资料库/投递/a.txt", "旧内容")
        useStore()
        assertTrue(KbTools.index(args(), root) is ToolOutcome.Ok)
        Thread.sleep(1100)
        write(root, "资料库/投递/a.txt", "新内容")
        // 增量：内容变了 ⇒ 重切进新内容
        assertTrue(KbTools.index(args(), root) is ToolOutcome.Ok)
        var idx = KbTools.store!!.load()!!
        assertTrue(idx.docs.single().chunks.any { it.text.contains("新内容") })
        // rebuild=true：全量重切（读数 reused=0 由 KbIndexerTest 钉，这里只验不报错）
        assertTrue(KbTools.index(args("rebuild" to true), root) is ToolOutcome.Ok)
        idx = KbTools.store!!.load()!!
        assertTrue(idx.docs.single().chunks.any { it.text.contains("新内容") })
    }

    // ── kb_search：没索引 / 有索引 / 没命中 ──────────────────────────────

    @Test
    fun `没索引_明说先建索引`() {
        useStore()
        val out = KbTools.search(args("query" to "进城"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("kb_index"))
    }

    @Test
    fun `索引损坏_也明说先重建_不抛异常`() {
        val s = useStore()
        assertTrue(s.save(sampleIndex()))
        s.indexPath().writeText("{ 坏")
        val out = KbTools.search(args("query" to "任何词"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("损坏"))
    }

    @Test
    fun `有索引_命中带来源`() {
        val s = useStore()
        assertTrue(s.save(sampleIndex()))
        val out = KbTools.search(args("query" to "紫禁之巅"))
        assertTrue("应命中：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("每段都要带来源（N2 行为侧）", text.contains("【来源："))
        assertTrue(text.contains("书.txt"))
        assertTrue(text.contains("第 1-3 行"))
    }

    @Test
    fun `无命中_明说没找到_不硬凑`() {
        val s = useStore()
        assertTrue(s.save(sampleIndex()))
        val out = KbTools.search(args("query" to "完全不搭边的词"))
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("没找到"))
    }

    @Test
    fun `查询为空_拒`() {
        val s = useStore()
        assertTrue(s.save(sampleIndex()))
        val out = KbTools.search(args("query" to "  "))
        assertTrue(out is ToolOutcome.Failed)
    }

    // ── Schema 契约（走注册表同一条校验链） ──────────────────────────────

    @Test
    fun `两个工具的schema都合规`() {
        val reg = ToolRegistry()
        BaopuToolCatalog.loadInto(reg) { ws() }
        for (name in listOf(KbTools.NAME_INDEX, KbTools.NAME_SEARCH)) {
            val spec = reg.all().first { it.name == name }
            val issues = ToolSchemaRules.audit(name, spec.inputSchema, spec.description)
            assertTrue("$name schema 不合规：$issues", issues.isEmpty())
            // 分组：资料库组（默认不可见，要 capabilities 解锁）
            assertEquals(ToolGroup.LIBRARY, spec.group)
        }
        // 默认可见 12 条不变（kb 两条在 LIBRARY 组，默认不可见）
        assertEquals(12, reg.visibleSpecs(ToolRegistry.NO_SESSION).size)
    }

    @Test
    fun `未知参数与越界参数被拒`() {
        val reg = ToolRegistry()
        BaopuToolCatalog.loadInto(reg) { ws() }
        assertNotNull(reg.validateCall("kb_search", args("query" to "x", "zd_probe" to 1)))
        assertNotNull(reg.validateCall("kb_search", args("query" to "x", "top_k" to 99)))
        assertNotNull(reg.validateCall("kb_index", args("path" to "a", "rebuild" to "不是布尔")))
    }

    @Test
    fun `store没注入_两个工具都明说不猜不崩`() {
        KbTools.store = null
        assertTrue(KbTools.index(args(), ws()) is ToolOutcome.Failed)
        assertTrue(KbTools.search(args("query" to "x")) is ToolOutcome.Failed)
    }

    // ── 自动注入入口 ──────────────────────────────────────────────────────

    @Test
    fun `自动注入_没索引返回null不阻塞`() {
        useStore()
        assertNull(KbTools.autoInjectNote("用户的问题"))
    }

    @Test
    fun `自动注入_有索引无命中返回null`() {
        val s = useStore()
        assertTrue(s.save(sampleIndex()))
        assertNull(KbTools.autoInjectNote("风马牛不相及的问题"))
    }

    @Test
    fun `自动注入_命中产note带来源`() {
        val s = useStore()
        assertTrue(s.save(sampleIndex()))
        val note = KbTools.autoInjectNote("紫禁之巅")
        assertNotNull(note)
        assertTrue(note!!.contains("【来源："))
        assertTrue(note.contains("知识库"))
    }

    private fun sampleIndex(): KbIndex = KbIndex(
        root = "/tmp/ws",
        rootLabel = "资料库/投递",
        updatedAt = 1L,
        docs = listOf(
            KbDoc(
                relPath = "书.txt", size = 10, mtime = 20, kind = "text",
                chunks = listOf(KbChunk("书.txt", 1, 3, "第三章", "决战紫禁之巅", KbChunk.SOURCE_FILE)),
            ),
        ),
    )
}
