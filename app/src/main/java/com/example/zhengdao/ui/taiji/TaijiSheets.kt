// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
//
// P4-1（2026-10-14）：自 TaijiComponents.kt（拆分前 1929 行）按内聚性拆出——
// **只搬不改**，代码逐行原样（含已提交的 P2-10 48dp 触摸目标修复）。
package com.example.zhengdao.ui.taiji

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPermission
import com.example.zhengdao.ui.PluginsScreen
import com.example.zhengdao.ui.util.pressClickable

// ── 权限批准底部抽屉（★ 里程碑门禁）─────────────────────────────────

/**
 * 权限批准。
 *
 * ⚠️ **不可省**：Agent 要改文件/执行命令时发 `permission.asked`，不响应就**卡死**，
 * 用户看到的是"卡住了"。
 * ⚠️ **不能盲批**：必须显示工具名 + 具体目标（文件路径 / 命令）。
 */
// ModalBottomSheet 在 Material3 里仍是 @ExperimentalMaterial3Api，必须显式 OptIn。
// 不用非实验性替代品：权限抽屉要求"点外面不能关、必须显式批准"，
// 这正是 ModalBottomSheet 的语义；换成普通 Dialog 会丢掉底部抽屉手势与层级。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    permission: OcPermission,
    onRespond: (allow: Boolean, remember: Boolean) -> Unit,
) {
    // 2026-10-10 UIUX 审计 B4 修：勾选状态**必须以 permission 为 key**。
    // 原写法 `remember { mutableStateOf(false) }` 不带 key，而本抽屉常驻组合（permission
    // 非 null 就挂载）⇒ 连续来第二个授权请求时，「记住这个选择」沿用上一次的勾选值。
    // 后果偏危险：用户可能本来只想记住"允许 ls"，结果把"允许 rm -rf"也记住了。
    var remember by remember(permission) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { /* 不允许点外面关掉——必须显式选择 */ }) {
        // 2026-10-10 UIUX 审计 B5b：补 navigationBarsPadding——手势导航下「允许 / 拒绝」
        // 会被导航条压住一截；这个抽屉还不能被 dismiss，用户只能选一个，被压住就没法选。
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
            Text(stringResource(R.string.taiji_perm_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            // 工具名（action）：bash / edit / write …
            Text(
                stringResource(R.string.taiji_perm_tool, permission.title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            // 具体目标（resources[]）：命令 / 文件路径 —— 用户判断的唯一依据，必须显示
            permission.detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.taiji_perm_target),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Checkbox(remember, onCheckedChange = { remember = it })
            Text(stringResource(R.string.taiji_perm_remember), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onRespond(false, false) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.taiji_perm_deny)) }
                Button(
                    onClick = { onRespond(true, remember) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.taiji_perm_allow)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.taiji_perm_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 消息操作底部抽屉（长按消息触发，2026-10-09）───────────────────────

/**
 * 长按一条消息后弹出的操作面板。
 *
 * ## 为什么是底部抽屉，而不是"长按直接复制 + Toast"
 *
 * 移动端长按的肌肉记忆是"弹出可做哪些事的菜单"（微信/Telegram/iMessage 都是这样）。
 * 直接复制有两个问题：① 用户不知道复制的是哪一份（正文？还是连思考过程一起？）；
 * ② 想复制**整段对话**时无处可点。抽屉把选择交给用户，代价只是多一次点击。
 *
 * ## 关于「删除」
 *
 * 用户最初的需求是"长按复制、删除"。查过服务端接口（`OcRepository` 里的端点清单）：
 * 只有 `DELETE /api/session/{id}`（删**整段会话**，已在左侧抽屉提供），
 * **没有**删单条消息的端点。于是这里**不做**假的删除按钮（点了没反应或只在本地隐藏，
 * 下次拉取又回来——那是"看着成功、其实没有"），改为一句话说明删除整段会话在哪。
 *
 * 文案与可点性都由 [MessageCopy] 计算，面板自身不碰剪贴板（`onCopy` 回调给页面）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: OcMessage,
    conversation: List<OcMessage>,
    onCopy: (label: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    // 复制进剪贴板的文本随界面语言走（角色抬头、思考过程/入参/结果等标签）
    val messageText = remember(message, ctx) { MessageCopy.textOf(message, ctx.resources) }
    val conversationText = remember(conversation, ctx) {
        MessageCopy.textOfConversation(conversation, ctx.resources)
    }
    val preview = messageText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val messageCopyRoleSep = stringResource(R.string.copy_role_separator)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.taiji_msg_actions_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (preview.isEmpty()) stringResource(R.string.taiji_no_copyable_text)
                else MessageCopy.roleLabel(message, ctx.resources) + messageCopyRoleSep + preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            SheetActionRow(stringResource(R.string.taiji_copy_message), enabled = messageText.isNotEmpty()) {
                onCopy("zhengdao-message", messageText)
            }
            SheetActionRow(
                stringResource(R.string.taiji_copy_all_conv, conversation.size),
                enabled = conversationText.isNotEmpty(),
            ) {
                onCopy("zhengdao-conversation", conversationText)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                // 2026-10-10 UIUX 审计 P0-2 顺手：把"接口"这个开发术语换成大白话。
                // "不能单独删其中一条" ⇒ 用户能立刻 get。
                stringResource(R.string.taiji_delete_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 抽屉里的一行操作。禁用态**依然可见**（而不是消失）——用户需要知道"有这件事、只是现在不行"。
 *
 * 高度 14dp×2 + bodyLarge 行高 ≈ 52dp，满足无障碍 48dp 最小触摸目标
 * （与 2026-10-08 走查里"折叠头太矮"是同一类问题的预防）。
 */
@Composable
private fun SheetActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.pressClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 14.dp)
            .semantics { contentDescription = label },
    )
}
// ── 插件面板 ──────────────────────────────────────────────────────────

/**
 * 插件面板（2026-10-08）：**从抽屉进来**，内容复用「设置 → 插件」那一份 [PluginsScreen]。
 *
 * 刻意**不新写一份 UI**：两次实现同一个开关，迟早会出现"这边关掉了、那边还显示已启用"
 * （本项目已经踩过一次同源事故：插件开关曾写在没人读的配置文件上，见 PluginManager 类注释）。
 * 这里只负责外壳（标题 + 完成键 + 高度），状态与操作全部来自 PluginsScreen。
 *
 * **高度不设 dp 上限**（2026-10-08 修复，原为 `heightIn(max = 560.dp)`）：写死的 560dp 在
 * 矮窗 / 横屏 / 分屏下可能比窗口本身还高 —— 那时面板高度由这个数字而不是由窗口决定，
 * 顶部的标题、「完成」以及下方的「添加插件」输入框就有被挤出可视区的风险。
 * 现在只约束宽度，让面板**跟着窗口自适应**：内容矮时按内容高，内容高时由窗口收口，
 * 溢出的部分交给 [PluginsScreen] 自带的 verticalScroll ⇒ 顶部始终可达、底部能滚到。
 * 也不改用 `fillMaxHeight(0.92f)`：那会让内容很少时也硬撑满窗口，白留一大片空白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 2026-10-10 UIUX 审计 B5 修：补 `imePadding()` + `navigationBarsPadding()`。
        // ① 本面板里的 PluginsScreen 含「添加插件」输入框（npm 包名），没有 imePadding 时
        //    键盘弹出会把输入框整个盖住，用户看不见自己输的是什么——而"输对了没"全靠这一行。
        // ② 手势导航下底部「完成」会被导航条压住一截。
        // 两处都与同文件 MessageActionsSheet 对齐（它本来就有），此前三处抽屉不一致。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.taiji_plugins),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
            }
            PluginsScreen()
        }
    }
}
