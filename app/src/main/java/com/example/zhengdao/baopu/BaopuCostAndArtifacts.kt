// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第三十八批（成本进跑马灯 ＋ 产物在 App 内点开看）的**判据本体**。
//
// 为什么单开一个文件：这批的判据（A1–A5／B1–B5／B7–B8）全部要能在 JVM 里直测——
// 引擎与控制器都要 Android 上下文（`RunEngine` 的构造要 `BaopuDb`、`Context`），
// 在单测里造不出来。所以把「怎么算累计、怎么拼每步行、什么够格进产物、
// 复制怎么自证」都写在这里（纯函数＋只吃 `java.io.File`），壳层与引擎只是调用方。
//
// 三条纪律在本文件里有落点（派工单 §7 硬要求）：
// 1. **查看截断 ≠ 复制截断**：[artifactViewOf] 按 200 KB／3000 行截断，
//    [artifactCopyOf] 一律从盘上重读全文；
// 2. **复制后自证**：[artifactCopyOf] 比「文本放回 UTF-8 字节」与「盘上长度」，
//    不等就 `complete = false`（调用方必须报「复制不完整」，不许静默）；
// 3. **原文逐字节口径**：不转义、不改换行、不加 BOM、不 trim、不补末尾换行
//    （解码只用 `String(bytes, UTF_8)`，没有任何 replace）。
package com.example.zhengdao.baopu

import java.io.File

// ── A 段 · 数值格式（A5） ───────────────────────────────────────────────────

/** 到多少就换 `k`（含）。 */
const val TOKEN_K_THRESHOLD = 1000L

/**
 * token 数成文（**纯函数**，A5）。
 *
 * 口径（派工单 §1.3 写死）：`< 1000` 原样；`≥ 1000` 用 `k` 保留一位小数。
 * 用例钉死：`999`→`999`、`1000`→`1.0k`、`12345`→`12.3k`。
 *
 * 全整数运算（四舍五入到百位 = `(n+50)/100` 再拆个位），**不用 `String.format`、
 * 不碰 `Locale`**——`%.1f` 在德语/法语区域下会把小数点换成逗号，
 * 那是「同一个数在中英界面显示成两个样」的老坑（全仓有同类门禁）。
 */
fun formatTokenCount(n: Long): String {
    if (n < TOKEN_K_THRESHOLD) return n.toString()
    val tenths = (n + 50) / 100
    return "${tenths / 10}.${tenths % 10}k"
}

/**
 * 一步的耗时成文（**纯函数**）。
 *
 * 负数 = 不知道（续跑的第一步没有「开步时刻」可比）⇒ 返回 null，调用方**不显示**，
 * 不写「0.0s」假装知道（与 `roundSummary` 里「没有用时数据就不显示用时」同口径）。
 * 分档：`< 60s` 一位小数秒；整分钟收成 `2m`；不足 0.1s 说 `<0.1s`（不说 `0.0s`）。
 */
fun formatStepElapsed(ms: Long): String? {
    if (ms < 0) return null
    if (ms < 60_000) {
        val tenths = (ms + 50) / 100
        return if (tenths == 0L) "<0.1s" else "${tenths / 10}.${tenths % 10}s"
    }
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (sec == 0L) "${min}m" else "${min}m${sec}s"
}

// ── A 段 · 本轮用量（A1/A2/A3/A4） ─────────────────────────────────────────

/**
 * 一步的用量读数（`step` 用**引擎那一侧的步号**，不是「第几个到达的回调」）。
 *
 * 为什么用引擎步号：usage 只有「服务端真给了读数」的那几步才有（`persistUsage`
 * 里五列全空就 return）。若按到达顺序编号，中间缺一步就会把第 3 步标成第 2 步
 * ——那是编数。三列都**可空**：null = 没测到（不是 0），沿用 `LlmUsage` 的口径。
 */
data class BaopuUsageRow(
    val step: Int,
    val tokensIn: Long?,
    val tokensOut: Long?,
    val cacheHit: Long?,
    val elapsedMs: Long,
)

/**
 * 本轮累计 ＋ 每步列表（[BaopuChatController] 只持有它的一份 `mutableStateOf`）。
 *
 * 三个 total 是**累计**（A1：各步之和），不是「最后一步」；`rows` 是明细（A2）。
 * 新开一轮 ⇒ 换回 [emptyRoundUsage]（A4：不跨轮累计）。
 */
data class BaopuRoundUsage(
    val rows: List<BaopuUsageRow> = emptyList(),
    val totalIn: Long? = null,
    val totalOut: Long? = null,
    val totalCacheHit: Long? = null,
)

/** 一轮开局的空账（A4 的「清零」就是回到它）。 */
fun emptyRoundUsage(): BaopuRoundUsage = BaopuRoundUsage()

/** 有值才加：一边 null 就取另一边（两边都 null 还是 null ⇒ 不显示、不写 0）。 */
private fun addOrNull(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> a + b
}

/**
 * 收到一步的读数 ⇒ 追加一行并重算三个累计（**纯函数**，A1 的落点）。
 *
 * 三个 total 都走 [addOrNull]（**各步之和**）。谁把它改成「取 `row` 自己」
 * （只留最后一步），A1 立刻红——这正是 §4 负例 1 的靶心。
 */
fun accumulateUsage(prev: BaopuRoundUsage, row: BaopuUsageRow): BaopuRoundUsage =
    BaopuRoundUsage(
        rows = prev.rows + row,
        totalIn = addOrNull(prev.totalIn, row.tokensIn),
        totalOut = addOrNull(prev.totalOut, row.tokensOut),
        totalCacheHit = addOrNull(prev.totalCacheHit, row.cacheHit),
    )

/**
 * 「本轮用量」这条要不要进跑马灯（**纯函数**，A3）。
 *
 * 口径：**没有读数就不加这一条**——用户看到的是「没有这行」，不是「入 0」。
 * 判据取「至少有一步带着一个非空的入/出/命中数」：
 * - 整轮一步 usage 都没 ⇒ `rows` 空 ⇒ 不出（引擎侧本来就只在有读数时才回调：
 *   `persistUsage` 里 `updateRunLogUsage` 返回 null 就 return，这里是第二道兜底）；
 * - 只带了 reasoning/cacheMiss 这类我们不显示的列 ⇒ 也不算「有读数」⇒ 不出
 *   （出了就是一行光「第 3 步」，用户只会问「我的成本呢」）。
 */
fun shouldShowUsageNotice(usage: BaopuRoundUsage): Boolean =
    usage.rows.any { it.tokensIn != null || it.tokensOut != null || it.cacheHit != null }

/**
 * 「本轮用量」的文案素材（标签由调用方从 strings.xml 取好传进来）。
 *
 * 为什么不让纯函数自己读资源：读了就得上 Android 上下文，JVM 直测全废
 * （A1–A3 是判据，必须能在 `./gradlew test` 里跑）。`stepLabel` 收步号返「第 3 步」，
 * 中文侧由 `stringResource(R.string.baopu_usage_step, step)` 供给。
 */
data class UsageCopy(
    val stepLabel: (Int) -> String,
    val inLabel: String,
    val outLabel: String,
    val cacheHitLabel: String,
    val sep: String = " · ",
)

private fun appendItem(sb: StringBuilder, copy: UsageCopy, label: String, value: Long?) {
    if (value == null) return
    if (sb.isNotEmpty()) sb.append(copy.sep)
    sb.append(label).append(' ').append(formatTokenCount(value))
}

/** 累计一行（跑马灯展开后的第一行；没读到的那一项整段不写）。 */
fun usageTotalLine(usage: BaopuRoundUsage, copy: UsageCopy): String {
    val sb = StringBuilder()
    appendItem(sb, copy, copy.inLabel, usage.totalIn)
    appendItem(sb, copy, copy.outLabel, usage.totalOut)
    appendItem(sb, copy, copy.cacheHitLabel, usage.totalCacheHit)
    return sb.toString()
}

/**
 * 每步明细（**纯函数**，A2 的落点）：一行一步，顺序 = 收到读数的顺序，
 * 步号 = 引擎给的步号（所以「从 1 连续」由引擎每步恰好回调一次保证）。
 * 某步只有出没有入 ⇒ 那一列不写（不补 0）。
 */
fun usageDetailLines(usage: BaopuRoundUsage, copy: UsageCopy): List<String> =
    usage.rows.map { row ->
        val tail = StringBuilder()
        appendItem(tail, copy, copy.inLabel, row.tokensIn)
        appendItem(tail, copy, copy.outLabel, row.tokensOut)
        formatStepElapsed(row.elapsedMs)?.let {
            if (tail.isNotEmpty()) tail.append(copy.sep)
            tail.append(it)
        }
        val head = copy.stepLabel(row.step)
        if (tail.isEmpty()) head else "$head${copy.sep}$tail"
    }

/** 展开后的整块文案：累计一行 ＋ 每步一行（`\n` 分隔；`BaopuNoticeTicker` 的 body 可多行）。 */
fun usageNoticeBody(usage: BaopuRoundUsage, copy: UsageCopy): String {
    val head = usageTotalLine(usage, copy)
    val detail = usageDetailLines(usage, copy)
    return (listOf(head) + detail).filter { it.isNotBlank() }.joinToString("\n")
}

// ── B 段 · 产物筛选（B1/B2/B5） ────────────────────────────────────────────

/**
 * 文本产物扩展名白名单（派工单 §2.1 逐字八条；比较时统一转小写 ⇒ 大小写不敏感）。
 *
 * 图片、PDF、docx、zip 一律**不做**——用户原话「其他的太重了」。
 */
val TEXT_ARTIFACT_EXTENSIONS: Set<String> =
    setOf("json", "txt", "md", "csv", "yml", "yaml", "log", "html")

/** 收集时的单文件大小上限（>512 KB 不收；B2 的「大小上限」就这一处判据）。 */
const val ARTIFACT_MAX_FILE_BYTES: Long = 512L * 1024L

/** 判二进制前读多少字节（8 KB；里面有 `\u0000` 就当二进制不收）。 */
const val ARTIFACT_SNIFF_BYTES: Int = 8 * 1024

/** 产物行最多摆几片（超了只列前 8，剩下的报「还有 N 个」；B5）。 */
const val ARTIFACT_CHIP_MAX: Int = 8

/** 查看器的字节上限（200 KB；§7 纪律 1：这只管**看**，不管复制）。 */
const val ARTIFACT_VIEW_MAX_BYTES: Int = 200 * 1024

/** 查看器的行数上限（3000 行；同上，只管看）。 */
const val ARTIFACT_VIEW_MAX_LINES: Int = 3000

/** 数「共 M 行」时最多扫多少字节（4 MB）；超出就报 -1 = 没数完，不编总数。 */
const val ARTIFACT_SCAN_MAX_BYTES: Long = 4L * 1024L * 1024L

/** 「复制全文」的硬上限（16 MB）。超了 `complete=false` ⇒ 界面明说没复制，不静默给半截。 */
const val ARTIFACT_COPY_MAX_BYTES: Long = 16L * 1024L * 1024L

private const val NEWLINE_BYTE: Byte = '\n'.code.toByte()

/** 取扩展名（小写、无点）：先剥目录再取最后一个 `.` 之后；点开头/结尾都不算。 */
fun artifactExtension(relPath: String): String {
    val name = artifactFileName(relPath)
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase()
}

/** 文件名的最后一段（产物片上显示的那个；`/` 与 `\` 都当分隔符）。 */
fun artifactFileName(relPath: String): String =
    relPath.substringAfterLast('/').substringAfterLast('\\')

/** 扩展名在白名单里吗（**纯函数**，不碰盘）。 */
fun isTextArtifactPath(relPath: String): Boolean = artifactExtension(relPath) in TEXT_ARTIFACT_EXTENSIONS

/** 前 8 KB 里有 NUL 字节就当二进制（**纯函数**；B2 的第二条判据）。 */
fun hasNulByte(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }

/** 读流头最多 n 字节（读不满就给读到的那些）。 */
private fun readHead(f: File, n: Int): ByteArray? = runCatching {
    f.inputStream().use { input ->
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r <= 0) break
            got += r
        }
        if (got == n) buf else buf.copyOfRange(0, maxOf(got, 0))
    }
}.getOrNull()

/**
 * 盘上这份够不够格当「文本产物」（**只在引擎线程上调**，别搬到主线程——要开两次流）。
 *
 * 四道：在盘上且是文件 → 非 0 字节 → ≤512 KB → 前 8 KB 没有 NUL。
 * 0 字节算「不够格」：空文件点开是空白一片，比不列更让人困惑（与图片格子的
 * 「0 字节＝没内容」同口径）。
 */
fun isTextArtifactFile(f: File): Boolean {
    if (!f.isFile) return false
    val size = f.length()
    if (size == 0L || size > ARTIFACT_MAX_FILE_BYTES) return false
    val head = readHead(f, ARTIFACT_SNIFF_BYTES) ?: return false
    return !hasNulByte(head)
}

/**
 * 从「本轮真写成功的路径」里挑出品格够的文本产物（B1／B2／B5 的落点）。
 *
 * 保序去重（同一文件写两次只列一次，顺序按第一次）；**不在这里砍到 8 个**——
 * 砍是展示层的事（[artifactChipPlan]），这里砍了就数不出「还有 N 个」。
 */
fun selectTextArtifacts(root: File, relPaths: List<String>): List<String> {
    val kept = LinkedHashSet<String>()
    relPaths.forEach { rel ->
        if (isTextArtifactPath(rel) && isTextArtifactFile(File(root, rel))) kept += rel
    }
    return kept.toList()
}

/** 产物行的摆法：最多 [max] 片 ＋「还有 N 个」的 N（B5）。 */
data class ArtifactChipPlan(val shown: List<String>, val more: Int)

fun artifactChipPlan(paths: List<String>, max: Int = ARTIFACT_CHIP_MAX): ArtifactChipPlan =
    ArtifactChipPlan(
        shown = paths.take(max.coerceAtLeast(0)),
        more = (paths.size - max.coerceAtLeast(0)).coerceAtLeast(0),
    )

// ── B 段 · 只读查看（B3/B4） ───────────────────────────────────────────────

/**
 * 查看器要显示的那一份（**截断只发生在这里**）。
 *
 * - [text]：盘上原文的前缀（UTF-8 解码；没截断时与整文件逐字一致，B3）；
 * - [shownLines] / [totalLines]：显示了多少行、盘上共多少行（[totalLines] = -1
 *   表示扫到 [ARTIFACT_SCAN_MAX_BYTES] 上限没数完 ⇒ 界面只说「只显示前 N 行」，不编总数）；
 * - [truncated]：是不是被 200 KB／3000 行卡住了（B4）；
 * - [fileBytes]：盘上总长度（顶部那行「N 字节」用它，不是显示那份的长度）。
 */
data class ArtifactView(
    val relPath: String,
    val text: String,
    val shownLines: Int,
    val totalLines: Int,
    val truncated: Boolean,
    val fileBytes: Long,
)

/** 行数口径（B3/B7 与「已复制 N 行」共用）：空文件 0 行；末尾换行不额外多算一行。 */
fun countTextLines(text: String): Int = when {
    text.isEmpty() -> 0
    text.endsWith("\n") -> text.count { it == '\n' }
    else -> text.count { it == '\n' } + 1
}

/**
 * 读「看」的那一份：一次流式扫过整文件，**只把前 200 KB／前 3000 行留在内存里**，
 * 剩下的只数换行不存字节（B4 那句「不许把 5 MB 的 txt 一次读进 Compose」就在这）。
 *
 * 只在 IO 线程上调（`produceState` + `Dispatchers.IO`，与本仓图片解码同房型）。
 */
fun artifactViewOf(root: File, relPath: String): ArtifactView? {
    val f = File(root, relPath)
    if (!f.isFile) return null
    val cap = ByteArray(ARTIFACT_VIEW_MAX_BYTES)
    var kept = 0
    var keptNewlines = 0
    var totalNewlines = 0
    var scanned = 0L
    var stoppedCollecting = false
    var truncated = false
    var scanCapped = false
    var lastByte: Int = -1
    val ok = runCatching {
        f.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            outer@ while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                scanned += n
                var i = 0
                while (i < n) {
                    val b = chunk[i]
                    if (b == NEWLINE_BYTE) totalNewlines++
                    lastByte = b.toInt() and 0xFF
                    if (!stoppedCollecting) {
                        if (kept < cap.size) {
                            cap[kept] = b
                            kept++
                            if (b == NEWLINE_BYTE) {
                                keptNewlines++
                                if (keptNewlines >= ARTIFACT_VIEW_MAX_LINES) {
                                    stoppedCollecting = true
                                    truncated = true
                                }
                            }
                        } else {
                            stoppedCollecting = true
                            truncated = true
                        }
                    }
                    i++
                }
                if (scanned > ARTIFACT_SCAN_MAX_BYTES) {
                    scanCapped = true
                    break@outer
                }
            }
            Unit
        }
    }.isSuccess
    if (!ok) return null
    val keptTail = if (kept > 0) cap[kept - 1].toInt() and 0xFF else -1
    return ArtifactView(
        relPath = relPath,
        text = String(cap.copyOfRange(0, kept), Charsets.UTF_8),
        shownLines = if (kept == 0) 0 else keptNewlines + (if (keptTail == '\n'.code) 0 else 1),
        totalLines = when {
            scanCapped -> -1
            lastByte == '\n'.code -> totalNewlines
            else -> totalNewlines + 1
        },
        truncated = truncated,
        fileBytes = f.length(),
    )
}

// ── B 段 · 复制全文（§7 硬要求；B7/B8） ────────────────────────────────────

/**
 * 「复制全文」的结果（§7 三条纪律都靠这四个数交给人看）。
 *
 * [chars] 是**字符数**（剪贴板里放的确实是这么多个 char），[fileBytes] 是盘上长度；
 * 两者对不上是正常的（中文一字三字节），所以 [complete] 判的是「文本放回 UTF-8
 * 字节 == 盘上那份字节」——这才是「一个字符都不许少」的真判据。
 */
data class ArtifactCopy(
    val text: String,
    val chars: Int,
    val lines: Int,
    val fileBytes: Long,
    val complete: Boolean,
)

/**
 * 从盘上**重新完整读一遍**（§7 纪律 1：查看截断 ≠ 复制截断）。
 *
 * - 不接「显示那一份」的字符串进来——复制的源头永远是 `File`；
 * - 解码只 `String(bytes, UTF_8)`：不转义、不改换行（CRLF 原样带过去）、
 *   不加 BOM、不 trim、不补末尾换行（§7 纪律 3；文件本来带 BOM 就照原样保留，
 *   那属于「盘上就有的字节」）；
 * - 读完自证（§7 纪律 2）：`bytes.size == 盘上长度` **且**
 *   `text.toByteArray(UTF_8)` 与盘上字节逐字节相同 ⇒ `complete = true`；
 *   任一条不满足（读炸了／超 16 MB／编码不是合法 UTF-8）⇒ `complete = false`，
 *   调用方必须回「复制不完整，请重试」，不许静默把半截当成功。
 *
 * 「字符数 vs 字节数」的口径：中文一字三个字节，所以 [chars] 与 [fileBytes] 天生不等；
 * 自证比的是**字节**（放进剪贴板的那份文本回到 UTF-8 后与盘上逐字节相同），
 * 这才等价于「一个字符都不许少」。
 */
fun artifactCopyOf(root: File, relPath: String): ArtifactCopy? {
    val f = File(root, relPath)
    if (!f.isFile) return null
    val size = f.length()
    if (size > ARTIFACT_COPY_MAX_BYTES) {
        return ArtifactCopy(text = "", chars = 0, lines = 0, fileBytes = size, complete = false)
    }
    val bytes = runCatching { f.readBytes() }.getOrNull()
        ?: return ArtifactCopy(text = "", chars = 0, lines = 0, fileBytes = size, complete = false)
    val text = String(bytes, Charsets.UTF_8)
    val roundTrip = text.toByteArray(Charsets.UTF_8)
    return ArtifactCopy(
        text = text,
        chars = text.length,
        lines = countTextLines(text),
        fileBytes = size,
        complete = bytes.size.toLong() == size && roundTrip.contentEquals(bytes),
    )
}

// ── §8 · 顶栏产物条（B9／B10／B11 的形态判定） ─────────────────────────────

/**
 * 顶栏那一条该显示什么（判据 B9）。
 *
 * 把判定放进纯函数是有原因的：这条口径（有产物出产物条、没产物退回最近一条回复）
 * 是最容易在 UI 里写歪又最难在 UI 里测到的一条，JVM 直接测它就够了，Compose 照它摆。
 */
sealed class TopBarSlot {

    /**
     * 产物条。
     * - [total]：「产物 · N」的 N = 本轮收集到的文本产物个数（永远 > 0，空列表走 [ReplyPreview]）；
     * - [firstName]：折叠行里那个短名（路径最后一段，超宽由 UI 打省略号）；
     * - [firstRelPath]：它对应的相对路径 —— B11：折叠态那个**小号**复制按钮复制的就是这一个文件的全文；
     * - [expanded]：展开态由调用方给（本函数不持状态，纯的）。
     */
    data class Artifacts(
        val total: Int,
        val firstName: String,
        val firstRelPath: String,
        val expanded: Boolean,
    ) : TopBarSlot()

    /**
     * 没产物 ⇒ 退化为现状：仍显示最近一条回复的 `CopyBox`（§8.2「别让这一行空着」）。
     * [text] 为空串时调用方连框也不出（沿用改造前那句 `isNotBlank()` 判定）。
     */
    data class ReplyPreview(val text: String) : TopBarSlot()
}

fun topBarSlot(artifacts: List<String>, lastAssistant: String, expanded: Boolean): TopBarSlot =
    if (artifacts.isEmpty()) {
        TopBarSlot.ReplyPreview(lastAssistant)
    } else {
        TopBarSlot.Artifacts(
            total = artifacts.size,
            firstName = artifactFileName(artifacts.first()),
            firstRelPath = artifacts.first(),
            expanded = expanded,
        )
    }

/**
 * B10：点折叠行 ⇒ 展开；再点一下 ⇒ 收起。状态机只有这一条边，
 * 展开态本身归 UI（这里只回答「点一下之后该是什么」）。
 */
fun toggleTopBarExpanded(expanded: Boolean): Boolean = !expanded

/**
 * B11：折叠态那个小号复制按钮**只复制第一个产物**，展开后才是每行各复制各的。
 * null ⇒ 没东西可复制 ⇒ 按钮禁用（不给用户点出一个假失败，同 `CopyBox` 的纪律）。
 */
fun firstArtifactForCopy(artifacts: List<String>): String? = artifacts.firstOrNull()

/** 展开态的一行：相对路径 ＋ 显示名 ＋ 盘上字节数（读不到给 0，不编数）。 */
data class ArtifactEntry(val relPath: String, val name: String, val fileBytes: Long)

/**
 * 展开列表每行后面那个大小：只做 `File.length()`（一次 stat，最多 [ARTIFACT_CHIP_MAX] 个），
 * **不读文件内容**——内容只在查看器与复制那两处按 §7 口径读。
 * 和别的读盘函数一样，只在 IO 线程上调。
 */
fun artifactEntries(root: File, relPaths: List<String>): List<ArtifactEntry> =
    relPaths.map { rel ->
        val f = File(root, rel)
        ArtifactEntry(
            relPath = rel,
            name = artifactFileName(rel),
            fileBytes = if (f.isFile) f.length() else 0L,
        )
    }
