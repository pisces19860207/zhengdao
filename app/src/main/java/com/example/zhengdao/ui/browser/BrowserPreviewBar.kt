// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §5.3/§5.4（本地预览页签的控件与"头部标明"）。
// 设计口径来自 `证道设计报告/玄览-底栏改版-定稿与施工单-2026-10-18.md` 那条 56dp 底栏：
// 预览页签**替换**那条栏（不是再叠一条），所以壳子合计仍是 52（标签条）+ 56（这一栏）
// = 108dp，页面可视 604dp 不变——`BrowserBottomBarGuardTest` 的那笔 dp 账在这里同样成立。
package com.example.zhengdao.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.browser.PreviewMode
import com.example.zhengdao.util.copyPlainText

/**
 * 本地预览页签那一栏（§5.3/§5.4）：**只在预览页签出现**，顶掉上网页签那条 56dp 底栏。
 *
 * 为什么不复用 [BrowserBottomBar] 加几个参数：那一栏的每一件都是"上网"语义——
 * 地址胶囊（点开能填网址）、前进/后退（预览页签只有一条注入历史，这两个键是死键）、
 * 刷新（重注入的是内存里那份文本，不是重读盘，放在预览里就是"刷新了却没新内容"的假象）、
 * 主页（预览页签的 `loadUrl` 被断网档硬拒 = 静默）。把它们端给预览页签，等于让用户
 * 挨个键试出"哪些在这儿不干活"。反过来在同一个组件里分两态，那些几何门禁
 * （56dp / 48×48 / 12dp 圆角 / 胶囊 40dp…）就要开始按页签类型讲条件——两条 UI 一条链，
 * 谁都不是对方的一种特例。**分成两个组件、共用同一个 56dp 高度**是最便宜的老实做法。
 *
 * 三个动作（§5 验收逐条对上）：
 * - 「看源码 / 看排版」= 模式切换（[BrowserTabState.previewMode]，判据
 *   [com.example.zhengdao.browser.LocalPreviewPolicy.document]：源码档整段转义，
 *   渲染档逐字交给 WebView）；
 * - 「脚本 关/开」= §5.3 的可选沙箱，**默认关**，开了也只在本页签跑、仍出不了网；
 * - 「复制」= §5.4「源码模式可复制」；走项目唯一的复制入口 [copyPlainText]
 *   （不许再抄一份 ClipboardManager），并把**返回值**当判据：失败就写"没复制上"，
 *   不弹一句假的「已复制」。
 *
 * 头部那一行（§5.4「标明这是哪个文件、当前是断网/禁 JS 状态」）：文件名 +
 * 「只显示 · 断网 · 脚本关/开」——状态写死在文字里，不靠用户记住自己开过什么。
 */
@Composable
internal fun BrowserPreviewBar(
    fileName: String,
    mode: PreviewMode,
    jsSandbox: Boolean,
    /** 复制的内容：文件的**原始文本**（不是注入用的文档壳——源码档那层 `<pre>` 不是内容）。 */
    text: String,
    onSetMode: (PreviewMode) -> Unit,
    onToggleJsSandbox: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = xuanLanPalette()
    // 复制反馈是本栏的一次性状态（离开组合就没了）：它是"刚刚那一下的结果"，
    // 不塞进 BrowserTabState（那不是页签的事实，是控件的事实）。
    var copyNote by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // 文案在组合期取（[stringResource] 是 @Composable，不能到 onClick 那种普通 lambda 里调）；
    // 点击回调只把已经取好的串写进 [copyNote]。
    val copiedNote = stringResource(R.string.browser_preview_copied)
    val copyFailedNote = stringResource(R.string.browser_preview_copy_failed)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(palette.stripBg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.browser_preview_status, badgeJs(jsSandbox)),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 模式切换：按钮上写的永远是"按下之后去看什么"，不是"现在在看什么"
        // （后者要用户先在脑子里取反，是那种看着没错、用起来别扭的文案）。
        ToggleCell(
            label = stringResource(
                if (mode == PreviewMode.RENDER) R.string.browser_preview_to_source
                else R.string.browser_preview_to_render,
            ),
            onClick = {
                onSetMode(if (mode == PreviewMode.RENDER) PreviewMode.SOURCE else PreviewMode.RENDER)
            },
        )
        ToggleCell(
            label = stringResource(
                if (jsSandbox) R.string.browser_preview_js_on_label else R.string.browser_preview_js_off_label,
            ),
            onClick = { onToggleJsSandbox(!jsSandbox) },
        )
        ToggleCell(
            label = copyNote ?: stringResource(R.string.browser_preview_copy),
            onClick = {
                // 判据是复制的**结果**，不是"调用没抛异常"——copyPlainText 已经把这个区别
                // 收在返回值里（空文本 / 取不到剪贴板服务都回 false）。
                val ok = copyPlainText(context, fileName, text)
                copyNote = if (ok) copiedNote else copyFailedNote
            },
        )
    }
}

/**
 * 一个文字键。
 *
 * 为什么不是底栏那种 48×48 图标键：预览栏是"三个文字动作"，硬套底栏几何（48×48 +
 * spacedBy(4) + 12dp 内缩）在 374.9dp 宽屏上会把文件名挤没。这里让键自适应宽度、
 * 文件名那一格 `weight(1f)` 吃掉剩余并省略号——**栏高仍与底栏同一个 56dp**，
 * 壳子总高 108dp 那笔账不变（`BrowserBottomBarGuardTest` 的 dp 推断在这一栏同样成立）。
 * 颜色取 [xuanLanPalette] 那套已过 AA 的墨色（第㊱刀/㊽ 的对比度教训：不自造色值）。
 */
@Composable
private fun ToggleCell(label: String, onClick: () -> Unit) {
    val palette = xuanLanPalette()
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.ink,
            maxLines = 1,
        )
    }
}

/** 状态行里的脚本那一截（两条字符串资源，避免在 XML 里做句子拼接）。 */
@Composable
private fun badgeJs(jsSandbox: Boolean): String =
    stringResource(if (jsSandbox) R.string.browser_preview_badge_js_on else R.string.browser_preview_badge_js_off)
