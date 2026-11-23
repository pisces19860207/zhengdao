// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十七批 · 源码门禁：只读本单 4 个生产源码，钉住「引用既有档、零装配、不挤占
// 722 三行与敏感层、纯判定无 IO」。每道闸配负例注入自检——不是「文件含字面量」恒真。
package com.example.zhengdao.baopu.state.casting

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/casting/"
    private val files = listOf("CastingRefs.kt", "CastingModel.kt", "CastingChecks.kt", "CastingSheet.kt")
        .map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    /** 装配 / 引擎入口：一字不许出现。 */
    private val assemblyTokens = listOf("RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "attach(", "Context")

    /** 闸 / 落库：一字不许出现（本单不与 747 抢账、不做判分）。 */
    private val gateTokens = listOf("RiskMatrix", "insertRunLog", "contractLine", "persistContract", "BaopuSql")

    /** 722 三行格式与 738 注入位：本单只产出摘要文本，不写这些格式、不碰装配位。 */
    private val occupancyTokens = listOf("不变量", "词档", "asContextNotes", "InvariantSeal", "readingCodex")

    /** 无 IO、无随机、无时钟。 */
    private val impureTokens = listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "getenv")

    /** 摘要字数上限必须钉死为 120。 */
    @Test
    fun `判据二_摘要上限钉死120`() {
        assertEquals(120, SUMMARY_LIMIT)
        assertTrue(code().contains("SUMMARY_LIMIT = 120"))
    }

    /** 判据①：档名必须引用既有档——源码里必须出现这些既有档入口；不许出现硬编码档名。 */
    @Test
    fun `判据一_档名引用既有档入口`() {
        val text = code()
        listOf("AnchorFields.spec", "values().map", "BODY_PART", "PREPUCE", "PHYSIO").forEach { tok ->
            assertTrue("必须引用既有档入口 $tok", text.contains(tok))
        }
    }

    /** 判据①：不许把既有档名硬编码成字符串字面（引用=动态取，复制=字面写死）。 */
    @Test
    fun `判据一_不许硬编码既有档名字面`() {
        val text = code()
        // 这些既有档名只允许从 values()/spec() 取，不许作为字符串常量出现在生产源码
        listOf("\"鹅蛋\"", "\"饱满\"", "\"湿透\"", "\"未割半包\"", "\"深沟\"").forEach { lit ->
            assertFalse("不许硬编码档名 $lit", text.contains(lit))
        }
    }

    /** 边界：零装配。 */
    @Test
    fun `边界_零装配`() {
        val text = code()
        assemblyTokens.forEach { tok -> assertFalse("不得装配 $tok", text.contains(tok)) }
    }

    /** 边界：零闸零落库。 */
    @Test
    fun `边界_零闸零落库`() {
        val text = code()
        gateTokens.forEach { tok -> assertFalse("不得进闸落库 $tok", text.contains(tok)) }
    }

    /** 判据②：注入不挤占 722 三行与敏感层。 */
    @Test
    fun `判据二_不挤占722三行与敏感层`() {
        val text = code()
        occupancyTokens.forEach { tok -> assertFalse("不得挤占 $tok", text.contains(tok)) }
        // 下体组必须被摘要排除：CastingSheet 里出现 safeGroups 且不含 GENITAL
        assertTrue("摘要须有安全组白名单", text.contains("safeGroups"))
    }

    /** 判据④：纯判定无 IO。 */
    @Test
    fun `判据四_无IO无随机无时钟`() {
        val text = code()
        impureTokens.forEach { tok -> assertFalse("纯判定层不得出现 $tok", text.contains(tok)) }
    }

    /** 摘要实测：下体字段不会进摘要，脸型会进。 */
    @Test
    fun `判据二_摘要排除下体含脸型`() {
        val card = CastingCard(
            roleId = "R", profileId = "P", isMain = false,
            values = mapOf(
                "face.shape" to listOf("鹅蛋"),
                "body.frame" to listOf("中"),
                "g.prepuce" to listOf("已割"),
            ),
        )
        val sheet = CastingSheet.of(card)
        assertTrue("摘要含脸型", sheet.summary.contains("脸型"))
        assertFalse("摘要不得含下体档", sheet.summary.contains("已割"))
        assertTrue("档位向量含下体字段", sheet.vector.any { it.fieldId == "g.prepuce" })
        assertTrue("摘要 ≤120 字", sheet.summary.length <= 120)
    }

    /** 摘要超长截断且不切半个词。 */
    @Test
    fun `判据二_摘要超120字按段截断`() {
        val v = LinkedHashMap<String, List<String>>()
        CastingRefs.CARD_FIELDS.filter { it.group != CardGroup.GENITAL && it.words().isNotEmpty() }
            .forEach { v[it.id] = listOf(it.words().first()) }
        val card = CastingCard("R", "P", false, v)
        val summary = CastingSheet.of(card).summary
        assertTrue("摘要 ≤120 字：$summary", summary.length <= 120)
        assertTrue("摘要以句号收尾", summary.endsWith("。"))
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────────

    @Test
    fun `闸自检_注入装配词必被咬住`() {
        val poisoned = stripComments("val x = RunEngine.attach(ctx)")
        assertTrue(assemblyTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入722词必被咬住`() {
        assertTrue(occupancyTokens.any { stripComments("val x = \"不变量\"").contains(it) })
    }

    @Test
    fun `闸自检_注入硬编码档名必被咬住`() {
        assertTrue(listOf("\"鹅蛋\"").any { stripComments("val x = \"鹅蛋\"").contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val only = stripComments("// 这里只提到 RunEngine 与 鹅蛋 作说明\nval ok = 1")
        assertFalse(assemblyTokens.any { only.contains(it) })
    }

    /** 行/块注释整段换空格（保留换行）；字符串原样保留。Kotlin 块注释可嵌套。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '"' -> i = copyString(src, i, out)
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") { depth++; out.append("  "); i += 2 }
            else if (two == "*/") { depth--; out.append("  "); i += 2 }
            else { if (src[i] != '\n') out.append(' '); else out.append('\n'); i++ }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
            if (src[i] == '"') { i++; break }
            i++
        }
        return i
    }
}
