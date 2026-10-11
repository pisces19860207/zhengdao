// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #8-B 的守卫：**`~/.hermes/tools` 里的东西默认一个都不许删**。
 *
 * 这一组测试锁的是"删错东西"这个最贵的错误——`~/.hermes/tools` 里 python / node / uv
 * 删掉就等于把 hermes 弄瘸，而它们和 chromium / ffmpeg 躺在同一个目录里、名字长得很像
 * （`uv-0.12.3-linux-arm64` vs `chromium-1208-linux-arm64`）。
 *
 * ⚠️ 2026-10-11（ERRATA E-092）：ffmpeg 从"可选"改成"必需"。真机上清掉它之后 hermes 每次
 * 启动都报 `install out of sync (ffmpeg: not installed or outdated)`——`pm/install.py` 的
 * `drift()` 只跳过 `package.optional`，ffmpeg 会进问题列表 ⇒ 它是必需包，清理入口不许碰。
 *
 * ⚠️ 2026-10-11 深夜（ERRATA E-115 补记）：**hermes 自己装的扩展一律保留**——先是浏览器工具
 * （`agent-browser` + `chromium`，E-114 同一条用户消息点名），随后桌面自动化 `cua-driver`
 * 一并划入 ⇒ `OPTIONAL_TOOL_NAMES` 变空表，`清理可选工具` 这条路成了空操作。
 * 这组测试因此改成断言「没有任何工具目录可清、清理调用不删任何东西」。
 */
class StorageAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dirWith(parent: File, name: String, mb: Int): File {
        val d = File(parent, name)
        d.mkdirs()
        if (mb > 0) {
            val f = File(d, "blob.bin")
            f.writeBytes(ByteArray(mb * 1024 * 1024))
        }
        return d
    }

    @Test
    fun `工具目录按词边界分类`() {
        // 2026-10-11（用户点名）：chromium / agent-browser 由「可选」升为「保留」——
        // 有它们 hermes 才能开网页（用户实测过），所以不再是可清项。
        assertEquals(StorageAudit.ToolKind.PINNED, StorageAudit.toolKind("chromium-1208-linux-arm64"))
        assertEquals(StorageAudit.ToolKind.PINNED, StorageAudit.toolKind("agent-browser-0.5.1"))
        // 2026-10-11 深夜：桌面自动化 cua-driver 也从「可选」升为「保留」（同一条理由：
        // hermes 自己装的扩展，有它 hermes 就多一种能力）⇒ OPTIONAL 表现在是空表。
        assertEquals(StorageAudit.ToolKind.PINNED, StorageAudit.toolKind("cua-driver-0.3.0-linux-arm64"))
        assertTrue("可选表现在应当是空的", StorageAudit.OPTIONAL_TOOL_NAMES.isEmpty())
        // E-092：ffmpeg 是 hermes 必需包，永远不许被清理入口扫到。
        assertEquals(StorageAudit.ToolKind.REQUIRED, StorageAudit.toolKind("ffmpeg-7.1.5-linux-arm64"))
        assertEquals(StorageAudit.ToolKind.CORE, StorageAudit.toolKind("python-3.14.7"))
        assertEquals(StorageAudit.ToolKind.CORE, StorageAudit.toolKind("node-26.0.0"))
        assertEquals(StorageAudit.ToolKind.CORE, StorageAudit.toolKind("uv-0.12.3-linux-arm64"))
        assertEquals(StorageAudit.ToolKind.CORE, StorageAudit.toolKind("npm-11.0.0"))
        assertEquals(StorageAudit.ToolKind.CORE, StorageAudit.toolKind("ripgrep-14.1.0"))
        // 前缀相同但不是同一个东西（AgentProcesses 的 `uvicorn` 教训）：不许误判成 uv。
        assertEquals(StorageAudit.ToolKind.OTHER, StorageAudit.toolKind("uvicorn-1.0"))
        assertEquals(StorageAudit.ToolKind.OTHER, StorageAudit.toolKind("nodejs-tools"))
        assertEquals(StorageAudit.ToolKind.OTHER, StorageAudit.toolKind("ffmpegx"))
        assertEquals(StorageAudit.ToolKind.OTHER, StorageAudit.toolKind(""))
    }

    @Test
    fun `明细里没有一个 hermes 工具是可清的`() {
        val tools = tmp.newFolder("tools")
        dirWith(tools, "cua-driver-0.3.0-linux-arm64", 2)
        dirWith(tools, "chromium-1208-linux-arm64", 3)
        dirWith(tools, "python-3.14.7", 1)
        dirWith(tools, "something-else", 1)

        val items = StorageAudit.toolItemsIn(tools)
        assertEquals(4, items.size)
        // 2026-10-11 深夜起：hermes 自己装的扩展一律「保留」，明细里逐行写明理由。
        assertFalse(items.first { it.label == "cua-driver-0.3.0-linux-arm64" }.removable)
        assertTrue(items.first { it.label == "cua-driver-0.3.0-linux-arm64" }.note.contains("保留"))
        assertFalse(items.first { it.label == "chromium-1208-linux-arm64" }.removable)
        assertTrue(items.first { it.label == "chromium-1208-linux-arm64" }.note.contains("保留"))
        assertFalse(items.first { it.label == "python-3.14.7" }.removable)
        assertFalse(items.first { it.label == "something-else" }.removable)
        // 可清项为 0 ⇒ ui/SettingsStorageActivity 的「清理可选工具」入口不会渲染（它按可清体积 > 0 判）。
        assertEquals(0, items.count { it.removable })
        // 按体积降序：最大的排在最前，用户一眼看到"哪里最占地方"。
        assertEquals("chromium-1208-linux-arm64", items.first().label)
    }

    @Test
    fun `可选表为空时清理一个工具目录都不删`() {
        val tools = tmp.newFolder("tools")
        val chromium = dirWith(tools, "chromium-1208-linux-arm64", 2)
        val browser = dirWith(tools, "agent-browser-0.5.1", 1)
        val cua = dirWith(tools, "cua-driver-0.3.0-linux-arm64", 1)
        val ffmpeg = dirWith(tools, "ffmpeg-7.1.5-linux-arm64", 1)
        val python = dirWith(tools, "python-3.14.7", 1)
        val uv = dirWith(tools, "uv-0.12.3-linux-arm64", 1)
        val other = dirWith(tools, "weird-tool", 1)

        val (count, freed) = StorageAudit.cleanOptionalToolDirs(tools)

        // 2026-10-11 深夜：用户决定 hermes 自己装的扩展一律保留 ⇒ 这条路现在是空操作。
        assertEquals(0, count)
        assertEquals(0L, freed)
        assertTrue("浏览器工具是用户点名保留的，不许碰", chromium.isDirectory)
        assertTrue("浏览器工具是用户点名保留的，不许碰", browser.isDirectory)
        assertTrue("桌面自动化也是用户点名保留的，不许碰", cua.isDirectory)
        assertTrue("ffmpeg 是 hermes 必需包，不许碰", ffmpeg.isDirectory)
        assertTrue("核心运行时不许碰", python.isDirectory)
        assertTrue("核心运行时不许碰", uv.isDirectory)
        assertTrue("未归类的目录不许碰", other.isDirectory)
    }

    @Test
    fun `自检缺口只报注册了却没落地的包`() {
        val tools = tmp.newFolder("tools")
        dirWith(tools, "python-3.14.7-linux-arm64", 0)
        File(tools, "facts.json").writeText(
            """
            {"schema":1,"packages":{
              "python":{"entry":"python-3.14.7-linux-arm64"},
              "ffmpeg":{"entry":"ffmpeg-9.0.1-linux-arm64"},
              "internal-ish":{"entry":"nope","internal":true}
            }}
            """.trimIndent()
        )
        val missing = StorageAudit.missingRegisteredPackages(File(tools, "facts.json"))
        assertEquals(listOf("ffmpeg" to "ffmpeg-9.0.1-linux-arm64"), missing)
        // 没有 facts.json（没装过 hermes）时安静返回空，不编造缺口。
        assertTrue(StorageAudit.missingRegisteredPackages(File(tools, "no-such.json")).isEmpty())
    }

    @Test
    fun `可选表为空时清理是空操作，不存在目录也安静返回零`() {
        val tools = tmp.newFolder("tools")
        val cua = dirWith(tools, "cua-driver-0.3.0-linux-arm64", 1)
        val (c1, f1) = StorageAudit.cleanOptionalToolDirs(tools)
        assertEquals("可选表为空 ⇒ 一个都不删", 0, c1)
        assertEquals(0L, f1)
        assertTrue(cua.isDirectory)
        val (c2, f2) = StorageAudit.cleanOptionalToolDirs(File(tmp.root, "nope"))
        assertEquals(0, c2)
        assertEquals(0L, f2)
    }

    @Test
    fun `留档数与自动清理同口径`() {
        // 公共区 rootfs 安装包保留「当前 + 上一个」= 2；StorageAudit 与 RootfsCache.pruneKeep、
        // CacheCleaner.prunableRootfsBytes 必须是同一个数，免得又出现两套账（E-073）。
        assertEquals(2, StorageAudit.KEEP_ROOTFS_ARCHIVES)
    }
}
