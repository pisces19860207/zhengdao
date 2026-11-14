// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开规范：PNG（RFC 2083，IHDR 在签名之后固定偏移）、JPEG（ITU-T T.81，SOF 段带高宽）。
//
// 第六百三十二批 · 图片尺寸探测（像素宽高 + 类型）——只读文件头，不解码像素。
//
// 为什么需要它：docx 里插图必须给 `wp:extent`（EMU 单位的长宽），
// 不给或给错，Word 要么显示成 0×0、要么把图拉变形。全解码一遍只为拿宽高是浪费，
// PNG 的宽高在 IHDR 固定偏移、JPEG 在第一个 SOF 段里，读几十个字节就够。
package com.example.zhengdao.baopu.office

/** 探测结果：图片类型（用于 Content_Types 扩展名）＋ 像素宽高。 */
internal data class ImageInfo(val ext: String, val width: Int, val height: Int)

/**
 * 图片头探测（**纯函数**，JVM 直测）。
 *
 * 只认两种（判据：docx 插图 ≤2 张，没说格式——但只认最常见的 PNG/JPEG，
 * 别的（webp/heic）明确拒绝并在工具的报错里说人话，不硬塞进 Word）。
 */
internal object ImageProbe {

    private const val PNG_SIG_LEN = 8

    fun probe(bytes: ByteArray): ImageInfo? {
        png(bytes)?.let { return it }
        return jpeg(bytes)
    }

    /** PNG：`89 50 4E 47 0D 0A 1A 0A` ＋ 长度(4) ＋ `IHDR` ＋ 宽(4) ＋ 高(4)，均为大端。 */
    private fun png(bytes: ByteArray): ImageInfo? {
        if (bytes.size < 24) return null
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) if (bytes[i] != sig[i]) return null
        val w = beInt(bytes, PNG_SIG_LEN + 8)
        val h = beInt(bytes, PNG_SIG_LEN + 12)
        return if (w > 0 && h > 0) ImageInfo("png", w, h) else null
    }

    /** JPEG：跳过各段，遇到 SOF（C0–CF，除 C4/C8/CC）就读高宽（高位在前，字节序为大端）。 */
    private fun jpeg(bytes: ByteArray): ImageInfo? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (isSof(marker)) {
                val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                if (w > 0 && h > 0) return ImageInfo("jpeg", w, h)
            }
            if (len < 2) return null
            i += 2 + len
        }
        return null
    }

    private fun isSof(marker: Int): Boolean =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private fun beInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
