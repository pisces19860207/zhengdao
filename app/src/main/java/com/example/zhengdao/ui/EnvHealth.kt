// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import com.example.zhengdao.rust.CoreNative
import com.example.zhengdao.terminal.EnvSelfHeal
import com.example.zhengdao.terminal.HermesEnv
import com.example.zhengdao.terminal.ResMonitor
import java.io.File

/**
 * 环境体检（P7 的"查"半边）：状态卡展开的逐项勾叉。
 *
 * 全部为宿主侧只读检查（rootfs 目录就在 App 私有存储里，直接读文件，不进 guest、
 * 不 spawn 进程，秒级完成）。判定条件与 [EnvSelfHeal] 的修复条件一一对应——
 * 勾叉红的就是修复按钮能修绿的，不存在"报红但修不了"的悬空项；
 * RootFS/proot 损坏属重解压兜底，引导到设置页的「修复环境」。
 * 网络连通性是宿主侧探测（guest 侧故障另见故障排查手册，非一键可修）。
 *
 * ## 不变量（#4 走查，2026-10-09 修补）
 *
 * **每个 ✗ 项都必须有去路**，三选一：
 * - [Check.fixId] 非空 ⇒ 宿主侧一键自愈；
 * - [Check.terminalCmd] 非空 ⇒ 脚本落盘后进终端跑（过程可见）；
 * - [Check.route] 非空 ⇒ 带用户走到**正确的那一页**（修复环境 / 系统授权 / 网络自检）。
 *
 * 走查前最后四项（proot / rootfs / network / storage）只有一句笼统的「去处理」，
 * 统统丢到设置页顶部让用户自己找——等于没指路。现在各带一条 [route]，
 * 由主页渲染成对应文案并直达（`EnvHealthTest.每个红灯项都有去路` 锁死这条不变量）。
 */
object EnvHealth {

    /**
     * 一项体检结果。fixId 非空 = 点「修复」可**宿主侧**定向自愈；terminalCmd 非空 =
     * 点「修复」要进 guest 里跑（脚本落盘后打开终端，过程可见）；route 非空 =
     * 引导项，点按钮**直达**对应的处理位置（不是笼统地丢到设置页顶部）。
     *
     * [warn] = 超阈值但**没有一键修复**（当前只有资源占用一项）：渲染为黄色 ⚠，
     * 与红色 ✗（可修或引导去处理）区分开，以维持「红 = 修得了/去得对」这条不变量。
     */
    data class Check(
        val id: String,
        val label: String,
        val ok: Boolean,
        val detail: String,
        val fixId: String? = null,
        val warn: Boolean = false,
        val terminalCmd: String? = null,
        val route: String? = null,
    )

    /** 修复动作标识（与 Check.fixId 对应）。 */
    const val FIX_DNS = "dns"
    const val FIX_TIMEZONE = "timezone"
    const val FIX_UV = "uv"
    const val FIX_HERMES_DEPS = "hermes-deps"

    /** 引导去路标识（与 Check.route 对应）：不是一键自愈，而是把用户送到正确的位置。 */
    const val ROUTE_REPAIR_ENV = "repair-env"       // 设置页「修复环境」（重解压系统层）
    const val ROUTE_STORAGE_GRANT = "storage-grant" // 系统「所有文件访问」授权页
    const val ROUTE_NET_CHECK = "net-check"         // 设置页「网络自检」（分应用代理等）

    /**
     * 不变量（#4，2026-10-09）：**每个 ✗ 都必须给出去路**——一键自愈 [Check.fixId]、
     * 终端命令 [Check.terminalCmd]、或一条 [Check.route]。
     *
     * 反例就是 #4 要消灭的形态：体检说你坏了，界面上却只有一个「去处理」，点了到设置页还得
     * 自己找是哪张卡。新增体检项时，要么给它 fixId / terminalCmd，要么把它登记进
     * [GUIDED_ROUTES]——`EnvHealthTest` 会盯着这张表。
     */
    internal fun hasExit(c: Check): Boolean =
        c.ok || c.fixId != null || c.terminalCmd != null || c.route != null

    /**
     * 四个「只能去别处处理」的体检项（没有 [Check.fixId]，也开不了终端命令）各自的去路。
     * 表里的值被对应 check 直接取用，所以这张表就是这些项的真实行为，不是文档。
     */
    internal val GUIDED_ROUTES: Map<String, String> = mapOf(
        "proot" to ROUTE_REPAIR_ENV,
        "rootfs" to ROUTE_REPAIR_ENV,
        "network" to ROUTE_NET_CHECK,
        "storage" to ROUTE_STORAGE_GRANT,
    )

    /** 逐项体检。IO 线程调用（文件读取若干 + 一个 ConnectivityManager 查询 + 一次资源采样 ~0.7 s）。 */
    fun inspect(ctx: Context): List<Check> = listOf(
        prootCheck(ctx),
        rootfsCheck(ctx),
        dnsCheck(ctx),
        timezoneCheck(ctx),
        uvCheck(ctx),
        hermesDepsCheck(ctx),
        networkCheck(ctx),
        storageCheck(ctx),
        nativeCheck(),
        resourceCheck(),
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
        FIX_HERMES_DEPS -> HermesEnv.repairOnHost(HermesEnv.hermesHome(ctx))
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
                // 每次启动都会按 SHA256 从内置资源重放（[ProotLauncher]），所以这里还缺
                // 说明连重放都没成功——去路给「修复环境」，别再让用户自己找。
                else -> "proot 或 loader 缺失（启动时会自动重放，仍缺就用「修复环境」重解压）"
            },
            route = if (ok) null else GUIDED_ROUTES["proot"],
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
            route = if (ok) null else GUIDED_ROUTES["rootfs"],
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

    /**
     * Hermes 依赖环境（E-025，2026-10-08 真机事故："搬家包恢复后 hermes 必崩"）。
     *
     * 只在装了 Hermes 时才判定。判定与 [HermesEnv.repairOnHost] 的修复范围一一对应：
     * 记录指向不存在的依赖代、而盘上还有**完整**代 ⇒ 宿主侧一键改写记录（[FIX_HERMES_DEPS]）；
     * 要重建 Python 环境、或要 `git checkout` 补回源码锁 ⇒ 脚本落盘后进终端跑（[Check.terminalCmd]）。
     */
    private fun hermesDepsCheck(ctx: Context): Check {
        val st = HermesEnv.inspect(ctx)
        return Check(
            id = "hermes-deps",
            label = "Hermes 依赖环境",
            ok = st.ok,
            detail = st.detail,
            fixId = if (!st.ok && st.canRepairOnHost) FIX_HERMES_DEPS else null,
            terminalCmd = if (!st.ok && !st.canRepairOnHost) HermesEnv.REPAIR_CMD else null,
        )
    }

    /**
     * 网络连通性。
     *
     * 只说"系统标记为已验证"是不够的：VPN / 企业网 / 部分运营商网络上这个标记经常
     * 是 false，而网络其实完全可用；反过来，拿不到能力（权限缺失、无活跃网络）时
     * 也不能直接判失败。所以系统标记只作为快路径，不确定时用一次**真实 TCP 探测**
     * 给出结论——体检要的是真实情况，不是"猜"。
     */
    private fun networkCheck(ctx: Context): Check {
        val cap = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
        }.getOrNull()
        val validated = cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val hasInternet = cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val (ok, detail) = networkVerdict(
            validated = validated,
            reachable = validated || tcpReachable(),
            hasInternet = hasInternet,
        )
        return Check(
            id = "network", label = "网络连通性", ok = ok, detail = detail,
            // 网络本身没法"一键修"（可能是代理 App、可能是运营商），去路是设置页的
            // 「网络自检」：它会实测访问国内源与索引并给出可复制的结论（[NetSelfCheck]）。
            route = if (ok) null else GUIDED_ROUTES["network"],
        )
    }

    /** 网络体检结论（纯函数，便于单测锁死文案与判定）。 */
    internal fun networkVerdict(
        validated: Boolean,
        reachable: Boolean,
        hasInternet: Boolean,
    ): Pair<Boolean, String> = when {
        validated -> true to "宿主网络可用（系统已验证）"
        reachable -> true to "宿主网络可用（实测已连通）"
        hasInternet -> false to "已连上网络但外网不通（代理或受限网络？）"
        else -> false to "宿主网络不可用，Agent 安装与更新会失败"
    }

    /**
     * 真实连通性探测：TCP 连一下国内公共 DNS 的 53 端口，超时 1.2 秒。
     * 体检要求"秒级完成"，所以只连一个目标、超时给短；连得上即说明出网路径通。
     * IO 线程调用（inspect 整体在 Dispatchers.IO）。
     */
    private fun tcpReachable(): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("223.5.5.5", 53), 1200)
            true
        }
    }.getOrDefault(false)

    private fun storageCheck(ctx: Context): Check {
        // 主路径 = MANAGE_EXTERNAL_STORAGE（E-005 修订）。基础 /sdcard 读写不依赖它，
        // 但 Agent 产出工作区的主路径依赖，故以此为准。
        val granted = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        // 2026-10-09（#1 回归）：只看授权位是不够的——第 0 步那次事故里「授权位看着对、
        // 共享存储就是读不到」正是 READ 的 `maxSdkVersion` 帽子造成的。这里补一次**真实读**
        // （`/storage/emulated/0/Download` 列得出来吗），把授权位和真实能力分开报。
        val readable = SystemInfoProvider.sharedStorageReadable()
        val (ok, detail) = storageVerdict(granted, readable)
        return Check(
            id = "storage", label = "存储权限", ok = ok, detail = detail,
            // 授权页可由主页直接拉起（HomeScreen 里发 ACTION_MANAGE_APP_ALL_FILES_ACCESS_
            // PERMISSION 带 package: 的 Intent），不必先绕到设置页再找那一行。
            route = if (ok) null else GUIDED_ROUTES["storage"],
        )
    }

    /**
     * 存储体检结论（纯函数，便于单测锁死文案与判定）。
     *
     * [readable] 必须是「真的去读了一次」的结果，不是权限位的复述：第 0 步的教训就是
     * 权限位与真实能力会脱节（`READ_EXTERNAL_STORAGE` 带 `maxSdkVersion` 帽子时，
     * Android 13+ 上 READ 权限为空，而 MANAGE 仍可能是 true）。
     */
    internal fun storageVerdict(granted: Boolean, readable: Boolean): Pair<Boolean, String> = when {
        granted && readable -> true to "所有文件访问已授权，共享存储可直读"
        granted -> false to "所有文件访问已授权，但共享存储读不到（权限帽子或存储视图受限，需复查）"
        else -> false to "所有文件访问未授权，工作区主路径不可用；到系统设置开启"
    }

    /**
     * native 核心（`libzhengdao_core.so`）到底有没有加载进来（2026-10-08 补，用户授权）。
     *
     * **为什么值得占一格**：Rust 那条链路的失败是**静默**的——[CoreNative] 在类加载时探一次
     * `System.loadLibrary`，失败即永久标记，之后解压退回 `RootfsInstaller` 的 commons-compress
     * 纯 Java 路径、SHA256 退回 `MessageDigest`：功能照常，只是更慢。E-012（16KB 页对齐漏配
     * ⇒ native 静默失效）与 E-022（R8 改掉 JNI 回调名 ⇒ release 装上一解压就 SIGABRT）
     * 都出在这条链路上，而在补这一格之前，装完包之后**没有任何地方**能看出"现在跑的是 Java 回退"。
     *
     * 用户 2026-10-08 的原话是「sha256→Rust 真机验出来收益不大，你觉得有意义就接吧」——
     * 对：**速度上确实没多少收益**（实测差距 <2%，见 ERRATA 关于解压耗时的记录），
     * 这一格的价值是"降级可见"，不是"更快"。
     *
     * **编码成 ⚠ 而不是 ✗**：native 加载失败用户侧修不了（.so 打包/页对齐的问题，只能换包），
     * 报红会破坏本文件「红 = 修得了」的不变量；与 [resourceCheck] 同构——异常但无自愈动作。
     * 本 App 只打 `arm64-v8a`（`app/build.gradle.kts` 的 abiFilters），所以在能装上本包的设备上
     * 它**本不该**是 false；真是 false 就说明打出来的包有问题。
     */
    internal fun nativeCheck(): Check {
        val available = runCatching { CoreNative.isRustAvailable() }.getOrDefault(false)
        return Check(
            id = "native",
            label = "native 加速层",
            ok = true,
            warn = !available,
            detail = nativeDetail(available),
        )
    }

    /** native 体检文案（纯函数：类加载与 .so 在 JVM 单测里都不存在，故判定与文案分家）。 */
    internal fun nativeDetail(available: Boolean): String = if (available) {
        "libzhengdao_core.so 已加载：解压与 SHA256 走 native"
    } else {
        "未加载：解压与 SHA256 走 Java 回退（功能正常但更慢）；疑似打包或页对齐问题，见 E-012/E-022"
    }

    /**
     * 资源占用（v1.2 阶段 3.2）：太极 serve + PRoot guest 的 RSS 与 CPU。
     *
     * 按 UID 聚合本 App 的全部进程（含 PRoot 里的 bash / tmux / node 子进程），
     * 超阈值走 **warn（黄色 ⚠）**——内存/CPU 没有一键修复，报红会违反
     * 「红 = 修得了」的不变量。采样与判定都在 [ResMonitor]。
     */
    private fun resourceCheck(): Check {
        val v = runCatching { ResMonitor.sampleWithLog() }
            .getOrElse { ResMonitor.verdict(null) }
        return Check(
            id = "resource", label = "资源占用",
            ok = v.ok, detail = v.detail, warn = v.warn,
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
