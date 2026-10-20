// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§1（能力/成本/延迟表，「拿不到就标 UNKNOWN，不猜」）
// 与 §3（表本身的判据）。结构性红线在 `ModelRoutingGateTest`（源码门禁），这里只跑判据行为。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力 / 成本 / 延迟表（[ModelCapabilityTable]）判据用例。
 *
 * 这批最容易被怀疑的是「表是不是又造了一份真相」和「UNKNOWN 是不是被当成 0 混进排序」，
 * 所以这里的重心不是行数，而是三条口径：
 * 1. 表里**不许**有 `Provider` 已有的列（能不能看图 / 窗口 / 输出上限）——门禁 G2 钉源码，这里钉判据；
 * 2. 成本「三件齐」才算有价（缺一件都不排），延迟「至少有 P50」才算有延迟；
 * 3. 查不到行 ⇒ 空行（全 UNKNOWN），不是 null，更不是 0。
 */
class ModelCapabilityTableTest {

    /** 造一行（只给判据用；表里那 10 行的数据由下面的用例单独钉）。 */
    private fun row(
        inputPer1k: Double? = null,
        outputPer1k: Double? = null,
        currency: String? = null,
        p50: Long? = null,
        p90: Long? = null,
        quality: Int? = null,
        tool: ModelCapabilityTable.Support = ModelCapabilityTable.Support.UNKNOWN,
        thinking: ModelCapabilityTable.Support = ModelCapabilityTable.Support.UNKNOWN,
        tiers: List<String> = emptyList(),
    ): ModelCapabilityTable.Row = ModelCapabilityTable.Row(
        channelId = "fixture",
        model = "fixture-model",
        toolSchema = tool,
        thinking = thinking,
        thinkingTiers = tiers,
        inputPer1k = inputPer1k,
        outputPer1k = outputPer1k,
        currency = currency,
        latencyP50Ms = p50,
        latencyP90Ms = p90,
        qualityTier = quality,
        evidence = "测试夹具，不是表内数据",
    )

    // ── 表的结构自证 ──────────────────────────────────────────────────────

    @Test
    fun 同一通道加模型只许一行_两行就是两份真相() {
        assertEquals(
            "重复键：${ModelCapabilityTable.duplicateKeys()}。一个 (channel,model) 两行 ⇒ 改一行漏一行，必漂",
            emptyList<String>(),
            ModelCapabilityTable.duplicateKeys(),
        )
    }

    @Test
    fun 表里不许出现仓库没有的通道() {
        val known = (Providers.all().map { it.id } + ProviderPrefs.CHANNEL_CUSTOM).toSet()
        val ghosts = ModelCapabilityTable.ROWS.filterNot { it.channelId in known }.map { it.channelId }
        assertTrue("悬空通道行（仓库 Providers 里没有它）：$ghosts", ghosts.isEmpty())
    }

    @Test
    fun 四个预设与五个免费模型都有自己那一行() {
        val targets = Providers.all().map { it.id to it.model } +
            Providers.FREE_MODELS.map { Providers.BUILTIN_FREE.id to it }
        targets.forEach { (id, model) ->
            val r = ModelCapabilityTable.rowOf(id, model)
            assertTrue("$id/$model 查不到精确行（退到兜底行就不算这一台模型的依据）", r != null)
            assertEquals("$id/$model 命中的必须是它自己那一行", model, r?.model)
            assertTrue("$id/$model 的 evidence 空着就是没依据", !r!!.evidence.isBlank())
        }
    }

    // ── UNKNOWN 口径（派工单 §1 原话） ────────────────────────────────────

    @Test
    fun 生产表里成本延迟质量一律UNKNOWN_没有台账就不许填() {
        ModelCapabilityTable.ROWS.forEach { r ->
            val where = "${r.channelId}/${r.model}"
            assertNull("$where 输入价：全仓无价格台账 ⇒ 不许抄公开价目表冒充依据", r.inputPer1k)
            assertNull("$where 输出价：同上", r.outputPer1k)
            assertNull("$where 币种：有价没币种等于没价", r.currency)
            assertNull("$where P50：没有按通道聚合的延迟读数 ⇒ 不猜", r.latencyP50Ms)
            assertNull("$where P90：同上", r.latencyP90Ms)
            assertNull("$where 质量档：「模型名看着强」不算依据", r.qualityTier)
            assertFalse("$where 必须排不进成本序", r.costKnown)
            assertFalse("$where 必须排不进延迟序", r.latencyKnown)
            assertFalse("$where 必须排不进质量序", r.qualityKnown)
        }
    }

    @Test
    fun 每一行都写清了依据与那几格为什么空() {
        ModelCapabilityTable.ROWS.forEach { r ->
            val e = r.evidence
            assertTrue("${r.channelId}/${r.model} evidence 太短，说不出依据就是没依据", e.length > 20)
            assertTrue(
                "${r.channelId}/${r.model} 的空列必须在 evidence 里写明 UNKNOWN／不猜／不许填，" +
                    "否则下一个人会当成漏配顺手填上",
                e.contains("UNKNOWN") || e.contains("不猜") || e.contains("不许填"),
            )
        }
    }

    @Test
    fun 查不到的通道给空行_不是null也不是0() {
        val r = ModelCapabilityTable.rowOrUnknown("no-such-channel", "no-such-model")
        assertEquals(ModelCapabilityTable.Support.UNKNOWN, r.toolSchema)
        assertEquals(ModelCapabilityTable.Support.UNKNOWN, r.thinking)
        assertTrue(r.thinkingTiers.isEmpty())
        assertFalse("没依据不能当有价：0 价会让这条陌生通道在「省钱」里排第一", r.costKnown)
        assertFalse(r.latencyKnown)
        assertFalse(r.qualityKnown)
        assertTrue("空行也要说明自己是空行：${r.evidence}", r.evidence.contains("不猜"))
    }

    @Test
    fun 精确行优先_命中不了才退该通道的兜底行() {
        val precise = ModelCapabilityTable.rowOf("builtin-free", "muse-spark-1.3-contributor-free")!!
        assertEquals("精确行命中的是自己", "muse-spark-1.3-contributor-free", precise.model)
        val fallback = ModelCapabilityTable.rowOf("builtin-free", "some-model-not-listed")!!
        assertEquals("退到兜底行", ModelCapabilityTable.ANY_MODEL, fallback.model)
        assertEquals(
            "元数据说 reasoning 只有 121/126 ⇒ 兜底行不能替没登记的模型充「会思考」",
            ModelCapabilityTable.Support.UNKNOWN,
            fallback.thinking,
        )
    }

    @Test
    fun 兜底行不许跨通道串门() {
        // openai-compat 没写 ANY_MODEL 行：查一个没登记的模型必须查不到，而不是借 builtin-free 那行
        assertNull(ModelCapabilityTable.rowOf("openai-compat", "no-such-model"))
        assertNull(ModelCapabilityTable.rowOf("anthropic", "no-such-model"))
    }

    @Test
    fun 自定义通道整行没依据_因为那是用户自填的端点() {
        val r = ModelCapabilityTable.rowOf(ProviderPrefs.CHANNEL_CUSTOM, ModelCapabilityTable.ANY_MODEL)!!
        assertEquals(ModelCapabilityTable.Support.UNKNOWN, r.toolSchema)
        assertEquals(ModelCapabilityTable.Support.UNKNOWN, r.thinking)
        assertFalse(r.costKnown)
        assertTrue("自定义端点的行必须说清「仓库零依据」：${r.evidence}", r.evidence.contains("零依据"))
    }

    // ── 判据本身（表里没数据，但规则要钉住，否则后续批填了数就跑形） ────────

    @Test
    fun 成本三件齐才算有价_缺一件都不排() {
        assertFalse("只有输入价", row(inputPer1k = 1.0).costKnown)
        assertFalse("只有输出价", row(outputPer1k = 2.0).costKnown)
        assertFalse("有价没币种", row(inputPer1k = 1.0, outputPer1k = 2.0).costKnown)
        assertFalse("币种是空白串不算币种", row(inputPer1k = 1.0, outputPer1k = 2.0, currency = "  ").costKnown)
        assertTrue("三件齐才算", row(inputPer1k = 1.0, outputPer1k = 2.0, currency = "CNY").costKnown)
        assertTrue(
            "0 价是合法数据（免费车道真验通了就该填 0），不许被当成缺失",
            row(inputPer1k = 0.0, outputPer1k = 0.0, currency = "CNY").costKnown,
        )
    }

    @Test
    fun 延迟至少有P50才算有延迟_只有P90排不出通常多快() {
        assertFalse(row(p90 = 900L).latencyKnown)
        assertTrue(row(p50 = 500L).latencyKnown)
        assertTrue(row(p50 = 500L, p90 = 900L).latencyKnown)
    }

    @Test
    fun 质量档有数字才算有质量() {
        assertTrue(row(quality = 1).qualityKnown)
        assertTrue("质量档 0 也按「有依据」处理（这个数不由本仓生产，判据只管有没有）", row(quality = 0).qualityKnown)
        assertFalse(row(quality = null).qualityKnown)
    }

    @Test
    fun 人读一行不含Key_未知列写UNKNOWN而不是空字符串() {
        val empty = ModelCapabilityTable.unknownRow("chan", "mod").render()
        assertTrue("没依据要写成 UNKNOWN：$empty", empty.contains("成本=UNKNOWN"))
        assertTrue("延迟：$empty", empty.contains("延迟=UNKNOWN"))
        assertTrue("质量：$empty", empty.contains("质量=UNKNOWN"))
        assertFalse("Key 从来不进表，人读行也不许出现", empty.contains("sk-"))

        val known = row(inputPer1k = 1.0, outputPer1k = 2.0, currency = "CNY", p50 = 500L, quality = 3).render()
        assertTrue("P90 没测到要写 UNKNOWN，不是拿 0 冒充：$known", known.contains("P50=500ms P90=UNKNOWN"))
        assertTrue("有价要带上币种：$known", known.contains("per 1k CNY"))
        assertTrue(known.contains("质量=3"))
    }
}
