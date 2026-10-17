// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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
 * - 默认五个全启用；
 * - 关掉的工具**不进 tools 数组**（模型看不见）⇒ 判据③「模型看不到该工具」的构造成立；
 * - 被关工具仍是注册形态（同 Schema），模型幻觉调用拿到「该工具已被用户关闭」，
 *   **不自动打开**；
 * - 开关热改（同一注册表实例，改完下一轮请求即生效）。
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
    fun `一张表_默认七个全启用`() {
        setup()
        val reg = registry()

        assertEquals(BaopuToolCatalog.DEFAULT_ENABLED.size, BaopuToolCatalog.ENTRIES.size)
        assertEquals(
            listOf(
                "read_file", "list_dir", "search_in_files", "file_info",
                "write_file", "edit_file", "move_path",
            ),
            reg.names(),
        )
        BaopuToolCatalog.ENTRIES.forEach { e ->
            assertTrue("${e.name} 默认必须启用", reg.isEnabled(e.name))
        }
        assertEquals(BaopuToolCatalog.DEFAULT_ENABLED, reg.enabledNames().toSet())
    }

    @Test
    fun `表的顺序即tools数组顺序`() {
        setup()
        val reg = registry()
        val names = mutableListOf<String>()
        for (i in 0 until reg.toolsJsonOpenAi().length()) {
            names += reg.toolsJsonOpenAi().getJSONObject(i).getJSONObject("function").getString("name")
        }
        assertEquals(BaopuToolCatalog.ENTRIES.map { it.name }, names)
    }

    @Test
    fun `表里每个工具都有权限与Schema`() {
        setup()
        val reg = registry()
        BaopuToolCatalog.ENTRIES.forEach { e ->
            val spec = reg.find(e.name)
            assertNotNull("${e.name} 必须注册", spec)
            assertTrue(spec!!.description.isNotBlank())
            assertTrue(spec.inputSchema.has("properties"))
            assertFalse(spec.inputSchema.optBoolean("additionalProperties"))
        }
        // 三个 CONFIRM：write_file / edit_file / move_path（都会改盘上文件，每次都要用户点头）
        listOf("write_file", "edit_file", "move_path").forEach {
            assertEquals("$it 必须 CONFIRM", ToolPermission.CONFIRM, reg.find(it)!!.permission)
        }
        listOf("read_file", "list_dir", "search_in_files", "file_info").forEach {
            assertEquals(ToolPermission.FREE, reg.find(it)!!.permission)
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

        assertTrue(reg.isEnabled("file_info"))
        reg.setDisabled("file_info", true)
        assertFalse(reg.isEnabled("file_info"))
        assertFalse(reg.toolsJsonOpenAi().toString().contains("file_info"))

        reg.setDisabled("file_info", false) // 再打开
        assertTrue(reg.isEnabled("file_info"))
        assertTrue(reg.toolsJsonOpenAi().toString().contains("file_info"))
    }

    @Test
    fun `关七个_全关时tools数组为空`() {
        setup()
        val prefs = InMemoryToolPrefs(BaopuToolCatalog.DEFAULT_ENABLED)
        val reg = registry(prefs)
        assertEquals(0, reg.toolsJsonOpenAi().length())
        assertEquals(0, reg.toolsJsonAnthropic().length())
        // 注册表仍有七条（都能拿到「已被关闭」）
        assertEquals(7, reg.names().size)
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
    fun `表里不许出现删除类工具`() {
        // 纪律：本刀工具集没有任何删除能力（写操作只到 write_file 为止）
        BaopuToolCatalog.ENTRIES.forEach { e ->
            assertFalse("不许有删除类工具：${e.name}", e.name.contains("delete") || e.name.contains("remove"))
        }
    }
}
