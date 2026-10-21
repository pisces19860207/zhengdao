// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose Material3 官方组件；Android 平台 ClipboardManager
// （经 util/Clipboard.kt 的 copyPlainText 出口）。复制口径沿用本仓库既有纪律：
// 失败不许弹"已复制"骗人（原参照件 ui/baopu/MessageCopy.kt 已随旧抱朴 UI 清场删除，
// 纪律记录在此，不再指向仓库外文件名）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.util.copyPlainText
import kotlinx.coroutines.delay

/**
 * 「小框 + 右上角复制」通用组件（刀 0 · 2026-10-16）。
 *
 * 给刀 1 的各种只读面板（token 估算说明、守卫拒绝理由、run_log 明细……）用：
 * 内容框 + 一个复制入口 + 复制结果的轻提示，三件事打包，不为每个面板重写一遍。
 *
 * ## 三条既有纪律的落地
 * 1. **复制失败不许弹"已复制"**：`copyPlainText` 返回 false 时提示明确说失败
 *    （E-020/E-045/E-054 同款原则：不接受静默假装成功）；
 * 2. **空文本禁用复制**：没东西可复制不是失败，是没得复制——按钮直接禁用，
 *    不给用户点出一个假失败；
 * 3. **i18n 不新增字符串**：复用既有键（`copy_text` / `taiji_copied` /
 *    `taiji_copy_failed`，中英文 locale 都齐）。键名带 `taiji` 前缀是历史命名，
 *    语义通用；本刀零修改既有文件（含 strings.xml），不为三个字新开键。
 *
 * 提示走组件内 1.6s 自消退（LaunchedEffect），不依赖 Snackbar host——
 * 这个组件要在没有 Scaffold 的地方也能用。
 */
@Composable
fun CopyBox(
    text: String,
    modifier: Modifier = Modifier,
    label: String = "证道",
    maxLines: Int = 6,
    // 2026-10-18 用户：「已完成做成动态卡吧，点击后展开，再次点击就收回，只显示已完成和右边的复制按钮」
    // ⇒ collapsible=true 时默认 1 行，点正文展开全文，再点收回（复制按钮不受影响）。
    collapsible: Boolean = false,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    var hint by remember { mutableStateOf<String?>(null) }
    var expanded by remember(text) { mutableStateOf(false) }

    // 提示 1.6s 后自消退；hint 变化会重启这个 effect（每次复制都重新计时）。
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_600)
            hint = null
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Box {
            Column(modifier = Modifier.padding(top = 4.dp, end = 88.dp, bottom = 4.dp)) {
                Text(
                    text = text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    maxLines = if (collapsible && !expanded) 1 else maxLines,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 右上角：复制按钮（空文本禁用）
            TextButton(
                onClick = {
                    val ok = copyPlainText(context, label, text)
                    hint = if (ok) {
                        res.getString(R.string.taiji_copied)
                    } else {
                        res.getString(R.string.taiji_copy_failed)
                    }
                },
                enabled = text.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = stringResource(R.string.copy_text),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            // 底部：轻提示（有才占位，避免没提示时底部空一行）
            if (hint != null) {
                Text(
                    text = hint.orEmpty(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
