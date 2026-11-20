// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryprune

import com.example.zhengdao.baopu.EntryCodec
import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryQuality
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.testkit.TempSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七百五十八批 · 前置门＋频率门＋豁免＋恢复/保留的**装配层**用例（757 判据①②④
 * 的落盘读数）：真目录、真文件（TempSandbox：建了必删）、真 [MemoryStore]。
 * 断言落在「文件与字节」上：门槛拦下＝零写零账零耗天闸；豁免 id 不退役；
 * 恢复＝字节级还原；保留清单真的挡住下一轮。
 */
class PruneGatesTest {

    private companion object {
        const val NOW = 1_770_000_000_000L
        const val DAY = MemoryQuality.DAY_MILLIS
    }

    // ── 夹具（照 PruneWiringTest 的 Library 房型） ──────────────────────────

    private fun entry(
        id: String,
        body: String,
        createdAt: Long = NOW - 10 * DAY,
        useCount: Int = 0,
        status: MemoryStatus = MemoryStatus.CONFIRMED,
    ) = MemoryEntry(
        id = id,
        scope = MemoryScope.PROJECT,
        kind = MemoryKind.FACT,
        title = "条目$id",
        body = body,
        sourceSession = "s1",
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        useCount = useCount,
        asOf = createdAt,
    )

    private class Library(sandbox: File) {
        val globalRoot = File(sandbox, "global").apply { mkdirs() }
        val projectRoot = File(sandbox, "project").apply { mkdirs() }
        val rollbackRoot = File(sandbox, "rollback").apply { mkdirs() }
        val workspace = File(sandbox, "workspace").apply { mkdirs() }
        val store = MemoryStore(globalRoot = globalRoot, projectRoot = projectRoot)

        fun seed(e: MemoryEntry) {
            val dir = store.dirFor(e.scope)
            dir.mkdirs()
            File(dir, "${e.id}.md").writeText(EntryCodec.render(e), Charsets.UTF_8)
        }

        fun fileText(id: String): String? = store.findFile(id)?.readText(Charsets.UTF_8)
    }

    /** 默认门槛参数：总开关开、每周、阈值 30 天（判据走参数，夹具不用真等 90 天）。 */
    private fun run(
        lib: Library,
        now: Long = NOW,
        enabled: Boolean = true,
        schedule: String = PruneSettings.SCHEDULE_WEEKLY,
        retireAfterDays: Long = 30L,
        keepIds: Set<String> = emptySet(),
        lastRunDayKey: Long? = null,
        indexSync: ((List<MemoryEntry>) -> Unit)? = null,
    ): GatedPruneOutcome {
        if (lastRunDayKey != null) {
            PruneDayGate.writeAfterRound(
                lib.workspace, lastRunDayKey, PruneDayGate.NEVER, "pOld", "",
                0, 0, 0, "", "", now,
            )
        }
        return runGatedPruneRoundCore(
            store = lib.store,
            workspace = lib.workspace,
            rollbackRoot = lib.rollbackRoot,
            nowMs = now,
            hourOfDay = 2,
            screenOn = false,
            engineBusy = false,
            indexSync = indexSync,
            enabled = enabled,
            schedule = schedule,
            retireAfterDays = retireAfterDays,
            keepIds = keepIds,
        )
    }

    private fun seededLib(sandbox: File, vararg entries: MemoryEntry): Library {
        val lib = Library(sandbox)
        entries.forEach { lib.seed(it) }
        return lib
    }

    private fun aged(id: String, days: Long, body: String = "某次核对的取值口径记录") =
        entry(id, body, createdAt = NOW - days * DAY)

    // ── 频率门（纯函数；757 §2.1.4 判据本体） ────────────────────────────────

    @Test
    fun 频率门_daly每日_gap0不跑_gap1跑() {
        val dayKey = PatrolScheduler.dayBucket(NOW)
        assertFalse("daily：今天已跑（gap=0）⇒ 不满足", pruneScheduleDue(PruneSettings.SCHEDULE_DAILY, NOW, dayKey))
        assertTrue("daily：隔了 1 天 ⇒ 满足", pruneScheduleDue(PruneSettings.SCHEDULE_DAILY, NOW, dayKey - 1))
    }

    @Test
    fun 频率门_weekly_gap6不跑_gap7跑() {
        val dayKey = PatrolScheduler.dayBucket(NOW)
        assertFalse("weekly：差 6 天 ⇒ 不满足", pruneScheduleDue(PruneSettings.SCHEDULE_WEEKLY, NOW, dayKey - 6))
        assertTrue("weekly：满 7 天 ⇒ 满足", pruneScheduleDue(PruneSettings.SCHEDULE_WEEKLY, NOW, dayKey - 7))
    }

    @Test
    fun 频率门_从没跑过必满足_off恒不满足() {
        assertTrue("weekly：NEVER ⇒ 满足", pruneScheduleDue(PruneSettings.SCHEDULE_WEEKLY, NOW, PruneDayGate.NEVER))
        assertTrue("daily：NEVER ⇒ 满足", pruneScheduleDue(PruneSettings.SCHEDULE_DAILY, NOW, PruneDayGate.NEVER))
        assertFalse("off：不管 last 是什么都拦", pruneScheduleDue(PruneSettings.SCHEDULE_OFF, NOW, PruneDayGate.NEVER))
        assertFalse("off：即使隔很久也拦", pruneScheduleDue(PruneSettings.SCHEDULE_OFF, NOW, PatrolScheduler.dayBucket(NOW) - 30))
    }

    @Test
    fun 频率门_时钟回拨不跑() {
        val dayKey = PatrolScheduler.dayBucket(NOW)
        // last 比 now 的桶还新（时钟回拨/跨天倒转）⇒ gap 为负 ⇒ 判不满足（不跑不写账）。
        assertFalse(pruneScheduleDue(PruneSettings.SCHEDULE_DAILY, NOW, dayKey + 1))
        assertFalse(pruneScheduleDue(PruneSettings.SCHEDULE_WEEKLY, NOW, dayKey + 5))
    }

    // ── 前置门＋门槛拦下＝零写零账零耗天闸（757 §三 行1/行2 生效层） ────────────

    @Test
    fun 门槛_总开关关_一条不动_零写零账() {
        TempSandbox.use("zd758-gate-disabled") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            val outcome = run(lib, enabled = false)
            assertTrue("门槛拦下", outcome.blocked)
            assertTrue(outcome.why.contains("总开关"))
            assertNull("零产物", outcome.result)
            assertFalse("账没落（文件不存在）", PruneLedger.fileOf(lib.workspace).isFile)
            assertFalse("状态没写（天闸不耗）", PruneDayGate.fileOf(lib.workspace).isFile)
            assertEquals("条目原样", MemoryStatus.CONFIRMED, lib.store.loadAll().first { it.id == "f1" }.status)
        }
    }

    @Test
    fun 门槛_周期off_同拦() {
        TempSandbox.use("zd758-gate-off") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            val outcome = run(lib, schedule = PruneSettings.SCHEDULE_OFF)
            assertTrue("周期＝off ⇒ 拦下", outcome.blocked)
            assertFalse("零写", PruneLedger.fileOf(lib.workspace).isFile)
            assertEquals(MemoryStatus.CONFIRMED, lib.store.loadAll().first { it.id == "f1" }.status)
        }
    }

    @Test
    fun 频率门_装配层_同天第二轮不跑_隔天跑() {
        TempSandbox.use("zd758-gate-frequency") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            val first = run(lib, schedule = PruneSettings.SCHEDULE_DAILY, retireAfterDays = 30)
            assertFalse("第一轮（NEVER ⇒ 满足）真跑", first.blocked)
            assertTrue(first.result!!.appliedRetires == 1)
            val ledgerLines = PruneLedger.fileOf(lib.workspace).readLines().size
            val stateBefore = PruneDayGate.lastState(lib.workspace)!!
            val second = run(lib, schedule = PruneSettings.SCHEDULE_DAILY)
            assertTrue("同一天第二轮被频率门拦下（叠加不替代）", second.blocked)
            assertTrue(second.why.contains("频率门"))
            assertEquals(
                "拦下＝零写（账行数不变）",
                ledgerLines,
                PruneLedger.fileOf(lib.workspace).readLines().size,
            )
            assertEquals("状态没被碰", stateBefore, PruneDayGate.lastState(lib.workspace))
        }
    }

    @Test
    fun 频率门_装配层_weekly_差6天不跑_差7天跑() {
        TempSandbox.use("zd758-gate-weekly") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            val dayKey = PatrolScheduler.dayBucket(NOW)
            val blocked = run(lib, schedule = PruneSettings.SCHEDULE_WEEKLY, lastRunDayKey = dayKey - 6)
            assertTrue("差 6 天 ⇒ 频率门拦下", blocked.blocked)
            assertEquals("拦下＝状态没被碰（天闸不耗；夹具预写的 lastRunDayKey 原样）", dayKey - 6, PruneDayGate.lastRunDayKey(lib.workspace))
            val due = run(lib, schedule = PruneSettings.SCHEDULE_WEEKLY, lastRunDayKey = dayKey - 7)
            assertFalse("满 7 天 ⇒ 真跑", due.blocked)
            assertTrue("退役真的应用了", due.result!!.appliedRetires == 1)
        }
    }

    // ── 阈值与豁免：喂给判据层的两个装配参数 ─────────────────────────────────

    @Test
    fun 阈值_从设置喂入_45天闲置_90不退_30退() {
        TempSandbox.use("zd758-retire-days") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 45))
            val keepDefault = run(lib, retireAfterDays = 90L)
            assertFalse(keepDefault.blocked)
            assertEquals("45 天闲置 ≥ 90 天周期？否——不退役", 0, keepDefault.result!!.round.retires.size)
            // 第一跑是空轮也写状态（天闸口径）——第二跑前把桶重置为 NEVER，只测阈值本身。
            val trimmed = run(lib, retireAfterDays = 30L, lastRunDayKey = PruneDayGate.NEVER)
            assertEquals("45 天闲置 ≥ 30 天周期 ⇒ 退役（阈值经装配层喂进 runPruneRound）", 1, trimmed.result!!.appliedRetires)
        }
    }

    @Test
    fun 判据_永久保留_豁免id不退役_清单外照常退役() {
        TempSandbox.use("zd758-keep-list") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40), aged("f2", days = 40, body = "另一条核对的口径记录"))
            assertTrue("前提：豁免清单写上了 f1", PruneKeepList.add(lib.workspace, "f1"))
            assertTrue("幂等：重复追加不炸", PruneKeepList.add(lib.workspace, "f1"))
            val outcome = run(lib, keepIds = PruneKeepList.ids(lib.workspace))
            val statuses = lib.store.loadAll().associate { it.id to it.status }
            assertEquals("豁免 id 不退役（喂 runPruneRound 前已剔除）", MemoryStatus.CONFIRMED, statuses["f1"])
            assertEquals("清单外的照常退役", MemoryStatus.RETIRED, statuses["f2"])
            assertEquals("本轮只退役 1 条", 1, outcome.result!!.appliedRetires)
            assertEquals("豁免清单原样（只加豁免，不删行）", setOf("f1"), PruneKeepList.ids(lib.workspace))
        }
    }

    @Test
    fun 负例_豁免清单没接上_同样的条目必退役() {
        TempSandbox.use("zd758-keep-negative") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            val noKeep = run(lib, keepIds = emptySet())
            assertEquals("不带豁免 ⇒ f1 照退（判据不是恒真的）", 1, noKeep.result!!.appliedRetires)
            assertEquals(MemoryStatus.RETIRED, lib.store.loadAll().first { it.id == "f1" }.status)
        }
    }

    // ── 恢复／永久保留（757 §2.2 三动作的装配核） ─────────────────────────────

    @Test
    fun 恢复_原像字节级写回_索引随文件重建() {
        TempSandbox.use("zd758-restore") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            val before = lib.fileText("f1")!!
            run(lib, retireAfterDays = 30L)
            assertEquals("前提：真退役了", MemoryStatus.RETIRED, lib.store.loadAll().first { it.id == "f1" }.status)
            val synced = mutableListOf<List<String>>()
            val result = restoreRetiredCore(lib.store, lib.workspace, lib.rollbackRoot, "f1") { entries ->
                synced += entries.map { it.id }
            }
            assertTrue("恢复读数：${result.message}", result.ok)
            assertEquals("f1", result.restoredId)
            assertEquals("字节级还原（752 restoreOne 同房型）", before, lib.fileText("f1"))
            assertEquals("恢复后回到活状态", MemoryStatus.CONFIRMED, lib.store.loadAll().first { it.id == "f1" }.status)
            assertTrue("索引钩子收到恢复条目", synced.last().contains("f1"))
        }
    }

    @Test
    fun 恢复_roundId已翻篇或档里无此条_如实说不装成功() {
        TempSandbox.use("zd758-restore-missing") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            run(lib, retireAfterDays = 30L)
            // 状态文件的 roundId 翻到新一轮（757 §六 已知边界：本轮回滚档无此条）。
            PruneDayGate.writeAfterRound(
                lib.workspace, PatrolScheduler.dayBucket(NOW), PruneDayGate.NEVER, "pLater", "",
                0, 0, 0, "", "", NOW,
            )
            val result = restoreRetiredCore(lib.store, lib.workspace, lib.rollbackRoot, "f1", indexSync = null)
            assertFalse(result.ok)
            assertEquals("757 §六 的逐字边界文案", "本轮回滚档无此条，无法恢复", result.message)
            assertEquals("条目仍是退役态", MemoryStatus.RETIRED, lib.store.loadAll().first { it.id == "f1" }.status)
            // 从没跑过 ⇒ 同一句（不许装成功）。
            val fresh = File(lib.workspace.parentFile, "fresh").apply { mkdirs() }
            val freshLib = Library(fresh)
            freshLib.seed(aged("f2", days = 40))
            val none = restoreRetiredCore(freshLib.store, freshLib.workspace, freshLib.rollbackRoot, "f2", indexSync = null)
            assertFalse(none.ok)
            assertEquals("本轮回滚档无此条，无法恢复", none.message)
        }
    }

    @Test
    fun 恢复_写回没成_报恢复失败() {
        TempSandbox.use("zd758-restore-fail") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            run(lib, retireAfterDays = 30L)
            // 夹具把条目文件挪走 ⇒ findFile 找不到 ⇒ 恢复失败（如实，不装成功）。
            lib.store.findFile("f1")!!.delete()
            val result = restoreRetiredCore(lib.store, lib.workspace, lib.rollbackRoot, "f1", indexSync = null)
            assertFalse(result.ok)
            assertTrue("失败文案点名「恢复失败」", result.message.contains("恢复失败"))
        }
    }

    @Test
    fun 永久保留_恢复成功且清单写上_失败如实() {
        TempSandbox.use("zd758-keep") { sandbox ->
            val lib = seededLib(sandbox, aged("f1", days = 40))
            run(lib, retireAfterDays = 30L)
            val result = keepRetiredCore(lib.store, lib.workspace, lib.rollbackRoot, "f1", indexSync = null)
            assertTrue("保留读数：${result.message}", result.ok)
            assertTrue(result.message.contains("永久保留"))
            assertEquals("恢复到活状态", MemoryStatus.CONFIRMED, lib.store.loadAll().first { it.id == "f1" }.status)
            assertEquals("豁免清单有了它", setOf("f1"), PruneKeepList.ids(lib.workspace))
        }
    }
}
