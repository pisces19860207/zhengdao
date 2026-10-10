// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.util.Log
import com.example.zhengdao.core.IssueCenter
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

    /** resolv.conf 的规范内容（与 `rootfs/build-rootfs.sh` 2.7 **保持同一份**，避免镜像与运行时打架）。 */
    private const val RESOLV_CONF =
        "options timeout:1 attempts:3 rotate\n" +
            "nameserver 223.5.5.5\n" +      // 阿里 DNS（国内）
            "nameserver 119.29.29.29\n" +   // 腾讯 DNSPod（国内）
            "nameserver 1.1.1.1\n" +        // Cloudflare（国际/走代理）
            "nameserver 8.8.8.8\n"          // Google（国际/走代理）

    private const val HOSTS_BASE = "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"

    /**
     * /etc/hosts 钉住项（v1.2 网络优化，2026-10-07 修订）。
     *
     * 四条 = **遥测屏蔽（P1）**：网络不稳时遥测组件无退避重试，会拖出大量失败 DNS 查询
     * （表现为发热/耗电）。真机实测（2026-10-07）：`statsig.anthropic.com`、
     * `telemetry.opencode.ai`、`telemetry.anthropic.com` **本来就解析不出来**，
     * 只有 `statsig.com` 能解析（34.128.128.0）——所以钉住对前三条是"省掉一次注定失败的
     * 查询"，对 `statsig.com` 才是真屏蔽。收益有限但零成本，且**不依赖外部 IP**，无轮换风险。
     */
    private val HOSTS_PINS = listOf(
        "0.0.0.0 statsig.anthropic.com",
        "0.0.0.0 statsig.com",
        "0.0.0.0 telemetry.opencode.ai",
        "0.0.0.0 telemetry.anthropic.com",
    )

    /**
     * **已撤除的钉住项**（2026-10-07 用户拍板）：见到就从 hosts 里删掉。
     *
     * 原条目：`172.65.90.21 opencode.ai`（P0，规避 IPv6 黑洞下的长超时）。撤除理由：
     * 1. **收益 ≈ 0**——太极 serve 跑在**宿主 bionic**，不读 rootfs 的 `/etc/hosts`，
     *    对主产品完全无效；终端里的 opencode 已于 v1.2 阶段 1 卸载；guest 内即便还有进程，
     *    也只省**首次请求 8–25 ms**（连接复用后 DNS 成本归零）。端到端由 TLS + 跨境
     *    RTT（~1.7 s）主导，DNS 只占 ~1%。
     * 2. **风险是硬故障而非性能波动**——opencode.ai 由 Cloudflare 前置，实测解析出 4 个
     *    轮换地址（172.65.90.20–.23）。IP 一旦轮换就是**直接连不上**，且不可控、不可自愈。
     *
     * 用零收益换硬故障风险不划算。保留的三项（遥测屏蔽 / NO_PROXY / Bun IPv6 flag）
     * 均不依赖外部 IP，无此风险。详见故障排查手册 **坑 #0** 与 **坑 #4**。
     */
    private val HOSTS_UNPINS = listOf("opencode.ai")

    /**
     * 纯函数（可单测，不碰 Android API）：从 hosts 文本里摘除作废钉住项。
     *
     * **只按精确域名匹配**（取每行第二个字段），因此 `telemetry.opencode.ai`
     * 不会被 `opencode.ai` 误伤。返回 `(新文本, 删除行数)`，已是干净文本时删除行数为 0。
     */
    internal fun stripObsoletePins(text: String): Pair<String, Int> {
        val lines = text.lines()
        val kept = lines.filter { line ->
            val f = line.trim().split(Regex("\\s+"))
            f.size < 2 || f[1] !in HOSTS_UNPINS
        }
        return kept.joinToString("\n") to (lines.size - kept.size)
    }

    /** DNS 兜底（设计文档 §4）：proot 内没有 systemd-resolved，缺 resolv.conf 就是
     * "下载得动、上不了网"的第一大故障。多路 DNS：国内源在前（快且稳），国际源兜底
     * （走 VPN 时由其接管）。options 行（2026-10-06，用户路上移动网络 DNS 超时反馈）：
     * 单查询 1 秒超时、重试 3 次、多服务器轮换——移动网络丢包时快速换源，
     * 替代默认的 5 秒死等。旧版只有国际源或无重试参数时整体重写。 */
    fun ensureDnsFiles(resolv: File, hosts: File): Boolean = try {
        // #4：进得来就先把上次那条记录撤下 —— 自愈是幂等的，本次没炸 = 故障不在了
        IssueCenter.resolve("selfheal-dns")
        var changed = false
        // 旧版 resolv 只有国际源或无 options：升级后补齐国内源 + 重试参数
        val stale = resolv.isFile && (!resolv.readText().contains("223.5.5.5") ||
            !resolv.readText().contains("options timeout"))
        if (!resolv.isFile || resolv.length() == 0L || stale) {
            resolv.parentFile?.mkdirs()
            // ⚠️ v1.2 网络优化把 resolv.conf 锁成了 0444（防被覆盖）。但这个文件在
            // **App 私有目录**里，属主就是我们自己——直接写会被自己设的权限挡掉，
            // 从此再也自愈不了（"锁定"把自己锁死）。所以：先临时放开 → 写 → 再锁回。
            val locked = resolv.isFile && !resolv.canWrite()
            if (locked) resolv.setWritable(true)
            // 加固-2（E-085）：写失败也要把锁挂回去。原先三行是直排的：writeText 一抛异常
            // 就直接跳到下面的 catch，resolv.conf 永远停在可写 —— 用户看到的是"锁明明开着，
            // 文件还是被 guest 里的进程改掉了"，而自愈日志里只有一行看不懂的写失败。
            try {
                resolv.writeText(RESOLV_CONF)
            } finally {
                if (locked) resolv.setWritable(false)
            }
            changed = true
            RunLog.log("DNS 配置已重写（resolv.conf 国内源 + 重试参数）")
        }
        // 锁定 0444（v1.2 网络优化"防被覆盖"）：**每次启动都确保最终态是锁的**，
        // 这样老镜像（构建时没锁）升级 App 后也会补上。
        // 说明：文件在 App 私有目录、属主就是我们自己，这个锁只防 guest 内进程误改；
        // 自愈路径已在上面走"临时放开 → 写 → 锁回"，不会被自己锁死。
        if (resolv.isFile && resolv.canWrite()) {
            resolv.setWritable(false)
            changed = true
        }
        ensureHosts(hosts) || changed
    } catch (t: Throwable) {
        // 写不进去不阻断会话；网络类故障由故障排查手册的引导项兜底
        Log.w(TAG, "DNS 修复失败: ${t.message}")
        // #4：自愈失败以前只落一行 Logcat/RunLog —— 用户侧表现为"奇怪，明明修过还是上不了网"。
        IssueCenter.report(
            id = "selfheal-dns",
            title = "DNS 配置没能写入",
            detail = "${t.javaClass.simpleName}: ${t.message}；guest 内可能出现下载得动、上不了网。" +
                "去设置页「修复环境」重解压系统层（约 30 秒）可恢复。",
            actionLabel = "去修复",
            actionId = IssueCenter.ACTION_REPAIR_ENV,
        )
        false
    }

    /**
     * /etc/hosts：缺文件则重建；钉住项按**域名**判存在，缺哪条补哪条（幂等，不动用户写的）。
     */
    private fun ensureHosts(hosts: File): Boolean {
        return try {
            IssueCenter.resolve("selfheal-hosts")
            if (!hosts.isFile || hosts.length() == 0L) {
                hosts.parentFile?.mkdirs()
                // 重建时**不写**已撤除的 opencode.ai 钉 IP
                hosts.writeText(HOSTS_BASE + HOSTS_PINS.joinToString("\n", postfix = "\n"))
                RunLog.log("/etc/hosts 已重建（遥测屏蔽；opencode.ai 钉 IP 已撤除）")
                return true
            }
            var cur = hosts.readText()
            var changed = false
            // 撤除历史遗留的 opencode.ai 钉 IP（精确域名匹配，不动 telemetry.opencode.ai）
            val (stripped, removed) = stripObsoletePins(cur)
            if (removed > 0) {
                cur = stripped
                changed = true
                RunLog.log("/etc/hosts 已撤除 $removed 条作废钉住项（opencode.ai 钉 IP：收益≈0、IP 轮换即硬故障）")
            }
            val present = cur.split(Regex("\\s+")).toSet()
            val missing = HOSTS_PINS.filter { pin -> pin.substringAfter(' ') !in present }
            if (missing.isEmpty()) {
                if (changed) hosts.writeText(cur + (if (cur.endsWith("\n")) "" else "\n"))
                return changed
            }
            hosts.writeText(
                cur + (if (cur.endsWith("\n")) "" else "\n") +
                    missing.joinToString("\n", postfix = "\n")
            )
            RunLog.log("/etc/hosts 已补齐 ${missing.size} 条钉住项")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "hosts 修复失败: ${t.message}")
            IssueCenter.report(
                id = "selfheal-hosts",
                title = "/etc/hosts 没能写入",
                detail = "${t.javaClass.simpleName}: ${t.message}；遥测屏蔽与钉住项可能失效（不影响基本联网）。",
                actionLabel = "去修复",
                actionId = IssueCenter.ACTION_REPAIR_ENV,
            )
            false
        }
    }

    /** 时区同步（用户反馈：tmux 状态栏时钟比手机慢 8 小时）：rootfs 镜像构建时
     * /etc/localtime 指向 Etc/UTC，guest 内 date/tmux 全按 UTC 显示。改指
     * Asia/Shanghai 并补 /etc/timezone；已是目标值时跳过（幂等）。 */
    fun ensureTimezone(rootfsDir: File): Boolean = try {
        IssueCenter.resolve("selfheal-timezone")
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
        IssueCenter.report(
            id = "selfheal-timezone",
            title = "时区没能校准",
            detail = "${t.javaClass.simpleName}: ${t.message}；guest 里 date/tmux 会按 UTC 显示" +
                "（慢 8 小时），会话内由 TZ 环境变量兜底。",
            actionLabel = "去修复",
            actionId = IssueCenter.ACTION_REPAIR_ENV,
        )
        false
    }

    /** uv 系统级配置（belt；主防护是内嵌 uv 包装器）：hermes 的 install.sh 全局
     * UV_NO_CONFIG=1 且 pm 剥 UV_* 环境变量、重定向 XDG_CONFIG_HOME——用户级
     * uv.toml 全失效。/etc/uv/uv.toml 是 uv 官方配置发现层级里的系统级路径
     * （未实测·推断，验证法：guest 内 uv --help 查 "System configuration"）。 */
    fun ensureUvConfig(rootfsDir: File): Boolean = try {
        IssueCenter.resolve("selfheal-uv")
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
        IssueCenter.report(
            id = "selfheal-uv",
            title = "uv 系统级配置没能写入",
            detail = "${t.javaClass.simpleName}: ${t.message}；hermes 装依赖时可能撞上" +
                "硬链接不可用（proot 下 SELinux/bind 边界）。",
            actionLabel = "去修复",
            actionId = IssueCenter.ACTION_REPAIR_ENV,
        )
        false
    }

    /** hermes uv 包装器接管（坑 #1，update 韧性）：hermes update 若拉到新的 pinned
     * uv 版本会新开 tools/uv-<ver> 目录、放下真实二进制——裸奔一次就硬链接失败。
     * 统一巡检：ELF 真身挪为 uv.real、原路径放包装器（幂等）。返回是否有修补动作。
     *
     * @param ensurePinnedDir 额外保证 pinned 目录（uv-0.12.3-linux-arm64）里已有可执行的
     *   包装器。安装路径需要它：hermes 的 install.sh 里 `ensure_uv` **只要看路径上已有
     *   可执行文件就跳过下载**，预置包装器即接管；而启动巡检只处理"已经存在的 uv-* 目录"，
     *   不会替安装器把目录先建出来。
     *
     *   ⚠️ 为什么必须在**宿主侧**写而不是往终端里 `echo <base64> | base64 -d`
     *   （2026-10-07 改）：那段 base64 有 1.5KB，bash 会把整行回显出来 —— 真机实测
     *   点击「安装」后**整整一屏全是 base64 乱码**，用户看不到任何安装进度（这正是他报的
     *   "hermes 安装有问题"）。宿主侧写文件走的是同一个 bind（filesDir/home ⇄ /root），
     *   结果完全一样，但终端里只留下可读的输出。
     */
    fun ensureHermesUvWrappers(homeDir: File, ensurePinnedDir: Boolean = false): Boolean = try {
        IssueCenter.resolve("selfheal-uv-wrapper")
        val tools = File(homeDir, ".hermes/tools")
        val dirs = (tools.listFiles { f -> f.isDirectory && f.name.startsWith("uv-") }
            ?: emptyArray()).toMutableList()
        if (ensurePinnedDir) {
            val pinned = File(tools, PINNED_HERMES_UV_DIR)
            if (pinned.mkdirs()) RunLog.log("hermes 预置 uv 目录：${pinned.name}")
            if (dirs.none { it.name == pinned.name }) dirs.add(pinned)
        }
        var changed = false
        for (d in dirs) {
            if (!d.isDirectory) continue
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
        IssueCenter.report(
            id = "selfheal-uv-wrapper",
            title = "hermes 内嵌 uv 没能包装",
            detail = "${t.javaClass.simpleName}: ${t.message}；内嵌 uv 裸奔时硬链接必然失败" +
                "（hermes 装/更新依赖会报错）。进一次终端会再巡检一次，也可去设置页处理。",
            actionLabel = "去设置",
            actionId = IssueCenter.ACTION_OPEN_SETTINGS,
        )
        false
    }

    /**
     * hermes install.sh 里 `ensure_uv` 钉死的 uv 版本目录名。
     * ⚠️ 与 [ProotLauncher.HERMES_UV_WRAPPER_B64] 的 URL/哈希同批固化：hermes 将来升
     * pin 版本时这个名字会失效（新目录由 ensure_uv 自己下载），属已知降级——
     * 见 ProotLauncher 里那段较长的说明。
     */
    const val PINNED_HERMES_UV_DIR = "uv-0.12.3-linux-arm64"

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
