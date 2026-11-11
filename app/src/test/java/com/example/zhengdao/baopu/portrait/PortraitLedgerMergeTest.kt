// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批 · 判据③④⑤的账本与进卡判定测试：
// - 草稿只是账上一行（判据③）；merge 判"已有外貌拒写"（判据④负例）；进过卡不重进；
// - 落账行带 模型/是否外发（判据⑤）；坏行跳过；行里没有图片字节（判据⑦的账面口径）。
package com.example.zhengdao.baopu.portrait

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitLedgerMergeTest {

    private fun draftOf(id: String = "") = PortraitDraft(
        id = id, ts = 1_000L, character = "阿檀", image = "立绘/a.png",
        appearance = "黑发红瞳，白袍金纹，身形清瘦", features = "256×384（2:3）·半身",
        model = "本地", sentOut = false,
    )

    // ── 草稿（判据③）────────────────────────────────────────────────────

    @Test
    fun `草稿追加回填id_读回逐字段一致`() = TempSandbox.use("zd597-ledger") { dir ->
        val ws = File(dir, "ws").apply { mkdirs() }
        val saved = PortraitLedger.appendDraft(ws, draftOf())
        assertTrue("id 形如 pad-<ts>-<序>", saved.id.startsWith("pad-1000-1"))
        val back = PortraitLedger.draftById(ws, saved.id)!!
        assertEquals("阿檀", back.character)
        assertEquals("黑发红瞳，白袍金纹，身形清瘦", back.appearance)
        assertEquals("本地", back.model)
        assertFalse(back.sentOut)
    }

    @Test
    fun `没有账本时_读数全空_不抛`() = TempSandbox.use("zd597-ledger-empty") { dir ->
        val ws = File(dir, "ws").apply { mkdirs() }
        assertTrue(PortraitLedger.drafts(ws).isEmpty())
        assertTrue(PortraitLedger.accounts(ws).isEmpty())
        assertNull(PortraitLedger.draftById(ws, "pad-x"))
    }

    @Test
    fun `坏行跳过_整本不报废`() = TempSandbox.use("zd597-ledger-bad") { dir ->
        val ws = File(dir, "ws").apply { mkdirs() }
        PortraitLedger.appendDraft(ws, draftOf())
        PortraitLedger.file(ws).appendText("这不是 JSON\n", Charsets.UTF_8)
        assertEquals("坏行不算草稿", 1, PortraitLedger.drafts(ws).size)
        assertEquals("坏行不算落账", 0, PortraitLedger.accounts(ws).size)
    }

    // ── 落账（判据⑤）────────────────────────────────────────────────────

    @Test
    fun `落账行带模型与是否外发`() = TempSandbox.use("zd597-ledger-account") { dir ->
        val ws = File(dir, "ws").apply { mkdirs() }
        PortraitLedger.appendAccount(
            ws, PortraitAccount(2_000L, PortraitLedger.OP_LOOK, "立绘/a.png", "本地", sentOut = false, ok = true, note = ""),
        )
        PortraitLedger.appendAccount(
            ws, PortraitAccount(3_000L, PortraitLedger.OP_DESCRIBE, "立绘/a.png", "gemini-2.5-flash", sentOut = true, ok = true, note = ""),
        )
        val rows = PortraitLedger.accounts(ws)
        assertEquals(2, rows.size)
        assertEquals("本地", rows[0].model)
        assertFalse("本地特征：没外发", rows[0].sentOut)
        assertEquals("gemini-2.5-flash", rows[1].model)
        assertTrue("视觉描述：外发过", rows[1].sentOut)
    }

    @Test
    fun `账面没有图片字节_只有路径与文本`() = TempSandbox.use("zd597-ledger-nobytes") { dir ->
        val ws = File(dir, "ws").apply { mkdirs() }
        PortraitLedger.appendDraft(ws, draftOf())
        val text = PortraitLedger.file(ws).readText(Charsets.UTF_8)
        assertFalse("dataUrl 不许进账", text.contains("data:image"))
        assertFalse("base64 头不许进账", text.contains(";base64,"))
    }

    // ── 进卡判定（判据③④）──────────────────────────────────────────────

    private fun cardJson(existingAppearance: String? = null): JSONObject {
        val data = JSONObject()
            .put("name", "阿檀")
            .put("description", "")
        existingAppearance?.let {
            data.put("extensions", JSONObject().put(PortraitMerge.FIELD, it))
        }
        return JSONObject().put("spec", "chara_card_v3").put("data", data)
    }

    @Test
    fun `判据四负例_卡里已有外貌_拒写不覆盖`() {
        val v = PortraitMerge.apply(cardJson("旧外貌：金发"), "新外貌：黑发")
        assertTrue(v is PortraitMerge.Verdict.Refuse)
        assertEquals(PortraitMerge.REFUSE_HAS_APPEARANCE, (v as PortraitMerge.Verdict.Refuse).reason)
    }

    @Test
    fun `空白外貌视为没有_可以补`() {
        val card = cardJson()
        card.getJSONObject("data").put("extensions", JSONObject().put(PortraitMerge.FIELD, "   "))
        val v = PortraitMerge.apply(card, "新外貌")
        assertTrue(v is PortraitMerge.Verdict.Ok)
    }

    @Test
    fun `没有data的角色卡_拒_外貌是角色的属性`() {
        val worldbook = JSONObject().put("entries", JSONArray())
        val v = PortraitMerge.apply(worldbook, "外貌")
        assertTrue(v is PortraitMerge.Verdict.Refuse)
        assertEquals(PortraitMerge.REFUSE_NO_DATA, (v as PortraitMerge.Verdict.Refuse).reason)
    }

    @Test
    fun `进卡成功_写进extensions外观位_原对象不被改`() {
        val card = cardJson()
        val before = card.toString()
        val v = PortraitMerge.apply(card, "黑发红瞳")
        assertTrue(v is PortraitMerge.Verdict.Ok)
        val out = (v as PortraitMerge.Verdict.Ok).card
        assertEquals("黑发红瞳", out.getJSONObject("data").getJSONObject("extensions").getString(PortraitMerge.FIELD))
        assertEquals("传入对象一个字不动", before, card.toString())
    }

    @Test
    fun `v2卡没有extensions_也能补上`() {
        val v2 = JSONObject().put("data", JSONObject().put("name", "阿檀"))
        val v = PortraitMerge.apply(v2, "外貌")
        assertTrue(v is PortraitMerge.Verdict.Ok)
    }

    // ── merged 留痕（一份草稿只进一次卡）────────────────────────────────

    @Test
    fun `merged留痕_重复进卡可判`() = TempSandbox.use("zd597-ledger-merged") { dir ->
        val ws = File(dir, "ws").apply { mkdirs() }
        val d = PortraitLedger.appendDraft(ws, draftOf())
        assertTrue(PortraitLedger.mergedDraftIds(ws).isEmpty())
        PortraitLedger.appendMerged(ws, d.id, "卡/阿檀.json", 4_000L)
        assertEquals(setOf(d.id), PortraitLedger.mergedDraftIds(ws))
    }
}
