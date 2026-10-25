// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二百一十批 · 申请入口唯一化（源码门禁）。
 *
 * 用户 2026-10-25 口径升级的原话级要求：「定时任务的权限等等各种权限申请应该放到证道 APP 的
 * 设置里弄一个申请权限的卡」＋「其它页的申请/去开启全部删掉，原处只留一行『去设置→申请权限』」。
 * 落地约束（派工单 §2）：
 * - **权限状态可以到处显示**（检查/读现状是被允许的，负例 7 钉的就是不许删状态）；
 * - **发起申请只此一处**：四类申请 API 只允许出现在「申请权限」卡的实现文件里；
 * - **不许改判定**（本门禁只扫入口 token，不碰任何 checkSelfPermission 之类的状态读取）。
 *
 * 四类申请 API（token 级）：
 * 1. `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` —— 系统「所有文件访问」页（带回落通用页）；
 * 2. 通知的运行时请求 —— `NOTIFICATIONS_PERMISSION` 字面量的**发起侧**引用（声明处除外）；
 * 3. `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` —— 系统「精确闹钟」专属页跳转；
 * 4. 电池优化两件套 —— `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（列表页，134 批口径）
 *    与 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（一键框，Play 受限用法，**全仓零容忍**）。
 * 另加 `requestPermissions(` —— 命令式运行时请求，同样只许在卡内（当前卡用的是 launcher 契约，
 * 所以这一项目前应为 0 处；留白名单形状是为了将来卡内真要用时不必改门禁）。
 *
 * 判据一律读**剥掉注释**的源码：解释「这里原来有什么」的注释里必然出现这些反例词
 * （ColdStartDialogGuardTest 类 KDoc 记着这个坑：B-补-22 首跑就被自己的注释带偏过）。
 *
 * 配套负例（自检变异）：[自检_往别处塞一个申请API立刻红]——把同样一套扫描器喂一段假源码，
 * 必须抓得到，防止「全仓干净所以恒绿」的空转（与 BaopuCostWiringTest 的判别器同款思路）。
 */
class PermissionEntryGateTest {

    private val cardRel = "src/main/java/com/example/zhengdao/ui/SettingsPermissionCard.kt"

    /** 通知权限字面量的声明处（判定与申请共用这一份；ScheduleRedLineGateTest 钉着不许抄第二份）。 */
    private val setupRel = "src/main/java/com/example/zhengdao/ui/PermissionSetup.kt"

    /** 单测的工作目录可能是模块目录 `app/`，也可能是仓库根；两种都试。 */
    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()

    /** 剥掉行注释与块注释（KDoc 也算）——判据只读真代码。 */
    private fun strip(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(src.length)
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** 主源码树里全部 .kt（剥注释后按文件返回；键＝仓库相对路径，形如 `src/main/java/…`）。 */
    private fun mainSources(): Map<String, String> {
        // 单测的工作目录可能是模块目录 `app/`，也可能是仓库根——两种都试（只认 isDirectory，
        // 别用 isFile：那是给文件用的，会把目录判成"不存在"而静默扫出 0 个文件）。
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到主源码树 src/main/java（试过 app/ 与仓库根两种 CWD）")
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("主源码树一个 .kt 都没读到（路径解析坏了，这套账会空转）：${root.absolutePath}", files.isNotEmpty())
        return files.associate { f ->
            "src/main/java/" + f.relativeTo(root).path.replace('\\', '/') to strip(f.readText())
        }
    }

    /**
     * 扫描器：返回「不该出现却出现了」的 (文件, token) 清单。
     *
     * @param sources 文件相对路径 → 剥注释源码（测试可喂假源码做变异自检）。
     */
    private fun offenders(sources: Map<String, String>): List<Pair<String, String>> {
        val cardOnly = listOf(
            "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
            "ACTION_REQUEST_SCHEDULE_EXACT_ALARM",
            "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            "requestPermissions(",
        )
        val out = mutableListOf<Pair<String, String>>()
        sources.forEach { (path, src) ->
            if (path == cardRel) return@forEach
            cardOnly.forEach { tok ->
                if (src.contains(tok)) out += path to tok
            }
            // 通知字面量的发起侧引用：声明处（PermissionSetup.kt）除外
            if (path != setupRel && src.contains("NOTIFICATIONS_PERMISSION")) {
                out += path to "NOTIFICATIONS_PERMISSION"
            }
            // 一键框：全仓零容忍（卡内也不许有）
            if (src.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
                out += path to "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
            }
        }
        return out
    }

    @Test
    fun `门禁_四类申请API只出现在申请权限卡实现文件`() {
        val bad = offenders(mainSources())
        assertTrue(
            "别处出现了权限申请 API（申请入口唯一化：只许「申请权限」卡一处发起）：$bad",
            bad.isEmpty(),
        )
    }

    @Test
    fun `门禁_卡文件确实有四类申请API_不是空转`() {
        // 正向：卡里四类都在（含电池列表页），否则上面那条「别处没有」可能是整批都没做。
        val card = strip(code(cardRel))
        for (tok in listOf(
            "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
            "ACTION_REQUEST_SCHEDULE_EXACT_ALARM",
            "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            "NOTIFICATIONS_PERMISSION",
        )) {
            assertTrue("「申请权限」卡里找不到 $tok —— 唯一入口自己没实现？", card.contains(tok))
        }
        // 一键框在卡内也不许有（134 批 lint(BatteryLife) 口径：用列表页，不诱导）。
        assertFalse(
            "卡里也不许用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 一键框（Play 受限用法）",
            card.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        )
        // 四行状态判定必须委托既有单一来源，不许在卡里另写一套（派工单红线：不许改判定）。
        assertTrue("所有文件访问状态必须委托 PermissionSetup.manageAllFilesGranted", card.contains("PermissionSetup.manageAllFilesGranted()"))
        assertTrue("通知状态必须委托 PermissionSetup.notificationsGranted", card.contains("PermissionSetup.notificationsGranted("))
        assertTrue("精确闹钟状态必须委托 SchedulePermissions.canScheduleExactAlarms", card.contains("SchedulePermissions.canScheduleExactAlarms("))
        assertTrue("电池豁免状态必须委托 SchedulePermissions.isIgnoringBatteryOptimizations", card.contains("SchedulePermissions.isIgnoringBatteryOptimizations("))
        // 负例 2（状态与系统不一致/缓存旧值）：从系统页回来必须重读——ON_RESUME 观察者
        // 与通知请求回执各刷一次，源码形态可钉（真机行为归蓝总管采）。
        assertTrue("卡必须有 ON_RESUME 重读（拒绝后回来要显示未给）", card.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue("通知请求的回执里必须 refresh()（不信 result，重读系统状态）", card.contains(") { refresh() }"))
    }

    @Test
    fun `自检_往别处塞一个申请API立刻红`() {
        // 变异自检（仓内同款纪律：判据必须带负例，否则「全仓干净」恒真）。
        // 每种 token 各塞一个假文件，扫描器必须全部抓到。
        val fake = mapOf(
            "ui/FakeAllFiles.kt" to "ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION))",
            "ui/FakeNotify.kt" to "launcher.launch(PermissionSetup.NOTIFICATIONS_PERMISSION)",
            "ui/FakeExact.kt" to "ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))",
            "ui/FakeBattery.kt" to "ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))",
            "ui/FakeBatteryOneTap.kt" to "ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))",
            "ui/FakeImperative.kt" to "ActivityCompat.requestPermissions(this, arrayOf(p), 101)",
        )
        val bad = offenders(fake)
        assertEquals("每种塞入的申请 API 都必须被抓到（实得 $bad）", fake.size, bad.size)
        // 塞进卡文件自己 ⇒ 不算别处（白名单形状验证：卡内允许）。
        assertTrue(offenders(mapOf(cardRel to "Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)")).isEmpty())
    }
}
