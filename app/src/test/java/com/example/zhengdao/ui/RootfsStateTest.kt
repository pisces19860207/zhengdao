package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「环境是否已安装」必须是**可观察**状态（v1.2 修复：装完环境回主页不刷新）。
 *
 * 背景（真机实测 2026-10-07）：环境在终端里装完，回到主页状态卡依旧显示
 * 「环境未安装」、「安装运行环境」按钮照常亮着，必须杀进程重进才变「环境就绪」。
 * 根因是各页面直接在组合里调用 `AppState.rootfsInstalled(ctx)` 这种一次性读文件的
 * 普通函数——Compose 不为它建立失效关系，装完没人通知 UI。
 *
 * 这批用例锁住的是「通知通道」本身：安装器写完标记调 [RootfsState.markInstalled]
 * 后，观测方必须立刻看到 true。缺了这条通道，UI 就又退回成一次性快照。
 *
 * 注：`refresh(ctx)` 需要真实 Context，本地 JVM 单测无 android.jar 实现，
 * 由真机走查覆盖（回到前台重读）。
 */
class RootfsStateTest {

    @Test
    fun `安装器置位后观测方立刻看到已安装`() {
        RootfsState.markRemoved()               // 复位，避免受其他用例影响
        assertFalse("初始应为未安装", RootfsState.installed.value)

        RootfsState.markInstalled()             // 模拟 RootfsInstaller 写完成标记后通知
        assertTrue("置位后必须立刻为已安装", RootfsState.installed.value)
    }

    @Test
    fun `环境被清除后置位回未安装`() {
        RootfsState.markInstalled()
        assertTrue(RootfsState.installed.value)

        RootfsState.markRemoved()
        assertFalse("清除后必须回到未安装", RootfsState.installed.value)
    }

    @Test
    fun `重复置位不改变结果（幂等）`() {
        RootfsState.markRemoved()
        repeat(3) { RootfsState.markInstalled() }
        assertTrue(RootfsState.installed.value)
    }

    // ── 边界扩展（v1.2 后补）：反向与异常路径 ──────────────────────

    @Test
    fun `重复清除同样幂等`() {
        RootfsState.markRemoved()               // 先确保未安装
        repeat(3) { RootfsState.markRemoved() }
        assertFalse("多次清除后仍应为未安装", RootfsState.installed.value)
    }

    @Test
    fun `安装清除再安装_状态往返不失真`() {
        // 模拟真实序列：装好 → 用户卸载/清环境 → 再装
        RootfsState.markInstalled()
        assertTrue(RootfsState.installed.value)

        RootfsState.markRemoved()
        assertFalse("清除后应回未安装", RootfsState.installed.value)

        RootfsState.markInstalled()
        assertTrue("再装后必须回到已安装", RootfsState.installed.value)
    }

    @Test
    fun `未安装态下重复清除_状态仍正确（幂等反向）`() {
        RootfsState.markRemoved()
        RootfsState.markRemoved()
        assertFalse(RootfsState.installed.value)
    }

    @Test
    fun `置位后立即清除_不留脏状态`() {
        // 防御性场景：安装失败回滚时 markInstalled 与 markRemoved 紧邻调用
        RootfsState.markInstalled()
        RootfsState.markRemoved()
        assertFalse("回滚后必须为未安装", RootfsState.installed.value)
    }
}
