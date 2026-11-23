// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 子区层：下游引用账（判据①）与 id 冻结（「别改名」的机器门禁）。
// 只读源码，零写盘、零临时目录（水位口径）。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubzoneRefsTest {

    // ── 判据①：每个 id 都有下游引用 ──────────────────────────────────────

    @Test
    fun 判据一_十四个id个个都有下游引用() {
        val missing = SubzoneRefs.missingRefs()
        assertTrue("有 id 没有下游引用：${missing.map { it.why }}", missing.isEmpty())
        for (id in SubzoneId.entries) {
            assertTrue("${id.label}（${id.code}）引用为空", SubzoneRefs.of(id).isNotEmpty())
        }
    }

    @Test
    fun 判据一_空账即十四个全红并点名() {
        val findings = SubzoneRefs.missingRefs(refs = emptyMap())
        assertEquals("空账必须逐条点名", SubzoneId.entries.size, findings.size)
        assertTrue("一律红", findings.all { it.verdict == Verdict.DENY })
        assertTrue("理由须带 code（下游按 code 拼字符串）", findings.all { it.why.contains(it.id!!.code) })
    }

    @Test
    fun 判据一_少一条即点名那一个() {
        val cut = SubzoneId.entries.associateWith { id ->
            if (id == SubzoneId.SULCUS) emptyList<SubzoneRef>() else SubzoneRefs.of(id)
        }
        val findings = SubzoneRefs.missingRefs(refs = cut)
        assertEquals("只报缺的那一个", listOf(SubzoneId.SULCUS), findings.map { it.id })
        assertTrue(findings.single().why.contains(SubzoneId.SULCUS.label))
    }

    @Test
    fun 三个消费方都真被用到_不是挂名() {
        val coverage = SubzoneRefs.coverageOf()
        assertEquals("三个消费方都要进账", Consumer.entries.toSet(), coverage.keys)
        for ((consumer, count) in coverage) {
            assertTrue("${consumer.orderId} 一个 id 都没引用，等于挂名", count > 0)
        }
        val total = SubzoneId.entries.sumOf { SubzoneRefs.of(it).size }
        assertTrue("引用条数应多于子区数（多数 id 有两个以上的下游）", total > SubzoneId.entries.size)
    }

    @Test
    fun 引用说明写的是用途_不写很重要三个字() {
        for (id in SubzoneId.entries) {
            for (ref in SubzoneRefs.of(id)) {
                assertTrue("${id.code}／${ref.consumer.orderId}：说明太短，没写拿它干什么", ref.note.length >= 8)
                assertTrue("说明里夹了长度单位", BodyPartGuard.measureHit(ref.note) == null)
                assertTrue("说明里夹了判定词", BodyPartGuard.diagnosisHits(ref.note).isEmpty())
            }
        }
    }

    @Test
    fun 引用账一行一id_逐字可抄() {
        val lines = SubzoneRefs.line().lines()
        assertEquals(SubzoneId.entries.size, lines.size)
        assertTrue("格式须 code→单号", lines.first().matches(Regex("^[A-Za-z]+→(804|727|867)(/(804|727|867))*$")))
    }

    // ── id 冻结：只许加，不许改名 ────────────────────────────────────────

    /**
     * 这 14 个 code 是**对外契约**：804／727／867 直接拼字符串引用。这张期望表故意写在测试里，
     * 不是「第二份定义」，而是把「改名」这件事变成红门禁——改一个字就得先改这张表，
     * 改表的人会看见上面那句注释。
     */
    @Test
    fun id冻结_十四个code逐字不变() {
        assertEquals(
            listOf(
                "apex", "glansbody", "corona", "sulcus", "frenulum",
                "shaftProximal", "shaftMid", "shaftDistal", "radix",
                "meatusRim", "meatusSlit",
                "prepuceInner", "prepuceOuter", "prepuceOrifice",
            ),
            SubzoneId.entries.map { it.code },
        )
    }

    @Test
    fun id冻结_code与label一一对应_不许重名() {
        assertEquals(SubzoneId.entries.size, SubzoneId.entries.map { it.code }.toSet().size)
        assertEquals("label 也不许重名（入文用词撞车＝两处指同一块）", SubzoneId.entries.size, SubzoneId.entries.map { it.label }.toSet().size)
    }
}
