// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import com.example.zhengdao.terminal.CacheCleaner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 太极预置单测（[TaijiPreset]，2026-10-09 从 terminal/ProotLauncher 搬来，见 ERRATA E-069）。
 *
 * 三组断言，各自锁一个"曾经真错过"的点：
 *  1. **人设文案**：v2 治的是"谎称自己在 Debian 里"（真机证据：`ls /` 被拒、`PATH` 空）；
 *     v3 再要求它把工作区钉在 App 私有目录上、不许再去共享工作区找活干。
 *  2. **插件数组搬运**：`stripLegacyMemPlugin` 只删 opencode-mem 族，**数组形态的插件原样保留**
 *     （旧实现用 `optString` 会把它字符串化，等于改坏用户配置）。
 *  3. **旧缓存搬家**：公共区 `Download/证道/opencode/` 里的安装包搬进私有 `files/oc/pkg/`，
 *     同名不覆盖，搬完删旧目录。
 */
class TaijiPresetTest {

    // ── 人设文案 ────────────────────────────────────────────────────────────

    private val ws = "/data/user/0/com.example.zhengdao/files/oc/workspace"

    @Test
    fun `人设说清自己在哪、工作区在哪`() {
        val t = TaijiPreset.personaText(ws)
        // 事实一：App 内置、宿主进程，不是 Debian/远程机
        assertTrue(t.contains("安卓手机上"))
        assertTrue(t.contains("不在 Debian 里"))
        assertTrue(t.contains("/bin/sh"))
        assertTrue(t.contains("/system/bin"))
        assertTrue(t.contains("没有 python / pip / node / npm / git"))
        // 事实二：工作区就是 App 私有目录（v3 的核心）
        assertTrue(t.contains(ws))
        // 指路：Linux 的活去终端
        assertTrue(t.contains("「终端」Tab"))
    }

    @Test
    fun `人设里不许再出现旧谎话与旧工作区`() {
        val t = TaijiPreset.personaText(ws)
        // v1 的谎：说自己在 proot Debian 13.7 里（真机实测推翻）
        assertFalse(t.contains("通过 proot 运行"))
        assertFalse(t.contains("你运行在用户的安卓手机上——一个由证道"))
        // 旧人设给的是「终端」里的 guest 路径（/workspace、/root）与共享工作区；v3 不再这么写。
        // ⚠️ 不能断言 `!contains("/workspace")`：fixture 的私有工作区路径自己就含 "/workspace"
        //    （`…/files/oc/workspace`），所以要查的是 guest 专属的标记。
        assertFalse(t.contains("/root"))
        assertFalse(t.contains("Download/证道"))
    }

    @Test
    fun `工作区路径变了文案就跟着变`() {
        assertTrue(TaijiPreset.personaText("/tmp/other-ws").contains("/tmp/other-ws"))
    }

    // ── 插件数组搬运 ────────────────────────────────────────────────────────

    @Test
    fun `只删遗留记忆插件_其余原样保留`() {
        val obj = JSONObject(
            """{"plugin":["opencode-mem@latest","@scope/keep-me",
               ["@chncaesar/opencode-plugin-memory",{"opt":1}]]}"""
        )
        assertTrue(TaijiPreset.stripLegacyMemPlugin(obj))
        val arr = obj.getJSONArray("plugin")
        assertEquals(2, arr.length())
        assertEquals("@scope/keep-me", arr.get(0))
        // 数组形态必须原样放回，不能被字符串化
        assertTrue(arr.get(1) is org.json.JSONArray)
        assertEquals("@chncaesar/opencode-plugin-memory", (arr.get(1) as org.json.JSONArray).get(0))
    }

    @Test
    fun `没有遗留插件时不动配置`() {
        val obj = JSONObject("""{"plugin":["@scope/keep-me"]}""")
        assertFalse(TaijiPreset.stripLegacyMemPlugin(obj))
        // plugin 键不存在时同样不动（也不该凭空造一个空数组）
        val empty = JSONObject("{}")
        assertFalse(TaijiPreset.stripLegacyMemPlugin(empty))
        assertFalse(empty.has("plugin"))
    }

    @Test
    fun `清空后连键一起删`() {
        val obj = JSONObject("""{"plugin":["opencode-mem"]}""")
        assertTrue(TaijiPreset.stripLegacyMemPlugin(obj))
        assertFalse(obj.has("plugin"))
    }

    @Test
    fun `推荐插件幂等预置_同包不同版本段视为已有`() {
        val obj = JSONObject("{}")
        assertTrue(TaijiPreset.ensurePluginEnabled(obj, "@chncaesar/opencode-plugin-memory"))
        assertFalse(TaijiPreset.ensurePluginEnabled(obj, "@chncaesar/opencode-plugin-memory@latest"))
        assertFalse(TaijiPreset.ensurePluginEnabled(obj, "@chncaesar/opencode-plugin-memory"))
        assertEquals(1, obj.getJSONArray("plugin").length())
    }

    // ── 旧缓存搬家 ──────────────────────────────────────────────────────────

    private fun tmpDir(): File = File.createTempFile("taiji-preset", "").let {
        it.delete(); it.mkdirs(); it
    }

    @Test
    fun `旧安装包缓存搬进私有目录且删掉旧目录`() {
        val base = tmpDir()
        val legacy = File(base, "Download/证道/opencode").apply { mkdirs() }
        File(legacy, "opencode-2.0.22-1-aarch64.pkg.tar.xz").writeBytes(ByteArray(2048))
        val target = File(base, "files/oc/pkg")

        assertTrue(OcManager.migrateLegacyCacheDir(legacy, target))
        assertTrue(File(target, "opencode-2.0.22-1-aarch64.pkg.tar.xz").isFile)
        assertEquals(2048L, File(target, "opencode-2.0.22-1-aarch64.pkg.tar.xz").length())
        assertFalse("旧目录必须被删掉（太极不该在公共区留痕迹）", legacy.exists())
    }

    @Test
    fun `同名文件不覆盖_旧目录仍被清掉`() {
        val base = tmpDir()
        val legacy = File(base, "legacy").apply { mkdirs() }
        val target = File(base, "pkg").apply { mkdirs() }
        File(legacy, "same.bin").writeBytes(ByteArray(10))
        File(legacy, "only-old.bin").writeBytes(ByteArray(20))
        File(target, "same.bin").writeBytes(ByteArray(99))

        assertTrue(OcManager.migrateLegacyCacheDir(legacy, target))
        assertEquals(99L, File(target, "same.bin").length())      // 新位置更权威
        assertTrue(File(target, "only-old.bin").isFile)            // 其余照搬
        assertFalse(legacy.exists())
    }

    @Test
    fun `旧目录不存在_或为空_时不动作`() {
        val base = tmpDir()
        val target = File(base, "pkg")
        assertFalse(OcManager.migrateLegacyCacheDir(File(base, "nope"), target))
        val emptyLegacy = File(base, "empty").apply { mkdirs() }
        assertFalse(OcManager.migrateLegacyCacheDir(emptyLegacy, target))
        assertTrue("空旧目录不该被误删（由调用方决定）", emptyLegacy.exists())
    }

    // ── 缓存清理的目标范围（含新私有位置）──────────────────────────────────

    @Test
    fun `包缓存清理目标含私有 pkg 与公共区旧位置`() {
        val files = File("/data/data/com.example.zhengdao/files")
        val paths = CacheCleaner
            .agentCacheTargets(files, File("/sdcard/Download/证道/cache"), File("/sdcard/Download/证道"))
            .map { it.second.invariantSeparatorsPath }
        assertTrue(paths.contains("/data/data/com.example.zhengdao/files/oc/pkg"))
        assertTrue(paths.contains("/sdcard/Download/证道/opencode"))
        // 工作区与私有人设一律不在清理范围内
        assertFalse(paths.any { it.contains("oc/workspace") })
        assertFalse(paths.any { it.contains("oc/xdg/config") })
    }
}
