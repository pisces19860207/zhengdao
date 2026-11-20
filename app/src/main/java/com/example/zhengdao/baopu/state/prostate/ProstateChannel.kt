// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-20-第七百三十五批（前列腺通道）①②③与判据全部。
//
// P 通道唯一出口：感觉档×排出两型×触发三分类 → 一行事件，或一条带理由的拒绝。
// 三条闸（缺一不许出事件）：
//   1) 触发三分类必须显式——没给 ⇒ 拒；
//   2) unverified 默认关——没显式放行 ⇒ 不产生；放行 ⇒ 行内带「未核实」；
//   3) 要写射出必须显式给触发条件——没给 ⇒ 拒；前列腺相关默认走流出。
// 出口处再硬扫一遍：流出行里出现任何节律词 ⇒ 拒（fail-closed 兜底）。
// 本包不读 727 冲击档、不 import injury 包——感觉通道与力学通道分离。
package com.example.zhengdao.baopu.state.prostate

/** 节律词闭集：只许出现在射出侧。流出行出口硬扫用。 */
internal val RHYTHM_WORDS = listOf("节律", "搏动", "脉冲", "强弱")

/** 出口两态：事件行，或带理由的拒绝。拒绝不悄悄吞，理由照给。全包 internal，不加公开面。 */
internal sealed class ProstateOutcome {
    data class Event(val line: String) : ProstateOutcome()
    data class Rejected(val reason: String) : ProstateOutcome()
}

/** P 通道唯一出口（纯函数、零 IO、零时钟；同输入两次逐字节一致）。 */
internal object ProstateChannel {

    fun emit(
        sensation: ProstateSensation,
        trigger: TriggerClass?,
        kind: ExpulsionKind? = null,
        outflowVolume: OutflowVolume? = null,
        emissionCondition: String? = null,
        allowUnverified: Boolean = DEFAULT_ALLOW_UNVERIFIED,
    ): ProstateOutcome {
        val t = trigger ?: return ProstateOutcome.Rejected("触发三分类缺失，不许出事件")
        if (t == TriggerClass.UNVERIFIED && !allowUnverified) {
            return ProstateOutcome.Rejected("unverified 默认关：无任何刺激不产生事件")
        }
        val k = kind ?: ExpulsionKind.DEFAULT
        if (k == ExpulsionKind.EMISSION && emissionCondition.isNullOrBlank()) {
            return ProstateOutcome.Rejected("写射出必须显式给触发条件")
        }
        val expulsionText = if (k == ExpulsionKind.EMISSION) {
            "射出·${emissionCondition?.trim()}·${ExpulsionKind.EMISSION.trait}"
        } else {
            val v = OutflowVolume.of(outflowVolume?.level)
            "流出·${v.label}·${ExpulsionKind.OUTFLOW.trait}"
        }
        val triggerText = if (t == TriggerClass.UNVERIFIED) {
            "触发：${t.id}（${t.label}·$UNVERIFIED_TAG）"
        } else {
            "触发：${t.id}（${t.label}）"
        }
        val line = "前列腺·感觉：${sensation.label}（${sensation.observable}）；" +
            "排出：$expulsionText；$triggerText（$DISCLAIMER）"
        if (k == ExpulsionKind.OUTFLOW) {
            val hit = RHYTHM_WORDS.firstOrNull { it in line }
            if (hit != null) return ProstateOutcome.Rejected("流出行出现节律词：$hit")
        }
        return ProstateOutcome.Event(line)
    }
}
