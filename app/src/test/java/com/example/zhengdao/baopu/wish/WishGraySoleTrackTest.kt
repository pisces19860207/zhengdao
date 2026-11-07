// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十六批 · 判据「灰度无第二套」的 grep 断言（钉源码，不钉行为）：
//   · 许愿入口的开关只认 506 的灰度登记表（CanaryRegistry/CanaryCapability.WISH_PIPELINE）；
//   · 本批接线层不许自己定义档位/阈值/梯子——那是第二套灰度；
//   · 许愿流水线内部的转正阈值仍与 SkillStore.PROMOTE_USES 同源（510 已钉，这里复钉防松动）。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WishGraySoleTrackTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 本批接线层（改动文件；wish 内部判据件不在其列——它们一行没动）。 */
    private val wiringFiles = listOf(
        "src/main/java/com/example/zhengdao/baopu/wish/WishFlow.kt",
        "src/main/java/com/example/zhengdao/baopu/agent/WishIOWiring.kt",
        "src/main/java/com/example/zhengdao/baopu/wish/WishTrigger.kt",
    )

    @Test
    fun 入口档位只认506登记表() {
        val flow = repoFile(
            "src/main/java/com/example/zhengdao/baopu/wish/WishFlow.kt",
        ).readText()
        assertTrue(
            "入口必须问 506 的登记表（CanaryCapability.WISH_PIPELINE）",
            flow.contains("CanaryCapability.WISH_PIPELINE"),
        )
        assertTrue(
            "档位必须从 CanaryRegistry.defaults() 读（不另造开关源）",
            flow.contains("CanaryRegistry.defaults()"),
        )
        assertTrue(
            "关闭档必须放行进引擎（不是吞掉）",
            flow.contains("CanaryBand.OFF"),
        )
    }

    @Test
    fun 接线层不另造第二套灰度() {
        val bandDecl = Regex("""enum class \w*(Band|Gray|Ladder)""")
        val thresholdDecl = Regex("""const val \w*(THRESHOLD|LIMIT_PCT|BAND|USES|PROMOTE)""")
        for (rel in wiringFiles) {
            val text = repoFile(rel).readText()
            assertFalse("$rel 不许自己定义档位/梯子", bandDecl.containsMatchIn(text))
            assertFalse("$rel 不许自己定义灰度阈值", thresholdDecl.containsMatchIn(text))
        }
    }

    @Test
    fun 流水线转正阈值仍是SkillStore那把() {
        assertEquals(SkillStore.PROMOTE_USES, WishGrayRelease.PROMOTE_THRESHOLD)
    }

    @Test
    fun 登记表里许愿流缺省不是全开() {
        assertEquals(
            "许愿流缺省＝灰度档（CANARY），不许缺省全开",
            com.example.zhengdao.baopu.consequence.CanaryBand.CANARY,
            com.example.zhengdao.baopu.consequence.CanaryRegistry
                .defaults()[com.example.zhengdao.baopu.consequence.CanaryCapability.WISH_PIPELINE],
        )
        assertTrue(
            "506 的『缺省不许全开』负例对本批登记照跑",
            com.example.zhengdao.baopu.consequence.CanaryRegistry.violations().isEmpty(),
        )
    }
}
