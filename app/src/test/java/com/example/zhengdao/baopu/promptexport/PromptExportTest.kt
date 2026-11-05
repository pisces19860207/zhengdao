// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百五十九批 · 提示词导出口的判据（派工单 §二 ①～⑤）。
//
// 造数原则：**不造"看起来像提示词"的东西**。判据③④要的负例用真串（真 `sk-`、真 base64、
// 一句独特正文），判据⑤要的缺省值直接咬 `enabled(devMode, pref)` 这个纯函数——
// 不拿 Android 桩去测 SharedPreferences（JVM 单测里 android.jar 是桩，测出来的只是桩）。
//
// 一处口径照实说：`cacheDir` 与 `BuildConfig.DEBUG` 两条只有真机能验（本文件用
// [PromptExport.captureTo] 的文件面替代），真机读数在交付报告 §二。
package com.example.zhengdao.baopu.promptexport

import com.example.zhengdao.baopu.CriteriaPitPack
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromptExportTest {

    private lateinit var dir: File

    /** 固定时刻（元信息里的「时间」一栏要可断言，不能跟着跑测试的墙钟漂）。 */
    private val fixedMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zd459-export").toFile()
        PromptExport.resetForTest()
    }

    @After
    fun tearDown() {
        PromptExport.resetForTest()
        dir.deleteRecursively()
    }

    private fun write(text: String, on: Boolean = true, target: File = dir): Boolean =
        PromptExport.captureTo(
            dir = target,
            systemText = text,
            modelId = "test-model-x",
            channelId = "test-channel",
            protocol = "OPENAI_COMPAT",
            on = on,
            nowMs = fixedMs,
        )

    private fun bodyText(target: File = dir): String =
        File(target, PromptExport.SYSTEM_FILE).readText()

    private fun metaText(target: File = dir): String =
        File(target, PromptExport.META_FILE).readText()

    // ── 判据⑤：开关缺省值（release 默认关 / debug 默认开）─────────────────

    @Test
    fun `release缺省不导出`() {
        assertFalse("release 的缺省必须是关（派工单 §二⑤）", PromptExport.defaultOn(devMode = false))
        assertFalse(PromptExport.enabled(devMode = false, pref = null))
    }

    @Test
    fun `debug缺省导出`() {
        assertTrue("debug 的缺省必须是开（判据①靠它跑一轮就有文件）", PromptExport.defaultOn(devMode = true))
        assertTrue(PromptExport.enabled(devMode = true, pref = null))
    }

    @Test
    fun `SP显式关掉_debug也不导`() {
        assertFalse(PromptExport.enabled(devMode = true, pref = false))
    }

    @Test
    fun `SP显式打开_release也导`() {
        assertTrue(PromptExport.enabled(devMode = false, pref = true))
    }

    @Test
    fun `开关键名与路径与派工单逐字一致`() {
        assertEquals("zhengdao-inspect", PromptExport.SWITCH_FILE)
        assertEquals("prompt_export", PromptExport.SWITCH_KEY)
        assertEquals("prompt-export", PromptExport.DIR_NAME)
        assertEquals("last-system.txt", PromptExport.SYSTEM_FILE)
        assertEquals("last-system.meta.txt", PromptExport.META_FILE)
    }

    // ── 判据③：脱敏负例（`sk-` / `Bearer` / 长 hex / 长 base64）──────────

    @Test
    fun `脱敏_sk串不出现原文`() {
        val secret = "sk-abcdef1234567890ABCDEF"
        val out = PromptExport.scrub("key=$secret 后面还有字").text
        assertFalse("`sk-` 串必须被洗掉", out.contains(secret))
        assertTrue(out.contains("***"))
        assertTrue("洗的是密钥，不是整句话", out.contains("后面还有字"))
    }

    @Test
    fun `脱敏_Bearer不出现原文`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.abcdefghijklmnop.qrstuvwx"
        val out = PromptExport.scrub("Authorization: Bearer $token").text
        assertFalse(out.contains(token))
        assertTrue(out.contains("Bearer ***"))
    }

    @Test
    fun `脱敏_长hex不出现原文`() {
        val digest = "a3f5" + "0".repeat(60)
        val out = PromptExport.scrub("sha256=$digest").text
        assertFalse("32 位以上的长 hex 一律洗（可能是 key 指纹）", out.contains(digest))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `脱敏_长base64不出现原文`() {
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ejAxMjM0NTY3ODk="
        val out = PromptExport.scrub("blob: $blob").text
        assertFalse(out.contains(blob))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `脱敏_普通提示词一个字不改`() {
        // 负例：四条规则必须**认得清**普通中文提示词，不许无差别乱杀（否则导出的就不是原文了）。
        val plain = "# 证道「抱朴」运行环境说明\n" +
            "## 环境与权限\n1. **你在哪**：安卓手机上、证道 App 自己的进程里。\n" +
            "判据 3 条（表序：写死三条，还没有进化表）· 坑账 0 条\n" +
            "全文：/data/user/0/com.example.zhengdao/files/抱朴记忆/判决经验账.jsonl"
        val scrubbed = PromptExport.scrub(plain)
        assertEquals("普通提示词必须逐字不变", plain, scrubbed.text)
        assertEquals(0, scrubbed.hits)
    }

    @Test
    fun `脱敏命中数如实计`() {
        val out = PromptExport.scrub("a=sk-111111111111 b=sk-222222222222")
        assertEquals(2, out.hits)
    }

    // ── 判据④：聊天正文不进导出 ─────────────────────────────────────────

    @Test
    fun `聊天正文不进导出文件`() {
        val chatLine = "我今晚想吃番茄牛腩，别忘了买香菜"
        val system = "# 系统\n你是抱朴。\n\n## 规矩\n1. 交付物必须真落盘。\n"
        assertTrue(write(system))
        // 正例：写盘真的写了（否则下面那条「不在」是空转）。
        assertEquals("没有敏感串、没超长 ⇒ 导出的就是 system 段本身，逐字一致", system, bodyText())
        // 负例：正文那句话在函数入参里根本没有位置，文件里也不许出现。
        assertFalse(bodyText().contains(chatLine))
        assertFalse(metaText().contains(chatLine))
    }

    // ── 截断（超长标「已截断」）──────────────────────────────────────────

    @Test
    fun `超长截断并标已截断`() {
        val long = "甲".repeat(PromptExport.MAX_CHARS + 500)
        val clipped = PromptExport.clip(long)
        assertTrue(clipped.truncated)
        assertTrue("截断位置不许越过上限太多", clipped.body.length <= PromptExport.MAX_CHARS + 40)
        assertTrue(clipped.body.contains("已截断"))
        assertTrue(clipped.body.contains("原文 ${long.length} 字符"))
    }

    @Test
    fun `不超长逐字一致且不标截断`() {
        val short = "# 系统\n" + "乙".repeat(100)
        val clipped = PromptExport.clip(short)
        assertFalse(clipped.truncated)
        assertEquals(short, clipped.body)
    }

    // ── 判据②：元信息里的模型 id 与注入包版本／条数 ──────────────────────

    private fun facts(pack: PackFacts = PackFacts(1, 3, 0, "写死三条，还没有进化表")) = ExportFacts(
        atMs = fixedMs,
        modelId = "gemini-2.5-flash",
        channelId = "google",
        protocol = "GEMINI_NATIVE",
        chars = 16_984,
        outChars = 16_984,
        truncated = false,
        tokens = 4_883,
        scrubHits = 0,
        pack = pack,
    )

    @Test
    fun `元信息含模型id与注入包版本条数`() {
        val meta = PromptExport.metaOf(facts())
        assertTrue(meta.contains("模型: gemini-2.5-flash"))
        assertTrue(meta.contains("通道: google"))
        assertTrue("判据②要的版本号", meta.contains("注入包: 版本 v1"))
        assertTrue("判据②要的条数", meta.contains("判据 3 条"))
        assertTrue(meta.contains("坑账 0 条"))
        assertTrue(meta.contains("token 估算: 4883"))
        assertTrue(meta.contains("时间: "))
        assertTrue(meta.contains("未截断"))
    }

    @Test
    fun `注入包取不到时写未知不写0`() {
        val meta = PromptExport.metaOf(facts(PackFacts(1, -1, -1, "取不到（读盘失败）")))
        assertTrue(meta.contains("判据 未知"))
        assertTrue(meta.contains("坑账 未知"))
        assertFalse("取不到不许写 0 冒充读过", meta.contains("判据 0 条"))
    }

    @Test
    fun `真捕获写出的元信息含注入包版本`() {
        assertTrue(write("# 系统\n正文"))
        val meta = metaText()
        // 条数那一栏要过真 snapshot（JVM 里账本可能未装配 ⇒ 「未知」也算如实），
        // 所以这里只钉「版本号一定在」这条；条数的格式由上面 metaOf 那条咬。
        assertTrue(meta.contains("注入包: 版本 v${CriteriaPitPack.VERSION}"))
        assertTrue(meta.contains("判据 "))
    }

    // ── 「导出上一次」的触发 ────────────────────────────────────────────

    @Test
    fun `开关关着一个字节都不写`() {
        assertTrue(write("# 系统\n第一次"))
        File(dir, PromptExport.SYSTEM_FILE).delete()
        File(dir, PromptExport.META_FILE).delete()
        assertFalse(write("# 系统\n不该落盘", on = false))
        assertFalse(File(dir, PromptExport.SYSTEM_FILE).exists())
        assertNotNull("第一次的缓存还在（关着的那一次不许污染它）", PromptExport.exportLastTo(dir))
        assertEquals("# 系统\n第一次", bodyText())
    }

    @Test
    fun `导出上一次_没捕获过时返回null`() {
        PromptExport.resetForTest()
        assertNull("没有「上一次」就不许拿磁盘旧文件冒充", PromptExport.exportLastTo(dir))
        assertFalse(File(dir, PromptExport.SYSTEM_FILE).exists())
    }

    @Test
    fun `导出上一次_能把上次那份重新落盘`() {
        assertTrue(write("# 系统\n内容"))
        File(dir, PromptExport.SYSTEM_FILE).delete()
        val path = PromptExport.exportLastTo(dir)
        assertNotNull(path)
        assertEquals("# 系统\n内容", bodyText())
    }

    // ── 边界：只读复制（源码级）──────────────────────────────────────────

    private fun source(rel: String): String {
        val roots = listOf(File("app/src/main/java"), File("src/main/java"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 app 源码根（cwd=${File("").absolutePath}）")
        val f = File(root, rel)
        assertTrue("找不到源文件：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `源级闸_抓取点落在拼装之后发请求之前`() {
        val src = source("com/example/zhengdao/baopu/agent/RunEngine.kt")
        val call = src.indexOf("promptTap?.invoke(systemFinal, provider)")
        assertTrue("抓取点不见了（RUN 引擎没把 system 段递出来）", call > 0)
        val build = src.indexOf("val systemFinal = if (prefix.isBlank())")
        val messages = src.indexOf("val messages = buildList {")
        assertTrue("抓取点必须在 systemFinal 拼好之后", call > build)
        assertTrue("抓取点必须在组装 messages 之前（否则抄的就不一定是发出去的那一份）", call < messages)
        assertEquals("只许一处抓取点（多一处就是两条口径）", 1, Regex("promptTap\\?").findAll(src).count())
    }

    @Test
    fun `源级闸_导出器不碰正文与提示词拼装`() {
        val src = source("com/example/zhengdao/baopu/promptexport/PromptExport.kt")
        val code = src.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)//.*$"), "")
        listOf("ROLE_USER", "ROLE_SYSTEM", "history", "LlmMessage", "systemSuffix(").forEach { word ->
            assertFalse("导出器不许碰「$word」（它只允许抄 system 段）", code.contains(word))
        }
    }
}
