// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百五十八批判据（模型口径 2026-10-23 更正：不写死 2.5，先拉 /models 清单）：
// - 纯函数：产物路径/文件名、数量上限、尺寸白名单、同名不覆盖、PNG 尺寸解析；
// - 模型挑选：剥 models/ 前缀、能力筛（generateContent 一票否决）、Pro → 3.1 Flash → 都没有；
// - MockWebServer：先 GET 清单再 POST 生图；inline_data 落盘（字节级）、请求形状、回执；
// - 图生图：参考图进 parts；无 Key：可读错误零请求；清单无图模型：可读错误且不回退 2.5。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.util.TimeFormats
import java.io.File
import java.net.InetAddress
import java.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenerateImageToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private var oldEnv: GenerateImageTool.Env? = null

    /** 1×1 透明 PNG（公开的标准测试图；IHDR 宽高各 1）。 */
    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private val fixedNow = 1_767_000_000_000L
    private val stamp: String = TimeFormats.fileStamp(fixedNow)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        root = tmp.newFolder("workspace")
        oldEnv = GenerateImageTool.env
    }

    @After
    fun tearDown() {
        GenerateImageTool.env = oldEnv
        server.shutdown()
    }

    private fun provider(apiKey: String = "k") = Provider(
        id = "gemini",
        label = "Gemini",
        protocol = LlmProtocol.GEMINI_NATIVE,
        baseUrl = "http://127.0.0.1:${server.port}/v1beta",
        model = GenerateImage.PREFERRED_PRO,
        apiKey = apiKey,
    )

    private fun env(apiKey: String = "k", dir: (File) -> File = { File(it, "产物") }) =
        GenerateImageTool.Env(
            provider = provider(apiKey),
            artifactDir = dir,
            now = { fixedNow },
        ).also { GenerateImageTool.env = it }

    /** `{base}/models` 的响应（Gemini 原生形状：name 带 `models/` 前缀）。 */
    private fun modelsBody(vararg ids: String): String {
        val rows = ids.joinToString(",") { id ->
            """{"name":"models/$id","displayName":"$id","supportedGenerationMethods":["generateContent"]}"""
        }
        return """{"models":[$rows]}"""
    }

    private fun modelsOk(vararg ids: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(modelsBody(*ids))

    private fun imageBody(mime: String, bytes: ByteArray, snake: Boolean = false): String {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val part = if (snake) {
            "{\"inline_data\":{\"mime_type\":\"$mime\",\"data\":\"$b64\"}}"
        } else {
            "{\"inlineData\":{\"mimeType\":\"$mime\",\"data\":\"$b64\"}}"
        }
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"好了\"},$part]},\"finishReason\":\"STOP\"}]}"
    }

    private fun ok(bytes: ByteArray = png, snake: Boolean = false) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(imageBody("image/png", bytes, snake))

    // ── ① 纯函数 ──────────────────────────────────────────────────────────

    @Test
    fun `P1_产物目录_默认与自定义工作区都落产物`() {
        // 157 目录收口：旧「默认工作区落 资料库/整理、自定义落 产物/」两套口径已合并成一处。
        assertEquals(
            "产物",
            GenerateImage.artifactDir(root).toRelativeString(root).replace('\\', '/'),
        )
    }

    @Test
    fun `P2_文件名_时间戳加中文slug_第二张起带序号`() {
        assertEquals("画一只橘猫", GenerateImage.slugOf("画一只橘猫！"))
        assertEquals("$stamp-画一只橘猫", GenerateImage.baseName(stamp, "画一只橘猫", 0))
        assertEquals("$stamp-画一只橘猫-2", GenerateImage.baseName(stamp, "画一只橘猫", 1))
        assertEquals("图片", GenerateImage.slugOf("！！！"))
        assertTrue("slug 最长 24 字", GenerateImage.slugOf("猫".repeat(60), 24).length == 24)
    }

    @Test
    fun `P3_数量上限4_比例只认白名单`() {
        assertEquals("1:1", GenerateImage.normalizeSize(null))
        assertEquals("1:1", GenerateImage.normalizeSize(" "))
        assertEquals("16:9", GenerateImage.normalizeSize("16:9"))
        assertNull("2:1 不在白名单", GenerateImage.normalizeSize("2:1"))
        assertEquals(1, GenerateImage.normalizeCount(null))
        assertEquals(1, GenerateImage.normalizeCount(0))
        assertEquals(4, GenerateImage.normalizeCount(99))
        assertEquals(3, GenerateImage.normalizeCount(3))
    }

    @Test
    fun `P4_同名不覆盖_依次加序号`() {
        val dir = tmp.newFolder("out")
        File(dir, "a.png").writeBytes(byteArrayOf(1))
        File(dir, "a-2.png").writeBytes(byteArrayOf(2))
        assertEquals("a-3.png", GenerateImage.uniqueTarget(dir, "a", "png").name)
        assertEquals("b.png", GenerateImage.uniqueTarget(dir, "b", "png").name)
    }

    @Test
    fun `P5_PNG尺寸解析_1x1`() {
        assertEquals(1 to 1, GenerateImage.dimensionsOf(png))
        assertNull("不是图片 ⇒ null", GenerateImage.dimensionsOf(byteArrayOf(1, 2, 3)))
    }

    // ── ② 模型挑选（纯函数）──────────────────────────────────────────────

    @Test
    fun `N1_清单能力筛_剥models前缀_去重_非generateContent一票否决`() {
        val json = """
            {"models":[
              {"name":"models/gemini-3-pro-image","supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-3.1-flash-image","outputModalities":["TEXT","IMAGE"]},
              {"name":"models/gemini-3-pro-image","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3-pro","outputModalities":["TEXT","IMAGE"]},
              {"name":"models/gemini-2.5-flash-image","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-image-veto","supportedGenerationMethods":["embedContent"]}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("gemini-3-pro-image", "gemini-3.1-flash-image", "gemini-3-pro", "gemini-2.5-flash-image"),
            GenerateImage.imageModelIds(json),
        )
        assertTrue("坏 JSON ⇒ 空表，不猜", GenerateImage.imageModelIds("{oops").isEmpty())
        assertTrue("没有 models 数组 ⇒ 空表", GenerateImage.imageModelIds("{}").isEmpty())
    }

    @Test
    fun `N2_挑选顺序_Pro优先_其次31Flash_只有25就null`() {
        assertEquals(
            GenerateImage.PREFERRED_PRO,
            GenerateImage.pickImageModel(
                modelsBody(GenerateImage.PREFERRED_FLASH, "gemini-2.5-flash-image", GenerateImage.PREFERRED_PRO),
            ),
        )
        assertEquals(
            GenerateImage.PREFERRED_FLASH,
            GenerateImage.pickImageModel(modelsBody("gemini-2.5-flash-image", GenerateImage.PREFERRED_FLASH)),
        )
        assertEquals(
            "同一族取最短名（preview 也算 Pro）",
            GenerateImage.PREFERRED_PRO,
            GenerateImage.pickImageModel(
                modelsBody("${GenerateImage.PREFERRED_PRO}-preview", GenerateImage.PREFERRED_PRO),
            ),
        )
        assertEquals(
            "只有 preview 也能用",
            "${GenerateImage.PREFERRED_FLASH}-preview",
            GenerateImage.pickImageModel(modelsBody("${GenerateImage.PREFERRED_FLASH}-preview")),
        )
        assertNull(
            "清单只有 2.5 ⇒ null（绝不回退旧模型）",
            GenerateImage.pickImageModel(modelsBody("gemini-2.5-flash-image", "gemini-2.0-flash")),
        )
    }

    @Test
    fun `N3_清单地址_v1beta补models_一次拉满`() {
        assertEquals(
            "http://x/v1beta/models?pageSize=${GenerateImage.MODELS_PAGE_SIZE}",
            GenerateImage.imageModelsUrl("http://x/v1beta"),
        )
        assertEquals(
            "http://x/v1beta/models?pageSize=1000",
            GenerateImage.imageModelsUrl("http://x"),
        )
    }

    // ── ③ 生图（MockWebServer：先清单、再生图）──────────────────────────────

    @Test
    fun `G1_200_inlineData落盘_回执带路径尺寸字节数_请求形状正确`() {
        env()
        server.enqueue(modelsOk(GenerateImage.PREFERRED_PRO, GenerateImage.PREFERRED_FLASH))
        server.enqueue(ok())
        val out = GenerateImageTool.run(root, "画一只橘猫", "1:1", 1, null)
        val text = (out as ToolOutcome.Ok).text
        val expected = File(root, "产物/$stamp-画一只橘猫.png")
        assertTrue("文件要真落盘：$expected", expected.isFile)
        assertArrayEquals("落盘字节必须与 inlineData 解码后一致", png, expected.readBytes())
        assertTrue("回执要有相对路径：$text", text.contains("产物/$stamp-画一只橘猫.png"))
        assertTrue("回执要有尺寸：$text", text.contains("1×1"))
        assertTrue("回执要有字节数：$text", text.contains("${png.size} 字节"))
        assertFalse(
            "base64 不许进对话（回执里连片段都不许有）",
            text.contains(Base64.getEncoder().encodeToString(png).take(24)),
        )

        val listReq = server.takeRequest()
        assertEquals("先拉清单", "GET", listReq.method)
        assertEquals("/v1beta/models?pageSize=1000", listReq.path)
        assertEquals("k", listReq.getHeader("x-goog-api-key"))
        assertFalse("Key 不许进 URL", listReq.path.orEmpty().contains("key"))

        val genReq = server.takeRequest()
        assertEquals("POST", genReq.method)
        assertEquals("/v1beta/models/${GenerateImage.PREFERRED_PRO}:generateContent", genReq.path)
        assertEquals("k", genReq.getHeader("x-goog-api-key"))
        val body = JSONObject(genReq.body.readUtf8())
        val cfg = body.getJSONObject("generationConfig")
        assertEquals(
            listOf("TEXT", "IMAGE"),
            cfg.getJSONArray("responseModalities").let { a -> (0 until a.length()).map { a.getString(it) } },
        )
        assertEquals("1:1", cfg.getJSONObject("imageConfig").getString("aspectRatio"))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("画一只橘猫", parts.getJSONObject(0).getString("text"))
        assertEquals("文生图只有 text 一个 part", 1, parts.length())
    }

    @Test
    fun `G2_snake_case的inline_data也认`() {
        env()
        server.enqueue(modelsOk(GenerateImage.PREFERRED_PRO))
        server.enqueue(ok(snake = true))
        val out = GenerateImageTool.run(root, "画一只橘猫", null, null, null)
        assertTrue(out is ToolOutcome.Ok)
        assertTrue(File(root, "产物/$stamp-画一只橘猫.png").isFile)
    }

    @Test
    fun `G3_图生图_参考图字节进inline_data`() {
        env()
        val refDir = File(root, "附件").apply { mkdirs() }
        val refBytes = byteArrayOf(0x11, 0x22, 0x33)
        File(refDir, "cat.png").writeBytes(refBytes)
        server.enqueue(modelsOk(GenerateImage.PREFERRED_PRO))
        server.enqueue(ok())
        val out = GenerateImageTool.run(root, "把这张图改成水彩", "4:3", 1, "附件/cat.png")
        assertTrue(out is ToolOutcome.Ok)
        server.takeRequest()
        val parts = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        val inline = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/png", inline.getString("mime_type"))
        assertEquals(Base64.getEncoder().encodeToString(refBytes), inline.getString("data"))
    }

    @Test
    fun `G4_count2_一次清单两次生图_第二张带序号`() {
        env()
        server.enqueue(modelsOk(GenerateImage.PREFERRED_PRO))
        server.enqueue(ok())
        server.enqueue(ok())
        val out = GenerateImageTool.run(root, "画一只橘猫", "1:1", 2, null)
        val text = (out as ToolOutcome.Ok).text
        assertEquals("1 次清单 + 2 次生图", 3, server.requestCount)
        assertTrue(File(root, "产物/$stamp-画一只橘猫.png").isFile)
        assertTrue(File(root, "产物/$stamp-画一只橘猫-2.png").isFile)
        assertTrue("两张都要列出来：$text", text.contains("画好了（2 张"))
    }

    @Test
    fun `G5_清单只有31Flash_请求走31Flash`() {
        env()
        server.enqueue(modelsOk(GenerateImage.PREFERRED_FLASH))
        server.enqueue(ok())
        assertTrue(GenerateImageTool.run(root, "画一只橘猫", null, null, null) is ToolOutcome.Ok)
        server.takeRequest()
        assertEquals(
            "/v1beta/models/${GenerateImage.PREFERRED_FLASH}:generateContent",
            server.takeRequest().path,
        )
    }

    // ── ④ 错误路径 ────────────────────────────────────────────────────────

    @Test
    fun `E1_无Key_可读错误_一个请求都不发`() {
        env(apiKey = "")
        val out = GenerateImageTool.run(root, "画一只橘猫", null, null, null)
        val text = (out as ToolOutcome.Failed).error
        assertTrue("要点名 Key：$text", text.contains("Key"))
        assertTrue("要指出去哪儿填：$text", text.contains("设置"))
        assertEquals("没 Key 不许发请求（清单也不许拉）", 0, server.requestCount)
        assertFalse("没 Key 不许建出图来", File(root, "产物").exists())
    }

    @Test
    fun `E2_400_可读错误_不带响应体原文`() {
        env()
        server.enqueue(modelsOk(GenerateImage.PREFERRED_PRO))
        server.enqueue(MockResponse().setResponseCode(400).setBody("SECRET-BODY-原文"))
        val text = (GenerateImageTool.run(root, "画一只橘猫", null, null, null) as ToolOutcome.Failed).error
        assertTrue("要报 400：$text", text.contains("400"))
        assertFalse("响应体原文不许进 UI：$text", text.contains("SECRET-BODY"))
        assertEquals("清单 1 次 + 生图 1 次，生图不自动重试", 2, server.requestCount)
    }

    @Test
    fun `E3_没有inlineData_说清只回了文字`() {
        env()
        server.enqueue(modelsOk(GenerateImage.PREFERRED_PRO))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"你好\"}]},\"finishReason\":\"STOP\"}]}"),
        )
        val text = (GenerateImageTool.run(root, "画一只橘猫", null, null, null) as ToolOutcome.Failed).error
        assertTrue("要说明只回了文字：$text", text.contains("只回了文字"))
    }

    @Test
    fun `E4_参考图越界_直接拒且不发请求`() {
        env()
        val out = GenerateImageTool.run(root, "画一只橘猫", null, null, "../外面.png")
        assertTrue(out is ToolOutcome.Rejected)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `E5_比例写错_报白名单不发请求`() {
        env()
        val text = (GenerateImageTool.run(root, "画一只橘猫", "2:1", null, null) as ToolOutcome.Failed).error
        assertTrue("要点名不认识的比例：$text", text.contains("2:1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `E6_清单只有25_可读错误_不发generate请求_不回退`() {
        env()
        server.enqueue(modelsOk("gemini-2.5-flash-image"))
        val text = (GenerateImageTool.run(root, "画一只橘猫", null, null, null) as ToolOutcome.Failed).error
        assertTrue("要点明没有可用生图模型：$text", text.contains("没有可用的生图模型"))
        assertTrue("要说明不回退：$text", text.contains("不会退回旧模型"))
        assertEquals("只拉了清单就停：1 次请求", 1, server.requestCount)
        assertEquals("唯一一次是清单 GET", "/v1beta/models?pageSize=1000", server.takeRequest().path)
        assertFalse("没挑出模型不许建产物目录", File(root, "产物").exists())
    }

    @Test
    fun `E7_清单403_可读Key错误_不发generate请求`() {
        env()
        server.enqueue(MockResponse().setResponseCode(403).setBody("SECRET-BODY-原文"))
        val text = (GenerateImageTool.run(root, "画一只橘猫", null, null, null) as ToolOutcome.Failed).error
        assertTrue("要报 403：$text", text.contains("403"))
        assertTrue("要指向 Key 设置：$text", text.contains("Key"))
        assertFalse("响应体原文不许进 UI：$text", text.contains("SECRET-BODY"))
        assertEquals("只拉了清单就停：1 次请求", 1, server.requestCount)
        assertFalse("没挑出模型不许建产物目录", File(root, "产物").exists())
    }

    @Test
    fun `E8_清单为空_可读错误`() {
        env()
        server.enqueue(modelsOk())
        val text = (GenerateImageTool.run(root, "画一只橘猫", null, null, null) as ToolOutcome.Failed).error
        assertTrue("要点明没有可用生图模型：$text", text.contains("没有可用的生图模型"))
        assertEquals("只拉了清单就停：1 次请求", 1, server.requestCount)
    }
}
