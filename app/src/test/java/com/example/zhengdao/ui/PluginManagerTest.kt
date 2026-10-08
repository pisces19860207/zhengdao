// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PluginManager 单测（2026-10-07）。
 *
 * 重点覆盖两类易错点：
 *  1. **包标识解析**——npm 的 scope（`@scope/pkg`）本身就带 `@`，不能把它误当版本分隔符；
 *  2. **配置读写的幂等性与不动他人内容**——`plugin` 数组项既有字符串也有 `[spec, opts]` 数组，
 *     停用某个插件时**绝不能顺手把别的插件或它的选项丢掉**（这正是"删一个插件把配置改坏"的经典事故）。
 */
class PluginManagerTest {

    private fun tmpFile(name: String = "opencode.json"): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "zd-plugin-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, name)
    }

    // ── parseSpec ────────────────────────────────────────────────────────────

    @Test
    fun `普通包名无版本段`() {
        assertEquals("opencode-mem" to null, PluginManager.parseSpec("opencode-mem"))
    }

    @Test
    fun `普通包名带 latest 版本段`() {
        assertEquals("opencode-mem" to "latest", PluginManager.parseSpec("opencode-mem@latest"))
    }

    @Test
    fun `scope 包的单个 at 不是版本分隔符`() {
        assertEquals("@chncaesar/opencode-plugin-memory" to null,
            PluginManager.parseSpec("@chncaesar/opencode-plugin-memory"))
    }

    @Test
    fun `scope 包带版本`() {
        assertEquals("@chncaesar/opencode-plugin-memory" to "0.1.1",
            PluginManager.parseSpec("@chncaesar/opencode-plugin-memory@0.1.1"))
    }

    @Test
    fun `空串返回空包名`() {
        assertEquals("" to null, PluginManager.parseSpec("   "))
    }

    @Test
    fun `尾部多余的 at 视为空版本`() {
        assertEquals("pkg" to null, PluginManager.parseSpec("pkg@"))
    }

    // ── samePackage ──────────────────────────────────────────────────────────

    @Test
    fun `无版本与 latest 视为同一个包`() {
        assertTrue(PluginManager.samePackage("opencode-mem", "opencode-mem@latest"))
    }

    @Test
    fun `前缀相同但不是同一个包`() {
        assertFalse(PluginManager.samePackage("opencode-mem", "opencode-memory"))
    }

    @Test
    fun `scope 不同不算同一个包`() {
        assertFalse(PluginManager.samePackage("@a/pkg", "@b/pkg"))
    }

    @Test
    fun `空标识不与任何包相同`() {
        assertFalse(PluginManager.samePackage("", ""))
    }

    // ── specOf ───────────────────────────────────────────────────────────────

    @Test
    fun `specOf 取字符串形态`() {
        assertEquals("pkg", PluginManager.specOf("pkg"))
    }

    @Test
    fun `specOf 取数组形态的第 0 项`() {
        val arr = JSONArray().put("pkg").put(JSONObject().put("maxSummaryChars", 3000))
        assertEquals("pkg", PluginManager.specOf(arr))
    }

    @Test
    fun `specOf 对空串与空数组返回 null`() {
        assertNull(PluginManager.specOf("  "))
        assertNull(PluginManager.specOf(JSONArray()))
        assertNull(PluginManager.specOf(null))
        assertNull(PluginManager.specOf(42))
    }

    // ── readSpecs ────────────────────────────────────────────────────────────

    @Test
    fun `读取字符串与数组两种形态`() {
        val f = tmpFile()
        f.writeText("""{"plugin":["a","b@latest",["c",{"maxSummaryChars":3000}]]}""")
        assertEquals(listOf("a", "b@latest", "c"), PluginManager.readSpecs(f))
    }

    @Test
    fun `没有 plugin 键返回空表`() {
        val f = tmpFile()
        f.writeText("""{"snapshot":false}""")
        assertTrue(PluginManager.readSpecs(f).isEmpty())
    }

    @Test
    fun `文件不存在返回空表`() {
        assertTrue(PluginManager.readSpecs(tmpFile("nope.json")).isEmpty())
    }

    @Test
    fun `配置损坏返回空表而不抛异常`() {
        val f = tmpFile()
        f.writeText("{ this is not json")
        assertTrue(PluginManager.readSpecs(f).isEmpty())
    }

    // ── applyToFile ──────────────────────────────────────────────────────────

    @Test
    fun `文件不存在时不凭空空造配置`() {
        assertFalse(PluginManager.applyToFile(tmpFile("absent.json"), "pkg", true))
    }

    @Test
    fun `启用会追加并保留既有项`() {
        val f = tmpFile()
        f.writeText("""{"snapshot":false,"plugin":["keep@1.0"]}""")
        assertTrue(PluginManager.applyToFile(f, "new-pkg", true))
        assertEquals(listOf("keep@1.0", "new-pkg"), PluginManager.readSpecs(f))
    }

    @Test
    fun `重复启用同一插件不重写文件`() {
        val f = tmpFile()
        f.writeText("""{"plugin":["pkg@latest"]}""")
        // 已是启用状态（同包，仅版本段写法不同）→ 幂等：不重写、不改动用户的写法
        assertFalse(PluginManager.applyToFile(f, "pkg", true))
        assertEquals(listOf("pkg@latest"), PluginManager.readSpecs(f))
    }

    @Test
    fun `停用只移除目标项`() {
        val f = tmpFile()
        f.writeText("""{"plugin":["a","b@latest","c"]}""")
        assertTrue(PluginManager.applyToFile(f, "b", false))
        assertEquals(listOf("a", "c"), PluginManager.readSpecs(f))
    }

    @Test
    fun `停用别的插件的数组选项形态时原样保留`() {
        val f = tmpFile()
        f.writeText("""{"plugin":[["keep",{"maxSummaryChars":3000}],"drop"]}""")
        assertTrue(PluginManager.applyToFile(f, "drop", false))
        // keep 仍是数组形态、选项还在——把字符串化会丢掉用户配置
        val obj = JSONObject(f.readText())
        val arr = obj.getJSONArray("plugin")
        assertEquals(1, arr.length())
        assertTrue(arr.get(0) is JSONArray)
        assertEquals("keep", (arr.get(0) as JSONArray).getString(0))
        assertTrue(3000 == ((arr.get(0) as JSONArray).get(1) as JSONObject).getInt("maxSummaryChars"))
    }

    @Test
    fun `停用不存在项的插件不改动文件`() {
        val f = tmpFile()
        f.writeText("""{"plugin":["a"]}""")
        assertFalse(PluginManager.applyToFile(f, "zzz", false))
        assertEquals(listOf("a"), PluginManager.readSpecs(f))
    }

    @Test
    fun `清空最后一个插件时连 plugin 键一起删`() {
        val f = tmpFile()
        f.writeText("""{"snapshot":false,"plugin":["only"]}""")
        assertTrue(PluginManager.applyToFile(f, "only", false))
        val obj = JSONObject(f.readText())
        assertFalse(obj.has("plugin"))
        assertTrue(obj.has("snapshot"))            // 其它键仍保留
        assertFalse(obj.getBoolean("snapshot"))    // 且值未被改动（原本就是 false）
    }

    @Test
    fun `原本没有 plugin 键时停用不报变更`() {
        val f = tmpFile()
        f.writeText("""{"snapshot":false}""")
        assertFalse(PluginManager.applyToFile(f, "pkg", false))
        assertFalse(JSONObject(f.readText()).has("plugin"))
    }

    // ── isLegacy ─────────────────────────────────────────────────────────────

    @Test
    fun `被摘除的插件被识别为遗留`() {
        assertTrue(PluginManager.isLegacy("opencode-mem"))
        assertTrue(PluginManager.isLegacy("opencode-mem@latest"))
    }

    @Test
    fun `推荐插件与相似名不误判为遗留`() {
        assertFalse(PluginManager.isLegacy("@chncaesar/opencode-plugin-memory"))
        assertFalse(PluginManager.isLegacy("opencode-memory"))
        assertFalse(PluginManager.isLegacy("my-opencode-mem"))
    }

    // ── isEnabled ────────────────────────────────────────────────────────────

    @Test
    fun `isEnabled 忽略版本段差异`() {
        val f = tmpFile()
        f.writeText("""{"plugin":["pkg@latest"]}""")
        assertTrue(PluginManager.isEnabled(f, "pkg"))
        assertFalse(PluginManager.isEnabled(f, "other"))
    }

    // ── 缓存布局解析（真机实测：scope 包会多一层目录）────────────────────────

    private fun tmpDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "zd-cache-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `识别非 scope 与 scope 两种缓存布局`() {
        val root = tmpDir()
        // 非 scope：<cache>/opencode-mem@latest/package.json
        val a = File(root, "opencode-mem@latest").apply { mkdirs() }
        File(a, "package.json").writeText("""{"version":"2.29.0"}""")
        // scope：<cache>/@chncaesar/opencode-plugin-memory@latest/package.json
        val b = File(File(root, "@chncaesar"), "opencode-plugin-memory@latest").apply { mkdirs() }
        File(b, "package.json").writeText("""{"version":"0.1.1"}""")
        // 干扰项 1：包内的 node_modules 是依赖，不是插件包
        val dep = File(b, "node_modules/left-pad").apply { mkdirs() }
        File(dep, "package.json").writeText("""{"version":"1.0.0"}""")

        val got = PluginManager.findPackageRoots(root, 2)
            .map { PluginManager.fullNameOf(it) }
            .sorted()
        assertEquals(listOf("@chncaesar/opencode-plugin-memory", "opencode-mem"), got)
    }

    @Test
    fun `scope 目录本身不含包描述文件不被当成包`() {
        val root = tmpDir()
        File(root, "@scope").mkdirs()
        assertTrue(PluginManager.findPackageRoots(root, 2).isEmpty())
    }

    @Test
    fun `超过扫描深度的目录不被误报为包`() {
        val root = tmpDir()
        val deep = File(root, "a/b/pkg").apply { mkdirs() }
        File(deep, "package.json").writeText("""{"version":"1.0.0"}""")
        assertTrue(PluginManager.findPackageRoots(root, 2).isEmpty())
    }

    @Test
    fun `fullNameOf 补回 scope 并去掉版本段`() {
        val root = tmpDir()
        val scoped = File(File(root, "@chncaesar"), "opencode-plugin-memory@latest").apply { mkdirs() }
        val plain = File(root, "opencode-mem@latest").apply { mkdirs() }
        assertEquals("@chncaesar/opencode-plugin-memory", PluginManager.fullNameOf(scoped))
        assertEquals("opencode-mem", PluginManager.fullNameOf(plain))
    }

    // ── 版本解析 ─────────────────────────────────────────────────────────────

    @Test
    fun `从 opencode 写的依赖清单里读出版本`() {
        val root = tmpDir()
        val pkg = File(File(root, "@chncaesar"), "opencode-plugin-memory@latest").apply { mkdirs() }
        // opencode 的真实写法：安装目录的 package.json 只是依赖清单，没有 version 字段
        File(pkg, "package.json").writeText(
            """{"dependencies":{"@chncaesar/opencode-plugin-memory":"0.1.1"}}"""
        )
        assertEquals("0.1.1", PluginManager.versionFrom(pkg, "@chncaesar/opencode-plugin-memory"))
    }

    @Test
    fun `包自身带 version 字段也能读出`() {
        val root = tmpDir()
        val pkg = File(root, "opencode-mem@latest").apply { mkdirs() }
        File(pkg, "package.json").writeText("""{"name":"opencode-mem","version":"2.29.0"}""")
        assertEquals("2.29.0", PluginManager.versionFrom(pkg, "opencode-mem"))
    }

    @Test
    fun `读不到版本且目录名无版本段时返回 null`() {
        val root = tmpDir()
        val pkg = File(root, "some-plugin").apply { mkdirs() }
        File(pkg, "package.json").writeText("""{"dependencies":{}}""")
        assertNull(PluginManager.versionFrom(pkg, "some-plugin"))
    }

    @Test
    fun `目录名里的 latest 不算版本`() {
        val root = tmpDir()
        val pkg = File(root, "some-plugin@latest").apply { mkdirs() } // 无 package.json
        assertNull(PluginManager.versionFrom(pkg, "some-plugin"))
    }

    // ── normalizeSpecInput（2026-10-08，配合「添加插件」入口）─────────────────

    @Test
    fun `合法包名原样通过`() {
        assertEquals("my-plugin", PluginManager.normalizeSpecInput("my-plugin"))
        assertEquals("@scope/my-plugin", PluginManager.normalizeSpecInput("@scope/my-plugin"))
        assertEquals("pkg@1.2.3", PluginManager.normalizeSpecInput("pkg@1.2.3"))
        assertEquals("@scope/pkg@latest", PluginManager.normalizeSpecInput("@scope/pkg@latest"))
    }

    @Test
    fun `首尾空白被裁掉——粘贴带换行仍可用`() {
        assertEquals("my-plugin", PluginManager.normalizeSpecInput(" \n my-plugin \n "))
    }

    @Test
    fun `空串与纯空白被拒`() {
        assertNull(PluginManager.normalizeSpecInput(""))
        assertNull(PluginManager.normalizeSpecInput("   "))
    }

    @Test
    fun `内部空白被拒——粘贴进整段说明文字不能变成插件名`() {
        assertNull(PluginManager.normalizeSpecInput("my plugin"))
        assertNull(PluginManager.normalizeSpecInput("my\nplugin"))
        assertNull(PluginManager.normalizeSpecInput("my\tplugin"))
        assertNull(PluginManager.normalizeSpecInput("启用这个插件 opencode-plugin-x"))
    }

    @Test
    fun `相对路径与 URL 尾巴被拒`() {
        assertNull(PluginManager.normalizeSpecInput("./local-plugin.js"))
        assertNull(PluginManager.normalizeSpecInput("/usr/share/plugin.js"))
    }

    @Test
    fun `斜杠形态必须与 scope 匹配`() {
        assertNull(PluginManager.normalizeSpecInput("scope/pkg"))    // 非 scope 却带斜杠
        assertNull(PluginManager.normalizeSpecInput("@scope"))       // scope 缺 name
        assertNull(PluginManager.normalizeSpecInput("@scope/a/b"))   // 多一级
        assertEquals("@scope/pkg", PluginManager.normalizeSpecInput("@scope/pkg"))
    }
}
