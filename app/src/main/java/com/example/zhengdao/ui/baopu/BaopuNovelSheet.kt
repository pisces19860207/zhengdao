// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 小说工程总览（第一百五十五批 W1/W2 的 UI 落点）：之前三个 novel_* 工具只有模型看得见，
// 抽屉里没有任何入口。这里只做**只读总览**＋一个「新建」提示，写盘仍走 novel_create（CONFIRM）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.NOVEL_BODY_DIR
import com.example.zhengdao.baopu.agent.NOVEL_META_FILE
import com.example.zhengdao.baopu.agent.NOVEL_ROOT_DIR
import java.io.File
import org.json.JSONObject

/** 一行小说项目读数（只读；不写盘、不建目录）。 */
private data class NovelRow(val title: String, val chapters: Int, val chars: Int)

@Composable
internal fun BaopuNovelSheet(
    workspaceRoot: File,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val rows = remember(workspaceRoot) { readNovels(workspaceRoot) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.baopu_novel_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.baopu_novel_sheet_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.baopu_novel_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = row.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = pluralStringResource(
                                    R.plurals.baopu_novel_stats,
                                    row.chapters,
                                    row.chapters,
                                    row.chars,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.baopu_novel_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onCreate(name.trim()) },
                        enabled = name.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.baopu_novel_create))
                    }
                }
            }
        }
    }
}

/** 读 `小说/<书名>/`：title 取 book.json，章数/字数取 `正文/` 下的文件。坏文件跳过，不崩。 */
private fun readNovels(root: File): List<NovelRow> {
    val dir = File(root, NOVEL_ROOT_DIR)
    val projects = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
    return projects.mapNotNull { project ->
        val meta = File(project, NOVEL_META_FILE)
        if (!meta.isFile) return@mapNotNull null
        val title = runCatching {
            JSONObject(meta.readText()).optString("title").trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: project.name
        val bodyDir = File(project, NOVEL_BODY_DIR)
        val chapters = bodyDir.listFiles { f -> f.isFile } ?: emptyArray()
        val chars = chapters.sumOf { f ->
            runCatching {
                f.readText().count { !it.isWhitespace() }
            }.getOrDefault(0)
        }
        NovelRow(title = title, chapters = chapters.size, chars = chars)
    }.sortedBy { it.title }
}
