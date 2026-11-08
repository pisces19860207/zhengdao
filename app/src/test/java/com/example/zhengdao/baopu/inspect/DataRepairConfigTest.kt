// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十七批 · 类④ 配置漂移的**四段用例**（造漂移 ⇒ 体检发现 ⇒ 自修 ⇒ 再体检变好）。
//
// 这一路最容易被写坏的地方是"顺手兜一个默认值"：读不到就当 true、表里没有就照惯例改。
// 派工单把它钉死了——**判据表是唯一真源，表里没有的项只报不修**。所以下面这几条用例
// 一半在证"改得回去"，另一半在证"不该改的一个都不改"（后者红才是这条闸有用）。
//
// 用的是假 [ConfigAccess]：真适配器（`repair.NightWatchConfig`）要 Context＋SharedPreferences，
// JVM 单测里 `android.jar` 是桩（`isReturnDefaultValues`），拿它测判据只会测到桩。
// 适配器的形状由源码门禁钉（见 `HealthWiringGateTest`），真机读数另列验收项。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.inspect.DataRepair.ConfigCriteria
import com.example.zhengdao.baopu.inspect.DataRepair.ConfigRow
import com.example.zhengdao.baopu.inspect.DataRepair.ConfigItem
import com.example.zhengdao.baopu.repair.ConfigAccess
import com.example.zhengdao.baopu.repair.DataDoctor
import com.example.zhengdao.baopu.repair.RepairEntry
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class DataRepairConfigTest {

    private val root = File("build/tmp/repair447-config").absoluteFile.apply {
        deleteRecursively()
        mkdirs()
    }

    private val now = 1_790_000_000_000L

    /** 用例自己的判据表（447 的判据收表作参数，表是数据不是判据；511 起生产表是空的）。 */
    private val TEST_KEY = "cfg.test"
    private val TEST_ROWS = listOf(ConfigRow(TEST_KEY, "true", "测试依据行：447 用例自己的表"))

    /** 一个配置域的样子：值表、读不出的键、整个域读不动、写不回去（失败路径各有用例走）。 */
    private class FakeConfig(
        val values: MutableMap<String, String> = mutableMapOf(),
        val unreadable: MutableSet<String> = mutableSetOf(),
        var domainDown: Boolean = false,
        var refuseWrites: Boolean = false,
    ) : ConfigAccess {

        val writes = ArrayList<Pair<String, String>>()

        override fun read(key: String): String? =
            if (domainDown || key in unreadable) null else values[key]

        override fun observed(): List<ConfigItem> =
            if (domainDown) {
                listOf(ConfigItem("<配置域读不动>", "模拟"))
            } else {
                values.map { ConfigItem(it.key, it.value) }
            }

        override fun write(key: String, value: String): Boolean {
            if (refuseWrites) return false
            writes += key to value
            values[key] = value
            return true
        }
    }

    // ── 四段：造漂移 ⇒ 发现 ⇒ 改回 ⇒ 变好 ──────────────────────────────────

    @Test
    fun `造漂移_体检发现_改回判据表_再体检变好`() {
        val config = FakeConfig(mutableMapOf(TEST_KEY to "false"))

        // ① 发现：说得出「哪项、从几改到几、依据哪一行」
        val found = DataDoctor.diagnoseConfig(config, now, TEST_ROWS)
        assertEquals(RepairKind.CONFIG, found.kind)
        assertEquals(RepairVerdict.FIXABLE, found.verdict)
        assertTrue("要说原值：${found.reason}", found.reason.contains("原值=false"))
        assertTrue("要说判据表值：${found.reason}", found.reason.contains("判据表值=true"))
        assertTrue("要说出处的行号内容：${found.reason}", found.reason.contains(TEST_ROWS[0].basis))
        assertEquals(listOf(TEST_KEY), found.targets)

        // ② 自修
        val out = DataDoctor.repairConfig(config, root, now, TEST_ROWS)
        assertTrue("该修成：${out.note}", out.done)
        assertEquals("写回的正是判据表的值", listOf(TEST_KEY to "true"), config.writes)

        // ③ 再体检变好（不是"修好了"，是"对位"）
        val again = DataDoctor.diagnoseConfig(config, now, TEST_ROWS)
        assertEquals(RepairVerdict.NOTHING, again.verdict)
        assertTrue("要说全对位：${again.reason}", again.reason.contains("全对位"))
    }

    @Test
    fun `先记后修_账行里有哪项_原值_目标值_依据行`() {
        val config = FakeConfig(mutableMapOf(TEST_KEY to "false"))
        assertTrue(DataDoctor.repairConfig(config, root, now, TEST_ROWS).done)

        // 账上原样两行：intent 在 result 之前（读口回的是新→旧，所以这里直接看盘上的行序）
        val raw = RepairLedger.fileIn(root).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals("一次修复两行（先记后修）", 2, raw.size)
        val intent = requireNotNull(RepairEntry.parse(raw[0])) { "第一行该是能解开的 intent：${raw[0]}" }
        val result = requireNotNull(RepairEntry.parse(raw[1])) { "第二行该是能解开的 result：${raw[1]}" }
        assertEquals(RepairLedger.PHASE_INTENT, intent.phase)
        assertEquals(RepairLedger.PHASE_RESULT, result.phase)
        assertEquals("动手前先写清改哪项", TEST_KEY, intent.target)
        assertTrue(
            "intent 要带原值与目标值：${intent.reason}",
            intent.reason.contains("原值=false") && intent.reason.contains("判据表值=true"),
        )
        assertTrue("intent 要带依据哪一行：${intent.reason}", intent.reason.contains(TEST_ROWS[0].basis))
        assertTrue("依据能反查这趟体检：${intent.evidence}", intent.evidence.endsWith(":config"))
        assertEquals("修成了", RepairLedger.OUTCOME_DONE, result.outcome)
        assertEquals(RepairKind.CONFIG.wire, result.kind)
    }

    // ── 三条"不许" ───────────────────────────────────────────────────────────

    @Test
    fun `表外的项只报不修_且把它看到的值列进账`() {
        val config = FakeConfig(
            mutableMapOf(
                TEST_KEY to "true",
                "某个人为的键" to "别的值",
            ),
        )
        val plan = DataDoctor.diagnoseConfig(config, now, TEST_ROWS)
        assertEquals("表外项存在 ⇒ 只报，不动手", RepairVerdict.NEED_HUMAN, plan.verdict)
        assertTrue("要说「表里没有这一行」：${plan.reason}", plan.reason.contains("表里没有这一行"))
        assertTrue("要把看到的值列出来：${plan.reason}", plan.reason.contains("某个人为的键=别的值"))

        val out = DataDoctor.repairConfig(config, root, now, TEST_ROWS)
        assertFalse("需人处理不许硬修", out.done)
        assertTrue("要说清卡在需人处理：${out.note}", out.note.startsWith("需人处理："))
        assertEquals("一个键都没写", emptyList<Pair<String, String>>(), config.writes)
        assertFalse("没动手就不留账", RepairLedger.fileIn(root).exists())
    }

    @Test
    fun `读不出按未取样_绝不猜默认值`() {
        val config = FakeConfig(
            mutableMapOf(TEST_KEY to "false"),
            unreadable = mutableSetOf(TEST_KEY),
        )
        val plan = DataDoctor.diagnoseConfig(config, now, TEST_ROWS)
        assertEquals(RepairVerdict.NEED_HUMAN, plan.verdict)
        assertTrue("要说「未取样，不猜」：${plan.reason}", plan.reason.contains("未取样"))
        assertTrue("要指名哪一行没读出来：${plan.reason}", plan.reason.contains(TEST_KEY))
        assertFalse(DataDoctor.repairConfig(config, root, now, TEST_ROWS).done)
        assertEquals("读不出就一个键都不写", emptyList<Pair<String, String>>(), config.writes)
        assertFalse(RepairLedger.fileIn(root).exists())
    }

    @Test
    fun `写不回去就是没改成_失败也留一行痕`() {
        val config = FakeConfig(mutableMapOf(TEST_KEY to "false"))
        config.refuseWrites = true

        val out = DataDoctor.repairConfig(config, root, now, TEST_ROWS)
        assertFalse("写不回去不算修成", out.done)
        assertTrue("要说改成几比几：${out.note}", out.note.contains("0/1"))

        val rows = RepairLedger.read(root)
        assertEquals("intent＋failed 的 result 都要在", 2, rows.size)
        assertEquals(RepairLedger.OUTCOME_FAILED, rows[0].outcome)
    }

    @Test
    fun `整个配置域读不动_不许被读成没有表外项`() {
        val config = FakeConfig(mutableMapOf(TEST_KEY to "true"))
        config.domainDown = true
        val plan = DataDoctor.diagnoseConfig(config, now, TEST_ROWS)
        assertEquals("没看见≠没有：域读不动也要成为一条需人处理", RepairVerdict.NEED_HUMAN, plan.verdict)
        assertTrue("要让人看得见「是读不动，不是干净」：${plan.reason}", plan.reason.contains("配置域读不动"))
    }

    @Test
    fun `全对位时不动手也不留账`() {
        val config = FakeConfig(mutableMapOf(TEST_KEY to "true"))
        assertEquals(RepairVerdict.NOTHING, DataDoctor.diagnoseConfig(config, now, TEST_ROWS).verdict)
        assertFalse(DataDoctor.repairConfig(config, root, now, TEST_ROWS).done)
        assertEquals(emptyList<Pair<String, String>>(), config.writes)
        assertFalse("留了就像修过", RepairLedger.fileIn(root).exists())
    }

    // ── 判据表本体 ───────────────────────────────────────────────────────────

    /** 479 把那个开关删了，键名只在用例里当"表外键"的样本（生产侧零引用）。 */
    private val LEGACY_SWITCH_KEY = "auto_maintenance"

    @Test
    fun `判据表511起是空的_开关交给用户_加行必须说得出谁拍的板`() {
        assertEquals(
            "511 给了用户真写口（设置页那个「后台自动维护」开关）⇒ 判据表必须清空：" +
                "御医把用户按下去的「关」改回「开」就是盖掉她的选择（447 自己写过的移交项）",
            emptyList<String>(),
            ConfigCriteria.ROWS.map { it.key },
        )
        assertNull("表外键一律没有判据行", ConfigCriteria.rowOf(LEGACY_SWITCH_KEY))
        // 表是数据：递一张有出处的表它照样能用，每一行都得说得出依据（铁律④）
        TEST_ROWS.forEach { row ->
            assertTrue("${row.key} 这行没有依据出处（铁律④）", row.basis.isNotBlank())
            assertTrue("expected 是字符串口径：${row.expected}", row.expected.isNotBlank())
        }
    }

    @Test
    fun `空表就是没有可对位的项_不许把域里每个键都报成需人处理`() {
        // 511 之后生产表是空的：那个开关本来就是用户在设置页自己拨的，
        // 这时把域里看到的每个键都算"表外需人处理"是纯噪声（体检月月报一条没用的）。
        val plan = DataRepair.configPlan(
            readings = emptyList(),
            outside = listOf(ConfigItem(LEGACY_SWITCH_KEY, "false")),
            rows = emptyList(),
            evidence = "diagnose:$now:config",
        )
        assertEquals(RepairVerdict.NOTHING, plan.verdict)
        assertTrue("要说清是表空：${plan.reason}", plan.reason.contains("判据表为空"))
        assertFalse("表空时不许出现表外噪声：${plan.reason}", plan.reason.contains("判据表外"))
    }

    @Test
    fun `判据层不替调用方记表_假表也算得出来`() {
        // 递一张假表：假表里只有「假键」。真表里的 nightwatch_auto 在假表看来就是**表外项**——
        // 这条用例就是证判据层没偷偷去查全局那张表（查了就会把它当"对位的行"漏掉）。
        val plan = DataRepair.configPlan(
            readings = listOf(ConfigItem("假键", "跑偏的值")),
            outside = listOf(ConfigItem(TEST_KEY, "true")),
            rows = listOf(DataRepair.ConfigRow("假键", "判据值", "测试用的依据行")),
            evidence = "diagnose:$now:config",
        )
        assertEquals(RepairKind.CONFIG, plan.kind)
        assertEquals(RepairVerdict.FIXABLE, plan.verdict)
        assertEquals(listOf("假键"), plan.targets)
        assertTrue(
            "表外的 nightwatch_auto 要作为表外项报出来（判据层没偷偷查全局表）：${plan.reason}",
            plan.reason.contains("判据表外 1 项") && plan.reason.contains("${TEST_KEY}=true"),
        )
    }

    @Test
    fun `一轮只修一类_配置这一路自己也过同一道闸`() {
        assertNull(DataRepair.batchGuard(listOf(RepairKind.CONFIG)))
        val two = DataRepair.batchGuard(listOf(RepairKind.CONFIG, RepairKind.TEMP))
        assertNotNull("配置与缓存一起下单必须整单拒", two)
        assertTrue("要说收到几类：$two", two!!.contains("2 类"))
    }
}
