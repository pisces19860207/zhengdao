// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuScreen：真页面根——会话区＋消息流＋输入区＋状态条
// ＋中断提示；提示词小框＋右上角复制＝复用 ui/baopu/CopyBox.kt）+ §4.4
// （MainActivity 的 TabIndex.BAOPU 支由占位页改指本页）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.RunState

/**
 * 抱朴页（刀 1 · B 段）：占位页 [BaopuRebuilding] 的真身。
 *
 * 结构（上→下）：会话头（状态/中断/续跑）→ 记忆候选卡 → 提示词小框（可复制）
 * → 消息流 → 输入区。
 * 「提示词小框」的落地：展示**最近一条抱朴回复**的可复制小框（CopyBox）——
 * 用户把回复复制到别处用，是这一行设计的实际用途场景（报告里写明解释）。
 */
@Composable
fun BaopuScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { BaopuChatController(context) }

    // 引擎事件驱动刷新已由 controller 内部做；这里补一个前台可见时的兜底刷新
    // （进程被杀后重进：库里有中断标记，controller 构造时 refresh 已读一次）。
    LaunchedEffect(Unit) { controller.refresh() }

    val state = controller.engineState.value
    val lastAssistant = controller.messages.lastOrNull { it.role != "user" }
        ?.parts?.lastOrNull { it.kind == "text" }?.text.orEmpty()

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
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
        }
    }
}
