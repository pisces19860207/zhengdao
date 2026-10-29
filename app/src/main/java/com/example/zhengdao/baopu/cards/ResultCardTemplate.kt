// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **答案卡的规格层**（派工单 §2.5）：模板（静态声明）→ 卡（带值的实例）。
//
// ## 为什么规格与取值要分两步
// 派工单定死「组件只做一次，场景只加模板」「新增场景＝加一份 JSON，不改组件代码」。
// 要做到这句，组件就只能认**规格**（组／行／动作／可拨／默认折起），不能认业务字段。
// 所以这一层把两件事分开：
//   · [ResultCardTemplate]：JSON 里那份静态声明（label 用 strings 键，不是字面量）；
//   · [ResultCardSpec]：producer 把值喂进来之后、可以直接交给界面渲染的那一份。
// 界面只吃后者，永远不知道「物流」「知识点」这些词，也就没有第二次改组件的理由。
//
// ## 为什么 label 是 strings 键而不是 JSON 里的字面量
// 本仓有两条既有纪律：文案全在 strings.xml（中英两份对称，`check-string-text.py` 钉着中英值），
// 界面不许出现中文字面量。模板里写「配货单号」等于在 assets 里开了第三套措辞，
// 换语言时它不会跟着变。所以 JSON 只写 `labelKey`，取值由调用方注入 [lookup]，
// 缺键由 `ResultCardTemplateGateTest` 在 JVM 里逐份对 strings 核对（读得到 XML，不需要 R）。
//
// ## 「未填」不是空串
// 负例⑤：表里没有的字段显示 0 或空白＝红。缺值一律落成模板里那句
// [ResultCardTemplate.emptyTextKey]（默认 `baopu_card_unfilled`），并置 `filled = false`——
// 两个事实分开存：界面拿 `filled` 决定「能不能点」（未填的号码不可点），
// 拿 `value` 决定「显示什么」。合成一个字符串就再也分不出「本来空」和「写了个『未填』」。
//
// ## 号码的形状判据不在这里
// `dialable` 只是「这一行**允许**拨」的声明；真能不能拨由 producer 给的 [CardRow.dial] 决定
// （195 的 `LogisticsPhone.parse` 才是判据的唯一出处）。本层不判位数、不剥分隔符——
// 两处各判一次，就会出现「卡里能点、台账里不能点」这种两套答案。
package com.example.zhengdao.baopu.cards

import org.json.JSONArray
import org.json.JSONObject

/** 卡里的一行（值已就位、label 已本地化）。 */
data class CardRow(
    val key: String,
    val label: String,
    val value: String,
    val copyable: Boolean = false,
    /** 这一行**允许**点着拨号；真正可不可点还要 [dial] 不为空（未填／形状不过都没有 dial）。 */
    val dialable: Boolean = false,
    /** 原样显示，但**日志与审计一个字都不记**（§2：客户/司机姓名与手机号）。 */
    val sensitiveInLog: Boolean = false,
    /** 默认折起（自测类卡的「解题步骤」）。 */
    val collapsible: Boolean = false,
    /** 拨号盘要的号码串（剥好分隔符）；null ＝ 这一行不可拨。 */
    val dial: String? = null,
    /** 有真值吗：false ⇒ [value] 是「未填」那句，不是用户数据。 */
    val filled: Boolean = true,
)

/** 一组行（`title` 为 null ＝ 这一组不摆组标题）。 */
data class CardGroup(val key: String, val title: String?, val rows: List<CardRow>)

/** 卡底那排只读动作（复制全部／详情／追问一句）；没有提交类动作，见 [KNOWN_ACTION_IDS]。 */
data class CardAction(val id: String, val label: String)

/** 一张可直接渲染的答案卡。 */
data class ResultCardSpec(
    val templateId: String,
    val title: String,
    val groups: List<CardGroup>,
    val actions: List<CardAction>,
    /** 这张卡代表几条结果（多票＝一张卡里分条；0＝不摆计数）。 */
    val itemCount: Int = 0,
    /**
     * 这一条「是谁」（单号／题号／文件路径；空 ＝ producer 没给身份）。
     *
     * 它只服务一件事：同一条的结果又到了一次（比如状态被勾过之后重问一遍），
     * 应当**原地换值**而不是并排两张。全字段比做不到这件事——值一变就成「另一条」了。
     * 空身份退回全字段比（严格），总比两张一模一样的卡强。
     */
    val identity: String = "",
) {
    /** 所有行（按渲染顺序）——正文复述判据、复制全部、条数预算都从这一处取。 */
    val rows: List<CardRow> get() = groups.flatMap { it.rows }
}

// ── 静态声明（JSON 那一份）─────────────────────────────────────────────────

data class CardRowSpec(
    val key: String,
    val labelKey: String,
    val copyable: Boolean = false,
    val dialable: Boolean = false,
    val sensitiveInLog: Boolean = false,
    val collapsible: Boolean = false,
)

data class CardGroupSpec(val key: String, val titleKey: String?, val rows: List<CardRowSpec>)

data class CardActionSpec(val id: String, val labelKey: String)

data class ResultCardTemplate(
    val templateId: String,
    val version: Int,
    val titleKey: String,
    /** 缺值时显示什么（缺省 [DEFAULT_EMPTY_KEY]）。 */
    val emptyTextKey: String,
    val groups: List<CardGroupSpec>,
    val actions: List<CardActionSpec>,
) {
    companion object {
        /** 「未填」那句的缺省键（§2 表里没有就写未填，不补 0）。 */
        const val DEFAULT_EMPTY_KEY = "baopu_card_unfilled"

        /** 模板格式版本（本批只认 1；2 要另开单改解析，不许猜字段）。 */
        const val FORMAT_VERSION = 1
    }
}

/**
 * 壳认得的动作 id（白名单，**闭集**）。
 *
 * 闭集的理由：列表外 id 若被静默丢掉，模板作者写错一个字母就会得到「按钮消失了但没人报错」；
 * 这里在解析期就判死并说清是哪个 id，比界面少画一个按钮好查一百倍。
 */
object CardActions {
    /** 复制全部（把卡里的字段抄成一段文字）。 */
    const val COPY_ALL = "copyAll"

    /** 详情（卡内展开完整字段；与正文的「详情」指同一份内容）。 */
    const val DETAIL = "detail"

    /** 追问一句（回到提问的口子）。 */
    const val FOLLOW_UP = "followUp"

    val KNOWN_ACTION_IDS: Set<String> = setOf(COPY_ALL, DETAIL, FOLLOW_UP)
}

// ── 解析（严格；错就说清哪一行，不猜）──────────────────────────────────────

/** 模板读取的三态（与 `PolicyTable.Load` 同房型：Ok／Bad 二态就够，注册表再把 Bad 落日志）。 */
sealed interface TemplateLoad {
    data class Ok(val template: ResultCardTemplate) : TemplateLoad

    /** @param reason 给开发看的一句话（哪份文件的哪个字段） */
    data class Bad(val reason: String) : TemplateLoad
}

object ResultCardTemplates {

    /**
     * 一份 JSON → 一份模板。
     *
     * 严格之处：缺 `templateId`／`version` 不认识／`groups` 空／组里没行／同一模板内 `key` 重复，
     * 一律 [TemplateLoad.Bad]。理由：这四样只要放过一样，界面就会出现「一张空白卡」或
     * 「同一个字段画两遍」，而那种卡在真机上看起来像数据丢了。
     */
    fun parse(source: String, text: String): TemplateLoad {
        val root = runCatching { JSONObject(text) }.getOrElse { e ->
            return TemplateLoad.Bad("$source: 不是合法 JSON（${e.javaClass.simpleName}）")
        }
        val id = root.optString("templateId").trim()
        if (id.isEmpty()) return TemplateLoad.Bad("$source: 缺 templateId")
        val version = root.optInt("version", 0)
        if (version != ResultCardTemplate.FORMAT_VERSION) {
            return TemplateLoad.Bad("$source: version=$version 不是 ${ResultCardTemplate.FORMAT_VERSION}")
        }
        val titleKey = root.optString("titleKey").trim()
        if (titleKey.isEmpty()) return TemplateLoad.Bad("$source: 缺 titleKey")
        val emptyKey = root.optString("emptyTextKey").trim()
            .ifEmpty { ResultCardTemplate.DEFAULT_EMPTY_KEY }

        val groupArr: JSONArray = root.optJSONArray("groups")
            ?: return TemplateLoad.Bad("$source: 缺 groups")
        if (groupArr.length() == 0) return TemplateLoad.Bad("$source: groups 是空的")

        val seen = HashSet<String>()
        val groups = ArrayList<CardGroupSpec>(groupArr.length())
        readGroups(source, groupArr, groups, seen)?.let { return TemplateLoad.Bad(it) }

        val actionArr = root.optJSONArray("actions") ?: JSONArray()
        val actions = ArrayList<CardActionSpec>(actionArr.length())
        readActions(source, actionArr, actions)?.let { return TemplateLoad.Bad(it) }

        return TemplateLoad.Ok(
            ResultCardTemplate(
                templateId = id,
                version = version,
                titleKey = titleKey,
                emptyTextKey = emptyKey,
                groups = groups,
                actions = actions,
            ),
        )
    }

    /**
     * 读 `groups`（2026-10-29 从 [parse] 抽出）：一张模板的**形状校验**是独立的一件事，
     * 挤在 parse 里只会让它 79 行、还要靠缩进才看得出层级。
     * 返回 null＝通过，返回字符串＝错误（文案逐字保留，单测对着这些句子断言）。
     */
    private fun readGroups(
        source: String,
        groupArr: JSONArray,
        out: MutableList<CardGroupSpec>,
        seen: MutableSet<String>,
    ): String? {
        for (gi in 0 until groupArr.length()) {
            val g = groupArr.optJSONObject(gi) ?: return "$source: groups[$gi] 不是对象"
            val groupKey = g.optString("key").trim().ifEmpty { "g$gi" }
            val titleKeyOfGroup = g.optString("titleKey").trim().ifEmpty { null }
            val rowArr: JSONArray = g.optJSONArray("rows")
                ?: return "$source: groups[$gi] 缺 rows"
            if (rowArr.length() == 0) return "$source: groups[$gi] 没有行"
            val rows = ArrayList<CardRowSpec>(rowArr.length())
            for (ri in 0 until rowArr.length()) {
                val r = rowArr.optJSONObject(ri)
                    ?: return "$source: groups[$gi].rows[$ri] 不是对象"
                val key = r.optString("key").trim()
                if (key.isEmpty()) return "$source: groups[$gi].rows[$ri] 缺 key"
                if (!seen.add(key)) return "$source: key「$key」在同一模板里重复"
                val labelKey = r.optString("labelKey").trim()
                if (labelKey.isEmpty()) {
                    return "$source: 行「$key」缺 labelKey（文案不许写在 JSON 里）"
                }
                rows += CardRowSpec(
                    key = key,
                    labelKey = labelKey,
                    copyable = r.optBoolean("copyable", false),
                    dialable = r.optBoolean("dialable", false),
                    sensitiveInLog = r.optBoolean("sensitiveInLog", false),
                    collapsible = r.optBoolean("collapsible", false),
                )
            }
            out += CardGroupSpec(groupKey, titleKeyOfGroup, rows)
        }
        return null
    }

    /** 读 `actions`（同上，从 [parse] 抽出）：动作 id 必须落在闭集里，这是一条独立判据。 */
    private fun readActions(
        source: String,
        actionArr: JSONArray,
        out: MutableList<CardActionSpec>,
    ): String? {
        for (ai in 0 until actionArr.length()) {
            val a = actionArr.optJSONObject(ai)
                ?: return "$source: actions[$ai] 不是对象"
            val actionId = a.optString("id").trim()
            if (actionId !in CardActions.KNOWN_ACTION_IDS) {
                return "$source: actions[$ai] 的 id「$actionId」不在闭集 " +
                    CardActions.KNOWN_ACTION_IDS.joinToString("/")
            }
            val labelKey = a.optString("labelKey").trim()
            if (labelKey.isEmpty()) return "$source: 动作「$actionId」缺 labelKey"
            out += CardActionSpec(actionId, labelKey)
        }
        return null
    }
}

// ── 绑定（模板 ＋ 值 → 卡）─────────────────────────────────────────────────

object ResultCardBinder {

    /**
     * 把一份值表贴到模板上。
     *
     * @param values 行的 `key` → 原样值（producer 给的是**表里怎么写就怎么来**的字符串）
     * @param dials 行的 `key` → 可拨号码串（producer 已过 195 的形状判据；没给＝这一行不可拨）
     * @param lookup strings 取值（界面传 `stringResource`，单测传假表）
     */
    fun bind(
        template: ResultCardTemplate,
        values: Map<String, String>,
        lookup: (String) -> String,
        dials: Map<String, String> = emptyMap(),
        itemCount: Int = 0,
    ): ResultCardSpec {
        val empty = lookup(template.emptyTextKey)
        val groups = template.groups.map { g ->
            CardGroup(
                key = g.key,
                title = g.titleKey?.let(lookup),
                rows = g.rows.map { r ->
                    val raw = values[r.key]?.trim().orEmpty()
                    val filled = raw.isNotEmpty()
                    CardRow(
                        key = r.key,
                        label = lookup(r.labelKey),
                        value = if (filled) raw else empty,
                        copyable = r.copyable,
                        // 未填的行一律不可拨：写着「未填」的号码点下去只能拨个空号（负例④）
                        dialable = r.dialable && filled,
                        sensitiveInLog = r.sensitiveInLog,
                        collapsible = r.collapsible,
                        dial = if (r.dialable && filled) dials[r.key] else null,
                        filled = filled,
                    )
                },
            )
        }
        return ResultCardSpec(
            templateId = template.templateId,
            title = lookup(template.titleKey),
            groups = groups,
            actions = template.actions.map { CardAction(it.id, lookup(it.labelKey)) },
            itemCount = itemCount,
        )
    }
}

// ── 条数预算（≤ 屏 60%）────────────────────────────────────────────────────

/**
 * 「短就是短」的算术（派工单 §2.5 末条：单卡片高度 ≤ 屏 60%，超出先折「另有 N 项」）。
 *
 * 为什么不靠界面量完再决定：Compose 里「先画再量再删行」会闪一帧满卡。
 * 这里用**行高估算**在组装期就算好该留几行，界面只按结果画——估算值偏大一点没关系
 * （宁可早折一行，也不许整卡被挤成 60% 之外）。
 */
object ResultCardBudget {

    /** 卡高上限占可用高度的比例（派工单：≤60%）。 */
    const val HEIGHT_FRACTION = 0.6f

    /** 一行的估算高（label 13sp ＋ value 15sp 同一行：22dp 行高 ＋ 4dp 行距）。 */
    const val ROW_DP = 26

    /** 卡头＋动作条＋内距的估算高。 */
    const val CHROME_DP = 152

    /** 至少留几行（再矮也要看得见一条，否则卡是空的）。 */
    const val MIN_ROWS = 3

    /** 屏 60% 折成 dp。 */
    fun maxHeightDp(availableDp: Int): Int = (availableDp * HEIGHT_FRACTION).toInt()

    /** 60% 能摆几行。 */
    fun maxRows(availableDp: Int): Int =
        ((maxHeightDp(availableDp) - CHROME_DP) / ROW_DP).coerceAtLeast(MIN_ROWS)

    /** 折叠结果：留下的卡 ＋ 被折起的行数（界面写「另有 N 项」）。 */
    data class Folded(val card: ResultCardSpec, val hiddenRows: Int)

    /**
     * 按预算折行：**从尾部折**，组内保序（前几组先显示，最后那组可能只剩一半）。
     *
     * 不按「每组留一行」摊平：摊平之后每条都缺一半字段，用户得点开每组才知道缺什么；
     * 从尾部折则「前 N 条完整、后面折起来」，与她心里「先看头一票」的顺序一致。
     */
    fun fold(card: ResultCardSpec, maxRows: Int): Folded {
        val total = card.rows.size
        if (total <= maxRows || maxRows <= 0) return Folded(card, 0)
        var left = maxRows
        val outGroups = ArrayList<CardGroup>(card.groups.size)
        for (g in card.groups) {
            if (left <= 0) break
            val take = minOf(left, g.rows.size)
            outGroups += g.copy(rows = g.rows.take(take))
            left -= take
        }
        return Folded(card.copy(groups = outGroups), total - maxRows)
    }
}

/**
 * 两张卡是不是「同一件事」（负例⑦：同一批结果不许弹两张一样的卡）。
 *
 * 判法两级：① 两边都给了身份 ⇒ 比身份（同一条换了个值还是同一条）；
 * ② 有一边没给 ⇒ 逐行比值（严格：值不同就不是同一件事，宁可两张也不许误吞）。
 */
fun sameResult(a: ResultCardSpec, b: ResultCardSpec): Boolean {
    if (a.templateId != b.templateId) return false
    if (a.identity.isNotEmpty() && b.identity.isNotEmpty()) return a.identity == b.identity
    val ra = a.rows
    val rb = b.rows
    if (ra.size != rb.size) return false
    for (i in ra.indices) {
        if (ra[i].key != rb[i].key) return false
        if (ra[i].value != rb[i].value) return false
    }
    return true
}
