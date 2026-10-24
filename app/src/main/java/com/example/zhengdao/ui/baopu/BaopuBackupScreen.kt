// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第十批 §1 D 段第 1 条（长按图标 → 静态快捷方式「备份原件」⇒ 对话框式
// 页面：「原始/ 在 App 私有目录，卸载会丢」＋「立即导出全部／导出某类／取消」）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.util.TimeFormats

/**
 * 快捷方式／入口按钮的**请求持有者**（进程级，与 BrowserHolder 的 pendingUrl 同房型）。
 *
 * 为什么需要它：静态快捷方式的 intent 落在 [com.example.zhengdao.MainActivity]，
 * 而备份对话框画在抱朴页里。Activity 把请求塞进这里，抱朴页组合时消费——
 * 冷启动（捷径直接拉起 App）与热启动（App 已在栈里）两条路都通，且不往
 * AndroidManifest 里加 intent-filter（显式 intent 不需要过滤器）。
 */
object BaopuIntents {

    /** 静态快捷方式的 action（shortcuts.xml 与 MainActivity 共认这一个字面值）。 */
    const val ACTION_BACKUP = "com.example.zhengdao.action.BAOPU_BACKUP"

    private val backupRequested = mutableStateOf(false)

    /** 请求状态（抱朴页用它起 LaunchedEffect——热启动时请求是后到的，要能观察到）。 */
    internal val backupRequestedState: State<Boolean> get() = backupRequested

    /** 请求打开备份对话框（Activity 侧调）。 */
    fun requestBackup() {
        backupRequested.value = true
    }

    /**
     * 看一眼有没有待处理的备份请求（**不清**——切 Tab 用）。
     *
     * 为什么切 Tab 只看不吃：HomeTabs 的 ON_RESUME 靠它决定切不切抱朴，而对话框
     * 只有抱朴页自己知道该不该弹。真消费在 [consumeBackup]。
     */
    fun peekBackup(): Boolean = backupRequested.value

    /** 抱朴页组合时消费（只消费一次——`getAndSet(false)` 语义手写版）。 */
    fun consumeBackup(): Boolean {
        val v = backupRequested.value
        backupRequested.value = false
        return v
    }

    /**
     * 一次外部正文请求（第八十三批 §B 88）：系统分享的文本，或 `zhengdao://ask?q=` 解出的正文。
     *
     * [truncated] 只用于提示「超长已截断」——截断本身在纯路由层完成（上限
     * `MAX_BAOPU_CHARS`），这里不重复截断，免得提示的字数和实际内容对不上。
     */
    data class PromptRequest(val text: String, val truncated: Boolean)

    private val promptRequested = mutableStateOf<PromptRequest?>(null)

    /** 请求状态（输入框用 LaunchedEffect 观察——热启动时请求是后到的）。 */
    internal val promptRequestedState: State<PromptRequest?> get() = promptRequested

    /** 请求把一段外部正文预填进抱朴输入框（Activity 侧调）。**绝不发送**。 */
    fun requestPrompt(text: String, truncated: Boolean) {
        promptRequested.value = PromptRequest(text, truncated)
    }

    /** 看一眼有没有待预填正文（切 Tab 用，**不清**——真消费在输入框）。 */
    fun peekPrompt(): PromptRequest? = promptRequested.value

    /**
     * 输入框组合时消费（只消费一次）。
     *
     * 按**引用**比对：效果可能被重组重放，旧请求不能吞掉后到的新请求
     *（[requestPrompt] 每次都新建对象，引用不同即视为新请求）。
     */
    fun consumePrompt(expected: PromptRequest): Boolean {
        if (promptRequested.value !== expected) return false
        promptRequested.value = null
        return true
    }

    /**
     * 完成通知点击带回来的会话 id（第一百六十九批 §3）。
     *
     * 与备份/正文请求同一房型：通知落在 MainActivity，会话只在抱朴页里能开 ⇒
     * Activity 挂请求、抱朴页组合时消费；冷启动（点通知拉起 App）与热启动都丢不了。
     */
    const val EXTRA_SESSION = "baopu_session_id"

    private val sessionRequested = mutableStateOf<Long?>(null)

    /** 请求状态（抱朴页用 LaunchedEffect 观察；热启动时请求是后到的）。 */
    internal val sessionRequestedState: State<Long?> get() = sessionRequested

    /** 请求打开某个会话（Activity 侧调）。 */
    fun requestSession(sessionId: Long) {
        sessionRequested.value = sessionId
    }

    /** 看一眼有没有待打开的会话（切 Tab 用，**不清**——真消费在抱朴页）。 */
    fun peekSession(): Long? = sessionRequested.value

    /** 抱朴页消费（只消费一次）。 */
    fun consumeSession(): Long? {
        val v = sessionRequested.value
        sessionRequested.value = null
        return v
    }
}

/**
 * 备份原件对话框（刀 2 D 段第 1 条）——**对话框式页面**，不是全屏页：
 * 用户是被一个快捷方式带进来的，一句话说清风险 + 三个出口就够了。
 *
 * 三个出口（派工单原文）：立即导出全部 / 导出某类 / 取消。为什么不用
 * material3 的 [androidx.compose.material3.AlertDialog]：它只有
 * confirm/dismiss 两个按钮位，三个出口必须自己摆（[Dialog] + [Surface]）。
 *
 * 「导出某类」先给最常用的一档（文本件 `text`）——索引里 `kind` 就两值
 * （`text`/`unclaimed`），不认领的格式本来就不该进私有目录，导出它们没有意义。
 *
 * **自动导出默认关**（派工单 D 段原话）：本对话框只由用户动作打开，
 * 没有任何定时/条件自动导出——一开等于放弃隔离，必须用户明确开。
 */
@Composable
internal fun BaopuBackupDialog(
    lastExportAt: Long,
    unexportedCount: Int,
    busy: Boolean,
    onExportAll: () -> Unit,
    onExportText: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.baopu_backup_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.baopu_backup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = exportStatusLine(lastExportAt, unexportedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 工作区是 App 私有时，导出仍在 App 里——防丢目的达不到，必须说在明处
                // （不然用户以为导完了，卸载照样丢，比不导更糟）。
                if (BaopuWorkspace.isPrivate(LocalContext.current)) {
                    Text(
                        text = stringResource(R.string.baopu_backup_private_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = zhengdaoErrorText(), // P2-11：error 色 3.55:1 不达正文 AA 4.5:1
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onExportAll, enabled = !busy) {
                        Text(stringResource(R.string.baopu_backup_export_all))
                    }
                    TextButton(onClick = onExportText, enabled = !busy) {
                        Text(stringResource(R.string.baopu_backup_export_text))
                    }
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text(stringResource(R.string.baopu_backup_cancel))
                    }
                }
            }
        }
    }
}

/** 「上次导出时间／未导出件数」一行（从没导出过要说得和「0 天前」不一样）。 */
@Composable
internal fun exportStatusLine(lastExportAt: Long, unexportedCount: Int): String {
    val last = if (lastExportAt <= 0L) {
        stringResource(R.string.baopu_backup_never)
    } else {
        stringResource(R.string.baopu_backup_last, TimeFormats.fileStamp(lastExportAt))
    }
    return last + "；" + pluralStringResource(R.plurals.baopu_backup_unexported, unexportedCount, unexportedCount)
}
