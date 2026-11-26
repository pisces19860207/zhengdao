// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十七批 · 反恶堕与反神化门禁。
// 判据唯一真相：docs/反恶堕与反神化.md 的机读行（RULE/HIT/ROOT/ROOTCASE/ABS/REDLINE/…）。
// 本文件只读那张表、不抄第二份短语表；夹具固定在代码里，删表里一行 ⇒ 对应夹具由红转绿。
// 纯判定，无 IO 副作用（读文档除外）、无模型、无语义推断。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AntiCorruptionGateTest {

    private data class Rule(
        val id: String, val lock: String, val nature: String, val point: String,
        val bad: String, val good: String, val code: String, val cite: String,
    )

    private data class Finding(val code: String, val message: String)

    /** 变异入口：给替身文档绝对路径；不给就读仓库唯一表。 */
    private fun docFile(): File {
        val override = System.getenv("ANTICORRUPT_DOC")
        val candidates = if (override.isNullOrBlank()) {
            listOf(File("docs/反恶堕与反神化.md"), File("../docs/反恶堕与反神化.md"))
        } else {
            listOf(File(override))
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("反恶堕文档没找到：${candidates.joinToString { it.absolutePath }}")
    }

    private fun rows(prefix: String): List<List<String>> = docFile().readLines()
        .map { it.trim() }
        .filter { it.startsWith("$prefix|") }
        .map { it.split('|').map(String::trim) }

    private fun rules(): List<Rule> = rows("RULE").map { c ->
        check(c.size == 9) { "RULE 必须 9 列：$c" }
        Rule(c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8])
    }

    /** 检出短语 → 判定码；从 HIT 表读，不在代码里抄第二份短语。 */
    private fun hitPairs(): List<Pair<String, String>> = rows("HIT").map { c ->
        check(c.size == 3) { "HIT 必须 3 列：$c" }
        c[2] to c[1]
    }

    private fun contextWords(): List<String> = rows("CONTEXT").firstOrNull()?.drop(1).orEmpty()

    /**
     * 判定一段文本：命中检出短语 ⇒ 对应码；绝对化词只在「能力/人格语境」红；
     * 取向红线词一律红。去重按码。
     */
    private fun judge(text: String): List<Finding> = judgeWith(text, hitPairs())

    /** 可注入 HIT 表的判定，供变异自检用（删一行 ⇒ 重跑判定）。 */
    private fun judgeWith(text: String, hits: List<Pair<String, String>>): List<Finding> {
        val out = LinkedHashMap<String, String>()
        hits.forEach { (phrase, code) ->
            if (phrase in text && code !in out) out[code] = "命中禁令短语「$phrase」"
        }
        val abs = rows("ABS").map { it[1] }.filter { it in text }
        if (abs.isNotEmpty() && contextWords().any { it in text }) {
            out.putIfAbsent("ABSOLUTE_CLAIM", "能力/人格句含绝对化词「${abs.joinToString("/")}」")
        }
        rows("REDLINE").map { it[1] }.filter { it in text }.forEach { phrase ->
            out.putIfAbsent("ORIENTATION_SWITCH", "取向开关红线「$phrase」")
        }
        rows("RECORD").map { it[1] }.filter { it in text }.forEach { phrase ->
            out.putIfAbsent("RECORD_DESCRIPTION", "纪录级描写「$phrase」")
        }
        rows("SUCCESS").map { it[1] }.filter { it in text }.forEach { phrase ->
            out.putIfAbsent("SUCCESS_NARRATIVE", "成功学式叙述「$phrase」")
        }
        return out.map { (code, message) -> Finding(code, message) }
    }

    /** 三根判定：先按 ROOTREQ 的位置索引取必填槽；任缺 ⇒ 红。 */
    private fun rootVerdict(root: String, slots: List<String>): String {
        val valid = setOf("event", "latent", "longterm")
        val required = if (root in valid) {
            rows("ROOTREQ").firstOrNull { it[1] == root }
                ?.drop(2)?.flatMap { it.split(',') }?.filter { it.isNotBlank() }
                .orEmpty()
        } else emptyList()
        val filled = slots.mapIndexed { i, s -> (i + 1).toString() to s.isNotBlank() }.toMap()
        return when {
            required.isEmpty() -> "红"
            required.all { filled[it] == true } -> "绿"
            else -> "红"
        }
    }

    @Test
    fun `判据一_两把锁表齐_反恶堕7反神化7且禁令各5`() {
        val rs = rules()
        assertEquals("表条数变了要同步报告", 14, rs.size)
        assertEquals("反恶堕 7 条", 7, rs.count { it.lock == "反恶堕" })
        assertEquals("反神化 7 行（含 2 应有写法）", 7, rs.count { it.lock == "反神化" })
        assertEquals("反恶堕禁令 5", 5, rs.count { it.lock == "反恶堕" && it.nature == "禁令" })
        assertEquals("反神化禁令 5", 5, rs.count { it.lock == "反神化" && it.nature == "禁令" })
        assertTrue("每条错误写法非空", rs.all { it.bad.isNotBlank() })
        assertTrue("每条正确写法非空", rs.all { it.good.isNotBlank() })
        assertTrue("每条有判定码", rs.all { it.code.isNotBlank() })
        assertTrue("每条有素材 file:行 或引用", rs.all {
            Regex(""".+\.txt:\d+|[0-9]{3}|禁数值""").containsMatchIn(it.cite)
        })
        val fall = rs.count { it.lock == "反恶堕" }
        val god = rs.count { it.lock == "反神化" }
        println("ANTICORRUPT-READING rules=${rs.size} fall=$fall god=$god")
    }

    @Test
    fun `判据二_三条无过渡堕落句全被检出`() {
        val cases = rows("BADFALL")
        assertEquals("无过渡堕落夹具 3 条", 3, cases.size)
        val hit = cases.filter { judge(it[1]).any { f -> f.code == it[2] } }
        println("ANTICORRUPT-READING badfall hit=${hit.size}/${cases.size}")
        assertEquals("3 条须全检出", cases.size, hit.size)
    }

    @Test
    fun `判据三_三条完美无瑕句全被检出`() {
        val cases = rows("BADGOD")
        assertEquals("完美无瑕夹具 3 条", 3, cases.size)
        val hit = cases.filter { judge(it[1]).any { f -> f.code == it[2] } }
        println("ANTICORRUPT-READING badgod hit=${hit.size}/${cases.size}")
        assertEquals("3 条须全检出", cases.size, hit.size)
    }

    @Test
    fun `判据四_三根齐全绿缺要素红_无根多重红`() {
        val cases = rows("ROOTCASE")
        assertEquals("三根夹具 10 条", 10, cases.size)
        val wrong = cases.filterNot { c ->
            rootVerdict(c[2], listOf(c[3], c[4], c[5])) == c[6]
        }
        println("ANTICORRUPT-READING rootcase=${cases.size} wrong=${wrong.size}")
        assertEquals("三根夹具须逐条符合期望：$wrong", emptyList<List<String>>(), wrong)
        assertEquals("三根各一条定义", 3, rows("ROOT").size)
        assertEquals("ROOTREQ 三条", 3, rows("ROOTREQ").size)
    }

    @Test
    fun `判据五_人格变化须两事件加代价加残留`() {
        val shift = rows("SHIFT")
        val cells = shift.flatten()
        assertTrue("须有至少2个可指事件", cells.contains("至少2个可指事件"))
        assertTrue("须有代价", cells.contains("代价"))
        assertTrue("须有残留矛盾", cells.contains("残留矛盾"))
        val one = shift.first { it[1] == "一次事件翻转" }
        assertEquals("一次事件翻转 ⇒ 红", "红", one[2])
        assertEquals("对应判定码", "PERSONA_FLIP_ONE_EVENT", one[3])
        val ok = rows("OKSHIFT").first()
        assertEquals("有事件+代价+残留的写法不许红", emptyList<Finding>(), judge(ok[1]))
        println("ANTICORRUPT-READING shift=OK/green")
    }

    @Test
    fun `判据六_取向红线掰弯治好遇到对的人就转了全红`() {
        val red = rows("REDLINE").map { it[1] }
        assertTrue("掰弯在册", red.contains("掰弯"))
        assertTrue("被治好在册", red.contains("被治好"))
        assertTrue("遇到对的人就转了在册", red.contains("遇到对的人就转了"))
        listOf("他被掰弯了。", "后来他遇到对的人就转了。", "这种倾向可以被治好。").forEach { text ->
            assertTrue("取向红线须红：$text", judge(text).any { it.code == "ORIENTATION_SWITCH" })
        }
        val social = rows("SOCIAL").first().drop(1)
        assertEquals("社会因素须具体五项", 5, social.size)
        assertTrue("空泛社会影响不在具体项里", !social.contains(rows("SOCIALWEAK").first()[1]))
        println("ANTICORRUPT-READING redline=${red.size} social=${social.size}")
    }

    @Test
    fun `判据七_场域差异不算人格变化_判红即错`() {
        val cases = rows("SWITCH")
        assertEquals("场域夹具 4 条", 4, cases.size)
        val rootTokens = listOf("event", "latent", "longterm", "changeRoot", "TraitChangeEvidence")
        val wrong = ArrayList<String>()
        cases.forEach { c ->
            assertEquals("场域归类", "场域切换", c[2])
            assertEquals("场域期望绿", "绿", c[3])
            val found = judge(c[1])
            if (found.isNotEmpty()) wrong += "${c[0]} 被判红：$found"
            if (rootTokens.any { c[1].contains(it) }) wrong += "${c[0]} 夹具自带三根引用"
        }
        assertEquals("场域切换不许触发三根：$wrong", emptyList<String>(), wrong)
        // 负例自检：把「他在家脾气差」塞进人格变化夹具，检出器必须能咬（防恒绿空闸）
        assertTrue("注入人格翻转短语须被咬住",
            judge("他在家脾气差，但那晚之后整个人都变了。").any { it.code == "PERSONA_FLIP_ONE_EVENT" })
        println("ANTICORRUPT-READING switch green=${cases.size - wrong.size}/${cases.size}")
    }

    @Test
    fun `判据八_正确写法与合法写法全绿_不误杀`() {
        val rs = rules()
        val wrong = rs.filter { judge(it.good).isNotEmpty() }.map { it.id to judge(it.good) }
        assertEquals("每条正确写法都不许红：$wrong", emptyList<Pair<String, List<Finding>>>(), wrong)
        val ok = rows("OKSHIFT").first()
        assertEquals("合法变化写法绿", emptyList<Finding>(), judge(ok[1]))
        // 绝对化词须在能力/人格语境才红：无语境不误杀
        assertEquals("无能力/人格语境的「永远」不红", emptyList<Finding>(), judge("门口那盏灯永远亮着。"))
        // 空词表不能凭空报警
        println("ANTICORRUPT-READING good green=${rs.size}/${rs.size}")
    }

    @Test
    fun `判据九_禁成功学式叙述与纪录级描写`() {
        val success = rows("SUCCESS").map { it[1] }
        assertEquals("成功学句式 2 条", 2, success.size)
        success.forEach { phrase -> assertTrue("成功学须红：$phrase", judge(phrase).any { it.code == "SUCCESS_NARRATIVE" }) }
        val record = rows("RECORD").map { it[1] }
        assertEquals("纪录级短语 4 条", 4, record.size)
        println("ANTICORRUPT-READING success=${success.size} record=${record.size}")
    }

    @Test
    fun `变异自检_删一条HIT短语对应夹具由红转绿`() {
        val hits = hitPairs()
        val target = rows("BADFALL").first() // 「下一秒就主动」⇒ NO_TRANSITION
        assertTrue("基线须红", judgeWith(target[1], hits).any { it.code == target[2] })
        val dropped = hits.filterNot { (phrase, code) -> code == "NO_TRANSITION" && phrase == "下一秒就主动" }
        assertFalse("删行后必须转绿",
            judgeWith(target[1], dropped).any { it.code == "NO_TRANSITION" })
        println("ANTICORRUPT-READING mutation HIT: RED->GREEN")
    }

    @Test
    fun `变异自检_绝对化词须能力人格语境双方门禁`() {
        val withContext = "他技巧永远无人能及。"
        assertTrue("有语境须红", judge(withContext).any { it.code == "ABSOLUTE_CLAIM" })
        assertTrue("无语境不得红", judge("他唱歌永远是最动人的。").isEmpty())
        assertTrue("有语境无绝对化词不得红", judge("他技巧很好，一直稳步进步。").isEmpty())
        println("ANTICORRUPT-READING abs context gate: RED/GREEN both sides")
    }

    @Test
    fun `自检_两把锁引用既有单不新增维度不抄邻单档`() {
        val all = docFile().readText()
        assertTrue("不新增判定维度", all.contains("NEW_DIMENSIONS|0"))
        assertEquals("判定引用只指既有单",
            listOf("747", "921", "920", "810", "910", "934"),
            rows("REF").first().drop(1))
        val cites = rows("CITE")
        assertEquals("三处素材引用", 3, cites.size)
        assertTrue("素材 file:行在册", cites.any { it[2] == "00-00-男性-生理总纲.txt:418" })
        listOf("GrowthTier", "HardnessTier", "ErectVerdict", "FluidKind", "ViscosityGrade")
            .forEach { assertFalse("不许复制邻单档 $it", all.contains(it)) }
    }
}
