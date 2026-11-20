// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：单测夹具。
// 解析体（可手算期望值）＋由 660 真形体导出的代理体清单。零写盘、零临时目录。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.geometry.Box
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.ConvexHull
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.Ellipsoid
import com.example.zhengdao.baopu.state.geometry.Epa
import com.example.zhengdao.baopu.state.geometry.Gjk
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.JointLimit
import com.example.zhengdao.baopu.state.geometry.JointNames
import com.example.zhengdao.baopu.state.geometry.JointSpec
import com.example.zhengdao.baopu.state.geometry.PoseValidity
import com.example.zhengdao.baopu.state.geometry.Rot3
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import kotlin.math.PI

object CavityFixtures {

    fun sphere(id: String, c: Vec3, r: Double = 1.0): OrganProxy =
        OrganProxy(id, ProxyKind.SPHERE, Sphere(c, r))

    fun sphereAtZ(id: String, z: Double, r: Double = 1.0): OrganProxy = sphere(id, Vec3(0.0, 0.0, z), r)

    fun sphereAtX(id: String, x: Double, r: Double = 1.0): OrganProxy = sphere(id, Vec3(x, 0.0, 0.0), r)

    fun sphereAtY(id: String, y: Double, r: Double = 1.0): OrganProxy = sphere(id, Vec3(0.0, y, 0.0), r)

    fun capsule(id: String, a: Vec3, b: Vec3, r: Double): OrganProxy =
        OrganProxy(id, ProxyKind.CAPSULE, Capsule(a, b, r))

    fun ellipsoid(id: String, c: Vec3, radii: Vec3, rot: Rot3 = Rot3.IDENTITY): OrganProxy =
        OrganProxy(id, ProxyKind.ELLIPSOID, Ellipsoid(c, radii, rot))

    fun hull(id: String, verts: List<Vec3>): OrganProxy = OrganProxy(id, ProxyKind.HULL, ConvexHull(verts))

    fun boxProxy(id: String, c: Vec3, half: Vec3): OrganProxy =
        ProxySimplify.of(id, Box(c, half))

    /** 二十个顶点的凸包（远超特征点上限 12），用来钉截断记账。 */
    fun bigHull(id: String): OrganProxy = hull(id, bigHullVertices())

    private fun bigHullVertices(): List<Vec3> = (0 until 20).map { i ->
        val k = i * 2 - 19
        Vec3(k.toDouble(), (i % 5 - 2).toDouble(), (i % 7 - 3).toDouble())
    }

    /** 同一顶点集的乱序版本（偶数位在前、奇数位在后），用来钉「截断与排序与输入顺序无关」。 */
    fun bigHullShuffled(id: String): OrganProxy {
        val v = bigHullVertices()
        return hull(id, v.filterIndexed { i, _ -> i % 2 == 0 } + v.filterIndexed { i, _ -> i % 2 == 1 })
    }

    /** n 个重合顶点的退化凸包：特征点同址同深，用来把「截断后还剩几个」算成精确数。 */
    fun flatHull(id: String, at: Vec3, n: Int): OrganProxy = hull(id, List(n) { at })

    /** 沿 X 散开的 n 个半径 1 球：间距 stepCm 足够大时粗剔该把它们两两剔掉。 */
    fun spreadSpheres(n: Int, stepCm: Double): List<OrganProxy> =
        (0 until n).map { sphereAtX("s$it", it * stepCm) }

    /** 两两重叠的一团：粗剔一个都不许剔。 */
    fun overlappingCluster(n: Int): List<OrganProxy> = (0 until n).map { sphereAtX("c$it", it * 0.5) }

    /** 竖直堆叠：AABB 在 XZ 上必然重叠，Y 上分层。 */
    fun verticalStack(n: Int): List<OrganProxy> = (0 until n).map { sphereAtZ("v$it", it * 0.5) }

    val pairOfSpheres: Pair<OrganProxy, OrganProxy>
        get() = sphereAtZ("a", 0.0) to sphereAtZ("b", 1.5)

    fun fromBody(): List<OrganProxy> = ProxySimplify.fromBody(FormFixtures.state().proxies)

    fun bodyProxies() = FormFixtures.state().proxies

    fun anatomyZones() = FormFixtures.anatomy

    fun groundEnv() = Environment(0.0, 0.0)

    /** 十三区按 X 等距排开的球（半径 1）：相邻空隙＝spreadCm−2，推开演练用。 */
    fun zoneSpheres(spreadCm: Double): List<OrganProxy> =
        anatomyZones().mapIndexed { i, z -> sphere(z.zoneId, Vec3(i * spreadCm, 0.0, 0.0)) }

    /** 同上，但睾丸出左右两实例、右实例推到 farCm（钉「多实例区取最小空隙」）。 */
    fun zoneSpheresWithFarTwin(spreadCm: Double, farCm: Double): List<OrganProxy> =
        anatomyZones().mapIndexed { i, z -> sphere(z.zoneId, Vec3(i * spreadCm, 0.0, 0.0)) } +
            sphere("testis.r", Vec3(farCm, 0.0, 0.0))

    /**
     * 夹具链（第六百八十六批判据①的输入）：17 关节，杆长＝该关节代理体包围盒对角线，
     * 限位＝660 柔韧档缩放后的弧度限位。**测试侧从 660 产出推得，不编常数**；
     * 686 生产侧零装配，不替 699 建骨架链。
     */
    fun bodyChain(state: com.example.zhengdao.baopu.state.form.BodyState = FormFixtures.state()): Pair<JointChain, ChainPose> {
        val ps = ProxySimplify.fromBody(state.proxies)
        val diag = ps.associate { it.id to it.aabb().diagonalCm() }
        val limits = state.jointLimits.associate { it.joint to it.limit }
        val root = ps.first { it.id == JointNames.ROOT }.aabb().center()
        val chain = JointChain(
            JointNames.ALL.map { n ->
                JointSpec(n, requireNotNull(diag[n]) { "660 代理体缺关节 $n" }, Vec3.UNIT_Y,
                    requireNotNull(limits[n]) { "660 限位表缺关节 $n" })
            },
            root,
        )
        return chain to chain.pose(JointNames.ALL.map { 0.0 })
    }

    /** 越限姿态：首关节请求角超出 660 限位，用来钉「限位一项真的有牙」。 */
    fun beyondLimitPose(chain: JointChain): ChainPose =
        chain.pose(listOf(chain.joints.first().limit.maxRad + 1.0) + List(chain.joints.size - 1) { 0.0 })

    /** 两连杆解析链（闭式判定，不走采样）：杆长 3＋4，全周限位 ⇒ 可达距离带 [1, 7]。 */
    fun twoLinkChain(l1: Double = 3.0, l2: Double = 4.0): JointChain = JointChain(
        listOf(
            JointSpec(JointNames.ROOT, l1, Vec3.UNIT_Y, JointLimit(-PI, PI)),
            JointSpec(JointNames.SPINE, l2, Vec3.UNIT_Y, JointLimit(-PI, PI)),
        ),
        Vec3.ZERO,
    )

    /** 十七关节等长链（走 655 的采样路径，用来钉三态）。 */
    fun flatChain(lenCm: Double = 10.0): JointChain = JointChain(
        JointNames.ALL.map { JointSpec(it, lenCm, Vec3.UNIT_Y, JointLimit(-PI, PI)) },
        Vec3.ZERO,
    )

    /** 直接叫 655 的裁判数一遍违规条数（判据「686 只转写不重判」的对照组）。 */
    fun poseValidityViolationCount(ps: List<OrganProxy>, env: Environment): Int =
        PoseValidity.check(ps.map { SegmentProxy(it.id, it.shape) }, env).violations.size

    /**
     * 带符号表面间隙（厘米）：分离为正、互穿为 EPA 深度的负值、恰接触为零。
     * 判据②⑦要用它才有牙——655 的 Gjk.distance 对互穿只给非负距离，拿它断「不穿透」是空判。
     */
    fun signedGapCm(a: OrganProxy, b: OrganProxy): Double {
        val g = Gjk.distance(a.shape, b.shape)
        if (g.distanceCm > 0.0) return g.distanceCm
        return -(Epa.penetration(a.shape, b.shape)?.depthCm ?: 0.0)
    }

    /**
     * 660 形体在 655 自碰撞口径下必须声明的「天然接触」名单（**测试侧口径**）：
     * ① 13 区相邻（660 事实表，双实例展开）；② 骨架链相邻段；③ 骨盆与全部生殖器区实例；
     * ④⑤ 见 defaultPoseContacts（660 表没穷举的区↔区贴合＋默认站姿四肢贴躯干）。
     * ②—⑤ 都不在 660 事实表里（表只覆盖 13 区之间），由夹具声明，交付报告注明。
     */
    fun bodyAdjacent(ps: List<OrganProxy>, anatomy: List<AnatomyZone>): Set<Pair<String, String>> {
        val zoneIds = anatomy.map { it.zoneId }.toSet()
        val out = mutableSetOf<Pair<String, String>>()
        fun add(a: String, b: String) {
            if (a != b) out += if (a < b) a to b else b to a
        }
        for ((a, b) in CavityMap.adjacentPairs(ps, anatomy)) add(a, b)
        for ((a, b) in limbAdjacent) add(a, b)
        for (p in ps.filter { it.id.substringBefore('.') in zoneIds }) add(JointNames.ROOT, p.id)
        for ((a, b) in defaultPoseContacts) add(a, b)
        return out.toSet()
    }

    /**
     * ④⑤ 默认姿态下真会互穿、660 相邻表却没写到的对（**离线取证**：GJK 判相交＋EPA 深度四舍五入毫米，
     * 逐对记在行尾注释）。④＝区与区之间的解剖贴合（14 对）；⑤＝双臂垂在体侧与躯干／骨盆的接触（8 对）。
     * 名单只活在本单测试侧：660 相邻表要不要补全、四肢该不该离躯干这么近，都是 660 侧的事，见交付报告残留。
     * 公开给 `ProxyLegalityTest.天然接触名单只放真互穿的对` 逐条验真，不许拿声明当遮羞布。
     */
    val defaultPoseContacts = listOf(
        "cord.left" to "mons.mid", // 2
        "cord.right" to "mons.mid", // 2
        "corona.mid" to "frenulum.mid", // 4
        "corona.mid" to "meatus.mid", // 4
        "meatus.mid" to "prepuce.mid", // 9
        "radix.mid" to "raphe.mid", // 4
        "radix.mid" to "scrotum.mid", // 11
        "radix.mid" to "testis.left", // 3
        "radix.mid" to "testis.right", // 4
        "shaft.mid" to "corona.mid", // 20
        "shaft.mid" to "frenulum.mid", // 3
        "shaft.mid" to "scrotum.mid", // 9
        "sulcus.mid" to "frenulum.mid", // 1
        "testis.left" to "testis.right", // 1
        "elbow_l" to "hip_l", // 49
        "elbow_r" to "hip_r", // 49
        "root" to "elbow_l", // 21
        "root" to "elbow_r", // 21
        "spine" to "shoulder_l", // 8
        "spine" to "shoulder_r", // 8
        "wrist_l" to "hip_l", // 47
        "wrist_r" to "hip_r", // 47
    )

    private val limbAdjacent = listOf(
        JointNames.ROOT to JointNames.SPINE, JointNames.SPINE to JointNames.CHEST,
        JointNames.CHEST to JointNames.NECK, JointNames.NECK to JointNames.HEAD,
        JointNames.CHEST to JointNames.SHOULDER_L, JointNames.SHOULDER_L to JointNames.ELBOW_L,
        JointNames.ELBOW_L to JointNames.WRIST_L,
        JointNames.CHEST to JointNames.SHOULDER_R, JointNames.SHOULDER_R to JointNames.ELBOW_R,
        JointNames.ELBOW_R to JointNames.WRIST_R,
        JointNames.ROOT to JointNames.HIP_L, JointNames.HIP_L to JointNames.KNEE_L,
        JointNames.KNEE_L to JointNames.ANKLE_L,
        JointNames.ROOT to JointNames.HIP_R, JointNames.HIP_R to JointNames.KNEE_R,
        JointNames.KNEE_R to JointNames.ANKLE_R,
        JointNames.HIP_L to JointNames.HIP_R,
    )
}
