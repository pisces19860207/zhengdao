// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 渲染唯一出口：把判定通过的值翻成四件输出（paths/poolAt/dripAt/posture）。
// 只拼接档名与部位名，不生成任何数值；段序固定，同输入逐字相同。
package com.example.zhengdao.baopu.state.surfaceflow

/** 四件输出的唯一出口。别处不得自行拼这些段。 */
internal object SurfaceFlowRenderer {

    fun render(value: SurfaceFlowResult): FlowOutputs = FlowOutputs(
        paths = renderPaths(value),
        poolAt = renderPools(value),
        dripAt = renderDrips(value),
        posture = value.posture.label,
    )

    /** 路径按声明序；毛束修饰附在末尾，只有真记了毛束才出现。 */
    private fun renderPaths(value: SurfaceFlowResult): List<String> {
        val lines = value.paths.map { path ->
            val dest = path.to?.label ?: "止"
            "${path.kind.label}[${path.from.label}→$dest]·${path.behavior.label}"
        }
        if (value.hairWicks.isEmpty()) return lines
        val wick = "毛束[" + value.hairWicks.joinToString("、") { "${it.site.label}=沾水" } + "]"
        return lines + wick
    }

    /** 积水按部位声明序输出；无积水记「无」，出口随行带出。 */
    private fun renderPools(value: SurfaceFlowResult): List<String> {
        if (value.pools.isEmpty()) return listOf("无")
        return value.pools.sortedBy { it.site.ordinal }.map { pool ->
            val outlet = pool.outlet?.label ?: "未记"
            "${pool.site.label}=${pool.level.label}·出口$outlet"
        }
    }

    /** 滴落按部位声明序输出；最低点必随行。 */
    private fun renderDrips(value: SurfaceFlowResult): List<String> {
        if (value.drips.isEmpty()) return listOf("无")
        return value.drips.sortedBy { it.site.ordinal }.map { drip ->
            val lowest = if (drip.isLowest == true) "是" else "否"
            "${drip.site.label}=最低点$lowest"
        }
    }
}
