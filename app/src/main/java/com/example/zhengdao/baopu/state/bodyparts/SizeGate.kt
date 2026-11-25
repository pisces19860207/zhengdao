// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十批 · 尺寸层出口：成年才出档，未成年整层不出（判据⑤）。
//
// 这个文件是整批的**唯一装配点**，也是「四向全禁」被断言钉住的地方：
// 它收下 BodyContext，却只读 `context.adult` 一处。源码门禁数着这个字面量——
// 出现第二次（比如 `context.stature > 180`）测试就红，不用等到有人读出「身高推尺寸」。
package com.example.zhengdao.baopu.state.bodyparts

/** 尺寸层：要么出档，要么整层不出。没有「出半层」这种形状。 */
sealed interface SizedLayer {

    fun line(): String

    /** 出档。profile 原样带出，出口不加工任何一档。 */
    data class Present(val profile: SizeProfile) : SizedLayer {
        override fun line(): String = profile.line()
    }

    /** 不出档。只给一句通用理由，不带任何档名（判据⑤：不写未成年段）。 */
    data class Absent(val reason: String) : SizedLayer {
        override fun line(): String = SizedLayer.ABSENT_PREFIX + reason
    }

    companion object {
        const val ABSENT_PREFIX: String = "尺寸层不出档："
    }
}

/** 出口。**只判成年**：三个体格维度不参与，年龄只进这道闸。 */
object SizeGate {

    const val MINOR_ABSENT: String = "未达成年口径，本层不出档"

    fun resolve(profile: SizeProfile, context: BodyContext): SizedLayer =
        if (context.adult) {
            SizedLayer.Present(profile)
        } else {
            SizedLayer.Absent(MINOR_ABSENT)
        }

    /** 报告用一行：出档就打印记录，不出档就打印理由。 */
    fun line(profile: SizeProfile, context: BodyContext): String = resolve(profile, context).line()
}
