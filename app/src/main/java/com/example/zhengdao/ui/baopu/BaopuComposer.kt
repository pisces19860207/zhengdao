// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuComposer：输入框 ＋ 发送/停止按钮）；
// 第二十二批 A 段（2026-10-18 用户「＋附件（曲别针）、相机权限」）：
// **一个「＋」＝ 拍照／相册／选文件三个入口**，拿到的东西先落进工作区再交给抱朴。
package com.example.zhengdao.ui.baopu

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/**
 * 输入区：＋（附件）／输入框／发送（busy 时变停止）。busy 由调用方给（引擎状态）。
 *
 * 附件（第二十二批 A 段）三条口径：
 * 1. **一个「＋」开三个口**：拍照／相册／选文件 —— 不摆三个按钮挤输入框；
 * 2. **先落工作区再交给抱朴**：附件一律复制进 `<工作区>/附件/`，消息里只带**相对路径**
 *    （抱朴能用 `read_file` 读到；用户也能在文件管理器里看见、能备份、能删）；
 * 3. **图片诚实说**：本批还没有视觉通道 ⇒ 图片只存不送，消息里明写「当前通道不能看图」，
 *    绝不假装模型看过了（视觉接线归后续批次）。
 */
// 多选上限：9 个（够用，又不至于把消息刷成一长串）
private const val MAX_ATTACH = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BaopuComposer(
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    // 多选（2026-10-18 用户）：最多 9 个
    var attachList by remember { mutableStateOf<List<BaopuAttachments.Saved>>(emptyList()) }
    var sheetOpen by remember { mutableStateOf(false) }
    // 一句提示（没权限 / 保存失败）；下一次成功或再点一次就换掉
    var note by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    // 回形针的读屏名（自绘图形没有文字，必须给 contentDescription，否则无障碍读成「按钮」）
    val attachDesc = stringResource(R.string.baopu_attach_title)
    // 相机要写入的那个临时 Uri（TakePicture 先给 Uri，回调时才知道成没成）
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    /** 一次选中若干 Uri，能存几个存几个；有失败就报一句（不静默）。 */
    fun saveAll(uris: List<Uri>, fallback: String) {
        if (uris.isEmpty()) return
        val saved = mutableListOf<BaopuAttachments.Saved>()
        var failed = 0
        for (uri in uris) {
            if (attachList.size + saved.size >= MAX_ATTACH) break
            val one = BaopuAttachments.save(ctx, uri, fallback)
            if (one == null) failed++ else saved += one
        }
        if (saved.isNotEmpty()) attachList = attachList + saved
        note = when {
            failed > 0 -> ctx.getString(R.string.baopu_attach_failed)
            uris.size > saved.size -> ctx.getString(R.string.baopu_attach_too_many, MAX_ATTACH)
            else -> null
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) {
            saveAll(listOf(uri), ctx.getString(R.string.baopu_attach_photo_name))
        }
        // 用户取消：不提示、不报错（取消不是失败）
    }
    // 相册：系统照片选择器**多选**（不申请存储读权限）
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACH),
    ) { uris ->
        saveAll(uris, "image")
    }
    // 选文件：也支持多选
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        saveAll(uris, "file")
    }
    val launchCamera = {
        val file = BaopuAttachments.newCameraFile(ctx)
        // authority 与 AndroidManifest 里的 provider 必须一字不差
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".baopu.files", file)
        cameraUri = uri
        takePicture.launch(uri)
    }
    // 声明了 CAMERA 就必须在运行时请求（不请求直接起相机 = SecureException）
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera() else note = ctx.getString(R.string.baopu_attach_camera_denied)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        attachList.forEachIndexed { idx, a ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.baopu_attach_chip, a.relPath, a.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { attachList = attachList.filterIndexed { i, _ -> i != idx } }) {
                    Text(stringResource(R.string.baopu_attach_remove))
                }
            }
        }
        note?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelSmall,
                // P2-11：colorScheme.error 做正文只有 3.55:1，走 zhengdaoErrorText()（门禁钉着）
                color = zhengdaoErrorText(),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth() // imePadding 由 BaopuScreen 根 Column 统一消费（2026-10-18，避免双份）
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 回形针（2026-10-18 用户：「这个+号可以小点，太大了，做个小回形针吧」）：
            // 40dp 的无边框小键，图形是自绘 Canvas（不引图标库、不加 emoji，与玄览/终端那套自绘一致）
            IconButton(
                onClick = { sheetOpen = true },
                enabled = !busy,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(40.dp)
                    .semantics { contentDescription = attachDesc },
            ) {
                PaperclipGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant, side = 20.dp)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.baopu_input_hint)) },
                maxLines = 4,
                enabled = !busy,
            )
            if (busy) {
                // 停止：立刻可点（点停止任何时候都该有用）
                Button(
                    onClick = { onStop() },
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.baopu_stop))
                }
            } else {
                Button(
                    onClick = {
                        val body = text
                        val list = attachList
                        if (body.isNotBlank() || list.isNotEmpty()) {
                            text = ""
                            attachList = emptyList()
                            onSend(withAttachmentHint(ctx, body, list))
                        }
                    },
                    enabled = text.isNotBlank() || attachList.isNotEmpty(),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.baopu_send))
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.baopu_attach_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                TextButton(
                    onClick = {
                        sheetOpen = false
                        requestCamera.launch(android.Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    Text(stringResource(R.string.baopu_attach_camera))
                }
                TextButton(
                    onClick = {
                        sheetOpen = false
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    Text(stringResource(R.string.baopu_attach_gallery))
                }
                TextButton(
                    onClick = {
                        sheetOpen = false
                        // 文本/文档/压缩包都行：`*/*`，让用户自己挑
                        pickFiles.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    Text(stringResource(R.string.baopu_attach_file))
                }
            }
        }
    }
}

/**
 * 回形针小图标（自绘，不引图标库）。
 *
 * 几何（第一版画成了个「0」，2026-10-18 真机当场返工）：外圈「右竖 → 底部半圆 → 左竖」，
 * 顶部再折一个小半圆回到**内侧长臂**，内外间距留够 0.16 宽（18dp 下约 3dp）才看得出是回形针。
 */
@Composable
private fun PaperclipGlyph(tint: androidx.compose.ui.graphics.Color, side: androidx.compose.ui.unit.Dp) {
    Canvas(
        Modifier
            .size(side)
            // 斜 28°：回形针的经典姿态（正着放会被看成「0」）
            .graphicsLayer { rotationZ = -28f },
    ) {
        val w = size.width
        val h = size.height
        fun x(f: Float) = w * f
        fun y(f: Float) = h * f
        val path = Path().apply {
            // 外侧右竖
            moveTo(x(0.74f), y(0.18f))
            lineTo(x(0.74f), y(0.66f))
            // 底部半圆：右 →（经底）→ 左
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    left = x(0.26f), top = y(0.40f), right = x(0.74f), bottom = y(0.90f),
                ),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            // 外侧左竖（比右侧短：上面要给顶部小半圆让位）
            lineTo(x(0.26f), y(0.28f))
            // 顶部小半圆：左 →（经顶）→ 内侧
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    left = x(0.26f), top = y(0.08f), right = x(0.58f), bottom = y(0.48f),
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            // 内侧长臂（回形针的另一根棱；别画过底部弧线，否则看着像穿透）
            lineTo(x(0.58f), y(0.58f))
        }
        // 斜着放（-28°）：回形针的经典姿态，比正着的「U」更好认（第一版正着放，看起来像个 0）
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 把「附件」并进要发出去的那句话。**图片与文件分开说**：本批没有视觉通道，
 * 图片只能进工作区、抱朴看不了 —— 这条必须写进消息里，不然后面它会自己编内容。
 */
internal fun withAttachmentHint(
    ctx: Context,
    body: String,
    list: List<BaopuAttachments.Saved>,
): String {
    if (list.isEmpty()) return body
    // 文件与图片分开说：图片本批看不了，必须明写（不许假装看过）
    val files = list.filterNot { it.isImage }
    val images = list.filter { it.isImage }
    fun describe(items: List<BaopuAttachments.Saved>): String =
        items.joinToString("；") { "${it.relPath}（${it.sizeBytes} B）" }
    val hints = buildList {
        if (files.isNotEmpty()) {
            add(ctx.getString(R.string.baopu_attach_hint_files, files.size, describe(files)))
        }
        if (images.isNotEmpty()) {
            add(ctx.getString(R.string.baopu_attach_hint_images, images.size, describe(images)))
        }
    }
    val hint = hints.joinToString("\n")
    return if (body.isBlank()) hint else body + "\n\n" + hint
}
