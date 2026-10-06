// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import com.example.zhengdao.terminal.EnvSelfHeal
import java.io.File

/**
 * 环境体检（P7 的"查"半边）：状态卡展开的逐项勾叉。
 *
 * 全部为宿主侧只读检查（rootfs 目录就在 App 私有存储里，直接读文件，不进 guest、
 * 不 spawn 进程，秒级完成）。判定条件与 [EnvSelfHeal] 的修复条件一一对应——
 * 勾叉红的就是修复按钮能修绿的，不存在"报红但修不了"的悬空项；
 * RootFS/proot 损坏属重解压兜底，引导到设置页的「修复环境」。
 * 网络连通性是宿主侧探测（guest 侧故障另见故障排查手册，非一键可修）。
 */
object EnvHealth {

    /** 一项体检结果。fixId 非空 = 点「修复」可定向自愈；为空 = 引导项。 */
    data class Check(
        val id: String,
        val label: String,
        val ok: Boolean,
        val detail: String,
        val fixId: String? = null,
    )

    /** 修复动作标识（与 Check.fixId 对应）。 */
    const val FIX_DNS = "dns"
    const val FIX_TIMEZONE = "timezone"
    const val FIX_UV = "uv"

    /** 逐项体检。IO 线程调用（文件读取若干 + 一个 ConnectivityManager 查询）。 */
    fun inspect(ctx: Context): List<Check> = listOf(
        prootCheck(ctx),
        rootfsCheck(ctx),
        dnsCheck(ctx),
        timezoneCheck(ctx),
        uvCheck(ctx),
        networkCheck(ctx),
        storageCheck(ctx),
    )

    /** 定向自愈。返回是否有修补动作；rootfs/proot 类不在一键范围（重解压兜底）。 */
    fun fix(ctx: Context, fixId: String): Boolean = when (fixId) {
        FIX_DNS -> EnvSelfHeal.ensureDnsFiles(
            File(ctx.filesDir, "rootfs/etc/resolv.conf"),
            File(ctx.filesDir, "rootfs/etc/hosts"),
        )
        FIX_TIMEZONE -> EnvSelfHeal.ensureTimezone(File(ctx.filesDir, "rootfs"))
        FIX_UV -> {
            val a = EnvSelfHeal.ensureUvConfig(File(ctx.filesDir, "rootfs"))
            val b = EnvSelfHeal.ensureHermesUvWrappers(File(ctx.filesDir, "home"))
            a || b
        }
        else -> false
    }

    // ── 各项判定（条件与 EnvSelfHeal 的重写条件同源，红=可修绿）──

    private fun prootCheck(ctx: Context): Check {
        val proot = File(ctx.filesDir, "termux-proot/proot")
        val loader = File(ctx.filesDir, "termux-proot/loader")
        val ok = proot.isFile && proot.canExecute() && loader.isFile
        return Check(
            id = "proot", label = "proot 就绪", ok = ok,
            detail = when {
                ok -> "proot + loader 就位"
                else -> "proot 或 loader 缺失，重装环境可恢复"
            },
        )
    }

    private fun rootfsCheck(ctx: Context): Check {
        val rootfs = File(ctx.filesDir, "rootfs")
        val marker = File(rootfs, ".zhengdao-rootfs-ok")
        // 最小完整性集：安装完成标记 + shell 与 coreutils 入口存在。
        // 损坏判定保守——只查这三样，误报会引发不必要的重解压。
        val ok = marker.isFile && File(rootfs, "bin/sh").isFile &&
            File(rootfs, "usr/bin/env").isFile
        return Check(
            id = "rootfs", label = "RootFS 完整性", ok = ok,
            detail = when {
                ok -> "Debian 系统层完整"
                marker.isFile -> "系统层文件缺失，需「修复环境」重解压（约 30 秒）"
                else -> "环境未安装或安装未完成"
            },
        )
    }

    private fun dnsCheck(ctx: Context): Check {
        val resolv = File(ctx.filesDir, "rootfs/etc/resolv.conf")
        // 判定与 EnvSelfHeal.ensureDnsFiles 的 stale 条件同源：
        // 文件在、含国内源、含重试参数 = 新版配置
        val text = if (resolv.isFile) resolv.readText() else ""
        val ok = resolv.isFile && text.contains("223.5.5.5") && text.contains("options timeout")
        return Check(
            id = "dns", label = "DNS 配置", ok = ok,
            detail = when {
                ok -> "国内源 + 重试参数已配置"
                resolv.isFile -> "缺国内源或重试参数（旧版配置），可一键重写"
                else -> "resolv.conf 不存在，可一键写入"
            },
            fixId = if (ok) null else FIX_DNS,
        )
    }

    private fun timezoneCheck(ctx: Context): Check {
        val rootfs = File(ctx.filesDir, "rootfs")
        val wanted = "/usr/share/zoneinfo/Asia/Shanghai"
        val cur = runCatching {
            android.system.Os.readlink(File(rootfs, "etc/localtime").absolutePath)
        }.getOrNull()
        val ok = cur == wanted
        return Check(
            id = "timezone", label = "时区校准", ok = ok,
            detail = when {
                ok -> "Asia/Shanghai"
                // 会话启动时 TZ 环境变量兜底，guest 时间仍正确，但属"未校准"状态
                else -> "localtime 未指向 Asia/Shanghai（TZ 环境变量兜底中），可一键校准"
            },
            fixId = if (ok) null else FIX_TIMEZONE,
        )
    }

    private fun uvCheck(ctx: Context): Check {
        val uvToml = File(ctx.filesDir, "rootfs/etc/uv/uv.toml")
        // 坑 #1：hermes 内嵌 uv 裸奔（ELF 未包装）会让硬链接必然失败。
        // 无 tools 目录 = 未装 hermes 或无内嵌 uv，视为通过。
        val tools = File(ctx.filesDir, "home/.hermes/tools")
        val naked = tools.listFiles { f -> f.isDirectory && f.name.startsWith("uv-") }
            ?.any { isElf(File(it, "uv")) } ?: false
        val ok = uvToml.isFile && uvToml.readText().contains("link-mode") && !naked
        return Check(
            id = "uv", label = "uv 配置", ok = ok,
            detail = when {
                naked -> "hermes 内嵌 uv 未包装（硬链接会失败），可一键包装"
                !uvToml.isFile -> "/etc/uv/uv.toml 缺失，可一键写入"
                !uvToml.readText().contains("link-mode") -> "uv.toml 缺 link-mode，可一键重写"
                else -> "系统级 link-mode=copy + 内嵌 uv 已包装"
            },
            fixId = if (ok) null else FIX_UV,
        )
    }

    private fun networkCheck(ctx: Context): Check {
        val ok = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cap = cm.getNetworkCapabilities(cm.activeNetwork)
            cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(false)
        return Check(
            id = "network", label = "网络连通性", ok = ok,
            detail = if (ok) "宿主网络已通过系统验证"
            else "宿主网络未验证通过（guest 侧网络问题见「设置 → 常见问题」与故障排查手册）",
        )
    }

    private fun storageCheck(ctx: Context): Check {
        // 主路径 = MANAGE_EXTERNAL_STORAGE（E-005 修订）。基础 /sdcard 读写不依赖它，
        // 但 Agent 产出工作区的主路径依赖，故以此为准。
        val ok = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        return Check(
            id = "storage", label = "存储权限", ok = ok,
            detail = if (ok) "所有文件访问已授权（主路径）"
            else "所有文件访问未授权，工作区主路径不可用；到系统设置开启",
        )
    }

    private fun isElf(f: File): Boolean {
        if (!f.isFile) return false
        val head = ByteArray(4)
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { it.readFully(head) }
            head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        }.getOrDefault(false)
    }
}
