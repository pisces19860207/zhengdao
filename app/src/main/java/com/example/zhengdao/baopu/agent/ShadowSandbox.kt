// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十六批 §2 的沙盒**实现**（接口与判定分别住在 `inspect/ShadowAdapter.kt` 与
// `inspect/ShadowRehearsal.kt`）。为什么这份文件在 `agent/` 而不是 `inspect/`：
// 勘验层那条门禁（[com.example.zhengdao.baopu.inspect.InspectReadOnlyGateTest] 的
// 「纠察只查不改」）不许 inspect 目录里出现建目录／写盘／删目录任何一口，
// 而影子**必须**建副本才能跑 ⇒ 碰盘的活儿只能落在装配层。
//
// ## 三件只在这里发生的事（§1①②）
// 1. **开副本**：`<工作区>/.shadow/<opId>/`，只把声明的对象复制进去；
//    声明路径必须是相对写法且落在工作区里，绝对路径／越界 ⇒ 直接不跑（结构故障）；
// 2. **拍快照**：对每个声明对象取 `sha256＋大小＋存在性` 三格，并在副本的 `.rollback/`
//    里留一份 pre-image（§1②：只看"文件在不在"认不出"退路是别人的旧版"）；
// 3. **在副本里复刻这次动作**：把 `write_file`／`edit_file` 的 executor 用**相对路径**
//    重新喂一遍，root 换成副本根 ⇒ 工具自己建的 `备份/`、`.tmp` 都只长在副本里。
//
// ## 两条红线（打错一条就是事故，不是判据）
// · **真盘只读**：这份文件对真盘只有 `readBytes`/`length`/`isFile` 三种动作，
//   唯一的删除发生在 `.shadow/<opId>/` 里面（且删之前把三件事 assert 齐：
//   父目录名＝`.shadow`、在工作区里、不等于工作区根）；
// · **喂工具的必须是相对路径**：管线第 ② 段已经把 `path` 改写成绝对真盘路径，
//   原样透传就等于"影子写了真文件"。所以这里从 [ShadowInput.declaredPaths]
//   （六步第①步那个相对 objectId）取路径，root 给副本根——两个条件缺一个都不跑。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.inspect.ImpactShape
import com.example.zhengdao.baopu.inspect.RollbackPoint
import com.example.zhengdao.baopu.inspect.ShadowInput
import com.example.zhengdao.baopu.inspect.ShadowRun
import com.example.zhengdao.baopu.inspect.ShadowSandbox
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.PathGuard
import java.io.File
import java.security.MessageDigest

/**
 * 文件沙盒版影子适配器（`ShadowAdapter` 缺省装的就是这一份）。
 *
 * @param maxCopyBytes 单个对象超过这个大小就不跑（8MB）：预演是**每次写动作前**问的一句，
 *   不该为了省一张卡把几十 MB 复制一遍。超了 ⇒ 结构故障 ⇒ 照旧弹卡（保守方向，不是放行）。
 */
internal class FileShadowSandbox(
    private val maxCopyBytes: Long = MAX_COPY_BYTES,
) : ShadowSandbox {

    companion object {
        /** 副本根的名字（点开头：157 那套用户可见目录里不该多出"影子"这一层）。 */
        const val SHADOW_DIR_NAME = ".shadow"

        /** 副本里 pre-image 的落点（只进回滚点那一格，不进影响集合——见 [snapshotOf]）。 */
        const val ROLLBACK_DIR_NAME = ".rollback"

        /** 单对象复制上限（超过就不跑预演，见类注释）。 */
        const val MAX_COPY_BYTES = 8L * 1024 * 1024

        /** 回执进明细时留这么多字（明细只进账，但仍然不该塞整段异常栈）。 */
        private const val NOTE_CHARS = 120
    }

    /** 一次预演的现场（before 快照与真盘 before 摘要在这一份里交接给 [rehearse]）。 */
    private class Scene(
        val copyRoot: File,
        val workspace: File,
        val declared: List<String>,
        val existed: List<String>,
        val before: Map<String, Node>,
        val realBefore: Map<String, String>,
    )

    /** 盘上一个节点的两格读数（大小给数字、摘要给"是不是同一版"）。 */
    private class Node(val bytes: Long, val sha: String)

    override fun rehearse(input: ShadowInput): ShadowRun {
        val prepared = open(input)
        if (prepared.second != null) return ShadowRun(built = false, note = prepared.second ?: "")
        val scene = prepared.first ?: return ShadowRun(built = false, note = "副本没建起来")
        // 快照必须在**试跑之前**拍：跑完之后副本里那份已经是新版本，再拍就成了"用改后的字节当退路"。
        val snapshot = snapshotOf(scene)
        val trial = runTrial(input, scene)
        val after = treeOf(scene.copyRoot)
        val diff = diffOf(scene, after)
        return ShadowRun(
            built = true,
            note = "副本 ${scene.declared.size} 个对象",
            copyRoot = scene.copyRoot.absolutePath,
            existedBefore = scene.existed,
            // 306 批：改前字节数一并交回——move/delete 的所称形状要报「源没了、多少字节」。
            existedBytes = scene.before.mapValues { it.value.bytes },
            observedPaths = diff.paths,
            observed = diff.shape,
            rollback = snapshot,
            // §1④ 第三条件：真盘上声明对象跑完之后字节还是不是原来那份。
            realUntouched = scene.declared.all { rel ->
                sha256(File(scene.workspace, rel)) == scene.realBefore[rel]
            },
            landed = landedOf(input, scene, after, diff),
            trialOk = trial.first,
            trialNote = trial.second,
        )
    }

    /**
     * 清副本（只在判据层说 CONSISTENT 之后被调）。
     *
     * 删之前把三件事一次 assert 齐（路径必须是 `.shadow/<opId>` 那一层、必须在工作区里、
     * 必须不等于工作区根）——删错一棵树就是数据事故，不靠调用方保证。
     */
    override fun discard(copyRoot: String) {
        val dir = copyRoot.trim().takeIf { it.isNotEmpty() }?.let(::File) ?: return
        val parent = dir.parentFile
        val workspace = parent?.parentFile
        if (!dir.isDirectory || dir.absolutePath == workspace?.absolutePath) return
        if (parent?.name != SHADOW_DIR_NAME) return
        if (workspace == null || !PathGuard.isInside(workspace, dir)) return
        runCatching { dir.deleteRecursively() }
        // 副本清空了就把 `.shadow/` 自己摘掉（工作区根目录里不该留一个空的隐藏壳）。
        runCatching { if (parent.listFiles()?.isEmpty() == true) parent.delete() }
    }

    // ── 1｜开副本 ────────────────────────────────────────────────────────────

    /** 建副本根＋复制声明对象；任何一条不合格 ⇒ (null, 原因)（调用方按结构故障收）。 */
    private fun open(input: ShadowInput): Pair<Scene?, String?> {
        val prepared = prepareOpen(input)
        val opened = prepared.first ?: return null to (prepared.second ?: "预演副本打不开")
        val workspace = opened.workspace
        val rels = opened.rels
        val copyRoot = opened.copyRoot
        val existed = mutableListOf<String>()
        val before = LinkedHashMap<String, Node>()
        val realBefore = LinkedHashMap<String, String>()
        rels.forEach { rel ->
            val one = copyOne(workspace, copyRoot, rel)
            one.error?.let { return null to it }
            if (one.existed) {
                existed += rel
                before[relPathKey(rel)] = one.node
            }
            // 真盘 before 摘要（跑完之后同一格再取一次，两值必须相等；新建对象＝空串＝"两边都不存在"）。
            realBefore[rel] = one.realSha
        }
        return Scene(copyRoot, workspace, rels, existed, before, realBefore) to null
    }


    /** 「副本开好了」的三样东西（296 收口时把 open 的校验半段抽出来的形状）。 */
    private data class Opened(val workspace: File, val rels: List<String>, val copyRoot: File)

    /**
     * 开副本前的**全部校验 + 建根**（2026-10-30，detekt 硬墙红了 2 条时拆出来的）。
     *
     * 为什么单拆：`open` 原来一屏里塞了「算工作区 → 算对象 → 判越界 → 判超大 → 算副本根 →
     * 清旧 → 建目录 → 逐对象复制」八件事，复杂度 16 刚好越线；复制那段本来就是循环，
     * 校验这边是一串**并列的拒绝条件**——拆出来的是"准入"这一件事。
     */
    private fun prepareOpen(input: ShadowInput): Pair<Opened?, String?> {
        if (input.opId.isBlank()) return null to "这次动作没有 opId ⇒ 副本没有归属，不开"
        val workspace = input.workspaceRoot.trim().takeIf { it.isNotEmpty() }?.let(::File)
            ?: return null to "工作区根没给"
        if (!workspace.isDirectory) return null to "工作区根不在盘上"
        val rels = input.declaredPaths.map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() }
        if (rels.isEmpty()) return null to "声明对象一个都没有"
        if (rels.any { File(it).isAbsolute || it.split('/').contains("..") }) {
            return null to "声明路径里有绝对写法或越出工作区的上跳（$rels）"
        }
        rels.forEach { rel -> oversized(File(workspace, rel))?.let { return null to it } }
        val copyRoot = File(File(workspace, SHADOW_DIR_NAME), safeName(input.opId))
        if (copyRoot.absolutePath == workspace.absolutePath || !PathGuard.isInside(workspace, copyRoot)) {
            return null to "副本根不在工作区里：${copyRoot.name}"
        }
        if (copyRoot.isDirectory) discard(copyRoot.absolutePath)
        if (!copyRoot.mkdirs() && !copyRoot.isDirectory) return null to "建不了副本根目录"
        return Opened(workspace, rels, copyRoot) to null
    }

    /** 单对象复制上限这一格（超了交回错误文案，没超交回 null）。 */
    private fun oversized(src: File): String? =
        if (src.isFile && src.length() > maxCopyBytes) {
            "声明对象 ${src.name} 有 ${src.length()} 字节 > $maxCopyBytes ⇒ v1 不在预演里复制"
        } else {
            null
        }

    /** 一次复制的三格读数（副本里有没有原件／改前摘要／真盘摘要）；失败走 [error]。 */
    private class CopyOne(
        val existed: Boolean,
        val node: Node,
        val realSha: String,
        val error: String?,
    )

    /** 把一个声明对象复制进副本并取三格读数（新建对象：existed=false、摘要空串）。 */
    private fun copyOne(workspace: File, copyRoot: File, rel: String): CopyOne {
        val src = File(workspace, rel)
        if (!src.isFile) return CopyOne(existed = false, node = Node(0L, ""), realSha = "", error = null)
        val dst = File(copyRoot, rel)
        dst.parentFile?.mkdirs()
        val ok = runCatching { src.copyTo(dst, overwrite = true) }.isSuccess
        if (!ok) return CopyOne(false, Node(0L, ""), "", "复制原件进副本失败：${src.name}")
        val sha = sha256(src)
        return CopyOne(true, Node(src.length(), sha), sha, null)
    }

    /** 把 opId 折成一个能当目录名的写法（工具调用 id 可能带 `:`/`/` 之类）。 */
    internal fun safeName(opId: String): String =
        opId.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("").take(64).ifEmpty { "op" }

    // ── 3｜在副本里复刻这次动作 ─────────────────────────────────────────────

    /** @return Pair(工具回执是不是 Ok, 回执短形) */
    private fun runTrial(input: ShadowInput, scene: Scene): Pair<Boolean, String> {
        val rel = scene.declared.firstOrNull() ?: return false to "没有声明对象"
        val outcome = when (input.tool) {
            "write_file" -> WriteFileTool.write(scene.copyRoot, rel, input.content, input.overwrite)
            "edit_file" -> EditFileTool.edit(
                scene.copyRoot, rel, input.editOp, input.fromLine, input.toLine, input.content,
            )
            // 306 批三件：照原样在副本里**真跑一遍**——root 换成副本根、路径用相对写法，
            // 与 write/edit 同一条红线（绝对路径/越界在 open() 那一格就已经拒了）。
            // · move_path：声明 = [源, 目标]；副本内同盘 rename（原子），工具自己的「待删除清单」
            //   只在跨分区分支才写，副本里不会冒出来；
            // · delete_path：permanent 照原样带进去——工具把源挪进副本的 `回收站/`（进不了影响面，
            //   见 [isBookkeeping]）或真删，两种都是"声明对象消失"；
            // · convert_encoding：GBK/UTF-16 → UTF-8 原子替换；工具自己的 `备份/` 同样不算影响面。
            "move_path" -> MovePathTool.move(
                root = scene.copyRoot,
                from = scene.declared.getOrElse(0) { "" },
                to = scene.declared.getOrElse(1) { "" },
            )
            "delete_path" -> DeletePathTool.run(
                path = rel,
                permanent = input.permanent,
                root = scene.copyRoot,
            )
            "convert_encoding" -> ConvertEncodingTool.convert(scene.copyRoot, File(scene.copyRoot, rel))
            else -> return false to "工具「${input.tool}」不在适配器覆盖里"
        }
        val text = when (outcome) {
            is ToolOutcome.Ok -> outcome.text
            is ToolOutcome.Failed -> outcome.error
            is ToolOutcome.Rejected -> outcome.reason
        }
        return (outcome is ToolOutcome.Ok) to text.take(NOTE_CHARS)
    }

    /**
     * 306 批：声明对象的动作在副本里**落了没有**——按工具各自的「落」算。
     * write/edit 的落是"声明对象有字节/变了字"；搬与删的落是"声明对象**没了**"
     * （源还在副本里 ＝ 这一趟压根没搬成，照旧判没落盘）。
     */
    private fun landedOf(input: ShadowInput, scene: Scene, after: Map<String, Node>, diff: Diff): Boolean {
        val first = scene.declared.firstOrNull()?.let { relPathKey(it) } ?: return false
        return when (input.tool) {
            "move_path" -> {
                val to = scene.declared.getOrNull(1)?.let { relPathKey(it) } ?: return false
                !after.containsKey(first) && after.containsKey(to)
            }
            "delete_path" -> !after.containsKey(first)
            else -> diff.landed
        }
    }

    // ── 2｜快照（§1②：sha256＋大小＋存在性三格）─────────────────────────────

    /**
     * 回滚点读数：声明对象的 pre-image。
     *
     * 多个对象时交回**最弱的那一个**（有一格退路不可用就是这次动作退不回去）。
     * 新建对象没有旧版 ⇒ [RollbackPoint.expectsAbsent]，可用判据换成"副本里确实原先没有"。
     */
    private fun snapshotOf(scene: Scene): RollbackPoint {
        val points = scene.declared.map { rel ->
            val src = File(scene.copyRoot, rel)
            val existed = src.isFile
            val bak = File(File(scene.copyRoot, ROLLBACK_DIR_NAME), safeName(rel) + ".bak")
            if (existed) {
                val ok = runCatching {
                    bak.parentFile?.mkdirs()
                    src.copyTo(bak, overwrite = true)
                }.isSuccess
                RollbackPoint(
                    path = bak.absolutePath,
                    exists = ok && bak.isFile,
                    bytes = if (ok && bak.isFile) bak.length() else 0L,
                    originalBytes = if (ok && bak.isFile) src.length() else null,
                    sha256 = if (ok && bak.isFile) sha256(bak) else "",
                    originalSha256 = if (src.isFile) sha256(src) else "",
                )
            } else {
                RollbackPoint(path = bak.absolutePath, exists = false, expectsAbsent = true)
            }
        }
        return points.firstOrNull { !it.usable } ?: points.firstOrNull() ?: RollbackPoint()
    }

    // ── 实测：副本树 diff ───────────────────────────────────────────────────

    /**
     * 副本树的快照（相对写法 → 大小＋摘要）。
     *
     * 摘要而不是只有大小：等长覆盖在纯大小口径里等于"没改"，影响集合会报空、
     * `landed` 会永远红——与 §1② 给回滚点的教训同病（"文件在不在"认不出"是别人的旧版"）。
     *
     * 两条排除（都在明细里说得出理由，不是为了让数字好看）：
     * · `.rollback/` —— 那是**回滚点**那一格的事实，混进影响集合就等于"每次覆盖都多碰一个对象"，
     *   判据①（写一次影子）会永远红；
     * · `备份/` —— 工具在副本里自己建的旧版本区，同理归第 2 件事管。
     */
    private fun treeOf(root: File): Map<String, Node> {
        val out = LinkedHashMap<String, Node>()
        if (!root.isDirectory) return out
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = runCatching { f.absoluteFile.toRelativeString(root.absoluteFile) }
                .getOrDefault(f.name).replace('\\', '/')
            if (isBookkeeping(rel)) return@forEach
            out[relPathKey(rel)] = Node(f.length(), sha256(f))
        }
        return out
    }

    /**
     * 影子／工具自己的记账目录（不算"这次动作影响到的对象"）。
     *
     * 306 批补 `回收站/`：那不是影子自己的东西，是 `delete_path`（回收站档）在副本里给自己的
     * **退路**——与 `备份/` 同一格（写侧三件工具都自带退路目录）。真盘上的回收站照常工作，
     * 这里收窄的只是「副本树 diff 的影响面口径」：声明的删除对象"消失"就是全部实测，
     * 工具把它挪去自己退路里的那一下不算多碰一个对象。
     */
    internal fun isBookkeeping(rel: String): Boolean {
        val parts = rel.lowercase().split("/")
        return parts.contains(ROLLBACK_DIR_NAME.lowercase()) ||
            parts.contains(BaopuKernel.Data.DIR_BACKUP.lowercase()) ||
            parts.contains(DeletePathTool.TRASH_DIR.lowercase()) ||
            parts.any { it.endsWith(".tmp") }
    }

    /** 两侧一比：appeared/changed/disappeared 折成实测形状，路径统一写成副本根下的样子。 */
    private fun diffOf(scene: Scene, after: Map<String, Node>): Diff {
        val appeared = after.filter { (k, _) -> k !in scene.before }
        val disappeared = scene.before.filter { (k, _) -> k !in after }
        // 比摘要不比大小：等长覆盖是真改动，只比大小会把它判成"没碰"。
        val changed = after.filter { (k, v) -> scene.before[k]?.let { it.sha != v.sha } == true }
        val keys = (appeared.keys + changed.keys + disappeared.keys).sorted()
        val paths = keys.map { "${scene.copyRoot.absolutePath}/$it" }
        val first = scene.declared.firstOrNull()?.let { relPathKey(it) }
        return Diff(
            paths = paths,
            shape = ImpactShape(
                newFiles = appeared.size,
                overwriteTotal = changed.size,
                deleteCount = disappeared.size,
                deleteBytes = disappeared.values.sumOf { it.bytes },
                touchedPaths = paths,
            ),
            // 声明对象在副本里到底落了盘没有：新建 ⇒ 有字节；覆盖 ⇒ 摘要与改前不同。
            landed = first != null && after.containsKey(first) &&
                (scene.before[first] == null && after[first]!!.bytes > 0L ||
                    after[first]!!.sha != scene.before[first]?.sha),
        )
    }

    /** [diffOf] 的产物。 */
    private class Diff(val paths: List<String>, val shape: ImpactShape, val landed: Boolean)

    /** 相对写法归一（与判据层同一个口径）。 */
    private fun relPathKey(p: String): String =
        p.replace('\\', '/').trim().removePrefix("./").trimEnd('/').lowercase()

    /** 文件 sha256（小写 hex）；读不到 ⇒ 空串（空串与空串相等＝"两边都不存在"，不冒充摘要）。 */
    private fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrDefault("")
}
