// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhengdao.BackChevron
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「开源许可」二级页（2026-10-11）。
 *
 * ## 为什么会有这一页
 *
 * 仓库里早有 `licenses/`（第三方许可证正文与 NOTICE 汇总）与 `THIRD-PARTY-LICENSES.md`，但**正文只在仓库里**：
 * APK 的 `assets/` 只有 `fonts/` 与 `proot/`，设置页「关于」的「许可证」只指向一个 GitHub 网址
 * ⇒ **只拿到 APK、不联网的接收者取不到 Apache-2.0 副本**，Apache-2.0 第 4(a) 条（要给接收者许可证副本）
 * 在**分发物层面**没落实（缺口登记见 `docs/ERRATA.md` E-104）。
 *
 * 处置：构建期把仓库根 `licenses/` 目录与根 `LICENSE` 原样装进 `assets/licenses/`
 * （`app/build.gradle.kts` 的 `stageLicenseAssets` 任务；**git 里仍只有一份正文**，
 * 免得两份副本漂移后 `licenses/README.md` 的 SHA256 校验失效），本页负责把随包的那几份正文读给用户看。
 *
 * ## 两条刻意的写法
 *
 * 1. **列表页与详情页共用同一个 Activity**（靠 `open` 状态切换），不另开一个详情 Activity：
 *    最长的一份是 GPL-3.0（674 行 / 约 35 KB），整段塞进一个 `item` 会一次性排版全部行；
 *    详情页按 [LINES_PER_CHUNK] 行切块交给 `LazyColumn`，只为可见的几块排版。
 *    返回键先回列表、再退出本页（`BackHandler`），避免在详情页按返回直接跳回设置页。
 * 2. **不复制正文、不改一字**：读的就是随包 asset，页脚把 asset 路径写出来，用户可自行核对
 *    （来源 URL、取回日期、字节数/行数/SHA256 见 `licenses/README.md`）。
 */
class OpenSourceLicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // edge-to-edge 与 MainActivity / KeepaliveLogActivity 同款：窗口自行贴边后，
        // 系统栏避让必须由页面自己做（下面 Column 上的 statusBarsPadding / navigationBarsPadding），
        // 否则返回栏被状态栏压住、末项被导航栏遮挡。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                        var open by remember { mutableStateOf<LicenseEntry?>(null) }

                        // 系统返回键：详情页 → 列表页；列表页 → 交回系统（finish）。
                        BackHandler(enabled = open != null) { open = null }

                        LicensesTopBar(
                            title = open?.title ?: "开源许可",
                            onBack = { if (open != null) open = null else finish() },
                        )

                        val entry = open
                        if (entry == null) {
                            LicenseList(onOpen = { open = it })
                        } else {
                            LicenseDetail(entry)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一份随包正文 = 一个 asset。字段只用来在界面上交代「这是谁的、为什么要带它」，
 * 义务的完整表述在仓库 `THIRD-PARTY-LICENSES.md`（这里只放一句话摘要，避免两处说法打架）。
 */
private data class LicenseEntry(
    val title: String,
    val asset: String,
    val covers: String,
    val obligation: String,
)

/** 详情页每块的行政数：674 行的 GPL-3.0 ≈ 6 块。 */
private const val LINES_PER_CHUNK = 120

private val ENTRIES = listOf(
    /*
     * 2026-10-28（第二百六十六批）：条目按「当前 APK 里真有这个组件吗」重排了一次 ——
     * 随包的正文只剩四份（Apache-2.0 / BSD-3-Clause-zstd / LGPL-2.1 / NOTICES）＋ 第一方声明
     * ＋ 本记录页所读的 README。proot（GPL-2.0）、libtalloc（LGPL-3.0）、libandroid-shmem
     * （BSD-3-Clause）、zstd-jni（BSD-2-Clause）、旧版第一方 GPL-3.0 与字体 OFL 全部退出本页：
     * 它们的正文仍在仓库 `licenses/`（历史留档，旧版本 ≤ v2.2.1 的包里有），但**不随这个包**，
     * 列在这里就会变成"页面上说带、包里没有"——正是用户 2026-10-28 那句
     * 「别把那些许可证也带进去了」要治的病。判据与台账见 `licenses/README.md` 两张表。
     */
    LicenseEntry(
        title = "Apache-2.0",
        asset = "licenses/Apache-2.0.txt",
        covers = "随包的 Apache-2.0 Maven 依赖：Compose / AndroidX / OkHttp / kotlinx / mikepenz 等" +
            "（Termux 的三个组件已随终端搬去 zhengdao-term，本仓不再聚合）",
        obligation = "第 4(a) 条：随分发物给接收者许可证副本；第 4(b) 条：改过的文件逐个标注修改通知" +
            "（本项目 12 个衍生文件已在文件头标注，逐文件修改点见 PROVENANCE.md）；" +
            "第 4(d) 条 NOTICE 见下面那份汇总。",
    ),
    LicenseEntry(
        title = "BSD 3-Clause（zstd）",
        asset = "licenses/BSD-3-Clause-zstd.txt",
        covers = "zstd 本体（facebook/zstd v1.5.6）—— 由 Rust 核静态编进随包的 libzhengdao_core.so",
        obligation = "二进制分发时须复现版权声明、条件列表与免责声明；无源码要约义务。" +
            "这一份与已删的 zstd-jni（Java 包装，BSD-2-Clause）**版权人不同**，不能互相顶替。",
    ),
    LicenseEntry(
        title = "GNU LGPL v2.1",
        asset = "licenses/LGPL-2.1.txt",
        covers = "jxl（JExcelApi 2.6.12，旧版 .xls 只读转换）",
        obligation = "LGPL-2.1 第 3 条：随附正文并给出源码获取途径（见 THIRD-PARTY-LICENSES.md 第 2 节）。" +
            "该 jar 内一份许可证文本都没有（实测），所以必须我们自己随包。",
    ),
    LicenseEntry(
        title = "第三方 NOTICE 汇总",
        asset = "licenses/NOTICES.txt",
        covers = "OkHttp 内置 Public Suffix List 数据的 NOTICE（该数据为 MPL-2.0）",
        obligation = "Apache-2.0 第 4(d) 条：随附被分发组件 NOTICE 里可读的归属声明副本。" +
            "Android 打包默认剥掉构件顶层的 META-INF/NOTICE*，故由本项目另存一份随包。",
    ),
    LicenseEntry(
        title = "第一方代码（本项目）",
        asset = "licenses/LICENSE-zhengdao.txt",
        covers = "证道（zhengdao）自身的第一方代码（app/ 里本项目编写部分、tools/、docs/ 等）",
        obligation = "自 2026-10-11 起为「保留所有权利」：可浏览、克隆、本地评估；再分发与任何商业使用" +
            "（含 SaaS / 捆绑 / OEM / 企业内部规模化部署）需项目所有者事先书面许可。" +
            "正文 = 仓库根 LICENSE 的构建期副本。",
    ),
    LicenseEntry(
        title = "正文来源与校验记录",
        asset = "licenses/README.md",
        covers = "随包每一份正文的来源 URL、取回日期、字节数、行数与 SHA256；" +
            "另列「仅历史留档、不再随包」的那几份（proot / libtalloc / libandroid-shmem / zstd-jni / 旧版 GPL-3.0）",
        obligation = "想自己核的人照它跑一条命令即可（文中给了 sha256sum 与 git cat-file 两种写法）。",
    ),
)

@Composable
private fun LicensesTopBar(title: String, onBack: () -> Unit) {
    // B-补-8（2026-10-16）：顶栏不再是写死高度，理由见 SettingsCommon.SettingsTopBar。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
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
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun LicenseList(onOpen: (LicenseEntry) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SectionCard("这份安装包为什么带这些正文") {
                Text(
                    "只拿到安装包、连不上网的人，也应该能读到本项目聚合进来的每一份第三方许可证原文" +
                        "（Apache-2.0 第 4(a) 条、GPL/LGPL 第 3 条、BSD-3-Clause 的复现要求）。" +
                        "所以这些正文随 APK 一起分发，本页读的就是包里那一份，一个字都没改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "「谁为什么需要哪一份、义务怎么履行」的完整说法在仓库的 PROVENANCE.md 与 " +
                        "THIRD-PARTY-LICENSES.md；每份正文的来源与哈希见本页最后一项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(ENTRIES) { entry ->
            SectionCard(entry.title) {
                Text(
                    entry.covers,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.obligation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entry) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("阅读全文", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "（assets/${entry.asset}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LicenseDetail(entry: LicenseEntry) {
    val ctx = LocalContext.current
    var text by remember(entry.asset) { mutableStateOf<String?>(null) }
    var error by remember(entry.asset) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.asset) {
        // 读 asset 是 IO（最长一份约 35 KB）：本项目明令主线程不读盘。
        // runCatching 兜底：读不到就如实显示，不假装有正文。
        withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open(entry.asset).bufferedReader().use { it.readText() } }
                .onSuccess { text = it }
                .onFailure { error = it.message ?: it::class.java.simpleName }
        }
    }

    val body = text
    val failure = error
    when {
        failure != null -> Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                "读不到随包正文（assets/${entry.asset}）：$failure",
                style = MaterialTheme.typography.bodySmall,
                // P2-11：错误正文 zhengdaoErrorText()（error 色 3.55:1 不达 AA）
                color = zhengdaoErrorText(),
            )
        }

        body == null -> Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                "正在读取…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SectionCard("这份正文覆盖什么") {
                    Text(
                        entry.covers,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.obligation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "随包路径：assets/${entry.asset}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 按行切块：正文一个字不改，只是拆成多块让 LazyColumn 按需排版。
            items(body.split('\n').chunked(LINES_PER_CHUNK)) { chunk ->
                Text(
                    text = chunk.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
    }
}
