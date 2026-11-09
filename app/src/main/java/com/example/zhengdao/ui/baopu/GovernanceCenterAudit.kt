// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十一批（2026-11-09）· 治理中心第 7 块「开发核对」：一行摘要 ＋「导出核对包」。
//
// 为什么单独一个文件：治理中心屏（GovernanceCenterScreen.kt）同时在飞三张单的追加
// （544 项目账本 / 558 租约读数 / 561 本单），块本体放这里、屏里只留一行为编排，
// 合并时谁也不蹭谁的行。与本屏既有各块同一套板式（SectionCard ＋ 48dp 触摸目标）。
//
// 口径（判据建在上面）：
//   · **不加抽屉入口**（判据⑥）：入口净增 0——这块只活在治理中心屏内；
//   · 第 4 个人按写口：本屏原本三个写口（清小黑板/清看板/确认待办），导出核对包是第四个，
//     同样**只由人按**，模型够不着（BuildAuditPack 不在任何工具的 spec 里）；
//   · 导出＝只读采集＋写 `<工作区>/产物/.zd-audit-*.json/.md`（点号开头不计产物数，
//     不覆盖旧文件）——采集/组包/防覆盖全在 `BuildAuditPack`，这里只画不算。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BuildAuditPack
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.SectionCard
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「开发核对」块：摘要一行 ＋「导出核对包」按钮 ＋ 导出结果行（成功给文件名，失败给原因）。
 *
 * 三段各自一个函数（同 BoardSection 的拆法——一段一件事，也免得整块超 detekt 60 行）。
 *
 * @param summary 一行摘要（`构建 … · 开关 … · 可删候选 …`；null＝首读未回，先占位）
 * @param wsRoot  工作区根：导出落点 `<wsRoot>/产物/`（与产物计数同一间屋，541/547 同口径）
 */
@Composable
internal fun AuditSection(summary: String?, wsRoot: File) {
    val context = LocalContext.current
    // 协程作用域必须装崩溃处理器（本仓门禁：UI 侧不许裸 rememberCoroutineScope）
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var busy by remember { mutableStateOf(false) }
    var doneLine by remember { mutableStateOf<String?>(null) }
    var failLine by remember { mutableStateOf<String?>(null) }
    SectionCard(title = stringResource(R.string.gc_audit_title)) {
        Text(
            text = stringResource(R.string.gc_audit_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = summary ?: stringResource(R.string.common_loading),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AuditExportRow(
            busy = busy,
            onExport = {
                busy = true
                launchAuditExport(
                    scope,
                    context,
                    wsRoot,
                    onDone = { doneLine = it; failLine = null },
                    onFail = { failLine = it; doneLine = null },
                    onFinished = { busy = false },
                )
            },
        )
        AuditResultLines(done = doneLine, fail = failLine)
    }
}

/** 底下那行：左「消融登记」状态小字，右「导出核对包」按钮（48dp 触摸目标，导出中禁用）。 */
@Composable
private fun AuditExportRow(busy: Boolean, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (BuildAuditPack.registryEstablished()) {
                    R.string.gc_audit_ablation_set
                } else {
                    R.string.gc_audit_ablation_unset
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onExport,
            enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                stringResource(
                    if (busy) R.string.gc_audit_exporting else R.string.gc_audit_export,
                ),
            )
        }
    }
}

/** 导出结果两行：成功灰字、失败红字（各来各的色，同一个出口防两处样式漂移）。 */
@Composable
private fun AuditResultLines(done: String?, fail: String?) {
    if (done != null) {
        AuditStatusLine(
            stringResource(R.string.gc_audit_done, done),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (fail != null) {
        AuditStatusLine(
            stringResource(R.string.gc_audit_fail, fail),
            MaterialTheme.colorScheme.error,
        )
    }
}

/** 导出结果行（成功灰字、失败红字；一个出口，防两处各写各的样式）。 */
@Composable
private fun AuditStatusLine(text: String, color: Color) {
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        Spacer(Modifier.padding(bottom = 2.dp))
    }
}

/**
 * 人按「导出核对包」那一下：IO 上采集＋落两件，回主线程报结果。
 * 采集/组包/防覆盖全在 BuildAuditPack；这里只编排（与 launchTodoConfirm 同款形态）。
 */
private fun launchAuditExport(
    scope: CoroutineScope,
    context: android.content.Context,
    wsRoot: File,
    onDone: (String) -> Unit,
    onFail: (String) -> Unit,
    onFinished: () -> Unit,
) {
    scope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val nowMs = System.currentTimeMillis()
                val governance = BuildAuditPack.governanceJson(wsRoot, nowMs)
                val pack = BuildAuditPack.collect(context, wsRoot, nowMs, governance)
                val (json, md) = BuildAuditPack.exportFiles(wsRoot, pack, nowMs)
                md.name + "（" + json.name + "）"
            }
        }
        onFinished()
        outcome.fold(onSuccess = onDone, onFailure = { onFail(it.message ?: "unknown") })
    }
}
