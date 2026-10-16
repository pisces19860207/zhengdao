// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.core

import android.content.Context
import android.content.SharedPreferences

/**
 * App 设置存储的唯一真源（文件 `zhengdao-settings`）。
 *
 * 第㊸刀（Qoder 审查 🟡-3，台账 §B 顺序 24）：terminal（底层）原来反向
 * `import com.example.zhengdao.ui.Settings` 拿这份 prefs——依赖方向反了。
 * 把访问器下沉到 core（低层共享包，terminal 已有 import core 的先例），
 * `ui.Settings.prefs` 保留同名 API 委托到这里：ui 侧调用点零改动，
 * 文件名与键一字不变 ⇒ **行为零变化**。
 *
 * 纪律：本文件只许放「无业务逻辑的存储访问器」；core 包不得 import
 * ui / terminal / oc（唯一例外是既有 KDoc 里的文字提及）。
 */
object AppPrefs {

    /** SharedPreferences 文件名（历史文件，改名=数据丢失，绝不动）。 */
    const val FILE_NAME: String = "zhengdao-settings"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
