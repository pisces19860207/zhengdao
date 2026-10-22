// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十四批（刀 3 · B 段）§3 判据 J7（四档映射、默认 high）与
// J12（设置页只读展示的格式化函数）；§1.1 模型侧实测（八档枚举、非法值 422）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ModelParams 纯判据内核单测（刀 3 B 段 · 2026-10-17）。
 *
 * 只测**纯函数**（归一/UI 档位/覆盖/摘要格式化）——SP 薄壳要 Context，
 * 本仓无 Robolectric 实例化不了（ProviderPrefs 同款教训，见 [ModelParams] KDoc）。
 */
class ModelParamsTest {

    @Test
    fun `effort归一_服务端八档原样放行_脏值归null`() {
        // ① 实测八档（none/minimal/low/medium/high/xhigh/ultra/max；非法值 422）
        listOf("none", "minimal", "low", "medium", "high", "xhigh", "ultra", "max").forEach {
            assertEquals(it, ModelParams.normalizeEffort(it))
        }
        assertEquals("high", ModelParams.normalizeEffort("HIGH")) // 大小写不敏感
        assertEquals("low", ModelParams.normalizeEffort("  low  ")) // 首尾空白容忍
        assertNull(ModelParams.normalizeEffort("banana"))
        assertNull(ModelParams.normalizeEffort(null))
        assertNull(ModelParams.normalizeEffort(""))
    }

    @Test
    fun `uiEffort_用户选择优先_没选看通道声明_都没有默认high`() {
        assertEquals("high", ModelParams.uiEffort(null, null)) // J7「默认 high」判在这
        assertEquals("low", ModelParams.uiEffort(null, "low")) // 通道声明兜底
        assertEquals("max", ModelParams.uiEffort("max", "low")) // 用户选过的赢
        assertEquals("high", ModelParams.uiEffort("banana", null)) // 脏值不当选择，回落默认
    }

    @Test
    fun `effortOverlay_只盖声明了effort的通道_没选过不盖_脏值不盖`() {
        val ds = Providers.DEEPSEEK
        assertEquals("low", ModelParams.effortOverlay(ds, "low").reasoningEffort)
        assertEquals("none", ModelParams.effortOverlay(ds, "none").reasoningEffort)
        // 没选过 ⇒ 通道原样（用预设自己的 high）
        assertEquals("high", ModelParams.effortOverlay(ds, null).reasoningEffort)
        // 通道没声明 effort ⇒ 不盖也不发（不替不认这个字段的通道瞎发）
        assertNull(ModelParams.effortOverlay(Providers.OPENAI_COMPAT, "low").reasoningEffort)
        assertNull(ModelParams.effortOverlay(Providers.OPENROUTER_FREE, "low").reasoningEffort)
        // 脏值不盖（服务端 422）
        assertEquals("high", ModelParams.effortOverlay(ds, "banana").reasoningEffort)
    }

    @Test
    fun `summaryOf_千分位固定ROOT口径_未声明给null_零值也给null`() {
        val ds = ModelParams.summaryOf(Providers.DEEPSEEK)
        assertEquals("1,048,576", ds.window) // J12：不许显示 128000（旧回退值）
        assertEquals("393,216", ds.maxOutput)
        val none = ModelParams.summaryOf(Providers.OPENAI_COMPAT)
        assertNull(none.window) // 没声明 ⇒ null（界面显示「未声明」，不是 0 不是 128000）
        assertNull(none.maxOutput)
        // 0 既不是「没声明」也不是可展示值：summaryOf 只认 > 0
        val zero = ModelParams.summaryOf(
            Providers.OPENAI_COMPAT.copy(contextWindow = 0, maxOutput = 0),
        )
        assertNull(zero.window)
        assertNull(zero.maxOutput)
        // 负数同 0（脏数据不当真）
        val neg = ModelParams.summaryOf(Providers.OPENAI_COMPAT.copy(contextWindow = -1))
        assertNull(neg.window)
    }
}
