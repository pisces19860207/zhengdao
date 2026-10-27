// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百五十一批（律令瘦身 §2）：**资料库回抱朴**。
//
// 为什么有这一页：律令里原来有一行「资料库」（只报一个文件数，250 批之后连跳转都没有），
// 而抱朴抽屉里那一行「资料库」跳的却是设置页 —— 250 之后那一路落到的是**浏览器桥页**，
// 是个死入口。用户 2026-10-27 定：「资料库（改成抱朴的）」。于是：
//   · 律令里那一行删掉（律令只管「这个 App」）；
//   · 抱朴抽屉那一行改开本页，进去能看三件事：已索引几个文件／上次更新／重建索引。
//
// 判据只三件（派工单 §2）：已索引 N 个文件、上次更新、重建索引按钮 —— 所以本页**只摆这三样**，
// 不做预览、不做导出、不做开关（那些属于别的批；资料库总开关 `KnowledgeBase.setEnabled`
// 目前没有 UI，本批不给它加，免得顺手改语义）。
//
// 数据从哪来：全部走 `core` 的 `KnowledgeBase`（与终端侧、抱朴侧同一份真源），
// 本页不自己数文件、不自己拼路径 —— 索引文件的时间戳就是「上次更新」。
// 承载方式与抱朴设置页一致：Dialog 全屏（`usePlatformDefaultWidth = false`），
// 因为抽屉里弹的是真窗口，不这么写会被挤成中间一条。
package com.example.zhengdao.ui.baopu

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.core.kb.KnowledgeBase
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.InfoRow
import com.example.zhengdao.ui.SettingsDivider
import com.example.zhengdao.ui.SettingsSubPage
import com.example.zhengdao.ui.theme.rCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 资料库（抱朴抽屉「资料与书」组第一行）。
 *
 * 三件事，缺一不可（派工单 §2 的判据）：已索引 N 个文件 / 上次更新 / 「重建索引」。
 * 状态读取一律在 IO 线程（`KnowledgeBase.status` 会读目录，注释里明写了必须在 IO 上跑），
 * 主线程只拿结果画。
 *
 * @param onDismiss 关页（返回键与顶栏返回都走它）。
 */
@Composable
internal fun KnowledgeLibraryScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // null = 还没读完（先占位「统计中…」，读完覆盖 —— 与设置页同一套写法）。
    var status by remember { mutableStateOf<KnowledgeBase.Status?>(null) }
    // 索引文件的 mtime：null = 清单还没生成过（不是 0，0 会被格式化成 1970 年）。
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    // 文案在组合期取好再交给点击回调：Compose 的 lint（LocalContextGetResourceValueCall）不许在
    // 事件回调里用 `ctx.getString(...)`——配置变更时它拿到的是旧值，而 `stringResource` 会跟着失效。
    val rebuildStartedText = stringResource(R.string.baopu_kb_rebuild_started)

    LaunchedEffect(tick) {
        val s = withContext(Dispatchers.IO) {
            runCatching { KnowledgeBase.status(ctx) }.getOrNull()
        }
        val at = withContext(Dispatchers.IO) {
            runCatching {
                KnowledgeBase.indexFile(ctx).takeIf { it.isFile }?.lastModified()
            }.getOrNull()
        }
        status = s
        updatedAt = at
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsSubPage(
                title = stringResource(R.string.baopu_drawer_library),
                onBack = onDismiss,
            ) {
                SectionCard(stringResource(R.string.baopu_kb_group)) {
                    // 两行都是**只读状态**（InfoRow：标签＋右对齐数值，不带"›"）——
                    // 带箭头会让人以为点得进去，而这一页唯一的动作是下面那个按钮。
                    InfoRow(
                        label = stringResource(R.string.baopu_kb_files_label),
                        value = when {
                            status == null -> stringResource(R.string.settings_status_measuring)
                            status?.state == KnowledgeBase.State.DISABLED ->
                                stringResource(R.string.settings_status_disabled)
                            else -> pluralStringResource(
                                R.plurals.settings_status_file_count,
                                status?.files ?: 0,
                                status?.files ?: 0,
                            )
                        },
                    )
                    SettingsDivider()
                    InfoRow(
                        label = stringResource(R.string.baopu_kb_updated),
                        value = updatedAt?.let { TimeFormats.minuteStamp(it) }
                            ?: stringResource(R.string.baopu_kb_never),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.baopu_kb_rebuild_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        // 用户显式要求 ⇒ requestRebuild 内部走 forced（E-061：他删过也照样重建）。
                        // 它自己起后台线程，这里不等；说一句"已开始"，再重读一次状态。
                        KnowledgeBase.requestRebuild(ctx)
                        Toast.makeText(ctx, rebuildStartedText, Toast.LENGTH_SHORT).show()
                        tick++
                    },
                    shape = RoundedCornerShape(rCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.baopu_kb_rebuild))
                }
            }
        }
    }
}
