// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

import android.content.Context
import com.example.zhengdao.terminal.ProotLauncher
import java.io.File

/**
 * 安装包公共缓存（用户第三批）：
 * - 位置 /sdcard/Download/zhengdao/cache/（legacy 存储视图下应用自身可读写，
 *   2026-10-04 实测：target 28 + WRITE 权限的应用进程可直接读写 Download；
 *   不可 bind 的是 proot——两回事）。重装 App 后缓存仍在，免重新下载。
 * - 公共目录建不了（未授权 / 建目录失败）时退回应用私有 cacheDir/rootfs-cache。
 * - 保留策略：最多保留 2 个版本的压缩包，超出删最旧（用户指定）。
 */
object RootfsCache {

    /** 环境更新 manifest（用户第三批）：手动检查时拉取，包含最新版本号与下载地址。
     *  M3 起升级为 ed25519 验签 manifest（设计文档 §6），届时仅替换此 URL 与解析逻辑。 */
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/pisces19860207/zhengdao/main/rootfs/manifest.json"

    /** 压缩包文件名里提取版本号：debian-13.7-base-arm64.tar.zst → 13.7 */
    fun versionOf(archiveName: String): String? =
        Regex("""debian-([0-9.]+)-""").find(archiveName)?.groupValues?.get(1)

    /** 当前已装 rootfs 的版本（读 etc/zhengdao-rootfs.info 的 distro=debian-X.Y）。 */
    fun currentVersion(ctx: Context): String? = try {
        val f = File(ctx.filesDir, "rootfs/etc/zhengdao-rootfs.info")
        if (f.isFile) {
            Regex("""distro=debian-([0-9.]+)""").find(f.readText())?.groupValues?.get(1)
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 公共缓存目录；不可用时返回 null（调用方退回应用私有缓存）。 */
    fun publicDir(ctx: Context): File? = try {
        if (ProotLauncher.storageGranted(ctx)) {
            val d = File("/storage/emulated/0/Download/zhengdao/cache")
            if (d.isDirectory || d.mkdirs()) d else null
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 实际使用的缓存目录（公共优先，私有兜底——两者逻辑完全同构）。 */
    fun dir(ctx: Context): File =
        publicDir(ctx) ?: File(ctx.cacheDir, "rootfs-cache").apply { mkdirs() }

    /** 按下载 URL 得到缓存目标文件（文件名取自 URL 末段）。 */
    fun archiveFor(ctx: Context, url: String): File =
        File(dir(ctx), url.substringAfterLast('/'))

    /** 列出缓存中的安装包（.tar.zst / .tar.gz，按修改时间新→旧）。 */
    fun listArchives(ctx: Context): List<File> = try {
        dir(ctx).listFiles { f ->
            f.isFile && (f.name.endsWith(".tar.zst") || f.name.endsWith(".tar.gz"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 只删「非当前版本」的安装包与残片（设置页「清理旧版本缓存」按钮）。
     * 当前版本识别不了时（info 缺失）不动任何 .tar 包，只清 .part 残片。
     * @return 删除的文件数
     */
    fun cleanupNonCurrent(ctx: Context): Int {
        val current = currentVersion(ctx)
        var deleted = 0
        try {
            dir(ctx).listFiles()?.forEach { f ->
                val isArchive = f.name.endsWith(".tar.zst") || f.name.endsWith(".tar.gz")
                when {
                    f.name.endsWith(".part") -> { if (f.delete()) deleted++ }
                    isArchive && current != null && versionOf(f.name) != current ->
                        if (f.delete()) deleted++
                }
            }
        } catch (_: Throwable) {
        }
        return deleted
    }

    /** 可回退的目标：版本 ≠ 当前版本的安装包（新→旧）。 */
    fun rollbackCandidates(ctx: Context): List<File> {
        val current = currentVersion(ctx) ?: return emptyList()
        return listArchives(ctx).filter { versionOf(it.name) != current }
    }

    /** 安装成功后调用：保留最新 keep 个安装包（用户指定 2 个），超出删最旧。 */
    fun pruneKeep(ctx: Context, keep: Int = 2) {
        try {
            listArchives(ctx).drop(keep).forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }
}
