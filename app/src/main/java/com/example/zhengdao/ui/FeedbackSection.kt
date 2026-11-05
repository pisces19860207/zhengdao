// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.zhengdao.R
import com.example.zhengdao.feedback.FeedbackDiagnostics
import com.example.zhengdao.feedback.FeedbackLinks
import com.example.zhengdao.feedback.FeedbackSnapshot
import com.example.zhengdao.util.copyPlainText

/**
 * 设置页的「反馈」一块（第四百五十二批）。整块只挂在本页最后一个分组之后，
 * 设置页本身的结构、其它分组、浮层一行未动。
 *
 * 三个动作全部放在点击回调里：没有 LaunchedEffect、没有后台任务、没有自动发送。
 * 不点就什么都不会离开这台设备；点开外链也只是把 Intent 交给系统浏览器或邮件应用。
 */
@Composable
internal fun FeedbackSection() {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var note by remember { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }

    /** 同一块诊断文本：复制过就复用，没复制过才现采一次，保证三个入口内容一致。 */
    fun block(): String = diagnostic ?: FeedbackDiagnostics.build(
        FeedbackDiagnostics.project(FeedbackSnapshot.collect(ctx)),
    ).also { diagnostic = it }

    fun openOrNote(intent: Intent) {
        val opened = runCatching { ctx.startActivity(intent) }.isSuccess
        if (!opened) note = res.getString(R.string.settings_feedback_open_failed)
    }

    SectionCard(stringResource(R.string.settings_feedback_title)) {
        SettingRow(
            label = stringResource(R.string.settings_feedback_copy),
            value = note ?: stringResource(R.string.settings_feedback_copy_desc),
        ) {
            val ok = copyPlainText(ctx, "zhengdao-diagnostics", block())
            note = res.getString(
                if (ok) R.string.settings_feedback_copied else R.string.settings_feedback_copy_failed,
            )
        }
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.settings_feedback_github),
            value = stringResource(R.string.settings_feedback_github_desc),
            valueMaxLines = 2,
        ) {
            val url = FeedbackLinks.issueUrl(
                res.getString(R.string.settings_feedback_issue_title), block(),
            )
            openOrNote(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.settings_feedback_email),
            value = stringResource(R.string.settings_feedback_email_desc),
        ) {
            val url = FeedbackLinks.mailtoUrl(
                res.getString(R.string.settings_feedback_mail_subject), block(),
            )
            openOrNote(Intent(Intent.ACTION_SENDTO, url.toUri()))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_feedback_no_upload),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
