// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十一批（打开产物）· 「在文件管理器中定位」的退化层。
// 单独开文件的原因：BaopuArtifactShelfTest 钉着「产物架里不许出现剪贴板写法、
// 整文件只有一层 Dialog」——复制的是**路径**（不是查看器那套复制全文），
// 不重写查看器，也不把第二层窗口塞进产物架。
package com.example.zhengdao.ui.baopu

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.getSystemService
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.rCard

/** 没有文件管理器能接目录 Uri 时的那一层：路径看得见、复制点得动。 */
@Composable
internal fun ArtifactLocateDialog(absPath: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            // 16dp＝rCard（与既有「Dialog 内 Surface」同值）；弹窗层到底用 14 还是 16 的定案
            // 还在 radius 白名单备注里排队，这里先按现值上令牌，不擅自改观感。
            shape = RoundedCornerShape(rCard),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.baopu_artifact_locate_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.baopu_artifact_locate_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.baopu_artifact_locate_path, absPath),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService<ClipboardManager>()
                        if (cm == null) {
                            Toast.makeText(
                                ctx,
                                res.getString(R.string.baopu_artifact_locate_copy_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            val label = res.getString(R.string.baopu_artifact_locate_copy)
                            cm.setPrimaryClip(ClipData.newPlainText(label, absPath))
                            Toast.makeText(
                                ctx,
                                res.getString(R.string.baopu_artifact_locate_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }) {
                        Text(stringResource(R.string.baopu_artifact_locate_copy))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.baopu_artifact_shelf_close))
                    }
                }
            }
        }
    }
}
