// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十四批（2026-11-04，派工单《顶栏对齐设计稿》）：**顶栏模型区两段**的唯一判据源。
//
// 为什么要有这个文件（不是「又一层封装」）：
//   顶栏那两颗（徽标 / 模型名）过去只在一处算过名字（`ModelPickerRules.companyOf` 摆公司全名）。
//   434 把它们换成「`[徽标] 模型名`、不重复公司名、档位必须有、内部构建号不上屏」，
//   并且**两个命中区各自弹一个列表、同一时刻只许开一个**。这些判据一旦散进 @Composable
//   就没人能证明「两个弹层不会同时开着」——本仓没有 Robolectric，能算的必须当场算、必须能 JVM 直测。
//
// 四条硬判据（逐条对应派工单）：
//   1. 显示名 = 小映射表（[TOP_BAR_NAMES]）；**表里没有 ⇒ 原样显示 ID**，绝不猜、绝不编（§模型名④）；
//   2. 徽标 = 自绘字母（[BADGE_LETTERS]），不用官方 logo（§模型区②：商标与许可不走这条路）；
//   3. 弹层互斥＝一个算式（[toggleSheet]）：点同一个区＝收起，点另一个区＝那个开、这个关；
//   4. 「未配置 / 仅此一家」是**两区各自的空态**（[onlyOneProviderHint]），不是空列表。
//
// 纪律：本文件零 Android、零 IO、零 Context、零时间。排版留在 `BaopuModelArea.kt`，
// 落盘留在 `ProviderPrefs`，换引擎留在 `BaopuRuntime.switchChannel`——这里只回答「该显示什么、该弹哪一个」。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ModelPickerRules
import com.example.zhengdao.baopu.Provider

/**
 * 顶栏模型区当前开着哪一张弹层（判据 3 的定义域）。
 *
 * 三个值**互斥**：类型里没有「两个都开」这一项——「同一时刻只许开一个」由类型兜住，
 * 不靠调用方记得先关另一个（上一版截图两个同时开着，就是这么漏的）。
 */
internal enum class BaopuTopBarSheet {
    NONE,

    /** 徽标区开着：弹供应商 / 网关。 */
    PROVIDER,

    /** 模型名区开着：弹同一公司下的模型。 */
    MODEL,
}

/** 顶栏模型区的纯判据（第四百三十四批）。 */
internal object BaopuTopBarRules {

    // ── ① 显示名：一张小映射表，表外一律显 ID ──────────────────────────────

    /**
     * 模型 ID → 顶栏显示名（派工单§模型区①「模型名用小映射表，规模很小」）。
     *
     * **落点二选一的选边理由**（工单允许 `Provider` 增 `modelLabel` 或一张常量表）：
     * `Provider` 是 data class，出生处有三拨（[Provider] 的预设常量、`Providers.withUserKey`、
     * `ProviderPrefs.resolve` 的自定义回退），加字段等于把改动铺到顶栏之外——434 的边界是
     * 「只碰顶栏相关文件，动了别处停手报」。常量表 closed 在本文件里，且天然满足「表里没有 ⇒ 显 ID」。
     *
     * 表里的值三条口径（派工单§模型名）：
     * · **不带公司名**（公司由徽标那一颗代表，A 案：`[D] V4.1 flash`，不是 `[D] DeepSeek V4.1 flash`）；
     * · **带档位**（`flash` / `mini` / `Pro` 这类网上通行的叫法）；
     * · **不带内部构建号**（`v4.1-flash-1017` 的 `1017` 不上屏）。
     *
     * 来源可查（2026-11-04 用户裁定「死别名直接删掉，按照最新的来」之后重过一遍）：
     * · DeepSeek 两条 = 本仓**实测**的现役 ID 对上官方产品名
     *   （`D:\zd-tools\ds-probe.txt`，2026-10-17 `GET /models`：`deepseek-flash` → `DeepSeek-V4.1-Flash`、
     *   `deepseek-v4-pro` → `DeepSeek-V4-Pro`），按 A 案摘掉公司那一截 ⇒ 顶栏正是派工单逐字要的 `V4.1 flash`；
     * · 其余是本仓预设的默认模型（`Provider.model`）、`ModelPickerRules.presetModels` 短名单，
     *   以及 131 批已审的 `ModelPickerRules.CURATED_NAMES`，同样只摘公司词——
     *   **没往表里塞任何一个猜出来的名字，也没留一条已经查不到出处的 ID**。
     * 原来那条 `deepseek-v4.1-flash-1017`（派工单举例用的假 ID）删了：显示字照旧是 `V4.1 flash`，
     * 但它现在钉在**机上真存在**的那条 ID 上；`deepseek-chat` / `deepseek-reasoner`（官方 2026-07-24
     * 停用的一对别名）也删了——不替死别名包装产品名，真在机器上剩这条存储时按判据 1 显 ID，
     * 她看得见、也就知道要重选。
     */
    val TOP_BAR_NAMES: Map<String, String> = mapOf(
        // 派工单逐字例子（判据③拿它对截图）——挂在实测现役 ID 上
        "deepseek-flash" to "V4.1 flash",
        "deepseek-v4-pro" to "V4 Pro",
        "gpt-6.1-sol" to "GPT 6.1 Sol",
        // 本仓预设的默认模型（Provider.model）
        "gpt-4o-mini" to "GPT-4o mini",
        "claude-sonnet-4-5" to "Sonnet 4.5",
        "gemini-2.5-flash" to "2.5 Flash",
        // 131 批已审的常见表（CURATED_NAMES），按 A 案摘掉公司那一截
        "claude-opus-4-5" to "Opus 4.5",
        "gpt-4o" to "GPT-4o",
        "gemini-2.5-pro" to "2.5 Pro",
        // presetModels 里剩下的那几个预设 ID（131 批③「预设也能选模型」的名单）
        "gpt-4.1-mini" to "GPT 4.1 mini",
        "gpt-4.1" to "GPT 4.1",
        "claude-haiku-4-5" to "Haiku 4.5",
    )

    /**
     * 顶栏那颗模型名（判据 1）。
     *
     * 表里没有 ⇒ **原样回吐 ID**：宁可让她看见 `nvidia/nemotron-3.5-super-vl:free` 这么一串，
     * 也不给一个猜出来的名字——猜错一次，顶栏这颗就再也没人信了（工单§模型名④「绝不猜、绝不编」）。
     * 表里的值若仍带着公司词（如 `Gemini 2.5 Pro` 落在公司「Google Gemini」名下），
     * 按 A 案再摘一次头词；摘完会变空 ⇒ 不摘（宁可重复公司，也不给一个空名字）。
     */
    fun topBarModelName(modelId: String, company: String): String {
        val id = modelId.trim()
        val named = TOP_BAR_NAMES[id.lowercase()] ?: return id
        return dropCompanyWord(named, company)
    }

    /** 摘掉头部的公司词（大小写与标点无关；只摘一次，不递归）。 */
    private fun dropCompanyWord(name: String, company: String): String {
        val parts = name.split(" ")
        if (parts.size <= 1) return name
        val head = parts.first().trim('.', ',', '-', '_').lowercase()
        if (head.isEmpty()) return name
        val words = company.lowercase().split(' ', '-', '_', '.', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (head !in words) return name
        return parts.drop(1).joinToString(" ").ifBlank { name }
    }

    // ── ② 徽标：自绘字母 ──────────────────────────────────────────────────

    /**
     * 公司 → 字母徽标（判据 2；派工单点名的 D/O/G/Z/Q 在前）。
     *
     * 比对键是公司名的**小写整词**（`companyOf` 已经砍掉「 免费」那半截）。
     * 表里没有 ⇒ 取公司名第一个非空字符大写（`自定义` ⇒ 「自」）——那是**首字母**，不是猜公司。
     */
    val BADGE_LETTERS: Map<String, String> = mapOf(
        "deepseek" to "D",
        "openai" to "O",
        "anthropic" to "A",
        "google" to "G",
        "google gemini" to "G",
        "gemini" to "G",
        "openrouter" to "R",
        "zhipu" to "Z",
        "智谱" to "Z",
        "qwen" to "Q",
        "阿里" to "Q",
        "通义" to "Q",
        "custom" to "C",
    )

    /** 字母徽标（永不返回两句解释，只回一个字符；空公司名 ⇒ 空串＝徽标不摆）。 */
    fun badgeLetterOf(company: String): String {
        val key = company.trim().lowercase()
        if (key.isEmpty()) return ""
        BADGE_LETTERS[key]?.let { return it }
        return key.first { c -> !c.isWhitespace() }.uppercaseChar().toString()
    }

    // ── ③ 两张弹层互斥（派工单「交付后反馈」里必须修的那一条）─────────────────

    /**
     * 点一个区之后，哪一张该开着（判据 3）。
     *
     * · 点**没开着**的那个区 ⇒ 那个开（另一个自然关：这里只有一个值，不是两个布尔）；
     * · 点**正开着**的那个区 ⇒ 收起（[BaopuTopBarSheet.NONE]）。
     * 点空白关闭走 DropdownMenu 自己的 `onDismissRequest`，同一个 setter。
     */
    fun toggleSheet(open: BaopuTopBarSheet, target: BaopuTopBarSheet): BaopuTopBarSheet =
        if (open == target) BaopuTopBarSheet.NONE else target

    /** 弹层能不能开（判据 4 的一半：没配 Key 的那一颗不弹列表，先把人去设置）。 */
    fun sheetForClick(configured: Boolean, open: BaopuTopBarSheet, target: BaopuTopBarSheet): BaopuTopBarSheet =
        if (!configured) BaopuTopBarSheet.NONE else toggleSheet(open, target)

    // ── ④ 两张弹层的条目（当前项高亮 ＋ 各自的空态话）───────────────────────

    /** 徽标区弹层里的一行：一家供应商 / 一个网关。 */
    data class ProviderRow(
        val id: String,
        val name: String,
        val letter: String,
        val selected: Boolean,
        /** 这家此刻有没有 Key：没有 ⇒ 点了它不切，走「引导去设置」那唯一一条路。 */
        val configured: Boolean,
    )

    /**
     * 供应商 / 网关名单（判据 4）。
     *
     * 顺序照 [Providers.all] 的预设序，自定义（网关）永远在最后——与 131 那张抽屉同一套排法，
     * 免得顶栏和抽屉两份顺序。`configuredIds` 由调用方从 Key 池算（本文件不碰 SP）。
     */
    fun providerRows(
        all: List<Provider>,
        currentId: String,
        configuredIds: Set<String>,
        customName: String? = null,
        customId: String = "custom",
    ): List<ProviderRow> {
        val presets = all.map { p ->
            val name = ModelPickerRules.companyOf(p)
            ProviderRow(p.id, name, badgeLetterOf(name), p.id == currentId, p.id in configuredIds)
        }
        val custom = customName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            listOf(ProviderRow(customId, name, badgeLetterOf(name), customId == currentId, customId in configuredIds))
        }.orEmpty()
        return presets + custom
    }

    /**
     * 「仅此一家」（判据 4 的另一半）：能切的（有 Key 的）**不到两家** ⇒ 徽标区不弹列表，
     * 弹那一句「就这一家，没得切」。工单写的是「不弹空列表」——这里连半条列表都不弹。
     *
     * 当前那家没 Key 时也算「仅此一家」吗？算：`count <= 1` 包含 0 家，
     * 但调用方在此之前先走 [sheetForClick] 的未配置分支，所以真到这里的只有「一家」那一种。
     */
    fun onlyOneProviderHint(rows: List<ProviderRow>): Boolean =
        rows.count { it.configured } <= 1

    /** 模型名区弹层里的一行：同一家下的一个模型。 */
    data class ModelRow(val id: String, val name: String, val selected: Boolean)

    /**
     * 同一家下的模型名单（判据 1＋5：条目也走同一张表，**弹层里不许露原始 ID**——
     * 除非表里没有，那时按判据 1 显 ID，右列表与顶栏同一条规则）。
     *
     * 去重保序；当前用的那个如果不在名单里也补在最后（否则高亮找不到家）。
     */
    fun modelRows(ids: List<String>, currentId: String, company: String): List<ModelRow> {
        val current = currentId.trim()
        val ordered = (ids.map { it.trim() } + current).filter { it.isNotEmpty() }
        val seen = linkedSetOf<String>()
        ordered.forEach { seen += it }
        return seen.map { ModelRow(it, topBarModelName(it, company), it == current) }
    }

}
