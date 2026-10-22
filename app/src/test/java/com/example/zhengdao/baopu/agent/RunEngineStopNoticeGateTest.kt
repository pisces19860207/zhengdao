// 151 批：150 批 F/I 的用户可见缺口补丁门禁（源码级）。
//
// 为什么是源码门禁：RunEngine 依赖 Android（Looper/SharedPreferences），本仓 JVM 单测
// 起不来整个引擎；150/151 这几条修的是「临界区结构 + 文案 + 删除范围」，没有纯函数可抽。
// 与 150 的 RunEngineConcurrencyGateTest 同口径：钉住形态，防回退。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEngineStopNoticeGateTest {

    private fun src(): String {
        val f = File("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        return if (f.isFile) f.readText()
        else File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt").readText()
    }

    @Test
    fun `F_停止文案分开说排队与已开始处理的那一条`() {
        val s = src()
        assertTrue("缺「另有 1 条已开始处理」的提示", s.contains("另有 1 条已开始处理的消息会保留，可续跑"))
        assertTrue("缺「正在处理的那条会停在当前步」的提示", s.contains("正在处理的那条会停在当前步，消息保留可续跑"))
        // 旧文案仍可保留（无在飞时只报排队）；但不能只有旧文案。
        assertTrue("旧文案应仍在（无在飞时用）", s.contains("已停止，排队中的 \$n 条也清掉了"))
    }

    @Test
    fun `F_在飞计数在锁内取条时加_收尾时减`() {
        val s = src()
        assertTrue(
            "缺 polledInFlight 字段",
            s.contains("private val polledInFlight = java.util.concurrent.atomic.AtomicInteger(0)"),
        )
        assertTrue("取条处没计数", s.contains("polledInFlight.incrementAndGet()"))
        assertTrue("收尾处没减回", s.contains("polledInFlight.decrementAndGet()"))
        // 读计数必须在 queueGate 临界区内（停止侧）
        val stopIdx = s.indexOf("fun stop() {")
        val body = s.substring(stopIdx, s.indexOf("fun stopRequestedNow()", stopIdx))
        assertTrue("stop() 里读 polledInFlight 但不在 queueGate 内", body.contains("synchronized(queueGate)"))
        assertTrue("stop() 里没读 polledInFlight", body.contains("val running = polledInFlight"))
    }

    @Test
    fun `I_兜底删只删最新一条_不按文案全删`() {
        val s = src()
        assertTrue("缺 newestMatch", s.contains("var newestMatch = 0L"))
        assertTrue("应取 max(id)", s.contains("if (id > newestMatch) newestMatch = id"))
        assertTrue("应只删一条", s.contains("if (newestMatch > 0)"))
        assertFalse("旧的 ids.forEach 全删必须消失", s.contains("ids.forEach { id ->"))
    }
}
