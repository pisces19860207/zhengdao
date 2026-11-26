// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十批 · 勃起动态专批：两态与分型。
//
// 本批管什么、不管什么（派工单分工，机器可判，见 ErectSourceAssertTest）：
// · 810 管「两态与分型」：疲软↔勃起的形状／角度／硬度对照与增长分型；
// · 868 管「过程」：起多快、维持、消退两型、半勃占比——本包**不写时间曲线**；
// · 814 管「截面与廓形」——本包只引用，写 pending:814，不定义截面；
// · 775 管子区 id（在飞）——本包只写 pending:775，不自造子区名；
// · 804/831 管包皮位——本包对照表的包皮位行只引用它们的 AreaLevel 六档，不自造。
//
// 零 IO、零随机、零时钟、零装配：同输入必得同结论。
package com.example.zhengdao.baopu.state.erect

/** 本单唯一来源口径。 */
const val ERECT_SOURCE: String =
    "派工单-2026-11-24-第八百一十批（勃起动态：分型·硬度·两态对照）＋本项目工程假设"

/** 口径成形日。 */
const val ERECT_AS_OF: String = "2026-11-24"

/** 硬度档的字符串引用口径：对齐 facts.json 的 fb.hardness_ehs，不 import 660 类型。 */
const val HARDNESS_REF: String = "fb.hardness_ehs"

/**
 * 906：唯一允许出现 absent 的位置——**真的不知道**疲软态时才渲染它。
 * 疲软是已知状态，走 FullnessTier.FLACCID，永不使用本常量。
 */
const val FULLNESS_ABSENT: String = "absent"

/** 包皮位档的字符串引用口径：对齐 804/831 的六档覆盖档，不 import prepuce 类型。 */
const val PREPUCE_REF: String = "804/831 六档覆盖档"

/** 占位：775 子区 id 在飞；本包只标 pending，不自造名。 */
const val PENDING_SUBZONE: String = "pending:775"

/** 占位：814 截面与廓形未落地；本包只标 pending，不自造截面名。 */
const val PENDING_SECTION: String = "pending:814"
