// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百八十批（2026-10-24）§1「技能索引失败态 UI」：
//   失败多时不再把一句红判语在每个技能行下铺一遍（一长串扫不动），改成**摘要优先**——
//   顶部一行读数（技能 N · 失败 M · 待转正 K）→ 同因聚合成「同类失败 ×N」→
//   点开详情才看完整判语与逐条证据（可复制，供排查）。
//
// 三条硬口径：
//   1. **不改技能判据/存储**：判语原句来自 SkillRefine.reasonOf（第五十七批的既有产物），
//      最近失败时刻来自 skill_event 只读查询——本文件不写盘、不改判据、不碰 SKILL.md。
//   2. **呈现学决策中心**：信息卡照 DecisionRowCard 同款（Surface medium＋tonalElevation 1.dp＋
//      徽标＋标题＋副题＋展开钮，热区 48dp），详情照「同款决策卡」（AlertDialog）——不新造容器。
//   3. **聚合不丢信息**：摘要只管省屏（同因一条 ×N），完整信息（判语原文、每个技能名、
//      各自最近失败时刻）一条不少地留在详情里——丢一条就是「摘要丢信息」，门禁红。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.util.TimeFormats

/** 失败时刻的显示口径：`yyyy-MM-dd HH:mm`（util/TimeFormats 唯一真源，主源码不碰 SimpleDateFormat）。 */
internal fun skillFailureTimeText(at: Long): String = TimeFormats.minuteStamp(at)

/**
 * 一条「失败（待重验）」技能在聚合前的读数。数据全由调用方现读（设置页从 DB/重验表现算），
 * 本文件不摸 DB、不摸盘——判据不动、存储不动（红线）。
 *
 * @param reason 重验判语原句（SkillRefine.reasonOf 的产物；同因聚合的键）
 * @param lastFailedAt 最近一次失败使用的时刻（skill_event 里 kind='use' 且 ok=0 的 MAX(at)）；
 *                     无失败记录（「久未用」类判语）⇒ null，详情里明说，不装作有
 */
internal data class SkillFailureRow(
    val name: String,
    val global: Boolean,
    val reason: String,
    val lastFailedAt: Long?,
)

/** 同因聚成的一组（「同类失败 ×N」的那「一类」）。 */
internal data class SkillFailureGroup(
    val reason: String,
    val rows: List<SkillFailureRow>,
) {
    val count: Int get() = rows.size

    /** 这组里最近的一次失败（判语是「久未用」类 ⇒ 全组都没有 ⇒ null）。 */
    val lastFailedAt: Long? get() = rows.mapNotNull { it.lastFailedAt }.maxOrNull()
}

/**
 * 失败行的**同因聚合**（派工单 §1「多条失败按原因聚合成同类失败 ×N，不重复铺同一条」）。
 *
 * 键＝判语原句**逐字相等**（reasonOf 一共三种 ⇒ 天然至多三类）；组序＝首现序，
 * 组内保持传入序（两层各自的人设索引行原序）——都是稳定口径，不重排、不去重名字
 * （两层同名是两回事，各算各的行，与设置页列表同一口径）。
 */
internal fun aggregateSkillFailures(rows: List<SkillFailureRow>): List<SkillFailureGroup> {
    val byReason = LinkedHashMap<String, MutableList<SkillFailureRow>>()
    rows.forEach { row -> byReason.getOrPut(row.reason) { mutableListOf() }.add(row) }
    return byReason.map { (reason, group) -> SkillFailureGroup(reason, group.toList()) }
}

/**
 * 设置页现读数 ⇒ 失败行列表：两层「待重验」表就是失败集（不在表里＝不失败，不进这列），
 * 最近失败时刻逐条问 [lastFailedAtOf]（只对失败的技能查询，正常的技能一次都不查）。
 */
internal fun buildSkillFailureRows(
    globalRecheck: Map<String, String>,
    projectRecheck: Map<String, String>,
    lastFailedAtOf: (String) -> Long?,
): List<SkillFailureRow> =
    globalRecheck.map { (name, reason) ->
        SkillFailureRow(name, global = true, reason = reason, lastFailedAt = lastFailedAtOf(name))
    } + projectRecheck.map { (name, reason) ->
        SkillFailureRow(name, global = false, reason = reason, lastFailedAt = lastFailedAtOf(name))
    }

/**
 * 详情「复制全文」要的文本（判据「详情里保留原始错误/堆栈（可复制），供排查」）。
 *
 * 判语原句、提醒整句、每个技能名（带层）、各自最近失败时刻（或「无失败记录」）——
 * **一条不少**：摘要把长句收进详情，详情要是再把名字丢了，用户就拿不到排查线索。
 * 句子全部由调用方查 strings.xml 拼好传进来（判据 T6：本函数只摆字节，不产中文）。
 */
internal fun skillFailureCopyText(
    group: SkillFailureGroup,
    badgeLine: String,
    hintLine: String,
    globalLabel: String,
    projectLabel: String,
    lastFailedPrefix: String,
    noFailureLabel: String,
    timeTextOf: (Long) -> String,
): String = buildString {
    append(badgeLine).append('\n')
    if (hintLine.isNotBlank()) append(hintLine).append('\n')
    group.rows.forEach { r ->
        append(r.name)
            .append("（").append(if (r.global) globalLabel else projectLabel).append('）')
        append(if (r.lastFailedAt != null) lastFailedPrefix + timeTextOf(r.lastFailedAt) else noFailureLabel)
        append('\n')
    }
}.removeSuffix("\n")

/** 「同类失败 ×N」一行（决策中心 DecisionRowCard 同款信息卡；点开详情，热区 48dp）。 */
@Composable
internal fun SkillFailureGroupRow(
    group: SkillFailureGroup,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = stringResource(R.string.skill_fail_group_badge, group.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                group.lastFailedAt?.let {
                    Text(
                        text = stringResource(R.string.skill_fail_last_failed_at, skillFailureTimeText(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_expand_arrow))
            }
        }
    }
}

/**
 * 失败详情对话框（「同款决策卡」：AlertDialog）。
 *
 * 判语原文＋提醒整句在前（摘要没说全的这里说全），下面每个技能一行（名字＋层＋
 * 最近失败时刻／无失败记录）。底排「复制全文」（复制 [skillFailureCopyText] 的产物）
 * 与「关闭」。本对话框**不写盘**、不改任何状态——纯看。
 */
@Composable
internal fun SkillFailureDetailDialog(
    group: SkillFailureGroup,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var copied by remember { mutableStateOf<String?>(null) }
    val globalLabel = stringResource(R.string.skill_layer_global)
    val projectLabel = stringResource(R.string.skill_layer_project)
    val badgeLine = stringResource(R.string.skill_fail_group_badge, group.count)
    val hintLine = stringResource(R.string.skill_recheck_badge, group.reason)
    val lastFailedPrefix = stringResource(R.string.skill_fail_detail_last_failed, "")
    val noFailureLabel = stringResource(R.string.skill_fail_detail_no_fail)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_fail_detail_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = hintLine,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                group.rows.forEach { r ->
                    Text(
                        text = r.name + "（" + (if (r.global) globalLabel else projectLabel) + "）",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = r.lastFailedAt?.let { lastFailedPrefix + skillFailureTimeText(it) }
                            ?: noFailureLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                copied?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val text = skillFailureCopyText(
                        group = group,
                        badgeLine = badgeLine,
                        hintLine = hintLine,
                        globalLabel = globalLabel,
                        projectLabel = projectLabel,
                        lastFailedPrefix = lastFailedPrefix,
                        noFailureLabel = noFailureLabel,
                        timeTextOf = ::skillFailureTimeText,
                    )
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("zhengdao-skill-fail", text))
                    copied = res.getQuantityString(R.plurals.skill_copied_chars, text.length, text.length)
                }) { Text(stringResource(R.string.skill_copy_all)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_close)) }
            }
        },
    )
}
