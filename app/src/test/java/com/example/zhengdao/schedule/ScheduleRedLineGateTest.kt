// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百六十七批 · 定时任务 v1》的红线与 §0.5 三条硬约束、§2 权限三行、§3 负例清单。
//
// 这一套是**源码门禁**（按文本判，剥掉注释再判，防"注释里写一句就算过"）：
// 派工单的红线有几条不是"行为写错了"，而是"这条路径根本不该存在"——
// 例如「定时任务不许自动调工具」「不许抬库版本」「通知不许建第二套」。
// 这些用行为测试拦不住（功能没做错，是接线做多了），只能钉在源码上。
//
// 口径与仓里同类门禁一致：见 terminal/NotificationRequestCodeGateTest、ui/settings/SettingsPureGateTest。
package com.example.zhengdao.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.notify.AppNotifications

class ScheduleRedLineGateTest {

    /**
     * 单元测试进程的工作目录是模块目录 app/（`CoroutineCrashHandlerTest` 就按这个写），
     * 但也有跑法是从仓根起；所以两种写法都试。**目录也要认**：只判 isFile 会把目录当没看见，
     * 于是 pkgDir 指到不存在的 app/src/... ⇒ listFiles() 读出 0 个文件 ⇒ 整套账空转跑绿
     * （本批负例 M1 第一次跑绿就是这么暴露的）。
     */
    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile || it.isDirectory) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    private val pkgDir: File
        get() = repoFile("src/main/java/com/example/zhengdao/schedule")

    /** 包内全部源码（剥注释）：新加一个文件就自动进这张账，不用改测试。 */
    private fun packageCode(): String {
        val files = pkgDir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue(
            "包内至少要读到 11 个 .kt（读到 0 个说明路径解析又坏了，这套账会静默空转）：实际 " +
                files.size + " 个，目录 " + pkgDir.absolutePath,
            files.size >= 11,
        )
        return files.joinToString("\n") { code("src/main/java/com/example/zhengdao/schedule/${it.name}") }
    }

    // ── ① 红线：不自动调工具、不替你点确认 ────────────────────────────────

    @Test
    fun `红线_定时任务只发一句话不许碰工具与确认`() {
        val pkg = packageCode()
        for (bad in listOf(
            "resumeWithApproval",   // 替用户点"允许"
            "approveRestoredPending",
            "answerChoice",         // 替用户回答决策卡
            "ToolRegistry",
            "executeTool",
            "SkillStore",           // §0.5 约束 3：技能线不碰
        )) {
            assertFalse("定时任务里出现了「$bad」——v1 只到点发预设 prompt，工具与确认一概不动", pkg.contains(bad))
        }
        // 引擎侧只许这两个入口（与用户在输入框敲一句话同一条路）。
        val runner = code("src/main/java/com/example/zhengdao/schedule/ScheduleRunner.kt")
        assertTrue("装配层必须走 engine.send（用户同一个入口）", runner.contains("engine.send("))
        assertTrue("没有绑定会话时新建一个（标题带出处）", runner.contains("engine.createSession("))
    }

    // ── ② §0.5 约束 3：不碰 mcp / browser / 终端 / rootfs 执行链 ──────────

    @Test
    fun `约束3_不接终端与浏览器与MCP那条线`() {
        val pkg = packageCode()
        for (bad in listOf(
            "com.example.zhengdao.bridge",
            "com.example.zhengdao.ui.browser",
            "com.example.zhengdao.baopu.mcp",
            "SessionService",
            "ProotLauncher",
            "ProcessBuilder",
            "Runtime.getRuntime",
        )) {
            assertFalse("定时任务里出现了「$bad」——本批不许碰 A 段 / rootfs / 终端 / MCP / 浏览器", pkg.contains(bad))
        }
        // rootfs 包里只许用日志那一个类（RunLog 是纯落盘日志，不是执行链）。
        Regex("com\\.example\\.zhengdao\\.rootfs\\.[A-Za-z]+").findAll(pkg).forEach {
            assertEquals("rootfs 只许引 RunLog：${it.value}", "com.example.zhengdao.core.RunLog", it.value)
        }
        // terminal 包里唯一允许的引用是 169 那套通知渠道（§0.5 约束 2 要求复用它，不许另建）。
        Regex("com\\.example\\.zhengdao\\.terminal\\.[A-Za-z]+").findAll(pkg).forEach {
            assertEquals(
                "terminal 只许引 AppNotifications（复用渠道，不接终端执行链）：${it.value}",
                "com.example.zhengdao.terminal.AppNotifications",
                it.value,
            )
        }
    }

    // ── ③ §0.5 约束 1：不抬库版本、不进 SQLite ───────────────────────────

    @Test
    fun `约束1_v1存独立文件不抬库版本`() {
        val pkg = packageCode()
        for (bad in listOf("onUpgrade", "user_version", "DATABASE_VERSION", "SupportSQLiteOpenHelper", "openHelper")) {
            assertFalse("定时任务里出现了「$bad」：172-L4 正在 BaopuDb.kt 上做 V19，本批不许抬库版本", pkg.contains(bad))
        }
        // 存储落点钉死在私有目录的那个文件名上（界面上「坏了」那句也报同一个名字）。
        val store = code("src/main/java/com/example/zhengdao/schedule/ScheduleStore.kt")
        assertTrue(
            "表名钉在 files/schedule_tasks.json（不抬库版本的物理凭据）",
            store.contains("""const val FILE_NAME = "schedule_tasks.json""""),
        )
        assertTrue("读的是 filesDir（私有目录），不是共享存储", store.contains("filesDir"))
        assertFalse("存储不许落在 Download/ 之类共享路径", store.contains("/storage/emulated"))
    }

    // ── ④ §0.5 约束 2：通知复用 169 那套，不建第二套 ─────────────────────

    @Test
    fun `约束2_通知复用既有渠道与前台口径`() {
        val notifier = code("src/main/java/com/example/zhengdao/schedule/ScheduleNotifier.kt")
        val pkg = packageCode()
        assertTrue("必须走 169 那套渠道（AGENT），不新增第五个渠道", notifier.contains("AppNotifications.AGENT"))
        assertTrue("必须与 169 同一个前台抑制口径", notifier.contains("AppForeground.isForeground()"))
        for (bad in listOf("NotificationChannel(", "createNotificationChannel")) {
            assertFalse("定时任务自己建了渠道（「$bad」）——那就是第二套通知栈", pkg.contains(bad))
        }
        // 点按回会话用的挂单口也是 169 那个键（见 ui/baopu/BaopuIntents）。
        assertTrue("点通知回会话必须复用 BaopuIntents.EXTRA_SESSION", notifier.contains("BaopuIntents.EXTRA_SESSION"))
    }

    // ── ⑤ §2：权限判定只有一份，且三行权限只出现在一处 ────────────────────

    @Test
    fun `权限_通知判定委托既有单一来源`() {
        val perms = code("src/main/java/com/example/zhengdao/schedule/SchedulePermissions.kt")
        assertTrue(
            "通知权限必须委托 PermissionSetup.notificationsGranted（两处各查一边迟早得出两个结论）",
            perms.contains("PermissionSetup.notificationsGranted"),
        )
        val pkg = packageCode()
        assertFalse(
            "包内不许再抄一份通知权限字面量（用 Manifest 常量或 PermissionSetup 那一份）",
            pkg.contains("\"android.permission.POST_NOTIFICATIONS\""),
        )
        val exactLiteral = Regex("\"android\\.permission\\.SCHEDULE_EXACT_ALARM\"").findAll(pkg).count()
        assertTrue("精确闹钟权限字面量全包只许一处，实际 $exactLiteral 处", exactLiteral <= 1)
    }

    @Test
    fun `权限_三行只在设置页有一套控件`() {
        // 第二百一十批（入口与权限归位）：三行从 SettingsDataActivity 的「定时任务用到的权限」
        // 卡搬进设置 Hub 顶部的「申请权限」集中卡（ui/SettingsPermissionCard.kt）——
        // 「一处一套控件」的口径没变，只是那一处换了文件；卡里还多一行「所有文件访问」。
        val card = code("src/main/java/com/example/zhengdao/ui/SettingsPermissionCard.kt")
        val pkg = packageCode()
        for (key in listOf("sched_perm_notify", "sched_perm_exact", "sched_perm_battery")) {
            assertTrue("申请权限卡缺权限行「$key」", card.contains("R.string.$key"))
            assertFalse("定时任务页不许再来一套权限控件（$key）——一处一套", pkg.contains("R.string.$key"))
        }
        // 降级路径要说出来（§5 ②）：三条理由各归一行，不许合并成一句"请授权"。
        for (desc in listOf("sched_perm_notify_desc", "sched_perm_exact_desc", "sched_perm_battery_desc")) {
            assertTrue("缺理由行「$desc」：用户得知道不给会怎样", card.contains("R.string.$desc"))
        }
    }

    // ── ⑥ §3 负例：删除/暂停之后不许还跑 ────────────────────────────────

    @Test
    fun `负例_删除与暂停必须撤掉系统登记`() {
        val page = code("src/main/java/com/example/zhengdao/schedule/ScheduleTasksActivity.kt")
        assertTrue("列表页写表前要先撤旧登记（cancelSlot），否则删了任务它照样到点响", page.contains("cancelSlot("))
        val scheduler = code("src/main/java/com/example/zhengdao/schedule/ScheduleScheduler.kt")
        assertTrue(scheduler.contains("cancelUniqueWork("))
        assertTrue("闹钟那条路也要 cancel，否则只撤了作业", Regex("AlarmManager[\\s\\S]{0,160}\\.cancel\\(").containsMatchIn(scheduler))
    }

    // ── ⑦ 不常驻服务、不起裸线程（红线 32 / BareThreadGuard 的同一条账） ──

    @Test
    fun `实现方式_到点用WorkManager不自建线程`() {
        val pkg = packageCode()
        for (bad in listOf("Thread(", "Executors.", "java.util.Timer", "startForegroundService")) {
            assertFalse("定时任务里出现了「$bad」：执行交给 WorkManager，不常驻服务、不自建线程", pkg.contains(bad))
        }
        // 不自定义 Service 子类（getSystemService 那种取系统服务的调用不在禁列，所以按声明判）。
        assertFalse(
            "本批不注册自己的 Service（派工单：不常驻服务）",
            Regex("class\\s+\\w+\\s*:\\s*(Service|IntentService|LifecycleService)\\b").containsMatchIn(pkg),
        )
        assertFalse(
            "清单里 schedule 包不挂 Service 组件",
            Regex(":\\.schedule\\.[A-Za-z]*Service").containsMatchIn(code("src/main/AndroidManifest.xml")),
        )
    }

    // ── ⑧ manifest 只加必要权限与静态组件 ────────────────────────────────

    @Test
    fun `manifest_只加精确闹钟一项不占USE_EXACT_ALARM`() {
        val m = code("src/main/AndroidManifest.xml")
        assertTrue("要排精确闹钟就得声明 SCHEDULE_EXACT_ALARM", m.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertFalse(
            "USE_EXACT_ALARM 是装机即授、撤不掉的 normal 权限，Google Play 把它划给闹钟/日历类核心功能；" +
                "一条会话提醒不够格（§2 与交付报告 §0.6）",
            m.contains("android.permission.USE_EXACT_ALARM"),
        )
        assertTrue("闹钟接收器必须是清单静态组件：动态注册重启后不存在，闹钟会静默丢掉", m.contains(".schedule.ScheduleAlarmReceiver"))
        assertTrue("列表页 Activity 不导出", Regex("ScheduleTasksActivity\"[^>]*android:exported=\"false\"").containsMatchIn(m))
    }
}
