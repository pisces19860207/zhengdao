// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十一批（设计修订）· 判据「热加载：导入后**同一会话内**检索能命中新数据」。
//
// 这条判据只有真跑才看得见：文件写对了不等于读到的是新的。这里走完整一圈——
// 造一条真记忆 → 导出成 zip → 导进另一棵空目录 → 当场热加载 → **紧接着**召回。
// 中途不重建任何对象、不开新会话，用的就是"同一个进程、下一行代码"。
package com.example.zhengdao.backup

import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.MemoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupHotReloadTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd491-reload").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun mounts(global: File, project: File): List<BackupMount> = listOf(
        BackupMount("memory/global", global, BackupGroup.MEMORY, splitLedger = true),
        BackupMount("memory/project", project, BackupGroup.MEMORY, splitLedger = true),
    )

    /** 造一条**真的**记忆文件（走 MemoryStore.write：front-matter 由它渲染，不手捏格式）。 */
    private fun seedMemory(global: File, project: File): Int {
        val store = MemoryStore(global, project)
        val r = store.write(
            MemoryEntry(
                id = "1762400000000-称谓偏好",
                scope = MemoryScope.PROJECT,
                kind = MemoryKind.PREFERENCE,
                title = "称谓偏好",
                body = "用户喜欢被叫「老许」，回答尽量短句。",
                tags = listOf("称呼"),
                // 记忆的合格性判据要求"哪次会话提炼的必须记"（MemoryRules 的既有红线）
                sourceSession = "s-491",
                status = MemoryStatus.CONFIRMED,
                createdAt = 1_762_400_000_000L,
                updatedAt = 1_762_400_000_000L,
            ),
        )
        assertTrue("夹具：先造一条记忆（${r.reason}）", r.ok)
        return store.loadAll().size
    }

    private fun exportOf(global: File, project: File, dest: File): File {
        val walk = BackupWalker.walk(mounts(global, project))
        assertTrue("夹具：导出得有东西", walk.files.isNotEmpty())
        val out = BackupExporter.export(dest, walk.files, emptyList(), walk.skipped, ExportMeta("t", false, 1_762_400_000_000L))
        return (out as ExportOutcome.Ok).file
    }

    private fun importInto(zip: File, global: File, project: File): ImportPreview {
        val preview = (BackupImporter.open(zip, mounts(global, project), File(root, "work")) as OpenResult.Ready).preview
        val applied = BackupImporter.apply(preview, emptySet()) as ApplyOutcome.Ok
        assertTrue("应该写进去几条：$applied", applied.applied > 0)
        return preview
    }

    @Test
    fun 导入后当场热加载且同一进程里就能召回到新记忆() {
        val aGlobal = File(root, "A/global")
        val aProject = File(root, "A/project")
        seedMemory(aGlobal, aProject)
        val zip = exportOf(aGlobal, aProject, File(root, "b.zip"))

        val bGlobal = File(root, "B/global")
        val bProject = File(root, "B/project")
        // 导之前：B 这边什么都没有
        assertTrue("导入前 B 应该是空的", MemoryStore(bGlobal, bProject).loadAll().isEmpty())

        importInto(zip, bGlobal, bProject)

        // 热加载：同一进程、紧接着调（没有重启、没有新会话）
        val report = BackupHotReload.afterImport(
            setOf(BackupGroup.MEMORY),
            object : ReloadPorts {
                override fun reindexMemory(): Int = MemoryStore(bGlobal, bProject).reindex().entries.size
                override fun resyncSearch(): Boolean = true
            },
        )
        assertEquals("热加载应该重建到 1 条", 1, report.memoryEntries)
        assertTrue("检索索引这一路要如实报已同步", report.searchResynced)

        // 检索命中（这才是判据：不是"文件在"，是"搜得到"）
        val hits = MemoryStore(bGlobal, bProject).recall("老许")
        assertEquals("召回必须命中刚导入那条", 1, hits.picked.size)
        assertEquals("称谓偏好", hits.picked[0].title)
    }

    @Test
    fun 只导设置时不白跑索引重建() {
        val report = BackupHotReload.afterImport(
            setOf(BackupGroup.SETTINGS),
            object : ReloadPorts {
                override fun reindexMemory(): Int = error("只改设置不该碰索引")
                override fun resyncSearch(): Boolean = error("只改设置不该碰索引")
            },
        )
        assertEquals(0, report.memoryEntries)
        assertNotNull("跳过也要有一句理由", report.skippedReason)
    }

    @Test
    fun 索引重建失败要如实说而不是假装成功() {
        val report = BackupHotReload.afterImport(
            setOf(BackupGroup.MEMORY),
            object : ReloadPorts {
                override fun reindexMemory(): Int = throw IllegalStateException("库锁住了")
                override fun resyncSearch(): Boolean = false
            },
        )
        assertEquals(0, report.memoryEntries)
        assertNotNull("失败必须有一句人话", report.skippedReason)
        assertTrue("文案要点明文件已经写进去了", report.line().contains("写进去"))
    }
}
