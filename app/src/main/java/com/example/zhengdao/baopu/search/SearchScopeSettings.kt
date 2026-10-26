// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十九批（2026-10-26 用户口径「给了存储权限就默认可全盘找，开关有点多余」）：
// **本对象已停用**——读侧判定（[SearchScopeRules.phoneAccessOf]）不再有「用户开关」这个输入，
// 设置页那张开关卡整卡删除，这里也**没有了写入口**。
//
// 还留下的只有两样，都是为了老账本/老 prefs 读得出来，不是能力：
// - [KEY_PHONE_ON]：键名常量（改名等于把老 prefs 变成读不懂的哑键）；
// - [get]：**兼容读取**。老用户以前拨过的值还能读出来，但**不再产生任何效果**——
//   判定路径（`SearchScope.status` / `phoneAccessOf` / `plan`）一个都不调它。
//   「删了 UI 仍偷偷读开关」这条负例由 `SearchScopeTest` 的源码门禁钉着：
//   `baopu/search/` 里除了本文件，任何文件都不许出现 `KEY_` 与 `putBoolean`。
//
// 没有 devMode／adb／环境变量旁路——现在连正门都没有了：范围只跟系统授权走。
package com.example.zhengdao.baopu.search

import android.content.Context
import com.example.zhengdao.core.AppPrefs

/**
 * `search_phone_on` 的**兼容读口**（第二百二十九批起停用）。
 *
 * 历史上它曾是设置页开关的读写口（197 批）；229 删掉开关后，`set()` 已删除，
 * 判定也不再调用 [get]。留它只为「老 prefs 里那个值」有一个明确的、唯一的读法，
 * 免得将来有人想迁移这段历史时找不到键。
 */
internal object SearchScopeSettings {

    /** 键名（进 prefs 文件 `zhengdao-settings`；老用户已拨过的值还躺在这里，键名不许动）。 */
    const val KEY_PHONE_ON = "search_phone_on"

    /**
     * 兼容读取（**判据不调用**）：老值还在，只是不再有任何效果——
     * 拨过「开」的老用户不会因此多搜一个字节，拨过「关」的也不会因此少搜一个字节。
     */
    fun get(ctx: Context): Boolean =
        runCatching { AppPrefs.prefs(ctx).getBoolean(KEY_PHONE_ON, false) }.getOrDefault(false)
}
