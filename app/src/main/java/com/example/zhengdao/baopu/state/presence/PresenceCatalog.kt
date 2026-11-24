// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十九批之后的定稿把这张矩阵**降级为参考**：它只回答「这类场所通常怎么处理」，
// 不参与任何判定，也不 produce issue。判据函数里不许调用本表的取档接口（源码门禁钉死）。
package com.example.zhengdao.baopu.state.presence

object PresenceCatalog {

    /** 参考矩阵的一格。tier 用四档应对的名字表示，不是判据结论。 */
    data class MatrixCell(
        val row: String,
        val venue: String,
        val tier: String,
        val note: String,
    ) {
        init {
            require(row.isNotBlank() && venue.isNotBlank() && tier.isNotBlank() && note.isNotBlank()) {
                "参考矩阵每格必须带行、场所、档与注"
            }
        }

        fun line(): String = row + "@" + venue + "=" + tier + "（" + note + "）"
    }

    const val MATRIX_ROW_COUNT: Int = 4
    const val MATRIX_VENUE_COUNT: Int = 4
    const val MATRIX_CELL_COUNT: Int = MATRIX_ROW_COUNT * MATRIX_VENUE_COUNT

    private val referenceMatrix: List<MatrixCell> = listOf(
        MatrixCell("同性同龄含小孩", "家里", "自然", "家庭常态"),
        MatrixCell("同性同龄含小孩", "大澡堂", "自然", "分区常态"),
        MatrixCell("同性同龄含小孩", "多人宿舍", "自然", "同室常态"),
        MatrixCell("同性同龄含小孩", "合租房", "穿内裤", "看合租习惯"),
        MatrixCell("同性长辈在场", "家里", "披遮", "习惯差异大"),
        MatrixCell("同性长辈在场", "大澡堂", "披遮", "习惯差异大"),
        MatrixCell("同性长辈在场", "多人宿舍", "披遮", "习惯差异大"),
        MatrixCell("同性长辈在场", "合租房", "披遮", "习惯差异大"),
        MatrixCell("异性在场", "家里", "穿内裤", "默认应对"),
        MatrixCell("异性在场", "大澡堂", "穿内裤", "分区默认应对"),
        MatrixCell("异性在场", "多人宿舍", "穿内裤", "默认应对"),
        MatrixCell("异性在场", "合租房", "穿内裤", "默认应对"),
        MatrixCell("有陌生人", "家里", "自然", "家里更宽"),
        MatrixCell("有陌生人", "大澡堂", "自然", "分区内常态"),
        MatrixCell("有陌生人", "多人宿舍", "披遮", "参考"),
        MatrixCell("有陌生人", "合租房", "穿戴整齐", "明确外人"),
    )

    init {
        require(referenceMatrix.size == MATRIX_CELL_COUNT) { "参考矩阵必须四行×四场所共 16 格" }
        require(referenceMatrix.map { it.row }.distinct().size == MATRIX_ROW_COUNT) { "参考矩阵缺行" }
        require(referenceMatrix.map { it.venue }.distinct().size == MATRIX_VENUE_COUNT) { "参考矩阵缺场所" }
        val tiers = CanBeNude.entries.map { it.label.substringBefore("（") }
        require(referenceMatrix.all { it.tier in tiers }) { "参考矩阵出现四档之外的档名" }
        require(Venue.entries.all { it.routine }) { "六场所全部是日常身体场景" }
    }

    /** 只供展示与人工参考；判据函数禁止调用。 */
    fun matrix(): List<MatrixCell> = referenceMatrix

    /** 六场所全是日常身体场景，判据据此抑制避讳类提示。 */
    fun isRoutine(venue: Venue): Boolean = venue.routine

    fun referenceLine(): String = referenceMatrix.joinToString("；") { it.line() }
}
