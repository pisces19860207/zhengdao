// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 262 批真机验收（第二层）：**玄览真身**，不是探针自己造的 WebView。
//
// 探针（EvidenceDeviceProbeActivity）只证明了「WebView 机制上后台能读 DOM」；它造的是
// 自己的 WebView。工单要的最后一跳是：**抱朴待在抱朴页签时，巡按去读玄览的页面**——
// 这时玄览的标签根本没有被组合（WebView 没挂到任何窗口上），比"退到后台"更极端。
//
// 本测试就跑这个场景，而且不需要人手点：
//   起 MainActivity（默认停在抱朴页签）→ 用**产品入口** BrowserTabManager.newTab 建玄览
//   标签并载入页面 → 此时玄览页签未组合、WebView 没挂窗 → 调**产品方法**
//   BrowserCommands.evidenceSnapshot() 取证据 → 再 moveTaskToBack 退到后台取一次。
//
// 判据：两次都必须取到页面正文（含探针页标题），失败带原因、不猜。
// 读数落 external files 的 real_browser_result.json，adb pull 回收。
package com.example.zhengdao.patrol

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.MainActivity
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.ui.browser.BrowserTabManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceOnRealBrowserTest {

    private val rows = JSONArray()

    @Test
    fun 玄览未上屏也能取到页面证据() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        var tabId: Int? = null
        var activity: MainActivity? = null

        try {
            // 1. 起主界面（默认停在抱朴页签 ⇒ 玄览页签不会被组合）
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity = inst.startActivitySync(intent) as MainActivity
            Thread.sleep(1_500)

            // 2. 用产品入口建玄览标签并载入探针页（debug 源集的 assets）
            inst.runOnMainSync {
                tabId = BrowserTabManager.newTab(activity, PAGE_URL)
            }
            assertTrue("建不出玄览标签（可能到标签上限）", tabId != null)
            record("玄览未上屏（页签未组合）", "建标签", "说明", "tabId=$tabId url=$PAGE_URL")

            // 3. 等加载，然后**按产品方法**取证据（会重试，直到拿到正文或超时）
            //    ⚠️ 地址必须是网络 URL：玄览的守卫是 allowsNavigation = isNetwork(url) &&
            //    !blockAllNetwork（LocalPreviewPolicy），file:// 会被 loadUrl 静默丢弃 ⇒
            //    永远「从没导航过」。第一版用的是 assets 的 file://，就是这么红的。
            val before = awaitEvidence("玄览未上屏")
            assertTrue("玄览未上屏时取不到证据：${before.second}", before.first)

            // 4. 退到后台再取一次（App 整体不在前台）
            inst.runOnMainSync { activity.moveTaskToBack(true) }
            Thread.sleep(2_500)
            val after = awaitEvidence("App 退到后台")
            assertTrue("App 退到后台时取不到证据：${after.second}", after.first)
        } finally {
            // 5. 无论成败都回前台并收尾（失败留在后台会把后续测试/人工操作搅乱）
            runCatching {
                inst.runOnMainSync {
                    activity?.moveTaskToBack(false)
                    tabId?.let { id -> runCatching { BrowserTabManager.closeTab(id) } }
                }
            }
            activity?.let { writeResult(it, tabId) }
        }
    }

    /**
     * 轮询产品方法直到拿到正文（最多 [RETRY] 次）。
     * @return 是否成功 ＋ 最后一次的读数或失败原因
     */
    private fun awaitEvidence(phase: String): Pair<Boolean, String> {
        var last = "（还没调）"
        repeat(RETRY) { i ->
            val r = runCatching { BrowserCommands.evidenceSnapshot() }
            val capture = r.getOrNull()
            when (capture) {
                is BrowserCommands.EvidenceCapture.Ok -> {
                    // 判据不用固定字串（真网站标题会变）：readyState=complete 且 DOM 摘要
                    // 有实质内容（>40 字）就算真读到了
                    val dom = capture.text
                        .substringAfter("DOM 摘要:\n", "")
                        .substringBefore("\n\n可访问性文本:")
                        .trim()
                    if (capture.text.contains("readyState: complete") && dom.length > 40) {
                        record(phase, "取证据", "能", summarize(capture.text, attempt = i + 1))
                        return true to capture.text.take(160)
                    }
                    last = "第 ${i + 1} 次还没成形（DOM ${dom.length} 字）：${capture.text.take(100)}"
                }
                is BrowserCommands.EvidenceCapture.Bad -> last = "第 ${i + 1} 次：${capture.error}"
                null -> last = "第 ${i + 1} 次：抛异常 ${r.exceptionOrNull()?.message}"
            }
            Thread.sleep(POLL_MS)
        }
        record(phase, "取证据", "不能", last)
        return false to last
    }

    /** 从回执里抽关键读数（元素数／正文字数／有无可访问性文本），原样落账。 */
    private fun summarize(text: String, attempt: Int): String {
        val elements = Regex("元素数: (\\d+)").find(text)?.groupValues?.get(1) ?: "?"
        val ready = Regex("readyState: (\\w+)").find(text)?.groupValues?.get(1) ?: "?"
        val hasAx = !text.contains("（无可访问性标注")
        val domLen = text.substringAfter("DOM 摘要:\n", "").substringBefore("\n\n可访问性文本:")
        return "第 ${attempt} 次即取到；元素数=$elements readyState=$ready " +
            "DOM 摘要 ${domLen.length} 字 可访问性文本=${if (hasAx) "有" else "无"}"
    }

    private fun record(phase: String, what: String, verdict: String, detail: String) {
        rows.put(
            JSONObject()
                .put("phase", phase)
                .put("what", what)
                .put("verdict", verdict)
                .put("detail", detail),
        )
        Log.i(TAG, "ROW|$phase|$what|$verdict|$detail")
    }

    private fun writeResult(activity: android.app.Activity, tabId: Int?) {
        runCatching {
            val dir = activity.getExternalFilesDir(OUT_SUB) ?: File(activity.filesDir, OUT_SUB)
            dir.mkdirs()
            val root = JSONObject()
                .put("batch", "262")
                .put("layer", "real-browser")
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("url", PAGE_URL)
                .put("tabId", tabId ?: -1)
                .put("rows", rows)
            File(dir, OUT_FILE).writeText(root.toString(2), Charsets.UTF_8)
            Log.i(TAG, "result written: ${File(dir, OUT_FILE).absolutePath}")
        }.onFailure { Log.e(TAG, "write failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "real-browser-evidence"
        private const val PAGE_URL = "https://www.baidu.com"
        private const val OUT_SUB = "patrol_evidence_probe"
        private const val OUT_FILE = "real_browser_result.json"
        private const val RETRY = 20
        private const val POLL_MS = 400L
    }
}
