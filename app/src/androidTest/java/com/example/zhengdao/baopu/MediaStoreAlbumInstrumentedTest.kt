// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 第一百九十九批 · **真机**（instrumented）：相册分流与归属。
 *
 * 为什么必须上真机：`MediaStore` 在 JVM 上是空壳（`isReturnDefaultValues`），
 * 「相册里到底看得见看不见」「删掉之后缩略图会不会变幽灵」这两条只有真机能答。
 *
 * 四条判据（对应派工单 §2）：
 * ① 发布一张 ⇒ 相册里真有一行，`RELATIVE_PATH` 是 `Pictures/证道/图片/`、`IS_PENDING=0`、
 *    `OWNER_PACKAGE_NAME` 是本包，账本里也有一条；
 * ② 同一张再发一次 ⇒ 判重（不重复入库，不产生第二行）；
 * ③ 删自己那条 ⇒ 相册行真没了（不是只删文件），账本留墓碑；
 * ④ 账上没有的 `_id` ⇒ 一律 Ask（**不许**猜着删别人的照片）。
 *
 * 自洁：本测试造出来的行全部在 [tearDown] 里按 `_id` 删掉——用户相册里不许留实验垃圾。
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreAlbumInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var ws: File
    private val created = mutableListOf<Long>()

    @Before
    fun setUp() {
        ws = File(ctx.cacheDir, "media-199-${System.currentTimeMillis()}").apply { mkdirs() }
        MediaPublisher.env = MediaPublisher.Env(
            workspace = { ws },
            now = { 1_760_000_000_000L },
            log = { /* 测试不落日志 */ },
        )
    }

    @After
    fun tearDown() {
        // 本测试造的行一条都不留（哪怕断言中途挂了）
        created.forEach { runCatching { MediaStoreSink.deleteById(ctx, it) } }
        MediaPublisher.env = null
        runCatching { ws.deleteRecursively() }
    }

    /** 同一张 1×1 PNG，尾部追加一个 seed 字节 ⇒ 内容不同（哈希不同）、仍是能解码的 PNG。 */
    private fun png(seed: Int): ByteArray = PNG_1PX + byteArrayOf(seed.toByte())

    private fun publishImage(seed: Int = 1, name: String = "199-测试图") =
        MediaPublisher.publish(
            ctx = ctx,
            bytes = png(seed),
            name = name,
            mime = "image/png",
            source = MediaOwnership.SOURCE_IMAGE,
            ext = "png",
        ).also { r ->
            if (r is MediaPublish.Ok) created += r.entry.id
        }

    @Test
    fun 发布进相册_行在_账本在() {
        val r = publishImage()
        assertTrue("应当进相册：$r", r is MediaPublish.Ok)
        val ok = r as MediaPublish.Ok
        assertTrue("账本必须写成功", ok.ledgerRecorded)
        assertEquals("相册相对路径要落 图片/ 子目录", "Pictures/证道/图片/" + ok.entry.name, ok.entry.relPath)

        val row = MediaStoreSink.rowOf(ctx, ok.entry.id)
        assertNotNull("相册里必须有这一行", row)
        assertEquals("Pictures/证道/图片/", row!!.relPath)
        assertEquals("相册里的归属必须是本包", ctx.packageName, row.ownerPackage)

        // IS_PENDING 必须已经置 0（否则相册里看不见，等于没发布）
        val pending = ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            "${MediaStore.MediaColumns._ID}=?",
            arrayOf(ok.entry.id.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else -1 } ?: -1
        assertEquals("IS_PENDING 必须已发布（0）", 0, pending)

        val ledger = MediaOwnership.readAll(ws)
        assertEquals(1, ledger.size)
        assertEquals(ok.entry.id, ledger[0].id)
        assertEquals(ctx.packageName, ledger[0].owner)
        assertEquals(MediaOwnership.sha256(png(1)), ledger[0].sha256)
    }

    @Test
    fun 同图再发_判重_不产生第二行() {
        val first = publishImage(seed = 2) as MediaPublish.Ok
        val before = MediaStoreSink.publishedNames(ctx, MediaOwnership.relImageDir()).size
        val second = publishImage(seed = 2)
        assertTrue("同图必须判重：$second", second is MediaPublish.Duplicate)
        assertEquals(first.entry.id, (second as MediaPublish.Duplicate).entry.id)
        assertEquals(
            "相册里不许因为重复发布多出一行",
            before,
            MediaStoreSink.publishedNames(ctx, MediaOwnership.relImageDir()).size,
        )
    }

    @Test
    fun 删自己那条_相册行消失_账本留墓碑() {
        val ok = publishImage(seed = 3) as MediaPublish.Ok
        val r = MediaPublisher.delete(ctx, ok.entry.id)
        assertTrue("自己写的必须能删：$r", r is MediaDelete.Done)
        created -= ok.entry.id // 已经删掉了，tearDown 不用再删
        assertNull("相册里那一行必须真没了（不是只删文件）", MediaStoreSink.rowOf(ctx, ok.entry.id))
        val all = MediaOwnership.readAll(ws)
        assertTrue("要留墓碑", all.any { it.id == ok.entry.id && !it.isLive })
        // 账本只追加：墓碑前那条 added 行仍在（与 JVM 口径「活记录只剩墓碑前那一条」一致）；
        // 268 上真机首跑时修正此断言（原句与账本设计相反，199 时未上过真机）——
        // 「删过不许再删」由相册行消失兜底 ⇒ 再删一次必须 Failed，不许 Done
        val again = MediaPublisher.delete(ctx, ok.entry.id)
        assertTrue("再删一次不许再 Done（相册行已没了）：$again",
            again is MediaDelete.Failed || again is MediaDelete.Ask)
    }

    @Test
    fun 账本里没有的id_一律Ask不猜() {
        // 拿一个真实存在、但**不是**本 App 账本里的 id：直接查一张系统里已有的图
        val someoneElse = ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
            arrayOf("Pictures/证道/%"),
            "${MediaStore.MediaColumns._ID} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (someoneElse == null) return // 机器上没有别的照片：这条负例没法取证，跳过
        val r = MediaPublisher.delete(ctx, someoneElse)
        assertTrue("别人的照片必须 Ask，不许删：$r", r is MediaDelete.Ask)
        assertNotNull("那条必须还在", MediaStoreSink.rowOf(ctx, someoneElse))
    }

    @Test
    fun 相册路径删除_只认两个目录() {
        val ok = publishImage(seed = 4) as MediaPublish.Ok
        // 正例：账本里那条的相册路径能删
        assertTrue(
            MediaPublisher.deleteByRelPath(ctx, ok.entry.relPath) is MediaDelete.Done,
        )
        created -= ok.entry.id
        // 负例：不在两个目录里的路径一律 Ask（不碰）
        assertTrue(MediaPublisher.deleteByRelPath(ctx, "Pictures/证道/别处/x.png") is MediaDelete.Ask)
        assertFalse(
            "不是相册路径的不该被当相册条目",
            MediaPublisher.deleteByRelPath(ctx, "Download/证道/截屏/x.png") is MediaDelete.Done,
        )
    }

    private companion object {
        /** 1×1 透明 PNG（89 字节；真机 MediaStore 会认这个 MIME 与头）。 */
        val PNG_1PX: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte(),
        )
    }
}
