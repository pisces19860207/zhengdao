// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import android.content.Context
import com.example.zhengdao.baopu.MediaOwnership
import com.example.zhengdao.baopu.MediaPublish
import com.example.zhengdao.baopu.MediaPublisher
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.browser.PageExporter
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.core.ZhengdaoDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出落盘（651）：**图进相册、PDF 进 `产物/玄览导出/`**。
 *
 * 两个落点都不是随手挑的，各自被一条既有纪律钉住：
 * - **PNG 走相册的唯一出口** [MediaPublisher]（判重、唯一命名、归属账本、删除同源）。
 *   自己 insert MediaStore 会破 `MediaPathGateTest` G3「MediaStore 写删只有一个出口」，
 *   而且绕过账本 ⇒ 将来删不掉一个自己造的文件。
 * - **PDF 落 `<工作区>/产物/玄览导出/`**：`产物/` 是本仓**唯一对外输出口**（157 收口原话），
 *   用户能在 App 的产物架里找到、能长按打开；写这里也不碰 MediaStore
 *   （G3 那条禁令不分文件类型）。
 *
 * 三条纪律：
 * 1. **一律 IO 线程**（[withContext]）：相册写入与文件写都在主线程会 ANR；
 * 2. **失败要说得出原因**（[Failure] 枚举），不许静默返回空字符串当成功；
 * 3. **不覆盖用户已有文件**：名字带到秒的时间戳；相册那条由 [MediaPublisher] 唯一化，
 *   PDF 那条由 [uniqueFile] 查一次同名再定名（本轮不删用户任何旧文件）。
 */
internal object PageExportSink {

    /** 落盘结局：成功给"在哪儿"，失败给"为什么"（文案由 UI 映射，不在这里拼中文）。 */
    sealed interface Saved {
        /** @param where 用户可读的位置（相册相对路径 / 工作区相对路径） */
        data class Ok(val where: String, val duplicate: Boolean = false) : Saved

        /** @param kind 失败类型（UI 据此挑一句人话） */
        data class Failed(val kind: Failure, val detail: String = "") : Saved
    }

    enum class Failure {
        /** 相册通道没接上（App 装配缺失）。 */
        NO_ALBUM,

        /** 写入被系统拒了（权限/空间）。 */
        IO,

        /** 内容为空（0 字节）。 */
        EMPTY,
    }

    /** PDF 在产物下的子目录名（`产物/玄览导出/`）。 */
    const val PDF_SUBDIR = "玄览导出"

    /** PNG 进相册。 */
    suspend fun savePng(context: Context, shot: PageExporter.Shot, baseName: String): Saved =
        withContext(Dispatchers.IO) {
            if (shot.png.isEmpty()) return@withContext Saved.Failed(Failure.EMPTY)
            when (val r = MediaPublisher.publish(
                ctx = context.applicationContext,
                bytes = shot.png,
                name = "$baseName.png",
                mime = "image/png",
                source = MediaOwnership.SOURCE_SCREENSHOT,
                ext = "png",
            )) {
                is MediaPublish.Ok -> Saved.Ok(r.entry.relPath)
                is MediaPublish.Duplicate -> Saved.Ok(r.entry.relPath, duplicate = true)
                is MediaPublish.Unavailable -> {
                    RunLog.log("玄览 导出：PNG 入相册失败：${r.reason}")
                    Saved.Failed(Failure.NO_ALBUM, r.reason)
                }
            }
        }

    /** PDF 进 `产物/玄览导出/`。 */
    suspend fun savePdf(context: Context, bytes: ByteArray, baseName: String): Saved =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext Saved.Failed(Failure.EMPTY)
            val appCtx = context.applicationContext
            val dir = File(LibraryPaths.doneDir(appCtx), PDF_SUBDIR)
            // 先写 .part 再改名：中途失败只留一个半截临时文件，删掉它——
            // 产物里不许出现「看着是 PDF、其实只有一半」的东西（用户会当成导出成功）。
            val target = uniqueFile(dir, "$baseName.pdf")
            val temp = File(dir, "${target.name}.part")
            runCatching {
                if (!dir.isDirectory && !dir.mkdirs()) error("建目录失败：${dir.absolutePath}")
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) error("改名失败：${temp.absolutePath}")
                // 回执给工作区相对路径（用户能照着在产物架里认出来）
                val where = "${ZhengdaoDirs.PRODUCTS_DIR}/$PDF_SUBDIR/${target.name}"
                RunLog.log("玄览 导出：PDF 落到 $where（${bytes.size} 字节）")
                Saved.Ok(where)
            }.getOrElse {
                runCatching { if (temp.exists()) temp.delete() }
                RunLog.log("玄览 导出：PDF 写入失败：${it.message}")
                Saved.Failed(Failure.IO, it.message.orEmpty())
            }
        }

    /** 文件名：`玄览-<标题或域名>-<到秒的时间戳>`（标题里的非法字符换成下划线）。 */
    fun baseName(title: String, now: Long): String {
        val safe = sanitize(title).ifBlank { "页面" }
        return "玄览-$safe-${TimeFormats.fileStamp(now)}"
    }

    /**
     * 文件名里不许出现的字符换掉（各家文件管理器/相册都认不全 `/\:*?"<>|`）。
     *
     * 保留：字母、数字、`-`、`_`、以及**非控制字符的宽字符**（中日韩标题是整个字库的主角，
     * 全换成下划线等于把标题丢了）。其余一律 `_`。
     */
    fun sanitize(raw: String): String {
        val cleaned = raw.trim().map { ch -> if (allowedInName(ch)) ch else '_' }.joinToString("")
        return cleaned.trim('_').take(40)
    }

    private fun allowedInName(ch: Char): Boolean {
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_') return true
        if (ch.isISOControl()) return false
        return ch.code > 0x80
    }

    /** 同名就加 `-1`、`-2`…（不覆盖用户已有的那份）。 */
    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "pdf")
        var i = 1
        while (i < 1000) {
            val candidate = File(dir, "$stem-$i.$ext")
            if (!candidate.exists()) return candidate
            i++
        }
        return File(dir, "$stem-$i.$ext")
    }
}
