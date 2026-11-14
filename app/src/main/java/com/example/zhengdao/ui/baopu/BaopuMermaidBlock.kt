// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十七批 · 对话里的「Mermaid 代码块 + 渲染成图」。
//
// 口径（派工单【做什么】③④＋判据⑤）：
// - **默认只显示代码**，一个字都不自动渲染——不点按钮就 0 次离屏任务
//   （这条不是靠"看代码里没调用"，而是 [MermaidRenderer.invocationCount] 在真机测试里断言）；
// - 点了才出图：出一个 PNG 落 `<工作区>/产物/图表/`，并把图画在按钮下面；
// - 语法错 ⇒ 把 mermaid 的**首行错误**原样显示（不许静默空白、不许只说"失败了"）；
// - 超过 20KB ⇒ 直接说超了，**不截断、不尝试**（截出来是残图，比不画更糟）。
//
// 容器：不复用四档行动卡（那是"抱朴让你决定什么"的形状），这里只是一段代码＋一个按钮，
// 用最轻的描边块，不新增第五种卡。
package com.example.zhengdao.ui.baopu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.mermaid.MermaidFence
import com.example.zhengdao.mermaid.MermaidRenderResult
import com.example.zhengdao.mermaid.MermaidRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 代码块最多显示多少行（超出折成「…」，全文仍可长按选中复制）。 */
private const val CODE_MAX_LINES = 14

/** 图在对话里解码到多大（px，长边）——够看清结构，又不至于把列表拖死。 */
private const val PREVIEW_TARGET_PX = 1600

@Composable
internal fun BaopuMermaidBlock(
    diagram: MermaidFence.Segment.Diagram,
    /** 稳定键：同一段代码在重组/滚动后状态不丢（渲染结果按它记住）。 */
    key: String,
) {
    val ctx = LocalContext.current
    // 文案走 LocalResources（Configuration 变化时它才是新鲜的；LocalContext.getString 会被 lint 拦）
    val res = LocalResources.current
    var running by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    // 存路径而不是 File：rememberSaveable 只认可序列化的东西；滚动回来重新解码即可。
    var savedPath by rememberSaveable(key) { mutableStateOf<String?>(null) }
    // UI 侧协程必须挂崩溃处理器（CoroutineCrashHandlerTest 门禁钉着）
    val scope = rememberCoroutineScope { CoroutineCrashHandler }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        // 代码本体：等宽、灰底、最多 14 行（全文仍在 SelectionContainer 里可选可复制）
        Text(
            text = diagram.code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = CODE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )

        when {
            diagram.overLimit -> {
                Text(
                    text = stringResource(R.string.baopu_mermaid_too_large, diagram.bytes.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            diagram.code.isBlank() -> {
                Text(
                    text = stringResource(R.string.baopu_mermaid_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            else -> {
                TextButton(
                    onClick = {
                        if (running) return@TextButton
                        running = true
                        error = null
                        scope.launch {
                            val root = BaopuWorkspace.hostDir(ctx)
                            val activity = ctx.findActivity()
                            if (activity == null) {
                                running = false
                                error = res.getString(R.string.baopu_mermaid_no_activity)
                                return@launch
                            }
                            // 出图会阻塞等主线程的离屏会话 ⇒ 必须离开主线程
                            val result = withContext(Dispatchers.IO) {
                                MermaidRenderer.render(activity, root, diagram.code, diagram.kind)
                            }
                            running = false
                            when (result) {
                                is MermaidRenderResult.Ok ->
                                    savedPath = result.file.absolutePath
                                is MermaidRenderResult.Fail ->
                                    error = result.reason
                            }
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !running,
                ) {
                    Text(
                        text = stringResource(
                            if (running) R.string.baopu_mermaid_rendering else R.string.baopu_mermaid_render,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        error?.let { reason ->
            Text(
                text = stringResource(R.string.baopu_mermaid_failed, reason),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        val path = savedPath
        if (path != null) {
            val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
                value = withContext(Dispatchers.IO) {
                    decodeSampledImage(File(path), PREVIEW_TARGET_PX)
                }
            }
            val image = bmp
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.baopu_mermaid_label),
                    contentScale = ContentScale.Fit,
                    // 按位图自己的宽高比排版：只写 fillMaxWidth 会让高度取"位图原始高度"，
                    // 一张 1312×4000 的图会把整条消息顶到屏幕外。
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(image.width.toFloat() / image.height.toFloat().coerceAtLeast(1f))
                        .padding(top = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.baopu_mermaid_saved, path),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 从任意 Context 往上找 Activity（离屏取图必须挂在真窗口上）。 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
