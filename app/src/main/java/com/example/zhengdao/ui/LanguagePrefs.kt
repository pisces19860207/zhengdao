package com.example.zhengdao.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * 应用内语言（per-app language，Android 13+）。
 *
 * 2026-10-12 新增（用户要求「设置页要可以选择 App 的英文界面」）。
 *
 * 为什么不做兼容层：minSdk = 36 ⇒ `LocaleManager`（API 33+）全量可用，不需要
 * androidx.appcompat 的 AppCompatDelegate.setApplicationLocales()。系统在
 * [set] 之后会自动重建当前 Activity，界面语言当场生效（不用重启 App）。
 *
 * 支持的语言列表与 `res/xml/locales_config.xml` 必须保持一致——那份是给系统
 * 「设置 → 应用 → 证道 → 语言」看的，这份是给 App 内的选择器用的。
 */
object LanguagePrefs {

    /** 跟随系统（清空 per-app locale）。 */
    const val SYSTEM = ""

    /** 简体中文。 */
    const val ZH = "zh-CN"

    /** 英文。 */
    const val EN = "en"

    private fun manager(ctx: Context): LocaleManager? =
        runCatching { ctx.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager }.getOrNull()

    /**
     * 当前界面语言：返回 [SYSTEM] / [ZH] / [EN] 三选一。
     *
     * 读的是系统记的 per-app locale（不是 App 自己的 SharedPreferences）——
     * 这样用户在**系统设置**里改过之后，App 里的选择器也立刻对得上。
     */
    fun current(ctx: Context): String {
        val locales = manager(ctx)?.applicationLocales ?: return SYSTEM
        if (locales.isEmpty) return SYSTEM
        val tag = locales[0].toLanguageTag()
        return when {
            tag.startsWith("zh", ignoreCase = true) -> ZH
            tag.startsWith("en", ignoreCase = true) -> EN
            else -> SYSTEM
        }
    }

    /**
     * 设置界面语言。[tag] 取 [SYSTEM] / [ZH] / [EN]；[SYSTEM] 表示清空、跟随系统。
     *
     * 失败时静默（个别 ROM 可能不支持该 API）——语言不是核心功能，
     * 不因为它把一个设置页搞崩。
     */
    fun set(ctx: Context, tag: String) {
        val lm = manager(ctx) ?: return
        runCatching {
            lm.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
        }
    }
}
