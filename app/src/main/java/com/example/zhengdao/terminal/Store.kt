// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import android.content.Context
import java.io.File

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
 * | `logs/`   | 运行日志、错误日志 | [com.example.zhengdao.rootfs.RunLog] |
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
object Store {

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
     * [GUEST_PRIVATE_SCRIPTS_DIR] 与 [com.example.zhengdao.ui.AgentInstaller]。
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
        if (Workspace.storageGranted(ctx)) {
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

    /** 日志目录：`Download/证道/logs/`。 */
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
     * （面板每打开一次、App 每启动一次都调 [CacheCleaner.measure]，
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
    internal fun isLegalKind(kind: String): Boolean = kind.isNotEmpty() && kind.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    }

    /**
     * 逐条目搬家：把 [legacy] 里的东西搬进 [target]（目标已有同名条目则跳过）。
     *
     * 纯 [File] 操作、无 Android 依赖，可直接单测（见 StoreTest）。
     * @return 实际搬动的条目数
     */
    fun adoptDir(target: File, legacy: File): Int {
        if (!legacy.isDirectory) return 0
        if (!target.isDirectory && !target.mkdirs()) return 0
        val src = runCatching { legacy.listFiles() }.getOrNull() ?: return 0
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
                false
            }
    }

    /**
     * [adoptFile] 的判据：目标是否是源的**半截残片**（严格更短，且字节逐位等于源的前缀）。
     *
     * 只有"内容就是源的开头一段"才算没搬完 —— 于是既不会把用户手动放在公共区的**另一个**
     * 同名文件（内容对不上）误清掉，也能把任何长度的截断副本救回来。0 字节目标按残片处理
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
}
