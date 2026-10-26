// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十九批 · 相册写入的**唯一实现**（Android 那一半）。
//
// 为什么必须走 ContentResolver（设计 §5.3 那条坑，逐字照抄）：
//   用 `File` 直接往 `Pictures/` 写**不会进 MediaStore** ⇒ 相册看不见，等下次媒体扫描
//   才可能冒出来；反过来，删 `Pictures/证道/` 的条目若只删文件，会在相册里留一张
//   **幽灵缩略图**。⇒ 用户可见的图必须 insert ＋ IS_PENDING 事务式发布，删除必须删行。
//
// 三条纪律：
//   1. **`IS_PENDING = 1` 打头**：先把字节写完再置 0，中途被杀 ⇒ 相册里留不下半个条目
//      （半截条目正是派工单 §2 的负例之一）；
//   2. **失败要收干净**：insert 成功、写字节失败 ⇒ 把那条 pending 行删掉，别留垃圾；
//   3. **只删自己**：删除前先按 `_id` 读 `OWNER_PACKAGE_NAME` 与账本对，判据在
//      `MediaOwnership.deleteVerdict`（一个字节的判断都不在本文件里另写一遍）。
package com.example.zhengdao.baopu

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.zhengdao.core.RunLog
import java.io.File

/** 一次入库的结局（调用方据此决定回执说人话还是退回文件落盘）。 */
internal sealed interface MediaPublish {
    /** 进相册了。`ledgerRecorded == false` ⇒ 账本没写成，删除时会走确认（**不假装登记过**）。 */
    data class Ok(val entry: MediaOwnership.Entry, val ledgerRecorded: Boolean) : MediaPublish

    /** 同图已在相册（`sha256` 判重命中）——**不再入库**，直接复用那条。 */
    data class Duplicate(val entry: MediaOwnership.Entry) : MediaPublish

    /** 进不去相册（没授权/插入失败/设备不支持）：给一句能直接说给用户听的原因。 */
    data class Unavailable(val reason: String) : MediaPublish
}

/** 删除的结局。 */
internal sealed interface MediaDelete {
    /** 删掉了（相册行 ＋ 账本墓碑）。 */
    data class Done(val entry: MediaOwnership.Entry) : MediaDelete

    /** 不是自己写的 ⇒ 必须回到「正常确认路径」，**不许**硬删。 */
    data class Ask(val reason: String) : MediaDelete

    /** 自己写的但删失败（行没了/被占用）：如实说。 */
    data class Failed(val reason: String) : MediaDelete
}

/**
 * `ContentResolver` 那一层：insert／query／delete，就这三件事。
 *
 * 不做判据（判据在 [MediaOwnership]），不碰工作区（账本路径由调用方给）——
 * 这样 JVM 单测能穷举所有分支，而这里只剩「Android API 调对了没有」。
 */
internal object MediaStoreSink {

    /** 相册集合：主外部卷的图片表（`Pictures/` 属于它）。 */
    private fun collection() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * 事务式写入：`IS_PENDING=1` ⇒ 写字节 ⇒ 置 0。
     *
     * @return 成功 ⇒ 新行的 `_id`；失败 ⇒ null（原因由调用方拼人话）
     */
    fun insertImage(
        ctx: Context,
        relDir: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Long? {
        val resolver = ctx.contentResolver
        val col = collection()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(col, values) }.getOrNull() ?: return null
        val ok = runCatching {
            val out = resolver.openOutputStream(uri) ?: error("openOutputStream 返回空")
            out.use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }.isSuccess
        if (!ok) {
            // 收干净：写坏的 pending 行留着会在相册里变成一张打不开的灰图
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri.lastPathSegment?.toLongOrNull()
    }

    /**
     * 观察 `MediaStore.Files` 的**变化通知**（第一百八十二批：知识库刷新的可选加速）。
     *
     * 只读观察，不 insert/delete/update；放在本文件是为了守住 [MediaPathGateTest] 的
     * 「MediaStore 只许一个出口」——别处连 `MediaStore.` 这个名字都不出现。
     * 观察者不可靠（漏事件/厂商限制）时返回 false，调用方只记日志，正确性由周期扫描兜底。
     */
    fun registerFilesObserver(ctx: Context, onChange: () -> Unit): Boolean = runCatching {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, changed: android.net.Uri?) {
                onChange()
            }
        }
        ctx.contentResolver.registerContentObserver(uri, true, obs)
        true
    }.getOrDefault(false)

    /** 相册某个目录下**已发布**（`IS_PENDING=0`）的文件名——用来算「不覆盖」的名字。 */
    fun publishedNames(ctx: Context, relDir: String): Set<String> {
        val out = mutableSetOf<String>()
        runCatching {
            ctx.contentResolver.query(
                collection(),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf(relDir),
                null,
            )?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (idx >= 0) c.getString(idx)?.let { out += it }
                }
            }
        }
        return out
    }

    /** 读一行的归属信息（`_id` 不存在 ⇒ null）。 */
    fun rowOf(ctx: Context, id: Long): Row? = runCatching {
        ctx.contentResolver.query(
            collection(),
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            ),
            "${MediaStore.MediaColumns._ID}=?",
            arrayOf(id.toString()),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            Row(
                name = c.getString(0) ?: "",
                relPath = c.getString(1) ?: "",
                ownerPackage = runCatching { c.getString(2) }.getOrNull(),
            )
        }
    }.getOrNull()

    /** 按 `_id` 删行（**调用方必须先过 `deleteVerdict`**：这里不做判据）。 */
    fun deleteById(ctx: Context, id: Long): Boolean = runCatching {
        val uri = android.content.ContentUris.withAppendedId(collection(), id)
        ctx.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    /** 一行相册记录的归属信息。 */
    data class Row(val name: String, val relPath: String, val ownerPackage: String?)
}

/**
 * 相册发布的**装配层门面**（进程级单例，`BaopuRuntime` 注入工作区）。
 *
 * 为什么不在工具层直接 `ctx.contentResolver`：工具层要 JVM 可测（`BrowserTools`／
 * `GenerateImageTool` 都是「零 Android」的判据层）。这里收成一处，与
 * `BaopuBrowserWiring` 同一个理由——「接 Android 的手只许有一只」。
 *
 * ⚠️ **本对象刻意不存 `Context` 字段**：`Context` 放进静态字段要交 `StaticFieldLeak`
 * 这条 lint 的税（`WorkspacePrefs` 的 KDoc 记过同一笔账），也会把 Activity 泄漏到进程级。
 * 口径：`Context` 一律**当参数传**（调用方手里都有）；只有「工作区」（一个 `File`）
 * 和「删除闭包」（装配层给，内部握着 appCtx）留在静态位里。
 *
 * 没装（Env 为 null）⇒ [publish] 回 `Unavailable`，调用方退回文件落盘并如实说明
 * （**不许**假装相册里有）。
 */
internal object MediaPublisher {

    /**
     * 装配参数（**不含 Context**，见类 KDoc）。
     *
     * @param workspace 抱朴当前工作区（`.归属.jsonl` 落在这里；换工作区跟着换）
     * @param now 注入时间戳（单测钉文件名/账本时间）
     */
    data class Env(
        val workspace: () -> File,
        val now: () -> Long = { System.currentTimeMillis() },
        val log: (String) -> Unit = { RunLog.log(it) },
    )

    @Volatile
    var env: Env? = null

    /**
     * 相册删除的**工具侧出口**（`delete_path` 拿不到 Context，只有路径）。
     *
     * 为什么是闭包而不是 `Context` 字段：函数类型不会被 `StaticFieldLeak` 盯上，也不会
     * 把 Activity 钉在进程里；闭包由装配层给（`BaopuRuntime.installMedia`），内部用 appCtx。
     */
    @Volatile
    private var albumDeleter: ((String) -> MediaDelete)? = null

    /** 装配「按路径删相册条目」这条出口（幂等；重复装只换实现）。 */
    fun installAlbumDeleter(f: (String) -> MediaDelete) {
        albumDeleter = f
    }

    /** 本包名（`OWNER_PACKAGE_NAME` 与账本 `owner` 用同一个值）。 */
    fun ownerOf(ctx: Context): String = ctx.packageName

    /**
     * 把一张图发布进相册 ＋ 记账。
     *
     * @param mime 只认图片 MIME（image/png、image/jpeg 这类；别的格式不进图片表）
     * @param source [MediaOwnership.SOURCE_SCREENSHOT] 或 [MediaOwnership.SOURCE_IMAGE]
     */
    fun publish(
        ctx: Context,
        bytes: ByteArray,
        name: String,
        mime: String,
        source: String,
        ext: String,
    ): MediaPublish {
        val e = env ?: return MediaPublish.Unavailable("相册通道还没接上（App 内部装配缺失）")
        val relDir = MediaOwnership.relDirOf(source)
            ?: return MediaPublish.Unavailable("不认识的来源：$source")
        if (bytes.isEmpty()) return MediaPublish.Unavailable("图是空的（0 字节），不入库")
        val pkg = ownerOf(ctx)
        val workspace = e.workspace()
        val sha = MediaOwnership.sha256(bytes)
        val ledger = MediaOwnership.readAll(workspace)
        // 同图只存一份：判重命中就直接复用（不重复入库，也不覆盖用户已有的那张）
        MediaOwnership.findLiveBySha(ledger, sha)?.let { return MediaPublish.Duplicate(it) }

        val taken = runCatching { MediaStoreSink.publishedNames(ctx, relDir) }.getOrDefault(emptySet()) +
            MediaOwnership.live(ledger).filter { it.relPath.startsWith(relDir) }.map { it.name }
        val (base, _) = MediaOwnership.splitName(name)
        val finalName = MediaOwnership.uniqueName(taken, base, ext)
        val id = MediaStoreSink.insertImage(ctx, relDir, finalName, mime, bytes)
            ?: return MediaPublish.Unavailable("相册写入失败（可能是存储权限或系统限制）")
        // 读回落地的真实名字：MediaStore 遇到重名会自己加 " (1)"，账本若记着我们
        // 以为的那个名字，`relPath` 就会指向一条不存在的路径（删除时永远对不上）。
        val row = MediaStoreSink.rowOf(ctx, id)
        val landed = row?.name?.takeIf { it.isNotBlank() } ?: finalName
        val landedDir = (row?.relPath?.takeIf { it.isNotBlank() } ?: relDir)
            .let { if (it.endsWith("/")) it else "$it/" }

        val entry = MediaOwnership.Entry(
            id = id,
            name = landed,
            relPath = landedDir + landed,
            sha256 = sha,
            owner = pkg,
            createdAt = e.now(),
            source = source,
            bytes = bytes.size.toLong(),
        )
        val recorded = MediaOwnership.append(workspace, entry)
        if (!recorded) e.log("抱朴相册：账本写不进去（${MediaOwnership.LEDGER_NAME}）；这条删除时会走确认")
        return MediaPublish.Ok(entry, ledgerRecorded = recorded)
    }

    /**
     * 按**相册相对路径**删（`delete_path` 那头的入口：模型给的是文件名/路径，不是 `_id`）。
     *
     * 先规整成账本里的口径，再在账上找活记录，最后仍走 [delete] 的同一套判据——
     * **不新增第二条删除判据**（两条判据迟早会漂）。
     */
    fun deleteByRelPath(ctx: Context, path: String): MediaDelete {
        val reject = albumPathReject(path)
        if (reject != null) return reject
        val entry = liveEntryOf(path)
            ?: return MediaDelete.Ask(
                "归属清单里没有 ${MediaOwnership.albumRelPathOf(path)}（按「不是本 App 生成的」处理）",
            )
        return delete(ctx, entry.id)
    }

    /**
     * 工具侧入口（`delete_path` 只有路径、没有 Context）：真正的删除交给装配层装的闭包。
     *
     * 没装 ⇒ `Failed`（**不许**当成删除成功，也不许静默跳过）。
     */
    fun deleteByRelPath(path: String): MediaDelete =
        albumDeleter?.invoke(path) ?: MediaDelete.Failed("相册删除通道没接上（App 内部装配缺失）")

    /** 路径规整＋账本查找（两个入口共用；`null` ⇒ 按「不是我们的」处理）。 */
    private fun liveEntryOf(path: String): MediaOwnership.Entry? {
        val rel = MediaOwnership.albumRelPathOf(path) ?: return null
        if (!MediaOwnership.isAlbumPath(rel)) return null
        val e = env ?: return null
        return MediaOwnership.findLiveByRelPath(MediaOwnership.readAll(e.workspace()), rel)
    }

    /** 路径不合规时的那句人话（`null` ⇒ 其实合规，别用）。 */
    private fun albumPathReject(path: String): MediaDelete? {
        val rel = MediaOwnership.albumRelPathOf(path)
            ?: return MediaDelete.Ask("这不像是本 App 相册里的路径（Pictures/证道/…）：$path")
        if (!MediaOwnership.isAlbumPath(rel)) {
            return MediaDelete.Ask("$rel 不在相册的两个目录里（只认 截屏/ 与 图片/）")
        }
        return null
    }

    /**
     * 删一条**自己写的**相册条目（相册行 ＋ 账本墓碑）。
     *
     * 判据全在 [MediaOwnership.deleteVerdict]：账上没这条、id 对不上、owner 不是本包、
     * 相册里的 `OWNER_PACKAGE_NAME` 不是本包 ⇒ 一律 [MediaDelete.Ask]（宁可多问）。
     */
    fun delete(ctx: Context, mediaId: Long): MediaDelete {
        val e = env ?: return MediaDelete.Failed("相册通道还没接上（App 内部装配缺失）")
        val workspace = e.workspace()
        val ledger = MediaOwnership.readAll(workspace)
        val entry = MediaOwnership.findById(ledger, mediaId)
        val row = MediaStoreSink.rowOf(ctx, mediaId)
        val pkg = ownerOf(ctx)
        return when (val v = MediaOwnership.deleteVerdict(entry, mediaId, row?.ownerPackage, pkg)) {
            is MediaOwnership.DeleteVerdict.Ask -> MediaDelete.Ask(v.reason)
            is MediaOwnership.DeleteVerdict.Ours -> {
                if (row == null) {
                    MediaDelete.Failed("相册里已经没有这一条（可能被用户先删了）")
                } else if (!MediaStoreSink.deleteById(ctx, mediaId)) {
                    MediaDelete.Failed("相册拒绝删除这一条（系统限制）")
                } else {
                    MediaOwnership.appendTombstone(workspace, v.entry, e.now())
                    MediaDelete.Done(v.entry)
                }
            }
        }
    }
}
