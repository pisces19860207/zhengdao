// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批 · 工具层的负例与整链测试（真 PNG 文件 → ImageIO 解码 → 判据 → 账本）：
// - 判据②负例：vision=false ⇒ 假 port **零请求**，回话逐字"该模型看不了图"；
// - 判据③落点：vision=true ⇒ 假 port 恰好一次请求（dataUrl＋提示词逐字），落账 sentOut=true；
// - 判据④负例：merge 撞已有外貌 ⇒ Rejected，卡文件一个字节不变；
// - 判据⑦：产物目录里只有 .appearance.jsonl 一件——图片不进产物、账里没有 dataUrl。
// 全局单例 env 谁用谁还（ToolSchemaAllTest 同款纪律）。
package com.example.zhengdao.baopu.portrait

import com.example.zhengdao.baopu.agent.PortraitAppearanceTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.VisionPort
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitAppearanceToolTest {

    private class FakePort : VisionPort {
        var calls = 0
        var lastDataUrl: String? = null
        var lastPrompt: String? = null
        var reply: String = "银发蓝瞳，白裙。"
        override fun describe(imageDataUrl: String, prompt: String): String {
            calls++
            lastDataUrl = imageDataUrl
            lastPrompt = prompt
            return reply
        }
    }

    @After
    fun restore() {
        PortraitAppearanceTool.env = null
    }

    private fun wsOf(dir: File): File = File(dir, "ws").apply { mkdirs() }

    private fun writeFixtures(ws: File): List<File> = listOf(
        "立绘/half.png" to PortraitFixtures.HALF,
        "立绘/full.png" to PortraitFixtures.FULL,
        "立绘/scene.png" to PortraitFixtures.SCENE,
    ).map { (rel, px) ->
        val f = File(ws, rel)
        f.parentFile?.mkdirs()
        PortraitFixtures.writePng(px, f)
        f
    }

    private fun envOf(port: FakePort, vision: Boolean) = PortraitAppearanceTool.Env(
        visionSupported = vision,
        modelId = "test-vision-model",
        port = port,
        decoder = PortraitFixtures.IMAGE_IO_DECODER,
        now = { 1_000L },
    )

    // ── 判据①整链＋判据⑤落账＋判据⑦ ─────────────────────────────────────

    @Test
    fun `look_三张夹具_读数正确_账本三行_产物无图片`() = TempSandbox.use("zd597-tool-look") { dir ->
        val ws = wsOf(dir)
        val files = writeFixtures(ws)
        PortraitAppearanceTool.env = envOf(FakePort(), vision = false)
        val spec = PortraitAppearanceTool.lookSpec { ws }
        val out = spec.executor(
            JSONObject().put("paths", JSONArray(files.map { it.relativeTo(ws).path })),
        )
        assertTrue(out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("半身像判半身＋紫发", text.contains("half.png") && text.contains("半身") && text.contains("#963CB4"))
        assertTrue("全身像判全身＋红瞳", text.contains("full.png") && text.contains("全身") && text.contains("#C82828"))
        assertTrue("远景判 3:2", text.contains("3:2"))
        assertTrue("差异只报不改", text.contains("差异 ①↔②") && text.contains("只报不改"))
        assertTrue("落账口径=本地/未外发", text.contains("模型=本地；未外发"))
        // 判据⑤：三行账，模型=本地、sentOut=false
        val rows = PortraitLedger.accounts(ws)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.model == "本地" && !it.sentOut && it.op == PortraitLedger.OP_LOOK })
        // 判据⑦：产物目录只有账本一件，没有图片；账面没有 dataUrl
        val products = PortraitLedger.file(ws).parentFile!!
        assertEquals(listOf(".appearance.jsonl"), products.list()!!.sorted())
        assertFalse(PortraitLedger.file(ws).readText().contains("data:image"))
    }

    @Test
    fun `look_读不了的文件_如实说_账记失败`() = TempSandbox.use("zd597-tool-look-bad") { dir ->
        val ws = wsOf(dir)
        File(ws, "立绘").mkdirs()
        File(ws, "立绘/not.png").writeText("这不是图片")
        PortraitAppearanceTool.env = envOf(FakePort(), vision = false)
        val spec = PortraitAppearanceTool.lookSpec { ws }
        val out = spec.executor(JSONObject().put("paths", JSONArray().put("立绘/not.png")))
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("读不了"))
        assertEquals(1, PortraitLedger.accounts(ws).size)
        assertFalse(PortraitLedger.accounts(ws)[0].ok)
    }

    // ── 判据②负例：无视觉能力 ⇒ 零请求 ──────────────────────────────────

    @Test
    fun `判据二负例_vision假_port零请求_回话逐字`() = TempSandbox.use("zd597-tool-novision") { dir ->
        val ws = wsOf(dir)
        val f = writeFixtures(ws)[0]
        val port = FakePort()
        PortraitAppearanceTool.env = envOf(port, vision = false)
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val out = spec.executor(
            JSONObject().put("action", "describe").put("path", f.relativeTo(ws).path),
        )
        assertTrue("不是失败：本地特征照出", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertEquals("负例②：请求次数必须是 0", 0, port.calls)
        assertTrue("逐字文案", text.contains("该模型看不了图"))
        assertFalse("描述不可能出现", text.contains(port.reply))
        val rows = PortraitLedger.accounts(ws)
        assertEquals(1, rows.size)
        assertFalse("账面：没外发", rows[0].sentOut)
    }

    // ── 判据③落点：能力有 ⇒ 恰好一次请求（CONFIRM 在管线；工具里闸只看能力）──

    @Test
    fun `describe_vision真_一次请求_dataUrl与提示词逐字_账记外发`() = TempSandbox.use("zd597-tool-describe") { dir ->
        val ws = wsOf(dir)
        val f = writeFixtures(ws)[0]
        val port = FakePort()
        PortraitAppearanceTool.env = envOf(port, vision = true)
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val out = spec.executor(
            JSONObject().put("action", "describe").put("path", f.relativeTo(ws).path),
        )
        assertTrue(out is ToolOutcome.Ok)
        assertEquals("负例③的另一面：放行才请求，且只一次", 1, port.calls)
        assertTrue("dataUrl 形状", port.lastDataUrl!!.startsWith("data:image/png;base64,"))
        assertTrue("发出去的是真图字节（非空 base64）", port.lastDataUrl!!.length > 200)
        assertEquals("提示词逐字", PortraitDescribeGate.PROMPT, port.lastPrompt)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("出参进回执", text.contains(port.reply))
        assertTrue("回执写明外发", text.contains("已外发一次"))
        val rows = PortraitLedger.accounts(ws)
        assertEquals(1, rows.size)
        assertTrue("账面：外发＋模型名", rows[0].sentOut && rows[0].model == "test-vision-model")
    }

    @Test
    fun `describe_超长回复_截到一百二十字`() = TempSandbox.use("zd597-tool-clamp") { dir ->
        val ws = wsOf(dir)
        val f = writeFixtures(ws)[0]
        val port = FakePort()
        port.reply = "长".repeat(300)
        PortraitAppearanceTool.env = envOf(port, vision = true)
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val out = spec.executor(
            JSONObject().put("action", "describe").put("path", f.relativeTo(ws).path),
        )
        assertTrue(out is ToolOutcome.Ok)
        val desc = (out as ToolOutcome.Ok).text.lineSequence().firstOrNull { it.startsWith("长") }!!
        assertEquals(PortraitDescribeGate.MAX_DESC_CHARS, desc.length)
    }

    @Test
    fun `describe_文本冒充图片_拒_不请求`() = TempSandbox.use("zd597-tool-fakeimg") { dir ->
        val ws = wsOf(dir)
        File(ws, "立绘").mkdirs()
        File(ws, "立绘/fake.png").writeText("假装是图")
        val port = FakePort()
        PortraitAppearanceTool.env = envOf(port, vision = true)
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val out = spec.executor(
            JSONObject().put("action", "describe").put("path", "立绘/fake.png"),
        )
        assertTrue(out is ToolOutcome.Failed)
        assertEquals("解码都过不了，一个字节不出门", 0, port.calls)
    }

    // ── 判据③/④：draft 只是草稿；merge 撞已有外貌拒写 ────────────────────

    private fun draftAndCard(ws: File, cardName: String, existing: String?): Pair<String, File> {
        val port = FakePort()
        PortraitAppearanceTool.env = envOf(port, vision = false)
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val d = spec.executor(
            JSONObject()
                .put("action", "draft")
                .put("character", "阿檀")
                .put("path", "立绘/half.png")
                .put("appearance", "紫发绿瞳，深蓝长袍"),
        )
        assertTrue(d is ToolOutcome.Ok)
        val id = Regex("pad-\\S+").find((d as ToolOutcome.Ok).text)!!.value
        val card = File(ws, cardName)
        card.parentFile?.mkdirs()
        val data = JSONObject().put("name", "阿檀")
        existing?.let { data.put("extensions", JSONObject().put("appearance", it)) }
        card.writeText(JSONObject().put("spec", "chara_card_v3").put("data", data).toString())
        return id to card
    }

    @Test
    fun `draft_只是账上一行_卡不存在也不许碰`() = TempSandbox.use("zd597-tool-draft") { dir ->
        val ws = wsOf(dir)
        writeFixtures(ws)
        PortraitAppearanceTool.env = envOf(FakePort(), vision = false)
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val d = spec.executor(
            JSONObject()
                .put("action", "draft")
                .put("character", "阿檀")
                .put("path", "立绘/half.png")
                .put("appearance", "紫发绿瞳，深蓝长袍"),
        )
        assertTrue(d is ToolOutcome.Ok)
        assertTrue("回话写明只是草稿", (d as ToolOutcome.Ok).text.contains("没进卡"))
        assertEquals("草稿在账上", 1, PortraitLedger.drafts(ws).size)
        assertFalse("没有卡文件被造出来", File(ws, "立绘/卡.json").exists())
    }

    @Test
    fun `判据四负例_merge撞已有外貌_拒绝且文件零字节改动`() = TempSandbox.use("zd597-tool-merge-hit") { dir ->
        val ws = wsOf(dir)
        writeFixtures(ws)
        val (id, card) = draftAndCard(ws, "卡/阿檀.json", existing = "旧外貌：金发")
        val before = card.readText()
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val out = spec.executor(
            JSONObject().put("action", "merge").put("draft_id", id).put("card", "卡/阿檀.json"),
        )
        assertTrue("负例④：必须 Rejected", out is ToolOutcome.Rejected)
        assertTrue((out as ToolOutcome.Rejected).reason.contains("不覆盖"))
        assertEquals("卡文件一个字节没动", before, card.readText())
        assertTrue("也没留进卡痕迹", PortraitLedger.mergedDraftIds(ws).isEmpty())
    }

    @Test
    fun `merge_空白卡_写入extensions并留痕_二次merge拒`() = TempSandbox.use("zd597-tool-merge-ok") { dir ->
        val ws = wsOf(dir)
        writeFixtures(ws)
        val (id, card) = draftAndCard(ws, "卡/阿檀.json", existing = null)
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val out = spec.executor(
            JSONObject().put("action", "merge").put("draft_id", id).put("card", "卡/阿檀.json"),
        )
        assertTrue(out is ToolOutcome.Ok)
        val merged = JSONObject(card.readText())
        assertEquals(
            "紫发绿瞳，深蓝长袍",
            merged.getJSONObject("data").getJSONObject("extensions").getString("appearance"),
        )
        assertEquals(setOf(id), PortraitLedger.mergedDraftIds(ws))
        val again = spec.executor(
            JSONObject().put("action", "merge").put("draft_id", id).put("card", "卡/阿檀.json"),
        )
        assertTrue("一份草稿只进一次卡", again is ToolOutcome.Rejected)
    }

    @Test
    fun `merge_PNG卡_拒_本单不重写PNG`() = TempSandbox.use("zd597-tool-merge-png") { dir ->
        val ws = wsOf(dir)
        writeFixtures(ws)
        val (id, _) = draftAndCard(ws, "卡/阿檀.json", existing = null)
        val png = File(ws, "卡/阿檀.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val spec = PortraitAppearanceTool.appearanceSpec { ws }
        val out = spec.executor(
            JSONObject().put("action", "merge").put("draft_id", id).put("card", "卡/阿檀.png"),
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("JSON"))
        assertEquals("PNG 没被动过", 3, png.length())
    }
}
