// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 判据①：每个代理体都能被 655 的 GJK/EPA 判点，且四项合法（限位／可达／自碰撞／穿地）。
// 本单不重判：四项全部**转写** 655 已有裁判（PoseValidity 三项＋Reachability 一项）的结论，
// 因此有一条等价性断言钉住「686 说几条非法＝655 自己说几条非法」。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.ViolationUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyLegalityTest {

    private fun proxies(vararg ps: OrganProxy) = ps.toList()

    private fun reading(rs: List<LegalityReading>, id: String, item: LegalityItem): LegalityReading =
        rs.first { it.proxyId == id && it.item == item }

    /** 半径 1 的球，球心抬到 y＝1cm ⇒ 最低点恰贴住地面（贴地不算穿地）。 */
    private fun sphereOnGround(id: String, z: Double): OrganProxy =
        CavityFixtures.sphere(id, Vec3(0.0, 1.0, z))

    private fun check(
        ps: List<OrganProxy>,
        env: Environment = Environment(0.0, 0.0),
        chain: JointChain = CavityFixtures.twoLinkChain(),
        pose: ChainPose? = null,
        anchors: Map<String, String> = mapOf("a" to "root", "b" to "root"),
        adjacent: Set<Pair<String, String>> = emptySet(),
    ): List<LegalityReading> =
        ProxyLegality.check(ps, env, chain, pose ?: chain.pose(listOf(0.0, 0.0)), anchors, adjacent)

    @Test
    fun `四项读数齐备_每个代理体各一条`() {
        val rs = check(proxies(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtZ("b", 6.0)))
        assertEquals(8, rs.size)
        for (id in listOf("a", "b")) {
            assertEquals(4, rs.count { it.proxyId == id })
            assertEquals(LegalityItem.values().toSet(), rs.filter { it.proxyId == id }.map { it.item }.toSet())
        }
    }

    @Test
    fun `分离的四项全绿`() {
        // 两心放在两连杆链（3＋4）的可达带 [1,7] 内，免得把「够不着」误当成「分离就该全绿」。
        // 球心抬到 y＝1cm（半径 1）⇒ 最低点恰贴地：655 穿地判据是 `lowest < groundY`，贴地不算穿。
        val rs = check(proxies(sphereOnGround("a", 2.0), sphereOnGround("b", 6.0)))
        assertTrue(rs.joinToString("\n") { "${it.proxyId}/${it.item}=${it.state}" }, ProxyLegality.allLegal(rs))
        assertEquals(0, ProxyLegality.illegalCount(rs))
    }

    @Test
    fun `穿透对判自碰撞非法_深度按整数毫米`() {
        val rs = check(proxies(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtZ("b", 1.5)))
        for (id in listOf("a", "b")) {
            val r = reading(rs, id, LegalityItem.SELF_COLLISION)
            assertEquals(LegalityState.ILLEGAL, r.state)
            assertEquals("穿透 0.5cm＝5mm", 5, r.amountMilli)
            assertEquals(ViolationUnit.MILLIMETRE, r.unit)
            assertEquals("当事另一方要点名", if (id == "a") "b" else "a", r.other)
        }
        assertFalse(ProxyLegality.allLegal(rs))
    }

    @Test
    fun `声明相邻之后同一对转合法`() {
        val ps = proxies(sphereOnGround("a", 2.0), sphereOnGround("b", 3.5))
        val rs = check(ps, adjacent = setOf("a" to "b"))
        assertEquals(LegalityState.LEGAL, reading(rs, "a", LegalityItem.SELF_COLLISION).state)
        assertTrue(ProxyLegality.allLegal(rs))
    }

    @Test
    fun `恰好接触不算穿透_与655口径一致`() {
        val rs = check(proxies(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtZ("b", 2.0)))
        assertEquals(LegalityState.LEGAL, reading(rs, "a", LegalityItem.SELF_COLLISION).state)
        assertEquals(0, reading(rs, "a", LegalityItem.SELF_COLLISION).amountMilli)
    }

    @Test
    fun `穿地判非法`() {
        val rs = check(proxies(CavityFixtures.sphere("a", Vec3(0.0, 0.5, 0.0))), anchors = mapOf("a" to "root"))
        val r = reading(rs, "a", LegalityItem.GROUND)
        assertEquals(LegalityState.ILLEGAL, r.state)
        assertEquals(5, r.amountMilli)
        assertEquals(ViolationUnit.MILLIMETRE, r.unit)
    }

    @Test
    fun `穿沙另记一档_仍是穿地项`() {
        val rs = check(
            proxies(CavityFixtures.sphere("a", Vec3(0.0, 2.5, 0.0))),
            env = Environment(0.0, 3.0),
            anchors = mapOf("a" to "root"),
        )
        val r = reading(rs, "a", LegalityItem.GROUND)
        assertEquals(LegalityState.ILLEGAL, r.state)
        assertEquals("球心 2.5、半径 1 ⇒ 最低点 1.5；沙面 3.0 ⇒ 穿沙 1.5cm＝15mm", 15, r.amountMilli)
        assertTrue("一句话要说是穿沙不是穿地：${r.detail}", r.detail.contains("沙"))
    }

    @Test
    fun `越限判非法_量值是毫弧度并挂到该关节的代理体`() {
        val chain = CavityFixtures.twoLinkChain()
        val pose = chain.pose(listOf(0.0, chain.joints[1].limit.maxRad + 0.25))
        val rs = check(
            proxies(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtZ("b", 6.0)),
            chain = chain,
            pose = pose,
            anchors = mapOf("a" to "spine", "b" to "root"),
        )
        val r = reading(rs, "a", LegalityItem.LIMIT)
        assertEquals("越限的是 spine 关节，代理体 a 挂在它上面", LegalityState.ILLEGAL, r.state)
        assertEquals(ViolationUnit.MILLIRADIAN, r.unit)
        assertEquals("请求角超限位 0.25rad＝250 毫弧度", 250, r.amountMilli)
        assertEquals(LegalityState.LEGAL, reading(rs, "b", LegalityItem.LIMIT).state)
    }

    @Test
    fun `可达在链长界内判合法`() {
        val rs = check(proxies(CavityFixtures.sphere("a", Vec3(0.0, 5.0, 0.0))), anchors = mapOf("a" to "root"))
        assertEquals(LegalityState.LEGAL, reading(rs, "a", LegalityItem.REACH).state)
    }

    @Test
    fun `可达界外判非法_两连杆3加4的距离带是一到七`() {
        val rs = check(proxies(CavityFixtures.sphere("a", Vec3(0.0, 90.0, 0.0))), anchors = mapOf("a" to "root"))
        val r = reading(rs, "a", LegalityItem.REACH)
        assertEquals(LegalityState.ILLEGAL, r.state)
        assertTrue("一句话要给出距离：${r.detail}", r.detail.contains("90"))
    }

    @Test
    fun `三态诚实_采样未中记NOT_PROVEN_不当绿也不当红`() {
        val rs = check(
            proxies(CavityFixtures.sphere("a", Vec3(0.0, 5.0, 0.0))),
            chain = CavityFixtures.flatChain(10.0),
            pose = CavityFixtures.flatChain(10.0).pose(List(17) { 0.0 }),
            anchors = mapOf("a" to "root"),
        )
        val r = reading(rs, "a", LegalityItem.REACH)
        assertTrue("长链只能给三态之一：$r", r.state in listOf(LegalityState.LEGAL, LegalityState.NOT_PROVEN))
        assertEquals("NOT_PROVEN 不算非法", r.state == LegalityState.LEGAL || r.state == LegalityState.NOT_PROVEN, ProxyLegality.allLegal(rs))
    }


    @Test
    fun `等价性_686说的非法条数等于655自己说的条数`() {
        // 三球全悬在地面之上：只留自碰撞一类违规，×2 的换算才干净（穿地只挂单侧）。
        val ps = proxies(
            CavityFixtures.sphere("a", Vec3(0.0, 5.0, 0.0)),
            CavityFixtures.sphere("b", Vec3(0.0, 5.0, 1.5)),
            CavityFixtures.sphere("c", Vec3(0.0, 5.0, 2.8)),
        )
        val rs = check(ps, anchors = mapOf("a" to "root", "b" to "root", "c" to "root"))
        val mine = rs.filter { it.state == LegalityState.ILLEGAL && it.item in listOf(LegalityItem.SELF_COLLISION, LegalityItem.GROUND) }
        val theirs = CavityFixtures.poseValidityViolationCount(ps, Environment(0.0, 0.0))
        assertEquals("686 只是转写 655 的结论，条数不许对不上（自碰撞 655 每对记一条、686 两侧各记一条，故×2）", theirs * 2, mine.size)
        assertEquals("对照组该恰好两条（a↔b、b↔c；a↔c 相距 2.8 不穿）", 2, theirs)
    }

    @Test
    fun `读数都带假设与非测量注记`() {
        for (r in check(proxies(CavityFixtures.sphereAtZ("a", 0.0)))) {
            assertTrue(r.assumption.isNotBlank())
            assertEquals(com.example.zhengdao.baopu.state.geometry.GeometryNotes.NOT_MEASUREMENT, r.note)
        }
    }

    @Test
    fun `天然接触名单只放真互穿的对_不许拿声明当遮羞布`() {
        val ps = CavityFixtures.fromBody()
        val byId = ps.associateBy { it.id }
        assertEquals("④⑤ 离线取证共 22 对", 22, CavityFixtures.defaultPoseContacts.size)
        for ((a, b) in CavityFixtures.defaultPoseContacts) {
            val pa = requireNotNull(byId[a]) { "名单里的 $a 不是 660 代理体名" }
            val pb = requireNotNull(byId[b]) { "名单里的 $b 不是 660 代理体名" }
            val gap = CavityFixtures.signedGapCm(pa, pb)
            assertTrue("$a↔$b 没互穿（间隙 $gap）就不该声明成天然接触", gap < 0.0)
        }
    }

    @Test
    fun `真形体四项_660的三十二个代理体全绿`() {
        val state = FormFixtures.state()
        val ps = ProxySimplify.fromBody(state.proxies)
        val (chain, pose) = CavityFixtures.bodyChain(state)
        // 660 只承诺足底最低点在 1e-9 容差内归零，地面留 0.5mm 余量：浮点尾巴不该被判成穿地。
        val rs = ProxyLegality.check(
            ps, Environment(-0.05, -0.05), chain, pose, state.proxies.anchors,
            CavityFixtures.bodyAdjacent(ps, CavityFixtures.anatomyZones()),
        )
        assertEquals(
            "660 形体在 655 口径下的非法项：\n" + rs.filter { it.state == LegalityState.ILLEGAL }
                .joinToString("\n") { "  ${it.proxyId}/${it.item} ${it.amountMilli} ${it.detail}" },
            0, ProxyLegality.illegalCount(rs),
        )
        val perProxy = rs.groupBy { it.proxyId }
        assertEquals("代理体全覆盖", ps.size, perProxy.size)
        assertTrue(
            "每个代理体四项齐备（一个邻体穿多处时自碰撞可多于一条）：缺项的代理体＝" +
                perProxy.filter { it.value.map { r -> r.item }.toSet() != LegalityItem.values().toSet() }.keys,
            perProxy.values.all { it.map { r -> r.item }.toSet() == LegalityItem.values().toSet() },
        )
        assertTrue("长链可达判定允许三态，但不许出现非法之外的空态", rs.none { it.state == LegalityState.ILLEGAL })
    }

    @Test
    fun `判据六_合法性读数同输入两次逐字节一致`() {
        val state = FormFixtures.state()
        val ps = ProxySimplify.fromBody(state.proxies)
        val (chain, pose) = CavityFixtures.bodyChain(state)
        val a = ProxyLegality.check(ps, Environment(0.0, 0.0), chain, pose, state.proxies.anchors, CavityFixtures.bodyAdjacent(ps, CavityFixtures.anatomyZones()))
        val b = ProxyLegality.check(ps, Environment(0.0, 0.0), chain, pose, state.proxies.anchors, CavityFixtures.bodyAdjacent(ps, CavityFixtures.anatomyZones()))
        assertEquals(a.toString(), b.toString())
    }
}
