// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuComposer：输入框 ＋ 发送/停止按钮）；
// 第二十二批 A 段（2026-10-18 用户「＋附件（曲别针）、相机权限」）：
// **一个「＋」＝ 拍照／相册／选文件三个入口**，拿到的东西先落进工作区再交给抱朴；
// 第三十批 B 段（2026-10-18 用户「传的图片排的好乱啊，并排不好吗？」＋「三行纯文字
// 跟『图片』这件事完全不搭」）：附件条改成**横排缩略图 chip ＋ 一行放不下就横向滚动**。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.BaopuWorkspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    /**
     * 发送回调：`(要发出去的文本, 本轮附件)`。
     *
     * 第二十四批 B 段把签名从 `(String)` 扩成两参：图片要**随消息落成
     * `part.kind='image'`**（气泡里渲缩略图），而文本里那句 hint **照样保留**
     * （判据 B2——有了图片块不等于模型一定看得到，接线没通之前那句提示仍然是真的）。
     */
    onSend: (String, List<BaopuAttachments.Saved>) -> Unit,
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
        // 附件条（第三十批 B 段）：**横排** chip，一行放不下就**横向滚动**。
        // 不许换行——换行会把输入区顶高，键盘一弹就吃掉正文（派工单点名）。
        if (attachList.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 先裁边界再滚动：chip 越界时不许糊到屏幕边外
                    .clip(RectangleShape)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(ATTACH_GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachList.forEachIndexed { idx, a ->
                    val remove = { attachList = attachList.filterIndexed { i, _ -> i != idx } }
                    // 图片 = 缩略图方块；文件 = 一条文字 chip（文件名＋移除），两者视觉分开
                    if (a.isImage) AttachThumbChip(a, remove) else AttachFileChip(a, remove)
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
                            // hint 照旧拼进文本（B2：不许因为有了 image part 就删提示）
                            onSend(withAttachmentHint(ctx, body, list), list)
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

/** 附件 chip 之间的间距（唯一真源，与图片区的 `IMAGE_GAP_DP` 同一口径：改一处全改）。 */
private const val ATTACH_GAP_DP = 8

/** 图片 chip 的方块边长（派工单指定 56dp）。 */
private val ATTACH_THUMB_DP = 56.dp

/** chip 圆角（8dp，与气泡里图片格同一档）。 */
private val ATTACH_CHIP_RADIUS_DP = 8.dp

/**
 * 右上角「×」的热区。
 *
 * 28dp —— **明知低于 M3 的 48dp 触摸目标**：56dp 的方块上再套 48dp 的移除区，
 * × 会盖掉半张图。派工单要的是「右上角小 × 移除」，所以按"看得清"取舍，
 * 并把这条列进报告的真机手感项（蓝总管摸完再决定要不要把 chip 放大）。
 *
 * UI 第二批（2026-10-19 §1）复核结论：**这一处按派工单停下来报告，不改。**
 * 两条理由都在 [thumbTapBandDp] 的算术里：
 * 1. 框架本来会把小目标的触摸判定扩到 48dp（`ViewConfiguration.minimumTouchTargetSize`），
 *    但 Compose 的优先级条款是「别人的**直接命中**赢过我的最小触摸扩展命中」——
 *    chip 那层 `combinedClickable`（点缩略图看路径）就是直接命中 ⇒ × 扩不出去；
 * 2. 真把 48dp 做进布局，缩略图只剩 [thumbTapBandDp] ＝ 8dp 宽的 L 形边，
 *    「点开看路径」等于废掉。要么放大方块（视觉变）、要么把 × 挪出方块（版式变），
 *    两条都超出本单「视觉尺寸一个字不换」的纪律 ⇒ 交蓝总管定。
 */
private val ATTACH_REMOVE_DP = 28.dp

/**
 * 缩略图还剩多少能点的边：方块边长 − 移除盒边长。
 *
 * 剩下的是一条 8dp 宽的 L 形（左边一条＋底边一条），不是 48dp 的方块 ⇒ 判定"放不下"。
 * 门禁 H3 钉这条算术：谁想把 × 直接套 48dp，先让这条算术说明白缩略图还剩什么。
 */
internal fun thumbTapBandDp(chipDp: Int, hitDp: Int): Int = chipDp - hitDp

/**
 * 56dp chip 的采样目标像素：56 × 3.5（真机 HONOR PGT-AN10 实测 density 3.5）= 196。
 * 复用图片区那份 [decodeSampledImage]，不另写解码。
 */
private const val CHIP_TARGET_PX = 196

/**
 * 图片附件 chip：56dp 方块缩略图 ＋ 右上角小 ×。
 *
 * 三条口径：
 * 1. **缩略图只读工作区文件**（同一份 [decodeSampledImage]，不复制、不落盘）；
 * 2. **路径不常驻显示**：点一下才弹「附件：相对路径（字节数）」、长按复制相对路径
 *    ——语义仍是**相对路径**（判据 B1 那条过滤一个字没动）；
 * 3. **图没了要说**：选完又被删掉的图，这一格显示「图片找不到了」，不留空白方块。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachThumbChip(a: BaopuAttachments.Saved, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val relPath = a.relPath
    val removeDesc = stringResource(R.string.baopu_attach_remove)
    val file = remember(relPath) { File(BaopuWorkspace.hostDir(ctx), relPath) }
    val bmp by produceState<Bitmap?>(initialValue = null, relPath) {
        value = withContext(Dispatchers.IO) { decodeSampledImage(file, CHIP_TARGET_PX) }
    }
    Box(
        modifier = Modifier
            .size(ATTACH_THUMB_DP)
            .clip(RoundedCornerShape(ATTACH_CHIP_RADIUS_DP))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { showAttachmentPath(ctx, a) },
                onLongClick = { copyRelPath(ctx, relPath) },
            ),
    ) {
        val shown = bmp
        when {
            shown != null -> Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(R.string.baopu_attach_thumb_desc, relPath),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 还没解完 ⇒ 留底色（短暂）；文件确实不在 ⇒ 明说（持久）
            !imageFilePresent(file) -> Text(
                text = stringResource(R.string.baopu_image_missing_short),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(4.dp),
            )
        }
        // 移除：文字符号 ×（不引图标库、不新自绘 glyph）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(ATTACH_REMOVE_DP)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable(onClick = onRemove)
                .semantics { contentDescription = removeDesc },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * 非图片附件 chip（txt/pdf/…）：一条**文字** chip，只显示文件名 ＋「移除」。
 *
 * 与图片 chip 分开：方块＝图、条＝文件，一眼能数清"我传了几张图"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachFileChip(a: BaopuAttachments.Saved, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        shape = RoundedCornerShape(ATTACH_CHIP_RADIUS_DP),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = a.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 10.dp, end = 4.dp)
                    .combinedClickable(
                        onClick = { showAttachmentPath(ctx, a) },
                        onLongClick = { copyRelPath(ctx, a.relPath) },
                    ),
            )
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.baopu_attach_remove))
            }
        }
    }
}

/** 点一下 chip 才说「路径（字节数）」——原来这三行常驻，把输入区顶成一屏字。 */
private fun showAttachmentPath(ctx: Context, a: BaopuAttachments.Saved) {
    Toast.makeText(
        ctx,
        ctx.getString(R.string.baopu_attach_chip, a.relPath, a.sizeBytes),
        Toast.LENGTH_SHORT,
    ).show()
}

/** 长按复制**相对路径**（跟气泡里的图片格同一语义：剪贴板里不许出现本机绝对路径）。 */
private fun copyRelPath(ctx: Context, relPath: String) {
    val cm = ctx.getSystemService<ClipboardManager>()
    cm?.setPrimaryClip(ClipData.newPlainText("baopu-attach-path", relPath))
    Toast.makeText(ctx, ctx.getString(R.string.baopu_attach_path_copied), Toast.LENGTH_SHORT).show()
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
 * 附件 → `part.kind='image'` 的相对路径（第二十四批 B 段，**纯函数**、JVM 直测）。
 *
 * ## 只收相对路径（判据 B1）
 *
 * 落库的 payload 只能是**相对工作区**的路径：
 * - 存绝对路径 ⇒ 用户换工作区/换机型后全部失效，而且本机路径不该进数据库（会被
 *   导出、被备份、被别的进程读到）；
 * - 存 `data:` base64 ⇒ 一张 2 MB 图变 2.7 MB 文本，库会被撑爆，且同一张图被存两遍
 *   （文件在工作区、副本在库里）。
 *
 * 所以这里做一道**过滤**而不是"信任调用方"：`/` 开头、`data:`、`file:`、`content:`、
 * 含 `/storage/`、含盘符的一律丢。丢的时候不留半条脏数据。
 */
internal object AttachmentParts {

    /** 取本轮该落成 image part 的相对路径（保持原顺序）。 */
    fun imageRelPaths(list: List<BaopuAttachments.Saved>): List<String> =
        list.asSequence()
            .filter { it.isImage }
            .map { it.relPath.trim() }
            .filter { it.isNotEmpty() }
            .filter { isRelativeSafe(it) }
            .toList()

    /**
     * 这条路径能不能当 payload（B1 的守门员）。
     *
     * 只认"相对路径"这四个字的本义：不以 `/` 开头、不带 scheme、不含挂载点、
     * 不是 Windows 盘符。`..` 也不收 —— 相对路径里的上跳会把读图动作带出工作区。
     */
    fun isRelativeSafe(rel: String): Boolean {
        if (rel.isBlank()) return false
        if (rel.startsWith("/")) return false
        if (rel.contains("..")) return false
        if (rel.contains("\\")) return false
        listOf("data:", "file:", "content:", "http:", "https:").forEach {
            if (rel.startsWith(it, ignoreCase = true)) return false
        }
        if (rel.contains("/storage/")) return false
        if (Regex("""^[A-Za-z]:""").containsMatchIn(rel)) return false
        return true
    }
}

/**
 * 把「附件」并进要发出去的那句话。**图片与文件分开说**：本批没有视觉通道，
 * 图片只能进工作区、抱朴看不了 —— 这条必须写进消息里，不然后面它会自己编内容。
 *
 * 第二十四批 B 段：图片现在**同时**落成 image part（气泡里能看缩略图），但这句
 * hint **不删**（判据 B2）。理由：图片落库 ≠ 模型看得到 —— 组装侧（ContextAssembler
 * 按视觉能力裁图）归内核批次接线；在那之前"看不见图"仍然是事实，删了提示就是骗人。
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
