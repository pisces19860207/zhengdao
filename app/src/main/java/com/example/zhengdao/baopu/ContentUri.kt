// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十八批（2026-11-13）· `content://` 读取的唯一入口（613 的边界延后项）。
//
// 为什么单独一层、且**零 Android 依赖**：
// 613 只把 `content://` 识别出来后「不认」（另立单），本单要做到真读得到。工具层
// （sheet_read / read_file）历来是纯 JVM 直测的纯函数，不能认识 Context；而
// `ContentResolver.openInputStream` 只在装配层（BaopuRuntime）拿得到。于是按项目既有
// 「房型」（DeviceTools.host / ScheduleTaskTool.sink）：这里定义一个可注入的端口，
// 装配层注入真实现，JVM 单测注入内存 ContentProvider 假件 —— 两端打的是同一份读判据。
//
// 四条口径（派工单 §一）：
// 1. 认得出：`content://` 前缀（大小写不敏感、去首尾空白），**不是本地路径**；
// 2. 读得到：端口只负责开流，字节全在内存里读，一个字节都不落盘（⑤ 不落产物）；
// 3. 说得清：无权限 / URI 失效 / 未装配 ⇒ 统一「URI 无权限或已失效」，绝不报「不存在」；
// 4. 限读上限：声明大小超限或边读边数超限 ⇒ 整条失败，**不截断返回前 N 字节**。
package com.example.zhengdao.baopu

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 前缀常量：工具层与管线层共用同一把尺，不各写一个字面量。 */
internal const val CONTENT_SCHEME = "content://"

/** 一次打开拿到的东西：显示名（判 .xlsx/.csv 用）、声明大小（可空）、输入流。 */
internal class ContentStream(
    val displayName: String?,
    val declaredSize: Long?,
    val stream: InputStream,
)

/**
 * 端口（fun interface，纯 JVM）：`null` ＝ 无权限、URI 失效、或 provider 打不开。
 *
 * 装配层用 `ContentResolver.openInputStream(Uri.parse(...))` 实现；测试注入内存
 * 「测试 ContentProvider」假件。工具层只见这个接口，不认识 Context / Uri。
 */
internal fun interface ContentOpener {
    fun open(uri: String): ContentStream?
}

/** 注入点。放在 object 里是项目既有房型；持有的是端口对象，不是 Context（lint 安全）。 */
internal object ContentUriSource {
    @Volatile
    var opener: ContentOpener? = null
}

/** 一次读取的三种结局：**闭集**，没有第四态；调用方逐个给模型说人话。 */
internal sealed interface ContentRead {
    /** 完整读出（未超限）；[displayName] 可空（provider 没给 DISPLAY_NAME）。 */
    data class Ok(val bytes: ByteArray, val displayName: String?) : ContentRead

    /** 无权限 / URI 失效 / 端口未装配 —— 一律同一句，绝不混进「不存在」。 */
    data object Unavailable : ContentRead

    /** 超过上限；[bytes] 是声明大小或已读到的下界，用于报「N MB」。 */
    data class TooBig(val bytes: Long) : ContentRead
}

internal object ContentUri {

    /** 认得出：`content://...`。trim ＋ 大小写不敏感，避免「Content:// 」被当相对路径。 */
    fun isContent(raw: String): Boolean = raw.trim().startsWith(CONTENT_SCHEME, ignoreCase = true)

    /**
     * 读一条 content:// ⇒ **完整字节或明确失败**，绝不截断。
     *
     * 上限先看声明大小（能不打开流就拒最省），再边读边数兜住「provider 不报大小」的
     * 情形：一旦累计超过 [maxBytes] 就整条返回 [ContentRead.TooBig]，已读的那点字节
     * 直接丢弃，不交给解析层 —— 这就是派工单「不截断读取」。
     */
    fun read(uri: String, maxBytes: Long): ContentRead {
        val opener = ContentUriSource.opener ?: return ContentRead.Unavailable
        val handle = openOrNull(opener, uri) ?: return ContentRead.Unavailable
        val declared = handle.declaredSize
        if (declared != null && declared > maxBytes) {
            handle.stream.closeQuietly()
            return ContentRead.TooBig(declared)
        }
        return handle.stream.use { input -> readBounded(input, maxBytes, handle.displayName) }
    }

    /** 开流失败（含 SecurityException / FileNotFoundException / stub 抛错）一律归 Unavailable。 */
    private fun openOrNull(opener: ContentOpener, uri: String): ContentStream? = try {
        opener.open(uri)
    } catch (e: Exception) {
        null
    }

    private fun readBounded(input: InputStream, maxBytes: Long, displayName: String?): ContentRead {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            // 最多只读到「上限 + 1」：一超就停，不把整个超大文件读进内存才判超限。
            val want = minOf(buf.size.toLong(), maxBytes + 1 - total).toInt()
            if (want <= 0) break
            val n = input.read(buf, 0, want)
            if (n < 0) break
            total += n
            if (total > maxBytes) return ContentRead.TooBig(total)
            out.write(buf, 0, n)
        }
        return ContentRead.Ok(out.toByteArray(), displayName)
    }

    private fun InputStream.closeQuietly() = try { close() } catch (e: Exception) { /* 已失败，不掩盖主因 */ }

    /** 无权限 / 失效的统一话术（派工单 §一③：**不许**出现「不存在」）。 */
    fun unavailableMessage(uri: String): String = "URI 无权限或已失效：$uri"

    /** 超限话术：N 用上取整 MB，保证「刚超 1 字节」也能看出 N > M。 */
    fun tooBigMessage(bytes: Long, maxBytes: Long, uri: String): String {
        val n = ceilMb(bytes)
        val m = ceilMb(maxBytes)
        return "文件过大（${n} MB，上限 ${m} MB）：$uri（未读取内容，不截断）"
    }

    private fun ceilMb(bytes: Long): Long = (bytes + MIB - 1) / MIB

    /**
     * 解析用的「虚拟名字」：provider 给了 DISPLAY_NAME 就用；没给则按魔数猜 ——
     * zip 头 `PK` ⇒ xlsx，其余按 csv 文本。content:// 常常没有扩展名，不猜就没法
     * 走 [SheetReader] 那一份按扩展名分流的判据。
     */
    fun parseName(displayName: String?, bytes: ByteArray): String {
        if (!displayName.isNullOrBlank()) return displayName.trim()
        val zip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (zip) "content.xlsx" else "content.csv"
    }

    private const val BUFFER_BYTES = 8 * 1024
    private const val MIB = 1024L * 1024L
}
