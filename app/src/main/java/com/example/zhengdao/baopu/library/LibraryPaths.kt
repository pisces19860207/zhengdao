// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：java.io.File / java.nio.file.Path 语义（JDK 标准库）；
// 路径边界判定复用 `rootfs/PathGuard.kt`（组件级比较，防同前缀兄弟目录）。
package com.example.zhengdao.baopu.library

import android.content.Context
import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.baopu.BaopuWorkspace
import java.io.File

/**
 * 资料库三处路径 ＋ 扫描（刀 2 A 段）。
 *
 * ## 三处路径（用户 2026-10-16 拍板，台账 §E.12 定版）
 * - **共享区** ＝ `<工作区>/资料库/`（`工作区` = [Workspace.hostDir]，跟着设置走）：
 *   - `投递/` —— 用户丢原件的地方。**抱朴只扫这里，绝不扫工作区根**（根上有
 *     `_索引.md`、素材库等，扫了必误报——判据 §5.3 的两例负测就是钉这个）；
 *   - `整理/` —— 抱朴的**唯一对外输出口**（`md`/`json`/`_认脸索引.json`），
 *     终端 agent 只读这里。
 * - **私有原件** ＝ `context.filesDir/baopu/library/原始/`（App 私有 ⇒ 终端 agent
 *   真看不到；代价＝卸载/清数据会丢，防丢三条见 D 段）。
 *
 * ## 为什么路径计算分纯函数与 Context 壳两层
 * 本机无 Robolectric，[Workspace] 依赖 Android [Context] ⇒ JVM 单测打不到壳。
 * 所有判据（越界即拒、只扫投递、分类）都落在**纯函数**上（喂 [File] 进，出结论），
 * 壳只做「从 Context 取工作区」这一件事。
 */
internal object LibraryPaths {

    /** 共享区根目录名（挂在工作区下；与终端侧 `KnowledgeBase.DIR` 同名不同物——
     *  终端那份的 `原始/` 是旧布局，本刀的投递/整理是定版布局，见台账 §E.12）。 */
    const val DIR_LIBRARY = "资料库"

    /** 用户投递原件的目录（只扫这里）。 */
    const val SUB_DELIVER = "投递"

    /** 抱朴对外输出口（唯一）。 */
    const val SUB_DONE = "整理"

    /** App 私有目录里的原件目录名。 */
    const val SUB_RAW_PRIVATE = "原始"

    /** 私有资料库根（`filesDir/baopu/library`）下的两级目录名。 */
    const val DIR_BAOPU = "baopu"
    const val DIR_LIBRARY_PRIVATE = "library"

    /**
     * 单次扫描文件数上限（与 `KnowledgeBase.MAX_SCAN` 同量级）。
     * 达到上限**必须显式告知**（[ScanResult.truncated]），静默截断会让用户以为
     * 「资料全在这儿了」。
     */
    const val MAX_SCAN = 2000

    /** 硬上限：先收这么多个再排序截断（保证扫描结果确定性，不被 listFiles 顺序左右）。 */
    private const val HARD_CAP = MAX_SCAN * 4

    // ── Context 壳（薄：只负责把 Context 变成 File） ──────────────────────────

    /** 工作区根（唯一真相源 [Workspace.hostDir]；共享/仅私有两种模式都成立）。 */
    fun workspaceRoot(ctx: Context): File = BaopuWorkspace.hostDir(ctx)

    /** 共享区 `投递/`。 */
    fun deliverDir(ctx: Context): File = deliverDirUnder(workspaceRoot(ctx))

    /** 共享区 `整理/`（唯一对外输出口）。 */
    fun doneDir(ctx: Context): File = doneDirUnder(workspaceRoot(ctx))

    /** App 私有原件目录 `filesDir/baopu/library/原始/`。 */
    fun rawPrivateDir(ctx: Context): File = rawDirUnder(ctx.filesDir)

    // ── 纯函数（可 JVM 直测） ────────────────────────────────────────────────

    fun deliverDirUnder(workspace: File): File = File(File(workspace, DIR_LIBRARY), SUB_DELIVER)

    fun doneDirUnder(workspace: File): File = File(File(workspace, DIR_LIBRARY), SUB_DONE)

    fun rawDirUnder(filesDir: File): File =
        File(File(File(filesDir, DIR_BAOPU), DIR_LIBRARY_PRIVATE), SUB_RAW_PRIVATE)

    /**
     * 原件进私有目录的目标路径（**越界即拒**，判据 §5.4）。
     *
     * [relPath] 来自扫描结果（相对 `投递/` 的路径），但**不信任它**：`../` 逃逸、
     * 绝对路径、盘符一律在这里拒（返回 null），由调用方落 [com.example.zhengdao.rootfs.RunLog]。
     * 双保险：先显式查危险形态，再过 [PathGuard.isInside]（组件级）。
     *
     * @return 合法目标路径；越界 ⇒ null
     */
    fun destinationUnder(rawDir: File, relPath: String): File? {
        if (relPath.isBlank()) return null
        if (relPath.startsWith("/") || relPath.startsWith("\\")) return null
        if (relPath.contains(":")) return null // 盘符（C:\…）与 URL scheme 一并拒
        val components = relPath.replace('\\', '/').split('/')
        if (components.any { it == ".." }) return null
        if (components.any { it.isBlank() }) return null
        val dest = File(rawDir, relPath.replace('\\', '/'))
        if (!PathGuard.isInside(rawDir, dest)) return null
        return dest
    }

    // ── 扫描（只扫投递/） ─────────────────────────────────────────────────────

    /**
     * 文件分类：认领的文本件 / 认领的 Word 件（第一百二十一批起，抽正文后进流水线） /
     * 不认领（只列清单，不解析）。
     */
    enum class FileKind { TEXT, DOCX, UNCLAIMED }

    /**
     * 扫描到的一个文件。
     * @param relPath 相对 `投递/` 的路径（统一用 `/` 分隔，跨平台稳定）
     * @param reason 不认领原因（[FileKind.UNCLAIMED] 时非空，进 `_待外部转换.json`）
     */
    data class ScannedFile(
        val relPath: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val kind: FileKind,
        val reason: String = "",
    )

    /** 扫描结果（[truncated] = 达到上限，**必须**告知，不许静默）。 */
    data class ScanResult(val files: List<ScannedFile>, val truncated: Boolean)

    /**
     * 扫 `投递/`（**只扫这里**——判据 §5.3：工作区根的 `_索引.md`、`素材库/` 一律不进结果）。
     *
     * 递归、跳符号链接（不跟随、不复制链接本身）、跳过系统垃圾名
     * （`.DS_Store`/`Thumbs.db`/`._` 开头的 macOS 残件）。结果按 [ScannedFile.relPath]
     * **显式排序**（不依赖 listFiles 顺序 ⇒ 同一目录两次扫描字节一致）。
     */
    fun scanDeliver(dir: File): ScanResult = scanTree(dir)

    /**
     * 扫任意目录树（[scanDeliver] 与流水线扫私有 `原始/` 共用同一把尺子）。
     * 返回的 relPath 一律 `/` 分隔、相对传入目录。
     */
    fun scanTree(dir: File): ScanResult {
        if (!dir.isDirectory) return ScanResult(emptyList(), false)
        val collected = ArrayList<ScannedFile>()
        var hardTruncated = false
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && collected.size < HARD_CAP) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (f in children) {
                if (isSymlink(f)) continue
                when {
                    f.isDirectory -> stack.addLast(f)
                    f.isFile -> {
                        if (isJunkName(f.name)) continue
                        // 双保险：扫到的必须真在投递目录内（软链已跳，这里防极端 bind 挂载）
                        if (!PathGuard.isInside(dir, f)) continue
                        // ⚠️ Windows 上 dir.absolutePath 与 f.absolutePath 之间是 `\` 分隔，
                        // 只 trimStart('/') 会留下前导 `/`（2026-10-17 单测实测：relPath 变成
                        // `/a.txt` ⇒ destinationUnder 的「绝对路径即拒」把整批料全拒掉）。
                        // 两个分隔符都要trim，再统一成 `/`。
                        val rel = f.absolutePath
                            .removePrefix(dir.absolutePath)
                            .trimStart('/', '\\')
                            .replace('\\', '/')
                        val (kind, reason) = classify(f.name)
                        collected += ScannedFile(
                            relPath = rel,
                            name = f.name,
                            size = f.length(),
                            mtime = f.lastModified(),
                            kind = kind,
                            reason = reason,
                        )
                        if (collected.size >= HARD_CAP) {
                            hardTruncated = true
                            break
                        }
                    }
                }
            }
        }
        collected.sortBy { it.relPath }
        val truncated = hardTruncated || collected.size > MAX_SCAN
        return ScanResult(collected.take(MAX_SCAN), truncated)
    }

    // ── 扫描门槛（第三十一批 C 段：按需，不启动全量） ───────────────────────────

    /**
     * 顶层条目数（文件＋目录，**不递归**；跳软链）——扫描门槛用，成本 O(一层)。
     *
     * 为什么需要它：导入扫描挂在「进页面＋每轮 Settled」上（见 LibraryImport.scan 的调用方），
     * 料少时无所谓，料多时每轮都在整树爬。先花一次 listFiles 数顶层，超过 [MAX_SCAN]
     * 就**不再全量扫**，只列顶层并明说「太多了，先收窄到某个子目录」。
     */
    fun countTopLevel(dir: File): Int {
        if (!dir.isDirectory) return 0
        val children = dir.listFiles() ?: return 0
        return children.count { !isSymlink(it) }
    }

    /**
     * 只列**顶层文件**（不递归；跳软链与垃圾名；按名排序保证确定性）——
     * 门槛触发时的降级形态：告诉模型「这层有什么」，不假装看全了整棵树。
     */
    fun listTopLevelFiles(dir: File): List<ScannedFile> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<ScannedFile>()
        for (f in dir.listFiles() ?: return emptyList()) {
            if (isSymlink(f) || !f.isFile) continue
            if (isJunkName(f.name)) continue
            if (!PathGuard.isInside(dir, f)) continue
            val (kind, reason) = classify(f.name)
            out += ScannedFile(
                relPath = f.name,
                name = f.name,
                size = f.length(),
                mtime = f.lastModified(),
                kind = kind,
                reason = reason,
            )
        }
        out.sortBy { it.relPath }
        return out
    }

    /**
     * 按扩展名分类（**只认已知文本格式与 `.docx`**；其余一律不认领——不猜编码、不引解析库）。
     * 纯函数，单测直接打。
     */
    fun classify(name: String): Pair<FileKind, String> {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || name == ext) {
            // 没有扩展名（或名字就是"xxx"且无点）⇒ 无法判断，不认领
            return FileKind.UNCLAIMED to "没有扩展名，无法判断是不是文本"
        }
        if (ext in TEXT_EXTS) return FileKind.TEXT to ""
        // 第一百二十一批：`.docx` 认领——正文由 `DocxTextExtractor` 零依赖抽取
        // （zip + word/document.xml），接现有导入流水线；旧版 `.doc` 不是 zip，仍不认领
        if (ext == "docx") return FileKind.DOCX to ""
        if (ext in OFFICE_EXTS) return FileKind.UNCLAIMED to "Office/PDF 文档（.$ext），需外部转换"
        if (ext in IMAGE_EXTS) return FileKind.UNCLAIMED to "图片（.$ext），需外部转换"
        if (ext in ARCHIVE_EXTS) return FileKind.UNCLAIMED to "压缩包（.$ext），需外部转换"
        return FileKind.UNCLAIMED to "未知格式（.$ext），暂不认领"
    }

    /** 认领的文本扩展名（§E.11：`.txt/.md/.json/.yaml/.csv/.html/.xml/.log` 与源码文本等）。 */
    val TEXT_EXTS: Set<String> = setOf(
        "txt", "md", "markdown", "json", "jsonl", "yaml", "yml", "csv", "tsv",
        "html", "htm", "xml", "svg", "log",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cc", "cpp", "hpp",
        "rs", "go", "rb", "sh", "bash", "zsh", "sql", "toml", "ini", "cfg", "conf",
        "properties", "gradle",
    )

    private val OFFICE_EXTS: Set<String> = setOf(
        // docx 不在这张表里——第一百二十一批起它是认领件（FileKind.DOCX），见 classify
        "pdf", "doc", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
    )

    private val IMAGE_EXTS: Set<String> = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "tif", "tiff",
    )

    private val ARCHIVE_EXTS: Set<String> = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst",
    )

    /** 系统/工具垃圾名（不进清单；用户真文件不以此开头）。 */
    private fun isJunkName(name: String): Boolean =
        name == ".DS_Store" || name == "Thumbs.db" || name.startsWith("._")

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    // ── 写盘工具（共享：绝不产生半个文件） ───────────────────────────────────

    /**
     * 原子写：先写 `<目标>.tmp` 再 rename（rename 在同一文件系统内是原子操作）。
     * rename 失败（跨卷/权限）退回直接写——**失败返回 false**，调用方按需报账，
     * 不许静默当成功。
     */
    fun atomicWrite(target: File, text: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (tmp.renameTo(target)) {
            true
        } else {
            target.writeText(text, Charsets.UTF_8)
            runCatching { tmp.delete() }
            target.isFile
        }
    }.getOrDefault(false)
}
