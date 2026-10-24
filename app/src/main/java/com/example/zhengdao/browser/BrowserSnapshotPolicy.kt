// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

import java.net.URI

/**
 * 玄览页签快照的**纯判据**（第一百七十二批 L4 · J-L4-1/2/3/8/9/10）。
 *
 * 为什么单独成类：这些判定全是列表/字符串/数值运算，不碰 Android。按本项目房型
 * （审计类缺陷修成「纯函数 + JVM 表驱动单测」）抽出来，JVM 直接覆盖；
 * `BrowserTabManager` / 快照仓库只做装配与落库。
 *
 * 设计依据：`_zd-private/docs/设计-2026-10-24-第一百七十二批-L4页签快照.md`
 * §2.1（URL 脱敏）、§4（恢复与 id 游标）、§5（滚动 clamp 与二次 clamp）、§6（剪枝）。
 */
object BrowserSnapshotPolicy {

    /** 一条快照行（与 `browser_tab` 一一对应；`id` 用 Int 与 `Tab.id` 同型，避免两套 id 语义）。 */
    data class Row(
        val id: Int,
        val url: String,
        val title: String,
        val scrollX: Float,
        val scrollY: Float,
        val updatedAt: Long,
        val lastUsedAt: Long,
    )

    /** 上限与保留期（用户 2026-10-24 拍板：100 条 / 7 天）。 */
    const val MAX_ROWS = 100
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 恢复滚动时"用户已手动滚动"的判定阈值（CSS px，§5 二条例外）。 */
    const val USER_SCROLL_EPSILON = 8f

    // ── J-L4-8：落库前的 URL 脱敏 ─────────────────────────────────────────

    /**
     * 写库前把 URL 洗干净；不可存则返回 null。
     *
     * 规则（用户 2026-10-24 复核口径）：
     * 1. 只存 `http` / `https`（其余 scheme 一律 null —— 预览页签的 `local-preview://` 也不进库）；
     * 2. **整段剥掉 fragment**（`#access_token=…` 这类最常见的泄漏面）；
     * 3. **整条剥掉敏感 query 参数**（不是打码、是删掉）——键名判据复用
     *    [SensitiveRedactor.isSensitiveQueryKey]（它背后就是 `redactUrl` 用的同一份
     *    `SENSITIVE_QUERY_KEY`，全仓只有一处键名清单）；
     * 4. 其余 query 原样保留（恢复要有意义，`?q=`、`?page=` 这类得留着）。
     *
     * 代价（写进图纸、不当 bug）：剥掉 `code`/`token` 之后，一次性的授权回跳页恢复时打不开，
     * 需要重新登录 —— 隐私优先，有意为之。
     */
    fun sanitizeUrlForStore(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val noFragment = trimmed.substringBefore('#')
        if (noFragment.isEmpty()) return null
        val uri = runCatching { URI(noFragment) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val authority = uri.rawAuthority ?: return null
        if (authority.isEmpty()) return null

        val keptQuery = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.filterNot { pair -> SensitiveRedactor.isSensitiveQueryKey(pair.substringBefore('=')) }
            ?.joinToString("&")
            .orEmpty()

        val path = uri.rawPath.orEmpty()
        return buildString {
            append(scheme).append("://").append(authority)
            append(path)
            if (keptQuery.isNotEmpty()) append('?').append(keptQuery)
        }
    }

    // ── J-L4-9：恢复后的 id 游标 ──────────────────────────────────────────

    /**
     * 恢复完标签后新的 `nextId`：`max(恢复 id, 当前 nextId - 1) + 1`。
     *
     * 为什么要它：`Tab.id` 被 `key(activeId)`、每标签委托闭包、桥的 `tabId` 路由三处当身份用。
     * 恢复出来的 id 从库里来（可能到 100），若 `nextId` 还从 1 数，新建标签必然撞 id ——
     * 表现是"点新标签切到了旧页面"或委托把事件写进别人的 state。
     *
     * @param restoredIds 本次恢复出来的全部 id（可空表）
     * @param currentNextId 管理器当前的 nextId（即"下一个要用的 id"）
     */
    fun nextIdAfterRestore(restoredIds: List<Int>, currentNextId: Int): Int {
        val maxRestored = restoredIds.maxOrNull() ?: 0
        val maxUsed = maxOf(maxRestored, currentNextId - 1)
        return maxUsed + 1
    }

    // ── J-L4-2：恢复顺序与活跃标签 ────────────────────────────────────────

    /** 恢复顺序 = 创建序（`id ASC`）。夹具里已排好的原样返回，这里只做防御性排序。 */
    fun restoreOrder(rows: List<Row>): List<Row> = rows.sortedBy { it.id }

    /** 恢复后该选中哪颗：`last_used_at` 最大者；同一时刻则 id 小的优先（确定性）。空表 null。 */
    fun activeRestoredId(rows: List<Row>): Int? =
        rows.minWithOrNull(compareByDescending<Row> { it.lastUsedAt }.thenBy { it.id })?.id

    // ── J-L4-3：滚动 clamp ────────────────────────────────────────────────

    /**
     * 把库里的滚动值夹进「这一页此刻允许的范围」，返回 (x, y)。
     *
     * 三条都是真机踩点：
     * - 页面变短了（内容比视口还短）⇒ 两个方向都归 0；
     * - 横向：**页面不可横滚或值本身就 0 ⇒ 不滚**（横向页面极少，硬滚会把人甩到右边）；
     * - NaN / 负数一律当 0（库里的脏值不许让页面跳到奇怪位置）。
     */
    fun clampScroll(
        y: Float,
        x: Float,
        contentHeight: Float,
        contentWidth: Float,
        viewportHeight: Float,
        viewportWidth: Float,
    ): Pair<Float, Float> {
        val maxY = (contentHeight - viewportHeight).coerceAtLeast(0f)
        val maxX = (contentWidth - viewportWidth).coerceAtLeast(0f)
        val safeY = if (y.isNaN() || y < 0f) 0f else y.coerceAtMost(maxY)
        val safeX = if (x.isNaN() || x <= 0f || maxX <= 0f) 0f else x.coerceAtMost(maxX)
        return safeX to safeY
    }

    /**
     * 第二次补 clamp 还要不要做（J-L4-10 / 真机第 ⑤ 条）。
     *
     * @param userScrolled 期间用户是否自己滚过（超过 [USER_SCROLL_EPSILON]）
     * @param finishedAt 首次 `onPageFinished` 的时刻；null = 还没完成
     * @param now 当前时刻
     * @param alreadyRescheduled 是否已经补过一次（最多一次）
     */
    fun shouldSecondClamp(
        userScrolled: Boolean,
        finishedAt: Long?,
        now: Long,
        alreadyRescheduled: Boolean,
        delayMs: Long = 800L,
    ): Boolean {
        if (finishedAt == null) return false
        if (alreadyRescheduled) return false
        if (userScrolled) return false
        return now - finishedAt >= delayMs
    }

    // ── J-L4-1：LRU / TTL 剪枝 ────────────────────────────────────────────

    /**
     * 该删哪些行（`id` 集合）。两条规则叠加：
     * 1. `last_used_at < now - ttl` 全删（过期）；
     * 2. 剩下的超过 [limit] 条 ⇒ 保留 `last_used_at` 最大的 limit 条，其余删。
     *
     * 纯函数：SQL 侧另有一份等价语句（`BrowserTabSql.PRUNE_*`），两者由测试对齐。
     */
    fun pruneIds(
        rows: List<Row>,
        now: Long,
        ttlMs: Long = TTL_MS,
        limit: Int = MAX_ROWS,
    ): Set<Int> {
        if (rows.isEmpty()) return emptySet()
        val cutoff = now - ttlMs
        val expired = rows.filter { it.lastUsedAt < cutoff }.map { it.id }.toSet()
        val alive = rows.filterNot { it.id in expired }
        val kept = alive
            .sortedWith(compareByDescending<Row> { it.lastUsedAt }.thenBy { it.id })
            .take(limit.coerceAtLeast(0))
            .map { it.id }
            .toSet()
        val overflow = alive.map { it.id }.filterNot { it in kept }.toSet()
        return expired + overflow
    }
}
