// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 纹状体接线的判据测试：出卡落账／门拒兜底／重活延后逐字节一致／
// 采纳只递许愿不落技能（灰度未过时技能库不含该条）。
package com.example.zhengdao.baopu.habit

import com.example.zhengdao.baopu.biogate.BioAnalogGate
import com.example.zhengdao.baopu.biogate.BioIntent
import com.example.zhengdao.baopu.biogate.GateVerdict
import com.example.zhengdao.baopu.biogate.ThreeQuestionGate
import com.example.zhengdao.baopu.insula.BioTuning
import com.example.zhengdao.baopu.wish.WishOutcome
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class HabitWiringTest {

    /** 最小规格（许愿确认回执的形状；测试只关心 wishId）。 */
    private fun emptySpec() = com.example.zhengdao.baopu.wish.WishSpec(
        goal = "test", input = "", output = "", candidates = emptyList(),
    )

    private val flow = listOf("write_file", "read_file", "list_dir")
    private val now = 1_700_000_000_000L

    @Before
    fun cleanSlots() {
        HabitCardStore.resetForTest()
    }

    @After
    fun cleanSlotsAgain() {
        HabitCardStore.resetForTest()
    }

    private fun fiveRuns(base: Long = now) = (0 until 5).map {
        RunSeq(runId = "r$it@$base", tools = flow, ok = true, rpe = RPE_PASS, atMs = base + it * 1_000L)
    }

    /** 永远拒收的门（兜底侧夹具）。 */
    private val denyingGate = BioAnalogGate { GateVerdict.Deny("测试夹具：全拒") }

    /** 工作区快照（相对路径 → 字节）：逐字节一致的判据读数。 */
    private fun snapshot(root: File): Map<String, ByteArray> = root.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(root).path to it.readBytes() }

    /** 快照逐字节一致（ByteArray 用 contentEquals，不用恒等——Map 默认 equals 认不出内容）。 */
    private fun byteIdentical(before: Map<String, ByteArray>, after: Map<String, ByteArray>): Boolean =
        before.keys == after.keys && before.keys.all { before.getValue(it).contentEquals(after.getValue(it)) }

    /** 判据主例：5 次全成 ⇒ 卡出槽、账有 suggest 行、去重状态记键。 */
    @Test
    fun 判据_五次全成出一轮卡且账有三类痕迹() = TempSandbox.use("zd521-habit-round") { root ->
        val emitted = runHabitRound(root, fiveRuns(), BioTuning.DEFAULT, now, ThreeQuestionGate)
        assertEquals(1, emitted!!.size)
        assertEquals(1, HabitCardStore.latest().size)
        val rows = readHabitRows(root)
        assertEquals(5, rows.count { it.optString("kind") == HABIT_KIND_OBSERVE })
        assertEquals(1, rows.count { it.optString("kind") == HABIT_KIND_SUGGEST })
        assertTrue(suggestedKeysWithTime(root).containsKey(flow.joinToString(">")))
    }

    /** 判据（负例）：门全拒 ⇒ 卡不出、suggest 行不落——兜底侧，但 observe 照记（读数是事实）。 */
    @Test
    fun 兜底_门拒时卡不出账不出() = TempSandbox.use("zd521-habit-gate-deny") { root ->
        val emitted = runHabitRound(root, fiveRuns(), BioTuning.DEFAULT, now, denyingGate)
        assertTrue(emitted!!.isEmpty())
        assertTrue(HabitCardStore.latest().isEmpty())
        val rows = readHabitRows(root)
        assertEquals(5, rows.count { it.optString("kind") == HABIT_KIND_OBSERVE })
        assertEquals(0, rows.count { it.optString("kind") == HABIT_KIND_SUGGEST })
    }

    /** 判据（兜底）：岛叶说延后重活 ⇒ 这轮整体让开，工作区**逐字节**不动。 */
    @Test
    fun 兜底_延后重活时工作区逐字节不动() = TempSandbox.use("zd521-habit-defer") { root ->
        // 先放一枚哨兵（代表"没有它时的现状"），再跑延后轮。
        File(File(root, "habit-seed"), "sentinel.txt").apply { parentFile.mkdirs() }.writeText("keep")
        val before = snapshot(root)
        val emitted = runHabitRound(root, fiveRuns(), BioTuning.DEFAULT.copy(deferHeavy = true), now, ThreeQuestionGate)
        assertNull("延后轮返回 null", emitted)
        assertTrue("延后轮不许动工作区任何一字节", byteIdentical(before, snapshot(root)))
        assertTrue(HabitCardStore.latest().isEmpty())
    }

    /** 判据（兜底）：没轨迹（空表）⇒ 空轮返回，上轮的卡原样保留（什么都不做，连卡都不动）。 */
    @Test
    fun 兜底_空轨迹时上轮卡保持原样() = TempSandbox.use("zd521-habit-empty") { root ->
        runHabitRound(root, fiveRuns(), BioTuning.DEFAULT, now, ThreeQuestionGate)
        val before = HabitCardStore.latest()
        assertEquals(1, before.size)
        val again = runHabitRound(root, emptyList(), BioTuning.DEFAULT, now + 1, ThreeQuestionGate)
        assertTrue(again!!.isEmpty())
        assertEquals("空轮不动卡槽", before, HabitCardStore.latest())
    }

    /** 判据：失败后冷却中 ⇒ 整轮让开（重试间隔管自己的节奏）。 */
    @Test
    fun 兜底_失败后冷却中整轮让开() = TempSandbox.use("zd521-habit-cooling") { root ->
        noteHabitFailure(root, now)
        val tuning = BioTuning.DEFAULT.copy(retryBackoffMs = 1_000_000L)
        val emitted = runHabitRound(root, fiveRuns(), tuning, now + 100, ThreeQuestionGate)
        assertNull("冷却中让开", emitted)
        // 冷却已过 ⇒ 照常跑。
        val after = runHabitRound(root, fiveRuns(), tuning, now + 2_000_000L, ThreeQuestionGate)
        assertEquals(1, after!!.size)
    }

    /** 判据（关键负例）：采纳只递许愿——**技能库不含该条**（沙盒里没有任何 SKILL.md 落地）。 */
    @Test
    fun 判据_采纳后技能库不含该条() = TempSandbox.use("zd521-habit-adopt") { root ->
        runHabitRound(root, fiveRuns(), BioTuning.DEFAULT, now, ThreeQuestionGate)
        val card = HabitCardStore.latest().single()
        var submitted: String? = null
        val notice = adoptHabit(
            root,
            card.id,
            WishSubmit { text ->
                submitted = text
                WishOutcome.AwaitingConfirmation("wish-1-test", emptySpec())
            },
            now + 1,
        )
        assertTrue("采纳要有回话", notice!!.contains("wish-1-test"))
        assertEquals("递的是卡片上定稿的许愿原话", card.wishText, submitted)
        // 灰度未过（还没确认、没试跑、没灰度）⇒ 技能库不含该条：沙盒里没有 SKILL.md。
        val skills = root.walkTopDown().filter { it.name == "SKILL.md" }.toList()
        assertTrue("不许有任何技能文件落地", skills.isEmpty())
        // 账行：adopt 行 by=用户；卡原位换成带 wishId 的（再点采纳不重复递）。
        val rows = readHabitRows(root)
        val adopt = rows.single { it.optString("kind") == HABIT_KIND_ADOPT }
        assertEquals("用户", adopt.optString("by"))
        assertEquals("wish-1-test", adopt.optString("wishId"))
        val updated = HabitCardStore.latest().single()
        assertEquals("wish-1-test", updated.wishId)
        val again = adoptHabit(root, card.id, WishSubmit {
            WishOutcome.AwaitingConfirmation("wish-2-test", emptySpec())
        }, now + 2)!!
        assertTrue("已递过的卡不再重复递（回话指回第一张）", again.contains("wish-1-test"))
    }

    /** 判据（兜底）：账根缺位（null）⇒ 采纳只回话，什么都不落。 */
    @Test
    fun 兜底_账根缺位时采纳不动盘() {
        HabitCardStore.publish(listOf(HabitSuggestion("habit:x", "a>b", listOf("a", "b"), 5, now, "原话")))
        val notice = adoptHabit(null, "habit:x", WishSubmit {
            WishOutcome.AwaitingConfirmation("wish-x", emptySpec())
        }, now)
        assertTrue(notice!!.contains("账根没装配"))
        assertFalse(HabitCardStore.latest().single().wishId.isNotEmpty())
    }

    /** 判据：同轮重复跑不重复出卡（去重状态生效——建议只提一次）。 */
    @Test
    fun 判据_同序列只建议一次() = TempSandbox.use("zd521-habit-dedupe") { root ->
        runHabitRound(root, fiveRuns(), BioTuning.DEFAULT, now, ThreeQuestionGate)
        HabitCardStore.resetForTest() // 人收了卡。
        val again = runHabitRound(root, fiveRuns(), BioTuning.DEFAULT, now + 5_000L, ThreeQuestionGate)
        assertTrue("已建议过的序列不再浮", again!!.isEmpty())
        assertTrue(HabitCardStore.latest().isEmpty())
    }

    /** 判据（源级）：批次限值——并发度决定一轮出几张卡（岛叶调它的真消费点）。 */
    @Test
    fun 判据_并发度限制一轮出卡数() = TempSandbox.use("zd521-habit-concurrency") { root ->
        val pools = (0 until 5).map { p ->
            (0 until 5).map {
                RunSeq("r$p-$it", listOf("t$p", "u$p"), true, RPE_PASS, now + p * 100_000L + it)
            }
        }.flatten()
        val tuning = BioTuning.DEFAULT.copy(concurrency = 2)
        val emitted = runHabitRound(root, pools, tuning, now, ThreeQuestionGate)
        assertEquals("岛叶的并发度=2 ⇒ 本轮至多两张新卡", 2, emitted!!.size)
    }

    /** 意图形状自查：出卡/采纳的过门意图各带可观察面（三问①在接线层的落点）。 */
    @Test
    fun 判据_接线层意图自带可观察面() {
        val intent = BioIntent(
            organ = ThreeQuestionGate.ORGAN_STRIATUM,
            action = ThreeQuestionGate.ACTION_SUGGEST,
            subject = "a>b",
            observable = "习惯账 suggest 行＋HabitCardStore 出现 habit 卡",
        )
        assertEquals(GateVerdict.Admit, ThreeQuestionGate.admit(intent))
    }
}
