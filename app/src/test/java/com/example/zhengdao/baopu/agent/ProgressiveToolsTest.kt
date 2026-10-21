// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §2（验收判据）与 §6（工具备齐）。
// 这一份是**渐进披露的判据本体**：§2 明写「纯 visibleTools(catalog, settings, unlockedGroups)
// 的 JVM 直测，三组用例（默认 / 解锁 / 用户关闭）」，所以这里绝大多数用例不打 Android、
// 不起 Activity、不连模型——注册表与判据都是零 Android 依赖的纯逻辑。
//
// 与既有测试的分工（不重复钉、也不漏钉）：
// - `ToolRegistrySwitchTest` 钉的是**刀 2 追加包**那套「表＋单工具开关」，本批只改了它三条
//   用例的期望值（tools 数组从"全部启用"变成"启用 ∩ 可见"）；组判据一律在本文件。
// - `BaopuSettingsGuardTest` 钉设置页的搬运与映射表完整性（J8：每个工具有标签/副标题）。
// - 本文件钉：一条链、默认可见读数、按会话解锁、用户开关优先、孤儿工具直接红。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.CodeText
import org.json.JSONArray
import org.json.JSONObject
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
 * 工具渐进披露 v1 的判据（第五十九批 §2/§6）。
 *
 * 读法：判据 A–D 是「表与默认可见」，E–H 是「capabilities 的语义」，
 * I–J 是 §2 那两条**源码级门禁**（一条链、一个组装点）。
 */
class ProgressiveToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 公共件 ────────────────────────────────────────────────────────────

    /** 假工具（判据只吃 name/group，Schema 与执行器都用最省事的形状）。 */
    private fun fake(name: String, group: ToolGroup): ToolSpec = ToolSpec(
        name = name,
        description = name,
        inputSchema = JSONObject().put("type", "object").put("properties", JSONObject()),
        group = group,
    ) { ToolOutcome.Ok(name) }

    private fun catalogOf(vararg names: Pair<String, ToolGroup>): List<ToolSpec> =
        names.map { (n, g) -> fake(n, g) }

    /** 真表装配出来的注册表（与 BaopuRuntime 同一条加载路：ENTRIES 一张表 + capabilities 接线）。 */
    private fun realRegistry(
        prefs: ToolPrefs = InMemoryToolPrefs(),
        unlocks: GroupUnlockStore = InMemoryGroupUnlocks(),
        root: File = tmp.newFolder("ws"),
        ask: ((AskUserRequest) -> AskUserOption?)? = null,
    ): ToolRegistry = ToolRegistry(prefs, unlocks, ask = ask).also {
        BaopuToolCatalog.loadInto(it) { root }
    }

    private fun names(arr: JSONArray, path: String? = "function"): List<String> =
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            (path?.let { p -> o.getJSONObject(p) } ?: o).getString("name")
        }

    // ── 判据 A：§2 要求的三组纯函数用例 ──────────────────────────────────

    @Test
    fun `A1_默认可见_只有core与edit进数组`() {
        val catalog = catalogOf(
            "read_file" to ToolGroup.CORE,
            "write_file" to ToolGroup.EDIT,
            "browser_click" to ToolGroup.BROWSER,
            "file_info" to ToolGroup.FILES,
            "card_inspect" to ToolGroup.CARD,
            "write_skill" to ToolGroup.SKILL,
            "preview_local_file" to ToolGroup.PREVIEW,
            "library_list" to ToolGroup.LIBRARY,
        )
        val visible = visibleTools(catalog, ToolUserSettings(), emptySet()).map { it.name }
        assertEquals("默认只出 core+edit（§1.2：其余组模型根本看不见）", listOf("read_file", "write_file"), visible)
    }

    @Test
    fun `A2_解锁后该组进数组_其余组照旧看不见`() {
        val catalog = catalogOf(
            "read_file" to ToolGroup.CORE,
            "browser_click" to ToolGroup.BROWSER,
            "browser_open" to ToolGroup.CORE,
            "file_info" to ToolGroup.FILES,
        )
        val visible = visibleTools(catalog, ToolUserSettings(), setOf(ToolGroup.BROWSER)).map { it.name }
        assertEquals(listOf("read_file", "browser_click", "browser_open"), visible)
        assertFalse("没解锁的 files 组不许顺带漏出来", visible.contains("file_info"))
    }

    @Test
    fun `A3_用户开关优先_组关了则解锁也不生效`() {
        val catalog = catalogOf("browser_click" to ToolGroup.BROWSER, "read_file" to ToolGroup.CORE)
        val settings = ToolUserSettings(disabledGroups = setOf(ToolGroup.BROWSER))
        // 三条都走同一条链：默认可见 / 解锁 / 用户关闭，两个开关各管一档
        assertEquals(listOf("read_file"), visibleTools(catalog, settings, emptySet()).map { it.name })
        assertEquals(
            "组被用户关掉 ⇒ capabilities 解锁也必须无效（§1.4）",
            listOf("read_file"),
            visibleTools(catalog, settings, setOf(ToolGroup.BROWSER)).map { it.name },
        )
        // 单工具开关只关那一个，同组其余照常（§6「两档并存、互不覆盖」）
        val one = catalogOf("read_file" to ToolGroup.CORE, "list_dir" to ToolGroup.CORE)
        assertEquals(
            listOf("list_dir"),
            visibleTools(one, ToolUserSettings(disabledNames = setOf("read_file")), emptySet()).map { it.name },
        )
    }

    // ── 判据 B：默认可见读数（§2「tools ≤ 12」）─────────────────────────

    @Test
    fun `B1_普通通道默认可见恰好12条`() {
        val reg = realRegistry()
        val visible = reg.visibleSpecs()
        assertEquals("§2 硬卡「默认 tools ≤ 12」：实测读数 ${visible.map { it.name }}", 12, visible.size)
        assertEquals(
            // 表序（read_file…card_build + capabilities），不是名字序——顺序变了前缀缓存就抖
            listOf(
                "read_file", "list_dir", "search_in_files", "write_file", "edit_file",
                "browser_open", "browser_snapshot", "update_plan", "image_ops", "ask_user",
                "card_build", "capabilities",
            ),
            visible.map { it.name },
        )
        // 两个协议侧同一份集合（数组形状不同，成员必须同源）
        assertEquals(12, names(reg.toolsJsonOpenAi()).size)
        assertEquals(12, names(reg.toolsJsonAnthropic(), path = null).size)
    }

    @Test
    fun `B2_免费通道在同一张表上是14条`() {
        // read / bash 是免费通道的兼容占位（BaopuRuntime 按 provider.builtinFree 单独注册）。
        // 它们必须由 COMPAT 组**默认可见**：一旦藏起来，免费档第一次请求就 403 FreeTierError。
        // 于是 §2 那句「≤12」在同一条链上有两个读数——本条把它钉成**实测值**，不藏进注释。
        val reg = realRegistry()
        reg.register(fake("read", ToolGroup.CORE)) // 组会被 register() 按表重盖成 COMPAT
        reg.register(fake("bash", ToolGroup.CORE))
        assertEquals(ToolGroup.COMPAT, reg.all().first { it.name == "read" }.group)
        val visible = reg.visibleSpecs().map { it.name }
        assertEquals(14, visible.size)
        assertTrue("免费档要求 tools 数组含 read/bash：$visible", visible.contains("read") && visible.contains("bash"))
    }

    // ── 判据 C：§6「工具备齐」——默认可见要覆盖点名那十件事 ────────────────

    @Test
    fun `C1_默认可见覆盖点名的读写出列搜浏览ask计划图片写卡`() {
        val visible = realRegistry().visibleSpecs().map { it.name }.toSet()
        val named = listOf(
            "read_file", // 读
            "write_file", // 写
            "edit_file", // 改
            "list_dir", // 列
            "search_in_files", // 搜
            "browser_open", "browser_snapshot", // 浏览器读页
            "ask_user", "update_plan", // 问人 / 计划
            "image_ops", // 图片改写
            "card_build", // card（写卡本体）
            "capabilities", // §1.3：解锁入口自己必须看得见，否则渐进披露是死锁
        )
        named.forEach { assertTrue("§6 点名的能力没进默认可见：$it", it in visible) }
    }

    @Test
    fun `C2_capabilities返回全量名册_每组一行带状态`() {
        val reg = realRegistry()
        reg.currentSessionId = 11L
        val out = CapabilitiesTool.spec().executor(JSONObject()) as ToolOutcome.Ok
        val roster = out.text
        ToolGroup.values().forEach { g ->
            assertTrue("名册漏了组「${g.key}」：$roster", roster.contains(g.key))
        }
        // 三种状态都得在（§1.3：无参数＝列全量名册）
        assertTrue(roster.contains("默认可见"))
        assertTrue(roster.contains("未解锁"))
        // 组里的工具名也得到（模型得知道解锁完能用哪些）
        assertTrue(roster.contains("browser_click"))
        // 当前可见条数与注册表同源（这句数字要是各算一遍就是第二条链）
        assertTrue(roster.contains("当前可见工具（${reg.visibleSpecs().size} 个）"))
    }

    @Test
    fun `C3_名册不列本通道没注册的工具`() {
        // 普通通道没有 read/bash。名册把它们也列出来 = 骗模型「解锁了就能用」，
        // 而 COMPAT 压根不在可解锁名单里 ⇒ 模型只能反复试。
        val reg = realRegistry()
        val lines = (CapabilitiesTool.spec().executor(JSONObject()) as ToolOutcome.Ok).text.lines()
        val at = lines.indexOfFirst { it.startsWith("- compat") }
        assertTrue("名册里必须有 compat 那一组（它是真组，只是本通道没注册）", at >= 0)
        val detail = lines.getOrElse(at + 1) { "" }
        assertTrue("compat 那一行要说清「本通道没有注册」：$detail", detail.contains("本通道没有注册"))
        assertFalse("不许把没注册的工具端给模型：$detail", detail.contains("工具：read"))
    }

    // ── 判据 D：§6「孤儿工具直接红」 ──────────────────────────────────────

    @Test
    fun `D1_分组表与注册集合双向相等`() {
        assertNull("孤儿工具/过期条目：${ToolGroupTable.checkCoverage()}", ToolGroupTable.checkCoverage())
        val declared = BaopuToolCatalog.ENTRIES.map { it.name }.toSet() + ToolGroupTable.RUNTIME_REGISTERED
        assertEquals("表多出来的名字根本没注册", declared, ToolGroupTable.GROUP_OF.keys)
        // 运行时单独注册的四条（write_skill / append_persona / read / bash）也必须在表里——
        // 否则它们会带着 ToolSpec 的默认组 CORE 溜进默认可见，等于「漏分组不报错」
        // （append_persona 是 58 批加进来的，本批合流时补的组：SKILL）
        ToolGroupTable.RUNTIME_REGISTERED.forEach { assertNotNull("运行时注册条目没分组：$it", ToolGroupTable.GROUP_OF[it]) }
    }

    @Test
    fun `D2_注册时按表盖章_工具文件写什么组都不算`() {
        val reg = realRegistry()
        // file_info 的工具文件里没写组（默认 CORE）；表说它是 FILES ⇒ 注册后必须是 FILES
        assertEquals(ToolGroup.FILES, reg.all().first { it.name == "file_info" }.group)
        // 反向也要成立：装配层自己"记得"带的组一律被表覆盖（register 的 copy 不是装饰）
        reg.register(ToolSpec("book_outline", "假", JSONObject(), group = ToolGroup.CORE) { ToolOutcome.Ok("x") })
        assertEquals(ToolGroup.LIBRARY, reg.all().first { it.name == "book_outline" }.group)
    }

    // ── 判据 E/F：capabilities 解锁与按会话（§1.4） ───────────────────────

    @Test
    fun `E1_申请并同意本次会话后_同会话的下一次数组里有它`() {
        // 第九十七批口径变更：capabilities 不再当场自解锁（59 批 §1.3 FREE 自解锁作废）；
        // 现在必须经过一张御批卡，用户点「本次会话」才落解锁。
        val reg = realRegistry(ask = { pick -> pick.options.first { it.id == "session" } })
        reg.currentSessionId = 101L
        assertFalse(names(reg.toolsJsonOpenAi()).contains("browser_click"))

        val out = CapabilitiesTool.spec().executor(
            JSONObject().put("enable", JSONArray().put("browser")),
        ) as ToolOutcome.Ok
        assertTrue("同意后要回明文说解锁了：${out.text}", out.text.contains("已解锁：browser"))

        assertTrue(
            "§2：同意后同一会话的下一次请求必须含该组工具",
            names(reg.toolsJsonOpenAi(101L)).contains("browser_click"),
        )
        // 没申请的组仍然看不见（不是"一解锁就全开"）
        assertFalse(names(reg.toolsJsonOpenAi(101L)).contains("file_info"))
    }

    @Test
    fun `E2_本次会话同意按会话分格_新会话回默认`() {
        val unlocks = InMemoryGroupUnlocks()
        val reg = realRegistry(unlocks = unlocks, ask = { pick -> pick.options.first { it.id == "session" } })
        reg.currentSessionId = 201L
        CapabilitiesTool.spec().executor(JSONObject().put("enable", JSONArray().put("card")))
        assertEquals(setOf(ToolGroup.CARD), unlocks.unlocked(201L))

        // 换会话（§4 打回第三条：全局共享就是这一条）
        reg.currentSessionId = 202L
        assertFalse("新会话必须回到默认（§1.4）", names(reg.toolsJsonOpenAi(202L)).contains("card_inspect"))
        assertEquals("两个会话各一格，不许共用一格", emptySet<ToolGroup>(), unlocks.unlocked(202L))
        // 老会话回来还在（解锁是累加的，本会话内不缩）
        assertTrue(names(reg.toolsJsonOpenAi(201L)).contains("card_inspect"))
        // 清格入口备好（删会话时调；本批没接线，报告里如实写）
        unlocks.clear(201L)
        assertEquals(emptySet<ToolGroup>(), unlocks.unlocked(201L))
    }

    @Test
    fun `E2b_一直同意写持久档_新会话也看得见`() {
        // 「一直」= 写长期同意（approvedGroups），所以跨会话；这也是设置页组开关的同一档。
        val prefs = InMemoryToolPrefs()
        val unlocks = InMemoryGroupUnlocks()
        val reg = realRegistry(prefs, unlocks, ask = { pick -> pick.options.first { it.id == "always" } })
        reg.currentSessionId = 211L
        val out = CapabilitiesTool.spec().executor(
            JSONObject().put("enable", JSONArray().put("card")),
        ) as ToolOutcome.Ok
        assertTrue(out.text.contains("已解锁：card"))
        assertEquals(setOf(ToolGroup.CARD), prefs.approvedGroups())
        // 换会话仍在（持久档的语义）
        reg.currentSessionId = 212L
        assertTrue("一直同意必须跨会话生效", names(reg.toolsJsonOpenAi(212L)).contains("card_inspect"))
    }

    @Test
    fun `E7_用户取消则不授权_且不再弹同一张卡`() {
        val prefs = InMemoryToolPrefs()
        val unlocks = InMemoryGroupUnlocks()
        var cards = 0
        val reg = realRegistry(prefs, unlocks, ask = { pick ->
            cards += 1
            pick.options.first { it.id == "cancel" }
        })
        reg.currentSessionId = 221L
        val out = CapabilitiesTool.spec().executor(
            JSONObject().put("enable", JSONArray().put("library")),
        ) as ToolOutcome.Ok
        assertEquals("第一次申请只弹一张卡", 1, cards)
        assertTrue("要如实说没同意：${out.text}", out.text.contains("用户这次没同意"))
        assertFalse("没同意就不许解锁", unlocks.unlocked(221L).contains(ToolGroup.LIBRARY))
        assertFalse(names(reg.toolsJsonOpenAi(221L)).contains("library_list"))
        assertTrue("不许写持久档", prefs.approvedGroups().isEmpty())
        // 立刻再申请：限流闸压住，不再弹卡（§2「同类建议限流/合并，不每次任务弹」）
        CapabilitiesTool.spec().executor(JSONObject().put("enable", JSONArray().put("library")))
        assertEquals("被拒后不能每次都弹卡（N＝3）", 1, cards)
    }

    @Test
    fun `E8_没有界面时如实说不授权`() {
        // ask=null = 后台/无界面：与 ask_user 同一口径（明说，不假装用户选了）
        val unlocks = InMemoryGroupUnlocks()
        val reg = realRegistry(unlocks = unlocks, ask = null)
        reg.currentSessionId = 231L
        val out = CapabilitiesTool.spec().executor(
            JSONObject().put("enable", JSONArray().put("files")),
        ) as ToolOutcome.Ok
        assertTrue("要明说没人应答：${out.text}", out.text.contains("没有回应"))
        assertTrue(unlocks.unlocked(231L).isEmpty())
        assertFalse(names(reg.toolsJsonOpenAi(231L)).contains("file_info"))
    }

    @Test
    fun `E3_被用户关掉的组解不开并给出原因`() {
        val prefs = InMemoryToolPrefs()
        val unlocks = InMemoryGroupUnlocks()
        val reg = realRegistry(prefs, unlocks)
        reg.currentSessionId = 301L
        reg.setGroupDisabled(ToolGroup.LIBRARY, true)

        val out = CapabilitiesTool.spec().executor(
            JSONObject().put("enable", JSONArray().put("library")),
        ) as ToolOutcome.Ok
        assertTrue("必须回「解不开」并说原因是用户关的：${out.text}", out.text.contains("解不开：library"))
        assertTrue(out.text.contains("被你用户在设置里关掉了"))
        assertFalse("用户开关赢了：这一格不许有 library", unlocks.unlocked(301L).contains(ToolGroup.LIBRARY))
        assertFalse(names(reg.toolsJsonOpenAi(301L)).contains("library_list"))
    }

    @Test
    fun `E4_写错组名被拒并列出可用组名`() {
        val reg = realRegistry()
        reg.currentSessionId = 401L
        val out = CapabilitiesTool.spec().executor(
            JSONObject().put("enable", JSONArray().put("browsers")),
        ) as ToolOutcome.Ok
        assertTrue(out.text.contains("没有这个组：browsers"))
        assertTrue("要把可点的组名列给模型：${out.text}", out.text.contains("可解锁的组名"))
        // COMPAT 不在可点名解锁的名单里（它由通道决定）——注意判的是**那一行**，
        // 名册本体仍会列 compat 这一组（它是真组，只是不该被点名）
        val unlockable = out.text.lines().first { it.startsWith("可解锁的组名：") }
        assertFalse("COMPAT 不该能被点名解锁：$unlockable", unlockable.contains("compat"))
        assertTrue("browser 组必须在名单里：$unlockable", unlockable.contains("browser"))
    }

    @Test
    fun `E5_未解锁直接调用拿到明说的拒绝`() {
        val reg = realRegistry()
        reg.currentSessionId = 501L
        // §4 打回第一条：默认不可见是"不进数组"，不是"看得见但报错"。这一条验的是
        // **另一件事**：模型凭旧上下文/协议 B 硬调时，必须拿到明说而不是静默成功。
        assertFalse(names(reg.toolsJsonOpenAi(501L)).contains("browser_click"))
        val spec = reg.find("browser_click") ?: error("未解锁的工具仍必须可寻址（否则变'未知工具'）")
        assertEquals("桩必须免确认（不执行就没有可确认的东西）", ToolPermission.FREE, spec.permission)
        val r = spec.executor(JSONObject())
        assertTrue("必须 Rejected，不是 Failed（不重试撞同一堵墙）：$r", r is ToolOutcome.Rejected)
        val reason = (r as ToolOutcome.Rejected).reason
        assertTrue(reason.contains("该工具组未解锁"))
        assertTrue("要指路：先调 capabilities（${reason}）", reason.contains("capabilities"))
        assertTrue("要点名解哪一组", reason.contains("browser"))
    }

    @Test
    fun `E6_被用户关掉的工具不许被指路成解锁一下就能用`() {
        // find() 里两条拒绝的**先后**是判据：用户开关优先（§1.4）。
        // 反了就会对一个用户明确关掉的东西说"调 capabilities 就能开"——那是当着用户的面撒谎。
        val prefs = InMemoryToolPrefs()
        val reg = realRegistry(prefs)
        reg.currentSessionId = 601L
        prefs.setDisabled("file_info", true) // files 组本来就未解锁
        val reason = (reg.find("file_info")!!.executor(JSONObject()) as ToolOutcome.Rejected).reason
        assertTrue("必须先说「已被用户关闭」：$reason", reason.contains("该工具已被用户关闭"))
        assertFalse("关了的东西不许再指路 capabilities", reason.contains("capabilities"))
    }

    @Test
    fun `G0_去注释工具自己得站得住`() {
        // G1/G2/G3 三条门禁都吃 [codeOnly]；它要是漏掉注释或把代码吃掉，门禁就变成摆设。
        // 这一条钉它三件事：注释里的关键词不算、代码里的算、字符串里的不算。
        val withComment = "// 这里提到 visibleTools( 是散文\nfun visibleTools(a: Int) = a\n"
        assertEquals("注释里的不算、代码里的那一处要算", 1, Regex("visibleTools\\(").findAll(codeOnly(withComment)).count())
        assertEquals(
            "字符串里的关键词不算（提示词里常提到工具名）",
            0,
            Regex("visibleTools\\(").findAll(codeOnly("val s = \"看 visibleTools(x) 那段\"")).count(),
        )
        // 正则版崩在这里的形态：行尾注释里一个未闭合引号 ⇒ 从这里递归到文件尾（StackOverflowError）
        val nasty = "val a = 1 // 他说 \"没闭合\n" + "x".repeat(20000) + "\nfun visibleTools(a: Int) = a\n"
        assertEquals(1, Regex("visibleTools\\(").findAll(codeOnly(nasty)).count())
        // Kotlin 三引号原始串：正则版不认，会把串里的中文当成代码（这里要的是"不算"）
        assertEquals(
            0,
            Regex("visibleTools\\(").findAll(codeOnly("val r = \"\"\"多行 visibleTools(x) 还在串里\"\"\"\n")).count(),
        )
        // 字符字面量里那一个引号不许把后面的代码全吞掉：两处 `fun c(` 都得留下（正则版会只剩一处）
        val charLit = codeOnly("fun c(q: Char) = q == '\"'\nfun c(a: Int) = a\n")
        assertEquals(2, Regex("fun c\\(").findAll(charLit).count())
        // 换行数不变 ⇒ 门禁失败时报的行号对得上原文
        val lines = "line1\n/* 块\n注释 */line2\nline3\n"
        val stripped = codeOnly(lines)
        assertEquals(lines.count { it == '\n' }, stripped.count { it == '\n' })
        assertTrue("块注释结束后同一行上的代码要留下：$stripped", stripped.contains("line2"))
    }

    // ── 判据 G/H：§2 源码级门禁（一条链、一个组装点） ─────────────────────

    private fun mainSrc(rel: String): String =
        File("src/main/java/com/example/zhengdao/$rel").readText(Charsets.UTF_8)
            .replace("\r\n", "\n")

    /**
     * 去掉注释与字符串后的源码（门禁只钉**代码**，不被注释里的关键词骗）。
     *
     * 本体在 [CodeText]（状态机）。这里不再抄正则：正则版 `"(\.|[^"\])*"` 会在一个未闭合
     * 引号处逐字符递归到文件尾，G1 那条全仓扫描就是这么 StackOverflowError 的（2026-10-19）。
     * 用法差异只有一处：[mainSrc] 先把 CRLF 归成 LF，行注释的 `$` 才在多行模式下正确收尾。
     */
    private fun codeOnly(src: String): String = CodeText.codeOnly(src)

    @Test
    fun `G1_tools数组只由一条链算_全仓不许有第二条`() {
        val reg = codeOnly(mainSrc("baopu/agent/ToolRegistry.kt"))
        // 判据本体只有一份
        assertEquals("可见性判据本体只能定义一次", 1, Regex("fun isVisibleTool\\(").findAll(reg).count())
        assertEquals("整表版只能定义一次", 1, Regex("fun visibleTools\\(").findAll(reg).count())
        // toolsJsonXxx 两个出口都必须走 visibleSpecs，且**不许**自己碰 prefs / all().filter
        listOf("toolsJsonOpenAi", "toolsJsonAnthropic").forEach { fn ->
            val body = reg.substringAfter("fun $fn(").substringBefore("\n    }")
            assertTrue("$fn 必须走 visibleSpecs（唯一那条链）", body.contains("visibleSpecs("))
            assertFalse("$fn 不许自己判开关（那是第二条过滤链）", body.contains("isDisabled"))
            assertFalse("$fn 不许自己判组", body.contains("DEFAULT_VISIBLE"))
            assertFalse("$fn 不许直接铺 specs 全表", body.contains("specs.values"))
        }
        // 全仓：判据的调用点只能在 ToolRegistry.kt（别的文件再 filter 一次就是第二条链）
        val offenders = File("src/main/java/com/example/zhengdao").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.absolutePath.replace('\\', '/').contains("baopu/agent/ToolRegistry.kt").not() }
            .filter { f ->
                val t = codeOnly(f.readText(Charsets.UTF_8))
                Regex("visibleTools\\(").containsMatchIn(t) || Regex("isVisibleTool\\(").containsMatchIn(t)
            }
            .map { it.name }
            .toList()
        assertEquals("第二处使用可见性判据的文件（§4 打回第四条）：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `G2_引擎里只有一个组装tools数组的点`() {
        val engine = codeOnly(mainSrc("baopu/agent/RunEngine.kt"))
        assertEquals(
            "tools 数组的组装语句必须恰好一处（§2 源码门禁；两处以上＝两条链的开端）",
            1,
            Regex("val tools = when").findAll(engine).count(),
        )
        // 那一处里两个协议分支各调一次注册表出口——同一处、同一条链的两种**形状**，
        // 不是第二条链（多了第三处就是有人在别处自己算了一遍）
        assertEquals(2, Regex("registry\\.toolsJson").findAll(engine).count())
        // 会话身份也只有一个落点：capabilities 的 executor 拿不到 sessionId，
        // 全靠引擎在每轮入口写 currentSessionId（房型同 planSessionId）
        assertEquals(
            "currentSessionId 的写入点必须恰好一处",
            1,
            Regex("currentSessionId\\s*=").findAll(engine).count(),
        )
        // 且那一处**显式**传会话号，不吃默认值（吃了 NO_SESSION 就退化成"永远新会话"）
        assertTrue(
            "组装处必须显式传 sessionId",
            Regex("registry\\.toolsJson(OpenAi|Anthropic)\\(sessionId\\)").containsMatchIn(engine),
        )
    }

    @Test
    fun `G3_设置页只有一张组表`() {
        // 与 ToolRegistrySwitchTest 的判据 D 同一件事的两面：那边钉"成员从表读"，
        // 这边钉"判据不在 UI 重算一遍"。设置页只许**读** DEFAULT_VISIBLE 来显示"默认是否可见"，
        // 不许拿它去算可见集合（那是第二条链的 UI 版本）。
        val screen = codeOnly(mainSrc("ui/baopu/BaopuSettingsScreen.kt"))
        assertFalse("设置页不许自己 filter 出可见集合", Regex("visibleTools\\(|visibleSpecs\\(").containsMatchIn(screen))
        assertTrue("设置页必须从那张唯一的表读成员", screen.contains("ToolGroupTable.namesOf("))
    }

    // ── 判据 I：组开关与单工具开关并存（§6 最后一条） ─────────────────────

    @Test
    fun `I1_两档开关并存_互不覆盖`() {
        val prefs = InMemoryToolPrefs()
        val reg = realRegistry(prefs)
        reg.currentSessionId = 701L
        reg.unlockStore().unlock(701L, listOf(ToolGroup.CARD))

        // 先关一个工具：同组另一个照常在（单工具开关不外溢）
        reg.setDisabled("card_inspect", true)
        val v1 = names(reg.toolsJsonOpenAi(701L))
        assertFalse(v1.contains("card_inspect"))
        assertTrue("同组另一个不该被牵连", v1.contains("card_export"))

        // 再关整组：整组消失，但**单工具开关的状态没被改写**（重开组就回到刚才的样子）
        reg.setGroupDisabled(ToolGroup.CARD, true)
        val v2 = names(reg.toolsJsonOpenAi(701L))
        assertFalse(v2.contains("card_export"))
        assertFalse(v2.contains("card_inspect"))

        reg.setGroupDisabled(ToolGroup.CARD, false)
        val v3 = names(reg.toolsJsonOpenAi(701L))
        assertTrue("组重开 ⇒ 此前没被单独关的 card_export 回来", v3.contains("card_export"))
        assertFalse("此前被单独关的 card_inspect 不许借组开关复活", v3.contains("card_inspect"))
    }

    @Test
    fun `I2_组开关与解锁是两件事_都关不住默认可见`() {
        val prefs = InMemoryToolPrefs(initialGroups = setOf(ToolGroup.BROWSER))
        val reg = realRegistry(prefs)
        reg.currentSessionId = 801L
        // browser_open 在 CORE（读页），browser_click 在 BROWSER（交互）：
        // 关掉 browser 组只该带走交互四件，不该把"打开网页并读它"也带走。
        val v = names(reg.toolsJsonOpenAi(801L))
        assertTrue(v.contains("browser_open"))
        assertFalse(v.contains("browser_click"))
    }

    @Test
    fun `I3_默认可见集合就是那两个组加通道占位`() {
        assertEquals(
            "§1.2：默认可见只有 core 与 edit（COMPAT 是通道占位，理由见其 KDoc）",
            setOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.COMPAT),
            ToolGroup.DEFAULT_VISIBLE,
        )
        // 未解锁的组不许有人偷偷默认可见
        ToolGroup.values().filterNot { it in ToolGroup.DEFAULT_VISIBLE }.forEach { g ->
            assertFalse("${g.key} 组不该默认可见", g in ToolGroup.DEFAULT_VISIBLE)
        }
    }

    @Test
    fun `I4_手动关组会撤掉会话解锁`() {
        val unlocks = InMemoryGroupUnlocks()
        val reg = realRegistry(unlocks = unlocks, ask = { pick -> pick.options.first { it.id == "session" } })
        reg.currentSessionId = 901L
        CapabilitiesTool.spec().executor(JSONObject().put("enable", JSONArray().put("browser")))
        assertTrue(unlocks.unlocked(901L).contains(ToolGroup.BROWSER))
        // 设置页关组 ⇒ 会话内已解锁的一并失效（§3.5「手动关 ⇒ 立即禁用整组」）
        reg.setGroupDisabled(ToolGroup.BROWSER, true)
        unlocks.relockAll(listOf(ToolGroup.BROWSER))
        assertFalse("关组后会话格里的 browser 必须被撤掉", unlocks.unlocked(901L).contains(ToolGroup.BROWSER))
        assertFalse(names(reg.toolsJsonOpenAi(901L)).contains("browser_click"))
    }

    @Test
    fun `I5_长期同意跨会话可见_禁用压过同意`() {
        val prefs = InMemoryToolPrefs(initialApproved = setOf(ToolGroup.BROWSER))
        val reg = realRegistry(prefs)
        reg.currentSessionId = 1001L
        // 「一直」档不依赖会话：新会话也看得见
        assertTrue(names(reg.toolsJsonOpenAi(1001L)).contains("browser_click"))
        // 用户后来手动关掉（disabled 优先于 approved）
        prefs.setGroupDisabled(ToolGroup.BROWSER, true)
        assertFalse("disabled 必须压过 approved", names(reg.toolsJsonOpenAi(1001L)).contains("browser_click"))
    }

    @Test
    fun `I6_组授权不等于免确认_CONFIRM工具仍每次一张卡`() {
        val prefs = InMemoryToolPrefs(initialApproved = setOf(ToolGroup.FILES))
        val reg = realRegistry(prefs)
        reg.currentSessionId = 1101L
        // 组授权后工具确实可见（这是本批要的）
        assertTrue(names(reg.toolsJsonOpenAi(1101L)).contains("delete_path"))
        // 但权限分级一个字节没动：delete_path 仍是 CONFIRM（每次动作一张卡）
        assertEquals(ToolPermission.CONFIRM, reg.find("delete_path")!!.permission)
    }

    // ── 判据 J：§7 提示词（许愿式编程 W6＋渐进披露得让模型知道） ────────────

    /**
     * §1 把「模型能看见哪些工具」变成了运行时状态，§7 又给了它「写完就预览给用户看」这条新工作
     * 方式——两件事都只写在提示词里才可能真的被模型用上。所以这里钉**提示词原文**：
     * 代码与判据都绿、提示词却没提，模型仍然只会「这个工具我没有」，§7 那条
     * 「一轮里同时看到写 html 与玄览预览两张卡」在真机上就永远验不过。
     *
     * 口径说明（不装作验过了）：本用例只证明 App **告诉**了模型这两条；模型收到之后
     * 是否真的按这个方式干活，是通道侧行为，归蓝总管真机采数。
     */
    @Test
    fun `J1_提示词写了按需披露与能写能看不代执行`() {
        // 读原文、不读 codeOnly：这些句子在 ``` 三引号的提示词正文里，去了注释与字符串就没了。
        val preset = mainSrc("baopu/BaopuPreset.kt")

        // 一、§7 点名的那一行（原文要求：「写程序/做页面（能写能看，不替你执行）」）
        assertTrue("§7：提示词必须有「写程序/做页面」这条能力声明", preset.contains("写程序/做页面"))
        assertTrue("§7：同一条里必须说清「不替你执行」", preset.contains("不替你执行"))
        assertTrue("§7：必须把预览工具的名字给模型（否则它不知道用哪个）", preset.contains("preview_local_file"))

        // 二、§1 的副作用：工具是运行时按需给的，模型得知道「看不见≠没有」
        assertTrue("§1：提示词必须说明工具按需给、不是没有", preset.contains("工具是按需给你的"))
        assertTrue("§1：必须给出解锁入口 capabilities", preset.contains("capabilities"))
        assertTrue("§1：必须说清用户关掉的组解不开", preset.contains("解不开"))

        // 三、这段必须在「你会做什么」那一节里——写到别处等于模型看不见
        val section = preset.substringAfter("## 你会做什么")
        assertTrue("两条新句必须落在「## 你会做什么」节内", section.contains("写程序/做页面") && section.contains("工具是按需给你的"))
    }
}
