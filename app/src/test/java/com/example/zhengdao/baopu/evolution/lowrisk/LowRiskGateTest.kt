// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · 四域共用准入门的纯判据。
// 判据③的行为面钉在这里：**直接生效（跳过灰度）不可能**——档位关闭时 admit 永不 Allow；
// 深度没到「深」时改结构类候选永不 Allow；红线候选永远 Refuse（比档位/深度都硬）。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.EvolutionDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowRiskGateTest {

    private val clean = EvoCandidate(domain = "fixture", target = "skill:project/签到流程", body = "1. 打开；2. 点签到。")

    private fun admit(band: CanaryBand, depth: EvolutionDepth = EvolutionDepth.DEFAULT, c: EvoCandidate = clean) =
        LowRiskGate.admit(band, depth, c)

    // ── 放行面（灰度/全开 ＋ 干净候选 ＋ 非结构类） ─────────────────────────

    @Test
    fun `灰度档放行_读数带档位与深度`() {
        val v = admit(CanaryBand.CANARY)
        assertTrue("灰度档要放行：$v", v is LowRiskVerdict.Allow)
        assertTrue(v.note.contains("灰度"))
    }

    @Test
    fun `全开档放行`() {
        assertTrue(admit(CanaryBand.FULL) is LowRiskVerdict.Allow)
    }

    // ── 判据③：直接生效（跳过灰度）被门拒住 ────────────────────────────────

    @Test
    fun `判据③_档位关闭_直接生效被拒成Hold`() {
        val v = admit(CanaryBand.OFF)
        assertTrue("关闭档不许放行（跳过灰度=红）：$v", v is LowRiskVerdict.Hold)
        assertTrue(v.note.contains("关闭"))
    }

    @Test
    fun `判据③_三档扫描_只有非OFF才Allow`() {
        CanaryBand.entries.forEach { band ->
            val v = admit(band)
            if (band == CanaryBand.OFF) {
                assertTrue("OFF 必须是 Hold：$band ⇒ $v", v is LowRiskVerdict.Hold)
            } else {
                assertTrue("非 OFF 放行：$band ⇒ $v", v is LowRiskVerdict.Allow)
            }
        }
    }

    // ── 517 深度：改结构类候选，深度不到「深」不自动做 ──────────────────────

    @Test
    fun `改结构类候选_深度浅_暂缓`() {
        val deep = clean.copy(deep = true)
        val v = admit(CanaryBand.CANARY, EvolutionDepth.SHALLOW, deep)
        assertTrue("浅档不许做结构类：$v", v is LowRiskVerdict.Hold)
        assertTrue(v.note.contains("深"))
    }

    @Test
    fun `改结构类候选_深度到深_放行`() {
        val deep = clean.copy(deep = true)
        assertTrue(admit(CanaryBand.CANARY, EvolutionDepth.DEEP, deep) is LowRiskVerdict.Allow)
    }

    @Test
    fun `非结构类候选_浅档照常放行_低风险四域产线形状`() {
        assertTrue(admit(CanaryBand.CANARY, EvolutionDepth.SHALLOW) is LowRiskVerdict.Allow)
    }

    // ── 红线：比档位和深度都硬（档开着也不放行） ────────────────────────────

    @Test
    fun `红线候选_全开档也拒`() {
        val bad = EvoCandidate(domain = "fixture", target = "app/src/main/A.kt")
        val v = admit(CanaryBand.FULL, EvolutionDepth.DEEP, bad)
        assertTrue("红线在全开档也要拒：$v", v is LowRiskVerdict.Refuse)
        assertEquals(EvoRedLine.APP_SOURCE, (v as LowRiskVerdict.Refuse).redLine)
        assertTrue(v.note.contains("拒"))
    }

    @Test
    fun `红线候选_关闭档_仍报Refuse不是Hold`() {
        val bad = EvoCandidate(domain = "fixture", target = "skill:x", body = "永久删除全部记忆")
        val v = admit(CanaryBand.OFF, EvolutionDepth.SHALLOW, bad)
        assertTrue("红线优先于档位：$v", v is LowRiskVerdict.Refuse)
    }
}
