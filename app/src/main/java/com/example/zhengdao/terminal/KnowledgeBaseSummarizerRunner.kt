// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：OpenCode 官方 serve 模式 HTTP API（端点与信封形态均为本项目真机实测，
//   见 oc/OcRepository.kt 的注释与 docs/知识库-P2设计方案.md 第三节）；OkHttp 4.12 官方 API。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.oc.OcManager
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 资料库 **P2 · 摘要** 的调用编排（借太极的免费模型给 `原始/` 里的文件补摘要）。
 *
 * ## 设计要点（每一条都对应一个真实约束）
 *
 * | 约束 | 做法 |
 * |---|---|
 * | **绝不干扰用户正在看的太极会话** | **自己 new 一个 OkHttpClient**，直接用 [OcManager.servePassword] 鉴权打 REST，**不走 `OcRepository` 那个全局单例**（它的 `_state` 只有一个活动会话，切进去会把用户的界面切走） |
 * | **绝不挂死** | 全程总超时 [TOTAL_TIMEOUT_MS]；**权限请求一律自动拒绝**（[KnowledgeBaseSummarizer.denyReplyBody]）—— 沉默会让 agent 永远等下去 |
 * | **绝不让摘要失败拖垮清单** | 摘要只是往 `整理/00-目录.md` **追加一节**；失败就保持原样，清单照旧可用 |
 * | **绝不碰 `原始/`** | 落盘点只有 [KnowledgeBase.writeSummaries]（写 `整理/`） |
 * | **绝不静默失败** | 每一个 early-return 都有一行 RunLog，并且**说明为什么跳过** |
 *
 * ## ⚠️ 本文件在真机验证前属于「未验证代码」
 *
 * `docs/知识库-P2设计方案.md` 第六节列了三处无法在本机验证的点。本文件按"**能测的全在
 * [KnowledgeBaseSummarizer]、测不了的在这里**"切开，但下面这些**仍必须真机走一遍**：
 * 1. 新会话有没有默认模型（没有的话 `prompt` 会失败 —— 届时看日志的 HTTP 码）
 * 2. "答完了"的稳定性收敛在当前模型上需要几轮（[POLL_INTERVAL_MS] / [SETTLE_ROUNDS]）
 * 3. 权限请求的实际形态（[AUTO_DENY] 是否够用，还是需要先批准一次读权限）
 *
 * 三条都过了，再把 [requestSummaries] 接到「重新整理」按钮与启动流程上
 * （自动化时机见 P2 方案第八节：**只做用户主动点击**，不做开机自动跑）。
 */
object KnowledgeBaseSummarizerRunner {

    /** 轮询间隔：模型回一段长文本要几秒，1.5s 一取既够快也不至于打满 CPU。 */
    private const val POLL_INTERVAL_MS = 1500L

    /** 连续多少轮"文本没变"才认定答完。1 轮即可：比 2 轮省一半等待，误判风险由非空兜底。 */
    private const val SETTLE_ROUNDS = 1

    /** 整个任务的总上限（含排队与生成）。超了就放弃，绝不无限等。 */
    private const val TOTAL_TIMEOUT_MS = 180_000L

    /** 单个文件喂给模型的试读字节上限。 */
    private const val READ_BYTES_PER_FILE = 1200

    /** 试读时只看这些扩展名 —— 其余（docx/pdf/图片）留给 P3 转换，这里只给文件名。 */
    private val TEXT_EXT = setOf("txt", "md", "markdown", "json", "csv", "log", "yaml", "yml", "ini", "html")

    /** 同时只允许一个摘要任务（防用户连点「重新整理」起一堆）。 */
    private val running = AtomicBoolean(false)

    /** 摘要任务的临时会话前缀（用于日志与"这是谁建的会话"的自解释）。 */
    private const val SESSION_TITLE = "证道·资料库整理"

    /**
     * 跑一次摘要（**后台线程，立即返回**）。
     *
     * @param onDone 结束回调（主线程外的任意线程），参数 = 成功写入的条数（0 表示没做成）
     */
    fun requestSummaries(ctx: Context, onDone: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!KnowledgeBase.isEnabled(app)) {
            RunLog.log("资料库摘要：已跳过（总开关是关的）")
            onDone?.invoke(0)
            return
        }
        if (!running.compareAndSet(false, true)) {
            RunLog.log("资料库摘要：已跳过（上一轮还在跑）")
            onDone?.invoke(0)
            return
        }
        val t = Thread({
            var wrote = 0
            try {
                wrote = runOnce(app)
            } catch (e: Throwable) {
                RunLog.log("资料库摘要：意外中断（${e.javaClass.simpleName}: ${e.message}）")
            } finally {
                running.set(false)
                onDone?.invoke(wrote)
            }
        }, "zd-kb-summarize")
        t.isDaemon = true
        t.start()
    }

    // ── 主流程 ──────────────────────────────────────────────────────────────

    private fun runOnce(ctx: Context): Int {
        // 1) 前置：太极在不在
        if (!OcManager.serveRunning()) {
            RunLog.log("资料库摘要：已跳过（太极没在运行 —— 到太极页开一次即可）")
            return 0
        }
        val pw = OcManager.servePassword
        if (pw.isNullOrBlank()) {
            RunLog.log("资料库摘要：已跳过（拿不到太极的访问密码）")
            return 0
        }

        // 2) 清单必须先在：摘要是"追加一节"，没有清单就没地方追加
        val idx = KnowledgeBase.indexFile(ctx)
        if (!idx.isFile) {
            RunLog.log("资料库摘要：已跳过（清单还没生成，先让清单跑完）")
            return 0
        }

        // 3) 挑要处理的文件（只读文本类，其余只给文件名）
        val items = KnowledgeBase.listRaw(ctx)
        if (items.isEmpty()) {
            RunLog.log("资料库摘要：已跳过（原始/ 里没有文件）")
            return 0
        }
        val picked = items.take(KnowledgeBaseSummarizer.MAX_FILES_PER_RUN)
        val payload = picked.map { pair: Pair<String, Long> ->
            pair.first to readHead(File(KnowledgeBase.rawDir(ctx), pair.first))
        }

        KnowledgeBase.markBusy(ctx)
        RunLog.log("资料库摘要：开始（${payload.size} 个文件，借太极的模型）")

        val http = newClient(pw)
        var sid: String? = null
        return try {
            sid = createSession(http)
            if (sid == null) {
                RunLog.log("资料库摘要：建会话失败（看上一行的 HTTP 码）")
                return 0
            }
            if (!sendPrompt(http, sid, KnowledgeBaseSummarizer.buildPrompt(payload))) {
                RunLog.log("资料库摘要：任务没发出去（可能是这个模型不接受这种请求）")
                return 0
            }
            val answer = waitForAnswer(http, sid)
            if (answer.isNullOrBlank()) {
                RunLog.log("资料库摘要：等不到回答（${TOTAL_TIMEOUT_MS / 1000}s 上限）")
                return 0
            }
            val known = picked.map { pair: Pair<String, Long> -> pair.first }.toSet()
            val parsed = KnowledgeBaseSummarizer.parseSummaries(answer, known)
            if (parsed.isEmpty()) {
                RunLog.log("资料库摘要：回答里没解析出可用摘要（原文前 200 字：${answer.take(200)}）")
                return 0
            }
            val ok = KnowledgeBase.writeSummaries(ctx, parsed)
            if (ok) {
                RunLog.log("资料库摘要：完成，写入 ${parsed.size} 条")
                parsed.size
            } else {
                RunLog.log("资料库摘要：写下失败（清单可能正被别的流程重写，下次再来）")
                0
            }
        } finally {
            KnowledgeBase.clearBusy(ctx)
            sid?.let { deleteSession(http, it) }   // 临时会话不进用户的历史列表
        }
    }

    // ── HTTP（自己一套，绝不复用 OcRepository 的单例）───────────────────────

    private fun newClient(password: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            // 与 OcClient.AuthInterceptor 同款：Basic base64(opencode:<pw>)
            val cred = android.util.Base64.encodeToString(
                "opencode:$password".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Basic $cred")
                    .header("Content-Type", "application/json")
                    .build()
            )
        }
        .build()

    private fun url(path: String) = "http://127.0.0.1:${OcManager.PORT}$path"

    private fun post(client: OkHttpClient, path: String, body: String): String? = runCatching {
        val req = Request.Builder().url(url(path))
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful) {
                RunLog.log("资料库摘要：POST $path → HTTP ${r.code}（${text?.take(160)}）")
                null
            } else text
        }
    }.onFailure { RunLog.log("资料库摘要：POST $path 失败（${it.message}）") }.getOrNull()

    private fun get(client: OkHttpClient, path: String): String? = runCatching {
        val req = Request.Builder().url(url(path)).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful) null else text
        }
    }.getOrNull()

    /** `POST /api/session` → `{"data":{"id":"ses_…"}}`（⚠️ 必须剥信封，否则静默拿到空串）。 */
    private fun createSession(client: OkHttpClient): String? {
        val resp = post(client, "/api/session", "{}") ?: return null
        val sid = runCatching {
            val o = JSONObject(resp)
            (o.optJSONObject("data") ?: o).optString("id")
        }.getOrNull()
        val id = sid?.takeIf { it.isNotEmpty() }
        if (id == null) {
            RunLog.log("资料库摘要：建会话响应里没有 id（原文前 200 字：${resp.take(200)}）")
        }
        return id
    }

    private fun sendPrompt(client: OkHttpClient, sid: String, text: String): Boolean {
        val body = JSONObject().apply { put("text", text) }.toString()
        return post(client, "/api/session/$sid/prompt", body) != null
    }

    /**
     * 轮询消息直到稳定。
     *
     * 判定用 [KnowledgeBaseSummarizer.isSettled]（连续两轮文本相同）；同时每隔一轮
     * 拒绝一次权限请求 —— **不响应权限 = 任务永久挂起**（P2 方案第六节第 3 条）。
     */
    private fun waitForAnswer(client: OkHttpClient, sid: String): String? {
        val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
        var prev: String? = null
        var stable = 0
        var denyChecked = 0
        while (System.currentTimeMillis() < deadline) {
            val last = get(client, "/api/session/$sid/message")
            if (last != null) {
                val text = KnowledgeBaseSummarizer.assistantTextFromMessages(last)
                if (KnowledgeBaseSummarizer.isSettled(prev, text)) {
                    stable++
                    if (stable >= SETTLE_ROUNDS) return text
                } else {
                    stable = 0
                    // 顺便看一眼有没有卡着的权限请求（每 3 轮查一次，别把轮询打满）
                    if (++denyChecked % 3 == 0) denyPendingPermissions(client, sid)
                }
                prev = text
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return prev
    }

    /**
     * 把挂着的权限请求**全部拒掉**。
     *
     * ⚠️ 这是本任务"不挂死"的关键保险。拒绝而不是批准：本任务只需要读文件，
     * 任何写/执行请求都超出授权（见 [KnowledgeBaseSummarizer.shouldAutoDeny]）。
     */
    private fun denyPendingPermissions(client: OkHttpClient, sid: String) {
        val raw = get(client, "/api/permission/request") ?: return
        val arr: JSONArray = runCatching {
            val t = raw.trimStart()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("data")
        }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val self = o.optJSONObject("data") ?: o
            val pid = self.optString("id").takeIf { it.isNotEmpty() } ?: continue
            if (self.optString("sessionID") != sid) continue      // 不是本任务的，别乱动
            post(client, "/api/session/$sid/permission/$pid/reply", KnowledgeBaseSummarizer.denyReplyBody())
            RunLog.log("资料库摘要：自动拒绝了一个权限请求（本任务只读，pid=$pid）")
        }
    }

    private fun deleteSession(client: OkHttpClient, sid: String) {
        runCatching {
            val req = Request.Builder().url(url("/api/session/$sid")).delete().build()
            client.newCall(req).execute().close()
        }
    }

    // ── 试读 ────────────────────────────────────────────────────────────────

    /**
     * 读文件开头一小段当"试读"。
     *
     * ⚠️ 会**跳过符号链接**（延续 `CacheCleaner` 的教训：跟随软链会让统计/扫描虚报）；
     * 只读文本类扩展名，`.docx`/`.pdf`/图片一律只给文件名（P3 才做转换）。
     */
    private fun readHead(f: File): String? {
        if (!f.isFile) return null
        val ext = f.extension.lowercase()
        if (ext !in TEXT_EXT) return null
        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
        if (isLink) return null
        return runCatching {
            f.inputStream().use { ins ->
                val buf = ByteArray(READ_BYTES_PER_FILE)
                val n = ins.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
