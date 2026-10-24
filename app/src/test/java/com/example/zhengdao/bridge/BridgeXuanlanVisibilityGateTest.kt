// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百七十二批 S5 · **桥不许看玄览的可视性**（L2 的护栏）。
 *
 * 背景（L2 承诺）：App 在前台、玄览不可见（聊天/终端占屏）时，页面必须活着，
 * 并且**可截图 / 可读 DOM / 可操作**。桥这条路只要有一处去读「玄览在不在屏上」，
 * 就会出现"在终端里让 agent 读页面 ⇒ 回一句没在显示"的假失败 —— 页面明明活着。
 *
 * 所以这不是"目前恰好没读"就能算数的约定，而是一条**源码门禁**：
 * `bridge` 目录下所有 .kt 里不许出现 `browserOnScreen` / `isIdle(` / `releaseIfIdle(`。
 * 三个名字分别对应：可见性标记、可回收判据、真回收动作 —— 桥一个都不该沾。
 *
 * 真机侧的三条正向证据（status / snapshot / screenshot 在玄览隐藏时都通）归蓝总管采，
 * 本门禁只保证"代码层面没有把可见性写进判据"。
 */
class BridgeXuanlanVisibilityGateTest {

    private fun bridgeDir(): File =
        File("src/main/java/com/example/zhengdao/bridge").let {
            if (it.isDirectory) it else File("app/src/main/java/com/example/zhengdao/bridge")
        }

    @Test
    fun `S5 桥的源码里不许出现玄览可视性判据`() {
        val dir = bridgeDir()
        assertTrue("找不到 bridge 源码目录：${dir.path}", dir.isDirectory)
        val ktFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }.orEmpty()
        assertTrue("bridge 目录里应有 .kt 文件（否则这条门禁是空跑）", ktFiles.isNotEmpty())

        val forbidden = listOf("browserOnScreen", "isIdle(", "releaseIfIdle(")
        val hits = ArrayList<String>()
        for (f in ktFiles) {
            val text = f.readText()
            for (token in forbidden) {
                if (text.contains(token)) hits.add("${f.name} 里出现了 `$token`")
            }
        }
        assertTrue(
            "桥不许按「玄览在不在屏上」决定能做/不能做 —— L2 要求玄览隐藏时仍可读 DOM/截图/操作。" +
                "命中：" + hits.joinToString("；"),
            hits.isEmpty(),
        )
    }
}
