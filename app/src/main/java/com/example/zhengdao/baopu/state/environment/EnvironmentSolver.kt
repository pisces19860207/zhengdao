// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十七批 · 环境求解器：统一入口。
// 一次给定场景参数 ⇒ EnvironmentState（SceneState.environment 槽值，SceneState 归 660）。
// 纯函数、确定性：同输入逐字节一致；零新依赖（仅 kotlin.*）。
package com.example.zhengdao.baopu.state.environment

/** 环境统一求解入口：光照/风/湿度/汗液/沙含水一次产出。 */
object EnvironmentSolver {

    const val ASSUMPTION =
        "环境求解 v1：各分项 assumption 见 sun/wind/humidity/sweat/sand 字段；" +
            "风级→风速取蒲福代表值（区间中值）后喂湿度/汗液侧；整体为公式与档位推算，非测量"

    /**
     * 主入口。时刻为当地真太阳时（小时 0..24）；风级 0..12；活动强度 0..4；沙含水档 0..2。
     * 越界参数按各分项 require 拒收（不静默钳位到合法域外语义）。
     */
    fun solve(
        latitudeDeg: Double,
        dayOfYear: Int,
        trueSolarTimeH: Double,
        beaufort: Int,
        windDirDeg: Double,
        tempC: Double,
        rhPct: Double,
        activity: Int,
        sandMoistureGrade: Int,
    ): EnvironmentState {
        val windMs = WindTable.representativeMs(beaufort)
        return EnvironmentState(
            sun = SolarSolver.solve(latitudeDeg, dayOfYear, trueSolarTimeH),
            wind = WindTable.of(beaufort, windDirDeg),
            humidity = HumiditySolver.humidityOf(tempC, rhPct, windMs),
            sweat = HumiditySolver.sweatOf(activity, tempC, rhPct, windMs, sandMoistureGrade),
            sand = HumiditySolver.sandMoistureOf(sandMoistureGrade),
            assumption = ASSUMPTION,
        )
    }
}