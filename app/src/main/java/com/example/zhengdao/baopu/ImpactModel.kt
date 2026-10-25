// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十批-后果预览（用户 2026-10-26 改名「影响提示」）§1。
//
// 本文件只放**形状**：`Impact`（事实结构）、`FormState`（网页表单快照）、
// `LocalFacts`（只读事实口）、`NoFacts`（什么都不知道）。算法在 `ImpactPreview`，
// 文案在 `ImpactLine`——「算什么」「怎么说」「从哪读」三件事各自成文件，各自可测。
package com.example.zhengdao.baopu

/**
 * 一次动作会造成的**事实**（不含好坏判断）。
 *
 * 形状照派工单 §1（[newFiles]/[overwrite]/[deleteCount]/[deleteBytes]/
 * [ledgerRows]/[originalsTouched]/[unknown] 是派单原文那七格），其余字段是 §2
 * 覆盖表里另外六类动作要摆的事实（各自带默认值 ⇒ 未覆盖的动作照旧）。
 *
 * 纪律：**只装事实，不装句子**。中文怎么写成一句话归 [ImpactLine]（这样两种语言、
 * 两种展示位可以共用同一份读数，也不会让"算"沾上"排序/取舍"）。
 */
internal data class Impact(
    /** 这一类动作（决定 [ImpactLine] 用哪句模板）。 */
    val kind: Kind = Kind.NONE,

    // ── 写/覆盖（write_file / edit_file）────────────────────────────────
    /** 会新建几个文件。 */
    val newFiles: Int = 0,
    /** 会被覆盖的文件名（**已截到前 [ImpactPreview.MAX_NAMES] 个**，名字不含目录）。 */
    val overwrite: List<String> = emptyList(),
    /** 会被覆盖的文件总数（[overwrite] 只是前三名；总数以这格为准）。 */
    val overwriteTotal: Int = 0,

    // ── 删（delete_path，含清空回收站/批量）──────────────────────────────
    /** 会删几个文件。 */
    val deleteCount: Int = 0,
    /** 会删掉多少字节（合计）。 */
    val deleteBytes: Long = 0,
    /** 其中**已入台账**的几条（产物台账 `artifact` ＋ 资料库台账 `library_item`）。 */
    val ledgerRows: Int = 0,

    // ── 移动（move_path）──────────────────────────────────────────────
    /** 会移动几个（0 或 1；批量由调用方合批）。 */
    val moved: Int = 0,
    /** 目标已存在 ⇒ 撞名几个。 */
    val collisions: Int = 0,

    // ── 网页提交类点击（browser_click）─────────────────────────────────
    /** 目标元素的短名（选择器或按钮文本；**不含整串 CSS**——太长且泄结构）。 */
    val nodeText: String = "",
    /** 已填字段数（拿不到 DOM ⇒ 0 且 [formKnown]=false）。 */
    val formFilled: Int = 0,
    /** 必填还空着几项。 */
    val formRequiredEmpty: Int = 0,
    /** 点了会跳到别的站点（跨域）。 */
    val crossDomain: Boolean = false,
    /** 表单读数拿到了没有（false ⇒ 只报目标名，不编「已填 0 项」）。 */
    val formKnown: Boolean = false,

    // ── 定时任务（schedule_task）──────────────────────────────────────
    /** 下一次触发的绝对时刻（epoch ms；0 = 算不出）。 */
    val nextFireAtMs: Long = 0,
    /** 一天触发几次（每天 1、每周 1/7 记 0、一次性 0）。 */
    val firePerDay: Int = 0,

    // ── 技能回放（第一百九十六批）──────────────────────────────────────
    /** 预计步数区间（下限/上限）。 */
    val stepsLo: Int = 0,
    val stepsHi: Int = 0,
    /** 会碰几个文件（回放里读+写去重后的条数）。 */
    val touches: Int = 0,

    // ── 技能自治（第二百零五批草稿治理）────────────────────────────────
    /** 将退役几条。 */
    val retireCount: Int = 0,
    /** 将退役的前三名（名字；界面只显示条数，名字进决策中心可查）。 */
    val retireNames: List<String> = emptyList(),
    /** 将转正几条。 */
    val promoteCount: Int = 0,
    /** 保留几条。 */
    val keepCount: Int = 0,
    /** 其中「最近用过」的几条（保留组里 30 天内有活动的）。 */
    val recentlyUsed: Int = 0,

    // ── 日记自治（第一百四十七批维护）──────────────────────────────────
    /** 将归档几条（原文转进 `记忆/归档/`）。 */
    val archiveCount: Int = 0,
    /** 将清理几条（归档期满且被摘要完整承载的空/冗余条目）。 */
    val cleanCount: Int = 0,
    /** 索引要不要重建（有归档或清理 ⇒ 要）。 */
    val indexRebuild: Boolean = false,

    // ── 元信息 ───────────────────────────────────────────────────────
    /** 原件区命中（`资料库/原始/`）——调用方据此知道管线会**硬拒**。 */
    val originalsTouched: Boolean = false,
    /** 动作本身会被工具自己拒（目标已存在、目录不能删、源不存在……）。 */
    val refused: Boolean = false,
    /** 超时/读不到 ⇒ **后果未知**（界面就显示这四个字，不猜）。 */
    val unknown: Boolean = false,
    /** 本次算事实花了多少毫秒（p50/p95 读数就用它）。 */
    val elapsedMs: Long = 0,
) {

    /** 这类动作有多少可说的（`false` ⇒ 界面一个字都不显示）。 */
    val isEmpty: Boolean
        get() = !unknown && kind == Kind.NONE

    /** 动作类别（只为选模板；`NONE` = 这类动作不预览）。 */
    enum class Kind {
        NONE,

        /** 写/覆盖文件。 */
        WRITE,

        /** 删文件。 */
        DELETE,

        /** 移动/改名。 */
        MOVE,

        /** 网页提交类点击。 */
        CLICK,

        /** 定时任务。 */
        SCHEDULE,

        /** 技能回放。 */
        SKILL_REPLAY,

        /** 技能自治（草稿治理）。 */
        SKILL_GOVERN,

        /** 日记自治（浓缩/归档/清理）。 */
        DIARY_MAINTAIN,
    }
}

/**
 * 一个网页表单的**只读**快照（第一百七十h批同样思路：脚本与解析同处，避免两套漂移）。
 *
 * @param filled 已填字段数（排除按钮/隐藏域）
 * @param requiredEmpty 必填（`required`/`aria-required`）还空着的项数
 * @param actionHost 提交目标的主机（没写 action ⇒ 当前页主机）
 * @param currentHost 当前页主机
 * @param buttonText 目标元素的可见短名（取不到 ⇒ 空串）
 */
internal data class FormState(
    val filled: Int = 0,
    val requiredEmpty: Int = 0,
    val actionHost: String = "",
    val currentHost: String = "",
    val buttonText: String = "",
) {
    /** 提交会不会跨站点（主机不同即算；空串不判）。 */
    val crossDomain: Boolean
        get() = actionHost.isNotEmpty() && currentHost.isNotEmpty() &&
            !actionHost.equals(currentHost, ignoreCase = true)
}

/**
 * 只读事实的来源（**全部由调用方注入** ⇒ 纯 JVM 直测；生产实现在 `ImpactFacts.kt`）。
 *
 * 为什么是接口而不是直接读 File：预览必须能被单测喂「慢盘」「读不到」「台账 5 行」
 * 这些形态，而真机上的痛（SELinux、软链、超大目录）在 JVM 里造不出来。
 * 接口只有六个只读出口，**没有任何写口**——这是「只读」在类型上的保证。
 */
internal interface LocalFacts {

    /** 工作区根（绝对路径；null = 拿不到 ⇒ 一切路径类事实降级为"未知"）。 */
    val root: String?

    /** 原件区绝对路径（`资料库/原始/`；null = 这套环境没有原件区）。 */
    val rawDir: String?

    /**
     * 路径的大小（**字节**）。
     *
     * @return null = 不存在或读不到（两者**故意不分**：预览不猜"是不是没权限"，
     *         反正结论都是"不知道"，分别报只会让界面多一句废话）
     */
    fun sizeOf(path: String): Long?

    /** 是不是目录（不存在 ⇒ false）。 */
    fun isDir(path: String): Boolean

    /** 目录下的**直接**子项名（null = 读不到/不是目录）。 */
    fun listNames(path: String): List<String>?

    /** 这些路径里有几条已在台账（产物台账＋资料库台账，按路径/名字匹配）。 */
    fun ledgerRowsFor(paths: List<String>): Int

    /**
     * 网页表单快照（只对 `browser_click` 的问；调用方负责 ≤150ms 预算）。
     *
     * @param selector 点击目标（CSS 选择器）
     * @return null = 拿不到（没有玄览页、JS 超时、页面没绘制完）——**不假装"已填 0 项"**
     */
    fun formState(selector: String): FormState?

    /** 单调时钟（毫秒）。测试注入假钟 ⇒ 限时判据可复现。 */
    fun nowMs(): Long
}

/** 什么都不知道的事实源（没接线/纯逻辑测试用）：所有读口回"不知道"，绝不自造事实。 */
internal object NoFacts : LocalFacts {
    override val root: String? get() = null
    override val rawDir: String? get() = null
    override fun sizeOf(path: String): Long? = null
    override fun isDir(path: String): Boolean = false
    override fun listNames(path: String): List<String>? = null
    override fun ledgerRowsFor(paths: List<String>): Int = 0
    override fun formState(selector: String): FormState? = null
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
}
