// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §3 死卡的那一条）：**真机冷扫的实测数字**——几万文件、多少 MB、扫多久、峰值内存。
//
// ## 为什么这份必须在设备上跑
// 冷扫要真的 `listFiles()`、真的 `SQLiteDatabase`、真的那几个 G 字节；本仓没有 Robolectric
// （`android.jar` 是 stub）⇒ JVM 里一条都跑不动。判据与算式那半边由 `PhoneScanTest`／`PhoneTextTest`／
// `PhoneRankTest`／`PhoneIndexArgsTest`／`PhoneSearchWiringTest` 钉死，本文件只负责那一串**读数**。
// 派工单原话「不许估算」——所以这里没有一个是推算出来的数，全部是当场读出来的；
// 前提（开关＋甲档授权）不满足就直接判红并说清要点什么，**不产出任何半个数字**。
//
// ## 一个反直觉但必须记的设计事实
// 一次扫描有 90 秒预算（`PhoneScanRules.SCAN_TIMEOUT_MS`），预算到就 `TIMEOUT` 停下、已入库的照常可搜。
// 所以「冷扫要多久」的真实答案通常不是**一趟**的时间，而是**若干趟**的累加：
// 第二趟起 `reuse` 那条快路生效（账上 size+mtime 对上的文件一个字节都不重读），
// 于是每趟的 `bytesRead` 断崖式下降、只补没扫过的那部分。本文件把每一趟都记下来，
// 因为「扫完」与「扫一趟」是两件事，报告里只写后者就是把数字说小。
//
// ## 峰值内存为什么不开采样线程
// 起一个线程每 50 ms 轮一次 `ActivityManager.getProcessMemoryInfo`：一来那条 API 的 `totalPss` 早就废弃
// （lint 会报红，本批预算 0/0），二来采样本身要 CPU，会把「扫多久」这条读数抬高——被测的东西被量具改变了。
// 所以这里读内核自己记的 `/proc/self/status`：`VmHWM` 是**整个进程生命期的峰值 RSS**（不是采样估计），
// `VmRSS` 是当下值；扫前扫后各读一次，差值就是这趟扫描把内存顶高了多少。一个线程都不新建，
// 也顺手避开 §2「常驻扫描」那条负例的形状。
//
// ## 怎么跑（她的规矩：`adb install -r` ＋ `am instrument`，绝不 `connectedDebugAndroidTest`）
// ```
// JAVA_HOME='D:/Program Files/Android/Android Studio/jbr' ./gradlew.bat --offline -x lint \
//   :app:assembleDebug :app:assembleDebugAndroidTest
// adb install -r app/build/outputs/apk/debug/app-debug.apk
// adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
// adb shell am instrument -w -e class com.example.zhengdao.baopu.search.PhoneSearchColdScanInstrumentedTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// adb exec-out run-as com.example.zhengdao cat files/zd-cold-scan.txt   # 同一份数字，落盘版
// ```
// ⚠️ 不用 `connectedDebugAndroidTest`：AGP 跑完会**卸载 App**，而 `files/` 里是整台手机上的 Linux
// （rootfs 201MB + hermes 4.4G + 抱朴 opencode 289MB，见 `StorageReadWriteSmokeTest` 文件头那次事故）。
package com.example.zhengdao.baopu.search

import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSearchColdScanInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `真机冷扫_文件数_字节_耗时_峰值内存一次记全`() {
        val st = SearchScope.status(ctx)
        // 2026-10-26 第二百二十九批：搜索范围**不再有开关**——给了"所有文件访问"就默认全盘。
        // 所以前提只剩一条：系统授权真的给了（没给就如实失败，不许交一个 0 根的数字）。
        assertTrue(
            "前提未满足（档位=" + st.access + "）：这一条只出读数——" +
                "去 系统设置 → 应用 → 证道 → 所有文件访问 授予授权后重跑" +
                "（不许在没授权的情况下交一个 0 根的数字）",
            st.access == PhoneAccess.GRANTED,
        )

        val out = StringBuilder()
        fun say(s: String) {
            Log.i(TAG, s)
            out.append(s).append('\n')
        }

        say("设备=${Build.MANUFACTURER} ${Build.MODEL} Android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        val snap = PhoneSearch.snapshot(ctx)
        snap.roots.forEach { say("授予根 ${it.kind.tag} = ${it.absRoot}") }
        assertTrue("账上一个有效根都没有（开关与授权都过了却读不到根 ⇒ 是判据断了，不是没数据）", snap.roots.isNotEmpty())

        // 「冷扫」的定义就是**索引文件不存在**：先把它删掉，只删自己那一颗，路径两头都断死。
        val db = SearchDb.dbPathUnder(ctx.filesDir)
        assertTrue("search.db 不在应用私有目录里（${db.absolutePath}）：不删，免得删到用户的东西", db.absolutePath.startsWith(ctx.filesDir.absolutePath))
        assertTrue("要删的路径不是 search.db 那一颗（${db.name}）", db.name == "search.db" || db.name.startsWith("search.db-"))
        listOf(db, File(db.path + "-wal"), File(db.path + "-journal"), File(db.path + "-shm")).forEach { f ->
            if (f.isFile) {
                say("删掉旧索引 ${f.name} = ${f.length() / 1024 / 1024} MB")
                assertTrue("旧索引删不掉（${f.absolutePath}）：这一趟就不是冷扫，读数作废", f.delete())
            }
        }

        // 删库把 scope 账一起带走了 ⇒ 必须先把账本重同步出来，否则下面 scan 的 activeRoots()
        // 是空表，「冷扫」扫的是零个根——那是一份 0 读数冒充的冷扫（2026-10-25 原稿漏了这步，
        // 真机跑之前读 scan 源码抓出来的：scan 只读账，不建账）。
        PhoneSearch.syncNow(ctx)
        val roots2 = PhoneSearch.snapshot(ctx).roots
        assertTrue("删库重同步后账上一个根都没有（开关与授权都过了还这样 ⇒ 是判据断了）：冷扫会扫零个根，读数作废", roots2.isNotEmpty())
        roots2.forEach { say("重同步后的授予根 ${it.kind.tag} = ${it.absRoot}") }

        val rss0 = statusKb("VmHWM")
        val cur0 = statusKb("VmRSS")
        val native0 = Debug.getNativeHeapAllocatedSize()
        val heap0 = jvmHeapBytes()

        // ① 真正的冷扫：忽略水位、忽略快路，整根重扫
        val cold = PhoneSearch.scan(ctx, forceFull = true)
        say(
            "冷扫一趟：用时 ${cold.elapsedMs} ms、扫到 ${cold.scanned} 个、入库 ${cold.docsWritten} 篇、" +
                "不认领 ${cold.unclaimed} 篇、长文截断 ${cold.cappedDocs} 篇、跳过 ${cold.skipped} 个、" +
                "读了 ${mb(cold.bytesRead)}、切片 ${cold.shards} 片、失败 ${cold.failedShards} 片、" +
                "停因 ${cold.stopped.name}、约 M=${cold.approxTotal ?: "（还没扫全过）"}",
        )
        say("冷扫之后 search.db = ${db.length() / 1024 / 1024} MB")

        // ② 之后每一趟都是增量：快路生效 ⇒ 只补没扫过的那部分。扫到 DONE 为止（最多 MAX_PASSES 趟）。
        var pass = 1
        var total = cold.elapsedMs
        var last = cold
        var done = cold.stopped == StopReason.DONE
        while (!done && pass < MAX_PASSES) {
            last = PhoneSearch.scan(ctx)
            pass++
            total += last.elapsedMs
            done = last.stopped == StopReason.DONE
            say(
                "第 $pass 趟（增量）：用时 ${last.elapsedMs} ms、扫到 ${last.scanned} 个、复用 ${last.reused} 篇、" +
                    "入库 ${last.docsWritten} 篇、删掉 ${last.goneDeleted} 篇、读了 ${mb(last.bytesRead)}、" +
                    "停因 ${last.stopped.name}、search.db=${db.length() / 1024 / 1024} MB",
            )
        }
        say(
            "建全量索引合计：$pass 趟、${total} ms（${total / 1000} 秒）、最后入库 ${last.docsWritten} 篇＋复用 ${last.reused} 篇、" +
                "扫到 ${last.scanned} 个、读了 ${mb(last.bytesRead)}、${if (done) "扫完（DONE）" else "还没扫完（预算到）"}",
        )

        // ③ 检索延迟：索引建起来之后，用户按一次「搜索」要等多久。
        listOf("报告", "合同", "证道").forEach { q ->
            val hits = PhoneSearch.query(ctx, q)
            say("检索「$q」用时 ${measureMs { PhoneSearch.query(ctx, q) }} ms、命中 ${hits.hits.size} 条")
        }

        val rss1 = statusKb("VmHWM")
        say(
            "峰值内存（内核记账，不是采样）：VmHWM 扫前 ${kB(rss0)}、扫后 ${kB(rss1)}、差 ${kB(rss1 - rss0)}；" +
                "VmRSS 扫前 ${kB(cur0)}、扫后 ${kB(statusKb("VmRSS"))}",
        )
        say(
            "native 堆：扫前 ${mb(native0)}、扫后 ${mb(Debug.getNativeHeapAllocatedSize())}；" +
                "JVM 已用堆：扫前 ${mb(heap0)}、扫后 ${mb(jvmHeapBytes())}",
        )
        say("说明：VmHWM/VmRSS 含整个进程（rootfs、hermes 那些都在里面），所以能交的是「峰值差值」而不是「扫描独占内存」。")

        val f = File(ctx.filesDir, OUT_NAME)
        f.writeText(out.toString())
        assertTrue("读数没落盘（${f.absolutePath}）：报告拿不到数字", f.isFile && f.length() > 200L)
        assertTrue("读起来一条数字都没有", out.contains("用时"))
    }

    /** `/proc/self/status` 里某个键的千字节数；读不到返回 -1（不许编一个数）。 */
    private fun statusKb(key: String): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith(key) }
            .filter { it.isDigit() }.toLong()
    }.getOrDefault(-1L)

    private fun jvmHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun kB(v: Long): String = if (v < 0) "（读不到）" else "${v} kB（${v / 1024} MB）"

    private fun mb(bytes: Long): String = if (bytes < 1024) "$bytes B" else "${bytes / 1024 / 1024} MB（$bytes 字节）"

    private inline fun measureMs(block: () -> Unit): Long {
        val t = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - t
    }

    private companion object {
        const val TAG = "ZdColdScan"
        const val OUT_NAME = "zd-cold-scan.txt"

        /** 最多补几趟：预算 90 秒 × 8 ≈ 12 分钟设备时间，再多就是占着手机不干别的事。 */
        const val MAX_PASSES = 8
    }
}
