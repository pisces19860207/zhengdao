// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 一句话查（派工单 §3：本地规则优先、毫秒级、离线、不调模型）。
//
// ## 匹配的三个来源都是**表里的真实值**
// 范围（方向/卸点）、车牌、单号、货名四样，都拿当天表里出现过的值去比，**不做任何词库**：
// 「北京」能命中是因为今天确实有往北京的单子。表里没有这个词 ⇒ [QueryOutcome.NoMatch] ⇒
// 答案写「这份表里没有北京的记录」，不许推「可能在路上」（§3 拒答条与 §3.5 禁推断词是同一条）。
//
// ## 歧义只反问一行
// 一个词命中**多个**值时不自己挑：`北京` 同时命中 `北京朝阳` 与 `北京通州` ⇒ 反问一句
// 「问的是哪个？」。例外是**完全相等**优先：表里真有一列写着 `北京`，那就按 `北京` 算——
// 这不是猜，是「你说的那个名字表里就有」这条硬事实。
//
// ## 车牌与单号的形状规则为什么只这么宽
// 车牌要求「省份汉字 + 字母 + 4~6 位」，单号要求**连续 5 位以上数字**。
// 4 位不要：`10-25`、`0930` 这种日期与钟点会被当成单号，一句「今天的表几点导的」就会变成
// 「查不到单号 1025」——那是把没在问的东西答出来了，比不答坏。
// 命不中形状规则时，整句只当范围/货名去比，认不出就落 [LogisticsIntent.LIST]。
package com.example.zhengdao.baopu.logistics

/** 问的是哪件事（本地规则的四种问法 + 一个兜底列单）。 */
enum class LogisticsIntent {
    /** 「装了没」「发了没」：要 已装/缺口 的明细。 */
    LOADED,

    /** 「几件」「几车」「几单」：要数量。 */
    COUNT,

    /** 「什么货」：要货名分布。 */
    GOODS,

    /** 「这车谁的」「老张电话」：要司机与号码（2026-10-25 她补的口径，号码原样、可点直拨）。 */
    WHO,

    /** 「今天还有哪些没发」：要待发名单。 */
    UNSENT,

    /** 认不出问法：把单子列出来（不编答案）。 */
    LIST,
    ;

    companion object {
        /** 问法关键词（**子串**命中；列表顺序就是优先级）。 */
        private val KEYS: List<Pair<LogisticsIntent, List<String>>> = listOf(
            UNSENT to listOf("还没发", "没发", "未发", "没有发", "哪些没", "还没走", "没走", "还没装车"),
            LOADED to listOf("装了没", "装了么", "装了没有", "装没装", "没装", "装好", "上了没", "发了没", "走了没", "到车了没", "装了"),
            // WHO 排在 GOODS/COUNT 之前、LOADED 之后：「谁」「电话」这类词不会出现在装没装的问法里；
            // 「几车谁的」这种半截问法既然点名了「谁」，就先给她人（数量那一问她还能接着问）。
            WHO to listOf("谁的", "谁", "电话", "手机", "号码", "司机", "联系", "咋联系", "怎么联系", "联系方式"),
            GOODS to listOf("什么货", "啥货", "什么品名", "货名", "什么品类", "装的是什么", "是什么货"),
            COUNT to listOf("几件", "多少件", "几个", "件数", "几车", "多少车", "几台", "多少台", "几单", "多少单", "多少票", "几票"),
        )

        /**
         * 优先级：`今天还有哪些没发` 里有「没」也带「发」，但它是 UNSENT 不是 LOADED，
         * 所以 UNSENT 排在 LOADED 前面（顺序是判据，测试逐条钉）。
         */
        fun firstIntent(normalized: String): LogisticsIntent =
            KEYS.firstOrNull { (_, words) -> words.any { normalized.contains(it) } }?.first ?: LIST
    }
}

/** 一句话拆出来的条件（全部是「表里真有的值」，或形状规则抓到的车牌/单号）。 */
data class QuerySpec(
    val intent: LogisticsIntent,

    /** 命中的范围值（方向或卸点原文）；空 = 没限定范围（问的是全天）。 */
    val scopes: List<String>,

    /** 表里有单子、但这句话里的名词**一个值都没命中** ⇒ 原样带出去，答案要说「这份表里没有『北京』的记录」。 */
    val unmatchedTerm: String,

    /** 命中的车牌（归一后大写）；`null` = 没问车。 */
    val plate: String?,

    /** 命中的单号（精确优先，其次唯一包含）；`null` = 没问单号。 */
    val waybill: String?,

    /** 命中的货名原文；`null` = 没问货名。 */
    val goods: String?,

    /** 一个范围词命中多个值 ⇒ 要反问（此时 [scopes] 装的是候选值，不是答案条件）。 */
    val ambiguous: Boolean,

    /**
     * 命中的司机名（表里真有的那几个写法）。
     *
     * 与 [scopes] 不同，这里**不制造歧义反问**：命中两个司机时把两人的号一起列出去就是完整答案
     * （「王师傅、张师傅 谁的？」这种反问对「师傅电话」这句毫无用处），而范围那一侧要是并两个方向
     * 数就会算错车数——两条判据的代价不同，所以形状一样而处理不同。
     */
    val drivers: List<String> = emptyList(),

    /**
     * 这句话问的是**这张表根本没有的一列**（「货主」「运费」…）：原文放这里，答案就说
     * 「表里没有『货主』这一列」——和「没有老张的记录」（[unmatchedTerm]）是两件不同的事：
     * 前者是她把人问错了，后者是这张配货单压根不给这一维。
     */
    val unmatchedColumn: String = "",
) {
    val hasScope: Boolean get() = !ambiguous && unmatchedTerm.isEmpty() && scopes.isNotEmpty()
}

/** 查询结局（四种都要能变成一句诚实的话）。 */
sealed interface QueryOutcome {
    /** 这一天根本没有表：答案里**不许出现任何数字**（判据：无表 10 句）。 */
    data object NoTable : QueryOutcome

    /** 有表、范围词命中多个值 ⇒ 反问一行。 */
    data class Ambiguous(val term: String, val options: List<String>) : QueryOutcome

    /** 有表、但这句话问的东西表里没有。 */
    data class NoMatch(val spec: QuerySpec) : QueryOutcome

    /** 有表、有条件、有行。 */
    data class Found(val spec: QuerySpec, val agg: Aggregate) : QueryOutcome
}

/** 一句话 → 条件 → 行 → 聚合（纯函数链，全在内存里跑；查询词不落库、不进日志）。 */
object LogisticsQuery {

    /** 车牌：省份汉字 + 发牌机关字母 + 4~6 位（`冀A12345`、`京A12345D`）。 */
    private val PLATE_RE = Regex("""[\u4e00-\u9fa5][A-Za-z][A-Za-z0-9]{4,6}""")

    /** 没带省份汉字时的尾巴（`A12345`），只用来对**已存车牌的后缀**，不独立成立。 */
    private val PLATE_TAIL_RE = Regex("""[A-Za-z][A-Za-z0-9]{4,6}""")

    /** 单号：连续 5 位以上数字（4 位不要，理由见文件头）。 */
    private val WAYBILL_RE = Regex("""(?<!\d)\d{5,20}(?!\d)""")

    /** 叫人不叫全名的两种形状：「老张／小张」与「张师傅／张哥／张经理」（姓氏档，见 [driverOf]）。 */
    private val SURNAME_RE = Regex("""[老小]([\u4e00-\u9fa5])|([\u4e00-\u9fa5])(?:师傅|师父|哥|姐|经理|老板|队长)""")

    /** 问法里的疑问词与标点——「减法提取名词」用（「北京的货装了没」留下「北京」）。 */
    private val STOPWORDS = listOf(
        "请问", "帮我看", "查一下", "看一下", "看看", "怎么", "什么时候", "几点",
        "今天", "今日", "咱们", "这批", "那些", "哪些", "有没有", "还有", "已经", "现在", "一共", "总共", "合计",
        "装了没", "装了么", "装了没有", "装没装", "没装", "装好", "上了没", "发了没", "走了没", "到车了没", "装车", "装了", "没发", "还没", "未发", "没走", "哪些没", "没有发", "还没走",
        "什么货", "啥货", "什么品名", "货名", "什么品类", "是什么货", "装的是什么", "是什么", "什么",
        // 问「谁的／电话」时这几个字是**疑问的形状**，不是名词：不剔掉它们，「老张电话」剩下的
        // 名词就成了「电话」，答案会写「表里没有『电话』的记录」——那是答非所问。
        // 「这车／这台车」同理：是**指代眼前的这一车**（2026-10-25「这车谁的」口径的主语），
        // 不是表里的一个值；不剔的话「这车谁的」会落成 NoMatch，把最常用的问法答死。
        "谁的", "谁", "电话", "手机号", "手机号码", "号码", "司机", "师傅", "联系方式", "怎么联系", "咋联系", "联系",
        "这车", "这台车",
        "几件", "多少件", "件数", "几个", "几车", "多少车", "几台", "多少台", "几单", "多少单", "多少票", "几票", "多少",
        "的", "了", "吗", "呢", "啊", "吧", "是", "有", "和", "与", "以及", "没",
        "，", "。", "？", "?", "、", "：", "；", "！", " ",
    )

    /** 减法剔词的次序：长词先剔，否则「装没装」会被「没」先拆散，剩下「装」这种 1 字段。 */
    private val STOPWORDS_BY_LEN = STOPWORDS.sortedBy { -it.length }

    /** 面板与工具的唯一入口。 */
    fun ask(store: LogisticsStore, day: String, text: String, basis: LogisticsBasis): QueryOutcome {
        val rows = store.rowsFor(day)
        if (rows.isEmpty()) return QueryOutcome.NoTable
        val spec = parse(text, rows)
        if (spec.ambiguous) return QueryOutcome.Ambiguous(spec.scopes.firstOrNull() ?: "", spec.scopes)
        if (spec.unmatchedTerm.isNotEmpty()) return QueryOutcome.NoMatch(spec)
        val hit = filter(rows, spec)
        if (hit.isEmpty()) return QueryOutcome.NoMatch(spec)
        return QueryOutcome.Found(spec, LogisticsAggregate.aggregate(hit, basis))
    }

    /** 拆一句话（[rows] 提供当天真实存在的值，作为唯一的匹配词典）。 */
    fun parse(text: String, rows: List<StoredRow>): QuerySpec {
        val norm = text.trim().lowercase()
        val intent = LogisticsIntent.firstIntent(norm)
        val plate = plateOf(norm, rows)
        val waybill = waybillOf(norm, rows)

        // 范围：拿表里出现过的方向/卸点去比这句话，完全相等优先，多个命中就反问
        val values = rows.flatMap { listOf(it.direction, it.unload) }
            .filter { it.isNotEmpty() }
            .distinct()
        // 两条命中路有次序，不能颠倒：先认「整值出现在这句话里」（表里就写着她说的这个词），
        // 一个都没有才退到「值是这句话里那个名词的更长的写法」——表里写 `北京朝阳` 时她只会说
        // `北京`，只留第一条会让最常用的问法永远查不出来（判据：LogisticsQueryTest 的「范围用包含」）。
        val whole = values.filter { norm.contains(LogisticsContract.normalize(it)) }
        val terms = termsOf(norm)
        val hits = whole.ifEmpty { values.filter { v -> terms.any { t -> LogisticsContract.normalize(v).contains(t) } } }
        val exact = hits.filter { LogisticsContract.normalize(it) == LogisticsContract.normalize(text) }
        val scopes: List<String>
        val ambiguous: Boolean
        when {
            exact.isNotEmpty() -> {
                scopes = exact
                ambiguous = false
            }
            hits.size > 1 -> {
                scopes = hits
                ambiguous = true
            }
            hits.size == 1 -> {
                scopes = hits
                ambiguous = false
            }
            else -> {
                scopes = emptyList()
                ambiguous = false
            }
        }
        // 表里一个值都没命中、四个条件一个都没成立，但这句话里确实剩下个名词 ⇒ 它就是「表里没有」的那个词。
        // `goods` 也要参与这条判断：认到了货名还说「表里没有『钢材货』的记录」是答非所问。
        val goods = rows.map { it.goods }.filter { it.isNotEmpty() }.distinct()
            .firstOrNull { norm.contains(LogisticsContract.normalize(it)) }
        val drivers = driverOf(norm, terms, rows)
        val none = scopes.isEmpty() && plate == null && waybill == null && goods == null && drivers.isEmpty()
        val unmatched = if (none) leftoverTerm(norm) else ""
        // 「老张的电话」认不到人 ⇒ 说「没有老张的记录」；「货主是谁」问的是**根本不在配货单上的一维**
        // ⇒ 说「没有货主这一列」。两句话的下一步动作完全不同：前者是把人问错了，后者是这张表压根不给。
        val column = if (unmatched.isEmpty()) "" else LogisticsContract.absentColumn(unmatched, rows)
        return QuerySpec(intent, scopes, unmatched, plate, waybill, goods, ambiguous, drivers, column)
    }

    /**
     * 司机名的三档认法（表里出现过的名字是唯一的词典）。
     *
     * ① 整名出现在这句话里（表里写「张师傅」她也说「张师傅」）；② 这句话剩下的名词与整名互相包含；
     * ③ **姓氏**档：「老张」「小张」「张哥」「张师傅」这类叫法，表里可能写的是另一个形状。
     * 第三档只在表里认得出这个姓时成立，同姓多人就把几个人一起带上（见 [QuerySpec.drivers] 的理由）。
     */
    internal fun driverOf(norm: String, terms: List<String>, rows: List<StoredRow>): List<String> {
        val names = rows.map { it.driver }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return emptyList()
        val whole = names.filter { norm.contains(LogisticsContract.normalize(it)) }
        if (whole.isNotEmpty()) return whole
        val byTerm = names.filter { n ->
            val d = LogisticsContract.normalize(n)
            terms.any { t -> d.contains(t) || t.contains(d) }
        }
        if (byTerm.isNotEmpty()) return byTerm
        val surnames = SURNAME_RE.findAll(norm)
            .flatMap { m -> listOf(m.groupValues[1], m.groupValues[2]) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (surnames.isEmpty()) return emptyList()
        return names.filter { n -> surnames.any { s -> LogisticsContract.normalize(n).contains(s) } }
    }

    /**
     * 这句话里剩下的名词段（[leftoverTerm] 与范围的第二条命中路共用同一次剔词）。
     *
     * 做法是**减法**而不是加法：把疑问词、标点、以及形状规则已经认领的车牌/单号从这句话里剔掉，
     * 剩下的每一段都是名词候选。不用词库、不分词——装一个中文分词器是为了一句问话付一台手机的内存，
     * 而且分词器会把「北京朝阳」切成两个词，反而造出一个表里没有的值。
     */
    internal fun termsOf(norm: String): List<String> {
        var rest = norm
        STOPWORDS_BY_LEN.forEach { w -> rest = rest.replace(w, "|") }
        PLATE_RE.findAll(norm).forEach { m -> rest = rest.replace(m.value.lowercase(), "|") }
        WAYBILL_RE.findAll(norm).forEach { m -> rest = rest.replace(m.value, "|") }
        return rest.split('|')
            .map { it.trim() }
            .filter { it.length >= 2 }
    }

    /** 最长的那段名词（用来把「这份表里没有『北京』的记录」说准）。 */
    internal fun leftoverTerm(norm: String): String = termsOf(norm).maxByOrNull { it.length }.orEmpty()

    /** 车牌：先按形状抓，抓到就去库里核；没带省份汉字时只认**表里有的那个后缀**（唯一命中才算）。 */
    internal fun plateOf(norm: String, rows: List<StoredRow>): String? {
        PLATE_RE.find(norm)?.value?.uppercase()?.let { shaped ->
            val plates = rows.flatMap { it.plates() }
            if (plates.any { it == shaped }) return shaped
            // 形状像车牌但表里没有：仍然带上它，答案会说「这份表里没有冀A99999的记录」——
            // 这不是猜，是把用户说的那个东西原样还给她
            return shaped
        }
        val tail = PLATE_TAIL_RE.find(norm)?.value?.uppercase() ?: return null
        val plates = rows.flatMap { it.plates() }.distinct()
        val byTail = plates.filter { it.endsWith(tail) }
        return if (byTail.size == 1) byTail.first() else null
    }

    /** 单号：精确优先，其次**唯一**包含（包含命中两单以上就当没抓到，反问比猜强）。 */
    internal fun waybillOf(norm: String, rows: List<StoredRow>): String? {
        val token = WAYBILL_RE.findAll(norm).maxByOrNull { it.value.length }?.value ?: return null
        val up = token.uppercase()
        val ids = rows.map { it.waybill }
        if (ids.any { it == up }) return up
        val contains = ids.filter { it.contains(up) || up.contains(it) }
        return if (contains.size == 1) contains.first() else null
    }

    /** 条件筛行（范围用**包含**：命中「北京」时「北京朝阳」的单子也是往北京的货）。 */
    fun filter(rows: List<StoredRow>, spec: QuerySpec): List<StoredRow> = rows.filter { r ->
        val scopeOk = spec.scopes.isEmpty() || spec.scopes.any { s ->
            r.direction.contains(s) || r.unload.contains(s) || s.contains(r.direction.ifEmpty { r.unload })
        }
        val plateOk = spec.plate == null || r.plates().any { it == spec.plate }
        val waybillOk = spec.waybill == null || r.waybill == spec.waybill ||
            r.waybill.contains(spec.waybill) || spec.waybill.contains(r.waybill)
        val goodsOk = spec.goods == null || r.goods.contains(spec.goods ?: "")
        val driverOk = spec.drivers.isEmpty() || spec.drivers.any { d -> r.driver.contains(d) }
        scopeOk && plateOk && waybillOk && goodsOk && driverOk
    }
}
