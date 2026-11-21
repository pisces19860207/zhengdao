// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 判据③的夹具：四档站位各自的「身体」＋手臂链＋目标物。
// 为什么不用 660 的默认站姿：686 已实测 660 默认站姿双臂垂在体侧时腕/肘陷进骨盆与躯干
// （残留 4/5，左右各四对）⇒ 拿它当「合法姿态」会把 660 的待复查问题带成 741 的假红。
// 这里的四档身体是本单测试侧的最小凸体，**只用来喂 655 判四项**，不当人体模型用。
package com.example.zhengdao.baopu.state.containment

import com.example.zhengdao.baopu.state.geometry.Box
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.Ellipsoid
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.JointLimit
import com.example.zhengdao.baopu.state.geometry.JointNames
import com.example.zhengdao.baopu.state.geometry.JointSpec
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3

/** 四档站位的几何夹具（测试侧；数值是本单自拟的最小人体近似，不是 660 的事实表）。 */
object ContainmentFixtures {

    /** 肩到肘、肘到腕的连杆长（夹具自拟）。 */
    const val UPPER_ARM = 30.0
    const val FOREARM = 25.0

    /** 关节限位（弧度）：肩可前后摆，肘只能向前屈。 */
    const val SWING = 2.6
    const val FLEX = 2.6

    private const val SHOULDER = "shoulder"
    private const val ELBOW = "elbow"
    private const val WRIST = "wrist"
    private const val HIP = "hip"
    private const val KNEE = "knee"
    private const val ANKLE = "ankle"

    private fun v(x: Double, y: Double, z: Double) = Vec3(x, y, z)

    /** 左侧用 `_l`、右侧用 `_r`，与 655 的关节名同形（名字对齐由测试钉住）。 */
    private fun side(base: String, sign: Double): String = base + if (sign < 0.0) "_l" else "_r"

    private fun ellipsoid(name: String, c: Vec3, r: Vec3) = SegmentProxy(name, Ellipsoid(c, r))

    private fun capsule(name: String, a: Vec3, b: Vec3, r: Double) = SegmentProxy(name, Capsule(a, b, r))

    private fun ball(name: String, c: Vec3, r: Double) = SegmentProxy(name, Sphere(c, r))

    private fun foot(name: String, c: Vec3, half: Vec3) = SegmentProxy(name, Box(c, half))

    private val SIDES = listOf(-1.0, 1.0)

    fun chain(shoulder: Vec3): JointChain = JointChain(
        joints = listOf(
            JointSpec(SHOULDER, UPPER_ARM, Vec3.UNIT_Y, JointLimit(-SWING, SWING)),
            JointSpec(ELBOW, FOREARM, Vec3.UNIT_Y, JointLimit(0.0, FLEX)),
        ),
        rootPosCm = shoulder,
    )

    fun pose(chain: JointChain): ChainPose = chain.pose(listOf(0.0, 0.0))

    /** 目标物：一颗小球，摆在过肩点、朝 +X 伸出去 [span] 的位置（离身体侧面，不压到身体）。 */
    fun target(shoulder: Vec3, span: Double = 30.0): SegmentProxy =
        SegmentProxy("target", Sphere(Vec3(shoulder.x + span, shoulder.y, shoulder.z), 3.0))

    fun ground(): Environment = Environment(0.0, 0.0)

    /** 站在开口前：直立，双臂垂在体侧，双脚落地。 */
    fun standBody(): List<SegmentProxy> = listOf(
        ellipsoid(JointNames.ROOT, v(0.0, 95.0, 0.0), v(13.0, 9.0, 9.0)),
        ellipsoid(JointNames.SPINE, v(0.0, 112.0, 0.0), v(14.0, 10.0, 9.0)),
        ellipsoid(JointNames.CHEST, v(0.0, 130.0, 0.0), v(15.0, 10.0, 10.0)),
        capsule(JointNames.NECK, v(0.0, 140.0, 0.0), v(0.0, 146.0, 0.0), 4.0),
        ball(JointNames.HEAD, v(0.0, 155.0, 0.0), 9.0),
    ) + SIDES.flatMap { s ->
        listOf(
            capsule(side(SHOULDER, s), v(15.0 * s, 138.0, 0.0), v(22.0 * s, 136.0, 0.0), 5.0),
            capsule(side(ELBOW, s), v(22.0 * s, 136.0, 0.0), v(24.0 * s, 110.0, 0.0), 4.5),
            capsule(side(WRIST, s), v(24.0 * s, 110.0, 0.0), v(25.0 * s, 88.0, 0.0), 4.0),
            capsule(side(HIP, s), v(8.0 * s, 90.0, 0.0), v(9.0 * s, 58.0, 0.0), 7.0),
            capsule(side(KNEE, s), v(9.0 * s, 58.0, 0.0), v(9.0 * s, 18.0, 0.0), 5.5),
            foot(side(ANKLE, s), v(9.0 * s, 3.0, 4.0), v(5.0, 3.0, 10.0)),
        )
    }

    /** 侧身：整体偏转九十度——正面变窄（宽对 x 折半）、前后变长（深对 z 拉长），肩落在 z 向。 */
    fun sidleBody(): List<SegmentProxy> = listOf(
        ellipsoid(JointNames.ROOT, v(0.0, 95.0, 0.0), v(9.0, 9.0, 13.0)),
        ellipsoid(JointNames.SPINE, v(0.0, 112.0, 0.0), v(9.0, 10.0, 14.0)),
        ellipsoid(JointNames.CHEST, v(0.0, 130.0, 0.0), v(10.0, 10.0, 15.0)),
        capsule(JointNames.NECK, v(0.0, 140.0, 0.0), v(0.0, 146.0, 0.0), 4.0),
        ball(JointNames.HEAD, v(0.0, 155.0, 0.0), 9.0),
    ) + SIDES.flatMap { s ->
        listOf(
            capsule(side(SHOULDER, s), v(0.0, 138.0, -15.0 * s), v(0.0, 136.0, -22.0 * s), 5.0),
            capsule(side(ELBOW, s), v(0.0, 136.0, -22.0 * s), v(0.0, 110.0, -24.0 * s), 4.5),
            capsule(side(WRIST, s), v(0.0, 110.0, -24.0 * s), v(0.0, 88.0, -25.0 * s), 4.0),
            capsule(side(HIP, s), v(0.0, 90.0, -8.0 * s), v(0.0, 58.0, -9.0 * s), 7.0),
            capsule(side(KNEE, s), v(0.0, 58.0, -9.0 * s), v(0.0, 18.0, -9.0 * s), 5.5),
            foot(side(ANKLE, s), v(0.0, 3.0, -9.0 * s), v(10.0, 3.0, 5.0)),
        )
    }

    /** 跪：膝着地、小腿贴地向后、大腿竖着、上身直立。 */
    fun kneelBody(): List<SegmentProxy> = listOf(
        ellipsoid(JointNames.ROOT, v(0.0, 52.0, 0.0), v(13.0, 8.0, 9.0)),
        ellipsoid(JointNames.SPINE, v(0.0, 64.0, 0.0), v(14.0, 8.0, 9.0)),
        ellipsoid(JointNames.CHEST, v(0.0, 78.0, 0.0), v(15.0, 8.0, 10.0)),
        capsule(JointNames.NECK, v(0.0, 86.0, 0.0), v(0.0, 92.0, 0.0), 4.0),
        ball(JointNames.HEAD, v(0.0, 101.0, 0.0), 9.0),
    ) + SIDES.flatMap { s ->
        listOf(
            capsule(side(SHOULDER, s), v(15.0 * s, 84.0, 0.0), v(20.0 * s, 82.0, 0.0), 5.0),
            capsule(side(ELBOW, s), v(22.0 * s, 82.0, 0.0), v(24.0 * s, 60.0, 0.0), 4.5),
            capsule(side(WRIST, s), v(24.0 * s, 60.0, 0.0), v(25.0 * s, 40.0, 0.0), 4.0),
            capsule(side(HIP, s), v(8.0 * s, 48.0, 0.0), v(8.0 * s, 14.0, 0.0), 7.0),
            capsule(side(KNEE, s), v(8.0 * s, 10.0, 0.0), v(8.0 * s, 10.0, -30.0), 5.5),
            foot(side(ANKLE, s), v(8.0 * s, 4.0, -32.0), v(4.0, 4.0, 6.0)),
        )
    }

    /** 伏：整个身体趴在地上，双臂前伸，双腿后伸。 */
    fun proneBody(): List<SegmentProxy> = listOf(
        ellipsoid(JointNames.ROOT, v(0.0, 10.0, 22.0), v(13.0, 7.0, 9.0)),
        ellipsoid(JointNames.SPINE, v(0.0, 10.0, 8.0), v(14.0, 8.0, 9.0)),
        ellipsoid(JointNames.CHEST, v(0.0, 10.0, -8.0), v(15.0, 9.0, 10.0)),
        capsule(JointNames.NECK, v(0.0, 10.0, -18.0), v(0.0, 10.0, -22.0), 4.0),
        ball(JointNames.HEAD, v(0.0, 11.0, -30.0), 9.0),
    ) + SIDES.flatMap { s ->
        listOf(
            capsule(side(SHOULDER, s), v(21.0 * s, 11.0, -12.0), v(25.0 * s, 11.0, -16.0), 5.0),
            capsule(side(ELBOW, s), v(25.0 * s, 11.0, -16.0), v(27.0 * s, 11.0, -40.0), 4.5),
            capsule(side(WRIST, s), v(27.0 * s, 11.0, -40.0), v(28.0 * s, 11.0, -60.0), 4.0),
            capsule(side(HIP, s), v(8.0 * s, 10.0, 26.0), v(8.0 * s, 10.0, 50.0), 7.0),
            capsule(side(KNEE, s), v(8.0 * s, 10.0, 50.0), v(8.0 * s, 10.0, 86.0), 5.5),
            foot(side(ANKLE, s), v(8.0 * s, 6.0, 92.0), v(4.0, 4.0, 6.0)),
        )
    }

    fun body(stance: Stance): List<SegmentProxy> = when (stance) {
        Stance.STAND_FRONT -> standBody()
        Stance.SIDLE -> sidleBody()
        Stance.KNEEL -> kneelBody()
        Stance.PRONE -> proneBody()
    }

    /** 该档的肩点（手臂链的根）。 */
    fun shoulderOf(stance: Stance): Vec3 = when (stance) {
        Stance.STAND_FRONT -> v(22.0, 136.0, 0.0)
        Stance.SIDLE -> v(0.0, 136.0, -22.0)
        Stance.KNEEL -> v(22.0, 82.0, 0.0)
        Stance.PRONE -> v(25.0, 11.0, -16.0)
    }

    /** 夹具里用的关节名与 655 的关节名同形（测试另行钉住，防两套命名漂开）。 */
    fun jointName(base: String, sign: Double): String = side(base, sign)

    fun leftName(): String = side(SHOULDER, -1.0)

    /**
     * 天然接触名单：骨架相邻段与肢链相邻段。655 口径下这些对跳过自碰撞，
     * 其余任意两段互穿才算违规。
     */
    fun adjacent(): Set<Pair<String, String>> {
        val pairs = linkedSetOf(
            JointNames.ROOT to JointNames.SPINE,
            JointNames.SPINE to JointNames.CHEST,
            JointNames.CHEST to JointNames.NECK,
            JointNames.NECK to JointNames.HEAD,
        )
        for (s in SIDES) {
            val shoulder = side(SHOULDER, s)
            val elbow = side(ELBOW, s)
            val wrist = side(WRIST, s)
            val hip = side(HIP, s)
            val knee = side(KNEE, s)
            val ankle = side(ANKLE, s)
            pairs += JointNames.CHEST to shoulder
            pairs += shoulder to elbow
            pairs += elbow to wrist
            pairs += JointNames.ROOT to hip
            pairs += hip to knee
            pairs += knee to ankle
        }
        return pairs
    }

    /** 送审场景。默认四档都该过四项；要造非法姿态就在 [body]/[env]/[angles] 上动手。 */
    fun scene(
        stance: Stance,
        span: Double = 30.0,
        body: List<SegmentProxy> = body(stance),
        env: Environment = ground(),
        angles: List<Double> = listOf(0.0, 0.0),
    ): StanceScene {
        val shoulder = shoulderOf(stance)
        val limb = chain(shoulder)
        return StanceScene(
            stance = stance,
            segments = body,
            env = env,
            chain = limb,
            chainPose = limb.pose(angles),
            target = target(shoulder, span),
            adjacent = adjacent(),
        )
    }

    /** 四档各审一次（真跑 655）：这一组应当四项全过。 */
    fun legalAudits(): Map<Stance, StanceAudit> =
        StanceTable.stances().associateWith { StanceLegality.audit(scene(it)) }

    /** 手工台账：只用于判三态表，不跑几何。[overrides] 里给的项改成给定三态。 */
    fun audit(
        stance: Stance,
        overrides: Map<AuditItem, AuditState> = emptyMap(),
    ): StanceAudit = StanceAudit(
        stance = stance,
        readings = AuditItem.entries.map { item ->
            val state = overrides[item] ?: AuditState.PASS
            AuditReading(item, state, "夹具：${item.label}${state.label}")
        },
    )
}
