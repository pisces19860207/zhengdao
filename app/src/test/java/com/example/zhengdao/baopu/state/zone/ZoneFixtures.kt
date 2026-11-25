// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.state.cavity.CavityFixtures
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.form.ZoneTable
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.impact.AreaTier
import com.example.zhengdao.baopu.state.impact.ImpactInput
import com.example.zhengdao.baopu.state.impact.ImpactResult
import com.example.zhengdao.baopu.state.impact.ImpactSheet
import com.example.zhengdao.baopu.state.impact.ImpactSite
import com.example.zhengdao.baopu.state.impact.MassTier
import com.example.zhengdao.baopu.state.impact.SpeedTier
import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.CoverState
import com.example.zhengdao.baopu.state.prepuce.SkinAmount
import com.example.zhengdao.baopu.state.thermo.CremasterTrigger
import com.example.zhengdao.baopu.state.thermo.Direction
import com.example.zhengdao.baopu.state.thermo.HumidityBand
import com.example.zhengdao.baopu.state.thermo.Medium
import com.example.zhengdao.baopu.state.thermo.Posture
import com.example.zhengdao.baopu.state.thermo.SurfaceReading
import com.example.zhengdao.baopu.state.thermo.ThermalBand
import com.example.zhengdao.baopu.state.thermo.ThermoEval
import com.example.zhengdao.baopu.state.thermo.ThermoInput
import com.example.zhengdao.baopu.state.thermo.ThermoReading
import com.example.zhengdao.baopu.state.thermo.WindBand

internal object ZoneFixtures {

    val bodyState get() = FormFixtures.state()
    val anatomyZones get() = FormFixtures.anatomy
    val zoneTable get() = FormFixtures.zones

    fun thermo(
        thermal: ThermalBand = ThermalBand.MILD,
        humidity: HumidityBand = HumidityBand.NORMAL,
        medium: Medium = Medium.NONE,
        trigger: CremasterTrigger = CremasterTrigger.NONE,
    ): ThermoReading = ThermoEval.eval(
        ThermoInput(
            thermal = thermal,
            humidity = humidity,
            wind = WindBand.STILL,
            medium = medium,
            posture = Posture.STAND,
            trigger = trigger,
            turgor = Direction.FLAT,
            sweatGrade = 1,
        ),
    )

    fun zeroThermo(): ThermoReading = ThermoReading(
        volume = 0,
        hang = 0,
        rugosity = 0,
        cling = 0,
        cremasteric = 0,
        surface = SurfaceReading(wetFilm = 0, colorShift = 0, vesselVisible = 0, drivers = emptyList()),
    )

    fun impact(
        site: ImpactSite = ImpactSite.SCROTUM,
        rounds: Int = 0,
        cremasteric: Boolean = false,
    ): ImpactResult = ImpactSheet.of(
        ImpactInput(
            speed = SpeedTier.FAST,
            mass = MassTier.HEAVY,
            area = AreaTier.POINT,
            site = site,
            cremasteric = cremasteric,
            rounds = rounds,
        ),
    )

    fun prepuce(): PrepuceReferences = PrepuceReferences(
        cover = CoverState.PARTIAL,
        area = AreaLevel.OVER_HALF,
        skin = SkinAmount.MODERATE,
    )

    fun query(
        body: BodyState = bodyState,
        anatomy: List<AnatomyZone> = anatomyZones,
        zones: ZoneTable = zoneTable,
        thermo: ThermoReading? = thermo(),
        impact: ImpactResult? = impact(),
        prepuce: PrepuceReferences? = null,
        environment: Environment = CavityFixtures.groundEnv(),
    ): ZoneStateQuery = ZoneStateQuery.of(
        body = body,
        anatomy = anatomy,
        zones = zones,
        environment = environment,
        thermo = thermo,
        impact = impact,
        prepuce = prepuce,
    )
}
