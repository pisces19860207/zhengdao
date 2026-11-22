// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · 六个判定器：四维变化 / 混合 / 垂坠 / 织物 / 覆盖 / 离体。
// 全部是纯函数：吃档位枚举、吐档位枚举，不读时钟、不读文件、不用随机（判据⑧）。
// 判据编号与派工单一一对应；判红就不出值——判不出来就不编，这是本包的底线。
package com.example.zhengdao.baopu.state.fluid

/** 判红/提示的码册：报告、测试、变异一律引这里的常量，不许各写一份字面量。 */
internal object FluidCodes {
    // ① 混合
    const val MIX_NO_PRIMARY = "MIX_NO_PRIMARY"
    const val MIX_AVERAGED = "MIX_AVERAGED"
    const val MIX_VISCOSITY_JUMP = "MIX_VISCOSITY_JUMP"
    const val MIX_VISCOSITY_DRIFT = "MIX_VISCOSITY_DRIFT"

    // ② 四维变化
    const val CHANGE_NO_DRIVER = "CHANGE_NO_DRIVER"
    const val CHANGE_REWET_NO_SOURCE = "CHANGE_REWET_NO_SOURCE"
    const val CHANGE_REWET_NO_SOURCE_DRY_MARK = "CHANGE_REWET_NO_SOURCE_DRY_MARK"

    // ③ 垂坠
    const val DROOP_NO_POSTURE = "DROOP_NO_POSTURE"

    // ④ 织物
    const val FABRIC_EMPTY = "FABRIC_EMPTY"
    const val FABRIC_SKIP = "FABRIC_SKIP"

    // ⑤ 覆盖
    const val COVERAGE_OVER = "COVERAGE_OVER"
    const val COVERAGE_NO_CARRIER = "COVERAGE_NO_CARRIER"
    const val COVERAGE_TEXT_OVER = "COVERAGE_TEXT_OVER"

    // ⑥ 离体
    const val EJECT_NO_DRIVE = "EJECT_NO_DRIVE"
    const val EJECT_NO_LANDING = "EJECT_NO_LANDING"
    const val SHED_NO_INTENSITY = "SHED_NO_INTENSITY"
    const val SHED_NO_DETACH = "SHED_NO_DETACH"
    const val SHED_NO_DIR = "SHED_NO_DIR"
    const val SHED_NOT_TANGENTIAL = "SHED_NOT_TANGENTIAL"
    const val DEPART_EMPTY = "DEPART_EMPTY"
    const val DEPART_DUP = "DEPART_DUP"
    const val DEPART_SWAP = "DEPART_SWAP"
    const val VAGUE_LANDING = "VAGUE_LANDING"

    // ⑦ 点值
    const val UNIT_POINT_VALUE = "UNIT_POINT_VALUE"
}

/**
 * ① 四维变化：在给定的一步里，四维各自怎么动。
 * 只有 [EventKind.FRESH_FLUID] 能把干涸拉回湿润——这就是判据③的结构性保证。
 */
internal object FluidChange {

    /** 唯一入口：吃「上一步的体」＋「这一步的四类驱动」，吐「这一步之后的体」。 */
    fun step(before: FluidBody, step: FluidStep): FluidVerdict<FluidBody> {
        val dryness = drynessOf(before, step)
        FluidChecks.rewet(before.dryness.grade, dryness, step.event)?.let { return FluidVerdict(null, listOf(it)) }
        val after = FluidBody(
            volume = read(before.volume, volumeOf(before, step), wetOrDryDrivers(step)),
            viscosity = read(before.viscosity, viscosityOf(before, dryness), wetOrDryDrivers(step)),
            temperature = read(before.temperature, tempOf(step), tempDrivers(step)),
            dryness = read(before.dryness, dryness, wetOrDryDrivers(step)),
        )
        val issues = FluidChecks.driverIssues(after)
        return if (issues.isEmpty()) FluidVerdict.of(after) else FluidVerdict(null, issues)
    }

    /** 干涸档：时间与暴露往上推，温湿往下拉，新液一步拉回湿润。 */
    private fun drynessOf(before: FluidBody, step: FluidStep): DrynessGrade {
        if (EventKind.isFreshSource(step.event)) return DrynessGrade.WET
        val delta = dryPush(step) - humidPull(step)
        return DrynessGrade.of(before.dryness.grade.level + delta)
    }

    private fun dryPush(step: FluidStep): Int =
        timePush(step.elapsed) + exposurePush(step.exposure)

    private fun timePush(e: ElapsedBand): Int = if (e.level >= ElapsedBand.WHILE.level) 1 else 0

    private fun exposurePush(e: ExposureBand): Int = e.level

    private fun humidPull(step: FluidStep): Int = if (step.env.level >= EnvBand.WARM.level) 1 else 0

    /** 黏稠档跟着干涸档走：更干更稠，新液回来变稀。 */
    private fun viscosityOf(before: FluidBody, dryness: DrynessGrade): ViscosityGrade {
        val move = dryness.level - before.dryness.grade.level
        return ViscosityGrade.of(before.viscosity.grade.level + move.coerceIn(-1, 1))
    }

    /** 量档：迎风久了收一点，新液加一点。 */
    private fun volumeOf(before: FluidBody, step: FluidStep): VolumeGrade {
        val blown = step.exposure == ExposureBand.AIRFLOW && step.elapsed.level >= ElapsedBand.WHILE.level
        val delta = (if (blown) -1 else 0) + (if (EventKind.isFreshSource(step.event)) 1 else 0)
        return VolumeGrade.of(before.volume.grade.level + delta)
    }

    /** 温度档随环境走一档，冷暖各一边；温和不动。 */
    private fun tempOf(step: FluidStep): FluidTempGrade = when {
        step.env.level >= EnvBand.WARM.level -> FluidTempGrade.of(FluidTempGrade.MILD.level + 1)
        step.env.level <= EnvBand.COOL_DRY.level -> FluidTempGrade.of(FluidTempGrade.MILD.level - 1)
        else -> FluidTempGrade.MILD
    }

    private fun wetOrDryDrivers(step: FluidStep): List<ChangeDriver> = buildList {
        if (timePush(step.elapsed) > 0) add(ChangeDriver.TIME)
        if (step.exposure.level > ExposureBand.COVERED.level) add(ChangeDriver.EXPOSURE)
        if (step.env.level >= EnvBand.WARM.level) add(ChangeDriver.TEMP_HUMIDITY)
        if (EventKind.isFreshSource(step.event)) add(ChangeDriver.EVENT)
    }

    private fun tempDrivers(step: FluidStep): List<ChangeDriver> =
        if (step.env.level == EnvBand.MILD.level) emptyList() else listOf(ChangeDriver.TEMP_HUMIDITY)

    /** 读数归集：零档不挂驱动，非零档把历史驱动与这一步新驱动并起来（顺序稳定）。 */
    private fun <G : FluidGrade> read(
        before: GradeRead<G>,
        grade: G,
        fresh: List<ChangeDriver>,
    ): GradeRead<G> = GradeRead(grade, if (grade.zero) emptyList() else (before.drivers + fresh).distinct())
}

/** ② 混合：主液主导＋次液修正。三条规则硬编码在 [MixRule] 里，不许取平均。 */
internal object FluidMixer {

    /** 唯一入口：先卡「平均式」与「没给主液」，再按规则算外观/流动/黏度。 */
    fun mix(req: MixRequest): FluidVerdict<Mixture> {
        if (req.mode == MixMode.AVERAGED) {
            return FluidVerdict.red(FluidCodes.MIX_AVERAGED, "混合不许取平均：只许主液主导＋次液修正")
        }
        val primary = req.primary
            ?: return FluidVerdict.prompt(null, FluidCodes.MIX_NO_PRIMARY, "混合未给主液：先说清主液是哪一种")
        val rule = ruleOf(primary, req.secondary, req.onSkin)
        val viscosity = viscosityOf(req, rule)
        val appearance = appearanceOf(rule, viscosity)
        return FluidVerdict.of(
            Mixture(
                dominant = primary,
                secondary = req.secondary,
                baseViscosity = req.primaryViscosity,
                viscosity = viscosity,
                appearance = appearance,
                flow = flowOf(appearance),
                dryHint = if (rule == MixRule.SALIVA_PRE_SEMINAL) DryHint.SLOW else DryHint.NORMAL,
                rule = rule,
                onSkin = req.onSkin,
            )
        )
    }

    /** 规则名册：只认三条（汗＋前液须在体表），其余一律单液处理，不临时发明第四条。 */
    private fun ruleOf(primary: FluidKind, secondary: FluidKind?, onSkin: Boolean): MixRule {
        if (secondary == null) return MixRule.PLAIN
        val pair = setOf(primary, secondary)
        return when {
            pair == setOf(FluidKind.SWEAT, FluidKind.PRE_SEMINAL) && onSkin -> MixRule.SWEAT_PRE_SEMINAL
            pair == setOf(FluidKind.SALIVA, FluidKind.PRE_SEMINAL) -> MixRule.SALIVA_PRE_SEMINAL
            pair == setOf(FluidKind.PRE_SEMINAL, FluidKind.SEMEN) -> MixRule.PRE_SEMINAL_SEMEN
            else -> MixRule.PLAIN
        }
    }

    /** 黏度：只有「前液＋精液」这一条准跳一档；其余一律等于主液原档，不升不平均。 */
    private fun viscosityOf(req: MixRequest, rule: MixRule): ViscosityGrade =
        if (rule == MixRule.PRE_SEMINAL_SEMEN) ViscosityGrade.upOne(req.primaryViscosity)
        else req.primaryViscosity

    private fun appearanceOf(rule: MixRule, viscosity: ViscosityGrade): Appearance =
        when (rule) {
            MixRule.SWEAT_PRE_SEMINAL -> Appearance.SHEEN
            MixRule.SALIVA_PRE_SEMINAL -> Appearance.STRINGY
            MixRule.PRE_SEMINAL_SEMEN -> if (viscosity.level >= ViscosityGrade.CREAMY.level) {
                Appearance.CLOT
            } else {
                Appearance.STRINGY
            }
            MixRule.PLAIN -> plainAppearance(viscosity)
        }

    /** 单液外观只看它自己的黏度档：越稠越容易挂丝、成块。 */
    private fun plainAppearance(viscosity: ViscosityGrade): Appearance = when {
        viscosity.level >= ViscosityGrade.GLUTINOUS.level -> Appearance.CLOT
        viscosity.level >= ViscosityGrade.CREAMY.level -> Appearance.STRINGY
        else -> Appearance.SHEEN
    }

    private fun flowOf(a: Appearance): FlowKind = when (a) {
        Appearance.SHEEN -> FlowKind.SLIDE
        Appearance.STRINGY -> FlowKind.STRETCH
        Appearance.CLOT -> FlowKind.HOLD
        Appearance.DRY_MARK -> FlowKind.HOLD
    }
}

/** 外观一个额外出口：干了以后身体上的样子就是干痕——干痕这个型有真出处，不是摆设。 */
internal object FluidLook {

    /** 干到干痕/干涸，外观就记干痕；否则按当前黏度档给。 */
    fun appearanceOf(body: FluidBody): Appearance = when {
        body.dryness.grade.level >= DrynessGrade.DRY_MARK_LEVEL -> Appearance.DRY_MARK
        body.viscosity.grade.level >= ViscosityGrade.GLUTINOUS.level -> Appearance.CLOT
        body.viscosity.grade.level >= ViscosityGrade.CREAMY.level -> Appearance.STRINGY
        else -> Appearance.SHEEN
    }
}

/** ③ 液体的垂坠倾向档：必须带体位，缺体位判红。本单不管摆动（731 管）。 */
internal object FluidDroop {

    fun judge(req: DroopRequest): FluidVerdict<DroopReading> {
        val posture = req.posture
            ?: return FluidVerdict.red(FluidCodes.DROOP_NO_POSTURE, "垂坠倾向必须带体位：不说体位就不知道往哪儿垂")
        val level = req.volume.level + uprightBonus(posture) + thinBonus(req.viscosity)
        return FluidVerdict.of(DroopReading(DroopTendency.of(level), posture))
    }

    /** 竖直位顺着体表往下带，卧姿不带。 */
    private fun uprightBonus(p: Posture): Int = if (Posture.isUpright(p)) 1 else 0

    /** 越稀越容易往下淌，稠了就挂住。 */
    private fun thinBonus(v: ViscosityGrade): Int = if (v.level <= ViscosityGrade.THIN.level) 1 else 0
}

/** ④ 织物：三态（珠滚/吸收/透过）＋ 四类材质；层序由外到内，不许跳层。 */
internal object FluidFabric {

    /** 逐层推进：只有上一层「透过」，下一层才可能被触及；触及不到就记未触及。 */
    fun propagate(materials: List<FabricMaterial>, volume: VolumeGrade, viscosity: ViscosityGrade): FabricStack {
        require(materials.isNotEmpty()) { "织物栈至少一层" }
        val layers = ArrayList<FabricLayer>(materials.size)
        var reach = true
        for (material in materials) {
            val outcome = if (reach) outcomeOf(material, volume, viscosity) else LayerOutcome.DRY
            layers += FabricLayer(material, outcome)
            reach = outcome == LayerOutcome.PASSED_THROUGH
        }
        return FabricStack(layers)
    }

    /** 材质四类的差别写在表里：化纤珠滚、羊毛稠液珠滚、混纺量大就透、棉最吃水。 */
    fun outcomeOf(material: FabricMaterial, volume: VolumeGrade, viscosity: ViscosityGrade): LayerOutcome = when {
        volume.zero -> LayerOutcome.DRY
        material == FabricMaterial.SYNTHETIC -> LayerOutcome.BEADED
        material == FabricMaterial.WOOL -> woolOutcome(viscosity)
        material == FabricMaterial.BLEND -> if (volume.level >= VolumeGrade.MODERATE.level) {
            LayerOutcome.PASSED_THROUGH
        } else {
            LayerOutcome.ABSORBED
        }
        else -> if (volume.level >= VolumeGrade.COPIOUS.level) LayerOutcome.PASSED_THROUGH else LayerOutcome.ABSORBED
    }

    private fun woolOutcome(viscosity: ViscosityGrade): LayerOutcome =
        if (viscosity.level >= ViscosityGrade.CREAMY.level) LayerOutcome.BEADED else LayerOutcome.ABSORBED

    /** 判跳层：任何一层被触及，上一层必须是「透过」，否则红。 */
    fun judge(stack: FabricStack): FluidVerdict<FabricStack> {
        if (stack.layers.isEmpty()) return FluidVerdict.red(FluidCodes.FABRIC_EMPTY, "织物栈至少一层")
        val skip = (1 until stack.layers.size).firstOrNull { i -> skippedAt(stack, i) }
        return if (skip == null) {
            FluidVerdict.of(stack)
        } else {
            FluidVerdict.red(
                FluidCodes.FABRIC_SKIP,
                "第 " + (skip + 1) + " 层被触及，但上一层没透过：液体不许跳层",
            )
        }
    }

    private fun skippedAt(stack: FabricStack, i: Int): Boolean =
        stack.layers[i].outcome != LayerOutcome.DRY &&
            stack.layers[i - 1].outcome != LayerOutcome.PASSED_THROUGH
}

/** ⑤ 量-面积对应：量档决定覆盖上限；量 3 才允许全覆盖，且全覆盖必须点名载体。 */
internal object FluidCoverage {

    /** 上限表：无/点状内（局部）/半覆盖/全覆盖。 */
    fun maxOf(volume: VolumeGrade): Coverage = when (volume.level) {
        VolumeGrade.NONE.level -> Coverage.NONE
        VolumeGrade.TRACE.level -> Coverage.LOCAL
        VolumeGrade.MODERATE.level -> Coverage.HALF
        else -> Coverage.FULL
    }

    fun judge(claim: CoverageClaim): FluidVerdict<CoverageReading> {
        val max = maxOf(claim.volume)
        if (claim.coverage.level > max.level) {
            return FluidVerdict.red(
                FluidCodes.COVERAGE_OVER,
                "量档「" + claim.volume.label + "」最多到「" + max.label + "」，写不成「" + claim.coverage.label + "」",
            )
        }
        val reading = CoverageReading(claim.coverage, max, claim.carrier)
        return if (claim.coverage == Coverage.FULL && claim.carrier == null) {
            FluidVerdict.prompt(reading, FluidCodes.COVERAGE_NO_CARRIER, "全覆盖必须给载体：体表/织物/器物点一个")
        } else {
            FluidVerdict.of(reading)
        }
    }
}

/** ⑥ 离体三型：喷射（盆底驱动＋落点）/ 甩出（无盆底＋切向＋方向）/ 重力流（贴体无初速）。 */
internal object FluidDeparture {

    /** 喷射：缺盆底驱动或缺落点，两条都判红。初速沿开口轴向之后只受重力，这一条是类型自带的。 */
    fun ejection(req: EjectionRequest): FluidVerdict<Ejection> {
        val drive = req.drive
            ?: return FluidVerdict.red(FluidCodes.EJECT_NO_DRIVE, "喷射必须有盆底驱动档（强/中/弱）：没有驱动就不是喷射")
        val landing = req.landing
            ?: return FluidVerdict.red(FluidCodes.EJECT_NO_LANDING, "喷射必须给落点：抛物线得落在具体一处")
        return FluidVerdict.of(Ejection(drive, landing, req.axis))
    }

    /** 甩出：强度、脱离方向、甩动方向三样缺一即红；脱离方向给法向也红（初速沿切向）。 */
    fun shed(req: ShedRequest): FluidVerdict<Shed> {
        val intensity = req.intensity
            ?: return FluidVerdict.red(FluidCodes.SHED_NO_INTENSITY, "甩出必须给甩动强度档（强/中/弱）")
        val detach = req.detach
            ?: return FluidVerdict.red(FluidCodes.SHED_NO_DETACH, "甩出必须给脱离方向（切向）")
        val dir = req.dir
            ?: return FluidVerdict.red(FluidCodes.SHED_NO_DIR, "甩出必须给甩动方向（向上/向下/侧向/斜向）")
        if (detach == DetachDir.NORMAL) {
            return FluidVerdict.red(FluidCodes.SHED_NOT_TANGENTIAL, "甩出的初速沿切向：脱离方向给法向就是另一种运动")
        }
        return FluidVerdict.of(Shed(intensity, detach, dir))
    }

    /** 重力流：无初速、贴体。贴不贴体不是可选项，所以这里没有入参。 */
    fun seepage(): FluidVerdict<Seepage> = FluidVerdict.of(Seepage.ATTACHED)

    /** 并存：两段以上分别记，段序即渲染序；同一型记两段判红。 */
    fun set(vararg parts: DeparturePart): FluidVerdict<DepartureSet> {
        if (parts.isEmpty()) return FluidVerdict.red(FluidCodes.DEPART_EMPTY, "没有任何一段离体，就不该出这一笔")
        val kinds = parts.map { it.kind }
        if (kinds.distinct().size != kinds.size) {
            return FluidVerdict.red(FluidCodes.DEPART_DUP, "同一型记了两段：并存只记不同型的段")
        }
        return FluidVerdict.of(DepartureSet(parts.toList()))
    }
}
