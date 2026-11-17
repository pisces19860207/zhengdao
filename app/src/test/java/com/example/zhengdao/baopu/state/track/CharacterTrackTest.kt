// 第六百七十批 · 判据①–⑧＋追加判据（hard_veto／信号来源／拓扑锁死／参数域回放）。
// 规格：素材库/01-沦陷链（10 条）＋02-欲望图谱（3 条）；行为基准 character_track.py。
package com.example.zhengdao.baopu.state.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CharacterTrackTest {

    private fun positive() = TrackSignal(1, SignalSource.STRIATUM)
    private fun rejection() = TrackSignal(-1, SignalSource.AMYGDALA)
    private fun unknown() = TrackSignal(0, SignalSource.THALAMUS)

    /** 一轮标准推进（信号→轮末→进攻→接触），与参考实现场景 1 同形。 */
    private fun round(track: CharacterTrack, anchor: Boolean = false): String? {
        track.feed(positive())
        val adv = track.tick(anchor)
        track.attackerStep()
        track.touch(minOf(3, track.params.salienceLayerMax.getValue(track.salience)))
        assertTrue("每步不变量", track.invariantsHold())
        return adv
    }

    private fun tower(track: CharacterTrack, rounds: Int = 12) {
        repeat(rounds) { round(track) }
    }

    // ── 判据① 12 轮 S0→S6，每阶段恰 2 轮、零跳级 ─────────────────────────────
    @Test
    fun criterion1_twelve_rounds_two_per_stage_no_skip() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        for (r in 1..12) {
            val before = track.dStage
            round(track)
            assertTrue("零跳级：每步至多 +1", track.dStage - before in 0..1)
            assertEquals("每阶段恰 2 轮", minOf(6, r / 2), track.dStage)
        }
        assertEquals(6, track.dStage)
        assertEquals(4, track.salience)
        assertEquals(5, track.aStage)
        assertEquals(3, track.layer)
    }

    // ── 判据② 第 3 轮锚点：停留 2→1、加速 1 轮、仍逐级 ────────────────────────
    @Test
    fun criterion2_anchor_shortens_dwell_not_topology() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        val seq = mutableListOf<Int>()
        for (r in 1..6) {
            track.feed(positive())
            track.tick(anchorHit = r == 3)
            assertTrue(track.invariantsHold())
            seq.add(track.dStage)
        }
        assertEquals("锚点轮（第3轮）提前换阶段", listOf(0, 1, 2, 2, 3, 3), seq)
        val control = CharacterTrack(stance = Stance.DESIRE)
        repeat(3) { control.feed(positive()); control.tick() }
        assertEquals("无锚点第3轮仍在 S1（对照）", 1, control.dStage)
        assertEquals("锚点记在命中时的阶段", listOf(1), track.stateView().anchorsHit)
    }

    // ── 判据③ 高塔＋1 拒绝：Lv4→Lv3、进攻链同步降档 ──────────────────────────
    @Test
    fun criterion3_one_rejection_drops_everything() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        tower(track)
        assertEquals(4, track.salience)
        assertEquals(6, track.dStage)
        assertEquals(5, track.aStage)
        assertEquals(3, track.layer)
        track.feed(rejection())
        assertTrue(track.invariantsHold())
        assertEquals("显性度 Lv4→Lv3", 3, track.salience)
        assertEquals("防守同步降档 S6→S4", 4, track.dStage)
        assertEquals("进攻同步降档 F→E", 4, track.aStage)
        assertEquals("接触层 3 在 Lv3/S4 上限内保持", 3, track.layer)
        assertEquals("回退重置停留计数", 0, track.turns)
    }

    // ── 判据④ 打断 3 轮恢复：状态与惯性完整 ──────────────────────────────────
    @Test
    fun criterion4_interruption_keeps_state_and_inertia() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        tower(track, rounds = 5)
        track.holdInertia("层${track.layer}·被触碰")
        val before = track.stateView()
        val snapshotBefore = track.snapshot()
        repeat(3) { track.feed(unknown()) }
        val after = track.stateView()
        assertEquals("惯性不重置", before.inertia, after.inertia)
        assertEquals("阶段不变", before.dStage, after.dStage)
        assertEquals("进攻不变", before.aStage, after.aStage)
        assertEquals("接触层不变", before.layer, after.layer)
        assertEquals("显性度不变", before.salience, after.salience)
        assertEquals("锚点史不变", before.anchorsHit, after.anchorsHit)
        assertEquals("snapshot 逐字节不变", snapshotBefore, track.snapshot())
    }

    // ── 判据⑤ 单向门：仅靠显性度推进会红 ─────────────────────────────────────
    @Test
    fun criterion5_salience_alone_cannot_advance_stage() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        repeat(4) { track.feed(positive()) }
        assertEquals("显性度已到 Lv4", 4, track.salience)
        assertEquals("没有轮数推进 ⇒ 阶段不动", 0, track.dStage)
        assertEquals("进攻也不动", 0, track.aStage)
        track.tick()
        assertEquals("停留未满 ⇒ 高显性度也不抢跑", 0, track.dStage)
    }

    // ── 判据⑥ 双重约束：S1 破绽却 F 催逼 ⇒ 必须拒 ────────────────────────────
    @Test
    fun criterion6_dual_cap_blocks_F_at_S1() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        repeat(2) { track.feed(positive()); track.tick() }
        assertEquals("防守到 S1", 1, track.dStage)
        repeat(2) { track.feed(positive()) }
        assertEquals("显性度拉到 Lv4", 4, track.salience)
        repeat(6) { track.attackerStep() }
        assertTrue("S1 时进攻不得越过 B（只挂显性度一侧会到 F）", track.aStage <= 1)
        val msg = track.touch(3)
        assertTrue("S1 时层3必须拒：$msg", msg.startsWith("拒绝接触层3"))
        assertTrue("接触层不得越过阶段上限", track.layer <= 1)
        assertTrue(track.invariantsHold())
    }

    // ── 判据⑦ 无排斥确认不满足 ⇒ 拒绝推进（三条件逐条） ──────────────────────
    @Test
    fun criterion7_no_repulsion_gate_blocks_and_drops() {
        val variants = listOf(
            NoRepulsion(noRetreat = false),
            NoRepulsion(noVerbalReject = false),
            NoRepulsion(noMuscleRigid = false),
        )
        for (gate in variants) {
            val track = CharacterTrack(stance = Stance.DESIRE)
            track.feed(positive())
            track.feed(positive())
            assertEquals(2, track.salience)
            track.tick()
            val msg = track.feed(positive(), gate)
            assertEquals("任意一条不满足 ⇒ 显性度不升反降", "无排斥未确认→Lv1", msg)
            assertEquals(1, track.salience)
            assertNull("硬门拒推进", track.tick())
            assertEquals(0, track.dStage)
            assertTrue("接触也拒", track.touch(1).startsWith("拒绝接触层"))
            assertEquals("进攻不推进", "A观察", track.attackerStep())
        }
    }

    @Test
    fun criterion7_confirmed_signals_resume_advance() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        track.feed(positive())
        track.feed(positive())
        track.tick()
        track.feed(positive(), NoRepulsion(noRetreat = false))
        track.feed(positive())
        assertEquals("重新确认后显性度回升", 2, track.salience)
        assertEquals("防守→S1破冰", track.tick())
    }

    // ── 判据⑧ 摘要 ≤40 token、字段齐、同状态两次生成一致 ─────────────────────
    @Test
    fun criterion8_summary_budget_fields_determinism() {
        val anchors = AnchorConfig(
            mapOf(
                0 to listOf("被触碰", "被叫名字", "被命令"),
                1 to listOf("被叫名字"),
                2 to listOf("被触碰"),
                3 to listOf("被命令"),
            ),
        )
        val track = CharacterTrack(anchors = anchors, stance = Stance.DESIRE)
        tower(track, rounds = 12)
        track.holdInertia("层3·被触碰")
        track.recordAutonomous("私密场景走神")
        val line = track.summaryLine()
        assertTrue("token 预算：$line", TrackSummary.tokenEst(line) <= TrackSummary.TOKEN_BUDGET)
        for (field in listOf("[角色轨]", "｜层", "｜Lv", "·欲望", "｜锚", "｜惯性:")) {
            assertTrue("字段齐：缺 $field", line.contains(field))
        }
        assertEquals("同状态两次生成一致", line, track.summaryLine())
    }

    @Test
    fun criterion8_summary_worst_case_still_in_budget() {
        val anchors = AnchorConfig((0..6).associateWith { listOf("锚点名八字长", "第二个名字", "第三个名字") })
        val track = CharacterTrack(anchors = anchors, stance = Stance.DESIRE)
        repeat(12) { round(track, anchor = true) }
        repeat(2) { track.tick(anchorHit = true) }
        track.holdInertia("层3·被触碰·走神·忆起·联想·被叫名字·被命令")
        repeat(3) { track.recordAutonomous("自主事件") }
        val line = track.summaryLine()
        assertTrue("最坏配置仍在预算内：$line", TrackSummary.tokenEst(line) <= TrackSummary.TOKEN_BUDGET)
        assertEquals(line, track.summaryLine())
    }

    @Test
    fun criterion8_example_line_shape() {
        val track = CharacterTrack(
            anchors = AnchorConfig(mapOf(3 to listOf("被触碰"))),
            stance = Stance.DESIRE,
        )
        repeat(6) { track.feed(positive()); track.tick() }
        track.tick(anchorHit = true)
        track.attackerStep()
        track.attackerStep()
        track.touch(2)
        track.holdInertia("层2·被触碰")
        val line = track.summaryLine()
        assertTrue("锚点用配置名：$line", line.contains("锚[被触碰]"))
        assertTrue("惯性带冒号：$line", line.contains("惯性:层2·被触碰"))
        assertEquals("示例行＝约 25 token（tokenEst/1，御裁③钉死口径）行=$line", 25, TrackSummary.tokenEst(line))
        assertTrue(TrackSummary.tokenEst(line) <= TrackSummary.TOKEN_BUDGET)
    }

    // ── 追加③ hard_veto：三态归零＋写账＋3 轮试探不自恢复＋安全信号解除 ───────
    @Test
    fun veto_zeroes_three_states_and_writes_ledger() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        tower(track)
        track.hardVeto("联想")
        assertEquals("阶段→S0", 0, track.dStage)
        assertEquals("接触层→1", 1, track.layer)
        assertEquals("显性度→Lv1", 1, track.salience)
        assertTrue(track.vetoActive())
        assertTrue("进攻链被双重约束压回", track.aStage <= 1)
        val ledger = track.vetoHistory()
        assertEquals(1, ledger.size)
        assertEquals("联想", ledger[0].trigger)
        assertNull("尚未解除", ledger[0].releasedBy)
    }

    @Test
    fun veto_three_probe_rounds_do_not_self_recover() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        tower(track)
        track.hardVeto("僵硬")
        repeat(3) {
            track.feed(positive())
            track.tick()
            track.attackerStep()
            track.touch(1)
        }
        assertTrue("连续 3 轮试探不得自恢复", track.vetoActive())
        assertEquals(0, track.dStage)
        assertEquals(1, track.salience)
        assertEquals(1, track.layer)
        assertEquals("持续轮数写账", 3, track.vetoHistory()[0].heldTurns)
    }

    @Test
    fun veto_release_only_by_safety_signal() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        tower(track)
        track.hardVeto("话语")
        assertFalse("无否决时解除返回 false", CharacterTrack().releaseVeto(SafetySignal.USER_INPUT))
        assertTrue(track.releaseVeto(SafetySignal.CHARACTER_WITHDRAW))
        assertFalse(track.vetoActive())
        val row = track.vetoHistory()[0]
        assertEquals("角色主动撤离", row.releasedBy)
        track.feed(positive())
        track.feed(positive())
        track.tick()
        assertEquals("解除后信号驱动恢复推进", "防守→S1破冰", track.tick())
    }

    // ── 御裁② 新判据：veto 期间注入「高阶段＋高显性度」仍是 S0/层1/Lv1 ─────────
    @Test
    fun veto_period_discards_all_advance_inputs_and_releases_to_zero() {
        val track = CharacterTrack(stance = Stance.DESIRE)
        tower(track)
        assertEquals("塔先登顶", 6, track.dStage)
        track.holdInertia("层3·被触碰")
        track.hardVeto("离别")
        repeat(6) {
            track.feed(positive())
            assertNull(track.tick())
            track.attackerStep()
            track.touch(3)
        }
        assertEquals("veto 期间阶段锁 S0", 0, track.dStage)
        assertEquals("veto 期间显性度锁 Lv1", 1, track.salience)
        assertEquals("veto 期间接触层锁 1", 1, track.layer)
        assertEquals("进攻按双约束钳到上限 B（御裁②口径）", 1, track.aStage)
        assertEquals("窗口整体丢弃（不累积、不追溯）", emptyList<Int>(), track.stateView().window)
        assertEquals("惯性跨 veto 保留", "层3·被触碰", track.stateView().inertia)
        assertTrue(track.invariantsHold())
        track.releaseVeto(SafetySignal.USER_INPUT)
        assertEquals("解除后不是回原值", 0, track.dStage)
        assertEquals("解除后显性度从 Lv1 起重推", 1, track.salience)
        repeat(4) { round(track) }
        assertTrue("解除后重新按双约束推进", track.dStage >= 1)
    }

    // ── 追加① 信号来源：状态机不得自己产生信号（源码断言＋结构断言） ──────────
    @Test
    fun signals_only_from_external_sources() {
        val dir = File("src/main/java/com/example/zhengdao/baopu/state/track")
        assertTrue("track 包须存在", dir.isDirectory)
        val sources = dir.listFiles { f -> f.extension == "kt" }!!.joinToString("\n") { it.readText() }
        for (banned in listOf("Random(", "Math.random", "System.currentTimeMillis", "System.nanoTime", "kotlin.random")) {
            assertFalse("状态机不得自带信号源：$banned", sources.contains(banned))
        }
        assertTrue("feed 只收带来源的 TrackSignal", sources.contains("fun feed(signal: TrackSignal"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun signal_value_out_of_range_rejected() {
        TrackSignal(2, SignalSource.USER)
    }

    // ── 追加② 拓扑锁死：状态集/映射/版本钉住（改拓扑不升版 ⇒ 红） ─────────────
    @Test
    fun topology_is_pinned() {
        assertEquals(1, TRACK_SPEC_MAJOR)
        assertEquals(listOf("S0情境", "S1破冰", "S2唤醒", "S3掩饰", "S4憋声", "S5承受", "S6沦陷"), STAGE_NAMES)
        assertEquals(listOf("A观察", "B正当接触", "C越界", "D突破", "E掌控", "F催逼"), ATTACKER_NAMES)
        val p = TrackParams.DEFAULT
        assertEquals(mapOf(1 to 1, 2 to 3, 3 to 4, 4 to 6), p.salienceStageMax)
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 4, 4 to 5), p.salienceAttackerMax)
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 3), p.salienceLayerMax)
        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 5), p.attackerCapByStage)
        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 3, 5 to 3, 6 to 3), p.layerCapByStage)
        assertEquals("升2降1不对称", 2, p.advanceNeed)
        assertEquals(1, p.retreatNeed)
        assertEquals("无排斥确认恰三条件", 3, NoRepulsion::class.java.declaredFields.count { it.type == Boolean::class.javaPrimitiveType })
    }

    // ── 追加② 参数域：非法参数 fail-closed；合法参数走回放对拍 ────────────────
    @Test(expected = IllegalArgumentException::class)
    fun params_dwell_below_floor_rejected() {
        TrackParams(minDwell = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun params_anchor_factor_zero_rejected() {
        TrackParams(anchorFactor = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun params_missing_map_key_rejected() {
        TrackParams(salienceStageMax = mapOf(1 to 1, 2 to 3, 3 to 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anchors_more_than_three_rejected() {
        AnchorConfig(mapOf(0 to listOf("一", "二", "三", "四")))
    }

    @Test
    fun params_replay_min_dwell_three_still_invariant() {
        val track = CharacterTrack(params = TrackParams(minDwell = 3), stance = Stance.DESIRE)
        for (r in 1..18) {
            val before = track.dStage
            round(track)
            assertTrue("参数回放每步不变量", track.invariantsHold())
            assertTrue("参数回放零跳级", track.dStage - before in 0..1)
            assertEquals("min_dwell=3 ⇒ 每阶段恰 3 轮", minOf(6, r / 3), track.dStage)
        }
        val default = CharacterTrack(stance = Stance.DESIRE)
        repeat(2) { round(default) }
        val paramTwo = CharacterTrack(params = TrackParams(minDwell = 3), stance = Stance.DESIRE)
        repeat(2) { round(paramTwo) }
        assertNotEquals("参数改动产生不同序列（回放对拍有对象）", default.dStage, paramTwo.dStage)
    }
}