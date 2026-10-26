// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百五十八批：`generate_image`——原生 Gemini 文生图/图生图。
// 判据（路径/文件名/数量/尺寸/响应解析/尺寸解析/模型挑选）在 `GenerateImage`（纯函数），
// 这里只管四件事：校验 → 拉模型清单 → 网络 → 落盘。
//
// 为什么本工具**不自动重试 5xx**：生图按张烧额度，盲目重发会把一次失败叠成两张的账。
// 派工单 §2 的 5xx 重试是 wire 层（LlmClient 流式）的口径；这里如实把失败交回去。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmWire
import com.example.zhengdao.baopu.MediaPublish
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.rootfs.PathGuard
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal object GenerateImageTool {

    const val ARG_PROMPT = "prompt"
    const val ARG_SIZE = "size"
    const val ARG_COUNT = "count"
    const val ARG_REFERENCE = "reference"

    /** 无 Key 时的人话（必须是「去哪儿填」，不是一条 401）。 */
    const val NO_KEY =
        "还没填 Google Gemini 的 Key：去「设置 → 通道 → Google Gemini」填好 Key 再让我画（图只会存到本机，不上传）。"

    private const val NO_ENV = "生图还没接上 Gemini 通道（App 内部装配缺失）：先换到 Google Gemini 再试。"

    /**
     * 运行时接线（装配层 [com.example.zhengdao.baopu.BaopuRuntime] 注入）。
     *
     * [artifactDir] 是 `File -> File` 而不是固定目录：工作区随时会换，落点跟着当时的工作区算。
     * [now] 注入是为了单测能钉住文件名里的时间戳；[http] 注入让 MockWebServer 直测。
     */
    data class Env(
        val provider: Provider,
        val artifactDir: (workspace: File) -> File,
        val http: OkHttpClient = defaultHttp(),
        val now: () -> Long = { System.currentTimeMillis() },
        val log: (String) -> Unit = { RunLog.log(it) },
        /**
         * 执行时的 Key 兜底（2026-10-23）：装配那一刻若所有 Key 都在冷却，[provider] 会带着
         * 空 Key 被固化，于是生图永远回「还没填 Key」。每次执行再问一次（仍走同一套
         * Key 池/冷却判据），只在装配那份为空时才用它。
         */
        val keyProvider: (() -> String)? = null,
        /**
         * 第一百九十九批：把画好的图**存进手机相册**（`Pictures/证道/图片/`，相册可见）。
         *
         * 为什么注入而不是直接调：本工具是「零 Android」的判据层（见文件头），
         * `ContentResolver` 只许活在装配层。`null` ⇒ 退回 158 的老行为（只写 `产物/`），
         * 单测与「相册通道没接上」的那条路走的就是它。
         *
         * 返回 `Unavailable` ⇒ 落回 `产物/` 并在回执里如实说明（**不许**假装相册里有）。
         */
        val publishAlbum: ((bytes: ByteArray, name: String, mime: String, ext: String) -> MediaPublish)? = null,
    )

    @Volatile
    var env: Env? = null

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 生图比对话慢得多（常见 10–60 秒）：共用连接池，只把读写超时放宽。 */
    private fun defaultHttp(): OkHttpClient = LlmClient.sharedHttp.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "generate_image",
        description = "画图（文生图/图生图；每次执行前弹确认卡，会消耗 Gemini 额度）：" +
            "先拉 {base}/models 清单挑图模型（Nano Banana Pro 优先，其次 Nano Banana 2），" +
            "产物只落本机 `<工作区>/产物/`（157 收口后默认与自定义工作区同一口径），同名不覆盖。" +
            "reference 给工作区里的一张图，就是「照着它改」。count 最多 ${GenerateImage.MAX_COUNT} 张。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PROMPT to jsonObjectOf(
                    "type" to "string",
                    "description" to "要画什么（越具体越好，可以写中文；图生图时写「把这张图改成……」）",
                ),
                ARG_SIZE to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*GenerateImage.SIZES.toTypedArray()),
                    "description" to "宽高比（省略＝1:1）：1:1 / 4:3 / 3:4 / 16:9 / 9:16",
                ),
                ARG_COUNT to jsonObjectOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to GenerateImage.MAX_COUNT,
                    "description" to "画几张（默认 1，最多 ${GenerateImage.MAX_COUNT}；每张单独请求、分别烧额度）",
                ),
                ARG_REFERENCE to jsonObjectOf(
                    "type" to "string",
                    "description" to "可选：工作区里的参考图（相对工作区根，如 产物/cat.png），用于图生图",
                ),
            ),
            "required" to jsonArrayOf(ARG_PROMPT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.IMAGE_MS,
    ) { args ->
        run(
            root = workspaceRoot(),
            prompt = args.optString(ARG_PROMPT),
            size = if (args.has(ARG_SIZE)) args.optString(ARG_SIZE) else null,
            count = if (args.has(ARG_COUNT)) args.optInt(ARG_COUNT) else null,
            reference = if (args.has(ARG_REFERENCE)) args.optString(ARG_REFERENCE) else null,
        )
    }

    /**
     * 执行。
     *
     * @param root 工作区根（参考图与产物的边界基准）
     */
    fun run(
        root: File,
        prompt: String,
        size: String?,
        count: Int?,
        reference: String?,
    ): ToolOutcome {
        val text = prompt.trim()
        if (text.isEmpty()) return ToolOutcome.Failed("缺少必填参数「$ARG_PROMPT」：告诉我要画什么。")
        val aspect = GenerateImage.normalizeSize(size)
            ?: return ToolOutcome.Failed(
                "不认识的比例「${size?.trim()}」：只认 ${GenerateImage.SIZES.joinToString(" / ")}。",
            )
        val n = GenerateImage.normalizeCount(count)

        val e0 = env ?: return ToolOutcome.Failed(NO_ENV)
        // 装配时的 provider 可能已经过时（当时池子全在冷却）：执行时再问一次 Key。
        val liveKey = e0.provider.apiKey.ifBlank { e0.keyProvider?.invoke().orEmpty().trim() }
        if (liveKey.isBlank()) return ToolOutcome.Failed(NO_KEY)
        val e = if (liveKey == e0.provider.apiKey) e0 else e0.copy(provider = e0.provider.copy(apiKey = liveKey))

        // 参考图全部在联网前验完：白烧一张额度再报「图不存在」是最糟的顺序。
        val ref: Pair<String, String>? = if (reference.isNullOrBlank()) {
            null
        } else {
            val file = resolve(root, reference.trim())
            if (!PathGuard.isInside(root, file)) {
                return ToolOutcome.Rejected(
                    "参考图越界被拒：$reference 不在工作区（${root.absolutePath}）内。",
                )
            }
            if (!file.isFile) return ToolOutcome.Failed("参考图不存在：$reference（先用 list_dir 看清有什么）")
            if (file.length() > GenerateImage.MAX_REFERENCE_BYTES) {
                return ToolOutcome.Failed(
                    "参考图太大：${file.length()} 字节（上限 ${GenerateImage.MAX_REFERENCE_BYTES} 字节）。",
                )
            }
            val mime = GenerateImage.mimeOfFile(file.name)
                ?: return ToolOutcome.Failed("参考图只认 png/jpg/webp：${file.name}。")
            val bytes = runCatching { file.readBytes() }.getOrNull()
                ?: return ToolOutcome.Failed("读不了参考图：$reference。")
            mime to Base64.getEncoder().encodeToString(bytes)
        }

        // 模型不写死：先拉清单挑一把；清单里没有 Pro/3.1 ⇒ 如实报错，不回退 2.5。
        val (model, modelError) = resolveModel(e)
        if (model == null) return ToolOutcome.Failed(modelError ?: "挑不出可用的生图模型：稍后再试。")
        e.log("抱朴生图：模型 $model（来自 /models 清单）")

        val dir = e.artifactDir(root)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return ToolOutcome.Failed("建不了产物目录：${dir.absolutePath}。")
        }
        val stamp = TimeFormats.fileStamp(e.now())
        val lines = mutableListOf<String>()
        for (i in 0 until n) {
            when (val one = generateOne(e, root, model, text, aspect, ref, dir, stamp, i)) {
                is ToolOutcome.Ok -> lines += one.text
                is ToolOutcome.Rejected -> return one
                is ToolOutcome.Failed -> {
                    // 已落盘的几张要如实交代，不让它们变成「看不见的额度」。
                    val prefix = if (lines.isEmpty()) "" else "已落盘 ${lines.size} 张：${lines.joinToString("；")}。"
                    return ToolOutcome.Failed(prefix + one.error)
                }
            }
        }
        val where = displayPath(root, dir)
        val head = if (n == 1) {
            "画好了（1 张，在 $where/）："
        } else {
            "画好了（$n 张，在 $where/）："
        }
        return ToolOutcome.Ok(
            head + "\n" + lines.joinToString("\n") { "· $it" } +
                "\n（产物只在本机，没有上传；要发给别人，直接拿这个文件发。）",
        )
    }

    /**
     * 拉 `{base}/models` 挑模型；返回 (模型, 错误)。
     *
     * 这是每次生图**唯一**的清单请求（不是每张一次）；Key 走 `x-goog-api-key` 头，
     * 不进 URL；失败只记状态码，响应体原文不进出话。
     */
    private fun resolveModel(e: Env): Pair<String?, String?> {
        val request = Request.Builder()
            .url(GenerateImage.imageModelsUrl(e.provider.baseUrl))
            .header("Accept", "application/json")
            .apply { LlmWire.headersOf(e.provider).forEach { (k, v) -> header(k, v) } }
            .build()
        val body = try {
            e.http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    e.log("抱朴生图：拉模型清单 HTTP ${resp.code}")
                    return null to GenerateImage.errorText(resp.code)
                }
                resp.body?.string().orEmpty()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            e.log("抱朴生图：拉模型清单失败 ${t.javaClass.simpleName}")
            return null to "连不上 Gemini 的模型清单（${t.javaClass.simpleName}）：检查网络后再试。"
        }
        val model = GenerateImage.pickImageModel(body)
            ?: return null to "当前 Key/端点下没有可用的生图模型：清单里既没有 " +
            "${GenerateImage.PREFERRED_PRO} 也没有 ${GenerateImage.PREFERRED_FLASH}。" +
            "去 Gemini 确认这把 Key 开通了图片模型（本工具不会退回旧模型）。"
        return model to null
    }

    /** 画一张：一次请求、一张图、一个文件。 */
    private fun generateOne(
        e: Env,
        root: File,
        model: String,
        prompt: String,
        aspect: String,
        ref: Pair<String, String>?,
        dir: File,
        stamp: String,
        index: Int,
    ): ToolOutcome {
        val url = LlmWire.endpoint(e.provider.protocol, e.provider.baseUrl, model, stream = false)
        val request = Request.Builder()
            .url(url)
            .apply {
                post(GenerateImage.requestBody(prompt, aspect, ref).toString().toRequestBody(JSON))
                LlmWire.headersOf(e.provider).forEach { (k, v) -> header(k, v) }
            }
            .build()

        val json = try {
            e.http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 只记状态码：响应体可能含提示词回声，Key 也绝不进日志。
                    e.log("抱朴生图：HTTP ${resp.code}（模型 $model）")
                    return ToolOutcome.Failed(GenerateImage.errorText(resp.code))
                }
                runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                    ?: return ToolOutcome.Failed("Gemini 的响应不是合法 JSON：稍后再试。")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            e.log("抱朴生图：网络失败 ${t.javaClass.simpleName}")
            return ToolOutcome.Failed("连不上 Gemini（${t.javaClass.simpleName}）：检查网络后再试。")
        }

        val images = GenerateImage.inlineImagesOf(json)
        if (images.isEmpty()) return ToolOutcome.Failed(GenerateImage.noImageText(json))

        // 一次请求只落第一张：多回也只存一张，避免「一次请求悄悄存三份」。
        val img = images.first()
        val ext = GenerateImage.extForMime(img.mime)
            ?: return ToolOutcome.Failed("Gemini 回的图片格式不认识：${img.mime}（只认 png/jpg/webp）。")
        val bytes = runCatching { Base64.getDecoder().decode(img.base64) }.getOrNull()
            ?: return ToolOutcome.Failed("Gemini 回的图片数据坏了（base64 解不开）：再试一次。")
        val base = GenerateImage.baseName(stamp, prompt, index)
        val dims = GenerateImage.dimensionsOf(bytes)
        val sizeText = if (dims == null) "尺寸未识别" else "${dims.first}×${dims.second}"

        // 第一百九十九批：生图是**给人看的**（相册可见）⇒ 先走 ContentResolver 进
        // `Pictures/证道/图片/`，一份图只落一处（设计 §5.2），不再往 `产物/` 双写。
        // 相册通道不可用（没权限/装配缺失）⇒ 才落回 `产物/`，并在回执里说明白。
        var albumMiss: String? = null
        when (val pub = e.publishAlbum?.invoke(bytes, "$base.$ext", img.mime, ext)) {
            is MediaPublish.Ok -> return ToolOutcome.Ok(
                "画好了，已存进手机相册：${pub.entry.relPath} · $sizeText · ${bytes.size} 字节" +
                    if (!pub.ledgerRecorded) "（归属清单没写成，删的时候会再问一次）" else "",
            )

            is MediaPublish.Duplicate -> return ToolOutcome.Ok(
                "画好了，但这一张和相册里已有的那张一模一样（${pub.entry.relPath}），" +
                    "没有重复入库 · $sizeText · ${bytes.size} 字节",
            )

            is MediaPublish.Unavailable -> albumMiss = pub.reason
            null -> Unit
        }

        val target = GenerateImage.uniqueTarget(dir, base, ext)
        runCatching { atomicWrite(target, bytes) }.onFailure {
            return ToolOutcome.Failed(
                "图片存不进 ${displayPath(root, target)}：${it.javaClass.simpleName}。",
            )
        }
        val tail = albumMiss?.let { "（相册没写成：$it；这张只在 产物/，相册里看不到）" } ?: ""
        return ToolOutcome.Ok("${displayPath(root, target)} · $sizeText · ${bytes.size} 字节$tail")
    }

    /** 工作区内的显示路径（相对根、正斜杠）；拿不到就退回绝对路径。 */
    private fun displayPath(root: File, f: File): String = runCatching {
        f.absoluteFile.toRelativeString(root.absoluteFile).replace('\\', '/')
    }.getOrDefault(f.absolutePath)

    private fun resolve(root: File, path: String): File =
        if (File(path).isAbsolute) File(path) else File(root, path)

    /** 原子写：先写 `.tmp` 再 rename（与 `ImageOpsTool` / `LibraryPaths.atomicWrite` 同口径）。 */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}
