// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.content.Context
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView

/**
 * 终端外观偏好（字号 + 配色），存 SharedPreferences，设置页可调，进终端时应用。
 *
 * ⚠️ 两个易错点（2026-10-05 实测得出，改这里时务必留意）：
 * 1. **字号单位是 px 不是 dp**——`TerminalView.setTextSize()` 内部直接
 *    `mTextPaint.setTextSize(textSize)`，形参注释写的 dp 是错的。必须 × density。
 * 2. **TerminalView 只提供背景，不提供前景**——`TerminalRenderer.render()` 仅在反色
 *    模式填充背景，常规渲染不画背景。所以背景色必须同时写进「视图 background」和
 *    「调色板 COLOR_INDEX_BACKGROUND」两处，缺一会白底白字或黑底黑字。
 */
object TerminalPrefs {

    private const val PREFS = "zhengdao-ui"
    const val KEY_SIZE_DP = "terminal_text_size_dp"
    const val KEY_SCHEME = "terminal_color_scheme"

    /** 默认字号（dp）。12dp 在 3.5 密度屏上约 50 列 × 29 行，接近桌面终端的信息密度。 */
    const val DEFAULT_SIZE_DP = 12
    private const val DEFAULT_SCHEME = "classic"

    /** 字号档位（dp）。越小显示内容越多。 */
    val SIZE_OPTIONS: List<Int> = listOf(10, 11, 12, 14, 16, 18)

    enum class Scheme(
        val id: String,
        val label: String,
        /** 背景（ARGB） */
        val bg: Int,
        /** 前景 / 默认文字色（ARGB） */
        val fg: Int,
        /** 光标色（ARGB） */
        val cursor: Int,
    ) {
        CLASSIC("classic", "经典 · 黑底白字", 0xFF000000.toInt(), 0xFFD8D8D8.toInt(), 0xFF9E9E9E.toInt()),
        AMBER("amber", "琥珀 · 黑底橙字", 0xFF14100A.toInt(), 0xFFFFB000.toInt(), 0xFFFFD48A.toInt()),
        GREEN("green", "复古绿 · 黑底绿字", 0xFF001208.toInt(), 0xFF3DF53D.toInt(), 0xFF8CFF8C.toInt()),
        PAPER("paper", "浅色 · 白底黑字", 0xFFF6F6F6.toInt(), 0xFF1A1A1A.toInt(), 0xFF666666.toInt()),
        ;

        companion object {
            fun of(id: String?): Scheme = values().firstOrNull { it.id == id } ?: CLASSIC
        }
    }

    fun sizeDp(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
            .coerceIn(SIZE_OPTIONS.first(), SIZE_OPTIONS.last())

    fun scheme(ctx: Context): Scheme =
        Scheme.of(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SCHEME, DEFAULT_SCHEME)
        )

    fun saveSize(ctx: Context, dp: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SIZE_DP, dp).apply()
    }

    fun saveScheme(ctx: Context, scheme: Scheme) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SCHEME, scheme.id).apply()
    }

    /** 把「字号 + 配色」一次性应用到终端视图。进终端时调用，设置页改完下次进终端生效。 */
    fun applyTo(termView: TerminalView, ctx: Context) {
        applyScheme(termView, scheme(ctx))
        // dp → px（见类注释第 1 点）；setTextSize 内部会自动 updateSize 重算行列
        termView.setTextSize((sizeDp(ctx) * ctx.resources.displayMetrics.density).toInt())
    }

    /**
     * 应用配色：写调色板 + 写视图背景（两处都要，见类注释第 2 点）。
     * 调色板是全局静态单例，改完后必须让**已存在**的会话 `mColors.reset()` 才会重新拷贝。
     */
    fun applyScheme(termView: TerminalView, scheme: Scheme) {
        val palette = TerminalColors.COLOR_SCHEME.mDefaultColors
        palette[TextStyle.COLOR_INDEX_BACKGROUND] = scheme.bg
        palette[TextStyle.COLOR_INDEX_FOREGROUND] = scheme.fg
        palette[TextStyle.COLOR_INDEX_CURSOR] = scheme.cursor

        SessionManager.session?.emulator?.mColors?.reset()

        termView.setBackgroundColor(scheme.bg)
        termView.invalidate()
    }
}
