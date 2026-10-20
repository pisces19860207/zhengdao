// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第八十三批（§B 88）· Intent 分享 + 深链的**源码门禁**。
 *
 * 行为判据（URI 解析、优先级、截断、非法 scheme、单次消费）全在纯函数里，由
 * [IncomingIntentRouterTest] 用 JVM 逐条钉；本文件只钉**接线不会被悄悄改回**——
 * Activity/Compose/Manifest 在本仓没有可跑的 UI 测试基建（与既有门禁同一判断），
 * 所以照 `TerminalShellP1GateTest` / `RouteRestoreGuardTest` 的房型做「剥注释 + 切片 + 关键串」。
 *
 * 对应派工单：`派工单-2026-10-20-第八十三批-Intent分享深链.md` §2。
 * 负例（本门禁真的会红）的实测记录见交付报告 §负例：把终端预填改回补 `\n`、
 * 把输入框预填处加一行 `onSend(...)`，本文件都会失败。
 */
class IncomingIntentWiringGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 只剥 `//` 与块注释（保留字符串字面量）：注释里提到旧写法不算数。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun code(rel: String): String = stripComments(src(rel))

    private fun slice(s: String, startMarker: String, endMarker: String): String {
        val start = s.indexOf(startMarker)
        assertTrue("找不到起点 `$startMarker`（被改名/删掉了？）", start >= 0)
        val end = s.indexOf(endMarker, start + startMarker.length)
        assertTrue("找不到终点 `$endMarker`（起点之后）", end > start)
        return s.substring(start, end)
    }

    private fun mainActivity(): String = code("src/main/java/com/example/zhengdao/MainActivity.kt")
    private fun terminal(): String = code("src/main/java/com/example/zhengdao/TerminalActivity.kt")
    private fun composer(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")
    private fun baopuIntents(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuBackupScreen.kt")
    private fun router(): String = code("src/main/java/com/example/zhengdao/IncomingIntentRouter.kt")

    private fun dispatchBlock(): String =
        slice(mainActivity(), "private fun dispatchIncomingIntent(", "override fun onCreate(")

    // ── ① 同一路由口：onCreate / onNewIntent 都不许各长一套 ─────────────────

    @Test
    fun `门禁_onCreate 与 onNewIntent 走同一个路由口`() {
        val main = mainActivity()
        val onCreate = slice(main, "override fun onCreate(", "private fun warnIfUnofficialSignature")
        // 两条 onCreate 分支（!isTaskRoot 收栈 + 正常路径）都要把 intent 交给分发口。
        assertEquals(
            "onCreate 应有两处 handleBaopuShortcut(intent…)（收栈分支 + 正常路径），" +
                "否则分享/深链在冷启动的某条路上会静默丢失",
            2,
            Regex(Regex.escape("handleBaopuShortcut(intent")).findAll(onCreate).count(),
        )
        assertTrue("onCreate 的收栈分支还在吗？找不到 isTaskRoot", onCreate.contains("isTaskRoot"))

        val onNewIntent = slice(main, "override fun onNewIntent(intent: Intent)", "override fun onTrimMemory")
        assertTrue(
            "onNewIntent 必须把热启动 intent 交给同一个分发口 handleBaopuShortcut(intent)",
            onNewIntent.contains("handleBaopuShortcut(intent)"),
        )
        assertEquals(
            "onCreate 的两条分支（收栈 + 正常）都不得在配置变更/进程恢复时重放外部请求",
            2,
            Regex(Regex.escape("handleBaopuShortcut(intent, allowIncoming = savedInstanceState == null)"))
                .findAll(onCreate).count(),
        )

        val shortcut = slice(main, "private fun handleBaopuShortcut(", "private fun dispatchIncomingIntent(")
        assertTrue(
            "分发口必须调 dispatchIncomingIntent(intent)（快捷方式与系统入口共用一条路）",
            shortcut.contains("dispatchIncomingIntent(intent)"),
        )
        assertTrue(
            "Activity 侧必须调用纯函数 routeIncomingIntent(request)",
            dispatchBlock().contains("routeIncomingIntent(request)"),
        )
        assertEquals(
            "MainActivity 里 routeIncomingIntent( 只许有一处调用——出现第二处就是两套逻辑",
            1,
            Regex(Regex.escape("routeIncomingIntent(")).findAll(main).count(),
        )
    }

    @Test
    fun `门禁_同一 intent 只处理一次且先过门再路由`() {
        val dispatch = dispatchBlock()
        val shotAt = dispatch.indexOf("shouldHandle(request.token)")
        val routeAt = dispatch.indexOf("routeIncomingIntent(request)")
        assertTrue("dispatchIncomingIntent 必须过单次消费门 shouldHandle(request.token)", shotAt > 0)
        assertTrue("单次消费门必须**先于**路由判定（否则重复投递会先触发副作用）", shotAt < routeAt)
        assertTrue(
            "token 必须含对象身份（System.identityHashCode），否则两次独立分享同文会被误吞",
            dispatch.contains("System.identityHashCode(intent)"),
        )
        val router = router()
        assertTrue(
            "单次消费门本体必须存在（IntentSingleShot.shouldHandle）",
            router.contains("fun shouldHandle(token: String?): Boolean"),
        )
    }

    // ── ② 分享 / ask：只预填，绝不自动发送 ────────────────────────────────

    @Test
    fun `门禁_分享与 ask 只预填不发送`() {
        val dispatch = dispatchBlock()
        val share = slice(dispatch, "is IncomingRoute.ShareToBaopu ->", "is IncomingRoute.AskBaopu ->")
        val ask = slice(dispatch, "is IncomingRoute.AskBaopu ->", "is IncomingRoute.PrefillTerminal ->")
        listOf("ShareToBaopu" to share, "AskBaopu" to ask).forEach { (name, block) ->
            assertTrue("$name 必须调 BaopuIntents.requestPrompt(...)", block.contains("requestPrompt("))
            listOf("onSend", "sendMessage", ".send(").forEach {
                assertFalse("$name 分支不得自动发送（出现 `$it` 即回潮）", block.contains(it))
            }
        }

        val effect = slice(
            composer(),
            "val incomingPrompt by BaopuIntents.promptRequestedState",
            "fun saveAll(",
        )
        assertTrue("输入框必须消费请求", effect.contains("BaopuIntents.consumePrompt(request)"))
        assertTrue("输入框必须把外部正文合并进草稿", effect.contains("mergeIncomingDraft(text, request.text)"))
        assertFalse("预填效果里不得调用 onSend —— 发送只由用户点按钮触发", effect.contains("onSend"))
        assertFalse("预填效果里不得直接调发送函数", effect.contains(".send("))

        val requestPrompt = slice(baopuIntents(), "fun requestPrompt(", "fun peekPrompt(")
        assertTrue("requestPrompt 只挂进程级状态", requestPrompt.contains("promptRequested.value = PromptRequest("))
        assertFalse("requestPrompt 不得发送消息", requestPrompt.contains("onSend"))
    }

    @Test
    fun `门禁_切抱朴由请求状态驱动而不是只在 ON_RESUME`() {
        val main = mainActivity()
        assertTrue(
            "HomeTabs 必须直接观察 promptRequestedState（热启动 onNewIntent 不触发 ON_RESUME）",
            main.contains("LaunchedEffect(incomingPrompt)"),
        )
        val home = slice(main, "fun HomeTabs(", "val scope =")
        assertTrue(
            "HomeTabs 要在请求非空时切到抱朴 Tab",
            home.contains("if (incomingPrompt != null) tab = TabIndex.BAOPU"),
        )
        assertTrue(
            "!isTaskRoot 副本收栈的旧主页也要在 ON_RESUME 看一眼 pending",
            home.contains("BaopuIntents.peekPrompt() != null"),
        )
        // 分享/深链落点必须回首页（设置页/欢迎页之上也能到抱朴），但权限页是门槛。
        val app = slice(main, "fun ZhengdaoApp(", "NavHost(")
        assertTrue("ZhengdaoApp 必须观察同一个请求状态", app.contains("promptRequestedState"))
        assertTrue("请求到达时要回 home", app.contains("nav.navigate(\"home\") { launchSingleTop = true }"))
        assertTrue("guidePermissions 时不许抢权限页的导航", app.contains("if (guidePermissions) return@LaunchedEffect"))
    }

    // ── ③ terminal 深链：只预填、不带换行、不执行 ─────────────────────────

    @Test
    fun `门禁_terminal 深链只预填不执行`() {
        val dispatch = dispatchBlock()
        val terminalBranch = slice(
            dispatch,
            "is IncomingRoute.PrefillTerminal ->",
            "is IncomingRoute.Rejected ->",
        )
        assertTrue(
            "终端深链必须复用唯一入口 terminalIntent(this, null, null)（autocmd 传 null）",
            terminalBranch.contains("terminalIntent(this, null, null)"),
        )
        assertTrue(
            "只带预填 extra EXTRA_TERMINAL_PREFILL",
            terminalBranch.contains("putExtra(EXTRA_TERMINAL_PREFILL, route.command)"),
        )
        listOf("autocmd", "SessionManager.write", "startSession", "exec(", "Runtime.getRuntime").forEach {
            assertFalse("终端深链分支不得出现 `$it`（那意味着可能自动执行）", terminalBranch.contains(it))
        }

        val term = terminal()
        val injector = slice(term, "private fun injectPendingPrefill()", "private fun startNetWatch()")
        assertTrue(
            "写 pty 前必须二次校验",
            injector.contains("isSafeTerminalPrefillCommand(cmd)"),
        )
        assertTrue(
            "预填写入的是命令本身的字节（不带换行）",
            injector.contains("SessionManager.write(cmd.toByteArray(Charsets.UTF_8))"),
        )
        assertFalse(
            "预填函数里不得出现换行转义（`\\n`）——补换行就是自动执行",
            injector.contains("\\n"),
        )
        assertTrue(
            "写入成功才清 pendingPrefill（失败保留待重试）",
            injector.indexOf("SessionManager.write(") < injector.lastIndexOf("pendingPrefill = null"),
        )
        assertEquals(
            "injectPendingPrefill 必须在定义之外被两条注入路径各调一次（attach + onEmulatorSet）",
            3,
            Regex(Regex.escape("injectPendingPrefill()")).findAll(term).count(),
        )
        assertTrue(
            "readRequestFromIntent 必须读预填 extra",
            slice(term, "private fun readRequestFromIntent(", "private fun routeSessionIfNeeded(")
                .contains("pendingPrefill = intent?.getStringExtra(EXTRA_TERMINAL_PREFILL)"),
        )

        // 路由层的第一道闸：长度与换行/控制符都必须判。
        val routeTerminal = slice(router(), "private fun routeTerminal(", "internal fun isSafeTerminalPrefillCommand(")
        assertTrue("命令必须有长度上限", routeTerminal.contains("MAX_TERMINAL_COMMAND_CHARS"))
        assertTrue("命令必须挡换行/控制字符", routeTerminal.contains("containsTerminalControl(command)"))
        val second = slice(router(), "internal fun isSafeTerminalPrefillCommand(", "private fun containsTerminalControl(")
        assertTrue("二次校验必须仍查长度", second.contains("command.length <= MAX_TERMINAL_COMMAND_CHARS"))
        assertTrue("二次校验必须仍查控制字符", second.contains("!containsTerminalControl(command)"))
    }

    // ── ④ Manifest：只开声明的三扇门，且不读本地文件 ─────────────────────

    @Test
    fun `门禁_manifest 只含本单声明的外部入口`() {
        val manifest = src("src/main/AndroidManifest.xml")
        val mainBlock = Regex("""<activity\s+android:name="\.MainActivity".*?</activity>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value
            ?: throw AssertionError("AndroidManifest.xml 里找不到 .MainActivity 的 activity 块")

        assertTrue(
            "MainActivity 必须是 singleTop（热启动走 onNewIntent，与 onCreate 共用路由）",
            mainBlock.contains("android:launchMode=\"singleTop\""),
        )
        assertEquals(
            "MainActivity 应恰好四个 intent-filter（MAIN + SEND + ask + terminal）",
            4,
            Regex("<intent-filter>").findAll(mainBlock).count(),
        )
        assertEquals("分享入口恰好一个 ACTION_SEND", 1, Regex(Regex.escape("android.intent.action.SEND")).findAll(mainBlock).count())
        assertEquals("只收 text/plain", 1, Regex(Regex.escape("android:mimeType=\"text/plain\"")).findAll(mainBlock).count())
        assertEquals("深链恰好两个 ACTION_VIEW", 2, Regex(Regex.escape("android.intent.action.VIEW")).findAll(mainBlock).count())
        assertEquals("scheme 只认 zhengdao", 2, Regex(Regex.escape("android:scheme=\"zhengdao\"")).findAll(mainBlock).count())
        assertEquals("host=ask 恰好一处", 1, Regex(Regex.escape("android:host=\"ask\"")).findAll(mainBlock).count())
        assertEquals("host=terminal 恰好一处", 1, Regex(Regex.escape("android:host=\"terminal\"")).findAll(mainBlock).count())

        // 玄览的浏览器地盘：http/https 不声明；本地文件/内容一律不收。
        listOf("http", "https", "file", "content").forEach { scheme ->
            assertFalse(
                "Manifest 不得声明 scheme=$scheme（http/https 归玄览；file/content 等于接收本地文件）",
                Regex("""android:scheme\s*=\s*"$scheme"""").containsMatchIn(manifest),
            )
        }
        // 不新增权限、不新增后台服务（本单只开入口）。
        val perms = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            "权限清单变了：本单不许新增权限（新增请先改这条对账单并说明理由）",
            setOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.CAMERA",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.INTERNET",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                "android.permission.WAKE_LOCK",
                "android.permission.WRITE_EXTERNAL_STORAGE",
            ),
            perms,
        )
        assertTrue(
            "TerminalActivity 必须仍然 exported=false（深链不直接开放终端 Activity）",
            Regex("""<activity\s+android:name="\.TerminalActivity"[^>]*android:exported="false"""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(manifest),
        )

        // 读文件的三个口子都不许出现：外部文本只当数据。
        val dispatch = dispatchBlock()
        listOf("coerceToText", "contentResolver", "openInputStream", "openFileDescriptor", "File(").forEach {
            assertFalse("Intent 分发不得读本地文件（出现 `$it` 即违反 §1③）", dispatch.contains(it))
        }
        assertTrue(
            "ClipData 只取 text/htmlText（纯文本）",
            dispatch.contains("clipItem?.text?.toString() ?: clipItem?.htmlText"),
        )
    }

    @Test
    fun `门禁_拒绝原因清单与报告口径一致`() {
        val reasons = Regex("""^\s*([A-Z_]+)\(""", RegexOption.MULTILINE)
            .findAll(router())
            .map { it.groupValues[1] }
            .filter { it in setOf("UNSUPPORTED_TYPE", "UNSAFE_SCHEME", "MALFORMED_URI", "UNKNOWN_HOST", "EMPTY_TEXT", "COMMAND_TOO_LONG", "COMMAND_HAS_CONTROL") }
            .toSet()
        assertEquals(
            "拒绝清单变了：报告 §拒绝清单与本枚举必须一一对应",
            setOf(
                "UNSUPPORTED_TYPE", "UNSAFE_SCHEME", "MALFORMED_URI", "UNKNOWN_HOST",
                "EMPTY_TEXT", "COMMAND_TOO_LONG", "COMMAND_HAS_CONTROL",
            ),
            reasons,
        )
    }
}
