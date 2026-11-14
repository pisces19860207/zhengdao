// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开规范：PNG（RFC 2083，IHDR 在签名之后固定偏移）、JPEG（ITU-T T.81，SOF 段带高宽）、
//   WebP 容器（RIFF；Google 公开文档：VP8 / VP8L / VP8X 三种位流头）、
//   ISO/IEC 23008-12（HEIF：`ftyp` 盒的 brand 标识 heic/heix/hevc/mif1…）。
//
// 第六百三十二批 · 图片尺寸探测（像素宽高 + 类型）——只读文件头，不解码像素。
// 第六百四十二批 · 加 WebP（三种位流都读得出宽高）与 HEIC（只认品牌；宽高由解码后量）。
//
// 为什么需要它：docx 里插图必须给 `wp:extent`（EMU 单位的长宽），
// 不给或给错，Word 要么显示成 0×0、要么把图拉变形。全解码一遍只为拿宽高是浪费：
// PNG 的宽高在 IHDR 固定偏移、JPEG 在第一个 SOF 段、WebP 在 RIFF 块头，读几十个字节就够。
package com.example.zhengdao.baopu.office

/** 探测结果：图片类型（用于 Content_Types 扩展名）＋像素宽高。 */
internal data class ImageInfo(val ext: String, val width: Int, val height: Int)

/** 认得出来的图片类型（642 起四种）。 */
internal enum class ImageKind {

    /** PNG：docx 原生能嵌，原样写。 */
    PNG,

    /** JPEG：docx 原生能嵌，原样写。 */
    JPEG,

    /** WebP：Android 原生能画（API 26+），docx 里按 `image/webp` 写（Word 365 / WPS 认）。 */
    WEBP,

    /** HEIC/HEIF：Android 9（API 28）起能解码，但 **docx 里必须换成 PNG** 才打得开。 */
    HEIC,

    /** 别的（GIF/BMP/TIFF/AVIF…）：明确拒绝并说人话，不硬塞进 Word。 */
    OTHER,
}

/**
 * 图片头探测（**纯函数**，JVM 直测）。
 *
 * 只读字节、不解码像素：调用方（工具层）先把关大小与张数，这里只回答"这是什么图、多大"。
 */
internal object ImageProbe {

    private const val PNG_SIG_LEN = 8

    /** 类型判定（**不看扩展名**——后缀骗人的文件很常见）。 */
    fun kindOf(bytes: ByteArray): ImageKind = when {
        png(bytes) != null -> ImageKind.PNG
        jpeg(bytes) != null -> ImageKind.JPEG
        webpSize(bytes) != null -> ImageKind.WEBP
        // 容器对但位流认不出（极少）：仍当 webp，交给解码层去画
        isWebpContainer(bytes) -> ImageKind.WEBP
        heic(bytes) -> ImageKind.HEIC
        else -> ImageKind.OTHER
    }

    /** 宽高（HEIC 给不出——尺寸要先解码；那条路走 [ImageEmbed]）。 */
    fun probe(bytes: ByteArray): ImageInfo? {
        png(bytes)?.let { return it }
        jpeg(bytes)?.let { return it }
        return webpSize(bytes)
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

    /** `RIFF????WEBP` 容器头（宽高要看后面的块）。 */
    private fun isWebpContainer(bytes: ByteArray): Boolean =
        bytes.size >= 30 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")

    /**
     * WebP 宽高：`RIFF <size> WEBP` 之后是第一个块，三种位流各有各的位置：
     *
     * - `VP8 `（有损）：帧标签 3 字节 ＋ 起始码 `9D 01 2A`，随后宽/高各 2 字节（低 14 位有效）；
     * - `VP8L`（无损）：`0x2F` 之后 4 字节里，前 14 位 = 宽-1、接着 14 位 = 高-1；
     * - `VP8X`（扩展，带透明/动画/元数据）：画布宽-1、高-1 各 3 字节（小端）。
     *
     * 三种都要认：聊天/浏览器存下来的多为 VP8 ，截图工具常给 VP8L，带透明或动画的给 VP8X。
     * 只认一种就会把"明明能打开"的图判成不认识。
     */
    private fun webpSize(bytes: ByteArray): ImageInfo? {
        if (!isWebpContainer(bytes)) return null
        return when (asciiAt(bytes, 12, 4)) {
            "VP8 " -> {
                val ok = bytes.size >= 30 &&
                    (bytes[23].toInt() and 0xFF) == 0x9D &&
                    (bytes[24].toInt() and 0xFF) == 0x01 &&
                    (bytes[25].toInt() and 0xFF) == 0x2A
                if (!ok) return null
                withDims(le16(bytes, 26) and 0x3FFF, le16(bytes, 28) and 0x3FFF)
            }
            "VP8L" -> {
                if (bytes.size < 25) return null
                val bits = leInt(bytes, 21)
                withDims((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
            }
            "VP8X" -> withDims(le24(bytes, 24) + 1, le24(bytes, 27) + 1)
            else -> null
        }
    }

    private fun withDims(w: Int, h: Int): ImageInfo? =
        if (w > 0 && h > 0) ImageInfo("webp", w, h) else null

    /**
     * HEIC/HEIF：ISO-BMFF 的 `ftyp` 盒（`00 00 00 xx ftyp` ＋ 主品牌）。
     *
     * 只认品牌不认宽高：宽高要读 `meta/iprp/ipco/ispe`，而**我们并不需要它**——
     * HEIC 一律先解码成 PNG（[ImageEmbed]），尺寸从解码后的位图量。
     */
    private fun heic(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (!ascii(bytes, 4, "ftyp")) return false
        return asciiAt(bytes, 8, 4).lowercase() in HEIF_BRANDS
    }

    /** HEIF 家族的常见主品牌（**不含 avif**：另一种编解码，Word 也不认）。 */
    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

    private fun isSof(marker: Int): Boolean =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private fun ascii(bytes: ByteArray, at: Int, text: String): Boolean {
        if (at + text.length > bytes.size) return false
        for (i in text.indices) if (bytes[at + i] != text[i].code.toByte()) return false
        return true
    }

    private fun asciiAt(bytes: ByteArray, at: Int, len: Int): String {
        if (at + len > bytes.size) return ""
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append((bytes[at + i].toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private fun beInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le24(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16)

    private fun leInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
