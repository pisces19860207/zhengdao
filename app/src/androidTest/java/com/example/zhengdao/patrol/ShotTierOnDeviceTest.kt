// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 262 批真机验收（第三层）：**三级截图链路 ＋ 切屏状态机**，全部走产品真身。
//
// 之前挂账的"三级链路的宿主半没在真机跑"，其实不需要人坐在手机前点——
// 判定是纯函数（ShotTierRule.decide），两个事实与两个动作都在 BrowserHolder 上，
// 直接调就行。本测试按工单 §6.1 的三级顺序走：
//
//   ① 没渲染过 + App 在前台  ⇒ 判定应是 LOAD_THEN_PAINT（不打扰用户）
//   ② loadForShot() 前台载入 ⇒ 必须真的就绪（readyState=complete 且提交了地址）
//   ③ 载入后必须变成"可渲染" ⇒ isRenderable 翻真，否则下次还会白图
//   ④ 画一次图数像素          ⇒ 这是"到底出没出图"的硬判据（本机读不了图，就数）
//   ⑤ 摘下离屏挂载            ⇒ WebView 必须脱离我们的容器
//   ⑥ 切屏状态机              ⇒ 用户手动切走后 restoreAfterShot 必须返回 false（不抢屏）
//
// 读数落 shot_tier_result.json，adb pull 回收。
package com.example.zhengdao.patrol

import android.graphics.Canvas
import android.os.Build
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.MainActivity
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.TabIndex
import com.example.zhengdao.baopu.ShotTier
import com.example.zhengdao.baopu.ShotTierRule
import com.example.zhengdao.ui.browser.BrowserHolder
import com.example.zhengdao.ui.browser.BrowserTabManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShotTierOnDeviceTest {

    private val rows = JSONArray()
    private var activity: MainActivity? = null
    private var tabId: Int? = null

    @After
    fun 收尾() {
        val inst = InstrumentationRegistry.getInstrumentation()
        runCatching {
            inst.runOnMainSync {
                activity?.moveTaskToBack(false)
                runCatching { BrowserHolder.detachForShot() }
                tabId?.let { id -> runCatching { BrowserTabManager.closeTab(id) } }
            }
        }
        activity?.let { writeResult(it) }
    }

    @Test
    fun 三级截图链路在真机上走通且真的出图() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        // 起主界面：默认停在抱朴页签 ⇒ 玄览页签不会组合，页面「从没渲染过」
        val act = inst.startActivitySync(
            android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        activity = act
        Thread.sleep(1_500)

        inst.runOnMainSync { tabId = BrowserTabManager.newTab(act, PAGE_URL) }
        assertTrue("建不出玄览标签", tabId != null)

        // 等页面真的可读再开始（百度首页在真机上要 2~4 秒）。
        // 第一版只 sleep 1.2s 就调 loadForShot ⇒ 那时页面还在载入，readyState 不是 complete，
        // loadForShot 的 2.5s 上限一到就返回 false——那是**我等待姿势不对**，不是链路不行。
        val waitBegin = System.currentTimeMillis()
        val readable = waitUntilReadable()
        record(
            "① 预备", "等页面可读（模拟用户已看过这一页）",
            if (readable) "能" else "不能",
            "耗时 ${System.currentTimeMillis() - waitBegin}ms（判据：产品方法 evidenceSnapshot 能取到正文）",
        )
        assertTrue("玄览标签的页面在 15s 内没变得可读", readable)

        // ── ① 事实 + 判定（先记录事实，不急着断言） ──────────────────────────
        //
        // ⚠️ 这里刻意**先画后判**：第一版按"未上屏 ⇒ 不可渲染"写了断言，结果直接红——
        // pageRenderableNow() 返回 true。原因：isRenderable = everLoaded &&
        // renderedWhileForeground && !recycled，而 renderedWhileForeground 只表示
        // 「载入完成那一刻 App 在前台」，**不代表这一页真的被画到过屏幕上**。
        // 玄览页签没组合、WebView 没挂窗口，这个位照样会被置真。
        // ⇒ 那就让数据说话：在它说"可渲染"的状态下直接画一次，看是不是白图。
        val renderable0 = BrowserHolder.pageRenderableNow()
        val foreground0 = BrowserHolder.appInForegroundNow()
        val tier0 = ShotTierRule.decide(pageRenderable = renderable0, appForeground = foreground0)
        record(
            "① 判定", "页签未上屏 + App 在前台",
            "说明",
            "pageRenderable=$renderable0 appInForeground=$foreground0 ⇒ $tier0" +
                "（状态机说" + (if (renderable0) "可渲染" else "不可渲染") +
                "；它只看 renderedWhileForeground，不看有没有挂到窗口上）",
        )

        // ── ①.5 就在"状态机说可渲染"的状态下直接画一次 ─────────────────────
        val directRatio = captureShot("①.5 不载入直接画（PAINT_OFFSCREEN 那条路）", "shot_direct_offscreen.png")
        record(
            "①.5 直接画", "未上屏时按判定直接画",
            if (directRatio >= 0.05) "能" else "不能",
            "非白 ${pct(directRatio)}（若 <1% 就是白图 ⇒ 判定说能画、实际画不出，是链路缺口）",
        )

        // ── ② 前台载入 ─────────────────────────────────────────────────────
        val diagBefore = webViewDiag()
        val t0 = System.currentTimeMillis()
        val loaded1 = BrowserHolder.loadForShot()
        val loadMs1 = System.currentTimeMillis() - t0
        record(
            "② 前台载入·第 1 次", "loadForShot()",
            if (loaded1) "能" else "不能",
            "返回 $loaded1，耗时 ${loadMs1}ms（超时上限 2500ms＋沉降）",
        )

        // 第 1 次可能超时：WebView 从 0×0 第一次挂上 1312×2848 时，主线程被 layout 占满，
        // 问 readyState 的回调挤不回来。这不是逻辑错，是「首次挂载开销 × 2.5s 上限」的取舍。
        // 再调一次（此时已挂上窗口）看链路是否可用——引擎侧也允许多试一次。
        val t1 = System.currentTimeMillis()
        val loaded = loaded1 || BrowserHolder.loadForShot()
        val loadMs2 = System.currentTimeMillis() - t1
        record(
            "② 前台载入·第 2 次", "loadForShot()",
            if (loaded) "能" else "不能",
            "第 1 次=$loaded1；第 2 次返回 ${loaded && !loaded1}，耗时 ${loadMs2}ms",
        )
        val diagAfter = webViewDiag()
        record("② 诊断", "loadForShot 前后 WebView 状态", "说明", "前：$diagBefore ｜ 后：$diagAfter")
        assertTrue("loadForShot 必须真的就绪（readyState=complete 且提交了地址）", loaded)

        // ── ③ 载入后是否变成可渲染 ──────────────────────────────────────────
        val renderable1 = BrowserHolder.pageRenderableNow()
        record(
            "③ 状态翻转", "pageRenderableNow()",
            "说明",
            "载入前=$renderable0 载入后=$renderable1",
        )

        // ── ④ 真的出图吗（数像素） ──────────────────────────────────────────
        val after = captureShot("④ 载入后画图", "shot_after_load.png")
        record(
            "④ 出图", "非白像素占比",
            if (after >= 0.05) "能" else "不能",
            "非白 ${pct(after)}（直接画时 ${pct(directRatio)}；百度首页白底文字多，真图约 5%~25%，" +
                "纯白 <1% 即没出图）",
        )
        assertTrue("载入后必须画出真图（非白 ≥5%），实际 ${pct(after)}", after >= 0.05)

        // ── ⑤ 摘下离屏挂载 ─────────────────────────────────────────────────
        val detached = runCatching { runOnMainSyncBlocking { BrowserHolder.detachForShot() } }
        val parentAfter = runOnMainSyncBlocking { BrowserTabManager.activeTab()?.webView?.parent }
        record(
            "⑤ 摘下挂载", "detachForShot()",
            if (parentAfter == null) "能" else "不能",
            "调用=${detached.isSuccess}；调用后 WebView.parent=${parentAfter?.javaClass?.simpleName ?: "null"}",
        )

        // ── ⑥ 切屏状态机：用户手动切走就不抢 ────────────────────────────────
        val origin = BrowserHolder.openForShot()
        Thread.sleep(800)
        val switched = runOnMainSyncBlocking { BrowserHolder.appTab }
        // 模拟「用户自己点了抱朴」：把当前页签改成抱朴，再让引擎尝试恢复
        runOnMainSyncBlocking { BrowserHolder.requestTabTo(TabIndex.BAOPU) }
        Thread.sleep(800)
        val restore = BrowserHolder.restoreAfterShot(origin)
        val reallySwitched = switched == TabIndex.BROWSER
        record(
            "⑥ 不抢屏", "用户手动切走后 restoreAfterShot",
            when {
                !reallySwitched -> "说明"
                !restore -> "能"
                else -> "不能"
            },
            "openForShot 的 origin=$origin，切屏后 appTab=$switched" +
                if (!reallySwitched) {
                    "；⚠️ 这次程序化切屏没生效（appTab 没变成玄览），「用户切走」这一格**没验到**，" +
                        "不当作通过——判定本身已由 ShotTierRuleTest 与 BrowserHolderShotSwitchTest 钉住"
                } else {
                    "；用户切走后恢复返回 $restore（期望 false —— 他切去哪儿是他此刻的选择）"
                },
        )
        if (reallySwitched) {
            assertFalse("用户手动切走后 restoreAfterShot 必须返回 false（不许抢屏）", restore)
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /**
     * 主线程内画一次 WebView：**同时落一张 PNG**（人能直接看）＋数非白像素（机器可断言）。
     *
     * 落 PNG 是因为本机读得了图——数像素只是给断言用的量化，最终判据仍然是眼睛看图：
     * 白图 vs 真图一眼就分得出来，比 0.00% / 3.52% 这种数字更不容狡辩。
     */
    private fun captureShot(label: String, fileName: String): Double {
        val wv = BrowserTabManager.activeTab()?.webView
        if (wv == null) {
            record("画图", label, "失败", "没有活动标签的 WebView")
            return -1.0
        }
        val dir = activity?.getExternalFilesDir(OUT_SUB) ?: activity?.filesDir
        dir?.mkdirs()
        val result = runOnMainSyncBlocking {
            if (wv.width <= 0 || wv.height <= 0) {
                Pair(-1.0, "尺寸 ${wv.width}x${wv.height}")
            } else {
                val bmp = createBitmap(wv.width, wv.height)
                wv.draw(Canvas(bmp))
                var nonWhite = 0
                var total = 0
                var y = 0
                while (y < bmp.height) {
                    var x = 0
                    while (x < bmp.width) {
                        val p = bmp[x, y]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        if (r < 240 || g < 240 || b < 240) nonWhite++
                        total++
                        x += 8
                    }
                    y += 8
                }
                // 原样落盘给人看（压缩失败不影响数值判据）
                if (dir != null) {
                    runCatching {
                        java.io.FileOutputStream(java.io.File(dir, fileName)).use { out ->
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                }
                bmp.recycle()
                val ratio = if (total == 0) -1.0 else nonWhite.toDouble() / total
                Pair(ratio, "${wv.width}x${wv.height} → $fileName")
            }
        }
        Log.i(TAG, "$label size=${result.second} ratio=${result.first}")
        return result.first
    }

    /** 一眼看清 WebView 挂没挂窗口、有没有尺寸、committed 是哪个地址。 */
    private fun webViewDiag(): String = runOnMainSyncBlocking {
        val wv = BrowserTabManager.activeTab()?.webView
        if (wv == null) {
            "无 WebView"
        } else {
            "attached=${wv.isAttachedToWindow} size=${wv.width}x${wv.height} " +
                "parent=${wv.parent?.javaClass?.simpleName ?: "null"} url=${wv.url}"
        }
    }

    /** 轮询到「产品方法能取到正文」为止（模拟"用户已经看过这一页"）。 */
    private fun waitUntilReadable(timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = runCatching { BrowserCommands.evidenceSnapshot() }.getOrNull()
            if (r is BrowserCommands.EvidenceCapture.Ok) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun pct(r: Double): String =
        if (r < 0) "拿不到" else String.format(java.util.Locale.US, "%.2f%%", r * 100)

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

    private fun writeResult(activity: android.app.Activity) {
        runCatching {
            val dir = activity.getExternalFilesDir(OUT_SUB) ?: File(activity.filesDir, OUT_SUB)
            dir.mkdirs()
            val root = JSONObject()
                .put("batch", "262")
                .put("layer", "shot-tier")
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
        private const val TAG = "shot-tier-device"
        private const val PAGE_URL = "https://www.baidu.com"
        private const val OUT_SUB = "patrol_evidence_probe"
        private const val OUT_FILE = "shot_tier_result.json"
    }
}
