// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十七批 · 环境求解器：统一环境状态（SceneState.environment 的槽值）。
// SceneState 本体归 660（人体模型批）建，本批不建、不碰；本件即其 environment 槽的值类型。
package com.example.zhengdao.baopu.state.environment

/** 场景环境槽值：光照＋风＋湿度＋汗液＋沙含水，一次求解整体产出。 */
data class EnvironmentState(
    val sun: SolarSolver.SunState,
    val wind: WindTable.WindState,
    val humidity: HumiditySolver.HumidityState,
    val sweat: HumiditySolver.SweatState,
    val sand: HumiditySolver.SandMoisture,
    val assumption: String,
    val note: String = EnvironmentNotes.NOT_MEASUREMENT,
)