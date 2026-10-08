// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 欢迎页：品牌 + 一句话简介 + 三步上手 + 状态感知的开始按钮。
 *
 * @param onSkip 「先去太极看看」——**太极不依赖 Debian 环境**（宿主 bionic 原生 serve，
 *   从不进 PRoot，见故障排查手册坑 #0），所以新用户不必等 326MB 装完就能用主打功能。
 *   环境没装时才给这个入口；装过之后没有意义。
 */
@Composable
fun WelcomeScreen(
    environmentInstalled: Boolean,
    onStart: (installed: Boolean) -> Unit,
    onSkip: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "证 道",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "在手机上运行你自己的 AI Agent",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        // ── 三步上手（第三批）：小白第一次打开就知道全流程 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(14.dp),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepRow(1, "在「设置」填好模型密钥（用哪个填哪个）")
            StepRow(2, "进「太极」直接对话——不必等运行环境装完")
            StepRow(3, "要用命令行或装其他 Agent，再回来装运行环境")
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = { onStart(environmentInstalled) },
            modifier = Modifier.height(56.dp),
        ) {
            Text(
                text = if (environmentInstalled) "进 入" else "安装运行环境",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // 跳过入口：太极不依赖 Debian，先给核心功能，环境可以以后再装。
        // 做成次级文字按钮——主路径仍是装环境（洞天与 Agent 离不开它）。
        if (!environmentInstalled) {
            TextButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) {
                Text("先去太极看看（不装环境）")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (environmentInstalled) "运行环境就绪"
            // 2026-10-08：原句「运行环境用于「洞天」终端与 Agent，太极不需要」在一句话里
            // 同时出现「洞天」和「终端」——而底栏只有三个 Tab，用户找不到叫「洞天」的地方。
            // 底栏已统一叫「终端」，这里也跟着改。
            else "运行环境用于「终端」和其他 Agent，太极不需要",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 三步引导的单行：iOS 蓝圆形序号 + 说明文字。 */
@Composable
private fun StepRow(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
