// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第二十四批 C 段（组装半段）· 把工作区里的图片文件读成 [VisionRules.ImagePayload]。
//
// 为什么要有这一层：[VisionRules] 明确写着「把本机文件读成 base64 是 IO，归调用方做，
// 这里只收已经读好的 payload」——本文件就是那个「调用方」。放在 `baopu/` 而不是
// `ui/baopu/`，因为它服务的是**引擎**（RunEngine 在自己的线程上调），不是界面。
//
// 四条纪律：
//   1. **路径只活在这里**：读出来的 [VisionRules.ImagePayload] 只有 dataUrl ＋ 字节数，
//      结构上装不下路径（V6）。出了这个函数，本机路径就没了。
//   2. **越界一律跳过**：用 `canonicalFile` 判断「解析后还在工作区里」，
//      这样 `../`、软链、绝对路径三种绕法都被同一条判据挡住（不靠字符串前缀比对）。
//   3. **读不了就跳过，不抛**：单张图坏了不该让整轮请求失败。
//   4. **跳过的必须能说出来**（2026-10-18 真机教训）：从前超 2 MB 的图**直接丢**，
//      `VisionRules.select` 收到空列表连说明都没有 ⇒ 用户发 3 MB 照片，模型只看到
//      「用户附了 1 张图片」，于是回「我看不到」，用户以为视觉被关了。
//      ⇒ 现在：12 MB 以内**原图直发**；超过的、以及 HEIC 这类接口不吃的格式，交给 `ImageShrink`
//      重编码一次；缩不成也**逐张记原因**，由调用方拼进本轮输入。
package com.example.zhengdao.baopu

import java.io.File
import java.util.Base64

/**
 * 工作区图片 ⇒ 可发送的 payload 列表。
 *
 * 纯 IO、无 Android 依赖（JVM 单测直接喂临时目录；缩图通过 [load] 的 `shrink` 注入，
 * 真实现是 `ImageShrink`）。
 */
object VisionFiles {

    /** 认脸只认扩展名：认不出的一律不收（不猜、不嗅探、不把未知字节当图片发出去）。 */
    private val MIME_BY_EXT: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
    )

    /** 缩图结果（缩图本身要 Android Bitmap，所以由外部注入，本文件只认这两种结果）。 */
    sealed class ShrinkOutcome {
        class Ok(val bytes: ByteArray) : ShrinkOutcome()
        class Failed(val reason: String) : ShrinkOutcome()
    }

    /** 没能发出去的一张图 ＋ **原因**（原因必须是人话：它会出现在模型和用户眼前）。 */
    data class Skipped(val relPath: String, val reason: String)

    /**
     * 一次读图的结果。
     *
     * @param payloads 要发出去的图（顺序与入参一致）
     * @param skipped 没发出去的（**一张都不许漏记**）
     * @param shrunk 被缩过的相对路径（只用于日志与说明，不进 payload）
     */
    data class Loaded(
        val payloads: List<VisionRules.ImagePayload> = emptyList(),
        val skipped: List<Skipped> = emptyList(),
        val shrunk: List<String> = emptyList(),
    ) {
        /**
         * 拼在本轮输入末尾的说明。空串 = 全发了、无需解释。
         *
         * 只点名前 [MAX_LISTED] 张，剩下用「另外 N 张同理」收口 —— 一次十张全解不开时，
         * 十行原因会把本轮输入撑得比正文还长。
         */
        fun note(): String {
            if (skipped.isEmpty()) return ""
            val listed = skipped.take(MAX_LISTED).joinToString("；") { "${it.relPath}（${it.reason}）" }
            val rest = skipped.size - minOf(MAX_LISTED, skipped.size)
            val tail = if (rest > 0) "；另外 $rest 张同理" else ""
            return "（有 ${skipped.size} 张图片没发出去：$listed$tail" +
                "。请先用文字把图里的关键内容说一遍，别猜。）"
        }

        companion object {
            /** 说明里最多点名几张。 */
            const val MAX_LISTED = 3
        }
    }

    /**
     * 把 [relPaths]（**相对工作区**的路径）读成 payload。
     *
     * 逐张过闸，任何一条不过就**记进 [Loaded.skipped] 并跳过这一张**（不抛、不中断整批）：
     * - 解析后不在 [root] 里（越界）
     * - 不是普通文件 / 空文件
     * - 扩展名认不出
     * - 超过 [cap] 或格式接口不吃（HEIC/BMP）：先交给 [shrink] 重编码；没有 shrink 或失败 ⇒ 记原因
     * - 读盘失败
     *
     * @param shrink 缩图实现（真机传 `ImageShrink::toJpeg`，JVM 测试传假的）
     * @return 顺序与入参一致的 payload ＋ 全部跳过原因
     */
    fun load(
        root: File,
        relPaths: List<String>,
        cap: Int = VisionRules.MAX_IMAGE_BYTES,
        shrink: ((File) -> ShrinkOutcome)? = null,
    ): Loaded {
        if (relPaths.isEmpty()) return Loaded()
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return Loaded()
        val prefix = base.path + File.separator
        val out = ArrayList<VisionRules.ImagePayload>(relPaths.size)
        val skipped = ArrayList<Skipped>(0)
        val shrunk = ArrayList<String>(0)
        for (rel in relPaths) {
            if (rel.isBlank()) {
                skipped += Skipped(rel, "路径是空的")
                continue
            }
            val file = runCatching { File(base, rel).canonicalFile }.getOrNull()
            if (file == null) {
                skipped += Skipped(rel, "路径解析不了")
                continue
            }
            // 越界判据：解析后的真实路径必须仍在工作区里（`../`、软链、绝对路径都是这一条挡）
            if (!file.path.startsWith(prefix)) {
                skipped += Skipped(rel, "不在工作区里（越界）")
                continue
            }
            if (!file.isFile) {
                skipped += Skipped(rel, "文件不在了")
                continue
            }
            val len = file.length()
            if (len <= 0L) {
                skipped += Skipped(rel, "是空文件")
                continue
            }
            val declared = mimeOf(file.name)
            // HEIC/BMP 这类系统能解码、接口不吃的格式：走重编码转 JPEG（不丢图）
            val convertible = declared == null && isConvertible(file.name)
            if (declared == null && !convertible) {
                skipped += Skipped(rel, "扩展名认不出，不当图片发")
                continue
            }
            if (len > cap || convertible) {
                // 超限不丢：先缩（手机照片动辄 3 MB，2 MB 硬线会把它们全部吞掉）
                if (shrink == null) {
                    skipped += Skipped(rel, "超过单张 ${cap / 1024 / 1024} MB 上限，且没有缩图实现")
                    continue
                }
                when (val r = runCatching { shrink(file) }.getOrNull()) {
                    is ShrinkOutcome.Ok -> {
                        if (r.bytes.isEmpty() || r.bytes.size > cap) {
                            skipped += Skipped(rel, "缩完还是超过 ${cap / 1024 / 1024} MB")
                            continue
                        }
                        shrunk += rel
                        out.add(payloadOf(if (convertible) "image/jpeg" else declared!!, r.bytes))
                    }
                    is ShrinkOutcome.Failed -> skipped += Skipped(rel, r.reason)
                    null -> skipped += Skipped(rel, "缩图时抛异常")
                }
                continue
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                skipped += Skipped(rel, "读盘失败")
                continue
            }
            out.add(payloadOf(declared!!, bytes))
        }
        return Loaded(payloads = out, skipped = skipped, shrunk = shrunk)
    }

    /**
     * 只要 payload（老调用口径，保留给单测与兼容）。
     *
     * ⚠️ 新代码请用 [load]：这个方法**丢掉跳过原因**，正是 2026-10-18 那次的病根。
     */
    fun payloadsOf(root: File, relPaths: List<String>): List<VisionRules.ImagePayload> =
        load(root, relPaths).payloads

    private fun payloadOf(mime: String, bytes: ByteArray): VisionRules.ImagePayload {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return VisionRules.ImagePayload(
            dataUrl = VisionRules.DATA_URL_PREFIX + mime.removePrefix("image/") + ";base64," + b64,
            bytes = bytes.size,
        )
    }

    /** 系统能解码、接口不吃的格式（HEIC/HEIF 手机默认拍照格式；BMP 少见但也能解）。 */
    private val CONVERT_EXTS: Set<String> = setOf("heic", "heif", "bmp")

    /** 要不要走「重编码成 JPEG」这条路（格式不支持，或文件超过上限）。 */
    internal fun isConvertible(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in CONVERT_EXTS
    }

    /** 扩展名 ⇒ MIME；认不出回 null。 */
    internal fun mimeOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return null
        return MIME_BY_EXT[fileName.substring(dot + 1).lowercase()]
    }
}
