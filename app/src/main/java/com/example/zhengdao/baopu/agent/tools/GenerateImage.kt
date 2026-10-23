// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百五十八批：`generate_image` 的**判据层**——纯函数、零 IO、零 Android，JVM 直测；
// 真网络与落盘在 `GenerateImageTool`（分工照 `ImageOps` / `ImageOpsTool`）。
//
// 五条规矩：
// 1. **原图永不覆盖**：产物名 `<时间戳>-<slug>.<ext>`，同名就加 `-2/-3`；
// 2. 落点只在本机：默认工作区（手机共享存储里的「证道」）落 `资料库/整理/`；
//    自定义/私有工作区落 `<工作区>/产物/`——两处都是"本地产物"，不上传；
// 3. count 上限 4：一次请求画一张（多张就多请求），确认卡上先说清烧几次额度；
// 4. size 只认 Gemini 的五个宽高比，缺省 1:1；写了别的**如实报错**，不偷偷换默认值；
// 5. 响应里的 base64 只在内存里解码后落盘，绝不进出话/回执/日志；
// 6. **模型不写死**（2026-10-23 用户口径更正）：每次从 `{base}/models` 清单里挑
//    Nano Banana Pro（gemini-3-pro-image），没有就退 Nano Banana 2（gemini-3.1-flash-image）；
//    两者都没有就如实报错，**绝不**回退 `gemini-2.5-flash-image`。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ModelCatalog
import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object GenerateImage {

    /** 首选生图模型（Nano Banana Pro）。只在清单**实测存在**时才用。 */
    const val PREFERRED_PRO = "gemini-3-pro-image"

    /** 次选生图模型（Nano Banana 2）。 */
    const val PREFERRED_FLASH = "gemini-3.1-flash-image"

    /** 清单一次拉全（Gemini 默认 50 条，图片模型可能落在后面；端点上限 1000）。 */
    const val MODELS_PAGE_SIZE = 1000

    /** 一次最多画几张（每张一条请求、分别烧额度）。 */
    const val MAX_COUNT = 4

    /** 不写 size 时 Gemini 的默认宽高比。 */
    const val DEFAULT_SIZE = "1:1"

    /** Gemini `imageConfig.aspectRatio` 认的比例（写别的会被 400 拒，不如先报清楚）。 */
    val SIZES: List<String> = listOf("1:1", "4:3", "3:4", "16:9", "9:16")

    /** 自定义/私有工作区的产物子目录（默认工作区走 `资料库/整理`）。 */
    const val SUBDIR = "产物"

    /** 参考图上限（base64 之后约 ×1.37，8MB 原图 ~11MB 请求体，已比常见网关上限保守）。 */
    const val MAX_REFERENCE_BYTES = 8L * 1024 * 1024

    /**
     * 产物目录。
     *
     * 为什么两套：派工单写「`资料库/整理/<时间戳>-<slug>.png`（工作区模式落 `产物/`）」——
     * 默认工作区就是手机里那个固定的「证道」，图片进资料库的「整理」栏，用户能在资料库里
     * 直接看到；自定义/私有工作区没有资料库语义，就落工作区自己的 `产物/`。
     */
    fun artifactDir(workspace: File, libraryMode: Boolean): File =
        if (libraryMode) LibraryPaths.doneDirUnder(workspace) else File(workspace, SUBDIR)

    /** 数量口径：不给 ⇒ 1；越界夹到 1..[MAX_COUNT]（Schema 已挡一层，这里是第二层）。 */
    fun normalizeCount(raw: Int?): Int = (raw ?: 1).coerceIn(1, MAX_COUNT)

    /** 比例口径：空 ⇒ 1:1；不在白名单 ⇒ null（调用方报错）。 */
    fun normalizeSize(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return DEFAULT_SIZE
        return s.takeIf { it in SIZES }
    }

    /**
     * 文件名用的 slug（字母/数字/汉字保留，其余折成 `_`；最长 [maxChars]，兜底「图片」）。
     *
     * 为什么按字符而不是按字节：文件名是给人看的，截半个汉字比截短更难解释。
     */
    fun slugOf(prompt: String, maxChars: Int = 24): String {
        val sb = StringBuilder()
        for (ch in prompt.trim()) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else if (sb.isNotEmpty() && sb.last() != '_') {
                sb.append('_')
            }
        }
        while (sb.endsWith("_")) sb.setLength(sb.length - 1)
        val cut = if (sb.length > maxChars) sb.substring(0, maxChars) else sb.toString()
        return cut.trimEnd('_').ifEmpty { "图片" }
    }

    /** `<stamp>-<slug>`；第 2 张起再加 `-2`、`-3`（第 1 张不带序号）。 */
    fun baseName(stamp: String, prompt: String, index: Int = 0): String =
        if (index <= 0) "$stamp-${slugOf(prompt)}" else "$stamp-${slugOf(prompt)}-${index + 1}"

    /** 不覆盖同名：目标已存在就依次试 `-2`、`-3`…… */
    fun uniqueTarget(dir: File, base: String, ext: String = "png"): File {
        val first = File(dir, "$base.$ext")
        if (!first.exists()) return first
        var n = 2
        while (true) {
            val next = File(dir, "$base-$n.$ext")
            if (!next.exists()) return next
            n++
        }
    }

    /** MIME ⇒ 扩展名；认不出回 null（不把陌生格式硬存成 .png）。 */
    fun extForMime(mime: String?): String? = when (mime?.trim()?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> null
    }

    /** 参考图扩展名 ⇒ MIME（图生图的 `inline_data.mime_type`）。 */
    fun mimeOfFile(name: String): String? = when (ImageOps.formatOf(name)) {
        "png" -> "image/png"
        "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }

    /** 模型清单地址（Gemini 原生 `{base}/v1beta/models`，一次拉 [MODELS_PAGE_SIZE] 条）。 */
    fun imageModelsUrl(baseUrl: String): String =
        ModelCatalog.geminiModelsUrl(baseUrl) + "?pageSize=$MODELS_PAGE_SIZE"

    /**
     * 从 `{base}/models` 响应里取**有生图能力**的模型 id（剥掉 `models/` 前缀）。
     *
     * 能力怎么认（任一条成立即可）：`outputModalities` 含 `IMAGE`；或 id 里带 `image`
     * （Gemini 图片模型的命名口径）。若 `supportedGenerationMethods` 明说不能
     * `generateContent` ⇒ 一票否决。坏 JSON 回空表（调用方给可读错误），不猜。
     */
    fun imageModelIds(json: String): List<String> {
        val out = mutableListOf<String>()
        val models = runCatching { JSONObject(json).optJSONArray("models") }.getOrNull() ?: return out
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            val raw = m.optString("name").ifBlank { m.optString("id") }.trim()
            val id = raw.substringAfterLast('/')
            if (id.isBlank()) continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            if (methods != null && methods.length() > 0) {
                val canGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (!canGenerate) continue
            }
            val modalities = m.optJSONArray("outputModalities")
            val declaresImage = modalities != null &&
                (0 until modalities.length()).any { modalities.optString(it).equals("IMAGE", ignoreCase = true) }
            if (!declaresImage && !id.contains("image", ignoreCase = true)) continue
            out += id
        }
        return out.distinct()
    }

    /**
     * 挑这次真要用的模型：Pro 优先、3.1 Flash 次之；都没有 ⇒ null（调用方报错）。
     *
     * 只认这两个族（`-preview`/`-001` 这类后缀算同一族，取最短名），**绝不**退回
     * `gemini-2.5-flash-image`——2026-10-23 用户明确「别再用 2.5」。
     */
    fun pickImageModel(json: String): String? {
        val ids = imageModelIds(json)
        fun pick(family: String): String? =
            ids.filter { it == family || it.startsWith("$family-") }.minByOrNull { it.length }
        return pick(PREFERRED_PRO) ?: pick(PREFERRED_FLASH)
    }

    /**
     * 请求体（纯函数，测试逐字段钉形状）。
     *
     * 文生图 = 一个 text part；图生图 = text part ＋ 参考图 inline_data part（Gemini 原生形状）。
     */
    fun requestBody(prompt: String, size: String, reference: Pair<String, String>? = null): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (reference != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", reference.first).put("data", reference.second),
                ),
            )
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                    .put("imageConfig", JSONObject().put("aspectRatio", size)),
            )
    }

    /** 一张返回的图（base64 原文只在这里过一手，调用方解码后立刻落盘）。 */
    data class InlineImage(val mime: String, val base64: String)

    /**
     * 从 Gemini 响应里取所有 inline 图。
     *
     * 两种写法都认：REST 的 `inlineData/mimeType` 与文档里常见的 `inline_data/mime_type`——
     * 服务端换一种拼法不该让工具整条瞎掉（本项目在 Gemini 分支已按同一口径容过）。
     */
    fun inlineImagesOf(json: JSONObject): List<InlineImage> {
        val out = mutableListOf<InlineImage>()
        val candidates = json.optJSONArray("candidates") ?: return out
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val inline = part.optJSONObject("inline_data") ?: part.optJSONObject("inlineData") ?: continue
                val data = inline.optString("data")
                if (data.isBlank()) continue
                val mime = inline.optString("mime_type").ifBlank { inline.optString("mimeType") }
                    .ifBlank { "image/png" }
                out += InlineImage(mime, data)
            }
        }
        return out
    }

    /** 没图时的人话：先看提示词被拦，再看候选的 finishReason。 */
    fun noImageText(json: JSONObject): String {
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.ifBlank { null }?.let {
            return "Gemini 拒绝了这段提示词（blockReason=$it）：换个说法再试。"
        }
        val reason = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optString("finishReason")?.ifBlank { null }
        return when {
            reason == null -> "Gemini 没返回图片（响应里没有 inlineData）：稍后再试，或把提示词写具体些。"
            reason == "STOP" -> "Gemini 只回了文字、没给图（提示词可能不像在要图）：把「画一张……」说具体些再试。"
            else -> "Gemini 没返回图片（finishReason=$reason）：换个提示词再试。"
        }
    }

    /** HTTP 状态 ⇒ 人话（**不带响应体原文**：它可能含提示词回声，也不该进 UI）。 */
    fun errorText(code: Int): String = when (code) {
        400 -> "Gemini 说这个请求不成立（HTTP 400）：检查提示词或参考图，必要时把要求写简单些。"
        401, 403 -> "Gemini Key 无效或没有权限（HTTP $code）：去「设置 → 通道 → Google Gemini」检查 Key。"
        404 -> "Gemini 说地址或模型不存在（HTTP 404）：检查 base URL，以及这把 Key 是否开通了图片模型。"
        429 -> "Gemini 额度已用尽或请求太频繁（HTTP 429）：过一会儿再试。"
        in 500..599 -> "Gemini 服务端暂时出错（HTTP $code）：过一会儿再试（本工具不自动重试，免得重复烧额度）。"
        else -> "Gemini 返回 HTTP $code：检查 Key 与网络后再试。"
    }

    /**
     * 图片像素尺寸（PNG/JPEG/WebP；认不出回 null）。
     *
     * 为什么不用 BitmapFactory：JVM 单测里没有 Android 实现，而「回执要带尺寸」是判据之一；
     * 三种头都是公开格式的固定字段，纯解析反而能在门禁里逐字节钉死。
     */
    fun dimensionsOf(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size >= 24 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            val w = u32be(bytes, 16)
            val h = u32be(bytes, 20)
            return if (w > 0 && h > 0) w to h else null
        }
        if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8) {
            return jpegSize(bytes)
        }
        if (bytes.size >= 16 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return webpSize(bytes)
        }
        return null
    }

    private fun u32be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 3 < b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) {
                i++
                continue
            }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2) return null
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 8 >= b.size) return null
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return if (w > 0 && h > 0) w to h else null
            }
            i += 2 + len
        }
        return null
    }

    private fun webpSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30) return null
        return when (String(b, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> {
                val w = ((b[26].toInt() and 0xFF) or ((b[27].toInt() and 0xFF) shl 8)) and 0x3FFF
                val h = ((b[28].toInt() and 0xFF) or ((b[29].toInt() and 0xFF) shl 8)) and 0x3FFF
                if (w > 0 && h > 0) w to h else null
            }

            "VP8L" -> {
                val bits = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or
                    ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
            }

            "VP8X" -> {
                val w = ((b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or
                    ((b[26].toInt() and 0xFF) shl 16)) + 1
                val h = ((b[27].toInt() and 0xFF) or ((b[28].toInt() and 0xFF) shl 8) or
                    ((b[29].toInt() and 0xFF) shl 16)) + 1
                w to h
            }

            else -> null
        }
    }
}
