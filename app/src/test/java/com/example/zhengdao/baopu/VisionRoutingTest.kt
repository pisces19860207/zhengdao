// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百二十四批 · 多模态输入 v1》＋ 2026-10-21 用户口径第 1 条：
// 「不碰 ModelCapabilityTable，也不改 ModelRoutingGateTest G2。vision 唯一真相是 Provider.vision；
// 路由用 ModelRouter.need.vision，不另存一份。」
//
// 这个文件证三件事：
// 1. 「算不算已验证能看图」只读 `Provider.vision` 那一格——能力表里这一格今天不存在，
//    按口径也**不该**存在（第九十三批门禁 G2 已经把这条钉死了，本批不去改它，
//    只在 `VisionConsentWiringTest` 里钉「本批没有绕开它」）；
// 2. 「谁有资格当候选」沿用第九十三批从第六十四批抽出来的 [ProviderPrefs.routeCandidates]，
//    本批一处都没另起——同仓库长出第二套「下一个该切谁」就是漂移的起点；
// 3. 任何一条读数都不带 Key（哨兵对拍，与 ModelRouterTest 同法）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「找已验证看图的通道」这条判据（[VisionRouting]）——纯 JVM，零 Android、零 IO。
 */
class VisionRoutingTest {

    private fun channel(id: String, model: String = "m-$id", vision: Boolean): Provider = Provider(
        id = id,
        label = "标签-$id",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://routing124-fixture.invalid/v1",
        model = model,
        // 哨兵：任何一条读数里出现它，就是「把密钥打进日志」那一条打回事由
        apiKey = SENTINEL,
        vision = vision,
    )

    // ── 判据本体 ────────────────────────────────────────────────────────────

    @Test
    fun `已验证只看Provider的vision一格`() {
        val a = channel("a", vision = true)
        val b = channel("b", vision = false)
        val c = channel("c", vision = true)
        val d = channel("d", vision = false)
        // false 一律排掉：**不区分**「有依据地不支持」与「没查过」
        // （口径第 4 条：本批不给 Provider 加 visionState，一份真相两种读法就是漂移）
        assertEquals(listOf("a", "c"), VisionRouting.verifiedAmong(listOf(a, b, c, d)).map { it.id })
        // 名单里一个 true 都没有 ⇒ 空表 ⇒ 卡上不会出现「切到已验证通道」那一项（见 VisionConsentTest）
        assertTrue(VisionRouting.verifiedAmong(listOf(b, d)).isEmpty())
        assertTrue(VisionRouting.verifiedAmong(emptyList()).isEmpty())
        // 名单序原样保留：卡上「切到哪条」的建议序＝候选序，本批故意不排序（理由见 VisionRouting KDoc）
        assertEquals(listOf("c", "a"), VisionRouting.verifiedAmong(listOf(c, a)).map { it.id })
    }

    @Test
    fun `与ModelRouter同一把尺子_need只带vision`() {
        val need = ModelRouter.Need(vision = true)
        val all = listOf(channel("x", vision = true), channel("y", vision = false))
        val byRouter = all.filter { ModelRouter.satisfies(ModelRouter.candidateOf(it), need) }.map { it.id }
        assertEquals(
            "看图能力过滤必须走 ModelRouter.satisfies（换一套判据＝两份真相）",
            byRouter,
            VisionRouting.verifiedAmong(all).map { it.id },
        )
        // 缺项话术也是 ModelRouter 那一份：写清到底是哪一格不满足
        val missing = ModelRouter.missingOf(ModelRouter.candidateOf(all[1]), need)
        assertEquals(1, missing.size)
        assertTrue(missing[0].contains("Provider.vision=false"))
    }

    @Test
    fun `能力表全UNKNOWN不影响看图判定`() {
        // 夹具通道在能力表里查不到行 ⇒ 整行 UNKNOWN；但 vision=true 仍然算已验证
        val p = channel("chan-not-in-table", vision = true)
        val candidate = ModelRouter.candidateOf(p)
        assertFalse("夹具通道没有成本依据（表里全 UNKNOWN）", candidate.row.costKnown)
        assertEquals(listOf("chan-not-in-table"), VisionRouting.verifiedAmong(listOf(p)).map { it.id })
        // 反面对照：vision=false 时表里有没有行都不满足 ⇒ 表不是「能不能看图」的真相
        val q = ModelRouter.candidateOf(channel("chan-not-in-table-2", vision = false))
        assertTrue(ModelRouter.missingOf(q, ModelRouter.Need(vision = true)).isNotEmpty())
    }

    // ── 候选规矩（复用第九十三批那一套） ────────────────────────────────────

    @Test
    fun `候选规矩沿用routeCandidates一处没改`() {
        val keyFor: (String) -> String? = { SENTINEL }
        val current = Providers.OPENAI_COMPAT.id
        val raw = ProviderPrefs.routeCandidates(current, keyFor)
        val ok = VisionRouting.verifiedCandidates(current, keyFor)
        // 「先按既有规矩列名单，再按 vision 过滤」——两步之间不许有第三条规矩
        assertEquals(raw.filter { it.vision }.map { it.id }, ok.map { it.id })
        assertFalse("排除当前通道（切回自己不算切换）", ok.any { it.id == current })
        assertFalse("排除内置免费通道（2026-10-17 真机整条 403）", ok.any { it.builtinFree })
        assertTrue("今天三条预设都声明 vision=true ⇒ 至少给出一条可切通道", ok.isNotEmpty())
    }

    @Test
    fun `没填Key的预设与当前通道都不进候选`() {
        val none: (String) -> String? = { null }
        assertTrue(
            "一个 Key 都没填 ⇒ 没有可切通道（不抛异常，卡上就只剩「仍发送／取消」）",
            VisionRouting.verifiedCandidates(Providers.OPENAI_COMPAT.id, none).isEmpty(),
        )
        val only: (String) -> String? = { if (it == Providers.ANTHROPIC.id) SENTINEL else null }
        assertEquals(
            listOf(Providers.ANTHROPIC.id),
            VisionRouting.verifiedCandidates(Providers.OPENAI_COMPAT.id, only).map { it.id },
        )
    }

    @Test
    fun `自定义通道三件齐进名单但未验证看图被排掉`() {
        val keyFor: (String) -> String? = { SENTINEL }
        val current = Providers.DEEPSEEK.id
        val base = "https://custom124-fixture.invalid/v1"
        val raw = ProviderPrefs.routeCandidates(current, keyFor, base, "some-model")
        assertTrue(
            "三件齐 ⇒ 自定义通道进候选名单（第九十三批的规矩，本批不改）",
            raw.any { it.id == ProviderPrefs.CHANNEL_CUSTOM },
        )
        val ok = VisionRouting.verifiedCandidates(current, keyFor, base, "some-model")
        assertFalse(
            "自定义通道的 Provider.vision 是默认 false（没依据 ⇒ 不许猜）⇒ 不算已验证",
            ok.any { it.id == ProviderPrefs.CHANNEL_CUSTOM },
        )
    }

    // ── 读数（进 run_log 的那一句） ─────────────────────────────────────────

    @Test
    fun `describe只有条数与id和模型`() {
        assertEquals("已验证看图的候选通道：0 条", VisionRouting.describe(emptyList()))
        val s = VisionRouting.describe(
            listOf(channel("v1", "model-one", vision = true), channel("v2", "model-two", vision = true)),
        )
        assertTrue(s.contains("2 条"))
        assertTrue(s.contains("v1/model-one"))
        assertTrue(s.contains("v2/model-two"))
        assertFalse("读数里不许出现 Key", s.contains(SENTINEL))
        assertFalse("读数里不许出现 label（越少越好，label 可能带用户起的名字）", s.contains("标签-"))
    }

    @Test
    fun `负例-哨兵真进了候选可任何读数都不许带`() {
        val p = channel("v", vision = true)
        // 对照：先证明哨兵真的进了候选（否则上面那条断言是空的）
        assertTrue("Provider 是 data class，toString 会带出 apiKey ⇒ 门禁禁的就是这种写法", p.toString().contains(SENTINEL))
        assertEquals(SENTINEL, p.apiKey)
        assertFalse(VisionRouting.describe(listOf(p)).contains(SENTINEL))
        val keyFor: (String) -> String? = { SENTINEL }
        val ok = VisionRouting.verifiedCandidates(Providers.OPENAI_COMPAT.id, keyFor)
        assertFalse(
            "整条链路对外只有一句话：describe",
            VisionRouting.describe(ok).contains(SENTINEL),
        )
    }

    private companion object {
        const val SENTINEL = "sk-SENTINEL-124-不许出现在任何读数"
    }
}
