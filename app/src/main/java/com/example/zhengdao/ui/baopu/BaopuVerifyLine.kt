// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十七批 · 验收结论那一行的**渲染层**（话术键 → 屏上的字）。
//
// ## 这一层只管两件事
// 1. `VerifyPhrase` 键名 → `R.string.*` 的映射（唯一真源就是 [VERIFY_PHRASE_RES]）；
// 2. 把 [VerifyLine] 摆成**一行**：结论＋依据（整行可点＝点开证据）＋一行出路按钮。
// 判定、取数、三态一个都不在这里——那是 `baopu/verify/VerifyNarrative.kt` 与 302/332/378 的地盘。
//
// ## 为什么是表，不是 when
// 212 批那张卡文案表用 `when (key)`；这里的键照抄会多一条 detekt `CyclomaticComplexMethod`
// （阈值 15，本批预算是**净零**）。所以是 `Map`。代价是漏一个键编译器不报错，于是
// `VerifyVisibleGateTest` 顶上：键表与 `VerifyPhrase` 一一对应、中英两份 XML 各有同名
// `<string>`、值里没有内部词、占位符条数按表钉死——任何一边漏了当场红，而不是等用户
// 看见一串键名（[phraseText] 找不到键时**回键名本身**，就是为了这条能在测试里被看见）。
//
// ## 代码里不许写死一句中文
// 派工单前置②「文案走 strings，判据钉 key」要在**代码这一头**也成立：只要这里出现一句
// 硬编码中文，「换措辞不红门禁」就成了假话。连顿号（[VerifyPhrase.JOIN_OR]）与行分隔符
// （[VerifyPhrase.LINE_SEP]）都从 strings 取；浮层限高与圆角**复用** 139 批那两把尺
// （`PROCESS_DETAIL_MAX_DP`／`rSheet`），不另立数字——`check-radius-tokens.py` 也不放行裸写。
package com.example.zhengdao.ui.baopu

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.verify.VerifyAction
import com.example.zhengdao.baopu.verify.VerifyLine
import com.example.zhengdao.baopu.verify.VerifyNarrative
import com.example.zhengdao.baopu.verify.VerifyPhrase
import com.example.zhengdao.ui.theme.rSheet

// ── 键名 → 资源（唯一映射处；门禁按这张表钉判据①「三态各用哪个 key」）────────

private val VERIFY_PHRASE_RES: Map<VerifyPhrase, Int> = mapOf(
    VerifyPhrase.PASS to R.string.baopu_verify_pass,
    VerifyPhrase.FAIL_DETAIL to R.string.baopu_verify_fail_detail,
    VerifyPhrase.FAIL_BARE to R.string.baopu_verify_fail_bare,
    VerifyPhrase.UNVERIFIABLE_DETAIL to R.string.baopu_verify_unverifiable_detail,
    VerifyPhrase.UNVERIFIABLE_BARE to R.string.baopu_verify_unverifiable_bare,
    VerifyPhrase.PAGE_READ to R.string.baopu_verify_page_read,
    VerifyPhrase.NO_EVIDENCE to R.string.baopu_verify_no_evidence,
    VerifyPhrase.NEEDS_TAP to R.string.baopu_verify_needs_tap,
    VerifyPhrase.NEEDS_TAP_NOWHERE to R.string.baopu_verify_needs_tap_nowhere,
    VerifyPhrase.BASIS_FILES to R.string.baopu_verify_basis_files,
    VerifyPhrase.BASIS_GATE to R.string.baopu_verify_basis_gate,
    VerifyPhrase.BASIS_READ to R.string.baopu_verify_basis_read,
    VerifyPhrase.BASIS_TAP to R.string.baopu_verify_basis_tap,
    VerifyPhrase.BASIS_BARE to R.string.baopu_verify_basis_bare,
    VerifyPhrase.EVIDENCE_TITLE to R.string.baopu_verify_evidence_title,
    VerifyPhrase.EVIDENCE_MORE to R.string.baopu_verify_evidence_more,
    VerifyPhrase.EVIDENCE_NONE to R.string.baopu_verify_evidence_none,
    VerifyPhrase.TOGGLE_OPEN to R.string.baopu_verify_toggle_open,
    VerifyPhrase.TOGGLE_CLOSE to R.string.baopu_verify_toggle_close,
    VerifyPhrase.ACT_RETRY to R.string.baopu_verify_act_retry,
    VerifyPhrase.ACT_SWAP to R.string.baopu_verify_act_swap,
    VerifyPhrase.ACT_XUANLAN to R.string.baopu_verify_act_xuanlan,
    VerifyPhrase.ACT_AGAIN to R.string.baopu_verify_act_again,
    VerifyPhrase.ACT_NOTE to R.string.baopu_verify_act_note,
    VerifyPhrase.ACT_GO_TAP to R.string.baopu_verify_act_go_tap,
    VerifyPhrase.SWAP_PREFILL to R.string.baopu_verify_swap_prefill,
    VerifyPhrase.JOIN_OR to R.string.baopu_verify_join_or,
    VerifyPhrase.LINE_SEP to R.string.baopu_verify_line_sep,
    VerifyPhrase.NOTE_ITEM to R.string.baopu_verify_note_item,
    VerifyPhrase.NOTE_SOURCE to R.string.baopu_verify_note_source,
    VerifyPhrase.NOTE_DONE to R.string.baopu_verify_note_done,
    VerifyPhrase.NOTE_FAIL to R.string.baopu_verify_note_fail,
    VerifyPhrase.DETAIL_NONE to R.string.baopu_verify_detail_none,
    VerifyPhrase.ITEM_PATH_SET to R.string.baopu_verify_item_path_set,
    VerifyPhrase.ITEM_BYTE_IDENTICAL to R.string.baopu_verify_item_byte_identical,
    VerifyPhrase.ITEM_NO_ESCAPE to R.string.baopu_verify_item_no_escape,
    VerifyPhrase.ITEM_LEDGER_COMPLETE to R.string.baopu_verify_item_ledger_complete,
    VerifyPhrase.ITEM_EXISTS to R.string.baopu_verify_item_exists,
    VerifyPhrase.ITEM_STRUCTURE to R.string.baopu_verify_item_structure,
    VerifyPhrase.ITEM_USER_CHECKLIST to R.string.baopu_verify_item_user_checklist,
    VerifyPhrase.ITEM_COMPILE to R.string.baopu_verify_item_compile,
    VerifyPhrase.ITEM_TESTS_GREEN to R.string.baopu_verify_item_tests_green,
    VerifyPhrase.ITEM_DIFF_IN_SCOPE to R.string.baopu_verify_item_diff_in_scope,
    VerifyPhrase.ITEM_BUDGET to R.string.baopu_verify_item_budget,
    VerifyPhrase.ITEM_SHAPE to R.string.baopu_verify_item_shape,
    VerifyPhrase.ITEM_SAMPLE_5 to R.string.baopu_verify_item_sample_5,
    VerifyPhrase.ITEM_REOPENABLE to R.string.baopu_verify_item_reopenable,
)

private fun phraseText(phrase: VerifyPhrase, res: Resources, vararg args: String): String {
    val id = VERIFY_PHRASE_RES[phrase] ?: return phrase.key
    return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
}

/**
 * 这一句话术要喂的那个参数位（**只有**带 %1$s 的那几键拿得到东西）。
 *
 * 缺项那两键走 [verifyItemDetail]：一样都点不出来时填的是「没点到具体哪一条」那句，
 * 不是空串——空占位符上屏就是「没验过：缺 。要我再做一次吗」（负例③反面那种不是人话）。
 * 数目那几键取 [VerifyLine.numbers] 的第一位；`numbers` 为空 ⇒ 空串，那时选的根本不是
 * 这些键（[VerifyNarrative] 的 `when` 保证：没读数就退成不带参数的
 * [VerifyPhrase.BASIS_BARE]）。
 */
private fun argOf(phrase: VerifyPhrase, line: VerifyLine, res: Resources): String = when (phrase) {
    VerifyPhrase.FAIL_DETAIL, VerifyPhrase.UNVERIFIABLE_DETAIL, VerifyPhrase.NOTE_ITEM ->
        verifyItemDetail(line, res)

    VerifyPhrase.NEEDS_TAP, VerifyPhrase.BASIS_READ -> line.place
    VerifyPhrase.BASIS_FILES, VerifyPhrase.BASIS_GATE ->
        line.numbers.firstOrNull()?.toString().orEmpty()

    else -> ""
}

/** 一句话术 → 屏上的字（参数位由 [argOf] 取；不需要参数的键原样取句）。 */
internal fun verifyText(phrase: VerifyPhrase, line: VerifyLine, res: Resources): String {
    val arg = argOf(phrase, line, res)
    return if (arg.isEmpty()) phraseText(phrase, res) else phraseText(phrase, res, arg)
}

/** 缺项名串（「写出来的文件、一笔一笔的文件账」；顿号本身也在 strings 里）。 */
internal fun verifyItemNames(line: VerifyLine, res: Resources): String =
    line.items.joinToString(phraseText(VerifyPhrase.JOIN_OR, res)) { phraseText(it, res) }

/** 缺项那一位填什么：一条都没点出来时那句实话（不留空占位符）。 */
internal fun verifyItemDetail(line: VerifyLine, res: Resources): String =
    if (line.items.isEmpty()) phraseText(VerifyPhrase.DETAIL_NONE, res) else verifyItemNames(line, res)

internal fun verifyConclusionText(line: VerifyLine, res: Resources): String =
    verifyText(line.conclusion, line, res)

internal fun verifyBasisText(line: VerifyLine, res: Resources): String =
    verifyText(line.basis, line, res)

/**
 * 屏上那一行的全文：`结论｜依据`（判据②「一轮最多一行」在这一处就是**一行**；
 * 依据后面跟着的证据不在这一行里，点开才看）。
 */
internal fun verifyRowText(line: VerifyLine, res: Resources): String =
    verifyConclusionText(line, res) + phraseText(VerifyPhrase.LINE_SEP, res) + verifyBasisText(line, res)

/** 证据浮层摆出来的几行（超出 [VerifyNarrative.MAX_EVIDENCE_ROWS] 的进 [verifyEvidenceFootnote]）。 */
internal fun verifyEvidenceRows(line: VerifyLine, res: Resources): List<String> =
    line.evidence.take(VerifyNarrative.MAX_EVIDENCE_ROWS)

/** 浮层脚注：没证据 ⇒ 明说；有但没摆完 ⇒ 「还有 N 条」；摆完了 ⇒ 空串（渲染层不出这一行）。 */
internal fun verifyEvidenceFootnote(line: VerifyLine, res: Resources): String {
    if (line.evidence.isEmpty()) return phraseText(VerifyPhrase.EVIDENCE_NONE, res)
    val more = line.evidence.size - verifyEvidenceRows(line, res).size
    return if (more > 0) phraseText(VerifyPhrase.EVIDENCE_MORE, res, more.toString()) else ""
}

/** 「就这样，先记下来」写进未办成单的那条摘要（541 的账、565 的看板；判据④）。 */
internal fun verifyNoteText(line: VerifyLine, res: Resources): String =
    verifyText(VerifyPhrase.NOTE_ITEM, line, res)

/** 那条待办挂的来源名（同一个口子给 541 的 `sourceId`）。 */
internal fun verifyNoteSourceText(res: Resources): String = phraseText(VerifyPhrase.NOTE_SOURCE, res)

/** 记上了的回执（按钮点下去必须有反应，负例④）。 */
internal fun verifyNoteDoneText(res: Resources): String = phraseText(VerifyPhrase.NOTE_DONE, res)

/** 没记上的回执（541 自己给了原因就用它的原因；这句是**写盘抛异常**那种没原因可引的兜底）。 */
internal fun verifyNoteFailText(res: Resources): String = phraseText(VerifyPhrase.NOTE_FAIL, res)

/** 「换个做法」替她把话起个头（只预填进输入框，发不发由她）。 */
internal fun verifySwapPrefillText(res: Resources): String = phraseText(VerifyPhrase.SWAP_PREFILL, res)

internal fun verifyActionText(action: VerifyAction, res: Resources): String = phraseText(action.label, res)

// ── 渲染 ──────────────────────────────────────────────────────────────────

/**
 * 每条回答尾巴上的**那一行**验收结论（挂在过程条后面，同一轮的同一处）。
 *
 * 摆法照 139 批的过程条：整行可点（[BaopuRecordRow] 视觉 44dp／热区 48dp 同一件），
 * 点开是浮层明细——**不推正文**；下面一行的出路按钮才是"怎么办"（§二 三类各有出路）。
 * 出路一个都不在这里执行：只发回调，去处由控制器给（判据③在控制器与门禁两头钉）。
 */
@Composable
internal fun BaopuVerifyLineRow(
    line: VerifyLine,
    onAction: (VerifyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    var open by remember(line) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            BaopuRecordRow(
                text = verifyRowText(line, res),
                onClick = { open = !open },
                onClickLabel = stringResource(
                    if (open) {
                        VERIFY_PHRASE_RES.getValue(VerifyPhrase.TOGGLE_CLOSE)
                    } else {
                        VERIFY_PHRASE_RES.getValue(VerifyPhrase.TOGGLE_OPEN)
                    },
                ),
            )
            if (open) VerifyEvidenceSheet(line, res, onDismiss = { open = false })
        }
        if (line.actions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                line.actions.forEach { action ->
                    TextButton(
                        onClick = { onAction(action) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = verifyActionText(action, res),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 证据浮层：文件路径/账行逐条摆，标题与脚注都来自 strings（限高与圆角同 139 批那一族）。 */
@Composable
private fun VerifyEvidenceSheet(line: VerifyLine, res: Resources, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(rSheet),
        modifier = Modifier.widthIn(min = 220.dp),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = PROCESS_DETAIL_MAX_DP.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = phraseText(VerifyPhrase.EVIDENCE_TITLE, res),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            verifyEvidenceRows(line, res).forEach { row ->
                Text(
                    text = row,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val footnote = verifyEvidenceFootnote(line, res)
            if (footnote.isNotBlank()) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
