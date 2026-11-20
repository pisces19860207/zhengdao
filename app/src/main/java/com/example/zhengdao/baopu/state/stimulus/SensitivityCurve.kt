// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 734 §一②③、§二②＋追加长期暴露：
//   ② 适应：同强度持续 ⇒ 敏感档每 N 拍降 1 档（726 节拍口径）。
//   ③ 饱和：越过阈值 ⇒ 敏感档不升反降（方向反转断言）。
//   ④ 恢复：停止后敏感档按 728 恢复档回升，必然回升（时间夹具）。
//   长期：暴露/摩擦累积 ⇒ 基线最多降一档，可逆但慢、不即时回弹。
//
// 敏感档整体是一条「先随强度升、越过饱和阈值后反转下降」的曲线；
// 表驱动、纯函数、无积分器；短期饱和与长期基线分两段独立记。
package com.example.zhengdao.baopu.state.stimulus

internal object SensitivityCurve {
    const val MAX_INTENSITY = 5
    const val MAX_LEVEL = SensitivityGrade.MAX

    /** 饱和阈值：刺激强度 > 此档即进入饱和区，敏感开始反转下降。 */
    const val SATURATION_THRESHOLD = 3

    /**
     * 敏感随刺激强度的表：0..3 随着升（未饱和），4..5 反转下降（饱和）。
     * 关键负例：阈值后仍单调升 ⇒ 红；这里 [3,2,1] 严格降，方向反转成立。
     */
    private val TABLE = intArrayOf(0, 1, 2, 3, 2, 1)

    fun at(intensity: Int): Int = TABLE[intensity.coerceIn(0, MAX_INTENSITY)]

    /** 饱和不适档：未饱和 0，越阈后 1、2。 */
    fun discomfortAt(intensity: Int): Int =
        DiscomfortGrade.of((intensity - SATURATION_THRESHOLD).coerceIn(0, DiscomfortGrade.MAX)).level

    fun isSaturated(intensity: Int): Boolean = intensity > SATURATION_THRESHOLD
}
