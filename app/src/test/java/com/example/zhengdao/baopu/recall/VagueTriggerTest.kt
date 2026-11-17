// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 540 触发判据（纯函数直测）。
 *
 * 夹具铁律（派工单【判据】「模糊指代夹具 3 条命中」）：三条模糊指代夹具必须全部
 * 触发且判为 [VagueSignal.Kind.VAGUE_REF]；显式「帮我找一下」触发 EXPLICIT_FIND；
 * 负例（闲聊里顺口一个「那个」）必须不触发——触发太多就是打扰。
 */
class VagueTriggerTest {

    // ── 夹具：模糊指代 3 条，全部命中 ─────────────────────────────────────

    @Test
    fun `夹具1_上次说的那个部署是什么来着_触发`() {
        val s = VagueTrigger.signalOf("上次说的那个部署是什么来着？")
        assertNotNull("模糊指代夹具①必须命中", s)
        assertEquals(VagueSignal.Kind.VAGUE_REF, s!!.kind)
        assertEquals("夹具①的实词就是「部署」", listOf("部署"), s.keywords)
    }

    @Test
    fun `夹具2_那个怎么弄来着_触发且线索交给上下文`() {
        val s = VagueTrigger.signalOf("那个怎么弄来着")
        assertNotNull("模糊指代夹具②必须命中", s)
        assertEquals(VagueSignal.Kind.VAGUE_REF, s!!.kind)
        assertTrue("剥完指代与求证词没有实词 ⇒ 本句零线索", s.keywords.isEmpty())
    }

    @Test
    fun `夹具3_你还记得吗就是之前说的那个_触发`() {
        val s = VagueTrigger.signalOf("你还记得吗，就是之前说的那个")
        assertNotNull("模糊指代夹具③必须命中", s)
        assertEquals(VagueSignal.Kind.VAGUE_REF, s!!.kind)
    }

    // ── 显式要求 ──────────────────────────────────────────────────────────

    @Test
    fun `显式_帮我找一下_触发且带实词`() {
        val s = VagueTrigger.signalOf("帮我找一下之前聊的端口配置")
        assertNotNull("显式要求必须触发", s)
        assertEquals(VagueSignal.Kind.EXPLICIT_FIND, s!!.kind)
        assertTrue("端口/配置两词必须在线索里", s.keywords.containsAll(listOf("端口", "配置")))
    }

    // ── 负例：闲聊不许触发 ────────────────────────────────────────────────

    @Test
    fun `负例_那个方案不错_不触发`() {
        assertNull("有指代但没求证语气、还有实词 ⇒ 当聊天", VagueTrigger.signalOf("那个方案不错，就这样吧。"))
    }

    @Test
    fun `负例_普通问句_不触发`() {
        assertNull(VagueTrigger.signalOf("今天天气怎么样"))
        assertNull(VagueTrigger.signalOf(""))
        assertNull(VagueTrigger.signalOf("   "))
    }

    @Test
    fun `负例_光杆那个带问号_触发（除了指代什么都没说）`() {
        assertNotNull("「那个？」就是在找东西", VagueTrigger.signalOf("那个？"))
    }

    // ── 线索提取：确定性 ──────────────────────────────────────────────────

    @Test
    fun `线索_输入同序输出同序_拉丁词在前`() {
        val a = VagueTrigger.contentKeywords("先聊 sqlite 然后部署 Postgres")
        val b = VagueTrigger.contentKeywords("先聊 sqlite 然后部署 Postgres")
        assertEquals(a, b)
        assertEquals("拉丁词排在二元组前", "sqlite", a.first())
        assertTrue(a.contains("postgres"))
        assertTrue(a.contains("部署"))
    }

    @Test
    fun `线索_截断到上限_超长句不把检索组吃光`() {
        val long = "这里有一段很长很长的中文内容需要切成二元组用来测试截断行为是否正确稳定可靠"
        val kws = VagueTrigger.contentKeywords(long, cap = 5)
        assertEquals(5, kws.size)
    }
}
