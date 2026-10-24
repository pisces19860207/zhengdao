// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 工具集「表＋开关」单测（刀 2 追加包；判据追加③）。
 *
 * 钉死追加包原话级口径：
 * - 一张表加载（spec＋permission＋factory），**引擎不写死工具清单**；
 * - 默认全启用：ENTRIES 二十四个全开（第十七批 update_plan、第三十一批资料库两件套）；
 *   `write_skill` 由 BaopuRuntime 单独注册（要 personaFiles）⇒ DEFAULT_ENABLED 二十四个；
 * - 关掉的工具**不进 tools 数组**（模型看不见）⇒ 判据③「模型看不到该工具」的构造成立；
 * - 被关工具仍是注册形态（同 Schema），模型幻觉调用拿到「该工具已被用户关闭」，
 *   **不自动打开**；
 * - 开关热改（同一注册表实例，改完下一轮请求即生效）。
 *
 * 第五十九批（工具渐进披露）改了本文件三条的**期望值**，没减一条判据：
 * `tools` 数组从「全部启用的」变成「启用的 ∩ 此刻可见的」（默认可见只有 core/edit/compat），
 * 所以「表序即 tools 数组序」现在分两段验（默认段 + 全解锁段）；
 * 权限/Schema 那条改取 [ToolRegistry.all]（真身）而不是 [ToolRegistry.find]（可能是桩）；
 * 开关热改那条换了对象（file_info 已属默认不可见的 files 组）。
 * 新增的组判据本体在 `ProgressiveToolsTest`，这里只留「表/设置页只有一张组表」这条门禁。
 */
class ToolRegistrySwitchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun registry(prefs: ToolPrefs = InMemoryToolPrefs()): ToolRegistry {
        val reg = ToolRegistry(prefs)
        BaopuToolCatalog.loadInto(reg) { root }
        return reg
    }

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    // ── 表 ───────────────────────────────────────────────────────────────

    @Test
    fun `一张表_默认全启用_表序即注册序`() {
        setup()
        val reg = registry()

        // write_skill / append_persona 由 BaopuRuntime 单独注册（要 personaFiles，ENTRIES 的工厂只吃 workspaceRoot）
        assertEquals(BaopuToolCatalog.ENTRIES.size + 2, BaopuToolCatalog.DEFAULT_ENABLED.size)
        assertEquals(
            listOf(
                "read_file", "list_dir", "search_in_files", "find_files", "book_outline",
                "file_info", "write_file", "edit_file", "move_path", "convert_encoding",
                // 小说工程三件（第一百五十五批）：表序即注册序（convert_encoding 之后）
                "novel_create", "novel_project", "novel_continue",
                "browser_open", "browser_snapshot", "browser_click",
                "browser_type", "browser_scroll", "browser_screenshot",
                // 第一百七十七批：坐标四件＋等待（表序即注册序，插在 screenshot 之后）
                "browser_tap", "browser_swipe", "browser_long_press",
                "browser_key", "browser_wait",
                "update_plan",
                "library_list", "library_organize",
                // 第一百二十八批两条：kb_index（建索引，CONFIRM）/ kb_search（检索，FREE 只读）
                "kb_index", "kb_search",
                "delete_path", "image_ops", "generate_image", "ask_user",
                "card_inspect", "card_export", "card_build",
                // 第五十九批两条：capabilities（渐进披露入口）与 preview_local_file（玄览只显示）
                "capabilities", "preview_local_file",
                // 第一百二十七批：多 Agent 协作（AGENT 组，默认不可见但默认启用）
                "run_subtasks",
            ),
            reg.names(),
        )
        BaopuToolCatalog.ENTRIES.forEach { e ->
            assertTrue("${e.name} 默认必须启用", reg.isEnabled(e.name))
        }
        assertEquals(BaopuToolCatalog.ENTRIES.map { it.name }.toSet(), reg.enabledNames().toSet())
    }

    @Test
    fun `表的顺序即tools数组顺序`() {
        setup()
        val reg = registry()
        fun namesOf(arr: org.json.JSONArray) = (0 until arr.length()).map {
            arr.getJSONObject(it).getJSONObject("function").getString("name")
        }
        // 第五十九批改了这条的**期望**，没改它的判据：tools 数组仍由表序产出，
        // 只是默认只含「可见组」那一段（过滤不改序）。
        // 157：期望算法与 isVisibleTool 同源——组级默认可见 ∪ 工具级默认可见名单
        // （library_list / kb_search 在 LIBRARY 组，但只读 ⇒ 进默认面）。
        val defaultVisible = BaopuToolCatalog.ENTRIES.map { it.name }
            .filter {
                ToolGroupTable.GROUP_OF[it] in ToolGroup.DEFAULT_VISIBLE ||
                    it in ToolGroup.DEFAULT_VISIBLE_TOOLS
            }
        assertEquals(defaultVisible, namesOf(reg.toolsJsonOpenAi()))
        // 全解锁（模拟 capabilities 把每一组都开过）⇒ 表序**完整**复现
        reg.currentSessionId = 7L
        reg.unlockStore().unlock(7L, ToolGroup.values().toList())
        assertEquals(BaopuToolCatalog.ENTRIES.map { it.name }, namesOf(reg.toolsJsonOpenAi()))
    }

    @Test
    fun `表里每个工具都有权限与Schema`() {
        setup()
        val reg = registry()
        // 取**真身**（[ToolRegistry.all]）而不是 [ToolRegistry.find]：第五十九批起 find 对
        // 未解锁组的工具回的是桩（权限一律 FREE、执行回拒绝），拿它验权限等于验了桩。
        val real = reg.all().associateBy { it.name }
        BaopuToolCatalog.ENTRIES.forEach { e ->
            val spec = real[e.name]
            assertNotNull("${e.name} 必须注册", spec)
            assertTrue(spec!!.description.isNotBlank())
            assertTrue(spec.inputSchema.has("properties"))
            assertFalse(spec.inputSchema.optBoolean("additionalProperties"))
            // 每条都被那张唯一的表盖了组（§6「孤儿工具直接红」的另一面：不许有 CORE 漏盖的）
            assertEquals(
                "${e.name} 的组必须由 ToolGroupTable 盖章",
                ToolGroupTable.GROUP_OF[e.name],
                spec.group,
            )
        }
        // 七个 CONFIRM：四个改盘上文件 ＋ open/click/type 会改页面状态
        listOf(
            "write_file", "edit_file", "move_path", "convert_encoding",
            "browser_open", "browser_click", "browser_type",
        ).forEach {
            assertEquals("$it 必须 CONFIRM", ToolPermission.CONFIRM, real[it]!!.permission)
        }
        listOf(
            "read_file", "list_dir", "search_in_files", "find_files", "book_outline", "file_info",
            "browser_snapshot", "browser_scroll", "browser_screenshot",
        ).forEach {
            assertEquals(ToolPermission.FREE, real[it]!!.permission)
        }
    }

    // ── 开关：判据③ ──────────────────────────────────────────────────────

    @Test
    fun `关掉write_file_不进tools数组_模型看不见`() {
        setup()
        val prefs = InMemoryToolPrefs()
        val reg = registry(prefs)
        assertTrue(reg.toolsJsonOpenAi().toString().contains("write_file"))

        prefs.setDisabled("write_file", true)

        // OpenAI 与 Anthropic 两个协议侧都不含它
        assertFalse(reg.toolsJsonOpenAi().toString().contains("write_file"))
        assertFalse(reg.toolsJsonAnthropic().toString().contains("write_file"))
        assertFalse(reg.enabledNames().contains("write_file"))
        // 但注册表里还在（幻觉调用要能拿到拒绝，而不是「未知工具」）
        assertNotNull(reg.find("write_file"))
        // 别的工具不受影响
        assertTrue(reg.toolsJsonOpenAi().toString().contains("read_file"))
    }

    @Test
    fun `关掉的工具_执行回已被用户关闭_不自动打开`() {
        setup()
        val prefs = InMemoryToolPrefs()
        val reg = registry(prefs)
        prefs.setDisabled("write_file", true)

        val spec = reg.find("write_file")!!
        // 桩是免确认的（否则模型每调一次就弹一次确认框，反而骚扰）
        assertEquals(ToolPermission.FREE, spec.permission)
        val args = org.json.JSONObject().put("path", "a.txt").put("content", "x")
        val r = spec.executor(args)

        assertTrue("必须回拒绝：$r", r is ToolOutcome.Rejected)
        assertTrue((r as ToolOutcome.Rejected).reason.contains("该工具已被用户关闭"))
        // 不自动打开 ⇒ 文件没被写
        assertFalse(File(root, "a.txt").exists())
        assertFalse(reg.isEnabled("write_file"))
    }

    @Test
    fun `桩的Schema与真身一致_幻觉调用先过SchemaCheck`() {
        setup()
        val prefs = InMemoryToolPrefs()
        val reg = registry(prefs)
        val realSchema = reg.find("write_file")!!.inputSchema.toString()
        prefs.setDisabled("write_file", true)

        val stub = reg.find("write_file")!!
        assertEquals(realSchema, stub.inputSchema.toString())
        // 少参数照样被 SchemaCheck 拦（桩不是"什么都能过"）
        val err = reg.validateCall("write_file", org.json.JSONObject())
        assertNotNull("缺参数必须被 Schema 拦下", err)
    }

    @Test
    fun `开关热改_同一实例下一轮即生效`() {
        setup()
        val prefs = InMemoryToolPrefs()
        val reg = registry(prefs)

        // 第五十九批把 file_info 挪进了默认不可见的 files 组（它不再是"模型一定看得见"的），
        // 所以这一条改用默认可见的 search_in_files 当对象——它验的是**单工具开关热改**，
        // 与组可见性是两件事，别把它们搅在一起。
        assertTrue(reg.isEnabled("search_in_files"))
        reg.setDisabled("search_in_files", true)
        assertFalse(reg.isEnabled("search_in_files"))
        assertFalse(reg.toolsJsonOpenAi().toString().contains("search_in_files"))

        reg.setDisabled("search_in_files", false) // 再打开
        assertTrue(reg.isEnabled("search_in_files"))
        assertTrue(reg.toolsJsonOpenAi().toString().contains("search_in_files"))
    }

    @Test
    fun `全关时tools数组为空`() {
        setup()
        val prefs = InMemoryToolPrefs(BaopuToolCatalog.DEFAULT_ENABLED)
        val reg = registry(prefs)
        assertEquals(0, reg.toolsJsonOpenAi().length())
        assertEquals(0, reg.toolsJsonAnthropic().length())
        // 注册表仍有全部条目（都能拿到「已被关闭」那条拒绝，而不是"未知工具"）
        assertEquals(BaopuToolCatalog.ENTRIES.size, reg.names().size)
    }

    @Test
    fun `未知工具_校验报错并列出已注册`() {
        setup()
        val reg = registry()
        val err = reg.validateCall("delete_everything", org.json.JSONObject())
        assertNotNull(err)
        assertTrue(err!!.contains("未知工具"))
        assertTrue(err.contains("read_file"))
    }

    @Test
    fun `内存prefs_增删查`() {
        val prefs = InMemoryToolPrefs()
        assertFalse(prefs.isDisabled("a"))
        prefs.setDisabled("a", true)
        assertTrue(prefs.isDisabled("a"))
        assertEquals(setOf("a"), prefs.disabledNames())
        prefs.setDisabled("a", false)
        assertFalse(prefs.isDisabled("a"))
        assertTrue(prefs.disabledNames().isEmpty())
    }

    @Test
    fun `删除类工具只许delete_path一个_且只删文件不递归`() {
        // 🛑 口径变更（2026-10-18 用户逐字「删除工具没加啊？」）：
        // 刀 2 时代写的是「工具集没有任何删除能力」，那是因为当时连确认卡都没做稳；
        // 现在用户明确要抱朴能删（唯一红线是 资料库/原始/，而它本来就在工作区外）。
        // 所以这条门禁反过来钉**收口**：删除类工具只许这一个，且必须满足三条硬约束。
        val deleteish = BaopuToolCatalog.ENTRIES.filter {
            it.name.contains("delete") || it.name.contains("remove")
        }.map { it.name }
        assertEquals("删除类工具只许 delete_path 一个：$deleteish", listOf("delete_path"), deleteish)

        // 硬约束一：必须是 CONFIRM（每次一张确认卡，不许静默动手）
        val spec = BaopuToolCatalog.ENTRIES.first { it.name == "delete_path" }.factory { File(".") }
        assertEquals("delete_path 必须 CONFIRM", ToolPermission.CONFIRM, spec.permission)

        // 硬约束二：只删单个文件——源码里不许出现递归删除
        val src = File("src/main/java/com/example/zhengdao/baopu/agent/tools/DeletePathTool.kt")
            .let { if (it.isFile) it else File("app/$it") }
            .readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
        listOf("deleteRecursively", "deleteOnExit").forEach { dangerous ->
            assertFalse("delete_path 不许出现 $dangerous（只删单个文件）", src.contains(dangerous))
        }

        // 硬约束三：默认进回收站（能反悔），`permanent` 才真删
        assertTrue("delete_path 必须支持默认进回收站", src.contains("TRASH_DIR"))
        assertTrue("默认值必须是「不彻底删」", src.contains("optBoolean(ARG_PERMANENT, false)"))
    }
    @Test
    fun `分组只有一张表_设置页从它读_文案两种语言都齐`() {
        // 判据 D（2026-10-19「落 A」的形态，第五十九批 §1.1/§1.6 换了钉法）。
        // 以前这条钉的是"设置页那张展示用组表别漏工具"；本批派工单把口径抬成
        // **「分组表只有一份，放 ToolRegistry」**——展示层再留一份组表就是第二份，
        // 所以这里改成钉「第二份不许存在 + 成员一律从 ToolGroupTable 读」。
        val screen = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
            .let { if (it.isFile) it else File("app/$it") }
            .readText()
        // 结构判据看的是**去掉注释与块注释后**的代码：本批在注释里逐字写了"为什么删掉
        // BaopuToolGroups 那份第二张表"（§4 打回第一条要求把接线讲明），一条诚实的注释
        // 不该把门禁判红——判红的那条针脚留着，但它钉的是代码，不是散文。
        val screenCode = screen
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

        // 一、第二张组表必须没有（§1.1 原话级：UI 从那张唯一的表读）
        assertFalse("设置页不许再有第二张分组表（BaopuToolGroups 已删）", screenCode.contains("BaopuToolGroups"))
        assertTrue(
            "设置页的组成员必须从 ToolGroupTable 读（namesOf），不是自己列名单",
            Regex("ToolGroupTable\\.namesOf").containsMatchIn(screenCode),
        )

        // 二、设置页不许再装配注册表。装配会把全局 CapabilitiesTool.sink 抢到自己身上，
        // 而那张临时注册表的 currentSessionId 还是 NO_SESSION(-1) ⇒ 模型 capabilities
        // 解锁写进 -1 那一格，真实会话永远读不到（§1.4 会话级持久就废了）。
        assertFalse(
            "设置页只读开关存储（BaopuRuntime.toolPrefs），不装配注册表",
            screenCode.contains("BaopuRuntime.registry("),
        )
        assertTrue("设置页必须读那份开关存储", screenCode.contains("BaopuRuntime.toolPrefs("))

        // 三、每个会注册进去的工具都必须有组（孤儿直接红，§6）
        assertNull("分组表覆盖率自检必须过：${ToolGroupTable.checkCoverage()}", ToolGroupTable.checkCoverage())
        BaopuToolCatalog.ENTRIES.forEach { e ->
            val g = ToolGroupTable.GROUP_OF[e.name]
            assertNotNull("孤儿工具（分组表里没有 ${e.name}）", g)
            // ENTRIES 里的工具都要有开关 ⇒ 不许落在"由通道决定、没有开关"的 COMPAT 组
            assertFalse(
                "${e.name} 不该落在没有开关的 COMPAT 组（设置页就不可能给它开关）",
                g == ToolGroup.COMPAT,
            )
        }

        // 四、组标题 + 两档默认可见提示：中英都要有
        // 141 批：工具区改成摘要卡后，`baopu_tools_progressive_note`（渐进披露那段长说明）
        // 与新增的摘要卡五个键一起换位 —— 这里只管「组标题 / 组状态」这批仍活着的键。
        // 第一百五十八批：生图字符串单独成文件（strings_baopu_image.xml）；连同目录内其余 strings*.xml 一起读。
        fun allStrings(path: String): String {
            val f = File(path).let { if (it.isFile) it else File("app/$it") }
            val extra = f.parentFile?.listFiles { x -> x.name.startsWith("strings") && x.name.endsWith(".xml") }
                .orEmpty().filter { it != f }
            return f.readText() + extra.joinToString("") { it.readText() }
        }
        val zh = allStrings("src/main/res/values/strings.xml")
        val en = allStrings("src/main/res/values-en/strings.xml")
        val keys = ToolGroup.values().filter { it != ToolGroup.COMPAT }
            .map { "baopu_tools_group_${it.key}" } +
            listOf(
                "baopu_tools_group_default_on", "baopu_tools_group_default_off",
                "baopu_tools_summary", "baopu_tools_row_pending", "baopu_tools_row_closed",
                "baopu_tools_row_partial", "baopu_tools_rows_empty",
            )
        keys.forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }
}