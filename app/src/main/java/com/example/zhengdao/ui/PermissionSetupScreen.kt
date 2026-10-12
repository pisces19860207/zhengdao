// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.zhengdao.terminal.ProotLauncher
import com.example.zhengdao.ui.theme.zhengdaoReadyGreen
import com.example.zhengdao.ui.util.pressClickable
import com.example.zhengdao.util.HumanizeError

// 状态行「已授权／已开启」用的绿：2026-10-12 起改走主题感知取色
// （浅色 #2E7D32 / 深色 #30D158，见 theme/Theme.kt 的 zhengdaoReadyGreen()），
// 深色主题下不再是一枚看不清的深绿。

/**
 * 首启「权限」页的状态与判据（2026-10-11）。
 *
 * 为什么集中在 object 里：进入本页的判定发生在 [com.example.zhengdao.MainActivity.onCreate]
 * 的**系统对话框之前**（那里不是 Compose 环境，拿不到 LocalContext），页面自身又要用同一套
 * 判定来画状态，两处必须是同一份代码——否则就是项目里反复踩过的「双判」问题
 * （同类教训见 terminal/Workspace.kt:32-37 的 v1.3 E2 消双判）。
 */
object PermissionSetup {

    /** 路由名：叠在起始页之上的一页（与设置页 "settings" 同风格，不用 EnvHealth 的 "-" 连字符名）。 */
    const val ROUTE = "permission_setup"

    /**
     * 引导标记写在哪份 prefs：与首启历史标记同源，即 `"zhengdao-ui"`。
     *
     * ⚠️ **不是** SettingsScreen.kt 里那个 `object Settings` 的 prefs（那份叫 `"zhengdao-settings"`，
     * 存的是工作区模式 / 已装环境版本）——
     * 首启标记历来都在 `"zhengdao-ui"`（`last_route` 等首启状态在那一份；
     * terminal/TerminalPrefs.kt 的一次性提示也用它）。放错盒子以后排查「为什么又弹了」会找不到。
     */
    private const val PREFS = "zhengdao-ui"

    /** 「已经引导过」标记键（含用户点「稍后再说」的情形）。 */
    private const val KEY_GUIDED = "permission_setup_guided"

    /**
     * 通知权限的字面量（与 MainActivity.kt:146 首启那段通知请求一字不差）。
     * 全文件只此一处：判定与 launcher 拉起都用它，免得两处各写一遍字符串、改一处漏一处。
     */
    const val NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    /** 是否已经引导过这一页（写过标记就不算首次）。 */
    fun guided(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GUIDED, false)

    /**
     * 记下「已引导过」。**点过任何一个底部按钮都要写**（「稍后再说」也算数）——
     * 用户要摆脱的就是反复弹，标记只在用户选择过之后才写，见 [shouldGuide] 的说明。
     */
    fun markGuided(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GUIDED, true)
            .apply()
    }

    /**
     * 共享存储（必需项）是否已授权。
     *
     * 判据：**不做 READ+WRITE 双查**，直接委托 [ProotLauncher.storageGranted] 这个单一判定源。
     * 该实现查的是 WRITE_EXTERNAL_STORAGE：targetSdk=28（app/build.gradle.kts:163）在 Android 11+
     * 上自动保持 legacy 存储视图，拿到 WRITE 即可读写共享存储；且本项目设了
     * `requestLegacyExternalStorage`（AndroidManifest.xml:47）。若本页自己另查一份 READ+WRITE，
     * 用户只授了其中一个时，本页与设置页会给出不同结论（v1.3 E2 修的就是这个）。
     */
    fun storageGranted(ctx: Context): Boolean = ProotLauncher.storageGranted(ctx)

    /**
     * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）是否已开启。
     *
     * ⚠️ 真话，必须写进文案：本 App 的 targetSdk=28，该开关**只对 target≥30 的 App 存在**，
     * 系统对证道永远返回 false（ProotLauncher.kt:40-49 记录的 2026-10-04 修复：此前用它做
     * 存储判定，恒 false，导致判定永远失败）。所以这一行只作**可选增强**展示，
     * **绝不参与「必需项就绪」判定**——否则底部主按钮永远只能显示「稍后再说」，
     * 用户看到的是一个永远不会变成「完成」的页面。
     */
    fun manageAllFilesGranted(): Boolean =
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 通知权限（POST_NOTIFICATIONS，安卓 13+ 运行时权限，AndroidManifest.xml:31 已声明）。
     *
     * 这里只是**展示状态**：本权限此前就已在首启申请（MainActivity.kt:144-152，requestCode 101），
     * 本次只是把它从「又一个系统对话框」改成列表里的一行，没有新增任何权限申请。
     */
    fun notificationsGranted(ctx: Context): Boolean = runCatching {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, NOTIFICATIONS_PERMISSION)
    }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED

    /**
     * 必需项是否有缺。
     *
     * 当前**只有共享存储一项**是必需的：通知缺失只是收不到「装完清理」等提示
     * （真机验收结论记在 MainActivity 首启通知请求上方那段注释里：拒绝不影响任何功能），
     * 「所有文件访问」在本机系统上拿不到（见 [manageAllFilesGranted]）⇒ 二者都不算必需。
     */
    fun missingRequired(ctx: Context): Boolean = !storageGranted(ctx)

    /**
     * 首启是否要把这一页叠出来：**没引导过** 且 **必需项有缺**。
     *
     * 引导过（含「稍后再说」）就不再自动出现——反复弹正是用户反馈要摆脱的东西；
     * 之后要改权限走设置页「权限」卡（那份按用户要求保持原样）。
     */
    fun shouldGuide(ctx: Context): Boolean = !guided(ctx) && missingRequired(ctx)
}

/**
 * 首启「权限」页（2026-10-11）。
 *
 * 背景：用户反馈「新安装时申请各种权限的页面……单个卡片有点晃眼也不符合使用习惯」。
 * 此前首启是**一次一个系统对话框**串起来弹：READ/WRITE（MainActivity requestCode 100）、
 * POST_NOTIFICATIONS（101）、再叠一个「开启完整存储访问」AlertDialog。现在收成一页：
 * 权限列在**一张卡里的若干行**，每行自己点开授权，底部一个按钮进主界面。
 *
 * 判据与取舍（都是有意为之，改之前先读）：
 * - 视觉语言沿用设置页「权限」卡的排版习惯（SectionCard 的卡片 + SettingRow 的行节奏：
 *   文字 + 右侧状态 + 行尾 ›），不另起一套审美；绿色用 `zhengdaoReadyGreen()`——
 *   主题 tertiary `#34C759` 白底只有 2.22:1，作正文色读不清；浅色走 #2E7D32（5.13:1）、
 *   深色走 #30D158（8.4:1），见 ui/theme/Theme.kt。
 * - 未授权时点整行直接去授权；已授权时点整行去系统「应用详情」看/改（权限已给，
 *   再拉一次请求系统会直接回「已授予」，表现就是点了没反应）。
 * - 「网络访问」是安装时权限，**行不可点、不画 ›**：没事可做的地方给一个能点的行，
 *   比不给点更糟（点了没反应，用户会以为坏了）。
 * - 底部只有一个主按钮，文案随状态变：「完成，进入证道」（必需项已就绪）／
 *   「稍后再说」（未就绪也能进）。两个文案做的事相同（记已引导 + 进主界面），
 *   区别只在显示的那句话是否与当前状态相符——**不拦人**是这个 App 一以贯之的做法
 *   （与首启权限「拒绝不打断使用」同一立场）。
 * - 硬件返回键不拦：按返回就退回起始页，且**不写**已引导标记（用户并没有选择「稍后」），
 *   下次冷启动会再出现一次。这是刻意的：要消灭的是「对话框串」，不是这一页。
 *
 * 系统栏由本页自行避让（edge-to-edge 下的既有约定，各页面各自补偿，见 MainActivity 的入口注释）；
 * 返回前台（从系统授权页回来）时重读一次状态，见下面的 ON_RESUME 观察者。
 */
@Composable
fun PermissionSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current

    // 这三项由三个不同的系统页/对话框改变，且返回顺序不可预期，
    // 统一在 ON_RESUME 与每个 launcher 的回执里重读（不用单一 tick——各自重读更抗"只变了一项"）。
    var storageOk by remember { mutableStateOf(PermissionSetup.storageGranted(ctx)) }
    var manageOk by remember { mutableStateOf(PermissionSetup.manageAllFilesGranted()) }
    var notifyOk by remember { mutableStateOf(PermissionSetup.notificationsGranted(ctx)) }
    // 授权失败的人话提示（照 SettingsScreen 权限卡的 permHint 写法）：红字，成功时清空。
    var hint by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        storageOk = PermissionSetup.storageGranted(ctx)
        manageOk = PermissionSetup.manageAllFilesGranted()
        notifyOk = PermissionSetup.notificationsGranted(ctx)
    }

    // 存储读写：RequestMultiplePermissions（与设置页 permLauncher 同一契约）。
    // 回执里**重新读系统状态**而不是信 result：部分 ROM 会把弹窗静默掉，
    // 而 result 只在"弹过了"时才有值。
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
        hint = if (PermissionSetup.storageGranted(ctx)) {
            "已授权"
        } else {
            "系统未放行——到「设置 → 应用 → 证道 → 权限」手动开启（部分系统会把弹窗静默掉）"
        }
    }

    // 通知：单个权限用 RequestPermission 即可。
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refresh()
        hint = if (PermissionSetup.notificationsGranted(ctx)) {
            null
        } else {
            "通知未放行——不影响使用，只是收不到「装完清理」这类静默提示"
        }
    }

    // 从系统页回来（「所有文件访问」设置页、应用详情页）重读状态：
    // 这些页面没有回执回调，只能靠 ON_RESUME（与 SettingsScreen 权限卡里的重读同款做法）。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 系统「应用详情」页：已授权的行点进去看/改权限。
    val openAppDetails: () -> Unit = {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
            )
        }.onFailure {
            Toast.makeText(ctx, "打开失败：${HumanizeError.title(it)}", Toast.LENGTH_SHORT).show()
        }
    }

    // 「所有文件访问」系统页：优先带包名的专属页，个别 ROM 没有这个 Activity 时回落通用页
    //（与 HomeScreen.openStorageGrant / SettingsScreen.launchManage 同一行为，三处必须一致）。
    val openManageAllFiles: () -> Unit = {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${ctx.packageName}"))
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure {
                Toast.makeText(ctx, "打开失败：${HumanizeError.title(it)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val allReady = storageOk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 上半屏可滚（小屏上四行也能看全），底部按钮固定——按钮被滚出屏幕等于用户找不到出口。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "权限",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "证道要读写你的文件、在清理完成时给你提示，需要下面几项权限。" +
                    "可以逐项点开授权；也可以稍后再设（设置 → 权限里随时能改）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // 卡片标题叫「授权状态」而不是「权限」：页头已经是大字「权限」，
            // 上下再来一个「权限」就是设置页犯过的重复（旧版外层"设置"与页内大字"设置"叠着，
            // 见 MainActivity 里设置页顶栏那段注释）。
            SectionCard("授权状态") {
                PermissionRow(
                    label = "存储读写（共享存储 /sdcard）",
                    granted = storageOk,
                    status = if (storageOk) "已授权" else "未授权",
                    desc = "Agent 读写手机文件（/sdcard）依赖此项；缺失时终端里读写手机文件会全部失败" +
                        "（必需项）。",
                    onClick = {
                        if (storageOk) {
                            openAppDetails()
                        } else {
                            permLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                )
                            )
                        }
                    },
                )
                RowGapDivider()
                PermissionRow(
                    label = "所有文件访问（可选 · 主路径）",
                    granted = manageOk,
                    status = if (manageOk) "已开启" else "未开启",
                    desc = "开启后 Agent 可访问更广的存储范围。基础 /sdcard 读写不开也能用；" +
                        "本机系统对证道不提供这个开关，未开启属正常，不影响使用。",
                    onClick = openManageAllFiles,
                )
                RowGapDivider()
                PermissionRow(
                    label = "通知",
                    granted = notifyOk,
                    status = if (notifyOk) "已授权" else "未授权",
                    desc = "「装完清理」等静默提示走通知；未授权时系统会直接丢掉通知，" +
                        "但不影响任何功能。",
                    onClick = {
                        if (notifyOk) {
                            openAppDetails()
                        } else {
                            notifyLauncher.launch(PermissionSetup.NOTIFICATIONS_PERMISSION)
                        }
                    },
                )
                RowGapDivider()
                // 网络访问：安装时权限（INTERNET），必然已授予 ⇒ granted 恒 true、onClick = null
                //（不可点、不画 ›：没事可做的地方给个能点的行，点了没反应比不给点更糟）
                PermissionRow(
                    label = "网络访问",
                    granted = true,
                    status = "自动授予",
                    desc = "INTERNET 为安装时权限，无需操作。若走代理：请在代理 App 的" +
                        "「分应用代理」里勾选证道。",
                    onClick = null,
                )
            }

            hint?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Button(
                onClick = {
                    // 点过就走人（含「稍后再说」）：标记只在这里写，见 PermissionSetup.markGuided 说明
                    PermissionSetup.markGuided(ctx)
                    onDone()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (allReady) "完成，进入证道" else "稍后再说",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!allReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "「存储读写」是必需项，现在也可以先进去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 权限行：一行标题 + 右侧状态 + 一行小字说明，可选点击（[onClick] 为 null 时不可点、不画 ›）。
 *
 * 为什么不直接复用 SettingsScreen 的 `internal fun SettingRow`：SettingRow 的行尾**恒有 › 且恒可点**，
 * 而本页的「网络访问」是纯展示行、每行还要多带一行小字说明。改 SettingRow 会波及设置页
 * （用户明确要求设置页保持原样），故这里写本页专用的等价小组件，排版节奏与之一致。
 */
@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    status: String,
    desc: String,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth()
    Column(
        modifier = (if (onClick != null) {
            base.pressClickable(onClickLabel = label, onClick = onClick)
        } else {
            base
        }).padding(vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) zhengdaoReadyGreen() else MaterialTheme.colorScheme.error,
            )
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "›",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 行间分隔：与设置页权限卡里那处 `0.5.dp + outlineVariant` 的分隔线同款。 */
@Composable
private fun RowGapDivider() {
    Spacer(Modifier.height(2.dp))
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(2.dp))
}
