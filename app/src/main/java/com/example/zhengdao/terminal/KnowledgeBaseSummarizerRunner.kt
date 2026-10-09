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
 * ## ✅ 真机验证结论（2026-10-09，设备 AD3J023824001723）
 *
 * | 原来担心的 | 真机实测 |
 * |---|---|
 * | 新会话有没有默认模型 | **没有** —— `POST /api/session {}` 的响应与 `GET /api/session/{id}` 里都没有 `model` 字段。serve 会用"上一个会话用过的模型"兜底，兜到不吐正文的模型时表现就是"等到超时、一条摘要都没有"。**已改为建会话时显式带 [MODEL_ID]** |
 * | "答完了"要几轮 | 肯给正文的模型 15~35 秒一次答完，`SETTLE_ROUNDS = 1` 够用。真正的问题从来不是收敛，而是**有的免费模型死活不吐正文**（7 个里 5 个正常） |
 * | 权限请求会不会挂死 | 实测 `GET /api/permission/request` 全程 `data: []`（模型根本没真调工具），`AUTO_DENY` 那条保险没被触发过 —— 保留，代价为零 |
 * | 断网时会不会干等 | 会白等满 60 秒（模型在云端，本机 serve 帮不上）。**试过加"没网就跳过"的前置判断，两种口径都被真机打回，最后没留这段代码**：只看 `ConnectivityManager` 能力时，分应用代理会报告一个带 `INTERNET`、甚至 `VALIDATED` 的活跃网络（飞行模式 + Wi-Fi/data 全关也照样），于是判不出"没网"；改成真连一次 `223.5.5.5:53` 又太武断（代理可能只放行特定域名，连不上 DNS 不代表模型也连不上，错杀比多等 60 秒更糟）。最终做法是把成因写进超时文案（见 [waitForAnswer]）|
 *
 * 另外两条实测事实（写单测时踩过）：真机消息是**扁平对象**
 * （`{"id":…,"type":"assistant","content":[…],"finish":…}`，**没有 `info` 外层**，数组顺序新→旧）；
 * `type` 还有 `idle` / `model-switched` / `synthetic` 三种取值。
 *
 * 自动化时机见 P2 方案第八节：**只做用户主动点击**，不做开机自动跑。
 */
object KnowledgeBaseSummarizerRunner {

    /** 轮询间隔：模型回一段长文本要几秒，1.5s 一取既够快也不至于打满 CPU。 */
    private const val POLL_INTERVAL_MS = 1500L

    /** 连续多少轮"文本没变"才认定答完。1 轮即可：比 2 轮省一半等待，误判风险由非空兜底。 */
    private const val SETTLE_ROUNDS = 1

    /**
     * 整个任务的总上限（含排队与生成）。超了就放弃，绝不无限等。
     *
     * ⚠️ 2026-10-09 真机收紧：180s → 60s。太极**没有保活**（用户切到别的 App 或锁屏，
     * serve 随时可能被系统收走），等 180 秒的唯一结果是"用户盯着「整理中」三分钟，最后什么都没有"。
     * 真机实测肯给正文的模型 15~35 秒就答完，60 秒足够且不折磨人。
     */
    private const val TOTAL_TIMEOUT_MS = 60_000L

    /**
     * 摘要用的模型（**显式指定**，不靠 serve 兜底）。
     *
     * 为什么必须显式：真机实测 `POST /api/session {}` 建出来的会话**没有 `model` 字段**
     * （`GET /api/session/{id}` 也没有），此时 serve 用"上一个会话用过的模型"顶上 ——
     * 那可能是用户在太极里随便试过的任何一个免费模型，其中有的（`step-5-preview-free`、
     * `nemotron-3.5-lightning-free`）压根不吐正文，表现就是"等到超时、一条摘要都没有"。
     *
     * 选它的理由：太极里默认就是它（用户不用额外挑）；真机实测同一条提示词，
     * 5/7 个免费模型能给正文，它 25 秒给出 401 字结构化摘要，是最快的一档。
     */
    private const val MODEL_ID = "mimo-v2.6-flash-free"

    /** 免费模型全在 `opencode` 这一家 provider 下（真机 `GET /api/model` 核实过）。 */
    private const val MODEL_PROVIDER = "opencode"

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
                // 具体原因与建议由 waitForAnswer 的最后一行日志给出，这里不重复刷屏
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

    /**
     * `POST /api/session` → `{"data":{"id":"ses_…"}}`（⚠️ 必须剥信封，否则静默拿到空串）。
     *
     * ⚠️ **必须带上 [MODEL_ID]**：不带的话 serve 拿"上一个会话用过的模型"兜底，
     * 用户上次在太极里挑的是什么就用什么 —— 挑到不吐正文的模型时，这里一切正常，
     * 失败现象出现在 60 秒后的"等不到正文"上，极难排查（真机踩过）。
     */
    private fun createSession(client: OkHttpClient): String? {
        val body = JSONObject().apply {
            put(
                "model",
                JSONObject().apply {
                    put("id", MODEL_ID)
                    put("providerID", MODEL_PROVIDER)
                },
            )
        }.toString()
        val resp = post(client, "/api/session", body) ?: return null
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
        var rounds = 0
        while (System.currentTimeMillis() < deadline) {
            rounds++
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
        // 超时不是"网断了"，而是这种任务最常见的失败形态：模型一直在思考 / 反复想调工具，
        // 始终没吐正文。日志要把线索说清楚，否则用户只看到"整理了一会儿什么也没发生"。
        // 另一条同样常见的成因是**真的没网**（摘要是云端模型算的）—— 两种都在文案里点到。
        RunLog.log(
            "资料库摘要：等不到正文（${TOTAL_TIMEOUT_MS / 1000}s 上限，轮询 $rounds 次）" +
                " —— 要么现在没网，要么模型只给思考 / 想自己去读文件。" +
                "保持证道在前台、确认有网，再点一次「重新整理」即可"
        )
        return null
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
     * ⚠️ **跳过符号链接**（延续 `CacheCleaner` 的教训：跟随软链会让统计/扫描虚报）。
     *
     * 三类文件，三种待遇：
     * - 纯文本（txt/md/json…）⇒ 直接读前 [READ_BYTES_PER_FILE] 字节
     * - **`.docx`（P3a）** ⇒ 先抽正文（[DocxTextExtractor]，零依赖），再取前一段
     * - 其余（`.pdf` / 图片 / 旧 `.doc`）⇒ 返回 null，只给模型文件名
     *   （P3b 未做：PDF 要引第三方库，见 `docs/知识库-P3设计方案.md`）
     */
    private fun readHead(f: File): String? {
        if (!f.isFile) return null
        val ext = f.extension.lowercase()
        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
        if (isLink) return null

        if (ext == "docx") return readDocxHead(f)

        if (ext !in TEXT_EXT) return null
        return runCatching {
            f.inputStream().use { ins ->
                val buf = ByteArray(READ_BYTES_PER_FILE)
                val n = ins.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * `.docx` 试读（P3a）。
     *
     * ⚠️ 与纯文本不同，`.docx` **必须整个文件读进来才能解 zip**（zip 目录在文件末尾，
     * 不能只读开头）。所以这里先用"文件大小"挡一道（[DocxTextExtractor.MAX_DOCX_BYTES]），
     * 再整读。抽取本身是 O(n) 扫描，开销与文件大小线性相关
     * （本机 JVM 上跑过真实结构的 docx，含 3 MB 图片，**9ms**；真机待验）。
     *
     * 抽不出来（不是合法 docx / 加过密的 docx）⇒ 返回 null，退回"只给文件名"，**不报错**。
     */
    private fun readDocxHead(f: File): String? {
        if (f.length() > DocxTextExtractor.MAX_DOCX_BYTES) {
            RunLog.log("资料库摘要：`${f.name}` 太大（${f.length() / 1024 / 1024} MB），跳过正文提取")
            return null
        }
        return runCatching {
            val text = DocxTextExtractor.extract(f.readBytes())
            text.takeIf { it.isNotBlank() }?.take(READ_BYTES_PER_FILE)
        }.onFailure {
            RunLog.log("资料库摘要：`${f.name}` 正文提取失败（${it.javaClass.simpleName}），退回只给文件名")
        }.getOrNull()
    }
}
