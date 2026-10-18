// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 docs/派工单-2026-10-19-第三十一批-资料库整理.md（A 段 library_list 只读清单、
// B 段 library_organize 写落地）。用户原话：「资料库现在给抱朴也可以整理，不再只是 APP 自动落
// 那一堆东西了，给 APP 减下负。」
//
// 五条硬纪律（逐条对应判据 L1/L3/L4/L5/L6）：
// 1. library_list **纯只读**：无 write/delete/mkdir，跑完目标目录 mtime 都不变（L1）；
// 2. library_list 输出封顶走 [com.example.zhengdao.baopu.agent.BrowserTools.MAX_TEXT_CHARS]
//    同口径，超了**明说还有多少条**，不许静默截（L2）；
// 3. library_organize 三个动作一律 `ToolPermission.CONFIRM`，一次调用只做一件（L3）；
// 4. promote 同名已存在 ⇒ **拒绝并说清，绝不覆盖**（L4；与 move_path 的「加后缀让路」刻意
//    不同——整理是人工决策，让路等于替用户决定留哪份）；
// 5. 越界（`..`/绝对路径/盘符/指向 `原始/`）一律 Rejected 且错误信息是人话没有堆栈（L5）；
//    `资料库/原始/` 一个字节都不碰（L6）——工具只够得着工作区里的 投递/ 与 整理/，
//    App 私有 `原始/` 在工作区外，PathGuard 天然挡住，单测再放哨兵钉死。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.library.FaceIndex
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.util.TimeFormats
import java.io.File

/**
 * 资料库两件套（第三十一批）：[list] 给抱朴看清楚，[organize] 让抱朴真落地。
 *
 * 为什么并入一个 object：两个工具共享同一组路径判据（投递/整理/索引文件）与同一套
 * 「相对哪个目录」的解析，拆开必然各写一份、各漂一份。
 *
 * 为什么 promote 用 rename 而不是 copy+delete：同分区 rename 是原子的——要么搬完，
 * 要么原样；复制＋删除中间失败会留下「两边都有」或「两边都没有」（后者是资料丢失）。
 * rename 失败（跨卷/权限）⇒ 明说失败，文件原样没动，不静默。
 */
internal object LibraryTools {

    const val ARG_DIR = "dir"
    const val ARG_PATTERN = "pattern"
    const val ARG_ACTION = "action"
    const val ARG_PATH = "path"
    const val ARG_NEW_NAME = "new_name"

    /** 索引文件（工作区内 `资料库/索引.md`；只覆写这一个文件）。 */
    const val INDEX_NAME = "索引.md"

    /** 新文件名长度封顶（与附件消毒同一口径：保留扩展名地截）。 */
    const val MAX_NAME_CHARS = 60

    // ── A 段：library_list（只读） ────────────────────────────────────────────

    fun listSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "library_list",
        description = "列资料库的清单（只读，一个字节都不动）：投递/（用户丢进来的原件）或 整理/" +
            "（抱朴的产出）。每条给相对路径、字节数、扩展名、sha256 前 8 位（肉眼判断重复）。" +
            "输出封顶 ${BrowserTools.MAX_TEXT_CHARS} 字符，超了会写明还有多少条——用 pattern 收窄重试。" +
            "整理前先看一眼，别让抱朴瞎猜文件结构。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_DIR to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("deliver", "done"),
                    "description" to "列哪个目录：deliver=投递（默认），done=整理",
                ),
                ARG_PATTERN to jsonObjectOf(
                    "type" to "string",
                    "description" to "可选：文件名/路径子串过滤（不区分大小写），命中才列",
                ),
            ),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        list(
            root = workspaceRoot(),
            dir = args.optString(ARG_DIR).trim().ifEmpty { "deliver" },
            pattern = args.optString(ARG_PATTERN).trim().ifBlank { null },
        )
    }

    /**
     * 列清单（**纯函数**：root 已定、无 Android 依赖，JVM 直测）。
     *
     * sha256 只给**真正印出来的行**算（封顶前先量行长，不浪费 IO）——清单动辄上千条，
     * 每条都算哈希等于每次调用把整个资料库读一遍。
     */
    fun list(root: File, dir: String = "deliver", pattern: String? = null): ToolOutcome {
        val target = when (dir) {
            "done" -> LibraryPaths.doneDirUnder(root)
            else -> LibraryPaths.deliverDirUnder(root)
        }
        if (!PathGuard.isInside(root, target)) {
            return ToolOutcome.Rejected("路径越界被拒：资料库目录不在工作区（${root.absolutePath}）内。")
        }
        if (!target.isDirectory) {
            return ToolOutcome.Ok("（空清单）：${relOf(root, target)} 目录还不存在（还没有人投过件）")
        }
        val scan = LibraryPaths.scanTree(target)
        val pat = pattern?.trim().orEmpty()
        val matched = if (pat.isEmpty()) {
            scan.files
        } else {
            scan.files.filter { it.name.contains(pat, ignoreCase = true) || it.relPath.contains(pat, ignoreCase = true) }
        }
        val sb = StringBuilder()
        sb.append("目录：${relOf(root, target)}｜扫描到 ${scan.files.size} 条")
        if (scan.truncated) sb.append("（已达扫描上限 ${LibraryPaths.MAX_SCAN} 条，未列全）")
        if (pat.isNotEmpty()) sb.append("｜pattern「$pat」命中 ${matched.size} 条")
        sb.append('\n')
        if (matched.isEmpty()) {
            sb.append("（没有可列的文件）\n")
        }
        var shown = 0
        var bytes = 0L
        for (f in matched) {
            // 先量行长（sha 固定 8 字符）再决定要不要真算——封顶外的行一个字节都不读
            val provisional = "${f.relPath}\t${f.size} B\t${extOf(f.name)}\t00000000\n"
            if (shown > 0 && sb.length + provisional.length > BrowserTools.MAX_TEXT_CHARS) break
            val sha8 = runCatching { FaceIndex.sha256(File(target, f.relPath)) }.getOrNull()?.take(8) ?: "读取失败"
            sb.append(f.relPath).append('\t').append(f.size).append(" B\t")
                .append(extOf(f.name)).append('\t').append(sha8).append('\n')
            shown++
            bytes += f.size
        }
        val rest = matched.size - shown
        if (rest > 0) {
            sb.append("…还有 $rest 条没列（输出封顶 ${BrowserTools.MAX_TEXT_CHARS} 字符）——")
                .append("用 pattern 收窄后重试，或先 library_organize 归掉一批再看\n")
        }
        if (shown > 0) sb.append("（以上 $shown 条共 $bytes B；只读清单，没动任何文件）\n")
        sb.append("（整理动作用 library_organize：promote 移到整理 / rename 改名 / index 重建索引）")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    // ── B 段：library_organize（写，一律 CONFIRM） ────────────────────────────

    fun organizeSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "library_organize",
        description = "把抱朴的整理结果落地（每次调用只做一件，执行前 App 弹确认卡）：" +
            "promote＝把投递里的一个文件移到整理（同名已存在会拒绝，不覆盖）；" +
            "rename＝在同一个目录内改名（新名不带路径分隔符）；" +
            "index＝重建 资料库/索引.md（只覆写这一个文件）。" +
            "只动 投递/ 与 整理/，绝不动 原始/；冲突、越界、不存在都会说清原因，不静默失败。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ACTION to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("promote", "rename", "index"),
                    "description" to "promote=投递→整理；rename=同目录改名；index=重建 资料库/索引.md",
                ),
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "promote/rename 用：相对 投递/ 或 整理/ 的路径（如 a.txt 或 武侠/a.txt）",
                ),
                ARG_NEW_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "rename 用：新文件名（纯文件名，不带路径分隔符）",
                ),
            ),
            "required" to jsonArrayOf(ARG_ACTION),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        organize(
            root = workspaceRoot(),
            action = args.optString(ARG_ACTION).trim(),
            path = args.optString(ARG_PATH).trim().ifBlank { null },
            newName = args.optString(ARG_NEW_NAME).trim().ifBlank { null },
        )
    }

    /**
     * 整理落地（**纯函数**：root 已定、无 Android 依赖，JVM 直测）。
     *
     * @param now 索引时间戳（注入以便 JVM 直测确定性输出）
     */
    fun organize(
        root: File,
        action: String,
        path: String?,
        newName: String?,
        now: Long = System.currentTimeMillis(),
    ): ToolOutcome {
        val deliver = LibraryPaths.deliverDirUnder(root)
        val done = LibraryPaths.doneDirUnder(root)
        if (!PathGuard.isInside(root, deliver) || !PathGuard.isInside(root, done)) {
            return ToolOutcome.Rejected("路径越界被拒：资料库目录不在工作区（${root.absolutePath}）内。")
        }
        return when (action) {
            "promote" -> promote(root, deliver, done, path)
            "rename" -> rename(root, deliver, done, path, newName)
            "index" -> index(root, done, now)
            else -> ToolOutcome.Failed(
                "不认识的动作：$action（只认 promote／rename／index；一次调用只做一件）",
            )
        }
    }

    /** promote：投递/<rel> → 整理/<rel>（同名已存在 ⇒ 拒绝，不覆盖）。 */
    private fun promote(root: File, deliver: File, done: File, path: String?): ToolOutcome {
        val rel = normalizeRel(path, "投递") ?: return ToolOutcome.Failed("promote 需要 path（投递里的文件相对路径，如 a.txt）")
        val src = underDir(deliver, rel)
            ?: return ToolOutcome.Rejected(
                "路径越界被拒：$rel 不在 投递/ 内（不许 ..／绝对路径／盘符；library_organize 只动投递与整理）",
            )
        if (!src.exists()) {
            return ToolOutcome.Failed("投递里没有这个文件：$rel（先用 library_list dir=deliver 看清有什么）")
        }
        if (!src.isFile) {
            return ToolOutcome.Failed("这是个目录不是文件：$rel（promote 只移单个文件；目录请逐个文件来）")
        }
        val dst = File(done, rel)
        if (!PathGuard.isInside(root, dst)) {
            return ToolOutcome.Rejected("路径越界被拒：目标路径不在工作区内。")
        }
        if (dst.exists()) {
            return ToolOutcome.Failed(
                "整理/ 里已有同名文件：$rel（不覆盖——两份都可能有用；先 rename 其一，或你自己确认后删旧的）",
            )
        }
        val size = src.length()
        val moved = runCatching {
            dst.parentFile?.mkdirs()
            src.renameTo(dst)
        }.getOrDefault(false)
        if (!moved) {
            return ToolOutcome.Failed(
                "搬不动：$rel → 整理/$rel（rename 失败：跨存储或权限？）。文件原样没动，没有半截状态。",
            )
        }
        return ToolOutcome.Ok("已移到 整理/$rel（$size B）。投递里那份已经不在；要留底请先自己复制一份。")
    }

    /** rename：同一目录内改名（新名过 sanitize 同口径；目标已存在 ⇒ 拒绝）。 */
    private fun rename(root: File, deliver: File, done: File, path: String?, newName: String?): ToolOutcome {
        val rawName = newName?.trim().orEmpty()
        if (rawName.isEmpty()) return ToolOutcome.Failed("rename 需要 new_name（新文件名，纯文件名）")
        val cleaned = sanitizeName(rawName)
        if (cleaned == null || cleaned != rawName) {
            return ToolOutcome.Failed(
                "新文件名不合法：$rawName" +
                    (if (cleaned != null) "（按口径净化后应是 $cleaned，请直接用净化后的名字）" else "") +
                    "——不许路径分隔符／控制字符／前导点，长度封顶 $MAX_NAME_CHARS 字",
            )
        }
        val rel = normalizeRel(path, "投递或整理") ?: return ToolOutcome.Failed("rename 需要 path（要改名的文件相对路径）")
        // 危险形态先显式拒（..／绝对路径／盘符）：destinationUnder 对这两处都返回 null 即越界
        if (underDir(deliver, rel) == null && underDir(done, rel) == null) {
            return ToolOutcome.Rejected(
                "路径越界被拒：$rel 形态不合法（不许 ..／绝对路径／盘符；library_organize 只动投递与整理）",
            )
        }
        // 投递/ 与 整理/ 都找一遍；两边都有 ⇒ 说清让模型指哪个（不猜）
        val inDeliver = underDir(deliver, rel)?.takeIf { it.isFile }
        val inDone = underDir(done, rel)?.takeIf { it.isFile }
        val src = when {
            inDeliver != null && inDone != null -> return ToolOutcome.Failed(
                "投递/ 和 整理/ 里都有 $rel——请把路径写全（资料库/投递/$rel 或 资料库/整理/$rel）指明改哪个",
            )
            inDeliver != null -> inDeliver
            inDone != null -> inDone
            else -> return ToolOutcome.Failed(
                "投递/ 与 整理/ 里都没有这个文件：$rel（先用 library_list 确认路径）",
            )
        }
        val parent = src.parentFile ?: return ToolOutcome.Failed("定位不到所在目录：$rel")
        val dst = File(parent, cleaned)
        if (dst.absolutePath == src.absolutePath) {
            return ToolOutcome.Failed("新名和旧名一样：$cleaned（什么都没改）")
        }
        if (dst.exists()) {
            return ToolOutcome.Failed("同目录下已有 $cleaned（不覆盖——先换个名字，或你自己确认后删旧的）")
        }
        val size = src.length()
        val renamed = runCatching { src.renameTo(dst) }.getOrDefault(false)
        if (!renamed) {
            return ToolOutcome.Failed("改不了名：$rel → $cleaned（rename 失败：权限或 IO？）。文件原样没动。")
        }
        return ToolOutcome.Ok("已改名：$rel → $cleaned（$size B，还在 ${relOf(root, parent)}/）。")
    }

    /**
     * index：重建 `资料库/索引.md`（**只覆写这一个文件**；内容＝整理/ 的清单，一行一条）。
     *
     * 整份重写而不是追加：索引是「当前整理/ 的样子」的快照，追加会把删掉的文件留在索引里
     * （索引说谎比没有索引更糟）。原子写（先 .tmp 再 rename）沿用 [LibraryPaths.atomicWrite]。
     */
    private fun index(root: File, done: File, now: Long): ToolOutcome {
        val scan = if (done.isDirectory) LibraryPaths.scanTree(done) else LibraryPaths.ScanResult(emptyList(), false)
        val sb = StringBuilder()
        sb.append("# 资料库索引\n\n")
        sb.append("> 抱朴 library_organize(index) 生成于 ${TimeFormats.fileStamp(now)}；一行一条，只列 整理/。\n")
        sb.append("> 重新生成会整份覆写本文件；投递里的原件不在这里，私有原件在 App 的 原始/。\n\n")
        var bytes = 0L
        scan.files.forEach { f ->
            sb.append("- ").append(f.relPath).append('\t').append(f.size).append(" B\t")
                .append(extOf(f.name)).append('\n')
            bytes += f.size
        }
        if (scan.files.isEmpty()) {
            sb.append("（整理/ 还是空的——没有可索引的产出）\n")
        }
        if (scan.truncated) {
            sb.append("\n（注意：整理/ 的条目已达扫描上限 ${LibraryPaths.MAX_SCAN} 条，索引未列全）\n")
        }
        val indexFile = File(File(root, LibraryPaths.DIR_LIBRARY), INDEX_NAME)
        if (!PathGuard.isInside(root, indexFile)) {
            return ToolOutcome.Rejected("路径越界被拒：索引文件不在工作区内。")
        }
        val wrote = LibraryPaths.atomicWrite(indexFile, sb.toString())
        if (!wrote) {
            return ToolOutcome.Failed("索引写不进去：${relOf(root, indexFile)}（权限或 IO 错误）。整理/ 的文件一个都没动。")
        }
        return ToolOutcome.Ok(
            "已重建 ${relOf(root, indexFile)}：${scan.files.size} 条共 $bytes B" +
                "（只覆写了这一个文件；${if (scan.truncated) "注意未列全" else "全量"}）。",
        )
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    /**
     * 把模型给的相对路径归一到「相对投递/整理」的口径，并挡掉危险形态。
     *
     * 宽容的一处：模型常把工作区全路径写进来（`资料库/投递/a.txt`）——剥掉这层前缀，
     * 剩下的仍要过 [LibraryPaths.destinationUnder] 的显式危险形态检查 ＋ PathGuard 双保险。
     */
    private fun normalizeRel(path: String?, what: String): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        var rel = raw.replace('\\', '/')
        for (prefix in listOf("${LibraryPaths.DIR_LIBRARY}/${LibraryPaths.SUB_DELIVER}/", "${LibraryPaths.DIR_LIBRARY}/${LibraryPaths.SUB_DONE}/")) {
            if (rel.startsWith(prefix)) rel = rel.removePrefix(prefix)
        }
        if (rel.isEmpty() || rel == "/") return null
        if (rel.startsWith("/")) return null
        return rel
    }

    /** [rel] 解析到 [base] 之下（越界 ⇒ null）。复用 [LibraryPaths.destinationUnder]，不另起一套。 */
    private fun underDir(base: File, rel: String): File? = LibraryPaths.destinationUnder(base, rel)

    /**
     * 文件名消毒（与 `ui/baopu/BaopuAttachments.sanitize` 同口径：分隔符/控制字符压 `_`、
     * 去前导点、长度封顶）。净化后与原文不同 ⇒ 调用方拒绝（不静默改用户的意图）。
     *
     * 与附件口径的**一处刻意差异**：封顶时**永远保留扩展名**（附件那份只在「最后一个点落在
     * 前 12 字符」时保留——对 `书名（一长串）.txt` 会把 .txt 削掉，资料库场景不能接受）。
     *
     * @return 合法名；净化后为空 ⇒ null
     */
    fun sanitizeName(raw: String): String? {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .trim()
            .removePrefix(".")
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= MAX_NAME_CHARS) return cleaned
        val dot = cleaned.lastIndexOf('.')
        if (dot in 1 until cleaned.length - 1) {
            val ext = cleaned.substring(dot)
            val base = cleaned.take(MAX_NAME_CHARS - ext.length)
            if (base.isNotEmpty()) return base + ext
        }
        return cleaned.take(MAX_NAME_CHARS)
    }

    private fun extOf(name: String): String {
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty() || ext == name) "-" else ext.lowercase()
    }

    private fun relOf(root: File, f: File): String = runCatching {
        f.absoluteFile.toRelativeString(root.absoluteFile).replace('\\', '/')
    }.getOrDefault(f.name)
}
