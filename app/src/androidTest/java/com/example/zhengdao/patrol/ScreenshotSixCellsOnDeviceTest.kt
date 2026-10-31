// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 262 批 · 六格真机验收（用户 2026-10-28 20:05 逐字给出的标准，与 263 合成一体后）。
//
// 为什么要单开一个类而不改现有的 ScreenshotOffscreenOnDeviceTest：
// 那个类验的是"能/不能"（布尔），本类验的是**六格 × 五个证据**——每格都要
//   PNG 文件 ＋ sha256 前 8 位 ＋ WhiteImageJudge 枚举 ＋ 耗时 ms ＋ 前后焦点。
// 判据不同、交付物不同，混在一个类里会让原来的门禁读数变味（原有断言一条不改）。
//
// 不许另写一套截图引擎：本类每格都调**产品方法** BrowserCommands.captureShotRef
// （＝ browser_screenshot 工具走的那个），PNG 也是从它真正落盘的那个文件读回来的
// ——不是本类自己造图。枚举是拿那份 PNG 再喂 [WhiteImageJudge] 复判，同一份判据、
// 同一份代码，只是把结果留档（captureShotRef 内部判完就丢了）。
package com.example.zhengdao.patrol

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.MainActivity
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.browser.offscreen.WhiteImageJudge
import com.example.zhengdao.ui.browser.BrowserHolder
import com.example.zhengdao.ui.browser.BrowserTabManager
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotSixCellsOnDeviceTest {

    private val cells = JSONArray()
    private var activity: MainActivity? = null
    private val tabIds = mutableListOf<Int>()

    /** 第 6 格要求必须出现的原句（用户逐字给的）。 */
    private val mustSay = "这张是重新载入页面后画的，反映页面初始状态，不是当前滚动/已填内容"

    /** 焦点一致计数（第 5 格）。 */
    private var focusChecked = 0
    private var focusSame = 0

    /**
     * 每张**成功**的图带回来的 note（第 6 格的证据面）。
     *
     * 为什么单独收一份、不从 cells 的字符串里 find：上一版就是这么干的，结果第 6 格判
     * 「命中 0 处」——而同一份日志里每格都明明白白带着那句话。把一个格子的结论建立在
     * 「别的格子的说明文字里有没有这串字」上，本身就是会骗人的判据（漏拼一个字就红）。
     */
    private val okNotes = mutableListOf<String>()

    @After
    fun 收尾() {
        val inst = InstrumentationRegistry.getInstrumentation()
        runCatching {
            inst.runOnMainSync {
                tabIds.forEach { id -> runCatching { BrowserTabManager.closeTab(id) } }
            }
        }
        activity?.let { writeResult(it) }
    }

    @Test
    fun 六格全跑() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val act = inst.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        activity = act
        Thread.sleep(1_500)

        // ── 第 1 格：抱朴在前台、不点玄览，同一页连截 3 张 ────────────────────
        val t1 = createTab(act, URL_A)
        assertTrue("建不出玄览标签", t1 != null)
        tabIds += t1!!
        assertTrue("页面 15s 内没变可读", waitUntilReadable())
        Thread.sleep(800)
        recordPrecondition(act)

        val three = mutableListOf<ShotEvidence>()
        repeat(3) { i ->
            val e = shoot(act, "1-${i + 1}", "第 1 格 连截第 ${i + 1} 张")
            three += e
            Thread.sleep(1_000)
        }
        val richCount = three.count { it.verdict == WhiteImageJudge.Classification.RICH.name }
        cell(
            "1", "抱朴前台不点玄览，同一页连截 3 张",
            if (richCount == 3) "过" else "不过",
            "3 张里 RICH=$richCount/3：" + three.joinToString(" / ") {
                "${it.tag}=${it.verdict}(${it.ms}ms, sha8=${it.sha8})"
            },
        )
        assertTrue("第 1 格：连截 3 张必须全 RICH，实际 $richCount/3", richCount == 3)

        // ── 第 2 格：玄览真上过一次屏、再切回抱朴（页面"此前载入过"）──────────
        val screenBefore = runCatching { BrowserHolder.browserOnScreenNow() }.getOrDefault(false)
        // 点页签在这台机器上不稳（uiautomator dump 在 instrumentation 里拿不到输出，
        // 只能按比例坐标点；底部手势区偶尔会吃掉 2810 那一行）⇒ 依次试三档高度，
        // **以 browserOnScreen 真的翻转为准**，不拿"点过就算"当成功。
        var reached = false
        var usedY: Float = 0f
        for (y in listOf<Float?>(null, 0.97f, 0.95f)) {
            tapTab(act, "玄览", y)
            if (waitBrowserOnScreen(true, 2_500)) {
                reached = true
                usedY = y ?: 0.9866f
                break
            }
        }
        if (reached) {
            Thread.sleep(1_200)
            val attachedOnScreen = attachedNow()
            var left = false
            for (y in listOf<Float?>(null, 0.97f, 0.95f)) {
                tapTab(act, "抱朴", y)
                if (waitBrowserOnScreen(false, 2_500)) {
                    left = true
                    break
                }
            }
            Thread.sleep(800)
            val attachedAfter = attachedNow()
            Log.i(
                TAG,
                "CELL2PRE|onScreenBefore=$screenBefore|reached=$reached(y=$usedY)|attachedOnScreen=$attachedOnScreen|" +
                    "leftScreen=$left|attachedAfterBack=$attachedAfter",
            )
            val e = shoot(act, "2-1", "第 2 格 玄览上过屏后再截")
            cell(
                "2", "玄览在后台、该页此前载入过",
                if (e.verdict == WhiteImageJudge.Classification.RICH.name) "过" else "不过",
                "上屏前 browserOnScreen=$screenBefore → 上屏=$reached（点 y=$usedY 才中，attached=$attachedOnScreen）→ " +
                    "切回抱朴=$left（attached=$attachedAfter）；${e.summary()}",
            )
            assertTrue("第 2 格：载入过的页应能出 RICH", e.verdict == WhiteImageJudge.Classification.RICH.name)
        } else {
            cell("2", "玄览在后台、该页此前载入过", "没验成", "三档坐标都没能把玄览切上屏，本格跳过（其余格照跑）")
        }

        // ── 第 3 格：新网页、从未载入过，立刻截 ──────────────────────────────
        // ⚠️ 上一版这里 150ms 就截，撞到的是「还没有地址」——`newTab` 只发 loadUrl，
        // `state.url` 由 onPageStarted 才写，窗口期里 url 还是空的，captureShotRef 第一道
        // （pageUrl.isBlank）就 return 了，根本没走到离屏。要验的是用户真场景：
        // **地址已经有了（onPageStarted 已到）、页面还没载完（onPageFinished 未到）**。
        val t3 = createTab(act, URL_B)
        assertTrue("第 3 格：建不出新标签", t3 != null)
        tabIds += t3!!
        val urlWritten = waitUntilUrlWritten(4_000)
        val loadedAtShot = everLoadedNow()
        val renderedAtShot = renderedNow()
        val attachedAtShot = attachedNow()
        Log.i(
            TAG,
            "CELL3PRE|urlWritten=$urlWritten|everLoaded=$loadedAtShot|rendered=$renderedAtShot|attached=$attachedAtShot",
        )
        val e3 = shoot(act, "3-1", "第 3 格 从未载入过的新网页")
        val c3ok = e3.verdict == WhiteImageJudge.Classification.RICH.name
        cell(
            "3", "玄览在后台、该页从未载入过（离屏前台 1dp 载入）",
            if (c3ok) "过" else "如实失败",
            "截前 urlWritten=$urlWritten everLoaded=$loadedAtShot rendered=$renderedAtShot " +
                "attached=$attachedAtShot（rendered 真但 attached 假＝从没被 layout 过）；${e3.summary()}",
        )
        if (!c3ok) {
            // 允许失败，但**必须有可读原因**，且不许拿灰图当成功。
            assertTrue("第 3 格：失败必须带可读原因", e3.failureReason.isNotBlank())
            assertTrue("第 3 格：不许静默回灰图当成功", !e3.reportedOkWithUniform)
        }

        // ── 第 4 格：App 整个退到后台（home）后再截 ──────────────────────────
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(2_000)
        val e4 = shoot(act, "4-1", "第 4 格 App 退后台（home）")
        val v4 = when {
            e4.reportedOkWithUniform -> "犯规（拿灰图当成功）"
            e4.verdict == WhiteImageJudge.Classification.RICH.name -> "过（居然截到了 RICH，比预期强）"
            e4.failureReason.isNotBlank() -> "过（允许失败：给了可读原因，没回灰图）"
            else -> "不过（失败但没给原因）"
        }
        cell(
            "4", "App 退后台后截图（允许失败）",
            v4,
            "①可读原因=${e4.failureReason.ifBlank { "（成功，无原因）" }} ②灰图当成功=${e4.reportedOkWithUniform} " +
                "③枚举=${e4.verdict} 详情=${e4.raw}",
        )
        assertTrue("第 4 格①：不许拿灰图当成功", !e4.reportedOkWithUniform)
        // 回前台，别把后面的收尾留在后台
        shell("am start -n ${ctx.packageName}/.MainActivity")
        Thread.sleep(2_500)

        // ── 第 5 格：焦点不变（横切，逐格对比）───────────────────────────────
        cell(
            "5", "截图前后 mCurrentFocus 一致",
            if (focusChecked > 0 && focusSame == focusChecked) "过" else "不过",
            "$focusSame/$focusChecked 次前后一致；截屏不弹全屏 Activity（全流程只在 MainActivity 里）",
        )
        assertTrue("第 5 格：截图前后焦点必须一致", focusChecked > 0 && focusSame == focusChecked)

        // ── 第 6 格：提示语必须含那句话（横切：只查真正出图的那几张的 note）─────────
        val hit = okNotes.count { it.contains(mustSay) }
        cell(
            "6", "提示语含用户指定那句话",
            if (okNotes.isNotEmpty() && hit == okNotes.size) "过" else "不过",
            "$hit/${okNotes.size} 张成功的图的 note 含这句；样例=${okNotes.firstOrNull() ?: "（本轮没有出图，无从检查）"}",
        )
        assertTrue(
            "第 6 格：每张成功的图都必须带那句话（实际 $hit/${okNotes.size}）",
            okNotes.isNotEmpty() && hit == okNotes.size,
        )
    }

    // ── 一次截图 + 五件证据 ─────────────────────────────────────────────────

    private inner class ShotEvidence(
        val tag: String,
        val ms: Long,
        val verdict: String,
        val sha8: String,
        val png: String,
        val note: String,
        val raw: String,
        val failureReason: String,
        /** captureShotRef 回了 Ok，但那张图其实是整幅同色（＝静默回灰图，犯规）。 */
        val reportedOkWithUniform: Boolean,
    ) {
        fun summary(): String =
            "$verdict ｜ ${ms}ms ｜ sha256:$sha8 ｜ png=$png ｜ note=$note" +
                if (failureReason.isNotBlank()) " ｜ 原因=$failureReason" else ""
    }

    private fun shoot(act: MainActivity, tag: String, what: String): ShotEvidence {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val dir = outDir(act)
        val before = shotFiles(ctx)
        val focusBefore = currentFocus()

        val t0 = System.currentTimeMillis()
        val r = runCatching { BrowserCommands.captureShotRef(ctx, null, "0") }
        val ms = System.currentTimeMillis() - t0

        val focusAfter = currentFocus()
        focusChecked++
        if (focusBefore.isNotBlank() && focusBefore == focusAfter) focusSame++
        if (focusBefore.isBlank() || focusAfter.isBlank()) {
            Log.w(TAG, "焦点取不到：before=[$focusBefore] after=[$focusAfter]")
        }

        // 从产品真正落盘的新文件里读回字节（不是本类自己造图）
        val added = shotFiles(ctx) - before
        val landed = added.firstOrNull()?.let { File(BrowserCommands.shotDir(ctx), it) }

        return when (val c = r.getOrNull()) {
            is BrowserCommands.ShotCapture.Ok -> {
                val enum = landed?.let { judgeFile(it) } ?: WhiteImageJudge.Classification.EMPTY.name
                val sha = landed?.let { sha256(it) }?.take(8) ?: "--------"
                val copy = landed?.let { src ->
                    val dst = File(dir, "262cell-${tag}.png")
                    runCatching { src.copyTo(dst, overwrite = true) }.getOrNull()
                }
                val uniform = enum == WhiteImageJudge.Classification.UNIFORM_WHITE.name ||
                    enum == WhiteImageJudge.Classification.UNIFORM_BLANK.name
                val noteText = c.ref.note ?: "（无）"
                okNotes += noteText
                ShotEvidence(
                    tag = tag, ms = ms, verdict = enum, sha8 = sha,
                    png = copy?.absolutePath ?: (landed?.absolutePath ?: "（没找到落盘文件）"),
                    note = noteText, raw = "mode=${c.ref.mode} ${c.ref.width}x${c.ref.height}",
                    failureReason = "", reportedOkWithUniform = uniform,
                ).also { logRow(what, it) }
            }

            is BrowserCommands.ShotCapture.Bad -> ShotEvidence(
                tag = tag, ms = ms, verdict = "（没出图）", sha8 = "--------", png = "（无）",
                note = "（无）", raw = c.error, failureReason = c.error, reportedOkWithUniform = false,
            ).also { logRow(what, it) }

            null -> {
                val msg = r.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message}" } ?: "null"
                ShotEvidence(
                    tag = tag, ms = ms, verdict = "（抛异常）", sha8 = "--------", png = "（无）",
                    note = "（无）", raw = msg, failureReason = msg, reportedOkWithUniform = false,
                ).also { logRow(what, it) }
            }
        }
    }

    private fun logRow(what: String, e: ShotEvidence) {
        Log.i(TAG, "CELL|$what|${e.verdict}|${e.ms}ms|sha8=${e.sha8}|note=${e.note}|raw=${e.raw}")
    }

    /** 那份 PNG 是什么枚举（与产品同一份判据）。 */
    private fun judgeFile(f: File): String = runCatching {
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return "EMPTY"
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val v = WhiteImageJudge.judge(px, w, h)
        bmp.recycle()
        v.classification.name
    }.getOrDefault("EMPTY")

    private fun sha256(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun shotFiles(ctx: android.content.Context): Set<String> =
        BrowserCommands.shotDir(ctx).listFiles()?.map { it.name }?.toSet() ?: emptySet()

    private fun outDir(act: MainActivity): File =
        (act.getExternalFilesDir(OUT_SUB) ?: File(act.filesDir, OUT_SUB)).apply { mkdirs() }

    // ── 前置 / 页面 / 焦点 / 点页签 ─────────────────────────────────────────

    private fun createTab(act: MainActivity, url: String): Int? {
        var id: Int? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            id = BrowserTabManager.newTab(act, url)
        }
        return id
    }

    private fun recordPrecondition(act: MainActivity) {
        var parent: String? = null
        var attached = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wv = BrowserTabManager.activeTab()?.webView
            parent = wv?.parent?.javaClass?.simpleName
            attached = wv?.isAttachedToWindow == true && (wv?.width ?: 0) > 0
        }
        Log.i(TAG, "PRE|parent=${parent ?: "null"}|attached=$attached")
        cell(
            "0", "前置：玄览此刻没上屏（用户停在抱朴）",
            if (!attached) "符合" else "不符合",
            "WebView.parent=${parent ?: "null"} attached=$attached",
        )
    }

    /** 此刻活跃标签的 WebView 是否真的挂在窗口上且有尺寸（判据与 BrowserHolder 同一份）。 */
    private fun attachedNow(): Boolean {
        var out = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wv = BrowserTabManager.activeTab()?.webView
            out = wv?.isAttachedToWindow == true && (wv?.width ?: 0) > 0
        }
        return out
    }

    private fun everLoadedNow(): Boolean {
        var out = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            out = BrowserTabManager.activeTab()?.state?.everLoaded == true
        }
        return out
    }

    /**
     * `renderedWhileForeground` 位（"载入完成那一刻 App 在前台"）。
     *
     * 第 3 格要它，是因为这一位**会骗人**：它也只看"载入时 App 在不在前台"，不看这一页
     * 到底有没有被 layout 过。真机读数里 everLoaded/rendered 都真、attached 却 false，
     * 正是 `isRenderable()` 单用不够、必须再叠"真在窗口上"的那个情形。
     */
    private fun renderedNow(): Boolean {
        var out = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            out = BrowserTabManager.activeTab()?.state?.renderedWhileForeground == true
        }
        return out
    }

    /**
     * 等 `state.url` 被写进来（＝ onPageStarted 已到），但**不等**载入完成。
     *
     * 这就是第 3 格要的那个瞬间：地址有了、页面还没载完。
     */
    private fun waitUntilUrlWritten(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var u = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                u = BrowserTabManager.activeTab()?.state?.url.orEmpty()
            }
            if (u.isNotBlank()) return true
            Thread.sleep(20)
        }
        return false
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

    private fun waitBrowserOnScreen(want: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { BrowserHolder.browserOnScreenNow() }.getOrDefault(!want) == want) return true
            Thread.sleep(150)
        }
        return false
    }

    /**
     * 点底栏某个页签。
     *
     * ⚠️ 不能取"第一个 text=玄览 的节点"：抱朴的对话正文里随时会出现「玄览」二字
     * （用户和抱朴聊过这个话题），dump 出来第一条命中往往是聊天文本。底栏那三个字
     * 的特征是**在屏幕最底部**——所以按 y 取最靠下的那个，再要求它落在下 12% 的带子里。
     *
     * @param yRatio 非 null 时跳过 dump 直接按屏幕高比例点（instrumentation 里
     *   `uiautomator dump` 常常拿不到输出，真机上靠这条兜底；2026-10-28 实测过）。
     */
    private fun tapTab(act: MainActivity, label: String, yRatio: Float? = null): Boolean = runCatching {
        val screenH = act.resources.displayMetrics.heightPixels
        val w = act.resources.displayMetrics.widthPixels
        val ratioX = when (label) {
            "抱朴" -> 0.159f
            "玄览" -> 0.5f
            else -> 0.841f
        }
        if (yRatio != null) {
            shell("input tap ${(w * ratioX).toInt()} ${(screenH * yRatio).toInt()}")
            Log.i(TAG, "TAP|$label|比例($ratioX,$yRatio)")
            return true
        }
        repeat(2) { attempt ->
            val dumped = shell("uiautomator dump /sdcard/_zd_ui.xml")
            if (dumped.isBlank() || dumped.contains("ERROR", ignoreCase = true)) {
                Log.w(TAG, "TAP|dump 第${attempt + 1}次不通：$dumped")
            }
            val xml = shell("cat /sdcard/_zd_ui.xml")
            val cands = Regex("<node[^>]*?text=\"$label\"[^>]*?>").findAll(xml).mapNotNull { m ->
                val b = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(m.value)
                    ?: return@mapNotNull null
                val (x1, y1, x2, y2) = b.destructured
                intArrayOf(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
            }.filter { it[1] > (screenH * 0.88).toInt() }.toList()
            val pick = cands.maxByOrNull { it[1] }
            if (pick != null) {
                val cx = (pick[0] + pick[2]) / 2
                val cy = (pick[1] + pick[3]) / 2
                shell("input tap $cx $cy")
                Log.i(TAG, "TAP|$label|($cx,$cy)|候选=${cands.size}")
                return true
            }
            Thread.sleep(400)
        }
        // 兜底：本机（PGT-AN10 1312×2848）底栏三格中心，按屏幕比例算，换机型仍近似成立
        shell("input tap ${(w * ratioX).toInt()} ${(screenH * 0.9866f).toInt()}")
        Log.i(TAG, "TAP|$label|兜底比例坐标")
        true
    }.getOrDefault(false)

    private fun currentFocus(): String = runCatching {
        shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            .replace("\r", "").lines().joinToString(" ") { it.trim() }.take(300)
    }.getOrDefault("")

    private fun shell(cmd: String): String = runCatching {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
            String(it.readBytes(), Charsets.UTF_8)
        }
    }.getOrDefault("")

    // ── 落结果 ──────────────────────────────────────────────────────────────

    private fun cell(id: String, what: String, verdict: String, detail: String) {
        cells.put(JSONObject().put("cell", id).put("what", what).put("verdict", verdict).put("detail", detail))
        Log.i(TAG, "GRID|$id|$what|$verdict|$detail")
    }

    private fun writeResult(act: android.app.Activity) {
        runCatching {
            val dir = (act.getExternalFilesDir(OUT_SUB) ?: File(act.filesDir, OUT_SUB)).apply { mkdirs() }
            val root = JSONObject()
                .put("batch", "262")
                .put("layer", "screenshot-six-cells")
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("urlA", URL_A)
                .put("urlB", URL_B)
                .put("focusChecked", focusChecked)
                .put("focusSame", focusSame)
                .put("cells", cells)
            File(dir, OUT_FILE).writeText(root.toString(2), Charsets.UTF_8)
            Log.i(TAG, "result written -> ${File(dir, OUT_FILE).absolutePath}")
        }.onFailure { Log.e(TAG, "write failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "shot-six-cells"
        private const val URL_A = "https://www.baidu.com"
        private const val URL_B = "https://cn.bing.com"
        private const val OUT_SUB = "patrol_evidence_probe"
        private const val OUT_FILE = "screenshot_six_cells_result.json"
    }
}
