// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 岛叶落账与撤销面的判据测试：
// 低电调参有账行 · 一键回默认读回原值 · 用户设置前后逐字节一致 · 门拒即兜底。
package com.example.zhengdao.baopu.insula

import com.example.zhengdao.baopu.biogate.BioAnalogGate
import com.example.zhengdao.baopu.biogate.GateVerdict
import com.example.zhengdao.baopu.biogate.ThreeQuestionGate
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TuningLedgerTest {

    private val now = 1_700_000_000_000L

    /** 永远拒收的门（兜底侧夹具）。 */
    private val denyingGate = BioAnalogGate { GateVerdict.Deny("测试夹具：全拒") }

    /** 低电量夹具的调节动作（与 BioTuningTest 的纯判定一致）。 */
    private fun lowBatteryMoves(): List<TuningMove> {
        val vitals = BioVitals(
            batteryPct = 18, charging = false, hot = false,
            heapPressure = 0.3, storageFreeRatio = 0.5, actP95Ms = 2_000L, budgetLeft = 3,
        )
        return insulaAdjust(BioTuning.DEFAULT, vitals)
    }

    /** 工作区快照（相对路径 → 字节）：用户设置逐字节一致的判据读数。 */
    private fun snapshot(root: File): Map<String, ByteArray> = root.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes() }

    /** 判据主例：低电量夹具 ⇒ 并发度下调且**有账行**（tune 行带 from/to/basis）。 */
    @Test
    fun 判据_低电调参有账行且现值生效() = TempSandbox.use("zd521-insula-tune") { root ->
        val applied = applyTuningMoves(root, lowBatteryMoves(), now, ThreeQuestionGate)
        assertTrue("至少并发度与延后两条生效", applied.size >= 2)
        val rows = readInsulaRows(root)
        val tuneRows = rows.filter { it.optString("kind") == INSULA_KIND_TUNE }
        assertEquals("每个生效动作一行账", applied.size, tuneRows.size)
        val concurrencyRow = tuneRows.first { it.optString("param") == TunableParam.CONCURRENCY.wire }
        assertEquals("2", concurrencyRow.optString("from"))
        assertEquals("1", concurrencyRow.optString("to"))
        assertTrue("账行带依据", concurrencyRow.optString("basis").contains("电量 18%"))
        // 现值读回：并发度=1、延后=true（参数.json 生效）。
        val tuning = readTuning(root)
        assertEquals(BioTuning.MIN_CONCURRENCY, tuning.concurrency)
        assertTrue(tuning.deferHeavy)
    }

    /** 判据：一键回默认后读回**原值**（出厂档），且 reset 也是一行账、历史不删。 */
    @Test
    fun 判据_一键回默认读回原值且有账() = TempSandbox.use("zd521-insula-reset") { root ->
        applyTuningMoves(root, lowBatteryMoves(), now, ThreeQuestionGate)
        val beforeRows = readInsulaRows(root).size
        val was = resetTuningToDefaults(root, now + 1)
        assertEquals("回默认返回调节前的现值", BioTuning.MIN_CONCURRENCY, was.concurrency)
        assertEquals("读回原值（出厂档）", BioTuning.DEFAULT, readTuning(root))
        val rows = readInsulaRows(root)
        val reset = rows.first { it.optString("kind") == INSULA_KIND_RESET }
        assertEquals("用户", reset.optString("by"))
        assertTrue("历史账行不删（停用不删历史口径）", rows.size > beforeRows)
    }

    /** 判据：门全拒 ⇒ 不落账、参数不动（兜底侧；门拒＝没有这件器官）。 */
    @Test
    fun 兜底_门拒时参数与账全不动() = TempSandbox.use("zd521-insula-gate-deny") { root ->
        val applied = applyTuningMoves(root, lowBatteryMoves(), now, denyingGate)
        assertTrue(applied.isEmpty())
        assertEquals("参数回出厂档", BioTuning.DEFAULT, readTuning(root))
        assertTrue("账上零行", readInsulaRows(root).isEmpty())
    }

    /** 判据：用户设置前后逐字节一致——调节与回默认全程只动自己的两份文件。 */
    @Test
    fun 判据_用户设置前后逐字节一致() = TempSandbox.use("zd521-insula-settings") { root ->
        // 哨兵＝用户设置面（真机上是 SharedPreferences；JVM 夹具里以文件代表其字节事实）。
        val settings = File(root, "用户设置-哨兵.json")
        settings.writeText("{\"provider\":\"openrouter\",\"tier\":\"DEFAULT\",\"tools\":[\"read_file\"]}")
        val before = snapshot(root)
        applyTuningMoves(root, lowBatteryMoves(), now, ThreeQuestionGate)
        resetTuningToDefaults(root, now + 1)
        val after = snapshot(root)
        // 差集里只许出现岛叶自己的文件（参数.json／调节账.jsonl，都在「岛叶/」下）。
        // ByteArray 逐字节比（contentEquals）——默认 equals 是恒等，同一份内容也比不出来。
        val changed = (before.keys + after.keys).filter { key ->
            val b = before[key]
            val a = after[key]
            b == null || a == null || !b.contentEquals(a)
        }.toSet()
        assertTrue("改动的文件：$changed", changed.all { it.contains("岛叶") })
        assertTrue("用户设置哨兵逐字节不变", before.getValue("用户设置-哨兵.json").contentEquals(after.getValue("用户设置-哨兵.json")))
    }

    /** 判据：参数文件形状即白名单——账上自造的键读回时被丢弃，回落出厂档。 */
    @Test
    fun 判据_参数面认不得的键不生效() = TempSandbox.use("zd521-insula-whitelist") { root ->
        writeTuning(root, BioTuning.DEFAULT.copy(concurrency = 1))
        // 手工在参数面塞白名单外的键（模拟"有人绕过门直改文件"）。
        val f = insulaParamsFile(root)
        f.writeText(f.readText().trimEnd('}').removeSuffix(",") + ",\"authTier\":\"DELEGATED\"}")
        assertEquals("白名单外的键丢弃，认得的键保留", 1, readTuning(root).concurrency)
    }

    /** 判据：读数留底——每轮调节前有 observe 行（依据可回放）。 */
    @Test
    fun 判据_读数留底可回放() = TempSandbox.use("zd521-insula-observe") { root ->
        appendObserved(
            root,
            BioVitals(18, false, false, 0.3, 0.5, 2_000L, 3),
            "岛叶快照｜电量：18%（放电）",
            now,
        )
        val row = readInsulaRows(root).single()
        assertEquals(INSULA_KIND_OBSERVE, row.optString("kind"))
        assertEquals(18, row.optInt("battery"))
        assertTrue(row.optString("basis").contains("电量"))
    }

    /** 判据：现值压行（reset 账行的 from/to 格）四键按定序可读。 */
    @Test
    fun 判据_现值压行四键定序() {
        assertEquals(
            "并发度=2,批量大小=20,重试间隔=${BioTuning.DEFAULT_RETRY_BACKOFF_MS}ms,延后重活=false",
            BioTuning.DEFAULT.wireLine(),
        )
    }

    /** 判据（跨件兜底）：岛叶不可用（没文件/账根空）⇒ 读回出厂默认——纹状体按默认档走。 */
    @Test
    fun 兜底_岛叶不可用时读回出厂默认() = TempSandbox.use("zd521-insula-absent") { root ->
        assertEquals("空沙盒＝岛叶缺席 ⇒ 出厂档", BioTuning.DEFAULT, readTuning(root))
        assertTrue("缺席也不许有半截状态", readInsulaRows(root).isEmpty())
    }
}
