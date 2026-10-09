package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #8-B 的守卫：**只清可选工具（浏览器/媒体/自动化），核心运行时一个都不许碰**。
 *
 * 这一组测试锁的是"删错东西"这个最贵的错误——`~/.hermes/tools` 里 python / node / uv
 * 删掉就等于把 hermes 弄瘸，而它们和 chromium / ffmpeg 躺在同一个目录里、名字长得很像
 * （`uv-0.12.3-linux-arm64` vs `chromium-1208-linux-arm64`）。
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
        assertEquals(StorageAudit.ToolKind.OPTIONAL, StorageAudit.toolKind("chromium-1208-linux-arm64"))
        assertEquals(StorageAudit.ToolKind.OPTIONAL, StorageAudit.toolKind("ffmpeg-7.1.5-linux-arm64"))
        assertEquals(StorageAudit.ToolKind.OPTIONAL, StorageAudit.toolKind("cua-driver-0.3.0-linux-arm64"))
        assertEquals(StorageAudit.ToolKind.OPTIONAL, StorageAudit.toolKind("agent-browser-0.5.1"))
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
    fun `明细只把可选工具标成可清`() {
        val tools = tmp.newFolder("tools")
        dirWith(tools, "chromium-1208-linux-arm64", 2)
        dirWith(tools, "python-3.14.7", 1)
        dirWith(tools, "uv-0.12.3-linux-arm64", 1)
        dirWith(tools, "something-else", 1)

        val items = StorageAudit.toolItemsIn(tools)
        assertEquals(4, items.size)
        assertEquals(2L, items.first { it.label == "chromium-1208-linux-arm64" }.mb)
        assertTrue(items.first { it.label == "chromium-1208-linux-arm64" }.removable)
        assertTrue(items.first { it.label == "chromium-1208-linux-arm64" }.note.contains("重新下载"))
        assertFalse(items.first { it.label == "python-3.14.7" }.removable)
        assertFalse(items.first { it.label == "uv-0.12.3-linux-arm64" }.removable)
        assertFalse(items.first { it.label == "something-else" }.removable)
        // 按体积降序：最大的排在最前，用户一眼看到"哪里最占地方"。
        assertEquals("chromium-1208-linux-arm64", items.first().label)
    }

    @Test
    fun `清理只删可选工具，核心运行时与未归类目录原样留在原地`() {
        val tools = tmp.newFolder("tools")
        val chromium = dirWith(tools, "chromium-1208-linux-arm64", 2)
        val ffmpeg = dirWith(tools, "ffmpeg-7.1.5-linux-arm64", 1)
        val python = dirWith(tools, "python-3.14.7", 1)
        val uv = dirWith(tools, "uv-0.12.3-linux-arm64", 1)
        val other = dirWith(tools, "weird-tool", 1)

        val (count, freed) = StorageAudit.cleanOptionalToolDirs(tools)

        assertEquals(2, count)
        assertEquals(3L * 1024 * 1024, freed)
        assertFalse("可选工具要被删掉", chromium.exists())
        assertFalse("可选工具要被删掉", ffmpeg.exists())
        assertTrue("核心运行时不许碰", python.isDirectory)
        assertTrue("核心运行时不许碰", uv.isDirectory)
        assertTrue("未归类的目录不许碰", other.isDirectory)
    }

    @Test
    fun `清理是幂等的，且不存在目录时安静返回零`() {
        val tools = tmp.newFolder("tools")
        dirWith(tools, "agent-browser-0.5.1", 1)
        val (c1, f1) = StorageAudit.cleanOptionalToolDirs(tools)
        assertEquals(1, c1)
        assertTrue(f1 > 0)
        val (c2, _) = StorageAudit.cleanOptionalToolDirs(tools)
        assertEquals(0, c2)
        val (c3, f3) = StorageAudit.cleanOptionalToolDirs(File(tmp.root, "nope"))
        assertEquals(0, c3)
        assertEquals(0L, f3)
    }

    @Test
    fun `留档数与自动清理同口径`() {
        // 公共区 rootfs 安装包保留「当前 + 上一个」= 2；StorageAudit 与 RootfsCache.pruneKeep、
        // CacheCleaner.prunableRootfsBytes 必须是同一个数，免得又出现两套账（E-073）。
        assertEquals(2, StorageAudit.KEEP_ROOTFS_ARCHIVES)
    }
}
