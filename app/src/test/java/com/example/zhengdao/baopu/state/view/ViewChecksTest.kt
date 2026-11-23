// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 判据①②③⑤：四件齐、视点挂节点（漂浮即零投影）、遮挡只收不算、取景对账。
package com.example.zhengdao.baopu.state.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewChecksTest {

    private val scene = ViewFixtures.scene()

    private fun report(
        viewpoint: Viewpoint = ViewFixtures.atDoor(),
        targets: List<NodeRef> = listOf(ViewFixtures.bed),
        claims: List<OcclusionClaim> = emptyList(),
        subject: NodeRef? = null,
    ): ViewReport = ViewChecks.report(viewpoint, scene, targets, claims, subject)

    /** 按代号找结论。 */
    private fun ViewReport.codes(): List<String> = findings.map { it.code }

    private fun ViewReport.of(code: String): ViewFinding =
        findings.firstOrNull { it.code == code } ?: error("没有结论 $code；实际：${codes()}")

    // ── 判据①：四件齐（结构性锁：公开面就这四个输出，多一个都不行） ─────────

    @Test
    fun `判据一_四件输出一件不多一件不少`() {
        val getters = ViewProjection::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercaseChar() } }
            .toSet()
        assertEquals(
            "四件输出必须是 target＋inFrustum／distanceTier／occludedBy／screenRelative",
            setOf("target", "inFrustum", "distanceTier", "occludedBy", "screenRelative"),
            getters,
        )
    }

    @Test
    fun `判据一_每个目标都出齐四件`() {
        val targets = listOf(ViewFixtures.bed, ViewFixtures.bedEdge, ViewFixtures.lamp, ViewFixtures.window)
        val r = report(targets = targets)
        assertEquals("四个目标各一条投影", 4, r.projections.size)
        val four = listOf("getInFrustum", "getDistanceTier", "getOccludedBy", "getScreenRelative")
        r.projections.forEach { p ->
            four.forEach { getter ->
                assertNotNull(
                    "${p.target.nodeId} 的 $getter 必须是值（空列表也算值，null 不算）",
                    ViewProjection::class.java.getMethod(getter).invoke(p),
                )
            }
        }
        assertTrue("四件齐要有一条明写的结论", r.codes().contains("四件齐"))
    }

    @Test
    fun `判据一_门口一镜的四件读数`() {
        val r = report(targets = listOf(ViewFixtures.bed, ViewFixtures.window))
        val bed = r.projections.first { it.target == ViewFixtures.bed }
        assertEquals("床：中焦视野内", Visibility.INSIDE, bed.inFrustum)
        assertEquals("床：厅堂两格＝远", DistanceTier.FAR, bed.distanceTier)
        assertEquals("床：画面正中", ScreenCell(ScreenColumn.CENTER, ScreenRow.MIDDLE), bed.screenRelative.cell)
        assertTrue("床：在画面里", bed.screenRelative.onScreen)

        val win = r.projections.first { it.target == ViewFixtures.window }
        assertEquals("窗：站在门口看它就在正右两格，贴边但还在画面里", Visibility.EDGE, win.inFrustum)
        assertEquals("窗：屏幕右中格", ScreenCell(ScreenColumn.RIGHT, ScreenRow.MIDDLE), win.screenRelative.cell)
        assertTrue("窗：贴边算在画面里", win.screenRelative.onScreen)
    }

    @Test
    fun `判据一_出画的目标也照给屏幕格`() {
        // 站在区域正中朝前看：窗在右后 ⇒ 出画；屏幕格照给（右中），onScreen 老实说假。
        val r = ViewChecks.report(ViewFixtures.atCenter(), scene, listOf(ViewFixtures.window))
        val win = r.projections.first { it.target == ViewFixtures.window }
        assertEquals("出画", Visibility.OUTSIDE, win.inFrustum)
        assertEquals("屏幕右中格", ScreenCell(ScreenColumn.RIGHT, ScreenRow.MIDDLE), win.screenRelative.cell)
        assertFalse("不在画面里就要说不在了", win.screenRelative.onScreen)
    }

    // ── 判据②：视点与目标都必须挂在场景图节点上 ─────────────────────────

    @Test
    fun `判据二_视点挂错层等于没挂_红且零投影`() {
        val floating = Viewpoint(
            anchor = NodeRef("door", SceneLayer.PART), // 场景图上 door 是物件层
            region = RegionGrade.HALL, at = Cell(depth = Depth.BACK),
            facing = Octant.FRONT, pitch = PitchTier.LEVEL,
            frustum = Frustum(FovTier.MEDIUM), focusDistance = DistanceTier.FAR,
        )
        val r = report(viewpoint = floating)
        assertEquals("一条投影都不许给", 0, r.projections.size)
        assertEquals(ViewState.RED, r.overall)
        assertTrue("要报漂浮视点", r.codes().contains("漂浮视点"))
        assertTrue("要报零投影", r.codes().contains("零投影"))
    }

    @Test
    fun `判据二_视点格位与锚点格位对不上_装订不上`() {
        val shifted = ViewFixtures.atDoor().copy(at = Cell()) // 挂在门上却写在区域中心
        val r = report(viewpoint = shifted)
        assertEquals("装订不上就不给投影", 0, r.projections.size)
        assertTrue(r.codes().contains("装订不上"))
        assertEquals(ViewState.RED, r.overall)
    }

    @Test
    fun `判据二_目标不在场景图上_红且零投影`() {
        val r = report(targets = listOf(NodeRef("ghost", SceneLayer.OBJECT)))
        assertEquals(0, r.projections.size)
        assertTrue(r.codes().contains("漂浮目标"))
    }

    @Test
    fun `判据二_取景对象不许是场景根`() {
        val root = PlacedNode(NodeRef("stage", SceneLayer.SCENE), Cell())
        val withRoot = SceneNodes(scene.all + root)
        val r = ViewChecks.report(ViewFixtures.atDoor(), withRoot, listOf(root.ref))
        assertEquals(0, r.projections.size)
        assertTrue("整场不是一件可以取景的物", r.codes().contains("取景对象太粗"))
    }

    @Test
    fun `判据二_视点构造时不许挂场景根`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            Viewpoint(
                anchor = NodeRef("stage", SceneLayer.SCENE),
                region = RegionGrade.HALL, at = Cell(),
                facing = Octant.FRONT, pitch = PitchTier.LEVEL,
                frustum = Frustum(FovTier.MEDIUM), focusDistance = DistanceTier.NEAR,
            )
        }
        assertTrue("报错要说清是漂浮坐标", thrown.message!!.contains("漂浮坐标"))
    }

    @Test
    fun `判据二_场景图同一id挂两层当场拒`() {
        val dup = listOf(
            PlacedNode(ViewFixtures.door, Cell(depth = Depth.BACK)),
            PlacedNode(NodeRef("door", SceneLayer.PART), Cell(depth = Depth.FRONT)),
        )
        assertThrows(IllegalArgumentException::class.java) { SceneNodes(dup) }
    }

    // ── 遮挡：只收 861 的结论，做视锥侧对账（不碰几何） ──────────────────

    @Test
    fun `遮挡_按距离由近到远排`() {
        val claims = listOf(
            ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.bedEdge), // 远档
            ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.chair), // 中档
        )
        val r = report(targets = listOf(ViewFixtures.bed), claims = claims, subject = ViewFixtures.bed)
        val bed = r.projections.first { it.target == ViewFixtures.bed }
        assertEquals("两个都挡得住", 2, bed.occludedBy.size)
        assertEquals("近的排前面：椅子（中档）", ViewFixtures.chair, bed.occludedBy[0].blocker)
        assertEquals("远的排后面：床沿（远档）", ViewFixtures.bedEdge, bed.occludedBy[1].blocker)
        assertEquals("画面内的遮挡者都是确定事实，不改总态", ViewState.PASS, r.overall)
    }

    @Test
    fun `遮挡_遮挡者自己出画要留提示且不计入`() {
        // 区域中心朝前看：窗在右后、自己就出画 ⇒ 它挡不住床，只能提示一句。
        val r = ViewChecks.report(
            ViewFixtures.atCenter(), scene, listOf(ViewFixtures.bed),
            listOf(ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.window)),
        )
        assertTrue("不计入遮挡列表", r.projections.first().occludedBy.isEmpty())
        assertTrue("但要留一条提示", r.codes().contains("视野外遮挡"))
        assertEquals(ViewState.HINT, r.overall)
    }

    @Test
    fun `遮挡_自己挡自己不算`() {
        val r = report(
            targets = listOf(ViewFixtures.bed),
            claims = listOf(ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.bed)),
        )
        assertTrue("自挡略过", r.projections.first().occludedBy.isEmpty())
        assertTrue(r.codes().contains("自挡"))
    }

    @Test
    fun `遮挡_没挡的不计入`() {
        val r = report(
            targets = listOf(ViewFixtures.bed),
            claims = listOf(ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.chair, blocks = false)),
        )
        assertTrue("blocks=false 不进遮挡列表", r.projections.first().occludedBy.isEmpty())
        assertFalse("也不该有提示", r.codes().contains("视野外遮挡"))
    }

    @Test
    fun `遮挡_遮挡者不在场景图上要判红`() {
        val ghost = NodeRef("ghost", SceneLayer.OBJECT)
        val r = report(targets = listOf(ViewFixtures.bed), claims = listOf(ViewFixtures.claim(ViewFixtures.bed, ghost)))
        assertTrue("挡了谁说不清", r.codes().contains("漂浮遮挡者"))
        assertTrue("遮挡账自身也要对账", r.codes().contains("遮挡结论漂浮"))
        assertEquals(ViewState.RED, r.overall)
    }

    // ── 取景距离对账 ────────────────────────────────────────────────────

    @Test
    fun `取景_声明与算出来一致`() {
        val r = report(subject = ViewFixtures.bed) // 声明远、床算出来远
        assertEquals(ViewState.PASS, r.of("取景一致").state)
        assertEquals(ViewState.PASS, r.overall)
    }

    @Test
    fun `取景_差一档提提示`() {
        val r = report(viewpoint = ViewFixtures.atDoor(focus = DistanceTier.MID), subject = ViewFixtures.bed)
        assertEquals(ViewState.HINT, r.of("取景差一档").state)
    }

    @Test
    fun `取景_差两档判红`() {
        val r = report(viewpoint = ViewFixtures.atDoor(focus = DistanceTier.NEAR), subject = ViewFixtures.bed)
        assertEquals(ViewState.RED, r.of("取景矛盾").state)
        assertEquals(ViewState.RED, r.overall)
    }

    @Test
    fun `取景_不给主体就不对账`() {
        val r = report()
        assertFalse("没指定主体就不该有取景结论", r.codes().any { it.startsWith("取景") })
    }

    // ── 三态取最严 ＋ 确定性 ────────────────────────────────────────────

    @Test
    fun `总态取最严_红压过提示压过通过`() {
        val r = report(
            viewpoint = ViewFixtures.atDoor(focus = DistanceTier.NEAR), // 红：取景矛盾
            targets = listOf(ViewFixtures.bed, ViewFixtures.chair),
            claims = listOf(ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.bed)), // 提示：自挡
            subject = ViewFixtures.bed,
        )
        assertTrue("既有红又有提示", r.codes().contains("取景矛盾") && r.codes().contains("自挡"))
        assertEquals("取最严", ViewState.RED, r.overall)
    }

    @Test
    fun `确定性_同一镜两跑逐字一致`() {
        val targets = listOf(ViewFixtures.bed, ViewFixtures.window, ViewFixtures.chair)
        val a = report(targets = targets, subject = ViewFixtures.bed).line()
        val b = report(targets = targets, subject = ViewFixtures.bed).line()
        assertEquals("同输入必须同输出", a, b)
    }

    @Test
    fun `确定性_目标次序不影响遮挡与总结论`() {
        val claims = listOf(
            ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.chair),
            ViewFixtures.claim(ViewFixtures.bed, ViewFixtures.bedEdge),
        )
        val forward = report(targets = listOf(ViewFixtures.bed, ViewFixtures.lamp), claims = claims)
        val backward = report(targets = listOf(ViewFixtures.lamp, ViewFixtures.bed), claims = claims)
        val a = forward.projections.first { it.target == ViewFixtures.bed }.occludedBy.map { it.blocker.nodeId }
        val b = backward.projections.first { it.target == ViewFixtures.bed }.occludedBy.map { it.blocker.nodeId }
        assertEquals("遮挡排序不受目标传入次序影响", a, b)
        assertEquals(forward.overall, backward.overall)
    }
}
