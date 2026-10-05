// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android Storage Access Framework 官方文档
//（ACTION_OPEN_DOCUMENT_TREE / DocumentsContract 合同）与 androidx.documentfile 官方库。
package com.example.zhengdao.mirror

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机文件夹镜像（Plan B，2026-10-06）。
 *
 * ⚠️ 定位更正（同日勘误 E-005，见 docs/ERRATA.md）：此前"/sdcard 直连不可用、
 * SAF 是唯一通路"的结论源自 run-as 探针的方法论假象——run-as 的 SELinux 域
 * （runas_app）被 FUSE 拒，不代表 App 真身（untrusted_app）。真身自 READ 帽子
 * 摘除（d414dca）后 /sdcard 读写全通，本引擎**降级为备用方案**：个别 ROM 传统
 * 视图异常时的兜底，平时无需配置。
 *
 * 语义（用户定稿）：
 * - **复制语义，不是挂载**：所选 SAF 文件夹 ↔ filesDir/phone-mirror（guest 内 /mnt/phone）；
 *   大文件占双份空间，设置页有明示
 * - 双向：拉取（SAF→镜像）+ 推送（镜像→SAF）；差异判定 size + 秒级 mtime，冲突较新者胜
 * - **只同步新增与修改，不删除**（防误删）；深度 ≤2 层；文件总数 ≤1000
 * - 触发：App 冷启动自动一次（syncIfConfigured）+ 设置页手动
 */
object PhoneMirror {

    private const val KEY_URI = "mirror_tree_uri"
    private const val KEY_SUMMARY = "mirror_last_summary"
    private const val MAX_FILES = 1000
    /** 层级限制：根目录文件(1) + 一级子目录内的文件(2)，更深处不同步 */
    private const val MAX_DEPTH = 2

    private val syncing = AtomicBoolean(false)

    /** 镜像落点（宿主真实路径，ProotLauncher 绑为 guest 的 /mnt/phone） */
    fun mirrorDir(ctx: Context): File = File(ctx.filesDir, "phone-mirror")

    fun treeUri(ctx: Context): Uri? =
        Settings.prefs(ctx).getString(KEY_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun lastSummary(ctx: Context): String? = Settings.prefs(ctx).getString(KEY_SUMMARY, null)

    /** 保存用户所选文件夹并取持久化授权。写授权拿不到时退只读（拉取可用，推送会失败并回报）。 */
    fun saveTreeUri(ctx: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        Settings.prefs(ctx).edit().putString(KEY_URI, uri.toString()).putString(KEY_SUMMARY, null).apply()
        RunLog.log("镜像: 已选定手机文件夹 $uri")
    }

    class Result(
        val pulled: Int,
        val pushed: Int,
        val skipped: Int,
        val failed: Int,
        val capped: Boolean,
        val message: String,
    )

    /**
     * 执行一次双向同步。已有同步在跑时返回 null（本次忽略）。
     * ⚠️ 必须在后台线程调用（内部是文件 IO 与 SAF 跨进程调用）。
     */
    fun sync(ctx: Context, onProgress: (String) -> Unit = {}): Result? {
        if (!syncing.compareAndSet(false, true)) {
            onProgress("已有一次同步在进行，忽略本次")
            return null
        }
        return try {
            val r = doSync(ctx, onProgress)
            val summary = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()) +
                " · 拉取${r.pulled} 推送${r.pushed} 跳过${r.skipped}" +
                (if (r.failed > 0) " 失败${r.failed}" else "")
            Settings.prefs(ctx).edit().putString(KEY_SUMMARY, summary).apply()
            RunLog.log("镜像同步: $summary${if (r.capped) "（超出 $MAX_FILES 上限，未同步全部）" else ""}")
            r
        } catch (t: Throwable) {
            RunLog.log("镜像同步失败: ${t.message}")
            Result(0, 0, 0, 1, capped = false, message = "同步失败: ${t.message}")
        } finally {
            syncing.set(false)
        }
    }

    /** 已配置所选文件夹时才同步（冷启动自动触发用）；未配置静默跳过。 */
    fun syncIfConfigured(ctx: Context): Result? = if (treeUri(ctx) != null) sync(ctx) else null

    // ── 内部实现 ──

    private class Counters {
        var pulled = 0
        var pushed = 0
        var skipped = 0
        var failed = 0
        var total = 0
        var capped = false
    }

    private fun doSync(ctx: Context, onProgress: (String) -> Unit): Result {
        val uri = treeUri(ctx) ?: return Result(0, 0, 0, 0, capped = false, message = "未选择手机文件夹")
        val root = DocumentFile.fromTreeUri(ctx, uri)
            ?: return Result(0, 0, 0, 0, capped = false, message = "所选文件夹不可访问（可能已被移动或删除），请重新选择")
        val mirror = mirrorDir(ctx).apply { mkdirs() }
        val c = Counters()

        pullLevel(ctx, root, mirror, depth = 1, c = c, onProgress = onProgress)
        if (!c.capped) pushLevel(ctx, root, mirror, depth = 1, c = c, onProgress = onProgress)

        val msg = if (c.capped) "文件数超过 $MAX_FILES，已停——请缩小所选文件夹范围" else "同步完成"
        onProgress(msg)
        return Result(c.pulled, c.pushed, c.skipped, c.failed, c.capped, msg)
    }

    /** 计数与上限：每处理一个文件前调用；超限置 capped 并终止本轮 */
    private fun tick(c: Counters, onProgress: (String) -> Unit): Boolean {
        if (c.capped) return false
        c.total++
        if (c.total > MAX_FILES) {
            c.capped = true
            onProgress("文件数超过 $MAX_FILES，停止同步")
            return false
        }
        return true
    }

    /** 拉取：SAF → 镜像。目录按层级递进（≤MAX_DEPTH）；文件缺失或手机侧较新才复制。 */
    private fun pullLevel(
        ctx: Context,
        safDir: DocumentFile,
        localDir: File,
        depth: Int,
        c: Counters,
        onProgress: (String) -> Unit,
    ) {
        if (c.capped) return
        localDir.mkdirs()
        val children = runCatching { safDir.listFiles() }.getOrElse {
            c.failed++
            onProgress("读取手机文件夹失败: ${it.message}")
            return
        }
        for (doc in children) {
            if (c.capped) return
            val name = doc.name ?: continue
            if (name.startsWith(".")) continue
            if (doc.isDirectory) {
                if (depth >= MAX_DEPTH) continue
                pullLevel(ctx, doc, File(localDir, name), depth + 1, c, onProgress)
                continue
            }
            if (!tick(c, onProgress)) return
            val local = File(localDir, name)
            val docMs = runCatching { doc.lastModified() }.getOrElse { 0L }
            when {
                !local.exists() ->
                    if (copySafToLocal(ctx, doc, local, docMs)) {
                        c.pulled++; onProgress("拉取 $name")
                    } else c.failed++

                differs(local.length(), local.lastModified(), doc.length(), docMs) ->
                    // 本地较新：不动本地，留给推送阶段处理；手机侧较新：覆盖本地
                    if (local.lastModified() / 1000 >= docMs / 1000) {
                        c.skipped++
                    } else if (copySafToLocal(ctx, doc, local, docMs)) {
                        c.pulled++; onProgress("更新 $name（手机侧较新）")
                    } else c.failed++

                else -> c.skipped++
            }
        }
    }

    /** 推送：镜像 → SAF。只推缺失或镜像侧较新的文件；不删除任何 SAF 侧文件。 */
    private fun pushLevel(
        ctx: Context,
        safDir: DocumentFile,
        localDir: File,
        depth: Int,
        c: Counters,
        onProgress: (String) -> Unit,
    ) {
        if (c.capped) return
        val entries = localDir.listFiles() ?: return
        val safIndex = runCatching { safDir.listFiles().associateBy { it.name } }.getOrElse { return }

        for (dir in entries.filter { it.isDirectory }) {
            if (c.capped) return
            if (dir.name.startsWith(".")) continue
            if (depth >= MAX_DEPTH) continue
            val safSub = safIndex[dir.name]?.takeIf { it.isDirectory }
                ?: safDir.createDirectory(dir.name)
                ?: run { c.failed++; continue }
            pushLevel(ctx, safSub, dir, depth + 1, c, onProgress)
        }

        for (f in entries.filter { it.isFile }) {
            if (c.capped) return
            if (f.name.startsWith(".")) continue
            if (!tick(c, onProgress)) return
            val docMs = safIndex[f.name]?.let { d -> runCatching { d.lastModified() }.getOrElse { 0L } } ?: 0L
            val doc = safIndex[f.name]?.takeIf { it.isFile }
            when {
                doc == null ->
                    if (createSafFromLocal(ctx, safDir, f)) {
                        c.pushed++; onProgress("推送 ${f.name}")
                    } else c.failed++

                differs(f.length(), f.lastModified(), doc.length(), docMs) ->
                    if (f.lastModified() / 1000 < docMs / 1000) {
                        c.skipped++ // 手机侧较新：拉取阶段已覆盖本地
                    } else if (writeSafFromLocal(ctx, doc, f)) {
                        c.pushed++; onProgress("更新 ${f.name}（镜像侧较新）")
                    } else c.failed++

                else -> c.skipped++
            }
        }
    }

    private fun differs(localSize: Long, localMs: Long, safSize: Long, safMs: Long): Boolean =
        localSize != safSize || localMs / 1000 != safMs / 1000

    /** SAF → 镜像；复制后把本地 mtime 对齐到源（防下一轮把"刚拉来的"误判成镜像侧改动） */
    private fun copySafToLocal(ctx: Context, doc: DocumentFile, target: File, docMs: Long): Boolean {
        val ok = try {
            ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (t: Throwable) {
            RunLog.log("镜像拉取失败 ${doc.name}: ${t.message}")
            false
        }
        if (ok) target.setLastModified(docMs)
        return ok
    }

    /** 镜像 → SAF（新建文档）；复制后对齐本地 mtime（防下一轮把"刚推走的"误判成手机侧改动） */
    private fun createSafFromLocal(ctx: Context, parent: DocumentFile, f: File): Boolean {
        val ok = try {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension)
                ?: "application/octet-stream"
            val newUri = parent.createFile(mime, f.name)?.uri ?: return false
            ctx.contentResolver.openOutputStream(newUri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } != null
        } catch (t: Throwable) {
            RunLog.log("镜像推送失败(新建) ${f.name}: ${t.message}")
            false
        }
        if (ok) f.setLastModified(System.currentTimeMillis())
        return ok
    }

    /** 镜像 → SAF（覆写已有文档）；同上对齐本地 mtime */
    private fun writeSafFromLocal(ctx: Context, doc: DocumentFile, f: File): Boolean {
        val ok = try {
            ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } != null
        } catch (t: Throwable) {
            RunLog.log("镜像推送失败 ${f.name}: ${t.message}")
            false
        }
        if (ok) f.setLastModified(System.currentTimeMillis())
        return ok
    }
}
