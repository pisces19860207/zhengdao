// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

/**
 * 会话级页面记忆的**纯判据**（651）。
 *
 * 用户口径（2026-11-15）：「每会话最近 20 页（有序），切换/重启后保留；**只记 URL 与标题**」。
 *
 * 三件事按顺序钉在这里，别处不许再判：
 * 1. **上限 20**（[MAX_PAGES]）：第 21 页进来时把最老的那一页挤出去；
 * 2. **有序**：列表恒按「访问先后」 oldest → newest 排（[Page] 列表的顺序就是真相，
 *    不靠时间戳排——同一毫秒连开两页时时间戳会撞，顺序就散了）；
 * 3. **只记 URL 与标题**：[Page] 只有两个字段，**结构上就没有正文位**。
 *    这不是"我们记得别写正文"这种口头保证：想记正文得先给这个类加字段，
 *    而 [com.example.zhengdao.browser.PageMemorySql] 的建表语句与本文件的字段表
 *    由 `PageMemoryGateTest` 逐列比对（多一列就红）。正文从不经过这里，就无从落盘。
 *
 * 为什么不碰 Android：判据全是可枚举的边界（空 URL、超长标题、重复 URL、超 20 页），
 * 普通 JVM 单测能直接打到真行为（房型照 [LocalPreviewPolicy] / [PageExportPolicy]）。
 */
object PageMemoryPolicy {

    /** 每会话保留的页数上限（用户口径 20，见类注释）。 */
    const val MAX_PAGES = 20

    /** 标题落库前的长度上限（防某个站拿 40KB 的 <title> 把账撑爆）。 */
    const val MAX_TITLE_CHARS = 200

    /** URL 落库前的长度上限（与 [BrowserSnapshotPolicy] 的 URL 夹紧同量级）。 */
    const val MAX_URL_CHARS = 2048

    /**
     * 一条页面记忆：**只有这两列**。
     *
     * @param url 已过 [BrowserSnapshotPolicy.sanitizeUrlForStore] 的地址（脱敏是存储层的事，
     *   本类不重复做——判据只有一处）
     * @param title 已过 `SensitiveRedactor.redactTabTitle` 的标题；空串允许（页面没给标题）
     */
    data class Page(val url: String, val title: String)

    /**
     * 把一次「页面加载完成」归一成待落的行；`null` = 这条不该记。
     *
     * 三条不该记：① 空地址（恢复卡片/回收卡片那种没地址的标签）；② 假地址
     * （本地预览页签的 `local-preview://…` —— 它不是网页，记进"最近 20 页"里点不开）；
     * ③ 纯 `about:blank`（关标签时的清场跳转，不是用户看的页）。
     */
    fun normalize(url: String, title: String, isPreview: Boolean): Page? {
        if (isPreview) return null
        val u = url.trim()
        if (u.isEmpty()) return null
        if (u == ABOUT_BLANK) return null
        if (LocalPreviewPolicy.isPreviewUrl(u)) return null
        return Page(
            url = u.take(MAX_URL_CHARS),
            title = title.trim().take(MAX_TITLE_CHARS),
        )
    }

    /**
     * 把 [fresh] 并进 [kept]（两者都是 oldest → newest）。
     *
     * 重复访问**不叠加**：同一个 URL 再次出现时，旧的那条被摘掉、新的落到末尾
     * （「最近 20 页」问的是"我最近在看哪些页"，不是"我点过多少次"）。
     * 去重按 URL 逐字比（同一页不同 query 算两页——那本来就是两个地址）。
     */
    fun merge(kept: List<Page>, fresh: Page, max: Int = MAX_PAGES): List<Page> {
        val withoutDup = kept.filterNot { it.url == fresh.url }
        val appended = withoutDup + fresh
        return if (appended.size <= max) appended else appended.takeLast(max)
    }

    /** 界面要的顺序：新的在最上面（存储里仍按 oldest → newest 存，只在展示时反转）。 */
    fun recentFirst(pages: List<Page>): List<Page> = pages.asReversed()

    /** 列表主行文字：标题空就显示地址本身（不空转、不留一行空白）。 */
    fun labelOf(page: Page): String = page.title.trim().ifEmpty { page.url }

    /**
     * 「只记 URL 与标题」的**字段表**——建表语句与它逐列比对（见类注释第 3 条）。
     *
     * `id` 是顺序（单调，见 [PageMemorySql]）；`session_id` 是分桶；`visited_at` 只用于展示
     * 「什么时候看的」。除了这五个，一个都不许多——正文想进库，先得改这张表。
     */
    val COLUMNS: List<String> = listOf("id", "session_id", "url", "title", "visited_at")

    /** 关标签时引擎往空白页跳一次，这不是用户看的页。 */
    private const val ABOUT_BLANK = "about:blank"
}
