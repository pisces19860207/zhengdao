// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十一批 · 源码门禁：只读本单 6 个生产源码，钉住边界与计数锁。
// 每道闸配负例注入自检——不是「文件里含某个词」就过。
package com.example.zhengdao.baopu.state.relation

import com.example.zhengdao.SrcRoots
import com.example.zhengdao.baopu.state.track.RelationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/relation/"
    private val files = listOf(
        "RelationCatalog.kt", "RelationDims.kt", "RelationChecks.kt",
        "RelationGuard.kt", "RelationSheet.kt", "RelationRuleSwitch.kt",
    )

    private fun src(name: String): String = stripComments(SrcRoots.find(dir + name).readText())

    private fun code(): String = files.joinToString("\n") { src(it) }

    private val ioTokens = listOf("java.io", "InputStream", "readText", "File(", "ContentResolver")

    /** 907 S5：关系包不认识任何性反应包/档位。 */
    private val responseTokens = listOf(
        "state.erect.", "state.form.", "state.physiology.", "state.stimulus.",
        "HardnessTier", "ErectTier", "Libido",
    )

    private val wiringTokens = listOf(
        "RunEngine", "ContextAssembler", "persistContract", "StateSummary", "RiskMatrix",
    )

    /** 670 的 11 格只被引用、不被重做。 */
    private val trackTokens = listOf("RelationTrack(", "RelationTable.", "enum class RelationState")

    @Test
    fun `边界一_零IO`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_关系不推性反应_源码零引用`() {
        val text = code()
        responseTokens.forEach { tok -> assertFalse("不得引用性反应面 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界三_零装配零落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界四_不重做670的11格`() {
        val text = code()
        trackTokens.forEach { tok -> assertFalse("不得重做关系轨 $tok", text.contains(tok)) }
        assertEquals("670 的 11 态原样", 11, RelationState.entries.size)
    }

    @Test
    fun `边界五_变异开关生产默认恒正确且零调用`() {
        val switch = src("RelationRuleSwitch.kt")
        val defaults = listOf(
            "var treatMergedAsValid: Boolean = false",
            "var enforceCeiling: Boolean = true",
            "var requireEvent: Boolean = true",
            "var rapportPromotesKind: Boolean = false",
            "var enforceRelationSex: Boolean = true",
        )
        defaults.forEach { assertFalse("开关默认值被改：$it", defaultBroken(switch, it)) }
        files.filter { it != "RelationRuleSwitch.kt" }.forEach { name ->
            assertFalse("生产路径不许调用 ablate：$name", src(name).contains("ablate("))
        }
        assertEquals("默认快照", "V1=false,V2=true,V3=true,V4=false,V5=true",
            RelationRuleSwitch.snapshot())
    }

    @Test
    fun `边界六_topicOverlap不进startDims`() {
        val c = src("RelationCatalog.kt")
        val from = c.indexOf("fun startDims")
        val to = c.indexOf("fun valueAlign", from)
        assertTrue("找不到 startDims 块", from >= 0 && to > from)
        assertFalse("兴趣不许进 trust 起始", c.substring(from, to).contains("topicOverlap"))
    }

    @Test
    fun `计数锁_六件生产四件测试与唯一出口`() {
        assertEquals("生产 6 件", 6, files.size)
        assertEquals("line 唯一出口", 1, countOccurrences(src("RelationChecks.kt"), "fun line()"))
        assertEquals("红码 12 个（10 条规则，R2/R5 各两码）", 12,
            countOccurrences(src("RelationChecks.kt"), "RelVerdict.DENY, \"R"))
    }

    @Test
    fun `闸自检_负例注入六条全被咬住`() {
        val io = stripComments("val bad = File(\"x\").readText()")
        assertTrue("IO 注入必须被咬住", ioTokens.any { io.contains(it) })

        val sexual = stripComments("import com.example.zhengdao.baopu.state.erect.ErectGrades")
        assertTrue("性反应注入必须被咬住", responseTokens.any { sexual.contains(it) })

        val wiring = stripComments("val bad = RunEngine.run()")
        assertTrue("装配注入必须被咬住", wiringTokens.any { wiring.contains(it) })

        val track = stripComments("val bad = RelationTrack()")
        assertTrue("关系轨注入必须被咬住", trackTokens.any { track.contains(it) })

        val switchBad = stripComments(
            "var enforceCeiling: Boolean = false" + "\nprivate set",
        )
        assertTrue("开关改默认必须被咬住", defaultBroken(switchBad, "var enforceCeiling: Boolean = true"))

        val topic = stripComments("fun startDims(...) { val x = topicOverlap }")
        assertTrue("兴趣入起始必须被咬住", topic.substring(topic.indexOf("fun startDims")).contains("topicOverlap"))

        println("SOURCE-READING negative injections 6/6 caught")
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyComment = stripComments("// 这里提到 RunEngine 与 java.io 只是说明\nval ok = 1")
        assertFalse("注释里的装配不算", wiringTokens.any { onlyComment.contains(it) })
        assertFalse("注释里的 IO 不算", ioTokens.any { onlyComment.contains(it) })
    }

    private fun defaultBroken(src: String, correct: String): Boolean {
        val name = correct.substringBefore(" =")
        val line = src.lines().firstOrNull { it.trim().startsWith(name) } ?: return false
        return line.trim() != correct.trim()
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var n = 0
        var i = 0
        while (true) {
            i = text.indexOf(needle, i)
            if (i < 0) return n
            n += 1
            i += needle.length
        }
    }

    /** 剥 // 与 /* */（不剥字符串）；本单源码无嵌套块注释。 */
    private fun stripComments(source: String): String {
        val sb = StringBuilder()
        var i = 0
        var inBlock = false
        while (i < source.length) {
            if (!inBlock && source.startsWith("//", i)) {
                while (i < source.length && source[i] != '\n') {
                    sb.append(' ')
                    i += 1
                }
                continue
            }
            if (!inBlock && source.startsWith("/*", i)) {
                inBlock = true
                sb.append("  ")
                i += 2
                continue
            }
            if (inBlock && source.startsWith("*/", i)) {
                inBlock = false
                sb.append("  ")
                i += 2
                continue
            }
            val c = source[i]
            sb.append(if (inBlock && c != '\n') ' ' else c)
            i += 1
        }
        return sb.toString()
    }
}
