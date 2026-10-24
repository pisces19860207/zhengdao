// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §3/§6（S1 与授权闸的接线）＋ 用户 2026-10-24 口径
// 「一次会话授权＋审计，不能后台偷截」。照 `VisionConsentWiringTest` 的法子做**接线门禁**。
package com.example.zhengdao.baopu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百七十七批 · **接线门禁**（S1 像素进模型 ＋ 截图授权闸）。
 *
 * ## 为什么纯函数单测不够
 * [ScreenshotConsentTest]／[SensitivePageTest] 只证明「卡判据对不对」。这一批最危险的
 * 失败形状是**静默**：
 * - 闸写好了，但调用点在 `pipeline.execute` **之后**（页面已经被截了，再问用户＝事后通知）；
 * - 闸只是弹卡，DENY 分支却没返回 `Failed`（用户点了「不截」，图还是截了）；
 * - 读图不看 `provider.vision`（给未验证通道塞图片块，或把截图悄悄发出去）；
 * - 新截图不替换旧帧（模型以为看的是当前页，其实是三步前的）；
 * - `shotRoot` 没接（`证道/截屏/` 里的图被越界判据拒掉，静默变成「读不出来」）。
 * 这些都在**两处调用之间**，只有源码级钉子能拦住。
 *
 * 证据等级：本文件全是 ②（代码原文）；行为面（真机截图进模型）在交付报告「未验」栏。
 */
class ScreenshotWiringTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()

    /** 去注释后的源码（KDoc 里提到 `provider.vision` 不算引用）。 */
    private fun codeOnly(text: String): String =
        text.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engineRaw: String
        get() = src("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private val engine: String get() = codeOnly(engineRaw)

    /** 串行调用那一段（闸与执行都在里面，位置判据必须在同一段内比）。 */
    private val serialCall: String
        get() {
            val start = engine.indexOf("private fun handleSerialCall(")
            assertTrue("找不到 handleSerialCall（接线断了）", start >= 0)
            return engine.substring(start, engine.indexOf("private fun ", start + 10).coerceAtLeast(start))
        }

    /** 收图那一个函数体。 */
    private val adoptFn: String
        get() {
            val start = engine.indexOf("private fun adoptToolImages(")
            assertTrue("找不到 adoptToolImages（S1 断线）", start >= 0)
            return engine.substring(start, engine.indexOf("private fun ", start + 10).coerceAtLeast(start))
        }

    /** 闸那一个函数体。 */
    private val gateFn: String
        get() {
            val start = engine.indexOf("private fun screenshotGate(")
            assertTrue("找不到 screenshotGate（授权闸断线）", start >= 0)
            return engine.substring(start, engine.indexOf("private fun ", start + 10).coerceAtLeast(start))
        }

    // ── G1 闸的位置：真执行之前 ──────────────────────────────────────────────

    @Test
    fun `G1_授权闸在真执行之前`() {
        val gateAt = serialCall.indexOf("screenshotGate(call, sessionId)")
        val execAt = serialCall.indexOf("pipeline.execute(call)")
        assertTrue("必须调用 screenshotGate", gateAt >= 0)
        assertTrue("必须还在走 pipeline.execute", execAt >= 0)
        assertTrue("闸必须在执行之前（事后通知＝没闸）", gateAt < execAt)
    }

    @Test
    fun `G1b_闸排在计划闸之后_与既有顺序不打架`() {
        val planAt = serialCall.indexOf("planChangeGate(call)")
        val gateAt = serialCall.indexOf("screenshotGate(call, sessionId)")
        assertTrue("计划闸不该被挪走", planAt >= 0)
        assertTrue("截图闸排在计划闸之后", planAt < gateAt)
    }

    // ── G2 闸只对截图生效，且玄览没开不打扰用户 ────────────────────────────

    @Test
    fun `G2_只对截图工具生效_其它工具一个字节不改`() {
        assertTrue(
            "闸必须按工具名早退（否则每个工具都弹卡）",
            gateFn.contains("if (call.name != BrowserTools.NAME_SCREENSHOT) return null"),
        )
        assertTrue(
            "玄览没开就不弹卡（为一个必然失败的调用打扰用户）",
            gateFn.contains("if (BaopuBrowser.require() == null) return null"),
        )
    }

    @Test
    fun `G2b_敏感页与已授权两条早退写在一起`() {
        assertTrue(
            "已授权且非敏感页才放行",
            gateFn.contains("if (!sensitive && shotAuthorizedSessions.contains(sessionId)) return null"),
        )
        assertTrue("敏感页判据来自 SensitivePage", gateFn.contains("SensitivePage.isSensitive("))
    }

    // ── G3 不批就不截：DENY 必须返回 Failed ────────────────────────────────

    @Test
    fun `G3_拒绝或超时_返回Failed且不执行`() {
        assertTrue(
            "DENY 分支必须回 Failed（回 null 就等于放行）",
            gateFn.contains("GuardPipeline.Result.Failed(call = call, error = ScreenshotConsent.deniedText())"),
        )
        assertTrue(
            "答案判据必须走 ScreenshotConsent.decide（超时=拒绝由它定）",
            gateFn.contains("ScreenshotConsent.decide(picked?.id)"),
        )
        assertTrue("闸自己不许调 pipeline/执行器", !gateFn.contains("pipeline.execute"))
    }

    @Test
    fun `G3b_会话授权只在点了允许之后才记`() {
        val allowAt = gateFn.indexOf("ScreenshotConsent.Decision.ALLOW_SESSION ->")
        val addAt = gateFn.indexOf("shotAuthorizedSessions.add(sessionId)")
        assertTrue("必须记会话授权", addAt >= 0)
        assertTrue("记授权必须在 ALLOW_SESSION 分支里", allowAt >= 0 && allowAt < addAt)
    }

    // ── G4 审计：每次裁决都留一行 ────────────────────────────────────────────

    @Test
    fun `G4_三种裁决都落审计行`() {
        for (source in listOf("会话授权", "单次授权", "拒绝/无应答")) {
            assertTrue("缺审计：$source", gateFn.contains("ScreenshotConsent.auditLine(\"$source\""))
        }
        assertTrue("审计走 RunLog（log）", gateFn.contains("log(ScreenshotConsent.auditLine("))
    }

    @Test
    fun `G4b_像素去向也有审计`() {
        assertTrue("已附图要有账", adoptFn.contains("auditLine(\"已附图\""))
        assertTrue("未发送要有账", adoptFn.contains("auditLine(\"未发送（通道未验证看图）\""))
        assertTrue("读图失败要有账", adoptFn.contains("auditLine(\"读图失败\""))
    }

    // ── G5 只有验证过的通道才读盘 ────────────────────────────────────────────

    @Test
    fun `G5_未验证看图_不读盘不发送`() {
        val guardAt = adoptFn.indexOf("if (!provider.vision)")
        val loadAt = adoptFn.indexOf("VisionFiles.load(")
        assertTrue("必须有 provider.vision 判断", guardAt >= 0)
        assertTrue("必须有读图调用", loadAt >= 0)
        assertTrue("vision 判断必须在读盘之前（先读再判＝已经上内存了）", guardAt < loadAt)
        assertTrue(
            "未验证通道要给模型一条退路（读文字），不是干等",
            adoptFn.contains("browser_snapshot"),
        )
    }

    @Test
    fun `G5b_读图必须认第二个根_否则截屏目录被越界拒`() {
        assertTrue(
            "VisionFiles.load 必须带 extraRoot = shotRoot()",
            adoptFn.contains("extraRoot = shotRoot()"),
        )
        val runtime = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(
            "生产必须注入落盘根（Store.root = Download/证道）",
            runtime.contains("shotRoot = { Store.root(appCtx) }"),
        )
    }

    // ── G6 旧帧必须被替换，用户贴图不许动 ──────────────────────────────────

    @Test
    fun `G6_浏览器帧只留最新_用户贴图不动`() {
        assertTrue(
            "必须按 userImageCount 分界替换",
            adoptFn.contains("turnImages = turnImages.take(userImageCount) + loaded.payloads.take(1)"),
        )
        assertTrue(
            "用户贴图张数必须在 runLoop 里记下",
            engine.contains("userImageCount = turnImages.size"),
        )
        assertTrue("分界字段必须有注释说明用途", engineRaw.contains("private var userImageCount = 0"))
    }

    @Test
    fun `G6b_收图只发生在 Done 分支`() {
        val doneAt = engine.indexOf("is GuardPipeline.Result.Done ->")
        val adoptAt = engine.indexOf("adoptToolImages(result)")
        assertTrue("必须有收图调用", adoptAt >= 0)
        assertTrue("收图必须在 Done 分支里（失败/被拒没有图可收）", doneAt >= 0 && doneAt < adoptAt)
    }

    // ── G7 工具层把路径放进图片通道 ─────────────────────────────────────────

    @Test
    fun `G7_工具层把截图路径放进 imageRelPaths`() {
        val tools = codeOnly(src("src/main/java/com/example/zhengdao/baopu/agent/tools/BrowserTools.kt"))
        assertTrue(
            "截图 spec 必须把路径交给引擎（只回文本＝模型还是看不见）",
            tools.contains("imageRelPaths = listOfNotNull(shot.relPath)"),
        )
        assertTrue("拿不到路径要明说", tools.contains("看不到像素"))
    }

    @Test
    fun `G7b_管道把图片通道透传到 Done`() {
        val pipe = codeOnly(src("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt"))
        assertTrue(
            "finish 必须从 Ok 取图并塞进 Done",
            pipe.contains("(outcome as? ToolOutcome.Ok)?.imageRelPaths.orEmpty()"),
        )
        assertTrue("Done 要有 imageRelPaths 字段（默认空＝存量零改）", pipe.contains("val imageRelPaths: List<String> = emptyList()"))
    }
}
