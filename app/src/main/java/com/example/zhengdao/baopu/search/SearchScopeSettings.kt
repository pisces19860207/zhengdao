// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十七批（派工单 §1⑤）：设置页「把手机文件纳入搜索范围」开关的**写入口**。
//
// 为什么单独一个文件、不并进 [SearchScope]：168 批定下的房型——写口与判据分开，
// 源码门禁才门得住。`SearchScopeTest.写口只有一处_prefs布尔只能由SearchScopeSettings写`
// 扫 `baopu/search/` 全部源文件，断言「往 prefs 写布尔」这件事只出现在本文件；
// 哪天 [SearchScope] 里冒出一个 `putBoolean`，那条门禁就红——它意味着账本能给自己发授权。
//
// 默认值必须是 **false**（派工单 §1⑤「读不放大写」的另一半：没点头就不读）。
// 没有 devMode／adb／环境变量旁路：只有用户在设置里拨过才算数（168 批那条口径原样搬来）。
package com.example.zhengdao.baopu.search

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.core.AppPrefs

/** `search_phone_on` 的写口（设置页开关调用它）；读也走这里，保证「屏上显示的」与「账本看到的」同源。 */
internal object SearchScopeSettings {

    /** 键名（进 prefs 文件 `zhengdao-settings`；改名等于把用户已经拨过的开关默默关掉，本批之后不许动）。 */
    const val KEY_PHONE_ON = "search_phone_on"

    /** 拨动开关（唯一写口）。写失败按没写处理——下次读到的还是旧值，屏上状态回正。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            AppPrefs.prefs(ctx).edit { putBoolean(KEY_PHONE_ON, on) }
        }
    }

    /** 现读（缺省 **关**：意愿没被表达过时，读侧一律不扩范围）。 */
    fun get(ctx: Context): Boolean =
        runCatching { AppPrefs.prefs(ctx).getBoolean(KEY_PHONE_ON, false) }.getOrDefault(false)
}
