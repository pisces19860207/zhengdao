// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import androidx.compose.ui.res.stringResource
import com.example.zhengdao.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * 欢迎页（第二百一十批精简版）：品牌 ＋ 一句话作用 ＋ 三段温馨提示 ＋ 开始按钮。
 *
 * 用户 2026-10-25 原话：「欢迎页不用放权限申请了，就放欢迎使用证道 APP 之类的温馨提示
 * 和证道 APP 的作用，简短点就行」。同批把首启权限页（PermissionSetupScreen）整页退役：
 * **首启不再请求任何权限、不再弹权限清单**——权限申请全挪到「设置 → 申请权限」卡
 * （入口唯一化，源码门禁见 PermissionEntryGateTest），这里只用一行温馨提示指路。
 *
 * 布局：上半可滚 ＋ **底部开始按钮固定**（照权限页那套）。小屏 / 大字号下内容再高也
 * 滚得到底，开始按钮永远在屏上——P2-4 的「内容比屏幕高就直接裁掉、看不到开始按钮」
 * 不许复发（StateLifetimeGateTest 盯着 verticalScroll + rememberScrollState）。
 *
 * @param onSkip 「先去抱朴看看」——**抱朴不依赖 Debian 环境**（宿主 bionic 原生 serve，
 *   从不进 PRoot，见故障排查手册坑 #0），所以新用户不必等 326MB 装完就能用主打功能。
 *   环境没装时才给这个入口；装过之后没有意义。
 */
@Composable
fun WelcomeScreen(
    environmentInstalled: Boolean,
    onStart: (installed: Boolean) -> Unit,
    onSkip: () -> Unit = {},
) {
    // 系统栏自行避让（edge-to-edge 约定，见 MainActivity 入口注释）：底部按钮固定在
    // 导航栏之上，顶部品牌不被状态栏压。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "证 道",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // 一句话作用：这个 App 是干什么的（品牌名不重复说，上面那行就是）。
            Text(
                text = stringResource(R.string.welcome_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(32.dp))
            // ── 三段以内温馨提示（第二百一十批：原「三步上手」压缩，第一段换成权限指路）──
            // 不删功能说明：填密钥、抱朴对话两条原样保留；「要终端再装环境」由下方
            // 环境注脚与「先去抱朴看看」入口承接（那两句本来就在说同一件事）。
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
                TipRow(stringResource(R.string.welcome_tip_perm))
                TipRow(stringResource(R.string.welcome_step1))
                TipRow(stringResource(R.string.welcome_step2))
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (environmentInstalled) stringResource(R.string.welcome_ready)
                // 2026-10-08：原句「运行环境用于「洞天」终端与 Agent，太极不需要」在一句话里
                // 同时出现「洞天」和「终端」——而底栏只有三个 Tab，用户找不到叫「洞天」的地方。
                // 底栏已统一叫「终端」，这里也跟着改。
                else stringResource(R.string.welcome_env_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 底部固定区：开始按钮永远可见（小屏 + 大字号也不裁切）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { onStart(environmentInstalled) },
                modifier = Modifier.height(56.dp),
            ) {
                Text(
                    text = if (environmentInstalled) stringResource(R.string.welcome_enter)
                    else stringResource(R.string.welcome_install_env),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // 跳过入口：抱朴不依赖 Debian，先给核心功能，环境可以以后再装。
            // 做成次级文字按钮——主路径仍是装环境（逍遥与 Agent 离不开它）。
            if (!environmentInstalled) {
                TextButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.welcome_skip))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/** 温馨提示的一行：墨点 + 短句（不是步骤，不编号——三条之间没有先后）。 */
@Composable
private fun TipRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
