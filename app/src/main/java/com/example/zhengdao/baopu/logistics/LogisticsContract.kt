// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 表头契约（判据本体，纯函数、零 Android、零网络）。
//
// 依据：派工单-2026-10-24-第一百九十五批 §1，含 **2026-10-25 用户改单后的两档口径**
// （原「缺列即拒读」作废）与 **§1.8 冻结口径**：
// - **硬列**（缺一即拒读）：`单号`；`方向` 与 `卸点` **至少一列**；表结构本身可解析
//   （有表头行、有数据行、列名不重复）——这一档管的是「认不认识这张表」；
// - **可降级列**（缺了少报一维，**不拒读**）：`配货单号`/`货名`/`件数`/`重量`/`司机`/`手机号`/`车牌`/`状态`
//   ——这一档管的是「能报哪几维」。缺哪列 ⇒ 答案里明示缺哪列，且该维**不参与任何计算与告警**；
//   `手机号` 与 `配货单号` 是 **§1.8（2026-10-25 冻结）** 点名的两维：
//   「每一张配货单对应一辆车、一个司机和司机手机号」⇒ 车数默认按**配货单**去重，
//   这两列与姓名、车牌一样**原样显示、台账照实写**，但不进日志、不进审计、不进模型上下文；
// - **列名走别名表**：只有「别名表里一个都对不上」才叫「表头文字变了」⇒ 拒读；
//   `车牌号`→`车牌` 这类同义写法是**命中别名**，不是表头变了（派工单原负例②的例子按此改判，
//   已在交付报告里单列一条给蓝总管核对）。
//
// ## `单号` 与 `配货单号` 为什么是两个字段（§1.8.1 的直接后果）
// 真实导出里两者并存（194 批实测：40 行运单、9 张配货单），它们**不是一个东西的两写法**：
// 合并成一个字段时，「同一字段两列并存取最左」那条规矩会让主键落在配货单号上，
// 于是 40 行压成 9 行、31 行**静默消失**。所以 `单号`（运单身份，主键与去重用它）与
// `配货单号`（装车身份，§1.8 的「车」用它）各归各的字段，谁也不许替谁。
//
// 一句话原则：**缺了「识别/范围」就拒读，缺了「描述」就少报一维并明说**。
package com.example.zhengdao.baopu.logistics

/** 契约认识的十二列。[label] 是给用户看的话（拒读文案与脚注都用它，不再另造一份字符串）。 */
enum class LogisticsField(val label: String) {
    WAYBILL("单号"),

    /** 配货单号：§1.8 冻结的「车」就是按它去重（一张配货单＝一辆车）。 */
    ORDER_NO("配货单号"),
    DIRECTION("方向"),
    UNLOAD("卸点"),
    GOODS("货名"),
    PIECES("件数"),
    WEIGHT("重量"),

    /**
     * 体积/方数：与 [WEIGHT] 同一纪律——**只读进表、只原样展示，本批不参与任何计算与告警**
     * （重泡货的判断归 194；单位是立方米这件事由 §1.8 裁定，代码里仍然只存原文不去换算）。
     */
    VOLUME("体积"),
    DRIVER("司机"),
    PHONE("手机号"),
    PLATE("车牌"),
    STATUS("状态"),
}

/**
 * 表头核对的两种结局。
 *
 * 刻意不做第三种「勉强读」：派工单 §1 的原话是「不许猜列、不许错位填充」，
 * 所以要么 [Ok]（含降级维度的如实登记），要么 [Rejected] 说清缺哪列。
 */
sealed interface HeaderOutcome {

    /**
     * @param columnOf 字段 → 表头里的列号（0 起）；只含**认到的**列
     * @param absentSoft 缺掉的可降级列（进答案脚注「缺件数列」）
     * @param unclaimed 表头里没被任何字段认领的列名（照读其余，但如实列出来）
     */
    data class Ok(
        val columnOf: Map<LogisticsField, Int>,
        val absentSoft: Set<LogisticsField>,
        val unclaimed: List<String>,
        val header: List<String>,
    ) : HeaderOutcome

    /** @param reason 直接给用户看的一句话：必须点名缺哪列、以及认识的写法有哪些 */
    data class Rejected(val reason: String) : HeaderOutcome
}

/**
 * 表头即契约。
 *
 * ## 扩展方式（派工单要求「加一行就能扩，不改判据」）
 * 往 [ALIASES] 对应字段的名字集合里加一个写法即可——[resolve] 只做「归一后精确相等」，
 * 没有任何按位置兜底、没有模糊匹配、没有相似度阈值。加错别名的后果是那张表被认领，
 * 所以别名一律写**完整列名**（`重量（吨）`这种带单位整体写进来），不写词根。
 *
 * ## 为什么归一只做三件事
 * 去空格（含全角）、剥掉首尾括号里的单位、大小写统一。再多一步（同义词展开、繁简转换、
 * 去掉「合计」这种后缀）就开始猜了；猜错的代价是把件数列当成重量列，比拒读严重得多。
 */
object LogisticsContract {

    /** 硬列：缺任何一个 ⇒ 整份拒读。 */
    val HARD: Set<LogisticsField> = setOf(LogisticsField.WAYBILL)

    /** 「方向与卸点至少一列」这条硬规则的另一半（两个都不在 [HARD] 里，靠 [SCOPE_PAIR] 成对判）。 */
    val SCOPE_PAIR: Set<LogisticsField> = setOf(LogisticsField.DIRECTION, LogisticsField.UNLOAD)

    /** 可降级列：缺了不拒读，但该维不显示、不计算、不告警。 */
    val SOFT: Set<LogisticsField> = setOf(
        LogisticsField.ORDER_NO,
        LogisticsField.GOODS,
        LogisticsField.PIECES,
        LogisticsField.WEIGHT,
        LogisticsField.VOLUME,
        LogisticsField.DRIVER,
        LogisticsField.PHONE,
        LogisticsField.PLATE,
        LogisticsField.STATUS,
    )

    /**
     * 别名表（**唯一一张**，写死在代码里，表驱动测试逐条打）。
     *
     * 收录口径：只收 T9 这类零担专线导出里真会出现的整列名；一个写法只归一个字段，
     * 出现跨字段歧义（例如 `数量` 既可能是件数也可能是重量）时**只认领更窄的那个**，
     * 宁可漏认领走拒读，也不要错位填充。
     */
    val ALIASES: Map<LogisticsField, Set<String>> = mapOf(
        LogisticsField.WAYBILL to setOf("单号", "运单号", "单据号", "制单号", "货号", "运单编号"),
        // §1.8.1 的「车」按这一列去重（一张配货单＝一辆车）。真实导出里它与 `单号` 并存，
        // 所以它**不进 WAYBILL 的别名**：进了就会有两列并存时认领到配货单号上，
        // 40 行运单被压成 9 行（§1.8.2 点名的「配货单号」是另一样东西，不是单号的别名）。
        LogisticsField.ORDER_NO to setOf("配货单号", "配货单", "配载单号", "派车单号", "派车单"),
        LogisticsField.DIRECTION to setOf("方向", "线路", "流向", "目的地方向", "到达方向", "配载方向"),
        LogisticsField.UNLOAD to setOf("卸点", "卸货点", "卸货地点", "到达点", "目的地", "到站", "收货地"),
        LogisticsField.GOODS to setOf("货名", "品名", "货物名称", "货物", "品名规格", "货品名称"),
        // **「合计/总」那一档不认领**（194 批 §5.5 的真实表头里 `件数` 与 `件数合计`、
        // `总体积` 与 `体积（方）` 同时出现）：合计列是**另一粒度**（按配货单汇总）的数，
        // 把它当这一行的件数加起来就是翻倍——§1.8 那条「件数合计不翻倍」钉的就是这个。
        // 于是 `件数合计`/`总件数` 一律不认领，它们落进 [HeaderOutcome.Ok.unclaimed] 如实报出来。
        LogisticsField.PIECES to setOf("件数", "件", "数量", "包装件数"),
        LogisticsField.WEIGHT to setOf("重量", "总重量", "计费重量", "重量（吨）", "重量(吨)", "重量（公斤）", "重量(kg)", "吨位", "毛重"),
        // 194 批 §12 的实测表头写法：`总体积` / `总重量`（另一版写作 `体积（方）`/`重量（吨）`），
        // 别名按**整列名**收（带单位或带「总」前缀的写法都整体匹配）。
        // 裸「方」不收：它可能是「付款方式」这种写法的一部分，认错列比不认列坏得多。
        // 两种写法并存时认领**最左**那一列，另一列进 unclaimed（`体积（方）` 就是这样落进去的）。
        LogisticsField.VOLUME to setOf("体积", "总体积", "体积（方）", "体积(方)", "体积（立方米）", "体积(m3)", "方数", "方数（立方米）"),
        LogisticsField.DRIVER to setOf("司机", "司机姓名", "驾驶员", "师傅"),
        // 电话类写法从 司机 挪到这里：§1.8 把「司机姓名」与「司机手机号」当成两列，
        // 混进一个字段的话导出里同时有「司机」和「司机电话」时会把号码写进姓名那一格。
        // 裸「电话」「手机」不收：配货单上没客户信息，但调度电话这种写法一旦出现就是另一维。
        LogisticsField.PHONE to setOf("手机号", "司机手机号", "司机手机", "手机号码", "司机电话", "联系电话"),
        LogisticsField.PLATE to setOf("车牌", "车牌号", "车号", "车牌号码", "号牌号码", "车牌照"),
        LogisticsField.STATUS to setOf("状态", "装车状态", "配载状态", "发货状态", "运单状态", "状态说明"),
    )

    /** 某个字段认识的写法（拒读文案里要念给用户听）。 */
    fun aliasesOf(field: LogisticsField): List<String> = ALIASES[field].orEmpty().toList()

    /**
     * 配货单上**根本不会有**的那几维（派工单 §1.8.5：配货单没有客户信息）。
     *
     * 她问「货主是谁」「运费多少」时，答案不许是「没有『货主』的**记录**」——那暗示这类信息
     * 可能在别行别列，她会回去翻表。事实是 T9 的配货单压根不给这一维，所以答案点名
     * 「这一列」；这一档只写**确定不在配货单上的词**，宁可漏、不许把「钢材」这种货名错收进来。
     */
    val NOT_ON_SHIPMENT: Set<String> = setOf(
        "货主", "客户", "发货人", "托运人", "收货人", "收件人", "收货单位", "收货人姓名",
        "运费", "单价", "金额", "装卸费", "油费", "提货费", "回单", "备注", "里程", "公里数", "公里",
    )

    /**
     * 这句话剩下的名词是不是在问「这张表没有的那一列」。
     *
     * @return 列名原文（答案层拿它点名）；不是列名式的问法就回空串，答案照旧走「没有…的记录」。
     *   认到/降级登记过（行上带着这一维）的字段**不许**回「没有这一列」——比如表里有 `状态`
     *   而她问「状态」，那是查询没命中，不是列不在。
     */
    fun absentColumn(term: String, rows: List<StoredRow>): String {
        val t = normalize(term)
        if (t.isEmpty()) return ""
        val field = ALIASES.entries.firstOrNull { (f, names) ->
            normalize(f.label) == t || names.any { normalize(it) == t }
        }?.key
        if (field != null) {
            val absentHere = rows.all { field in it.missingFields() }
            return if (absentHere) term else ""
        }
        val offTable = NOT_ON_SHIPMENT.any { normalize(it) == t }
        return if (offTable) term else ""
    }

    /**
     * 列名归一：剥 BOM、去首尾空白与内部空格（含全角）、剥掉首尾成对括号外的空白、拉丁字母统一小写。
     *
     * ⚠️ 不做的事：不去「合计/小计」后缀、不拆斜杠并列名、不做繁简与同义转换。
     * 这些都是猜，猜错就是把 A 列塞进 B 字段。
     */
    fun normalize(raw: String): String {
        val noSpace = raw.trim().removePrefix("\uFEFF").replace(" ", "").replace("　", "")
        if (noSpace.isEmpty()) return ""
        val last = noSpace.last()
        val paired = (noSpace.first() == '(' && last == ')') || (noSpace.first() == '（' && last == '）')
        return if (paired) noSpace.substring(1, noSpace.length - 1).lowercase() else noSpace.lowercase()
    }

    /**
     * 核对表头。
     *
     * 判定次序（次序本身是判据，不许调换）：
     * 1. 空表头 ⇒ 拒读；
     * 2. 归一后重名列 ⇒ 拒读（重名列认领给谁都是猜）；
     * 3. 硬列 [LogisticsField.WAYBILL] 不在 ⇒ 拒读；
     * 4. [SCOPE_PAIR] 两列都不在 ⇒ 拒读（没有范围，「北京的装了没」就无从回答）；
     * 5. 其余 ⇒ [Ok]，缺掉的可降级列进 [Ok.absentSoft]，没人认领的列进 [Ok.unknown]。
     */
    fun resolve(header: List<String>): HeaderOutcome {
        val cells = header.map { normalize(it) }
        if (cells.none { it.isNotEmpty() }) {
            return HeaderOutcome.Rejected("表头是空的：没找到列名行。")
        }
        val dups = cells.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (dups.isNotEmpty()) {
            return HeaderOutcome.Rejected("表头有重复列名：${dups.joinToString("、")}——同一列名认领给谁都是猜，请核对导出表。")
        }
        // 列名 → 字段（一列最多认领一个字段；认领不上的列留在 unknown 里如实报）
        val columnOf = LinkedHashMap<LogisticsField, Int>()
        val claimed = HashSet<Int>()
        cells.forEachIndexed { index, cell ->
            if (cell.isEmpty()) return@forEachIndexed
            val field = ALIASES.entries.firstOrNull { it.value.any { alias -> normalize(alias) == cell } }?.key
            if (field != null && field !in columnOf) {
                // 同一字段两列并存（导出里同时有「车牌」和「车牌号」）⇒ 只认**最左**那一列，
                // 另一列落到 [HeaderOutcome.Ok.unclaimed] 里如实报出来。取最左而不是取相似度高的是
                // 因为「哪一列才是真值」在这两种写法之间没法判，再判就是猜。
                columnOf[field] = index
                claimed += index
            }
        }
        val missingHard = HARD.filter { it !in columnOf }
        if (missingHard.isNotEmpty()) {
            val names = missingHard.joinToString("、") { "「${it.label}」" }
            val known = missingHard.joinToString("；") { f -> "${f.label}认识的写法：${aliasesOf(f).joinToString("／")}" }
            return HeaderOutcome.Rejected(
                "表头变了，请核对：找不到${names}列。${known}。这张表没有读入，一行都没记。",
            )
        }
        if (SCOPE_PAIR.none { it in columnOf }) {
            return HeaderOutcome.Rejected(
                "表头变了，请核对：「方向」与「卸点」至少要有一列，这张表两列都没有——" +
                    "没有范围就没法回答哪个方向装没装。这张表没有读入，一行都没记。",
            )
        }
        val unclaimed = cells.indices.filter { it !in claimed && cells[it].isNotEmpty() }.map { header[it].trim() }
        // 降级名单只按**可降级档**算：方向/卸点是「范围」那一档，两列里缺一个不算降级
        // （硬规则已经保证至少有一个），把它们塞进脚注会得到一句假话「缺卸点列」。
        return HeaderOutcome.Ok(columnOf, SOFT - columnOf.keys, unclaimed, header)
    }
}
