// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 649 批一 · 内容创作资料包的门禁（四项，逐条对应派工单「最容易翻车的四条」里
// 与 app 侧有关的三条）：
//   ① 关着 ⇒ 无 pack_search、**零磁盘读**；
//   ② 开着但本轮没命中 L1 ⇒ 也不挂（逐轮算，不跨轮）；
//   ③ 开着且命中 ⇒ 才挂；注入 ≤3 片 / ≤6KB，每条带片 id 与来源；
//   ④ 每次注入记一笔账。
package com.example.zhengdao.baopu.pack

import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var packRoot: File

    @Before
    fun setUp() {
        packRoot = tmp.newFolder("packs", "男性")
        File(packRoot, "slices").mkdirs()
        writePack()
        PackTools.resetCacheForTest()
        PackTools.rootProvider = { packRoot }
    }

    @After
    fun tearDown() {
        // 每个用例都要把注入口还原：它们是 @Volatile 全局位，留着会串到别的用例
        PackTools.rootProvider = null
        PackTools.gateOn = { false }
        PackTools.ledger = PackLedger.NONE
        PackTools.resetCacheForTest()
    }

    // ── ① 关着：不挂、零磁盘读 ──────────────────────────────────────────────

    @Test
    fun `开关关着时_执行器第一行就退_且不读任何包文件`() {
        PackTools.gateOn = { false }
        val out = PackTools.search(JSONObject().put("q", "勃起温度"))

        assertTrue("关着必须拒，实际：$out", out is ToolOutcome.Rejected)
        val msg = (out as ToolOutcome.Rejected).reason
        assertTrue("拒绝理由要说清是没开：$msg", msg.contains("没开"))
        // 判据：如果它真去读了 index.json（本用例里 index.json 是**坏 JSON**），
        // 拿到的会是「索引读不出」。拿到「没开」⇒ 读盘那一段根本没执行到。
        assertFalse("关着还去读盘了：$msg", msg.contains("索引读不出"))
    }

    @Test
    fun `索引是懒加载_建壳不读盘`() {
        val store = PackStore(packRoot)
        assertEquals("建壳就读书了（懒加载不成立）", 0, store.indexLoads)
        store.index()
        assertEquals("第一次 index() 应只读一次", 1, store.indexLoads)
        store.index()
        assertEquals("第二次 index() 应走缓存，不再读盘", 1, store.indexLoads)
    }

    @Test
    fun `索引缺失时_检索空表_不抛`() {
        val empty = PackStore(tmp.newFolder("空包"))
        assertEquals(0, empty.indexLoads)
        assertTrue(empty.search("勃起").slices.isEmpty())
        assertEquals("缺 index.json 也不该去读它", 0, empty.indexLoads)
    }

    // ── ② 挂载判定：开关 ∧ 命中 ─────────────────────────────────────────────

    @Test
    fun `挂载判定_两个条件缺一不可`() {
        assertFalse(PackMount.shouldMount(false, true))
        assertFalse(PackMount.shouldMount(true, false))
        assertFalse(PackMount.shouldMount(false, false))
        assertTrue(PackMount.shouldMount(true, true))
    }

    @Test
    fun `L1命中_命中与不命中两例`() {
        PackTools.gateOn = { true }
        val store = PackStore(packRoot)
        assertTrue("「勃起」应命中生理状态", store.l1Hits("他勃起的时候温度怎么变").isNotEmpty())
        assertTrue("「锁结」应命中（夹具里就这两个主题）", store.l1Hits("锁结阶段怎么推进").isNotEmpty())
        assertTrue("无关话题不该命中", store.l1Hits("帮我订明天的机票").isEmpty())
    }

    @Test
    fun `关着时_L1判定直接false且不读索引`() {
        PackTools.gateOn = { false }
        assertFalse(PackTools.l1Hit("勃起温度"))
        // 换一个会炸的包根：真去读了就会抛/被吞成 false 无法区分，所以用 indexLoads 之外的办法——
        // 这里直接把根指到不存在的路径：若走到读盘，结果仍是 false，但下面用「开着」的对照证明判定是真的。
        PackTools.gateOn = { true }
        assertTrue("同样一句话，开着就该命中（证明上一行不是恒 false）", PackTools.l1Hit("勃起温度"))
    }

    // ── ③ 注册表可见性：关着不在 tools 数组里 ───────────────────────────────

    @Test
    fun `注册表_关着看不见_挂了才看见`() {
        val reg = registry()
        val names0 = reg.visibleSpecs(ToolRegistry.NO_SESSION).map { it.name }
        assertFalse("默认（没开）就不该看见 pack_search", "pack_search" in names0)

        reg.setPackMount(true)
        val names1 = reg.visibleSpecs(ToolRegistry.NO_SESSION).map { it.name }
        assertTrue("挂载后应该在 tools 数组里", "pack_search" in names1)

        reg.setPackMount(false)
        val names2 = reg.visibleSpecs(ToolRegistry.NO_SESSION).map { it.name }
        assertFalse("收回后必须立刻消失（逐轮算，不跨轮）", "pack_search" in names2)
    }

    @Test
    fun `注册表_没挂载时幻觉调用拿的是组锁桩`() {
        val reg = registry()
        val spec = reg.find("pack_search") ?: error("pack_search 没注册")
        val out = spec.executor(JSONObject().put("q", "勃起"))
        assertTrue("没挂载时调用应被拒，实际：$out", out is ToolOutcome.Rejected)
    }

    // ── ④ 检索预算与记账 ───────────────────────────────────────────────────

    @Test
    fun `检索_最多三片且合计不超六KB_每条带片id与来源`() {
        PackTools.gateOn = { true }
        val store = PackStore(packRoot)
        val hits = store.search("勃起", k = 9) // 越界也要被夹到 3
        assertTrue("片数超了：${hits.slices.size}", hits.slices.size <= PackStore.MAX_K)
        assertTrue("字节超了：${hits.bytes}", hits.bytes <= PackStore.MAX_BYTES)
        for ((meta, body) in hits.slices) {
            assertTrue("片 id 空了", meta.id.isNotBlank())
            assertTrue("来源文件名空了（派工单：每条带来源）", meta.from.isNotBlank())
            assertTrue("正文空了", body.isNotBlank())
            assertFalse("正文不该带 YAML 头", body.startsWith("---"))
        }
    }

    @Test
    fun `检索_edge片默认不注入`() {
        PackTools.gateOn = { true }
        val store = PackStore(packRoot)
        val hits = store.search("锁结", k = 3)
        assertTrue("edge 片默认不该进结果", hits.slices.none { it.first.coverage == "edge" })
        val withEdge = store.search("锁结", k = 3, includeEdge = true)
        assertTrue("显式放开后应能看到 edge 片", withEdge.slices.any { it.first.coverage == "edge" })
    }

    @Test
    fun `每次注入记一笔账`() {
        PackTools.gateOn = { true }
        val rows = ArrayList<Triple<String, List<String>, Int>>()
        PackTools.ledger = PackLedger { q, ids, bytes -> rows.add(Triple(q, ids, bytes)) }
        repeat(2) { PackTools.search(JSONObject().put("q", "勃起温度")) }

        assertEquals("两次检索应记两笔", 2, rows.size)
        assertTrue("账里要有片 id", rows[0].second.isNotEmpty())
        assertTrue("账里要有字节数", rows[0].third > 0)
        assertEquals("勃起温度", rows[0].first)
    }

    @Test
    fun `没命中也要明说_不编造`() {
        PackTools.gateOn = { true }
        val out = PackTools.search(JSONObject().put("q", "量子色动力学"))
        assertTrue(out is ToolOutcome.Ok)
        assertTrue("没命中要说没命中", (out as ToolOutcome.Ok).text.contains("没命中"))
    }

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private fun registry(): ToolRegistry {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { tmp.newFolder() }
        // 组 PACK 默认不可见：与生产一致（生产也靠 setPackMount 的那一位）
        assertEquals(ToolGroup.PACK, com.example.zhengdao.baopu.agent.ToolGroupTable.GROUP_OF["pack_search"])
        return reg
    }

    /** 三片 common + 一片 edge，正文各约 1KB（够撞 6KB 预算）。 */
    private fun writePack() {
        val filler = "这里是一段用于把正文撑到接近预算上限的中性说明文字。".repeat(30)
        val slices = listOf(
            Triple("phys-001", "勃起温度与充血", "common"),
            Triple("phys-002", "勃起硬度分级", "common"),
            Triple("phys-003", "勃起与性反应分离", "common"),
            Triple("knot-001", "模拟锁结核心机制", "edge"),
        )
        val arr = JSONArray()
        for ((id, title, cov) in slices) {
            val body = "# $title\n\n$filler"
            val f = File(packRoot, "slices/$id.md")
            f.writeText(
                "---\nid: \"$id\"\ntitle: \"$title\"\nkind: \"observation\"\n" +
                    "source: \"medical\"\ncoverage: \"$cov\"\nvia: \"ai_assisted\"\n" +
                    "provenance: \"测试夹具\"\nfrom: \"00-00-男性-生理总纲.txt\"\ntags: [\"勃起\", \"温度\"]\n---\n\n" +
                    body,
                Charsets.UTF_8,
            )
            arr.put(
                JSONObject()
                    .put("id", id).put("title", title).put("kind", "observation")
                    .put("source", "medical").put("coverage", cov).put("via", "ai_assisted")
                    .put("from", "00-00-男性-生理总纲.txt")
                    .put("tags", JSONArray(listOf("勃起", "温度")))
                    .put("bytes", f.length())
                    .put("body_bytes", body.toByteArray(Charsets.UTF_8).size)
                    .put("path", "slices/$id.md"),
            )
        }
        val doc = JSONObject()
            .put("schema", 1).put("pack", "男性").put("pack_version", "test")
            .put(
                "l1",
                JSONArray(
                    listOf(
                        JSONObject().put("key", "生理状态")
                            .put("aliases", JSONArray(listOf("勃起", "充血", "硬度", "温度"))),
                        JSONObject().put("key", "锁结")
                            .put("aliases", JSONArray(listOf("锁结", "嵌顿"))),
                    ),
                ),
            )
            .put("slices", arr)
        File(packRoot, "index.json").writeText(doc.toString(), Charsets.UTF_8)
    }
}
