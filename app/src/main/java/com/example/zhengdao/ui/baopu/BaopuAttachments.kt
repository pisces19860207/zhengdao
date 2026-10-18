// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：2026-10-18 用户「＋附件（曲别针）、相机权限」。第二十二批 A 段：
// 一个「＋」＝ 拍照／相册／选文件三个入口，拿到的东西**一律先落进工作区**再交给抱朴。
package com.example.zhengdao.ui.baopu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.Workspace
import com.example.zhengdao.util.TimeFormats
import java.io.File
import java.util.Locale

/**
 * 附件（第二十二批 A 段）：从相机/相册/文件选择器拿到的 [Uri]，**一律复制进工作区**
 * `<工作区>/附件/`，再把**工作区相对路径**交给抱朴。
 *
 * 三条纪律（与项目其它地方一致）：
 * 1. **不留在缓存里**：临时文件只用于相机写入，最终件必须落到工作区（用户能看见、能备份、能删）；
 * 2. **文件名要消毒**：`../`、`/`、`\`、控制字符一律换成 `_`，末尾截到 60 字，防路径穿越；
 * 3. **失败不静默**：拿不到流、没有存储权限、写不进去 —— 都返回 null 并由调用方弹一句提示。
 */
internal object BaopuAttachments {

    /** 工作区里的附件目录名（相对工作区根）。 */
    const val DIR = "附件"

    /** 相机临时目录（在 App 缓存里，FileProvider 只暴露这一个子目录）。 */
    const val CAMERA_DIR = "baopu-camera"

    /** 一个已落盘的附件。 */
    data class Saved(
        /** 相对工作区的路径，例如 `附件/20261018-173000-照片.jpg` */
        val relPath: String,
        val sizeBytes: Long,
        val displayName: String,
        val isImage: Boolean,
    )

    /** 相机要写哪：App 缓存里的一个空文件（由 FileProvider 换成 content:// 给相机）。 */
    fun newCameraFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, CAMERA_DIR).apply { mkdirs() }
        val name = "shot-" + System.currentTimeMillis() + ".jpg"
        return File(dir, name)
    }

    /**
     * 把一个 [uri] 复制进工作区附件目录。
     *
     * [fallbackName] 用于 `OpenableColumns.DISPLAY_NAME` 取不到名字的场合
     * （相机 content:// 常常没有名字）。
     */
    fun save(ctx: Context, uri: Uri, fallbackName: String = "附件"): Saved? = runCatching {
        val name = queryDisplayName(ctx, uri) ?: fallbackName
        val safe = sanitize(name)
        // 时间戳走统一的 TimeFormats（B-补-24 门禁：主源码里不许逐次新建 SimpleDateFormat）
        val stamp = TimeFormats.fileStamp()
        val target = File(File(Workspace.hostDir(ctx), DIR).apply { mkdirs() }, "$stamp-$safe")
        val copied = ctx.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
            true
        } ?: false
        if (!copied) return@runCatching null
        Saved(
            relPath = "$DIR/${target.name}",
            sizeBytes = target.length(),
            displayName = name,
            isImage = isImage(name, uri, ctx),
        )
    }.onFailure {
        RunLog.log("[baopu-attach] 保存附件失败（${it.javaClass.simpleName}）：${it.message}")
    }.getOrNull()

    /** 文件名消毒：去掉路径分隔与前导点、控制字符压成 `_`，截 60 字（保留扩展名）。 */
    internal fun sanitize(raw: String): String {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .trim()
            .removePrefix(".")
        if (cleaned.isEmpty()) return "附件"
        if (cleaned.length <= 60) return cleaned
        val dot = cleaned.lastIndexOf('.')
        return if (dot in 1 until 12) {
            val ext = cleaned.substring(dot)
            cleaned.take(60 - ext.length) + ext
        } else {
            cleaned.take(60)
        }
    }

    /** 图片判定：扩展名优先，其次 MIME（相机给的 content:// 常常只有 MIME）。 */
    internal fun isImage(name: String, uri: Uri, ctx: Context): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")) return true
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return mime.startsWith("image/")
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()
}
