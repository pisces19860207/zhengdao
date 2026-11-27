// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十九批 · 源码门禁：只读本单 4 个生产源码，钉住六条边界与一处结构锁。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 六条边界：
// ① 零 IO（不读影像、不读文件、不碰内容提供者）；
// ② 无长度单位字面（不反推尺寸，与 805 同口径）；
// ③ 无判定词（805 的判定表 + 本单的发育结论表，都不许在源码里成字）；
// ④ 不引用邻单类型（Size*／Torso*／Shape*／BodyPart* 的公开类型一个都不碰）；
// ⑤ 零装配（不进闸、不落库）；
// ⑥ 只给档（源码里没有「岁」、没有年龄阈值常量）——这条是本单「五维只给档」的静态证明。
//
// 结构锁：purposeCheck 的函数体必须同时认 minor 与三要素四个字段；少认一个，硬红就漏一边。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PubertySourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/bodyparts/"
    private val files = listOf(
        "PubertyCatalog.kt", "PubertyGuard.kt", "PubertyJudge.kt", "PubertySheet.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    private val lengthTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸",
    )

    private val diagnosisTokens = listOf(
        "诊" + "断", "异" + "常", "需就" + "医", "病" + "理", "确" + "诊", "治" + "疗",
        "性" + "早熟", "性" + "晚熟", "发育" + "迟", "骨" + "龄",
    )

    private val foreignTokens = listOf(
        "SizeGrades", "SizeProfile", "SizeContext", "SizeGate", "SizeRules",
        "TorsoGrades", "TorsoCatalog", "TorsoShape", "TorsoChecks",
        "ShapeJudge", "ShapeCatalog", "ShapeSheet", "ShapeGuard",
        "BodyPartCatalog", "BodyPartSheet", "BodyPartGrades",
        "AnatomyZone", "HumanBodySpec",
    )

    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "persistContract", "StateSummary",
    )

    /** 只给档：源码里不许有年龄数字与年龄门槛。 */
    private val ageTokens = listOf(
        "岁", "AGE_MIN", "AGE_MAX", "AGE_LIMIT", "ageMin", "ageMax", "ageLimit", "age <", "age >=",
    )

    @Test
    fun `边界一_零IO`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_源码内无长度单位字面`() {
        val text = code()
        lengthTokens.forEach { tok -> assertFalse("源码不得写长度单位 $tok", hasUnit(text, tok)) }
    }

    @Test
    fun `边界三_源码内无判定词`() {
        val text = code()
        diagnosisTokens.forEach { tok -> assertFalse("源码不得出现判定词 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界四_不引用邻单类型`() {
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界五_零装配不落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界六_五维只给档_源码无年龄门槛`() {
        val text = code()
        ageTokens.forEach { tok -> assertFalse("源码不得出现年龄门槛 $tok", text.contains(tok)) }
        println("SOURCE-READING age-gate tokens absent: " + ageTokens.size)
    }

    @Test
    fun `结构锁_purposeCheck认minor且三要素只在一处枚举`() {
        val src = code()
        val judge = purposeBlock(src)
        assertNotNull("必须找得到 purposeCheck 函数体", judge)
        assertTrue("purposeCheck 必须认 minor", judge!!.contains("minor"))
        assertTrue("purposeCheck 必须经 elements 消费三要素", judge.contains("elements"))
        assertFalse("判定不该看档位", judge.contains("PubertyTier"))

        val elements = elementsBlock(src)
        assertNotNull("必须找得到 elements 取值块", elements)
        val fields = listOf("sexualScene", "genitalFocus", "sexualizedGaze")
        fields.forEach { field ->
            assertTrue("elements 必须认 $field", elements!!.contains(field))
        }
        println("SOURCE-READING purposeCheck minor=1 elements=1 fields=" + fields.size + "/3")
    }

    @Test
    fun `计数锁_四张闭集与结论三值`() {
        assertEquals("五维", 5, PubertyDim.entries.size)
        assertEquals(PubertyDim.COUNT, PubertyDim.entries.size)
        assertEquals("四档", 4, PubertyTier.entries.size)
        assertEquals(PubertyTier.COUNT, PubertyTier.entries.size)
        assertEquals("里程碑", 5, PubertyMilestone.entries.size)
        assertEquals(PubertyMilestone.COUNT, PubertyMilestone.entries.size)
        assertEquals("三要素", 3, SexualElement.entries.size)
        assertEquals(SexualElement.COUNT, SexualElement.entries.size)
        assertEquals("目的判定两值", 2, PurposeVerdict.entries.size)
        assertEquals(PurposeVerdict.COUNT, PurposeVerdict.entries.size)
        assertEquals("判定三值沿用 805", 3, PubertyFinding.COUNT_VERDICTS)
        assertEquals("顺序表与枚举一一对应", PubertyMilestone.COUNT, MilestoneOrder.SEQUENCE.size)
        println("SOURCE-READING counts 5/4/5/3/2/3")
    }

    @Test
    fun `闸自检_注入必被咬住`() {
        val io = stripComments("val bad = File(\"x\").readText()")
        assertTrue("IO 注入必须被咬住", ioTokens.any { io.contains(it) })
        val len = stripComments("val bad = \"约7" + "c" + "m\"")
        assertTrue("长度注入必须被咬住", lengthTokens.any { hasUnit(len, it) })
        val diag = stripComments("val bad = \"" + "性" + "早熟\"")
        assertTrue("诊断结论注入必须被咬住", diagnosisTokens.any { diag.contains(it) })
        val foreign = stripComments("val bad = SizeGrades.HEIGHT")
        assertTrue("邻单注入必须被咬住", foreignTokens.any { foreign.contains(it) })
        val wiring = stripComments("val bad = RunEngine.run()")
        assertTrue("装配注入必须被咬住", wiringTokens.any { wiring.contains(it) })
        val age = stripComments("val AGE_MIN = 18")
        assertTrue("年龄门槛注入必须被咬住", ageTokens.any { age.contains(it) })
        val lightPurpose = stripComments(
            "fun purposeCheck(frame: PurposeFrame) = PurposeCheck(PurposeVerdict.ALLOW, emptyList(), \"x\")\n" +
                "fun textFindings(text: String) = emptyList<PubertyFinding>()\n",
        )
        assertFalse("不认 minor 的 purposeCheck 必须被咬住", purposeBlock(lightPurpose)?.contains("minor") == true)
        val lightElements = stripComments(
            "val elements: List<SexualElement>\n" +
                "    get() {\n        val out = ArrayList<SexualElement>()\n" +
                "        if (sexualScene) out += SexualElement.SCENE\n        return out\n    }\n" +
                "    val sexual: Boolean get() = elements.isNotEmpty()\n",
        )
        val eb = elementsBlock(lightElements)
        val threeFields = listOf("sexualScene", "genitalFocus", "sexualizedGaze")
        assertFalse("少一个要素必须被咬住", eb != null && threeFields.all { eb.contains(it) })
        println("SOURCE-READING negative injections 8/8 caught")
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments(
            "// 这里提到 readText、" + "c" + "m" + "、" + "岁" + " 只是说明\nval ok = 1",
        )
        assertFalse("注释里的 IO 不该算", ioTokens.any { onlyInComment.contains(it) })
        assertFalse("注释里的长度单位不该算", lengthTokens.any { hasUnit(onlyInComment, it) })
        assertFalse("注释里的年龄门槛不该算", ageTokens.any { onlyInComment.contains(it) })
    }

    /** purposeCheck 的函数体：从它到 textFindings 之间。取不到返回 null。 */
    private fun purposeBlock(src: String): String? {
        val from = src.indexOf("fun purposeCheck(")
        if (from < 0) return null
        val to = src.indexOf("fun textFindings(", from)
        if (to < 0) return null
        return src.substring(from, to)
    }

    /** 三要素唯一枚举处：elements 取值块（到派生属性 sexual 为止）。取不到返回 null。 */
    private fun elementsBlock(src: String): String? {
        val from = src.indexOf("val elements: List<SexualElement>")
        if (from < 0) return null
        val to = src.indexOf("val sexual:", from)
        if (to < 0) return null
        return src.substring(from, to)
    }

    /** 单位是否作为独立词出现：缩写后面不许紧跟 ASCII 字母（Symmetry 的 mm 不算）。 */
    private fun hasUnit(text: String, unit: String): Boolean {
        var i = text.indexOf(unit, ignoreCase = true)
        while (i >= 0) {
            val after = i + unit.length
            val asciiLetter = after < text.length &&
                (text[after] in 'a'..'z' || text[after] in 'A'..'Z')
            if (!asciiLetter) return true
            i = text.indexOf(unit, i + 1, ignoreCase = true)
        }
        return false
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
                else -> {
                    out.append(src[i]); i++
                }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') {
            out.append(' '); i++
        }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") {
                depth++; out.append("  "); i += 2
            } else if (two == "*/") {
                depth--; out.append("  "); i += 2
            } else {
                if (src[i] != '\n') out.append(' ') else out.append('\n')
                i++
            }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) {
                out.append(src[i + 1]); i += 2; continue
            }
            if (src[i] == '"') {
                i++; break
            }
            i++
        }
        return i
    }
}
