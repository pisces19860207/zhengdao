// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十八批 · 校准块**注入核心**（纯函数，零 IO／零模型／零闸引用）。
//
// 三档预算：L0 三行（直吃 722 ReadbackLines.asContextNotes，不重造格式）≤200 token；
//          L1 ≤3 条、L2 ≤8 条，每条 ≤60 字且自带 source + asOf；任何情况不全文注入。
// 三病对照：网文腔⇒词档行在 +「禁用默认词」提醒；医学腔⇒「非医疗判断」；
//          塌缩请求⇒变体档出现且不全给同一档。
// 边界：只产出 List<String> 纯文本 notes；不进任何闸、不改判定（判据④）。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.baopu.state.readback.ReadbackLines

/** 一次注入的可断言结果（计数与文本都落在这里，判据直接钉字段）。 */
internal data class Injection(
    val notes: List<String>,
    val level: CalibrationLevel,
    val entryCount: Int,
    val droppedNoSource: Int,
    val tokenDelta: Int,
    val style: CalibrationStyle,
    val vocabReminder: Boolean,
    val medicalNotice: Boolean,
    val variantLevelsShown: List<String>,
) {
    val hasVocabLine: Boolean get() = notes.any { it.startsWith(BodyReadbackVocabTag) }

    companion object {
        /** 词档行行头（722 模板常量，不在此重造，仅用于识别）。 */
        const val BodyReadbackVocabTag = "[词档]"

        fun empty(): Injection = Injection(
            notes = emptyList(),
            level = CalibrationLevel.OFF,
            entryCount = 0,
            droppedNoSource = 0,
            tokenDelta = 0,
            style = CalibrationStyle.NEUTRAL,
            vocabReminder = false,
            medicalNotice = false,
            variantLevelsShown = emptyList(),
        )
    }
}

/** 注入核心：按决策拼装 notes；[readback] 必须是 722 真三行（不在此重造）。 */
internal object CalibrationInject {

    const val L1_MAX = 3
    const val L2_MAX = 8

    /**
     * @param readback 722 三行；未命中或未装配时调用方应返回 [Injection.empty]，不传假三行。
     * @param entries 来自 711/719 的候选条目（可含缺源项，由本函数丢弃）。
     * @param variants 来自 723 的档位名（塌缩请求用）。
     */
    fun build(
        decision: TriggerDecision,
        readback: ReadbackLines,
        entries: List<SourceEntry>,
        variants: List<String>,
    ): Injection {
        if (!decision.calibrate) return Injection.empty()
        val usable = CalibrationSource.usable(entries)
        val dropped = entries.size - usable.size
        val cap = when (decision.level) {
            CalibrationLevel.L1 -> L1_MAX
            CalibrationLevel.L2 -> L2_MAX
            // L0 / OFF 不追加索引条目。
            else -> 0
        }
        val picked = pickRelevant(usable, decision, cap)
        val notes = ArrayList<String>()
        // L0 基础：直接吃 722 三行，逐行原样，不重造格式、不改字。
        notes.addAll(readback.asContextNotes())
        // 风格提醒（三病对照）。
        val vocabReminder = decision.style == CalibrationStyle.WEBNOVEL
        val medicalNotice = decision.style == CalibrationStyle.MEDICAL
        if (vocabReminder) notes += REMIND_VOCAB
        if (medicalNotice) notes += NOTICE_MEDICAL
        // 塌缩请求：变体档（至少两档，不全给同一档）。
        val shown = if (decision.collapse) variants.take(VARIANT_MAX) else emptyList()
        shown.forEach { notes += "[变体] $it" }
        // 带源条目：每条自带 source + asOf（判据③）。
        picked.forEach { notes += formatEntry(it) }
        return Injection(
            notes = notes,
            level = decision.level,
            entryCount = picked.size,
            droppedNoSource = dropped,
            tokenDelta = TokenEstimator.estimate(notes.joinToString("\n")),
            style = decision.style,
            vocabReminder = vocabReminder,
            medicalNotice = medicalNotice,
            variantLevelsShown = shown,
        )
    }

    /** 只按命中部位/状态做确定性选择（非检索）：命中词出现在正文则优先，其余按原序补足。 */
    private fun pickRelevant(
        usable: List<SourceEntry>,
        decision: TriggerDecision,
        cap: Int,
    ): List<SourceEntry> {
        if (cap <= 0 || usable.isEmpty()) return emptyList()
        val keys = decision.matchedZones
        val hit = if (keys.isEmpty()) emptyList() else usable.filter { e -> keys.any { it in e.text } }
        val rest = usable.filterNot { it in hit }
        return (hit + rest).take(cap)
    }

    /** 条目行：source id + 正文（clip ≤60 字）+ source + asOf，一条不缺。 */
    private fun formatEntry(e: SourceEntry): String =
        "[校准·${e.id}] ${CalibrationSource.clip(e.text)}｜来源:${e.source}｜asOf:${e.asOf}"

    const val VARIANT_MAX = 4
    const val REMIND_VOCAB = "[校准提醒] 词档行所列部位为正名；网文腔禁用默认词（如「一柱擎天」），一律按可观察特征写。"
    const val NOTICE_MEDICAL = "[校准提醒] 以下仅为创作描述，非医疗判断，不作诊断或治疗依据。"
}
