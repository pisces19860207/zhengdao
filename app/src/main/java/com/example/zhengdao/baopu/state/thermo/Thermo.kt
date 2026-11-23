// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 对外门面：一次调用完成"判据→求值→红线闸→出口"。
// 只读、纯函数、零装配、零落库；731 等下游只需读 ThermoReading.surface。
package com.example.zhengdao.baopu.state.thermo

/** 对外唯一入口。红线命中则 verdict.red=true 且 reading=null（红不出值）。 */
internal object Thermo {

    fun judge(input: ThermoInput): ThermoVerdict {
        val codes = ArrayList<String>()
        val messages = ArrayList<String>()
        fun collect(issues: List<String>) {
            issues.forEach { codes.add(it.substringBefore(":").trim()); messages.add(it) }
        }
        collect(ThermoChecks.mechanismsComplete())
        collect(ThermoChecks.directionsHold())
        collect(ThermoChecks.noPathological(noteOf(input)))
        if (codes.isNotEmpty()) return ThermoVerdict.bad(codes, messages)

        val reading = ThermoEval.eval(input)
        collect(ThermoChecks.noContinuousLeak(ThermoRenderer.render(reading)))
        if (codes.isNotEmpty()) return ThermoVerdict.bad(codes, messages)
        return ThermoVerdict.ok(reading)
    }

    /** 场景文字里若带病理性温度词也要拦；本门面本身不产生这类词。 */
    private fun noteOf(input: ThermoInput): String = when (input.trigger) {
        CremasterTrigger.NONE -> ""
        else -> input.trigger.label
    }

    fun render(value: ThermoReading): String = ThermoRenderer.render(value)
}
