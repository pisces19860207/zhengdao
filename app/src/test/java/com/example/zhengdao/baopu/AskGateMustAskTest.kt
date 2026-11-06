// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零七批 · 弹卡收口的**源码级门禁**：把「弹卡入口 × 是否走 consultAsk」这张表的
// 结论钉进测试——可省的问卡走普通闸（压得掉、占预算），法条必问卡走让步格（压不掉、
// 不占预算），补问与自有面不过闸（330/429/铁律口径）。任何一行回潮，这里先红。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskGateMustAskTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()

    private val engine: String
        get() = src("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private val wiring: String
        get() = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    private fun slice(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("找不到 $from", a >= 0)
        val b = text.indexOf(to, a)
        assertTrue("找不到结束标记 $to", b > a)
        return text.substring(a, b)
    }

    // ── 收口形状：闸只有一个口 ────────────────────────────────────────────

    @Test
    fun `consultAsk生产只有两处_askUser与askGroup_单收口`() {
        // 左相闸的真身只在 askUser / askGroup 两个执行体里——所有该过闸的入口
        // 都经由这两个函数，不存在第三个直连调解器的弹卡口。
        assertEquals(2, Regex("MediatorWiring\\.consultAsk\\(").findAll(engine).count())
        assertTrue("askUser 必须把必问档递给闸", engine.contains("MediatorWiring.consultAsk(mustAsk)"))
        assertTrue("askGroup 的④路让步格原样", engine.contains("MediatorWiring.consultAsk(viaUserChoice)"))
    }

    @Test
    fun `askUser带必问让步格_签名与转发`() {
        assertTrue("签名：askUser(req, mustAsk)", engine.contains("fun askUser(req: AskUserRequest, mustAsk: Boolean = false)"))
    }

    // ── 法条必问：走让步格（调解器压不掉、不占预算） ─────────────────────

    @Test
    fun `必问类十条_全部带mustAsk=true`() {
        val mustAskSites = listOf(
            "askUser(VisionConsent.request(verified, imageCount), mustAsk = true)",
            "askUser(ScreenshotConsent.request(hostOf(info?.url), info?.title, sensitive), mustAsk = true)",
            "askUser(ScreenshotSwitchConsent.request(hostOf(info?.url), info?.title), mustAsk = true)",
            "askUser(PlanGuardCards.changeRequest(change), mustAsk = true)",
            "askUser(PlanPledgeAmendCards.amendRequest(first.change), mustAsk = true)",
            "askUser(AmendRiskCards.reauthorizeRequest(recheck), mustAsk = true)",
            "askUser(PlanPledgeCards.pledgeRequest(candidate), mustAsk = true)",
            "askUser(PlanContractCards.approvalRequest(declared), mustAsk = true)",
            "askUser(dev.request, mustAsk = true)",
        )
        for (site in mustAskSites) {
            assertTrue("必问卡必须走让步格：$site", engine.contains(site))
        }
        // changeRequest 在超时重问处弹第二次（161 的重问一次），同一张必问卡两处；
        assertEquals(
            "mustAsk = true 恰好 10 处（9 张卡＋超时重问 1 处）",
            10,
            Regex(", mustAsk = true\\)").findAll(engine).count(),
        )
    }

    // ── 可省的问卡：走普通闸（压得掉、占预算） ───────────────────────────

    @Test
    fun `可省类_不带mustAsk_照旧受压`() {
        // 求助卡③：压了＝如实收场（本轮结束、没做成），不危险；
        // 计划偏离卡：催进度的卡，不是人批闸。
        assertFalse(
            "求助卡③不许走让步格",
            slice(engine, "private fun helpStep(", "private fun dispatchHelpOption(").contains("mustAsk = true"),
        )
        assertFalse(
            "计划偏离卡不许走让步格",
            engine.contains("askUser(PlanGuardCards.deviationRequest(items), mustAsk = true)"),
        )
        // 模型自己的 ask_user 工具：模型自己的问卡，正是闸要管的那个量
        assertTrue(
            "ask_user 工具照旧挂普通闸",
            wiring.contains("AskUserTool.sink = { req -> engine.askUser(req) }"),
        )
    }

    // ── 不过闸的：各有各的口径 ────────────────────────────────────────────

    @Test
    fun `askUserRaw只许补问两处_不再过闸是330与429的口径`() {
        // askUserRaw = 过闸放行后的原始弹卡体。生产只许两处：免打扰补问重递原卡（330：
        // 卡第一次弹时已过闸），补问前的处置卡（429①：检查点的人机对账，她正在场）。
        val calls = Regex("askUserRaw\\((?!req)").findAll(engine).map { it.value }.toList()
        assertEquals("askUserRaw 生产调用只许 2 处（补问＋处置卡），实际 ${calls.size}", 2, calls.size)
    }

    @Test
    fun `确认卡自有面_不经askUser_铁律压不到`() {
        // 确认卡（御批）走 ConfirmFlow 自己的等待/恢复，不问调解器——必问铁律的物理隔离。
        val confirm = src("src/main/java/com/example/zhengdao/baopu/agent/ConfirmFlow.kt")
        assertFalse("确认卡不许问调解器", confirm.contains("consultAsk"))
        assertFalse("确认卡不许借道 askUser（那是可省问卡的口）", confirm.contains("askUser("))
    }
}
