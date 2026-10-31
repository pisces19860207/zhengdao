// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · 禁止标量分（源级门禁）。
//
// 判据的产物必须是**可读检查条目**（通过／不通过＋原因），不许是 `0.87` 这种分数：
// 标量分不可审计、不可争辩，等于把裁判权偷偷交回给不透明的模型——那正是本批要锁死的东西。
//
// 口径（为什么不写「全仓禁止浮点」这种漂亮话）：
//   - 扫的是**判据产物**这一层：`policy/` 里本批那四个文件，加上全仓任何名字长成
//     Criteria／Admission／Criterion／CheckItem／Verdict 的类型；
//   - **阈值参数不算产物**：122 批 `Policy` 里的 eval floor（Double）是「表里装的参数」，
//     不是判据下结论时给出的数。把它一起扫了，等于第一天就把门禁调成不会红的样子。
//   - 扫描前先剥注释：注释里写「0.87 一类」是给人看的反例，不是产物。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.constitution.GateIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoScalarScoreGateTest {

    private val root = "src/main/java/com/example/zhengdao/baopu/policy"

    /** 本批的判据产物层（这四个文件是「判据下结论」的地方，一个浮点都不许有）。 */
    private val criteriaSurface = listOf(
        "ImmutableCriteria.kt",
        "CriteriaEvolution.kt",
        "AnchorSet.kt",
        "CriteriaEpoch.kt",
    )

    private fun codeOf(rel: String): String = GateIo.stripComments(GateIo.read("$root/$rel"))

    private fun floatingDeclarations(code: String): List<String> =
        Regex("""(?m)^\s*(?:internal |private )?(?:val|var)\s+\w+\s*:\s*(?:Double|Float)\b""")
            .findAll(code).map { it.value.trim() }.toList() +
            Regex("""(?m)^\s*(?:internal |private )?(?:override )?fun\s+\w+[^=\n]*:\s*(?:Double|Float)\b""")
                .findAll(code).map { it.value.trim() }.toList()

    private fun scoreNamedFields(code: String): List<String> =
        // 只抓**数值型**的分数样字段：`confidence: Confidence`（枚举）不是分数，别误杀。
        Regex("""(?m)^\s*(?:internal |private )?(?:val|var)\s+\w*(?i:score|rating|confidence)\w*\s*:\s*(?:Double|Float|Int|Long)\b""")
            .findAll(code).map { it.value.trim() }.toList()

    @Test
    fun `判据产物层零浮点声明零分数字段零小数量字面量`() {
        val sources = GateIo.kotlinSources(root).filter { (path, _) ->
            criteriaSurface.any { path.endsWith(it) }
        }
        assertEquals("本批四个判据产物文件都必须在扫描范围内（改名要一起改这里）", criteriaSurface.size, sources.size)
        sources.forEach { (path, text) ->
            val code = codeOf(path)
            assertEquals("$path 出现浮点声明：${floatingDeclarations(code)}", emptyList<String>(), floatingDeclarations(code))
            assertEquals("$path 出现分数样字段：${scoreNamedFields(code)}", emptyList<String>(), scoreNamedFields(code))
            val decimals = Regex("""(?<![\w.])\d+\.\d+""").findAll(code).map { it.value }.toList()
            assertFalse("$path 出现小数量字面量 $decimals（判据不产数）", decimals.isNotEmpty())
            // 「一致率」只能数条数，不许算成百分比：字样出现就说明有人在造分数。
            listOf("评分", "置信").forEach { token ->
                assertFalse("$path 不许出现「$token」（判据产物只给通过／不通过＋原因）", code.contains(token))
            }
        }
    }

    @Test
    fun `全仓判据结论类型都不许携带浮点`() {
        // 按**类体**扫，不按整个文件：同一文件里别的类有浮点不关判据类型的事。
        val offenders = GateIo.kotlinSources("src/main/java/com/example/zhengdao/baopu")
            .flatMap { (path, text) -> classBodies(GateIo.stripComments(text)).map { path to it } }
            .filter { (_, body) -> floatingDeclarations(body).isNotEmpty() || scoreNamedFields(body).isNotEmpty() }
            .map { it.first }
            .distinct()
        assertEquals("这些判据结论类型里有分数：$offenders", emptyList<String>(), offenders)
    }

    /** 粗粒度花括号配对取类体（判据结论类型都是简单 data class／enum，够用）。 */
    private fun classBodies(code: String): List<String> {
        val re = Regex("""(?m)^\s*(?:internal )?(?:data )?class\s+\w*(?:Criteria|Admission|Criterion|CheckItem|Verdict)\w*""")
        val out = mutableListOf<String>()
        re.findAll(code).forEach { m ->
            val start = code.indexOf('{', m.range.last)
            if (start >= 0) {
                var depth = 0
                var i = start
                while (i < code.length) {
                    when (code[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                out += code.substring(start, i + 1)
                                break
                            }
                        }
                    }
                    i++
                }
            }
        }
        return out
    }

    @Test
    fun `阈值参数不是判据产物_豁免名单只这两处`() {
        // 122 批的政策表装的是**参数**（eval floor 一类）。这里把它显式登记成豁免，
        // 是为了「豁免要看得见」：以后再有第三处，这条会红，得先过用户点头。
        // 按「有没有 Double/Float 字样」数：PolicyTable 的 strictDouble 是函数返回类型，
        // 不是 val 声明，但它确实是浮点阈值的那一处，不能漏。
        val floatOwners = GateIo.kotlinSources(root)
            .filter { (_, text) -> Regex("""\b(?:Double|Float)\b""").containsMatchIn(GateIo.stripComments(text)) }
            .map { (path, _) -> path.substringAfterLast('/') }
            .sorted()
        assertEquals(
            "policy 包里带浮点的文件超出已知两处（新增一处＝把阈值权力也放进来了）：$floatOwners",
            listOf("Policy.kt", "PolicyTable.kt"),
            floatOwners,
        )
    }

    @Test
    fun `负例_判据输出改成分数时这条门禁真的会红`() {
        val fixture = """
            internal data class CheckItem(
                val name: String,
                val score: Double,
            )
            internal fun grade(): Double = 0.87
        """.trimIndent()
        // 自证牙齿：字段声明一处（score: Double）、返回类型一处（fun grade(): Double）
        assertEquals("字段与返回值各一处浮点", 2, floatingDeclarations(fixture).size)
        assertEquals(1, scoreNamedFields(fixture).size)
        assertTrue("小数量字面量必须被抓住", Regex("""(?<![\w.])\d+\.\d+""").findAll(fixture).any())
    }

    @Test
    fun `门禁扫到的文件数不是零_免得空转`() {
        assertTrue("扫描根目录没找到源文件（路径漂了）", GateIo.kotlinSources(root).size >= criteriaSurface.size)
    }
}
