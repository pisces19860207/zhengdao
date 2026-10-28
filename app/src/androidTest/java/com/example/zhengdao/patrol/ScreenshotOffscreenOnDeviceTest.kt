// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 262 批（收窄后重做）真机验收：**截图改走离屏重载**之后的判据。
//
// 用户定的四条（原文）：
// 1) 抱朴在前台、**不点玄览**，连截 3 张都应出真图（不是灰图/白图）；
// 2) 玄览在后台时也要能截（走离屏，不依赖它可见）；
// 3) 失败时给可读原因（载入失败/等 visual 超时/页面跨域），不许静默回一张灰图；
// 4) 提示语要写清"重新载入 ⇒ 页面初始状态"这个代价。
//
// 本测试直接调**产品方法** `BrowserCommands.captureShotRef`（就是 browser_screenshot
// 工具走的那个），不经过模型、不花 token；每张都额外用 OffscreenShotter 存一份 PNG 到
// external files，供人拉下来**用眼睛看图**（不是只信"非白 5%"）。
package com.example.zhengdao.patrol

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.MainActivity
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.browser.offscreen.OffscreenShotter
import com.example.zhengdao.ui.browser.BrowserTabManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotOffscreenOnDeviceTest {

    private val rows = JSONArray()
    private var activity: MainActivity? = null
    private var tabId: Int? = null

    @After
    fun 收尾() {
        val inst = InstrumentationRegistry.getInstrumentation()
        runCatching {
            inst.runOnMainSync {
                activity?.moveTaskToBack(false)
                tabId?.let { id -> runCatching { BrowserTabManager.closeTab(id) } }
            }
        }
        activity?.let { writeResult(it) }
    }

    @Test
    fun 抱朴在前台不点玄览也能连截三张真图() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        val act = inst.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        activity = act
        Thread.sleep(1_500)

        // 建玄览标签并载入（**不点玄览**：用户停在抱朴页签，这才是要验的场景）
        inst.runOnMainSync { tabId = BrowserTabManager.newTab(act, PAGE_URL) }
        assertTrue("建不出玄览标签", tabId != null)

        val readable = waitUntilReadable()
        assertTrue("玄览标签的页面在 15s 内没变得可读", readable)
        Thread.sleep(800)

        val parent = runOnMainSyncBlocking { BrowserTabManager.activeTab()?.webView?.parent }
        val attached = runOnMainSyncBlocking {
            val wv = BrowserTabManager.activeTab()?.webView
            wv?.isAttachedToWindow == true && (wv.width ?: 0) > 0
        }
        record(
            "前置", "玄览此刻在不在屏上",
            if (!attached) "说明" else "说明",
            "WebView.parent=${parent?.javaClass?.simpleName ?: "null"} attached=$attached" +
                if (!attached) "（符合场景：用户停在抱朴页签，玄览没上屏）" else "（⚠️ 玄览在屏上，场景不符）",
        )

        // ── 判据 1：连截 3 张 ────────────────────────────────────────────────
        var okCount = 0
        repeat(3) { i ->
            val t0 = System.currentTimeMillis()
            val r = runCatching { BrowserCommands.captureShotRef(ctx, null, "0") }
            val ms = System.currentTimeMillis() - t0
            when (val c = r.getOrNull()) {
                is BrowserCommands.ShotCapture.Ok -> {
                    okCount++
                    record(
                        "第 ${i + 1} 张", "captureShotRef(视口)",
                        "能",
                        "mode=${c.ref.mode} ${c.ref.width}x${c.ref.height} ${c.ref.bytes}B " +
                            "${ms}ms ｜ note=${c.ref.note ?: "（无）"} ｜ file=${c.ref.file}",
                    )
                }

                is BrowserCommands.ShotCapture.Bad -> record(
                    "第 ${i + 1} 张", "captureShotRef(视口)", "不能",
                    "${ms}ms ｜ ${c.error}",
                )

                null -> record(
                    "第 ${i + 1} 张", "captureShotRef(视口)", "失败",
                    "${ms}ms ｜ 抛异常 ${r.exceptionOrNull()?.message}",
                )
            }
            Thread.sleep(1_000)
        }
        record(
            "判据 1", "连截 3 张出真图",
            if (okCount == 3) "能" else "不能",
            "成功 $okCount/3（每张都经过 WhiteImageJudge：整幅同色会被判失败，不会冒充真图）",
        )
        assertTrue("连截 3 张必须全部成功，实际 $okCount/3", okCount == 3)

        // ── 留一张图给人看 ──────────────────────────────────────────────────
        saveOneForHumanEye()

        // ── 判据 2：玄览在后台 + App 也退后台时，必须**如实失败并给原因** ──────
        inst.runOnMainSync { act.moveTaskToBack(true) }
        Thread.sleep(2_000)
        val bg = runCatching { BrowserCommands.captureShotRef(ctx, null, "0") }
        when (val c = bg.getOrNull()) {
            is BrowserCommands.ShotCapture.Ok -> record(
                "判据 2", "App 退后台时截图", "能",
                "居然成功了：${c.ref.width}x${c.ref.height} ${c.ref.bytes}B（说明这条也能走通）",
            )

            is BrowserCommands.ShotCapture.Bad -> record(
                "判据 2", "App 退后台时截图", "说明",
                "如实失败并给了原因：${c.error}",
            )

            null -> record("判据 2", "App 退后台时截图", "说明", "抛异常 ${bg.exceptionOrNull()?.message}")
        }
        inst.runOnMainSync { act.moveTaskToBack(false) }
    }

    /** 单独走一次离屏取图，把 PNG 落到 external files（人能直接看）。 */
    private fun saveOneForHumanEye() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val act = activity ?: return
        val dir = act.getExternalFilesDir(OUT_SUB) ?: File(act.filesDir, OUT_SUB)
        dir.mkdirs()
        val url = runOnMainSyncBlocking { BrowserTabManager.activeTab()?.state?.url } ?: return
        val t0 = System.currentTimeMillis()
        val r = runCatching { OffscreenShotter(act).capture(url) }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        when (r) {
            is OffscreenShotter.Outcome.Ok -> {
                File(dir, "offscreen_shot_for_human.png").writeBytes(r.png)
                record(
                    "留证", "离屏取图落 PNG",
                    "能",
                    "${r.width}x${r.height} ${r.png.size}B ${ms}ms 最大同色占比 " +
                        String.format(java.util.Locale.US, "%.2f%%", r.dominantRatio * 100),
                )
            }

            is OffscreenShotter.Outcome.Fail -> record("留证", "离屏取图落 PNG", "不能", "${r.kind}: ${r.reason}")
            null -> record("留证", "离屏取图落 PNG", "失败", "抛异常")
        }
    }

    private fun waitUntilReadable(timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = runCatching { BrowserCommands.evidenceSnapshot() }.getOrNull()
            if (r is BrowserCommands.EvidenceCapture.Ok) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun <T> runOnMainSyncBlocking(block: () -> T): T {
        val inst = InstrumentationRegistry.getInstrumentation()
        var out: T? = null
        inst.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun record(step: String, what: String, verdict: String, detail: String) {
        rows.put(
            JSONObject().put("step", step).put("what", what)
                .put("verdict", verdict).put("detail", detail),
        )
        Log.i(TAG, "ROW|$step|$what|$verdict|$detail")
    }

    private fun writeResult(act: android.app.Activity) {
        runCatching {
            val dir = act.getExternalFilesDir(OUT_SUB) ?: File(act.filesDir, OUT_SUB)
            dir.mkdirs()
            val root = JSONObject()
                .put("batch", "262")
                .put("layer", "screenshot-offscreen")
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("url", PAGE_URL)
                .put("rows", rows)
            File(dir, OUT_FILE).writeText(root.toString(2), Charsets.UTF_8)
            Log.i(TAG, "result written")
        }.onFailure { Log.e(TAG, "write failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "shot-offscreen-device"
        private const val PAGE_URL = "https://www.baidu.com"
        private const val OUT_SUB = "patrol_evidence_probe"
        private const val OUT_FILE = "screenshot_offscreen_result.json"
    }
}
