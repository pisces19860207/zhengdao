// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十一批（2026-11-09）· 开发核对包的判据测试。
// 对应派工单 561 判据：③五节齐全 ④隐私白名单（负例在 JVM 侧先钉"白名单真的白"，
// 真机侧另有"会话原文不进包"的行为负例）⑤点号开头不计产物数＋重复导出不覆盖旧文件
// ②md ≤ 200 行（灰度行封顶 ⇒ 行数是确定性质，不是"这次碰巧没超"）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.ReflectionRunner.Companion.isCountedProduct
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildAuditPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一条开关表（默认开、现值可挑）。 */
    private fun switches(first: Boolean, second: Boolean) = listOf(
        BuildAuditPack.SwitchLine("zhengdao-scaffold/history-prefilter", "历史预过滤", true, first),
        BuildAuditPack.SwitchLine("zhengdao-scaffold/compression-prompt", "压卡摘要", true, second),
    )

    /** 造一把 527 出口 JSON：白名单四键之外再塞一段"用户原文"当诱饵。 */
    private fun governanceJsonWith(marker: String): String = JSONObject()
        .put("v", 2)
        .put("day", "2026-11-09")
        .put("ratios", JSONObject().put("violation", "no_sample"))
        .put("cards", JSONObject().put("pass", 3))
        .put("gray", JSONObject().put("orchestration", "off"))
        .put("goal", JSONObject().put("value", "目标：$marker"))
        .put("openLoops", JSONArray().put(JSONObject().put("text", "承诺：$marker")))
        .put("identity", JSONObject().put("id8", "abcdef12"))
        .toString()

    private fun packOf(governance: String?): Pair<JSONObject, String> {
        val json = BuildAuditPack.buildJson(switches(true, true), "SIGNER", "APK", BuildAuditPack.readingsSection(governance))
        return JSONObject(json) to BuildAuditPack.buildMd(json, switches(true, true))
    }

    // ── 判据③：五节齐全 ─────────────────────────────────────────────────────

    @Test
    fun `五节齐全_build_switches_ablation_deadCandidates_readings`() {
        val (root, _) = packOf(governanceJsonWith("诱饵"))
        BuildAuditPack.SECTIONS.forEach { section ->
            assertTrue("五节缺了 $section", root.has(section))
        }
        assertEquals(5, BuildAuditPack.SECTIONS.size)
        // build 节的字段（派工单：version/gitSha/buildTime/abi/签名指纹 ＋ apkSha256）
        val build = root.getJSONObject("build")
        listOf("versionName", "versionCode", "gitSha", "buildTime", "abi", "signerSha256", "apkSha256")
            .forEach { key -> assertTrue("build 节缺 $key", build.has(key)) }
    }

    // ── 判据④：隐私白名单（JVM 侧负例）─────────────────────────────────────

    @Test
    fun `白名单外的段一概不进包_用户原文不出现`() {
        val marker = "紫金葫芦96号密语"
        val (root, md) = packOf(governanceJsonWith(marker))
        val jsonAll = root.toString()
        // 诱饵在 527 出口里确实存在（说明诱饵造对了）
        assertTrue(governanceJsonWith(marker).contains(marker))
        // 但核对包（json 与 md）里一个字都不许有
        listOf(jsonAll, md).forEach { body ->
            assertFalse("核对包泄漏了用户原文", body.contains(marker))
        }
        // readings 只许白名单四键：goal/openLoops/identity/conflicts 整段不进来
        // （org.json 的键序不稳定，按集合比，不按顺序比）
        val readings = root.getJSONObject("readings")
        val keys = readings.keys().asSequence().toSet()
        assertEquals(setOf("day", "ratios", "cards", "gray"), keys)
    }

    @Test
    fun `读不到出口_如实落unknown_不冒充0`() {
        val (root, _) = packOf(null)
        assertTrue(root.getJSONObject("readings").has("unknown"))
        // 入参是坏 JSON ⇒ 同一句"读不到"（parse 兜住，不抛）
        val (root2, _) = packOf("{oops")
        assertTrue(root2.getJSONObject("readings").has("unknown"))
    }

    // ── 判据③/559：未建立就是未建立，不编数 ────────────────────────────────

    @Test
    fun `登记表未建立_ablation写未建立_可删候选为空`() {
        if (BuildAuditPack.registryEstablished()) {
            // 559 落地翻真后，这条负例随那一批重写——现在锁的是"未建立"形态
            return
        }
        val (root, md) = packOf(governanceJsonWith("诱饵"))
        assertEquals("未建立", root.getJSONObject("ablation").getString("status"))
        assertEquals(0, root.getJSONObject("deadCandidates").getJSONArray("entries").length())
        assertEquals(0, BuildAuditPack.deadCandidateCount())
        assertTrue(md.contains("未建立"))
    }

    // ── 摘要一行（派工单定式）───────────────────────────────────────────────

    @Test
    fun `摘要一行_开关几关几开_可删候选数`() {
        assertEquals(
            "构建 a1b2c3d4 · 开关 0 关 / 2 开 · 可删候选 0",
            BuildAuditPack.summaryLine("a1b2c3d4", switches(true, true), 0),
        )
        assertEquals(
            "构建 a1b2c3d4-dirty · 开关 1 关 / 2 开 · 可删候选 2",
            BuildAuditPack.summaryLine("a1b2c3d4-dirty", switches(true, false), 2),
        )
    }

    // ── 判据⑤：文件名点号开头；重复导出不覆盖旧文件 ─────────────────────────

    @Test
    fun `文件名点号开头_且不计入产物数_与512同一把尺`() {
        val stamp = BuildAuditPack.stampFor(1731123000000L)
        assertTrue(stamp.matches(Regex("\\d{8}-\\d{4}")))
        // isCountedProduct 先看 isFile ⇒ 造真文件再断言（不然两边都 false，测了个寂寞）
        val dir = tmp.newFolder("products")
        val dotJson = File(dir, ".zd-audit-$stamp.json").apply { writeText("{}") }
        val dotMd = File(dir, ".zd-audit-$stamp.md").apply { writeText("# x") }
        val dotSeq = File(dir, ".zd-audit-$stamp-2.json").apply { writeText("{}") }
        listOf(dotJson, dotMd, dotSeq).forEach { f ->
            assertFalse("点号件 ${f.name} 不许计入产物数", isCountedProduct(f))
        }
        // 反向锚：真产物仍计入（尺子没坏）
        val normal = File(dir, "20261109_一件真产物.md").apply { writeText("x") }
        assertTrue(isCountedProduct(normal))
    }

    @Test
    fun `重复导出不覆盖旧文件_同号加序号且json与md成对`() {
        val dir = tmp.newFolder("products")
        assertEquals(".zd-audit-20261109-1030", BuildAuditPack.nextBase(dir, "20261109-1030"))
        // 第一对已存在 ⇒ 换 -2；两件里只有一件在也不行（json/md 必须同号成对）
        File(dir, ".zd-audit-20261109-1030.json").writeText("old")
        File(dir, ".zd-audit-20261109-1030.md").writeText("old")
        assertEquals(".zd-audit-20261109-1030-2", BuildAuditPack.nextBase(dir, "20261109-1030"))
        File(dir, ".zd-audit-20261109-1030-2.md").writeText("orphan")
        assertEquals(".zd-audit-20261109-1030-3", BuildAuditPack.nextBase(dir, "20261109-1030"))
    }

    @Test
    fun `exportFiles落两件_第二把不碰第一把`() {
        val wsRoot = tmp.newFolder("ws")
        val pack = BuildAuditPack.Pack(json = "{\"v\":1}", md = "# 旧\n", summary = "s", mdLines = 2)
        val (json1, md1) = BuildAuditPack.exportFiles(wsRoot, pack, 1731123000000L)
        assertTrue(json1.name.startsWith(".zd-audit-") && json1.name.endsWith(".json"))
        assertTrue(md1.name == json1.name.removeSuffix(".json") + ".md")
        val (json2, _) = BuildAuditPack.exportFiles(wsRoot, pack, 1731123000000L)
        assertEquals("-2", json2.name.removeSuffix(".json").takeLast(2))
        assertEquals("{\"v\":1}", json1.readText()) // 旧文件一字未动
        assertEquals("# 旧\n", md1.readText())
    }

    // ── 判据②：md 行数有确定上界（≤200 行），灰度超了封顶而不是涨行 ──────────

    @Test
    fun `md不超200行_灰度再多也封顶`() {
        val bigGray = JSONObject()
        repeat(120) { i -> bigGray.put("capability_$i", "off") }
        val governance = JSONObject()
            .put("day", "2026-11-09")
            .put("ratios", JSONObject().put("violation", "no_sample"))
            .put("cards", JSONObject())
            .put("gray", bigGray)
            .toString()
        val (root, md) = packOf(governance)
        val lines = md.count { it == '\n' } + 1
        assertTrue("md 行数 $lines 超 200（判据②）", lines <= 200)
        // 封顶要"说出来"：不能悄悄截
        assertTrue(md.contains("全量在同名 .json"))
        assertEquals(120, root.getJSONObject("readings").getJSONObject("gray").length())
    }

    // ── apkSha256 的算子（核对包与 CI 边车同一算法）──────────────────────────

    @Test
    fun `sha256对得上标准向量`() {
        val empty = File(tmp.root, "empty.bin")
        empty.writeBytes(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BuildAuditPack.sha256Hex(empty),
        )
        val abc = File(tmp.root, "abc.bin")
        abc.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BuildAuditPack.sha256Hex(abc),
        )
    }
}
