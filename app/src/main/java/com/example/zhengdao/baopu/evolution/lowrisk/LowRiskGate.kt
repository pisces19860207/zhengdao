// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A（2026-11-10）· 低风险四域共用的**准入门**（纯判据，零 IO、零时钟）。
//
// 判定的每一格都来自既有链，本文件一条新判都不写：
//   · **473 灰度**：档位（关闭/灰度/全开）说了"能不能自己跑"——关闭 ⇒ 候选保留，
//     灰度/全开 ⇒ 放行（放行的每一次都由调用方落观察账，指标才从账上算得出来）；
//   · **517 深度**：深度档说了"能改多深"——改结构类（[EvoCandidate.deep]）只有深度到「深」
//     才许自动做（`EvolutionDepth` DEEP 行的 KDoc：「必须已经过了灰度」）；
//   · **三条红线**：碰线 ⇒ 直接拒（判据④：拒**并记账**——记账在 [LowRiskEvo.recordRefusal]，
//     这里只出结论；红线的优先级最高：档位开着也不放行，深度到了也不放行）。
//
// 顺序就是语义：红线 > 档位 > 深度。把深度放在档位前没有意义（深度档从不单独放行，
// 它只收紧；档位是"跑不跑"的门，深度是"跑多深"的门）。
//
// 判据③的落点在这一句里：**直接生效（跳过灰度）不可能**——档位关闭时 [admit] 永不返回
// [LowRiskVerdict.Allow]，而档位行只能由 473 的梯子或人写进账（`CanaryLadder.refusalOf`
// 把模型自调档拒在账外），所以"没过灰度就生效"在类型上没有路；源级门禁再钉一次缝
// （`LowRiskEvoGateTest`：生产调用点拔掉门 ⇒ 红）。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.EvolutionDepth

/** 准入结论（**闭集三态**；allow 之外的两态都不执行，区别在要不要记账）。 */
internal sealed interface LowRiskVerdict {

    /** 结论的一句话读数（Hold/Refuse 的原因在这里，提示与账共用同一句）。 */
    val note: String

    /** 放行：进灰度（或已全开）。调用方**必须**在动作落定后落一次观察账。 */
    data class Allow(override val note: String) : LowRiskVerdict

    /**
     * 暂缓：候选保留、一个字节不写、**不记账**（档位关闭/深度不够是"还没轮到它"，
     * 不是"它做错了"——按 495 的口径，没资格被判决的事不许记成失败）。
     * 提示走调用方的运行日志，与 `MaintBrake.deferredLine` 的延后同款。
     */
    data class Hold(override val note: String) : LowRiskVerdict

    /** 红线拒：候选不执行**并且必须记账**（判据④；记账走 [LowRiskEvo.recordRefusal]）。 */
    data class Refuse(val redLine: EvoRedLine, override val note: String) : LowRiskVerdict
}

/** 四域共用的准入门（纯函数；档位与深度由调用方从账上读好传入——本层不碰盘）。 */
internal object LowRiskGate {

    /**
     * 一条候选进不进灰度。
     *
     * 三格顺序：红线 → 档位 → 深度。任何一格不放行，候选就一个字节都不动。
     */
    fun admit(band: CanaryBand, depth: EvolutionDepth, candidate: EvoCandidate): LowRiskVerdict {
        val line = RedLines.hit(candidate)
        if (line != null) {
            return LowRiskVerdict.Refuse(
                line,
                RedLines.readout(candidate, line) + "——拒并记账，候选不执行",
            )
        }
        if (band == CanaryBand.OFF) {
            return LowRiskVerdict.Hold(
                "灰度档=关闭 ⇒ 本域自动化不自己跑（候选保留；要开走人工入口，机制不许自拨）",
            )
        }
        if (candidate.deep && depth.rank < EvolutionDepth.DEEP.rank) {
            return LowRiskVerdict.Hold(
                "深度档=${depth.cn} ⇒ 改结构类候选不自动做（517：深度到「深」才许动结构）",
            )
        }
        return LowRiskVerdict.Allow("灰度档=${band.cn}／深度=${depth.cn} ⇒ 放行进灰度（每次动作都落观察账）")
    }
}
