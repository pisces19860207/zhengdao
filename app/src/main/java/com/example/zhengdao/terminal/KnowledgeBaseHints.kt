// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android 官方文档（java.io.File 语义、SharedPreferences）、Kotlin/JDK 标准库。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.Settings
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FIX-F：**提问时自动附上「资料库线索」**（2026-10-10 用户拍板开工）。
 *
 * 解决什么：用户最烦的是「AI 一遍遍翻文件、翻半天才回我」与「还得提醒它东西放哪」。
 * 光靠 `AGENTS.md` 让它自己去搜，依赖 agent 自觉（见报告 R15-1）；本模块把「找」这一步
 * 搬进 App —— **用户按下回车的那一刻，App 先在本地把相关资料找出来，把「文件名＋行号」
 * 附在这句话后面一起送进终端**，agent 想不看都看到了。
 *
 * ## ⚠️ 它不做什么（边界，别越界）
 * - **不是向量检索**：只做**子串匹配**（中文没分词）。找不到就当没找到，**不猜**。
 *   「换个说法才找得到」是另一档（R16：那才轮到向量／全文检索引擎）。
 * - **不给正文**：只给「哪个文件、第几行」。{@code 给正文＝替 agent 决定读什么，还费 token}。
 * - **不联网**：全程本机磁盘（这是本项目的隐私卖点）。
 * - **默认关闭**（见 [KEY_ENABLED]），需要在设置页「资料库」里手动打开。
 *
 * ## 注入的形状（已给用户看过样例）
 * **一行**、带醒目分隔、明确写「非本人输入」：
 * ```
 * 【证道自动附上·资料库线索（非本人输入，可在设置里关）】检索词=母猪；命中 3 处：
 * 男性待整理.txt:34 / 男性待整理.txt:40 / 男性待整理.txt:43；以上只是线索，需要正文时请自行读该文件。
 * ```
 * - ⚠️ **必须是一行**：终端里 `\n` 就是回车 —— 多行会把「一句话」提交成「好几条提问」。
 * - ⚠️ **命中 0 处就整条不注入**（[formatBlock] 返回 null）：不是每次提问都往上贴东西，
 *   所以「你好」「继续」这类闲聊不会产生任何附带文字。
 *
 * ## 为什么是「追加在用户这句话后面」，而不是「插在前面」
 * 终端是**逐字符**送进 AI 的：用户打字的同时字符已经到了 pty，AI 那侧的输入行里
 * 已经有他打的字。我们唯一能插手的时机是**回车按下的那一刻**（此时回车还没放行），
 * 而那时只能往这一行**后面**追加。要做成「块在前」，就得二选一：
 * ① 扣住用户输入不发（那他就看不到自己打的字）；② 发退格把已送出的字符抹掉
 * （raw 模式 TUI 里退格行为不可控，抹错就是把话改烂）。**两条都不干。**
 *
 * ## 「恢复会话会不会重复注入」
 * 注入只挂在**用户自己按回车**这一条路径上（[onUserCodePoint]）。App 启动会话时写的
 * 启动命令／tmux 预置走的是别的通道（[SessionManager.write]、ProotLauncher），
 * 不经过这里 ⇒ **恢复会话不会重复注入**（DSH 给的范围约束由此天然满足）。
 */
object KnowledgeBaseHints {

    // ── 开关（默认关：新东西先别自作主张贴到用户屏幕上）────────────────────────
    private const val KEY_ENABLED = "kb_hints_v1"

    // ── 缓冲与上限（全是"别让这一段字自己把上下文塞爆"）────────────────────────
    /** 行缓冲上限：超过就丢弃（400 字已远超一次提问） */
    private const val MAX_LINE_CHARS = 400

    /** 最多试几个候选词（候选越多＝扫得越狠，收益递减） */
    private const val MAX_CANDIDATES = 6

    /** 块里最多展示几个"检索词" */
    private const val MAX_KEYWORDS = 2

    /** 一次最多给几处命中 */
    private const val MAX_HITS = 6

    /** 同一个文件最多列几处（留出名额让"还出现在别的哪个文件"也能露头） */
    private const val MAX_HITS_PER_FILE = 3

    /** 命中的那一行截多长（只是让 agent 认得出是哪段，不是正文） */
    private const val MAX_HIT_LINE_CHARS = 60

    /** 块里文件名截多长 */
    private const val MAX_REL_CHARS = 40

    /** 整块长度上限 */
    private const val MAX_BLOCK_CHARS = 400

    /** 扫描上限：文件数 / 单文件大小 / 总字符数 / 总耗时 —— 任何一条到顶就停 */
    private const val MAX_FILES = 800
    private const val MAX_FILE_CHARS = 2L * 1024 * 1024
    private const val MAX_TOTAL_CHARS = 16L * 1024 * 1024
    private const val MAX_TOTAL_MS = 1500L

    /** 明显不是文本的扩展名，直接跳过（少读少耗时；读了也是乱码） */
    private val SKIP_EXT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "zip", "rar", "7z", "gz", "tar", "apk", "jar",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "ico",
        "mp3", "mp4", "mov", "avi", "mkv", "wav", "flac",
        "db", "sqlite", "so", "bin", "ttf", "otf", "woff", "woff2",
    )

    /**
     * 块首那一段"这不是你自己打的字"的标记。
     * 措辞刻意写死：① 明说"自动附上"；② 明说"非本人输入"（不冒充用户）；③ 告诉他能关。
     */
    private const val MARK =
        "【证道自动附上·资料库线索（非本人输入，可在设置里关）】"

    /** 问句里这些词单独拿出来搜没有意义（会把整句的召回率拉低），先剔掉 */
    private val FILLERS = listOf(
        "帮我", "帮忙", "麻烦", "请", "一下", "一点", "看看", "找找", "查查", "搜搜", "翻翻",
        "那个", "这个", "那段", "这段", "那本", "这本", "那篇", "这篇", "里面", "里边", "里",
        "关于", "内容", "文件", "资料", "素材", "东西", "地方", "位置",
        "是什么", "什么", "为什么", "怎么", "怎样", "如何", "有没有", "在哪", "哪里", "多少",
        "给我", "告诉我", "我想", "想要", "想看", "需要", "应该", "可以", "就是", "还有", "以及",
        "我", "你", "他", "她", "它", "的", "地", "得", "了", "吗", "呢", "吧", "啊", "呀", "哦",
        "和", "跟", "与", "把", "被", "在", "有", "是", "都", "就",
    ).sortedByDescending { it.length }

    private val ASCII_WORD = Regex("[A-Za-z0-9_]{3,}")
    private val CJK_RUN = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]+")

    // ── 运行时状态（行缓冲 / 单飞标记 / 后台线程）──────────────────────────────
    private val line = StringBuilder()
    private val inflight = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zd-kb-hints").apply { isDaemon = true }
    }

    fun isEnabled(ctx: Context): Boolean =
        Settings.prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        Settings.prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) reset()
    }

    /** 会话结束／界面销毁时清缓冲，避免跨会话串味。 */
    fun reset() {
        line.setLength(0)
    }

    /**
     * **主入口**：喂进一个"用户输入"的码点。
     *
     * @param codePoint 从 [com.termux.view.TerminalViewClient.onCodePoint] 拿到的码点
     * @param ctrlDown  同上的 ctrl 标记（软键盘回车的识别要用，见 [isEnter]）
     * @param write     写进 pty 的出口；约定：先写块、再写 `"\r"`。**允许在任意线程调用**，
     *                  实现方负责切回主线程。
     * @return true = 这个码点**被我接管了，别再写进 pty**（只可能是回车）
     */
    fun onUserCodePoint(ctx: Context, codePoint: Int, ctrlDown: Boolean, write: (String) -> Unit): Boolean {
        if (!isEnabled(ctx)) {
            if (line.isNotEmpty()) line.setLength(0)
            return false
        }
        if (isEnter(codePoint, ctrlDown)) {
            val question = line.toString().trim()
            line.setLength(0)
            // 空行放行；斜杠命令放行（`/help`、`/clear` 这类不是提问，别插嘴）
            if (question.isEmpty() || question.startsWith("/")) return false
            // 上一轮还在搜：吞掉这次回车。否则会"先裸提交一次、块再单独提交一条"
            if (inflight.get()) return true
            val cands = candidates(question)
            if (cands.isEmpty()) return false   // 没有可检索的词 ⇒ 不值得拦车
            start(ctx, cands, write)
            return true
        }
        when (codePoint) {
            8, 127 -> if (line.isNotEmpty()) line.setLength(line.length - 1)
            else -> if (codePoint >= 32 && line.length < MAX_LINE_CHARS) line.appendCodePoint(codePoint)
        }
        return false
    }

    /**
     * 回车判定。
     *
     * ⚠️ 这里有个 Termux 的绕路（读 [com.termux.view.TerminalView] 得知，别当成笔误）：
     * 软键盘的回车经 `sendTextToTerminal()` 时，`'\n'` 先被改成 `'\r'`(13)，紧接着
     * 因为 `13 <= 31` 又被当成 ctrl 走了一遍 `+96` ⇒ 到 `onCodePoint` 时是
     * **109（'m'）+ ctrlDown=true**，随后由 `inputCodePoint()` 里的 ctrl 分支还原成 13。
     * 所以"回车"在这个回调里有两种长相：13，或 (109 && ctrlDown)。
     */
    private fun isEnter(codePoint: Int, ctrlDown: Boolean): Boolean =
        codePoint == 13 || (ctrlDown && codePoint == 109)

    /** 粘贴也算"用户的话"（否则粘完再回车，缓冲是空的，就白粘了）。 */
    fun onUserText(text: String) {
        if (text.isEmpty()) return
        // 多行粘贴不是"一个提问"；超长粘贴（多半是整篇文档）也不该被当作检索词
        if (text.contains('\n') || line.length + text.length > MAX_LINE_CHARS) {
            line.setLength(0)
            return
        }
        line.append(text)
    }

    /** 退格／删除：缓冲跟着退（快捷键条的退格是直写 pty 的，得单独喂一口）。 */
    fun onUserBackspace() {
        if (line.isNotEmpty()) line.setLength(line.length - 1)
    }

    // ── 执行（后台线程）──────────────────────────────────────────────────────

    private fun start(ctx: Context, cands: List<String>, write: (String) -> Unit) {
        if (!inflight.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        worker.execute {
            var block: String? = null
            try {
                block = runCatching { build(app, cands) }
                    .onFailure { RunLog.log("资料库线索：本地检索失败（${it.message}）") }
                    .getOrNull()
            } finally {
                // 无论成败都必须把回车放行 —— 绝不能因为这里出错就吞掉用户的一次回车
                val payload = if (block.isNullOrEmpty()) "\r" else block + "\r"
                runCatching { write(payload) }
                    .onFailure { RunLog.log("资料库线索：写入终端失败（${it.message}）") }
                inflight.set(false)
            }
        }
    }

    private fun build(ctx: Context, cands: List<String>): String? {
        val deadline = System.currentTimeMillis() + MAX_TOTAL_MS
        val sources = collectSources(ctx, deadline)
        if (sources.isEmpty()) return null
        val r = scan(sources, cands, deadline)
        if (r.hits.isEmpty()) return null
        RunLog.log("资料库线索：命中 ${r.hits.size} 处（检索词=${r.keywords.joinToString("、")}）")
        return formatBlock(r.keywords, r.hits)
    }

    /**
     * 把 `原始/` 读成「相对路径 → 文本」。
     * **只读**：不建、不写、不改、不删；软链和超大文件/二进制跳过。
     */
    private fun collectSources(ctx: Context, deadline: Long): List<Pair<String, String>> {
        val base = KnowledgeBase.rawDir(ctx)
        if (!base.isDirectory) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        var totalChars = 0L
        while (stack.isNotEmpty()) {
            if (out.size >= MAX_FILES || totalChars >= MAX_TOTAL_CHARS ||
                System.currentTimeMillis() > deadline
            ) break
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (out.size >= MAX_FILES || totalChars >= MAX_TOTAL_CHARS ||
                    System.currentTimeMillis() > deadline
                ) break
                when {
                    f.isDirectory -> stack.addLast(f)
                    f.isFile -> {
                        if (f.length() <= 0L || f.length() > MAX_FILE_CHARS) continue
                        if (isSymlink(f)) continue
                        if (f.extension.lowercase(Locale.ROOT) in SKIP_EXT) continue
                        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                        val rel = f.absolutePath.removePrefix(base.absolutePath)
                            .trimStart(File.separatorChar)
                        out.add(rel to text)
                        totalChars += text.length
                    }
                }
            }
        }
        out.sortBy { it.first }   // 目录遍历顺序不定 ⇒ 排序保证结果可复现
        return out
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    // ── 纯逻辑（可单测，不碰 Context／磁盘）──────────────────────────────────

    internal data class Hit(val rel: String, val line: Int, val text: String)

    internal data class SearchResult(val keywords: List<String>, val hits: List<Hit>)

    /**
     * 生成候选检索词。中文没有词边界，所以用 **n-gram**：先整词（英文）→ 再 3 字窗口
     * → 最后 2 字窗口，按出现位置从左到右。
     *
     * 例：「帮我找母猪那段素材」→ 剔掉"帮我/找/那段" ⇒ `母猪素材`
     * ⇒ 3-gram「母猪素」「猪素材」都不中，2-gram「母猪」中 ⇒ 这才找得到。
     */
    internal fun candidates(line: String, max: Int = MAX_CANDIDATES): List<String> {
        val content = stripFillers(line)
        if (content.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // ① 英文/数字整词 —— 最精确，优先
        for (m in ASCII_WORD.findAll(content)) out.add(m.value.lowercase(Locale.ROOT))
        val runs = CJK_RUN.findAll(content).map { it.value }.toList()
        // ② 逐个词段：先 3 字窗口、再 2 字窗口，然后才轮到下一段。
        // ⚠️ 顺序刻意如此（探针实测后改的）：早先的写法是"所有词段的 3 字窗口都试完、
        //    再统一试 2 字窗口"，结果一段长问句的 3 字窗口就把候选名额吃光，
        //    **2 字窗口一个都没进列表** ⇒ 长问句会整体搜不到。现在每段自己先退到 2 字窗口。
        for (r in runs) {
            for (n in intArrayOf(3, 2)) {
                if (r.length < n) continue
                for (i in 0..r.length - n) out.add(r.substring(i, i + n))
            }
            if (out.size >= max) break
        }
        return out.take(max)
    }

    /** 剔掉问句里的口水词，保留它们的**位置**（换成空格，避免把两段词粘成一个假词）。 */
    internal fun stripFillers(line: String): String {
        var s = line
        for (f in FILLERS) if (s.contains(f)) s = s.replace(f, " ")
        return s
    }

    /**
     * 单遍扫描：每行按候选词**优先级**取第一个命中的（一行只记一次，避免同一行
     * 因为两个候选词被记两遍）。到 [deadline] 或 [maxHits] 就收工。
     */
    internal fun scan(
        sources: List<Pair<String, String>>,
        candidates: List<String>,
        deadline: Long,
        maxHits: Int = MAX_HITS,
    ): SearchResult {
        if (sources.isEmpty() || candidates.isEmpty()) return SearchResult(emptyList(), emptyList())
        val counts = LinkedHashMap<String, Int>()
        for (c in candidates) counts[c] = 0
        val perKeyword = LinkedHashMap<String, MutableList<Hit>>()
        var collected = 0
        loop@ for ((rel, text) in sources) {
            val lines = text.split('\n')
            for ((idx, raw) in lines.withIndex()) {
                if (collected >= maxHits * 4 || System.currentTimeMillis() > deadline) break@loop
                for (c in candidates) {
                    if (raw.contains(c, ignoreCase = true)) {
                        counts[c] = (counts[c] ?: 0) + 1
                        perKeyword.getOrPut(c) { mutableListOf() }
                            .add(Hit(rel, idx + 1, clip(raw.trim(), MAX_HIT_LINE_CHARS)))
                        collected++
                        break
                    }
                }
            }
        }
        val keywords = pickKeywords(candidates, counts)
        if (keywords.isEmpty()) return SearchResult(emptyList(), emptyList())
        val merged = LinkedHashMap<String, Hit>()
        for (k in keywords) {
            for (h in perKeyword[k].orEmpty()) merged.putIfAbsent("${h.rel}:${h.line}", h)
        }
        // 同一个文件最多列 3 处：目的是"告诉 agent 该看哪个文件"，
        // 全挤在一个文件里的话，"这条词还出现在别的哪几本"就看不到了。
        val perFile = LinkedHashMap<String, Int>()
        val hits = merged.values
            .sortedWith(compareBy({ it.rel }, { it.line }))
            .filter { (perFile.merge(it.rel, 1, Int::plus) ?: 1) <= MAX_HITS_PER_FILE }
            .take(maxHits)
        return SearchResult(keywords, hits)
    }

    /** 谁命中多谁当检索词；同数按候选优先级（=左到右出现的先后）。 */
    internal fun pickKeywords(
        candidates: List<String>,
        counts: Map<String, Int>,
        max: Int = MAX_KEYWORDS,
    ): List<String> =
        candidates.filter { (counts[it] ?: 0) > 0 }
            .sortedByDescending { counts[it] ?: 0 }   // 稳定排序：同数保持候选优先级
            .take(max)

    /** 命中 0 处 ⇒ 返回 null（**不注入**）。 */
    internal fun formatBlock(keywords: List<String>, hits: List<Hit>): String? {
        if (hits.isEmpty() || keywords.isEmpty()) return null
        val head = MARK + "检索词=" + keywords.joinToString("、")
        val tail = "；以上只是线索，需要正文时请自行读该文件。"
        val budget = MAX_BLOCK_CHARS - head.length - tail.length - 24
        val list = StringBuilder()
        var shown = 0
        for (h in hits) {
            val piece = (if (shown > 0) " / " else "") + clip(h.rel, MAX_REL_CHARS) + ":" + h.line
            if (list.length + piece.length > budget) break
            list.append(piece)
            shown++
        }
        if (shown == 0) return null
        val more = if (shown < hits.size) " 等 ${hits.size} 处" else ""
        return head + "；命中 " + hits.size + " 处：" + list + more + tail
    }

    /** 按**字符数**截断（终端上文件名多是中文，按字节算会偏）。 */
    internal fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "…"
}
