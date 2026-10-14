// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.keepalive

import android.app.ApplicationExitInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #5 被杀留档的单测（2026-10-09）。
 *
 * 只测**不需要 Context** 的那半边：原因码翻译、"哪些死法算不正常"、记录行的可解析性、
 * 去重、超限轮转、截断、RSS 换算、以及心跳行的字段完整性。真正读系统死亡证明
 * （`getHistoricalProcessExitInfos`）与非读不可的设备行为留给真机验证。
 */
class KeepaliveArchiveTest {

    private val tmp: File = File(System.getProperty("java.io.tmpdir"), "zd-keepalive-test").apply {
        deleteRecursively()
        mkdirs()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `原因码说人话`() {
        assertEquals("内存不足被系统清理（LMK）", KeepaliveArchive.reasonText(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("无响应（ANR）", KeepaliveArchive.reasonText(ApplicationExitInfo.REASON_ANR))
        assertEquals("崩溃（Java/Kotlin 异常）", KeepaliveArchive.reasonText(ApplicationExitInfo.REASON_CRASH))
        assertTrue(KeepaliveArchive.reasonText(999).startsWith("未知原因"))
    }

    @Test
    fun `预期内的退出不算异常`() {
        // 我们自己的、用户主动的、被更新的 = 正常
        assertFalse(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_EXIT_SELF))
        assertFalse(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertFalse(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_USER_STOPPED))
        assertFalse(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        // 不是我们安排的死法 = 要留档、要提示
        assertTrue(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_OTHER))
        assertTrue(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertTrue(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertTrue(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_ANR))
        assertTrue(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_SIGNALED))
        assertTrue(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_FREEZER))
    }

    @Test
    fun `后台被杀虽然报用户主动结束也要认出来`() {
        // 真机实测：am kill / 厂商后台管理杀进程时，系统给的是
        // reason=10（REASON_USER_REQUESTED）+ 描述 "[KILL BACKGROUND] kill background"。
        val bg = "[KILL BACKGROUND] kill background"
        assertTrue(KeepaliveArchive.isBackgroundKill(bg))
        assertTrue(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_USER_REQUESTED, bg))
        assertTrue(KeepaliveArchive.reasonText(ApplicationExitInfo.REASON_USER_REQUESTED, bg).contains("后台"))
        // 用户自己划掉的（同一原因码、没有后台杀的记号）仍算正常，不要吓人
        assertFalse(KeepaliveArchive.isBackgroundKill("[FORCE STOP] stop com.example.zhengdao"))
        assertFalse(KeepaliveArchive.isUnclean(ApplicationExitInfo.REASON_USER_REQUESTED, "[FORCE STOP] stop"))
        assertEquals(
            "用户主动结束",
            KeepaliveArchive.reasonText(ApplicationExitInfo.REASON_USER_REQUESTED, null),
        )
    }

    @Test
    fun `记录行能被解析回来`() {
        val r = KeepaliveArchive.ExitRecord(
            pid = 42,
            reason = ApplicationExitInfo.REASON_OTHER,
            timestamp = 1_700_000_000_000L,
            importance = 400,
            rssKb = 210 * 1024,
            pssKb = 180 * 1024,
            description = "厂商后台清理",
        )
        val line = KeepaliveArchive.recordLine(r)
        assertTrue(line.contains("pid=42 ts=1700000000000 reason="))
        assertTrue(line.contains("原因=其它（厂商后台清理最常见）"))
        assertTrue(line.contains("rss=210MB"))
        assertTrue(line.contains("描述=厂商后台清理"))
        // 落盘再解析：键必须一致（去重靠它，格式一改就会重复归档）
        val f = File(tmp, KeepaliveArchive.EXITS)
        f.writeText(line)
        assertEquals(setOf(KeepaliveArchive.key(r)), KeepaliveArchive.keys(f.readText()))
    }

    @Test
    fun `已经记过的退出不再归档`() {
        val a = KeepaliveArchive.ExitRecord(1, 0, 1000L, 400, 0, 0)
        val b = KeepaliveArchive.ExitRecord(2, 0, 2000L, 400, 0, 0)
        val existing = setOf(KeepaliveArchive.key(a))
        assertEquals(listOf(b), KeepaliveArchive.fresh(existing, listOf(a, b)))
        assertTrue(KeepaliveArchive.fresh(emptySet(), listOf(a, b)).size == 2)
    }

    @Test
    fun `超限轮转_追加式_总占用有上限`() {
        // D-1（第⑪批 B-1）：轮转从「整读整写 + 截断说明」改为「整体 rename 成 .1」，
        // 本用例随判据更新（旧用例钉的是旧语义，见工单 D-1 要求）。
        val f = File(tmp, KeepaliveArchive.HEARTBEAT)
        repeat(40) { KeepaliveArchive.appendCapped(f, "[时间] 心跳第 $it 条\n", 256L) }
        val rolled = File(tmp, KeepaliveArchive.HEARTBEAT + ".1")
        val text = f.readText()
        assertTrue(f.length() <= 256 + 200) // 活动文件不超上限（轮转在 append 前判定）
        assertTrue("超限后必须有一份 .1 旧档", rolled.isFile)
        assertTrue("旧档里应是历史心跳", rolled.readText().contains("心跳第"))
        assertFalse("最新的记录应在活动文件里", rolled.readText().contains("第 39 条"))
        assertTrue(text.contains("第 39 条")) // 最新那条必须在
        assertFalse(text.contains("已截断")) // 追加式轮转不再整读整写，没有截断说明
        assertTrue(
            "旧档也应在上限量级（整体 rename，不增长）",
            rolled.length() <= 256 + 200,
        )
    }

    @Test
    fun `超限后旧内容整体留在点一份_活动文件从新内容开始`() {
        val f = File(tmp, "hb.log")
        KeepaliveArchive.appendCapped(f, "OLD-1\n", 8L) // 6B，未超
        KeepaliveArchive.appendCapped(f, "OLD-2\n", 8L) // 12B，未超
        KeepaliveArchive.appendCapped(f, "OLD-3\n", 8L) // 12B > 8 ⇒ 本条写入前先轮转
        val rolled = File(tmp, "hb.log.1")
        assertTrue("超限后必须有一份 .1 旧档", rolled.isFile)
        assertEquals("旧内容应原样整体迁移（不截断不重写）", "OLD-1\nOLD-2\n", rolled.readText())
        assertEquals("活动文件只装轮转之后的新内容", "OLD-3\n", f.readText())
        // 再超限：.1 被最新一份旧档顶掉（旧档最多留一份）
        KeepaliveArchive.appendCapped(f, "OLD-4\nOLD-5\nOLD-6\n", 8L) // 6B 未超 → 24B
        KeepaliveArchive.appendCapped(f, "OLD-7\n", 8L) // 24B > 8 ⇒ 轮转，.1 被顶掉
        assertEquals("OLD-3\nOLD-4\nOLD-5\nOLD-6\n", rolled.readText())
        assertEquals("OLD-7\n", f.readText())
    }

    @Test
    fun `轮转后读端_旧档并入_不出现假空窗`() {
        // 收尾补充（第⑪批 B-1 / D-1）：轮转把活动文件整体改名成 `.1` 后它是空的，
        // 读端必须把 `.1` 并进来，否则保活页在轮转瞬间显示「一条心跳都没有」。
        val f = File(tmp, KeepaliveArchive.HEARTBEAT)
        KeepaliveArchive.appendCapped(f, "[a1]\n", 10L) // 5B
        KeepaliveArchive.appendCapped(f, "[b2]\n", 10L) // 10B
        KeepaliveArchive.appendCapped(f, "[c3]\n", 10L) // 10B 未超 ⇒ 15B
        KeepaliveArchive.appendCapped(f, "[d4]\n", 10L) // 15B > 10 ⇒ 轮转后再写
        assertTrue("前提：确实发生了一次轮转", File(tmp, KeepaliveArchive.HEARTBEAT + ".1").isFile)
        assertEquals(
            "读端应把旧档拼在活动文件之前（新 → 旧）",
            listOf("[d4]", "[c3]", "[b2]", "[a1]"),
            KeepaliveArchive.heartbeatTailIn(tmp, 6),
        )
        assertEquals("只要最新 2 条", listOf("[d4]", "[c3]"), KeepaliveArchive.heartbeatTailIn(tmp, 2))
        val clean = File(tmp, "clean").apply { mkdirs() }
        File(clean, KeepaliveArchive.HEARTBEAT).writeText("[x1]\n[y2]\n")
        assertEquals(
            "没有 .1 时行为与改造前一致",
            listOf("[y2]", "[x1]"),
            KeepaliveArchive.heartbeatTailIn(clean, 6),
        )
    }

    @Test
    fun `截断保留尾部并说明`() {
        val text = "A".repeat(100) + "尾巴"
        val out = KeepaliveArchive.truncate(text, 10)
        assertTrue(out.endsWith("尾巴"))
        assertTrue(out.contains("已截断"))
        assertEquals("短文本不动", KeepaliveArchive.truncate("短文本不动", 100))
    }

    @Test
    fun `RSS 页数换算成 MB`() {
        assertEquals(4L, KeepaliveArchive.rssMb(1024, 4096))
        assertEquals(0L, KeepaliveArchive.rssMb(0, 4096))
    }

    @Test
    fun `内存压力档位说人话`() {
        assertTrue(KeepaliveArchive.trimText(15).contains("TRIM_MEMORY_RUNNING_CRITICAL"))
        assertTrue(KeepaliveArchive.trimText(80).contains("TRIM_MEMORY_COMPLETE"))
        assertEquals("TRIM_MEMORY_7", KeepaliveArchive.trimText(7))
    }

    @Test
    fun `片段只留最近几份`() {
        val dir = File(tmp, "snippets").apply { mkdirs() }
        repeat(8) { i ->
            File(dir, "logcat-boot-2026100$i-000000.txt").apply {
                writeText("片段 $i")
                setLastModified(1_700_000_000_000L + i * 1000L)
            }
        }
        assertEquals(4, KeepaliveArchive.pruneSnippets(dir, keep = 4))
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(4, left.size)
        assertTrue(left.contains("logcat-boot-20261007-000000.txt")) // 最新的那份必须活着
    }

    @Test
    fun `心跳行带服务与内存字段`() {
        val line = KeepaliveWatcher.heartbeatLine(
            now = 1_700_000_000_000L,
            event = "界面进入 MainActivity",
            serviceRunning = true,
            memory = "内存=堆 10/256MB",
            threads = 12,
            activity = "MainActivity",
        )
        assertTrue(line.startsWith("["))
        assertTrue(line.contains("界面进入 MainActivity"))
        assertTrue(line.contains("服务=运行中"))
        assertTrue(line.contains("内存=堆 10/256MB"))
        assertTrue(line.contains("线程=12"))
        assertTrue(line.contains("界面=MainActivity"))
    }
}
