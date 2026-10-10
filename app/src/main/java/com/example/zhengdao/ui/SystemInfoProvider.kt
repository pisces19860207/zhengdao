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
 *
 * 展示原则（v1.1 修正）：这张表是给**非技术用户**看的"我的环境现在什么状态"，
 * 所以每一项要么给出确定值，要么如实说"未安装"，**能读到的项尽量给精确版本**。
 * 读不到的项宁可整行不显示（[Info.selinux]），也不写"未知"——"未知"读起来
 * 像 App 坏了一半，用户会以为环境没装好。
 */
object SystemInfoProvider {

    data class Info(
        // 系统与硬件
        val androidVersion: String, val apiLevel: String, val model: String,
        val manufacturer: String, val abi: String, val cores: String,
        val ramTotal: String, val ramAvail: String,
        val storageTotal: String, val storageAvail: String,
        val screen: String, val kernel: String,
        // 安全与运行环境（selinux 为 null = 本机读不到，展示时省略该行）
        val selinux: String?, val rooted: String, val gms: String, val webview: String,
        // 证道环境
        val rootfs: String, val distro: String, val proot: String,
        val agentList: String, val python: String, val node: String, val uv: String,
    ) {
        fun asText(): String {
            val out = mutableListOf<String>()
            out += "── 系统与硬件 ──"
            out += "Android 版本: $androidVersion (API $apiLevel)"
            out += "设备型号: $model"
            out += "制造商: $manufacturer"
            out += "CPU 架构: $abi"
            out += "核心数: $cores"
            out += "内存: $ramAvail 可用 / $ramTotal 总量"
            out += "存储(应用分区): $storageAvail 可用 / $storageTotal 总量"
            out += "屏幕: $screen"
            out += "内核: $kernel"
            out += "── 安全与运行环境 ──"
            selinux?.let { out += "SELinux: $it" }
            out += "Root: $rooted"
            out += "GMS: $gms"
            out += "WebView: $webview"
            out += "── 证道环境 ──"
            out += "RootFS: $rootfs"
            out += "发行版: $distro"
            out += "proot: $proot"
            out += "已装 Agent: $agentList"
            out += "Python: $python"
            out += "Node: $node"
            out += "uv: $uv"
            return out.joinToString("\n")
        }
    }

    private fun safe(f: () -> String): String = try { f() } catch (_: Throwable) { "未知" }

    private fun mb(bytes: Long): String = "${(bytes + 1048575) / 1048576} MB"

    fun collect(ctx: Context): Info {
        // 二进制版本探测带缓存，一次采集只探一遍。
        val runtime = probe(ctx)
        return Info(
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
        selinux = readSelinux(),
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
        // info 里存的是 debian-13.7，旧代码又拼了一次 "Debian "，
        // 真机上显示成 "Debian debian-13.7"。
        distro = envField(ctx, "distro")?.removePrefix("debian-")
            ?.let { "Debian $it" } ?: "未安装",
        proot = safe {
            val p = File(ctx.filesDir, "termux-proot/proot")
            if (p.isFile && p.canExecute()) "就绪（Termux fork）" else "未安装"
        },
        agentList = safe {
            val a = AppState.agents(ctx).filter { it.installed }
            if (a.isEmpty()) "无" else a.joinToString("、") { it.name }
        },
        // 注意别在这里再拼一遍 "Python "——展示行已经是 "Python: $python"，
        // 原先就渲染成了 "Python: Python 3.13"。
        python = envField(ctx, "python") ?: "未安装",
        // 环境包里的 info 只写了大版本（构建期常量）；真机上能从 node 二进制的
        // process.version 常量读到精确补丁号，读不到才退回大版本。
        node = runtime.node ?: envField(ctx, "node_major")?.let { "v$it" } ?: "未安装",
        // uv 有两个：rootfs 里的系统级二进制（上游 ELF，版本只能扫二进制，暂不取），
        // 以及 hermes 装在工作区的内嵌版——后者的版本号就在目录名里
        // （home/.hermes/tools/uv-0.12.3-linux-arm64），取它比笼统报"已安装"具体。
        uv = embeddedUvVersion(ctx)?.let { "$it（hermes 内嵌）" }
            ?: if (runtime.uvInstalled) "已安装" else "未安装",
        )
    }

    /**
     * hermes 把自带工具装在 `home/.hermes/tools/<name>-<ver>-<arch>/`，版本号在目录名里。
     * 注意这是**工作区里内嵌的那个 uv**，不是 rootfs 里的系统级 uv。
     */
    private fun embeddedUvVersion(ctx: Context): String? = try {
        File(ctx.filesDir, "home/.hermes/tools")
            .listFiles { f -> f.isDirectory && f.name.startsWith("uv-") }
            ?.mapNotNull { uvVersionFromDirName(it.name) }
            ?.maxOrNull()
    } catch (_: Throwable) { null }

    /** uv-0.12.3-linux-arm64 → 0.12.3；无版本段（如 uv-linux-arm64）返回 null。 */
    internal fun uvVersionFromDirName(name: String): String? =
        Regex("""^uv-([0-9][0-9.]*)-""").find(name)?.groupValues?.get(1)

    /** SELinux 模式；本机读不到返回 null（展示时整行省略，而不是渲染成一个像故障的"未知"）。 */
    private fun readSelinux(): String? = try {
        // Android 10+ 起第三方应用读不到 /sys/fs/selinux/enforce：SELinux 对 app 域直接
        // deny，真机（PGT-AN10 / Android 16）实测必得"未知"。它是内核安全策略，
        // 与用户的使用决策无关，读不到就不显示。
        val f = File("/sys/fs/selinux/enforce")
        if (f.canRead()) {
            if (f.readText().trim() == "1") "Enforcing" else "Permissive"
        } else null
    } catch (_: Throwable) { null }

    private fun rootfsInfo(ctx: Context) = File(ctx.filesDir, "rootfs/etc/zhengdao-rootfs.info")

    /** 读环境包构建时写入的 etc/zhengdao-rootfs.info（单行 key=value 表）。 */
    private fun envField(ctx: Context, key: String): String? = try {
        val f = rootfsInfo(ctx)
        if (f.isFile) {
            f.readLines().firstOrNull { it.startsWith("$key=") }
                ?.removePrefix("$key=")?.trim()?.takeIf { it.isNotEmpty() }
        } else null
    } catch (_: Throwable) { null }

    // ── 二进制版本探测（node 精确版本 / uv 是否就绪）────────────────────────
    // 注意别叫 Runtime——会遮蔽 java.lang.Runtime，上面读核心数就编不过了。
    private data class RuntimeProbe(val node: String?, val uvInstalled: Boolean)

    // 缓存键＝info 文件的 [mtime:size]：环境重装（文件被换掉）自动重探；否则每次
    // 采系统信息都去顺序读 150 MB 的 node 二进制是不可接受的。
    private var probeKey: String? = null
    private var probeCache: RuntimeProbe? = null

    private fun probe(ctx: Context): RuntimeProbe {
        val f = rootfsInfo(ctx)
        val key = "${f.lastModified()}:${f.length()}"
        probeCache?.let { if (probeKey == key) return it }
        val v = RuntimeProbe(
            // 用 info 里的主版本做前缀约束：node 二进制里还有 v8、openssl 等一堆
            // 版本串，泛匹配会先撞上它们。
            node = scanNodeVersion(
                File(ctx.filesDir, "rootfs/usr/bin/node"),
                envField(ctx, "node_major"),
            ),
            uvInstalled = File(ctx.filesDir, "rootfs/usr/local/bin/uv")
                .let { it.isFile && it.canExecute() },
        )
        probeKey = key
        probeCache = v
        return v
    }

    /**
     * 在 node 二进制里找第一个 `v<major>.<minor>.<patch>`（major 取自 info 文件）。
     * node 把 process.version 编译成了只读数据段里的一串 ASCII（实测 v26.10.0 落在
     * 149 MB 文件的 46 MB 处），没有任何小文件记录它，只能顺序扫。
     * 用 indexOf 定位前缀 + 手工吃数字与点，避免整文件正则的分配与回溯；命中即停。
     */
    internal fun scanNodeVersion(file: File, major: String?): String? = try {
        if (major.isNullOrBlank() || !file.isFile) null
        else file.inputStream().buffered(1 shl 18).use { ins ->
            val needle = "v$major."
            val buf = ByteArray(1 shl 18)
            // 跨块保留末尾片段，防止版本串正好被块边界切成两半
            val keep = needle.length + 12
            var carry = ""
            var found: String? = null
            while (found == null) {
                val n = ins.read(buf)
                if (n <= 0) break
                val s = carry + String(buf, 0, n, Charsets.ISO_8859_1)
                var idx = s.indexOf(needle)
                while (idx >= 0) {
                    var j = idx + 1
                    var dots = 0
                    var seg = 0
                    while (j < s.length) {
                        val c = s[j]
                        if (c in '0'..'9') { seg++; j++ }
                        else if (c == '.' && seg > 0 && dots < 2) { dots++; seg = 0; j++ }
                        else break
                    }
                    if (dots == 2 && seg > 0) {
                        found = s.substring(idx, j)
                        break
                    }
                    idx = s.indexOf(needle, idx + 1)
                }
                carry = if (s.length > keep) s.substring(s.length - keep) else s
            }
            found
        }
    } catch (_: Throwable) { null }

    /**
     * 目录占用统计（MB）。rootfs 内有为 proot 存储直通建的符号链接（sdcard/workspace 等），
     * 指向整个共享存储 —— 必须跳过符号链接子树，否则会把用户的照片视频全算进来。
     * 无权限目录（bind 挂载点为 0000 模式）跳过继续，整体失败返回 0。
     *
     * **下沉形态（v2.0 R3，见 `docs/证道-Rust化余地审计-2026-10-09.md` 候选 A）**：
     * 先走 Rust（`CoreNative.dirSizeBytes`，输入路径、输出一个字节数，边界只跨一次），
     * 拿不到再回退下面这段 Java `walkFileTree`——两条路径语义逐条对齐（跳过软链、
     * 只累加普通文件、无权限静默跳过），且可用 [com.example.zhengdao.rust.CoreNative.isRustAvailable]
     * 与同目录结果对拍。**回退纪律**：Rust 不可用 = 功能降级，不中断。
     */
    fun dirSizeMb(dir: File): Long {
        val bytes = com.example.zhengdao.rust.CoreNative.dirSizeBytes(dir)
            ?: javaDirSizeBytes(dir)
        return bytes / 1048576
    }

    /**
     * [dirSizeMb] 的 Java 回退实现（Rust 不可用或 JNI 失败时走这里）。
     *
     * 可见性是 `internal` 而非 `private`：它是真机对拍测试
     * `app/src/androidTest/java/com/example/zhengdao/rust/DirSizeParityTest.kt`
     * 的**基准实现**——`.so` 是 Android arm64 库，PC 上的 JVM 加载不了，
     * 只有仪器测试能同时跑到两条路（E-079）。
     */
    internal fun javaDirSizeBytes(dir: File): Long = try {
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
        total
    } catch (_: Throwable) { 0L }

    /** 共享存储是否可直读（legacy 视图设备为 true；安卓 16+ target 28 为 false）。 */
    fun sharedStorageReadable(): Boolean = try {
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
            File("/storage/emulated/0/Download").canRead()
    } catch (_: Throwable) { false }
}
