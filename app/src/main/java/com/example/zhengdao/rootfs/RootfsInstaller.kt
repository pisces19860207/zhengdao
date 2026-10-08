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

    /** 全量安装后的 distro 标记（老行为原样保留：标记内容里的发行版串一直是它）。 */
    private const val DEFAULT_DISTRO = "debian-13.7"

    private const val REQUIRED_FREE_BYTES = 2_500_000_000L // 落盘约 1.5–2GB + 余量

    class InstallFailed(message: String) : IOException(message)

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

    /** 启动时清理上次中断的残局（只动 tmp 目录，不碰现有 rootfs 与 home）。 */
    fun cleanupPartial(context: Context) {
        try {
            File(context.filesDir, TMP_NAME).deleteRecursively()
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
     *   全量安装路径请一律用 [envForMarker] 计算它，不要直接把索引的 env 传进来。
     * @param onEntry 每处理一个条目回调一次其路径（调用方自行节流展示）
     */
    fun install(context: Context, archive: File, env: String? = null, onEntry: (String) -> Unit) {
        val files = context.filesDir
        val rootfsDir = File(files, "rootfs")
        val tmpDir = File(files, TMP_NAME)
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        // ── Rust 快路径（v2.0 R2 原型）：数据常驻 native，边界只跨一次 ──
        // 回退纪律（规范 #2）：任何失败 → 落回下方 commons-compress Java 路径
        if (com.example.zhengdao.rust.CoreNative.isRustAvailable()) {
            val rustOk = runCatching {
                val report = com.example.zhengdao.rust.CoreNative.extract(
                    archive.canonicalPath, tmpDir.canonicalPath, null   // SHA 已在调用方校验过
                )
                // Rust 侧统计含目录条目；onEntry 节流由调用方负责
                Log.i(TAG, "Rust 解压完成: ${report.first} 条目 ${report.second / 1048576}MB sha=${report.third.take(12)}")
            }.isSuccess
            if (rustOk) {
                RootfsMarker.write(tmpDir, DEFAULT_DISTRO, env)
                swapIntoPlace(tmpDir, rootfsDir)
                Log.i(TAG, "RootFS 安装完成（Rust 路径）：${rootfsDir.path}")
                return
            }
            Log.w(TAG, "Rust 解压失败，回退 Java 路径")
        }

        extractArchiveJava(archive, tmpDir, onEntry = onEntry)

        // 完成标记（ProotLauncher 依据它判定环境可用；env 行是增量更新的基线）
        RootfsMarker.write(tmpDir, DEFAULT_DISTRO, env)

        swapIntoPlace(tmpDir, rootfsDir)
        Log.i(TAG, "RootFS 安装完成：${rootfsDir.path}")
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
     * 原子替换：旧环境整体让位（home 在独立目录，不受影响——设计文档 §8）。
     * 失败退整树复制，绝不留下"两个 rootfs"或"没有 rootfs"的中间态。
     */
    internal fun swapIntoPlace(tmpDir: File, rootfsDir: File) {
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        if (!tmpDir.renameTo(rootfsDir)) {
            tmpDir.copyRecursively(rootfsDir, overwrite = true)
            tmpDir.deleteRecursively()
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
                try {
                    Os.symlink(entry.linkName, target.absolutePath)
                } catch (t: Throwable) {
                    // 个别 symlink 建不出来不致命：退化为空文件占位，proot 环境仍可用
                    Log.w(TAG, "symlink 失败 $name -> ${entry.linkName}", t)
                    target.writeBytes(ByteArray(0))
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

    /** 防路径穿越：解压目标必须落在 rootfs 临时目录内部。 */
    private fun checkPathInside(root: File, target: File) {
        if (!target.canonicalFile.path.startsWith(root.path)) {
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
