// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android 官方文档（应用外部目录、java.io.File 语义）。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 资料库（P0：「丢进去就能用」的机械层）。
 *
 * 定位：把用户的私人文件变成一个**终端里的 agent 能自动找到并使用**的资料库。
 * 本层只做**机械活**（不涉及任何大模型）：
 *   1) 预置骨架目录：`资料库/{原始, 整理, 改版}` ＋ 说明文档
 *   2) 扫描 `原始/` → 生成 `整理/00-目录.md` 清单（原子写）
 *   3) 分发指路到 guest `/root/`：`AGENTS.md`（AGENTS.md 开放标准，25+ 工具通用）＋ `CLAUDE.md`
 *
 * 四条铁律（详见 docs/知识库-设计施工图.md）：
 *   - **原件不动**：`原始/` 只读，任何程序都不改、不删、不改名；用户要改文件 → 产物进 `改版/`
 *   - **写入者唯一**：`整理/` 只有本类会写；agent 与太极对其只读，避免互相覆盖成空文件
 *   - **原子写**：先写 `.tmp` 再 rename，绝不产生"半个文件"；写前校验非空、留 `.bak` 可回滚
 *   - **尊重用户删除**：用户删掉资料库目录后，不再重建（无流氓行为）
 *
 * 开销：常态下只做"文件是否存在"的检查；有变化才写盘。扫描在后台线程，不拖慢启动。
 */
object KnowledgeBase {

    const val DIR = "资料库"
    const val SUB_RAW = "原始"
    const val SUB_DONE = "整理"
    const val SUB_NEW = "改版"
    const val F_README = "说明.md"
    const val F_AGENTS = "AGENTS.md"
    const val F_CLAUDE = "CLAUDE.md"
    const val F_INDEX = "00-目录.md"

    /** 内容指纹：清单内容未变则不重写 */
    private const val KEY_SIG = "kb_sig_v1"
    /** 是否由 App 创建过（用于区分"还没建"与"用户删了"） */
    private const val KEY_CREATED = "kb_created_v1"
    /** 用户删除标记：置位后不再重建 */
    private const val KEY_DELETED = "kb_deleted_v1"
    /** 总开关（默认开）。关闭后不预置、不扫描、不分发 */
    private const val KEY_ENABLED = "kb_enabled_v1"
    /** 补摘要任务是否正在进行（P2 用；此刻由 App 写入，UI 只读） */
    private const val KEY_BUSY = "kb_busy_v1"

    /** 单次扫描文件数上限——防极端情况（用户丢了上万个小文件）拖住设备 */
    private const val MAX_SCAN = 2000

    /** 我们写入文件的标记行，用于识别"这段是本 App 写的" */
    private const val MARK = "<!-- zhengdao-kb -->"

    // ── 路径 ────────────────────────────────────────────────────────────────

    fun root(ctx: Context): File = File(Workspace.hostDir(ctx), DIR)
    fun rawDir(ctx: Context): File = File(root(ctx), SUB_RAW)
    fun doneDir(ctx: Context): File = File(root(ctx), SUB_DONE)
    fun revisedDir(ctx: Context): File = File(root(ctx), SUB_NEW)
    fun indexFile(ctx: Context): File = File(doneDir(ctx), F_INDEX)

    /** guest `/root` 的宿主侧路径（ProotLauncher 的 bind：filesDir/home → /root） */
    private fun guestHome(ctx: Context): File = File(ctx.filesDir, "home")

    /** 资料库是否已就绪（骨架存在） */
    fun isReady(ctx: Context): Boolean = File(root(ctx), F_README).isFile

    /** 总开关（默认开）。 */
    fun isEnabled(ctx: Context): Boolean = Settings.prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        Settings.prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * 供设置页显示的**四态**（"能读"与"整理好"是两件事）：
     * - `未挂载` —— 开关关 / 目录不存在 / `原始/` 是空的
     * - `已挂载` —— agent 已能读到（清单＋指路就绪）
     * - `整理中` —— 太极正在补摘要（P2）
     * - `已整理` —— 有摘要产出（P2）
     *
     * ⚠️ 本方法会读目录，**必须在 IO 线程调用**。
     */
    enum class State { DISABLED, NOT_MOUNTED, MOUNTED, BUSY, ORGANIZED }

    data class Status(
        val state: State,
        val files: Int,
        val path: String,
    )

    fun status(ctx: Context): Status {
        val r = root(ctx)
        val path = r.absolutePath
        if (!isEnabled(ctx)) return Status(State.DISABLED, 0, path)
        if (!r.isDirectory) return Status(State.NOT_MOUNTED, 0, path)
        if (Settings.prefs(ctx).getBoolean(KEY_BUSY, false)) {
            return Status(State.BUSY, countRaw(ctx), path)
        }
        val n = countRaw(ctx)
        if (n == 0) return Status(State.NOT_MOUNTED, 0, path)
        // 有摘要（清单里带"摘要"小节）视为已整理
        val organized = runCatching {
            indexFile(ctx).isFile && indexFile(ctx).readText(Charsets.UTF_8).contains("## 文件摘要")
        }.getOrDefault(false)
        return Status(if (organized) State.ORGANIZED else State.MOUNTED, n, path)
    }

    /** 轻量计数（只数文件，不建对象列表）。 */
    private fun countRaw(ctx: Context): Int {
        val base = rawDir(ctx)
        if (!base.isDirectory) return 0
        var n = 0
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        while (stack.isNotEmpty() && n < MAX_SCAN) {
            val dir = stack.removeLast()
            for (f in dir.listFiles() ?: continue) {
                if (isSymlink(f)) continue
                if (f.isDirectory) stack.addLast(f) else if (f.isFile) n++
            }
        }
        return n
    }

    /** 手动触发一次"重新整理"（设置页按钮）。 */
    fun requestRebuild(ctx: Context) {
        if (!isEnabled(ctx)) return
        val t = Thread({
            runCatching {
                ensureScaffold(ctx)
                rebuildIndex(ctx)
            }.onFailure { RunLog.log("资料库手动整理失败：${it.message}") }
        }, "zd-knowledge-base-rebuild")
        t.isDaemon = true
        t.start()
    }

    // ── 主入口 ──────────────────────────────────────────────────────────────

    /**
     * 每次启动终端会话前调用（由 ProotLauncher.buildLaunchPlan 触发）。
     *
     * 同步部分只有"预置骨架 ＋ 分发指路"——都是小文件写，毫秒级；
     * **扫描与清单生成放后台线程**，绝不阻塞启动。
     */
    fun refresh(ctx: Context) {
        runCatching {
            if (!isEnabled(ctx)) return@runCatching
            if (!ensureScaffold(ctx)) return@runCatching
            dispatch(ctx)
            val t = Thread({
                runCatching { rebuildIndex(ctx) }
                    .onFailure { RunLog.log("资料库清单生成失败：${it.message}") }
            }, "zd-knowledge-base-index")
            t.isDaemon = true
            t.start()
        }.onFailure { RunLog.log("资料库刷新失败：${it.message}") }
    }

    // ── 预置骨架 ────────────────────────────────────────────────────────────

    /**
     * 确定目录与骨架文件存在。
     * @return true = 资料库可用（后续扫描/分发照常）；false = 不该用（仅私有模式 / 用户已删除）
     */
    fun ensureScaffold(ctx: Context): Boolean {
        val prefs = Settings.prefs(ctx)
        val r = root(ctx)

        // 仅私有模式：不主动预置（那是"在意痕迹"的场景，不该凭空塞东西）；
        // 但用户既然自己建了这个目录，就说明他想用 —— 补齐骨架。
        if (!Workspace.isShared(ctx)) {
            if (!r.isDirectory) return false
            return seedFiles(ctx)
        }

        if (r.isDirectory) {
            prefs.edit().putBoolean(KEY_CREATED, true).apply()
            return seedFiles(ctx)
        }

        // 目录不存在：是我们建过、被用户删了？还是从没建过？
        if (prefs.getBoolean(KEY_CREATED, false) || prefs.getBoolean(KEY_DELETED, false)) {
            prefs.edit().putBoolean(KEY_DELETED, true).apply()
            return false
        }

        if (!r.mkdirs()) return false
        val ok = seedFiles(ctx)
        if (ok) {
            prefs.edit().putBoolean(KEY_CREATED, true).apply()
            RunLog.log("资料库已预置：${r.absolutePath}")
        }
        return ok
    }

    /** 建子目录 ＋ 写说明文档（幂等：内容未变不重写）。 */
    private fun seedFiles(ctx: Context): Boolean {
        rawDir(ctx).mkdirs()
        doneDir(ctx).mkdirs()
        revisedDir(ctx).mkdirs()
        val r = root(ctx)
        writeIfChanged(File(r, F_README), readmeText())
        writeIfChanged(File(r, F_AGENTS), libraryAgentsText())
        writeIfChanged(File(r, F_CLAUDE), "@AGENTS.md\n")
        return r.isDirectory
    }

    // ── 分发指路（给终端里的各种 agent）─────────────────────────────────────

    /**
     * 写 guest `/root/AGENTS.md` ＋ `/root/CLAUDE.md`。
     *
     * 为什么写这里：终端 agent 的**起点目录就是 `/root`**（`-w /root`），
     * 写这里才能被自动读到。`AGENTS.md` 是跨 agent 开放标准（25+ 工具原生支持）；
     * Claude Code 例外（它读 `CLAUDE.md`），故补一份只含 `@AGENTS.md` 的瘦文件。
     *
     * ⚠️ 与太极那份**完全分开**：太极读的是 `<XDG_CONFIG_HOME>/opencode/AGENTS.md`，
     *    两者路径不同、用途不同（太极那份只说"环境"，本份额外指路资料库）。
     */
    private fun dispatch(ctx: Context) {
        val home = guestHome(ctx)
        if (!home.isDirectory && !home.mkdirs()) return
        writeOrAppend(File(home, F_AGENTS), dispatchAgentsText(ctx))
        writeIfChanged(File(home, F_CLAUDE), "@AGENTS.md\n")
    }

    // ── 扫描 ＋ 清单 ────────────────────────────────────────────────────────

    private data class Item(val rel: String, val size: Long, val mtime: Long)

    /**
     * 扫描 `原始/`（递归，**跳过符号链接**）。
     *
     * ⚠️ 跳软链是必须的：本项目的缓存统计曾因 `walkTopDown()` 跟随软链而虚报约 2 倍
     *    （见 CacheCleaner 的注释），此处沿用 `Files.isSymbolicLink` 判定。
     */
    private fun scanRaw(ctx: Context): List<Item> {
        val base = rawDir(ctx)
        if (!base.isDirectory) return emptyList()
        val out = ArrayList<Item>()
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        while (stack.isNotEmpty() && out.size < MAX_SCAN) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (isSymlink(f)) continue
                when {
                    f.isDirectory -> stack.addLast(f)
                    f.isFile -> {
                        val rel = f.absolutePath.removePrefix(base.absolutePath).trimStart(File.separatorChar)
                        out.add(Item(rel, f.length(), f.lastModified()))
                        if (out.size >= MAX_SCAN) break
                    }
                }
            }
        }
        out.sortBy { it.rel }
        return out
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** 重建 `整理/00-目录.md`。内容未变则跳过。 */
    fun rebuildIndex(ctx: Context) {
        if (!rawDir(ctx).isDirectory) return
        val items = scanRaw(ctx)

        // 指纹 = 工作区路径 ＋ 每个文件的相对路径/大小/修改时间
        val sb = StringBuilder(Workspace.hostDir(ctx).absolutePath)
        for (it in items) sb.append('|').append(it.rel).append(':').append(it.size).append(':').append(it.mtime)
        val sig = sb.toString().hashCode().toString()

        val prefs = Settings.prefs(ctx)
        if (prefs.getString(KEY_SIG, null) == sig) return

        val text = indexText(items)
        if (atomicWriteChecked(indexFile(ctx), text)) {
            prefs.edit().putString(KEY_SIG, sig).apply()
        }
    }

    private fun indexText(items: List<Item>): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        sb.append("# 资料库目录\n\n")
        sb.append("> 由证道自动生成 · 更新于 ").append(ts).append("  \n")
        sb.append("> 原始文件在 `原始/` 里（**只读**）。本文件只是索引。\n\n")
        if (items.isEmpty()) {
            sb.append("_（还没有文件。把资料放进 `原始/`，就会出现在这里。）_\n")
        } else {
            sb.append("共 **").append(items.size).append("** 个文件：\n\n")
            for (it in items) {
                sb.append("- `").append(it.rel).append("` — ").append(humanSize(it.size)).append("\n")
            }
        }
        sb.append("\n---\n\n")
        sb.append("## 给 AI 的提示（重要）\n\n")
        sb.append("- 上面这些文件在 `原始/` 里，**只读** —— 不要修改、改名或删除它们\n")
        sb.append("- 用户要求「帮我改这个文件」时：读原件，把**完整改版**写进 `改版/`，并在回复里说明「原件没动」\n")
        sb.append("- 本文件（`00-目录.md`）由证道维护 —— **不要往里写东西**，否则会和 App 的写入互相覆盖\n")
        sb.append("- 需要细节时直接读 `原始/` 里的原文件\n")
        return sb.toString()
    }

    // ── 写入工具 ────────────────────────────────────────────────────────────

    /** 原子写：先写 `.tmp` 再 rename（rename 是原子操作，绝不产生半个文件）。 */
    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            runCatching { target.writeText(text, Charsets.UTF_8) }
            runCatching { tmp.delete() }
        }
    }

    /**
     * 带校验的原子写：空内容拒绝写入；覆盖前留 `.bak` 可回滚。
     * @return true = 已写入或内容本来就一致
     */
    private fun atomicWriteChecked(target: File, text: String): Boolean {
        if (text.isBlank()) return false
        val old = if (target.isFile) runCatching { target.readText(Charsets.UTF_8) }.getOrNull() else null
        if (old == text) return true
        if (old != null) {
            runCatching {
                File(target.parentFile, target.name + ".bak").writeText(old, Charsets.UTF_8)
            }
        }
        atomicWrite(target, text)
        return true
    }

    /** 内容未变不重写（幂等，避免无意义的写盘与 mtime 抖动）。 */
    private fun writeIfChanged(target: File, text: String) {
        runCatching {
            val old = if (target.isFile) target.readText(Charsets.UTF_8) else null
            if (old == text) return
            atomicWrite(target, text)
        }
    }

    /**
     * 写入，但**不覆盖别人的内容**：
     * - 文件不存在 → 写
     * - 已有我们的标记（MARK）→ 整份更新
     * - 已有别人的内容 → **追加**我们的段落（不删别人的）
     */
    private fun writeOrAppend(target: File, text: String) {
        runCatching {
            if (!target.isFile) {
                atomicWrite(target, text)
                return
            }
            val cur = target.readText(Charsets.UTF_8)
            when {
                cur.contains(MARK) -> if (cur != text) atomicWrite(target, text)
                cur.contains(text) -> Unit
                else -> atomicWrite(target, cur.trimEnd() + "\n\n" + text)
            }
        }
    }

    private fun humanSize(n: Long): String = when {
        n >= 1024L * 1024L -> String.format(Locale.CHINA, "%.1f MB", n / 1024.0 / 1024.0)
        n >= 1024L -> String.format(Locale.CHINA, "%.1f KB", n / 1024.0)
        else -> "$n B"
    }

    // ── 文案 ────────────────────────────────────────────────────────────────

    /** `资料库/说明.md` —— 给**用户**看，通篇人话、不出现任何技术词。 */
    private fun readmeText(): String = """
        # 这个文件夹是什么

        这是你的**资料库**。放进去的东西，终端里的 AI 就能读到，并用来回答你。

        ## 怎么用

        1. 把你的文件放进 `原始/`（各种格式都行：txt、md、word、pdf……）
        2. 打开终端，直接问 AI —— 它会自己去读
        3. 就这样，不用做别的

        ## 三个文件夹，各管一件事

        | 文件夹 | 是什么 | 你能干什么 |
        |---|---|---|
        | `原始/` | 你放原件的地方 | 随便放、随便删。**AI 不会动这里面的东西** |
        | `整理/` | AI 整理出来的目录和摘要 | 看看就好，别手动改 |
        | `改版/` | 你让 AI 改文件时，改好的版本 | 满意就自己拿去替换原件 |

        ## 你的东西安全吗

        - 文件**全部存在你自己的手机上**，不会上传到任何服务器
        - AI **不会修改、不会删除** `原始/` 里的任何东西
        - 就算你让它改 —— 它也只会把改好的放进 `改版/`，原件原地不动

        ## 想让 AI 更快找到

        - 文件放进 `原始/` 就行，它自己会看
        - 放了 word 或 pdf？AI 可能要先转一下格式，第一次会慢一点

        $MARK
    """.trimIndent()

    /** `资料库/AGENTS.md` —— 给 **agent** 看的说明书（详细版）。 */
    private fun libraryAgentsText(): String = """
        # AGENTS.md —— 用户资料库

        $MARK
        用户把私人资料放在了 `原始/` 里。**用户不会整理它，而且没有备份** —— 所以下面的规则是硬的。

        ## 目录

        - `原始/` —— 用户的原始文件。**只读区**
        - `整理/` —— 由证道 App 生成的目录与摘要。**你不要写它**
        - `改版/` —— 你唯一可以写的地方

        ## 铁律

        1. ⛔ **`原始/` 一个字都不许改**：不修改、不改名、不删除。
        2. ⛔ **不要写 `整理/`**：它由 App 统一维护；同时可能有别的程序在写它，
           你一插进去就会互相覆盖，最后变成空文件。
        3. ✅ **你的产出只能写进 `改版/`**。

        ## 怎么帮用户

        - 先读 `整理/00-目录.md` 看资料库里有什么；需要细节时再读 `原始/` 里的原文件。
        - 用户说「帮我改一下这个文件」时：读原件 → 把**完整改版**写进 `改版/<原名>（改）.<扩展名>`
          → 回复里说明"**原件没动，改好的在 `改版/` 里**"。
        - 发现某个文件还没被整理过 → **在回复里告诉用户**即可，由 App 去处理；**你自己不要写索引**。
        - 遇到 `.docx` / `.pdf` 读不了时，先用工具转成文本，**转出的副本放 `改版/`**。
    """.trimIndent()

    /**
     * 分发到 guest `/root/AGENTS.md` —— 终端 agent 的**开箱说明**。
     * 含：环境说明（治"以为自己在电脑里"）＋ 资料库指路。
     */
    private fun dispatchAgentsText(ctx: Context): String {
        val ws = Workspace.hostDir(ctx).absolutePath
        val wsNote = if (Workspace.isShared(ctx)) "手机文件管理器直接可见、可自由删除；卸载证道后仍保留"
        else "应用专属目录，随应用卸载自动删除"
        val kb = root(ctx).absolutePath
        val hasRaw = rawDir(ctx).isDirectory && (rawDir(ctx).list()?.isNotEmpty() == true)
        return """
            # 证道运行环境说明（每次对话开始前必读）

            $MARK
            ## 你的身份
            你运行在用户的安卓手机上——一个由证道 App 通过 proot 运行的 Debian 环境。
            禁止声称「我不在手机上」「我没有文件系统」；你就在手机里，文件就在下面这些路径。

            ## 文件地图
            - /workspace —— **产出与边界区**（手机侧：$ws；$wsNote）
            - /sdcard —— 共享存储整体可读可写，用于查找资料；**产出约定只进 /workspace**
            - /root —— 你的 home；各 Agent 配置在此（~/.hermes、~/.claude 等）

            ## 用户的资料库${if (hasRaw) "（里面有东西）" else "（目前是空的）"}
            路径：`$kb`（guest 内 `/workspace/资料库/`）
            - `原始/` —— 用户的原始文件，**只读，一个字都不许改**
            - `整理/00-目录.md` —— 资料清单，**先读它**了解有什么
            - `改版/` —— 用户让你改文件时，把改好的放进这里（**原件保持不动**）
            详细规则见 `/workspace/资料库/AGENTS.md`。

            ## 能力边界
            - 无 root，不要尝试需要 root 的操作
            - 禁止执行 apt upgrade；装依赖用 pip / npm
            - 找不到用户文件时：先 ls /workspace 和 /sdcard/Download，把已搜索的路径列出来再下结论，不要直接放弃
        """.trimIndent()
    }
}
