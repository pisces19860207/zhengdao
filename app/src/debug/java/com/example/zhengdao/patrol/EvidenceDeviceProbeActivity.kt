// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 262 批真机验收件（**只进 debug 源集，release 合并清单里没有这个 activity**）。
//
// 为什么要有它：工单 §4 要「玄览不在前台时取到证据」的真机读数，§6.2 要一张「后台
// 操作可靠性表」（点击／填表／滚动／懒加载／无限滚动／rAF，每行三态＋证据）。这些
// 都是 WebView 机制问题，手点测不出来也无法复现；用同一个 WebView 把同一套动作在
// 前台跑一遍、退到后台再跑一遍，读数才可比。
//
// 边界：
// - 不进产品路径：不 import 玄览/巡按任何类，只用 [EvidenceSnapshot] 的产品脚本
//   （这正是要验的东西），不碰共享 WebView。
// - 唯一入口是本 Activity，只能显式 `am start`；运行期再查一次 FLAG_DEBUGGABLE。
// - 判定只用 DOM 回读与像素采样，不出图给人看（真机读图这条在本机走不通）。
// - 后台不是靠 setVisibility 假的：`moveTaskToBack(true)` 让整个 App 真的退到后台。
package com.example.zhengdao.patrol

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.example.zhengdao.bridge.EvidenceSnapshot
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 262 批真机探针：前台／后台各跑六项操作 ＋ 各取一次页面证据，落 `probe_result.json`。
 *
 * 三态口径照工单：**能**（后台干得成）／**不能**（后台干不成）／**需前台**（后台做着
 * 不报错但结果不对——懒加载、无限滚动、rAF 这类"降频或停摆"的，归这一档）。
 */
class EvidenceDeviceProbeActivity : Activity() {

    /** 一行读数。 */
    private data class Row(
        val phase: String,
        val what: String,
        val verdict: String,
        val detail: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<Row>()
    private var webView: WebView? = null
    private var outFile: File? = null
    private var finished = false
    private var loadedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "probe start, batch=$BATCH")
        if (!isDebugBuild()) {
            rows += Row("GATE", "debug 构建检查", VERDICT_FAIL, "FLAG_DEBUGGABLE=false，拒绝运行")
            wrapUp()
            return
        }
        prepareOutput()
        buildUi()
        main.postDelayed({ if (!finished) finishHard("总超时 ${TOTAL_TIMEOUT_MS}ms 未跑完") }, TOTAL_TIMEOUT_MS)
        loadPage()
    }

    override fun onDestroy() {
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // ── 载入 ────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = android.widget.FrameLayout(this)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.blockNetworkImage = false
        wv.setBackgroundColor(0xFFFFFFFF.toInt())
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loadedOnce) {
                    loadedOnce = true
                    Log.i(TAG, "page finished: $url")
                    main.postDelayed({ if (!finished) runForeground() }, 200)
                }
            }
        }
        root.addView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        webView = wv
    }

    private fun loadPage() {
        webView?.loadUrl(ASSET_URL)
    }

    // ── 前台组 ──────────────────────────────────────────────────────────────

    private fun runForeground() = fClick(::afterFClick)
    private fun afterFClick() = fType(::afterFType)
    private fun afterFType() = fScroll(::afterFScroll)
    private fun afterFScroll() = fLazy(::afterFLazy)
    private fun afterFLazy() = fList(::afterFList)
    private fun afterFList() = fRaf(::afterFRaf)
    private fun afterFRaf() = fEvidence(::afterFEvidence)
    private fun afterFEvidence() = resetScroll(::goBackground)

    private fun fClick(done: () -> Unit) = probe(
        phase = PHASE_FG,
        what = "元素点击（DOM click）",
        action = "document.getElementById('btnF').click();'ok'",
        settleMs = SETTLE_MS,
        read = "document.getElementById('logF').textContent",
    ) { v ->
        verdictOf(v != null && v.contains("已点"), "logF=$v", "点击后 DOM 未更新")
    }(done)

    private fun fType(done: () -> Unit) = probe(
        phase = PHASE_FG,
        what = "填表（原生 setter + input/change）",
        action = TYPE_JS
            .replace("\$ID", "inpF")
            .replace("\$OUT", "logFI")
            .replace("\$VAL", "前台键入"),
        settleMs = SETTLE_MS,
        read = "document.getElementById('logFI').textContent",
    ) { v ->
        verdictOf(v != null && v.contains("前台键入"), "logFI=$v", "输入事件没触发或值没写进")
    }(done)

    private fun fScroll(done: () -> Unit) = probe(
        phase = PHASE_FG,
        what = "滚动（window.scrollTo）",
        action = "window.scrollTo(0, 700);'ok'",
        settleMs = SETTLE_MS,
        read = "String(Math.round(window.scrollY))",
    ) { v ->
        val y = v?.toDoubleOrNull() ?: -1.0
        verdictOf(y >= 600.0, "scrollY=$v（目标 700）", "scrollY 没变（$v）")
    }(done)

    private fun fLazy(done: () -> Unit) = probe(
        phase = PHASE_FG,
        what = "懒加载（IntersectionObserver）",
        action = "document.getElementById('boxF').scrollIntoView({block:'center'});'ok'",
        settleMs = OBSERVE_MS,
        read = "document.getElementById('boxF').textContent",
    ) { v ->
        verdictOf(v != null && v.contains("已加载"), "boxF=$v", "观察器没触发（$v）")
    }(done)

    private fun fList(done: () -> Unit) = probe(
        phase = PHASE_FG,
        what = "无限滚动（滚到底追加）",
        action = "window.scrollTo(0, document.body.scrollHeight);'ok'",
        settleMs = OBSERVE_MS,
        read = "String(document.getElementById('listF').children.length)",
    ) { v ->
        val n = v?.toIntOrNull() ?: 0
        verdictOf(n > 1, "listF 子节点=$n（基线 1）", "滚到底没追加（$n 条）")
    }(done)

    private fun fRaf(done: () -> Unit) = rafProbe(PHASE_FG) { done() }

    private fun fEvidence(done: () -> Unit) = evidenceProbe(PHASE_FG, "前台取证据", done = done)

    private fun resetScroll(done: () -> Unit) {
        js("window.scrollTo(0,0);'ok'") {
            main.postDelayed({ if (!finished) done() }, 250)
        }
    }

    // ── 退后台 ──────────────────────────────────────────────────────────────

    private fun goBackground() {
        Log.i(TAG, "moveTaskToBack -> 后台组")
        record(PHASE_FG, "退到后台", VERDICT_INFO, "moveTaskToBack(true)，等 ${BG_WAIT_MS}ms")
        moveTaskToBack(true)
        main.postDelayed({ if (!finished) runBackground() }, BG_WAIT_MS)
    }

    private fun runBackground() = bClick(::afterBClick)
    private fun afterBClick() = bType(::afterBType)
    private fun afterBType() = bScroll(::afterBScroll)
    private fun afterBScroll() = bLazy(::afterBLazy)
    private fun afterBLazy() = bLazyScroll(::afterBLazyScroll)
    private fun afterBLazyScroll() = bList(::afterBList)
    private fun afterBList() = bRaf(::afterBRaf)
    private fun afterBRaf() = bEvidence(::afterBEvidence)
    private fun afterBEvidence() = bShot(::afterBShot)
    private fun afterBShot() = bReloadNewPage(::wrapUp)

    private fun bClick(done: () -> Unit) = probe(
        phase = PHASE_BG,
        what = "元素点击（DOM click）",
        action = "document.getElementById('btnB').click();'ok'",
        settleMs = SETTLE_MS,
        read = "document.getElementById('logB').textContent",
    ) { v ->
        verdictOf(v != null && v.contains("已点"), "logB=$v", "点击后 DOM 未更新")
    }(done)

    private fun bType(done: () -> Unit) = probe(
        phase = PHASE_BG,
        what = "填表（原生 setter + input/change）",
        action = TYPE_JS
            .replace("\$ID", "inpB")
            .replace("\$OUT", "logBI")
            .replace("\$VAL", "后台键入"),
        settleMs = SETTLE_MS,
        read = "document.getElementById('logBI').textContent",
    ) { v ->
        verdictOf(v != null && v.contains("后台键入"), "logBI=$v", "输入事件没触发或值没写进")
    }(done)

    private fun bScroll(done: () -> Unit) = probe(
        phase = PHASE_BG,
        what = "滚动（window.scrollTo）",
        action = "window.scrollTo(0, 700);'ok'",
        settleMs = SETTLE_MS,
        read = "String(Math.round(window.scrollY))",
    ) { v ->
        val y = v?.toDoubleOrNull() ?: -1.0
        verdictOf(y >= 600.0, "scrollY=$v（目标 700）", "scrollY 没变（$v）")
    }(done)

    private fun bLazy(done: () -> Unit) = probe(
        phase = PHASE_BG,
        what = "懒加载（IntersectionObserver）",
        action = "window.__mode='B';document.getElementById('boxB').scrollIntoView({block:'center'});'ok'",
        settleMs = OBSERVE_MS,
        read = "document.getElementById('boxB').textContent",
    ) { v ->
        // 后台不触发观察器不是报错，是「必须回前台滚一次」——单列一档，不许算成页面没内容
        if (v != null && v.contains("已加载")) {
            VERDICT_YES to "boxB=$v"
        } else {
            VERDICT_NEED_FG to "boxB=$v（后台滚动没触发观察器）"
        }
    }(done)

    /**
     * 懒加载的**真问题**那一格：只改滚动位置，**不调 `scrollIntoView`**（那是强制布局）。
     *
     * 与上一格成对读：上一格问"观察器在后台到底会不会响"，这一格问"靠普通滚动能不能让它响"。
     * 两格放在一起，才回答得了工单那句"懒加载能不能在后台自己搞完"。
     */
    private fun bLazyScroll(done: () -> Unit) = probe(
        phase = PHASE_BG,
        what = "懒加载·纯滚动（不强制布局）",
        action = "var e=document.getElementById('boxC');var r=e.getBoundingClientRect();" +
            "window.scrollTo(0, window.scrollY + r.top - 200);'ok'",
        settleMs = OBSERVE_MS + 500,
        read = "document.getElementById('boxC').textContent",
    ) { v ->
        if (v != null && v.contains("已加载")) {
            VERDICT_YES to "boxC=$v"
        } else {
            VERDICT_NEED_FG to "boxC=$v（只改滚动位置，观察器没响）"
        }
    }(done)

    private fun bList(done: () -> Unit) = probe(
        phase = PHASE_BG,
        what = "无限滚动（滚到底追加）",
        action = "window.scrollTo(0, document.body.scrollHeight);'ok'",
        settleMs = OBSERVE_MS,
        read = "String(document.getElementById('listB').children.length)",
    ) { v ->
        val n = v?.toIntOrNull() ?: 0
        if (n > 1) VERDICT_YES to "listB 子节点=$n（基线 1）"
        else VERDICT_NEED_FG to "listB 子节点=$n（基线 1，后台没追加）"
    }(done)

    private fun bRaf(done: () -> Unit) = rafProbe(PHASE_BG) { done() }
    private fun bEvidence(done: () -> Unit) = evidenceProbe(PHASE_BG, "后台取证据", done = done)

    /**
     * 第一格对照：**已渲染过**的页在后台再画一次（263 §6.1 第二行说这种不失真）。
     * 这一格不是为了证明截图不行，而是把"能画"与"不能画"的分界线钉清楚。
     */
    private fun bShot(done: () -> Unit) {
        val wv = webView
        if (wv == null || wv.width <= 0 || wv.height <= 0) {
            record(PHASE_BG, "已渲染页·后台画图", VERDICT_FAIL, "WebView 尺寸 ${wv?.width}x${wv?.height}，画不了")
            done()
            return
        }
        val ratio = runCatching { nonWhiteRatio(wv) }.getOrElse { -1.0 }
        val pct = String.format(java.util.Locale.US, "%.2f%%", ratio * 100)
        // 页 1 是白底＋黑字＋浅蓝框：整屏渲染成功时非白约 3~6%；<0.5% 就是画不出来
        val verdict = when {
            ratio < 0 -> VERDICT_FAIL
            ratio >= 0.005 -> VERDICT_YES
            else -> VERDICT_NO
        }
        record(
            PHASE_BG,
            "已渲染页·后台画图（非白像素占比）",
            verdict,
            "非白 ${pct}（${wv.width}x${wv.height} 降采样；白底页渲染成功约 3~6%，<0.5% 即画不出）",
        )
        done()
    }

    /**
     * 后台**新载入**一格：这个页从没在前台渲染过 ⇒ 截图必白；DOM 仍应可读。
     * 这一格才是本批「DOM 优先、截图降级」的立论点（263 §6.1 第三行）。
     */
    private fun bReloadNewPage(done: () -> Unit) {
        Log.i(TAG, "background loadUrl -> page2")
        record(
            PHASE_BG,
            "后台新载入页（从没在前台渲染过）",
            VERDICT_INFO,
            "loadUrl(探针页 2)，等 ${BG_LOAD_WAIT_MS}ms 再取证据与画图",
        )
        webView?.loadUrl(ASSET_URL_2)
        main.postDelayed({
            if (finished) return@postDelayed
            evidenceProbe(PHASE_BG, "后台新载入页取证据", marker = "后台新载入") {
                bShotNewPage(done)
            }
        }, BG_LOAD_WAIT_MS)
    }

    /** 后台新载入页的画图对照：整幅带 40vh 红块，若接近 0% 就是没渲染。 */
    private fun bShotNewPage(done: () -> Unit) {
        val wv = webView
        if (wv == null || wv.width <= 0 || wv.height <= 0) {
            record(PHASE_BG, "后台新载入页·画图", VERDICT_FAIL, "WebView 尺寸不可用")
            done()
            return
        }
        val ratio = runCatching { nonWhiteRatio(wv) }.getOrElse { -1.0 }
        val pct = String.format(java.util.Locale.US, "%.2f%%", ratio * 100)
        // 页 2 页首是 40vh 整块红：真渲染出来，整屏非白必然 >30%；
        // <5% 只可能是白图或上一页残留帧（实测见过 3.52%——那是旧帧，不是新页）
        val verdict = when {
            ratio < 0 -> VERDICT_FAIL
            ratio >= 0.30 -> VERDICT_YES
            ratio < 0.05 -> VERDICT_NO
            else -> VERDICT_NEED_FG
        }
        record(
            PHASE_BG,
            "后台新载入页·画图（非白像素占比）",
            verdict,
            "非白 ${pct}（${wv.width}x${wv.height} 降采样；页首 40vh 是红块，真渲染必然 >30%，" +
                "<5% 是白图或上一页残留帧）",
        )
        done()
    }

    // ── 两种通用探针 ────────────────────────────────────────────────────────

    /** 「执行 → 等 → 回读 → 判」一条；判定结果直接入账。 */
    private fun probe(
        phase: String,
        what: String,
        action: String,
        settleMs: Long,
        read: String,
        judge: (String?) -> Pair<String, String>,
    ): (() -> Unit) -> Unit = { done ->
        js(action) {
            main.postDelayed({
                if (finished) return@postDelayed
                js(read) { v ->
                    val (verdict, detail) = judge(v)
                    record(phase, what, verdict, detail)
                    done()
                }
            }, settleMs)
        }
    }

    /** rAF 速率：窗口内计数差 ÷ 秒数。60fps 前台，后台应显著掉。 */
    private fun rafProbe(phase: String, done: () -> Unit) {
        js("String(window.__raf)") { first ->
            main.postDelayed({
                if (finished) return@postDelayed
                js("String(window.__raf)") { second ->
                    val a = first?.toDoubleOrNull() ?: -1.0
                    val b = second?.toDoubleOrNull() ?: -1.0
                    val fps = if (a < 0 || b < 0) -1.0 else (b - a) / (RAF_WINDOW_MS / 1000.0)
                    val (verdict, detail) = when {
                        fps < 0 -> VERDICT_FAIL to "读不到计数器（$first→$second）"
                        fps >= 30 -> VERDICT_YES to "约 ${fps.toInt()} fps（窗口 ${RAF_WINDOW_MS}ms）"
                        fps > 0 -> VERDICT_NEED_FG to "被降频：约 ${fps.toInt()} fps"
                        else -> VERDICT_NO to "停摆：${RAF_WINDOW_MS}ms 内 0 帧（动画/视频会卡住）"
                    }
                    record(phase, "视频/动画（rAF 帧率）", verdict, detail)
                    done()
                }
            }, RAF_WINDOW_MS)
        }
    }

    /** 用**产品脚本**取一次证据（这是本批真正要验的东西），并比对前后台读数。 */
    private fun evidenceProbe(
        phase: String,
        label: String,
        marker: String = "巡按取证据",
        done: () -> Unit,
    ) {
        val script = EvidenceSnapshot.evidenceJsScript()
        js(script) { raw ->
            val parsed = EvidenceSnapshot.parse(raw)
            if (parsed == null) {
                record(phase, label, VERDICT_FAIL, "回包解析失败（raw 长度 ${raw?.length ?: -1}）")
                done()
                return@js
            }
            val clamped = EvidenceSnapshot.clampTotal(parsed)
            val bytes = (parsed.domSummary.toByteArray(Charsets.UTF_8).size +
                parsed.axText.toByteArray(Charsets.UTF_8).size)
            val hasMarker = parsed.domSummary.contains(marker)
            val verdict = if (hasMarker) VERDICT_YES else VERDICT_NO
            record(
                phase,
                label,
                verdict,
                "正文含探针页标题=$hasMarker；url=${parsed.url.ifBlank { "（空）" }} " +
                    "元素数=${parsed.elementCount} " +
                    "骨架行=${parsed.domSummary.lines().size} 正文 ${parsed.domSummary.length} 字 " +
                    "AX ${parsed.axText.length} 字 原始 ${bytes}B 总闸=${clamped.totalTruncated} " +
                    "readyState=${parsed.readyState} 跨域iframe=${parsed.crossOriginFrames}",
            )
            Log.i(TAG, "[$phase] $label: elements=${parsed.elementCount} dom=${parsed.domSummary.length} ax=${parsed.axText.length}")
            done()
        }
    }

    // ── JS 桥 ──────────────────────────────────────────────────────────────

    private fun js(script: String, cb: (String?) -> Unit) {
        val wv = webView
        if (wv == null) {
            cb(null)
            return
        }
        runCatching {
            wv.evaluateJavascript(script) { raw ->
                cb(runCatching { JSONTokener(raw ?: "null").nextValue() as? String }.getOrNull())
            }
        }.onFailure {
            Log.w(TAG, "evaluateJavascript failed: ${it.message}")
            cb(null)
        }
    }

    /** 抽稀有像素占比：整幅白图 ≈ 0；真渲染的页面（带文字、色块）明显 > 0。 */
    private fun nonWhiteRatio(wv: WebView): Double {
        val w = wv.width
        val h = wv.height
        val bmp = createBitmap(w, h)
        wv.draw(Canvas(bmp))
        var nonWhite = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
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
        bmp.recycle()
        return if (total == 0) -1.0 else nonWhite.toDouble() / total
    }

    // ── 收尾 ────────────────────────────────────────────────────────────────

    private fun finishHard(reason: String) {
        if (finished) return
        record("GATE", "总超时", VERDICT_FAIL, reason)
        wrapUp()
    }

    private fun wrapUp() {
        if (finished) return
        finished = true
        val f = outFile
        if (f == null) {
            Log.e(TAG, "no output file")
            finish()
            return
        }
        runCatching {
            val root = JSONObject()
                .put("batch", BATCH)
                .put("at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()))
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE)
                .put("asset", ASSET_URL)
            val arr = JSONArray()
            rows.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("phase", r.phase)
                        .put("what", r.what)
                        .put("verdict", r.verdict)
                        .put("detail", r.detail),
                )
            }
            root.put("rows", arr)
            f.writeText(root.toString(2), Charsets.UTF_8)
            Log.i(TAG, "result written: ${f.absolutePath} (${rows.size} rows)")
        }.onFailure { Log.e(TAG, "result write failed: ${it.message}") }
        finish()
    }

    private fun record(phase: String, what: String, verdict: String, detail: String) {
        rows += Row(phase, what, verdict, detail)
        Log.i(TAG, "ROW|$phase|$what|$verdict|$detail")
    }

    private fun prepareOutput() {
        val dir = getExternalFilesDir(OUT_SUB) ?: File(filesDir, OUT_SUB)
        dir.mkdirs()
        outFile = File(dir, OUT_FILE)
        outFile?.delete()
    }

    private fun isDebugBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun verdictOf(ok: Boolean, good: String, bad: String): Pair<String, String> =
        if (ok) VERDICT_YES to good else VERDICT_NO to bad

    companion object {
        const val TAG = "patrol-evidence-probe"
        const val BATCH = "262"
        const val ASSET_URL = "file:///android_asset/patrol_evidence_probe.html"
        const val ASSET_URL_2 = "file:///android_asset/patrol_evidence_probe2.html"
        const val OUT_SUB = "patrol_evidence_probe"
        const val OUT_FILE = "probe_result.json"

        /** 三态 ＋ 两个非判据标记（失败／说明）。 */
        const val VERDICT_YES = "能"
        const val VERDICT_NO = "不能"
        const val VERDICT_NEED_FG = "需前台"
        const val VERDICT_FAIL = "失败"
        const val VERDICT_INFO = "说明"

        const val PHASE_FG = "前台"
        const val PHASE_BG = "后台"

        private const val SETTLE_MS = 350L
        private const val OBSERVE_MS = 1_500L
        private const val RAF_WINDOW_MS = 2_000L
        private const val BG_WAIT_MS = 2_500L
        private const val BG_LOAD_WAIT_MS = 4_000L
        private const val TOTAL_TIMEOUT_MS = 90_000L

        /**
         * 填表：React 受控组件只认原生 setter 之后派发的事件，直写 `el.value` 会被框架
         * 回滚——这一行验的就是"走原生 setter 能不能在后台改到"。
         */
        private const val TYPE_JS =
            "(function(){var el=document.getElementById('\$ID');" +
                "var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
                "d.set.call(el,'\$VAL');" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                "return document.getElementById('\$OUT').textContent;})()"
    }
}
