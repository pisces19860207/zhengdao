// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 判据②③的运行时半边：敏感行整条丢弃（夹具）、行键封闭集、去重。
// 页内源头闸的半边在 LearnBoardGateTest（源码断言）；这里是原生侧第二道闸的实跑证据。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ObserveRowsTest {

    // ── 夹具（页内脚本返回形状的微缩版：外层 {"result":…}，内层 {doc,seq,ev,pg}）─────

    private fun facesJson(
        tag: String = "BUTTON",
        name: String = "查询",
        inputType: String = "",
        inputName: String = "",
        placeholder: String = "",
    ): JSONObject = JSONObject()
        .put("tag", tag).put("role", "button").put("name", name).put("aria", "")
        .put("testid", "").put("id", "").put("inputName", inputName)
        .put("placeholder", placeholder).put("inputType", inputType).put("auto", "")
        .put("formLabel", "").put("xp", "/html/body/div[1]/" + tag.lowercase() + "[1]")
        .put("cls", JSONArray())

    /** 一条事件（a=kind；带 vs 表示输入事件）。 */
    private fun eventJson(
        a: String,
        seq: Int,
        el: JSONObject = facesJson(),
        vs: String? = null,
        doc: String = "doc1",
        url: String = "https://example.test/list",
        nc: Int = 10,
        t: Long = 1000L,
    ): JSONObject = JSONObject()
        .put("a", a).put("url", url).put("el", el).put("nc", nc).put("t", t).put("d", doc).put("s", seq)
        .also { if (vs != null) it.put("vs", vs) }

    private fun drainRaw(vararg events: JSONObject, page: JSONObject? = null, doc: String = "doc1"): String {
        val inner = JSONObject()
            .put("doc", doc)
            .put("seq", events.maxOfOrNull { it.optInt("s") } ?: 0)
            .put("ev", JSONArray(events.toList()))
        if (page != null) inner.put("pg", page)
        return JSONObject().put("result", inner.toString()).toString()
    }

    private fun pageJson(url: String = "https://example.test/list", nc: Int = 10, nodes: JSONArray = JSONArray()): JSONObject =
        JSONObject().put("url", url).put("title", "列表").put("nc", nc).put("nodes", nodes)

    private fun sessionIn(tmp: File, id: String = "1"): ObserveSession =
        ObserveSession(id, File(tmp, ObserveSession.fileNameFor(id))) { 10_000L }

    // ── 判据③：敏感行整条丢弃（夹具三连：type=password／name=验证码／autocomplete）────

    @Test
    fun `判据3_密码输入整条丢弃`() {
        TempSandbox.use("zd624-rows-pass") { tmp ->
            val s = sessionIn(tmp)
            val result = DrainResult.parse(
                drainRaw(
                    eventJson("type", 1, el = facesJson(tag = "INPUT", inputType = "password", name = "pw")),
                    eventJson("click", 2),
                ),
            )!!
            s.ingest(result)
            assertEquals("密码行不存在，普通行保留", 1, s.eventCount)
        }
    }

    @Test
    fun `判据3_验证码字段整条丢弃`() {
        TempSandbox.use("zd624-rows-otp") { tmp ->
            val s = sessionIn(tmp)
            val result = DrainResult.parse(
                drainRaw(
                    eventJson("type", 1, el = facesJson(tag = "INPUT", inputName = "sms_code_验证码", placeholder = "")),
                    eventJson("type", 2, el = facesJson(tag = "INPUT", inputType = "text", placeholder = "请输入验证码")),
                ),
            )!!
            s.ingest(result)
            assertEquals("两条都带敏感词 ⇒ 整会话 0 行", 0, s.eventCount)
        }
    }

    @Test
    fun `判据3_第二道闸认autocomplete敏感值`() {
        val f = ObserveFaces(
            tag = "INPUT", role = "textbox", name = "", aria = "", testid = "", id = "",
            inputName = "", placeholder = "", inputType = "text", auto = "one-time-code",
            formLabel = "", xp = "", cls = emptyList(),
        )
        assertTrue(f.isSensitive())
        val normal = f.copy(auto = "off")
        assertFalse(normal.isSensitive())
    }

    // ── 判据②：行键封闭集——事件行与身份面都没有放正文的第四列 ─────────────────────

    @Test
    fun `判据2_事件行键封闭集`() {
        val ev = ObserveEvent.fromJson(eventJson("type", 1, vs = "digits"))!!
        assertEquals(ObserveEvent.KEYS, ev.toJson().keys().asSequence().toSet())
        assertEquals(ObserveFaces.KEYS, ev.faces.toJson().keys().asSequence().toSet())
    }

    @Test
    fun `判据2_输入事件只落形状不落值`() {
        val ev = ObserveEvent.fromJson(eventJson("type", 1, vs = "digits"))!!
        assertEquals(ValueShape.DIGITS, ev.shape)
        val raw = ev.toJson().toString()
        assertFalse("行里不许出现任何值的字面通道", raw.contains("value"))
    }

    // ── 去重与页行 ────────────────────────────────────────────────────────────────

    @Test
    fun `同文档同序号只入一次`() {
        TempSandbox.use("zd624-rows-dedup") { tmp ->
            val s = sessionIn(tmp)
            val raw = drainRaw(eventJson("click", 1))
            s.ingest(DrainResult.parse(raw)!!)
            s.ingest(DrainResult.parse(raw)!!)
            assertEquals(1, s.eventCount)
        }
    }

    @Test
    fun `页行_照收含同事实心跳行_滑动封顶`() {
        TempSandbox.use("zd624-rows-page") { tmp ->
            // 会话甲：心跳行保留（缺证据靠它）
            val a = sessionIn(tmp, "1")
            a.ingest(DrainResult.parse(drainRaw(page = pageJson(nc = 10)))!!)
            a.ingest(DrainResult.parse(drainRaw(page = pageJson(nc = 10)))!!)
            a.ingest(DrainResult.parse(drainRaw(page = pageJson(nc = 12)))!!)
            val snapA = a.close() // close 冻结会话——封顶另起会话乙验
            assertEquals(3, snapA.pages.size)
            assertEquals(12, snapA.pages.last().nodeCount)
            // 会话乙：滑动封顶——塞 10 份只留最近 8 份
            val b = sessionIn(tmp, "2")
            for (i in 1..10) {
                b.ingest(DrainResult.parse(drainRaw(page = pageJson(nc = 100 + i)))!!)
            }
            assertEquals(ObserveSession.MAX_PAGES_PER_DOC, b.close().pages.size)
        }
    }

    @Test
    fun `敏感节点读回时身份字段被抹空`() {
        val node = ObserveNode.fromJson(
            JSONObject()
                .put("tag", "INPUT").put("role", "textbox").put("name", "登录密码").put("aria", "")
                .put("testid", "").put("id", "pwd").put("inputName", "pwd")
                .put("placeholder", "密码").put("inputType", "password").put("auto", "")
                .put("formLabel", "").put("xp", "input[1]").put("cls", JSONArray())
                .put("sens", true),
        )
        assertTrue(node.sensitive)
        assertEquals("敏感节点的身份面不出账", "", node.faces.name)
        assertEquals("", node.faces.placeholder)
    }

    @Test
    fun `解析坏串返回null不抛`() {
        assertNull(DrainResult.parse("not-json"))
        assertNotNull(DrainResult.parse(drainRaw(eventJson("click", 1))))
    }
}
