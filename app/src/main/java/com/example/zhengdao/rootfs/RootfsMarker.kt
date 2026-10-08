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

    /** 标记内容（字段都可缺省——旧安装只有 distro 一行）。 */
    data class Data(val distro: String?, val env: String?, val installedAt: String?)

    /** env id 的形态：16 位十六进制（协议 §1）。取值不合形态 = 视同"没有版本记录"。 */
    private val ENV_RE = Regex("^[0-9a-fA-F]{16}$")

    fun fileIn(rootfsDir: File): File = File(rootfsDir, NAME)

    /**
     * 解析标记正文。**任何输入都不抛异常**（不认识的行走开即可，空文本得到三个 null）：
     * 这个函数在更新检查路径上被调用，脏文件不能把设置页打崩。
     */
    fun parse(text: String): Data {
        var distro: String? = null
        var env: String? = null
        var at: String? = null
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
                // installed-by 等其余行：本对象不关心，保留给别人读
                else -> {}
            }
        }
        return Data(distro, env, at)
    }

    /**
     * 渲染标记正文（LF 结尾）。distro / env 为空就不写该行，
     * `installed-by=zhengdao` 永远写（老读取方与排查脚本都认它）。
     */
    fun render(distro: String?, env: String?, installedAt: String?): String = buildString {
        if (!distro.isNullOrBlank()) append("distro=").append(distro.trim()).append('\n')
        if (!env.isNullOrBlank()) append("env=").append(env.trim()).append('\n')
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
     */
    fun write(rootfsDir: File, distro: String?, env: String?, installedAt: String? = null) {
        val f = fileIn(rootfsDir)
        f.parentFile?.mkdirs()
        f.writeText(render(distro, env, installedAt))
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
}
