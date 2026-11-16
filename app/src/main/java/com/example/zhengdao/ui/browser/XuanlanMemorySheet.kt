// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import com.example.zhengdao.ui.theme.rPill
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.browser.PageMemoryPolicy
import kotlinx.coroutines.launch

/**
 * 会话级页面记忆的半屏（651）——「本会话看过的页」列表 ＋ 两种清空。
 *
 * 三条设计约束：
 * 1. **只显示 URL 与标题**：主行是标题（空则地址本身，[PageMemoryPolicy.labelOf]），
 *    次行是 host（复用 [BrowserUrlPolicy.displayHost]，地址展示的唯一判据不复制）；
 * 2. **新在上**：存储是 oldest → newest，展示走 [PageMemoryPolicy.recentFirst]
 *    （用户问的是"我刚看了什么"）；
 * 3. **两个清空各一句话说清范围**：「清空本会话」不动别的会话；「全部清空」先问一次
 *    （不可逆动作不靠手滑——与全站确认制同一条纪律）。
 *
 * 数据从 [PageMemoryStore] 来（IO 线程读），本文件不碰数据库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XuanlanMemorySheet(
    sessionId: Long,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val palette = xuanLanPalette()
    val scope = rememberCoroutineScope { com.example.zhengdao.keepalive.CoroutineCrashHandler }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pages by remember { mutableStateOf<List<PageMemoryPolicy.Page>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }

    // 打开即读一次；清空后 reloadKey+1 再读一次（不猜内存里的结果，读盘为准）
    LaunchedEffect(sessionId, reloadKey) {
        pages = PageMemoryStore.list(context, sessionId)
    }

    val clearedSessionText = stringResource(R.string.xuanlan_memory_cleared_session)
    val clearFailedText = stringResource(R.string.xuanlan_memory_clear_failed)
    val clearedAllTemplate = stringResource(R.string.xuanlan_memory_cleared_all)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.stripBg,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.xuanlan_memory_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.xuanlan_memory_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkDisabled,
            )
            Spacer(Modifier.height(12.dp))

            if (pages.isEmpty()) {
                Text(
                    text = stringResource(R.string.xuanlan_memory_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkDisabled,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(items = PageMemoryPolicy.recentFirst(pages), key = { it.url }) { page ->
                        MemoryRow(
                            page = page,
                            palette = palette,
                            onClick = {
                                onOpen(page.url)
                                onDismiss()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetAction(
                    label = stringResource(R.string.xuanlan_memory_clear_session),
                    enabled = pages.isNotEmpty(),
                    palette = palette,
                    modifier = Modifier.weight(1f),
                ) {
                    scope.launch {
                        val ok = PageMemoryStore.clearSession(context, sessionId)
                        onToast(if (ok) clearedSessionText else clearFailedText)
                        reloadKey++
                    }
                }
                SheetAction(
                    label = stringResource(R.string.xuanlan_memory_clear_all),
                    enabled = true,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                ) { confirmAll = true }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.xuanlan_memory_clear_all)) },
            text = { Text(stringResource(R.string.xuanlan_memory_confirm_all)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    scope.launch {
                        val n = PageMemoryStore.clearAll(context)
                        onToast(if (n > 0) String.format(clearedAllTemplate, n) else clearFailedText)
                        reloadKey++
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** 一行页面记忆：标题（主）＋ host（次）；整行可点＝打开这一页。 */
@Composable
private fun MemoryRow(
    page: PageMemoryPolicy.Page,
    palette: XuanLanPalette,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(rPill))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = PageMemoryPolicy.labelOf(page),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = BrowserUrlPolicy.displayHost(page.url),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.xuanlan_memory_open),
            style = MaterialTheme.typography.labelLarge,
            color = palette.focusRing,
        )
    }
}

/**
 * 半屏里的一枚动作键（48dp 热区、纸底、圆角 12）——与玄览底栏同一套语言。
 *
 * 刻意 `internal` 而不是各文件私有：页面记忆半屏与「尺寸与导出」半屏是同一个浮层的两个内容，
 * 键的几何与禁用态必须一模一样（两处各写一份＝两套手感，改一处漏一处）。
 */
@Composable
internal fun SheetAction(
    label: String,
    enabled: Boolean,
    palette: XuanLanPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.clusterPrimaryBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) palette.ink else palette.inkDisabled,
        )
    }
}
