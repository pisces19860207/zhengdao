// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.util.Log
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 环境自愈例程（P7 的"修"半边）：DNS / 时区 / uv 配置 / hermes 内嵌 uv 包装。
 *
 * 单一来源：ProotLauncher 每次启动会话前调用（兜底历史坏状态），首页状态卡的
 * 「环境体检 → 修复」按钮按需调用同一批函数（P7 定向自愈）——两处共享同一实现，
 * 修复语义与启动自愈永不漂移。全部幂等：已是目标状态时不写文件、返回 false。
 * （包装器 base64 常量留在 ProotLauncher 原位——AgentInstaller 与本处共用，
 * 不搬运大体积常量，防转写事故。）
 */
object EnvSelfHeal {

    private const val TAG = "EnvSelfHeal"

    /** DNS 兜底（设计文档 §4）：proot 内没有 systemd-resolved，缺 resolv.conf 就是
     * "下载得动、上不了网"的第一大故障。多路 DNS：国内源在前（快且稳），国际源兜底
     * （走 VPN 时由其接管）。options 行（2026-10-06，用户路上移动网络 DNS 超时反馈）：
     * 单查询 1 秒超时、重试 3 次、多服务器轮换——移动网络丢包时快速换源，
     * 替代默认的 5 秒死等。旧版只有国际源或无重试参数时整体重写。 */
    fun ensureDnsFiles(resolv: File, hosts: File): Boolean = try {
        var changed = false
        // 旧版 resolv 只有国际源或无 options：升级后补齐国内源 + 重试参数
        val stale = resolv.isFile && (!resolv.readText().contains("223.5.5.5") ||
            !resolv.readText().contains("options timeout"))
        if (!resolv.isFile || resolv.length() == 0L || stale) {
            resolv.parentFile?.mkdirs()
            resolv.writeText(
                "options timeout:1 attempts:3 rotate\n" +
                    "nameserver 223.5.5.5\n" +      // 阿里 DNS（国内）
                    "nameserver 119.29.29.29\n" + // 腾讯 DNSPod（国内）
                    "nameserver 1.1.1.1\n" +      // Cloudflare（国际/走代理）
                    "nameserver 8.8.8.8\n"        // Google（国际/走代理）
            )
            changed = true
            RunLog.log("DNS 配置已重写（resolv.conf 国内源 + 重试参数）")
        }
        if (!hosts.isFile || hosts.length() == 0L) {
            hosts.parentFile?.mkdirs()
            hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
            changed = true
        }
        changed
    } catch (t: Throwable) {
        // 写不进去不阻断会话；网络类故障由故障排查手册的引导项兜底
        Log.w(TAG, "DNS 修复失败: ${t.message}")
        false
    }

    /** 时区同步（用户反馈：tmux 状态栏时钟比手机慢 8 小时）：rootfs 镜像构建时
     * /etc/localtime 指向 Etc/UTC，guest 内 date/tmux 全按 UTC 显示。改指
     * Asia/Shanghai 并补 /etc/timezone；已是目标值时跳过（幂等）。 */
    fun ensureTimezone(rootfsDir: File): Boolean = try {
        val localtime = File(rootfsDir, "etc/localtime")
        val wanted = "/usr/share/zoneinfo/Asia/Shanghai"
        var changed = false
        if (File(rootfsDir, "usr/share/zoneinfo/Asia/Shanghai").isFile) {
            val cur = runCatching { android.system.Os.readlink(localtime.absolutePath) }.getOrNull()
            if (cur != wanted) {
                localtime.delete()
                android.system.Os.symlink(wanted, localtime.absolutePath)
                File(rootfsDir, "etc/timezone").writeText("Asia/Shanghai\n")
                RunLog.log("时区已校准: Asia/Shanghai（原 ${cur ?: "非链接"}）")
                changed = true
            }
        }
        changed
    } catch (t: Throwable) {
        Log.w(TAG, "时区修复失败: ${t.message}")
        false
    }

    /** uv 系统级配置（belt；主防护是内嵌 uv 包装器）：hermes 的 install.sh 全局
     * UV_NO_CONFIG=1 且 pm 剥 UV_* 环境变量、重定向 XDG_CONFIG_HOME——用户级
     * uv.toml 全失效。/etc/uv/uv.toml 是 uv 官方配置发现层级里的系统级路径
     * （未实测·推断，验证法：guest 内 uv --help 查 "System configuration"）。 */
    fun ensureUvConfig(rootfsDir: File): Boolean = try {
        val uvCfgDir = File(rootfsDir, "etc/uv")
        var changed = false
        if (uvCfgDir.isDirectory || uvCfgDir.mkdirs()) {
            val f = File(uvCfgDir, "uv.toml")
            if (!f.isFile || !f.readText().contains("link-mode")) {
                f.writeText(
                    "# 证道预置：Android/proot 无硬链接可用（SELinux 拒绝 + bind 边界）\n" +
                        "link-mode = \"copy\"\n"
                )
                changed = true
                RunLog.log("uv 系统级配置已重写（/etc/uv/uv.toml link-mode=copy）")
            }
        }
        changed
    } catch (t: Throwable) {
        Log.w(TAG, "uv 配置修复失败: ${t.message}")
        false
    }

    /** hermes uv 包装器接管（坑 #1，update 韧性）：hermes update 若拉到新的 pinned
     * uv 版本会新开 tools/uv-<ver> 目录、放下真实二进制——裸奔一次就硬链接失败。
     * 统一巡检：ELF 真身挪为 uv.real、原路径放包装器（幂等）。返回是否有修补动作。 */
    fun ensureHermesUvWrappers(homeDir: File): Boolean = try {
        val tools = File(homeDir, ".hermes/tools")
        val dirs = tools.listFiles { f -> f.isDirectory && f.name.startsWith("uv-") }
            ?: return false
        var changed = false
        for (d in dirs) {
            val uv = File(d, "uv")
            val real = File(d, "uv.real")
            if (isElf(uv) && !real.isFile) uv.renameTo(real)
            if (!uv.isFile || isElf(uv)) {
                uv.writeBytes(
                    android.util.Base64.decode(
                        ProotLauncher.HERMES_UV_WRAPPER_B64, android.util.Base64.DEFAULT
                    )
                )
                android.system.Os.chmod(uv.absolutePath, 493)
                changed = true
                RunLog.log("hermes 内嵌 uv 已包装（${d.name}，link-mode=copy 强制）")
            }
        }
        changed
    } catch (t: Throwable) {
        Log.w(TAG, "hermes uv 包装巡检失败: ${t.message}")
        false
    }

    /** ELF 魔数判定（\x7FELF）：区分真实 uv 二进制与我们的 shell 包装器。 */
    private fun isElf(f: File): Boolean {
        if (!f.isFile) return false
        val head = ByteArray(4)
        runCatching {
            java.io.RandomAccessFile(f, "r").use { it.readFully(head) }
        }.onFailure { return false }
        return head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
    }
}
