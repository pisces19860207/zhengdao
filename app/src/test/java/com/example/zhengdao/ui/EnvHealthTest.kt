// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境体检纯函数判定的单测（[EnvHealth.networkVerdict]、[EnvHealth.nativeDetail]、[EnvHealth.nativeCheck]）。
 *
 * 背景（2026-10-07 真机实测）：网络那一项曾是**恒 ✗**——manifest 缺 ACCESS_NETWORK_STATE，
 * `getNetworkCapabilities()` 抛 SecurityException 被 runCatching 吞掉，于是永远判失败，
 * 与网络好坏无关（用户手机上 WiFi 明明已 VALIDATED）。修法是补权限 + 不再只看系统
 * 标记（VPN／企业网下该标记常为 false，但网络可用），不确定时用真实 TCP 探测兜底。
 * 这里把四种组合的结论锁死，防止回归成"猜"。
 */
class EnvHealthTest {

    @Test
    fun `系统已验证直接通过`() {
        val (ok, detail) = EnvHealth.networkVerdict(validated = true, reachable = true, hasInternet = true)
        assertTrue(ok)
        assertEquals("宿主网络可用（系统已验证）", detail)
    }

    @Test
    fun `系统未标记但实测连通也算通过`() {
        // VPN / 企业网 / 部分运营商网络的常见形态：能用，但系统没打 VALIDATED
        val (ok, detail) = EnvHealth.networkVerdict(validated = false, reachable = true, hasInternet = true)
        assertTrue(ok)
        assertEquals("宿主网络可用（实测已连通）", detail)
    }

    @Test
    fun `连上了网但外网不通要点明代理或受限`() {
        val (ok, detail) = EnvHealth.networkVerdict(validated = false, reachable = false, hasInternet = true)
        assertFalse(ok)
        assertEquals("已连上网络但外网不通（代理或受限网络？）", detail)
    }

    @Test
    fun `完全没网才报不可用`() {
        val (ok, detail) = EnvHealth.networkVerdict(validated = false, reachable = false, hasInternet = false)
        assertFalse(ok)
        assertEquals("宿主网络不可用，Agent 安装与更新会失败", detail)
    }

    // ── native 加速层（2026-10-08 新增；用户授权「你觉得有意义就接吧」）──
    //
    // 这一格补的是**静默降级**的可见性：Rust 链路失败时 CoreNative 永久标记不可用，
    // 解压与校验退回 Java 实现，功能照常、只是更慢——在补这一格之前没人看得出。
    // 它与网络项相反：**修不了**，所以绝不能报红（否则违反「红 = 修得了」的不变量，
    // 首页会给出一个点了也没用的「去处理」）。

    @Test
    fun `native 可用时的文案说是 native`() {
        val d = EnvHealth.nativeDetail(true)
        assertTrue(d.contains("已加载"))
        assertTrue(d.contains("native"))
    }

    @Test
    fun `native 不可用时点明是 Java 回退而不是报错`() {
        val d = EnvHealth.nativeDetail(false)
        assertTrue("要让用户知道功能仍正常", d.contains("功能正常"))
        assertTrue("要说清是谁在干活", d.contains("Java 回退"))
        assertTrue("要指向已发生的两次事故", d.contains("E-012") && d.contains("E-022"))
    }

    @Test
    fun `native 项探不到时必须编成告警而不是故障`() {
        // JVM 单测里没有 .so，CoreNative 必然探到 false —— 正好用来验**编码**：
        // 修不了的降级项是 ⚠（ok=true + warn=true），不是 ✗（ok=false）。
        // 渲染侧的三态是 `!ok -> ✗`、`warn -> ⚠`，所以 ok=false 会盖掉 warn。
        val c = EnvHealth.nativeCheck()
        assertTrue("native 修不了，不能报红", c.ok)
        assertTrue("探不到 .so 时应是告警态", c.warn)
        assertNull("告警态不能给一键修复按钮", c.fixId)
        assertNull("也不该把用户丢进终端", c.terminalCmd)
        assertTrue(c.detail.contains("Java 回退"))
    }

    // ── #4（2026-10-09）：每个 ✗ 都必须给出去路 ──
    //
    // 走查里最常见的一类缺口：体检报红，界面却只有一个「去处理」，点了到设置页还要自己找
    // 是哪张卡。不变量写在这里，是为了让"新增体检项忘了给去路"在 CI 上失败，而不是
    // 等到用户在真机上撞见（同 nativeCheck 那条"修不了就不能报红"的思路）。

    @Test
    fun `一键修复 终端命令 去路 三者有一就算给出去路`() {
        assertTrue(
            EnvHealth.hasExit(
                EnvHealth.Check("dns", "DNS 配置", false, "旧版配置", fixId = EnvHealth.FIX_DNS),
            ),
        )
        assertTrue(
            EnvHealth.hasExit(
                EnvHealth.Check("hermes-deps", "Hermes 依赖环境", false, "记录坏了", terminalCmd = "bash x.sh"),
            ),
        )
        assertTrue(
            EnvHealth.hasExit(
                EnvHealth.Check("proot", "proot 就绪", false, "缺 loader", route = EnvHealth.ROUTE_REPAIR_ENV),
            ),
        )
    }

    @Test
    fun `报红却什么都不给的项会被判定成没有去路`() {
        // 刻意断言"这是没有去路"：这条就是 #4 要消灭的形态。
        assertFalse(EnvHealth.hasExit(EnvHealth.Check("x", "X", false, "坏了")))
        // 通过、以及"修不了只能告警"的项（ok=true + warn=true）都不需要去路。
        assertTrue(EnvHealth.hasExit(EnvHealth.Check("native", "native 加速层", true, "Java 回退", warn = true)))
    }

    @Test
    fun `只能去别处处理的体检项都登记了去路`() {
        // #8-D 起多了一项：存储占用（去设置页看明细 + 一键清理），所以这里从"四个"变成五个。
        assertEquals(setOf("proot", "rootfs", "network", "storage", "disk"), EnvHealth.GUIDED_ROUTES.keys)
        val allowed = setOf(
            EnvHealth.ROUTE_REPAIR_ENV,
            EnvHealth.ROUTE_STORAGE_GRANT,
            EnvHealth.ROUTE_NET_CHECK,
            EnvHealth.ROUTE_STORAGE_DETAIL,
        )
        EnvHealth.GUIDED_ROUTES.forEach { (id, route) ->
            assertTrue("$id 的去路必须是已知值（实际 $route）", route in allowed)
        }
    }

    // ── #8-D（2026-10-09）：存储占用体检 ──
    //
    // 阈值必须与自动清理同一条（CacheCleaner.AUTO_THRESHOLD_MB = 500）：面板说"没事"而启动时
    // 自动清了一大笔、或面板喊"该清了"而自动清理不动手，都是 E-073 那种两套账的翻版。

    @Test
    fun `存储占用超过阈值才报要清理`() {
        val (ok, detail) = EnvHealth.diskVerdict(480)
        assertTrue("没到 500MB 不该打扰用户", ok)
        assertTrue(detail.contains("480MB"))
        val (bad, badDetail) = EnvHealth.diskVerdict(500)
        assertFalse("到阈值就该报出来", bad)
        assertTrue("要说清能清多少", badDetail.contains("500MB"))
        assertTrue("要点明是哪几类", badDetail.contains("旧依赖代"))
    }

    // ── #1（2026-10-09）：存储项要把「授权位」和「真的读得到」分开 ──
    //
    // 第 0 步那次事故的形态是「授权位看着对、共享存储读不到」：`READ_EXTERNAL_STORAGE`
    // 带着 `maxSdkVersion=32` 帽子 ⇒ Android 13+ 上 READ 权限为空，而 MANAGE 仍可能是 true。
    // 只复述权限位的体检项永远发现不了它，所以这里锁死"必须真读一次"。

    @Test
    fun `存储授权位与真实可读都对才通过`() {
        val (ok, detail) = EnvHealth.storageVerdict(granted = true, readable = true)
        assertTrue(ok)
        assertTrue(detail.contains("可直读"))
    }

    @Test
    fun `授权位给了但读不到要点明是权限帽子或视图受限`() {
        val (ok, detail) = EnvHealth.storageVerdict(granted = true, readable = false)
        assertFalse(ok)
        assertTrue("要说清不是没授权", detail.contains("已授权"))
        assertTrue("要指向真正的原因", detail.contains("帽子") || detail.contains("视图"))
    }

    @Test
    fun `没授权时的文案照旧指向系统设置`() {
        val (ok, detail) = EnvHealth.storageVerdict(granted = false, readable = false)
        assertFalse(ok)
        assertTrue(detail.contains("系统设置"))
    }
}
