// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit

/**
 * 外观偏好：跟随系统 / 浅色 / 深色（2026-10-12 接入深色主题）。
 *
 * 为什么要有手动项：用户的可选值里「跟随系统」是默认，但**手动锁定浅色**是刚需——
 * 证道的终端页自成黑底，有人就喜欢整机深色、只有这个 App 保持浅色。
 *
 * 存 SharedPreferences（项目惯例：`zhengdao-*` 一个用途一个文件）。
 *
 * 生效方式：写偏好时递增 [changeTick]（Compose 可观察）⇒ 订阅它的 `ZhengdaoTheme`
 * 重算一次、整个界面**当场换主题**（不需要 recreate Activity，设置页也就不会被弹回首页）。
 * 偏好本体始终以 SharedPreferences 为唯一真相，冷启动自然读回。
 */
object AppearancePrefs {

    /** 跟随系统深色开关。 */
    const val SYSTEM = "system"

    /** 始终浅色。 */
    const val LIGHT = "light"

    /** 始终深色。 */
    const val DARK = "dark"

    private const val SP = "zhengdao-appearance"
    private const val KEY = "appearance"

    /**
     * 偏好变更通知（Compose 可观察）。只作「变了」的信号，值本身没有含义——
     * 读取方一律 `remember(changeTick.value) { AppearancePrefs.get(ctx) }`。
     * 这样避免在组合期间写状态（首次组合只读不写）。
     */
    internal val changeTick = mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun normalize(v: String?): String =
        if (v == LIGHT || v == DARK) v else SYSTEM

    /** 读当前偏好；读不到或值非法一律按「跟随系统」。 */
    fun get(ctx: Context): String =
        normalize(runCatching { prefs(ctx).getString(KEY, SYSTEM) }.getOrNull())

    /** 写偏好：落盘 + 递增变更信号（触发主题重算，界面立即切换）。 */
    fun set(ctx: Context, value: String) {
        val v = normalize(value)
        runCatching { prefs(ctx).edit { putString(KEY, v) } }
        changeTick.intValue++
    }

    /** 该偏好在当前系统设置下，最终是否走深色。[systemDark] 传 `isSystemInDarkTheme()`。 */
    fun resolveDark(pref: String, systemDark: Boolean): Boolean = when (pref) {
        LIGHT -> false
        DARK -> true
        else -> systemDark
    }
}
