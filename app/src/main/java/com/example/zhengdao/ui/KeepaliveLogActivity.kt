// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.BackChevron
import com.example.zhengdao.keepalive.KeepaliveArchive
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「保活记录（被杀留档）」二级页（2026-10-11）。
 *
 * ## 为什么会有这一页
 *
 * 用户原话：「保活记录这个卡弄成和问题反馈那种，太多太长了，单独给他弄一个。」
 * ——设置页里那张内联大卡（位置 / 解释 / 最近退出 / 最近心跳 / logcat 片段数 / 导出 / 清空）
 * 是**报障时才看**的内容，却天天占着设置页一整屏，所以整段搬到这里，设置页只留一行入口
 * （写法与「关于」卡里的「问题反馈（Issues）」一致）。
 *
 * 判据：**「看/查」类内容留在列表页内联，「整理/导出」类内容的操作现场另开一页**
 * （导出、清空都会改变留档本身，给它们一个自己的页面，误触成本也从"滚过去"变成"点进来"）。
 * 长解释文案一字未删——用户嫌的是占地方，不是嫌它没用。
 *
 * ## 与 ImeRegressionActivity 的两处不同（都是有意为之，被否决的写法记在这里）
 *
 * 1. **主题**：那边用裸 `MaterialTheme { }`（M3 默认紫），这里用 [ZhengdaoTheme]。
 *    原因：`ui/theme/Theme.kt` 声明本项目**只有一套** iOS 浅色主题；本页是用户从设置页
 *    点进来的功能页，跟设置页长得不一样就是缺陷；输入回归页是开发者自查工具页，它那身
 *    默认配色不必跟着学。⇒ 这里照 [com.example.zhengdao.MainActivity] 的写法：
 *    `ZhengdaoTheme { Surface(...) { ... } }`。
 * 2. **返回方式**：那边只靠系统返回键（它没有顶栏），这里补一个「‹ 返回」顶栏 ——
 *    照抄 [com.example.zhengdao.MainActivity] 设置页顶栏（48dp Box + 左返回 + 居中标题，
 *    `BackChevron` 复用 MainActivity 那个 public 组件，不另画图标）。
 *    原因：本 App 的二级页统一是"有返回栏"的形态（设置 / 插件），只在开发者工具页破例会让
 *    用户找不到退路。
 *
 * ## 没做的事
 *
 * 没动 `MainActivity.kt`（导航仍由 `startActivity` 拉起独立 Activity，不引入新的导航体系）；
 * 没改 [KeepaliveArchive] 的任何调用（导出/清空的 Toast 文案、失败兜底逐字照抄原设置页）。
 */
class KeepaliveLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // edge-to-edge：与 MainActivity 同款。窗口自行贴边后，系统栏避让必须由页面自己做
        //（下面 Column 上的 statusBarsPadding / navigationBarsPadding），否则返回栏被状态栏压住、
        // 末项被导航栏遮挡。ImeRegressionActivity 没做这一步（它保持系统默认 resize，内容天然安全），
        // 本页要顶栏，所以按 MainActivity 的方式来。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                        KeepaliveLogTopBar(onBack = { finish() })
                        KeepaliveLogScreen()
                    }
                }
            }
        }
    }
}

/**
 * 顶栏：左「‹ 返回」+ 居中标题，与设置页 / 插件页同款（见 MainActivity 的 `composable("settings")`）。
 *
 * 这里不用 `nav.popBackStack()` 那套兜底：本页是独立 Activity，返回 = `finish()`，
 * 栈底永远是 MainActivity，不存在"栈空导致整页纯白"的情形（那次真机白屏是 NavHost 的问题）。
 */
@Composable
private fun KeepaliveLogTopBar(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackChevron(tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("返回", color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text = "保活记录",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * 页面内容＝原设置页那张卡的全部内容，顺序不动。
 *
 * 拆成两张卡（原样只有一张）：卡标题**不能**再写「保活记录」——顶栏已经是这四个字，
 * 页内重复标题是本项目点名过的"最扎眼的重复"（见 SettingsScreen 里紧邻页首的那段注释）。
 */
@Composable
private fun KeepaliveLogScreen() {
    val ctx = LocalContext.current
    var exitLines by remember { mutableStateOf(emptyList<String>()) }
    var beatLines by remember { mutableStateOf(emptyList<String>()) }
    var snippetCount by remember { mutableStateOf(0) }
    // tick 只被「清空记录」自增：清空后重新读一遍，列表当场变空态。
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        // 读私有目录 → 必须 IO 线程（主线程读盘在本项目是明确禁止的）。
        // KeepaliveArchive 内部全是 runCatching 兜底，读不到就返回空表，这里不必再包一层。
        withContext(Dispatchers.IO) {
            exitLines = KeepaliveArchive.history(ctx, 3)
            beatLines = KeepaliveArchive.heartbeatTail(ctx, 5)
            snippetCount = KeepaliveArchive.snippets(ctx).size
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        // 组间距（20dp）与设置页一致：SectionCard 的标题在卡片外，组间距小于这个值时
        // 卡片外的小标题会读成上一张卡的注脚。
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionCard("留档位置与说明") {
            Text(
                "位置：${KeepaliveArchive.dirPath(ctx)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "进程被杀时系统会自己记下原因（内存不足 / 资源占用超限 / 厂商后台清理 / 崩溃 / ANR），" +
                    "下次启动时收进这里；心跳每 10 分钟一条，**最后一条就是遇难现场**（内存水位、" +
                    "前台服务在不在、用户当时在哪一页）。导出后连同 logcat 片段一起可交给作者分析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("最近的记录") {
            if (exitLines.isEmpty()) {
                Text(
                    "还没有退出记录——被杀过一次并重开之后就会有。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "最近退出：",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exitLines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (beatLines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "最近心跳：",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                beatLines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "logcat 片段：$snippetCount 份（每次启动与巡检各留一份，最多 5 份）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                OutlinedButton(onClick = {
                    val dst = KeepaliveArchive.export(ctx)
                    Toast.makeText(
                        ctx,
                        dst?.let { "已导出到 ${it.absolutePath}" } ?: "导出失败：公共区不可用（先在权限里开启「所有文件访问」）",
                        Toast.LENGTH_LONG,
                    ).show()
                }) { Text("导出到 Download/证道/logs") }
                TextButton(onClick = {
                    val n = KeepaliveArchive.clear(ctx)
                    tick++
                    Toast.makeText(ctx, "已清空 $n 份留档", Toast.LENGTH_SHORT).show()
                }) { Text("清空记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
