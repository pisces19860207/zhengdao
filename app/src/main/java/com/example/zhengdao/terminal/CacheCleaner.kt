// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：npm/uv/apt 官方 CLI 缓存清理参数。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 缓存清理（P4，2026-10-06；**二档 2026-10-07 落地**）。
 *
 * 三档白名单**写死**（用户定稿：禁止从配置读）。
 *
 * | 档位 | 清理项 | 风险 |
 * |---|---|---|
 * | 一档（guest 命令） | npm / uv / apt 缓存、Agent 升级残留 | 极低 |
 * | 二档（宿主侧） | `rootfs/tmp` 里有明确指纹的残留：`.<16hex>-<8digits>.so`、`mat-debug-*.log` | 低（下次启动慢几秒） |
 * | 三档（永不清） | `.hermes/tools` / `rootfs` 系统层 / home 用户数据 | 删了功能坏 |
 *
 * 二档为什么走宿主侧而不是 guest 命令：rootfs 就是宿主上的普通目录——guest 的 `/tmp`
 * 即 `files/rootfs/tmp`（ProotLauncher 写启动横幅用的就是这条映射，见
 * `File(rootfsDir, "tmp/.zhengdao-banner-pending")`）。宿主侧删除**不需要终端会话活着**
 * （guest 命令路线要求会话在跑，会话没起就只能干看着）、模式串能精确判定、还能进单测；
 * guest 命令里因此只留一档的官方 CLI 调用。
 *
 * 自动清理：会话启动后台检测 >500MB 才清；检测到安装进程跳过；静默通知。
 *
 * 路径探测的**两个真机修正**见 [probePaths] 的注释。
 */
object CacheCleaner {

    private const val AUTO_THRESHOLD_MB = 500L

    /** 二档的年龄门槛：比这更新的临时文件不动（可能正被运行中的进程持有）。 */
    internal const val TEMP_MIN_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * 每个「缓存项」由哪几个目录构成（**多候选求和**，不再只认一个路径）。
     *
     * 这里修的是三个真机（PGT-AN10 / Android 16）实测出来的路径错误：
     * - **uv**：hermes 把 uv 缓存从 `~/.cache/uv` 重定位到了 `~/.hermes/cache/uv`。
     *   旧实现只探测前者，真机实测 **1 MB**（真身 **254 MB**）——面板等于没测到这一项。
     * - **apt**：真正占地的是 `var/lib/apt/lists`（索引缓存，实测 **88 MB**）；
     *   `var/cache/apt/archives` 在 apt clean 之后基本是空的（实测 **1 MB**）。
     * - **安装包缓存**（2026-10-08 修，用户报「面板显示 0，可 Download/证道/rootfs 里明明
     *   躺着 312 MB」）：旧实现量的是**私有兜底** `cacheDir/rootfs-cache`，而 App 自己下载的
     *   包早就落在公共区 `Download/证道/rootfs/`。真机实测面板 **0 MB vs 实际 378 MB**
     *   （环境包 312 MB + opencode 包 66 MB）——正是"账本和实物对不上"。
     *
     * 两地都列出来、存在即计入，上游再换路径也不用改代码。旧实现只测到 91 MB，
     * 离 500 MB 的自动清理阈值差得远，那条通知等于永远不响。
     */
    internal fun probePaths(filesDir: File, cacheDir: File): List<Pair<String, List<File>>> = listOf(
        "npm 缓存" to listOf(File(filesDir, "home/.npm/_cacache")),
        "uv 缓存" to listOf(
            File(filesDir, "home/.cache/uv"),
            File(filesDir, "home/.hermes/cache/uv"),
        ),
        "apt 缓存" to listOf(
            File(filesDir, "rootfs/var/cache/apt/archives"),
            File(filesDir, "rootfs/var/lib/apt/lists"),
        ),
        // ⚠️ 键名被卸载对话框读取（HomeScreen「安装包缓存」），改名要同步改调用点
        "安装包缓存" to listOf(File(cacheDir, "rootfs-cache")),
    )

    /**
     * 各缓存项的大小（MB）。
     *
     * 2026-10-08 起与 [probePaths] 分工：**这里量的是"真身"**——
     * - npm / uv / pip 的新家是 `Download/证道/cache/<kind>`（ProotLauncher 把它们 bind
     *   进 guest 的 `/root/.npm` 等路径），ProotLauncher 的搬家是**搬**不是拷，
     *   所以公共区与私有 home 不会双算；私有那几项保留，是为了覆盖"仅私有模式"
     *   与"还没启动过一次终端"的中间态；
     * - 安装包缓存量的是 [RootfsCache.dir]（公共区 `Download/证道/rootfs`，
     *   无存储权限时自动回落到私有）+ 内置 opencode 包所在目录（`Download/证道/opencode`）。
     */
    fun measure(ctx: Context): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        fun sum(vararg dirs: File?): Long = dirs.filterNotNull().sumOf { dirSizeMb(it) }
        fun pub(kind: String): File? = runCatching { Store.cacheDirPath(ctx, kind) }.getOrNull()
        out["npm 缓存"] = sum(File(ctx.filesDir, "home/.npm/_cacache"), pub("npm"))
        out["uv 缓存"] = sum(
            File(ctx.filesDir, "home/.cache/uv"),
            File(ctx.filesDir, "home/.hermes/cache/uv"),
            pub("uv"),
        )
        out["pip 缓存"] = sum(File(ctx.filesDir, "home/.cache/pip"), pub("pip"))
        out["apt 缓存"] = sum(
            File(ctx.filesDir, "rootfs/var/cache/apt/archives"),
            File(ctx.filesDir, "rootfs/var/lib/apt/lists"),
        )
        out["安装包缓存"] = sum(
            runCatching { com.example.zhengdao.rootfs.RootfsCache.dir(ctx) }.getOrNull(),
            runCatching { File(Store.root(ctx), "opencode") }.getOrNull(),
        )
        // 二档报的是"真正会被删掉的量"，**不是**整个 /tmp 目录——tmux socket、
        // V8 编译缓存、锁文件、opencode 子目录都在里面，那些不归清理管，算进去就是虚报。
        out["临时文件"] = bytesToMb(staleTempBytes(ctx.filesDir, System.currentTimeMillis()))
        return out
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
     * 二档不在这里：它走 [cleanTempFiles] 的宿主侧删除（原因见类注释）。
     */
    fun guestCommand(): String = listOf(
        "echo '[清理] npm 缓存…'", "npm cache clean --force 2>/dev/null",
        "echo '[清理] uv 缓存…'", "uv cache prune 2>/dev/null",
        "echo '[清理] apt 缓存…'", "apt-get clean 2>/dev/null; apt-get autoclean 2>/dev/null",
        // ⚠️ 不再清 /root/.opencode-mem/...（v1.1.1 阶段 3.4）：opencode-mem 插件
        //    2026-10-06 已摘除（LegacyMemPlugin 幂等清理配置），该路径在真机上不存在，
        //    留着会让人误以为还在清它。
        "echo '[清理] Agent 升级残留…'", "rm -rf /root/.hermes/tools/*.tmp 2>/dev/null",
        "echo '[清理] 完成（三档白名单：tools/rootfs/home 用户数据永不清）'",
    ).joinToString("; ")

    // ── 二档：rootfs/tmp 的残留 ─────────────────────────────────────────────

    /**
     * 二档可清文件的**文件名判定**（纯函数，单独锁进单测）。
     *
     * 只认两类有明确指纹的残留，**绝不按通配删整个 /tmp**：
     * - `.<16 位小写十六进制>-<8 位数字>.so`：占大头的一类。真机实测 `files/rootfs/tmp`
     *   277 MB 里有 271 MB 是它——32 个文件，同族之间**逐字节相同**（18 份 4.7 MB +
     *   14 份 13.3 MB，md5 各自一致），ELF arm64 stripped。产生方在 guest 运行时，
     *   无法从本仓库溯源，所以只按"名字指纹 + 年龄"这两条保守依据判定。
     * - `mat-debug-<pid>.log`：0 字节空日志。
     *
     * 反例（**必须保留**，单测里逐条锁住）：`tmux-0`（tmux socket 目录，删了 session 断）、
     * `.ses`（会话标记）、`node-compile-cache`（V8 官方编译缓存目录）、各类 `*.lock`、
     * `opencode` 子目录、`.zhengdao-banner-pending`（启动横幅）。
     */
    internal fun isStaleTempName(name: String): Boolean {
        if (name.startsWith("mat-debug-") && name.endsWith(".log")) return true
        if (!name.startsWith(".") || !name.endsWith(".so")) return false
        val core = name.substring(1, name.length - 3)
        if (core.length != 25 || core[16] != '-') return false
        val hex = core.substring(0, 16)
        val index = core.substring(17)
        return hex.all { it in '0'..'9' || it in 'a'..'f' } && index.all { it in '0'..'9' }
    }

    /** [tmp] 下命中的残留文件（**纯文件系统函数**，不依赖 Android Context，可直接单测）。 */
    internal fun staleTempFilesIn(
        tmp: File,
        now: Long,
        minAgeMs: Long = TEMP_MIN_AGE_MS,
    ): List<File> {
        val list = tmp.listFiles() ?: return emptyList()
        return list.filter {
            it.isFile && isStaleTempName(it.name) && now - it.lastModified() >= minAgeMs
        }
    }

    internal fun staleTempFiles(filesDir: File, now: Long): List<File> =
        staleTempFilesIn(File(filesDir, "rootfs/tmp"), now)

    internal fun staleTempBytes(filesDir: File, now: Long): Long =
        staleTempFiles(filesDir, now).sumOf { it.length() }

    /**
     * 执行二档清理：删掉命中的残留文件，返回**实际**释放的字节数。
     * 静默失败（删不掉算 0），绝不抛异常。不碰未命中模式的文件。
     */
    fun cleanTempFiles(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val tmp = File(ctx.filesDir, "rootfs/tmp")
        var freed = 0L
        var count = 0
        staleTempFilesIn(tmp, now).forEach { f ->
            val n = f.length()
            if (f.delete()) {
                freed += n
                count++
            }
        }
        if (count > 0) RunLog.log("缓存清理(二档): 删除 $count 个残留文件，释放 ${bytesToMb(freed)}MB")
        return freed
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    internal fun bytesToMb(bytes: Long): Long = bytes / 1048576

    /**
     * 递归求「真实文件长度之和」（**不跟进符号链接**）。
     *
     * 这里修的是一个真机才暴露出来的虚报：`walkTopDown()` 会跟进"指向目录的软链"，
     * 而 uv 的缓存目录里 `archive-v0/<hash>` 全是指向 `wheels-v6` 那类目录的软链——
     * 同一份数据被数两遍。实测（PGT-AN10）：`home/.hermes/cache/uv` 的真实长度和是
     * **232.8 MB**，面板却报 **476 MB**（≈233×2）。旧实现只测 `home/.cache/uv`（1 MB），
     * 这个问题一直藏着看不见；这次把 uv 指向了真身，它才浮出来。
     *
     * 软链一律跳过（含指向文件的软链），因此结果是"实际占多少"的下界而不是上界——
     * 对一个展示给用户看的占用数字来说，宁可少报也不虚报。
     */
    internal fun fileLengths(dir: File): Long {
        val entries = try {
            dir.listFiles()
        } catch (_: Throwable) {
            null
        } ?: return 0L
        var total = 0L
        for (f in entries) {
            // 软链判定读失败时按"是软链"处理（跳过）：宁可少报也不虚报。
            // 用 nio 的判定——java.io.File 没有 isSymbolicLink（minSdk 36，nio.file 可用）。
            if (runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)) continue
            total += try {
                if (f.isDirectory) fileLengths(f) else f.length()
            } catch (_: Throwable) {
                0L
            }
        }
        return total
    }

    /** 目录大小（MB）；不存在返回 0。 */
    private fun dirSizeMb(dir: File): Long =
        if (dir.isDirectory) bytesToMb(fileLengths(dir)) else 0L

    /** 自动清理触发（SessionService 定时器调用）：超阈值 → 发通知提示（不静默执行删除，用户点通知进设置手动清——v1 稳妥版）。 */
    fun maybeNotify(ctx: Context, notify: (String, String) -> Unit) {
        val (needed, total) = autoCleanNeeded(ctx)
        if (needed) {
            notify("缓存占用 ${total}MB", "点此进入设置清理（不会删除任何用户数据）")
            RunLog.log("缓存自动检测: ${total}MB 超阈值，已通知")
        }
    }
}
