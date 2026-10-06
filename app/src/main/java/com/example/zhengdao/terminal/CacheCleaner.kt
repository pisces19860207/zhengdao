// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：npm/uv/apt 官方 CLI 缓存清理参数。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 缓存清理（P4，2026-10-06）：三档白名单**写死**（用户定稿：禁止从配置读）。
 *
 * | 档位 | 清理项 | 风险 |
 * |---|---|---|
 * | 一档（本类唯一暴露的清理） | npm / uv / apt 缓存、Agent 升级残留 | 极低 |
 * | 二档 | rootfs/tmp 的 V8 JIT 缓存 | 低（下次启动慢几秒）——未纳入 |
 * | 三档（永不清） | .hermes/tools / rootfs 系统层 / home 用户数据 | 删了功能坏 |
 *
 * 自动清理：会话启动后台检测 >500MB 才清；检测到安装进程跳过；静默通知。
 * 只用官方命令（npm cache clean --force / uv cache prune / apt clean），
 * 通过 guest 内执行（ProotLauncher 的终端会话），App 侧只负责探测与提示。
 */
object CacheCleaner {

    private const val AUTO_THRESHOLD_MB = 500L

    /** 各缓存目录的宿主侧大小（MB）。rootfs 内路径经 files/rootfs 直接探测。 */
    fun measure(ctx: Context): Map<String, Long> {
        val home = File(ctx.filesDir, "home")
        val rootfs = File(ctx.filesDir, "rootfs")
        return mapOf(
            "npm" to dirSize(File(home, ".npm/_cacache")),
            "uv" to dirSize(File(home, ".cache/uv")),
            "apt" to dirSize(File(rootfs, "var/cache/apt/archives")),
            "安装包缓存" to dirSize(File(ctx.cacheDir, "rootfs-cache")),
        )
    }

    fun totalMb(ctx: Context): Long = measure(ctx).values.sum()

    /** 可清理性检查：终端会话活着 = 可能正有安装任务 → 跳过自动清理。 */
    fun busy(ctx: Context): Boolean =
        com.example.zhengdao.terminal.SessionManager.isAlive()

    /**
     * 自动清理判定（会话启动时后台调用）：总量超阈值且无活动会话才清。
     * 返回 Pair(是否需要清, 总量 MB)。
     */
    fun autoCleanNeeded(ctx: Context): Pair<Boolean, Long> {
        val total = totalMb(ctx)
        return Pair(total >= AUTO_THRESHOLD_MB && !busy(ctx), total)
    }

    /**
     * 生成一档清理命令（在 guest 终端里执行的串；官方命令优先）。
     * 由 TerminalActivity 以 autocmd 注入执行——输出可见、可中断。
     */
    fun guestCommand(): String = listOf(
        "echo '[清理] npm 缓存…'", "npm cache clean --force 2>/dev/null",
        "echo '[清理] uv 缓存…'", "uv cache prune 2>/dev/null",
        "echo '[清理] apt 缓存…'", "apt-get clean 2>/dev/null; apt-get autoclean 2>/dev/null",
        "echo '[清理] Agent 升级残留…'", "rm -rf /root/.hermes/tools/*.tmp /root/.opencode-mem/models/*.tmp 2>/dev/null",
        "echo '[清理] 完成（三档白名单：tools/rootfs/home 用户数据永不清）'",
    ).joinToString("; ")

    /** 目录大小（MB）；不存在返回 0。 */
    private fun dirSize(dir: File): Long = try {
        if (dir.isDirectory) {
            var total = 0L
            dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
            total / 1048576
        } else 0
    } catch (_: Throwable) {
        0
    }

    /** 自动清理触发（SessionService 定时器调用）：超阈值 → 发通知提示（不静默执行删除，用户点通知进设置手动清——v1 稳妥版）。 */
    fun maybeNotify(ctx: Context, notify: (String, String) -> Unit) {
        val (needed, total) = autoCleanNeeded(ctx)
        if (needed) {
            notify("缓存占用 ${total}MB", "点此进入设置清理（不会删除任何用户数据）")
            RunLog.log("缓存自动检测: ${total}MB 超阈值，已通知")
        }
    }
}
