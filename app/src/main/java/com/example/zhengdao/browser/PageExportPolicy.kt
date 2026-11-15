// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

/**
 * 玄览「设备尺寸预设 ＋ 导出」的**纯判据**（651）。
 *
 * 用户口径（2026-11-15）：「设备尺寸预设（手机/平板/桌面，可自定义）＋ 导出 PNG（整页/可视区）/PDF；
 * **未加载完 ⇒ 拒绝导出并提示**」。三段判据全在这里，UI 与执行层都不许自己判：
 *
 * 1. **预设尺寸** [PRESETS]：手机＝跟随屏幕（零改动，见 [Viewport.followScreen]），
 *    平板 768×1024、桌面 1280×800（CSS px，等于桌面 Chrome 的常见视口）。
 *    自定义只填**宽**，高按 4:3 推（[customViewport]）——一个输入框能填完的事，
 *    不给用户两个数字去猜。
 * 2. **预设只决定"导出时按什么尺寸重排"** [captureTarget]：屏幕上的排版恒等于手机宽。
 *    最初想做成"屏上等比缩小预览"，真机上（21:41／21:46 两次实测）把 WebView 撑到 1280dp
 *    再套 0.29 缩放，得到一个 4480×2800px 的图层 —— **画出来是空白**。所以改成：
 *    预览不动屏，导出临时重排再拍（稳、且导出的图是真的桌面版式）。
 * 3. **未加载完不许导出** [refusalOf]：返回**一种**拒绝理由（枚举），文案由 UI 映射。
 *    七种情形逐条对应 [BrowserTabState] 既有字段——不新增状态位，也不猜。
 *
 * 为什么是纯函数：这七种拒绝情形与三条缩放账都能在 JVM 里逐格枚举（表驱动），
 * 不需要真机、不需要 WebView（房型照 [BrowserSnapshotPolicy] / [LocalPreviewPolicy]）。
 */
object PageExportPolicy {

    /** 预设里的三档（UI 的顺序即此顺序）。 */
    enum class Preset { PHONE, TABLET, DESKTOP, CUSTOM }

    /** 预设的 CSS 尺寸（px ＝ WebView 里的 CSS px，与 dp 在等宽下同值）。 */
    data class Size(val widthDp: Int, val heightDp: Int)

    /** 平板：iPad 的常见 CSS 视口。 */
    val TABLET = Size(768, 1024)

    /** 桌面：1280×800（桌面 Chrome 最保守的一档）。 */
    val DESKTOP = Size(1280, 800)

    /** 自定义允许的宽度区间（再窄没有网页能看，再宽手机根本缩不动）。 */
    const val MIN_CUSTOM_WIDTH_DP = 240
    const val MAX_CUSTOM_WIDTH_DP = 2048

    /** 自定义默认宽（＝平板宽，用户改起来有个合理起点）。 */
    const val DEFAULT_CUSTOM_WIDTH_DP = 768

    /** 自定义的高宽比（4:3）：只让用户填宽时，高按这条推。 */
    const val CUSTOM_ASPECT = 0.75f

    /**
     * 视口尺寸。
     *
     * `null` 宽 ⇒ **跟随屏幕**（手机档，也是默认档）：这时玄览的排版与 651 之前一字不差，
     * 老路径零改动——这是本批"不动别人判定"的技术保证，不是口头承诺。
     */
    data class Viewport(val widthDp: Int?, val heightDp: Int?) {
        val followsScreen: Boolean get() = widthDp == null
    }

    /** 跟随屏幕（手机档 / 默认）。 */
    val FOLLOW_SCREEN = Viewport(null, null)

    /** 预设 → 视口。`CUSTOM` 走 [customViewport]。 */
    fun viewportOf(preset: Preset, customWidthDp: Int = DEFAULT_CUSTOM_WIDTH_DP): Viewport = when (preset) {
        Preset.PHONE -> FOLLOW_SCREEN
        Preset.TABLET -> Viewport(TABLET.widthDp, TABLET.heightDp)
        Preset.DESKTOP -> Viewport(DESKTOP.widthDp, DESKTOP.heightDp)
        Preset.CUSTOM -> customViewport(customWidthDp)
    }

    /** 自定义宽 → 视口（宽夹在 [MIN_CUSTOM_WIDTH_DP]..[MAX_CUSTOM_WIDTH_DP]，高按 [CUSTOM_ASPECT]）。 */
    fun customViewport(widthDp: Int): Viewport {
        val w = widthDp.coerceIn(MIN_CUSTOM_WIDTH_DP, MAX_CUSTOM_WIDTH_DP)
        return Viewport(w, (w * CUSTOM_ASPECT).toInt().coerceAtLeast(1))
    }

    /** 导出目标的像素尺寸（设备像素，不是 dp）。 */
    data class Px(val w: Int, val h: Int)

    /**
     * 预设 → **导出目标像素**。
     *
     * 跟随屏幕 ⇒ 就用屏幕像素（和 651 之前的取图一模一样）；
     * 其余三档 ⇒ 预设 dp × density（1280dp @3.5x = 4480px）。
     *
     * 为什么不再做"屏上等比缩小预览"：真机上把 WebView 撑到 1280dp 再套一层 0.29 的缩放，
     * 那一层是 4480×2800px 的硬件图层——**画出来是空白**（21:41／21:46 两次真机实测）。
     * 所以口径改成：屏幕上的排版永远是手机宽，预设只决定"导出时按什么尺寸重排再拍"。
     */
    fun captureTarget(vp: Viewport, density: Float, screenWidthPx: Int, screenHeightPx: Int): Px {
        val w = vp.widthDp
        val h = vp.heightDp
        if (w == null || h == null) {
            return Px(screenWidthPx.coerceAtLeast(1), screenHeightPx.coerceAtLeast(1))
        }
        return Px(
            Math.round(w * density).coerceAtLeast(1),
            Math.round(h * density).coerceAtLeast(1),
        )
    }

    /** 拒绝导出的七种理由（`NONE` ＝ 可以导）。 */
    enum class Refusal {
        /** 可以导出。 */
        NONE,

        /** 没有标签 / 没有页面。 */
        NO_TAB,

        /** 从快照恢复、还没点「打开」——页面从没加载过。 */
        RESTORED,

        /** 渲染进程被系统回收，页面是死的。 */
        RECYCLED,

        /** 正在加载（转圈中）——半张图不算图。 */
        LOADING,

        /** 从没成功加载过（起始页 / 空标签）。 */
        NOT_LOADED,

        /** 载入过，但这一页没在前台渲染完成（白图风险，262/263 的老结论）。 */
        NOT_RENDERED,
    }

    /**
     * 「未加载完 ⇒ 拒绝导出」的唯一判据。
     *
     * 顺序即优先级：先判"有没有这颗标签"，再判"这颗标签属于哪种没准备好的情形"。
     * [BrowserTabState.isRenderable] 已经是"载入过 ＋ 前台渲染过 ＋ 没被回收"的合取，
     * 这里把它拆开报，是为了让用户看到**具体哪一步没成**（一句"没加载完"太含糊）。
     *
     * @param hasTab 当前活跃标签存在
     */
    fun refusalOf(
        hasTab: Boolean,
        loading: Boolean,
        everLoaded: Boolean,
        renderedWhileForeground: Boolean,
        restored: Boolean,
        recycled: Boolean,
    ): Refusal = when {
        !hasTab -> Refusal.NO_TAB
        restored -> Refusal.RESTORED
        recycled -> Refusal.RECYCLED
        loading -> Refusal.LOADING
        !everLoaded -> Refusal.NOT_LOADED
        !renderedWhileForeground -> Refusal.NOT_RENDERED
        else -> Refusal.NONE
    }

    /** 整页截图的高度上限（像素）：1080 宽 × 6000 高 × 4 字节 ≈ 26MB，留足余量（同离屏取图口径）。 */
    const val MAX_CAPTURE_HEIGHT_PX = 6000

    /**
     * 整页位图的**像素总量**预算（16M ≈ 64MB ARGB）。
     *
     * 为什么要有：桌面档的视口是 1280dp，在 3.5x 屏上就是 4480px 宽；
     * 若只看 6000 高度上限，一张整页图就是 4480×6000×4B ≈ 107MB —— 必 OOM。
     * 有预算后：手机档（1312 宽）照旧能拍满 6000；桌面档自动降到约 3571 高，
     * 并在回执里说明"只截了前 N 像素"（不静默交半张图）。
     */
    const val MAX_CAPTURE_PIXELS = 16_000_000

    /**
     * 整页截图高度夹紧：同时受 [MAX_CAPTURE_HEIGHT_PX] 与 [MAX_CAPTURE_PIXELS] 约束。
     *
     * @param widthPx 视口宽（像素）；不传按 1 算 ⇒ 只剩高度上限（老判据不变）
     */
    fun captureHeightPx(contentHeightPx: Int, widthPx: Int = 1): Int {
        val byPixels = if (widthPx > 0) MAX_CAPTURE_PIXELS / widthPx else MAX_CAPTURE_HEIGHT_PX
        val cap = minOf(MAX_CAPTURE_HEIGHT_PX, maxOf(8, byPixels))
        return contentHeightPx.coerceIn(8, cap)
    }

    /** 内容是否被截断（高度上限或像素预算夹过头）。 */
    fun truncated(contentHeightPx: Int, widthPx: Int = 1): Boolean =
        captureHeightPx(contentHeightPx, widthPx) < contentHeightPx

    // ── PDF 分页（A4，单位 point，1pt = 1/72 inch） ──────────────────────────

    /** A4 宽高（point）；页面按"宽度贴合、纵向切页"排。 */
    const val A4_WIDTH_PT = 595
    const val A4_HEIGHT_PT = 842

    /** 页边距（point）：留 18pt ≈ 6.3mm，够打印不咬边。 */
    const val PDF_MARGIN_PT = 18

    /** 每页能装下的源图高度（像素）：按页面可用宽算缩放，再折回源像素。 */
    fun sourceSliceHeightPx(sourceWidthPx: Int, sourceHeightPx: Int): Int {
        if (sourceWidthPx <= 0) return 0
        val usableW = (A4_WIDTH_PT - PDF_MARGIN_PT * 2).toFloat()
        val usableH = (A4_HEIGHT_PT - PDF_MARGIN_PT * 2).toFloat()
        val scale = usableW / sourceWidthPx
        if (scale <= 0f) return sourceHeightPx.coerceAtLeast(1)
        return (usableH / scale).toInt().coerceAtLeast(1)
    }

    /** 需要的 PDF 页数（sourceHeight ≤ 0 ⇒ 至少 1 页，不留空文档）。 */
    fun pdfPageCount(sourceWidthPx: Int, sourceHeightPx: Int): Int {
        val slice = sourceSliceHeightPx(sourceWidthPx, sourceHeightPx)
        if (slice <= 0) return 1
        return ((sourceHeightPx + slice - 1) / slice).coerceAtLeast(1)
    }
}
