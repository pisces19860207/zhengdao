// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零四批 · 判据①（禁反推／冠沟必在册）与判据②（五档聚合、露冠不推露沟）的机器证据。
// 另加：判据⑤在枚举层的负例（没有任何子区 label 同时含冠与沟）。
package com.example.zhengdao.baopu.state.prepuce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepuceCoverageTest {

    /** 建一张 12 区齐备的位图：龟头按 [glans]、冠沟按给定，其余部位默认露出。 */
    private fun maskOf(
        glans: CoverState = CoverState.COVERED,
        corona: CoverState = CoverState.COVERED,
        sulcus: CoverState = CoverState.COVERED,
        overrides: Map<SubRegion, CoverState> = emptyMap(),
    ): CoverageMask = CoverageMask.of(
        SubRegion.entries.map { region ->
            val state = when (region.group) {
                RegionGroup.CORONA -> corona
                RegionGroup.SULCUS -> sulcus
                RegionGroup.GLANS -> overrides[region] ?: glans
                else -> overrides[region] ?: CoverState.EXPOSED
            }
            region to state
        }
    )

    // —— 判据①：位图缺冠或沟任一项 ⇒ 红（两条消息各自指对） ——
    @Test
    fun `判据一_位图缺冠或缺沟各红一次且消息指对`() {
        val noCorona = runCatching {
            CoverageMask.of(SubRegion.SULCUS to CoverState.COVERED)
        }.exceptionOrNull()
        assertTrue("缺冠必须当场拒：$noCorona", noCorona is IllegalArgumentException)
        assertEquals(CoverageMask.MISSING_CORONA, noCorona?.message)

        val noSulcus = runCatching {
            CoverageMask.of(SubRegion.CORONA to CoverState.COVERED)
        }.exceptionOrNull()
        assertEquals(CoverageMask.MISSING_SULCUS, noSulcus?.message)
    }

    @Test
    fun `判据一_同一子区记两次也算红`() {
        val error = runCatching {
            CoverageMask.of(
                listOf(
                    SubRegion.CORONA to CoverState.COVERED,
                    SubRegion.CORONA to CoverState.EXPOSED,
                    SubRegion.SULCUS to CoverState.COVERED,
                )
            )
        }.exceptionOrNull()
        assertEquals(CoverageMask.DUPLICATE_REGION, error?.message)
    }

    // —— 判据①：由面积反推位图 ⇒ 红 ——
    @Test
    fun `判据一_由面积反推位图当场红`() {
        val error = runCatching { CoverageMask.fromArea(AreaLevel.ALMOST_ALL) }.exceptionOrNull()
        assertTrue("反推这条路必须断：$error", error is IllegalArgumentException)
        assertTrue(
            "消息须点明禁反推：${error?.message}",
            error?.message?.startsWith(CoverageMask.AREA_TO_MASK_REJECT) == true,
        )
    }

    @Test
    fun `判据一_一档对多图证明反推是编的`() {
        val threeCovered = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(
                SubRegion.GLANS_BACK to CoverState.COVERED,
                SubRegion.GLANS_FRONT to CoverState.COVERED,
                SubRegion.GLANS_LEFT to CoverState.COVERED,
            ),
        )
        val fourAndHalf = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(
                SubRegion.GLANS_BACK to CoverState.COVERED,
                SubRegion.GLANS_FRONT to CoverState.COVERED,
                SubRegion.GLANS_LEFT to CoverState.COVERED,
                SubRegion.GLANS_RIGHT to CoverState.COVERED,
                SubRegion.GLANS_NECK to CoverState.PARTIAL,
            ),
        )
        assertEquals(AreaLevel.ALMOST_ALL, threeCovered.area().level)
        assertEquals("两张不同位图必须同档", threeCovered.area().level, fourAndHalf.area().level)
        assertFalse("位图本身必须不同（否则这组证据是空的）", threeCovered.line() == fourAndHalf.line())
    }

    // —— 判据②：五档逐行 ——
    @Test
    fun `判据二_五档逐行对得上`() {
        val full = maskOf(glans = CoverState.COVERED).area()
        assertEquals(AreaLevel.FULL_COVER, full.level)
        assertEquals("A1", full.rowId)

        val almost = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(
                SubRegion.GLANS_BACK to CoverState.COVERED,
                SubRegion.GLANS_FRONT to CoverState.COVERED,
                SubRegion.GLANS_LEFT to CoverState.COVERED,
            ),
        ).area()
        assertEquals(AreaLevel.ALMOST_ALL, almost.level)
        assertEquals("A2", almost.rowId)

        val overHalf = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(
                SubRegion.GLANS_BACK to CoverState.COVERED,
                SubRegion.GLANS_FRONT to CoverState.COVERED,
            ),
        ).area()
        assertEquals(AreaLevel.OVER_HALF, overHalf.level)
        assertEquals("A3", overHalf.rowId)

        val coronaOnly = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.COVERED,
            sulcus = CoverState.PARTIAL,
        ).area()
        assertEquals(AreaLevel.CORONA_ONLY, coronaOnly.level)
        assertEquals("A4", coronaOnly.rowId)

        val bare = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.EXPOSED,
            sulcus = CoverState.EXPOSED,
        ).area()
        assertEquals(AreaLevel.FULLY_EXPOSED, bare.level)
        assertEquals("A5", bare.rowId)
    }

    @Test
    fun `判据二_半区折半_两区全覆加一区半露算半数左右`() {
        val half = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(
                SubRegion.GLANS_BACK to CoverState.COVERED,
                SubRegion.GLANS_FRONT to CoverState.COVERED,
                SubRegion.GLANS_LEFT to CoverState.PARTIAL,
            ),
        ).area()
        assertEquals(AreaLevel.OVER_HALF, half.level)

        val justOver = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(
                SubRegion.GLANS_BACK to CoverState.COVERED,
                SubRegion.GLANS_FRONT to CoverState.COVERED,
                SubRegion.GLANS_LEFT to CoverState.COVERED,
            ),
        ).area()
        assertEquals("三区全覆即过半，升到几乎全部", AreaLevel.ALMOST_ALL, justOver.level)
    }

    // —— 判据②：露冠不得推露沟（退至冠后＝冠露＋沟覆） ——
    @Test
    fun `判据二_冠露沟覆既不判完全露出也不判仅冠`() {
        val retracted = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.EXPOSED,
            sulcus = CoverState.COVERED,
        ).area()
        assertNull("退至冠后不属五档，不许硬塞", retracted.level)
        assertFalse("不许判完全露出", retracted.level == AreaLevel.FULLY_EXPOSED)
        assertFalse("不许判仅冠", retracted.level == AreaLevel.CORONA_ONLY)
        assertEquals("X2", retracted.rowId)
        assertEquals(PrepuceGuard.CORONA_NOT_SULCUS, retracted.reason)
    }

    @Test
    fun `判据二_沟也露出才判完全露出`() {
        val fullyBare = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.EXPOSED,
            sulcus = CoverState.EXPOSED,
        ).area()
        assertEquals(AreaLevel.FULLY_EXPOSED, fullyBare.level)

        val sulcusStillCovered = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.EXPOSED,
            sulcus = CoverState.PARTIAL,
        ).area()
        assertNull("沟半露也算仍被盖，同样不判完全露出", sulcusStillCovered.level)
    }

    // —— 五档之外：零星覆盖与「冠覆沟露」都不硬塞 ——
    @Test
    fun `档外_零星覆盖给未定且行号为 X1`() {
        val sparse = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(SubRegion.GLANS_BACK to CoverState.COVERED),
        ).area()
        assertNull(sparse.level)
        assertEquals("X1", sparse.rowId)
        assertFalse("未定必须写清未定", sparse.resolved)
    }

    @Test
    fun `档外_冠仍覆而沟已露给未定`() {
        val odd = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.COVERED,
            sulcus = CoverState.EXPOSED,
        ).area()
        assertNull(odd.level)
        assertEquals("X2", odd.rowId)
        assertTrue("理由须说清不是五档：${odd.reason}", odd.reason.contains("不是五档"))
    }

    @Test
    fun `面积_缺龟头子区就算不出并点名缺哪几区`() {
        val thin = CoverageMask.of(
            SubRegion.GLANS_BACK to CoverState.COVERED,
            SubRegion.CORONA to CoverState.COVERED,
            SubRegion.SULCUS to CoverState.COVERED,
        )
        val error = runCatching { thin.area() }.exceptionOrNull()
        assertTrue("缺区不许算面积：$error", error is IllegalArgumentException)
        val message = error?.message.orEmpty()
        assertTrue("须点名缺哪区：$message", message.startsWith(CoverageMask.AREA_NEEDS_PREFIX))
        assertTrue("须列出龟头腹侧：$message", message.contains(SubRegion.GLANS_FRONT.label))
        assertFalse("冠已在册不许误报：$message", message.contains("、${SubRegion.CORONA.label}"))
    }

    // —— 枚举层自检 ——
    @Test
    fun `子区表_十二区且龟头五区且冠沟分属两组`() {
        assertEquals("子区共 12 项", 12, SubRegion.entries.size)
        assertEquals("龟头五区", 5, GLANS_REGIONS.size)
        assertEquals("柱身三区", 3, SubRegion.entries.count { it.group == RegionGroup.SHAFT })
        assertTrue("冠必须是自己一组", SubRegion.CORONA.group == RegionGroup.CORONA)
        assertTrue("沟必须是自己一组", SubRegion.SULCUS.group == RegionGroup.SULCUS)
        assertFalse("冠沟不许同组", SubRegion.CORONA.group == SubRegion.SULCUS.group)
    }

    @Test
    fun `子区表_没有任何子区把冠与沟合成一格`() {
        val merged = SubRegion.entries.filter { PrepuceGuard.isMergedAreaName(it.label) }
        assertEquals("合名的子区必须为空：$merged", emptyList<SubRegion>(), merged)
        assertTrue("而「冠沟」这种合名必须被抓到", PrepuceGuard.isMergedAreaName("冠沟"))
    }

    @Test
    fun `判据五_区名入口收单区名不收合名`() {
        assertEquals(SubRegion.CORONA, CoverageMask.regionOf("冠"))
        assertEquals(SubRegion.SULCUS, CoverageMask.regionOf("沟"))
        assertEquals(SubRegion.GLANS_BACK, CoverageMask.regionOf(" 龟头·背侧 "))
        assertEquals(SubRegion.SULCUS, CoverageMask.regionOf("冠状沟"))

        val merged = runCatching { CoverageMask.regionOf("冠沟") }.exceptionOrNull()
        assertTrue("合名必须进不来：$merged", merged is IllegalArgumentException)
        assertTrue(
            "消息须以口径原话开头：${merged?.message}",
            merged?.message?.startsWith(PrepuceGuard.MERGED_NAME_REJECT) == true,
        )

        val unknown = runCatching { CoverageMask.regionOf("包皮") }.exceptionOrNull()
        assertEquals(CoverageMask.UNKNOWN_AREA + "（收到「包皮」）", unknown?.message)
    }

    @Test
    fun `三态_code 逐字对得上派工单`() {
        assertEquals("covered", CoverState.COVERED.code)
        assertEquals("partial", CoverState.PARTIAL.code)
        assertEquals("exposed", CoverState.EXPOSED.code)
    }

    @Test
    fun `确定性_同一位图两次聚合逐字一致`() {
        val mask = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(
                SubRegion.GLANS_BACK to CoverState.COVERED,
                SubRegion.GLANS_FRONT to CoverState.PARTIAL,
            ),
        )
        assertEquals(mask.area(), mask.area())
        assertEquals(mask.area().line(), mask.area().line())
    }
}
