// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.zhengdao.ui.AppState.rootfsInstalled
import java.io.File

/**
 * 系统信息采集（第二批）：全部为非敏感只读信息，任何一项失败返回"未知"，
 * 绝不抛异常。collect() 在后台线程调用（Settings/状态卡用 LaunchedEffect 异步加载）。
 */
object SystemInfoProvider {

    data class Info(
        // 系统与硬件
        val androidVersion: String, val apiLevel: String, val model: String,
        val manufacturer: String, val abi: String, val cores: String,
        val ramTotal: String, val ramAvail: String,
        val storageTotal: String, val storageAvail: String,
        val screen: String, val kernel: String,
        // 安全与运行环境
        val selinux: String, val rooted: String, val gms: String, val webview: String,
        // 证道环境
        val rootfs: String, val distro: String, val proot: String,
        val agentList: String, val python: String, val node: String, val uv: String,
    ) {
        fun asText(): String = listOf(
            "── 系统与硬件 ──",
            "Android 版本: $androidVersion (API $apiLevel)",
            "设备型号: $model", "制造商: $manufacturer",
            "CPU 架构: $abi", "核心数: $cores",
            "内存: $ramAvail 可用 / $ramTotal 总量",
            "存储(应用分区): $storageAvail 可用 / $storageTotal 总量",
            "屏幕: $screen", "内核: $kernel",
            "── 安全与运行环境 ──",
            "SELinux: $selinux", "Root: $rooted", "GMS: $gms", "WebView: $webview",
            "── 证道环境 ──",
            "RootFS: $rootfs", "发行版: $distro", "proot: $proot",
            "已装 Agent: $agentList", "Python: $python", "Node: $node", "uv: $uv",
        ).joinToString("\n")
    }

    private fun safe(f: () -> String): String = try { f() } catch (_: Throwable) { "未知" }

    private fun mb(bytes: Long): String = "${(bytes + 1048575) / 1048576} MB"

    fun collect(ctx: Context): Info = Info(
        androidVersion = safe { "Android ${Build.VERSION.RELEASE}" },
        apiLevel = safe { Build.VERSION.SDK_INT.toString() },
        model = safe { Build.MODEL },
        manufacturer = safe { Build.MANUFACTURER },
        abi = safe { Build.SUPPORTED_ABIS.firstOrNull() ?: "未知" },
        cores = safe { Runtime.getRuntime().availableProcessors().toString() },
        ramTotal = safe {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mb(mi.totalMem)
        },
        ramAvail = safe {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mb(mi.availMem)
        },
        storageTotal = safe {
            val st = StatFs(ctx.filesDir.absolutePath); mb(st.totalBytes)
        },
        storageAvail = safe {
            val st = StatFs(ctx.filesDir.absolutePath); mb(st.availableBytes)
        },
        screen = safe {
            val dm = ctx.resources.displayMetrics
            "${dm.widthPixels}x${dm.heightPixels}"
        },
        kernel = safe { System.getProperty("os.version") ?: "未知" },
        selinux = safe {
            val f = File("/sys/fs/selinux/enforce")
            when {
                !f.exists() -> "未知"
                f.readText().trim() == "1" -> "Enforcing"
                else -> "Permissive"
            }
        },
        rooted = safe {
            val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
                .any { File(it).exists() }
            if (su) "已 Root" else "未 Root"
        },
        gms = safe {
            val r = ctx.packageManager.resolveActivity(
                android.content.Intent("android.intent.action.VIEW").apply {
                    data = android.net.Uri.parse("https://play.google.com")
                }, 0
            )
            val has = try {
                ctx.packageManager.getPackageInfo("com.google.android.gms", 0); true
            } catch (_: Throwable) { false }
            if (has || r != null) "带 GMS（认证设备）" else "无 GMS"
        },
        webview = safe {
            val pi = ctx.packageManager.getPackageInfo("com.google.android.webview", 0)
            pi.versionName ?: "未知"
        },
        rootfs = safe {
            if (rootfsInstalled(ctx)) "已安装" else "未安装"
        },
        distro = safe {
            val f = File(ctx.filesDir, "rootfs/etc/zhengdao-rootfs.info")
            if (f.isFile) f.readLines().firstOrNull { it.startsWith("distro=") }
                ?.removePrefix("distro=")?.let { "Debian $it" } ?: "未知"
            else "未知"
        },
        proot = safe {
            val p = File(ctx.filesDir, "termux-proot/proot")
            if (p.isFile && p.canExecute()) "就绪（Termux fork）" else "未知"
        },
        agentList = safe {
            val a = AppState.agents(ctx).filter { it.installed }
            if (a.isEmpty()) "无" else a.joinToString("、") { it.name }
        },
        python = safe { envVersion(ctx, "python3 --version") ?: "未知" },
        node = safe { envVersion(ctx, "node --version") ?: "未知" },
        uv = safe { envVersion(ctx, "uv --version") ?: "未知" },
    )

    /** 从安装信息文件读取版本（不 spawn 进程，零开销）。 */
    private fun envVersion(ctx: Context, cmd: String): String? {
        val f = File(ctx.filesDir, "rootfs/etc/zhengdao-rootfs.info")
        if (!f.isFile) return null
        val map = f.readLines().associate {
            val p = it.split("=", limit = 2); if (p.size == 2) p[0] to p[1] else "" to ""
        }
        return when (cmd) {
            "python3 --version" -> map["python"]?.let { "Python $it" }
            "node --version" -> map["node_major"]?.let { "Node v$it.x" }
            else -> null
        }
    }

    /**
     * 目录占用统计（MB）。rootfs 内有为 proot 存储直通建的符号链接（sdcard/workspace 等），
     * 指向整个共享存储 —— 必须跳过符号链接子树，否则会把用户的照片视频全算进来。
     * 无权限目录（bind 挂载点为 0000 模式）跳过继续，整体失败返回 0。
     */
    fun dirSizeMb(dir: File): Long = try {
        var total = 0L
        java.nio.file.Files.walkFileTree(
            dir.toPath(),
            object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(
                    p: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult =
                    if (java.nio.file.Files.isSymbolicLink(p)) {
                        java.nio.file.FileVisitResult.SKIP_SUBTREE
                    } else java.nio.file.FileVisitResult.CONTINUE

                override fun visitFile(
                    p: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    p: java.nio.file.Path,
                    e: java.io.IOException,
                ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
            },
        )
        total / 1048576
    } catch (_: Throwable) { 0L }

    /** 共享存储是否可直读（legacy 视图设备为 true；安卓 16+ target 28 为 false）。 */
    fun sharedStorageReadable(): Boolean = try {
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
            File("/storage/emulated/0/Download").canRead()
    } catch (_: Throwable) { false }
}
