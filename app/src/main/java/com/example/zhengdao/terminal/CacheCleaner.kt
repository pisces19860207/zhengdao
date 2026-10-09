// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：npm/uv/apt 官方 CLI 缓存清理参数。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 缓存清理（P4，2026-10-06；**二档 2026-10-07 落地**；**「Agent 包缓存」直删 2026-10-09 落地**）。
 *
 * 三档白名单**写死**（用户定稿：禁止从配置读）。
 *
 * | 档位 | 清理项 | 风险 |
 * |---|---|---|
 * | 一档（guest 命令） | npm / uv / pip / apt 缓存、Agent 升级残留 | 极低 |
 * | 二档（宿主侧） | `rootfs/tmp` 里有明确指纹的残留：`.<16hex>-<8digits>.so`、`mat-debug-*.log` | 低（下次启动慢几秒） |
 * | Agent 包缓存（宿主侧直删） | `cache/{npm,uv,pip}`、`Download/证道/opencode`、`home/.npm`、`home/.cache/{uv,pip}`、`home/.hermes/cache`、`oc/xdg/cache` | 低（下次安装/运行会重下，随时可清） |
 * | 三档（永不清） | `.hermes/tools` / `rootfs` 系统层 / home 用户数据 | 删了功能坏 |
 *
 * ⚠️「Agent 包缓存」这档**不是三档**（三档是"永不清"那条白名单）；它和一档的区别只是
 * 执行位置：一档必须在 guest 里跑官方 CLI，这档在宿主侧直接删普通目录，不用开终端。
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
     * 一档清理项：`(显示名, 命令)`。
     *
     * ⚠️ **这是唯一的命令清单**：App 注入执行的 [guestCommand] 和终端里那条
     * `zzclean` 命令（[zzcleanScript]，2026-10-08 新增）都从它生成。
     * 两处各自维护一份必然会漂移——本项目已经因为"两份实现同一件事"踩过坑
     * （插件开关曾写在没人读的文件上，见 PluginManager 类注释）。
     */
    internal val TIER1_ACTIONS: List<Pair<String, String>> = listOf(
        "npm 缓存" to "npm cache clean --force 2>/dev/null",
        "uv 缓存" to "uv cache prune 2>/dev/null",
        // pip 缓存（2026-10-08 补，跟随 E-036）：设置页面板自 E-036 起**已经量** `~/.cache/pip`，
        // 而一档命令里没有它 —— 面板上看到一个清不掉的数字，就是"账本和实物对不上"。
        // `uv cache prune` 只管 uv 自己的缓存、不管 pip 的。没装 pip 时这条是空操作
        // （错误被吞，`;` 链也不会中断）。**这条的去留由 DSH 定**。
        "pip 缓存" to "pip cache purge 2>/dev/null",
        "apt 缓存" to "apt-get clean 2>/dev/null; apt-get autoclean 2>/dev/null",
        // ⚠️ 不再清 /root/.opencode-mem/...（v1.1.1 阶段 3.4）：opencode-mem 插件
        //    2026-10-06 已摘除（LegacyMemPlugin 幂等清理配置），该路径在真机上不存在，
        //    留着会让人误以为还在清它。
        "Agent 升级残留" to "rm -rf /root/.hermes/tools/*.tmp 2>/dev/null",
    )

    /**
     * guest 视角的缓存路径 → 显示名（`zzclean --status` 用）。
     *
     * 映射依据 `ProotLauncher` 的启动参数：`-b <files>/home:/root`（宿主 home 即 guest `/root`）、
     * rootfs 本身就是 guest 的 `/`。**不含「安装包缓存」**：`cacheDir/rootfs-cache` 只存在于
     * 宿主侧，guest 里没有对应路径，列出来只会是假的。
     */
    internal val GUEST_CACHE_PATHS: List<Pair<String, String>> = listOf(
        "npm" to "/root/.npm/_cacache",
        "uv（默认路径）" to "/root/.cache/uv",
        "uv（hermes 重定位）" to "/root/.hermes/cache/uv",
        "pip" to "/root/.cache/pip",
        "apt archives" to "/var/cache/apt/archives",
        "apt lists" to "/var/lib/apt/lists",
    )

    /**
     * 生成一档清理命令（在 guest 终端里执行的串；官方命令优先）。
     * 由 TerminalActivity 以 autocmd 注入执行——输出可见、可中断。
     * 二档不在这里：它走 [cleanTempFiles] 的宿主侧删除（原因见类注释）。
     */
    fun guestCommand(): String = buildList {
        TIER1_ACTIONS.forEach { (name, cmd) ->
            add("echo '[清理] $name…'")
            add(cmd)
        }
        add("echo '[清理] 完成（三档白名单：tools/rootfs/home 用户数据永不清）'")
    }.joinToString("; ")

    /**
     * 终端内自清理命令 `zzclean` 的**脚本正文**（写入 guest 的 `/usr/local/bin/zzclean`）。
     *
     * 为什么要有它（用户 2026-10-08 原话："终端有没有缓存机制，可不可以做一个终端自己清理
     * 缓存的方法"）：清理能力本来就有，但入口只在**设置页**——人已经在终端里的时候，
     * 既没有命令可用，也看不到占用，等于要退出终端才能清。
     *
     * **边界与三档白名单完全一致**（并写进脚本自身的 `--help`，让用户在终端里就能读到）：
     *  - 只做一档的官方 CLI 调用；
     *  - 绝不碰 `.hermes/tools`、`rootfs` 系统层、home 用户数据；
     *  - **不删 `rootfs/tmp` 的残留**——那是二档，指纹判定在宿主侧（`isStaleTempName`），
     *    guest 里看不到规则，照猜着删就是"按通配删目录"。
     */
    fun zzcleanScript(): String {
        // ⚠️ 下面所有 shell 的 `$` 都要写成 `${'$'}`：Kotlin 会把它当字符串模板解析，
        //    而这里要的是**字面量**（脚本里的 `$(du ...)`、`$1`、`$0`）。
        //    裸写 `$(` 或 `${1:-}` 直接编译不过——这是本函数最容易踩的坑。
        val status = GUEST_CACHE_PATHS.joinToString("\n") { (name, path) ->
            // 大小在前、名字在后：printf 的 %-Ns 按**字节**算宽度，中文标签（占 2 格/字）
            // 放在被补齐的那一列会参差不齐；大小是 ASCII，补起来才真的齐。
            "  if [ -d \"$path\" ]; then printf '%-8s %s\\n' " +
                "\"${'$'}(du -sh \"$path\" 2>/dev/null | cut -f1)\" \"$name\"; " +
                "else printf '%-8s %s\\n' '-' \"$name（不存在）\"; fi"
        }
        val clean = TIER1_ACTIONS.joinToString("\n") { (name, cmd) ->
            "  echo \"[清理] $name…\"\n  $cmd"
        }
        return """
#!/bin/sh
# zhengdao zzclean —— 终端内缓存清理（由 App 在每次启动会话前写入；手改会在下次启动被覆盖）
#
# 用法：
#   zzclean            清理一档缓存（npm / uv / pip / apt 官方命令 + Agent 升级残留）
#   zzclean --status   只看占用，不删任何东西
#
# 边界（与 App「设置 → 缓存清理」同一套三档白名单）：
#   一档 = 官方 CLI 缓存，可清；
#   二档 = rootfs/tmp 里的残留，**本命令不碰**——指纹判定在 App 侧，guest 里看不到规则；
#   三档 = .hermes/tools、rootfs 系统层、home 用户数据，**永远不清**。
set -u

status() {
  echo "=== 证道缓存占用（只读，未删任何文件）==="
$status
  echo "（不含「安装包缓存」：它只存在于 App 侧，环境里没有这个路径）"
}

clean() {
  echo "=== 开始清理一档缓存 ==="
$clean
  echo "[清理] 完成（三档白名单：tools / rootfs 系统层 / home 用户数据 永不清）"
}

case "${'$'}{1:-}" in
  -h|--help)   sed -n '2,/^set -u/{/^set -u/d;p}' "${'$'}0" ;;
  -s|--status) status ;;
  "")          clean ;;
  *)           echo "未知参数：${'$'}1（用 zzclean --help 看用法）"; exit 2 ;;
esac
""".trimStart()
    }


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

    // ── Agent 的「包缓存」（宿主侧直接删，不用开终端）────────────────────────

    /**
     * Agent 包缓存清理项（2026-10-09 新增，用户原话：「给那个什么多一个清理 agent 的缓存包的按钮」）。
     *
     * 为什么要单独有一条（与一档的分工）：
     * - **一档**是"在终端里跑官方 CLI"（`npm cache clean --force` / `uv cache prune` / `pip cache
     *   purge` / `apt-get clean`）——官方口径、输出可见，代价是**必须开一次终端**；
     * - **二档**只管 `rootfs/tmp` 里带命名指纹的陈旧残留；
     * - 而 Agent 自己下载的东西（opencode 安装包、npm/uv/pip 的包缓存、hermes 自己的 cache）
     *   全都躺在**宿主侧看得见的普通目录**里，删它们不涉及 guest 的运行状态，
     *   所以可以做成一个按钮直接删，不必先开终端。真机上这几项加起来是 GB 级的
     *   （`~/.hermes` 4.4 GB 里 cache 一项 253 MB，npm/uv/pip 公共缓存另计）。
     *
     * **边界仍是那条「永不清」白名单（`tools` / `rootfs` 系统层 / `home` 用户数据），宁可少删**：只删"能重新下载 / 重新生成"的缓存 ——
     * 绝不碰 `.hermes/tools`（装好的 Python/Node 工具链，删了 hermes 直接废）、
     * `.hermes/installs`（依赖环境与 `MEMORY.md`/`USER.md` 那类记忆）、`.hermes/hermes-agent`
     * 本体、`rootfs/` 系统层、`home/` 下的配置。
     *
     * 纯函数（不吃 Context），路径全由调用方给出，便于单测锁住"不许指到别处"。
     */
    internal fun agentCacheTargets(
        filesDir: File,
        publicCacheRoot: File,
        publicRoot: File,
    ): List<Pair<String, File>> = listOf(
        // 公共区（Download/证道/cache/<kind>，ProotLauncher 把它们 bind 进 guest 的 ~/.npm 等）
        "npm 包缓存" to File(publicCacheRoot, "npm"),
        "uv 包缓存" to File(publicCacheRoot, "uv"),
        "pip 包缓存" to File(publicCacheRoot, "pip"),
        // Download/证道/opencode：内置 OpenCode 的安装包（删了下次安装会重下）
        "opencode 安装包缓存" to File(publicRoot, "opencode"),
        // 私有 home：没启动过终端、或只用私有模式时，缓存落在这里
        "npm 缓存（私有）" to File(filesDir, "home/.npm/_cacache"),
        "uv 缓存（私有）" to File(filesDir, "home/.cache/uv"),
        "pip 缓存（私有）" to File(filesDir, "home/.cache/pip"),
        // hermes 把 uv 缓存重定位到这儿（真机 253 MB 的大头）
        "hermes 缓存" to File(filesDir, "home/.hermes/cache"),
        // 太极（App 内置 OpenCode）自己的 XDG cache
        "太极缓存" to File(filesDir, "oc/xdg/cache"),
    )

    private fun agentCacheTargetsFor(ctx: Context): List<Pair<String, File>> = runCatching {
        agentCacheTargets(ctx.filesDir, Store.cacheRoot(ctx), Store.root(ctx))
    }.getOrDefault(emptyList())

    /** Agent 包缓存各项占用（MB），**只列真的存在的**，供确认弹窗逐条展示"将要删什么"。 */
    fun agentCacheMeasure(ctx: Context): List<Pair<String, Long>> =
        agentCacheTargetsFor(ctx).map { (name, dir) -> name to dirSizeMb(dir) }.filter { it.second > 0 }

    /** Agent 包缓存合计（MB）。 */
    fun agentCacheMb(ctx: Context): Long = agentCacheMeasure(ctx).sumOf { it.second }

    /**
     * 执行清理：删掉 [agentCacheTargets] 里的目录，返回实际释放的字节数。
     *
     * ⚠️ **不因为"终端会话还活着"就拒绝执行**（真机教训，2026-10-09）：会话是常驻的
     * （tmux + 后台 ptmx），拿 `busy()` 当门就等于这个按钮几乎永远点不动 —— 第一次实现
     * 正是这样，真机上点了没反应。改成**由 UI 提示风险、用户自己决定**：
     * 可能被影响的是"此刻正在装依赖"的那次安装（缓存被删会重下），已经装好的东西不受影响。
     */
    fun cleanAgentCaches(ctx: Context): Long {
        val roots = runCatching { listOf(ctx.filesDir, Store.cacheRoot(ctx), Store.root(ctx)) }
            .getOrDefault(listOf(ctx.filesDir))
        var freed = 0L
        var count = 0
        agentCacheTargetsFor(ctx).forEach { (_, dir) ->
            // 双保险：目标必须在白名单根之下（配置/映射写错也不至于删到系统路径）
            val ap = runCatching { dir.canonicalPath }.getOrNull() ?: return@forEach
            val ok = roots.any { r ->
                runCatching { ap.startsWith(r.canonicalPath + File.separator) }.getOrDefault(false)
            }
            if (!ok) return@forEach
            val before = fileLengths(dir)
            if (deleteTree(dir)) {
                freed += before
                count++
            }
        }
        if (count > 0) RunLog.log("缓存清理(Agent 包缓存): 删除 $count 项 Agent 包缓存，释放 ${bytesToMb(freed)}MB")
        return freed
    }

    /**
     * 递归删除一棵目录树（返回"是否删掉了东西"）。
     *
     * **绝不跟随软链**：`walkFileTree` 不传 `FOLLOW_LINKS` 时软链按普通文件处理，
     * 删掉的是链接自身——uv 缓存里 `archive-v0/<hash>` 全是指向别处的软链，
     * 跟着走就可能删到白名单之外的东西。删不掉的部分静默跳过（宁可少报，也不抛异常）。
     */
    internal fun deleteTree(root: File): Boolean {
        if (!root.exists()) return false
        var any = false
        try {
            java.nio.file.Files.walkFileTree(
                root.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (java.nio.file.Files.deleteIfExists(file)) any = true
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        exc: java.io.IOException?,
                    ): java.nio.file.FileVisitResult {
                        if (java.nio.file.Files.deleteIfExists(dir)) any = true
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Throwable) {
            // 删不掉就算没删
        }
        return any
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
