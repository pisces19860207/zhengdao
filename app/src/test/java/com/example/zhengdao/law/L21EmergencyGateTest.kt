// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典门禁 **L21 紧急上报权**（法典卷七 L21）。
//
// 由 271 批的规范（law/spec/L21EmergencyCardSpec.kt，随本批删除）升格：
// 判据已搬进生产 `baopu/law/EmergencyGate.kt`，用例名照 spec 承诺逐字不变（#271-L21 销账）。
//
// ## 判据（可执行，判的是生产 EmergencyGate.admit）
// 三样缺一不可：① `EMERGENCY` 标记；② 为什么无法可依（逐条写明哪些条文都不适用）；
// ③ 危险的时间窗（多久内成真）。卡面必须同时给「立刻处置」「先停下」，且默认＝先停下。
//
// ## 负例（造违规即红，红在生产 admit 上）
// 缺标记／没写为何无法可依／没给时间窗／默认＝立刻处置 ⇒ 四种卡都必须被拒，且四条理由各不相同。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.law.EmergencyCard
import com.example.zhengdao.baopu.law.EmergencyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L21EmergencyGateTest {

    // ── 正例：三样齐 + 两个选项 + 默认停下 ─────────────────────────────────

    @Test
    fun `L21_正例_三样齐的紧急卡可以弹`() {
        assertNull("完整的紧急卡应当允许弹", EmergencyGate.admit(EmergencyCard()))
        assertNull(
            "多个不适用条文也算说清了",
            EmergencyGate.admit(EmergencyCard(whyNoLaw = listOf("L5 不覆盖", "L7 不覆盖"))),
        )
    }

    // ── 负例：四种残缺卡都必须被拒 ─────────────────────────────────────────

    @Test
    fun `L21_负例_缺标记或缺为何无法可依或缺时间窗或默认动手一律拒`() {
        val noMarker = EmergencyGate.admit(EmergencyCard(hasMarker = false))
        assertTrue("缺 EMERGENCY 标记必须拒", noMarker != null && noMarker.contains("EMERGENCY"))

        val noWhy = EmergencyGate.admit(EmergencyCard(whyNoLaw = emptyList()))
        assertTrue("没写为何无法可依必须拒", noWhy != null && noWhy.contains("无法可依"))

        val noWindow = EmergencyGate.admit(EmergencyCard(windowMinutes = 0))
        assertTrue("没给时间窗必须拒", noWindow != null && noWindow.contains("时间窗"))

        val defaultGo = EmergencyGate.admit(EmergencyCard(defaultIsStop = false))
        assertTrue("默认动手必须拒", defaultGo != null && defaultGo.contains("先停下"))

        val oneOption = EmergencyGate.admit(EmergencyCard(options = setOf("立刻处置")))
        assertTrue("只给一个选项必须拒", oneOption != null)

        // 四条理由各不相同（不是一句话糊弄）
        assertEquals(
            "四种残缺必须给四种不同的理由",
            4,
            setOf(noMarker, noWhy, noWindow, defaultGo).size,
        )
    }
}
