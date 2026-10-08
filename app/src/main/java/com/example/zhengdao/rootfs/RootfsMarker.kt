// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：ISO 8601 / RFC 3339 日期时间格式（java.time，JDK 标准库）。
package com.example.zhengdao.rootfs

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 环境标记文件（增量下发协议 §5：`<filesDir>/rootfs/.zhengdao-rootfs-ok`）。
 *
 * - **读取方只判"存在与否"**（[com.example.zhengdao.terminal.ProotLauncher]、AppState、EnvHealth），
 *   因此新增行不会破坏任何老逻辑；本对象是**写**这一侧的唯一来源。
 * - `env=`（16 位十六进制内容指纹，协议 §1）是增量更新的基线标识：
 *   旧安装没有这一行 ⇒ [installedEnv] 返回 null ⇒ **第一次更新走全量**，之后才有增量。
 * - `installed-by=zhengdao` 必须保留：现有代码一直写它，外部脚本/排查记录也认这一行。
 * - [parse] / [render] / [read] / [write] / [installedEnv] 全部是**纯函数或只碰 File**，
 *   可以在 JVM 单测里直接跑（不依赖 Android 框架）。
 */
object RootfsMarker {

    /** 标记文件名（与 [RootfsInstaller] 里的常量同一个名字，字符串只此一处）。 */
    const val NAME = ".zhengdao-rootfs-ok"

    /**
     * 标记内容（字段都可缺省——旧安装只有 distro 一行）。
     *
     * [archiveSha256] 是 2026-10-08 新增的第 4 个字段：**当初装出这份环境时，那个全量包的 sha256**。
     *
     * 为什么需要它：「修复环境」「回退版本」都是拿**本地缓存里那个包**重解压一次（不联网），
     * 而这两条路此前都不传 env（`install(ctx, archive) { }`）⇒ **一次修复就把增量基线抹掉了**：
     * 真机实测 21:26 检查是「已是最新版本（13.7，环境 51e1cc0c32f099aa）」，21:27:54 跑「修复环境」，
     * 21:56 再检查就变成「本地已安装 13.7，但缺少环境指纹记录」——用户看到的是"环境越修越不确定"。
     *
     * 有了这一行就能证明"我正要装的这个包 == 当初装出当前 env 的那个包"，env 于是可以**原样写回**；
     * 比不出来（换了版本、用户自选的包、老标记没有这一行）就照装但不写 env——
     * 与 [RootfsInstaller.envForMarker] 同一口径：**宁可少写一次，也不能写错基线**。
     */
    data class Data(
        val distro: String?,
        val env: String?,
        val installedAt: String?,
        val archiveSha256: String? = null,
    )

    /** env id 的形态：16 位十六进制（协议 §1）。取值不合形态 = 视同"没有版本记录"。 */
    private val ENV_RE = Regex("^[0-9a-fA-F]{16}$")

    /** 全量包 sha256 的形态：64 位十六进制。不合形态 = 视同"没记过"，不做"同源"推断。 */
    private val SHA_RE = Regex("^[0-9a-fA-F]{64}$")

    fun fileIn(rootfsDir: File): File = File(rootfsDir, NAME)

    /**
     * 解析标记正文。**任何输入都不抛异常**（不认识的行走开即可，空文本得到三个 null）：
     * 这个函数在更新检查路径上被调用，脏文件不能把设置页打崩。
     */
    fun parse(text: String): Data {
        var distro: String? = null
        var env: String? = null
        var at: String? = null
        var sha: String? = null
        text.split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val i = line.indexOf('=')
            if (i <= 0) return@forEach
            val key = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim().ifEmpty { null }
            when (key) {
                "distro" -> distro = value
                "env" -> env = value
                "installed-at" -> at = value
                "archive-sha256" -> sha = value
                // installed-by 等其余行：本对象不关心，保留给别人读
                else -> {}
            }
        }
        // sha 与 env 一样**校验形态**：脏值只能当"没记过"（否则会凭一个假 sha 做"同源"推断）
        return Data(distro, env, at, sha?.trim()?.lowercase()?.takeIf { SHA_RE.matches(it) })
    }

    /**
     * 渲染标记正文（LF 结尾）。distro / env / archive-sha256 为空就不写该行，
     * `installed-by=zhengdao` 永远写（老读取方与排查脚本都认它）。
     *
     * @param archiveSha256 排在参数表**最后**（而不是紧跟 env）：老调用点的位置参数
     *   `(rootfsDir, distro, env, installedAt)` 含义一个都不许变；行文顺序仍在 `env=` 之后，
     *   因为它和 env 描述的是同一件事（"这份内容是谁装的"）。
     */
    fun render(
        distro: String?,
        env: String?,
        installedAt: String?,
        archiveSha256: String? = null,
    ): String = buildString {
        if (!distro.isNullOrBlank()) append("distro=").append(distro.trim()).append('\n')
        if (!env.isNullOrBlank()) append("env=").append(env.trim()).append('\n')
        if (!archiveSha256.isNullOrBlank()) append("archive-sha256=").append(archiveSha256.trim()).append('\n')
        append("installed-by=zhengdao\n")
        if (!installedAt.isNullOrBlank()) append("installed-at=").append(installedAt.trim()).append('\n')
    }

    /** 读标记；文件不存在/读不动返回 null。 */
    fun read(rootfsDir: File): Data? = try {
        val f = fileIn(rootfsDir)
        if (f.isFile) parse(f.readText()) else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 写标记。`installedAt` 传 null = **不写** `installed-at` 行（不是"写当前时间"）——
     * 想记时间就显式传 [nowIso]，这样纯函数在测试里可复现。
     *
     * @param archiveSha256 本次装进环境的那个全量包的 sha256（[Data.archiveSha256] 的唯一来源）；
     *   增量安装、用户自选文件等"说不清是哪个包"的路径一律传 null ⇒ 不写该行 ⇒
     *   将来重装时不做"同源"推断（照装不写 env，下次老实全量）。
     */
    fun write(
        rootfsDir: File,
        distro: String?,
        env: String?,
        installedAt: String? = null,
        archiveSha256: String? = null,
    ) {
        val f = fileIn(rootfsDir)
        f.parentFile?.mkdirs()
        f.writeText(render(distro, env, installedAt, archiveSha256))
    }

    /** 当前时间（ISO8601 带本地偏移）——`installed-at` 的唯一来源。 */
    fun nowIso(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** 已装环境的 env id（读 `<filesDir>/rootfs`）。 */
    fun installedEnv(context: Context): String? = installedEnv(File(context.filesDir, "rootfs"))

    /**
     * 已装环境的 env id；以下情况一律返回 null（调用方据此回退全量）：
     * 标记缺失、旧格式（无 `env=` 行）、取值不是 16 位十六进制。
     * 大小写统一成小写，便于与索引/补丁里的值直接比较。
     */
    fun installedEnv(rootfsDir: File): String? =
        read(rootfsDir)?.env?.trim()?.lowercase()?.takeIf { ENV_RE.matches(it) }

    /**
     * 已装环境「当初那个全量包的 sha256」；两种情况返回 null，调用方据此**不做**"同源"推断：
     * 标记缺失/读不动，或标记是 2026-10-08 之前的旧格式（那时还没有 `archive-sha256=` 行）。
     * 大小写统一成小写，便于与 [com.example.zhengdao.rootfs.RootfsDownloader.sha256Of] 的返回值直接比。
     */
    fun installedArchiveSha256(rootfsDir: File): String? =
        read(rootfsDir)?.archiveSha256?.trim()?.lowercase()?.takeIf { SHA_RE.matches(it) }
}
