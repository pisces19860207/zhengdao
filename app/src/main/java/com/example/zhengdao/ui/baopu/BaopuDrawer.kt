// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-17 口述「不常用的设置什么的归到抱朴的设置抽屉里……满屏幕都是按钮和按键，
// 手机本来屏幕就小」＋ 选定方案「全收进去，顶条只留 ☰／状态／🔊／＋」。
//
// 为什么要抽屉而不是再加一行按钮：
//   抱朴页顶原本挂着「章节抽取」「备份原件」「换通道」三个文字按钮，下面还有一行状态＋新会话，
//   手机窄屏上两行入口占掉首屏一大块，真正的对话内容被挤下去。抽屉把低频入口收成一层，
//   首屏只留每次都用的四件。
//
// 口径：
//   1. 抽屉只做**入口搬运**，不减任何功能（原来能点的，抽屉里都还能点）；
//   2. ☰ 是唯一必须入口（窄屏）；宽屏把抽屉钉住常驻时不出 ☰；
//   3. 关闭路径三条都要有：点遮罩、点返回键、点抽屉里的项（项点了先关再跳）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/** 抽屉里的一行：标题＋说明＋点击。说明写给"不常用的人"看，省得点进去才知道是什么。 */
@Composable
private fun DrawerItem(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分组标题（会话／资料与书／设置）。 */
@Composable
private fun DrawerGroup(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * 抱朴的设置抽屉。
 *
 * @param busy 引擎在跑：新会话与换通道都置灰。这是引擎层约束，不是 UI 洁癖——
 *             [com.example.zhengdao.baopu.BaopuChatController] 构造期就持引擎，
 *             换 provider 必须重建控制器，跑到一半换会丢现场。
 */
@Composable
internal fun BaopuDrawer(
    busy: Boolean,
    onNewSession: () -> Unit,
    onHistorySessions: () -> Unit,
    onLibrary: () -> Unit,
    onExtract: () -> Unit,
    onBackup: () -> Unit,
    onChannel: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.baopu_drawer_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.baopu_drawer_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp),
        )

        DrawerGroup(stringResource(R.string.baopu_drawer_group_session))
        DrawerItem(
            title = stringResource(R.string.baopu_new_session),
            description = stringResource(R.string.baopu_drawer_new_session_desc),
            enabled = !busy,
            onClick = onNewSession,
        )
        // 第二十批 §2.1.1：历史会话入口，排在新会话后面——旧会话从这儿找回来。
        DrawerItem(
            title = stringResource(R.string.baopu_drawer_history),
            description = stringResource(R.string.baopu_drawer_history_desc),
            onClick = onHistorySessions,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        DrawerGroup(stringResource(R.string.baopu_drawer_group_library))
        DrawerItem(
            title = stringResource(R.string.baopu_drawer_library),
            description = stringResource(R.string.baopu_drawer_library_desc),
            onClick = onLibrary,
        )
        DrawerItem(
            title = stringResource(R.string.baopu_extract_title),
            description = stringResource(R.string.baopu_drawer_extract_desc),
            onClick = onExtract,
        )
        DrawerItem(
            title = stringResource(R.string.baopu_backup_title),
            description = stringResource(R.string.baopu_drawer_backup_desc),
            onClick = onBackup,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        DrawerGroup(stringResource(R.string.baopu_drawer_group_settings))
        DrawerItem(
            title = stringResource(R.string.baopu_channel_button),
            description = stringResource(R.string.baopu_drawer_channel_desc),
            enabled = !busy,
            onClick = onChannel,
        )
        DrawerItem(
            title = stringResource(R.string.baopu_drawer_settings),
            description = stringResource(R.string.baopu_drawer_settings_desc),
            onClick = onSettings,
        )
        Spacer(Modifier.height(24.dp))
    }
}
