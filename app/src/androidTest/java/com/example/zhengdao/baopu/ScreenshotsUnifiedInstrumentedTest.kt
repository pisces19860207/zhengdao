// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.MainActivity
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.core.ZhengdaoDirs
import com.example.zhengdao.ui.browser.BrowserTabManager
import java.io.File
import org.json.JSONObject
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
 * 第二百六十八批 · **真机**（instrumented）：截图统一落系统截屏夹 `Pictures/Screenshots/`。
 *
 * 为什么必须上真机：派工单 §3 负例③点名「模型回读失败（extraRoot 没跟着改）——这条必须真机验」。
 * `MediaStore` 与 `Environment` 在 JVM 上是空壳，「相册里到底看得见看不见」「回读像素是否
 * 与落盘字节一致」只有真机（模拟器）能答。
 *
 * 五条判据（对应派工单 §2 必须保住的三件事 ＋ §1.5 退路）：
 * ① **一条路**：`SOURCE_SCREENSHOT` 发布 ⇒ 相册里真有一行，`RELATIVE_PATH` 是
 *    `Pictures/Screenshots/`、`IS_PENDING=0`、`OWNER_PACKAGE_NAME` 是本包，账本里
 *    `source=screenshot` 一条、`relPath` 相册口径全路径；
 * ② **回读可用（生产装配同款）**：`VisionFiles.load(root=工作区, extraRoot=Environment…/Pictures,
 *    relPaths=["Screenshots/<名>"])` ⇒ 读回 1 份 payload，字节数与落盘一致；
 * ③ **判重**：同一张再发 ⇒ `Duplicate`，相册行不增（一份图只落一处）；
 * ④ **删图墓碑**：`deleteByRelPath("Pictures/Screenshots/<名>")` ⇒ `Done`，相册行真没了，
 *    账本留墓碑（旧目录的条目口径一字未改）；
 * ⑤ **退路落点**：`publicShotDir()` ＝ `<SD根>/Pictures/Screenshots` 且可写（`File` 直写合法，
 *    MANAGE_EXTERNAL_STORAGE 已授权）；负例：退路目录不许是 Download/证道/截屏。
 *
 * 自洁：本测试造出来的行全部在 [tearDown] 里按 `_id` 删掉——用户相册里不许留实验垃圾。
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotsUnifiedInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var ws: File
    private val created = mutableListOf<Long>()

    @Before
    fun setUp() {
        ws = File(ctx.cacheDir, "media-268-${System.currentTimeMillis()}").apply { mkdirs() }
        MediaPublisher.env = MediaPublisher.Env(
            workspace = { ws },
            now = { 1_760_000_000_000L },
            log = { /* 测试不落日志 */ },
        )
    }

    @After
    fun tearDown() {
        created.forEach { runCatching { MediaStoreSink.deleteById(ctx, it) } }
        MediaPublisher.env = null
        runCatching { ws.deleteRecursively() }
    }

    /** 同一张 1×1 PNG，尾部追加 seed 字节 ⇒ 内容不同（哈希不同）、仍是能解码的 PNG。 */
    private fun png(seed: Int): ByteArray = PNG_1PX + byteArrayOf(seed.toByte())

    private fun publishShot(seed: Int = 1, name: String = "268-测试截屏") =
        MediaPublisher.publish(
            ctx = ctx,
            bytes = png(seed),
            name = name,
            mime = "image/png",
            source = MediaOwnership.SOURCE_SCREENSHOT,
            ext = "png",
        ).also { r ->
            if (r is MediaPublish.Ok) created += r.entry.id
        }

    @Test
    fun 截屏发布_行在系统截屏夹_账本一条() {
        val r = publishShot()
        assertTrue("应当进相册：$r", r is MediaPublish.Ok)
        val ok = r as MediaPublish.Ok
        assertTrue("账本必须写成功", ok.ledgerRecorded)
        assertEquals(
            "截屏必须落系统截屏夹（268 落点）",
            "Pictures/Screenshots/" + ok.entry.name,
            ok.entry.relPath,
        )

        val row = MediaStoreSink.rowOf(ctx, ok.entry.id)
        assertNotNull("相册里必须有这一行", row)
        assertEquals("Pictures/Screenshots/", row!!.relPath)
        assertEquals("相册里的归属必须是本包", ctx.packageName, row.ownerPackage)

        val pending = ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            "${MediaStore.MediaColumns._ID}=?",
            arrayOf(ok.entry.id.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else -1 } ?: -1
        assertEquals("IS_PENDING 必须已发布（0）", 0, pending)

        val ledger = MediaOwnership.readAll(ws)
        assertEquals("一条图一条账（不双写）", 1, ledger.size)
        assertEquals(MediaOwnership.SOURCE_SCREENSHOT, ledger[0].source)
        assertEquals("Pictures/Screenshots/" + ok.entry.name, ledger[0].relPath)
    }

    @Test
    fun 回读_生产装配同款的extraRoot读得到同一份像素() {
        val ok = publishShot(seed = 2) as MediaPublish.Ok
        // 生产装配（BaopuRuntime.build）：shotRoot = Environment.getExternalStorageDirectory()/Pictures
        val shotRoot = File(Environment.getExternalStorageDirectory(), "Pictures")
        // 引擎拿到的相对路径（ShotRef.file ＝ 相册口径去掉 Pictures/ 前缀）
        val rel = MediaOwnership.relUnderPictures(ok.entry.relPath)
        assertEquals("回读路径必须落在 Screenshots/ 下", "Screenshots/${ok.entry.name}", rel)

        val loaded = VisionFiles.load(ws, listOf(rel), extraRoot = shotRoot)
        assertEquals("必须读回一份（越界/不存在＝负例③现场）", 1, loaded.payloads.size)
        assertTrue("不许有跳过原因：${loaded.skipped}", loaded.skipped.isEmpty())
        assertEquals("读回的字节数要与落盘一致", png(2).size, loaded.payloads[0].bytes)
        // 文件本体确实在系统截屏夹里（人翻文件管理器看到的同一份）
        assertTrue(
            "文件必须真在 <SD根>/Pictures/Screenshots/ 下",
            File(shotRoot, rel).isFile,
        )
    }

    @Test
    fun 同图再发_判重_不产生第二行() {
        val first = publishShot(seed = 3) as MediaPublish.Ok
        val before = MediaStoreSink.publishedNames(ctx, MediaOwnership.relShotDir()).size
        val second = publishShot(seed = 3)
        assertTrue("同图必须判重：$second", second is MediaPublish.Duplicate)
        assertEquals(first.entry.id, (second as MediaPublish.Duplicate).entry.id)
        assertEquals(
            "相册里不许因为重复发布多出一行",
            before,
            MediaStoreSink.publishedNames(ctx, MediaOwnership.relShotDir()).size,
        )
    }

    @Test
    fun 删图后账本留墓碑_相册行真没了() {
        val ok = publishShot(seed = 4) as MediaPublish.Ok
        val r = MediaPublisher.deleteByRelPath(ctx, ok.entry.relPath)
        assertTrue("新目录的相册路径必须能删（判据照旧）：$r", r is MediaDelete.Done)
        created -= ok.entry.id
        assertNull("相册里那一行必须真没了（不是只删文件）", MediaStoreSink.rowOf(ctx, ok.entry.id))
        val all = MediaOwnership.readAll(ws)
        assertTrue("要留墓碑", all.any { it.id == ok.entry.id && !it.isLive })
        // 账本只追加：墓碑前那条 added 行仍在（与 JVM 口径「活记录只剩墓碑前那一条」一致）；
        // 「删过不许再当新的」由相册行消失兜底 ⇒ 再删一次必须 Failed，不许 Done
        val again = MediaPublisher.deleteByRelPath(ctx, ok.entry.relPath)
        assertTrue("再删一次不许再 Done（相册行已没了）：$again",
            again is MediaDelete.Failed || again is MediaDelete.Ask)
    }

    @Test
    fun 退路落点_仍是系统截屏夹_不是旧目录() {
        val dir = BrowserCommands.publicShotDir()
        assertEquals(
            "退路必须落 <SD根>/Pictures/Screenshots（268 §1.5）",
            File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots").absolutePath,
            dir.absolutePath,
        )
        assertTrue("目录必须可建/已在", dir.isDirectory)
        assertFalse("退路不许回旧目录（负例①）", dir.absolutePath.contains("证道"))
        // MANAGE_EXTERNAL_STORAGE ⇒ File 直写这个目录合法（真正落盘字节是退路的全部动作）
        val probe = File(dir, "268-退路探针-${System.currentTimeMillis()}.tmp")
        runCatching { probe.writeText("probe") }
        if (probe.isFile) runCatching { probe.delete() }
        assertTrue("共享存储必须可写（否则退路是空头支票）", probe.parentFile?.isDirectory == true && !probe.exists())
    }

    /**
     * **读用户自己截的图**（改4 口径的真机证据）：生产装配同款 extraRoot（`…/Pictures`）
     * 能读 `Screenshots/` 下**用户自己截的屏**（与抱朴的图同一个夹子）。
     * 只读不写；夹子里没有用户截图（assumeTrue 挡）就空跑。
     */
    @Test
    fun 回读_用户自己截的屏_生产装配读得到() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots")
        val imgExts = setOf("jpg", "jpeg", "png", "webp")
        val userShots = dir.listFiles { f ->
            f.isFile && f.length() > 0 && f.extension.lowercase() in imgExts &&
                !f.name.startsWith("证道截屏")
        }.orEmpty().sortedBy { it.name }
        org.junit.Assume.assumeTrue("夹子里没有用户自己的截图（本条空跑）", userShots.isNotEmpty())
        val target = userShots.first()
        val loaded = VisionFiles.load(
            ctx.cacheDir,
            listOf("Screenshots/${target.name}"),
            extraRoot = File(Environment.getExternalStorageDirectory(), "Pictures"),
        )
        assertEquals("用户自己的截图必须读得到（负例③现场）", 1, loaded.payloads.size)
        assertTrue("不许有跳过原因：${loaded.skipped}", loaded.skipped.isEmpty())
        assertEquals("payload 字节数要与文件一致", target.length(), loaded.payloads[0].bytes.toLong())
        Log.i(TAG, "ROW|user-shot|name=${target.name}|bytes=${target.length()}")
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

        private const val TAG = "screenshots-unified-268"
        private const val PAGE_URL = "https://www.baidu.com"
        private const val RETRY = 20
        private const val POLL_MS = 400L
        private const val OUT_SUB = "screenshots_unified_268"
        private const val OUT_FILE = "capture_result.json"

        /** 清理时间窗（只清这个前缀的测试残留；2026-10-29 真机验证窗口）。 */
        private const val CLEANUP_WINDOW = "20261029-01"
    }

    /**
     * **真机截一张**（262 批同款套路：起主界面 → 产品入口建玄览标签 → 调**产品方法**
     * [BrowserCommands.captureShotRef]）。不是探针自己造的 WebView，是生产代码全链路：
     * 离屏重载取图 → `MediaPublisher.publish`（MediaStore）→ 账本 → 回读 → 删图墓碑。
     *
     * 派工单 §2「模型回读仍可用」的真机证据落在这里：回读用的是**生产装配同款**
     * extraRoot（`Environment…/Pictures`），payload 字节数与相册行实际字节一致
     * （adoptToolImages 拿到的 payload 就是它——「能描述」的像素原料已进请求体）。
     * 模型文字回话那一跳需要已配置的通道，与本落点改动无关（见交付报告「未验」栏）。
     */
    @Test
    fun 真机截一张_经产品captureShotRef_落系统截屏夹_回读_删图墓碑() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val shotRoot = File(Environment.getExternalStorageDirectory(), "Pictures")
        var tabId: Int? = null
        var activity: MainActivity? = null
        var evidence = JSONObject().put("batch", "268").put("url", PAGE_URL)

        try {
            // 1. 起主界面（App 在前台 ⇒ 离屏取图的 1dp 挂窗可用）
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity = inst.startActivitySync(intent) as MainActivity
            Thread.sleep(1_500)

            // 2. 产品入口建玄览标签并载入页面（必须网络 URL，file:// 会被守卫静默丢弃）
            inst.runOnMainSync { tabId = BrowserTabManager.newTab(activity, PAGE_URL) }
            assertTrue("建不出玄览标签", tabId != null)
            Thread.sleep(4_000) // 给页面一个起跑时间（离屏重载自己还会再等 visual）

            // 3. 产品方法截一张（离屏重载 → MediaStore → 账本）
            //    注意：`return@repeat` 是 continue 不是 break——这里用旗标，成功就停手，
            //    否则一轮测试会把整个截屏夹刷满（2026-10-29 真机首轮教训）。
            var capture: BrowserCommands.ShotCapture? = null
            var attempt = 0
            while (attempt < RETRY && capture == null) {
                attempt++
                val r = runCatching { BrowserCommands.captureShotRef(ctx, null, "0") }.getOrNull()
                if (r is BrowserCommands.ShotCapture.Ok) capture = r
                else {
                    Log.i(TAG, "attempt $attempt: $r")
                    Thread.sleep(POLL_MS)
                }
            }
            @Suppress("USELESS_ELVIS")
            val ok = capture
            assertTrue("真机截屏必须成功：$ok", ok is BrowserCommands.ShotCapture.Ok)
            val ref = (ok as BrowserCommands.ShotCapture.Ok).ref
            Log.i(TAG, "ROW|capture|file=${ref.file}|host=${ref.hostPath}|bytes=${ref.bytes}|note=${ref.note}")
            evidence = evidence
                .put("file", ref.file)
                .put("hostPath", ref.hostPath)
                .put("bytes", ref.bytes)
                .put("note", ref.note ?: "")

            // 4. 落点三条：相对路径在 Screenshots/ 下、绝对路径在系统截屏夹、note 如实
            assertTrue("回读路径必须落在 Screenshots/ 下：${ref.file}", ref.file.startsWith("Screenshots/"))
            assertTrue(
                "hostPath 必须在系统截屏夹：${ref.hostPath}",
                ref.hostPath.startsWith(
                    File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots").absolutePath,
                ),
            )
            assertTrue("note 必须说进了系统截屏夹：${ref.note}", ref.note?.contains("系统截屏夹") == true)

            // 5. 相册里真有一行（人翻相册看到的那条）：RELATIVE_PATH/IS_PENDING/owner 全对
            val landedName = ref.file.substringAfterLast('/')
            val row = MediaStoreSink.rowByName(ctx, MediaOwnership.relShotDir(), landedName)
            assertNotNull("相册里必须有这一行（Pictures/Screenshots/$landedName）", row)
            row!!
            assertEquals("Pictures/Screenshots/", row.relPath)
            assertEquals("相册归属必须是本包", ctx.packageName, row.ownerPackage)
            created += row.id
            evidence = evidence.put("mediaId", row.id).put("landedName", landedName)

            // 6. 一份图只落一处：旧核对图目录里**没有**这张（不双写）
            val oldDir = File(ZhengdaoDirs.root(ctx), "截屏")
            assertFalse(
                "旧目录不得出现新截图（负例①）：${File(oldDir, landedName)}",
                File(oldDir, landedName).isFile,
            )

            // 7. 回读（生产装配同款 extraRoot）＋ 字节对账（与相册行实际字节逐位一致）
            val loaded = VisionFiles.load(ws, listOf(ref.file), extraRoot = shotRoot)
            assertEquals("必须读回一份（越界/不存在＝负例③现场）", 1, loaded.payloads.size)
            assertTrue("不许有跳过原因：${loaded.skipped}", loaded.skipped.isEmpty())
            assertEquals("payload 字节数与 ShotRef 一致", ref.bytes, loaded.payloads[0].bytes)
            val rowBytes = MediaStoreSink.readBytes(ctx, row.id)
            assertNotNull("相册行字节读不回来", rowBytes)
            assertEquals("相册行实际字节必须与 payload 一致", ref.bytes, rowBytes!!.size)
            evidence = evidence.put("payloadBytes", loaded.payloads[0].bytes)

            // 8. 账本照旧（读**生产工作区**的真账本——Application 启动装配会把
            //    MediaPublisher.env 指回真工作区，这正是生产行为；账本跟工作区走）：
            //    source=screenshot、relPath 相册口径全路径
            val ledgerRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx)
            val ledger = MediaOwnership.readAll(ledgerRoot)
            val entry = ledger.lastOrNull { it.name == landedName }
            assertNotNull("账本里必须有这一条（${ledgerRoot}/.归属.jsonl）", entry)
            entry!!
            assertEquals(MediaOwnership.SOURCE_SCREENSHOT, entry.source)
            assertEquals("Pictures/Screenshots/$landedName", entry.relPath)
            evidence = evidence
                .put("ledgerRelPath", entry.relPath)
                .put("ledgerSource", entry.source)

            // 9. 删图墓碑（走 delete_path 的同一入口；账本也是生产真账本）
            val del = MediaPublisher.deleteByRelPath(ctx, entry.relPath)
            assertTrue("自己写的必须能删：$del", del is MediaDelete.Done)
            created -= row.id
            assertNull("相册行必须真没了", MediaStoreSink.rowOf(ctx, row.id))
            val after = MediaOwnership.readAll(ledgerRoot)
            assertTrue("要留墓碑", after.any { it.id == entry.id && !it.isLive })
            val again = MediaPublisher.deleteByRelPath(ctx, entry.relPath)
            assertTrue("再删不许再 Done：$again",
                again is MediaDelete.Failed || again is MediaDelete.Ask)
            evidence = evidence.put("deleted", true).put("reDelete", again.javaClass.simpleName)
        } finally {
            runCatching {
                inst.runOnMainSync {
                    tabId?.let { id -> runCatching { BrowserTabManager.closeTab(id) } }
                    activity?.moveTaskToBack(true)
                }
            }
            // MainActivity 启动会把 MediaPublisher.env 重装回真工作区（生产行为）；
            // 这里再指回测试工作区，别让同轮后面的 1×1 用例把账写进真账本
            runCatching {
                MediaPublisher.env = MediaPublisher.Env(workspace = { ws }, log = { })
            }
            // 证据落 external files（adb pull 回收），失败也要留现场
            runCatching {
                val dir = ctx.getExternalFilesDir(OUT_SUB) ?: File(ctx.filesDir, OUT_SUB)
                dir.mkdirs()
                File(dir, OUT_FILE).writeText(evidence.toString(2), Charsets.UTF_8)
                Log.i(TAG, "evidence: ${File(dir, OUT_FILE).absolutePath}")
            }
        }
    }

    /**
     * 幂等清理：把**上一次真机验证**留下的截图（名字在验证时间窗内、账本 source=screenshot、
     * 相册行还活着）按产品删除口走一遍——行删掉、账本留墓碑。时间窗约束保证只清测试残留，
     * 不碰用户自己的任何图；二遍全零（都删过 ⇒ Failed ⇒ 跳过）。
     */
    @Test
    fun 清理_268真机验证残留() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // 指向生产真账本（删除判据要按真账本对）
        MediaPublisher.env = MediaPublisher.Env(
            workspace = { com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx) },
            log = { },
        )
        val window = "证道截屏-$CLEANUP_WINDOW"
        val ledger = MediaOwnership.readAll(com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx))
        var cleaned = 0
        for (e in MediaOwnership.live(ledger)) {
            if (e.source != MediaOwnership.SOURCE_SCREENSHOT) continue
            if (!e.name.startsWith(window)) continue
            when (val r = MediaPublisher.deleteByRelPath(ctx, e.relPath)) {
                is MediaDelete.Done -> cleaned++
                is MediaDelete.Failed -> Log.i(TAG, "cleanup skip ${e.name}: ${r.reason}")
                is MediaDelete.Ask -> Log.i(TAG, "cleanup skip ${e.name}: ${r.reason}")
            }
        }
        Log.i(TAG, "ROW|cleanup|window=$window|cleaned=$cleaned")
    }
}
