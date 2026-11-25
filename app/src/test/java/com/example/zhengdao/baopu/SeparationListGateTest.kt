// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第九百零七批 · 判定引用：只读 docs/分离清单.md（唯一表），不新增业务包/判定维度。
// 判据：七对齐全 · 两端独立夹具 · 默认联动可检出 · 优先级链照素材抄 · 素材 file:line。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeparationListGateTest {

    private fun docFile(): File = listOf(
        File("docs/分离清单.md"), File("../docs/分离清单.md"),
    ).firstOrNull { it.isFile } ?: error("找不到 docs/分离清单.md（cwd=${System.getProperty("user.dir")}）")

    private fun lines(): List<String> = docFile().readText().lineSequence().map { it.trim() }.toList()

    /** 读机器可读行 `PREFIX|a|b|...`（无尾竖线，段一个不少）。 */
    private fun rows(prefix: String): List<List<String>> = lines()
        .filter { it.startsWith("$prefix|") }
        .map { it.split('|').map(String::trim) }

    private val ids = listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7")

    private val axes = mapOf(
        "S1" to listOf("心理兴奋", "勃起度"),
        "S2" to listOf("被吸引", "生理唤起"),
        "S3" to listOf("反射性勃起", "性欲"),
        "S4" to listOf("射精", "高潮"),
        "S5" to listOf("关系", "生理反应"),
        "S6" to listOf("性张力", "勃起度"),
        "S7" to listOf("兴奋", "前液"),
    )

    private val low = setOf("无", "疏远")
    private val high = setOf("强", "有", "高", "亲密")

    /** 默认联动检测器：句子里出现黑名单短语即红；只读表里的 BAD 行，不硬编码短语。 */
    private fun couplingHits(text: String, bad: List<String>): List<String> =
        bad.filter { text.contains(it) }

    @Test
    fun `判据一_七对齐全且每对有素材文件行`() {
        val table = lines().filter { it.startsWith("| S") && it.endsWith("|") }
            .map { it.split('|').drop(1).dropLast(1).map(String::trim) }
        assertEquals("七对恰好七行", ids.size, table.size)
        assertEquals("编号集", ids, table.map { it[0] })
        table.forEach { row ->
            assertEquals("每对九列：${row[0]}", 9, row.size)
            assertTrue("左端非空 ${row[0]}", row[1].isNotBlank())
            assertTrue("右端非空 ${row[0]}", row[2].isNotBlank())
            assertTrue("正确写法非空 ${row[0]}", row[5].isNotBlank())
            assertTrue("错误写法非空 ${row[0]}", row[6].isNotBlank())
            assertTrue("素材引用必须 file:行 ${row[0]}", Regex(""".+\.txt:\d+""").containsMatchIn(row[8]))
        }
    }

    @Test
    fun `判据二_每对两端独立夹具且判定允许`() {
        val bad = rows("BAD").map { it[2] }
        val fixes = rows("FIX")
        assertEquals("七对 × 两向 = 14 夹具", 14, fixes.size)
        ids.forEach { id ->
            val fs = fixes.filter { it[1] == id }
            assertEquals("$id 必须有正向与反向两夹具", 2, fs.size)
            val (aAxis, bAxis) = axes.getValue(id)
            fs.forEach { f ->
                assertEquals("$f 五段齐全", 5, f.size)
                assertEquals("$f 左端轴错", aAxis, f[2].substringBefore("="))
                assertEquals("$f 右端轴错", bAxis, f[3].substringBefore("="))
                assertEquals("$f 不得报警", "允许", f[4])
                val narrative = f[2] + "，" + f[3]
                assertTrue("$narrative 误触默认联动", couplingHits(narrative, bad).isEmpty())
            }
            assertTrue("$id 缺 A 高 B 低", fs.any {
                high.contains(it[2].substringAfter("=")) && low.contains(it[3].substringAfter("="))
            })
            assertTrue("$id 缺 A 低 B 高", fs.any {
                low.contains(it[2].substringAfter("=")) && high.contains(it[3].substringAfter("="))
            })
        }
    }

    @Test
    fun `判据三_四条默认联动必须被检出且正确句不报警`() {
        val bad = rows("BAD").map { it[2] }
        val four = listOf("看到就硬", "被碰就硬", "射精即高潮", "关系好就有反应")
        four.forEach { assertTrue("黑名单缺 $it", bad.contains(it)) }
        four.forEach { phrase ->
            assertTrue("$phrase 未被检出", couplingHits("他${phrase}。", bad).isNotEmpty())
        }
        val goods = rows("GOOD").map { it[1] }
        assertEquals("七对各有正确写法", ids.size, goods.size)
        goods.forEach { assertTrue("正确句误报：$it", couplingHits(it, bad).isEmpty()) }
        // 负例自检：把坏短语塞进正确句，闸必须咬住（防恒绿空闸）
        assertTrue("注入默认联动未被咬住", couplingHits(goods.first() + "看到就硬", bad).isNotEmpty())
        // 反向自检：空黑名单不能凭空报警
        assertTrue("空黑名单应全放行", couplingHits("他看到她硬了。", emptyList()).isEmpty())
    }

    @Test
    fun `判据四_优先级链照素材原文且方向不改`() {
        val expected = "核心优先级：全局状态变量 > 血液流动与局部状态 > 性反应分离总纲 > " +
            "射精/高潮/不应期分离总纲 > 安全边界 > 反恶堕与反神化锁 > 全局颜色总纲 > " +
            "体型总纲 > 重力与软组织惯性 > 生理通道阻力 > 其他描写规则。"
        val chain = lines().first { it.startsWith("CHAIN|") }.removePrefix("CHAIN|")
        assertEquals("链必须与素材逐字一致", expected, chain)
        assertEquals("11 环不可缺", 11, chain.removePrefix("核心优先级：").split(" > ").size)
        assertEquals(
            "素材行号", "00-00-男性-生理总纲.txt:12",
            lines().first { it.startsWith("SRC|") }.removePrefix("SRC|"),
        )
        // 方向自检：把「安全边界」与「全局颜色总纲」对调必须红
        val flipped = chain.replace("安全边界 > 反恶堕与反神化锁 > 全局颜色总纲",
            "全局颜色总纲 > 反恶堕与反神化锁 > 安全边界")
        assertFalse("方向对调必须被判红", flipped == expected)
    }

    @Test
    fun `判据五六_素材行号在册且不新增维度不复制邻单档`() {
        val expectedCites = mapOf(
            "S1" to "00-00-男性-生理总纲.txt:40",
            "S2" to "00-00-男性-生理总纲.txt:404",
            "S3" to "00-00-男性-生理总纲.txt:40",
            "S4" to "00-00-男性-生理总纲.txt:54",
            "S5" to "05_男性-互动.txt:54",
            "S6" to "00-00-男性-生理总纲.txt:404",
            "S7" to "08_母犬-发情机制.txt:4",
        )
        val cites = rows("CITE")
        ids.forEach { id ->
            assertTrue("$id 缺素材 file:行",
                cites.any { it[1] == id && it[2] == expectedCites.getValue(id) })
        }
        assertTrue("前液还需第二处源", cites.any { it[1] == "S7" && it[2] == "03_男性-状态氛围.txt:180" })
        assertTrue("射精/高潮还需第二处源", cites.any { it[1] == "S4" && it[2] == "03_男性-状态氛围.txt:236" })
        val text = lines().joinToString("\n")
        assertTrue("不新增判定维度", text.contains("NEW_DIMENSIONS|0"))
        assertEquals("判定引用只指既有单",
            listOf("826", "868", "780", "727", "898", "883", "827"),
            lines().first { it.startsWith("REF|") }.split('|').drop(1))
        listOf("GrowthTier", "HardnessTier", "ErectVerdict", "FluidKind", "ViscosityGrade")
            .forEach { assertFalse("不许复制邻单档 $it", text.contains(it)) }
    }
}
