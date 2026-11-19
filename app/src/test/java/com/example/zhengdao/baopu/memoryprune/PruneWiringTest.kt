// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryprune

import com.example.zhengdao.baopu.EntryCodec
import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryQuality
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.MemoryTiers
import com.example.zhengdao.testkit.TempSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七百五十二批 · 记忆修剪**装配层**的用例（判据①②④⑥的落地读数）：
 * 真目录、真文件（TempSandbox：建了必删）、真 [MemoryStore]——
 * 断言落在「文件与字节」上：物理文件数不减、原像写回字节级一致、
 * include-retired 仍可检索。
 */
class PruneWiringTest {

    private companion object {
        const val NOW = 1_770_000_000_000L
        const val DAY = MemoryQuality.DAY_MILLIS
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun entry(
        id: String,
        body: String,
        kind: MemoryKind = MemoryKind.FACT,
        createdAt: Long = NOW - 10 * DAY,
        useCount: Int = 0,
        status: MemoryStatus = MemoryStatus.CONFIRMED,
    ) = MemoryEntry(
        id = id,
        scope = MemoryScope.PROJECT,
        kind = kind,
        title = "条目$id",
        body = body,
        sourceSession = "s1",
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        useCount = useCount,
        asOf = createdAt,
    )

    /** 建一座两根记忆树（global/project 各一个 sandbox 子目录）＋壳＋回滚根＋工作区。 */
    private class Library(sandbox: File) {
        val globalRoot = File(sandbox, "global").apply { mkdirs() }
        val projectRoot = File(sandbox, "project").apply { mkdirs() }
        val rollbackRoot = File(sandbox, "rollback").apply { mkdirs() }
        val workspace = File(sandbox, "workspace").apply { mkdirs() }
        val store = MemoryStore(globalRoot = globalRoot, projectRoot = projectRoot)

        /** 直写条目文件（绕写入闸——闸本来就会拒收重复；存量重复正是本单的清理对象）。 */
        fun seed(e: MemoryEntry) {
            val dir = store.dirFor(e.scope)
            dir.mkdirs()
            File(dir, "${e.id}.md").writeText(EntryCodec.render(e), Charsets.UTF_8)
        }

        fun fileText(id: String): String? = store.findFile(id)?.readText(Charsets.UTF_8)

        fun mdFileCount(): Int = store.globalDir().listFiles().orEmpty().count { it.isFile && it.extension == "md" } +
            store.projectDir().listFiles().orEmpty().count { it.isFile && it.extension == "md" }
    }

    /** 夜间空闲跑一轮（窗口必开；「超期」用 30 天短周期——判据②可配，夹具不用真等 90 天）。 */
    private fun run(
        lib: Library,
        now: Long = NOW,
        indexSync: ((List<MemoryEntry>) -> Unit)? = null,
    ): PruneRoundResult = runPruneRoundCore(
        store = lib.store,
        workspace = lib.workspace,
        rollbackRoot = lib.rollbackRoot,
        nowMs = now,
        hourOfDay = 2,
        screenOn = false,
        engineBusy = false,
        indexSync = indexSync,
        retireAfterDays = 30L,
    )

    // ── 一轮全流程：应用、账、状态、读数 ─────────────────────────────────────

    @Test
    fun 一轮真跑_合并与退役落到文件_物理文件数不减() {
        TempSandbox.use("zd752-apply") { sandbox ->
            val lib = Library(sandbox)
            lib.seed(entry("a", "缓存刷新的时机在会话开始时执行一遍", createdAt = NOW - 3 * DAY))
            lib.seed(entry("b", "缓存刷新的时机在会话开始时执行一遍", createdAt = NOW - 2 * DAY))
            lib.seed(entry("f1", "某次核对的取值口径记录", createdAt = NOW - 40 * DAY))
            val before = lib.mdFileCount()
            val result = run(lib)
            assertFalse(result.aborted)
            assertEquals(1, result.appliedMerges)
            assertEquals(1, result.appliedRetires)
            // 主体：指针全集在盘上（判据①的落地读数——不只内存里对）。
            val survivorOnDisk = lib.store.loadAll().first { it.id == "a" }
            assertEquals(setOf("a", "b"), survivorOnDisk.mergedFrom.toSet())
            // 被吸收/退役条目：标 retired，文件原地还在（判据②磁盘不减）。
            assertEquals(MemoryStatus.RETIRED, lib.store.loadAll().first { it.id == "b" }.status)
            assertEquals(MemoryStatus.RETIRED, lib.store.loadAll().first { it.id == "f1" }.status)
            assertEquals("物理文件一个不增一个不减（不动物理文件）", before, lib.mdFileCount())
        }
    }

    @Test
    fun 一轮真跑_账面有总账与合并行_跳过明细点名反向清单() {
        TempSandbox.use("zd752-ledger") { sandbox ->
            val lib = Library(sandbox)
            lib.seed(entry("a", "缓存刷新的时机在会话开始时执行一遍", createdAt = NOW - 3 * DAY))
            lib.seed(entry("b", "缓存刷新的时机在会话开始时执行一遍", createdAt = NOW - 2 * DAY))
            lib.seed(entry("l1", "部署脚本里的坑：先停服务再换证书", kind = MemoryKind.LESSON, createdAt = NOW - 40 * DAY))
            run(lib)
            val rows = PruneLedger.read(PruneLedger.fileOf(lib.workspace))
            assertTrue(
                "总账行（判据⑥：合并 N / 退役 M / 跳过 K）",
                rows.any { it.what.contains("合并 1") && it.what.contains("退役 0") && it.what.contains("跳过 1") },
            )
            assertTrue(
                "合并行带指针全集（判据①账面：顺 id 都能回原文）",
                rows.any { it.why.contains("合并") && it.result.contains("a,b") },
            )
            assertTrue(
                "跳过明细点名反向清单（判据③账面）",
                rows.any { it.result.contains("反向清单") },
            )
        }
    }

    @Test
    fun 一轮真跑_状态文件记录天闸与撤销指针_读数投影可达() {
        TempSandbox.use("zd752-state") { sandbox ->
            val lib = Library(sandbox)
            lib.seed(entry("f1", "某次核对的取值口径记录", createdAt = NOW - 40 * DAY))
            val result = run(lib)
            val state = PruneDayGate.lastState(lib.workspace)
            assertEquals(result.round.dayKey, state?.lastRunDayKey)
            assertEquals("prev 记下修剪前的桶（撤销回它）", PruneDayGate.NEVER, state?.prevDayKey)
            assertTrue("可撤销（判据④入口旗）", state?.undoable == true)
            assertEquals("撤销指针＝回滚档名（本轮 id）", "${state?.roundId}.json", state?.rollbackName)
            val readout = pruneReadoutOf(state)
            assertTrue("读数行原样带出（判据⑥送达）", readout?.note?.contains("退役 1") == true)
        }
    }

    // ── 判据④：一键回滚（原像写回 → 字节级回到修剪前） ──────────────────────

    @Test
    fun 判据4_撤销后每条文件字节级回到修剪前_索引钩子收到恢复条目() {
        TempSandbox.use("zd752-undo") { sandbox ->
            val lib = Library(sandbox)
            lib.seed(entry("a", "缓存刷新的时机在会话开始时执行一遍", createdAt = NOW - 3 * DAY))
            lib.seed(entry("b", "缓存刷新的时机在会话开始时执行一遍", createdAt = NOW - 2 * DAY))
            lib.seed(entry("f1", "某次核对的取值口径记录", createdAt = NOW - 40 * DAY))
            val beforeTexts = listOf("a", "b", "f1").associateWith { lib.fileText(it)!! }
            val synced = mutableListOf<List<String>>()
            run(lib, indexSync = { entries -> synced += entries.map { it.id } })
            assertTrue(
                "前提自证：修剪真的动过文件",
                listOf("a", "b", "f1").any { lib.fileText(it) != beforeTexts[it] },
            )
            val undo = undoLatestPruneCore(
                lib.store, lib.workspace, lib.rollbackRoot, NOW + 60_000,
                indexSync = { entries -> synced += entries.map { it.id } },
            )
            assertTrue("撤销读数：${undo.message}", undo.ok)
            assertEquals("三条全部原样写回", listOf("a", "b", "f1"), undo.restoredIds.sorted())
            listOf("a", "b", "f1").forEach { id ->
                assertEquals("字节级还原（判据④）：$id", beforeTexts[id], lib.fileText(id))
            }
            assertTrue("索引钩子收到恢复条目（索引随文件重建）", synced.last().containsAll(listOf("a", "b", "f1")))
            val state = PruneDayGate.lastState(lib.workspace)
            assertEquals("状态回到修剪前：桶回 prev", PruneDayGate.NEVER, state?.lastRunDayKey)
            assertFalse("本轮标记清空——再点撤销＝没有可撤销", state?.undoable == true)
            assertTrue(
                "撤销在账面留一行（账只增不删）",
                PruneLedger.read(PruneLedger.fileOf(lib.workspace)).any { it.why.contains("撤销") },
            )
        }
    }

    @Test
    fun 判据4_没有可撤销的一轮_直说() {
        TempSandbox.use("zd752-undo-none") { sandbox ->
            val lib = Library(sandbox)
            val undo = undoLatestPruneCore(lib.store, lib.workspace, lib.rollbackRoot, NOW, indexSync = null)
            assertFalse(undo.ok)
            assertTrue(undo.message.contains("没有可撤销"))
        }
    }

    @Test
    fun 判据4_负例_回滚档读不回_不装成功() {
        TempSandbox.use("zd752-undo-bad") { sandbox ->
            val lib = Library(sandbox)
            lib.seed(entry("f1", "某次核对的取值口径记录", createdAt = NOW - 40 * DAY))
            run(lib)
            // 只删本批自己的回滚产物（不是用户记忆文件——夹具操作）
            val rollback = PruneRollback.fileOf(lib.rollbackRoot, PruneDayGate.lastState(lib.workspace)!!.roundId)
            assertTrue(rollback.delete())
            val undo = undoLatestPruneCore(lib.store, lib.workspace, lib.rollbackRoot, NOW + 60_000, indexSync = null)
            assertFalse("不能装作恢复成功", undo.ok)
            assertTrue(undo.message.contains("回滚档"))
            assertTrue("条目一个都没恢复", undo.restoredIds.isEmpty())
            assertEquals("条目仍是退役态", MemoryStatus.RETIRED, lib.store.loadAll().first { it.id == "f1" }.status)
        }
    }

    // ── 判据④的失败路径：回滚档写不上 ⇒ 整轮中止（一条不动） ─────────────────

    @Test
    fun 负例_回滚档写不上_整轮中止一条不动() {
        TempSandbox.use("zd752-abort-nowrite") { sandbox ->
            val lib = Library(sandbox)
            lib.seed(entry("f1", "某次核对的取值口径记录", createdAt = NOW - 40 * DAY))
            // 回滚根是一个**文件**不是目录 ⇒ 建档必失败
            val fileAsRoot = File(lib.rollbackRoot.parentFile, "rollback-as-file")
            fileAsRoot.writeText("not a dir")
            val result = runPruneRoundCore(
                store = lib.store,
                workspace = lib.workspace,
                rollbackRoot = fileAsRoot,
                nowMs = NOW,
                hourOfDay = 2,
                screenOn = false,
                engineBusy = false,
                indexSync = null,
                retireAfterDays = 30L,
            )
            assertTrue(result.aborted)
            assertTrue("中止有因（写前原像没落上）", result.abortWhy.contains("回滚档"))
            assertEquals("条目原样", MemoryStatus.CONFIRMED, lib.store.loadAll().first { it.id == "f1" }.status)
            assertNull("状态没写（天闸不耗——本轮没跑）", PruneDayGate.lastState(lib.workspace))
            assertTrue(
                "中止在账面留一行",
                PruneLedger.read(PruneLedger.fileOf(lib.workspace)).any { it.why.contains("中止") },
            )
        }
    }

    // ── 判据②：include-retired 检索（默认不召回／显式可召回且权重降） ─────────

    @Test
    fun 判据2_retired默认不召回_显式include可召回且权重降() {
        TempSandbox.use("zd752-include-retired") { sandbox ->
            val lib = Library(sandbox)
            lib.seed(entry("live", "甲主题的现行记录", createdAt = NOW - 3 * DAY))
            lib.seed(entry("dead", "乙主题的过期记录", createdAt = NOW - 40 * DAY))
            run(lib)
            assertEquals("dead 退役（40 天闲置 ≥ 30 天周期）", MemoryStatus.RETIRED, lib.store.loadAll().first { it.id == "dead" }.status)
            assertTrue("live 年轻照旧有效", lib.store.loadAll().first { it.id == "live" }.status == MemoryStatus.CONFIRMED)
            val default = lib.store.recall("主题记录")
            assertTrue("默认召回不带退役条目", default.picked.none { it.id == "dead" })
            val withRetired = lib.store.recall("主题记录", includeRetired = true)
            assertTrue("include-retired 仍可检索（判据②）", withRetired.picked.any { it.id == "dead" })
            assertEquals("活条目排在退役条目前（权重降不消失）", "live", withRetired.picked.first().id)
            assertTrue("退役条目文件仍在盘上（磁盘不减）", lib.fileText("dead")!!.isNotEmpty())
        }
    }

    @Test
    fun 判据2_retired不进注入_生命周期四态之外() {
        val retired = entry("f1", "某次核对的取值口径记录").copy(status = MemoryStatus.RETIRED)
        assertFalse("injectable 只认 CONFIRMED（退役自动落出注入）", MemoryTiers.injectable(retired))
        assertNull("四态之外（RETIRED 不进生命周期四态）", MemoryTiers.lifecycleOf(retired, NOW))
    }

    @Test
    fun 读数投影_没跑过或已撤销都回null() {
        assertNull(pruneReadoutOf(null))
        assertNull(
            pruneReadoutOf(
                PruneDayGate.State(PruneDayGate.NEVER, PruneDayGate.NEVER, "", "", 0, 0, 0, "", "", 0L),
            ),
        )
        val readout = pruneReadoutOf(
            PruneDayGate.State(5L, PruneDayGate.NEVER, "p1", "p1.json", 2, 3, 1, "合并 2 / 退役 3 / 跳过 1", "", NOW),
        )
        assertEquals("p1", readout?.roundId)
        assertTrue(readout?.note!!.contains("退役 3"))
    }
}
