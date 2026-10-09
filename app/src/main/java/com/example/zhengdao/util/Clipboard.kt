// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Android 平台 ClipboardManager / ClipData（官方 API）。
package com.example.zhengdao.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 把纯文本放进系统剪贴板。返回 `true` = 真的写进去了。
 *
 * ## 为什么返回 Boolean 而不是 Unit
 *
 * 复制是本 App 里少数"用户马上就会去别处粘贴"的操作：失败必须被调用方知道，
 * 才能不弹「已复制到剪贴板」这种假成功（与 `RunLog` / 安装流程同一条原则：
 * **不接受静默假装成功**）。目前真机上取不到剪贴板服务属于"理论上不会发生"，
 * 但返回值的意义在于调用方**必须**显式处理，而不是让它悄悄吞掉。
 *
 * ## 为什么用平台 ClipboardManager，而不是 Compose 的 LocalClipboardManager
 *
 * 项目已有两处同款实现（`HomeScreen` 的「复制系统信息」、`SettingsScreen` 的「复制日志」），
 * 都走平台 API；统一到一个函数是为了第三个调用点不再抄第三份。
 * Compose 侧 `LocalClipboardManager` 在旧 Compose 版本上对纯文本以外的行为不一致，
 * 不为了省两行代码引入第二种复制路径。
 *
 * Android 13+ 系统自己会弹「已复制」浮层，本函数**不做**任何提示 ——
 * 提示由调用方按自己的语义决定（Taiji 页用 Snackbar）。
 */
fun copyPlainText(context: Context, label: String, text: String): Boolean {
    if (text.isEmpty()) return false
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    return runCatching { cm.setPrimaryClip(ClipData.newPlainText(label, text)) }.isSuccess
}
