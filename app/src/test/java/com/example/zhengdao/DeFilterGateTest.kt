// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十四批 · 去滤镜门禁。
// 第九百四十三批 · 表搬 manifest：黑名单句式唯一表改读 assets/policy/blacklist-manifest.json
// 的 934-de-filter 类（与 935 体相传闻同一处维护、不共用行）；docs/去滤镜.md 的表块自此
// 冻结为历史记录，不再机读。总数与小计一律从 manifest 读——本文件不许出现总数条数字面量
// （BlacklistManifestGateTest 机检）。夹具仍固定在代码里，所以删掉 manifest 一行 ⇒ 对应夹具由红转绿。
// 总则只有一句：对任何人都不设滤镜；不做道德评判（写行为/处境/代价，不写判词）。
package com.example.zhengdao

import com.example.zhengdao.baopu.policy.BlacklistManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class DeFilterGateTest {

    private enum class Voice { NARRATOR, CHARACTER }

    private data class Rule(
        val id: String,
        val category: String,
        val sub: String,
        val template: String,
        val direction: String,
    )

    private data class Finding(val code: String, val message: String)

    private data class RedCase(val id: String, val text: String, val ruleId: String)

    /** 红夹具：与文档模板一一对应，而**不**从文档里抄模板，删行变异才能被看见。 */
    private val redCases = listOf(
        RedCase("JOB01", "医生都是冷静的，不会出错。", "JOB01"),
        RedCase("JOB02", "医生都冷漠，根本不管病人。", "JOB02"),
        RedCase("JOB03", "商人都是奸诈的，别信他们。", "JOB03"),
        RedCase("JOB04", "商人都是讲义气的，肯帮人。", "JOB04"),
        RedCase("JOB05", "教师都正经，不会说粗话。", "JOB05"),
        RedCase("JOB06", "教师都刻板，一点不通融。", "JOB06"),
        RedCase("JOB07", "当医生的身上总有股消毒水味。", "JOB07"),
        RedCase("JOB08", "干这行的肯定不干净。", "JOB08"),
        RedCase("JOB09", "上夜班的都懒散。", "JOB09"),
        RedCase("JOB10", "跑业务的都圆滑。", "JOB10"),
        RedCase("LOOK01", "他鹰钩鼻所以阴险。", "LOOK01"),
        RedCase("LOOK02", "他肥头大耳所以油腻猥琐。", "LOOK02"),
        RedCase("LOOK03", "他獐头鼠目，一看就不是好人。", "LOOK03"),
        RedCase("LOOK04", "她长相出众所以善良。", "LOOK04"),
        RedCase("LOOK05", "他长得丑，心眼坏。", "LOOK05"),
        RedCase("LOOK06", "他清瘦，所以阴郁。", "LOOK06"),
        RedCase("LOOK07", "长得好看肯定干净。", "LOOK07"),
        RedCase("ADV-M01", "他有钱所以有教养。", "ADV-M01"),
        RedCase("ADV-M02", "他有钱所以更值得尊重。", "ADV-M02"),
        RedCase("ADV-M03", "他穷所以卑微。", "ADV-M03"),
        RedCase("ADV-M04", "穷人都脏。", "ADV-M04"),
        RedCase("ADV-A01", "他有权所以靠得住。", "ADV-A01"),
        RedCase("ADV-A02", "当官的必然贪。", "ADV-A02"),
        RedCase("ADV-A03", "有权的人都不干净。", "ADV-A03"),
        RedCase("ADV-C01", "他能力强所以人品好。", "ADV-C01"),
        RedCase("ADV-C02", "他技术那么好，那点毛病算什么。", "ADV-C02"),
        RedCase("ADV-C03", "他能力差所以卑劣。", "ADV-C03"),
        RedCase("ADV-S01", "他能坐这个位置，说明他做人有一套。", "ADV-S01"),
        RedCase("ADV-S02", "混得好就是做人好。", "ADV-S02"),
        RedCase("ADV-S03", "他学历低，品性也好不到哪去。", "ADV-S03"),
        RedCase("UNI01", "军人必然忠诚刚直。", "UNI01"),
        RedCase("UNI02", "军人必然大男子。", "UNI02"),
        RedCase("UNI03", "警察都正义。", "UNI03"),
        RedCase("UNI04", "穿制服的都不是好人。", "UNI04"),
        RedCase("UNI05", "消防员必然无私。", "UNI05"),
        RedCase("UNI06", "公务员必然混日子。", "UNI06"),
        RedCase("UNI07", "公务员都正直。", "UNI07"),
        RedCase("UNI08", "当警察的都粗暴。", "UNI08"),
        RedCase("SCENE01", "他在外一套在家另一套，是人格分裂。", "SCENE01"),
        RedCase("SCENE02", "他对家里人都这样，可见骨子里坏。", "SCENE02"),
        RedCase("SCENE03", "他对家人这么好，一定是好人。", "SCENE03"),
        RedCase("SCENE04", "他有两副面孔。", "SCENE04"),
        RedCase("SCENE05", "所有男人都这样。", "SCENE05"),
        RedCase("SCENE06", "他在家暴躁，真是变态。", "SCENE06"),
        RedCase("FATE01", "面相决定命运。", "FATE01"),
        RedCase("FATE02", "看骨相就知道他靠不住。", "FATE02"),
        RedCase("FATE03", "他八字不好，所以品性差。", "FATE03"),
        RedCase("FATE04", "手相注定他一辈子穷。", "FATE04"),
        RedCase("FATE05", "命理决定他这一生的好坏。", "FATE05"),
        RedCase("DRESS01", "他穿金链，肯定没文化。", "DRESS01"),
        RedCase("DRESS02", "她穿得暴露所以轻浮。", "DRESS02"),
        RedCase("DRESS03", "他名牌加身所以有教养。", "DRESS03"),
        RedCase("DRESS04", "他衣衫褴褛，骨子里低贱。", "DRESS04"),
        RedCase("DRESS05", "他戴眼镜，是个书呆子。", "DRESS05"),
        RedCase("DRESS06", "她打扮精致所以心机深。", "DRESS06"),
    )

    /** 夹具编号集（943 双向一致性测试用：manifest 行 ⇄ 夹具，差集必须空）。 */
    internal val deFilterFixtureIds: Set<String> get() = redCases.map { it.id }.toSet()

    /** 变异入口：给替身文档的绝对路径；不给就读仓库里的唯一表。 */
    private fun docFile(): File {
        val override = System.getenv("DEFILTER_DOC")
        val candidates = if (override.isNullOrBlank()) {
            listOf(File("../docs/去滤镜.md"), File("docs/去滤镜.md"))
        } else {
            listOf(File(override))
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("去滤镜文档没找到：${candidates.joinToString { it.absolutePath }}")
    }

    private fun section(begin: String, end: String): List<String> {
        val all = docFile().readLines()
        val from = all.indexOfFirst { it.contains(begin) }
        val to = all.indexOfFirst { it.contains(end) }
        check(from >= 0 && to > from) { "文档缺标记：$begin / $end" }
        return all.subList(from + 1, to)
    }

    private fun rows(begin: String, end: String): List<List<String>> =
        section(begin, end)
            .filter { it.trimStart().startsWith("|") }
            .map { line -> line.trim().trim('|').split('|').map { cell -> cell.trim() } }
            .filter { cols -> cols.size >= 2 && cols[0] != "编号" && cols[0].any { it != '-' && it != ':' && it != ' ' } }

    /** 黑名单行（943 起唯一表在 manifest 的 934-de-filter 类；解析层已验过结构与计数）。 */
    private fun rules(): List<Rule> = BlacklistManifestFeed.deFilterRows().map {
        Rule(it.id, it.category, it.sub, it.template, it.direction)
    }

    /** `…` 是任意填充；其余片段按字面量钉住，不做语义推断。 */
    private fun matches(template: String, text: String): Boolean {
        val pattern = template.split('…').joinToString(".*") { Pattern.quote(it) }
        return Regex(pattern).containsMatchIn(text)
    }

    /** 总则直判的判词层：叙述者定性红；角色或明确归属的看法只 WARN。 */
    private fun verdictFinding(text: String, voice: Voice): List<Finding> {
        val phrase = listOf("烂人", "不是好人", "不像好人", "像坏人", "骨子里坏", "不是东西", "低贱", "变态")
            .firstOrNull { text.contains(it) } ?: return emptyList()
        val attributed = voice == Voice.CHARACTER ||
            listOf("觉得", "以为", "我看", "看来", "承认").any { text.contains(it) }
        return if (attributed) {
            listOf(Finding("VOICE_BIAS", "角色／归属看法「$phrase」（须读成偏见，可被推翻）"))
        } else {
            listOf(Finding("MORAL_VERDICT", "叙述者判词「$phrase」（只许写行为／处境／代价）"))
        }
    }

    private fun judge(text: String, voice: Voice, rules: List<Rule>): List<Finding> {
        val out = ArrayList<Finding>()
        rules.forEach { rule ->
            if (matches(rule.template, text)) {
                val head = if (voice == Voice.NARRATOR) "叙述者滤镜命中" else "角色偏见命中"
                val tail = if (voice == Voice.NARRATOR) "" else "（须读成偏见）"
                out += Finding(rule.id, "$head ${rule.id}：${rule.template}$tail")
            }
        }
        out += verdictFinding(text, voice)
        return out
    }

    @Test
    fun `七类表条数从唯一表复现_且只含句式模板`() {
        val rs = rules()
        val total = BlacklistManifestFeed.total(BlacklistManifest.KIND_DE_FILTER)
        val subs = BlacklistManifestFeed.subtotals(BlacklistManifest.KIND_DE_FILTER)
        assertEquals("行数与 manifest totals 一致（改表要同步计数，解析层也把守）", total, rs.size)
        assertEquals("小计之和必须等于总数", total, subs.values.sum())
        subs.forEach { (cat, n) -> assertEquals("$cat 条数与 manifest 小计一致", n, rs.count { it.category == cat }) }
        assertEquals("类别必须恰好七类", SEVEN_CATEGORIES, rs.map { it.category }.distinct())
        assertTrue("只收句式模板：必须带 … 占位且不是单词", rs.all { it.template.contains('…') && it.template.length >= 4 })
        assertEquals(setOf("好", "坏"), rs.map { it.direction }.toSet())
        println("DEFILTER-READING table total=$total byCategory=$subs from manifest")
    }

    @Test
    fun `黑名单句式逐条咬住_删一行必有一条由红转绿`() {
        val rs = rules()
        val red = ArrayList<String>()
        val green = ArrayList<String>()
        redCases.forEach { c ->
            if (judge(c.text, Voice.NARRATOR, rs).any { it.code == c.ruleId }) red += c.id
            else green += "${c.id}(${c.ruleId})"
        }
        println("DEFILTER-READING blacklist red=${red.size} green=${green.size} greenIds=${green.joinToString(",")}")
        assertEquals("有句式没牙（删行变异应在这里转绿）：$green", emptyList<String>(), green)
    }

    @Test
    fun `同一句式放角色口只WARN且须读成偏见`() {
        val rs = rules()
        redCases.forEach { c ->
            val found = judge(c.text, Voice.CHARACTER, rs)
            assertTrue("角色口也应命中句式：${c.id}", found.any { it.code == c.ruleId })
            assertFalse("角色口不许判红：${c.id} $found", found.any { it.message.startsWith("叙述者滤镜") || it.code == "MORAL_VERDICT" })
            assertTrue("角色口必须提示偏见：${c.id} $found", found.any { it.message.contains("偏见") })
        }
        println("DEFILTER-READING characterWARN=${redCases.size}/${redCases.size}")
    }

    @Test
    fun `四类反例全开且扩展反例都能合规写`() {
        val rs = rules()
        val all = rows("DEFILTER-COUNTER-BEGIN", "DEFILTER-COUNTER-END")
        val core = all.filter { it[0] in setOf("C01", "C02", "C03", "C04") }
        assertEquals("核心四类反例缺一即红", 4, core.size)
        val green = ArrayList<String>()
        all.forEach { c ->
            val found = judge(c[2], Voice.NARRATOR, rs)
            if (found.isEmpty()) green += c[0] else println("反例写红了：${c[0]} -> $found")
        }
        println("DEFILTER-READING counter core=${core.size}/4 all=${green.size}/${all.size} green=${green.joinToString(",")}")
        assertEquals("每条反例都必须能写成合规句", all.size, green.size)
    }

    @Test
    fun `双向_好滤镜与坏滤镜都钉死`() {
        val rs = rules()
        listOf("职业", "钱权能力", "军警消政府").forEach { cat ->
            val dirs = rs.filter { it.category == cat }.map { it.direction }.toSet()
            assertTrue("$cat 必须双向都有条目：$dirs", dirs.containsAll(setOf("好", "坏")))
        }
        listOf("钱", "权", "能力").forEach { sub ->
            val dirs = rs.filter { it.category == "钱权能力" && it.sub == sub }.map { it.direction }.toSet()
            assertTrue("$sub 必须双向：$dirs", dirs.containsAll(setOf("好", "坏")))
        }
    }

    @Test
    fun `面相学叙述者一律红_角色一律WARN`() {
        val rs = rules()
        val faceIds = rs.filter { it.sub == "面相" }.map { it.id }.toSet()
        assertEquals("面相句式闭集（小计从 manifest 实数复现）",
            setOf("LOOK01", "LOOK02", "LOOK03", "FATE01", "FATE02"), faceIds)
        val checked = redCases.filter { it.ruleId in faceIds }
        checked.forEach { c ->
            assertTrue("面相叙述者必须红：${c.id}", judge(c.text, Voice.NARRATOR, rs).any { it.code == c.ruleId && it.message.startsWith("叙述者滤镜") })
            val role = judge(c.text, Voice.CHARACTER, rs)
            assertFalse("角色面相不许判红：${c.id}", role.any { it.message.startsWith("叙述者滤镜") })
            assertTrue("角色面相必须读成偏见：${c.id}", role.any { it.message.contains("偏见") })
        }
        println("DEFILTER-READING physiognomy=${checked.size}/${checked.size}")
    }

    @Test
    fun `钱权能力三滤镜正反全红`() {
        val rs = rules()
        val ids = listOf(
            "ADV-M01", "ADV-M02", "ADV-M03", "ADV-M04", "ADV-A01", "ADV-A02", "ADV-A03",
            "ADV-C01", "ADV-C02", "ADV-C03", "ADV-S01", "ADV-S02", "ADV-S03",
        )
        val red = ids.count { id ->
            val sample = redCases.single { it.ruleId == id }
            judge(sample.text, Voice.NARRATOR, rs).any { it.code == id }
        }
        println("DEFILTER-READING adv red=$red/${ids.size}")
        assertEquals("钱／权／能力／成功学／阶层必须全红", ids.size, red)
    }

    @Test
    fun `军警消政府正义化与污名化都红`() {
        val rs = rules()
        val ids = listOf("UNI01", "UNI02", "UNI03", "UNI04", "UNI05", "UNI06", "UNI07", "UNI08")
        val red = ids.count { id ->
            val sample = redCases.single { it.ruleId == id }
            judge(sample.text, Voice.NARRATOR, rs).any { it.code == id }
        }
        println("DEFILTER-READING uniform red=$red/${ids.size}")
        assertEquals("正义化与污名化双向都红", ids.size, red)
    }

    @Test
    fun `场域切换允许且不触927三根`() {
        val rs = rules()
        val all = rows("DEFILTER-SWITCH-BEGIN", "DEFILTER-SWITCH-END")
        assertEquals("场域夹具四条", 4, all.size)
        val roots = listOf("eventRef", "latentRef", "longtermRef", "TraitChangeEvidence")
        val green = ArrayList<String>()
        all.forEach { c ->
            val found = judge(c[1], Voice.NARRATOR, rs)
            assertFalse("927 三根不许被场域切换触发：${c[0]} $found", found.any { it.code.startsWith("TRAIT_CHANGE") })
            assertFalse("场域夹具不许自带三根引用：${c[0]}", roots.any { c[1].contains(it) })
            if (found.isEmpty()) green += c[0]
        }
        println("DEFILTER-READING switch green=${green.size}/${all.size}")
        assertEquals("场域切换必须全绿", all.size, green.size)
    }

    @Test
    fun `叙述者判词红_角色看法WARN`() {
        val rs = rules()
        val verdict = "他对家里人都这样，可见他就是个烂人。"
        assertTrue("叙述者判词必须红", judge(verdict, Voice.NARRATOR, rs).any { it.code == "MORAL_VERDICT" })
        val role = judge("我看他不像好人。", Voice.CHARACTER, rs)
        assertTrue("角色看法只 WARN 且须读成偏见", role.any { it.code == "VOICE_BIAS" && it.message.contains("偏见") })
        assertFalse("角色看法不许升级成叙述者判词", role.any { it.code == "MORAL_VERDICT" })
        val behavior = "他做事迟到三次，大家都躲着他。"
        assertEquals("只写行为必须绿：$behavior", emptyList<Finding>(), judge(behavior, Voice.NARRATOR, rs))
    }

    @Test
    fun `合规负例全绿_角色偏见只WARN`() {
        val rs = rules()
        val all = rows("DEFILTER-COMPLIANT-BEGIN", "DEFILTER-COMPLIANT-END")
        var green = 0
        var warn = 0
        all.forEach { c ->
            val voice = if (c[2] == "角色") Voice.CHARACTER else Voice.NARRATOR
            val found = judge(c[1], voice, rs)
            when (c[3]) {
                "GREEN" -> {
                    green++
                    assertTrue("误杀合规写法：${c[0]} ${c[1]} -> $found", found.isEmpty())
                }
                "WARN" -> {
                    warn++
                    assertTrue("角色偏见必须 WARN：${c[0]} -> $found", found.any { it.message.contains("偏见") })
                    assertFalse("WARN 不许判红：${c[0]}", found.any { it.message.startsWith("叙述者滤镜") || it.code == "MORAL_VERDICT" })
                }
                else -> fail("未知期望 ${c[3]}")
            }
        }
        println("DEFILTER-READING compliant green=$green warn=$warn total=${all.size}")
    }

    @Test
    fun `自检_删一行句式对应夹具必须由红转绿`() {
        val rs = rules()
        val target = rs.first { it.id == "ADV-M01" }
        val sample = redCases.single { it.ruleId == target.id }
        assertTrue("基线必须红：${sample.text}", judge(sample.text, Voice.NARRATOR, rs).any { it.code == target.id })
        val dropped = rs.filter { it.id != target.id }
        assertFalse("删行后必须转绿", judge(sample.text, Voice.NARRATOR, dropped).any { it.code == target.id })
        println("DEFILTER-READING mutation selfcheck ${target.id}: RED->GREEN")
    }

    companion object {
        /** 七类闭集（顺序即 manifest 行序）；小计数值不在这里——从 manifest 读。 */
        private val SEVEN_CATEGORIES = listOf(
            "职业", "外貌", "钱权能力", "军警消政府", "场域", "命理", "服装配饰",
        )
    }
}
