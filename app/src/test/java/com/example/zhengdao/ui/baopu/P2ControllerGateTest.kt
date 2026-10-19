// 第五十六批 · 并发/落库 P2 六件（审计第三批 A 段）——UI 侧两件的门禁。
//
// P2-1（refresh 主线程读库）与 P2-2（forgetMemory 丢弃 Future）都在
// `BaopuChatController` 里；两者都只能钉源码结构（控制器要真 Context + 真库）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class P2ControllerGateTest {

    private val controllerPath = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
    private val terminalPath = "src/main/java/com/example/zhengdao/TerminalActivity.kt"

    private fun code(path: String): String {
        val raw = File(path).readText()
        return raw.lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    private fun bodyOf(path: String, signature: String): String {
        val src = code(path)
        val i = src.indexOf(signature)
        assertTrue("找不到 $signature（$path）", i >= 0)
        val rest = src.substring(i + signature.length)
        val next = rest.indexOf("\n    fun ")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    // ── P2-1：库读在 IO ────────────────────────────────────────────────

    @Test
    fun `P2_1_refresh的库读都在IO上`() {
        val body = bodyOf(controllerPath, "fun refresh(force: Boolean = false) {")
        assertTrue("refresh 必须把读包进 withContext(Dispatchers.IO)", body.contains("withContext(Dispatchers.IO)"))
        // 两个重读都必须在 IO 段内：出现在 IO 标记之后
        val ioAt = body.indexOf("withContext(Dispatchers.IO)")
        val msgsAt = body.indexOf("readMessages(")
        val rowsAt = body.indexOf("readStepRows(")
        assertTrue("refresh 里找不到 readMessages", msgsAt >= 0)
        assertTrue("refresh 里找不到 readStepRows", rowsAt >= 0)
        assertTrue("readMessages 必须在 IO 段内（不许回到主线程直读）", msgsAt > ioAt)
        assertTrue("readStepRows 必须在 IO 段内", rowsAt > ioAt)
        // 中断标记的库读也要在 IO 段内
        assertTrue("interruptedStep 必须在 IO 段内", body.indexOf("BaopuRuntime.interruptedStep(") > ioAt)
    }

    @Test
    fun `P2_1_状态写回仍留主线程`() {
        // 2026-10-17 真机两次实测：后台线程直写 Compose 状态会时灵时不灵 ⇒ 只搬读、不搬写
        val body = bodyOf(controllerPath, "fun refresh(force: Boolean = false) {")
        assertTrue("刷新要走控制器自己的 scope（Main 派发）", body.contains("scope.launch"))
        assertFalse(
            "不许把状态写回丢进 IO（那会 reintroduce 「时灵时不灵」）",
            body.contains("withContext(Dispatchers.IO) {\n            messages ="),
        )
    }

    // ── P2-2：forgetMemory 等落库 ──────────────────────────────────────

    @Test
    fun `P2_2_forgetMemory必须等写库完成`() {
        val body = bodyOf(controllerPath, "fun forgetMemory(id: String) {")
        assertTrue("必须 .get() 等事务提交（旧实现丢弃 Future ⇒ 失败被吞）", body.contains(".get()"))
        assertTrue("失败要有用户可见的一句话（不许静默）", body.contains("pendingNote"))
        assertTrue("成功后才刷新记忆列表", body.indexOf("refreshMemory()") > body.indexOf(".get()"))
    }

    // ── P2-6：onDestroy 清 onSessionDied ───────────────────────────────

    @Test
    fun `P2_6_onDestroy必须清onSessionDied`() {
        val body = bodyOf(terminalPath, "override fun onDestroy() {")
        assertTrue(
            "onDestroy 必须清 SessionManager.onSessionDied（否则单例钉住已销毁的 Activity）",
            body.contains("SessionManager.onSessionDied = null"),
        )
        // 原有三条也不许被顺手删掉
        for (cb in listOf("onViewUpdate", "onCopyText", "onPasteRequest")) {
            assertTrue("$cb 的清理不许被删", body.contains("SessionManager.$cb = null"))
        }
    }
}
