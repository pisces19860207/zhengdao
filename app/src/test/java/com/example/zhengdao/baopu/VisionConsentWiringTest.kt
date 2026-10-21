// 独立开发声明：本文件为本项目从零编写，未参考任何第三方既有代码。
//
// 依据：派工单《第一百二十四批 · 多模态输入 v1》＋ 2026-10-21 用户口径第 2、3、5 条
// （入口/压缩/编码复用 22、24 批不重写；未验证通道发图前弹现有确认卡；
// 不自动上传、key 不入日志/仓库；测试覆盖 vision=false、未知、切通道、取消、仍发送失败）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百二十四批 · **接线门禁**（照第二十四批 C 段 `VisionWiringTest` 的法子）。
 *
 * ## 为什么判据单测不够
 * [VisionConsentTest] 与 [VisionRoutingTest] 测的是两张纯函数——它们全绿，
 * 也不代表引擎真的在**读盘之前**问了用户。这一批最危险的失败形状和 24 批一样是**静默**：
 * 卡写好了、判据写好了，可调用点在 `VisionFiles.load` 之后（图已经读进内存），
 * 或者授权旗根本没传进组装层（用户点了「仍要发送」结果还是一张都不发），
 * 或者 `visionAuthorized` 的默认值被后来人「顺手」改成 `true`（= 拆掉整道闸，
 * 变成自动上传）。这些都在**两处调用之间**，只有源码级钉子能拦住。
 *
 * ## 钉什么
 * - G1 闸的**位置**：在 `runLoop` 里、在 `VisionFiles.load` 与 `persistUserMessage` 之前；
 * - G2 三条分支各就各位（切＝复用第六十四批机器；仍发送＝只举旗；取消/超时＝收轮）；
 * - G3 授权旗一路传到底（引擎 → `ContextAssembler.visionAuthorized` → `VisionRules.userAuthorized`）；
 * - G4 默认值即闸门（三处默认全 `false`，改一处就红）；
 * - G5 候选来源挂在装配层（引擎不持 Key）；
 * - G6 单源没被绕过（本批两个新文件都不查能力表、不加 `visionState`，G2 那条既有门禁原样在）；
 * - G7 状态机与本批互不打扰（`ChannelSwitchFsm` 一个字节没动）；
 * - G8 卡片复用（不新建 UI 家族成员）。
 * - B1 行为半段：授权旗确实改变 `VisionRules.select` 的结果，而三道容量闸一道不少。
 */
class VisionConsentWiringTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()

    /** 去注释后的源码（与 `ModelRoutingGateTest` 同法）：KDoc 里提到 `[ModelCapabilityTable]` 不算引用。 */
    private fun codeOnly(text: String): String =
        text.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
    private val engine: String get() = src(enginePath)
    private val runtime: String get() = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
    private val rules: String get() = src("src/main/java/com/example/zhengdao/baopu/VisionRules.kt")
    private val asm: String get() = src("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt")
    private val consent: String get() = src("src/main/java/com/example/zhengdao/baopu/VisionConsent.kt")
    private val routing: String get() = src("src/main/java/com/example/zhengdao/baopu/VisionRouting.kt")
    private val fsm: String get() = src("src/main/java/com/example/zhengdao/baopu/agent/ChannelSwitchFsm.kt")
    private val routingGateTest: String get() = src("src/test/java/com/example/zhengdao/baopu/ModelRoutingGateTest.kt")

    /** 同意闸那一个函数体（从声明到下一个私有函数之前）。 */
    private val consentFn: String
        get() {
            val start = engine.indexOf("private fun askVisionConsent(imageCount: Int): Boolean {")
            assertTrue("找不到同意闸函数（接线断了）", start >= 0)
            val end = engine.indexOf("private fun settle(", start)
            assertTrue("同意闸函数后面找不到 settle（钉子要挪）", end > start)
            return engine.substring(start, end)
        }

    // ── G1 闸的位置（红线「不自动上传」的落点） ────────────────────────────

    @Test
    fun `G1_同意闸排在读盘与落库之前`() {
        val gate = engine.indexOf("if (!askVisionConsent(pendingImageRelPaths.size)) return")
        val load = engine.indexOf("VisionFiles.load(spillRoot(), turnImagePaths")
        val persist = engine.indexOf("persistUserMessage(sessionId, userText, turnImagePaths, queueRowId)")
        val runLoop = engine.indexOf("private fun runLoop(")
        assertTrue("runLoop 入口必须有同意闸调用（一处都不许多：两处就是弹两遍卡）", gate in 1 until engine.length)
        assertEquals(
            "同意闸调用点必须唯一",
            1,
            Regex("if \\(!askVisionConsent\\(").findAll(engine).count(),
        )
        assertTrue("同意闸必须在 runLoop 里（askUser 阻塞，放 send 那一层就是主线程 ANR）", runLoop in 1 until gate)
        assertTrue("同意闸必须在读图之前：取消＝一个字节都不读盘，更谈不上编码上传", gate < load)
        assertTrue("同意闸必须在落库之前：取消＝这条消息不落库，库里不留「有问题没答案」的半轮", gate < persist)
        // 触发条件逐字钉住：本轮有图 且 通道未验证 且 不是续跑（续跑的图片上一轮已经问过）
        assertTrue(
            "触发条件少了任一条都会变成「纯文字轮也被卡住」或「续跑重复弹卡」",
            engine.contains("if (!resumed && pendingImageRelPaths.isNotEmpty() && !provider.vision) {"),
        )
    }

    // ── G2 三条分支 ────────────────────────────────────────────────────────

    @Test
    fun `G2_三条分支各就各位`() {
        assertTrue(consentFn.contains("askUser(VisionConsent.request(verified, imageCount))"))
        assertTrue(consentFn.contains("VisionConsent.decide(picked?.id)"))
        // 仍要发送：只举旗，别的一个都不许多改（旗是唯一传给组装层的东西）
        assertEquals("举旗只能有一处", 1, Regex("turnVisionAuthorized = true").findAll(consentFn).count())
        // 切通道：复用第六十四批机器（请求 → 安全点 → 效应），本轮入口就是安全点
        assertTrue(consentFn.contains("requestChannelSwitch(target)"))
        assertTrue(consentFn.contains("drainChannelSwitch(SafePoint.StepBoundary)"))
        assertTrue("切完必须复查 provider.vision（没切成就不发）", consentFn.contains("if (provider.vision) {"))
        // 取消 / 超时 / 认不出目标 / 切没成 ⇒ 一律收轮
        assertEquals("收轮出口四处分支各一次", 3, Regex("RunState\\.ABORTED").findAll(consentFn).count())
        // 闸自己不许读盘、不许组装（真读了再问就晚了）
        assertFalse(consentFn.contains("VisionFiles"))
        assertFalse(consentFn.contains("ContextAssembler"))
    }

    // ── G3＋G4 授权旗传到组装层，且默认关 ──────────────────────────────────

    @Test
    fun `G3_授权旗一路传到底`() {
        assertTrue(engine.contains("visionAuthorized = turnVisionAuthorized,"))
        assertTrue("V5 那句原样在：能力只读 Provider.vision", engine.contains("visionSupported = provider.vision,"))
        assertTrue("组装层拿的是旗子，不是自己算的", asm.contains("visionAuthorized: Boolean = false,"))
        assertTrue(asm.contains("VisionRules.select(images, visionSupported, visionAuthorized)"))
        assertTrue(rules.contains("userAuthorized: Boolean = false,"))
        assertTrue(
            "省略早退必须同时看两道（能力 且 授权）——只看能力就是静默丢图",
            rules.contains("if (!visionSupported && !userAuthorized) {"),
        )
    }

    @Test
    fun `G4_默认值就是闸门_三处默认全false`() {
        // 这三条字面量是「默认行为不变」的钉子：谁把它们改成 true，本用例立刻红
        assertEquals(
            "VisionRules 的授权参数默认 false（= 本参数存在之前的行为）",
            1, Regex("userAuthorized: Boolean = false").findAll(rules).count(),
        )
        assertEquals(
            "ContextAssembler 的授权参数默认 false",
            1, Regex("visionAuthorized: Boolean = false").findAll(asm).count(),
        )
        assertTrue(
            "引擎的候选接缝默认空表（装配层没挂 ⇒ 卡上不会出现「切到已验证通道」，绝不凭空切通道）",
            engine.contains("var visionCandidates: (String) -> List<Provider> = { emptyList() }"),
        )
        // 举旗只能发生在同意闸那一个函数里（别处不许有第二条「用户同意了」的路）
        assertEquals(
            "全引擎只有一处举旗，且就在同意闸里",
            Regex("turnVisionAuthorized = true").findAll(engine).count(),
            Regex("turnVisionAuthorized = true").findAll(consentFn).count(),
        )
        assertEquals("旗子每轮入口复位一次 ＋ 声明一次", 2, Regex("turnVisionAuthorized = false").findAll(engine).count())
        assertEquals("张数计数每轮入口归零一次 ＋ 声明一次", 2, Regex("stepImagesSent = 0").findAll(engine).count())
        assertTrue(
            "复位必须在闸之前（上一轮的通行证不许漏到这一轮）",
            engine.indexOf("turnVisionAuthorized = false") < engine.indexOf("if (!askVisionConsent("),
        )
    }

    // ── G5 候选来源挂在装配层 ──────────────────────────────────────────────

    @Test
    fun `G5_候选由装配层给_引擎不持Key`() {
        assertTrue(runtime.contains("engine.visionCandidates = { currentId ->"))
        assertTrue(runtime.contains("VisionRouting.verifiedCandidates("))
        assertTrue(runtime.contains("keyFor = { id -> ProviderPrefs.storedKey(appCtx, id) }"))
        val start = runtime.indexOf("engine.visionCandidates = {")
        val region = runtime.substring(start, runtime.indexOf("\n        }", start))
        assertFalse("注入那段不许出现 apiKey 字样（Key 只现取现用，不进引擎、不进日志）", region.contains("apiKey"))
        assertFalse("注入那段不许写日志", region.contains("RunLog"))
    }

    // ── G6 单源：vision 只在 Provider，本批没绕开能力表门禁 ────────────────

    @Test
    fun `G6_本批两个新文件都不查能力表也不加三态`() {
        val consentCode = codeOnly(consent)
        val routingCode = codeOnly(routing)
        listOf("ModelCapabilityTable.", "visionState", "SharedPreferences", "com.example.zhengdao.baopu.ui").forEach {
            assertFalse("VisionConsent 代码里不许出现 $it", consentCode.contains(it))
            assertFalse("VisionRouting 代码里不许出现 $it", routingCode.contains(it))
        }
        assertFalse("Key 不许进任何文案（本文件只取 id/model/label）", consentCode.contains("apiKey"))
        // 候选规矩没另起一套：名单仍由 ProviderPrefs.routeCandidates 出，过滤仍由 ModelRouter 判
        assertTrue(routingCode.contains("ProviderPrefs.routeCandidates("))
        assertTrue(routingCode.contains("ModelRouter.satisfies("))
        assertTrue(routingCode.contains("ModelRouter.Need(vision = true)"))
        assertFalse("不许自己重列预设（Providers.all() 只许在 routeCandidates 里出现一次）", routingCode.contains("Providers.all()"))
        // 第九十三批那条 G2 用例仍在原地，本批没改判它
        assertTrue(
            "ModelRoutingGateTest 的 G2（能力表里不许有 vision 列）必须原样存在",
            routingGateTest.contains("能力表里不许有vision与窗口与输出上限这三列"),
        )
    }

    // ── G7＋G8 别的批次的地盘不踩 ──────────────────────────────────────────

    @Test
    fun `G7_第六十四批状态机一个字节没改`() {
        assertFalse(
            "「切到已验证通道」必须复用既有状态机，本批不许改判据（要改就单开一批）",
            fsm.contains("第一百二十四批"),
        )
        assertTrue(fsm.contains("visionLost"))
    }

    @Test
    fun `G8_卡片复用ask_user家族不新加档位`() {
        val consentCode = codeOnly(consent)
        assertTrue(consentCode.contains("AskUserRequest("))
        assertTrue(consentCode.contains("AskUserOption("))
        assertFalse(
            "不许为这张卡新加 AskKind 档位（加档位就要动 ui 层渲染 = 踩 104/105/106/108 的地盘）",
            consentCode.contains("AskKind"),
        )
    }

    // ── B1 行为半段：旗子确实管用，三道容量闸一道不少 ──────────────────────

    @Test
    fun `B1_举旗才发图_但大小张数合法性三道闸照旧`() {
        val one = listOf(VisionRules.ImagePayload("data:image/png;base64,AAAA", 100))
        // 默认（没举旗）＝旧行为：一张都不发，并且明说省了几张（V3 不许静默丢）
        val denied = VisionRules.select(one, visionSupported = false)
        assertEquals(emptyList<String>(), denied.images)
        assertEquals(1, denied.omittedNoVision)
        assertTrue(denied.note.contains("已省略 1 张图片"))
        // 举旗＝用户自己担这个风险 ⇒ 图进请求体，note 换说法
        val allowed = VisionRules.select(one, visionSupported = false, userAuthorized = true)
        assertEquals(one.map { it.dataUrl }, allowed.images)
        assertTrue(allowed.note.contains("未验证看图"))
        assertTrue(allowed.note.contains("不会悄悄丢图"))
        // 同意冒险 ≠ 同意打穿窗口：超单张上限那张照样被拦
        val big = VisionRules.select(
            listOf(VisionRules.ImagePayload("data:image/png;base64,BBBB", VisionRules.MAX_IMAGE_BYTES + 1)),
            visionSupported = false,
            userAuthorized = true,
        )
        assertTrue(big.images.isEmpty())
        assertEquals(1, big.omittedTooLarge)
        // 张数闸也照旧（5 张 ⇒ 发 4 张、省 1 张）
        val many = VisionRules.select(
            List(VisionRules.MAX_IMAGES + 1) { VisionRules.ImagePayload("data:image/png;base64,CCCC$it", 100) },
            visionSupported = false,
            userAuthorized = true,
        )
        assertEquals(VisionRules.MAX_IMAGES, many.images.size)
        assertEquals(1, many.omittedOverLimit)
        // 能力够的通道：旗子不参与判断，note 一个字都不许多
        assertEquals("", VisionRules.select(one, visionSupported = true, userAuthorized = true).note)
    }
}
