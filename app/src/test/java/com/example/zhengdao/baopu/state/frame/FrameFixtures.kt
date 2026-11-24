// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百六十五批 · 测试夹具：构造骨点单，判据测试共用。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodytype.AgeTier
import com.example.zhengdao.baopu.state.bodytype.BodyFatTier
import com.example.zhengdao.baopu.state.bodytype.BodyProfile
import com.example.zhengdao.baopu.state.bodytype.BodyStateTier
import com.example.zhengdao.baopu.state.bodytype.MuscleTier
import com.example.zhengdao.baopu.state.bodytype.SectionGrade
import com.example.zhengdao.baopu.state.bodytype.SectionSpec
import com.example.zhengdao.baopu.state.bodytype.SkeletonTier

/** 夹具工厂：默认一张体脂中档的单，按需改体脂或骨架。 */
object FrameFixtures {

    fun profile(
        role: String = "甲",
        fat: BodyFatTier = BodyFatTier.MID,
        skeleton: SkeletonTier = SkeletonTier.MID,
    ): BodyProfile = BodyProfile(
        roleId = role,
        skeletonTier = skeleton,
        bodyFatTier = fat,
        muscleTier = MuscleTier.MID,
        ageTier = AgeTier.MID,
        bodyStateTier = BodyStateTier.SEDENTARY,
    )

    fun person(
        role: String = "甲",
        fat: BodyFatTier = BodyFatTier.MID,
        skeleton: SkeletonTier = SkeletonTier.MID,
        readings: List<PointReading>? = null,
    ): FramePerson {
        val p = profile(role, fat, skeleton)
        return FramePerson(role, p, readings?.let { BoneFrame(it) } ?: BoneFrame.of(p))
    }

    /** 单点改成指定可见档，其余保持推导值——负例入口。 */
    fun withVisible(base: FramePerson, point: FramePoint, visible: VisibleGrade): FramePerson {
        val readings = base.frame.all().map { reading ->
            if (reading.point == point) reading.copy(visible = visible) else reading
        }
        return FramePerson(base.roleId, base.profile, BoneFrame(readings))
    }

    fun section(width: SectionGrade = SectionGrade.MID): SectionSpec = SectionSpec(
        width = width,
        thickness = SectionGrade.MID,
        girth = SectionGrade.MID,
    )
}
