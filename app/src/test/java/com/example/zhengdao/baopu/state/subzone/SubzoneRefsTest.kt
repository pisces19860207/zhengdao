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
import java.io.File

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

    // ── 第八百八十八批：code 冻结与五列下游账 ────────────────────────────

    @Test
    fun code冻结_枚举集合必须与Codes逐字相同() {
        assertEquals("冻结表只能有十四个 code", 14, SubzoneRefs.Codes.size)
        assertEquals(
            "改任一 code 字面值都必须红；新增子区先看到红，再同单更新 Codes 与账本",
            SubzoneRefs.Codes,
            SubzoneId.entries.map { it.code }.toSet(),
        )
    }

    @Test
    fun 五列账_字段齐全且状态与形式只用规定值() {
        assertEquals(
            setOf("已引用", "计划中", "已废弃"),
            ReferenceStatus.entries.map { it.label }.toSet(),
        )
        assertEquals(
            setOf("直接(.code)", "间接(名字对应)", "全表(entries.map)"),
            ReferenceForm.entries.map { it.label }.toSet(),
        )
        for ((index, ref) in SubzoneRefs.downstreamRefs.withIndex()) {
            assertTrue("第 $index 行缺引用方", ref.consumer.isNotBlank())
            assertTrue("第 $index 行缺状态", ref.status.label.isNotBlank())
            assertTrue("第 $index 行缺引用形式", ref.form.label.isNotBlank())
            assertTrue("第 $index 行缺 code/范围", ref.codes.isNotEmpty() && ref.scope.isNotBlank())
            assertTrue("第 $index 行缺证据", ref.evidence.isNotBlank())
            assertTrue(
                "第 $index 行引用了冻结表外的 code：${ref.codes - SubzoneRefs.Codes}",
                ref.codes.all { it in SubzoneRefs.Codes },
            )
        }
    }

    @Test
    fun 五列账_727与869875886各恰好一行() {
        assertEquals(
            listOf("727", "869", "875", "886"),
            SubzoneRefs.downstreamRefs.map { it.consumer },
        )
        assertEquals(4, SubzoneRefs.downstreamRefs.map { it.consumer }.toSet().size)
    }

    @Test
    fun 已引用727_七个code逐个对应枚举名且证据行可复现() {
        val ref = SubzoneRefs.downstreamRefs.single { it.consumer == "727" }
        assertEquals(ReferenceStatus.REFERENCED, ref.status)
        assertEquals(ReferenceForm.INDIRECT, ref.form)
        assertEquals(
            setOf("apex", "frenulum", "shaftProximal", "shaftMid", "shaftDistal", "radix", "meatusRim"),
            ref.codes,
        )
        val mappings = listOf(
            "apex" to "ImpactSite.GLANS",
            "frenulum" to "ImpactSite.FRENULUM",
            "meatusRim" to "ImpactSite.MEATUS",
            "shaftProximal" to "ImpactSite.SHAFT",
            "shaftMid" to "ImpactSite.SHAFT",
            "shaftDistal" to "ImpactSite.SHAFT",
            "radix" to "ImpactSite.ROOT",
        )
        for ((code, site) in mappings) {
            assertTrue("$code 未逐 code 指向 $site", ref.evidence.contains("$code → $site"))
        }
        val evidenceLines = ref.evidence.lines().filter { it.contains(".kt:") }
        assertTrue("727 七个 code 与两条矩阵证据都要给文件:行", evidenceLines.size >= 9)
        for (line in evidenceLines) {
            val match = Regex("""(app/src/main/java/[^\s`]+\.kt):([1-9]\d*) `([^`]+)`$""").find(line)
            assertTrue("证据行末必须同时给文件:行与那一行原文：$line", match != null)
            val path = match!!.groupValues[1]
            val lineNo = match.groupValues[2].toInt()
            val source = sourceLine(path, lineNo)
            assertEquals("$path:$lineNo 必须与同一行所贴原文一致：$line", source, match.groupValues[3])
        }
        assertTrue("力矩阵取档缝也要有行号", ref.evidence.contains("ImpactMatrix.kt:91"))
    }

    @Test
    fun 计划中869875886_必须全表且写清各单接入时点() {
        val timings = mapOf(
            "869" to "869 合入后：四组接入",
            "875" to "875 合入后：外观隆起接入",
            "886" to "886 合入后：占位回填接入",
        )
        for ((consumer, timing) in timings) {
            val ref = SubzoneRefs.downstreamRefs.single { it.consumer == consumer }
            assertEquals("$consumer 尚未接入，不许冒充已引用", ReferenceStatus.PLANNED, ref.status)
            assertEquals("$consumer 接入时读全表", ReferenceForm.FULL_TABLE, ref.form)
            assertEquals("$consumer 的全表范围必须跟随 Codes", SubzoneRefs.Codes, ref.codes)
            assertEquals("全表（${SubzoneRefs.Codes.size}）", ref.scope)
            assertTrue("$consumer 必须写接入时点", ref.evidence.contains("接入时点=$timing"))
        }
    }

    @Test
    fun 账本自洽_计划缺时点或已引用缺行号都必须拒绝() {
        val cases: List<Triple<String, String, () -> Unit>> = listOf(
            Triple("缺接入时点", "接入时点") {
                DownstreamReference(
                    "869", ReferenceStatus.PLANNED, ReferenceForm.FULL_TABLE, SubzoneRefs.Codes, "尚未接入",
                )
            },
            Triple("空接入时点", "接入时点") {
                DownstreamReference(
                    "869", ReferenceStatus.PLANNED, ReferenceForm.FULL_TABLE, SubzoneRefs.Codes, "接入时点= ",
                )
            },
            Triple("已引用缺行号", "文件:行") {
                DownstreamReference("727", ReferenceStatus.REFERENCED, ReferenceForm.INDIRECT, setOf("apex"), "只有单号")
            },
            Triple("已引用零行号", "文件:行") {
                DownstreamReference("727", ReferenceStatus.REFERENCED, ReferenceForm.INDIRECT, setOf("apex"), "fake.kt:0")
            },
            Triple("缺废弃原因", "废弃原因") {
                DownstreamReference("old", ReferenceStatus.DEPRECATED, ReferenceForm.DIRECT, setOf("apex"), "不用了")
            },
            Triple("空废弃原因", "废弃原因") {
                DownstreamReference("old", ReferenceStatus.DEPRECATED, ReferenceForm.DIRECT, setOf("apex"), "废弃原因= ")
            },
        )
        val accepted = mutableListOf<String>()
        for ((name, message, build) in cases) {
            try {
                build()
                accepted += name
            } catch (error: IllegalArgumentException) {
                assertTrue(
                    "$name 拒绝理由须点明 $message：${error.message}",
                    error.message.orEmpty().contains(message),
                )
            }
        }
        assertTrue("假账构造必须被拒绝：${accepted.joinToString("、")}", accepted.isEmpty())
    }

    private fun sourceLine(path: String, line: Int): String {
        val file = listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("找不到证据文件：$path")
        return file.readLines()[line - 1].trim()
    }

    @Test
    fun id冻结_code与label一一对应_不许重名() {
        assertEquals(SubzoneId.entries.size, SubzoneId.entries.map { it.code }.toSet().size)
        assertEquals("label 也不许重名（入文用词撞车＝两处指同一块）", SubzoneId.entries.size, SubzoneId.entries.map { it.label }.toSet().size)
    }
}
