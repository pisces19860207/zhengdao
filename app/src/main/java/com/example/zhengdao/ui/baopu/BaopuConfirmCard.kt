// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 §3.4（确认卡：一行标题「要动文件：<路径>」、一行摘要、两个按钮
// 「批准」「拒绝」、忙碌态「执行中…」；形态照 BaopuAskUserCard，不另起一套视觉；
// 无障碍 contentDescription 走 semantics，**不引 material-icons**——本项目没这个依赖）
// ＋ §3.1（界面文案全走 strings.xml，中英各一份；九句由 B 段随包落盘，别重写）。
//
// 第一百三十七批（2026-10-22）：外框换成四档容器的**行动卡**（与提问卡同一个容器，
// 只有标题条文字不同——§6.2「七张合成一个容器，外形完全一致」）。
//
// 第一百七十一批（确认卡降噪 v2）：卡面多两样东西——
// ① 范围行（仅这一次之外的「本次运行内/本会话内/本技能内」；高风险工具不摆——
//    摆不摆由内核的 [com.example.zhengdao.baopu.ConfirmRisk] 定，本层只渲染）；
// ② 合并计数行（同 key 等待中又来的相同请求，显示次数、不重复弹卡）。
// 范围按钮把选择写进 [com.example.zhengdao.baopu.ConfirmScopeChoice] 旁路再走原
// onApprove——接线（BaopuScreen/控制器/引擎）落地后可改直连，见交付报告 §接线。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.ConfirmScope
import com.example.zhengdao.baopu.ConfirmScopeChoice
import com.example.zhengdao.baopu.agent.ConfirmRequest
import com.example.zhengdao.baopu.agent.KbTools
import com.example.zhengdao.baopu.agent.RunRules
import java.io.File
import org.json.JSONObject

/**
 * 文件改动确认卡（刀 3 A 段）——**只渲染**，零判据。
 *
 * 与 [BaopuAskUserCard] 的同与不同：同在一行标题 + 摘要 + 按钮；不同在没有
 * 自由输入行——这是「批不批」的二选一，不是聊天。
 *
 * 路径只从 `argsJson` 里取 `path` 做**标题展示**（不解析执行任何动作——
 * 执行只走引擎的 GuardPipeline.executeApproved；取不到就退化成工具名）。
 */
@Composable
internal fun BaopuConfirmCard(
    request: ConfirmRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    /** 工作区根（第一百四十九批 组5）：标题与摘要都把路径剥成相对显示；null ⇒ 原样。 */
    projectRoot: File? = null,
    /**
     * 第二百二十批 · 影响提示：**一行小字**（≤30 字，已经由
     * `ImpactLine.text` 保证单行＋不超字数；null ⇒ 这一行整个不渲染）。
     *
     * 为什么放最后且默认 null：存量调用点（含预览/测试）一个字不改，行为与本批之前
     * 逐字节相同；这也让「关掉开关 ⇒ 完全无提示」在 UI 层是**同一个**分支。
     */
    impactLine: String? = null,
) {
    // 点过就进忙碌态（执行中…）；卡被摘掉（控制器置空）时本组件随之消失
    var busy by remember(request.callId) { mutableStateOf(false) }
    val path = remember(request.argsJson, request.tool, projectRoot) {
        confirmCardTitlePath(request, projectRoot)
    }
    val approveDesc = stringResource(R.string.baopu_confirm_approve_desc)
    val rejectDesc = stringResource(R.string.baopu_confirm_reject_desc)
    // 第一百七十一批：可选范围（除「仅这一次」外的三档；高风险 ⇒ 空列表 ⇒ 不摆）
    val scopes = request.scopeOptions.filter { it != ConfirmScope.ONLY_ONCE }

    BaopuActionContainer(
        title = stringResource(R.string.baopu_tier_action_confirm),
        badge = stringResource(R.string.baopu_tier_action_badge),
        modifier = modifier,
    ) {
        // 标题：要动文件：<路径>（取不到 path ⇒ 换成「要执行：<工具名>」——
        // 浏览器/搬文件这类动作没有 path 参数，硬套「要动文件」会说假话）
        Text(
            text = if (path != null) {
                stringResource(R.string.baopu_confirm_title, path)
            } else {
                stringResource(R.string.baopu_confirm_title_action, request.tool)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        // 摘要：一句话人话（引擎生成；重启后同函数重算）
        Text(
            text = request.summary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        // 第二百二十批 · 影响提示：**最多一行、最多 30 字**（派工单 §2.5）。
        // 它只是这张卡上的第三行小字——不多一张卡、不加按钮、不折叠不展开。
        // 文案由 `ImpactLine` 算好（含「后果未知」降级），本层零判据。
        if (impactLine != null) {
            Text(
                text = impactLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 提示：批准真的会改（写前自动备份）；拒绝就跳过
        Text(
            text = if (path != null) {
                stringResource(R.string.baopu_confirm_hint)
            } else {
                stringResource(R.string.baopu_confirm_hint_action)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // 第一百七十一批：高风险明示（写盘/删除/扣费类永远只给「仅这一次」，
        // 卡面不给范围按钮；这句话解释「为什么没有范围选项」，不吓唬人）
        if (request.highRisk) {
            Text(
                text = stringResource(R.string.baopu_confirm_high_risk),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 第一百七十一批：同 key 等待中合并进来的次数（1 = 首张，不显示）
        if (request.mergeCount > 1) {
            Text(
                text = pluralStringResource(
                    R.plurals.baopu_confirm_merged,
                    request.mergeCount,
                    request.mergeCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    busy = true
                    onApprove()
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).semantics { contentDescription = approveDesc },
            ) {
                Text(
                    if (busy) {
                        stringResource(R.string.baopu_confirm_running)
                    } else {
                        stringResource(R.string.baopu_confirm_approve)
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    busy = true
                    onReject()
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).semantics { contentDescription = rejectDesc },
            ) {
                Text(stringResource(R.string.baopu_confirm_reject))
            }
        }
        // 第一百七十一批：范围行（按范围批准 = 带作用域的批准）。选了哪档就记进
        // 旁路（callId 锚定这一张卡），内核在 resumeWithApproval 里消费——
        // 「同 key 同作用域只问一次」从这一行开始。
        if (scopes.isNotEmpty()) {
            // 无障碍说明先在组合作用域里取好（semantics lambda 不是 @Composable）
            val scopeDescs = scopes.associateWith { scopeDesc(it) }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                scopes.forEach { scope ->
                    OutlinedButton(
                        onClick = {
                            busy = true
                            ConfirmScopeChoice.record(request.callId, scope)
                            onApprove()
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = scopeDescs.getValue(scope) },
                    ) {
                        Text(
                            text = stringResource(scopeLabel(scope)),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 范围按钮的短标签（界面文案走 strings.xml——§3.1；映射只此一处）。 */
private fun scopeLabel(scope: ConfirmScope): Int = when (scope) {
    ConfirmScope.THIS_RUN -> R.string.baopu_confirm_scope_run
    ConfirmScope.THIS_SESSION -> R.string.baopu_confirm_scope_session
    ConfirmScope.THIS_SKILL -> R.string.baopu_confirm_scope_skill
    ConfirmScope.ONLY_ONCE -> R.string.baopu_confirm_approve
}

/**
 * 范围按钮的无障碍说明（说清「批了之后什么不再问」——读屏用户听的是效果）。
 * 与按钮短标签同样走 strings.xml（§3.1 管的是界面文案，accessibility 也算界面）。
 */
@Composable
private fun scopeDesc(scope: ConfirmScope): String = stringResource(
    when (scope) {
        ConfirmScope.THIS_RUN -> R.string.baopu_confirm_scope_run_desc
        ConfirmScope.THIS_SESSION -> R.string.baopu_confirm_scope_session_desc
        ConfirmScope.THIS_SKILL -> R.string.baopu_confirm_scope_skill_desc
        ConfirmScope.ONLY_ONCE -> R.string.baopu_confirm_approve_desc
    },
)

/**
 * 卡面标题里的路径（**纯函数**，JVM 直测；第一百四十九批 组5 从 Composable 里抽出来）。
 *
 * 取法一字未改：只从 `argsJson` 取 `path`（取不到且是 kb_index ⇒ 用它的有效目标；
 * 再取不到 ⇒ null，卡面退化成「要执行：<工具名>」）。新增的只有最后一步——
 * 给了工作区根就把绝对路径剥成相对：②段路径守卫改写出来的就是这种长串，
 * 直接印在标题上又长又泄目录结构（[RunRules.relativizePath] 的口径）。
 *
 * 只做**显示**：`argsJson` 本身没被改过，批准执行的仍是原参数。
 */
internal fun confirmCardTitlePath(request: ConfirmRequest, projectRoot: File?): String? {
    val raw = runCatching {
        val obj = JSONObject(request.argsJson)
        val explicit = obj.optString("path").trim()
        when {
            explicit.isNotBlank() -> explicit
            // UI-D12：kb_index 的 path 有缺省值，卡面要写「将来要索引哪儿」，
            // 不许只回显 `{}`（2026-10-22 真机）。
            request.tool == KbTools.NAME_INDEX -> KbTools.effectiveTarget(obj)
            else -> null
        }
    }.getOrNull()
    return if (raw == null || projectRoot == null) raw else RunRules.relativizePath(raw, projectRoot)
}
