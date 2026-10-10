// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准与官方文档：
//   tar 归档格式（POSIX 1003.1-1988 ustar 及 GNU 扩展），经 Apache Commons Compress
//   官方文档使用 TarArchiveInputStream；zstd 魔数 0x28 B5 2F FD（zstd 官方规范）与
//   gzip 魔数 0x1F 8B（RFC 1952）用于自动识别压缩格式；
//   符号链接/权限经 android.system.Os（symlink/chmod，NDK POSIX 封装）。
package com.example.zhengdao.rootfs

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RootFS 解压安装器（设计文档 §6/§8）：
 * - 解压到独立 tmp 目录 → 写完成标记 → 原子替换 rootfs 目录（解压原子性，审计项）；
 * - 启动时清理上次中断的残局；
 * - 硬链接一律按“复制内容”落地（实测坑 #4：SELinux 拒绝非 root 应用创建硬链接，
 *   与 UV_LINK_MODE=copy、proot --link2symlink 同一因果）；
 * - 归档格式按魔数自动识别：zstd（主格式）、gzip（兼容格式），其余按纯 tar（增量补丁）。
 *
 * 增量更新（[RootfsDelta]）复用本对象的三块内核：[extractArchiveJava]（解包 + 类型处理）、
 * [swapIntoPlace]（原子替换）、[openTar]（读补丁元数据），保证两条路径的落盘语义一致。
 */
object RootfsInstaller {

    private const val TAG = "RootfsInstaller"

    /** 临时解包目录名。增量更新（[RootfsDelta]）复用同一个目录名，故对包内可见。 */
    internal const val TMP_NAME = "rootfs.tmp"

    /**
     * 旧环境让位目录名（#11 / E-078）：替换时先把 `rootfs` 改名到这里，新树就位成功后再删。
     * 与 [TMP_NAME] 一样落在 `filesDir` 下（同一文件系统 ⇒ 改名是原子元数据操作）。
     */
    internal const val OLD_NAME = "rootfs.old"

    /** 全量安装后的 distro 标记（老行为原样保留：标记内容里的发行版串一直是它）。 */
    private const val DEFAULT_DISTRO = "debian-13.7"

    private const val REQUIRED_FREE_BYTES = 2_500_000_000L // 落盘约 1.5–2GB + 余量

    /**
     * Rust 核心完整性失败的文案标记（`rust/core/src/extract.rs` 的 `ExtractError::ShaMismatch`：
     * `SHA256 不匹配: 期望 … 实际 …`）。命中它 = 盘上这份包不是我们要装的那份，
     * **不回退 Java 路径**（那里的解压不校验 sha256），而是把失败原样抛给用户。
     */
    private const val SHA_MISMATCH_MARK = "SHA256 不匹配"

    /**
     * Rust 核心「这份归档一条都没解出来」的文案标记（`rust/core/src/extract.rs` 的
     * `ExtractError::EmptyArchive`：`归档不含任何条目: 可落盘 0 条（跳过 N 条）`）。
     *
     * BUG-1（2026-10-10 / E-083）：**命中它同样不回退 Java 路径**——包是空的，回退只是再解一遍
     * 同一份空包；正确做法是和 Java 路径一样把安装判失败（那里有 `extracted == 0` 兜底）。
     * 不这么做的后果是：0 条目被当成装成功，`RootfsMarker.write` + `swapLocked` 之后
     * 用户得到一个"装好了"的空环境。
     */
    private const val EMPTY_ARCHIVE_MARK = "归档不含任何条目"

    class InstallFailed(message: String) : IOException(message)

    // ── 进程级安装锁（#11 / E-078）───────────────────────────────────────────
    //
    // 为什么锁必须在这里：环境的"替换"是全 App 唯一能毁掉已装环境的操作，而此前各入口
    // 互不设防——设置页四条长流程各发各的（全仓唯一一处 busy 门控只护「检查环境更新」，
    // 见 `ui/SettingsScreen.kt:993`），终端页三处用的是**实例字段** `installing`
    // （`TerminalActivity.kt:56-57`，只活在那个 Activity 里），增量更新与全量安装之间
    // 更是毫无关系。两个任务叠在一起时「解包 A」与「替换 B」交错 ⇒ 用户看到的是
    // "装了两次，最后环境是随机的"。
    //
    // 语义选择：**拒绝，不排队**。排队意味着第二个任务会在第一个刚换完环境时立刻再换一次，
    // 用户点两下就得等满两个周期，结果却与只点一次没有区别；直接告诉他"已有任务在跑"
    // 才是他要的信息。
    private val lock = AtomicBoolean(false)

    private val _installing = mutableStateOf(false)

    /** Compose 可读的进行中状态（设置页 / 终端页据此禁用按钮）。 */
    val installing: State<Boolean> get() = _installing

    /** 非 Compose 调用方（后台线程、`ui/InstallFlow`）用。 */
    fun isInstalling(): Boolean = lock.get()

    /**
     * 进程级互斥：重入直接抛 [InstallFailed]（文案原样出现在设置页状态行 / 终端横幅里）。
     *
     * 用 `AtomicBoolean` 而不是 `synchronized`：安装耗时以分钟计，这把锁只该被"看一眼"
     * （按钮 enabled、入口预检），绝不能让别人为了看它而阻塞两分钟。
     */
    internal fun <T> withInstallLock(what: String, block: () -> T): T {
        if (!lock.compareAndSet(false, true)) {
            throw InstallFailed("已有安装/更新任务在跑，本次「$what」未执行（等它结束后再试）")
        }
        _installing.value = true
        try {
            return block()
        } finally {
            _installing.value = false
            lock.set(false)
        }
    }

    /** 存储预检（设计文档 §2：按落盘体积校验，不足时给出明确差额）。 */
    fun ensureFreeSpace(context: Context, archiveBytes: Long) {
        val stat = StatFs(context.filesDir.absolutePath)
        val free = stat.availableBytes
        val needed = REQUIRED_FREE_BYTES + archiveBytes
        if (free < needed) {
            throw InstallFailed(
                "存储空间不足：本次安装约需 ${(needed + 511) / 1_000_000}MB，当前可用 ${(free + 511) / 1_000_000}MB"
            )
        }
    }

    /**
     * 增量更新的存储预检（**加固-4**，2026-10-10 / E-085）。
     *
     * 为什么不只传补丁大小：增量的正常路径是「整树**硬链接**克隆」（几乎不额外占空间），
     * 但 [RootfsDelta.cloneTree] 在硬链接失败时会退化成**整树复制**（设计文档实测坑 #4：
     * SELinux 拒非 root 建硬链接）—— 那一刻**旧树与新树同时在盘上**。[REQUIRED_FREE_BYTES]
     * 这个常量是按"一份树 + 余量"估的，树的实际大小超过它时就不够了；这里按**实测树大小**
     * 补上差额，树没超过常量时行为与从前**完全一致**（不改变宽松度，只堵住树变大后的缺口）。
     *
     * 调用方拿不到可靠树大小时传 0 —— 那就退回旧的常量口径，不因为"量不出来"而拒装。
     */
    fun ensureFreeSpaceForDelta(context: Context, patchBytes: Long, treeBytes: Long) {
        val extra = (treeBytes - REQUIRED_FREE_BYTES).coerceAtLeast(0L)
        ensureFreeSpace(context, patchBytes + extra)
    }

    /**
     * 启动时清理上次中断的残局（只动 tmp 与替换备份，不碰现有 rootfs 与 home）。
     *
     * `rootfs.old`（#11 / E-078）是替换过程留下的旧树备份，两种残局要分开处理：
     * - `rootfs` 在位 ⇒ 替换其实已经成功，备份只是没来得及删的垃圾，删掉（否则白占 1.5–2GB）；
     * - `rootfs` 缺失 ⇒ 上次替换死在"让位之后、就位之前"，**此时备份就是用户唯一的环境**，
     *   必须改名复原，绝不能跟着 tmp 一起删。
     */
    fun cleanupPartial(context: Context) {
        try {
            File(context.filesDir, TMP_NAME).deleteRecursively()
            val rootfs = File(context.filesDir, "rootfs")
            val backup = File(context.filesDir, OLD_NAME)
            if (backup.exists()) {
                if (rootfs.exists()) {
                    backup.deleteRecursively()
                } else if (backup.renameTo(rootfs)) {
                    Log.w(TAG, "上次替换中断，已从 $OLD_NAME 复原环境：${rootfs.path}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "清理残局失败（忽略）", t)
        }
    }

    /**
     * 决定全量安装后要不要把索引的 env 写进标记 —— **信任锚**（用户 2026-10-08 追加的硬规则）。
     *
     * 为什么不无条件信索引：索引（rootfs-index.json）由 CI 在流水线里更新，一旦 CI 半途失败
     * 或上传错序，索引里的 `env`/`sha256` 可能指向"还不是这次下载到的那个包"的版本。
     * 而标记里的 `env=` 是后续**增量更新的唯一基线**：写错了，下次要么拿一个基线对不上的补丁
     * （被 [RootfsDelta.canApply] 挡住），要么更糟——基线"看起来"匹配但树内容不是那个版本。
     *
     * 规则：**只有"实际校验通过的 sha256" == "索引里的 sha256"时，才认索引的 env**；
     * 索引取不到 / 边车校验值取不到 / 两者不一致 ⇒ 照常安装，但**不写 `env=` 行**，
     * 于是下次检查更新看到"本地无版本记录"⇒ 老实全量一次。宁可多下一次，不可错走增量。
     *
     * @param indexEnv   索引里的 env id
     * @param indexSha256 索引里的完整包 sha256（注意：不是补丁的）
     * @param actualSha256 本次下载**实际校验通过**的 sha256（[RootfsDownloader.download] 的返回值）
     * @return 要写进标记的 env id；null = 不写 `env=` 行
     */
    internal fun envForMarker(indexEnv: String?, indexSha256: String?, actualSha256: String?): String? {
        if (indexEnv.isNullOrBlank() || indexSha256.isNullOrBlank() || actualSha256.isNullOrBlank()) return null
        return if (indexSha256.trim().equals(actualSha256.trim(), ignoreCase = true)) indexEnv.trim().lowercase() else null
    }

    /**
     * 「重装同一份包」时该不该把已有的 env 写回（2026-10-08 真机 bug 的修法）。
     *
     * 「修复环境」与「回退版本」都是拿**本地缓存里那个包**重解压一次（不联网），此前一路走
     * `install(ctx, archive) { }`——不传 env ⇒ 标记里的 `env=` 行被抹掉，代价是**下次更新必然全量**：
     * 真机实测 21:26 检查＝「已是最新版本（13.7，环境 51e1cc0c32f099aa）」，21:27:54 跑「修复环境」，
     * 21:56 再检查＝「本地已安装 13.7，但**缺少环境指纹记录**」⇒ 用户按着提示"修环境"，
     * 反而把自己的增量基线修没了（同一份内容，却要再下一次 192 MB）。
     *
     * 判定规则与 [envForMarker] 同一哲学（**宁可少写一次，也不能写错基线**）：
     * 只有当标记里记着"当初那个包的 sha256"[RootfsMarker.Data.archiveSha256]、且它与
     * **本次要装的那个包的 sha256**（调用方用 [RootfsDownloader.sha256Of] 现算）逐字符相等时，
     * 才认为"树内容与当前 env 描述的正是同一份东西"，于是 env 原样写回；
     * 标记没有这一行（老安装）/包 sha 取不到/对不上 ⇒ 返回 null，**照装但不写 env**（下次老实全量）。
     *
     * @param rootfsDir 当前已装环境的目录（`<filesDir>/rootfs`），读它的标记文件
     * @param archiveSha256 本次要装的归档 sha256；null/空白 = 不确定 ⇒ 直接放弃推断
     */
    internal fun envForReinstall(rootfsDir: File, archiveSha256: String?): String? {
        val sha = archiveSha256?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val recorded = RootfsMarker.installedArchiveSha256(rootfsDir) ?: return null
        if (!recorded.equals(sha, ignoreCase = true)) return null
        return RootfsMarker.installedEnv(rootfsDir)
    }

    /**
     * 解压归档并安装。
     *
     * 参数顺序说明（协议正文写的是"env 放最后"）：**env 必须排在 onEntry 之前**。
     * Kotlin 的尾随 lambda 语法总是绑定到最后一个参数，所以 env 若在末尾，
     * 现有的 `install(ctx, archive) { }` 调用点（TerminalActivity、SettingsScreen、
     * CoreNativeExtractInstrumentedTest 共 4 处）会把 lambda 当成 env，直接编译不过。
     * 放在 onEntry 之前，所有老调用点一行都不用改，新调用点写成
     * `install(ctx, archive, envToWrite) { }` 也很顺。
     *
     * @param env 本次装入内容的 env id（增量协议 §1 的内容指纹）。**不确定就传 null**：
     *   标记里不写 `env=` 行，下次更新检测到"无版本记录"自然走全量——宁可多下一次，不可错走增量。
     *   全量安装路径请一律用 [envForMarker] 计算它，不要直接把索引的 env 传进来；
     *   「重装同一份包」（修复/回退）用 [envForReinstall] 计算它。
     * @param archiveSha256 本次装入的那个**全量包**的 sha256（写进标记，供下次重装做"同源"判定）；
     *   增量安装、用户自选文件等说不清来源的路径传 null。
     * @param onEntry 每处理一个条目回调一次其路径（调用方自行节流展示）
     */
    fun install(
        context: Context,
        archive: File,
        env: String? = null,
        archiveSha256: String? = null,
        onEntry: (String) -> Unit,
    ) = withInstallLock("安装环境") {
        val files = context.filesDir
        val rootfsDir = File(files, "rootfs")
        val tmpDir = File(files, TMP_NAME)
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        // ── Rust 快路径（v2.0 R2 原型）：数据常驻 native，边界只跨一次 ──
        // 回退纪律（规范 #2）：任何失败 → 落回 commons-compress Java 路径；
        // 唯一例外是**完整性失败**（见 extractArchive），它必须硬失败。
        // 把"这份包应当是什么 sha256"交给 Rust 对账：信任锚落在**恰好被解压的那些字节**上，
        // 而不是另一次遍历的结果；没有独立期望值的路径（用户自选文件等）传 null = 只算不校验。
        val usedRust = extractArchive(archive, tmpDir, expectedSha256 = archiveSha256, onEntry = onEntry)

        // 完成标记（ProotLauncher 依据它判定环境可用；env 行是增量更新的基线）
        RootfsMarker.write(tmpDir, DEFAULT_DISTRO, env, archiveSha256 = archiveSha256)

        // 锁已在手（#11）：走无锁的替换内核，别用自带锁的 [swapIntoPlace] 自锁自己
        swapLocked(tmpDir, rootfsDir)
        Log.i(
            TAG,
            if (usedRust) "RootFS 安装完成（Rust 路径）：${rootfsDir.path}"
            else "RootFS 安装完成：${rootfsDir.path}",
        )
    }

    /**
     * 解包归档（**Rust 优先，失败回退 Java**）——[install] 与 [RootfsDelta] 共用同一条路径。
     *
     * 补丁包也走这里（带 [skipNames]），所以"全量走 Rust、补丁走 Java"的分叉到此为止（E-050）。
     *
     * @param skipNames 需要跳过的成员名（去掉 `./` 前缀；补丁元数据 `.zhengdao-patch-info`）
     * @param expectedSha256 非 null 时由 Rust 对账归档整体 sha；**不匹配不回退 Java**
     *   —— 回退等于把校验降级成"没校验"（同一份坏包再解一遍），见 [install] 的注释
     * @throws InstallFailed 可落盘条目为 0（Rust 报 `EmptyArchive`，或旧 .so 回了 0 条目）：
     *   与 Java 路径的 `extracted == 0` 兜底对齐，**同样不回退**（BUG-1 / E-083）
     * @return true = 走了 Rust，false = 回退到 Java 路径
     */
    internal fun extractArchive(
        archive: File,
        destDir: File,
        skipNames: Set<String> = emptySet(),
        expectedSha256: String? = null,
        onEntry: (String) -> Unit = {},
    ): Boolean {
        if (com.example.zhengdao.rust.CoreNative.isRustAvailable()) {
            val rust = runCatching {
                val report = com.example.zhengdao.rust.CoreNative.extract(
                    archive.canonicalPath,
                    destDir.canonicalPath,
                    expectedSha256,
                    skipNames.toList(),
                )
                // BUG-1（2026-10-10）：Rust 报「可落盘 0 条目」= 这份包没东西可装，绝不回退 Java。
                // 新 .so 会在 Rust 侧就报 EmptyArchive（下面按 EMPTY_ARCHIVE_MARK 分流）；这条
                // 计数判断是给「新 Kotlin + 旧 .so」留的保险——旧 .so 会把 0 条目当成功回。
                if (report.first <= 0L) {
                    throw InstallFailed("压缩包不含任何条目或格式不受支持（Rust 核心）")
                }
                Log.i(TAG, "Rust 解压完成: ${report.first} 条目 ${report.second / 1048576}MB sha=${report.third.take(12)}")
            }
            if (rust.isSuccess) return true
            val err = rust.exceptionOrNull()
            // 0 条目：上面那条计数判断抛的（旧 .so 路径）
            if (err is InstallFailed) throw err
            if (err is IllegalStateException && err.message?.contains(EMPTY_ARCHIVE_MARK) == true) {
                throw InstallFailed("压缩包不含任何条目或格式不受支持（Rust 核心）：${err.message}")
            }
            // 完整性失败**不回退**：盘上这份包不是我们要装的那份（下载后被改动、缓存串了包，
            // 或校验通过到解压之间被换掉）。回退 Java 只会把同一份坏包再解一遍，而 Java 路径
            // 根本不校验 sha256 ⇒ 那就等于把校验悄悄降级成"没校验"。
            if (err is IllegalStateException && err.message?.contains(SHA_MISMATCH_MARK) == true) {
                throw InstallFailed("安装包完整性校验失败（Rust 核心）：${err.message}")
            }
            // 旧 .so 没有 nativeExtractSkip 符号时也落到这里（JNI 查找失败 → UnsatisfiedLinkError），
            // 于是"新 Kotlin + 旧 so"只让带跳过的补丁路径回退 Java，全量入口照常。
            // ⚠️ 注意 `BadArchive` 必须留在这条回退里：Rust 只认 zstd/gzip，第三种魔数一律
            //    `BadArchive("无法识别的压缩格式")`，而 Java 侧 `formatOf` 把未知魔数按**纯 tar**
            //    处理（离线手选包就是纯 tar）——把它列进"不回退"会直接废掉纯 tar 包。
            Log.w(TAG, "Rust 解压失败，回退 Java 路径（skip=${skipNames.size}）", err)
        }
        extractArchiveJava(archive, destDir, skipNames, onEntry)
        return false
    }

    /**
     * Java 路径解包内核：支持 zstd / gzip / **纯 tar**（未知魔数按 tar 处理，见 [formatOf]）。
     *
     * 抽出来是为了让 [RootfsDelta] 复用同一套类型处理（目录/软链/硬链/普通文件 + chmod +
     * 防穿越），避免增量路径出现第二份"略有不同"的解包逻辑。
     *
     * @param skipNames 需要跳过的成员名（调用方给的是**去掉 `./` 前缀**后的名字）
     */
    internal fun extractArchiveJava(
        archive: File,
        destDir: File,
        skipNames: Set<String> = emptySet(),
        onEntry: (String) -> Unit,
    ) {
        destDir.mkdirs()
        val canonicalRoot = destDir.canonicalFile
        var extracted = 0

        openTar(archive).use { tar ->
            val pendingHardLinks = mutableListOf<TarArchiveEntry>()
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                if (name.isNotEmpty() && name != "." && name !in skipNames) {
                    val target = File(destDir, name)
                    checkPathInside(canonicalRoot, target)
                    onEntry("./$name")
                    extractEntry(entry, name, target, destDir, tar, pendingHardLinks)
                    extracted++
                }
                entry = tar.nextTarEntry
            }
            // 二阶段：补齐“源文件在归档中后置”的前向硬链接
            for (hl in pendingHardLinks) {
                val src = File(destDir, hl.linkName.removePrefix("./"))
                val dst = File(destDir, hl.name.removePrefix("./"))
                if (src.isFile) {
                    dst.delete() // 先断开可能存在的硬链接，别写穿到旧树
                    src.copyTo(dst, overwrite = true)
                }
            }
        }

        // 空归档兜底：纯 tar 分支不再靠"无法识别的格式"报错，改由"一条都没解出来"把
        // 垃圾文件挡在这里（否则会装出一个空环境还报成功）。
        if (extracted == 0) throw InstallFailed("压缩包不含任何条目或格式不受支持")
    }

    /**
     * 打开归档为 tar 流（按魔数自动套 zstd/gzip 解压层，纯 tar 直接用原流）。
     * 调用方负责 `use { }` 关闭——链路一关到底，不会泄漏 fd。
     */
    internal fun openTar(archive: File): TarArchiveInputStream {
        val buffered = BufferedInputStream(FileInputStream(archive), 512 * 1024)
        return try {
            val decompressed: InputStream = when (formatOf(peekMagic(buffered))) {
                Format.ZSTD -> ZstdCompressorInputStream(buffered)
                Format.GZIP -> GzipCompressorInputStream(buffered, false)
                Format.TAR -> buffered
            }
            TarArchiveInputStream(decompressed, "UTF-8")
        } catch (t: Throwable) {
            runCatching { buffered.close() }
            throw t
        }
    }

    /**
     * 自带锁的原子替换入口（#11 / E-078）：[install] 与 [RootfsDelta] 内部已持锁，走的是
     * [swapLocked]；这里是给"只换目录"的调用方（含单测）用的门面。
     */
    internal fun swapIntoPlace(tmpDir: File, rootfsDir: File) =
        withInstallLock("替换环境") { swapLocked(tmpDir, rootfsDir) }

    /**
     * 替换实现（**调用方必须已持有安装锁**，见 [withInstallLock]）。
     *
     * 顺序是「旧树改名让位 → 新树改名就位 → 删旧树」，**不再先 `deleteRecursively()`**：
     * 旧实现在删除之后、改名之前被打断（进程被杀 / 存储掉线 / 用户强退），用户手里那份
     * 能用的环境就没了——而这是全 App 唯一会把"能用"变成"什么都没有"的一步。
     * 同目录改名是 O(1) 元数据操作，新旧两棵树同时存在**不多占一个字节**：
     * 解包阶段本来就是「旧 rootfs + rootfs.tmp」并存，峰值没变。
     *
     * 失败语义：让位失败 ⇒ 原样不动直接抛；就位失败 ⇒ 先把可能只拷了一半的新树删掉，
     * 再把旧树放回去；**放不回也要在文案里给出 `rootfs.old` 的路径**，绝不静默。
     */
    internal fun swapLocked(tmpDir: File, rootfsDir: File) {
        if (!tmpDir.isDirectory) throw InstallFailed("临时环境不存在，已放弃替换：${tmpDir.path}")
        val parent = rootfsDir.parentFile
            ?: throw InstallFailed("环境目录没有父目录，已放弃替换：${rootfsDir.path}")
        val backup = File(parent, OLD_NAME)

        // 上次替换被打断的两桩残局：rootfs 在位 ⇒ 备份是垃圾；rootfs 缺失 ⇒ 备份是用户仅有的环境
        if (!rootfsDir.exists() && backup.exists()) {
            if (backup.renameTo(rootfsDir)) {
                Log.w(TAG, "发现上次替换留下的旧环境，已复原：${rootfsDir.path}")
            }
        }
        if (backup.exists()) backup.deleteRecursively()

        val hadOld = rootfsDir.exists()
        if (hadOld && !rootfsDir.renameTo(backup)) {
            throw InstallFailed("旧环境让位失败（未改动任何内容），请重启 App 后重试：${rootfsDir.path}")
        }
        val placed = tmpDir.renameTo(rootfsDir) || runCatching {
            tmpDir.copyRecursively(rootfsDir, overwrite = true)
            true
        }.getOrDefault(false)
        if (!placed) {
            rootfsDir.deleteRecursively() // 清掉可能只拷了一半的新树
            val rolledBack = !hadOld || backup.renameTo(rootfsDir)
            throw InstallFailed(
                when {
                    !hadOld -> "环境替换失败（原本就没有已装环境，未留下中间态）"
                    rolledBack -> "环境替换失败，已回滚到原环境：${rootfsDir.path}"
                    else -> "环境替换失败，旧环境已保留在 ${backup.path}（重启 App 会自动复原）"
                }
            )
        }
        tmpDir.deleteRecursively()
        if (hadOld && backup.exists() && !backup.deleteRecursively()) {
            Log.w(TAG, "旧环境目录删不掉（不影响使用，下次启动会再试）：${backup.path}")
        }
    }

    private enum class Format { ZSTD, GZIP, TAR }

    private fun extractEntry(
        entry: TarArchiveEntry,
        name: String,
        target: File,
        tmpDir: File,
        tar: TarArchiveInputStream,
        pendingHardLinks: MutableList<TarArchiveEntry>,
    ) {
        when {
            entry.isDirectory -> {
                target.mkdirs()
                chmod(target, entry.mode)
            }
            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                target.delete()
                // 加固-1（E-085）：**linkname 也要在树内**。tar 规范允许绝对路径（按 guest 根
                // 解释）与 `../`（按链接所在目录解释），两者都能把链接指到解压根之外 —— 同一份包
                // 里的后续成员、或 guest 内的程序一跟随，就是一条写出环境目录的通道。
                // 越界时不建链接、退化成空文件占位（与"建不出来"同一条退路，环境仍可用）。
                if (!PathGuard.linkStaysInside(tmpDir, target, entry.linkName)) {
                    Log.w(TAG, "symlink 目标越界，改为空文件占位: $name -> ${entry.linkName}")
                    target.writeBytes(ByteArray(0))
                } else {
                    try {
                        Os.symlink(entry.linkName, target.absolutePath)
                    } catch (t: Throwable) {
                        // 个别 symlink 建不出来不致命：退化为空文件占位，proot 环境仍可用
                        Log.w(TAG, "symlink 失败 $name -> ${entry.linkName}", t)
                        target.writeBytes(ByteArray(0))
                    }
                }
            }
            entry.isLink -> {
                // 硬链接 → 复制内容落地；源文件若尚未解出（前向引用），登记到二阶段
                val src = File(tmpDir, entry.linkName.removePrefix("./"))
                target.parentFile?.mkdirs()
                if (src.isFile) {
                    target.delete() // 先断链接再写，避免写穿到旧 rootfs 的同一 inode
                    src.copyTo(target, overwrite = true)
                } else {
                    pendingHardLinks.add(entry)
                }
            }
            entry.isFIFO || entry.linkFlag == TarConstants.LF_CHR || entry.linkFlag == TarConstants.LF_BLK -> {
                // 设备节点与 FIFO 在 proot -b /dev 的方案下不需要真实创建，跳过
            }
            else -> {
                target.parentFile?.mkdirs()
                // 先删再写：增量路径里目标可能是与旧树硬链接共享 inode 的文件，
                // 直接 outputStream() 会改到正在被 proot 使用的旧环境（原子性就没了）。
                target.delete()
                target.outputStream().use { tarStream -> tar.copyTo(tarStream) }
                chmod(target, entry.mode)
            }
        }
    }

    /**
     * 按魔数识别压缩格式：zstd（0x28 B5 2F FD，主格式）或 gzip（0x1F 8B，兼容格式）；
     * **其余一律按纯 tar 处理**（增量补丁就是未压缩 tar）。真正的垃圾文件不再靠"认不出格式"
     * 挡掉，而是由 [extractArchiveJava] 末尾的"一条都没解出来"兜底报错。
     */
    private fun formatOf(magic: ByteArray): Format = when {
        magic.size >= 4 &&
            magic[0] == 0x28.toByte() && magic[1] == 0xB5.toByte() &&
            magic[2] == 0x2F.toByte() && magic[3] == 0xFD.toByte() -> Format.ZSTD
        magic.size >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> Format.GZIP
        else -> Format.TAR
    }

    private fun peekMagic(buffered: BufferedInputStream): ByteArray {
        buffered.mark(4)
        val magic = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = buffered.read(magic, read, 4 - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        if (read < 4) throw InstallFailed("压缩包过小或已损坏")
        return magic
    }

    /**
     * 防路径穿越：解压目标必须落在 rootfs 临时目录内部。
     *
     * 加固-3（E-085）：判据从**字符串前缀**改成**路径组件**（[PathGuard.isInside]）。
     * 原写法 `target.canonicalFile.path.startsWith(root.path)` 会把 `/…/rootfs-evil`
     * 判成"在 `/…/rootfs` 内"；同仓里本来就是对的写法见 `RootfsCache` 的
     * `startsWith(cacheDir.path + File.separator)`，现在两处口径统一到了 [PathGuard]。
     */
    private fun checkPathInside(root: File, target: File) {
        if (!PathGuard.isInside(root, target)) {
            throw InstallFailed("压缩包含越界路径: ${target.path}")
        }
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode and 4095) // 4095 = 0o7777（八进制 → 十进制）
        } catch (t: Throwable) {
            Log.w(TAG, "chmod 失败: ${file.path}", t)
        }
    }
}
