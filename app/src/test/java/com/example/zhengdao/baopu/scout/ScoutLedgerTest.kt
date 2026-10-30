// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候账与能力清单落盘的直测（临时目录，真文件、真 JSON 行）。
//
// 钉住三件事：
// ① 一行一条**绝对快照**，读侧取最后一行 ＝ 当前态（驳回次数不会被读丢）；
// ② 坏行跳过但**计数**（不静默丢账）；"不可用没原因"这种半截行不许进账；
// ③ 一页清单是覆盖式派生视图（每次就是最新那一页）。
package com.example.zhengdao.baopu.scout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScoutLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun memoryDir(): File = tmp.newFolder("记忆")

    private fun rec(
        cap: Capability = Capability.STORAGE,
        state: ScoutState = ScoutState.DENIED,
        denials: Int = 0,
        mentioned: Boolean = false,
        reason: String = "",
        intent: String = "",
        at: Long = 1L,
    ) = ScoutRecord(cap, state, denials, at, intent, reason, "", mentioned, "PGT-AN10/16")

    private val device = ScoutDevice("PGT-AN10", "16", 36, "1.0.0", 100L)

    @Test fun `a fresh install reads an empty ledger without inventing state`() {
        val loaded = ScoutLedger(listOf(memoryDir())).load()
        assertTrue(loaded.records.isEmpty())
        assertNull(loaded.device)
        assertEquals(0, loaded.badLines)
    }

    @Test fun `append then load round trips every field`() {
        val dir = memoryDir()
        val ledger = ScoutLedger(listOf(dir))
        assertTrue(ledger.append(rec(state = ScoutState.REVOKED, mentioned = true, intent = "整理相册", at = 42L)))

        val loaded = ledger.load()
        val got = loaded.records.getValue(Capability.STORAGE)
        assertEquals(ScoutState.REVOKED, got.state)
        assertTrue(got.revokedMentioned)
        assertEquals("整理相册", got.lastIntent)
        assertEquals(42L, got.lastAtMs)
        assertEquals("PGT-AN10/16", got.device)
        assertTrue(ledger.file().isFile)
        assertEquals(File(dir, ScoutLimits.LEDGER_FILE), ledger.file())
    }

    @Test fun `the last row wins so a denial count is never read back as zero`() {
        val ledger = ScoutLedger(listOf(memoryDir()))
        ledger.append(rec(denials = 0))
        ledger.append(rec(denials = 1))
        ledger.append(rec(denials = 2))
        assertEquals(2, ledger.load().records.getValue(Capability.STORAGE).denials)
    }

    @Test fun `device fingerprint rides a meta row and comes back intact`() {
        val ledger = ScoutLedger(listOf(memoryDir()))
        ledger.appendDevice(device, 9L)
        assertEquals(device, ledger.load().device)
    }

    @Test fun `bad lines are skipped and counted while good lines still load`() {
        val dir = memoryDir()
        val ledger = ScoutLedger(listOf(dir))
        ledger.append(rec(cap = Capability.CAMERA, state = ScoutState.GRANTED))
        ledger.file().appendText("这行不是 JSON\n", Charsets.UTF_8)
        ledger.file().appendText("{\"kind\":\"probe\",\"cap\":\"unknown\",\"state\":\"granted\"}\n", Charsets.UTF_8)
        ledger.append(rec(cap = Capability.NETWORK, state = ScoutState.GRANTED))

        val loaded = ledger.load()
        assertEquals(2, loaded.badLines)
        assertEquals(ScoutState.GRANTED, loaded.records.getValue(Capability.CAMERA).state)
        assertEquals(ScoutState.GRANTED, loaded.records.getValue(Capability.NETWORK).state)
    }

    @Test fun `an unavailable row without a reason is not a fact and does not load`() {
        val dir = memoryDir()
        ScoutLedger(listOf(dir)).file().apply {
            parentFile?.mkdirs()
            writeText("{\"kind\":\"probe\",\"cap\":\"root\",\"state\":\"unavailable\",\"at\":1}\n", Charsets.UTF_8)
        }
        val loaded = ScoutLedger(listOf(dir)).load()
        assertTrue(loaded.records.isEmpty())
        assertEquals(1, loaded.badLines)
    }

    @Test fun `capability and state wire values are a closed set`() {
        assertNull(Capability.ofWire("nfc"))
        assertNull(ScoutState.ofWire("maybe"))
        Capability.entries.forEach { assertEquals(it, Capability.ofWire(it.wire)) }
        ScoutState.entries.forEach { assertEquals(it, ScoutState.ofWire(it.wire)) }
        assertEquals(4, ScoutState.entries.size)
    }

    @Test fun `the sheet is a derived page that gets overwritten each time`() {
        val dir = memoryDir()
        val ledger = ScoutLedger(listOf(dir))
        val first = ScoutSheet.compose(listOf(rec(cap = Capability.CAMERA, state = ScoutState.GRANTED)), 1_000L)
        assertTrue(ledger.saveSheet(first))
        assertTrue(ledger.readSheet()!!.contains("能直接用：相机"))

        val second = ScoutSheet.compose(listOf(rec(cap = Capability.CAMERA, state = ScoutState.DENIED)), 2_000L)
        assertTrue(ledger.saveSheet(second))
        assertEquals(second.render(), ledger.readSheet())
        assertEquals(File(dir, ScoutLimits.SHEET_FILE), ledger.sheetFile())
    }

    @Test fun `no sheet yet reads as null instead of an empty page`() {
        assertNull(ScoutLedger(listOf(memoryDir())).readSheet())
    }

    @Test fun `a broken memory path fails quietly without throwing`() {
        val notADir = tmp.newFile("记忆")
        assertFalse(ScoutLedger(listOf(notADir)).append(rec()))
        assertFalse(ScoutLedger(listOf(notADir)).saveSheet(ScoutSheet.compose(emptyList(), 1L)))
        assertTrue(ScoutLedger(listOf(notADir)).load().records.isEmpty())
        assertNotNull(notADir)
    }

    @Test fun `the workspace copy mirrors the private one and survives a reinstall`() {
        val authority = tmp.newFolder("私有")
        val mirror = tmp.newFolder("记忆")
        val ledger = ScoutLedger(listOf(authority, mirror))

        assertTrue(ledger.append(rec(denials = 1, intent = "整理相册")))

        // 权威那份与镜像那份都要有（前者权限被关也写、后者卸载重装还在）。
        assertTrue(File(authority, ScoutLimits.LEDGER_FILE).isFile)
        assertTrue(File(mirror, ScoutLimits.LEDGER_FILE).isFile)
        assertEquals(2, ledger.paths().size)

        // 卸载重装：App 私有那份没了，工作区的镜像还能把驳回次数与"当时想做的事"找回来。
        File(authority, ScoutLimits.LEDGER_FILE).delete()
        val recovered = ledger.load().records.getValue(Capability.STORAGE)
        assertEquals(1, recovered.denials)
        assertEquals("整理相册", recovered.lastIntent)
    }

    @Test fun `with two copies the newer row wins`() {
        val authority = tmp.newFolder("私有2")
        val mirror = tmp.newFolder("记忆2")
        val ledger = ScoutLedger(listOf(authority, mirror))
        ledger.append(rec(denials = 1, at = 10L))
        ledger.append(rec(denials = 2, at = 20L))
        assertEquals(2, ledger.load().records.getValue(Capability.STORAGE).denials)
    }
}
