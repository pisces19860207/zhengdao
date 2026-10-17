// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuScreen：真页面根——会话区＋消息流＋输入区＋状态条
// ＋中断提示；提示词小框＋右上角复制＝复用 ui/baopu/CopyBox.kt）+ §4.4
// （MainActivity 的 TabIndex.BAOPU 支由占位页改指本页）。
// 刀 1 补丁：页顶加「换通道」入口（弹 BaopuChannelSheet）；保存后重建控制器
// （控制器构造期持引擎，而 provider 是引擎的构造期注入 ⇒ 换通道必须换引擎）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.agent.RunState

/**
 * 抱朴页（刀 1 · B 段）：占位页 [BaopuRebuilding] 的真身。
 *
 * 结构（上→下）：换通道入口 → 会话头（状态/中断/续跑）→ 记忆候选卡 → 提示词小框（可复制）
 * → 消息流 → 输入区。
 * 「提示词小框」的落地：展示**最近一条抱朴回复**的可复制小框（CopyBox）——
 * 用户把回复复制到别处用，是这一行设计的实际用途场景（报告里写明解释）。
 */
@Composable
fun BaopuScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 换通道 = 换引擎：控制器构造期持引擎（BaopuChatController:46），改 epoch 让它重建。
    var channelEpoch by remember { mutableIntStateOf(0) }
    val controller = remember(channelEpoch) { BaopuChatController(context) }
    var showChannel by remember { mutableStateOf(false) }

    // 引擎事件驱动刷新已由 controller 内部做；这里补一个前台可见时的兜底刷新
    // （进程被杀后重进：库里有中断标记，controller 构造时 refresh 已读一次）。
    LaunchedEffect(channelEpoch) { controller.refresh() }

    val state = controller.engineState.value
    val lastAssistant = controller.messages.lastOrNull { it.role != "user" }
        ?.parts?.lastOrNull { it.kind == "text" }?.text.orEmpty()

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showChannel = true }) {
                    Text(stringResource(R.string.baopu_channel_button))
                }
            }
            BaopuSessionBar(
                state = state,
                step = controller.engineStep.value,
                interruptedStep = controller.interruptedStep,
                notice = controller.engineNotice.value,
                hasSession = controller.sessionId != null,
                onResume = { controller.resume() },
                onNewSession = { controller.newSession() },
            )
            BaopuMemoryCard(
                candidates = controller.memoryCandidates,
                onConfirm = { controller.confirmMemory(it) },
                onForget = { controller.forgetMemory(it) },
            )
            // 提示词小框（右上角复制）：显示最近一条抱朴回复
            if (lastAssistant.isNotBlank()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    CopyBox(
                        text = lastAssistant,
                        label = stringResource(R.string.baopu_role_assistant),
                        maxLines = 4,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                // LazyColumn 自带滚动（不能再套 verticalScroll——嵌套滚动会崩）
                BaopuMessageList(
                    messages = controller.messages,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            BaopuComposer(
                busy = controller.busy,
                onSend = { controller.send(it) },
                onStop = { controller.stop() },
            )
            if (showChannel) {
                val (customBase, customModel) = ProviderPrefs.storedCustom(context)
                BaopuChannelSheet(
                    currentChannelId = ProviderPrefs.currentChannelId(context),
                    storedKeyOf = { id -> ProviderPrefs.storedKey(context, id).orEmpty() },
                    customBaseUrl = customBase,
                    customModel = customModel,
                    onDismiss = { showChannel = false },
                    onSave = { id, key, base, model ->
                        BaopuRuntime.switchChannel(context, id, key, base, model)
                        showChannel = false
                        channelEpoch++ // 换引擎（provider 是构造期注入；旧引擎线程闲着无害）
                    },
                )
            }
        }
    }
}
