// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十批 · 形轨.方位可达：子区枚举唯一来源缝（判据⑤：子区枚举来源单一）。
//
// 775 已落地 ⇒ 本源从「恒空」改为**纯转发**：子区清单一字都不存在这个文件里，
// 一律问 `subzone/SubzoneId`（全仓唯一定义点）。原注释写着「775 落地时只改本文件一处
// （枚举表挂进来），调用点一字不动」——照此办理：`BearingReach` 的调用点一个字没改。
//
// 第八百八十七批 · 缝的归属认账（887，2026-11-27）：**不回退**。
// · 此缝原计划由装配单统一接，775 提前接入，2026-11-27 由 887 认账。
// · 取消「另立装配单」的预期：接法就留在这里，790 不再单独维护本文件，也不许改回恒空。
// · 认账的三条理由与跨单影响写在 `docs/区域总表-下体.md` §四（少一次跨单引用／少一次改名风险／
//   本文件只做转发、长不出第二份清单）。
// · 留痕是本条的一部分：这段注释被删即红（`SubzoneThreeTierTest` 读本文件原文，要
//   「原计划」「775 提前接入」「2026-11-27」「887」四个要素都在）。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.subzone.SubzoneId

/** 子区枚举唯一来源。全仓不许有第二份子区清单（源码断言钉死，见测试）。 */
object SubzoneSource {

    /** 出处与现状：775 已落地，清单在 `state/subzone/SubzoneId`（本文件只转发，不存清单）。 */
    const val SOURCE: String = "775 已落地：state/subzone/SubzoneId（本文件只转发，不存清单）"

    /**
     * 某区的合法子区 code 清单。转发 `SubzoneId.ofZone`：登记过的区返回其子区，
     * 没登记过的区（睾丸／阴囊／精索／耻骨区／阴茎缝）返回空表——空表的意思是
     * 「775 没给这个区定子区」，不是「这个区没有子区」。
     */
    fun of(zoneId: String): List<String> = SubzoneId.ofZone(zoneId).map { it.code }
}
