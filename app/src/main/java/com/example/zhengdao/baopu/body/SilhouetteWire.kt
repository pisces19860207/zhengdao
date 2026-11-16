// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十六批 · 接线缝（~30 行）：H 的来源是形轨高度场。形轨高度场尚未建成
// （另单），本批先用夹具高度场独立成测；建成后换实现，本缝与核心模型不动。
package com.example.zhengdao.baopu.body

/** 形轨高度场来源：按位置输出凸起高度 H（cm，非负；契约由实现方保证）。 */
fun interface HeightFieldSource {
    fun bumpHeightCm(position: SilhouettePosition): Double
}

/** 666 接线：高度场 → 核心模型 → 三态＋一句话。C 一律查实测表，本缝不传数值型 C。 */
object SilhouetteWire {

    /** 夹具高度场：固定读数（腰线 1.2 / 裆缝 3.5 / 大腿根 2.8，cm），确定性、可单测。 */
    val FIXTURE: HeightFieldSource = HeightFieldSource { position ->
        when (position) {
            SilhouettePosition.WAIST -> 1.2
            SilhouettePosition.CROTCH -> 3.5
            SilhouettePosition.THIGH -> 2.8
        }
    }

    /** 从高度场取 H 走核心模型；阈值缺省用作者裁定表，换表走参数（缺表由核心拒绝）。 */
    fun grade(
        heightField: HeightFieldSource,
        fabricName: String,
        position: SilhouettePosition,
        posture: SilhouettePosture,
        thresholds: SilhouetteThresholds = SilhouetteThresholds.DEFAULT,
    ): SilhouetteVerdict = Silhouette.grade(
        bumpHeightCm = heightField.bumpHeightCm(position),
        fabricName = fabricName,
        position = position,
        posture = posture,
        thresholds = thresholds,
    )
}
