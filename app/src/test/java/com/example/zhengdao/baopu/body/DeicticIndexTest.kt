// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十批 · 双向映射索引判据夹具。纯内存断言，零写盘（水位 607 口径不受影响）。
// 判据①覆盖13区每区≥3；②正向10条（含3含糊）命中；③反向链与731一致；
// ④歧义列候选+区分；⑥民间别名不进规范输出＋每条source/asOf。
package com.example.zhengdao.baopu.body

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeicticIndexTest {

    private fun root(): File =
        listOf(File("."), File("..")).first { File(it, DeicticIndex.REL_PATH).isFile }

    private val idx: DeicticIndex by lazy { DeicticIndex.load(root()) }

    // ── 判据① 覆盖 13 区，每区 ≥3 条位置描述（缺 ⇒ 拒收）──
    @Test
    fun `判据1_覆盖13区_与anatomy_zones全集相等`() {
        val zones = File(root(), AnatomyZones.REL_PATH).readText()
        val expected = AnatomyZones.parse(zones).map { it.zoneId }.toSet()
        assertEquals(13, idx.zones.size)
        assertEquals(expected, idx.zones.map { it.zoneId }.toSet())
    }

    @Test
    fun `判据1_每区描述不少于3且字段齐`() {
        idx.zones.forEach { z ->
            assertTrue("${z.zoneId} 描述<3", z.descriptions.size >= 3)
            assertTrue("${z.zoneId} 描述含空 keys", z.descriptions.all { it.keys.isNotEmpty() })
            assertTrue("${z.zoneId} 描述 source/asOf 不齐", z.descriptions.all {
                it.source.isNotBlank() && it.asOf.isNotBlank()
            })
            assertTrue("${z.zoneId} entry source/asOf 不齐", z.source.isNotBlank() && z.asOf.isNotBlank())
        }
    }

    // ── 判据② 正向：10 条口语（含 3 条故意含糊）全部对上正确术语 ──
    private data class Case(val text: String, val expect: String, val vague: Boolean)

    private val cases = listOf(
        Case("龟头后面那道沟", "sulcus", false),
        Case("冠下面收进去那圈", "sulcus", false),
        Case("脖子那圈凹", "sulcus", false),
        Case("龟头边上鼓起来那一圈", "corona", false),
        Case("最宽的那道边", "corona", false),
        Case("撒尿的那个眼", "meatus", false),
        Case("中间那根长条", "shaft", false),
        Case("兜着蛋的袋子里那两颗", "scrotum", true),   // 含糊：scrotum/testis
        Case("连到阴囊的中线旁边一拽就紧", "raphe", true), // 含糊：raphe/frenulum
        Case("那道沟和那层皮", "sulcus", true),            // 含糊：sulcus/prepuce
    )

    @Test
    fun `判据2_正向10条_7明确唯一命中`() {
        val plain = cases.filter { !it.vague }
        assertEquals(7, plain.size)
        plain.forEach { c ->
            val r = idx.resolve(c.text)
            assertEquals("明确「${c.text}」应唯一命中 ${c.expect}，实得 ${r.candidates}",
                listOf(c.expect), r.candidates)
            assertFalse(r.ambiguous)
            assertNull(r.disambiguation)
        }
    }

    @Test
    fun `判据2_正向10条_3含糊列候选含正确术语`() {
        val vague = cases.filter { it.vague }
        assertEquals(3, vague.size)
        vague.forEach { c ->
            val r = idx.resolve(c.text)
            assertTrue("含糊「${c.text}」应含 ${c.expect}，实得 ${r.candidates}", c.expect in r.candidates)
            assertTrue("含糊「${c.text}」应判歧义", r.ambiguous)
            assertNotNull("含糊「${c.text}」应给区分办法", r.disambiguation)
        }
    }

    @Test
    fun `判据2_命中外来口语_零命中零候选`() {
        val r = idx.resolve("今天天气很好")
        assertTrue(r.candidates.isEmpty())
        assertFalse(r.ambiguous)
    }

    // ── 判据③ 反向：术语⇒上一环/下一环/相邻，顺序链与 731 一致 ──
    @Test
    fun `判据3_顺序链与731六节点逐字一致`() {
        assertEquals(
            listOf("apex", "glansbody", "corona", "sulcus", "neckTaper", "shaft"),
            idx.chain.core731,
        )
    }

    @Test
    fun `判据3_龟头坡在apex_corona间_颈过渡在sulcus_shaft间`() {
        val order = idx.chain.order
        assertTrue(order.indexOf("apex") < order.indexOf("glansbody"))
        assertTrue(order.indexOf("glansbody") < order.indexOf("corona"))
        assertTrue(order.indexOf("sulcus") < order.indexOf("neckTaper"))
        assertTrue(order.indexOf("neckTaper") < order.indexOf("shaft"))
    }

    @Test
    fun `判据3_反向查corona_sulcus的上下环`() {
        val corona = idx.chainOf("corona")!!
        assertEquals("glansbody", corona.prev)
        assertEquals("sulcus", corona.next)
        val sulcus = idx.chainOf("sulcus")!!
        assertEquals("corona", sulcus.prev)
        assertEquals("neckTaper", sulcus.next)
    }

    @Test
    fun `判据3_frenulum落sulcus_prepuce跨corona_sulcus`() {
        assertEquals("sulcus", idx.chain.side["frenulum"]!!.at)
        assertEquals(listOf("corona", "sulcus"), idx.chain.side["prepuce"]!!.spans)
        assertTrue("sulcus 相邻应含 frenulum", "frenulum" in idx.chainOf("sulcus")!!.adjacent)
        val pre = idx.chainOf("prepuce")!!
        assertTrue("prepuce 相邻含 corona", "corona" in pre.adjacent)
        assertTrue("prepuce 相邻含 sulcus", "sulcus" in pre.adjacent)
    }

    @Test
    fun `判据3_锚点host指向13区_非独立分区`() {
        assertEquals("glans", idx.chain.nodes["apex"]!!.host)
        assertEquals("glans", idx.chain.nodes["glansbody"]!!.host)
        assertEquals("shaft", idx.chain.nodes["neckTaper"]!!.host)
        assertEquals("anchor", idx.chain.nodes["apex"]!!.kind)
    }

    // ── 判据④ 歧义：一条描述属两区 ⇒ 列候选+区分办法（不硬选）──
    @Test
    fun `判据4_歧义不硬选_给候选与区分`() {
        val r = idx.resolve("兜着蛋的袋子里那两颗")
        assertEquals(setOf("scrotum", "testis"), r.candidates.toSet())
        assertTrue(r.ambiguous)
        assertTrue(r.disambiguation!!.contains("scrotum"))
        assertTrue(r.disambiguation!!.contains("testis"))
    }

    @Test
    fun `判据4_消歧表每条带source_asOf`() {
        idx.disambiguations.forEach {
            assertTrue("消歧 ${it.zones} source/asOf 不齐", it.source.isNotBlank() && it.asOf.isNotBlank())
        }
    }

    // ── 判据⑥ 别名分层：民间别名不进规范输出 ──
    @Test
    fun `判据6_马眼只作alias_规范输出用meatus`() {
        val meatus = idx.zones.first { it.zoneId == "meatus" }
        assertTrue("马眼须在民间别名", "马眼" in meatus.aliases.folk)
        assertEquals("meatus", idx.canonicalOf("马眼"))
        assertEquals("meatus", idx.canonicalOf("撒尿的那个眼"))
        val out = idx.canonicalOutput(meatus)
        assertTrue("规范输出应是 meatus（龟头...实为尿道口）", out.startsWith("meatus"))
        assertFalse("规范输出不得含马眼", "马眼" in out)
    }

    @Test
    fun `判据6_所有民间别名都不出现在任何规范输出`() {
        val folk = idx.zones.flatMap { it.aliases.folk }
        val outs = idx.zones.map { idx.canonicalOutput(it) }
        folk.forEach { f -> assertFalse("民间别名「$f」泄漏进规范输出", outs.any { f in it }) }
    }

    @Test
    fun `判据6_canonicalOf_未知词回落null`() {
        assertNull(idx.canonicalOf("不存在的说法"))
    }

    // ── fail-closed 与确定性 ──
    @Test
    fun `装配闸_链节点悬空拒绝装配`() {
        val json = File(root(), DeicticIndex.REL_PATH).readText()
            .replace("\"zone_id\": \"meatus\", \"zh\": \"尿道口\"", "\"zone_id\": \"unknown\", \"zh\": \"尿道口\"")
        assertFalse("链节点指向未知分区仍装配就是漂移", runCatching { DeicticIndex.parse(json) }.isSuccess)
    }

    @Test
    fun `确定性_同输入两次解析结果一致`() {
        val json = File(root(), DeicticIndex.REL_PATH).readText()
        val a = DeicticIndex.parse(json)
        val b = DeicticIndex.parse(json)
        assertEquals(a.zones, b.zones)
        assertEquals(a.chain, b.chain)
        assertEquals(idx.resolve("龟头后面那道沟"), idx.resolve("龟头后面那道沟"))
    }
}
