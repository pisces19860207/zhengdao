// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 本文件只做一件事：按 proot 官方文档记载的通用参数，组装启动命令与环境变量。
// 所有参数均为 proot 上游（proot-me/proot）文档公开的选项，语义见行内注释；
// 进程创建与 IO 由 Pty.kt / pty.c（POSIX forkpty + execve 标准接口）完成。
package com.example.zhengdao.terminal

import android.content.Context
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
    )

    /** 已授予「所有文件访问」= 手机存储可直通。 */
    fun storageGranted(context: Context): Boolean =
        android.os.Environment.isExternalStorageManager()

    /**
     * 组装启动计划：
     *  - 已安装 Debian 13.7 环境（rootfs 目录存在且带完成标记）→ 经 proot 启动 guest bash；
     *  - 未安装 → 回退到系统自带 shell，App 端行为完全一致，用户无感降级。
     */
    fun buildLaunchPlan(context: Context): LaunchPlan {
        val files = context.filesDir
        val rootfsDir = File(files, "rootfs")

        // proot 随 APK 内置（jniLibs: libproot.so）。注意：原生库目录里的 .so 没有
        // 执行位（系统按 dlopen 用途安装它们，不给 x 位），不能直接 execve——
        // 复制到可写目录并补 0700 权限；APK 升级（长度变化）时自动重新复制。
        val embedded = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val prootBin = File(files, "proot/proot")
        if (embedded.isFile) {
            prootBin.parentFile?.mkdirs()
            if (!prootBin.isFile || prootBin.length() != embedded.length()) {
                embedded.copyTo(prootBin, overwrite = true)
            }
            try {
                android.system.Os.chmod(prootBin.absolutePath, 448) // 0700
            } catch (_: Throwable) {
            }
        }
        val installMarker = File(rootfsDir, ".zhengdao-rootfs-ok")

        // 诊断日志：启动决策的每一项检查结果（临时，M1.2 验收后可移除）
        android.util.Log.i(
            "ZhengdaoLaunch",
            "buildLaunchPlan: embedded=${embedded.isFile} prootBin=${prootBin.isFile} " +
                "canExecute=${prootBin.canExecute()} marker=${installMarker.isFile} " +
                "rootfs=${rootfsDir.exists()} nativeLibDir=${context.applicationInfo.nativeLibraryDir}"
        )

        val rootfsReady = prootBin.isFile && prootBin.canExecute() && installMarker.isFile
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

        val args = mutableListOf(
            prootBin.absolutePath,   // 宿主侧 execve 的目标：proot 本体（自编译，静态链接）
            "--kill-on-exit",        // proot 退出时清掉 guest 内的全部进程，防孤儿
            "--link2symlink",        // 硬链接失败自动降级为符号链接（实测坑 #4：SELinux 拒绝非 root 硬链接）
            "-r", rootfsDir.absolutePath,          // guest 根目录
            "-0",                    // 假装 root（guest 内 uid/gid 显示为 0，apt 等工具可用）
            "-w", "/root",           // guest 内起始工作目录
            "-b", "/dev",            // 绑定设备树
            "-b", "/proc",           // 绑定进程信息
            "-b", "/sys",            // 绑定系统信息
            "-b", "${homeDir.absolutePath}:/root", // 用户 home（与系统层分离）
            "/bin/bash",             // guest 内要执行的命令（proot 会把它翻译到 rootfs 内）
            "-l",                    // login shell：读取 /etc/profile（UV_LINK_MODE 在那里全局生效）
        )
        // 手机存储直通（用户要求）：共享存储绑进 guest 的相同路径 + /sdcard 视图；
        // 默认工作区 Download/证道（guest 内 /root/工作区 直达）。未授权时静默跳过。
        if (storageGranted(context)) {
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
        )

        return LaunchPlan(
            cmd = prootBin.absolutePath,
            args = args.toTypedArray(),
            env = env.toTypedArray(),
            banner = "[证道] Debian 13.7 环境已启动（Python 3.13 / Node.js 26 / uv 就绪）\r\n",
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
