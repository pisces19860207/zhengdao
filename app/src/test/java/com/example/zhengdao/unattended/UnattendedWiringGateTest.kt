// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 388 的**源码门禁**（照 ScheduleRedLineGateTest 的口径：读源码、剥注释、按文本判）：
// 派工单里这几条不是"行为写错了"而是"接线必须存在/不许存在"——
// 前台服务必须 specialUse、通知必须走深链、引擎必须把离场锁屏喂给弹卡闸、
// 渠道只许升档不许新增。行为测试拦不住"接线做多了/做漏了"，只能钉在源码上。
package com.example.zhengdao.unattended

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UnattendedWiringGateTest {

    /**
     * 工作目录可能是 app/ 也可能是仓根：两种都试，再兜一层 ../（:core 的文件从 app/ 看
     * 在 ../core/ 下）。照 ScheduleRedLineGateTest 的口径加一层"必须读到"，防路径解析坏了
     * 之后整套账静默空转跑绿。
     */
    private fun repoFile(rel: String): File {
        val f = listOf(File(rel), File("app/$rel"), File("../$rel"))
            .firstOrNull { it.isFile || it.isDirectory }
        assertTrue("源码文件读不到（路径解析坏了整套账会空转）：$rel", f != null)
        return f!!
    }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    private val manifest: String get() = code("src/main/AndroidManifest.xml")

    // ── manifest：前台服务 / 值守页 / 深链 ──────────────────────────────

    @Test
    fun `前台服务在清单里_specialUse类型_带subtype说明`() {
        assertTrue(manifest.contains(".unattended.UnattendedDutyService"))
        assertTrue("值守服务必须 specialUse（dataSync 有 6h onTimeout）", manifest.contains("specialUse"))
        assertTrue("specialUse 必须写 PROPERTY_SPECIAL_USE_FGS_SUBTYPE", manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    }

    @Test
    fun `值守页在清单里_不对外导出`() {
        // 活动是自闭合（/>，没有 </activity>），所以"名字之后 200 字内必须 exported=false"
        // 就是"这一块就是值守页"的形状判（照 ScheduleRedLineGateTest 的近邻窗口口径）。
        val block = Regex(
            "\\.unattended\\.UnattendedActivity\"[\\s\\S]{0,200}?exported=\"false\"",
        ).find(manifest)
        assertTrue("值守页必须在清单里且 exported=false（只许深链进）", block != null)
    }

    @Test
    fun `深链unattended在清单里_带BROWSABLE`() {
        assertTrue("通知点按直达的深链必须在清单开正门", manifest.contains("android:host=\"unattended\""))
        // host 所在的 intent-filter 块里要有 BROWSABLE（块从 host 往前找开口、往后找合口）。
        val before = manifest.substringBefore("android:host=\"unattended\"")
        val start = before.lastIndexOf("<intent-filter")
        val end = manifest.indexOf("</intent-filter>", before.length)
        assertTrue(start >= 0 && end > start)
        assertTrue("unattended 的 filter 要 BROWSABLE", manifest.substring(start, end).contains("BROWSABLE"))
    }

    // ── 引擎接线：离场锁屏 ⇒ 弹卡闸 ────────────────────────────────────

    @Test
    fun `引擎把离场锁屏喂给弹卡闸_问闸与补问两处都要`() {
        val e = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("quietDefer 要把 awayLocked 传给 onAsk", e.contains("quietGate.onAsk(quietGrant, quietShape(now), now, req, locked)"))
        assertTrue("drainQuietQueue 要把 awayLocked 传给 takeNext", e.contains("quietGate.takeNext(quietGrant, quietShape(now), now, locked)"))
        assertTrue("被离场锁屏压下的卡要交 awayEvent（值守记账＋通知）", e.contains("awayEvent("))
    }

    @Test
    fun `装配层接的是UnattendedStatus的问口_引擎不摸SP不摸Keyguard`() {
        val r = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(r.contains("awayLocked = { UnattendedStatus.awayAndLocked() }"))
        val e = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("引擎文件不许出现 KeyguardManager", !e.contains("KeyguardManager"))
        assertTrue("引擎文件不许出现 SharedPreferences", !e.contains("SharedPreferences"))
    }

    // ── 通知：只升档不新增渠道 + 四类各走各档 + 深链 ────────────────────

    @Test
    fun `agent渠道升HIGH_带delete重建的升档口_不新增第五个渠道`() {
        val n = code("core/src/main/java/com/example/zhengdao/core/notify/AppNotifications.kt")
        assertTrue("agent 渠道要升到 HIGH", n.contains("IMPORTANCE_HIGH"))
        assertTrue("已装用户的升级必须 delete+recreate（改 importance 不生效）", n.contains("deleteNotificationChannel"))
        val ids = Regex("const val (SESSION|INSTALL|AGENT|UPDATE) = \"(\\w+)\"").findAll(n).toList()
        assertTrue("渠道仍是四个，不许新增第五个：${ids.size}", ids.size == 4)
    }

    @Test
    fun `四类通知_高档走agent_普通档走session_一个不许错档`() {
        val n = code("src/main/java/com/example/zhengdao/unattended/UnattendedNotifier.kt")
        // 高优先（红线停手、圈外入队）→ AGENT；普通（暂停/复工/降级/报告）→ SESSION。
        assertTrue(n.contains("NOTIF_STOP, REQ_STOP, AppNotifications.AGENT,"))
        assertTrue(n.contains("NOTIF_QUEUE, REQ_QUEUE, AppNotifications.AGENT,"))
        assertTrue(n.contains("NOTIF_PAUSE, REQ_PAUSE, AppNotifications.SESSION,"))
        assertTrue(n.contains("NOTIF_DEGRADE, REQ_DEGRADE, AppNotifications.SESSION,"))
        assertTrue(n.contains("NOTIF_REPORT, REQ_REPORT, AppNotifications.SESSION,"))
    }

    @Test
    fun `通知点击走深链_直达值守页`() {
        // 深链含 "//"——剥注释会把字符串字面里的 // 也吃掉，所以这条用**原文**判。
        val raw = repoFile("src/main/java/com/example/zhengdao/unattended/UnattendedNotifier.kt")
            .readText().replace("\r\n", "\n")
        assertTrue(raw.contains("zhengdao://unattended"))
        val n = code("src/main/java/com/example/zhengdao/unattended/UnattendedNotifier.kt")
        assertTrue("每条通知都要挂 contentIntent", n.contains(".setContentIntent(deepLink(ctx, "))
    }

    // ── 服务与看门狗 ────────────────────────────────────────────────────

    @Test
    fun `服务用ServiceCompat起前台_带specialUse类型_STICKY`() {
        val s = code("src/main/java/com/example/zhengdao/unattended/UnattendedDutyService.kt")
        assertTrue(s.contains("ServiceCompat.startForeground"))
        assertTrue(s.contains("FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        assertTrue("进程被杀后系统尽量重建", s.contains("START_STICKY"))
    }

    @Test
    fun `看门狗排在WorkManager上_KEEP幂等_断档要补报`() {
        val w = code("src/main/java/com/example/zhengdao/unattended/UnattendedWatchdog.kt")
        assertTrue(w.contains("PeriodicWorkRequestBuilder<UnattendedWatchdogWorker>"))
        assertTrue(w.contains("ExistingPeriodicWorkPolicy.KEEP"))
        assertTrue("断档必须记中断账（被中断要补报）", w.contains("UnattendedRecorder.interrupt"))
    }

    @Test
    fun `派工单红线_值守不许碰L3不许自动发版_发版归外发红线`() {
        // 在飞 388 明令"不碰 L3、不自动发版"：值守代码里不许出现发版/升级通道的口子。
        val dir = repoFile("src/main/java/com/example/zhengdao/unattended")
        val all = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }.orEmpty()
            .joinToString("\n") { it.readText().replace("\r\n", "\n") }
        assertTrue("不许起发版类的活动口子", !all.contains("installVersion("))
        assertTrue("不许引用计划模式（L3）的实现", !all.contains("PlanMode"))
    }
}
