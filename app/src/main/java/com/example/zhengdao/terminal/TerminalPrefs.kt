// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.View
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView

/**
 * 终端外观偏好（字号 + 配色 + 字体），存 SharedPreferences，设置页可调，进终端时应用。
 *
 * ⚠️ 三个易错点（2026-10-05 / 2026-10-10 实测得出，改这里时务必留意）：
 * 1. **字号单位是 px 不是 dp**——`TerminalView.setTextSize()` 内部直接
 *    `mTextPaint.setTextSize(textSize)`，形参注释写的 dp 是错的。必须 × density。
 * 2. **TerminalView 只提供背景，不提供前景**——`TerminalRenderer.render()` 仅在反色
 *    模式填充背景，常规渲染不画背景。所以背景色必须同时写进「视图 background」和
 *    「调色板 COLOR_INDEX_BACKGROUND」两处，缺一会白底白字或黑底黑字。
 * 3. **字体必须在 `setTextSize()` 之后设**——`TerminalRenderer` 是在 `setTextSize()`
 *    里懒创建的（构造函数不建），而 `setTypeface()` 会读 `mRenderer.mTextSize`，
 *    在渲染器存在之前调用它直接 NPE（见 `TerminalSizeResolver`）。
 */
object TerminalPrefs {

    private const val PREFS = "zhengdao-ui"

    /**
     * 终端正文字体（assets 相对路径）。
     *
     * 为什么用 assets 而不是 `res/font/`：字体 10+ MB，放 `res/font` 会被 AAPT
     * 处理（压缩/校验，且 res 资源名有限制），assets 按原样打进 APK 更合适。
     * 为什么选 JetBrains Maple Mono NF：中英文严格 2:1（中文恰好占 2 个字符格），
     * 且带 Nerd Font 图标区，配 tmux/CLI 工具链不缺字形。
     * 来源与许可见仓库根目录 `PROVENANCE.md`（OFL 1.1）。
     */
    private const val FONT_ASSET = "fonts/JetBrainsMapleMono-NF-Regular.ttf"

    private const val TAG = "TerminalPrefs"

    /** 已加载的字体（进程内缓存：`createFromAsset` 解 20 MB 字体，别每次进终端都做）。 */
    private var cachedTypeface: Typeface? = null
    const val KEY_SIZE_DP = "terminal_text_size_dp"
    const val KEY_SCHEME = "terminal_color_scheme"
    /** 画布留白（dp，2026-10-08 新增）。 */
    const val KEY_INSET_DP = "terminal_canvas_inset_dp"
    /** 间距档位（2026-10-10 新增，见 [Spacing]）。 */
    const val KEY_SPACING = "terminal_spacing"
    /** 顶部三个圆点（关 / 分屏 / 收回）的功能说明是否已在首次进入时弹过（v1.1.1 阶段 2.2） */
    private const val KEY_DOTS_HINT_SHOWN = "terminal_dots_hint_shown"

    /** 默认字号（dp）。12dp 在 3.5 密度屏上约 50 列 × 29 行，接近桌面终端的信息密度。 */
    const val DEFAULT_SIZE_DP = 12
    private const val DEFAULT_SCHEME = "classic"

    /**
     * 默认画布留白（dp）。0 表示文字贴边（旧观感）。
     *
     * 为什么默认给 8dp 而不是 0：文字顶到屏幕边缘时，长行与边框黏在一起、观感廉价；
     * 留白让画布看起来是"一块有边界的面板"。代价是可用宽度变小、列数随之减少
     * （8dp × 2 在 375dp 宽屏上约占 4%），所以留了「无」这一档给不想损失宽度的人。
     */
    const val DEFAULT_INSET_DP = 8

    /** 字号档位（dp）。越小显示内容越多。 */
    val SIZE_OPTIONS: List<Int> = listOf(10, 11, 12, 14, 16, 18)

    /** 画布留白档位（dp）。0 = 贴边。 */
    val INSET_OPTIONS: List<Int> = listOf(0, 8, 14)

    /**
     * 间距档位（2026-10-10 阶段二）。默认 [COMFORTABLE]。
     *
     * 三处间距都收在这里，避免散落到 XML（`themes.xml` / `term_keys.xml` 正在被 wt-ios-skin 分支改）：
     * 1. **行高倍率**——交给 `TerminalView.setLineHeightMultiplier()`（内部 `TerminalSizeResolver`），
     *    只放大行盒高度，字形宽度不变，所以中英文 2:1 不受影响；同样屏幕高度下行数按比例变少。
     * 2. **画布上下内边距**——只设上下（左右沿用用户选的「画布留白」，默认 8dp），写在
     *    外层容器 `canvas_host` 上（不能写在 `TerminalView` 上，见 [applyTo] 注释）。
     * 3. **快捷键条单键高度**——与 `TermKeyFlex` 现有的 `layout_height=48dp` 一致；收归这里是为了
     *    以后换档位时不必动 XML（[applyKeyBarHeight] 在代码里落实）。
     */
    enum class Spacing(
        val id: String,
        val label: String,
        /** 行高倍率：1.0 = 字体原生行高。 */
        val lineHeightMultiplier: Float,
        /** 画布容器上下内边距（dp）；左右内边距由 [insetDp] 决定。 */
        val paddingVerticalDp: Int,
        /** 快捷键条单键高度（dp）。 */
        val keyHeightDp: Int,
    ) {
        COMFORTABLE("comfortable", "舒适", 1.15f, 4, 48),
        COMPACT("compact", "紧凑", 1.0f, 0, 48),
        ;

        companion object {
            val DEFAULT = COMFORTABLE
            fun of(id: String?): Spacing = values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }

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

    fun insetDp(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_INSET_DP, DEFAULT_INSET_DP)
            .coerceIn(INSET_OPTIONS.first(), INSET_OPTIONS.last())

    fun saveInset(ctx: Context, dp: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_INSET_DP, dp).apply()
    }

    fun spacing(ctx: Context): Spacing =
        Spacing.of(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SPACING, Spacing.DEFAULT.id)
        )

    fun saveSpacing(ctx: Context, spacing: Spacing) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SPACING, spacing.id).apply()
    }

    /** 三点说明是否已看过（只看一次，之后靠长按圆点复查）。 */
    fun dotsHintShown(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DOTS_HINT_SHOWN, false)

    fun markDotsHintShown(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DOTS_HINT_SHOWN, true).apply()
    }

    /**
     * 终端正文字体：assets 里的 JetBrains Maple Mono（NF Regular）。
     *
     * 失败（asset 缺失/损坏/OOM）时**回退系统等宽**，不抛异常——字体属于外观，
     * 不该让终端起不来。加载结果进程内缓存，重复调用零成本。
     *
     * 首次加载会打一条 `Log.i`，记下 `createFromAsset` 的真实耗时（含从 APK 里解压
     * 17.94 MB 资产的成本）。这条日志是**故意留在代码里的**：将来评估
     * `androidResources.noCompress` 或做子集化时，它是唯一的对照基线
     * （见 `docs/acceptance/terminal-font-2026-10-10.md` §六）。
     */
    fun typeface(ctx: Context): Typeface {
        cachedTypeface?.let { return it }
        val startedAtNanos = System.nanoTime()
        val loaded = try {
            Typeface.createFromAsset(ctx.assets, FONT_ASSET)
        } catch (t: Throwable) {
            Log.w(TAG, "字体 $FONT_ASSET 加载失败，回退系统等宽", t)
            Typeface.MONOSPACE
        }
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
        Log.i(
            TAG,
            "字体首次加载：$FONT_ASSET，createFromAsset 耗时 ${elapsedMs}ms" +
                "（在此线程阻塞；此后走进程内缓存）",
        )
        cachedTypeface = loaded
        return loaded
    }

    /**
     * 把「字号 + 配色 + 间距（行高/留白）+ 字体」一次性应用到终端。进终端时调用，设置页改完下次进终端生效。
     *
     * @param canvasHost 承载画布的**外层容器**（`R.id.canvas_host`）。留白与底色都写在它身上，
     *   而不是写在 [termView] 上——`TerminalView.updateSize()` 用 `getWidth()` 算列数、
     *   **不减 padding**，直接给视图加留白会同时算错列数、又看不见留白
     *   （详见 `activity_main.xml` 里那段注释）。
     */
    fun applyTo(termView: TerminalView, canvasHost: View, ctx: Context) {
        val scheme = scheme(ctx)
        applyScheme(termView, scheme)
        val spacing = spacing(ctx)
        val density = ctx.resources.displayMetrics.density
        val insetPx = (insetDp(ctx) * density).toInt()
        val vInsetPx = (spacing.paddingVerticalDp * density).toInt()
        // 容器只负责留白与底色：留白区露的就是它，颜色必须与视图一致，
        // 否则会看到一圈异色边框（换配色时两处一起变，见 [applyScheme]）。
        canvasHost.setBackgroundColor(scheme.bg)
        // 左右 = 用户选的「画布留白」（默认 8dp）；上下 = 间距档位（舒适档 4dp，比左右窄，避免画布显得被压扁）。
        canvasHost.setPadding(insetPx, vInsetPx, insetPx, vInsetPx)
        // 行高倍率先设：渲染器还没建时它只记值，随后 setTextSize/setTypeface 建渲染器时都会带上。
        termView.setLineHeightMultiplier(spacing.lineHeightMultiplier)
        // dp → px（见类注释第 1 点）；setTextSize 内部会自动 updateSize 重算行列
        termView.setTextSize((sizeDp(ctx) * density).toInt())
        // 字体必须排在 setTextSize 之后（见类注释第 3 点）：上一步才把 mRenderer 建出来。
        // setTypeface 会按新字体的度量重建渲染器并 updateSize()，列数因此跟着新字宽重算。
        termView.setTypeface(typeface(ctx))
    }

    /**
     * 快捷键条单键高度（间距档位里的 `keyHeightDp`，默认 48dp）。
     *
     * 遍历 [keyBar] 下的所有 [android.widget.TextView] 直接改 LayoutParams.height —— 值收归
     * TerminalPrefs 管，但**不动 `themes.xml` 的 `TermKeyFlex`**（那条样式正在被 wt-ios-skin 分支改，
     * 两边同时改必冲突）。改高度不会影响按键的 id 与顺序，`wireKeyBar()` 的绑定照旧。
     *
     * ⚠️ 只改**单键**高度，不改 `key_bar_container`：窄屏是**两行**键（见 `term_keys.xml` 注释），
     * 容器实际高度 ≈ 2×48dp + 上下各 4dp padding；把容器压成 48dp 会裁掉第二行。
     */
    fun applyKeyBarHeight(keyBar: View, ctx: Context) {
        val heightPx = (spacing(ctx).keyHeightDp * ctx.resources.displayMetrics.density).toInt()
        applyKeyHeightRecursive(keyBar, heightPx)
    }

    private fun applyKeyHeightRecursive(view: View, heightPx: Int) {
        if (view is android.widget.TextView) {
            val lp = view.layoutParams ?: return
            if (lp.height != heightPx) {
                lp.height = heightPx
                view.layoutParams = lp
            }
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) applyKeyHeightRecursive(view.getChildAt(i), heightPx)
        }
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
