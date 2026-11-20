// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhengdao.BaoPuIcon
import com.example.zhengdao.R
import com.example.zhengdao.SettingsIcon
import com.example.zhengdao.XuanLanIcon

/**
 * 746 宽屏侧栏（medium/expanded 双栏的左栏）。
 *
 * 只画导航、不含任何状态：选中/点击全由外部传入（状态仍留在 HomeTabs 顶层的 rememberSaveable）。
 * 因此在 compact↔宽屏 切换时，本组件的出现/消失不会带走任何被保护的用户状态。
 */
@Composable
fun AdaptiveHomeRail(
    selectedTab: Int,
    onTabClick: (Int) -> Unit,
    baoPuTab: Int,
    browserTab: Int,
    settingsTab: Int,
    modifier: Modifier = Modifier,
) {
    val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.12.sp)
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NavigationRail(
        modifier = modifier.fillMaxHeight().width(80.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            NavigationRailItem(
                selected = selectedTab == baoPuTab,
                onClick = { onTabClick(baoPuTab) },
                icon = { BaoPuIcon(selectedTab == baoPuTab) },
                label = { Text(stringResource(R.string.nav_taiji), style = labelStyle) },
                colors = colors,
            )
            NavigationRailItem(
                selected = selectedTab == browserTab,
                onClick = { onTabClick(browserTab) },
                icon = { XuanLanIcon(selectedTab == browserTab) },
                label = { Text(stringResource(R.string.nav_browser), style = labelStyle) },
                colors = colors,
            )
            NavigationRailItem(
                selected = selectedTab == settingsTab,
                onClick = { onTabClick(settingsTab) },
                icon = { SettingsIcon(tint = railIconColor(selectedTab == settingsTab)) },
                label = { Text(stringResource(R.string.nav_settings), style = labelStyle) },
                colors = colors,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun railIconColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
