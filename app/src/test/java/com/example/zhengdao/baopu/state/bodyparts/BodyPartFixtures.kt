// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零五批 · 测试夹具：按目录表造完整单子，避免手写 72 条读数的漂移。
package com.example.zhengdao.baopu.state.bodyparts

internal object BodyPartFixtures {

    /** 按目录表造一张完整单子；默认全 0 档、无依据（高凸这类须依据的档会被构造拦下，正好用得上）。 */
    fun sheet(
        level: (Dim, Side, Posture) -> Int = { _, _, _ -> 0 },
        basis: (Dim, Int) -> String? = { _, _ -> null },
    ): BodyParts = BodyParts(BodyPartCatalog.specs.flatMap { spec ->
        BodyPartCatalog.sidesOf(spec).flatMap { side ->
            spec.postures.map { posture ->
                val lv = level(spec.dim, side, posture)
                Reading(spec.dim, side, posture, lv, basis(spec.dim, lv))
            }
        }
    })

    /** 干净单：中位档，配体脂中等、间距很宽时不触发任何判据。 */
    fun clean(): BodyParts = sheet(level = { dim, _, _ -> cleanLevel(dim) })

    private fun cleanLevel(dim: Dim): Int = when (dim) {
        Dim.PEC_SYMMETRY -> Symmetry.FULL.level
        Dim.AREOLA_RATIO -> AreolaRatio.TWO_TO_THREE.level
        Dim.AREOLA_REACH -> AreolaReach.WITHIN_PEC.level
        Dim.LARYNX -> LarynxGrade.FAINT.level
        Dim.NIPPLE_HEIGHT -> NippleHeight.SLIGHT.level
        else -> 1
    }

    /** 换掉一条读数（同 dim／side／posture）；新档须依据而没给时，copy 会按规则拒。 */
    fun with(
        body: BodyParts,
        dim: Dim,
        side: Side,
        posture: Posture,
        level: Int,
        basis: String? = null,
    ): BodyParts {
        val replaced = body.all().map { r ->
            if (r.dim == dim && r.side == side && r.posture == posture) {
                r.copy(level = level, basis = basis)
            } else {
                r
            }
        }
        return BodyParts(replaced)
    }
}
