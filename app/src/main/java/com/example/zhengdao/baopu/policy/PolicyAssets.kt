// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：**随包发布的只读资产**的读取壳。
//
// 这是 policy 包里唯一碰 Android 的文件：其它文件（Policy/PolicyTable/PolicyRuntime/
// PolicyAudit）保持零 Android、JVM 直测。读法本身（严格解析、版本、fail closed）
// 都在 [PolicyTable]；这里只把 `assets.open` 包成一个 `() -> String?`。
//
// 没有写口、没有缓存、没有网络：表在 APK 里，改它必须重新发版。
package com.example.zhengdao.baopu.policy

import android.content.Context

internal object PolicyAssets {

    /** APK 内的路径（assets 根以下）。 */
    const val PATH = "baopu/policy_v1.json"

    /** 审计里的来源名。 */
    const val SOURCE = "assets/$PATH"

    /** 读资产并交给唯一读表入口；读不到/读坏都由 [PolicyTable.Load] 三态表达。 */
    fun load(context: Context): PolicyTable.Load =
        PolicyTable.load(SOURCE) {
            context.assets.open(PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
}
