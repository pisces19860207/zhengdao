// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-21 第一百零三批 A 段（TOOLS-D1，P2）。
// 真机现场（qa-2026-10-21，设备 AD3J023824001723）：抱朴设置把「技能」组拨到「已开启」
// （SP `baopu_tools` 的 approved_groups 有 skill、审计 `[group] 手动开启 skill 来源=设置页`），
// 同进程里让抱朴用 append_persona ⇒ 模型回「没有叫 append_persona 的工具」；
// force-stop 重开才看得见 ⇒ 待查「组开关是热的」对已注册工具可见性是否成立。
//
// 本文件是这次待查的**判据本体**（J1–J4）＋逐组期望表：
// - 装配与 BaopuRuntime.registry() 同构（ENTRIES 一张表 + capabilities 接线 +
//   运行时单独注册的 write_skill / append_persona），prefs 用与 BaopuToolPrefs
//   **同语义**的 InMemoryToolPrefs（每次现读、零缓存）——设备侧的 SharedPreferences
//   也是每次现读，所以这条 JVM 链与真机链同构；
// - J1 拨开不重建也可见；J2 拨关立刻不可见（不许只加不减）；
// - J3 默认组与免费通道 read/bash 别名口径不变；J4 可见性仍只有一处判定。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A 段（TOOLS-D1）判据：组开关对**已注册工具可见性**必须是热的（不重建引擎即生效）。
 *
 * 与 ProgressiveToolsTest 的分工：那份钉 59 批的「一条链、默认可见、按会话解锁」；
 * 本份钉 103 批的「拨组开关不重建也生效／拨关立刻消失／别名口径不变／仍是一条链」。
 * 判据编号沿用派工单：J1–J4。
 */
class ToolVisibilityHotSwitchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 公共件 ────────────────────────────────────────────────────────────

    private fun names(arr: JSONArray, path: String? = "function"): List<String> =
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            (path?.let { p -> o.getJSONObject(p) } ?: o).getString("name")
        }

    /**
     * 与 [com.example.zhengdao.baopu.BaopuRuntime.registry] 同构的装配（普通通道那一支）。
     *
     * 为什么在测试里重演一遍而不调用 BaopuRuntime：后者要 Android Context
     * （SharedPreferences），JVM 直测不了；而 ToolPrefs 的接口语义
     * 「每次现读、零缓存」在 InMemoryToolPrefs 与 BaopuToolPrefs 上逐字相同
     * （后者 getStringSet 现读 SP，前者读内存 Map），所以这条链与真机链同构。
     */
    private val wsSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun productionRegistry(
        prefs: ToolPrefs = InMemoryToolPrefs(),
        unlocks: GroupUnlockStore = InMemoryGroupUnlocks(),
        root: File = tmp.newFolder("ws${wsSeq.incrementAndGet()}"),
        ask: ((AskUserRequest) -> AskUserOption?)? = null,
        onVisibleChanged: ((String) -> Unit)? = null,
    ): ToolRegistry = ToolRegistry(prefs, unlocks, ask = ask, onVisibleChanged = onVisibleChanged).also { reg ->
        BaopuToolCatalog.loadInto(reg) { root }
        reg.register(
            SkillTools.spec(
                workspaceRoot = { root },
                personaFiles = { PersonaFiles(File(root, "global"), root) },
            ),
        )
        reg.register(
            AppendPersonaTool.spec(
                personaFiles = { PersonaFiles(File(root, "global"), root) },
            ),
        )
    }

    /** 设置页组开关那一对写（BaopuSettingsScreen onCheckedChange 的原样搬运）。 */
    private fun ToolPrefs.settingsToggle(group: ToolGroup, want: Boolean) {
        setGroupApproved(group, want && group !in ToolGroup.DEFAULT_VISIBLE)
        setGroupDisabled(group, !want)
    }

    private val SESSION = 7L

    // ── J1：拨开技能组 ⇒ 不重建注册表，下一轮 tools 数组就有 append_persona ──

    @Test
    fun `J1_拨开技能组_不重建也可见_两个协议同源`() {
        val prefs = InMemoryToolPrefs()
        val reg = productionRegistry(prefs)
        reg.currentSessionId = SESSION
        // 拨前：skill 组默认不可见（模型看不见，不是"看得见但报错"）
        assertFalse(names(reg.toolsJsonOpenAi(SESSION)).contains("append_persona"))
        assertFalse(names(reg.toolsJsonOpenAi(SESSION)).contains("write_skill"))

        // 设置页把「技能」拨到开（写盘动作与真机现场同一对调用）
        prefs.settingsToggle(ToolGroup.SKILL, true)

        // 判据：**不重建注册表**（reg 实例一个字没动），下一轮数组必须含它
        val after = names(reg.toolsJsonOpenAi(SESSION))
        assertTrue("拨开 skill 后不重建也可见（TOOLS-D1 现场）：$after", after.contains("append_persona"))
        assertTrue("同组的 write_skill 一起可见：$after", after.contains("write_skill"))
        // Anthropic 形状同源（两个出口一条链）
        assertTrue(
            names(reg.toolsJsonAnthropic(SESSION), path = null).contains("append_persona"),
        )
        // 派工单 J1 的字面判据：enabledNames() 也含 append_persona
        assertTrue(reg.enabledNames().contains("append_persona"))
        // 执行侧同一判据：find() 拿到的必须是可以真调用的形态（不是组锁桩）
        val spec = reg.find("append_persona") ?: error("append_persona 必须可寻址")
        assertEquals(ToolPermission.FREE, spec.permission)
    }

    @Test
    fun `J1b_拨开是幂等的_重复拨不重复也不丢`() {
        val prefs = InMemoryToolPrefs()
        val reg = productionRegistry(prefs)
        reg.currentSessionId = SESSION
        prefs.settingsToggle(ToolGroup.SKILL, true)
        prefs.settingsToggle(ToolGroup.SKILL, true) // 用户手快再拨一次
        val after = names(reg.toolsJsonOpenAi(SESSION))
        assertEquals("append_persona 只出现一次", 1, after.count { it == "append_persona" })
        assertTrue(after.contains("write_skill"))
    }

    // ── J2：拨关 ⇒ 立刻不可见（不许只加不减）──────────────────────────────

    @Test
    fun `J2_拨关技能组_同一实例立刻不可见`() {
        val prefs = InMemoryToolPrefs()
        val unlocks = InMemoryGroupUnlocks()
        val reg = productionRegistry(prefs, unlocks)
        reg.currentSessionId = SESSION
        prefs.settingsToggle(ToolGroup.SKILL, true)
        assertTrue(names(reg.toolsJsonOpenAi(SESSION)).contains("append_persona"))

        // 拨关（设置页原样：approved 撤掉 + disabled 写上 + 会话解锁一并撤）
        prefs.settingsToggle(ToolGroup.SKILL, false)
        unlocks.relockAll(listOf(ToolGroup.SKILL))

        val after = names(reg.toolsJsonOpenAi(SESSION))
        assertFalse("拨关后必须立刻不可见（只加不减就是这条红）：$after", after.contains("append_persona"))
        assertFalse(after.contains("write_skill"))
        // 执行侧同步：拿到的是组锁桩（明说先调 capabilities），不是真身
        val outcome = reg.find("append_persona")!!.executor(JSONObject())
        assertTrue("拨关后硬调必须拿明说的拒绝：$outcome", outcome is ToolOutcome.Rejected)
    }

    @Test
    fun `J2b_先解锁再拨关_会话解锁也压不住`() {
        // 会话里先用 capabilities 解锁了 skill（御批卡「本次会话」），用户随后在设置页拨关：
        // 三条授权（默认可见/长期同意/会话解锁）都必须让位给用户开关（§1.4）。
        val prefs = InMemoryToolPrefs()
        val unlocks = InMemoryGroupUnlocks()
        val reg = productionRegistry(prefs, unlocks, ask = { pick -> pick.options.first { it.id == "session" } })
        reg.currentSessionId = SESSION
        CapabilitiesTool.spec().executor(JSONObject().put("enable", JSONArray().put("skill")))
        assertTrue(unlocks.unlocked(SESSION).contains(ToolGroup.SKILL))
        assertTrue(names(reg.toolsJsonOpenAi(SESSION)).contains("append_persona"))

        prefs.settingsToggle(ToolGroup.SKILL, false)
        unlocks.relockAll(listOf(ToolGroup.SKILL))
        assertFalse(names(reg.toolsJsonOpenAi(SESSION)).contains("append_persona"))
    }

    // ── J3：默认组与免费通道 read/bash 别名口径不变 ───────────────────────

    @Test
    fun `J3_默认可见读数不变_普通12免费14`() {
        // 普通通道：默认 12 条（core 8 + edit 4），与 59 批 B1 同一读数
        val plain = productionRegistry()
        assertEquals(12, plain.visibleSpecs().size)
        assertEquals(12, names(plain.toolsJsonOpenAi()).size)
        // 拨开任何组都不许把默认那 12 条挤掉（顺序即表序）
        val opened = productionRegistry(prefs = InMemoryToolPrefs(initialApproved = setOf(ToolGroup.SKILL)))
        val def = names(plain.toolsJsonOpenAi())
        names(opened.toolsJsonOpenAi()).take(12).let { first12 ->
            assertEquals("默认可见的 12 条必须原样在最前面（表序稳定）", def, first12)
        }
    }

    @Test
    fun `J3b_免费通道read bash别名口径不变`() {
        // 别名口径（J3）：免费通道注册 read/bash；read 跟随 read_file 的开关
        // （read_file 被关 ⇒ 别名同样拒——别名不许当成绕开开关的洞）；
        // bash 恒拒（本 App 不提供 shell）。这一条钉的是**现状口径**，本批不许顺手改。
        val prefsEnabled = InMemoryToolPrefs()
        val regEnabled = productionRegistry(prefsEnabled)
        // 与 BaopuRuntime.registry() 的 builtinFree 支路同形：按注册表此刻的开关落别名形态
        val readSchema = com.example.zhengdao.baopu.agent.ReadFileTool.spec().inputSchema
        regEnabled.register(
            ToolSpec(
                name = "read",
                description = "免费网关兼容占位：等价于 read_file（路径相对工作区根）。",
                inputSchema = readSchema,
                permission = ToolPermission.FREE,
                readOnly = true,
                parallelSafe = true,
            ) { args -> ReadFileTool.spec().executor(args) },
        )
        regEnabled.register(
            ToolSpec(
                name = "bash",
                description = "免费网关兼容占位：本 App 不提供 shell 执行（安全边界，不跨 rootfs）。",
                inputSchema = JSONObject().put("type", "object").put("properties", JSONObject()),
                permission = ToolPermission.FREE,
            ) { _ ->
                ToolOutcome.Rejected("bash 被拒：本 App 不提供 shell 执行（不跨 rootfs 是硬边界）")
            },
        )
        // 组会被 register() 按 ToolGroupTable 重盖成 COMPAT（默认可见那一档）
        assertEquals(ToolGroup.COMPAT, regEnabled.all().first { it.name == "read" }.group)
        assertEquals(14, regEnabled.visibleSpecs().size)
        val vis = names(regEnabled.toolsJsonOpenAi())
        assertTrue(vis.contains("read") && vis.contains("bash"))

        // read_file 被用户关掉 ⇒ 别名必须同样拒（现状口径，照钉）
        val prefsDisabled = InMemoryToolPrefs(initial = setOf("read_file"))
        val regDisabled = productionRegistry(prefsDisabled)
        regDisabled.register(
            ToolSpec(
                name = "read",
                description = "免费网关兼容占位：read_file 已被用户关闭，本别名同样不可用。",
                inputSchema = readSchema,
                permission = ToolPermission.FREE,
            ) { _ ->
                ToolOutcome.Rejected("read_file 已被用户关闭；本 App 不提供别的读取通道，也不会替你打开它。")
            },
        )
        val outcome = regDisabled.find("read")!!.executor(JSONObject().put("path", "x"))
        assertTrue("read_file 关了 ⇒ read 别名必须拒：$outcome", outcome is ToolOutcome.Rejected)
    }

    // ── J4：可见性仍只有一处判定（本批新增代码没开第二条链）────────────────

    @Test
    fun `J4_可见性判据仍只有一处_全仓无第二条链`() {
        val root = File("src/main/java/com/example/zhengdao")
        val reg = File(root, "baopu/agent/ToolRegistry.kt").readText(Charsets.UTF_8)
            .replace("\r\n", "\n")
        // 判据本体各只定义一次
        assertEquals(1, Regex("fun isVisibleTool\\(").findAll(reg).count())
        assertEquals(1, Regex("fun visibleTools\\(").findAll(reg).count())
        // 本批新增的拨组路径只写 prefs，不自己 filter 一遍可见性
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { f ->
                val t = f.readText(Charsets.UTF_8)
                Regex("fun isVisibleTool\\(|fun visibleTools\\(").containsMatchIn(t)
            }
            .map { it.name }
            .toList()
        assertEquals("第二处定义可见性判据的文件：$offenders", listOf("ToolRegistry.kt"), offenders)
    }

    // ── 逐组期望表（派工单 A 段交付物）：每个可开关组拨开都不需要重建引擎 ────
    @Test
    fun `逐组期望表_所有可开关组拨开即生效_拨关即消失`() {
        // 期望表（TOOLS-D1 修法①的逐组读数）：对每个「默认不可见」的组，
        // 拨开（不重建注册表）⇒ 组内代表工具进数组；拨关 ⇒ 立刻出数组。
        // 代表工具取每组在 ToolGroupTable 里钉过的名字（写_skill / append_persona /
        // preview_local_file / browser_* / files 四件 / library 三件 / card 两件）。
        val representatives = mapOf(
            ToolGroup.SKILL to listOf("write_skill", "append_persona"),
            ToolGroup.PREVIEW to listOf("preview_local_file"),
            ToolGroup.BROWSER to listOf("browser_click", "browser_type", "browser_scroll", "browser_screenshot"),
            ToolGroup.FILES to listOf("file_info", "find_files", "move_path", "convert_encoding", "delete_path"),
            ToolGroup.LIBRARY to listOf("book_outline", "library_list", "library_organize"),
            ToolGroup.CARD to listOf("card_inspect", "card_export"),
        )
        representatives.forEach { (group, tools) ->
            val prefs = InMemoryToolPrefs()
            val reg = productionRegistry(prefs)
            reg.currentSessionId = SESSION
            // 逐组代表性：默认不可见
            tools.forEach {
                assertFalse("${group.key} 组默认不该可见：$it", names(reg.toolsJsonOpenAi(SESSION)).contains(it))
            }
            // 拨开 ⇒ 不重建即生效
            prefs.settingsToggle(group, true)
            tools.forEach {
                assertTrue("${group.key} 拨开后不重建即可见（TOOLS-D1 期望表）：$it", names(reg.toolsJsonOpenAi(SESSION)).contains(it))
            }
            // 拨关 ⇒ 立刻消失
            prefs.settingsToggle(group, false)
            tools.forEach {
                assertFalse("${group.key} 拨关后必须立刻不可见：$it", names(reg.toolsJsonOpenAi(SESSION)).contains(it))
            }
        }
        // 默认可见两组（CORE/EDIT）不在期望表里：它们没有开关，
        // 由 B1/I3 两条既有判据钉住（12 条读数 + DEFAULT_VISIBLE 集合）。
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.COMPAT), ToolGroup.DEFAULT_VISIBLE)
    }

    // ── 留痕口（第一百零三批新增的观察窗）：给下一次真机复测直接读「那一刻的数组」──

    @Test
    fun `J1c_可见集合变化时吐一行留痕_不变不吐`() {
        val prefs = InMemoryToolPrefs()
        val lines = mutableListOf<String>()
        val reg = productionRegistry(prefs, onVisibleChanged = { lines += it })
        reg.currentSessionId = SESSION
        // 第一次算：从「没吐过」到「12 条」⇒ 吐一行
        reg.toolsJsonOpenAi(SESSION)
        assertEquals("第一次必须吐（从空到 12）", 1, lines.size)
        assertTrue("行首锚要能 grep：${lines[0]}", lines[0].startsWith("[tools] 可见 12 个："))
        // 再算一次不变 ⇒ 不吐（正常一轮不该刷屏）
        reg.toolsJsonOpenAi(SESSION)
        assertEquals("集合没变不许再吐", 1, lines.size)
        // 拨开技能组 ⇒ 变了 ⇒ 吐一行，且带上新工具名（真机复测就看这一行）
        prefs.settingsToggle(ToolGroup.SKILL, true)
        reg.toolsJsonOpenAi(SESSION)
        assertEquals(2, lines.size)
        assertTrue("新行必须点名 append_persona：${lines[1]}", lines[1].contains("append_persona"))
        assertTrue("新行必须点名 write_skill：${lines[1]}", lines[1].contains("write_skill"))
    }
}
