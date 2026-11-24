// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十五批 · 骨点单：一处读数怎么写，一张单子怎么算齐。
//
// 「三项齐」是结构性的：一条读数必带 visible / relative / palpable，
// 少一项构造不出来；relative 由点位的八向闭集给出，不另存第二份方位。
//
// 正式入口只有 [BoneFrame.of]：它从 815 的 BodyProfile 真枚举推每项读数。
// 裸构造器留给判据做负例（例如「体脂高却写分明」）；判据必须能看见负例才咬得住。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodytype.BodyProfile
import com.example.zhengdao.baopu.state.view.Octant

/** 一条骨点读数：三项齐。 */
data class PointReading(
    val point: FramePoint,
    val visible: VisibleGrade,
    val palpable: PalpableGrade,
) {
    /** 八向方位是点位属性，不另存副本，避免两处真相。 */
    val relative: Octant get() = point.relative

    /** 补丁五：归属区一行；zoneId 与 subzoneId 是两个字段，分别落账。 */
    fun zoneLine(): String =
        point.label + "｜区 " + point.zoneName + "（zoneId=" + point.zoneId +
            "·subzoneId=" + (point.subzoneId ?: "无") + "）"

    /** 一行读数：归属区＋显／向／触三项逐字落档。 */
    fun line(): String =
        point.label + "（" + point.zoneName + "）｜显 " + visible.label +
            "·向 " + relative.label + "·触 " + palpable.label
}

/**
 * 一张骨点单。构造即校验：点数齐、无重复、无缺项。
 * 裸构造器是判据的负例入口；正常装配请走 [of]。
 */
class BoneFrame(readings: List<PointReading>) {

    private val items: List<PointReading> = readings.toList()
    private val byPoint: Map<FramePoint, PointReading> = items.associateBy { it.point }

    init {
        require(items.isNotEmpty()) { EMPTY_REJECT }
        require(byPoint.size == items.size) { DUPLICATE_REJECT }
        val missing = FrameCatalog.POINTS.filterNot { byPoint.containsKey(it) }
        require(missing.isEmpty()) {
            MISSING_PREFIX + missing.size + " 点缺。" + missing.joinToString("、") { it.label }
        }
    }

    val size: Int get() = items.size

    /** 全部读数，顺序＝目录表书写序，逐字可回放。 */
    fun all(): List<PointReading> = FrameCatalog.POINTS.map { byPoint.getValue(it) }

    fun reading(point: FramePoint): PointReading = byPoint.getValue(point)

    /** 唯一出口：一行一点，出门前过文字闸。 */
    fun line(): String {
        val text = all().joinToString("\n") { it.line() }
        FrameGuard.rejectAll(text)
        return text
    }

    companion object {
        const val EMPTY_REJECT: String = "骨点单不能是空的：十四个点都要给档"
        const val DUPLICATE_REJECT: String = "同一骨点记了两次：两个真相"
        const val MISSING_PREFIX: String = "骨点单不齐："

        /** 表驱动取，不手数。 */
        val EXPECTED_SIZE: Int get() = FrameCatalog.POINTS.size

        /** 正式入口：只读 815 真枚举，逐点推 visible 与 palpable。 */
        fun of(profile: BodyProfile): BoneFrame = BoneFrame(
            FrameCatalog.POINTS.map { point ->
                PointReading(
                    point = point,
                    visible = FrameCatalog.visibleOf(point, profile.bodyFatTier),
                    palpable = FrameCatalog.palpableOf(point, profile.bodyFatTier),
                )
            },
        )
    }
}

/** 同场一个人：815 体型档案＋骨点单。 */
data class FramePerson(
    val roleId: String,
    val profile: BodyProfile,
    val frame: BoneFrame,
) {
    init {
        require(roleId.isNotBlank()) { "角色缺标识：结论点名不了" }
    }

    fun line(): String =
        roleId + "（体脂 " + profile.bodyFatTier.label + "·骨架 " + profile.skeletonTier.label +
            "）｜" + frame.line()
}
