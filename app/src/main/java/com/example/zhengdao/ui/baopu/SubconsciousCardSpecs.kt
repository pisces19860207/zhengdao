// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十六批 · 回响信号的**卡面与类型签**工厂（纯映射，照 `DecisionCardSpecs` 房型）。
//
// 三条边界：
// 1. 只做「已有信号 → 规格」的映射，不读盘、不碰引擎、不判三态（右相判过才浮出来，
//    浮出的恒为可建议——卡面只是如实转述）；
// 2. 卡上**只有一枚收尾钮**（看过就收）——回响是信号不是任务，没有采纳/拒绝那一路，
//    更没有「替人执行」的按钮（判据⑥在源码门禁里钉）；
// 3. 文案全部走 R（中英双份在 strings_baopu_subconscious.xml），这里不发明内容。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.subconscious.EchoKind
import com.example.zhengdao.baopu.subconscious.SubconsciousSignal

/** typeLabel（「建议」页签第四类行；字面值下意识/想起，灵感归 677）。 */
internal fun subconsciousTypeRes(kind: EchoKind): Int = when (kind) {
    EchoKind.FAIL_RECENT -> R.string.decision_type_subconscious
    EchoKind.STEP_UNFINISHED -> R.string.decision_type_recall
    EchoKind.MENTIONED_NOT_DONE -> R.string.decision_type_recall
}

/** 回响卡规格工厂（无状态；同入参同出参）。 */
internal object SubconsciousCardSpecs {

    fun spec(s: SubconsciousSignal, str: DecisionStrRes): DecisionCardSpec = DecisionCardSpec(
        variant = DecisionVariant.SUGGESTION,
        key = "sub:${s.id}",
        title = str.get(R.string.subconscious_card_title, s.title),
        subtitle = str.get(
            R.string.subconscious_row_subtitle,
            s.ledgerId,
        ),
        body = str.get(R.string.subconscious_card_note),
        footer = str.get(R.string.subconscious_card_basis, s.ledgerId),
        buttons = listOf(
            DecisionButton("dismiss", str.get(R.string.subconscious_card_dismiss), DecisionButtonRole.PRIMARY),
        ),
    )
}
