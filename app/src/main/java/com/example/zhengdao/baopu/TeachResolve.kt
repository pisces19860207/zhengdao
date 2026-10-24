// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §2：十级定位 ＋ 置信度门控 ＋ 只修坏步的自愈。
//
// 房型照 MemoryRules/SkillRules：**全部「算得清」的逻辑在这**（tier 顺序、门控判定、
// 自愈接受判据），DOM 访问走 [TeachDom] 端口——生产端是 BrowserCommands.execJs 注入的
// 查询脚本（见 TeachReplay.kt），测试端是内存假 DOM。两端只实现十条**哑查询**
// （按档找元素/取 url/数行数……），不许在端口实现里做决策。
package com.example.zhengdao.baopu

// ── DOM 端口（生产＝玄览 execJs，测试＝内存假 DOM）────────────────────────────

/** 向页面发的一条哑查询。 */
sealed class TeachQuery {
    /** 按一条定位器找元素（返回命中，≤5 个）。 */
    data class Scan(val locator: TeachLocator) : TeachQuery()

    /** 自愈专用：按语义快照找元素（tag+role+name/text 全等才算），命中里带各自的最佳定位器。 */
    data class FindBySnapshot(val snapshot: TeachElementSnapshot) : TeachQuery()

    /** 当前 URL。 */
    data object CurrentUrl : TeachQuery()

    /** 页面是否出现某段文本。 */
    data class TextPresent(val text: String) : TeachQuery()

    /** 列表行数（表格行/列表项的合计口径，录制与回放同一条查询）。 */
    data object RowCount : TeachQuery()

    /** 起点页结构指纹探针：关键表头文本集合＋关键按钮名集合。 */
    data object HeadersAndButtons : TeachQuery()

    /** 执行动作：定位器必须唯一命中才动手；返回执行后的 URL。 */
    data class Act(val locator: TeachLocator, val action: TeachAction, val value: String?) : TeachQuery()

    /** DOM 摘要（取证用：页面头几层结构＋行数＋URL，脱敏后落证据目录）。 */
    data object DomSummary : TeachQuery()
}

/** 一个候选元素的全部语义面（门控与安全判定都看它，不看坐标）。 */
data class TeachHit(
    val tag: String,
    val role: String? = null,
    val name: String? = null,
    val text: String? = null,
    val ariaLabel: String? = null,
    val testid: String? = null,
    val inputType: String? = null,
    val inputName: String? = null,
    val placeholder: String? = null,
    val inputId: String? = null,
    /** 仅自愈回复携带：该元素十档扫描后的最佳唯一定位器（无唯一锚 ⇒ null）。 */
    val healLocator: TeachLocator? = null,
) {
    /** 安全面（拒点判定用；TeachSafety 是 internal，本成员同档）。 */
    internal fun clickSurface() = TeachSafety.ClickSurface(
        text = text,
        accessibleName = name,
        ariaLabel = ariaLabel,
        testid = testid,
    )

    /** 拒填判定用的属性面。 */
    fun typeSurface() = TeachSafety.refuseTypeReason(
        inputType = inputType,
        inputName = inputName,
        inputId = inputId,
        placeholder = placeholder,
        autocomplete = null,
    )
}

/** 查询回复。 */
sealed class TeachReply {
    data class Hits(val hits: List<TeachHit>) : TeachReply()

    data class Url(val url: String) : TeachReply()

    data class Flag(val present: Boolean) : TeachReply()

    data class Count(val n: Int) : TeachReply()

    data class Anchors(val headers: List<String>, val buttons: List<String>) : TeachReply()

    data class Acted(val ok: Boolean, val reason: String?, val urlAfter: String) : TeachReply()

    data class Summary(val text: String) : TeachReply()

    data class Failed(val reason: String) : TeachReply()
}

/** 页面访问端口。实现方只做「问什么答什么」，不做任何门控决策。 */
interface TeachDom {
    fun ask(query: TeachQuery): TeachReply
}

// ── 定位：十级优先级逐档试 ────────────────────────────────────────────────────

/** 一次定位的结果：第一个有命中的档＋它的候选（0 命中 ⇒ winner=null）。 */
data class TeachResolution(
    val winner: TeachLocator?,
    val hits: List<TeachHit>,
    val tried: List<TeachLocator>,
)

internal object TeachResolve {

    /** 每档最多带回的候选数（门控要的是「几个」，5 个封顶防刷屏）。 */
    const val MAX_HITS_PER_TIER = 5

    /**
     * 按技能里记的定位链**逐档试**（§0 十级优先级）：tier 升序，第一个有命中的档胜出，
     * 后面的档不再看（低档只在前面的档全空时才有机会）。
     */
    fun resolve(locators: List<TeachLocator>, dom: TeachDom): TeachResolution {
        val tried = mutableListOf<TeachLocator>()
        for (loc in locators.sortedBy { it.tier }) {
            val reply = dom.ask(TeachQuery.Scan(loc))
            val hits = (reply as? TeachReply.Hits)?.hits.orEmpty().take(MAX_HITS_PER_TIER)
            tried += loc
            if (hits.isNotEmpty()) return TeachResolution(loc, hits, tried)
        }
        return TeachResolution(null, emptyList(), tried)
    }

    // ── 置信度门控（CANVAS：低置信或命中多个 ⇒ 停下问人，不硬点）────────────

    /** 门控判定。安全拒点（拒点名单/密码/标签白名单）在执行器里对 [Execute] 再过一道。 */
    sealed class Verdict {
        /** 唯一命中且语义档 ⇒ 可以动手（还差安全闸）。 */
        data class Execute(val locator: TeachLocator, val hit: TeachHit) : Verdict()

        /** 停下问人：命中 ≥2 个候选，或只命中低档（CSS/XPath）。给候选，不选第一个。 */
        data class AskCandidates(
            val reason: String,
            val candidates: List<TeachHit>,
            val scanned: List<String>,
        ) : Verdict()

        /** 录的定位全空 ⇒ 进自愈（只重抽这一步）。 */
        data class Heal(val resolution: TeachResolution) : Verdict()
    }

    fun gate(res: TeachResolution): Verdict = when {
        res.winner == null -> Verdict.Heal(res)
        res.hits.size >= 2 -> Verdict.AskCandidates(
            reason = "文案「${res.hits.first().text ?: res.hits.first().name ?: ""}」命中 ${res.hits.size} 个元素，" +
                "不选第一个——请人指定或改页面",
            candidates = res.hits,
            scanned = res.tried.map { it.describe() },
        )
        tierConfidence(res.winner.tier) < TEACH_EXECUTE_MIN_CONFIDENCE -> Verdict.AskCandidates(
            reason = "只命中低档定位（${res.winner.describe()}，置信度 ${tierConfidence(res.winner.tier)}）——" +
                "语义锚全失效，停下问人",
            candidates = res.hits,
            scanned = res.tried.map { it.describe() },
        )
        else -> Verdict.Execute(res.winner, res.hits.first())
    }

    // ── 自愈：只重抽坏的那一步，一次机会，零 LLM（arXiv 2603.20358）─────────

    /** 自愈结果。 */
    sealed class HealOutcome {
        /** 修好了：新定位器＋命中元素（报告里标「定位漂移：第 N 步 旧→新」，技能文件不改）。 */
        data class Healed(val locator: TeachLocator, val hit: TeachHit) : HealOutcome()

        /** 快照匹配到 ≥2 个元素：分不清是哪个，给候选问人。 */
        data class Ambiguous(val candidates: List<TeachHit>) : HealOutcome()

        /** 元素没了 / 找不到唯一语义锚：问人（或重新教）。 */
        data class NotFound(val reason: String) : HealOutcome()
    }

    /**
     * 自愈接受判据（安全优先，从严）：
     * 1. 活页面里**恰好一个**元素与示教时的语义快照全等（tag+role+name/text）——身份靠
     *    全量语义签名证明，不靠位置；
     * 2. 该元素的十档最佳定位器必须是**语义档（T1–T8）**——自愈后仍只剩 CSS/XPath 说明
     *    页面语义已经烂了，这不让过（停下问人）。
     * 满足 ⇒ Healed（执行器标漂移后继续）；技能文件一个字节不动。
     */
    fun selfHeal(snapshot: TeachElementSnapshot, dom: TeachDom): HealOutcome {
        val reply = dom.ask(TeachQuery.FindBySnapshot(snapshot))
        val hits = (reply as? TeachReply.Hits)?.hits.orEmpty()
        return when {
            hits.isEmpty() -> HealOutcome.NotFound(
                "自愈没找到与示教快照匹配的元素（${snapshot.tag}/${snapshot.name ?: snapshot.text ?: "?"}）——" +
                    "元素可能已删除，请人确认或重新教",
            )
            hits.size > 1 -> HealOutcome.Ambiguous(hits)
            else -> {
                val only = hits.first()
                val loc = only.healLocator
                when {
                    loc == null -> HealOutcome.NotFound(
                        "自愈找到唯一匹配元素，但它没有任何唯一语义锚（${snapshot.tag}/" +
                            "${snapshot.name ?: snapshot.text ?: "?"}）——停下问人",
                    )
                    loc.tier in TEACH_LOW_TIERS -> HealOutcome.NotFound(
                        "自愈后只剩低档锚（${loc.describe()}）——语义已烂，停下问人，技能不自动改",
                    )
                    else -> HealOutcome.Healed(loc, only)
                }
            }
        }
    }
}
