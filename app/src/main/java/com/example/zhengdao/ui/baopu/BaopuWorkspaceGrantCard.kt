// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 157 补充（用户 2026-10-23 定稿第 3 条）：设置页给「已放行工作区外的会话」清单，
// 可一键撤销；撤销后下次越界重新问。
//
// 为什么单开一个文件而不是塞进 BaopuSettingsScreen：那个文件已经 1000+ 行，
// 而这张卡自带「读库 → 列表 → 撤销 → 重读」的小闭环（含 IO 线程与状态），
// 挤进去只会让设置页越来越难读。房型照 BaopuArtifactShelf（同一套 scope + IO 纪律）。
//
// 判据（可跑）：`WorkspaceGrantTest` 钉住「清单只列放行过的会话」「撤销后 granted 变假」
// 「撤销只影响那一个会话」；UI 侧只做展示与回调，不含业务判据。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.WorkspaceGrantRow
import com.example.zhengdao.baopu.WorkspaceGrantRules
import com.example.zhengdao.baopu.WorkspaceGrants
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.util.TimeFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「已放行工作区外的会话」清单卡（157 补充）。
 *
 * 三态：读取中 / 空（一句解释，不吓人）/ 有行（标题 ＋ 时间 ＋ 工作区 ＋ 撤销）。
 * 撤销即时生效：先删库，再让账本清缓存（[WorkspaceGrants.revoke] 两件事一起做），
 * 所以撤完这一下，**下一次越界就会重新弹卡**——不需要重启、不依赖下一轮。
 */
@Composable
internal fun WorkspaceGrantCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（门禁扫「没装 handler 的裸作用域」）
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var rows by remember { mutableStateOf<List<WorkspaceGrantRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 读库要下 IO：db.read 是同步读（WAL 下很快，但不是主线程该干的事）。
    // 进页面时顺手把账本装到本机库——用户可能先开设置、后跑抱朴（那时引擎还没建）。
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            runCatching { BaopuRuntime.installGrants(ctx) }
            WorkspaceGrants.list()
        }
        loaded = true
    }

    SectionCard(title = stringResource(R.string.baopu_ws_grant_card)) {
        Column(modifier = modifier.fillMaxWidth()) {
            when {
                !loaded -> Text(
                    text = stringResource(R.string.baopu_ws_grant_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                rows.isEmpty() -> Text(
                    text = stringResource(R.string.baopu_ws_grant_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 命中 ≥48dp（派工单设计约束第 3 条）
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(
                                text = WorkspaceGrantRules.rowTitle(row),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = TimeFormats.minuteStamp(row.grantedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.baopu_ws_grant_root, row.rootPath),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            val id = row.sessionId
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { WorkspaceGrants.revoke(id) }
                                rows = withContext(Dispatchers.IO) { WorkspaceGrants.list() }
                                RunLog.log("抱朴设置：撤销工作区外放行 session=$id（删 $n 行）")
                                Toast.makeText(ctx, R.string.baopu_ws_grant_revoked, Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text(stringResource(R.string.baopu_ws_grant_revoke))
                        }
                    }
                }
            }
        }
    }
}
