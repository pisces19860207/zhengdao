// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.oc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 太极 opencode.json 写入口单测（v1.2 阶段 2.0 的验收项）。
 *
 * **要防的事故**：`ensurePermissionPolicy()` 原来是**整文件覆盖写**——
 * 它只认识 `$schema` 和 `permissions`，于是冷启动一次就把 `plugin` 数组、
 * 以及后续要加的 `snapshot` / `watcher` 调优字段**全部抹掉**。
 * （v1.1.1 真机上"插件显示已启用"只是因为 serve 已运行、走了提前返回分支没执行覆盖写。）
 *
 * 本测试把"冷启动重写"这条路径钉死：无论重写多少次，别人写进去的字段必须活着。
 */
class OcManagerConfigTest {

    private fun tmpConfig(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "zd-oc-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "opencode.json")
    }

    /** 模拟 `ensurePermissionPolicy` 的 mutate：只覆盖 `permissions` 一个字段。 */
    private val permissionsOnly: (JSONObject) -> Boolean = { obj ->
        obj.put("permissions", JSONArray("""[{"action":"shell","resource":"*","effect":"ask"}]"""))
        true
    }

    private fun JSONArray.toList(): List<String> =
        (0 until length()).map { getString(it) }

    // ── 核心验收：冷启动重写不抹掉别人的字段 ────────────────────────────────

    @Test
    fun `重写权限策略后 plugin 数组与调优字段全部幸存`() {
        val f = tmpConfig()
        f.writeText(
            """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "plugin": ["@chncaesar/opencode-plugin-memory"],
              "snapshot": false,
              "watcher": { "ignore": ["node_modules/**", "opencode/**"] },
              "model": "longcat-2.5-preview-free"
            }
            """.trimIndent()
        )

        OcManager.mergeConfigFile(f, permissionsOnly)

        val obj = JSONObject(f.readText())
        assertEquals(
            listOf("@chncaesar/opencode-plugin-memory"),
            obj.getJSONArray("plugin").toList()
        )
        assertEquals(false, obj.getBoolean("snapshot"))            // 别把调优字段冲掉
        assertEquals(
            listOf("node_modules/**", "opencode/**"),
            obj.getJSONObject("watcher").getJSONArray("ignore").toList()
        )
        assertEquals("longcat-2.5-preview-free", obj.getString("model")) // 用户手写的也要在
        // 权限策略确实写进去了
        assertEquals(
            "ask",
            obj.getJSONArray("permissions").getJSONObject(0).getString("effect")
        )
    }

    @Test
    fun `连续多次重写结果稳定（幂等，不越写越少）`() {
        val f = tmpConfig()
        f.writeText("""{"plugin":["@chncaesar/opencode-plugin-memory"]}""")

        OcManager.mergeConfigFile(f, permissionsOnly)
        val first = f.readText()
        OcManager.mergeConfigFile(f, permissionsOnly)
        OcManager.mergeConfigFile(f, permissionsOnly)

        assertEquals(first, f.readText())
        assertEquals(
            listOf("@chncaesar/opencode-plugin-memory"),
            JSONObject(f.readText()).getJSONArray("plugin").toList()
        )
    }

    @Test
    fun `plugin 项为 spec 加选项的数组形态也不被打回字符串`() {
        val f = tmpConfig()
        f.writeText("""{"plugin":[["@chncaesar/opencode-plugin-memory",{"debug":true}]]}""")

        OcManager.mergeConfigFile(f, permissionsOnly)

        val arr = JSONObject(f.readText()).getJSONArray("plugin")
        assertEquals(1, arr.length())
        // 数组形态必须原样保留，不能被 optString 字符串化
        assertTrue("数组形态不应被字符串化", arr.opt(0) is JSONArray)
        assertEquals("@chncaesar/opencode-plugin-memory", arr.getJSONArray(0).getString(0))
    }

    // ── 空文件 / 新建 ────────────────────────────────────────────────────────

    @Test
    fun `文件不存在时创建并补齐 schema 与 permissions`() {
        val f = tmpConfig()
        assertTrue(!f.isFile)

        OcManager.mergeConfigFile(f, permissionsOnly)

        val obj = JSONObject(f.readText())
        assertEquals("https://opencode.ai/config.json", obj.getString("\$schema"))
        assertEquals(
            "ask",
            obj.getJSONArray("permissions").getJSONObject(0).getString("effect")
        )
    }

    @Test
    fun `mutate 返回 false 时不写盘`() {
        val f = tmpConfig()
        f.writeText("""{"${'$'}schema":"https://opencode.ai/config.json","plugin":["keep-me"]}""")
        val before = f.readText()

        OcManager.mergeConfigFile(f) { false } // 什么都没改

        assertEquals("未改动时不应重写文件", before, f.readText())
    }
}
