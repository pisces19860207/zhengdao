// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §2「tar/tgz/txz/zst 走 Rust nativeExtract（不重编 .so）」＋
// §3「≤2GB／默认不覆盖」。
//
// 为什么先解到**暂存目录**再搬进去（而不是直接解到目标目录）：
// · `.so` 的出口只有「解到目录」，它不认「同名就停」也不认字节上限，而本批不许重编它；
// · 直接解到目标目录 ⇒ 撞名时会**静默覆盖**（`File::create` 语义），这正是负例②要红的行为；
// · 解到 `目标/.zd-extract-<uuid>/`（同一分区 ⇒ 搬迁是 rename，不复制数据）之后，
//   在搬之前就能判「同名」「超限」，判不过就把暂存整个删掉——目标目录一个字节都没脏。
// 已知代价（交付报告里明写）：`.tar.zst` / `.tar.xz` 的总量只能**落暂存后**判，
// 因为 Java 侧没有 zstd/xz 解码器做预扫描；`.tar` / `.tar.gz` / `.tgz` 走 [TarScan] 预判，落盘前就拒。
package com.example.zhengdao.baopu.archive

import com.example.zhengdao.rust.CoreNative
import java.io.File
import java.util.UUID

/** Rust 解包的调用形状（可注入：JVM 单测用假实现验证策略层，真机用 [CoreNative]）。 */
internal typealias NativeExtractCall = (archive: File, targetDir: File) -> Triple<Long, Long, String>

internal object NativeExtractor {

    /** 暂存目录前缀（解完就删；用户不该看见它，万一残留一眼能认出是谁的）。 */
    private const val STAGING_PREFIX = ".zd-extract-"

    /**
     * @param native 默认走 [CoreNative.extract]（真 .so）；单测注入假实现，**只替换解码器，
     *   不替换策略**——同名/超限/暂存清理走的都是生产代码。
     */
    fun extract(
        archive: File,
        target: File,
        kind: ArchiveKind,
        maxBytes: Long = ArchiveRules.MAX_TOTAL_BYTES,
        native: NativeExtractCall? = null,
    ): ExtractOutcome {
        val call: NativeExtractCall = native ?: { a, t ->
            CoreNative.extract(a.absolutePath, t.absolutePath, null)
        }

        // 1) 能预扫的先预扫：.tar/.tar.gz/.tgz 在落盘前就能拒（炸弹不落地）。
        val declared = TarScan.uncompressedBytes(archive, kind)
        if (declared != null && declared > maxBytes) {
            return ExtractOutcome.Rejected(
                "拒绝解压：包内声明解压后共 ${mb(declared)}，超过上限 ${mb(maxBytes)}（${ArchiveRules.MAX_TOTAL_HUMAN}）。",
            )
        }

        if (!target.exists() && !target.mkdirs()) {
            return ExtractOutcome.Failed("建不了目标目录：${target.path}")
        }
        val staging = File(target, STAGING_PREFIX + UUID.randomUUID().toString().take(8))
        if (!staging.mkdirs()) return ExtractOutcome.Failed("建不了暂存目录：${staging.path}")

        // 2) 解到暂存（真解码在 .so；这里只保证失败能收干净）。
        try {
            call(archive, staging)
        } catch (e: IllegalStateException) {
            deleteTree(staging)
            return ExtractOutcome.Failed(failureText(kind, e.message ?: "无细节"))
        } catch (e: RuntimeException) {
            deleteTree(staging)
            return ExtractOutcome.Failed(failureText(kind, "${e.javaClass.simpleName}: ${e.message ?: "无细节"}"))
        }

        // 3) 判总量（实测值，不看包头）——超了就删暂存，目标目录仍是原样。
        val bytes = treeBytes(staging)
        if (bytes > maxBytes) {
            deleteTree(staging)
            return ExtractOutcome.Rejected(
                "拒绝解压：解压后共 ${mb(bytes)}，超过上限 ${mb(maxBytes)}（${ArchiveRules.MAX_TOTAL_HUMAN}）。已清掉暂存，目标目录没有落任何文件。",
            )
        }

        // 4) 判同名（文件级）：命中就整包拒，暂存删掉。
        val conflict = firstConflict(staging, staging, target)
        if (conflict != null) {
            deleteTree(staging)
            return ExtractOutcome.Rejected(
                "拒绝解压：目标里已有同名文件「$conflict」。默认不覆盖——换一个空目录解开，" +
                    "或者先自己处理掉那个文件再试。已清掉暂存，目标目录没有落任何文件。",
            )
        }

        // 5) 搬进去（同分区 rename；跨分区才复制）。搬一半失败也要把暂存清掉并如实报。
        var entries = 0
        try {
            for (child in staging.listFiles().orEmpty()) {
                val n = countFiles(child) // 先数：搬完之后 child 已经不在了
                if (!mergeMove(child, File(target, child.name))) {
                    deleteTree(staging)
                    return ExtractOutcome.Failed("搬迁暂存文件失败：${child.name}（目标目录可能只搬了一半，请检查）")
                }
                entries += n
            }
        } finally {
            deleteTree(staging)
        }
        return ExtractOutcome.Ok(entries, bytes)
    }

    /** `.txz` 的失败原因要说人话：现成 .so 里只有 zstd/gzip 两种壳。 */
    private fun failureText(kind: ArchiveKind, detail: String): String = when (kind) {
        ArchiveKind.TAR_XZ ->
            "解不开 .tar.xz：现成的 libzhengdao_core.so 只认 zstd/gzip 两种压缩壳" +
                "（rust/core/src/extract.rs 按魔数分派，没有 xz 解码器），本批不重编 .so。" +
                "请改用 .tar.gz 或 .tar.zst。（底层报错：$detail）"

        else -> "解包失败：$detail"
    }

    private fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

    /** 目标里已经存在的**文件**（目录不算冲突：合并目录是正常解包行为）。 */
    private fun firstConflict(root: File, dir: File, target: File): String? {
        for (f in dir.listFiles().orEmpty()) {
            val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
            val dest = File(target, rel)
            if (isSymlink(f)) {
                if (dest.exists() || isSymlink(dest)) return rel
                continue
            }
            if (f.isDirectory) {
                if (dest.exists() && !dest.isDirectory) return rel
                firstConflict(root, f, target)?.let { return it }
            } else if (dest.exists()) {
                return rel
            }
        }
        return null
    }

    private fun mergeMove(src: File, dst: File): Boolean {
        if (isSymlink(src)) {
            if (dst.exists()) return false
            return src.renameTo(dst)
        }
        if (src.isDirectory) {
            if (dst.exists() && !dst.isDirectory) return false
            if (!dst.exists() && !dst.mkdirs()) return false
            for (child in src.listFiles().orEmpty()) {
                if (!mergeMove(child, File(dst, child.name))) return false
            }
            return src.delete()
        }
        if (dst.exists()) return false
        if (src.renameTo(dst)) return true
        // 跨分区（暂存与目标同一分区时不会走到这里）：复制后删源。
        return runCatching {
            src.copyTo(dst, overwrite = false)
            src.delete()
        }.getOrDefault(false)
    }

    private fun treeBytes(dir: File): Long {
        var total = 0L
        for (f in dir.listFiles().orEmpty()) {
            if (isSymlink(f)) continue
            total += if (f.isDirectory) treeBytes(f) else f.length()
        }
        return total
    }

    /** 数条目（顶层可能就是文件，别只按目录递归——tar 里只有一个文件时曾数出 0 条）。 */
    private fun countFiles(f: File): Int {
        if (isSymlink(f)) return 1
        if (!f.isDirectory) return 1
        var n = 0
        for (child in f.listFiles().orEmpty()) n += countFiles(child)
        return n
    }

    private fun deleteTree(dir: File) {
        if (!dir.exists()) return
        for (f in dir.listFiles().orEmpty()) {
            if (f.isDirectory && !isSymlink(f)) deleteTree(f)
            runCatching { f.delete() }
        }
        runCatching { dir.delete() }
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
