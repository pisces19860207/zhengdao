// 第五百八十四单 · 白名单单测：真目录（BaopuToolCatalog）现算，只许 R0∧readOnly∧FREE−排除集。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.testkit.TempSandboxPool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class McpWhitelistTest {

    private lateinit var root: File

    @Before
    fun pick() {
        root = TempSandboxPool.create("zd-mcp-wl-")
    }

    @After
    fun clean() = TempSandboxPool.clean()

    private fun registry(): ToolRegistry {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        return reg
    }

    @Test
    fun `白名单每件都是 R0 只读 FREE（档位按矩阵验）`() {
        val specs = McpWhitelist.specs(root)
        assertTrue("白名单不该是空的（目录里有一批 R0 只读件）", specs.isNotEmpty())
        specs.forEach { spec ->
            assertEquals("矩阵底档必须 R0：${spec.name}", com.example.zhengdao.baopu.RiskClass.R0, com.example.zhengdao.baopu.RiskMatrix.baseOf(spec.name))
            assertTrue("必须 readOnly：${spec.name}", spec.readOnly)
            assertEquals("必须 FREE：${spec.name}", com.example.zhengdao.baopu.agent.ToolPermission.FREE, spec.permission)
        }
    }

    @Test
    fun `白名单是注册表真成员的子集（不含幽灵名）`() {
        val reg = registry()
        val registered = reg.all().map { it.name }.toSet()
        val listed = McpWhitelist.names(root)
        listed.forEach { name ->
            assertTrue("白名单里的 $name 必须真注册过", name in registered)
        }
    }

    @Test
    fun `写类与交互件一律不进来`() {
        val listed = McpWhitelist.names(root).toSet()
        listOf(
            "write_file", "edit_file", "delete_path", "board_write", "todo_add",
            "ask_user", "capabilities", "update_plan", "mcp_set_token", "search_web",
            "schedule_task", "clipboard_write", "notify", "phone_dial",
            "browser_open", "preview_local_file",
        ).forEach { name ->
            assertFalse("「$name」不许经 MCP 暴露", name in listed)
        }
    }

    @Test
    fun `典型 R0 只读件确实暴露（正面样本）`() {
        val listed = McpWhitelist.names(root).toSet()
        listOf("read_file", "list_dir", "search_in_files", "file_info", "board_read", "governance_state")
            .forEach { name ->
                assertTrue("「$name」是 R0 只读，应在白名单里（实到：$listed）", name in listed)
            }
    }

    @Test
    fun `find 取回的 spec 与注册表同 Schema（同一把尺）`() {
        val reg = registry()
        val spec = McpWhitelist.find("read_file", root) ?: return fail("read_file 不在白名单")
        val regSpec = reg.find("read_file") ?: return fail("read_file 没注册")
        assertEquals(spec.inputSchema.toString(), regSpec.inputSchema.toString())
        assertEquals(spec.description, regSpec.description)
        assertEquals("组章与注册表同源（ToolGroupTable）", regSpec.group, spec.group)
    }
}
