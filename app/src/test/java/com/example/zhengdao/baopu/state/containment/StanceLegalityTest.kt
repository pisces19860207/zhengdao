// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 判据③：四档站位每档都要真过 655 四项。
// 这里全部是**真跑**：夹具把四档身体喂给 655 的 PoseValidity／Reachability，
// 741 只把结论摊成四项三态；另有一条等价性断言钉住「741 说不过的项＝655 说违规的项」，
// 防将来有人把转写写成重判（重判就会两套口径各说各话）。
package com.example.zhengdao.baopu.state.containment

import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.JointNames
import com.example.zhengdao.baopu.state.geometry.PoseValidity
import com.example.zhengdao.baopu.state.geometry.Reachability
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.ViolationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StanceLegalityTest {

    private fun audit(scene: StanceScene): StanceAudit = StanceLegality.audit(scene)

    private fun failed(audit: StanceAudit): Set<AuditItem> = audit.failures.toSet()

    @Test
    fun `判据三_四档站位各真跑655四项_四项全过`() {
        val audits = ContainmentFixtures.legalAudits()
        assertEquals("四档站位一档都不能少", Stance.entries.toSet(), audits.keys)
        for ((stance, audit) in audits) {
            assertEquals("${stance.label} 的四项必须各一条", 4, audit.readings.size)
            assertTrue(
                "${stance.label} 应当四项全过，实得 不过＝${audit.failures.map { it.label }} " +
                    "未证明＝${audit.unproven.map { it.label }}",
                audit.allPass,
            )
            assertTrue("${stance.label} 每项都要有话说", audit.readings.all { it.detail.isNotBlank() })
        }
    }

    @Test
    fun `判据三_夹具关节名与655关节名同形`() {
        assertEquals(JointNames.SHOULDER_L, ContainmentFixtures.leftName())
        assertEquals(JointNames.SHOULDER_L, ContainmentFixtures.jointName("shoulder", -1.0))
        assertEquals(JointNames.SHOULDER_R, ContainmentFixtures.jointName("shoulder", 1.0))
    }

    @Test
    fun `判据三_穿地必被抓_不是空转`() {
        val sunk = ContainmentFixtures.scene(Stance.STAND_FRONT, env = Environment(10.0, 10.0))
        val audit = audit(sunk)
        assertEquals("支撑面抬到脚底之上 ⇒ 只该穿地一项不过", setOf(AuditItem.GROUND), failed(audit))
        assertFalse(audit.allPass)
    }

    @Test
    fun `判据三_越限必被抓_不是空转`() {
        val over = ContainmentFixtures.scene(Stance.STAND_FRONT, angles = listOf(3.5, 0.0))
        val audit = audit(over)
        assertEquals("请求角越过限位 ⇒ 只该限位一项不过", setOf(AuditItem.LIMIT), failed(audit))
    }

    @Test
    fun `判据三_自碰撞必被抓_不是空转`() {
        val probe = SegmentProxy("probe", Sphere(Vec3(14.0, 130.0, 0.0), 5.0))
        val scene = ContainmentFixtures.scene(
            Stance.STAND_FRONT,
            body = ContainmentFixtures.standBody() + probe,
        )
        val audit = audit(scene)
        assertTrue("胸口插一颗球 ⇒ 自碰撞必须不过", AuditItem.SELF_COLLISION in failed(audit))
    }

    @Test
    fun `判据三_够不着必被抓_远了近了都不算够得着`() {
        val far = audit(ContainmentFixtures.scene(Stance.STAND_FRONT, span = 70.0))
        assertEquals("远超链长 ⇒ 只该可达一项不过", setOf(AuditItem.REACH), failed(far))
        val near = audit(ContainmentFixtures.scene(Stance.STAND_FRONT, span = 8.0))
        assertEquals("近到贴脸 ⇒ 同样不在链长可达区间内", setOf(AuditItem.REACH), failed(near))
    }

    @Test
    fun `判据三_741的可达三态与655三态逐档一致`() {
        val shoulder = ContainmentFixtures.shoulderOf(Stance.STAND_FRONT)
        val limb = ContainmentFixtures.chain(shoulder)
        for (span in listOf(8.0, 30.0, 45.0, 70.0)) {
            val scene = ContainmentFixtures.scene(Stance.STAND_FRONT, span = span)
            val mine = audit(scene).stateOf(AuditItem.REACH)
            val theirs = Reachability.ofChain(limb, span)
            val expected = when (theirs.state) {
                Reachability.State.REACHABLE -> AuditState.PASS
                Reachability.State.UNREACHABLE -> AuditState.FAIL
                Reachability.State.UNKNOWN -> AuditState.UNPROVEN
            }
            assertEquals("span=$span 的可达三态必须逐档照抄 655", expected, mine)
        }
    }

    @Test
    fun `判据三_741不合法项与655违规类别一一对应`() {
        val cases = listOf(
            ContainmentFixtures.scene(Stance.STAND_FRONT, env = Environment(10.0, 10.0)),
            ContainmentFixtures.scene(Stance.STAND_FRONT, angles = listOf(3.5, 0.0)),
            ContainmentFixtures.scene(Stance.STAND_FRONT, span = 70.0),
        )
        for (scene in cases) {
            val verdict = PoseValidity.check(scene.segments, scene.env, scene.chainPose, scene.adjacent)
            val kinds = verdict.violations.map { it.kind }.toSet()
            val expected = buildSet {
                if (ViolationKind.LIMIT_EXCEEDED in kinds) add(AuditItem.LIMIT)
                if (ViolationKind.SELF_COLLISION in kinds) add(AuditItem.SELF_COLLISION)
                if (ViolationKind.GROUND_PENETRATION in kinds || ViolationKind.SAND_PENETRATION in kinds) {
                    add(AuditItem.GROUND)
                }
            }
            val mine = failed(audit(scene)).toSet() - AuditItem.REACH
            assertEquals("非可达三项必须与 655 的违规类别逐项对齐", expected, mine)
        }
    }

    @Test
    fun `判据三_缺一项台账就拒绝参加判定`() {
        val short = ContainmentFixtures.audit(Stance.KNEEL).readings.drop(1)
        val error = runCatching { StanceAudit(Stance.KNEEL, short) }.exceptionOrNull()
        assertTrue("少一项必须当场拒，而不是当成过：$error", error is IllegalArgumentException)
    }

    @Test
    fun `判据三_目标物混进身体段就拒`() {
        val body = ContainmentFixtures.standBody()
        val scene = ContainmentFixtures.scene(Stance.STAND_FRONT)
        val error = runCatching {
            StanceScene(
                stance = scene.stance,
                segments = body,
                env = scene.env,
                chain = scene.chain,
                chainPose = scene.chainPose,
                target = body.first(),
                adjacent = scene.adjacent,
            )
        }.exceptionOrNull()
        assertTrue("目标物混进身体段会让自碰撞把它当自己人：$error", error is IllegalArgumentException)
    }

    @Test
    fun `判据三_夹具自身先自证_四档身体都真有段`() {
        for (stance in Stance.entries) {
            val body = ContainmentFixtures.body(stance)
            assertTrue("${stance.label} 的身体不能是空的", body.size >= 10)
            assertEquals("段名不许重复", body.size, body.map { it.name }.distinct().size)
        }
    }

    @Test
    fun `判据三_空身体空关节位姿一律拒`() {
        val scene = ContainmentFixtures.scene(Stance.KNEEL)
        val empty = runCatching {
            StanceScene(scene.stance, emptyList(), scene.env, scene.chain, scene.chainPose, scene.target)
        }.exceptionOrNull()
        assertTrue("空身体 ⇒ 四项会全空转成过：$empty", empty is IllegalArgumentException)
        val noJoints = runCatching {
            val bare = JointChain(listOf(scene.chain.joints.first()), Vec3.ZERO)
            val pose: ChainPose = bare.pose(listOf(0.0))
            StanceScene(scene.stance, scene.segments, scene.env, scene.chain, pose.copy(joints = emptyList()), scene.target)
        }.exceptionOrNull()
        assertTrue("位姿不带关节 ⇒ 限位项空转：$noJoints", noJoints is IllegalArgumentException)
    }
}
