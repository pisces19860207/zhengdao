// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android 官方文档（共享存储访问、应用外部目录语义）。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.core.AppPrefs
import java.io.File

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
object Workspace {

    private const val KEY_MODE = "workspace_mode" // default | custom | private
    private const val KEY_PATH = "workspace_path" // custom 模式的绝对路径
    private const val KEY_PREV = "workspace_prev_host" // 上一次用过的工作区（仅用于提示，不参与解析）
    private const val KEY_MIGRATED = "workspace_migrated_v2"
    private const val SHARED_ROOT = "/storage/emulated/0"

    /**
     * 共享存储可读写判定——**委托 ProotLauncher 单一实现**（v1.3 E2 消双判：
     * 此前本处查 READ+WRITE、ProotLauncher 只查 WRITE、设置页第三份又查 READ+WRITE，
     * 部分授权态下三处结论可能不同）。E-005 修正后的真实通路见 ProotLauncher 注释。
     */
    fun storageGranted(ctx: Context): Boolean = ProotLauncher.storageGranted(ctx)

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
    internal fun isSharedPath(hostPath: String, privatePath: String): Boolean =
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
     * —— 而 `资料库` 是挂在工作区下面的（[KnowledgeBase.root]），换工作区就等于换了一份
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
            AppPrefs.prefs(ctx).edit().putString(KEY_PREV, cur).apply()
        }
    }

    /** 设置为默认工作区（Download/证道）。 */
    fun setDefault(ctx: Context) {
        rememberCurrent(ctx)
        AppPrefs.prefs(ctx).edit().putString(KEY_MODE, "default").apply()
    }

    /** 设置为自定义工作区（任意共享存储路径；非法路径拒绝）。 */
    fun setCustom(ctx: Context, path: String): Boolean {
        if (!path.startsWith(SHARED_ROOT)) return false
        rememberCurrent(ctx)
        AppPrefs.prefs(ctx).edit()
            .putString(KEY_MODE, "custom")
            .putString(KEY_PATH, path.trimEnd('/'))
            .apply()
        return true
    }

    /** 设置为仅私有。 */
    fun setPrivate(ctx: Context) {
        rememberCurrent(ctx)
        AppPrefs.prefs(ctx).edit().putString(KEY_MODE, "private").apply()
    }

    /** 一次性迁移：旧 workspace_custom 的有效路径转存新键，无效值清除。 */
    private fun migrate(ctx: Context) {
        val prefs = AppPrefs.prefs(ctx)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacy = prefs.getString("workspace_custom", null)
        if (!legacy.isNullOrBlank()) {
            if (legacy.startsWith(SHARED_ROOT)) {
                prefs.edit().putString(KEY_PATH, legacy.trimEnd('/')).apply()
            }
            prefs.edit().remove("workspace_custom").apply()
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }
}
