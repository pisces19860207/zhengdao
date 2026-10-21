// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴线刀 2 追加包（章节级抽取流水线的 UI）：选书 → 切分预览
// （章数 / 前 3 章标题）→ 一次 ask_user：逐章摘要 / 只要索引 / 精读某几章。
// 落库走 ChapterIndexStore（chapter_index 表）；摘要**不跑模型**（零网络，
// 追加包明令不额外多跑一轮），是确定性抽取式。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.library.Chapter
import com.example.zhengdao.baopu.library.ChapterDigest
import com.example.zhengdao.baopu.library.ChapterIndexStore
import com.example.zhengdao.baopu.library.ChapterSplitter
import com.example.zhengdao.baopu.library.DigestRun
import com.example.zhengdao.baopu.library.ExtractAsk
import com.example.zhengdao.baopu.library.ExtractLabels
import com.example.zhengdao.baopu.library.ExtractMode
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 选书的扩展名（txt/md/markdown——与搬家段 DOC_EXTS 同口径，不引 office 解析）。 */
private val BOOK_EXTS = setOf("txt", "md", "markdown")

/** 预览/切分的单本大小上限（与 read_file 的 MAX_FILE_BYTES 同值：2 MB）。 */
private const val PREVIEW_MAX_BYTES = 2L * 1024 * 1024

/**
 * 章节抽取对话框（追加包 UI）。
 *
 * 三步：选书（私有原件目录）→ 切分预览 → 一次选择（逐章摘要 / 只要索引 /
 * 精读某几章）→ 落库结果。**对话框式**（与 BaopuBackupDialog 同房型）：
 * 这是个一次性动作，不是要常驻的页面。
 */
@Composable
internal fun BaopuExtractSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }

    // 步骤：0 选书 / 1 预览与选择 / 2 结果
    var step by remember { mutableIntStateOf(0) }
    var books by remember { mutableStateOf<List<Pair<String, File>>>(emptyList()) }
    var picked by remember { mutableStateOf<File?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var bookLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var fatalError by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var deepReadOpen by remember { mutableStateOf(false) }
    var deepReadInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var resultRun by remember { mutableStateOf<DigestRun?>(null) }

    LaunchedEffect(Unit) {
        books = withContext(Dispatchers.IO) {
            val raw = LibraryPaths.rawPrivateDir(ctx)
            LibraryPaths.scanTree(raw).files
                .filter { f ->
                    f.kind == LibraryPaths.FileKind.TEXT &&
                        f.name.substringAfterLast('.', "").lowercase() in BOOK_EXTS
                }
                .map { f -> f.relPath to File(raw, f.relPath) }
        }
    }

    /** 跑一轮抽取（切章数据已在内存；这里只做落库，IO 线程）。 */
    fun runExtract(withDigest: Boolean, selected: Set<Int>?) {
        val file = picked ?: return
        busy = true
        scope.launch {
            val run: DigestRun = withContext(Dispatchers.IO) {
                val bookName = file.name.substringBeforeLast('.')
                val r = ChapterDigest.build(
                    bookName = bookName,
                    chapters = chapters,
                    lines = bookLines,
                    now = System.currentTimeMillis(),
                    withDigest = withDigest,
                    selected = selected,
                )
                ChapterIndexStore(BaopuRuntime.db(ctx)).insertAll(r.entries)
                r
            }
            busy = false
            resultRun = run
            step = 2
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(24.dp).heightIn(max = 520.dp),
            ) {
                Text(
                    text = stringResource(R.string.baopu_extract_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.baopu_extract_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // 真机读书踩到：书单只扫私有原件，手工丢进「整理」的书不出现 ⇒ 这里把来源说清楚，
                // 不让用户以为书丢了（比改扫描范围安全：不动 LibraryPaths 的职责边界）。
                Text(
                    text = stringResource(R.string.baopu_extract_scope),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
                when (step) {
                    0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        fatalError?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = zhengdaoErrorText(), // P2-11：error 色 3.55:1 不达正文 AA 4.5:1,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (books.isEmpty()) {
                            Text(
                                text = stringResource(R.string.baopu_extract_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            books.forEach { (rel, file) ->
                                TextButton(onClick = {
                                    picked = file
                                    busy = true
                                    fatalError = null
                                    warning = null
                                    scope.launch {
                                        val loaded = withContext(Dispatchers.IO) { loadBook(file) }
                                        busy = false
                                        fatalError = loaded.fatal
                                        warning = loaded.note
                                        if (loaded.fatal == null) {
                                            chapters = loaded.chapters
                                            bookLines = loaded.lines
                                            step = 1
                                        }
                                    }
                                }) {
                                    Text(rel)
                                }
                            }
                        }
                    }

                    1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(
                                R.string.baopu_extract_preview,
                                chapters.size,
                                chapters.take(3).joinToString("；") { "${it.index + 1}. ${it.title}" },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        warning?.let { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        // 一次 ask_user（派工单 C 段房型）：三选项 + 永远留一行空白输入。
                        // 卡由纯函数 ExtractAsk.card 造（可 JVM 直测），这里只渲染；
                        // 文案从 strings.xml 注入（纯函数不碰 Context，但必须双语）。
                        val askCard = remember(chapters) {
                            ExtractAsk.card(
                                chapterCount = chapters.size,
                                firstTitles = chapters.map { it.title },
                                labels = ExtractLabels(
                                    digest = res.getString(R.string.baopu_extract_mode_digest),
                                    index = res.getString(R.string.baopu_extract_mode_index),
                                    deep = res.getString(R.string.baopu_extract_mode_deep),
                                    freePrompt = res.getString(R.string.baopu_extract_free_prompt),
                                ),
                            )
                        }
                        BaopuAskUserCard(
                            card = askCard,
                            enabled = !busy,
                            onPick = { opt ->
                                when (opt.id) {
                                    ExtractAsk.ID_DIGEST -> runExtract(withDigest = true, selected = null)
                                    ExtractAsk.ID_INDEX -> runExtract(withDigest = false, selected = null)
                                    ExtractAsk.ID_DEEP -> deepReadOpen = true
                                }
                            },
                            onFreeText = { text ->
                                val choice = ExtractAsk.parse(text)
                                if (choice == null) {
                                    warning = "没能对上选项，请直接点上面的按钮（不猜、不静默执行）"
                                } else {
                                    when (choice.mode) {
                                        ExtractMode.DIGEST -> runExtract(withDigest = true, selected = null)
                                        ExtractMode.INDEX -> runExtract(withDigest = false, selected = null)
                                        ExtractMode.DEEP -> {
                                            if (choice.chapters.isEmpty()) {
                                                deepReadOpen = true
                                                warning = "说要精读但没听出章号——在下面填，例如 3,5,8"
                                            } else {
                                                runExtract(withDigest = true, selected = choice.chapters)
                                            }
                                        }
                                    }
                                }
                            },
                        )
                        if (deepReadOpen) {
                            OutlinedTextField(
                                value = deepReadInput,
                                onValueChange = { deepReadInput = it },
                                label = { Text(stringResource(R.string.baopu_extract_deep_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            TextButton(
                                enabled = !busy && deepReadInput.isNotBlank(),
                                onClick = {
                                    runExtract(withDigest = true, selected = parseSelection(deepReadInput))
                                },
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text(stringResource(R.string.baopu_extract_deep_go)) }
                        }
                        if (busy) {
                            Text(
                                stringResource(R.string.baopu_extract_busy),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        val run = resultRun
                        Text(
                            text = run?.let { statusLine(it) }
                                ?: stringResource(R.string.baopu_extract_none),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                step = 0
                                picked = null
                                chapters = emptyList()
                                bookLines = emptyList()
                                resultRun = null
                                warning = null
                                deepReadOpen = false
                                deepReadInput = ""
                            }) { Text(stringResource(R.string.baopu_extract_again)) }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.baopu_extract_done))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.baopu_extract_close))
                    }
                }
            }
        }
    }
}

/** 选书后读+切（IO；fatal = 走不下去的原因，note = 走得下去但要知道的怪事）。 */
private fun loadBook(file: File): LoadedBook {
    if (file.length() > PREVIEW_MAX_BYTES) {
        return LoadedBook(fatal = "这本书 ${file.length() / 1024 / 1024} MB，超过 2 MB 预览上限——大书请先分册")
    }
    val text = runCatching { file.readText(Charsets.UTF_8) }
        .getOrElse { return LoadedBook(fatal = "读不了这个文件：${it.javaClass.simpleName}") }
    val chapters = ChapterSplitter.split(text)
    // 替换字符密集 ⇒ 大概率不是 UTF-8（GBK 书按 UTF-8 读的典型相貌）——明说，不装没看见
    val mojibake = text.count { it == '\uFFFD' }
    val note = if (mojibake > text.length / 100) "这本书可能不是 UTF-8 编码，预览可能是乱码" else null
    return LoadedBook(chapters = chapters, lines = text.lines(), note = note)
}

private data class LoadedBook(
    val chapters: List<Chapter> = emptyList(),
    val lines: List<String> = emptyList(),
    val fatal: String? = null,
    val note: String? = null,
)

/** 「3,7,9 / 3、7、9 / 3 7 9」⇒ {3,7,9}（容忍中英文分隔符；章号 0 起，与 Chapter.index 对齐）。 */
private fun parseSelection(input: String): Set<Int> =
    input.split(',', '，', '、', ' ', '\t', '\n')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .toSet()

/** 结果行（三段：写了几章 / 触顶剩余 / 精读故意跳过——都不静默）。 */
@Composable
private fun statusLine(run: DigestRun): String {
    if (run.entries.isEmpty()) return stringResource(R.string.baopu_extract_none)
    val parts = mutableListOf(stringResource(R.string.baopu_extract_result_written, run.entries.size))
    if (run.remaining > 0) {
        parts += stringResource(
            R.string.baopu_extract_result_remaining,
            run.remaining,
            ChapterDigest.MAX_CHAPTERS_PER_RUN,
        )
    }
    if (run.skipped > 0) {
        parts += stringResource(R.string.baopu_extract_result_skipped, run.skipped)
    }
    return parts.joinToString("；")
}
