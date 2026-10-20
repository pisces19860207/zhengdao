// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十批 ③ · 基线读取口径的门禁（派工单 §1：「baseline 更新有据——旧 baseline 不变，新增 B/C 条目」）。
//
// 全离线：读的是盘上两份 JSON，不联网、不花钱、不需要 key。
//
// 它守的是这一批最容易出事的那句话——**「B/C 也覆盖了」到底是谁说的**。
// 代码把通路搭好 ≠ 这两层有了水位。只要读法上留一个「取不到数就按通过算」的口子，
// 下一个接手的人就会把「没人验过」读成「都过了」，那比红更糟（本仓反复钉的就是这种假绿）。
package com.example.zhengdao.baopu.eval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvalBaselineTest {

    // ── ① 盘上这份：A 段没动、B/C 条目立在这儿 ──────────────────────────

    @Test
    fun `现盘基线_A段水位与旧读法逐字一致`() {
        val doc = requireNotNull(EvalBaseline.parseOrNull(baselineText())) {
            "eval/baseline.json 读不出来——门禁宁可红，也不许把坏基线当没有基线静默放行"
        }
        // 这三行就是「旧 baseline 不变」的证据本身：数字取自 2026-10-17 那轮真人跑出来的水位
        assertEquals(1.0, doc.passRate, 0.0)
        assertEquals(2.7, doc.avgSteps, 0.0)
        assertEquals(10, doc.cases)
        assertEquals(10, doc.passing.size)
        assertEquals("deepseek-flash", doc.model)
        // A 段判据字段必须还在原处（CI 的 check-eval-baseline.py 只认这四个；追加段不许动它们）
        val keys = keysOf(JSONObject(baselineText()))
        for (must in listOf("passRate", "avgSteps", "cases", "passing")) {
            assertTrue("基线顶层少了 $must（CI 脚本会读不出＝判红）：$keys", keys.contains(must))
        }
        for (forbidden in listOf("apiKey", "key", "token")) {
            assertFalse("基线里不许出现凭据类字段 $forbidden", keys.contains(forbidden))
        }
    }

    @Test
    fun `B与C两层都在基线里且都标着未实测`() {
        val doc = EvalBaseline.parse(baselineText())
        assertEquals("90 批交付的是这两层的账", setOf("B_GLOBAL", "C_PROJECT"), doc.layers.keys)
        for (l in doc.layers.values) {
            assertFalse("${l.persona} 不许自称已实测（本批只交代码，读数由人跑）", l.measured)
            assertEquals("没跑必须用这个标记", EvalBaseline.UNRUN, l.status)
            assertTrue("${l.persona} 未实测却写着 passing（伪造基线）", l.passing.isEmpty())
            assertNull("未实测的条目拿不出读数：$l", l.readingOrNull())
            assertTrue("${l.persona} 至少 3 条（派工单 §1）：${l.cases}", l.cases.size >= 3)
            assertTrue("label 要说清没实测：${l.label()}", l.label().contains("未实测"))
            assertTrue("label 不许让人误读成过：${l.label()}", l.label().contains("不许当通过"))
        }
        assertEquals(listOf("B_GLOBAL", "C_PROJECT"), doc.unrunLayers())
    }

    @Test
    fun `名册与casesjson双向对得上`() {
        val cases = EvalCases.load(File(casesFile().absolutePath))
        val doc = EvalBaseline.parse(baselineText())
        for (l in doc.layers.values) {
            val onDisk = cases.filter { it.persona == l.persona }.map { it.id }.sorted()
            assertEquals(
                "${l.persona} 名册与盘上该层用例不符（基线记的必须是真在跑的那几条）",
                onDisk,
                l.cases.sorted(),
            )
        }
        val listed = doc.layers.values.flatMap { it.cases }.toSet()
        for (c in cases.filter { it.usesLayerFixture }) {
            assertTrue("用例 ${c.id} 是 fixture 层却没进基线名册＝没人给它记账", c.id in listed)
        }
        // 反向：名册里不许留着已经删掉的用例（CI 的放松门禁只数用例文件，看不见这种僵尸条目）
        val alive = cases.map { it.id }.toSet()
        assertTrue("基线名册里有已经不存在的用例：${listed - alive}", (listed - alive).isEmpty())
    }

    // ── ② 读法：没跑 ≠ 通过 ─────────────────────────────────────────────

    @Test
    fun `未实测不许被读成通过_也不许被补成分数`() {
        // 这条测的是消费方唯一的出口：拿不到数就只能拿到 null，没有第三种值可补
        val doc = EvalBaseline.parse(baselineText())
        for (l in doc.layers.values) {
            assertNull("${l.persona} 没实测，读数出口必须是 null", l.readingOrNull())
        }
        val missing = EvalBaseline.parse("""{"passRate":0.9,"avgSteps":3.0,"passing":["a","b"]}""")
        assertEquals("cases 缺省按 passing 条数兜住（老基线没这字段）", 2, missing.cases)
        assertNull("老基线没有这一层＝没有这一层的数", missing.layer("B_GLOBAL"))
        assertNull("层不存在时读不出数（不许脑补 1.0）", missing.layer("B_GLOBAL")?.readingOrNull())
    }

    @Test
    fun `伪造与半成品条目当场抛`() {
        fun boom(entry: String, why: String) {
            val text = """{"passRate":1.0,"avgSteps":2.0,"cases":1,"passing":["a"],""" +
                """"personas":{"layers":{"B_GLOBAL":$entry}}}"""
            val r = runCatching { EvalBaseline.parse(text) }
            assertTrue("$why —— 却没抛（读出来了：${r.getOrNull()}）", r.isFailure)
        }
        boom("""{"measured":false,"status":"UNRUN_NO_KEY","cases":["x"],"passing":["x"]}""", "未实测却填了 passing")
        boom("""{"measured":false,"status":"UNRUN_NO_KEY","cases":["x"],"passRate":1.0}""", "未实测却填了 passRate")
        boom("""{"measured":false,"status":"","cases":["x"]}""", "未实测却不写为什么没跑")
        boom("""{"measured":false,"status":"UNRUN_NO_KEY"}""", "连该跑哪几条都没记")
        boom("""{"measured":true,"cases":["x"],"passing":["x"]}""", "自称实测却没有 passRate")
        boom("""{"measured":true,"passRate":1.5,"cases":["x"]}""", "passRate 越界")
        boom("""{"measured":true,"passRate":1.0,"cases":[]}""", "自称实测却没有名册")
        val typo = """{"passRate":1.0,"avgSteps":2.0,"personas":{"layers":""" +
            """{"D_GLOBAL":{"measured":false,"status":"UNRUN_NO_KEY","cases":["x"]}}}}"""
        assertTrue("层名拼错必须抛（拼错的条目永远不会被读到）", runCatching { EvalBaseline.parse(typo) }.isFailure)
    }

    @Test
    fun `层里的读数不许串进A段水位`() {
        val text = """{"passRate":1.0,"avgSteps":2.7,"cases":10,"passing":["a"],""" +
            """"personas":{"layers":{"B_GLOBAL":{"measured":true,"passRate":0.25,"avgSteps":6.0,"cases":["x"]}}}}"""
        val doc = EvalBaseline.parse(text)
        assertEquals("A 段通过率只认顶层字段：层里写多少都不许串进来", 1.0, doc.passRate, 0.0)
        assertEquals("层自己的读数要能单独取到（判 B 层退化用）", 0.25, doc.layer("B_GLOBAL")!!.readingOrNull()!!, 0.0)
        assertEquals("实测条目的 status 缺省补 MEASURED", "MEASURED", doc.layer("B_GLOBAL")!!.status)
    }

    // ── ③ 自动写回不许抹掉人工条目 ──────────────────────────────────────

    @Test
    fun `重写基线时带走B_C条目`() {
        val prev = baselineText()
        val merged = EvalBaseline.withPreservedPersonas(JSONObject().put("passRate", 1.0).put("avgSteps", 2.7), prev)
        val layers = merged.getJSONObject("personas").getJSONObject("layers")
        assertEquals("自动写回把人工条目带过来了", setOf("B_GLOBAL", "C_PROJECT"), keysOf(layers))
        // 新份自己写了 personas ⇒ 以新的为准（那是人手工改过的，自动跑批不许覆盖）
        val own = JSONObject().put("personas", JSONObject().put("note", "人手工改的"))
        assertEquals("人手工改的不许被带走", "人手工改的", EvalBaseline.withPreservedPersonas(own, prev).getJSONObject("personas").getString("note"))
        // 前值缺失或坏 JSON ⇒ 保留新份、不炸（不能为了记账把整份基线写坏）
        assertNull(EvalBaseline.withPreservedPersonas(JSONObject().put("passRate", 1.0), null).optJSONObject("personas"))
        assertNull(EvalBaseline.withPreservedPersonas(JSONObject().put("passRate", 1.0), "{ 坏文件").optJSONObject("personas"))
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    /** org.json 在安卓桩里没有 keySet()，只有 keys() 迭代器——统一走这里。 */
    private fun keysOf(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun baselineText(): String = locate("baseline.json").readText(Charsets.UTF_8)

    private fun casesFile(): File = locate("cases.json")

    /** 单测的工作目录是 `app/`，与 `EvalGateTest`/`EvalCasesFileTest` 同一个找根法子。 */
    private fun locate(name: String): File {
        var d: File? = File(".").absoluteFile
        repeat(6) {
            if (d != null && File(d, "eval/$name").isFile) return File(d, "eval/$name")
            d = d?.parentFile
        }
        throw AssertionError("找工作区根失败：向上六层都没有 eval/$name")
    }
}
