// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HygieneSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/hygiene/"
    private val files = listOf(
        "HygieneTypes.kt",
        "HygieneTables.kt",
        "HygieneGuard.kt",
        "HygieneRenderer.kt",
    ).map { dir + it }

    private val foreignTokens = listOf(
        "state.occupation", "OccDrive", "OccHygiene", "state.odor", "OdorKind", "FloraGrade",
        "780", "827", "804", "831",
    )
    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "android.content.Context", "BaopuDb", "persist", "Assembler", "Wiring",
    )
    private val impureTokens = listOf(
        "Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "writeText", "getenv", "System.",
    )
    private val pointTokens = listOf(
        "%", "％", "c" + "m", "m" + "L", "Double", "Float", "Math.", "kotlin.math", " * ", " / ",
    )
    private val medicalTokens = listOf(
        "诊断", "治疗", "用药", "感染", "发炎", "炎症", "病变", "疾病", "就医", "健康建议", "建议", "应该", "必须洗", "病名",
        "肿瘤", "癌症", "症状", "患病", "吃药", "抗生素", "医院", "真菌", "细菌", "瘙痒", "红肿", "溃烂", "包皮炎",
        "龟头炎", "性病", "传染", "药膏", "洗液", "消毒", "注意卫生", "保持卫生", "多洗", "勤洗", "包茎", "湿疣", "艾滋病",
        "梅毒", "疱疹", "疣", "癣", "湿疹", "皮炎",
    )
    private val occupationTokens = listOf(
        "军警消", "军人", "警察", "消防", "医生", "护士", "工人", "教师", "农民", "程序员", "厨师", "司机",
        "官员", "演员", "运动员", "工程师", "老板", "员工", "学生", "商人", "服务员", "销售", "保安", "保洁",
        "快递", "外卖", "律师", "法官", "检察官", "民警", "士兵", "军官", "经理", "主任", "客服", "导购", "技师",
        "渔民", "牧民", "矿工", "建筑工", "环卫工", "护工", "月嫂", "幼师", "设计师", "研究员", "科学家", "企业家",
        "个体户", "店主", "摊主", "主播", "助理", "中学生", "小学生", "初中生", "高中生", "大学生", "研究生", "留学生", "实习生",
    )
    private val evaluationTokens = listOf(
        "脏", "不洁", "不干净", "恶心", "反胃", "邋遢", "嫌弃", "作呕", "腌臜", "难闻", "熏人", "刺鼻", "骚", "臊",
    )
    private val requiredNames = listOf(
        "val smegmaTier", "val cleaningFreq", "val retractability", "val color",
        "val hygieneHabit", "val washCondition", "val bathFreq", "val localWash", "val hygieneDriver",
        "val foot", "val genitals", "val smellIntensity", "val smellType", "val flora", "val pathologicalOdor",
    )

    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    private fun domainCode(): String = files
        .filterNot { it.endsWith("HygieneGuard.kt") }
        .joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    @Test
    fun `边界_四件生产文件齐且无公开面`() {
        val text = code()
        requiredNames.forEach { name -> assertTrue("缺 $name", text.contains(name)) }
        assertFalse(text.contains("public "))
        assertTrue(text.contains("internal data class Absent"))
        assertTrue(text.contains("internal sealed interface SmellSlot"))
    }

    @Test
    fun `边界_只复用804831类型且不重复声明`() {
        val text = code()
        val imports = Regex("import com\\.example\\.zhengdao\\.baopu\\.state\\.prepuce\\.[A-Za-z]+")
            .findAll(text)
            .map { it.value }
            .toSet()
        assertEquals(
            setOf(
                "import com.example.zhengdao.baopu.state.prepuce.AreaLevel",
                "import com.example.zhengdao.baopu.state.prepuce.Circumcision",
                "import com.example.zhengdao.baopu.state.prepuce.RetractState",
            ),
            imports,
        )
        assertFalse(text.contains("enum class RetractState"))
        assertFalse(text.contains("enum class AreaLevel"))
        assertFalse(text.contains("enum class Circumcision"))
    }

    @Test
    fun `边界_不依赖职业气味邻包或旧批次实现`() {
        val text = code()
        foreignTokens.forEach { token -> assertFalse("不得依赖 $token", text.contains(token)) }
    }

    @Test
    fun `边界_零装配零落库`() {
        val text = code()
        wiringTokens.forEach { token -> assertFalse("不得装配 $token", text.contains(token)) }
    }

    @Test
    fun `边界_无IO无随机无时钟`() {
        val text = code()
        impureTokens.forEach { token -> assertFalse("纯函数层不得含 $token", text.contains(token)) }
    }

    @Test
    fun `判据五_无点值单位与连续数学`() {
        val text = code()
        pointTokens.forEach { token -> assertFalse("不得含 $token", text.contains(token)) }
        assertTrue(text.contains("private val BASE_ROWS = listOf"))
        assertTrue(text.contains("private val SWEAT_ROWS = listOf"))
        assertTrue(text.contains("private val CELLS"))
        assertTrue(text.contains("CELLS.getValue"))
    }

    @Test
    fun `判据一二_业务源码无职业倾向评价医学判断或建议`() {
        val text = domainCode()
        occupationTokens.forEach { token -> assertFalse("业务源码不得含职业 $token", text.contains(token)) }
        evaluationTokens.forEach { token -> assertFalse("业务源码不得含评价 $token", text.contains(token)) }
        medicalTokens.forEach { token -> assertFalse("业务源码不得含 $token", text.contains(token)) }
    }

    @Test
    fun `判据一二三四_闸门确实含五条硬口径`() {
        val text = code()
        listOf(
            "任何职业都不构成卫生倾向",
            "卫生状态不是脏或恶心评价",
            "没洗澡不得推出没洗脚或没洗下体",
            "他人代做只记中性动作",
            "包皮垢未到明显档",
        ).forEach { mark -> assertTrue("闸门缺 $mark", text.contains(mark)) }
    }

    @Test
    fun `闸自检_注入职业绑定必被码册咬住`() {
        val poisoned = stripComments("val s = \"医生爱干净\"")
        assertTrue(occupationTokens.any { poisoned.contains(it) })
        assertTrue(evaluationTokens.any { poisoned.contains(it) } || "爱干净" in poisoned)
    }

    @Test
    fun `闸自检_注入连续公式与IO必被咬住`() {
        val formula = stripComments("val x = a * b / c")
        assertTrue(pointTokens.any { formula.contains(it) })
        val io = stripComments("java.io.File(\"x\")")
        assertTrue(impureTokens.any { io.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后说明文字不算业务命中`() {
        val only = stripComments("// 医生、脏、Double、java.io 只是说明\nval ok = 1")
        occupationTokens.forEach { assertFalse(only.contains(it)) }
        evaluationTokens.forEach { assertFalse(only.contains(it)) }
        pointTokens.forEach { assertFalse(only.contains(it)) }
        impureTokens.forEach { assertFalse(only.contains(it)) }
    }

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
            else { if (src[i] != '\n') out.append(' ') else out.append('\n'); i++ }
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
