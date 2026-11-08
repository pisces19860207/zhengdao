// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百七十九批 · 后台维护的**四个器**（停一个、停全部，都按这四格说话）。
//
// 为什么单立一张表：用户 2026-11-08 定的分工是「金吾卫＝巡检/勘验 · 暗卫＝夜间抽检 ·
// 御医＝体检与修 · 巡按＝六项全跑」。单件暂停要能点名、急停要能一把全停、落账要能一眼
// 看出是哪一只——三件事都要同一份名单。名单写两遍，迟早会出现"停的是暗卫、说的是巡按"。
//
// 与 `inspect/MaintJob` 的关系：那是**触发**的两件（有负载阈与冷却，只有暗卫/巡按）；
// 本表是**器**的四件（含没有负载阈的金吾卫与御医）。`wire` 与 [MaintJob.wire] 同值，
// 暂停键才认得出是谁（暗卫 `nightwatch`／巡按 `patrol`）。
package com.example.zhengdao.baopu.maintenance

/** 后台维护的四器（闭集；认不出按 null 处理，不猜）。 */
internal enum class MaintDevice(val wire: String, val cn: String) {
    /** 金吾卫：用户点名的全量巡检/勘验。 */
    JINWU("jinwu", "金吾卫"),

    /** 暗卫：夜间抽检（轻）。 */
    NIGHTWATCH("nightwatch", "暗卫"),

    /** 御医：体检与修（月位＋自修）。 */
    DOCTOR("doctor", "御医"),

    /** 巡按：六项全跑（重）。 */
    PATROL("patrol", "巡按"),
    ;

    companion object {
        /** 认线名（`jinwu`/`nightwatch`/`doctor`/`patrol`）；认不出回 null——不静默当成某一只。 */
        fun ofWire(wire: String): MaintDevice? = entries.firstOrNull { it.wire == wire }
    }
}
