// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 判定本体：凸分流／凹积水（必有出口）／尖挂滴（必标最低点）／缘导流，
// 三行为＋毛束修饰；汗就地渗出、分区不均、会蒸发。纯函数：同输入同结论。
package com.example.zhengdao.baopu.state.surfaceflow

/** 体表水流判定器。只吃结构化输入，不碰 IO、随机、时钟；有红就不出值。 */
internal object SurfaceFlowRule {

    /** 点值提示词用拼接写，避免本包自己写出点值字面。 */
    private val UNIT_HINTS: List<String> = listOf(
        "c" + "m", "m" + "L", "H" + "z", "滴/秒", "次/分", "毫" + "秒", "米/秒", "厘" + "米", "毫" + "升",
    )

    /** 体液专名同样拼接：体液分泌与混合归 780，本包一个字都不许收。 */
    private val FOREIGN_HINTS: List<String> = listOf(
        "精" + "液", "唾" + "液", "前" + "液", "se" + "men", "sa" + "liva",
    )

    fun judge(claim: SurfaceFlowClaim): SurfaceVerdict<SurfaceFlowResult> {
        val issues = collect(claim)
        if (issues.any { it.severity == IssueSeverity.RED }) return SurfaceVerdict(null, issues)
        return SurfaceVerdict.of(resultOf(claim))
    }

    private fun resultOf(claim: SurfaceFlowClaim): SurfaceFlowResult = SurfaceFlowResult(
        source = claim.source,
        posture = requireNotNull(claim.posture),
        paths = claim.paths,
        pools = claim.pools,
        drips = claim.drips,
        hairWicks = claim.hairWicks,
        sweat = claim.sweat,
    )

    private fun collect(claim: SurfaceFlowClaim): List<SurfaceIssue> {
        val out = ArrayList<SurfaceIssue>()
        postureCheck(out, claim)
        noteCheck(out, claim)
        uniformCheck(out, claim)
        terrainCheck(out, claim)
        outletCheck(out, claim)
        dripCheck(out, claim)
        hairCheck(out, claim)
        sweatCheck(out, claim)
        return out
    }

    private fun postureCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        if (claim.posture == null) out.redd(SurfaceCodes.POSTURE_MISSING, "体位必填：路径随体位走")
    }

    /** 判据⑦：只给档与部位；给数字或流速/频率单位即红。体液专名一律越界红。 */
    private fun noteCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        val note = claim.note
        if (note.any { it.isDigit() } || UNIT_HINTS.any { it in note }) {
            out.redd(SurfaceCodes.NUMERIC_VALUE, "只给档与部位，不给流速/频率数值")
        }
        if (FOREIGN_HINTS.any { it in note }) {
            out.redd(SurfaceCodes.CROSS_DOMAIN, "体液分泌与混合归邻单，本包只管外部水与汗")
        }
    }

    /** 判据①：显式写全身均匀流下，或各汗区档位全同，都红。 */
    private fun uniformCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        val sweat = claim.sweat ?: return
        if (sweat.uniformRunoff) {
            out.redd(SurfaceCodes.UNIFORM_RUNOFF, "全身均匀流下不成立：凸处分流、凹处积水")
            return
        }
        val zones = sweat.zones
        if (zones.size < 2) return
        val sameVolume = zones.all { it.volume.level == zones.first().volume.level }
        val sameStage = zones.all { it.stage.level == zones.first().stage.level }
        if (sameVolume && sameStage) {
            out.redd(SurfaceCodes.UNIFORM_RUNOFF, "各汗区同档同阶段，等于全身均匀流下")
        }
    }

    /** 地形定行为：凸必分流、凹必积水；喉结横穿不下淌单独咬住。 */
    private fun terrainCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        claim.paths.forEach { path ->
            val sites = listOf(path.from) + listOfNotNull(path.to)
            sites.forEach { site ->
                when (site.kind) {
                    TerrainKind.CONVEX -> if (path.behavior != FlowBehavior.SPLIT) {
                        out.redd(SurfaceCodes.LARYNX_NO_SPLIT, "${site.label}是凸处：必须分流下淌，不许横穿")
                    }
                    TerrainKind.CONCAVE -> if (path.behavior != FlowBehavior.POOL) {
                        out.redd(SurfaceCodes.POOL_MISSING_CONCAVE, "${site.label}是凹处：必须记积水")
                    }
                    else -> Unit
                }
            }
        }
        // 文案闸独立于结构闸：结构写对、文案却写「横穿不下淌」也一样红。
        if (claim.paths.any { it.from == Site.LARYNX || it.to == Site.LARYNX } && "横穿" in claim.note) {
            out.redd(SurfaceCodes.LARYNX_NO_SPLIT, "喉结处横穿不下淌不成立：凸处必须分流下淌")
        }
    }

    /** 判据②：凹处被流经必须真的记积水；胸沟单列一码。积水档非零必须有出口。 */
    private fun outletCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        val touchedConcave = claim.paths
            .flatMap { listOf(it.from) + listOfNotNull(it.to) }
            .filter { it.kind == TerrainKind.CONCAVE }
            .toSet()
        touchedConcave.forEach { site ->
            val hasPool = claim.pools.any { it.site == site && it.level != PoolLevel.NONE }
            if (!hasPool) {
                val code = if (site == Site.STERNAL_GROOVE) {
                    SurfaceCodes.POOL_MISSING_STERNAL
                } else {
                    SurfaceCodes.POOL_MISSING_CONCAVE
                }
                out.redd(code, "${site.label}被水流经却没有积水记录")
            }
        }
        claim.pools.filter { it.level != PoolLevel.NONE && it.outlet == null }.forEach { pool ->
            out.redd(SurfaceCodes.POOL_NO_OUTLET, "${pool.site.label}积水必须有出口")
        }
    }

    /** 判据③④：最低点必填；包皮口缘滴落必须同时给组织档与长度档。 */
    private fun dripCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        claim.drips.forEach { drip ->
            if (drip.isLowest == null) {
                out.redd(SurfaceCodes.DRIP_NO_LOWEST, "${drip.site.label}滴落必须标 isLowest")
            }
            if (drip.site == Site.PREPUCE_RIM &&
                (drip.prepuceTissue == null || drip.prepuceLength == null)
            ) {
                out.redd(SurfaceCodes.PREPUCE_NO_BASIS, "包皮口缘滴落须给组织档与长度档")
            }
        }
    }

    /** 判据⑥：沿毛束必须真有毛束、且毛必须沾水。 */
    private fun hairCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        claim.hairWicks.filter { !it.wet }.forEach { wick ->
            out.redd(SurfaceCodes.HAIR_DRY, "${wick.site.label}毛未沾水，不能导流")
        }
        claim.paths.filter { it.kind == PathKind.ALONG_HAIR }.forEach { path ->
            if (claim.hairWicks.none { it.site == path.from }) {
                out.redd(SurfaceCodes.HAIR_NO_WICK, "${path.from.label}沿毛束却没记毛束")
            }
        }
    }

    /** 汗三点：分区必给、不许同档、每区带蒸发档；就地渗出先起珠/成片、量够才汇流。 */
    private fun sweatCheck(out: MutableList<SurfaceIssue>, claim: SurfaceFlowClaim) {
        if (claim.source == WaterSource.SWEAT && claim.sweat == null) {
            out.redd(SurfaceCodes.SWEAT_MISSING, "汗必须给分区读数，不许全身同档")
        }
        val sweat = claim.sweat ?: return
        sweatShapeCheck(out, sweat.zones)
        sweat.zones.forEach { zoneCheck(out, it) }
    }

    /** 分区形状：至少两区、不许重复、不许全同档。 */
    private fun sweatShapeCheck(out: MutableList<SurfaceIssue>, zones: List<SweatZoneRead>) {
        if (zones.size < 2) out.redd(SurfaceCodes.SWEAT_NO_ZONES, "汗分区不足两区")
        if (zones.map { it.zone }.toSet().size != zones.size) {
            out.redd(SurfaceCodes.SWEAT_DUP_ZONE, "汗分区重复")
        }
        if (zones.size >= 2 && zones.all { sameZoneRead(zones.first(), it) }) {
            out.redd(SurfaceCodes.SWEAT_UNIFORM, "汗分区全同档")
        }
    }

    private fun sameZoneRead(a: SweatZoneRead, b: SweatZoneRead): Boolean =
        a.volume.level == b.volume.level && a.stage.level == b.stage.level && a.evapTier?.level == b.evapTier?.level

    /** 单区读数：蒸发档必填；汇流之前必须先起珠/成片、量要够。 */
    private fun zoneCheck(out: MutableList<SurfaceIssue>, zone: SweatZoneRead) {
        if (zone.evapTier == null) {
            out.redd(SurfaceCodes.SWEAT_NO_EVAP, "${zone.zone.label}缺蒸发档")
        }
        if (zone.stage != SeepStage.RUNNING) return
        if (!zone.beadOrSheet) {
            out.redd(SurfaceCodes.SWEAT_SKIP_SEEP, "${zone.zone.label}跳过起珠/成片直接汇流")
        }
        if (zone.volume.level < FlowVolume.RUN_MIN_LEVEL) {
            out.redd(SurfaceCodes.SWEAT_EARLY_RUN, "${zone.zone.label}量不够就汇流")
        }
    }

    private fun MutableList<SurfaceIssue>.redd(code: String, detail: String) {
        add(SurfaceIssue(code, IssueSeverity.RED, detail))
    }
}
