// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体形状（第二十四批 A 段）——V1/V2/V7 的靶心。
 *
 * ## 为什么 V1 是「逐字节等价」这么重的判据
 *
 * `bodyOf` 是**全系统唯一的请求体出口**。图片能力是「加一段」的改动，但如果重写
 * 了纯文本路径，历史上所有按这个形状对过账的东西都会静默变样：真机日志、缓存命中
 * （同字节才算命中）、以及刀 0b 那 15 条老单测。所以纯文本必须走**原封不动**那条分支。
 *
 * ## 为什么这里不直接比字符串字面量
 *
 * `org.json.JSONObject` 的 key 顺序**不保证**（实现依版本而变）。写死一整串期望
 * JSON 会在换 JSON 实现时假红。本文件改为四条硬证据的组合：
 * ① `content` 的类型仍是 **String**（没被换成数组）；② 值逐字等于原文；
 * ③ 整份 body 里**没有**任何图片块的痕迹（`image_url` / `"type"`）；
 * ④ 同一输入连跑两次输出完全相同（确定性）。这四条一起，等价于「走了原分支」。
 */
class LlmWireVisionTest {

    private val compat = Provider(
        id = "test-compat",
        label = "test",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://example.com/v1",
        model = "test-model",
        maxOutput = 4096,
    )

    private val anthropic = Provider(
        id = "test-anthropic",
        label = "test",
        protocol = LlmProtocol.ANTHROPIC,
        baseUrl = "https://example.com/v1",
        model = "test-claude",
        maxOutput = 4096,
    )

    private fun firstMessage(body: String): JSONObject {
        val json = JSONObject(body)
        val arr = json.getJSONArray("messages")
        assertTrue("必须有 messages", arr.length() >= 1)
        return arr.getJSONObject(0)
    }

    // ── V1：纯文本逐字节等价 ─────────────────────────────────────────────────

    @Test
    fun `V1_没有图片时content仍是字符串`() {
        val msgs = listOf(LlmMessage(LlmMessage.ROLE_USER, "你好，看看这张图"))
        val content = firstMessage(LlmWire.bodyOf(compat, msgs, stream = false)).opt("content")
        assertTrue("content 被换成数组了——纯文本路径不该动", content is String)
        assertEquals("你好，看看这张图", content as String)
    }

    @Test
    fun `V1_纯文本请求体里没有任何图片块痕迹`() {
        val msgs = listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, "sys"),
            LlmMessage(LlmMessage.ROLE_USER, "hi"),
        )
        val body = LlmWire.bodyOf(compat, msgs, stream = false)
        assertFalse("出现了 image_url 说明走了图片分支", body.contains("image_url"))
        assertFalse("出现了 type 字段说明多段分支被误用", body.contains("\"type\""))
    }

    @Test
    fun `V1_同一输入两次输出完全相同`() {
        val msgs = listOf(LlmMessage(LlmMessage.ROLE_USER, "同样的话"))
        val a = LlmWire.bodyOf(compat, msgs, stream = false)
        val b = LlmWire.bodyOf(compat, msgs, stream = false)
        assertEquals("请求体必须是确定性的（缓存与日志对账都靠它）", a, b)
    }

    @Test
    fun `V1_Anthropic纯文本也是字符串`() {
        val msgs = listOf(LlmMessage(LlmMessage.ROLE_USER, "看看"))
        val content = firstMessage(LlmWire.bodyOf(anthropic, msgs, stream = false)).opt("content")
        assertTrue(content is String)
        assertEquals("看看", content as String)
    }

    // ── V2：多段 content，text 在前 image 在后 ───────────────────────────────

    @Test
    fun `V2_OpenAI_图片时content是数组且text在前`() {
        val url = "data:image/jpeg;base64,QUJD"
        val msgs = listOf(LlmMessage(LlmMessage.ROLE_USER, "这是什么", listOf(url)))
        val content = firstMessage(LlmWire.bodyOf(compat, msgs, stream = false))
            .getJSONArray("content")

        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("这是什么", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(url, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `V2_OpenAI_多张图顺序保持`() {
        val a = "data:image/png;base64,AAA"
        val b = "data:image/png;base64,BBB"
        val msgs = listOf(LlmMessage(LlmMessage.ROLE_USER, "两张", listOf(a, b)))
        val content = firstMessage(LlmWire.bodyOf(compat, msgs, stream = false)).getJSONArray("content")
        assertEquals(3, content.length())
        assertEquals(a, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals(b, content.getJSONObject(2).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `V2_内容为空时只发图片段_不造空text块`() {
        val url = "data:image/png;base64,AAA"
        val msgs = listOf(LlmMessage(LlmMessage.ROLE_USER, "", listOf(url)))
        val content = firstMessage(LlmWire.bodyOf(compat, msgs, stream = false)).getJSONArray("content")
        assertEquals("空 text 块有的端点会 400，别造", 1, content.length())
        assertEquals("image_url", content.getJSONObject(0).getString("type"))
    }

    @Test
    fun `V2_Anthropic_图片用base64_source形状`() {
        val url = "data:image/jpeg;base64,QUJD"
        val msgs = listOf(LlmMessage(LlmMessage.ROLE_USER, "看看", listOf(url)))
        val content = firstMessage(LlmWire.bodyOf(anthropic, msgs, stream = false))
            .getJSONArray("content")

        assertEquals(2, content.length())
        val img = content.getJSONObject(1)
        assertEquals("image", img.getString("type"))
        val source = img.getJSONObject("source")
        assertEquals("base64", source.getString("type"))
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals("QUJD", source.getString("data"))
    }

    @Test
    fun `V2_只发指定消息的图_别的消息不受影响`() {
        val url = "data:image/png;base64,AAA"
        val msgs = listOf(
            LlmMessage(LlmMessage.ROLE_USER, "第一句"),
            LlmMessage(LlmMessage.ROLE_ASSISTANT, "答"),
            LlmMessage(LlmMessage.ROLE_USER, "第二句", listOf(url)),
        )
        val arr = JSONObject(LlmWire.bodyOf(compat, msgs, stream = false)).getJSONArray("messages")
        assertTrue(arr.getJSONObject(0).opt("content") is String)
        assertTrue(arr.getJSONObject(1).opt("content") is String)
        assertTrue(arr.getJSONObject(2).opt("content") is JSONArray)
    }

    // ── V6（第二道锁）：请求体里没有本机路径 ─────────────────────────────────

    @Test
    fun `V6_请求体里不出现本机绝对路径`() {
        val url = "data:image/jpeg;base64,QUJD"
        val body = LlmWire.bodyOf(
            compat,
            listOf(LlmMessage(LlmMessage.ROLE_USER, "看图", listOf(url))),
            stream = false,
        )
        listOf("/data/user", "/storage/emulated", "/sdcard", "file://", "content://").forEach {
            assertFalse("请求体里出现了 $it", body.contains(it))
        }
    }

    // ── V7：压缩不带图 ───────────────────────────────────────────────────────

    @Test
    fun `V7_压缩请求一条图都不带`() {
        val history = listOf(
            HistoryMessage("1", HistoryMessage.ROLE_USER, "用户说的话"),
            HistoryMessage("2", HistoryMessage.ROLE_ASSISTANT, "抱朴的回答"),
        )
        val msgs = Compressor.buildRequest(history, compat)
        assertTrue("压缩至少要有 system + user 两条", msgs.size >= 2)
        msgs.forEach { m ->
            assertTrue(
                "压缩是减少 token，不是看图——不许带图（V7）",
                m.images.isEmpty(),
            )
        }
        // 再加一层：序列化出来也确实没有图片块
        val body = LlmWire.bodyOf(compat, msgs, stream = false)
        assertFalse(body.contains("image_url"))
    }

    // ── V5：视觉判据来自 Provider，不硬编码模型名 ───────────────────────────

    @Test
    fun `V5_只有DeepSeek声明vision_其余仍为false`() {
        // 2026-10-18 口径变更：用户明确 deepseek v4.1 flash（1017）是**全模态**模型，
        // 图片要发得出去 ⇒ DEEPSEEK 预设 vision=true。其余预设（没有依据）一律仍 false。
        // 这条会挡住「顺手给别的通道也开 true」——开之前必须真发一张图验证。
        val all = Providers.all()
        assertEquals("预设数量变了就要重看这条", 4, all.size)
        val visionOn = all.filter { it.vision }.map { it.id }
        assertEquals("只许 DeepSeek 开（用户给的模型事实）", listOf("deepseek"), visionOn)
    }

    @Test
    fun `V5_vision默认值是false`() {
        val p = Provider(
            id = "x", label = "x", protocol = LlmProtocol.OPENAI_COMPAT,
            baseUrl = "https://example.com", model = "m",
        )
        assertFalse("默认必须是不支持——宁可少发，不许瞎发", p.vision)
    }

    @Test
    fun `V5_组装层判据里不硬编码模型名`() {
        val file = java.io.File("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt")
            .let { if (it.exists()) it else java.io.File("app/src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt") }
        val raw = file.readText()
        // 只查**代码**：注释里出现模型名是正常的（既有 KDoc 记着 deepseek-flash 的
        // context_window 实测值，那是上下文窗口的依据，不是视觉判据）。若把注释也算上，
        // 这条判据会变成"不许在文档里提模型名"，那不是它想钉的东西。
        val code = raw
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }

        listOf("deepseek", "DeepSeek", "gpt-4o", "claude", "vl-free").forEach { name ->
            assertFalse(
                "组装层的**判据代码**里出现了模型名：$name（V5：视觉能力只能从 Provider.vision 读）",
                code.contains(name),
            )
        }
        assertTrue("判据参数必须在签名里", raw.contains("visionSupported: Boolean"))
        assertTrue("必须真的把它交给裁图判据", code.contains("VisionRules.select(images, visionSupported)"))
    }

    // ── 组装层：note 落位 ────────────────────────────────────────────────────

    @Test
    fun `组装时没有视觉能力_note拼在本轮输入末尾`() {
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "w", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "这张图里是什么",
            images = listOf(VisionRules.ImagePayload("data:image/png;base64,AAA", 100)),
            visionSupported = false,
        )
        val current = prompt.segments.first { it.kind == SegmentKind.CURRENT }.text
        assertTrue("原文必须在", current.contains("这张图里是什么"))
        assertTrue("说明必须在末尾：$current", current.contains("已省略 1 张"))
        assertTrue("一张都不许带", prompt.images.isEmpty())
    }

    @Test
    fun `组装时只有图没有字_说明也不丢`() {
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "w", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "",
            images = listOf(VisionRules.ImagePayload("/sdcard/a.jpg", 100)),
            visionSupported = true,
        )
        val current = prompt.segments.firstOrNull { it.kind == SegmentKind.CURRENT }
        assertNotNull("说明不能被 current 为空吞掉", current)
        assertTrue(current!!.text.contains("已省略"))
    }

    @Test
    fun `组装时没有图_不新增任何段`() {
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "w", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "纯文字",
        )
        val current = prompt.segments.first { it.kind == SegmentKind.CURRENT }.text
        assertEquals("没图就一个字都不加", "纯文字", current)
        assertTrue(prompt.images.isEmpty())
    }

    @Test
    fun `组装时支持视觉_图片原样交给上层`() {
        val url = "data:image/jpeg;base64,QUJD"
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "w", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "看图",
            images = listOf(VisionRules.ImagePayload(url, 100)),
            visionSupported = true,
        )
        assertEquals(listOf(url), prompt.images)
        val current = prompt.segments.first { it.kind == SegmentKind.CURRENT }.text
        assertEquals("全发了就不需要解释", "看图", current)
    }
}
