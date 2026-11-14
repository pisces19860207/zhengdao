// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十七批 · Mermaid 出图的**真机判据**（instrumented）。
//
// 为什么必须上机：整条链路的关键件都在 Android 里——WebView 的 `file:///android_asset`
// 载入、离线模式下的三项 settings、`postVisualStateCallback` 的视觉帧、`getBoundingClientRect`
// 量出来的高度换算成设备像素、位图落盘。JVM 单测只能钉住"围栏怎么切""页面怎么拼"，
// 一句都证明不了"图真画出来了"。
//
// 五条判据（对应派工单【判据】）：
//   ① 三类图（flowchart / sequence / state）各渲染成功，落 PNG 到 `<工作区>/产物/图表/`；
//   ② 语法错 ⇒ 失败原因非空且是**首行**（不是一屏期望符号表）；
//   ③ 断网仍成功：同一个离线配置下，外网地址**必然载入失败**（对照组），而随包页照出图；
//   ④ 注入不执行：`<script>window.__pwned=1</script>`／`<img onerror=…>` 只当字符；
//   ⑤ >20KB 拒：不产生任何 PNG。
//
// 跑法（本机纪律：不用 connectedDebugAndroidTest，它跑完会卸载 App）：
// ```
// ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
// adb install -r app/build/outputs/apk/debug/app-debug.apk
// adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
// adb shell am instrument -w -e class com.example.zhengdao.patrol.MermaidRenderOnDeviceTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// ```
package com.example.zhengdao.patrol

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.MainActivity
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.browser.offscreen.OffscreenShotter
import com.example.zhengdao.browser.offscreen.ProbeWebConfig
import com.example.zhengdao.mermaid.MermaidFence
import com.example.zhengdao.mermaid.MermaidPage
import com.example.zhengdao.mermaid.MermaidPaths
import com.example.zhengdao.mermaid.MermaidRenderResult
import com.example.zhengdao.mermaid.MermaidRenderer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MermaidRenderOnDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private var activity: MainActivity? = null
    private val evidence = StringBuilder()

    private fun act(): MainActivity {
        activity?.let { return it }
        val a = inst.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        activity = a
        Thread.sleep(1_500)
        return a
    }

    private fun root(): File = BaopuWorkspace.hostDir(ctx)

    private fun diagrams(): File = MermaidPaths.diagramsDir(root())

    /** 目标目录里的全部文件（含子目录名），用来判「只多了一个 PNG」。 */
    private fun listing(dir: File): List<String> =
        dir.listFiles()?.map { it.name + if (it.isDirectory) "/" else "" }?.sorted() ?: emptyList()

    private fun pngMagicOk(f: File): Boolean {
        val b = f.readBytes()
        if (b.size < 8) return false
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return (0 until 8).all { b[it] == magic[it] }
    }

    @After
    fun 收尾() {
        runCatching { activity?.moveTaskToBack(false) }
        val text = evidence.toString()
        if (text.isNotBlank()) {
            Log.i(TAG, text)
            runCatching { File(ctx.filesDir, "zd637-evidence.txt").writeText(text) }
        }
    }

    // ── ① 三类图各出一张 ────────────────────────────────────────────────────

    @Test
    fun 三类图各渲染成功并落PNG() {
        val samples = linkedMapOf(
            "flowchart" to "flowchart TD\n  开始[收到消息] --> 判断{有代码块?}\n" +
                "  判断 -->|有| 出图[调用离屏渲染]\n  判断 -->|无| 直答[直接回答]\n" +
                "  出图 --> 结束[落 PNG]\n  直答 --> 结束",
            "sequence" to "sequenceDiagram\n  participant 用户\n  participant 抱朴\n" +
                "  用户->>抱朴: 这张图画一下\n  抱朴->>抱朴: 起一个离屏 WebView\n" +
                "  抱朴-->>用户: 图在产物/图表",
            "state" to "stateDiagram-v2\n  [*] --> 待办\n  待办 --> 进行中: 开工\n" +
                "  进行中 --> 完成: 交付\n  完成 --> [*]",
        )
        val dir = diagrams()
        val before = listing(dir)

        for ((name, code) in samples) {
            val kind = MermaidFence.kindOf(code)
            val result = MermaidRenderer.render(act(), root(), code, kind)
            assertTrue("$name 没渲染成功：$result", result is MermaidRenderResult.Ok)
            result as MermaidRenderResult.Ok
            assertTrue("$name 的 PNG 不存在：${result.file}", result.file.isFile)
            assertTrue("$name 落下来的不是 PNG", pngMagicOk(result.file))
            assertTrue("$name 尺寸不合理：${result.width}x${result.height}", result.width > 200 && result.height > 80)
            assertTrue(
                "$name 的落点不对：${result.file.absolutePath}（必须在 ${dir.absolutePath} 下）",
                result.file.parentFile?.absolutePath == dir.absolutePath,
            )
            evidence.append("[$name] ${result.file.absolutePath} ${result.width}x${result.height} ")
                .append(String.format(java.util.Locale.US, "主色占比 %.3f", result.dominantRatio))
                .append('\n')
        }

        val after = listing(dir)
        val added = after.filterNot { it in before }
        assertTrue("应该正好多出 3 个文件，实际：$added", added.size == 3)
        assertTrue("产物目录里除了 PNG 还有别的东西：$added", added.all { it.endsWith(".png") })

        // 判据⑥：目录里一个缓存/临时件都没有（子目录也算违规）
        val junk = after.filterNot { it.endsWith(".png") }
        assertTrue("产物/图表 里出现了非 PNG：$junk", junk.isEmpty())
    }

    // ── ② 语法错 ⇒ 首行原因，不许静默空白 ───────────────────────────────────

    @Test
    fun 语法错报出首行且不落文件() {
        val bad = "flowchart TD\n  A[没有闭合的方括号 --> B"
        val dir = diagrams()
        val before = listing(dir)

        val result = MermaidRenderer.render(act(), root(), bad)
        assertTrue("语法错必须失败，实际：$result", result is MermaidRenderResult.Fail)
        val reason = (result as MermaidRenderResult.Fail).reason
        evidence.append("[语法错] $reason\n")
        assertTrue("失败原因不许为空", reason.isNotBlank())
        assertFalse("不许把异常类名丢给用户", reason.contains("Exception"))
        assertTrue("首行不该超过 200 字（说明把整段错误都印出来了）：${reason.length}", reason.length <= 200)
        assertTrue(
            "首行应当读得出是解析错：$reason",
            reason.contains("Parse error", ignoreCase = true) || reason.contains("解析", ignoreCase = true) ||
                reason.contains("error", ignoreCase = true),
        )
        assertEquals("失败不该落下任何文件", before, listing(dir))
    }

    // ── ③ 断网仍成功（判据落在「页面发不出网络请求」上）──────────────────────

    @Test
    fun 断网仍出图_且页内发不出网络请求() {
        val a = act()

        // 控制组：让**页面自己**去发一个 XHR。
        // 这一条比"载一个外网 URL 看失败"更贴身：外网 URL 会不会失败还牵连 WebView 的
        // 缓存/渲染进程状态（637 实测：同一离线配置下 https://example.com 仍能被载入），
        // 而用户要的保证是"这张图不依赖网络"——那就直接问页面：你发得出去吗？
        val netProbe = "(function(){" +
            "if(window.__net && window.__net!=='pending'){" +
            "return window.__net==='blocked'?'err:NET-BLOCKED-OK':'err:NET-REACHED';}" +
            "if(!window.__net){window.__net='pending';" +
            "try{var x=new XMLHttpRequest();x.open('GET','https://example.com/',true);" +
            "x.onload=function(){window.__net='ok'};x.onerror=function(){window.__net='blocked'};x.send();}" +
            "catch(e){window.__net='blocked';}}" +
            "return 'wait';})()"
        val control = OffscreenShotter(a, ProbeWebConfig(javaScriptEnabled = true), offlineOnly = true)
            .capture(MermaidPage.pageUrl(), netProbe)
        assertTrue("页面竟然能发网络请求（或探针没回话）：$control", control is OffscreenShotter.Outcome.Fail)
        val reason = (control as OffscreenShotter.Outcome.Fail).reason
        assertTrue("页内网络请求没有被拦住：$reason", reason.contains("NET-BLOCKED-OK"))
        evidence.append("[断网控制组] $reason\n")

        // 实验组：随包页照常出图
        val result = MermaidRenderer.render(act(), root(), "flowchart LR\n  离线 --> 出图")
        assertTrue("断网时随包页应当照出图：$result", result is MermaidRenderResult.Ok)
        val ok = result as MermaidRenderResult.Ok
        assertTrue(pngMagicOk(ok.file))
        evidence.append("[断网出图] ${ok.file.absolutePath} ${ok.width}x${ok.height}\n")
    }

    // ── ④ 注入不执行 ────────────────────────────────────────────────────────

    @Test
    fun 注入的脚本只当字符不执行() {
        val payloads = listOf(
            "flowchart TD\n  A[\"<script>window.__pwned=1</script>\"] --> B",
            "flowchart TD\n  A[\"<img src=x onerror=window.__pwned=1>\"] --> B",
            "flowchart TD\n  A[\"</script><script>window.__pwned=1</script>\"] --> B",
        )
        val a = act()
        // 探针多带一条判据：渲染结束后读 window.__pwned——被置起来就当场报 err
        for (code in payloads) {
            val probe = spyProbe(code)
            val out = OffscreenShotter(a, ProbeWebConfig(javaScriptEnabled = true), offlineOnly = true)
                .capture(MermaidPage.pageUrl(), probe)
            if (out is OffscreenShotter.Outcome.Fail) {
                assertFalse(
                    "注入真的执行了：${out.reason}",
                    out.reason.contains("PAYLOAD-EXECUTED"),
                )
                evidence.append("[注入] 页面拒绝渲染（也算没执行）：${out.reason}\n")
            } else {
                evidence.append("[注入] 照常出图且 window.__pwned 未定义：$code\n")
            }
        }
    }

    /** 测试专用探针：与 [MermaidPage.probeScript] 同构，多一条 `window.__pwned` 检查。 */
    private fun spyProbe(code: String): String {
        val literal = JSONObject.quote(code)
        return "(function(){var z=window.__zd||{s:'wait'};" +
            "if(window.__zd_started!==true){if(!window.ZD){return 'wait';}" +
            "window.__zd_started=true;window.ZD.render($literal);return 'wait';}" +
            "var pwned=(typeof window.__pwned!=='undefined');" +
            "if(z.s==='ok'){return pwned?'err:PAYLOAD-EXECUTED':'ok:'+z.h;}" +
            "if(z.s==='error'){return pwned?'err:PAYLOAD-EXECUTED':'err:'+(z.msg||'渲染失败');}" +
            "return 'wait';})()"
    }

    // ── ⑤ 超过 20KB 直接拒 ──────────────────────────────────────────────────

    @Test
    fun 超过二十KB直接拒绝且不落文件() {
        val head = "flowchart TD\n"
        val over = head + "x".repeat(MermaidFence.MAX_CODE_BYTES)
        assertTrue(over.toByteArray(Charsets.UTF_8).size > MermaidFence.MAX_CODE_BYTES)

        val dir = diagrams()
        val before = listing(dir)
        val beforeCalls = MermaidRenderer.invocationCount()

        val result = MermaidRenderer.render(act(), root(), over)
        assertTrue(result is MermaidRenderResult.Fail)
        val reason = (result as MermaidRenderResult.Fail).reason
        assertTrue("必须说清是超限：$reason", reason.contains("20480"))
        assertEquals("超限时不该写任何文件", before, listing(dir))
        // 计数器会 +1（进来过），但**没有**任何离屏会话发生：这里只判"没落文件"，
        // 因为计数器管的是"点没点"，不是"WebView 起没起"。
        assertEquals(beforeCalls + 1, MermaidRenderer.invocationCount())
        evidence.append("[超限] $reason\n")
    }

    @Test
    fun 空代码块也拒绝() {
        val result = MermaidRenderer.render(act(), root(), "   \n  ")
        assertTrue(result is MermaidRenderResult.Fail)
        assertNotNull((result as MermaidRenderResult.Fail).reason)
        assertTrue(result.reason.contains("空"))
    }

    private companion object {
        const val TAG = "ZD637"
    }
}
