// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 推演盘（军师）的**副本沙盒**：唯一碰盘的地方。
//
// 为什么落在 `agent/` 而不是新建一个 `rehearsal/` 包（与 `agent/ShadowSandbox.kt` 同一个理由）：
// 法典门禁 **L6 门②**（`constitution/L6BoundaryGateTest`）只扫 `baopu/agent/**` 的写 API 调用点。
// 碰盘的东西要是搬到别的目录，门就照不到它——那是绕闸，不是干净。所以照 296 批的做法：
// 文件留在被扫的域里，把「(文件, API)」逐格登记进白名单并写明理由。
//
// ## 四条红线（每条都有对应的判据测试）
// 1. **真盘只读**：对 `<workspace>` 下的对象只有 `length`/`exists`/`canWrite`/读字节，
//    唯一的删除发生在 `<cacheDir>/rehearsal/rh-<runId>/` 里面，且动手之前把四件事 assert 齐
//    （是目录／父目录名＝`rehearsal`／名字带 `rh-` 前缀／在 cacheDir 里且不等于根）——
//    抄的是 [FileShadowSandbox.discard] 那三件事的做法，多加一条前缀判据；
// 2. **副本一律在 `cacheDir/rehearsal/rh-<runId>/`**（派单【做什么】第一条）：
//    App 私有 cache 目录，分区存储下不需要任何权限，系统低空间时也会自己回收；
// 3. **>8MB 不复制**：只取大小／路径／可写三格＋首尾各 4KB 抽样（派单【判据】③）；
// 4. **不读影子箱**：声明路径里出现 [FileShadowSandbox.SHADOW_DIR_NAME] ⇒ 拒绝这一步
//    （派单【隔离】①）。引用那个常量而不是再抄一遍字符串：影子哪天改名，这里必须跟着变。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.PathGuard
import java.io.File
import java.io.RandomAccessFile

/**
 * 一次推演的副本根。
 *
 * @param cacheRoot 装配层给的 `context.cacheDir`（本文件不持 Context 只持 File——
 *   `StaticFieldLeak` 那条老教训，与 `BaopuRuntime`「Context 只到装配层这一处」同口径）
 * @param quotaBytes 本次配额（[RehearsalRules.quotaBytes] 算出来的；0 ⇒ 一律降级为纯计划层）
 * @param maxCopyBytes 单对象超过就不复制的阈值
 */
internal class RehearsalSandbox(
    private val cacheRoot: File,
    private val quotaBytes: Long,
    private val maxCopyBytes: Long = RehearsalRules.METADATA_ONLY_BYTES,
) {

    companion object {
        /** 军师的根目录名（派单【隔离】表：与影子的 `.shadow` 分根）。 */
        const val ROOT_DIR_NAME: String = "rehearsal"

        /** runId 的目录前缀（派单【隔离】②：一眼看得出这份副本是谁的）。 */
        const val RUN_ID_PREFIX: String = "rh-"
    }

    /** 开好了的副本根；没开成是 null（调用方按降级收，不抛）。 */
    var runDir: File? = null
        private set

    /** 已经用掉的配额头（复制进去的字节累加，实测）。 */
    var usedBytes: Long = 0L
        private set

    /** 一个声明对象归置到哪一档。 */
    enum class Stage {
        /** 原件复制进副本了（≤阈值的文件／目录里的小文件）。 */
        COPIED,

        /** 超阈值：只做元数据预演，**副本里没有这一份**。 */
        METADATA,

        /** 真实跑时才会新建的对象（副本里本来就没有，无需预置）。 */
        ABSENT,

        /** 配额装不下 ⇒ 调用方降级为纯计划层（判据④）。 */
        QUOTA_FULL,

        /** 声明不合格（绝对写法／越界／落在影子箱）⇒ 这一步不跑。 */
        REFUSED,
    }

    /** 归置结果＋元数据三格。 */
    data class Staged(val stage: Stage, val note: String, val meta: MetaReading?)

    /** 清理回执：删之前逐棵树称出来的字节，与"目录确实没了"。 */
    data class Cleanup(val bytesFreed: Long, val removedDirs: Int, val gone: Boolean)

    // ── 1｜开根 ────────────────────────────────────────────────────────────

    /**
     * 建 `<cacheRoot>/rehearsal/rh-<runId>/`。
     *
     * @return null＝开好了；非 null＝不能开的原因（调用方据此降级，不抛异常）
     */
    fun open(runId: String): String? {
        if (runId.isBlank()) return "这次推演没有 runId ⇒ 副本没有归属，不开"
        val root = File(File(cacheRoot, ROOT_DIR_NAME), RUN_ID_PREFIX + safeName(runId))
        if (!PathGuard.isInside(cacheRoot, root)) return "副本根不在 cacheDir 里：${root.name}"
        if (root.isDirectory) cleanupAt(root)
        if (!root.mkdirs() && !root.isDirectory) return "建不了副本根目录：${root.absolutePath}"
        runDir = root
        return null
    }

    /** 副本根（[open] 之后才有）。 */
    fun copyRoot(): File? = runDir

    /** 还剩多少配额（负数按 0 交回，报告里不出现「剩 -3MB」）。 */
    fun remainingBytes(): Long = (quotaBytes - usedBytes).coerceAtLeast(0L)

    // ── 2｜归置一个声明对象 ─────────────────────────────────────────────────

    /**
     * 声明对象按派单的三条分界归置：
     * 越界／影子箱 ⇒ [Stage.REFUSED]；不存在 ⇒ [Stage.ABSENT]；超阈值 ⇒ [Stage.METADATA]；
     * 装得下 ⇒ [Stage.COPIED]；装不下 ⇒ [Stage.QUOTA_FULL]。
     *
     * 复制走 [PathCopyCore.copyFile]（64KB 流式＋保留 mtime）：全仓已有这把尺子，
     * 不另造第二份复制机制（AGENTS 铁律 4「能复用就不重造」）。
     */
    fun stage(workspace: File, rel: String): Staged {
        val root = runDir ?: return Staged(Stage.REFUSED, "副本根没开", null)
        val clean = rel.trim().replace('\\', '/').trim('/')
        if (clean.isEmpty()) return Staged(Stage.REFUSED, "声明路径是空的", null)
        refuse(clean)?.let { return Staged(Stage.REFUSED, it, null) }
        val src = File(workspace, clean)
        if (!src.exists()) return Staged(Stage.ABSENT, "$clean（真实跑时新建）", metaOf(src, clean))
        return if (src.isDirectory) stageDir(src, root, clean) else stageFile(src, root, clean)
    }

    /** 单文件：超阈值只做元数据；否则称完大小看配额，装得下才复制。 */
    private fun stageFile(src: File, root: File, rel: String): Staged {
        val meta = metaOf(src, rel)
        if (src.length() > maxCopyBytes) {
            return Staged(
                Stage.METADATA,
                "$rel 有 ${meta.bytes} 字节 > $maxCopyBytes ⇒ 只做元数据预演，不复制",
                meta,
            )
        }
        val need = src.length()
        if (!RehearsalRules.fits(usedBytes, quotaBytes, need)) {
            return Staged(Stage.QUOTA_FULL, "配额 $quotaBytes B 只剩 ${remainingBytes()} B，装不下 $need B", meta)
        }
        val dst = File(root, rel)
        dst.parentFile?.mkdirs()
        if (!PathCopyCore.copyFile(src, dst)) {
            return Staged(Stage.REFUSED, "复制原件进副本失败：$rel", meta)
        }
        usedBytes += need
        return Staged(Stage.COPIED, "$rel 已复制进副本（$need B）", meta)
    }

    /**
     * 目录：逐个子文件走与单文件同一条阈值/配额规则（判据只有一份）。
     *
     * 中途配额满 ⇒ 整体交 [Stage.QUOTA_FULL]（调用方降级），不留"半棵树算成功"的中间态；
     * 超阈值的子文件照样只做元数据，计数进 note——数字是这趟真走出来的，不是估的。
     */
    private fun stageDir(srcDir: File, root: File, rel: String): Staged {
        var copied = 0
        var metaOnly = 0
        var totalBytes = 0L
        for (f in srcDir.walkTopDown().filter { it.isFile }.toList()) {
            val childRel = "$rel/${relativeOf(srcDir, f)}"
            totalBytes += f.length()
            if (f.length() > maxCopyBytes) {
                metaOnly += 1
                continue
            }
            if (!RehearsalRules.fits(usedBytes, quotaBytes, f.length())) {
                return Staged(
                    Stage.QUOTA_FULL,
                    "目录 $rel 里的 $childRel 有 ${f.length()} B，配额 $quotaBytes B 只剩 ${remainingBytes()} B",
                    null,
                )
            }
            val dst = File(root, childRel)
            dst.parentFile?.mkdirs()
            if (!PathCopyCore.copyFile(f, dst)) {
                return Staged(Stage.REFUSED, "复制目录里的子文件失败：$childRel", null)
            }
            usedBytes += f.length()
            copied += 1
        }
        val dirMeta = MetaReading(rel, exists = true, bytes = totalBytes, writable = srcDir.canWrite(),
            headBytes = 0, tailBytes = 0)
        return Staged(
            Stage.COPIED,
            "目录 $rel：副本内搬 $copied 个子文件（$totalBytes B），另有 $metaOnly 个超阈值只做元数据",
            dirMeta,
        )
    }

    /** f 相对 base 的写法（正斜杠；base 取目录本身，所以不会重复前缀）。 */
    private fun relativeOf(base: File, f: File): String =
        runCatching { f.absoluteFile.toRelativeString(base.absoluteFile) }
            .getOrDefault(f.name).replace('\\', '/')

    // ── 3｜元数据三格＋首尾抽样（派单【判据】③）─────────────────────────────

    /**
     * 大小／路径／可写＋首尾各 [RehearsalRules.SAMPLE_BYTES] 字节的抽样。
     *
     * 抽样只为拿「首尾确实读得到」这一格实测证据；读不到交 0，报告里看得出是没读到，
     * 不假装读到了。目录没有首尾（bytes 交子里总和，抽样格给 0）。
     */
    fun metaOf(file: File, path: String): MetaReading {
        if (!file.isFile) {
            // 还不存在的对象：「可写」问的是**要落它的那个目录**，不是它自己（对不存在的路径
            // 调 canWrite() 恒为 false，会把"其实能建"报成"建不了"）。
            val absent = !file.exists()
            return MetaReading(path, exists = file.isDirectory, bytes = 0L,
                writable = if (absent) file.parentFile?.canWrite() == true else file.canWrite(),
                headBytes = 0, tailBytes = 0)
        }
        val len = file.length()
        val head = sampleAt(file, 0L, RehearsalRules.SAMPLE_BYTES)
        val tailAt = if (len > RehearsalRules.SAMPLE_BYTES) len - RehearsalRules.SAMPLE_BYTES else 0L
        val tail = if (tailAt == 0L) 0 else sampleAt(file, tailAt, RehearsalRules.SAMPLE_BYTES)
        return MetaReading(path, exists = true, bytes = len, writable = file.canWrite(),
            headBytes = head, tailBytes = tail)
    }

    /** 从 [at] 起读最多 [want] 字节，交回实际读到的字节数（异常 ⇒ 0）。 */
    private fun sampleAt(file: File, at: Long, want: Int): Int = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(at)
            val buf = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = raf.read(buf, got, want - got)
                if (n <= 0) break
                got += n
            }
            got
        }
    }.getOrDefault(0)

    // ── 4｜副本树快照与 diff（「会改 N 个文件」的实测来源）────────────────────

    /** 副本里一个节点的两格读数（大小给数字，摘要给「是不是真变了」）。 */
    data class Node(val bytes: Long, val sha: String)

    /** 副本树**当前占的字节**（只走大小不摘要，所以每步都能称一次，用来取峰值）。 */
    fun treeBytes(): Long = runDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * 副本树的当前快照（相对副本根的写法 → 大小＋摘要）。
     *
     * 比摘要不只比大小：等长覆盖在纯大小口径里＝「没改」，`会改 N 个文件` 会少报——
     * 与 296 批给回滚点的教训同病（"文件在不在"认不出"是别人的旧版"）。
     */
    fun snapshot(): Map<String, Node> {
        val root = runDir ?: return emptyMap()
        val out = LinkedHashMap<String, Node>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = relativeOf(root, f)
            if (isBookkeeping(rel)) return@forEach
            out[RehearsalRules.relKey(rel)] = Node(f.length(), PathCopyCore.sha256(f) ?: "")
        }
        return out
    }

    /**
     * 两侧一比：新增／变了字／消失 的相对写法并成一条有序清单（就是报告里那个 N）。
     *
     * 记账目录（`备份/`、`回收站/`、`.tmp`）不算影响面：那是工具自己的退路，
     * 不是「这次动作会改到的文件」——口径与 `FileShadowSandbox.treeOf` 一致。
     */
    fun diffPaths(before: Map<String, Node>): List<String> {
        val after = snapshot()
        val appeared = after.filter { (k, _) -> k !in before }.keys
        val disappeared = before.filter { (k, _) -> k !in after }.keys
        val changed = after.filter { (k, v) -> before[k]?.let { it.sha != v.sha } == true }.keys
        return (appeared + changed + disappeared).sorted()
    }

    /** 工具/副本自己的记账位（不算影响面，理由见 [diffPaths]）。 */
    internal fun isBookkeeping(rel: String): Boolean {
        val parts = rel.lowercase().split("/")
        return parts.contains(BaopuKernel.Data.DIR_BACKUP.lowercase()) ||
            parts.contains(DeletePathTool.TRASH_DIR.lowercase()) ||
            parts.any { it.endsWith(".tmp") }
    }

    // ── 5｜清理（判据②⑤：跑完／取消／失败三条路都走到这里）──────────────────

    /** 清本次的副本根；调用方不必记路径（[runDir] 里有）。 */
    fun cleanup(): Cleanup {
        val dir = runDir ?: return Cleanup(0L, 0, true)
        val out = cleanupAt(dir)
        runDir = null
        usedBytes = 0L
        return out
    }

    /**
     * 删除之前的四件 assert（删错一棵树是数据事故，不靠调用方保证）。
     *
     * 单独抽出来是因为四条并排写在删除里时，摘掉任何一条都还是"别的几条挡住"——
     * 牙表（M6/M6b/M6c）要的是**每一条单独红**，所以这里给成一个可以直接问的判据。
     */
    internal fun deletable(dir: File): Boolean {
        val parent = dir.parentFile
        return parent?.name == ROOT_DIR_NAME &&
            dir.name.startsWith(RUN_ID_PREFIX) &&
            PathGuard.isInside(cacheRoot, dir) &&
            dir.absolutePath != parent.absolutePath
    }

    /**
     * 删一棵副本树，删之前先称重（「清理了 X MB」必须是实测，不能删完再编一个数）。
     *
     * 够不够格删交 [deletable]（四件事：父目录名＝`rehearsal`／名字带 `rh-`／
     * 在 cacheDir 里／不等于 `rehearsal` 根），这里只管"不是目录"那一格单独回答。
     */
    private fun cleanupAt(dir: File): Cleanup {
        if (!dir.isDirectory) return Cleanup(0L, 0, !dir.exists())
        if (!deletable(dir)) return Cleanup(0L, 0, false)
        val parent = dir.parentFile ?: return Cleanup(0L, 0, false)
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val count = dir.walkTopDown().filter { it.isDirectory }.count()
        runCatching { dir.deleteRecursively() }
        // 空了就把手上的 `rehearsal/` 一起摘掉：cacheDir 里不该留一个空的军师壳（同 296 批那条）。
        runCatching { if (parent.listFiles()?.isEmpty() == true) parent.delete() }
        return Cleanup(bytes, count, gone = !dir.exists())
    }

    /**
     * 清**已经存在**的那一份（`onStopped()` 用的幂等版）。
     *
     * 与 [cleanup] 的差别只有一件事：这里绝不建目录——现场早就没了就交"干净"，
     * 不能因为"来打扫"反而在 cacheDir 里 mkdir 出一个新壳。
     */
    fun discardExisting(runId: String): Cleanup {
        val root = File(File(cacheRoot, ROOT_DIR_NAME), RUN_ID_PREFIX + safeName(runId))
        if (!root.isDirectory) return Cleanup(0L, 0, gone = true)
        runDir = root
        return cleanup()
    }

    /**
     * 扫掉**上一次没清干净**的军师副本（进程被杀时 `finally` 走不到，判据②在真机上靠这条兜）。
     *
     * 只删带 `rh-` 前缀、比 [olderThanMs] 更老、且确认在 `rehearsal/` 下面的目录；
     * 正在用的那一根（[keepRunId]）永不动——扫掉别人正在写的副本等于自己制造事故。
     */
    fun sweepStale(keepRunId: String, olderThanMs: Long, nowMs: Long): Cleanup {
        val root = File(cacheRoot, ROOT_DIR_NAME)
        if (!root.isDirectory) return Cleanup(0L, 0, true)
        val keep = RUN_ID_PREFIX + safeName(keepRunId)
        var bytes = 0L
        var dirs = 0
        root.listFiles()?.filter {
            it.isDirectory && it.name.startsWith(RUN_ID_PREFIX) && it.name != keep &&
                nowMs - it.lastModified() >= olderThanMs
        }?.forEach {
            val one = cleanupAt(it)
            bytes += one.bytesFreed
            dirs += one.removedDirs
        }
        runCatching { if (root.listFiles()?.isEmpty() == true) root.delete() }
        return Cleanup(bytes, dirs, gone = !root.exists())
    }

    // ── 6｜隔离判据（派单【隔离】①②）──────────────────────────────────────

    /**
     * 这个声明路径军师能不能碰？能碰交 null，不能碰交理由。
     *
     * 四条：绝对写法／上跳越出工作区／落在**影子箱**／指向军师自己的根（防递归预演）。
     *
     * 进来先归一（反斜杠折成正斜杠）：不归一的话 `..\` 写法的上跳就绕过了检查，而且
     * 「绝对写法」这一判会随平台变（`File("C:\\x").isAbsolute` 只在 Windows 为真），
     * 判据不能看宿主机的脸色——所以盘符写法在这里显式拒。
     */
    fun refuse(raw: String): String? {
        val rel = raw.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) return "声明路径是空的"
        if (rel.startsWith("/") || rel.matches(Regex("[A-Za-z]:.*"))) return "声明路径是绝对写法：$rel"
        val parts = rel.split('/')
        if (parts.contains("..")) return "声明路径越出工作区：$rel"
        if (parts.any { it.equals(FileShadowSandbox.SHADOW_DIR_NAME, ignoreCase = true) }) {
            return "军师不读影子箱（派单【隔离】①）：$rel"
        }
        if (parts.any { it.equals(ROOT_DIR_NAME, ignoreCase = true) }) {
            return "声明路径指向军师自己的根，不做递归预演：$rel"
        }
        return null
    }

    /** 把 runId 折成能当目录名的写法（照 [FileShadowSandbox.safeName] 同一套字符规则）。 */
    internal fun safeName(runId: String): String =
        runId.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("").take(64).ifEmpty { "rh" }
}
