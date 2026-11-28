// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百四十三批 · 黑名单 manifest 门禁。934 与 935 的两份黑名单搬进同一份
// assets/policy/blacklist-manifest.json（同一口径、同一处维护、不共用行），本文件钉六件事：
//   ① B1 两类 id 齐且只此两类（第三张表不许并入）；
//   ② B4 双向一致性：manifest 每行 ⇒ 必须有实现；每个实现 ⇒ 必须有 manifest 行（差集空）；
//     ——934 的实现 = DeFilterGateTest 的红夹具，935 的实现 = 本文件的红夹具（逐行 27 条）；
//   ③ B6/⑤ 可复算：改 manifest 一条 ⇒ 至少一处测试红（解析层拒收 + 行级牙口，内存镜像可复算）；
//   ④ 源码闸：934 读端无总数条数字面量（B2）、935 读端无内置表（B3）、
//     判定代码不读 expect（V3：manifest 发不了通行证）、读端无回退字面量（V1）；
//   ⑤ V2 闸：双向与可复算的检查不许被悄悄摘除（存在性自检）；
//   ⑥ 935 行级牙口：每行夹具被且只被自己那行咬住（互斥），删行必转绿。
//
// 变异通道：BLACKLIST_MANIFEST 环境变量可把读端指向替身 manifest（对应老 DEFILTER_DOC 机制）。
package com.example.zhengdao

import com.example.zhengdao.baopu.policy.BlacklistManifest
import com.example.zhengdao.baopu.state.bodyparts.Rumor
import com.example.zhengdao.baopu.state.bodyparts.RumorClass
import com.example.zhengdao.baopu.state.bodyparts.RumorTarget
import com.example.zhengdao.baopu.state.bodyparts.ShapeJudge
import com.example.zhengdao.baopu.state.bodyparts.ShapeRumorCatalog
import com.example.zhengdao.baopu.state.bodyparts.Voice
import com.example.zhengdao.baopu.state.bodyparts.Verdict
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class BlacklistManifestGateTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun feedManifestRows() {
            BlacklistManifestFeed.installShapeMyth()
        }

        /** 测试源码定位（工作目录可能是 app/ 也可能是仓根）。 */
        private fun testSource(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

        private val MAIN_DIR = "src/main/java/com/example/zhengdao/baopu/"

        /** 生产源码定位（走 SrcRoots 的多根解析）。 */
        private fun mainSource(rel: String): String =
            SrcRoots.find("$MAIN_DIR$rel").readText()
    }

    // ── ① B1：两类齐 ─────────────────────────────────────────────────

    @Test
    fun `manifest两类id齐且只此两类`() {
        val root = JSONObject(BlacklistManifestFeed.rawText())
        val kinds = root.getJSONArray("kinds")
        val ids = (0 until kinds.length()).map { n -> kinds.getJSONObject(n).optString("id") }
        assertEquals(
            "kinds 闭集：恰为 934 与 935 两类、顺序固定（第三张表不许并入）",
            listOf(BlacklistManifest.KIND_DE_FILTER, BlacklistManifest.KIND_SHAPE_MYTH),
            ids,
        )
        val snap = BlacklistManifestFeed.snapshot()
        assertTrue("934 行非空", snap.deFilterCount > 0)
        assertTrue("935 行非空", snap.shapeMythCount > 0)
        println("MANIFEST-READING " + snap.summary())
    }

    @Test
    fun `totals与小计与行数三方一致`() {
        val snap = BlacklistManifestFeed.snapshot()
        assertEquals("934 totals＝行数", snap.deFilterCount, snap.total(BlacklistManifest.KIND_DE_FILTER))
        assertEquals("935 totals＝行数", snap.shapeMythCount, snap.total(BlacklistManifest.KIND_SHAPE_MYTH))
        assertEquals(
            "934 小计之和＝行数",
            snap.deFilterCount,
            snap.subtotal(BlacklistManifest.KIND_DE_FILTER).values.sum(),
        )
        assertEquals(
            "935 小计之和＝行数",
            snap.shapeMythCount,
            snap.subtotal(BlacklistManifest.KIND_SHAPE_MYTH).values.sum(),
        )
        assertEquals(
            "934 小计键集＝实际类别集",
            snap.deFilter.map { it.category }.distinct().toSet(),
            snap.subtotal(BlacklistManifest.KIND_DE_FILTER).keys,
        )
        assertEquals(
            "935 小计键集＝实际类 tag 集",
            snap.shapeMyth.map { it.klass }.distinct().toSet(),
            snap.subtotal(BlacklistManifest.KIND_SHAPE_MYTH).keys,
        )
        println(
            "MANIFEST-READING totals=" + snap.totals +
                " sub934=" + snap.subtotal(BlacklistManifest.KIND_DE_FILTER) +
                " sub935=" + snap.subtotal(BlacklistManifest.KIND_SHAPE_MYTH),
        )
    }

    // ── ② B4：双向一致性（差集空） ────────────────────────────────────

    @Test
    fun `双向一致性_934_manifest行与红夹具差集空`() {
        val manifestIds = BlacklistManifestFeed.snapshot().deFilter.map { it.id }.toSet()
        val fixtureIds = DeFilterGateTest().deFilterFixtureIds
        val manifestOnly = manifestIds - fixtureIds
        val fixtureOnly = fixtureIds - manifestIds
        assertEquals("manifest 有行无实现（差集必须空）：$manifestOnly", emptySet<String>(), manifestOnly)
        assertEquals("有实现无 manifest 行（差集必须空）：$fixtureOnly", emptySet<String>(), fixtureOnly)
        println("MANIFEST-READING 934 diff both ways = 0/0, ids=${manifestIds.size}")
    }

    @Test
    fun `双向一致性_935_manifest行与红夹具差集空`() {
        val manifestIds = BlacklistManifestFeed.snapshot().shapeMyth.map { it.id }.toSet()
        val fixtureIds = shapeMythFixtures.map { it.id }.toSet()
        val manifestOnly = manifestIds - fixtureIds
        val fixtureOnly = fixtureIds - manifestIds
        assertEquals("manifest 有行无实现（差集必须空）：$manifestOnly", emptySet<String>(), manifestOnly)
        assertEquals("有实现无 manifest 行（差集必须空）：$fixtureOnly", emptySet<String>(), fixtureOnly)
        println("MANIFEST-READING 935 diff both ways = 0/0, ids=${manifestIds.size}")
    }

    // ── ⑥ 935 行级牙口：互斥夹具 ─────────────────────────────────────

    @Test
    fun `935夹具逐行咬住_被且只被自己那行咬`() {
        val rows = BlacklistManifestFeed.snapshot().shapeMyth
        shapeMythFixtures.forEach { f ->
            val own = rows.first { it.id == f.id }
            assertTrue(
                "夹具 ${f.id} 必须被自己那行咬住：${own.template}",
                ShapeRumorCatalog.templateMatches(own.template, f.text),
            )
            val others = rows.filter { it.id != f.id }.count {
                ShapeRumorCatalog.templateMatches(it.template, f.text)
            }
            assertEquals("夹具 ${f.id} 必须只被自己那行咬住（互斥，删行才转绿）", 0, others)
            val findings = ShapeJudge.judgeText(f.text, Voice.NARRATOR)
            val expectedCode = if (own.klass == RumorClass.FORTUNE.tag) null else "RUMOR-NARRATOR-" + f.id
            if (expectedCode != null) {
                assertTrue(
                    "夹具 ${f.id} 叙述者必须红且代码对上：$findings",
                    findings.any { it.verdict == Verdict.DENY && it.code == expectedCode },
                )
            } else {
                assertTrue(
                    "夹具 ${f.id}（命理行）叙述者必须红：$findings",
                    findings.any {
                        it.verdict == Verdict.DENY && it.code == "FORTUNE-DIRECT" && it.why.contains(f.id)
                    },
                )
            }
        }
        println("MANIFEST-READING 935 teeth ${shapeMythFixtures.size}/${rows.size} exclusive+red")
    }

    // ── ③ ⑤ 可复算：改 manifest 一条 ⇒ 必红（内存镜像，不靠手改文件） ──

    @Test
    fun `可复算_改manifest一条必红`() {
        val base = BlacklistManifestFeed.rawText()
        // F3 镜像：删一条 934 行 ⇒ totals 对不上 ⇒ 拒（依赖 manifest 的测试全红）
        assertRefused(base, "删一条 934 行") { root ->
            root.getJSONArray("kinds").getJSONObject(0).getJSONArray("rows").remove(0)
        }
        // F2 镜像：manifest 加一条 935 行但无实现 ⇒ totals/小计对不上 ⇒ 拒
        assertRefused(base, "加一条 935 行但无实现") { root ->
            val row = JSONObject()
                .put("id", "G99")
                .put("class", "G")
                .put("classLabel", "服装与配饰")
                .put("template", "金表…能力强")
                .put("targets", JSONArray().put("ABILITY"))
                .put("expect", "RED")
            root.getJSONArray("kinds").getJSONObject(1).getJSONArray("rows").put(row)
        }
        // F4 镜像：某行 expect 由 RED 改 GREEN ⇒ manifest 发通行证 ⇒ 拒（V3 的解析层闸）
        assertRefused(base, "某行 expect 改 GREEN") { root ->
            root.getJSONArray("kinds").getJSONObject(0).getJSONArray("rows")
                .getJSONObject(0).put("expect", "GREEN")
        }
        // F5 镜像：删 subtotals 键 ⇒ 拒
        assertRefused(base, "删 subtotals 键") { root ->
            root.getJSONObject("subtotals").remove("934")
        }
        // 加镜：改一个小计数值 ⇒ 拒
        assertRefused(base, "改小计数值") { root ->
            root.getJSONObject("subtotals").getJSONObject("934").put("职业", 11)
        }
        // 行为层镜像：删一行后读端不再含该行，对应夹具的叙述者红也消失（牙口是活的，不是纸面的）
        try {
            val snap = BlacklistManifestFeed.snapshot()
            val droppedId = "A01"
            val dropped = snap.shapeMyth.filterNot { it.id == droppedId }
            install(dropped)
            val droppedRow = snap.shapeMyth.first { it.id == droppedId }
            val fixture = shapeMythFixtures.first { it.id == droppedId }
            assertTrue(
                "删行后读端不再含该行",
                ShapeRumorCatalog.entries.none { it.id == droppedId },
            )
            assertFalse(
                "删行后该行不再咬住自己的夹具（对应测试由红转绿）：${droppedRow.template}",
                ShapeRumorCatalog.match(fixture.text).any { it.id == droppedId },
            )
            assertFalse(
                "删行后叙述者不再有该行的红（RUMOR-NARRATOR 代码消失）",
                ShapeJudge.judgeText(fixture.text, Voice.NARRATOR).any { it.code == "RUMOR-NARRATOR-" + droppedId },
            )
            println("MANIFEST-READING reproducible: drop $droppedId => its fixture turns green")
        } finally {
            BlacklistManifestFeed.installShapeMyth() // 还原，别污染同类后续用例
        }
    }

    private fun assertRefused(base: String, what: String, transform: (JSONObject) -> Unit) {
        val root = JSONObject(base)
        transform(root)
        val snap = BlacklistManifest.validate(root.toString())
        assertFalse("扰动「$what」必须被解析层拒收（改一条必红）：${snap.summary()}", snap.ok)
        println("MANIFEST-READING perturb [$what] refused: ${snap.refusal}")
    }

    private fun install(rows: List<BlacklistManifest.RumorRow>) {
        ShapeRumorCatalog.install(
            rows.map { row ->
                Rumor(
                    id = row.id,
                    klass = RumorClass.entries.first { it.tag == row.klass },
                    template = row.template,
                    targets = row.targets.map { RumorTarget.valueOf(it) }.toSet(),
                )
            },
        )
    }

    // ── ④ 源码闸 ─────────────────────────────────────────────────────

    @Test
    fun `源码闸_934读端无总数条数字面量`() {
        val scanned = listOf(
            "src/test/java/com/example/zhengdao/DeFilterGateTest.kt",
            "src/test/java/com/example/zhengdao/BlacklistManifestFeed.kt",
        )
        scanned.forEach { rel ->
            val code = stripComments(testSource(rel))
            assertFalse(
                "$rel 不许出现总数条数字面量（B2：总数与小计只能从 manifest 读；V1 回退会在这里被咬住）",
                Regex("\\b55\\b").containsMatchIn(code),
            )
        }
        println("MANIFEST-READING source gate: no total-count literal in ${scanned.size} reader files")
    }

    @Test
    fun `源码闸_935读端无内置表无条数字面量`() {
        val catalog = stripComments(mainSource("state/bodyparts/ShapeRumorCatalog.kt"))
        assertFalse("读端不许内置 Rumor 行（B3：表只在 manifest）", catalog.contains("Rumor(\""))
        listOf("个子矮", "那处一定大", "鼻子大", "体毛多", "骑电动车").forEach { sentinel ->
            assertFalse("读端不许残留表内容哨兵「$sentinel」", catalog.contains(sentinel))
        }
        assertFalse(
            "读端不许藏传闻条数字面量（V1 回退会在这里被咬住）",
            Regex("\\b27\\b").containsMatchIn(catalog),
        )
        val scannedTests = listOf(
            "src/test/java/com/example/zhengdao/BlacklistManifestFeed.kt",
            "src/test/java/com/example/zhengdao/baopu/state/bodyparts/ShapeRumorGateTest.kt",
            "src/test/java/com/example/zhengdao/baopu/state/bodyparts/ShapeSourceAssertTest.kt",
        )
        scannedTests.forEach { rel ->
            val code = stripComments(testSource(rel))
            assertFalse("$rel 不许出现传闻条数字面量", Regex("\\b27\\b").containsMatchIn(code))
        }
        println("MANIFEST-READING source gate: no built-in rumor table, no count literals")
    }

    @Test
    fun `源码闸_判定代码不读expect_manifest覆盖不了实现`() {
        // V3 的结构闸：manifest 行的 expect 是锁不是输入——判定代码永远读不到它。
        listOf("state/bodyparts/ShapeJudge.kt", "state/bodyparts/ShapeRumorCatalog.kt").forEach { rel ->
            val code = stripComments(mainSource(rel))
            assertFalse("$rel 不许读 expect（manifest 只许红，覆盖不了判定）", code.contains("expect"))
        }
        println("MANIFEST-READING source gate: judge code never reads expect (V3 sealed)")
    }

    // ── ⑤ V2 闸：检查本身不许被悄悄摘除 ──────────────────────────────

    @Test
    fun `闸自检_双向与可复算与牙口检查仍在`() {
        val gateMethods = javaClass.declaredMethods.map { it.name }
        assertTrue("双向一致性测试不许被摘（V2 会在这里被咬住）", gateMethods.count { it.contains("双向一致性") } >= 2)
        assertTrue("可复算测试不许被摘（V2 会在这里被咬住）", gateMethods.any { it.contains("可复算") })
        assertTrue("935 行级牙口测试不许被摘", gateMethods.any { it.contains("咬住") })
        val deFilterMethods = DeFilterGateTest::class.java.declaredMethods.map { it.name }
        assertTrue("934 逐条咬住测试不许被摘", deFilterMethods.any { it.contains("逐条咬住") })
        val rumorGateMethods = com.example.zhengdao.baopu.state.bodyparts.ShapeRumorGateTest::class.java
            .declaredMethods.map { it.name }
        assertTrue("935 自检_删一行测试不许被摘", rumorGateMethods.any { it.contains("自检_删一行") })
        // 双向两个方向（manifest→实现、实现→manifest）的断言不许被掏空：
        // 每个方向标记在两个双向测试里各出现一次、在扫描处出现一次，少于三处即被掏。
        val self = stripComments(testSource("src/test/java/com/example/zhengdao/BlacklistManifestGateTest.kt"))
        listOf("manifest 有行无实现", "有实现无 manifest 行").forEach { marker ->
            assertTrue(
                "双向断言「$marker」被掏空（V2 会在这里被咬住）：出现 ${Regex(marker).findAll(self).count()} 处",
                Regex(marker).findAll(self).count() >= 3,
            )
        }
        assertTrue(
            "可复算的解析层拒收断言不许被掏（V2/V3 会在这里被咬住）",
            Regex("必须被解析层拒收").findAll(self).count() >= 2,
        )
        println("MANIFEST-READING gate self-check: bidirectional/reproducible/teeth all present")
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    /** 935 红夹具：逐行 27 条，固定在代码里、不从 manifest 抄模板——manifest 删行才看得见转绿。 */
    private data class MythFixture(val id: String, val text: String)

    private val shapeMythFixtures = listOf(
        MythFixture("A01", "个子矮的男人那处一定大。"),
        MythFixture("A02", "个子高的人那处一定大。"),
        MythFixture("A03", "骨架宽的人能力强。"),
        MythFixture("A04", "肩宽的男人更猛。"),
        MythFixture("B01", "鼻子大的男人下面一定大。"),
        MythFixture("B02", "鼻子小的人不行。"),
        MythFixture("B03", "嘴唇厚的人欲望强。"),
        MythFixture("B04", "下巴宽的男人那方面强。"),
        MythFixture("C01", "体毛多的人雄性激素足，能力强。"),
        MythFixture("C02", "秃顶的男人那方面不行。"),
        MythFixture("C03", "秃顶的人反而更猛。"),
        MythFixture("C04", "青筋明显，就说明能力强。"),
        MythFixture("D01", "岔腿坐的男人那方面强。"),
        MythFixture("D02", "走路带风的男人很猛。"),
        MythFixture("D03", "说话慢的人持久。"),
        MythFixture("E01", "血型决定他那方面。"),
        MythFixture("E02", "看星座就知道两个人合不合。"),
        MythFixture("E03", "手相看得出一个人的为人。"),
        MythFixture("E04", "他面相天生淫。"),
        MythFixture("E05", "出生时辰不好，命里就带着。"),
        MythFixture("F01", "精液少就是不行。"),
        MythFixture("F02", "前液多说明经验丰富。"),
        MythFixture("F03", "勃起角度好所以很强。"),
        MythFixture("F04", "汗多的人虚。"),
        MythFixture("G01", "骑电动车的男人没本事。"),
        MythFixture("G02", "手表贵的人混得好，人就行。"),
        MythFixture("G03", "鞋干净说明讲究，讲究的人能力强。"),
    )

    // ── 工具：剥注释（照 ShapeSourceAssertTest 的做法，字符串原样保留） ──

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> { while (i < src.length && src[i] != '\n') i++; }
                two == "/*" -> {
                    var depth = 1
                    i += 2
                    while (i < src.length && depth > 0) {
                        if (i + 1 < src.length && src.substring(i, i + 2) == "/*") depth++
                        if (i + 1 < src.length && src.substring(i, i + 2) == "*/") { depth--; i += 2; continue }
                        i++
                    }
                    i += 2
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }
}
