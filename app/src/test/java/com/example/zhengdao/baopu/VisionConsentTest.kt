// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十四批 · 多模态输入 v1》§5 测试口径（vision=false、未知、切通道、
// 取消、仍发送失败）＋ 2026-10-21 用户口径（三选一那张卡、默认不发、失败给可读原因）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.AskUserTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同意闸的**判据与文案**（[VisionConsent]）——纯 JVM，不碰 Android、不碰盘。
 *
 * 派工单要的五条覆盖面，逐条落在这里：
 * - `vision=false` ⇒ [VisionConsent.needed] 为真（要问）；
 * - 「未知」⇒ 同一个 `false`，同一条判据（第一百二十四批：不三态、不加 `visionState`）；
 * - 切通道 ⇒ 有候选才有那一项，且 `decide` 认得目标通道；
 * - 取消 / 超时 ⇒ 一律落 [VisionConsent.Decision.CANCEL]；
 * - 仍发送失败 ⇒ [VisionConsent.failureHint] 的三个条件与文案。
 *
 * 另加两条**打回**用例（哨兵 Key）：文案任何一处打印出 `apiKey` 就是派工单红线「key 不入日志/仓库」。
 */
class VisionConsentTest {

    private fun provider(id: String, model: String, vision: Boolean): Provider = Provider(
        id = id,
        label = "标签-$id",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://consent-fixture.invalid/v1",
        model = model,
        // 哨兵：任何一条文案里出现它 ⇒ 「打印密钥」那条打回事由
        apiKey = SENTINEL,
        vision = vision,
    )

    private val unverified = provider("chan-no-vision", "m-no-vision", vision = false)
    private val verified = provider("chan-vision", "m-vision", vision = true)

    // ── 触发判据 ────────────────────────────────────────────────────────────

    @Test
    fun `只有「本轮有图且通道未验证」才问`() {
        // vision=false ＋ 有图 ⇒ 问
        assertTrue(VisionConsent.needed(imageCount = 2, visionSupported = false))
        // 「未知」今天就是同一个 false ⇒ 同一条判据，不另开三态
        assertTrue(VisionConsent.needed(imageCount = 1, visionSupported = unverified.vision))
        // 有验证过的通道 ⇒ 不问（第二十四批那条路径一个字节不变）
        assertFalse(VisionConsent.needed(imageCount = 3, visionSupported = verified.vision))
        // 没图 ⇒ 永不问（纯文字轮次不许被这张卡拦住）
        assertFalse(VisionConsent.needed(imageCount = 0, visionSupported = false))
    }

    // ── 卡面：三个分支 ──────────────────────────────────────────────────────

    @Test
    fun `有候选时三个选项且切换排第一`() {
        val req = VisionConsent.request(listOf(verified), imageCount = 2)
        assertEquals(3, req.options.size)
        assertEquals(VisionConsent.switchTargetId("chan-vision"), req.options[0].id)
        assertTrue("推荐项必须是「切到已验证通道」（把风险留给用户之前先给一条安全路）", req.options[0].recommended)
        assertEquals(VisionConsent.OPT_SEND, req.options[1].id)
        assertEquals(VisionConsent.OPT_CANCEL, req.options[2].id)
        assertTrue("问题里必须带用户原话的那句", req.question.contains("当前通道未验证看图，可能失败/收费"))
        assertTrue("选项必须说清几张图", req.options.any { it.detail.contains("2 张") })
        assertTrue("切换项要报出去哪条通道（label＋id/model）", req.options[0].detail.contains("标签-chan-vision"))
        assertTrue(req.options[0].detail.contains("chan-vision/m-vision"))
    }

    @Test
    fun `负例-没有候选就不许出现切换那一项`() {
        val req = VisionConsent.request(emptyList(), imageCount = 1)
        assertEquals("没候选 ⇒ 只有「仍发送／取消」两项", 2, req.options.size)
        assertFalse(
            "硬塞一个切不过去的通道就是骗用户点",
            req.options.any { it.id.startsWith(VisionConsent.OPT_SWITCH_PREFIX) },
        )
        assertTrue("取消项要说明为什么没有第三条路", req.options.last().detail.contains("没有已验证看图的候选通道"))
    }

    @Test
    fun `选项数始终落在 ask-user 卡的 2 到 4 之间`() {
        listOf(0, 1, 2, 5).forEach { n ->
            val req = VisionConsent.request(List(n) { provider("c$it", "m$it", vision = true) }, imageCount = 1)
            assertTrue(
                "$n 条候选时选项数 ${req.options.size} 必须在 ${AskUserTool.MIN_OPTIONS}–${AskUserTool.MAX_OPTIONS} 之间",
                req.options.size in AskUserTool.MIN_OPTIONS..AskUserTool.MAX_OPTIONS,
            )
        }
        // 候选再多也只给一条建议，并把「还有几条」说在文案里（不静默丢掉其余候选）
        val three = VisionConsent.request(List(3) { provider("c$it", "m$it", vision = true) }, imageCount = 1)
        assertEquals(3, three.options.size)
        assertTrue(three.options[0].detail.contains("还有 2 条候选"))
    }

    // ── 答案 → 决策 ─────────────────────────────────────────────────────────

    @Test
    fun `超时与脏答案一律按取消`() {
        assertEquals(VisionConsent.Decision.SEND, VisionConsent.decide(VisionConsent.OPT_SEND))
        assertEquals(VisionConsent.Decision.CANCEL, VisionConsent.decide(VisionConsent.OPT_CANCEL))
        assertEquals(VisionConsent.Decision.SWITCH, VisionConsent.decide("vision-switch:chan-vision"))
        // 派工单红线「不自动上传」的落点：没人回答 = 不发
        assertEquals("超时（null）必须按取消", VisionConsent.Decision.CANCEL, VisionConsent.decide(null))
        assertEquals("认不出的 id 同样按取消，不猜", VisionConsent.Decision.CANCEL, VisionConsent.decide("o1"))
        assertEquals(VisionConsent.Decision.CANCEL, VisionConsent.decide(""))
    }

    @Test
    fun `切换答案必须能在候选名单里找回目标`() {
        val req = VisionConsent.request(listOf(verified), imageCount = 1)
        val picked = req.options[0]
        assertEquals(verified, VisionConsent.switchTarget(listOf(verified), picked.id))
        // 候选已变（用户刚改过设置）⇒ 认不出 ⇒ null，调用方按取消处理
        assertNull(VisionConsent.switchTarget(listOf(verified), "vision-switch:chan-gone"))
        assertNull(VisionConsent.switchTarget(listOf(verified), VisionConsent.OPT_SEND))
        assertNull(VisionConsent.switchTarget(listOf(verified), null))
        assertNull("空名单里谁也找不出目标", VisionConsent.switchTarget(emptyList(), picked.id))
    }

    // ── 失败文案 ────────────────────────────────────────────────────────────

    @Test
    fun `失败那句只在「授权＋仍未验证＋真有图」时出现`() {
        val hint = VisionConsent.failureHint(unverified, visionAuthorized = true, imagesSent = 2)
        assertTrue(hint.contains("仍要发送"))
        assertTrue(hint.contains("2 张"))
        assertTrue(hint.contains("chan-no-vision/m-no-vision"))
        assertTrue("要给出下一步能做的事", hint.contains("换一条已验证看图的通道"))
        // 三个条件少任何一个 ⇒ 空串（既有失败文案一个字节都不许多）
        assertEquals("", VisionConsent.failureHint(unverified, visionAuthorized = false, imagesSent = 2))
        assertEquals("", VisionConsent.failureHint(verified, visionAuthorized = true, imagesSent = 2))
        assertEquals("", VisionConsent.failureHint(unverified, visionAuthorized = true, imagesSent = 0))
    }

    // ── 打回：Key 不许进任何文案 ────────────────────────────────────────────

    @Test
    fun `负例-卡面与文案任何一处都不许出现密钥`() {
        listOf(0, 1, 3).forEach { n ->
            val req = VisionConsent.request(List(n) { provider("k$it", "m$it", vision = true) }, imageCount = 2)
            val all = req.question + req.options.joinToString("|") { "${it.id}${it.label}${it.detail}" }
            assertFalse("文案里出现了哨兵密钥：$SENTINEL", all.contains(SENTINEL))
        }
        val note = VisionConsent.authorizedNote(unverified, 2) +
            VisionConsent.failureHint(unverified, visionAuthorized = true, imagesSent = 2) +
            VisionRouting.describe(listOf(unverified, verified))
        assertFalse("留痕/失败句/读数里出现了哨兵密钥", note.contains(SENTINEL))
    }

    private companion object {
        const val SENTINEL = "sk-SENTINEL-DO-NOT-LEAK-124"
    }
}
