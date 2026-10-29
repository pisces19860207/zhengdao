// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L21），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L21 紧急上报权**（法典卷七 L21）。
//
// ## 判据（可执行）
// 紧急卡要弹得出去，**三样缺一不可**：
//   ① `EMERGENCY` 标记；② **为什么无法可依**（引用了哪些条文都不适用）；③ **危险的时间窗**（多久内成真）。
// 卡面还必须同时给「立刻处置」与「先停下」两个选项，且**默认＝先停下**（安全侧）。
//
// 为什么默认必须是"先停下"：L21 是"法外的唯一合法出口"，出口的默认位一旦偏向"动手"，
// 每一次紧急都会变成一次未经审查的执行；默认停下，最坏结果是慢一点。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L21），它不会红**
// 生产码里还没有 `EMERGENCY` 这一层（`grep EMERGENCY app/src/main` 零命中）⇒
// 本条规范钉的是**判据形状**，缺陷 #271-L21 在账。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// 缺标记／没写为何无法可依／没给时间窗／默认＝立刻处置 ⇒ 四种卡都必须被拒。
package com.example.zhengdao.law.spec


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L21EmergencyCardSpec {

    internal data class EmergencyCard(
        val hasMarker: Boolean = true,
        /** 逐条写明"哪些条文都不适用"；空 ⇒ 拒。 */
        val whyNoLaw: List<String> = listOf("L5/L7/L8 都不覆盖「账号将被改」"),
        /** 危险多久内会成真（分钟）；≤0 ⇒ 拒。 */
        val windowMinutes: Int = 10,
        val options: Set<String> = setOf("立刻处置", "先停下"),
        /** 卡面默认选项是不是"先停下"。 */
        val defaultIsStop: Boolean = true,
    )

    internal object EmergencyGate {
        const val MARKER = "EMERGENCY"

        /** null = 允许弹卡；否则是拒的理由。 */
        fun admit(card: EmergencyCard): String? = when {
            !card.hasMarker -> "缺 $MARKER 标记：这只是一张普通卡，不许借紧急的名义绕过每日限额"
            card.whyNoLaw.none { it.isNotBlank() } -> "没写「为什么无法可依」：紧急权的前提是现行条文无一覆盖"
            card.windowMinutes <= 0 -> "没给危险时间窗：说不出多久内成真，就不是即时危险"
            !card.options.containsAll(setOf("立刻处置", "先停下")) -> "卡面必须同时给「立刻处置」与「先停下」"
            !card.defaultIsStop -> "默认必须是「先停下」（安全侧）——默认动手等于每次紧急都在执行"
            else -> null
        }
    }

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
