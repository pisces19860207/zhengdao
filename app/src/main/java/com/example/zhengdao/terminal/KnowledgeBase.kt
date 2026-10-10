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
    /** 补摘要任务开始的时间戳（用于识别"上一轮留下的僵尸标记"） */
    private const val KEY_BUSY_AT = "kb_busy_at_v1"

    /**
     * 「整理中」标记的存活上限。
     * 超过它就认为那一轮已经不在了（App 被系统杀掉、或进程重启后残留）——
     * 否则设置页会永远显示「整理中」，用户会以为卡死了。
     */
    private const val BUSY_STALE_MS = 5L * 60L * 1000L

    /**
     * 单次扫描文件数上限——防极端情况（用户丢了上万个小文件）拖住设备。
     *
     * ⚠️ **达到上限时必须显式告知**（清单里写一行、设置页状态带后缀）——
     * 静默截断会让用户以为"资料全在这儿了"。
     * 公开给 UI 用（[com.example.zhengdao.ui.SettingsScreen] 判"是否已达上限"）。
     */
    const val MAX_SCAN = 2000

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
        // ⚠️ 用 isBusy() 而不是裸读 KEY_BUSY：它会自愈"上一轮留下的僵尸标记"（见 BUSY_STALE_MS）
        if (isBusy(ctx)) {
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

    /**
     * 手动触发一次"重新整理"（设置页按钮）。
     *
     * ⚠️ 与自动流程的关键区别：这是**用户的显式要求**，所以要 forced = true
     * —— 否则 [ensureScaffold] 看到"用户已删除"标记会拒绝重建，这个按钮就成了点不动的摆设（E-061）。
     */
    fun requestRebuild(ctx: Context) {
        if (!isEnabled(ctx)) return
        val t = Thread({
            runCatching {
                if (ensureScaffold(ctx, forced = true)) dispatch(ctx)
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
     * @param forced true = 用户显式要求（设置页「重新整理」／重新打开开关），
     *   此时即使他此前删过资料库也要重建（E-061）。
     * @return true = 资料库可用（后续扫描/分发照常）；false = 不该用（仅私有模式 / 用户已删除）
     */
    fun ensureScaffold(ctx: Context, forced: Boolean = false): Boolean {
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
        if (!shouldCreate(
                everCreated = prefs.getBoolean(KEY_CREATED, false),
                userDeleted = prefs.getBoolean(KEY_DELETED, false),
                forced = forced,
            )
        ) {
            prefs.edit().putBoolean(KEY_DELETED, true).apply()
            return false
        }

        if (!r.mkdirs()) return false
        val ok = seedFiles(ctx)
        if (ok) {
            prefs.edit().putBoolean(KEY_CREATED, true).putBoolean(KEY_DELETED, false).apply()
            RunLog.log("资料库已预置：${r.absolutePath}")
        }
        return ok
    }

    /**
     * 目录不存在时该不该建 —— 纯函数，便于单测（E-061）。
     *
     * 规则：从没建过 ⇒ 自动预置；用户删过 ⇒ **尊重用户的删除**、不自动重建；
     * 用户显式要求（设置页「重新整理」／重新打开开关）⇒ 重建。
     * 后两条的分界线就是"谁在要求"：自动流程不许复活他删掉的东西，手动按钮是他自己按的。
     */
    internal fun shouldCreate(everCreated: Boolean, userDeleted: Boolean, forced: Boolean): Boolean =
        forced || (!everCreated && !userDeleted)

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
    /** 扫描结果：条目 ＋ **是否因达到 [MAX_SCAN] 而截断**（截断必须告知，不能静默）。 */
    private class ScanResult(val items: List<Item>, val truncated: Boolean)

    private fun scanRaw(ctx: Context): List<Item> = scanRawFull(ctx).items

    private fun scanRawFull(ctx: Context): ScanResult {
        val base = rawDir(ctx)
        if (!base.isDirectory) return ScanResult(emptyList(), false)
        val out = ArrayList<Item>()
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        var truncated = false
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
                        if (out.size >= MAX_SCAN) {
                            // 收到上限就停 ⇒ 后面可能还有文件（此时无法确知，故用"可能"措辞）
                            truncated = true
                            break
                        }
                    }
                }
            }
        }
        out.sortBy { it.rel }
        return ScanResult(out, truncated)
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** 供 P2 摘要流程使用：列出 `原始/` 里的文件（相对路径 + 大小 + 修改时间）。 */
    fun listRaw(ctx: Context): List<Pair<String, Long>> =
        scanRaw(ctx).map { it.rel to it.size }

    /** 重建 `整理/00-目录.md`。内容未变则跳过。 */
    fun rebuildIndex(ctx: Context) {
        if (!rawDir(ctx).isDirectory) return
        val scan = scanRawFull(ctx)
        val items = scan.items

        // 指纹 = 工作区路径 ＋ 每个文件的相对路径/大小/修改时间
        val sb = StringBuilder(Workspace.hostDir(ctx).absolutePath)
        for (it in items) sb.append('|').append(it.rel).append(':').append(it.size).append(':').append(it.mtime)
        val sig = sb.toString().hashCode().toString()

        val prefs = Settings.prefs(ctx)
        // 指纹没变**且清单文件还在**才跳过：资料库被整个删掉再重建时，指纹可能恰好没变
        // （比如 `原始/` 本来就是空的），只比指纹会让 `整理/00-目录.md` 永远不再生成（E-061）。
        val idx = indexFile(ctx)
        if (prefs.getString(KEY_SIG, null) == sig && idx.isFile) return

        val text = indexText(items, scan.truncated)
        // 重渲染是"整份覆盖"，而模板里没有 `## 文件摘要` 一节 ⇒ 不搬回来的话，
        // 只要 `原始/` 有增删改（或清单被删后重建），模型跑出来的摘要就被静默抹掉。
        // 搬之前先读旧清单；搬的时候只保留原件还在的行（见 [KnowledgeBaseSummarizer.carryOverSummary]）。
        val old = runCatching { idx.readText(Charsets.UTF_8) }.getOrNull()
        val carried = KnowledgeBaseSummarizer.carryOverSummary(old, text)
        if (atomicWriteChecked(idx, carried)) {
            prefs.edit().putString(KEY_SIG, sig).apply()
        }
    }

    // ── P2：摘要回写 ────────────────────────────────────────────────────────

    /**
     * 把摘要写进 `整理/00-目录.md` 的 `## 文件摘要` 一节（P2）。
     *
     * ⚠️ **只写 `整理/`** —— 绝不碰 `原始/`（铁律一）。这是 P2 唯一的落盘点。
     *
     * 三件必须同时成立的事：
     * 1. 清单文件**已存在**（摘要只是**追加**一节，不能凭空造一份清单出来 —— 否则
     *    "清单还没生成"这个状态会被摘要悄悄盖过去）
     * 2. 合并后的正文**非空**（[KnowledgeBaseSummarizer.renderInto] 返回 null 就什么都不写）
     * 3. 写盘走 [atomicWriteChecked]（留 `.bak`、空内容拒绝）
     *
     * ⚠️ 写入后**故意不更新** [KEY_SIG]：摘要不是"原始文件变了"，更新指纹会让下一次
     * 真该重算清单的改动被跳过。代价只是"有摘要时清单会多渲染一次"，无害。
     *
     * @return true = 确实写入了
     */
    fun writeSummaries(ctx: Context, summaries: Map<String, String>): Boolean {
        val idx = indexFile(ctx)
        if (!idx.isFile) return false
        val old = runCatching { idx.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val merged = KnowledgeBaseSummarizer.renderInto(old, summaries) ?: return false
        return atomicWriteChecked(idx, merged)
    }

    /**
     * 标记"摘要任务正在跑"（设置页状态显示为「整理中」）。
     *
     * 与 [KEY_BUSY] 配套：这是**进程内的即时状态**，App 被杀掉后不会残留 ——
     * 用一个启动时的时间戳来判断"这个标记是不是上一轮回来的僵尸"（超过
     * [BUSY_STALE_MS] 就当作已经不在跑，避免状态永远卡在「整理中」）。
     */
    fun markBusy(ctx: Context) {
        Settings.prefs(ctx).edit()
            .putBoolean(KEY_BUSY, true)
            .putLong(KEY_BUSY_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearBusy(ctx: Context) {
        Settings.prefs(ctx).edit().putBoolean(KEY_BUSY, false).apply()
    }

    /** 是否正在整理（含"僵尸标记"自愈：超过 [BUSY_STALE_MS] 视为没在跑）。 */
    fun isBusy(ctx: Context): Boolean {
        val p = Settings.prefs(ctx)
        if (!p.getBoolean(KEY_BUSY, false)) return false
        val at = p.getLong(KEY_BUSY_AT, 0L)
        if (at > 0 && System.currentTimeMillis() - at > BUSY_STALE_MS) {
            clearBusy(ctx)
            return false
        }
        return true
    }

    private fun indexText(items: List<Item>, truncated: Boolean = false): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        sb.append("# 资料库目录\n\n")
        sb.append("> 由证道自动生成 · 更新于 ").append(ts).append("  \n")
        sb.append("> 原始文件在 `原始/` 里（**只读**）。本文件只是索引。\n\n")
        if (items.isEmpty()) {
            sb.append("_（还没有文件。把资料放进 `原始/`，就会出现在这里。）_\n")
        } else {
            sb.append("共 **").append(items.size).append("** 个文件：\n\n")
            if (truncated) {
                // 不静默：清单是给 agent 和用户看的，必须自己说出"这不全"
                sb.append("> ⚠️ **已达单次扫描上限 ").append(MAX_SCAN)
                    .append(" 个 —— 这里只列出了前 ").append(MAX_SCAN)
                    .append(" 个；`原始/` 里可能还有文件**未**列出来。**\n\n")
            }
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

        1. 把你的文件放进 `原始/`（**放进去不限格式**：txt、md、word、pdf……）
        2. 打开终端，直接问 AI —— 它会自己去读
        3. 就这样，不用做别的

        ## 三个文件夹，各管一件事

        | 文件夹 | 是什么 | 你能干什么 |
        |---|---|---|
        | `原始/` | 你放原件的地方 | 随便放、随便删。**AI 不会动这里面的东西** |
        | `整理/` | 证道生成的目录（清单）：资料库里有什么 | 看看就好，别手动改 |
        | `改版/` | 你让 AI 改文件时，改好的版本 | 满意就自己拿去替换原件 |

        ## 你的东西安全吗

        - 文件**全部存在你自己的手机上**；**证道本身不会把它们发出去**（终端里的 AI 自己联网时另说）
        - AI **不会修改、不会删除** `原始/` 里的任何东西
        - 就算你让它改 —— 它也只会把改好的放进 `改版/`，原件原地不动

        ## 想让 AI 更快找到

        - 文件放进 `原始/` 就行，它自己会看
        - `txt`、`md` 最稳；**Word 的 `.docx`** 也能读（AI 可能需要自己先解一下）
        - 如果是 `pdf` 或很老的 `.doc`，AI 可能要先转一下格式，第一次会慢一点

        $MARK
    """.trimIndent()

    /** `资料库/AGENTS.md` —— 给 **agent** 看的说明书（详细版）。 */
    private fun libraryAgentsText(): String = """
        # AGENTS.md —— 用户资料库

        $MARK
        用户把私人资料放在了 `原始/` 里。**用户不会整理它，而且没有备份** —— 所以下面的规则是硬的。

        ## 目录

        - `原始/` —— 用户的原始文件。**只读区**
        - `整理/` —— 由证道 App 生成的目录（清单）。**你不要写它**
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
        - 格式提示：⚠️ **App 不再预先抽取正文**（摘要功能已于 2026-10-10 移除，E-080）。
          需要 `.docx` 全文时**自己解压取正文**（`.docx` 本质是个 zip）；
          真正的旧版 `.doc` 与 `.pdf` App **不解析** —— 读不了时用工具转成文本，**转出的副本放 `改版/`**。
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
