// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批（2026-10-28 用户：「只把这张卡接到真机上给用户看一次（最小可看版本）」）：
// **只看一眼的入口**——把开工卡摆到真机上，数据是样本，按钮只在本页回显选择。
//
// 为什么放在 debug source set、而不是接进对话或设置页：
//   - 生产免卡接线（ConfirmFlow 真调六步）是她点名留给 **B 段下一批** 的事，本批不许做；
//   - 为了让卡片出现而把它塞进生产页面，等于把「看一眼」偷换成「接线」——两件事的正确率
//     不一样，红线也不同；
//   - debug source set 只在开发包里存在：正式包（release）的合并清单里没有这个 Activity，
//     桌面也没有第二个图标（`TaskGrantSourceGateTest` 钉住 main 清单里不许出现它）。
//
// 按钮为什么**什么都不做**（只回显）：这一页没有装配决策记录、没有会话、没有任务快照。
// 让它真去装一份授权，就等于在预览页里造一个假的"已授权"现场。
package com.example.zhengdao.baopu.authz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.ZhengdaoTheme

class TaskGrantCardPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZhengdaoTheme {
                Surface(Modifier.fillMaxSize()) {
                    TaskGrantCardPreview()
                }
            }
        }
    }
}

/** 本页选了什么（只在本页回显；不装授权、不落账、不跳别处）。 */
private enum class PreviewChoice { NONE, GRANT, PLAN_ONLY, DECLINE }

@Composable
private fun TaskGrantCardPreview() {
    var choice by remember { mutableStateOf(PreviewChoice.NONE) }
    val facts = remember { TaskGrantFacts.of(previewGrant()) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.baopu_authz_preview_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.baopu_authz_preview_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        BaopuTaskGrantCard(
            facts = facts,
            onApproveWithGrant = { choice = PreviewChoice.GRANT },
            onApprovePlanOnly = { choice = PreviewChoice.PLAN_ONLY },
            onDecline = { choice = PreviewChoice.DECLINE },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = when (choice) {
                PreviewChoice.NONE -> stringResource(R.string.baopu_authz_preview_choice_none)
                PreviewChoice.GRANT -> stringResource(R.string.baopu_authz_preview_choice_grant)
                PreviewChoice.PLAN_ONLY -> stringResource(R.string.baopu_authz_preview_choice_plan)
                PreviewChoice.DECLINE -> stringResource(R.string.baopu_authz_preview_choice_decline)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 卡面上的样本快照（两个待新建的文件，30 分钟到期）。
 *
 * 为什么在预览页里手搓一份、而不是读生产状态：这一页刻意**没有**装配任何东西
 * （没有会话、没有任务、没有决策记录），读生产状态只会读到"没有授权"。
 */
private fun previewGrant(): TaskGrant = TaskGrant(
    grantId = "preview-249",
    sessionId = -1L,
    taskId = "preview",
    processEpoch = "preview",
    contractHash = "preview-contract",
    objects = listOf(
        GrantObject(
            objectId = "plan.md",
            revision = 0,
            taskId = "preview",
            location = ObjectLocation.CONTROLLED_TASK_STORE,
            state = ObjectState.Absent,
        ),
        GrantObject(
            objectId = "notes.md",
            revision = 0,
            taskId = "preview",
            location = ObjectLocation.CONTROLLED_TASK_STORE,
            state = ObjectState.Absent,
        ),
    ),
    actions = listOf(GrantActionType.CREATE_TEXT, GrantActionType.EDIT_TEXT),
    maxRiskOrdinal = GrantLimits.MAX_RISK_ORDINAL,
    budget = GrantBudget(),
    issuedMonoMs = 0L,
    // 卡面上的「≤N 分钟」＝到期减签发；样本给 30 分钟，与夹具一致。
    expiresMonoMs = 30L * 60_000L,
    issuedWallMs = 0L,
    approvalEventId = "preview",
    presentedPayloadHash = "preview",
)
