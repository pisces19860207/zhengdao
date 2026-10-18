// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第二十四批 C 段（组装半段）· 把工作区里的图片文件读成 [VisionRules.ImagePayload]。
//
// 为什么要有这一层：[VisionRules] 明确写着「把本机文件读成 base64 是 IO，归调用方做，
// 这里只收已经读好的 payload」——本文件就是那个「调用方」。放在 `baopu/` 而不是
// `ui/baopu/`，因为它服务的是**引擎**（RunEngine 在自己的线程上调），不是界面。
//
// 三条纪律：
//   1. **路径只活在这里**：读出来的 [VisionRules.ImagePayload] 只有 dataUrl ＋ 字节数，
//      结构上装不下路径（V6）。出了这个函数，本机路径就没了。
//   2. **越界一律跳过**：用 `canonicalFile` 判断「解析后还在工作区里」，
//      这样 `../`、软链、绝对路径三种绕法都被同一条判据挡住（不靠字符串前缀比对）。
//   3. **读不了就跳过，不抛**：单张图坏了不该让整轮请求失败。跳过的张数由
//      [VisionRules.select] 如实说出来（它那边有「省略了几张」的口径）。
package com.example.zhengdao.baopu

import java.io.File
import java.util.Base64

/**
 * 工作区图片 ⇒ 可发送的 payload 列表。
 *
 * 纯 IO、无 Android 依赖（JVM 单测直接喂临时目录）。
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

    /**
     * 把 [relPaths]（**相对工作区**的路径）读成 payload。
     *
     * 逐张过闸，任何一条不过就**跳过这一张**（不抛异常、不中断整批）：
     * - 解析后不在 [root] 里（越界）⇒ 跳过
     * - 不是普通文件 / 空文件 ⇒ 跳过
     * - 超过 [VisionRules.MAX_IMAGE_BYTES] ⇒ 跳过（**先看 `length()` 再读**，不把 50 MB 读进内存）
     * - 扩展名认不出 ⇒ 跳过
     * - 读盘失败 ⇒ 跳过
     *
     * @return 顺序与入参一致的 payload（被跳过的位置上没有元素）
     */
    fun payloadsOf(root: File, relPaths: List<String>): List<VisionRules.ImagePayload> {
        if (relPaths.isEmpty()) return emptyList()
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        val prefix = base.path + File.separator
        val out = ArrayList<VisionRules.ImagePayload>(relPaths.size)
        for (rel in relPaths) {
            if (rel.isBlank()) continue
            val file = runCatching { File(base, rel).canonicalFile }.getOrNull() ?: continue
            // 越界判据：解析后的真实路径必须仍在工作区里（`../`、软链、绝对路径都是这一条挡）
            if (!file.path.startsWith(prefix)) continue
            if (!file.isFile) continue
            val len = file.length()
            if (len <= 0L || len > VisionRules.MAX_IMAGE_BYTES) continue
            val mime = mimeOf(file.name) ?: continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            val b64 = Base64.getEncoder().encodeToString(bytes)
            out.add(
                VisionRules.ImagePayload(
                    dataUrl = VisionRules.DATA_URL_PREFIX + mime.removePrefix("image/") + ";base64," + b64,
                    bytes = bytes.size,
                ),
            )
        }
        return out
    }

    /** 扩展名 ⇒ MIME；认不出回 null。 */
    internal fun mimeOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return null
        return MIME_BY_EXT[fileName.substring(dot + 1).lowercase()]
    }
}
