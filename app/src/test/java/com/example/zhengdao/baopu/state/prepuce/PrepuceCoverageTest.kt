// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零四批 · 判据①（禁反推／冠沟必在册）与判据②（六档聚合、露冠不推露沟）的机器证据。
// 第八百三十一批 · 退至冠后转正（A6）、未定必带原因与建议动作、六档序列与疲软序分离。
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

    // —— 判据②：六档逐行 ——
    @Test
    fun `判据二_六档逐行对得上`() {
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

        val retracted = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.EXPOSED,
            sulcus = CoverState.COVERED,
        ).area()
        assertEquals(AreaLevel.RETRACTED_BEHIND_CORONA, retracted.level)
        assertEquals("A6", retracted.rowId)
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

    // —— 判据②：露冠不得推露沟；831 把退至冠后从 X2 转正为 A6 ——
    @Test
    fun `判据二_冠露沟覆唯一落退至冠后且仍不推完全露出`() {
        val retracted = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.EXPOSED,
            sulcus = CoverState.COVERED,
        ).area()
        assertEquals(AreaLevel.RETRACTED_BEHIND_CORONA, retracted.level)
        assertEquals("A6", retracted.rowId)
        assertFalse("804 方向性不变：不许判完全露出", retracted.level == AreaLevel.FULLY_EXPOSED)
        assertFalse("不许判仅冠", retracted.level == AreaLevel.CORONA_ONLY)
        assertTrue("已定档不许带未定字段", retracted.unknownReason == null && retracted.suggestedAction == null)
        assertTrue("理由须点明退至冠后", retracted.reason.contains("退至冠后"))
    }

    @Test
    fun `判据一_退至冠后由冠沟位图唯一推出`() {
        val seen = mutableMapOf<Pair<CoverState, CoverState>, AreaLevel?>()
        for (corona in CoverState.entries) {
            for (sulcus in CoverState.entries) {
                val area = maskOf(
                    glans = CoverState.EXPOSED,
                    corona = corona,
                    sulcus = sulcus,
                ).area()
                seen[corona to sulcus] = area.level
                assertEquals(
                    "退至冠后的判据须与 804 闸门逐字一致：$corona/$sulcus",
                    PrepuceGuard.isRetractedBehindCorona(corona, sulcus),
                    area.level == AreaLevel.RETRACTED_BEHIND_CORONA,
                )
                if (corona == CoverState.EXPOSED && sulcus == CoverState.EXPOSED) {
                    assertEquals("只有冠沟都露才完全露出：$corona/$sulcus",
                        AreaLevel.FULLY_EXPOSED, area.level)
                }
            }
        }
        assertEquals("九种冠沟组合全覆盖", 9, seen.size)
        assertEquals(
            "恰两种组合落退至冠后（冠露 × 沟非露），唯一可推",
            2,
            seen.values.count { it == AreaLevel.RETRACTED_BEHIND_CORONA },
        )
    }

    @Test
    fun `判据五_覆盖六档序列与疲软序分离`() {
        assertEquals(
            listOf("完全覆盖", "几乎全部", "过半", "仅冠", "退至冠后", "完全露出"),
            AreaLevel.entries.map { it.label },
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), AreaLevel.entries.map { it.order })
        assertEquals("退至冠后不在疲软表序上", -1, AreaLevel.RETRACTED_BEHIND_CORONA.flaccidOrder)
        assertEquals("完全露出的疲软序仍是 804 的 4", 4, AreaLevel.FULLY_EXPOSED.flaccidOrder)
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
        assertEquals("沟半露也算仍被盖，落退至冠后", AreaLevel.RETRACTED_BEHIND_CORONA,
            sulcusStillCovered.level)
    }

    // —— 六档之外：零星覆盖与「冠覆沟露」都不硬塞，但必须治理 ——
    @Test
    fun `档外_零星覆盖给未定且带人补档`() {
        val sparse = maskOf(
            glans = CoverState.EXPOSED,
            overrides = mapOf(SubRegion.GLANS_BACK to CoverState.COVERED),
        ).area()
        assertNull(sparse.level)
        assertEquals("X1", sparse.rowId)
        assertFalse("未定必须写清未定", sparse.resolved)
        assertTrue("必须给未定因", sparse.unknownReason?.isNotBlank() == true)
        assertEquals(UnknownAction.HUMAN_FILL, sparse.suggestedAction)
        assertTrue("快照须带建议动作", sparse.line().contains("人补档"))
    }

    @Test
    fun `档外_冠仍覆而沟已露给未定且转人工`() {
        val odd = maskOf(
            glans = CoverState.EXPOSED,
            corona = CoverState.COVERED,
            sulcus = CoverState.EXPOSED,
        ).area()
        assertNull(odd.level)
        assertEquals("X2", odd.rowId)
        assertTrue("理由须说清不是六档：${odd.reason}", odd.reason.contains("不是六档"))
        assertTrue("必须给未定因", odd.unknownReason?.isNotBlank() == true)
        assertEquals(UnknownAction.ESCALATE, odd.suggestedAction)
        assertTrue("快照须带建议动作", odd.line().contains("转人工"))
    }

    @Test
    fun `判据三_裸未定当场红`() {
        val noWhy = runCatching { CoverageArea(null, "X1", "理由") }.exceptionOrNull()
        assertEquals(CoverageArea.BARE_UNKNOWN, noWhy?.message)

        val noAction = runCatching {
            CoverageArea(null, "X1", "理由", unknownReason = "零星覆盖")
        }.exceptionOrNull()
        assertEquals(CoverageArea.BARE_UNKNOWN, noAction?.message)

        val noWhyEither = runCatching {
            CoverageArea(null, "X1", "理由", suggestedAction = UnknownAction.HUMAN_FILL)
        }.exceptionOrNull()
        assertEquals(CoverageArea.BARE_UNKNOWN, noWhyEither?.message)

        val governed = CoverageArea(null, "X1", "理由", "零星覆盖", UnknownAction.HUMAN_FILL)
        assertTrue("治理齐全的未定可出门", governed.line().contains("人补档"))
    }

    @Test
    fun `判据三_已定档带未定字段也红`() {
        val error = runCatching {
            CoverageArea(AreaLevel.FULL_COVER, "A1", "龟头五区全 covered", "不该带")
        }.exceptionOrNull()
        assertEquals(CoverageArea.RESOLVED_WITH_UNKNOWN, error?.message)
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
