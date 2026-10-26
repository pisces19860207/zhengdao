// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **答案浮卡壳**（派工单 §1 三态、§2.5 通用壳、§2.6 校验样式）。
//
// ## 一件东西只做一次
// 这个文件是「壳」：它只认 [ResultCardSpec]（组／行／动作／可拨），不认识任何业务字段。
// 想加一个场景 ⇒ 加一份 `assets/baopu/cards/<id>.json` ＋ 中英两条文案 ＋ 文案表一行，
// **本文件的排版与判据一行都不用改**（`ResultCardTemplateGateTest` 钉着这条）。
//
// ## 三态的位置为什么在消息区里
// 派工单 §1① 要求「背后正文仍可见、输入框不被挡」（164 判据）。最省事也最可靠的做法是
// **不靠计算、靠物理位置**：本组件挂在 `BaopuScreen` 消息区的 `Box` 里，而那个 `Box` 只到
// 输入框上方——卡再高也压不到 composer（与 106 批计划区同一条理由）。
// 用 `Dialog` 做浮卡则相反：`Dialog` 占的是整屏窗口，键盘与输入框一律被它罩住。
//
// ## 壳里没有任何「提交」
// 派工单三条硬边界之一：答案卡只读。所以这里动作只有「复制全部 / 详情 / 追问一句」，
// 四个动作 id 是闭集（`CardActions.KNOWN_ACTION_IDS`），模板写别的 id 在解析期就红。
// [BaopuResultSubmitBar] 是给**将来的输入类卡**留的样式件（§2.6），结果卡一次都不调它——
// 门禁测试扫源码钉住这一条，免得哪天顺手把提交按钮加进答案卡。
//
// ## 号码：拨号盘，不外呼
// 与 195 同一处置：`ACTION_DIAL` 只把号码递进系统拨号盘，点一下不会真的拨出去；
// 长按＝复制。这一层不判「是不是号码」（`CardRow.dial` 由 producer 用 195 的判据算好），
// 也不做任何号码打码（§1.8 冻结：原样显示，日志不记）。
package com.example.zhengdao.ui.baopu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.cards.CardActions
import com.example.zhengdao.baopu.cards.CardPhase
import com.example.zhengdao.baopu.cards.CardRow
import com.example.zhengdao.baopu.cards.ResultCardBody
import com.example.zhengdao.baopu.cards.ResultCardBudget
import com.example.zhengdao.baopu.cards.ResultCardSpec
import com.example.zhengdao.baopu.cards.ResultCardState
import com.example.zhengdao.baopu.cards.SubmitPlan
import com.example.zhengdao.ui.theme.rChip
import com.example.zhengdao.ui.theme.rSheet
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/** 浮卡占消息区高度的上限（派工单 §2.5：单卡片高度 ≤ 屏 60%）。 */
internal const val RESULT_CARD_MAX_FRACTION = 0.6f

/** 一行字段的视觉高（可拨的那些要 48dp 热区，见 [ResultRowLine]）。 */
internal const val RESULT_CARD_ROW_DP = 44

/** 卡头/卡底按钮的命中热区（与壳件同一口径 BAO_ROW_MIN_HEIGHT_DP）。 */
internal const val RESULT_CARD_TOUCH_DP = 48

/**
 * 卡区宿主：三态在这里分工（展开／小条／整组收起），消息区里就挂它一个。
 *
 * 位置由调用方给（`BaopuScreen` 的消息区 `Box`，`Modifier.fillMaxSize()`），
 * 本组件只负责「对齐到底部」与「不超过 60%」。
 *
 * @param onCopyAll 复制全部（producer 负责真复制，本组件只把卡交出去）
 * @param onFollowUp 追问一句（回到提问口子；结果卡本身不弹任何需要回填的控件）
 */
@Composable
internal fun BaopuResultCardHost(
    state: ResultCardState,
    onCollapse: () -> Unit,
    onExpand: (Int) -> Unit,
    onCollapseAll: () -> Unit,
    onRestore: () -> Unit,
    onCopyAll: (ResultCardSpec) -> Unit,
    onFollowUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.cards.isEmpty()) return
    val res = LocalResources.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxSheetHeight = maxHeight * RESULT_CARD_MAX_FRACTION
        // 先取成局部量：往里走一层 Column/Box，隐式接收者就换成别的 scope 了（maxHeight 取不到）
        val availableDp = maxHeight.value.toInt()
        when (state.phase) {
            // ① 展开：轻遮罩（点外面＝收起）＋ 贴底的卡。
            CardPhase.EXPANDED -> {
                val active = state.active ?: return@BoxWithConstraints
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
                        .clickable(
                            // 读屏也要能「明知点哪儿」：这一层的动作就是收起
                            onClickLabel = res.getString(R.string.baopu_card_collapse),
                            onClick = onCollapse,
                        )
                        .semantics { contentDescription = res.getString(R.string.baopu_card_sheet_desc) },
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    // 多张结果：其余折成小条（点一下就换成它），最上面一张才是展开的那张。
                    state.cards.forEachIndexed { i, card ->
                        if (i == state.activeIndex) return@forEachIndexed
                        ResultStripRow(
                            text = stripTextOf(card),
                            onOpen = { onExpand(i) },
                            onDismissAll = null,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(rSheet),
                        tonalElevation = 3.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxSheetHeight),
                    ) {
                        ResultSheetBody(
                            card = active,
                            availableHeightDp = availableDp,
                            onCollapse = onCollapse,
                            onCopyAll = { onCopyAll(active) },
                            onFollowUp = onFollowUp,
                        )
                    }
                }
            }

            // ② 收起：正文里留一行小条（点它再开，数据还是内存里那一份，不重查）。
            CardPhase.COLLAPSED -> {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    state.cards.forEachIndexed { i, card ->
                        ResultStripRow(
                            text = stripTextOf(card),
                            onOpen = { onExpand(i) },
                            onDismissAll = if (i == 0) onCollapseAll else null,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // ③ 整组收起：只剩一句计数，点开回来（卡不删——负例②）。
            CardPhase.HIDDEN -> {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(rChip),
                        modifier = Modifier
                            .heightIn(min = RESULT_CARD_TOUCH_DP.dp)
                            .clickable(onClick = onRestore),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = res.getString(R.string.baopu_card_hidden_count, state.hiddenCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = res.getString(R.string.baopu_card_expand),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 小条那一行（标题 ＋ 条数；同一份格式化在 `ResultCardBody.stripLine`，避免两处措辞）。 */
@Composable
private fun stripTextOf(card: ResultCardSpec): String {
    val res = LocalResources.current
    return remember(card, res) {
        ResultCardBody.stripLine(card) { key -> baopuCardString(key, res) }
    }
}

/** 收起态的一行：整行可点＝再开；尾部「展开」是同一件事的显式入口；`×` ＝ 整组收起。 */
@Composable
private fun ResultStripRow(text: String, onOpen: () -> Unit, onDismissAll: (() -> Unit)?) {
    val res = LocalResources.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(rChip),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = RESULT_CARD_TOUCH_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = RESULT_CARD_TOUCH_DP.dp)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "\u203a",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.baopu_card_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (onDismissAll != null) {
                Box(
                    Modifier
                        .widthIn(min = RESULT_CARD_TOUCH_DP.dp)
                        .heightIn(min = RESULT_CARD_TOUCH_DP.dp)
                        .clickable(onClick = onDismissAll)
                        .semantics { contentDescription = res.getString(R.string.baopu_card_dismiss_all) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "\u00d7",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 卡身：标题条 ＋ 字段组 ＋ 「另有 N 项」＋ 只读动作条。
 *
 * @param availableHeightDp 消息区高度（用来算「60% 能摆几行」，超出先折）
 */
@Composable
private fun ResultSheetBody(
    card: ResultCardSpec,
    availableHeightDp: Int,
    onCollapse: () -> Unit,
    onCopyAll: () -> Unit,
    onFollowUp: () -> Unit,
) {
    val res = LocalResources.current
    val ctx = LocalContext.current
    var detailOpen by remember(card.templateId) { mutableStateOf(false) }
    // 单行级别的「展开」（collapsible 行，如学生卡的「解题步骤」默认折起）
    val rowOpen = remember(card.templateId) { mutableStateMapOf<String, Boolean>() }
    var note by remember(card.templateId) { mutableStateOf<String?>(null) }

    // 预算：不展开详情时按 60% 折行；点了详情就整卡可滚（外层 heightIn 已封顶）
    val folded = remember(card, availableHeightDp, detailOpen) {
        if (detailOpen) ResultCardBudget.Folded(card, 0)
        else ResultCardBudget.fold(card, ResultCardBudget.maxRows(availableHeightDp))
    }

    fun copyText(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("card", text))
    }

    fun dial(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        // 卡头：标题 ＋ 收起（视觉 44／热区 48）
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RESULT_CARD_TOUCH_DP.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (card.itemCount > 1) {
                Text(
                    text = res.getString(R.string.baopu_card_count, card.itemCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(
                onClick = onCollapse,
                modifier = Modifier.heightIn(min = RESULT_CARD_TOUCH_DP.dp),
            ) { Text(stringResource(R.string.baopu_card_collapse)) }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            folded.card.groups.forEach { group ->
                group.title?.let { t ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = t,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                group.rows.forEach { row ->
                    ResultRowLine(
                        row = row,
                        open = rowOpen[row.key] == true,
                        onToggle = { rowOpen[row.key] = rowOpen[row.key] != true },
                        onDial = { dial(it) },
                        onCopy = { v -> copyText(v); note = res.getString(R.string.baopu_card_copied) },
                    )
                }
            }
            if (folded.hiddenRows > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = RESULT_CARD_TOUCH_DP.dp)
                        .clickable { detailOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = res.getString(R.string.baopu_card_others_rows, folded.hiddenRows),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // 动作条：只读（复制全部／详情／追问一句），闭集外的 id 在解析期已被拒
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                card.actions.forEach { action ->
                    TextButton(
                        onClick = {
                            when (action.id) {
                                CardActions.COPY_ALL -> {
                                    copyText(copyAllTextOf(card, res))
                                    note = res.getString(R.string.baopu_card_copied)
                                    onCopyAll()
                                }
                                CardActions.DETAIL -> detailOpen = !detailOpen
                                CardActions.FOLLOW_UP -> onFollowUp()
                            }
                        },
                        modifier = Modifier.heightIn(min = RESULT_CARD_TOUCH_DP.dp),
                    ) { Text(action.label) }
                }
            }
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (note == null) Spacer(Modifier.height(8.dp))
        }
    }
}

/** 一行字段：label 在左、值在右；可拨的值点＝拨号盘、长按＝复制（未填不可点）。 */
@Composable
private fun ResultRowLine(
    row: CardRow,
    open: Boolean,
    onToggle: () -> Unit,
    onDial: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val res = LocalResources.current
    Row(
        Modifier
            .fillMaxWidth()
            // 可拨的行要 48dp 热区（视觉不放大：多数行仍是 44dp）
            .heightIn(min = if (row.dial != null) RESULT_CARD_TOUCH_DP.dp else RESULT_CARD_ROW_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(2f),
        )
        Spacer(Modifier.width(8.dp))
        when {
            // 默认折起的那一行（学生卡的「解题步骤」）：先给一个显式入口，点开才露内容
            row.collapsible && !open -> TextButton(
                onClick = onToggle,
                modifier = Modifier
                    .weight(3f)
                    .heightIn(min = RESULT_CARD_TOUCH_DP.dp),
            ) { Text(stringResource(R.string.baopu_card_expand)) }

            row.dial != null -> Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(3f)
                    .pointerInput(row.key, row.dial) {
                        detectTapGestures(
                            onTap = { onDial(row.dial) },
                            onLongPress = { onCopy(row.value) },
                        )
                    }
                    .semantics { contentDescription = res.getString(R.string.baopu_card_dial_desc, row.value) },
            )

            else -> Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(3f),
            )
        }
    }
}

/** 「复制全部」那段文字（label：value 一行一条；分隔符走 strings，不在代码里写全角冒号）。 */
internal fun copyAllTextOf(card: ResultCardSpec, res: android.content.res.Resources): String =
    card.groups.joinToString("\n\n") { g ->
        val head = g.title?.let { "$it\n" }.orEmpty()
        head + g.rows.joinToString("\n") { r ->
            res.getString(R.string.baopu_card_copy_pair, r.label, r.value)
        }
    }

// ── 校验样式（§2.6；给输入类卡复用，答案卡一次都不调）────────────────────────

/**
 * 红框 ＋ 一句人话 ＋ 点一下跳到那一项。
 *
 * 三件事分开给：**红框**由这张框负责（[isError]），**人话**由调用方从 `CardValidate.messageOf`
 * 取（永远是闭集里的一句，不会空白），**跳转**由 [onJump] 交给卡的滚动逻辑。
 */
@Composable
internal fun BaopuCardFieldFrame(
    isError: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(rChip)
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (isError) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.error, shape)
                } else {
                    Modifier
                },
            ),
    ) { content() }
}

/** 红框下面那句人话（点它＝滚到这一项）。 */
@Composable
internal fun BaopuCardFieldError(message: String, onJump: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.labelSmall,
        // P2-11：error 色 3.55:1 不达正文 4.5:1，走专用的深一档
        color = zhengdaoErrorText(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RESULT_CARD_TOUCH_DP.dp)
            .clickable(onClick = onJump),
    )
}

/**
 * 提交条：**置灰 ＋ 顶部小计**（「还差 2 项」），点小计跳到第一处错。
 *
 * @param plan 由 `CardValidate.planOf(卡自己算的错)` 得来——校验逻辑不在这里（§2.6 归属）
 */
@Composable
internal fun BaopuResultSubmitBar(
    plan: SubmitPlan,
    submitLabel: String,
    onSubmit: () -> Unit,
    onJump: (String) -> Unit,
) {
    val res = LocalResources.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RESULT_CARD_TOUCH_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 小计：不点一次提交就能看见「还差几项」，点它滚到第一处
        if (plan.needed > 0 && plan.summaryKey != null && plan.firstErrorKey != null) {
            val key = plan.firstErrorKey
            Text(
                text = res.getString(R.string.baopu_card_error_summary, plan.needed),
                style = MaterialTheme.typography.labelMedium,
                color = zhengdaoErrorText(),
                modifier = Modifier
                    .heightIn(min = RESULT_CARD_TOUCH_DP.dp)
                    .clickable { onJump(key) }
                    .padding(vertical = 14.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        TextButton(
            onClick = onSubmit,
            enabled = plan.enabled,
            modifier = Modifier.heightIn(min = RESULT_CARD_TOUCH_DP.dp),
        ) { Text(submitLabel) }
    }
}
