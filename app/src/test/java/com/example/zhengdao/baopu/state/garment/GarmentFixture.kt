// GZAG · 抱朴 · :policy · 第881批（2026-11-24）· 原创测试夹具。
package com.example.zhengdao.baopu.state.garment

internal object GarmentFixture {
    fun state(
        withUnderwear: Boolean = true,
        frontOpening: Boolean = false,
        wear: InnerWear = if (withUnderwear) {
            InnerWear.Present(Wornness.NEW, drivers = setOf(WearDriver.HABIT))
        } else {
            InnerWear.Absent
        },
        zipBehavior: ZipBehavior? = null,
        fabricStiffness: FabricStiffness = FabricStiffness.MEDIUM,
        flyType: FlyType = FlyType.ZIPPER,
    ): GarmentState {
        val base = GarmentDefaults.draft(withUnderwear)
        val draft = base.copy(
            flyType = flyType,
            innerLayer = requireNotNull(base.innerLayer).copy(
                frontOpening = frontOpening,
                wear = wear,
            ),
            zipBehavior = zipBehavior
                ?: if (flyType == FlyType.ZIPPER) ZipBehavior.SELF_LOCK else ZipBehavior.NOT_APPLICABLE,
            fabricStiffness = fabricStiffness,
            openingStyle = if (flyType == FlyType.ZIPPER) OpeningStyle.UNZIP else OpeningStyle.PULL_EDGE,
        )
        return requireNotNull(GarmentChecks.resolve(draft).state)
    }

    fun scene(
        phase: ZipPhase = ZipPhase.CLOSING,
        exposure: ExposurePath = ExposurePath.NO_UNDERWEAR,
        entanglement: EntanglementPath = EntanglementPath.SKIN,
        release: ReleaseMethod = ReleaseMethod.BACK_SLIDER,
        consequence: Consequence = Consequence.BRIEF_PAIN,
    ): PinchScene = PinchScene(phase, exposure, entanglement, release, consequence)

    fun codes(issues: List<GarmentIssue>): List<String> = issues.map { it.code }
}
