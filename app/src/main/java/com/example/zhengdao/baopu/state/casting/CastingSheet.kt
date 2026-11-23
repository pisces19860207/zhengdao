// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十七批 · 唯一出口：castingSheet(roleId) -> 档位向量 + ≤120 字人话摘要。
// 摘要只给 738 注入用：排除下体组（敏感层不进注入）、不产出 722 三行格式（不变量/状态/词档）、
// 不写点值、不判评价——只是一句「这个人长什么样」的可读描述。
package com.example.zhengdao.baopu.state.casting

/** 一份外观卡输出：档位向量（全字段，含下体，供判定层读）＋人话摘要（供注入）。 */
data class CastingSheetResult(
    val roleId: String,
    val vector: List<FieldGrade>,
    val summary: String,
) {
    /** 稳定快照，用于确定性比对。 */
    fun line(): String = "role=$roleId|vector=${vector.joinToString(";") { it.line() }}|summary=$summary"
}

/** 档位向量的一维：字段 id + 档名列表（逐字取自既有档）。 */
data class FieldGrade(val fieldId: String, val cn: String, val grades: List<String>) {
    fun line(): String = "$fieldId(${cn})=${grades.joinToString("+")}"
}

/** 摘要字数上限，硬钉死；超了按字段次序截断，绝不让下体挤进来。 */
const val SUMMARY_LIMIT = 120

/**
 * 对外唯一出口。给定一张卡，产出档位向量与人话摘要。
 * 这里不做校验（校验在 CastingChecks），只做可读化——判定与渲染职责分开。
 */
object CastingSheet {

    fun of(card: CastingCard): CastingSheetResult = CastingSheetResult(
        roleId = card.roleId,
        vector = vectorOf(card),
        summary = summaryOf(card),
    )

    /** 档位向量：按白名单次序出已设定字段，未设定不占项。 */
    private fun vectorOf(card: CastingCard): List<FieldGrade> =
        CastingRefs.CARD_FIELDS.filter { card.has(it.id) }
            .map { FieldGrade(it.id, it.cn, card.of(it.id)) }

    /**
     * ≤120 字摘要。只取非敏感组（脸/体型/部位/头发/标记），下体组一律不进注入；
     * 每组最多两项、每字段最多两个档，按白名单次序拼；超 120 字在最后一个完整词处截断。
     */
    private fun summaryOf(card: CastingCard): String {
        val safeGroups = setOf(CardGroup.FACE, CardGroup.BODY, CardGroup.PART, CardGroup.HAIR, CardGroup.MARK)
        val parts = ArrayList<String>()
        var perGroup = HashMap<CardGroup, Int>()
        CastingRefs.CARD_FIELDS.forEach { ref ->
            if (ref.group !in safeGroups || (perGroup[ref.group] ?: 0) >= 2 || !card.has(ref.id)) return@forEach
            val grades = card.of(ref.id).take(2)
            parts += "${ref.cn}${grades.joinToString("、")}"
            perGroup[ref.group] = (perGroup[ref.group] ?: 0) + 1
        }
        return clip(join(parts))
    }

    /** 拼接：每段之间用「，」，段内已用「、」，不产生 722 三行的「|」分隔符。 */
    private fun join(parts: List<String>): String =
        if (parts.isEmpty()) "这张卡尚未设定外观" else parts.joinToString("，") + "。"

    /** 按完整段截断到 [SUMMARY_LIMIT] 字（中文按 char 计），不切半个词、不留逗号尾巴。 */
    private fun clip(text: String): String {
        if (text.length <= SUMMARY_LIMIT) return text
        var cut = SUMMARY_LIMIT
        while (cut > 0 && text[cut - 1] != '，' && text[cut - 1] != '。') cut--
        val head = text.substring(0, cut).trimEnd('，', '、')
        return if (head.isEmpty()) text.substring(0, SUMMARY_LIMIT) else "$head。"
    }
}
