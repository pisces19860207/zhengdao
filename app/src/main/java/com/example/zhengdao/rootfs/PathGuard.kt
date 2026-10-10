// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准与官方文档：tar 归档格式（POSIX 1003.1-1988 ustar）里「成员名」与
// 「linkname」的路径语义（linkname 相对**链接所在目录**，允许绝对路径）；
// java.io.File / java.nio.file.Path 的 canonicalize（解析已存在的软链）与
// normalize（纯词法折叠 `.` / `..`）语义（JDK 标准库，Android API 26+ 可用）。
package com.example.zhengdao.rootfs

import java.io.File

/**
 * 解包路径边界判定（**加固-1 / 加固-3**，2026-10-10 / E-085）。
 *
 * 审计报告（`证道-rootfs审计与复核-2026-10-10.md`）指出两处口径问题，都收在这里：
 *
 * 1. **成员名的越界判据是字符串前缀**：`target.canonicalFile.path.startsWith(root.path)`
 *    —— `/data/data/…/rootfs-evil` 会被判成「在 rootfs 内」（`…/rootfs` 是它的前缀）。
 *    必须按**路径组件**比较（[java.nio.file.Path.startsWith] 就是组件级，Rust 侧
 *    `Path::starts_with` 同理）；字符串前缀比较等于给"同前缀兄弟目录"开了口子。
 * 2. **软链的 linkname 完全没判**：tar 允许绝对路径与 `../`，解出来的链接可以指向
 *    解压根之外；同一份包里的后续成员、或 guest 内的程序跟随时，就成了写出环境目录的通道。
 *    判据：绝对路径按 **guest 根**（= 解压根）解释、相对路径按 **链接所在目录** 解释，
 *    归一后必须仍在根内。
 *
 * 两个函数都是纯函数（不碰 Android API、不写盘），可直接 JVM 单测（见 `PathGuardTest`）。
 */
internal object PathGuard {

    /**
     * [target] 是否落在 [root] 内（按**路径组件**比较，不按字符串前缀）。
     *
     * `canonicalFile` 会解析路径中**已存在**部分的软链（与 Rust 侧 canonicalize 同语义），
     * 取不到时退回纯词法归一 —— 判定"越界"宁可保守，但绝不能因为拿不到规范路径就放行。
     */
    fun isInside(root: File, target: File): Boolean {
        val rootPath = runCatching { root.canonicalFile.toPath() }
            .getOrElse { root.absoluteFile.toPath() }
        val targetPath = runCatching { target.canonicalFile.toPath() }
            .getOrElse { target.absoluteFile.toPath() }
        return targetPath.normalize().startsWith(rootPath.normalize())
    }

    /**
     * 软链 [linkName] 是否落在 [root] 内？（tar 语义：绝对路径按根解释、相对路径按链接所在目录解释）
     *
     * 只做**词法**归一（不 canonicalize）：链接目标通常还不存在（同一份包里的后续成员），
     * 而 canonicalize 一个不存在的路径会把它原样返回 ⇒ 判不出 `..` 逃逸。真实存在的软链
     * 被后续成员跟随时，威胁由 [isInside]（canonicalize 版）兜住。
     *
     * 空 linkname 一律拒绝（tar 里它没有合法语义，建出来只会是个悬空链接）。
     */
    fun linkStaysInside(root: File, linkFile: File, linkName: String): Boolean {
        val name = linkName.trim()
        if (name.isEmpty()) return false
        val rootPath = root.absoluteFile.toPath().normalize()
        // 绝对路径 ⇒ 从 guest 根（解压根）起算；相对路径 ⇒ 从链接所在目录起算
        val base = if (name.startsWith("/")) {
            rootPath
        } else {
            linkFile.parentFile?.absoluteFile?.toPath() ?: return false
        }
        val joined = base.resolve(name.removePrefix("/")).normalize()
        return joined.startsWith(rootPath)
    }
}
