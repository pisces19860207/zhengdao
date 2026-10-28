// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import com.example.zhengdao.testkit.TestAssume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 262 批第一步的内核测试（[EvidenceSnapshot] 零 Android 依赖，JVM 直测）。
 *
 * 五组：
 * ① **parse 正负例**——三段 `\u0001`／meta 七字段 `\u0002` 的解析；缺段/数字坏/截断位坏
 *    ⇒ null（工单「不许静默空」的解析侧口径：解不出来就如实报，不猜）；
 * ② **钳制**——JS 侧字符位（2000/4000）由脚本产出；Kotlin 侧 64KB **字节级**总闸
 *    （CJK 每字 3 字节，字符钳制挡不满，负例构造 CJK 大文本实测）；
 * ③ **render**——跨域如实计数、空 content 带原因、两级截断各有标记行；
 * ④ **JS 源码门禁**——不得 eval、分隔符在场、常量嵌进脚本；
 * ⑤ **node 求值自证**——DOM 替身下现场求值 [evidenceJsScript] 产出，
 *    [EvidenceSnapshot.parse] 能原样吃下（Kotlin 字符串里的 JS 语法错误在这里当场爆）。
 */
class EvidenceSnapshotTest {

    // ── ① parse ───────────────────────────────────────────────────────────

    @Test
    fun `parse 正常回包字段全对`() {
        val raw = "https://e.com/a\u0002标题\u0002123\u00022\u00021\u00020\u0002complete" +
            "\u0001h2#t 标题\nbutton 提交\n正文" + "\u0001button: 打开菜单"
        val r = EvidenceSnapshot.parse(raw)!!
        assertEquals("https://e.com/a", r.url)
        assertEquals("标题", r.title)
        assertEquals(123, r.elementCount)
        assertEquals(2, r.crossOriginFrames)
        assertTrue(r.domTruncated)
        assertFalse(r.axTruncated)
        assertEquals("complete", r.readyState)
        assertEquals("h2#t 标题\nbutton 提交\n正文", r.domSummary)
        assertEquals("button: 打开菜单", r.axText)
    }

    @Test
    fun `parse 负例缺段与数字坏一律 null`() {
        assertNull(EvidenceSnapshot.parse(null))
        assertNull(EvidenceSnapshot.parse(""))
        assertNull(EvidenceSnapshot.parse("只有一段"))
        assertNull(EvidenceSnapshot.parse("a\u0001b\u0001c\u0001d")) // 四段
        // meta 字段数错（六段）
        assertNull(EvidenceSnapshot.parse("a\u0002b\u00021\u00020\u00020\u00020\u0001dom\u0001ax"))
        // 元素数不是数字
        assertNull(EvidenceSnapshot.parse("u\u0002t\u0002x\u00020\u00020\u00020\u0002complete\u0001d\u0001a"))
        // 截断位不是 0/1
        assertNull(EvidenceSnapshot.parse("u\u0002t\u00021\u00020\u0002x\u00020\u0002complete\u0001d\u0001a"))
    }

    // ── ② 64KB 字节级总闸 ────────────────────────────────────────────────

    private fun bigResult(domChars: Int, axChars: Int): EvidenceSnapshot.Result =
        EvidenceSnapshot.Result(
            url = "https://e.com/", title = "t", readyState = "complete",
            elementCount = 1, crossOriginFrames = 0,
            domSummary = "页".repeat(domChars),
            axText = "访".repeat(axChars),
            domTruncated = false, axTruncated = false,
        )

    @Test
    fun `未超 64KB 原样放行不标 truncated`() {
        val c = EvidenceSnapshot.clampTotal(bigResult(1_000, 1_000))
        assertFalse(c.totalTruncated)
        assertEquals(1_000, c.result.domSummary.length)
    }

    @Test
    fun `负例_CJK 大文本超 64KB 字符钳制挡不满_总闸砍到字节预算内`() {
        // 300 行骨架＋2000 正文＋4000 AX 的 CJK 最坏 ~100KB —— 64KB 闸必须真砍
        val c = EvidenceSnapshot.clampTotal(bigResult(20_000, 10_000))
        assertTrue("总闸必须触发", c.totalTruncated)
        val total = c.result.domSummary.toByteArray(Charsets.UTF_8).size +
            c.result.axText.toByteArray(Charsets.UTF_8).size
        assertTrue("钳后总字节必须 ≤64KB（实测 $total）", total <= EvidenceSnapshot.MAX_TOTAL_BYTES)
        // 砍序：先 AX 到半闸，剩余给 DOM —— DOM（骨架＋正文）是取证主体
        assertTrue(
            "AX 砍后不超半闸（实测 ${c.result.axText.toByteArray(Charsets.UTF_8).size}）",
            c.result.axText.toByteArray(Charsets.UTF_8).size <= EvidenceSnapshot.MAX_TOTAL_BYTES / 2,
        )
    }

    @Test
    fun `截断落在 UTF-8 字符边界_不留半个字`() {
        val c = EvidenceSnapshot.clampTotal(bigResult(30_000, 0))
        assertTrue(c.totalTruncated)
        // 截出的串必须还能按 UTF-8 解回（ByteArray→String 不抛即合法；lone surrogate
        // 才会变 '?'，而「页」是 BMP 字符，substring 按码元切不出半个 BMP 字）
        assertEquals("页", c.result.domSummary.substring(0, 1))
    }

    // ── ③ render ─────────────────────────────────────────────────────────

    @Test
    fun `render 跨域如实计数且空内容带原因`() {
        val out = EvidenceSnapshot.render(
            EvidenceSnapshot.Clamped(
                EvidenceSnapshot.Result(
                    url = "", title = "", readyState = "complete",
                    elementCount = 0, crossOriginFrames = 3,
                    domSummary = "", axText = "",
                    domTruncated = false, axTruncated = false,
                ),
                totalTruncated = false,
            ),
        )
        assertTrue("跨域计数必须在场", out.contains("跨域 iframe 3 个"))
        assertTrue("空 url 不许静默", out.contains("url: （空）"))
        assertTrue("空 DOM 摘要必须带原因", out.contains("页面可能还在加载，或可见主体在跨域 iframe 里"))
        assertTrue("空 AX 必须带原因", out.contains("无可访问性标注"))
    }

    @Test
    fun `render 两级截断各有标记行`() {
        val out = EvidenceSnapshot.render(
            EvidenceSnapshot.Clamped(
                EvidenceSnapshot.Result(
                    url = "u", title = "t", readyState = "complete",
                    elementCount = 1, crossOriginFrames = 0,
                    domSummary = "骨架", axText = "标注",
                    domTruncated = true, axTruncated = true,
                ),
                totalTruncated = true,
            ),
        )
        assertTrue(out.contains("DOM 正文已截到前 2000 字"))
        assertTrue(out.contains("可访问性文本已截到前 4000 字"))
        assertTrue(out.contains("64KB 总闸"))
    }

    // ── ④ JS 源码门禁 ────────────────────────────────────────────────────

    @Test
    fun `证据脚本不 eval_分隔符与常量在场`() {
        val s = EvidenceSnapshot.evidenceJsScript()
        assertFalse("不得 eval（页面 CSP unsafe-eval 会拦，与 execJS 同一条纪律）", s.contains("eval("))
        assertTrue("段分隔符 \\u0001 必须在场", s.contains("\\u0001"))
        assertTrue("字段分隔符 \\u0002 必须在场", s.contains("\\u0002"))
        assertTrue("2000 字钳制常量必须嵌进脚本", s.contains("DOMC=2000"))
        assertTrue("4000 字钳制常量必须嵌进脚本", s.contains("AXC=4000"))
        assertTrue("必须取 textContent（渲染无关）", s.contains("textContent"))
        assertFalse("不得用 innerText（要排版，离屏会拿空——本批可行性的根基）", s.contains("innerText"))
    }

    // ── ⑤ node 求值自证 ─────────────────────────────────────────────────

    @Test
    fun `JS 引擎下证据脚本对 DOM 替身求值_产出可被 parse 原样吃下`() {
        val probe = probeWithNode(EvidenceSnapshot.evidenceJsScript(), domStubPreamble)
        assertEquals("脚本必须无错求值", 0, probe.exitCode)
        val raw = probe.value()
        val r = EvidenceSnapshot.parse(raw)
        assertTrue("产出必须能被解析器吃下（拿到：${raw.take(120)}）", r != null)
        r!!
        assertEquals("https://example.com/x?a=1", r.url)
        assertEquals("页面标题", r.title)
        assertEquals("complete", r.readyState)
        assertEquals(3, r.elementCount)
        assertEquals(1, r.crossOriginFrames)
        assertFalse(r.domTruncated)
        assertFalse(r.axTruncated)
        // 骨架行：标签＋id＋折叠短文本；正文折叠换行；AX 首条吃 aria-label、第二条退 textContent
        assertTrue("骨架行形态", r.domSummary.contains("h2#t 标题 文本"))
        assertTrue("正文折叠空白", r.domSummary.contains("正文第一行 第二行"))
        assertTrue("AX 吃 aria-label", r.axText.contains("button: 打开菜单"))
        assertTrue("AX 退 textContent", r.axText.contains("button: 提交"))
        assertFalse("lab 全空的元素跳过", r.axText.contains("img"))
        assertEquals("AX 恰两条（去重＋空跳过）", 2, r.axText.split('\n').size)
    }

    @Test
    fun `JS 引擎下空 body 页面产出仍可解析_空段如实带回`() {
        val probe = probeWithNode(EvidenceSnapshot.evidenceJsScript(), domEmptyPreamble)
        assertEquals(0, probe.exitCode)
        val r = EvidenceSnapshot.parse(probe.value())
        assertTrue(r != null)
        r!!
        assertEquals(0, r.crossOriginFrames)
        assertEquals("", r.domSummary)
        assertEquals("", r.axText)
    }

    // ── node 探针（照 JsEmbedTest 同构：vm.runInThisContext 求值完成值） ────

    private fun probeWithNode(scriptText: String, preamble: String = ""): JsProbe {
        val node = nodeExecutable()
        TestAssume.externalTool(node != null, "node")
        val exe = requireNotNull(node)
        val dir = java.nio.file.Files.createTempDirectory("zd-evidence-jsprobe").toFile()
        try {
            java.io.File(dir, "case.js").writeText(preamble + "\n" + scriptText + "\n")
            val jsFile = java.io.File(dir, "probe.js")
            jsFile.writeText(
                "const fs=require('fs'),vm=require('vm'),path=require('path');\n" +
                    "let v;\n" +
                    "try{v=vm.runInThisContext(fs.readFileSync(path.join(__dirname,'case.js'),'utf8'))}\n" +
                    "catch(e){process.stderr.write(String((e&&e.stack)||e));process.exit(1)}\n" +
                    "process.stdout.write('" + NODE_MARKER + "'+String(v+'" + NODE_END + "'));\n",
            )
            val p = ProcessBuilder(exe, jsFile.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            return JsProbe(scriptText, out, if (done) p.exitValue() else -1)        } finally {
            dir.deleteRecursively()
        }
    }

    private fun nodeExecutable(): String? {
        if (nodeProbeResolved) return nodeProbeExe
        nodeProbeResolved = true
        val exe = if (java.io.File.separatorChar == '\\') "node.exe" else "node"
        nodeProbeExe = try {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            val ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            if (ok) exe else null
        } catch (_: Exception) {
            null
        }
        return nodeProbeExe
    }

    /**
     * DOM 替身：querySelectorAll 按选择器回固定替身集合（取值路径与真 WebView 一致：
     * getAttribute/textContent/id/tagName）；iframe 一个 contentDocument=null（跨域）、
     * 一个可访问（同源，不计）。两个探针共用。
     */
    private val domStubPreamble = """
        globalThis.location={href:'https://example.com/x?a=1'};
        globalThis.document={
          title:'页面标题', readyState:'complete',
          body:{textContent:'  正文第一行\n第二行  '},
          querySelectorAll:function(sel){
            if(sel.indexOf('h1,')===0) return [
              {tagName:'H2', id:'t', textContent:' 标题 文本 ', getAttribute:function(){return null}},
              {tagName:'BUTTON', id:'', textContent:'提交', getAttribute:function(){return null}}
            ];
            if(sel.indexOf('[aria-label]')===0) return [
              {tagName:'BUTTON', value:undefined,
               getAttribute:function(k){return k==='aria-label'?'打开菜单':null},
               textContent:'应被 aria 覆盖的文本'},
              {tagName:'IMG', value:undefined,
               getAttribute:function(k){return null}, textContent:''},
              {tagName:'BUTTON', value:undefined,
               getAttribute:function(k){return null}, textContent:'提交'}
            ];
            if(sel==='iframe') return [{contentDocument:null},{contentDocument:{}}];
            if(sel==='*') return [{},{},{}];
            return [];
          }
        };
    """.trimIndent()

    /** 空 body／零元素的页面（还原「还没内容」的取数形态）。 */
    private val domEmptyPreamble = """
        globalThis.location={href:'about:blank'};
        globalThis.document={
          title:'', readyState:'loading', body:{textContent:''},
          querySelectorAll:function(){return []}
        };
    """.trimIndent()

    private class JsProbe(val scriptText: String, val output: String, val exitCode: Int) {
        fun value(): String =
            // 回包（骨架/AX 段）自身含真实换行 ⇒ 不许按 '\n' 截断（那是 262 批实测踩过的坑：
            // 截在第一条骨架行 ⇒ parse 判缺段假红）；结束标记由 probe.js 追加在完成值尾部。
            output.substringAfter(NODE_MARKER, "").substringBefore(NODE_END)
    }

    private companion object {
        const val NODE_MARKER = "__ZD_PROBE__"
        const val NODE_END = "__ZD_PROBE_END__"
        var nodeProbeResolved = false
        var nodeProbeExe: String? = null
    }
}
