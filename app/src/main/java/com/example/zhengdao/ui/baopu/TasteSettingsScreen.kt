// 独立开发声明：本文件为本项目原创。
//
// 第二百零二批 · 「口味档案」设置页（**独立文件**：198 在飞占着 `BaopuSettingsScreen.kt`，
// 本批一个字都不碰那里）。图纸：派工单 §3「设置页（必须可纠正）」。
//
// 一条硬要求：**删掉一条，下一轮立即生效**。实现方式是「没有任何缓存」——
// 每次进页面都现读文件（[tick] 只是重读触发器），注入侧（[ExampleBank.inject]）同样每次现读。
// 所以这里的按钮只负责「写文件」，不负责「让谁失效」。
package com.example.zhengdao.ui.baopu

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R
import com.example.zhengdao.baopu.ExampleBank
import com.example.zhengdao.baopu.SampleKind
import com.example.zhengdao.baopu.TasteArchive
import com.example.zhengdao.baopu.TasteEntry
import com.example.zhengdao.baopu.TasteInjection
import com.example.zhengdao.baopu.TasteProfile
import com.example.zhengdao.baopu.TasteSample
import com.example.zhengdao.baopu.TasteSection
import com.example.zhengdao.baopu.TopicSuggest
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.SettingsSubPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一次读盘的全部结果（进页面/点「重新读取」各算一次；不跨页缓存）。
 *
 * @param preview 拿池子里第一条种子演示「下一次会喂什么」——不跑模型、不花 token
 */
private data class TasteSnap(
    val path: String,
    val archive: TasteArchive,
    val samples: List<TasteSample>,
    val muted: Int,
    val preview: TasteInjection?,
)

/** 口味档案设置页（抽屉「口味档案」直达，与抱朴设置页同一种承载方式：Dialog 全屏）。 */
@Composable
internal fun TasteSettingsScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // 本页的协程范围：删/移出这类写盘动作都在它上面跑（页面关掉就取消，不留孤儿）。
    // 装 CoroutineCrashHandler 是全仓门禁要求（CoroutineCrashHandlerTest：UI 作用域不许裸奔）。
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var tick by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf(TasteArchive()) }
    var samples by remember { mutableStateOf(emptyList<TasteSample>()) }
    var muted by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<TasteInjection?>(null) }

    // 资源在 Composable 层取好再交给回调（门禁 check-localcontext-res：Composable 里
    // 不许用 LocalContext 变量去取资源字符串，一律走 stringResource）
    val msgDeleted = stringResource(R.string.baopu_taste_deleted)
    val msgDeleteFailed = stringResource(R.string.baopu_taste_delete_failed)
    val msgMuted = stringResource(R.string.baopu_taste_muted_toast)
    val msgMuteFailed = stringResource(R.string.baopu_taste_mute_failed)

    LaunchedEffect(tick) {
        val snap = withContext(Dispatchers.IO) {
            val dir: File = ExampleBank.productDir(ctx)
            TasteSnap(
                path = ExampleBank.archiveFile(dir).absolutePath,
                archive = ExampleBank.loadArchive(dir),
                samples = ExampleBank.loadSamples(dir),
                muted = ExampleBank.mutedCount(dir),
                preview = ExampleBank.inject(dir, TopicSuggest.POOL.first(), TopicSuggest.STYLES.first()),
            )
        }
        path = snap.path
        archive = snap.archive
        samples = snap.samples
        muted = snap.muted
        preview = snap.preview
    }

    /** 删一条：写文件成功后重读（失败就不动页面，并把原因说出来）。 */
    fun deleteEntry(e: TasteEntry) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ExampleBank.deleteEntry(ctx, e.id) != null }
            Toast.makeText(
                ctx,
                if (ok) msgDeleted else msgDeleteFailed,
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) tick++
        }
    }

    /** 移出一篇（只记清单，不删文件）。 */
    fun muteSample(s: TasteSample) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ExampleBank.mute(ctx, s.kind, s.name) }
            Toast.makeText(
                ctx,
                if (ok) msgMuted else msgMuteFailed,
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) tick++
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsSubPage(title = stringResource(R.string.baopu_taste_title), onBack = onDismiss) {
                SectionCard(title = stringResource(R.string.baopu_taste_intro_title)) {
                    Text(
                        text = stringResource(R.string.baopu_taste_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.baopu_taste_path, path),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = archive.summary(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { tick++ },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.baopu_taste_reload)) }
                        OutlinedButton(
                            onClick = {
                                kotlinx.coroutines.MainScope().launch {
                                    withContext(Dispatchers.IO) { ExampleBank.ensureArchive(ctx) }
                                    tick++
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.baopu_taste_create)) }
                    }
                }

                // ── 三段条目：逐条可删 ──────────────────────────────────────
                TasteSection.entries.forEach { sec ->
                    val rows = archive.bySection(sec)
                    SectionCard(title = sectionTitle(sec) + "（${rows.size}）") {
                        if (rows.isEmpty()) {
                            Text(
                                text = stringResource(R.string.baopu_taste_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        rows.forEach { e ->
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(text = e.text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = stringResource(R.string.baopu_taste_evidence, e.evidence),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { deleteEntry(e) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.baopu_taste_delete)) }
                            }
                        }
                    }
                }

                // ── 被丢弃的行（写了却没生效的，必须让用户看见） ─────────────
                if (archive.rejected.isNotEmpty()) {
                    SectionCard(
                        title = stringResource(R.string.baopu_taste_rejected, archive.rejected.size.toString()),
                    ) {
                        archive.rejected.forEach { r ->
                            Text(
                                text = "${r.line}\n→ ${r.reason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }

                // ── 范例库：正/负各几篇 + 逐篇移出 ───────────────────────────
                val goods = samples.filter { it.kind == SampleKind.GOOD }
                val bads = samples.filter { it.kind == SampleKind.BAD }
                SectionCard(
                    title = stringResource(
                        R.string.baopu_taste_examples_title,
                        goods.size.toString(), bads.size.toString(),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.baopu_taste_examples_muted, muted.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    samples.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${s.kind.label}：${s.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                s.reason?.let {
                                    Text(
                                        text = stringResource(R.string.baopu_taste_reason, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(
                                onClick = { muteSample(s) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.baopu_taste_mute)) }
                        }
                    }
                }

                // ── 注入预览（下一次会喂什么，脱敏后的样貌） ──────────────────
                SectionCard(title = stringResource(R.string.baopu_taste_preview_title)) {
                    Text(
                        text = TasteProfile.previewHeadline(preview),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    preview?.notes?.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    preview?.block?.let { b ->
                        Text(
                            text = if (b.length > 600) b.take(600) + "…" else b,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 段标题（en 版也得看得懂；标题文字只在 UI 层翻译，档案文件里仍是中文段名）。 */
@Composable
private fun sectionTitle(sec: TasteSection): String = when (sec) {
    TasteSection.THEME -> stringResource(R.string.baopu_taste_sec_theme)
    TasteSection.STYLE -> stringResource(R.string.baopu_taste_sec_style)
    TasteSection.FORBIDDEN -> stringResource(R.string.baopu_taste_sec_forbidden)
}
