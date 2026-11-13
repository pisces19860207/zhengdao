// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 抱朴自己的工作区（2026-10-18 用户口径：「**设置页里的文件夹和抱朴分开设置的意思，再也不共用了**
// （当然用户要设置成一样也是可以的，等于就是终端里 agent 和抱朴都在一个地方工作）」＋
// 「**默认工作区就是跟着 APP 一起的那个 download/证道 文件夹**」）。
//
// ⚠️ 这是**口径反转**，必须写在最上面免得后来人以为是漏改：
//   在此之前抱朴与终端**共用** `terminal/Workspace` 那套键（终端的两个 preference 键名），
//   当年写的是「同一件事只有一个盒子」。用户 2026-10-18 明确改成**两个盒子**：
//     · 终端（agent / proot / bind）继续用 `terminal/Workspace`，一个字不改；
//     · 抱朴用自己的 `baopu_ws_mode`／`baopu_ws_path`，**不读也不写**终端那套。
//   两边可以设成同一个目录（默认就都是 `Download/证道`），但**设置互不影响**。
//
// 唯一还借用终端那边的东西是「共享存储授权」这一个**事实判定**——那不是设置，
// 是这台机器当前的权限状态；复制一份判定就是 E-005 那种双判，所以这里委托
// `EnvironmentScope.hasAllFilesAccess`（第二百四十批起判定已下沉 :core，
// 与终端模块（zhengdao-term）里同名判定用的是同一个实现）。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.PathGuard
import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.core.AppPrefs
import com.example.zhengdao.core.EnvironmentScope
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.WorkspaceTruth
import java.io.File

/**
 * 抱朴的工作区（**唯一真相源**，与终端那套分开）。
 *
 * 「工作区」在抱朴里意味着三件事，全从这里取根：① 工具能读写到哪儿（`PathGuard` 的根）
 * ② 附件落盘（`<工作区>/附件/`）③ spill 落盘。三处共用一个根，不许各自再拼。
 */
object BaopuWorkspace {

    /** 抱朴自己的键（**刻意不用**终端那两个键名，那两个归终端）。 */
    private const val KEY_MODE = "baopu_ws_mode"   // default | custom | private
    private const val KEY_PATH = "baopu_ws_path"   // custom 模式的绝对路径
    private const val SHARED_ROOT = "/storage/emulated/0"

    /** 默认工作区的目录名（跟着 App 一起的那个）：`/storage/emulated/0/Download/证道`。 */
    const val DEFAULT_DIR_NAME = "证道"

    /** 默认工作区（`Download/证道`）——**不写死绝对路径以外的任何东西**，就这一个来源。 */
    fun defaultDir(): File = File("$SHARED_ROOT/Download/$DEFAULT_DIR_NAME")

    /** 共享存储是否已授权（委托 `:core` 的**同一个实现**，不是第二份判定）。 */
    fun storageGranted(ctx: Context): Boolean = EnvironmentScope.hasAllFilesAccess(ctx)

    /**
     * 抱朴当前的宿主目录。保证目录存在。
     *
     * 解析顺序：没授权 ⇒ App 外部私有目录（基础功能不受损）；custom 且路径合法 ⇒ 它；否则默认目录。
     */
    fun hostDir(ctx: Context): File {
        val m = mode(ctx)
        val target = when {
            !storageGranted(ctx) -> privateDir(ctx)
            m == "private" -> privateDir(ctx)
            else -> resolve(m, customPath(ctx), defaultDir())
        }
        runCatching { target.mkdirs() }
        // 615：两条 return 合成一条，让「实走的到底是哪条」只在一处被说出来（见 reportTruth）。
        val host = if (target.isDirectory) target else privateDir(ctx).apply { runCatching { mkdirs() } }
        reportTruth(ctx, m, host)
        return host
    }

    /**
     * 实态自证（第六百一十五批）。
     *
     * 修的是 569 真机那一幕：`baopu_ws_path` 说 `Download/男性`、抽屉也说「男性」，
     * 而这里因为授权不在就悄悄改走 App 私有目录 —— 用户照着屏幕放素材，App 当然看不见。
     * 判据、文案、去重闸门都在 `:core` 的 [WorkspaceTruth] 那边**只写一份**，
     * 与 [com.example.zhengdao.core.ZhengdaoDirs.hostDir] 共用（两个解析器各记各的，互不顶掉）。
     */
    private fun reportTruth(ctx: Context, mode: String, host: File) {
        val declared = declaredPath(ctx, mode)
        WorkspaceTruth.report(
            source = "投递",
            realPath = host.absolutePath,
            declaredPath = declared,
            reason = truthReason(ctx, mode, host, declared),
            sink = RunLog::log,
        )
    }

    /**
     * SP 声明的那条路（**只用来和实走对拍**：不许拿它读写、也不许把它画上界面当真值）。
     *
     * 公开给显示层是有意的一次开口：界面要靠它判断「要不要补第二行实路径」，
     * 而不是自己去拼 `baopu_ws_path` —— 后者就是 569 那次分歧的另一半来源。
     */
    fun declaredPath(ctx: Context): String? = declaredPath(ctx, mode(ctx))

    private fun declaredPath(ctx: Context, mode: String): String? = when (mode) {
        "private" -> privateDir(ctx).absolutePath
        "custom" -> customPath(ctx)?.takeIf { it.isNotBlank() }
        else -> defaultDir().absolutePath
    }

    /** 那一行的「为什么」：把改掉路的那条分支点名说清楚，回落不许只留一个结果。 */
    private fun truthReason(ctx: Context, mode: String, host: File, declared: String?): String = when {
        !storageGranted(ctx) -> "共享存储未授权 ⇒ 回落 App 私有目录"
        mode != "private" && WorkspaceTruth.diverged(host.absolutePath, privateDir(ctx).absolutePath) ->
            "声明的目录不可用 ⇒ 回落 App 私有目录"
        WorkspaceTruth.diverged(host.absolutePath, declared) -> "实走与声明不一致"
        else -> WorkspaceTruth.SAME
    }

    /** 当前是不是「仅 App 私有」（没授权时也会落在这里）。 */
    fun isPrivate(ctx: Context): Boolean = !storageGranted(ctx) || mode(ctx) == "private"

    /** 设为仅 App 私有。 */
    fun setPrivate(ctx: Context) {
        AppPrefs.prefs(ctx).edit { putString(KEY_MODE, "private").remove(KEY_PATH) }
    }

    /** 仅 App 私有（没授权时的回落落点）。 */
    fun privateDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "workspace")

    /** 设为默认工作区（`Download/证道`）。 */
    fun setDefault(ctx: Context) {
        AppPrefs.prefs(ctx).edit { putString(KEY_MODE, "default").remove(KEY_PATH) }
    }

    /**
     * 设为自定义工作区。非法路径返回 false（**不许**把非法值写进 prefs，否则下次解析要靠回落兜底，
     * 用户会看到「设了却没生效」）。
     */
    fun setCustom(ctx: Context, path: String): Boolean {
        if (!isValidCustom(path)) return false
        AppPrefs.prefs(ctx).edit {
            putString(KEY_MODE, "custom")
            putString(KEY_PATH, path.trimEnd('/'))
        }
        return true
    }

    private fun mode(ctx: Context): String =
        AppPrefs.prefs(ctx).getString(KEY_MODE, "default") ?: "default"

    private fun customPath(ctx: Context): String? =
        AppPrefs.prefs(ctx).getString(KEY_PATH, null)

    /** 合法自定义路径：共享存储里的绝对路径，且不带 `..`。**纯函数，可单测。** */
    internal fun isValidCustom(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (!path.startsWith(SHARED_ROOT)) return false
        if (path.contains("..")) return false
        return true
    }

    /**
     * 纯解析：给定模式与自定义路径，得出宿主目录（不碰 prefs、不碰 Android）。
     *
     * 抽出来的理由：这条判断有四个分支（default／custom 合法／custom 非法／空），
     * 而 `hostDir` 自己拿不到 prefs 就没法测 —— 把判断抽成纯函数，JVM 里就能穷举。
     */
    internal fun resolve(mode: String?, customPath: String?, fallback: File): File =
        if (mode == "custom" && isValidCustom(customPath)) File(customPath!!.trimEnd('/')) else fallback

    /** 日志/关于页用的短描述（不打印整条路径进用户可见处时用它）。 */
    internal fun debugTag(): String = "baopu-ws/${BuildConfig.VERSION_NAME}"
}
