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

    /** 环境更新 manifest（用户第三批）：**降级兜底专用**。
     *  这是没人维护的死文件（自 CI 改为产出 `rootfs-index.json` 起就不再更新，见 ERRATA E-033），
     *  新逻辑一律先走 [INDEX_URL]；只有索引取不到时才回退到这里，拿完版本号也只做"提示"，
     *  不据此下载（老文件里的 url/sha256 可能早已失效）。 */
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/pisces19860207/zhengdao/main/rootfs/manifest.json"

    /** 环境索引（增量下发协议 §4）：版本/内容指纹/全量包/增量补丁的唯一事实来源。
     *  字符串只有一份——抓取逻辑在 [RootfsIndexFetcher]，这里复用同一个常量。 */
    const val INDEX_URL = RootfsIndexFetcher.URL

    /** 共享存储根：与 OpenCode 包（`Download/证道/opencode/`）同一个父目录，也是默认工作区。 */
    const val SHARED_DIR_PATH = "/storage/emulated/0/Download/证道"

    /** 安装包在共享存储里的子目录（与 `opencode/` 并列）。 */
    private const val CACHE_SUBDIR = "rootfs"

    /** 第三批起至 2026-10-08 的旧缓存目录：只读一次用于搬家，之后不再写入。 */
    private const val LEGACY_DIR_PATH = "/storage/emulated/0/Download/zhengdao/cache"

    /** 归档文件名前缀：只有这种名字才归本模块管。 */
    private const val ARCHIVE_PREFIX = "debian-"

    /**
     * 增量补丁文件名前缀（协议 §3：`rootfs-patch-<base>-to-<new>.tar.zst`）。
     * **刻意不归 [ARCHIVE_PREFIX] 管**：补丁是半成品输入，绝不能被
     * [listArchives] / [findLocalArchive] / [pruneKeep] / [cleanupNonCurrent] 当成
     * "可安装的全量包"捡走（否则自动安装会拿几十 MB 的补丁去装环境）。
     */
    private const val DELTA_PREFIX = "rootfs-patch-"

    /** 搬家只做一次（`publicDir` 会被反复调用）。 */
    @Volatile
    private var legacyMigrated = false

    /** 是否是补丁文件（半成品输入，不是可安装包）。 */
    private fun isDeltaName(name: String) = name.startsWith(DELTA_PREFIX)

    /** 是否本模块的文件（归档、`.part` 残片、边车校验值都是 `debian-` 前缀）。 */
    private fun isOurs(name: String) = name.startsWith(ARCHIVE_PREFIX) && !isDeltaName(name)

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

    /**
     * 缓存里**可能**就是索引那个包的本地文件（2026-10-08 用户拍板：本地已有同 sha 的包就别再下 192 MB）。
     *
     * 只做**廉价的定位**（先按 URL 猜文件名，再按字节数筛一遍），**不做 sha 校验**——
     * 理由：调用点有两处，语义不同：
     *  - 检查更新（后台线程）只想据此改提示文案与按钮，不该为了 192 MB 的哈希拖慢"检查"；
     *  - 真正安装前必须逐字节验（[RootfsDownloader.sha256Of] 走 Rust 核心，192 MB 约 1 秒），
     *    对不上就照旧走下载。
     * 因此这里返回的只是"候选"，**绝不能**当成"内容正确"的证明。
     *
     * @param size 索引给的字节数；对不上直接排除（比哈希便宜四个数量级）
     */
    fun localCandidateFor(ctx: Context, url: String, size: Long): File? =
        pickLocalCandidate(archiveFor(ctx, url), listArchives(ctx), size)

    /**
     * [localCandidateFor] 的**纯函数内核**（可单测，不碰 Context）：
     * 先认"按 URL 猜到的那个文件"（名字对上＝最可能），再在缓存列表里按字节数找。
     * `size <= 0` 或都不符 ⇒ null（宁可让用户下载，也不拿大小对不上的包去重装）。
     */
    internal fun pickLocalCandidate(preferred: File, cached: List<File>, size: Long): File? {
        if (size <= 0L) return null
        if (preferred.isFile && preferred.length() == size) return preferred
        return cached.firstOrNull { it.isFile && it.length() == size }
    }

    /**
     * [pickExpectedSha] 的结果：**用哪个校验值** + 它的**来源**（进日志/状态行）+ 本地边车是否已过期。
     *
     * @param sha 期望的 sha256（null = 一个来源都拿不到 ⇒ 调用方按"没有校验值"处理）
     * @param source 人类可读的来源（"索引" / "本地 .sha256" / "线上 .sha256" / "无"）
     * @param staleSidecar 本地 `.sha256` 伴生文件与索引不一致（校验通过后要把它重写成索引值）
     */
    internal data class ShaChoice(
        val sha: String?,
        val source: String,
        val staleSidecar: Boolean = false,
    )

    /**
     * **该拿哪个 sha256 去校验这个本地包**（纯函数，可单测，不碰 Context）。
     *
     * 背景（2026-10-08 真机假失败，见 ERRATA E-053）：用户手机终端页弹出
     * `安装失败：SHA256 校验失败：actual=d80639e7dc5c…`。原因不是包坏了、也不是网络问题，
     * 而是这条路径**优先信任 `Download/证道/rootfs/debian-….tar.zst.sha256` 这个本地边车**，
     * 而那个文件是 19:56 留下的**过期副本**（`d12cd1d3…`）；**全仓没有任何代码会写它**
     * （只读不写＝没人维护的死文件，E-033 记过同款死文件 manifest），远端一换包它就必然变馊
     * ⇒ 把"包是对的"误判成"校验失败"，用户看到一条完全无法理解的红字。
     *
     * 决策（复核顺序：先问签名过的索引，再问这个文件自己）：
     *  ① 本地包**长得就是索引那个包**（文件名 == 索引 url 末段 且 字节数 == 索引 size，
     *     任一项未知则跳过该项比对）⇒ **用索引的 sha256**：索引是唯一被 Ed25519 签名背书的
     *     来源（E-052），边车只是个没人维护的副本；
     *  ② 否则（老版本 / 换过名字 / 大小不同）⇒ 回到"按这个文件自己的说法"：
     *     本地 `.sha256` 边车 → 线上 `$url.sha256`。**刻意不拿索引去卡**：用户留着旧包
     *     本来就可能要装旧版本，索引描述的是"最新那个包"，不是"这个文件"。
     *
     * @param localSize 本地包字节数；`<= 0` = 未知（下载路径在文件还没落盘时就是这样）
     */
    internal fun pickExpectedSha(
        localName: String,
        localSize: Long,
        indexUrl: String?,
        indexSize: Long,
        indexSha: String?,
        sidecar: String?,
        onlineSha: String?,
    ): ShaChoice {
        val idxSha = indexSha?.trim()?.takeIf { it.isNotEmpty() }
        val side = sidecar?.trim()?.takeIf { it.isNotEmpty() }
        val online = onlineSha?.trim()?.takeIf { it.isNotEmpty() }
        val indexName = indexUrl?.substringAfterLast('/')?.substringBefore('?')
        val sameAsIndex = idxSha != null && indexName != null && indexName == localName &&
            (localSize <= 0L || indexSize <= 0L || localSize == indexSize)
        if (sameAsIndex) {
            return ShaChoice(
                sha = idxSha,
                source = "索引",
                staleSidecar = side != null && !side.equals(idxSha, ignoreCase = true),
            )
        }
        if (side != null) return ShaChoice(side, "本地 .sha256")
        if (online != null) return ShaChoice(online, "线上 .sha256")
        return ShaChoice(null, "无")
    }

    /**
     * 增量补丁的缓存目标（协议 §3 的固定命名，与全量包同目录但前缀不同）。
     *
     * 名字直接取自 URL 末段（`rootfs-patch-<base>-to-<new>.tar.zst`），因此**同一个补丁
     * 重复下载会覆盖同一个文件**，不会越积越多；索引给的 URL 若是镜像地址也照取名字。
     */
    fun deltaFor(ctx: Context, url: String): File {
        val name = url.substringAfterLast('/').substringBefore('?')
            .takeIf { it.startsWith(DELTA_PREFIX) && it.length > DELTA_PREFIX.length }
            ?: "rootfs-patch-unknown.tar.zst"
        return File(dir(ctx), name)
    }

    /**
     * 丢弃一个增量补丁（增量成功、或失败回退全量之后调用）。
     * 只认缓存目录内、`rootfs-patch-` 前缀的文件；连带清掉 `.part` 残片。
     * 越界/名字不对一律不动（共享目录里宁可少删）。
     */
    fun cleanupDelta(ctx: Context, f: File) {
        try {
            val cacheDir = dir(ctx).canonicalFile
            val target = f.canonicalFile
            val inside = target.path.startsWith(cacheDir.path + File.separator)
            if (!inside || !target.name.startsWith(DELTA_PREFIX)) {
                RunLog.log("拒绝清理非补丁缓存文件：${f.path}")
                return
            }
            target.delete()
            File(target.path + ".part").delete()
        } catch (t: Throwable) {
            RunLog.log("清理补丁缓存失败（忽略）：${f.name}（${t.message}）")
        }
    }

    /** 列出缓存中的安装包（`debian-*.tar.zst` / `debian-*.tar.gz`，按修改时间新→旧）。
     *  注意：`rootfs-patch-*` 增量补丁**不在其中**（前缀纪律，见 [DELTA_PREFIX]）。 */
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
        // preferredName 也得过前缀关：万一调用方传进来一个补丁名（rootfs-patch-*），
        // 绝不能把几十 MB 的半成品当"本地已安装包"直接装掉。
        if (preferredName != null && !isDeltaName(preferredName)) {
            cands += File(sharedDir(), preferredName)
            runCatching { cands += File(dir(ctx), preferredName) }
        }
        cands += listArchives(ctx)
        return cands.firstOrNull { it.isFile && !isDeltaName(it.name) && it.length() >= minBytes }
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

    /** 可回退的目标：版本 ≠ 当前版本的安装包（新→旧）。
     *  `rootfs-patch-*` 补丁不在其中（[listArchives] 已按前缀排除，这里再加一道闸）。 */
    fun rollbackCandidates(ctx: Context): List<File> {
        val current = currentVersion(ctx) ?: return emptyList()
        return listArchives(ctx).filter { !isDeltaName(it.name) && versionOf(it.name) != current }
    }

    /** 安装成功后调用：保留最新 keep 个安装包（用户指定 2 个），超出删最旧。 */
    fun pruneKeep(ctx: Context, keep: Int = 2) {
        try {
            listArchives(ctx).drop(keep).forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }
}
