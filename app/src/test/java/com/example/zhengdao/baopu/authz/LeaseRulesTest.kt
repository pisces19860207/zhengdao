// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约判定层的逐类判据（拒授 N1–N9 ＋ 类目/范围/计次）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.baopu.escalate.CapabilityKind
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaseRulesTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("baopu467-rules").toFile()
        File(root, "产物").mkdirs()
    }

    @After
    fun tearDown() {
        LeaseStore.resetForTest()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun spec(name: String, group: ToolGroup, readOnly: Boolean = false): ToolSpec = ToolSpec(
        name = name,
        description = "t",
        inputSchema = jsonObjectOf("type" to "object"),
        permission = ToolPermission.CONFIRM,
        group = group,
        readOnly = readOnly,
        executor = { com.example.zhengdao.baopu.agent.ToolOutcome.Ok("ok") },
    )

    /** 一份"什么毛病都没有"的事实：R2、根内、可回滚、目标在产物里。 */
    private fun facts(
        cls: String = "files/delete",
        tool: String = "delete_path",
        risk: RiskClass? = RiskClass.R2,
        groupKey: String = ToolGroup.FILES.key,
        capability: CapabilityKind = CapabilityKind.FILE_WRITE,
        targets: List<File> = listOf(File(root, "产物/a.md")),
    ): LeaseFacts = LeaseFacts(
        cls = cls,
        tool = tool,
        risk = risk,
        unclassified = false,
        mustAsk = false,
        inRoot = true,
        reversible = true,
        groupKey = groupKey,
        capability = capability,
        targetsInRawZone = false,
        targets = targets,
        args = JSONObject(),
        root = root,
    )

    // ── 类目：组 × 动作种类（含"记账口径≠权限口径"那条覆盖）────────────────

    @Test
    fun `类目_写工具落 write`() {
        assertEquals("edit/write", LeaseRules.classOf(spec("write_file", ToolGroup.EDIT)))
    }

    @Test
    fun `类目_convert_encoding 记账口径是读_权限口径改判写`() {
        // FileOpRules.kindOf(convert_encoding) == READ（记账口径），但它会写盘（矩阵 R2）
        assertEquals("files/write", LeaseRules.classOf(spec("convert_encoding", ToolGroup.FILES)))
    }

    @Test
    fun `类目_无文件语义退到能力类`() {
        assertEquals("browser/browser", LeaseRules.classOf(spec("browser_click", ToolGroup.BROWSER)))
        assertEquals("core/read", LeaseRules.classOf(spec("read_file", ToolGroup.CORE, readOnly = true)))
    }

    @Test
    fun `类目_删与移与拷各是一类`() {
        assertEquals("files/delete", LeaseRules.classOf(spec("delete_path", ToolGroup.FILES)))
        assertEquals("files/move", LeaseRules.classOf(spec("move_path", ToolGroup.FILES)))
        assertEquals("files/copy", LeaseRules.classOf(spec("copy_path", ToolGroup.FILES)))
    }

    // ── 拒授：逐类负例（判据「R3/原件区/外发/账号/改制度 一律拒绝授予」）───────

    @Test
    fun `N1_499三条硬线_必问拒授`() {
        assertNotNull(LeaseRules.denyReason(facts().copy(mustAsk = true)))
        assertNotNull(LeaseRules.denyReason(facts().copy(unclassified = true)))
        assertNotNull(LeaseRules.denyReason(facts().copy(risk = RiskClass.R3)))
    }

    @Test
    fun `N1_根外与不可逆拒授_499另两条硬线`() {
        assertNotNull("根外：租约不跨根", LeaseRules.denyReason(facts().copy(inRoot = false)))
        assertNotNull("不可逆：租约不碰", LeaseRules.denyReason(facts().copy(reversible = false)))
    }

    @Test
    fun `N2_R3_拒授且不给报价`() {
        val f = facts(risk = RiskClass.R3)
        assertNotNull(LeaseRules.denyReason(f))
        assertNull(LeaseRules.offerOf(f))
    }

    @Test
    fun `N3_联网取数_拒授`() {
        val f = facts(cls = "websearch/network", tool = "websearch", groupKey = ToolGroup.WEBSEARCH.key)
            .copy(capability = CapabilityKind.NETWORK)
        assertNotNull(LeaseRules.denyReason(f))
    }

    @Test
    fun `N4_改制度_三个组各拒一次`() {
        assertNotNull(LeaseRules.denyReason(facts(groupKey = ToolGroup.SKILL.key)))
        assertNotNull(LeaseRules.denyReason(facts(groupKey = ToolGroup.SCHEDULE.key)))
        assertNotNull(LeaseRules.denyReason(facts(groupKey = ToolGroup.MCP.key)))
    }

    @Test
    fun `N4_改制度_按名点的那几件也要拒`() {
        // write_skill/append_persona 在矩阵里是 R1 —— 只看档位会误开，必须按名/按组拒
        listOf("write_skill", "append_persona", "teach_skill_save", "import_data").forEach { t ->
            assertNotNull("$t 应该拒授", LeaseRules.denyReason(facts(tool = t, groupKey = ToolGroup.EDIT.key)))
        }
    }

    @Test
    fun `N5_原件区_拒授`() {
        assertNotNull(LeaseRules.denyReason(facts().copy(targetsInRawZone = true)))
    }

    @Test
    fun `N6_N7_未分类与表外_拒授`() {
        assertNotNull(LeaseRules.denyReason(facts(risk = null)))
    }

    @Test
    fun `N8_放大类_整目录整包一律不租`() {
        listOf("bulk_copy", "bulk_move", "organize", "archive_extract", "library_organize").forEach { t ->
            assertNotNull("$t 是放大类，不该给租约", LeaseRules.denyReason(facts(tool = t)))
        }
    }

    @Test
    fun `N9_取不到写目标_拒授`() {
        assertNotNull(LeaseRules.denyReason(facts(targets = emptyList())))
    }

    // ── 正例 ＋ 计次上限（定点 D9：单次最多扣 2）────────────────────────────

    @Test
    fun `P1_R2删文件_可授且范围是共同父目录`() {
        val dir = File(root, "产物").apply { mkdirs() }
        val f = facts(targets = listOf(File(dir, "a.md"), File(dir, "b.md")))
        val offer = LeaseRules.offerOf(f)
        assertNotNull(offer)
        assertEquals("files/delete", offer!!.cls)
        assertEquals(LeaseLimits.DEFAULT_CAP, offer.cap)
        assertEquals(LeaseLimits.DEFAULT_TTL_MS, offer.ttlMs)
        assertEquals(LeaseScope.Kind.DIR, offer.scope.kind)
        assertEquals(dir.canonicalFile.path, offer.scope.value)
    }

    @Test
    fun `P1_报价与天花板_默认10次4小时_上限15次12小时`() {
        assertEquals(10, LeaseLimits.DEFAULT.cap)
        assertEquals(4L * 3_600_000L, LeaseLimits.DEFAULT.ttlMs)
        assertEquals(15, LeaseLimits.MAX_CAP)
        assertEquals(12L * 3_600_000L, LeaseLimits.MAX_TTL_MS)
    }

    @Test
    fun `判据③_10个目标只扣2次`() {
        val ten = (1..10).map { File(root, "产物/f$it.md") }
        assertEquals(2, LeaseRules.costOf(ten))
        assertEquals(1, LeaseRules.costOf(ten.take(1)))
        assertEquals(2, LeaseRules.costOf(ten.take(2)))
    }

    @Test
    fun `scope_目录内命中_同前缀兄弟目录不命中`() {
        val dir = File(root, "产物").apply { mkdirs() }
        val sibling = File(root, "产物2").apply { mkdirs() }
        val scope = LeaseScope(LeaseScope.Kind.DIR, dir.canonicalFile.path)
        assertTrue(scope.within(listOf(File(dir, "x.md")), JSONObject()))
        assertTrue("同前缀兄弟不算越界", !scope.within(listOf(File(sibling, "x.md")), JSONObject()))
    }

    @Test
    fun `scope_站点类按 host 严格相等`() {
        val scope = LeaseScope(LeaseScope.Kind.HOST, "example.com")
        assertTrue(scope.within(emptyList(), JSONObject().put("url", "https://example.com/a")))
        assertTrue(!scope.within(emptyList(), JSONObject().put("url", "https://other.com/a")))
    }
}
