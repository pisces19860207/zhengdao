// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 B 段 #10 的第③处：完成通知**发失败不许把合并窗口记成「已发」**。
//
// 改前表现（`全仓隐患审计-2026-10-28.md` A-P2-21）：`BaopuCompletionNotifier.post` 在
// `mgr.notify(...)` **之前**就写了 `lastPostedAtMs = now` / `lastSessionId = sessionId`。
// 系统拒发（渠道被关、NotificationManager 拿不到、notify 抛异常）时这一刻照记不误 ⇒
// 接下来 [BaopuCompletionRules.MERGE_WINDOW_MS] 内同会话的完成通知全被自己吃掉；
// 用户看到的是「抱朴从来不告诉我跑完了」，而日志里只有一行发送失败。
//
// 改后：只有**真发出去**才占窗口；`onFailure` 那行日志一字未改（降级仍要说得出）。
// 通知本体要 Context，JVM 造不出来 ⇒ 判据分两半：窗口语义用纯函数 `shouldPost` 跑，
// 「状态推进发生在 notify 之后」用源码顺序门禁钉住。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompletionNoticeWindowTest {

    private fun src(rel: String): String {
        val f = listOf(rel, "../$rel").map { File(it) }.firstOrNull { it.isFile }
        assertTrue("找不到源码 $rel（门禁不许空转，cwd=${File("").absolutePath}）", f != null)
        return f!!.readText()
    }

    private val notifier: String get() = src("src/main/java/com/example/zhengdao/baopu/BaopuCompletionNotifier.kt")

    // ── 窗口语义（纯判据，改前改后都成立——这条钉的是「占窗口的后果」） ──────

    @Test
    fun `占了窗口同会话就不再发`() {
        val first = BaopuCompletionRules.shouldPost(
            foreground = false, permissionGranted = true, nowMs = 1_000L,
            lastPostedAtMs = 0L, lastSessionId = 0L, sessionId = 7L,
        )
        assertTrue("第一条该发", first)
        val secondIfStateWasAdvanced = BaopuCompletionRules.shouldPost(
            foreground = false, permissionGranted = true, nowMs = 1_500L,
            lastPostedAtMs = 1_000L, lastSessionId = 7L, sessionId = 7L,
        )
        assertFalse("一旦把窗口记成已发，5 秒内同会话第二条必被吞（这就是改后的代价）", secondIfStateWasAdvanced)
    }

    @Test
    fun `没占窗口下一轮还会再试`() {
        // 改前：发失败也占窗 ⇒ 上面那条断言的处境；改后：失败不占窗 ⇒ 这一条成立。
        val retry = BaopuCompletionRules.shouldPost(
            foreground = false, permissionGranted = true, nowMs = 1_500L,
            lastPostedAtMs = 0L, lastSessionId = 0L, sessionId = 7L,
        )
        assertTrue("发送失败后状态保持原样 ⇒ 下一轮仍然会发", retry)
    }

    // ── 顺序门禁：状态推进必须在 notify 之后 ─────────────────────────────

    @Test
    fun `状态推进排在真发出去之后`() {
        val notify = notifier.indexOf("mgr.notify(NOTIF_ID, n)")
        val stamp = notifier.indexOf("lastPostedAtMs = now")
        val sid = notifier.indexOf("lastSessionId = sessionId")
        assertTrue("notify 调用还在（没顺手删功能）", notify >= 0)
        assertTrue("时刻推进还在（没顺手删功能）", stamp >= 0)
        assertTrue("会话推进还在（没顺手删功能）", sid >= 0)
        assertTrue("改前写法是「先发前推」——时刻推进必须排在 notify 之后", stamp > notify)
        assertTrue("会话推进同样排在 notify 之后", sid > notify)
    }

    @Test
    fun `拿不到服务算发失败不是静默跳过`() {
        assertTrue("NotificationManager 拿不到要走失败分支",
            notifier.contains("?: throw IllegalStateException(\"NotificationManager 拿不到\")"))
        val at = notifier.indexOf("NotificationManager 拿不到")
        assertTrue("整个 throw 落在同一个 runCatching 里（穿透不出去）",
            notifier.indexOf("runCatching {") < at && at < notifier.indexOf(".onFailure {"))
        assertTrue("失败仍只 log 不崩（57 批同口径）",
            notifier.contains("RunLog.log(\"抱朴完成通知：发送失败"))
    }

    // ── 负例 ────────────────────────────────────────────────────────────

    @Test
    fun `负例_不许把静默改成崩溃`() {
        // 本批只允许这一处 throw，且它在 runCatching 内、外面还有 onFailure 兜着。
        val throws = Regex("throw ").findAll(notifier).toList().size
        assertTrue("throw 只许是「把拿不到服务归到失败分支」这一处，实测 $throws 处", throws <= 1)
        assertTrue("runCatching 包着整段发送", notifier.contains("runCatching {"))
        assertTrue("onFailure 还在", notifier.contains(".onFailure {"))
        assertFalse("不许让异常穿透（没有裸 try/finally 重写）", notifier.contains("catch ("))
    }

    @Test
    fun `负例_判据与话术一位没动`() {
        // 合并窗口、前台不弹、权限不硬弹——169 批的原判据不许被本批顺手改。
        val rules = src("src/main/java/com/example/zhengdao/baopu/BaopuCompletionRules.kt")
        assertTrue("窗口还是 5 秒", rules.contains("const val MERGE_WINDOW_MS = 5_000L"))
        assertTrue("前台/权限那条判据原样", rules.contains("if (foreground || !permissionGranted) return false"))
        assertTrue("标题话术原样", notifier.contains("BaopuCompletionRules.titleOf(kind, outcome)"))
        assertTrue("正文话术原样", notifier.contains("BaopuCompletionRules.textOf(kind, outcome)"))
    }

    @Test
    fun `负例_权限那条日志没被搬走`() {
        assertTrue("「权限未授予」仍只在日志里说一声",
            notifier.contains("RunLog.log(\"抱朴完成通知：权限未授予，只更新会话不弹通知\")"))
    }
}
