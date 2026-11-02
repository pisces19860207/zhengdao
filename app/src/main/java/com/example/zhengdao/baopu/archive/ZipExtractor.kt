// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §2「zip 走 Java」＋ §3 三条安全线。
//
// 为什么是「先看清单、再落盘」两段式（而不是边解边判）：
// 1. **默认不覆盖**要求「同名即停并报」——如果先解了 300 个文件才发现第 301 个撞名，
//    目标目录已经被写脏了；先读中央目录就能在**一个字节都没写**之前拒掉。
// 2. **≤2GB** 要求按声明总量判——先判能在解压前拒绝，不必等写满 2GB 再回滚（那是炸弹的真实形态）。
// 3. **zip-slip** 要求整包拒（不是「跳过坏条目继续解」）——半解出来的目录是最难收拾的状态。
// 声明值不可信，所以落盘时**再数一遍**实际字节，超限即回滚已写文件（两层都判）。
package com.example.zhengdao.baopu.archive

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** 单个 zip 的落盘计划（清单 + 目标路径），策略层判完才允许写。 */
internal object ZipExtractor {

    private const val BUF = 64 * 1024

    /**
     * @param archive 源 zip
     * @param target 解到哪个目录（不存在会建）
     * @param maxBytes 总量上限（默认 [ArchiveRules.MAX_TOTAL_BYTES]；测试注入小值走同一条判定）
     */
    fun extract(
        archive: File,
        target: File,
        maxBytes: Long = ArchiveRules.MAX_TOTAL_BYTES,
    ): ExtractOutcome {
        val index = when (val r = ZipIndex.parse(archive)) {
            is ZipIndexResult.Bad -> return ExtractOutcome.Failed(r.reason)
            is ZipIndexResult.Ok -> r.members
        }
        if (index.isEmpty()) return ExtractOutcome.Ok(0, 0L)

        // ── 第 1 段：清单级判定（一个字节都还没写） ──────────────────────────
        val (allowed, targets) = when (val plan = planExtraction(index, target, maxBytes)) {
            is ManifestVerdict.Rejected -> return ExtractOutcome.Rejected(plan.reason)
            is ManifestVerdict.Ready -> plan
        }

        // 同名判定：先看全部，命中第一个就整包拒（不写任何文件）。
        for ((rel, dest) in targets) {
            if (dest.exists()) {
                return ExtractOutcome.Rejected(
                    "拒绝解压：目标里已有同名文件「$rel」。默认不覆盖——" +
                        "换一个空目录解开，或者先自己处理掉那个文件再试。整包不落盘。",
                )
            }
        }

        if (!target.exists() && !target.mkdirs()) {
            return ExtractOutcome.Failed("建不了目标目录：${target.path}")
        }

        // ── 第 2 段：落盘（边写边数，声明值撒谎时靠这一段兜住） ────────────────
        return writeEntries(archive, target, index, allowed, maxBytes)
    }

    /** 第 1 段（清单级判定）的结论：要么给出「允许落盘的计划」，要么给出整包拒绝的理由。 */
    private sealed interface ManifestVerdict {
        data class Ready(val allowed: Set<String>, val targets: List<Pair<String, File>>) : ManifestVerdict
        data class Rejected(val reason: String) : ManifestVerdict
    }

    /** 第 1 段：只看中央目录清单——加密／符号链接／越界／重名／声明总量超限，命中一条就整包拒（不落盘）。 */
    private fun planExtraction(index: List<ZipMember>, target: File, maxBytes: Long): ManifestVerdict {
        val allowed = HashSet<String>(index.size * 2)
        val targets = ArrayList<Pair<String, File>>(index.size)
        var declaredTotal = 0L

        for (m in index) {
            if (m.encrypted) {
                return ManifestVerdict.Rejected("拒绝解压：成员「${m.name}」是加密条目（需要密码），本工具不支持加密 zip。")
            }
            if (m.symlink) {
                return ManifestVerdict.Rejected(
                    "拒绝解压：成员「${m.name}」是符号链接。链接是 zip-slip 的常用载体" +
                        "（先建一个指向外面的链接，再把文件写进链接里），本工具一律不解链接。",
                )
            }
            val verdict = ArchiveRules.checkName(m.name)
            if (verdict != ArchiveRules.NameVerdict.OK) {
                return ManifestVerdict.Rejected(ArchiveRules.rejectReason(m.name, verdict))
            }
            val rel = m.name.replace('\\', '/').trimEnd('/')
            if (!allowed.add(rel)) {
                return ManifestVerdict.Rejected("拒绝解压：包里有重名成员「$rel」（同名条目会让部分解包器静默丢文件），整包不落盘。")
            }
            if (m.isDirectory) continue
            val dest = ArchiveRules.resolveInside(target, rel)
                ?: return ManifestVerdict.Rejected(
                    "拒绝解压：成员「${m.name}」规范化后落在目标目录之外（zip-slip），整包不落盘。",
                )
            targets.add(rel to dest)
            declaredTotal += m.declaredSize
            if (declaredTotal > maxBytes) {
                return ManifestVerdict.Rejected(tooBig(declaredTotal, maxBytes))
            }
        }
        return ManifestVerdict.Ready(allowed, targets)
    }

    /** 第 2 段：逐条落盘（边写边数，声明值撒谎时靠这一段兜住）；中途出事一律 rollback。 */
    private fun writeEntries(
        archive: File,
        target: File,
        index: List<ZipMember>,
        allowed: Set<String>,
        maxBytes: Long,
    ): ExtractOutcome {
        val written = ArrayList<File>()
        val madeDirs = ArrayList<File>()
        val progress = WriteProgress()
        val charset = pickCharset(index.map { it.rawName })
        try {
            ZipInputStream(archive.inputStream().buffered(BUF), charset).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    writeOneEntry(zin, entry, target, allowed, maxBytes, progress, written, madeDirs)?.let { reason ->
                        return rollback(written, madeDirs, reason)
                    }
                }
            }
        } catch (e: IOException) {
            cleanup(written, madeDirs)
            return ExtractOutcome.Failed("解压中断（${e.javaClass.simpleName}: ${e.message ?: "无细节"}），已回滚本轮写出的文件")
        }
        return ExtractOutcome.Ok(progress.entries, progress.bytes)
    }

    /** 落盘进度：实际写出的字节数与条目数（声明值撒谎时靠它兜住）。 */
    private class WriteProgress {
        var bytes = 0L
        var entries = 0
    }

    /** 处理一个条目：目录条目建目录、文件条目校验后落盘并计入进度（目录不计条数）。返回 null＝成功；否则＝整包回滚的理由。 */
    private fun writeOneEntry(
        zin: ZipInputStream,
        entry: ZipEntry,
        target: File,
        allowed: Set<String>,
        maxBytes: Long,
        progress: WriteProgress,
        written: MutableList<File>,
        madeDirs: MutableList<File>,
    ): String? {
        val rel = entry.name.replace('\\', '/').trimEnd('/')
        if (entry.isDirectory) {
            val dir = ArchiveRules.resolveInside(target, rel)
                ?: return "成员「${entry.name}」落在目标目录之外（zip-slip）"
            if (!dir.exists() && dir.mkdirs()) madeDirs.add(dir)
            zin.closeEntry()
            return null
        }
        if (rel !in allowed) {
            return "包内条目「${entry.name}」不在目录清单里（中央目录与本地头不一致）"
        }
        val dest = ArchiveRules.resolveInside(target, rel)
            ?: return "成员「${entry.name}」落在目标目录之外（zip-slip）"
        dest.parentFile?.let { parent ->
            if (!parent.exists() && parent.mkdirs()) madeDirs.add(parent)
        }
        FileOutputStream(dest).use { out ->
            val end = copyLimited(zin, out, dest, maxBytes, progress.bytes)
            if (end < 0) return tooBig(-end, maxBytes)
            progress.bytes = end
        }
        written.add(dest)
        progress.entries++ // 目录条目不计（与原实现一致：目录分支提前 return，不走到这里）
        zin.closeEntry()
        return null
    }

    /**
     * 把当前文件条目从 [zin] 拷到 [out]，边写边数。
     * 返回**累计**总字节数；超过 [maxBytes] 时关流、删掉半成品 [dest]，返回「-累计总字节数」
     * （负值＝失败，调用方取负拿真实读数去拼 tooBig 文案）。
     */
    private fun copyLimited(zin: ZipInputStream, out: FileOutputStream, dest: File, maxBytes: Long, bytesSoFar: Long): Long {
        val buf = ByteArray(BUF)
        var total = bytesSoFar
        while (true) {
            val n = zin.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                out.close()
                dest.delete()
                return -total
            }
            out.write(buf, 0, n)
        }
        return total
    }

    private fun tooBig(total: Long, maxBytes: Long): String =
        "拒绝解压：解压后总量约 ${mb(total)}，超过上限 ${mb(maxBytes)}。" +
            "请先在电脑上拆小一点，或只解其中一部分。"

    private fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

    /** 回滚：删掉本轮写出的文件与建出的空目录（只删自己造的，不碰目标目录里原有的东西）。 */
    private fun rollback(written: List<File>, madeDirs: List<File>, reason: String): ExtractOutcome {
        cleanup(written, madeDirs)
        return ExtractOutcome.Rejected(reason)
    }

    private fun cleanup(written: List<File>, madeDirs: List<File>) {
        for (f in written.asReversed()) runCatching { f.delete() }
        for (d in madeDirs.asReversed()) runCatching { d.delete() }
    }

    /**
     * 选解码字符集：EFS 置位的包按 UTF-8；未置位的老包先试严格 UTF-8（不少工具写中文名却不置位），
     * 再退 GBK（Windows 中文区）。与 [ZipIndex] 的解码顺序**必须一致**，否则清单名与流里的名对不上。
     */
    private fun pickCharset(rawNames: List<ByteArray>): java.nio.charset.Charset {
        val gbk = runCatching { java.nio.charset.Charset.forName("GBK") }.getOrNull()
        val utf8Ok = rawNames.all { strictUtf8(it) }
        if (utf8Ok || gbk == null) return Charsets.UTF_8
        return gbk
    }

    private fun strictUtf8(raw: ByteArray): Boolean = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(raw))
    }.isSuccess
}
