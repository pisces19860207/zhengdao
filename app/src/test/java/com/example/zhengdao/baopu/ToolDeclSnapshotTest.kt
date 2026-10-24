// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.7：运行期「工具声明快照」（2026-10-24 用户点头加的第 7 件）。
//
// 这一行日志是「下次别再烧一小时真机」的开关：抱朴说「工具无法调用」时，靠它
// 四选一——① 压根没发出去（tools.count=0）② 发出去了但形状被改（schema.digest
// 与预期不符）③ 被过滤（filtered 非 none）④ 被预算裁掉（budget 非 0）。
//
// 判据（派工单 §1.7）：
// - 同一会话同一组工具连续两次请求，tools.digest 相同；
// - 把某个工具组关掉 ⇒ filtered 多一条且 tools.digest 变；
// - 改一行 sanitizeSchema ⇒ schema.digest 必变（本条用「被剥的关键词看不见、
//   保留的关键词看得见」这组锋利对照钉住：digest 是**转换后**形状的哈希）；
// - 只记哈希与名单摘要，不记参数值。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.wire.ToolDeclSnapshot
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDeclSnapshotTest {

    private fun p(protocol: LlmProtocol) = Provider(
        id = "ch-188",
        label = "ch-188",
        protocol = protocol,
        baseUrl = "https://snapshot.test/v1",
        model = "m",
        apiKey = "k",
    )

    /** 一个工具、一个必填参数（OpenAI 形状——注册表的产出形状）。 */
    private fun tool(
        name: String,
        extraParams: JSONObject? = null,
    ): org.json.JSONArray {
        val params = JSONObject().put("type", "object")
        if (extraParams != null) {
            for (k in extraParams.keys()) params.put(k, extraParams.get(k))
        }
        return org.json.JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("description", "读文件")
                        .put(
                            "parameters",
                            params.put(
                                "properties",
                                JSONObject().put("path", JSONObject().put("type", "string")),
                            ),
                        ),
                ),
        )
    }

    private fun digestOf(line: String, key: String): String =
        Regex("$key=([0-9a-f]+)").find(line)!!.groupValues[1]

    // ── 同一组工具：digest 稳定 ───────────────────────────────────────

    @Test
    fun `同一组工具连续两次请求_tools_digest相同_整行相同`() {
        val a = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), tool("read_file"))
        val b = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), tool("read_file"))
        assertEquals(a, b)
        assertTrue(a.startsWith("[tool-decls] "))
    }

    @Test
    fun `工具名单变_digest就变_被过滤被截断都藏不住`() {
        val one = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), tool("read_file"))
        val two = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), tool("write_file"))
        assertTrue("名单变了 tools.digest 必须变", digestOf(one, "tools.digest") != digestOf(two, "tools.digest"))
        // 多一个工具也算变（截断/漏发）
        val both = org.json.JSONArray()
            .put(tool("read_file").getJSONObject(0))
            .put(tool("write_file").getJSONObject(0))
        val twoTools = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), both)
        assertEquals("tools.count 要数得出条数", "2", twoTools.substringAfter("tools.count=").substringBefore(" "))
        assertTrue(digestOf(one, "tools.digest") != digestOf(twoTools, "tools.digest"))
    }

    // ── schema.digest 是**转换后**形状的哈希（改转换逻辑必变）─────────

    @Test
    fun `Gemini_schema_digest看不见被剥的关键词_看得见保留的关键词`() {
        val withDropped = tool("read_file", JSONObject().put("additionalProperties", false))
        val without = tool("read_file")
        val a = ToolDeclSnapshot.of(p(LlmProtocol.GEMINI_NATIVE), withDropped)
        val b = ToolDeclSnapshot.of(p(LlmProtocol.GEMINI_NATIVE), without)
        // additionalProperties 被 sanitizeSchema 剥掉 ⇒ 转换后同形 ⇒ digest 相同。
        // 这条证明 digest 取自「转换后」而不是「原始 tools」——改一行白名单它必变。
        assertEquals(
            "被剥的关键词在 digest 里必须看不见（否则说明 digest 取的是原始形状）",
            digestOf(b, "schema.digest"),
            digestOf(a, "schema.digest"),
        )
        // 保留的关键词（description 之外再取一个 format）看得见
        val withKept = tool("read_file", JSONObject().put("format", "uri"))
        val c = ToolDeclSnapshot.of(p(LlmProtocol.GEMINI_NATIVE), withKept)
        assertTrue("保留的关键词必须改变 digest", digestOf(b, "schema.digest") != digestOf(c, "schema.digest"))
    }

    @Test
    fun `OpenAI不转换_原始形状即线上形状_两个digest口径如实不同`() {
        val withDropped = tool("read_file", JSONObject().put("additionalProperties", false))
        val without = tool("read_file")
        val a = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), withDropped)
        val b = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), without)
        assertTrue("OpenAI 档不转换 ⇒ 原始形状不同 digest 就不同", digestOf(a, "schema.digest") != digestOf(b, "schema.digest"))
    }

    // ── 四问定位：四个场景各答一问 ───────────────────────────────────

    @Test
    fun `四问一_压根没发出去_tools_count为0`() {
        val line = ToolDeclSnapshot.of(p(LlmProtocol.GEMINI_NATIVE), org.json.JSONArray())
        assertTrue(line.contains("tools.count=0"))
    }

    @Test
    fun `四问二_形状被改_schema_digest与上次不同`() {
        val before = ToolDeclSnapshot.of(p(LlmProtocol.GEMINI_NATIVE), tool("read_file"))
        // 模拟「转换逻辑被改」：同一个工具，schema 里多一个**会被保留**的关键词
        val after = ToolDeclSnapshot.of(
            p(LlmProtocol.GEMINI_NATIVE),
            tool("read_file", JSONObject().put("description", "读文件（新版声明）")),
        )
        assertTrue(
            "形状变了必须一眼看出来（这一行就是「改了转换没人知道」的防线）",
            digestOf(before, "schema.digest") != digestOf(after, "schema.digest"),
        )
    }

    @Test
    fun `四问三_被过滤_filtered非none且点名原因`() {
        val line = ToolDeclSnapshot.of(
            p(LlmProtocol.OPENAI_COMPAT),
            tool("read_file"),
            filtered = "read_file=组未解锁;draw_chart=用户关闭",
        )
        assertTrue(line.contains("filtered=read_file=组未解锁;draw_chart=用户关闭"))
        assertTrue("没被过滤时要写 none", ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), tool("read_file")).contains("filtered=none"))
    }

    @Test
    fun `四问四_被预算裁_budget非0`() {
        val line = ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), tool("read_file"), budgetCut = 3)
        assertTrue(line.contains("budget=3"))
        // 当前没有任何裁剪机制 ⇒ 默认 0（字段先立住，答案如实是「没裁」）
        assertTrue(ToolDeclSnapshot.of(p(LlmProtocol.OPENAI_COMPAT), tool("read_file")).contains("budget=0"))
    }

    // ── 隐私：只记哈希与名单摘要 ─────────────────────────────────────

    @Test
    fun `不记参数值_只有哈希与名单`() {
        val line = ToolDeclSnapshot.of(p(LlmProtocol.GEMINI_NATIVE), tool("read_file"))
        assertFalse("不许出现 schema 正文", line.contains("properties"))
        assertFalse("不许出现参数值", line.contains("\"path\""))
        assertFalse("不许出现描述正文", line.contains("读文件"))
        // 通道与协议是定位必需的两列
        assertTrue(line.contains("channel=ch-188"))
        assertTrue(line.contains("protocol=GEMINI_NATIVE"))
    }

    // ── 源码门禁：引擎每次请求前都记 ─────────────────────────────────

    @Test
    fun `源码门禁_RunEngine每次请求前记快照`() {
        val f = File("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        val code = (if (f.isFile) f else File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"))
            .readText().lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
        val toolsAt = code.indexOf("val tools = when (provider.protocol)")
        assertTrue("找不到 tools 组装点", toolsAt >= 0)
        val snapAt = code.indexOf("ToolDeclSnapshot.of(", toolsAt)
        assertTrue("tools 组装后没有快照日志（§1.7 没接上）", snapAt >= 0)
        val streamAt = code.indexOf("streamRound(sessionId", toolsAt)
        assertTrue("找不到流式调用点", streamAt >= 0)
        assertTrue("快照必须在发请求**之前**（之后记就等于没记）", snapAt < streamAt)
        assertTrue("filtered 列必须由注册表的同一判据链算", code.contains("registry.filteredLine(sessionId)"))
    }
}
