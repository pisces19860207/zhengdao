// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：Android 官方文档（共享存储访问、应用外部目录语义）、java.io.File 语义。
package com.example.zhengdao.core

// 第二百四十批（抽 :core）可见性说明：`isLegalKind` / `isSharedPath` 原为 `internal`，现放宽为
// `public`：Kotlin 的 `internal` 是模块级的，而它们的单测留在 `:app`。两者都是纯判据，
// 放宽对行为零影响。

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * 证道**目录真源**（第二百四十批：终端解耦第一刀，纯搬家）。
 *
 * 本文件由三份历史真源**逐字合并**而来，路径一个字节都没动：
 * 1. `terminal/Store`（公共存放区 `Download/证道/`）—— 见 §A；
 * 2. `terminal/Workspace`（工作区解析器）—— 见 §B；
 * 3. 资料库的目录名与私有原件区（原 `baopu/kernel/BaopuKernel.Data` 的两个常量、
 *    `baopu/library/LibraryPaths.rawDirUnder`）—— 见 §C。
 *
 * 为什么合到一个 object：这三份都是「同一个问题：证道的目录在哪」，以前分散在
 * terminal / baopu 两个包里，于是 `KnowledgeBase`（要用工作区与资料库目录）只能反向
 * import 抱朴；现在它们下沉到 :core，抱朴与资料库都**向下**依赖本文件。
 *
 * 纪律：
 * - **路径与键名一个字节都不许改**（改名 = 用户数据失联）；
 * - 本文件不许 import `com.termux.**` / `com.example.zhengdao.terminal.**` /
 *   `com.example.zhengdao.baopu.**`（门禁 `CoreBoundaryGateTest`）。
 */
object ZhengdaoDirs {

    // ══════════════════════════════════════════════════════════════════════
    // §A 公共存放区（`Download/证道/` 下的固定子目录）——原 `terminal/Store`
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 公共存放区（`Download/证道/` 下的固定子目录）——**唯一真相源**。
     *
     * 用户 2026-10-08 定稿的诉求原话：
     * 「还有运行日志，错误日志，下载的东西都放到 download 证道 文件夹里，包括终端里下载的
     *   agent 的安装主程序，重新装 APP 的话也要像装环境一样的，自己就瞬间装好了」
     *
     * 落点约定（与已有的 `rootfs/`、`opencode/` 并列，**全英文小写**——guest 内 bash 路径
     * 不需要转义，也和前两者风格一致）：
     *
     * | 目录 | 装什么 | 谁写 |
     * |---|---|---|
     * | `logs/`   | 运行日志、错误日志 | [RunLog] |
     * | `cache/`  | `npm` / `uv` / `pip` 的**包缓存**（Agent 重装时的真正大头） | guest 内进程，经 proot bind 落在公共区 |
     * | `agents/` | Agent 安装脚本（`scripts/`） | AgentInstaller |
     *
     * 三条边界（用户已拍板）：
     * 1. **只搬缓存与安装包**：`~/.claude`、`~/.hermes` 里的 API key / 配置**留在私有 home**，
     *    绝不进公共目录——公共目录任何有存储权限的 App 都能读，也可能被云备份带走。
     * 2. **不跟随工作区**：宿主侧根目录恒为 [PUBLIC_ROOT]（`Download/证道`）。工作区是 Agent
     *    的产出目录（可以是用户的内容目录，如自定义的 `Download/男性`），App 自己的日志 /
     *    缓存 / 脚本账本不该混进去，也不该因为用户换工作区就失联。没存储权限 / 「仅私有」
     *    模式时整体退回应用私有目录（[privateRoot]）。
     * 3. **私有兜底永远可写**：公共目录建不出来时调用方退回私有目录（见各调用点），
     *    基础功能不因存储权限缺失而断。
     *
     * 迁移工具 [adoptDir] / [adoptFile] 是**幂等的逐条目搬家**：源目录里目标已同名的条目跳过，
     * 其余搬过去（`renameTo` 失败退回复制后删源——私有 `files/` 与公共 `/sdcard` 可能不同
     * 文件系统）。搬完源目录空了就删掉。因此可以在每次启动时无脑调用：第一次真搬，之后什么都不做。
     */

    const val DIR_LOGS = "logs"
    const val DIR_CACHE = "cache"
    const val DIR_AGENTS = "agents"

    /** Agent 安装脚本在公共区的子目录（`Download/证道/agents/scripts/`）。 */
    const val SUB_SCRIPTS = "scripts"

    /**
     * 公共区在手机共享存储上的固定位置（与 `RootfsCache` 的安装包目录同一处）。
     *
     * **刻意不跟随工作区**（2026-10-08 用户拍板、真机验证后修正）：用户把工作区设成了
     * 自定义目录（`Download/男性` = 他的小说工程），一开始这里跟着工作区走，于是日志、
     * 缓存、Agent 脚本全落进了那个内容目录。但那三样是"App 自己的东西"，放工作区会
     * ① 把几百 MB 包缓存和诊断日志倒进用户的内容目录；② 用户换个工作区就全部失联
     * （缓存要重下、账本看不到"装过什么"，正是"重装 App 秒装好"最需要的那份数据）。
     * 工作区只管 Agent 的**产出**（`/workspace` bind）；App 自己的东西一律放这里，
     * 与安装包（`rootfs/`、`opencode/`）待在一起。
     */
    const val PUBLIC_ROOT = "/storage/emulated/0/Download/证道"

    /** guest 侧的公共区挂载点（ProotLauncher 把 [root] bind 到这里）。 */
    const val GUEST_ROOT = "/opt/zhengdao"

    /**
     * guest 侧看到的 Agent 安装脚本**缓存**目录（`/opt/zhengdao/agents/scripts`）。
     *
     * ⚠️ 这是**缓存**，不是执行源（P3-2，2026-10-10）：它在共享存储里，任何拿到"所有文件
     * 访问权限"的 App 都能改它。安装脚本一律**复制到私有区再执行**，见
     * [GUEST_PRIVATE_SCRIPTS_DIR] 与 `com.example.zhengdao.ui.AgentInstaller`。
     */
    const val GUEST_SCRIPTS_DIR = "$GUEST_ROOT/$DIR_AGENTS/$SUB_SCRIPTS"

    /**
     * 安装脚本的**执行副本**目录（P3-2，2026-10-10）——guest 侧路径 `~/.zhengdao/scripts`。
     *
     * 装 Agent 时由宿主侧把**刚下载并核对过字节**的脚本复制到这里（宿主侧 = [hostPrivateScriptsDir]），
     * 终端里执行的是这一份：App 私有目录别的 App 读不到也写不到，改一行共享存储里的脚本
     * 不再等于任意代码执行。
     *
     * 为什么落在 home 而不是 filesDir 下别处：`ProotLauncher` 用 `-b <files>/home:/root`
     * 把宿主 home 绑成 guest 的 `/root`，只有这份映射里的路径 guest 才看得见
     * （rc 文件 `~/.zhengdao/install-<id>.rc` 走的是同一条 bind）。
     */
    const val GUEST_PRIVATE_SCRIPTS_DIR = "/root/.zhengdao/$SUB_SCRIPTS"

    /** [GUEST_PRIVATE_SCRIPTS_DIR] 的宿主侧对应目录（App 私有；建不出来时返回未创建的路径）。 */
    fun hostPrivateScriptsDir(ctx: Context): File =
        File(ctx.filesDir, "home/.zhengdao/$SUB_SCRIPTS").apply { runCatching { mkdirs() } }

    /** 公共区根目录（保证目录存在；没存储权限 / 仅私有模式时返回私有兜底，功能不断）。 */
    fun root(ctx: Context): File {
        val pub = File(PUBLIC_ROOT)
        if (storageGranted(ctx)) {
            runCatching { pub.mkdirs() }
            if (pub.isDirectory) return pub
        }
        return privateRoot(ctx)
    }

    /** 私有兜底根：共享存储不可用时的落脚点（东西在应用私有目录里，卸载即消失）。 */
    private fun privateRoot(ctx: Context): File =
        File(ctx.filesDir, "store").apply { runCatching { mkdirs() } }

    /** 当前根目录是否真的在共享存储上（决定 UI 文案与是否值得迁移）。 */
    fun isPublic(ctx: Context): Boolean = root(ctx).absolutePath.startsWith("/storage/emulated/0")

    /** 公共区下的一个子目录（保证存在；建不出来返回未创建的对象，调用方按需兜底）。 */
    fun dir(ctx: Context, name: String): File = File(root(ctx), name).apply { runCatching { mkdirs() } }

    /**
     * 日志目录：`Download/证道/logs/`。
     *
     * ⚠️ 2026-10-14 N-4 A 案起 `RunLog` **不再往这里写**（改私有 `filesDir/logs`）；
     * 本目录现只剩两个用途：保活记录导出（`KeepaliveArchive.export`）与旧日志一次性复制。
     */
    fun logsDir(ctx: Context): File = dir(ctx, DIR_LOGS)

    /** Agent 目录：`Download/证道/agents/`。 */
    fun agentsDir(ctx: Context): File = dir(ctx, DIR_AGENTS)

    /** Agent 安装脚本目录：`Download/证道/agents/scripts/`。 */
    fun agentScriptsDir(ctx: Context): File = File(agentsDir(ctx), SUB_SCRIPTS).apply { runCatching { mkdirs() } }

    /** 缓存根：`Download/证道/cache/`。 */
    fun cacheRoot(ctx: Context): File = dir(ctx, DIR_CACHE)

    /**
     * 一类包缓存的公共目录：`Download/证道/cache/<kind>/`。
     * @param kind 只允许简单名字（`npm` / `uv` / `pip`），杜绝 `../` 之类的花活。
     */
    fun cacheDir(ctx: Context, kind: String): File =
        cacheDirPath(ctx, kind).apply { runCatching { mkdirs() } }

    /**
     * 同上，但**只算路径、不建目录**——量体积 / 展示用。
     * （面板每打开一次、App 每启动一次都调 `CacheCleaner.measure`，
     *  用 [cacheDir] 会在用户存储里凭空建出 `cache/npm`、`cache/uv`、`cache/pip` 三个空目录。）
     */
    fun cacheDirPath(ctx: Context, kind: String): File {
        require(isLegalKind(kind)) { "非法缓存类别名: $kind" }
        // 刻意不经 [cacheRoot]：那个会 mkdirs，「看一眼体积」不该在用户存储里留下空目录
        return File(File(root(ctx), DIR_CACHE), kind)
    }

    /**
     * [cacheDir] 的名字校验（纯函数，可单测）。
     *
     * **只认 ASCII 字母数字和 `-` `_`**：刻意不用 `Char.isLetterOrDigit()`——它是 Unicode 语义的，
     * `"缓存".all { it.isLetterOrDigit() }` 为 true，中文目录名会直接漏进来，而公共区目录名要被
     * guest 内 bash 原样使用（中文要转义、还可能撞上编码问题）。
     */
    fun isLegalKind(kind: String): Boolean = kind.isNotEmpty() && kind.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    }

    /**
     * 逐条目搬家：把 [legacy] 里的东西搬进 [target]（目标已有同名条目则跳过）。
     *
     * 纯 [File] 操作、无 Android 依赖，可直接单测（见 StoreTest）。
     * @return 实际搬动的条目数
     */
    /** A5 的人话说明（纯函数，JVM 单测钉住措辞）。 */
    internal fun dirListFailureNote(path: String, e: Throwable): String =
        "[迁移] 目录列不出来（${e.javaClass.simpleName}）：$path"

    /** A6 的人话说明（纯函数）：rename 与 copy 都失败时，把异常类名与两端路径留证。 */
    internal fun moveFailureNote(from: String, to: String, e: Throwable): String =
        "[迁移] 搬不动（rename 与 copy 都失败，${e.javaClass.simpleName}）：$from → $to"

    fun adoptDir(target: File, legacy: File): Int {
        if (!legacy.isDirectory) return 0
        if (!target.isDirectory && !target.mkdirs()) return 0
        // 第七十一批③：源目录列不出内容（IO 错误）之前静默 return 0，迁移悄悄放弃、
        // 旧数据滞留无提示——补一条日志再按原样返回；返回值/行为不变。
        // （源目录不存在走上面 isDirectory 那条正常路径，不算异常，不打日志。）
        // A5（2026-10-29）：`File.listFiles()` **不抛异常**，出错时返回 null —— 原写法把
        // "列不出来（IO/权限）"和"目录是空的"当成同一件事，日志里那句"不可读"也没说清是哪种。
        // 行为不变（仍 return 0），但文案分清，并且带上异常类名（若真有异常）。
        val src = runCatching { legacy.listFiles() }.fold(
            onSuccess = { it },
            onFailure = { e ->
                RunLog.log(dirListFailureNote(legacy.path, e))
                null
            },
        ) ?: run {
            RunLog.log("迁移源列不出内容（IO/权限问题，不是空目录），跳过：src=${legacy.path}")
            return 0
        }
        var moved = 0
        for (f in src) {
            val dst = File(target, f.name)
            if (dst.exists()) continue // 目标已有同名：绝不覆盖（宁可留着旧源，也不能弄坏新数据）
            val ok = runCatching { f.renameTo(dst) }.getOrDefault(false) ||
                runCatching {
                    f.copyRecursively(dst, overwrite = false)
                    f.deleteRecursively()
                    true
                }.getOrElse {
                    // 🟡-1（Qoder 2026-10-13）：copyRecursively 的默认 onError 是**重抛**，所以复制中途
                    // 失败时会在上面那句就被接住 —— 源文件不会被删（这一点报告已用 kotlin-stdlib 源码
                    // 证伪了"复制失败仍删源"的旧疑似 🔴）。但 dst 里会留下**半截副本**，而下一轮的
                    // `dst.exists() → continue` 会把半截当成"已搬过"，于是**永不重拷**。
                    // 这里把半截清掉，让下一轮自愈；清不掉也无妨（比方说文件被占用），返回 false 的
                    // 语义不变：宁可下轮再试，也不能把半截留在公共区当成品。
                    runCatching { dst.deleteRecursively() }
                    RunLog.log(moveFailureNote(f.path, dst.path, it))
                    false
                }
            if (ok) moved++
        }
        // 源目录空了就删掉（留着会在公共/私有两侧都留一个空壳）
        runCatching {
            if (legacy.listFiles()?.isEmpty() != false) legacy.delete()
        }
        return moved
    }

    /**
     * 单文件版搬家（目标已存在**且完整**则不动）。
     *
     * 2026-10-14（审计第 2 期 B-4）：旧写法 `target.exists() → return false` 把**半截目标**也当成
     * "已搬过"。`legacy.copyTo(target)` 中途抛异常（存储写满 / 权限 / 被杀）会在目标名留下源的
     * **前 N 字节**，下一轮 `exists()` 直接放行 ⇒ **永不重拷**（与 [adoptDir] 的 🟡-1 同病，
     * `RootfsCache.migrateLegacy` 也有一处）。
     *
     * 判据（[isHalfCopy]）：目标**严格短于**源、且字节逐位等于源的前缀 ⇒ 判定为"上次没搬完的残片"，
     * 清掉重拷；否则（不短于源＝完整，或内容对不上＝用户自己放的另一个同名文件）一律不覆盖 ——
     * 与 [adoptDir]「宁可留着旧源，也不能弄坏新数据」同一条纪律。
     * 复制若再次失败，也把半截目标清掉，让下一轮能自愈重试。
     */
    fun adoptFile(target: File, legacy: File): Boolean {
        if (!legacy.isFile) return false
        if (target.exists() && !isHalfCopy(target, legacy)) return false
        // 半截残片先清掉：复制若再次失败，也不会把它留在公共区冒充成品
        runCatching { if (target.exists()) target.delete() }
        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        return runCatching { legacy.renameTo(target) }.getOrDefault(false) ||
            runCatching {
                legacy.copyTo(target, overwrite = true)
                legacy.delete()
                true
            }.getOrElse {
                // 失败留可重试状态：清掉半截目标，下一轮 target.exists() 为假 ⇒ 自愈重拷
                runCatching { target.delete() }
                RunLog.log(moveFailureNote(legacy.path, target.path, it))
                false
            }
    }

    /**
     * [adoptFile] 的判据：目标是否是源的**半截残片**（严格更短，且字节逐位等于源的前缀）。
     *
     * 只有"内容就是源的开头一段"才算没搬完 —— 于是既不会把用户手动放在公共区的**另一个**
     * 同名文件（内容对不上）误清掉，也能把任何截断副本救回来。0 字节目标按残片处理
     * （复制夭折在第一步的极端情形）。
     */
    private fun isHalfCopy(target: File, legacy: File): Boolean {
        if (!target.isFile) return false
        val n = target.length()
        if (n >= legacy.length()) return false
        if (n == 0L) return true
        return runCatching {
            val head = target.readBytes()
            val prefix = ByteArray(head.size)
            legacy.inputStream().use { input ->
                var off = 0
                while (off < prefix.size) {
                    val r = input.read(prefix, off, prefix.size - off)
                    if (r < 0) break
                    off += r
                }
            }
            prefix.contentEquals(head)
        }.getOrDefault(false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // §B 工作区解析器 ——原 `terminal/Workspace`
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 工作区解析器（0.6 显性化，2026-10-06）：Agent 产出位置的唯一真相源。
     * ProotLauncher 的 bind、home 软链、AGENTS.md 人设与 AgentInstaller 的脚本
     * 落点全部经由本解析器，保证四处一致。
     *
     * 语义（用户定稿）：
     * - guest 侧永远是 /workspace（终端/Agent/安装脚本的习惯与脚本路径不变）
     * - 默认宿主侧 = /sdcard/Download/证道：手机文件管理器直接可见、可自由删除，
     *   **卸载证道后文件夹仍保留——产出不丢**（特性而非缺陷，设置页正向文案）
     * - 自定义 = 共享存储下任意文件夹（内置文件夹浏览器选择，无任何手输框）
     * - 仅私有 = 应用外部目录（随卸载自动删除，给在意痕迹的场景）
     * - 无存储权限 → 自动回落仅私有（基础功能不受损）
     *
     * 旧值迁移（一次性）：workspace_custom 里 /storage/emulated/0 开头的有效路径
     * 转存为新键；无效值（如历史遗留的 Windows 风格路径）直接清除。
     */

    private const val KEY_MODE = "workspace_mode" // default | custom | private
    private const val KEY_PATH = "workspace_path" // custom 模式的绝对路径
    private const val KEY_PREV = "workspace_prev_host" // 上一次用过的工作区（仅用于提示，不参与解析）
    private const val KEY_MIGRATED = "workspace_migrated_v2"
    private const val SHARED_ROOT = "/storage/emulated/0"

    /**
     * 共享存储可读写判定——**单一实现**（v1.3 E2 消双判：此前本处查 READ+WRITE、
     * ProotLauncher 只查 WRITE、设置页第三份又查 READ+WRITE，部分授权态下三处结论可能不同）。
     * 第二百四十批起本判定落在 [EnvironmentScope.hasAllFilesAccess]：
     * 终端侧 `ProotLauncher.storageGranted` 与 `terminal/Workspace.storageGranted` 都改成委托它。
     */
    fun storageGranted(ctx: Context): Boolean = EnvironmentScope.hasAllFilesAccess(ctx)

    /** 宿主侧工作区目录（bind / 软链 / 脚本落点的唯一来源）。保证目录存在。 */
    fun hostDir(ctx: Context): File {
        migrate(ctx)
        val prefs = AppPrefs.prefs(ctx)
        val mode = prefs.getString(KEY_MODE, "default") ?: "default"
        val resolved = when {
            !storageGranted(ctx) -> privateDir(ctx)
            mode == "private" -> privateDir(ctx)
            mode == "custom" -> customDir(ctx) ?: defaultDir(ctx)
            else -> defaultDir(ctx)
        }
        runCatching { resolved.mkdirs() }
        if (resolved.isDirectory) return resolved
        // 共享存储创建失败（无权限/挂载异常）→ 回落私有，基础功能不受损
        return privateDir(ctx).apply { runCatching { mkdirs() } }
    }

    /**
     * 当前工作区是否位于共享存储（决定 /sdcard bind、软链与界面文案）。
     *
     * B-补-1 修正（2026-10-11）：判据从「路径以 `/storage/emulated/0` 开头」改成
     * 「解析出的宿主目录**不是 App 外部目录**」。为什么必须换：`privateDir` =
     * `getExternalFilesDir(null)/workspace` 本身就长成
     * `/storage/emulated/0/Android/data/<包名>/files/workspace`，前缀天然为真 ⇒
     * 「仅私有」恒被判成共享（[isPrivate] 永远 false），于是**仅私有模式下 `/sdcard`
     * 照旧 bind 进 guest**、三处文案还说成「共享存储，卸载保留」——正好与本文件第 19 行
     * 「仅私有 = 应用外部目录（随卸载自动删除）」的定稿语义相反（详见 ERRATA E-094）。
     */
    fun isShared(ctx: Context): Boolean =
        isSharedPath(hostDir(ctx).absolutePath, privateDir(ctx).absolutePath)

    /**
     * 纯判据（可单测，见 `WorkspacePathTest`）：宿主目录只要**不是** App 外部目录，就算共享存储。
     * 尾部斜杠差异不当作两种目录（`hostDir`/`privateDir` 由同一处解析器构造，理论上不会出现）。
     */
    fun isSharedPath(hostPath: String, privatePath: String): Boolean =
        hostPath.trimEnd('/') != privatePath.trimEnd('/')

    /** 是否为仅私有模式（App 外部目录，随卸载删除）。 */
    fun isPrivate(ctx: Context): Boolean = !isShared(ctx)

    fun defaultDir(ctx: Context): File = File("$SHARED_ROOT/Download/证道")

    fun privateDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "workspace")

    private fun customDir(ctx: Context): File? =
        AppPrefs.prefs(ctx).getString(KEY_PATH, null)
            ?.takeIf { it.startsWith(SHARED_ROOT) }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory || it.mkdirs() }

    /**
     * 上一次用过的工作区宿主目录（没有记录、或者记录的正是当前这个 ⇒ null）。
     *
     * 2026-10-11 新增。起因是一次真机现场：用户先把资料放进了 **旧工作区** 的
     * `资料库/原始/`，随后在设置页把工作区从 `Download/证道` 换成了 `Download/男性`
     * —— 而 `资料库` 是挂在工作区下面的（157 收口后门面是 `产物/`，旧根为 legacy 对账路径），换工作区就等于换了一份
     * 资料库；旧那份原样留在原地、不再被读。用户看到的现象是"我明明放了东西，
     * App 这边没反应"。记一笔"上一个工作区"，设置页就能**准确地说出**资料可能落在哪，
     * 而不是让用户自己猜。
     *
     * 只读用途：不要把本值喂回 [hostDir] 的解析（那是 [KEY_MODE]/[KEY_PATH] 的事）。
     */
    fun prevHostDir(ctx: Context): File? =
        AppPrefs.prefs(ctx).getString(KEY_PREV, null)
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it.trimEnd('/') != hostDir(ctx).absolutePath.trimEnd('/') }
            ?.let { File(it) }

    /** 切换工作区**之前**把"现在这个"记进 [KEY_PREV]（供资料库提示用）。 */
    private fun rememberCurrent(ctx: Context) {
        runCatching {
            val cur = hostDir(ctx).absolutePath
            AppPrefs.prefs(ctx).edit { putString(KEY_PREV, cur) }
        }
    }

    /** 设置为默认工作区（Download/证道）。 */
    fun setDefault(ctx: Context) {
        rememberCurrent(ctx)
        AppPrefs.prefs(ctx).edit { putString(KEY_MODE, "default") }
    }

    /** 设置为自定义工作区（任意共享存储路径；非法路径拒绝）。 */
    fun setCustom(ctx: Context, path: String): Boolean {
        if (!path.startsWith(SHARED_ROOT)) return false
        rememberCurrent(ctx)
        AppPrefs.prefs(ctx).edit {
            putString(KEY_MODE, "custom")
            putString(KEY_PATH, path.trimEnd('/'))
        }
        return true
    }

    /** 设置为仅私有。 */
    fun setPrivate(ctx: Context) {
        rememberCurrent(ctx)
        AppPrefs.prefs(ctx).edit { putString(KEY_MODE, "private") }
    }

    /** 一次性迁移：旧 workspace_custom 的有效路径转存新键，无效值清除。 */
    private fun migrate(ctx: Context) {
        val prefs = AppPrefs.prefs(ctx)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacy = prefs.getString("workspace_custom", null)
        if (!legacy.isNullOrBlank()) {
            if (legacy.startsWith(SHARED_ROOT)) {
                prefs.edit { putString(KEY_PATH, legacy.trimEnd('/')) }
            }
            prefs.edit { remove("workspace_custom") }
        }
        prefs.edit { putBoolean(KEY_MIGRATED, true) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // §C 资料库目录真源 ——原 `BaopuKernel.Data.DIR_PRODUCTS / LEGACY_LIBRARY_DIR`
    //     与 `LibraryPaths.rawDirUnder`
    // ══════════════════════════════════════════════════════════════════════

    /** 抱朴唯一对外输出口（旧 `资料库/整理`）—— 值与原 `BaopuKernel.Data.DIR_PRODUCTS` 一字不差。 */
    const val PRODUCTS_DIR = "产物"

    /** 旧资料库根目录名（157 前的 `<工作区>/资料库/`）—— **legacy**，仅存量识别用。 */
    const val LEGACY_LIBRARY_DIR = "资料库"

    /** 私有原件区的三级目录名（`filesDir/baopu/library/原始`）。 */
    private const val DIR_BAOPU = "baopu"
    private const val DIR_LIBRARY_PRIVATE = "library"

    /** 私有原件区里的原件子目录名（`原始/`）。 */
    const val SUB_RAW_PRIVATE = "原始"

    /**
     * 私有原件区（157 起：`filesDir/baopu/library/原始/`）。
     *
     * 路径与 `baopu/library/LibraryPaths.rawDirUnder` **逐字节相同**（那边现在委托到这里）；
     * 资料库（[com.example.zhengdao.core.kb.KnowledgeBase]）与抱朴共用这一份真源。
     */
    fun rawDirUnder(filesDir: File): File =
        File(File(File(filesDir, DIR_BAOPU), DIR_LIBRARY_PRIVATE), SUB_RAW_PRIVATE)
}
