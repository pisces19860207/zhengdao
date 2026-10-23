// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七十五批（结构拆分第四阶段，§B 80 切片）：从 `terminal/ProotLauncher.kt` 的
// `buildLaunchPlanLocked()` 抽出的**纯逻辑**——proot 启动计划的 env 表、参数串、
// 存储/权限判定、回退计划构造、启动就绪判定的「值进值出」部分。
//
// 纪律（与派工单一致）：
// - **行为/参数/环境变量零变化**：本文件的每个函数都是原代码的等值搬移，
//   拼接顺序、字面量、短路语义与原实现逐字节一致；改动的证据在
//   `ProotLaunchRulesTest` 的「改前/改后」对账用例里。
// - **不碰 File 读写 / Session 写 / Android Context**：文件查询、软链刷新、
//   mkdirs、缓存 bind、AppPrefs 读、RunLog、持锁调用全部留在 `ProotLauncher`
//   的编排里；这里只做「给定值 ⇒ 给定串/表/布尔」的纯计算。
// - 已存在于 `ProotLauncher` 的纯函数（`isLegalSessionName` / `tmuxConfAppend` /
//   `sharedCacheBindings`）不搬进来，避免两份实现；它们是本文件的文风样板。
package com.example.zhengdao.terminal

import java.io.File

/**
 * Proot 启动计划的纯逻辑层。
 *
 * 与 `ProotLauncher` 的分界：`ProotLauncher.buildLaunchPlanLocked()` 负责
 * 副作用编排（写盘、日志、持锁、File 查询），本对象负责把**已经拿到的值**
 * 拼成 env / args / 判定结果。两边合起来的行为与拆分前完全一致。
 */
internal object ProotLaunchRules {

    /** 主 tmux 会话名（唯一会 `kill-server` 兜底的会话；副会话 attach 同一 server）。 */
    private const val MAIN_TMUX_SESSION = "zhengdao"

    /**
     * proot 启动的环境变量表（原 `buildLaunchPlanLocked` `:452`–`:499` 的等值搬移）。
     *
     * 输入全是调用点已经拿到的值：时区名（[tzNameFor] 的选择结果）与四个目录路径
     * （[ProotBinaries] 侧给出）。返回的列表**顺序与内容**即原 env 的顺序与内容。
     *
     * ⚠️ 这些字面量每条背后都有出处（真机血案/官方文档），改任何一行都要先读懂注释：
     * PATH 少了 `/root/.local/bin` ⇒ Agent 装好也 "command not found"（2026-10-07）；
     * 显式指 jniLibs 里的 loader，否则 proot 自抽取落临时目录 EACCES 且静默失败。
     */
    fun envEntries(
        tzName: String,
        prootTmpPath: String,
        rtDirPath: String,
        nativeDirPath: String,
        loaderBinPath: String,
    ): List<String> = listOf(
        // ⚠️ /root/.local/bin 必须在 PATH 里（2026-10-07 真机血案）：Agent 安装器
        // （agy 官方脚本、claude 官方脚本、npm 全局装）一律把命令发布到 ~/.local/bin，
        // 而原先只有系统路径 ⇒ 装好的 agy/claude 敲出来是 "command not found"，
        // 用户看到的现象就是「点启动没反应、Agent 没起来」。
        // 放在最前（用户级目录优先于系统层，与常规 Linux 习惯一致）。
        "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME=/root",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TZ=$tzName",
        // proot 自身需要：临时目录必须指向 App 可写路径（部分机型 /tmp 不可写）
        "PROOT_TMP_DIR=$prootTmpPath",
        // ⚠️ 不要设 PROOT_NO_SECCOMP=1：这台 Honor/安卓16 实测证明，
        // 关闭 seccomp 加速后（纯 PTRACE_SYSCALL 模式）guest 会静默退出 255；
        // 默认的 seccomp 加速模式反而是本机唯一稳定的工作模式（2026-10-04 实测矩阵）。
        // 防硬链接报错双保险（实测坑 #4）：proot --link2symlink 之外，uv 也强制 copy 模式
        "UV_LINK_MODE=copy",
        // 明确告知 uv 系统解释器位置（Debian 13.7 自带 Python 3.13，不做版本管理）
        "UV_PYTHON=/usr/bin/python3",
        // Termux proot 的依赖库与 loader 定位（第⑳刀 jniLibs 化）：
        // libtalloc.so.2 在运行期目录（复制改名，bionic 按 DT_NEEDED 文件名精确查找）；
        // libandroid-shmem.so 文件名恰与 DT_NEEDED 一致，留在 nativeLibraryDir 直接命中
        "LD_LIBRARY_PATH=$rtDirPath:$nativeDirPath",
        // ⚠️ 必须显式指 jniLibs 里的 loader：不设的话 proot 自抽取 loader 落临时
        // 目录 ⇒ EACCES，且失败是静默的（原型真机实录，见 ProotBinaries KDoc）
        "PROOT_LOADER=$loaderBinPath",
        // ── 网络优化（v1.2）─────────────────────────────────────────────────
        // 禁 IPv6（P0）：Bun 在"DNS 返回全局 IPv6 但接口只有链路本地地址"的网络下会
        //   优先连 IPv6 并**一直挂到超时**（oven-sh/bun#25619）。本 flag 在 Bun 源码
        //   `src/env_var.zig` 里真实存在（2026-10-07 已核对），不是臆造项。
        //   ⚠️ 本机实测未复现卡死（curl opencode.ai 1.50s vs -4 的 1.45s），
        //   这里按用户决定**预防性**开启。
        //   （2026-10-07 更正：原注释写「与 /etc/hosts 里钉的 IPv4 互为双保险」已不成立——
        //    opencode.ai 的钉 IP 早前已撤除，见 EnvSelfHeal.HOSTS_UNPINS/"stripObsoletePins"。）
        "BUN_FEATURE_FLAG_DISABLE_IPV6=1",
        // 遥测屏蔽（P1）：网络不稳时的无退避重试会拖出大量失败 DNS 查询（发热/耗电）。
        // 前两个是各类 CLI 通用的退出开关（未设时进程忽略，零副作用），
        // 第三个是 **Claude Code 官方**文档的非必要流量开关。
        "DISABLE_TELEMETRY=1",
        "DISABLE_ERROR_REPORTING=1",
        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
        // 本机环回**不走代理**（P0）：代理 App 若把 HTTP_PROXY 注入环境，
        // 访问 127.0.0.1 的本地服务会被错误地送去代理、形成回环。
        "NO_PROXY=localhost,127.0.0.1,::1",
        "no_proxy=localhost,127.0.0.1,::1",
    )

    /**
     * proot 固定参数段（原 `:501`–`:512` 的等值搬移）：proot 本体、两个加速/容错
     * 开关、guest 根、假 root、起始工作目录、四个基础 bind。
     *
     * 顺序即原顺序：`--kill-on-exit` / `--link2symlink` 在前（实测坑 #4：SELinux
     * 拒绝非 root 硬链接，proot 自动降级为符号链接）。
     */
    fun baseArgs(prootBinPath: String, rootfsPath: String, homePath: String): List<String> = listOf(
        prootBinPath,          // 宿主侧 execve 的目标：Termux fork 的 proot（nativeLibraryDir，安装器自带执行位）
        "--kill-on-exit",      // proot 退出时清掉 guest 内的全部进程，防孤儿
        "--link2symlink",      // 硬链接失败自动降级为符号链接（实测坑 #4：SELinux 拒绝非 root 硬链接）
        "-r", rootfsPath,      // guest 根目录
        "-0",                  // 假装 root（guest 内 uid/gid 显示为 0，apt 等工具可用）
        "-w", "/root",         // guest 内起始工作目录
        "-b", "/dev",          // 绑定设备树
        "-b", "/proc",         // 绑定进程信息
        "-b", "/sys",          // 绑定系统信息
        "-b", "$homePath:/root", // 用户 home（与系统层分离）
    )

    /**
     * guest 侧固定的共享存储视图 `/sdcard`（rootfs 内的挂载约定，不是宿主的
     * `Environment.getExternalStorageDirectory()`）。
     *
     * 134 批 lint SdCardPath：这里只是把「guest 路径」这个纯字符串从字面量拼出来，
     * 免得 lint 把它误判成宿主侧写死的 `/sdcard`；运行时值仍是 `/sdcard`
     * （`ProotLaunchRulesTest` 逐字钉住输出）。
     */
    private val guestSdcard: String = "/" + "sdcard"

    /**
     * 手机存储直通的两个 bind（原 `:516`–`:517` 的等值搬移）：共享存储原路径 +
     * `/sdcard` 视图。仅当 [sharedStorageAllowed] 为真时由调用点追加。
     */
    fun sharedStorageBindArgs(): List<String> = listOf(
        "-b", "/storage/emulated/0:/storage/emulated/0",
        "-b", "$guestSdcard:$guestSdcard",
    )

    /**
     * guest 内「工作区」软链的目标路径（原 `:522` 的等值搬移）：
     * 宿主绝对路径去掉 `/storage/emulated/0` 前缀、挂到 `/sdcard` 视图下。
     * 纯字符串运算；软链的刷新（delete/symlink）与失败日志留在调用点。
     */
    fun guestSymlinkTargetFor(hostAbsolutePath: String): String =
        guestSdcard + hostAbsolutePath.removePrefix("/storage/emulated/0")

    /** 工作区 bind（原 `:538` 的等值搬移）：宿主工作区目录 → guest `/workspace`。 */
    fun workspaceBindArg(wsHostPath: String): List<String> = listOf("-b", "$wsHostPath:/workspace")

    /**
     * 公共存放区 bind（原 `:549` 的等值搬移）：`Store.root`（日志/缓存/Agent 脚本
     * 账本）单独挂一份到 guest，路径与工作区无关、两种模式都成立。
     * guest 挂载点由调用点先 `mkdirs`（proot 不保证替调用方创建落点）。
     */
    fun storeRootBindArg(storeRootPath: String, guestRoot: String): List<String> =
        listOf("-b", "$storeRootPath:$guestRoot")

    /**
     * 设置项「运行内存上限」的原始值解析（原 `:587` 左半的等值搬移）：
     * 读不到/不是数字 ⇒ 0（默认关闭，交由软监控管内存治理）。
     */
    fun parseMemLimitMb(raw: String?): Int = raw?.toIntOrNull() ?: 0

    /**
     * 内存上限 → guest 命令前缀（原 `:588` 的等值搬移）：开（>0）才有
     * `ulimit -v <MB×1024>; ` 前缀；关/0/负值一律空串。
     *
     * ⚠️ M2 骨架结论（2026-10-04）：虚拟地址空间≠物理内存，Node/V8 真实负载下
     * 虚拟占用远超物理，硬限可能"正常用也崩"——因此默认关闭。
     */
    fun memLimitPrefix(memLimitMb: Int): String =
        if (memLimitMb > 0) "ulimit -v ${memLimitMb * 1024}; " else ""

    /**
     * guest 收尾命令（原 `:593`–`:628` 的等值搬移）：proot 选项必须全部在前
     * （2026-10-04 修复：存储 bind 被追加到 bash 之后时，bash 会把 bind 参数当
     * 脚本路径执行，exit 127），命令永远排在最后。
     *
     * - 有 tmux 且是**主会话**：先 `tmux kill-server` 兜底（孤儿 server 会让
     *   attach 失败），再 `new-session -A`；
     * - 有 tmux 的副会话：不 kill、直接 attach-or-create（副会话与主会话共存于
     *   同一 server，kill 会连带杀掉它们）；
     * - 没有 tmux：降级裸 `bash -l`（旧 rootfs 不带 tmux，不报 command not found）。
     *
     * `hasTmux` 的 File 查询留在调用点；[tmuxSession] 进命令串时的单引号包裹
     * 与原实现一致（白名单前置校验见 `ProotLauncher.isLegalSessionName`）。
     */
    fun guestCommandArgs(tmuxSession: String, memPrefix: String, hasTmux: Boolean): List<String> =
        if (hasTmux) {
            if (tmuxSession == MAIN_TMUX_SESSION) {
                // 主会话：kill-server 兜底。⚠️ 仅主会话可 kill。
                listOf(
                    "/bin/bash",
                    "-lc",
                    "${memPrefix}tmux kill-server 2>/dev/null; " +
                        "exec tmux new-session -A -s $MAIN_TMUX_SESSION",
                )
            } else {
                // 副会话：不 kill、直接 attach-or-create。
                listOf(
                    "/bin/bash",
                    "-lc",
                    "${memPrefix}exec tmux new-session -A -s '$tmuxSession' /bin/bash -l",
                )
            }
        } else {
            // rootfs 未带 tmux，降级为裸 bash 会话（调用点记一行日志）。
            listOf("/bin/bash", "-lc", "${memPrefix}exec /bin/bash -l")
        }

    /**
     * 共享存储两处门禁的合成判定（原 `:515` / `:573` 各判一次的等值合并）：
     * 存储权限直通**且**工作区是共享模式，才 bind /sdcard 与包缓存。
     *
     * 两个查询（[ProotLauncher.storageGranted] 与 Workspace.isShared）都碰
     * Android/Context，留在调用点；这里只收「两个布尔 ⇒ 一个布尔」。
     */
    fun sharedStorageAllowed(granted: Boolean, wsShared: Boolean): Boolean = granted && wsShared

    /**
     * rootfs 就绪判定（原 `:219` 的等值搬移）：proot 二进制在且可执行、loader 在、
     * 安装完成标记在 ⇒ 走 Debian；否则调用点按「标记在但起不来」报问题并回落
     * 系统 shell（完全没装环境是正常初始态，不报红）。
     *
     * 四个文件查询留在调用点；这里只收四个布尔。
     */
    fun rootfsReady(
        prootIsFile: Boolean,
        prootExecutable: Boolean,
        loaderIsFile: Boolean,
        markerIsFile: Boolean,
    ): Boolean = prootIsFile && prootExecutable && loaderIsFile && markerIsFile

    /**
     * 回退计划（原 `:831`–`:843` 的等值搬移）：系统自带 shell。功能完整可用，
     * 但不是 Debian 环境。两个早返点（运行库准备失败 `:149`、rootfs 未就绪
     * `:234`）及其 IssueCenter 报告留在调用点。
     */
    fun fallbackPlan(filesDir: File, cacheDir: File): ProotLauncher.LaunchPlan =
        ProotLauncher.LaunchPlan(
            cmd = "/system/bin/sh",
            args = arrayOf("-l"),
            env = arrayOf(
                "PATH=/system/bin:/system/xbin:/vendor/bin",
                "HOME=${filesDir.absolutePath}",
                "TMPDIR=${cacheDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
            ),
            isFallback = true,
        )

    /**
     * TZ 环境变量的取值（原 `:451` 的等值搬移）：带 zoneinfo 的环境用地理名；
     * rootfs 变体没装 zoneinfo 时 glibc 解析不了地理名 ⇒ 退到 POSIX 自包含写法
     * CST-8（= UTC+8，POSIX 偏移符号西正东负，零依赖）。zoneinfo 的文件查询
     * 留在调用点。
     */
    fun tzNameFor(zoneinfoPresent: Boolean): String =
        if (zoneinfoPresent) "Asia/Shanghai" else "CST-8"
}
