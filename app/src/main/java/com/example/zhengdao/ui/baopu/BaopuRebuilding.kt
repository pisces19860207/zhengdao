package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/**
 * 抱朴页占位（2026-10-16 旧抱朴线清场）。
 *
 * 旧线＝「跑宿主 bionic 版 opencode ＋ REST/SSE 对接」：`oc/` 全目录（12 文件）与旧 UI 壳
 * `ui/baopu/` 全目录（10 文件）连同 26 个测试已整批删除，根因见 `docs/ERRATA.md` E-140
 * —— `targetSdk 36` 下 App 直接启动数据目录里的可执行文件被 W^X 拦（`error=13`），
 * 那条线在真机上根本起不来。
 *
 * 本文件只是**不让 Tab 变成空白**的过渡页：新抱朴（自研 agent）按刀次重做，
 * 真实页面归**刀 1**。刀 0 当前只做数据层（见 `docs/派工单-2026-10-16-第七批-抱朴刀0骨架.md`），
 * 因此这里刻意保持极简，不要往里加功能。
 */
@Composable
fun BaopuRebuilding(onExit: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.baopu_rebuilding_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.baopu_rebuilding_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.baopu_rebuilding_go))
        }
    }
}
