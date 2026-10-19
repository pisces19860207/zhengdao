// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md §5（按字节写规格）：
//   §5.1 块布局（大端）／§5.2 tEXt 布局（keyword + 0x00 + text，纯 ASCII base64）
//   §5.3 CRC 覆盖 type+data（不含 length）／§5.4 写入算法（插在 IEND 前、绝不覆盖）
//   §5.5 读回（全文件扫块、ccv3 优先、CRC 错只告警）／§5.6 往返逐字
//   §5.7 内置底图／§5.8 逐字话术／§16.2-ii（导出→读回键集合与键序不变）
//
// ⚠️ 规格 §5 开头写「只许 JDK：… javax.imageio（**仅夹具**）」，§5.7 却要求产品用
// ImageIO 现画 1×1。两者冲突，且 **Android 运行时不带 javax.imageio**（照抄会在真机
// NoClassDefFoundError）。⇒ 本实现取「仅夹具」那一条：产品侧用 `java.util.zip.Deflater`
// 手写一个 1×1 白图编码器（不是硬编码常量字节），夹具侧才用 ImageIO。
// 已登记进报告「规格不一致清单」。
package com.example.zhengdao.baopu.card

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.json.JSONObject

/** 一个 PNG 块（[offset] 是块头起始偏移，报错话术要用）。 */
internal data class PngChunk(
    val type: String,
    val data: ByteArray,
    val crcStored: Long,
    val offset: Int,
)

/** 扫块结果。 */
internal sealed interface PngScan {
    data class Ok(val chunks: List<PngChunk>) : PngScan

    /** [index] 从 1 起（话术「第 n 个数据块」）；[offset] 是该块块头偏移。 */
    data class Broken(val index: Int, val offset: Int) : PngScan
}

/** 写盘结果。 */
internal sealed interface PngWrite {
    data class Ok(val path: File) : PngWrite
    data class Failed(val message: String) : PngWrite
}

/** 读回结果。 */
internal sealed interface PngRead {
    data class Ok(
        val jsonText: String,
        val block: String,
        val priority: Int,
        val textKeys: List<String>,
        val notes: List<String>,
    ) : PngRead

    data class Failed(val message: String) : PngRead
}

/**
 * 酒馆角色卡 PNG 的读写（纯字节，零第三方依赖）。
 *
 * **只有 `tEXt` 算数**：`iTXt`/`zTXt` 不读、只如实登记（§5.2）。
 */
internal object CardPng {

    val SIGNATURE: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    const val KEY_CHARA = "chara"
    const val KEY_CCV3 = "ccv3"

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && (0 until 8).all { bytes[it] == SIGNATURE[it] }

    // ── 块扫描 ───────────────────────────────────────────────────────────

    fun scan(bytes: ByteArray): PngScan {
        if (!isPng(bytes)) return PngScan.Broken(0, 0)
        val chunks = ArrayList<PngChunk>()
        var off = 8
        var index = 1
        while (off < bytes.size) {
            if (bytes.size - off < 8) return PngScan.Broken(index, off)
            val len = intAt(bytes, off)
            if (len < 0 || bytes.size - off - 12 < len) return PngScan.Broken(index, off)
            val type = String(bytes, off + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(off + 8, off + 8 + len)
            val crc = uintAt(bytes, off + 8 + len)
            chunks += PngChunk(type, data, crc, off)
            off += 12 + len
            if (type == "IEND") break
            index++
        }
        return PngScan.Ok(chunks)
    }

    /** 独立复算一个块的 CRC（测试与读回共用；**不调用导出侧的结果**）。 */
    fun crcOf(type: String, data: ByteArray): Long {
        val c = CRC32()
        val t = type.toByteArray(Charsets.US_ASCII)
        c.update(t, 0, t.size)
        c.update(data, 0, data.size)
        return c.value
    }

    // ── 导出（§5.4） ─────────────────────────────────────────────────────

    /**
     * 把一**串** JSON 文本写进 PNG 的 `chara` 与 `ccv3` 两块（两块同一份）。
     *
     * 绝不 parse：一旦 parse→toString，键序／转义／空格全变，往返判据（F8）立刻炸。
     * 绝不覆盖：用 `CREATE_NEW` 让文件系统当裁判（先查后写有竞态窗口）。
     */
    fun export(jsonString: String, baseImage: ByteArray?, outPath: File): PngWrite {
        if (outPath.exists()) return PngWrite.Failed("目标已存在，未覆盖：${outPath.path}")

        val jsonBytes = CardJson.encodeUtf8Strict(jsonString)
            ?: return PngWrite.Failed("源文件不是 UTF-8，无法无损导出。")
        val payload = Base64.getEncoder().encodeToString(jsonBytes)   // 标准、带补齐、不换行

        val base = baseImage ?: whiteOnePixelPng()
        if (!isPng(base)) return PngWrite.Failed("底图不是 PNG，无法写入卡数据。")
        val chunks = when (val s = scan(base)) {
            is PngScan.Broken -> return PngWrite.Failed(
                "底图结构损坏：第 ${s.index} 个数据块（偏移 ${s.offset}）读不下去。",
            )
            is PngScan.Ok -> s.chunks
        }
        val iend = chunks.firstOrNull { it.type == "IEND" }
            ?: return PngWrite.Failed("底图结构损坏：找不到 IEND 块。")

        val head = base.copyOfRange(0, iend.offset)
        val tail = base.copyOfRange(iend.offset, base.size)
        val block1 = textChunk(KEY_CHARA, payload)
        val block2 = textChunk(KEY_CCV3, payload)

        val out = head + block1 + block2 + tail
        return try {
            Files.write(outPath.toPath(), out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            PngWrite.Ok(outPath)
        } catch (e: FileAlreadyExistsException) {
            PngWrite.Failed("目标已存在，未覆盖：${outPath.path}")
        } catch (e: Exception) {
            PngWrite.Failed("写入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 造一个 `tEXt` 块：`len(4) | "tEXt" | keyword + 0x00 + text | crc(4)`。 */
    fun textChunk(keyword: String, text: String): ByteArray {
        val kw = keyword.toByteArray(Charsets.US_ASCII)
        val tx = text.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(kw.size + 1 + tx.size)
        System.arraycopy(kw, 0, data, 0, kw.size)
        data[kw.size] = 0
        System.arraycopy(tx, 0, data, kw.size + 1, tx.size)
        return chunk("tEXt", data)
    }

    /** 通用块：`len | type | data | crc(type+data)`。 */
    fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(4 + 4 + data.size + 4)
        putInt(out, 0, data.size)
        System.arraycopy(t, 0, out, 4, 4)
        System.arraycopy(data, 0, out, 8, data.size)
        putInt(out, 8 + data.size, crcOf(type, data).toInt())
        return out
    }

    // ── 读回（§5.5） ─────────────────────────────────────────────────────

    /**
     * 读回卡数据。[textKeys] 里会如实登记见到的 `iTXt`/`zTXt`（「未使用」）。
     *
     * CRC 错**不拒绝**（只进 [PngRead.Ok.notes]）：现实里 CRC 错的卡还能用，
     * 直接拒绝会让用户失去可读性；但导出侧必须写对（F7）。
     */
    fun read(bytes: ByteArray): PngRead {
        if (!isPng(bytes)) return PngRead.Failed("这个文件不是 PNG。")
        val chunks = when (val s = scan(bytes)) {
            is PngScan.Broken -> return PngRead.Failed(CardText.pngBroken(s.index, s.offset))
            is PngScan.Ok -> s.chunks
        }

        val notes = ArrayList<String>()
        val blobs = LinkedHashMap<String, String>()
        val textKeys = ArrayList<String>()
        for (c in chunks) {
            when (c.type) {
                "tEXt" -> {
                    val zero = c.data.indexOf(0.toByte())
                    if (zero <= 0) {
                        notes += "有个 tEXt 块没有关键字分隔符（偏移 ${c.offset}），已跳过"
                        continue
                    }
                    val kw = String(c.data, 0, zero, Charsets.ISO_8859_1)
                    val txt = String(c.data, zero + 1, c.data.size - zero - 1, Charsets.ISO_8859_1)
                    if (blobs.containsKey(kw)) notes += "同名文本块重复：$kw（后者覆盖前者）"
                    blobs[kw] = txt
                    if (kw !in textKeys) textKeys += kw
                }
                "iTXt", "zTXt" -> {
                    val zero = c.data.indexOf(0.toByte())
                    val kw = if (zero > 0) String(c.data, 0, zero, Charsets.ISO_8859_1) else "?"
                    textKeys += "$kw（${c.type}，未使用）"
                }
            }
            if (c.crcStored != crcOf(c.type, c.data)) notes += "${c.type} 块 CRC 不符（偏移 ${c.offset}），照解"
        }

        val hasCcv3 = blobs.containsKey(KEY_CCV3)
        val hasChara = blobs.containsKey(KEY_CHARA)
        if (!hasCcv3 && !hasChara) {
            return PngRead.Failed(CardText.PNG_NO_CARD_BLOCK)
        }
        val kw = if (hasCcv3) KEY_CCV3 else KEY_CHARA
        val prio = if (hasCcv3) 1 else 2
        val txt = blobs.getValue(kw)
        if (hasCcv3 && hasChara && blobs[KEY_CHARA] != txt) {
            notes += "chara 与 ccv3 内容不一致，已取 ccv3"
        }

        val raw = try {
            Base64.getDecoder().decode(txt)
        } catch (e: IllegalArgumentException) {
            return PngRead.Failed(CardText.pngPayload("base64 解码失败"))
        }
        val s = CardJson.decodeUtf8Strict(raw)
            ?: return PngRead.Failed(CardText.pngPayload("内容不是 UTF-8"))
        val obj = CardJson.parseObject(s)
        if (obj == null) {
            return PngRead.Failed(CardText.pngPayload(if (looksLikeJson(s)) "JSON 最外层不是对象" else "内容不是 JSON"))
        }
        return PngRead.Ok(s, kw, prio, textKeys, notes)
    }

    /**
     * 「看起来像 JSON」的判据：首字符是 `{` 或 `[`。
     *
     * 不能靠 `JSONTokener.nextValue()`——它对裸字面量很宽松（`hello` 会被当成字符串），
     * 那样「不是 JSON」会被误报成「JSON 最外层不是对象」。
     */
    private fun looksLikeJson(s: String): Boolean {
        val t = CardJson.withoutBom(s).trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    // ── 内置底图（§5.7 的替代实现，见文件头） ────────────────────────────

    /**
     * 现画一张 1×1 白图（`colorType=2`，无调色板、无透明）。
     *
     * 产物必然含 `IHDR`/`IDAT`/`IEND`，走 §5.4 同一套插入逻辑。
     * 手写而非硬编码常量：字节由算法生成，可审计、可复算。
     */
    fun whiteOnePixelPng(): ByteArray {
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, 1)      // width
        putInt(ihdr, 4, 1)      // height
        ihdr[8] = 8             // bit depth
        ihdr[9] = 2             // color type: truecolor RGB
        ihdr[10] = 0            // compression
        ihdr[11] = 0            // filter
        ihdr[12] = 0            // interlace

        // 扫描行：1 个 filter 字节（0）＋ 3 个颜色字节（白）
        val rawScanline = byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        deflater.setInput(rawScanline)
        deflater.finish()
        val buf = ByteArray(64)
        val n = deflater.deflate(buf)
        deflater.end()
        val idat = buf.copyOfRange(0, n)

        return SIGNATURE + chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }

    // ── 大端读写 ─────────────────────────────────────────────────────────

    private fun putInt(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v ushr 24).toByte()
        dst[at + 1] = (v ushr 16).toByte()
        dst[at + 2] = (v ushr 8).toByte()
        dst[at + 3] = v.toByte()
    }

    private fun intAt(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 24) or
            ((src[at + 1].toInt() and 0xFF) shl 16) or
            ((src[at + 2].toInt() and 0xFF) shl 8) or
            (src[at + 3].toInt() and 0xFF)

    private fun uintAt(src: ByteArray, at: Int): Long = intAt(src, at).toLong() and 0xFFFFFFFFL
}

/** 内部便利：把 PNG 里解出来的 JSON 文本按对象读（失败返回 null）。 */
internal fun CardPng.readObject(bytes: ByteArray): JSONObject? =
    (read(bytes) as? PngRead.Ok)?.let { CardJson.parseObject(it.jsonText) }

/** 内部便利：写文件（只给测试 helper／工具层用，保持一处 I/O 口径）。 */
internal fun CardPng.writeBytesNew(path: File, bytes: ByteArray): Boolean = try {
    Files.write(path.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    true
} catch (e: Exception) {
    false
}

/** 内部便利：供工具层「读源文件字节」用；不在这里做任何编码判断。 */
internal fun File.readAllBytesOrNull(): ByteArray? = try {
    if (isFile) readBytes() else null
} catch (e: Exception) {
    null
}
