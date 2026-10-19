package com.example.zhengdao.testkit

import org.junit.Assume

/**
 * P0-3（2026-10-19）：环境不具备时的**唯一跳过出口**。
 *
 * 为什么统一：以前 17 处 `Assume.assumeTrue` 各写各的文案，同一件事（沙盒建不出软链）
 * 至少四种说法；报告里只看到 `skipped`，读不出为什么。这里把「跳过原因」收成一个对象，
 * 以后新增环境门也只走这里。
 *
 * 纪律：
 * - 原因必须是中文、非空、说清「本机缺什么」；
 * - 软链类用例一律用 [symlinkUnsupported]，文案固定，保证同一台机器连跑多轮时
 *   跳过原因逐字一致；
 * - 跳过不等于通过（JUnit 报告里仍是 skipped），不允许用 `return` 静默冒充通过（E-138）。
 */
object TestAssume {

    /** 通用环境门：条件不成立时抛 JUnit 的 AssumptionViolatedException（报告里记为 skipped）。 */
    fun assume(condition: Boolean, reason: String) {
        require(reason.isNotBlank()) { "跳过原因不能为空（P0-3）" }
        Assume.assumeTrue(reason, condition)
    }

    /**
     * 软链用例专用。
     *
     * Windows 非开发者模式 / 沙盒限制下 `Files.createSymbolicLink` 会失败（E-138）；
     * 失败时显式标「本机不支持」，不再出现「沙盒造不出真软链」「本机/本用户不允许建软链」
     * 等各写各的文案。
     */
    fun symlinkUnsupported(created: Boolean) {
        assume(
            created,
            "本机不支持创建符号链接（Windows 需管理员特权或沙箱限制，E-138）；本用例未覆盖",
        )
    }

    /** 外部命令（node 等）缺失：统一说明缺的是哪个工具。 */
    fun externalTool(present: Boolean, tool: String) {
        assume(
            present,
            "本机/CI 未安装 $tool，跳过依赖它的自证（形态判据仍由门禁覆盖）",
        )
    }
}
