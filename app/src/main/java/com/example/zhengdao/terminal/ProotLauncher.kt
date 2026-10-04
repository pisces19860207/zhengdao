// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 本文件只做一件事：按 proot 官方文档记载的通用参数，组装启动命令与环境变量。
// 所有参数均为 proot 上游（proot-me/proot）文档公开的选项，语义见行内注释；
// 进程创建与 IO 由 Pty.kt / pty.c（POSIX forkpty + execve 标准接口）完成。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.settings.ApiKeyStore
import java.io.File
import java.io.IOException

object ProotLauncher {

    /**
     * 默认 RootFS 下载地址：latest 发布永远指向最新一次构建的产物，
     * 因此这条地址长期有效。M3 起改为由 ed25519 验签的 manifest 动态下发。
     */
    const val DEFAULT_ROOTFS_URL =
        "https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-base-arm64.tar.zst"

    /**
     * 一次启动的完整计划。
     * @param cmd  宿主侧可直接 execve 的程序路径（proot 模式下 = proot 二进制；回退模式 = /system/bin/sh）
     * @param args 命令行参数（proot 模式下末尾附带 guest 内要执行的命令 /bin/bash -l）
     * @param env  传给子进程的环境变量（proot 会透传给 guest，guest 内进程全部可见）
     * @param banner 启动后打印在终端里的说明横幅
     */
    data class LaunchPlan(
        val cmd: String,
        val args: Array<String>,
        val env: Array<String>,
        val banner: String,
        val isFallback: Boolean = false,
        /** 会话是否由 tmux 保持（决定绿点分屏按钮是否可用） */
        val usesTmux: Boolean = false,
    )

    /**
     * 手机存储可直通判定。⚠️ targetSdk 28 走 WRITE_EXTERNAL_STORAGE 运行时权限
     * （Android 11+ 对 target≤29 的 App 自动保持 legacy 存储视图，授权即可写共享
     * 存储）；「所有文件访问」开关只对 target≥30 的 App 存在，本 App 永远拿不到，
     * 不能用作判定（2026-10-04 修复：此前用 isExternalStorageManager 恒 false）。
     */
    fun storageGranted(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 流式计算文件 SHA-256（版本固定校验用）。 */
    private fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 组装启动计划：
     *  - 已安装 Debian 13.7 环境（rootfs 目录存在且带完成标记）→ 经 proot 启动 guest bash；
     *  - 未安装 → 回退到系统自带 shell，App 端行为完全一致，用户无感降级。
     */
    fun buildLaunchPlan(context: Context): LaunchPlan {
        val files = context.filesDir
        val rootfsDir = File(files, "rootfs")

        // 2026-10-04 实测定论：自编译上游 proot 缺少 Android 适配（App 域内加载
        // guest 时静默退出 255，见设计文档 §4），本机实测只有带 Android 补丁的
        // Termux proot fork（GPL）能在 App 域内正常工作。基线切换为该 fork 的
        // 官方发行二进制（聚合分发，GPL 合规；PROVENANCE.md 有登记），随 APK
        // assets 内置，启动时释放到 files/termux-proot/ 并补执行位。
        // files/ 下的可执行文件 App 域可直接 execve（jnilLibs 方案留档不再使用）。
        val tpDir = File(files, "termux-proot").apply { mkdirs() }
        val prootBin = File(tpDir, "proot")
        val loaderBin = File(tpDir, "loader")
        val embedded = File(context.applicationInfo.nativeLibraryDir, "libproot.so") // 旧自编译版，留档
        // 版本固定清单（asset 名 → 释放文件名 → SHA256；来源与版本见 PROVENANCE.md /
        // THIRD-PARTY-LICENSES.md，Termux proot 5.1.107.96 aarch64 官方构建产物）。
        // 兜底路径：已释放且 SHA256 校验通过 → 跳过（修环境/重装不重复释放）；
        // 缺失或损坏 → 从 assets 重新释放并补权限。
        val pinned = listOf(
            Triple("tproot", "proot", "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717"),
            Triple("tloader", "loader", "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551"),
            Triple("libtalloc.so", "libtalloc.so.2", "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309"),
            Triple("libandroid-shmem.so", "libandroid-shmem.so", "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"),
        )
        for ((asset, target, expectedSha) in pinned) {
            val dst = File(tpDir, target)
            if (dst.isFile && sha256Of(dst).equals(expectedSha, ignoreCase = true)) continue // 已就位
            context.assets.open("proot/$asset").use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256Of(dst)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                RunLog.log("严重: $target SHA256 不匹配（actual=$actual），已删除损坏副本")
                dst.delete()
                continue
            }
            val mode = if (target == "proot" || target == "loader") 493 else 420 // 0755 / 0644
            try { android.system.Os.chmod(dst.absolutePath, mode) } catch (_: Throwable) {}
            RunLog.log("已释放: $target（SHA256 校验通过）")
        }
        RunLog.log(
            "启动决策: proot=" + prootBin.isFile + " loader=" + loaderBin.isFile +
                " marker=" + File(rootfsDir, ".zhengdao-rootfs-ok").isFile
        )

        val installMarker = File(rootfsDir, ".zhengdao-rootfs-ok")
        val rootfsReady = prootBin.isFile && prootBin.canExecute() && loaderBin.isFile && installMarker.isFile

        if (!rootfsReady) {
            return fallbackPlan(files, context.cacheDir)
        }

        // DNS 兜底（设计文档 §4：proot 内没有 systemd-resolved，缺 resolv.conf 就是
        // "下载得动、上不了网"的第一大故障；App 每次启动前确保存在）
        ensureDnsFiles(File(rootfsDir, "etc/resolv.conf"), File(rootfsDir, "etc/hosts"))

        // home 与系统分离（设计文档 §8）：用户数据放 App 私有目录，bind 挂到 guest 的 /root，
        // 这样「修复环境」重解压系统层时不碰用户数据
        val homeDir = File(files, "home").apply { mkdirs() }
        val prootTmp = File(files, "proot-tmp").apply { mkdirs() }

        val env = mutableListOf(
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            // proot 自身需要：临时目录必须指向 App 可写路径（部分机型 /tmp 不可写）
            "PROOT_TMP_DIR=${prootTmp.absolutePath}",
            // ⚠️ 不要设 PROOT_NO_SECCOMP=1：这台 Honor/安卓16 实测证明，
            // 关闭 seccomp 加速后（纯 PTRACE_SYSCALL 模式）guest 会静默退出 255；
            // 默认的 seccomp 加速模式反而是本机唯一稳定的工作模式（2026-10-04 实测矩阵）。
            // 防硬链接报错双保险（实测坑 #4）：proot --link2symlink 之外，uv 也强制 copy 模式
            "UV_LINK_MODE=copy",
            // 明确告知 uv 系统解释器位置（Debian 13.7 自带 Python 3.13，不做版本管理）
            "UV_PYTHON=/usr/bin/python3",
            // Termux proot 的依赖库与外部 loader 定位（其 fork 的 loader 为独立文件）
            "LD_LIBRARY_PATH=${tpDir.absolutePath}", // libtalloc.so.2 / libandroid-shmem.so 在此目录
            "PROOT_LOADER=${File(files, "termux-proot/loader").absolutePath}",
        )

        // API Key 注入（第二批）：设置页保存的密钥解密后以环境变量形式透传给 guest，
        // Claude Code / Hermes 等Agent 直接读取。只注入非空密钥；日志仅记服务商名，不记密钥值。
        runCatching {
            var injected = 0
            ApiKeyStore.PROVIDERS.forEach { (id, envName) ->
                val key = ApiKeyStore.get(context, id)
                if (!key.isNullOrBlank()) {
                    env.add("$envName=$key")
                    injected++
                }
            }
            if (injected > 0) RunLog.log("API Key 注入: $injected 个服务商")
        } // 解密失败不阻断启动（密钥坏了不该连累终端）

        val args = mutableListOf(
            prootBin.absolutePath,   // 宿主侧 execve 的目标：Termux fork 的 proot（files/ 下可执行）
            "--kill-on-exit",        // proot 退出时清掉 guest 内的全部进程，防孤儿
            "--link2symlink",        // 硬链接失败自动降级为符号链接（实测坑 #4：SELinux 拒绝非 root 硬链接）
            "-r", rootfsDir.absolutePath,          // guest 根目录
            "-0",                    // 假装 root（guest 内 uid/gid 显示为 0，apt 等工具可用）
            "-w", "/root",           // guest 内起始工作目录
            "-b", "/dev",            // 绑定设备树
            "-b", "/proc",           // 绑定进程信息
            "-b", "/sys",            // 绑定系统信息
            "-b", "${homeDir.absolutePath}:/root", // 用户 home（与系统层分离）
        )
        // 手机存储直通（用户要求）：共享存储绑进 guest 的相同路径 + /sdcard 视图；
        // 默认工作区 Download/证道（guest 内 /root/工作区 直达）。未授权时静默跳过。
        // 「仅私有」模式（设置页工作区三选）不绑共享存储，guest 完全看不到手机文件。
        val wsMode = com.example.zhengdao.ui.Settings.prefs(context)
            .getString("workspace_mode", "default") ?: "default"
        if (storageGranted(context) && wsMode != "private") {
            val shared = "/storage/emulated/0"
            args.addAll(arrayOf("-b", "$shared:$shared", "-b", "/sdcard:/sdcard"))
            try {
                val ws = File("$shared/Download/证道")
                ws.mkdirs()
                val link = File(context.filesDir, "home/工作区")
                link.parentFile?.mkdirs()
                if (!link.exists()) {
                    android.system.Os.symlink("/sdcard/Download/证道", link.absolutePath)
                }
            } catch (_: Throwable) {
            }
        }
        // 工作区（全版本兼容）：App 外部目录无需任何权限且真实路径可 bind；
        // 安卓 16 实测 /sdcard 原始路径对 target 28 应用不可达，/sdcard bind 仅对
        // legacy 视图设备生效（上面的 storageGranted 分支），两者并存互不影响。
        // 三档模式：默认 → 私有工作区（+共享存储直通）；自定义 → 用户路径（不可达时回退私有）；
        // 仅私有 → 只绑私有目录。
        val wsHost = when (wsMode) {
            "custom" -> {
                val custom = com.example.zhengdao.ui.Settings.prefs(context)
                    .getString("workspace_custom", "") ?: ""
                val f = if (custom.isNotBlank()) File(custom) else null
                if (f != null && f.isDirectory) {
                    RunLog.log("工作区(自定义): ${f.absolutePath} -> /workspace")
                    f
                } else {
                    RunLog.log("工作区(自定义): 路径不可达「$custom」，回退私有工作区")
                    File(context.getExternalFilesDir(null), "workspace")
                }
            }
            else -> File(context.getExternalFilesDir(null), "workspace")
        }.apply { mkdirs() }
        if (wsMode != "custom") {
            RunLog.log("工作区(${wsMode}): ${wsHost.absolutePath} -> /workspace")
        }
        args.addAll(arrayOf("-b", "${wsHost.absolutePath}:/workspace"))

        // guest 命令必须收尾：所有 proot 选项在前（2026-10-04 修复：存储 bind 被追加
        // 到 bash 之后时，bash 会把 bind 参数当脚本路径执行，exit 127）
        // 运行内存上限（用户第四批）：ulimit -v 限制 guest 进程虚拟地址空间，防单个
        // Agent / 编译任务无限膨胀拖垮整机。实测 3GB 下 Node v26 可正常启动（空闲态）。
        // ⚠️ M2 骨架结论（2026-10-04）：虚拟地址空间≠物理内存，Node/V8 真实负载下虚拟
        // 占用远超物理，硬限可能"正常用也崩"——因此默认关闭，交由 M2 的软监控
        // （读 /proc VmRSS + 通知栏警告）负责内存治理。设置页「运行内存上限」可手动开启。
        val memLimitMb = com.example.zhengdao.ui.Settings.prefs(context)
            .getString("guest_mem_limit_mb", "0")?.toIntOrNull() ?: 0
        val memPrefix = if (memLimitMb > 0) "ulimit -v ${memLimitMb * 1024}; " else ""
        // 会话保持（用户第三批）：tmux new-session -A = 有名为 zhengdao 的会话则 attach，
        // 没有则新建——终端断线重进不丢现场；Agent 经 autocmd 注入的命令同样落在会话内。
        // 外层 bash -l 先读 /etc/profile（UV_LINK_MODE 全局生效），再 exec 进 tmux。
        // 宿主侧检查 rootfs 是否带 tmux：旧包没有时降级裸 bash，guest 内不会报 command not found。
        val hasTmux = File(rootfsDir, "usr/bin/tmux").isFile
        if (hasTmux) {
            // kill-server 兜底：会话被 SIGKILL 终止时 proot 来不及跑 --kill-on-exit，
            // tmux server 可能残留为接不住客户端的僵尸（实测 2026-10-04 code=1）。
            // 真进程死亡时全组覆灭（ps 实测无孤儿），此行不影响任何存活场景。
            args.addAll(
                arrayOf(
                    "/bin/bash",
                    "-lc",
                    "${memPrefix}tmux kill-server 2>/dev/null; exec tmux new-session -A -s zhengdao",
                )
            )
        } else {
            RunLog.log("rootfs 未带 tmux，降级为裸 bash 会话")
            args.addAll(
                arrayOf(
                    "/bin/bash",
                    "-lc",
                    "${memPrefix}exec /bin/bash -l",
                )
            )
        }
        if (memLimitMb > 0) RunLog.log("guest 内存上限: ${memLimitMb}MB (ulimit -v)")

        // apt 分层策略（用户第四批）：系统层不支持 apt upgrade；语言级依赖走 pip / npm。
        // 横幅告知 + 设置页同步提示，替代对用户行为的假设。
        val aptHint = "[提示] 不要执行 apt upgrade（可能损坏环境）；系统级 apt 装软件可能失败，优先用 pip / npm\r\n"
        val banner = if (hasTmux) {
            "[证道] Debian 13.7 环境已启动（Python 3.13 / Node.js 26 / uv 就绪）\r\n" +
                "[证道] tmux 会话保持已启用（会话名 zhengdao）：Agent 断线重进不丢现场\r\n" + aptHint
        } else {
            "[证道] Debian 13.7 环境已启动（Python 3.13 / Node.js 26 / uv 就绪）\r\n" + aptHint
        }

        return LaunchPlan(
            cmd = prootBin.absolutePath,
            args = args.toTypedArray(),
            env = env.toTypedArray(),
            banner = banner,
            usesTmux = hasTmux,
        )
    }

    /** 回退计划：系统自带 shell。功能完整可用，但不是 Debian 环境。 */
    private fun fallbackPlan(filesDir: File, cacheDir: File): LaunchPlan = LaunchPlan(
        cmd = "/system/bin/sh",
        args = arrayOf("-l"),
        env = arrayOf(
            "PATH=/system/bin:/system/xbin:/vendor/bin",
            "HOME=${filesDir.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
        ),
        banner = "[证道] 运行环境尚未安装，当前为系统自带 shell；安装 Debian 13.7 环境后将自动切换为 bash\r\n",
        isFallback = true,
    )

    /** 确保 guest 内 DNS 配置存在（内容缺失即写入公共 DNS；失败不阻断启动）。 */
    private fun ensureDnsFiles(resolv: File, hosts: File) {
        try {
            if (!resolv.isFile || resolv.length() == 0L) {
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }
            if (!hosts.isFile || hosts.length() == 0L) {
                hosts.parentFile?.mkdirs()
                hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
            }
        } catch (_: IOException) {
            // 写不进去不阻断会话；网络类故障由 DNS 引导验证项（§9 Checklist）兜底
        }
    }
}
