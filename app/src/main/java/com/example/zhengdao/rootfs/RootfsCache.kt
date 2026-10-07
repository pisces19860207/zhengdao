// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

import android.content.Context
import com.example.zhengdao.terminal.ProotLauncher
import java.io.File

/**
 * 安装包缓存（用户第三批；2026-10-08 与 OpenCode 包并到同一个父目录）。
 *
 * - **位置 `/storage/emulated/0/Download/证道/rootfs/`**：与 OpenCode 的包
 *   （`Download/证道/opencode/`，见 OcManager）同一个父目录、彼此并列。
 *   重装 App 后缓存仍在，免重新下载（legacy 存储视图下应用自身可读写，
 *   2026-10-04 实测：target 28 + WRITE 权限的应用进程可直接读写 Download；
 *   不可 bind 的是 proot——两回事）。
 * - **为什么搬家**：OpenCode 的包一直在 `Download/证道/opencode/`，而 rootfs 的包
 *   从第三批起放在 `Download/zhengdao/cache/`（拉丁名），而「本地包自动安装」只认
 *   `Download/证道/` 根目录的固定文件名 —— 两个文件夹互不相通，于是 App **自己下过的包
 *   自己也认不出**，重装一次就要重下一遍 326MB（用户 2026-10-08 报的就是这个）。
 *   现在两者同父目录，且 [findLocalArchive] 能认出缓存里的包。
 * - **旧目录只搬一次**：`Download/zhengdao/cache/` 里的 `debian-*` 文件（含 `.part` 残片）
 *   会就地 rename 过来，因此断点续传的进度也不丢；之后只读新目录。
 * - **只碰 `debian-` 前缀的文件**：共享目录同时也是用户的工作区（`Download/证道` 是默认
 *   工作区），所以列表/清理/保留策略一律限定在 `debian-*` 上，绝不误删用户或 Agent 的
 *   `.tar.zst`（旧代码因为缓存目录是独占目录才敢按后缀删，搬进共享目录后必须收紧）。
 * - 公共目录建不了（未授权 / 建目录失败）时退回应用私有 `cacheDir/rootfs-cache`。
 * - 保留策略：最多保留 2 个版本的压缩包，超出删最旧（用户指定）。
 */
object RootfsCache {

    /** 环境更新 manifest（用户第三批）：手动检查时拉取，包含最新版本号与下载地址。
     *  M3 起升级为 ed25519 验签 manifest（设计文档 §6），届时仅替换此 URL 与解析逻辑。 */
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/pisces19860207/zhengdao/main/rootfs/manifest.json"

    /** 共享存储根：与 OpenCode 包（`Download/证道/opencode/`）同一个父目录，也是默认工作区。 */
    const val SHARED_DIR_PATH = "/storage/emulated/0/Download/证道"

    /** 安装包在共享存储里的子目录（与 `opencode/` 并列）。 */
    private const val CACHE_SUBDIR = "rootfs"

    /** 第三批起至 2026-10-08 的旧缓存目录：只读一次用于搬家，之后不再写入。 */
    private const val LEGACY_DIR_PATH = "/storage/emulated/0/Download/zhengdao/cache"

    /** 归档文件名前缀：只有这种名字才归本模块管。 */
    private const val ARCHIVE_PREFIX = "debian-"

    /** 搬家只做一次（`publicDir` 会被反复调用）。 */
    @Volatile
    private var legacyMigrated = false

    /** 是否本模块的文件（归档、`.part` 残片、边车校验值都是 `debian-` 前缀）。 */
    private fun isOurs(name: String) = name.startsWith(ARCHIVE_PREFIX)

    /** 是否是安装包归档本身。 */
    private fun isArchiveName(name: String) =
        isOurs(name) && (name.endsWith(".tar.zst") || name.endsWith(".tar.gz"))

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

    /** 共享存储根目录（`Download/证道`；用户也可以手动把安装包丢在这里——v0.6.0 起的老约定）。 */
    fun sharedDir(): File = File(SHARED_DIR_PATH)

    /**
     * 把旧缓存目录（`Download/zhengdao/cache`）里的 `debian-*` 文件搬到 [dst]。
     * 同盘 rename 是瞬时的；目标已存在则跳过（不覆盖）。搬空后顺手删掉旧目录。
     * @return 实际搬动的文件数
     */
    fun migrateLegacy(dst: File): Int {
        var moved = 0
        try {
            val legacy = File(LEGACY_DIR_PATH)
            if (!legacy.isDirectory) return 0
            legacy.listFiles()?.forEach { f ->
                if (!f.isFile || !isOurs(f.name)) return@forEach
                val target = File(dst, f.name)
                if (target.exists()) return@forEach
                if (f.renameTo(target)) {
                    moved++
                } else {
                    // 极少数文件系统上 rename 会失败：复制后删源（残片也照此对待，保住续传进度）
                    runCatching { f.copyTo(target, overwrite = true); f.delete() }
                        .onSuccess { moved++ }
                }
            }
            if (legacy.listFiles()?.isEmpty() == true) legacy.delete()
        } catch (_: Throwable) {
        }
        return moved
    }

    /** 公共缓存目录；不可用时返回 null（调用方退回应用私有缓存）。 */
    fun publicDir(ctx: Context): File? = try {
        if (ProotLauncher.storageGranted(ctx)) {
            val root = sharedDir()
            val d = File(root, CACHE_SUBDIR)
            if ((root.isDirectory || root.mkdirs()) && (d.isDirectory || d.mkdirs())) {
                if (!legacyMigrated) {
                    legacyMigrated = true
                    val n = migrateLegacy(d)
                    if (n > 0) RunLog.log("安装包缓存已并入 Download/证道/rootfs（搬了 $n 个文件，含断点残片）")
                }
                d
            } else null
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

    /** 列出缓存中的安装包（`debian-*.tar.zst` / `debian-*.tar.gz`，按修改时间新→旧）。 */
    fun listArchives(ctx: Context): List<File> = try {
        dir(ctx).listFiles { f -> f.isFile && isArchiveName(f.name) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 本地已有的安装包（用户需求：**检测到就自动安装，没有才下载**）。
     *
     * 查找顺序：
     *  ① [preferredName] 指定的固定文件名——先看 `Download/证道` 根目录（用户手动放进来的
     *     老约定），再看缓存目录 `Download/证道/rootfs`；
     *  ② 缓存目录里任意 `debian-*` 归档（App 自己下载过的、从文件选择装过的、从旧目录搬来的）。
     *
     * ② 是 2026-10-08 补上的：此前只认 ①，于是**App 自己下过的包反而认不出**
     * （下载落 `Download/zhengdao/cache`，自动安装只看 `Download/证道` 根目录）。
     *
     * @param minBytes 小于此值视为残件，不算"已有安装包"（默认 100MB）
     */
    fun findLocalArchive(
        ctx: Context,
        minBytes: Long = 100_000_000L,
        preferredName: String? = null,
    ): File? {
        val cands = ArrayList<File>(4)
        if (preferredName != null) {
            cands += File(sharedDir(), preferredName)
            runCatching { cands += File(dir(ctx), preferredName) }
        }
        cands += listArchives(ctx)
        return cands.firstOrNull { it.isFile && it.length() >= minBytes }
    }

    /**
     * 只删「非当前版本」的安装包与残片（设置页「清理旧版本缓存」按钮）。
     * 当前版本识别不了时（info 缺失）不动任何 .tar 包，只清 .part 残片；
     * 名字里读不出版本号的归档也不动（共享目录里宁可少删）。
     * @return 删除的文件数
     */
    fun cleanupNonCurrent(ctx: Context): Int {
        val current = currentVersion(ctx)
        var deleted = 0
        try {
            dir(ctx).listFiles()?.forEach { f ->
                val ver = versionOf(f.name)
                when {
                    isOurs(f.name) && f.name.endsWith(".part") -> { if (f.delete()) deleted++ }
                    isArchiveName(f.name) && current != null && ver != null && ver != current ->
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
