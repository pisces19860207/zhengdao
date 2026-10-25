// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · `logistics_query`（派工单 §3 的 A 档：只读 agent 工具；
// 2026-10-25 用户裁定参数形状与解锁话术，本文件逐字照做）。
//
// ## 为什么参数是**结构化**的而不是「把原话给我」
// 裁定给的是 `{direction?, plate?, waybill?, goods?, ask}`。这句话在防两件事：
// 1. 让模型把用户原话抄进来 ⇒ 本地那句 NL 解析要再判一遍意图，判出什么模型没法预期，
//    同一句问话两次运行可能走成两个条件；
// 2. 查询原话一旦成为参数，它就会进工具调用记录 ⇒ 派工单 §7「查询词不落库、不进日志」这条
//    就被工具面自己破了。结构化参数只带**表里本来就有的值**（方向、车牌、单号），
//    那是数据不是陈述，落进本轮请求里也不新增隐私面。
//
// ## 三档 ask 不多不少
// `loaded|count|goods` 是裁定原话。没加 `unsent`：未发名单归**播报**（面板与打开时提示），
// 塞进工具就等于同一件事有两条路径、两套话术。
//
// ## 「这车谁的／司机电话」在这层被拒（2026-10-25 新增口径）
// 答「谁」必然要把司机姓名与手机号写进答案，而答案是**给模型读的** ⇒ 等于出了本机，
// 撞上 §1.8.3「姓名与手机号不进模型上下文」。所以模型面永远不给这条路：who 一类的 ask
// 在参数检查处就被拒并指去抽屉面板（面板那一面才拿得到原样显示的授权）。
//
// ## 没表时回 JSON 而不是回错误
// `ToolOutcome.Failed` 会被引擎当成「工具坏了」，模型会重试；而「今天还没有表」不是故障，
// 是一个需要它转述给用户的事实。所以它是一条正常的 `found:false` 结果，`hint` 给出去路。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.logistics.LogisticsAggregate
import com.example.zhengdao.baopu.logistics.LogisticsAsk
import com.example.zhengdao.baopu.logistics.LogisticsBasis
import com.example.zhengdao.baopu.logistics.LogisticsField
import com.example.zhengdao.baopu.logistics.LogisticsIntent
import com.example.zhengdao.baopu.logistics.LogisticsQuery
import com.example.zhengdao.baopu.logistics.LogisticsRows
import com.example.zhengdao.baopu.logistics.LogisticsStore
import com.example.zhengdao.baopu.logistics.QueryOutcome
import com.example.zhengdao.baopu.logistics.QuerySpec
import com.example.zhengdao.baopu.logistics.StoredRow
import org.json.JSONObject
import java.util.TimeZone

/** 当日发货表的只读查询工具（零网络、零模型调用；查的是本机库里已经导入的表）。 */
internal object LogisticsQueryTool {

    const val NAME = "logistics_query"

    const val ARG_DIRECTION = "direction"
    const val ARG_PLATE = "plate"
    const val ARG_WAYBILL = "waybill"
    const val ARG_GOODS = "goods"
    const val ARG_ASK = "ask"

    /** `ask` 的取值（裁定的三档，一个不多）。 */
    val ASKS: List<String> = listOf("loaded", "count", "goods")

    /** 模型拿来问「谁」的常见写法（2026-10-25 新增口径：这一层不给答案，只给去路）。 */
    val WHOISH_ASKS: Set<String> = setOf("who", "whos", "whose", "driver", "phone", "mobile", "tel", "contact", "联系人", "联系电话", "谁的", "电话", "手机") +
        // 「司机」是列名：壳里不许写字面量（门禁钉着），认词也走 [LogisticsField] 的唯一出处
        setOf(LogisticsField.DRIVER.label)

    /**
     * who 一类 ask 的拒答话术（模型要转述给用户，所以要能照抄）。
     *
     * 不出现任何姓名与号码；同时把本工具**认的三档**再念一遍，免得模型换一个 ask 原地重试。
     */
    const val WHO_REFUSAL =
        "「这车是谁的／司机电话」不在本工具：司机姓名与手机号只在本机「查询助手」面板显示（不进模型上下文），" +
            "请引导用户在抽屉里打开「查询助手」面板问，那里号码可点开拨号盘。" +
            "本工具只答：装没装（loaded）、几件几车（count）、什么货（goods）。"

    /** 没表时给出去路的那句 `hint`（裁定原话）。 */
    const val HINT_NO_TABLE = "先导入 T9 导出的表"

    /** inbox 相对工作区的位置（没表那句话里要说清放哪儿，光说「先导入」她没法执行）。 */
    const val INBOX_HINT = "把导出的表放进 产物/物流/inbox/ 再问"

    /**
     * 存储壳由装配层注入（房型同 `KbTools.store`：Context 只到装配层这一处）。
     *
     * `null` ＝ 还没装配（单测里可以自己塞假的）；生产路径由 `BaopuRuntime.registry()` 赋值。
     */
    var store: LogisticsStore? = null

    /** 描述里的解锁话术（裁定 3：这一句**必须写死**，不许省成「请先解锁」）。 */
    const val UNLOCK_SENTENCE = "若你看不到本工具，先调用 capabilities(enable=\"logistics\")。"

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = "查本机当天导入的发货表（只读、不联网、不调模型）：按方向/车牌/单号/货名筛，" +
            "问装没装（loaded）、几件几车（count）、什么货（goods）。" +
            "答案固定三行、含数据时间；表里没有就说没有，不推断。" +
            "没有当日表时返回 found:false（NO_TABLE），不报错。" +
            "「这车谁的／司机电话」不在本工具（隐私）：引导用户到抽屉的「查询助手」面板问。" +
            " " + UNLOCK_SENTENCE,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_DIRECTION to jsonObjectOf(
                    "type" to "string",
                    "description" to "方向或卸点的写法（要填表里真有的值，如「北京」）；不填＝全天",
                ),
                ARG_PLATE to jsonObjectOf(
                    "type" to "string",
                    "description" to "车牌（如「冀A12345」，大小写与全角都认）；不填＝不限车",
                ),
                ARG_WAYBILL to jsonObjectOf(
                    "type" to "string",
                    "description" to "单号（连续数字，精确优先）；不填＝不限单",
                ),
                ARG_GOODS to jsonObjectOf(
                    "type" to "string",
                    "description" to "货名（表里的写法，子串命中）；不填＝不限货",
                ),
                ARG_ASK to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*ASKS.toTypedArray()),
                    "description" to "loaded=装没装；count=几件几车；goods=什么货",
                ),
            ),
            "required" to jsonArrayOf(ARG_ASK),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 只读本机库，无共享可变状态
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args) }

    /**
     * 执行器（生产入口）。
     *
     * 口径固定用默认档 [LogisticsBasis.ORDER]（§1.8 冻结：一张配货单就是一辆车）：
     * `basis` 不在裁定给的五个参数里，所以它不进 schema——切换口径是**面板**上的事
     * （那里有开关），模型这一侧只有一档，同一句问话两次运行得到的车数才是同一个数。
     */
    fun run(args: JSONObject): ToolOutcome {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        return run(args, now, zone, LogisticsAsk.dayKeyOf(now, zone), LogisticsBasis.ORDER)
    }

    /**
     * 纯执行（时间、时区、日、口径都由调用方给 ⇒ 读数可在 JVM 里钉死）。
     *
     * @return `ToolOutcome.Ok(JSON 文本)`；只有**参数本身不合法**时才 [ToolOutcome.Failed]
     *   （表里没有、查不到都不是错误，那是结果）
     */
    fun run(
        args: JSONObject,
        nowMillis: Long,
        zone: TimeZone,
        day: String,
        basis: LogisticsBasis,
    ): ToolOutcome {
        val ask = args.optString(ARG_ASK).trim().lowercase()
        if (ask.isEmpty()) return ToolOutcome.Failed("缺少必填参数「$ARG_ASK」（取值：${ASKS.joinToString("／")}）")
        if (ask in WHOISH_ASKS) return ToolOutcome.Failed(WHO_REFUSAL)
        if (ask !in ASKS) {
            // 认不出的一档不猜成最像的那个：猜错会把「什么货」答成「装没装」，那是答非所问
            return ToolOutcome.Failed("「$ask」不是本工具认的 ask（只认：${ASKS.joinToString("／")}）")
        }
        val s = store ?: return ToolOutcome.Failed("查询助手还没装配（库里没有可查的表），这轮不查")
        val rows = s.rowsFor(day)
        if (rows.isEmpty()) return noTable(day, ask, basis)

        val spec = specOf(ask, args)
        val hit = LogisticsQuery.filter(rows, spec)
        if (hit.isEmpty()) return noMatch(day, ask, basis, spec, rows, nowMillis, zone)
        val agg = LogisticsAggregate.aggregate(hit, basis)
        // 出话只有一条路径（三行纪律与「口径可疑」留痕都在 LogisticsAsk 里，不在这儿另写一遍）
        val answer = LogisticsAsk.answerOf(QueryOutcome.Found(spec, agg), rows, nowMillis, zone)
        return ToolOutcome.Ok(
            jsonObjectOf(
                "found" to true,
                "day" to day,
                "ask" to ask,
                "basis" to agg.basis.name,
                "rows" to agg.rows.size,
                "orders" to agg.orders,
                "vehicles" to agg.vehicles,
                "pieces" to (agg.piecesTotal ?: JSONObject.NULL),
                "absent" to jsonArrayOf(*agg.absent.map { it.label }.toTypedArray()),
                "dataTime" to LogisticsAsk.dataTimeNote(rows, zone),
                "answer" to answer,
            ).toString(),
        )
    }

    /** 结构化参数 → 条件（`unmatchedTerm` 留空：这里的值就是照着表给的，没有「表里没这个词」这一说）。 */
    internal fun specOf(ask: String, args: JSONObject): QuerySpec {
        val direction = args.optString(ARG_DIRECTION).trim()
        val plate = args.optString(ARG_PLATE).trim()
        val waybill = args.optString(ARG_WAYBILL).trim()
        val goods = args.optString(ARG_GOODS).trim()
        return QuerySpec(
            intent = when (ask) {
                "count" -> LogisticsIntent.COUNT
                "goods" -> LogisticsIntent.GOODS
                else -> LogisticsIntent.LOADED
            },
            scopes = if (direction.isEmpty()) emptyList() else listOf(direction),
            unmatchedTerm = "",
            plate = LogisticsRows.platesOf(plate).firstOrNull(),
            waybill = if (waybill.isEmpty()) null else LogisticsRows.waybillOf(waybill),
            goods = goods.ifEmpty { null },
            ambiguous = false,
        )
    }

    /** 没表：一句 `found:false` ＋ 去路（裁定 4 的原话形状）。 */
    internal fun noTable(day: String, ask: String, basis: LogisticsBasis): ToolOutcome.Ok = ToolOutcome.Ok(
        jsonObjectOf(
            "found" to false,
            "reason" to "NO_TABLE",
            "hint" to "$HINT_NO_TABLE（$INBOX_HINT）",
            "day" to day,
            "ask" to ask,
            "basis" to basis.name,
        ).toString(),
    )

    /**
     * 有表但这个条件下一单都没有。
     *
     * 这一条**不许**回 `NO_TABLE`：两个原因是不同的事，混在一起用户会去翻 inbox，
     * 而表其实在——只是没她问的那个方向/那台车。
     */
    private fun noMatch(
        day: String,
        ask: String,
        basis: LogisticsBasis,
        spec: QuerySpec,
        rows: List<StoredRow>,
        nowMillis: Long,
        zone: TimeZone,
    ): ToolOutcome.Ok = ToolOutcome.Ok(
        jsonObjectOf(
            "found" to false,
            "reason" to "NO_MATCH",
            "hint" to "表里有 ${rows.size} 单，但没有满足这个条件的；条件里的写法要和表里一致（方向：${knownScopes(rows)}）",
            "day" to day,
            "ask" to ask,
            "basis" to basis.name,
            "answer" to LogisticsAsk.answerOf(QueryOutcome.NoMatch(spec), rows, nowMillis, zone),
        ).toString(),
    )

    /** 当天表里出现过的方向（NO_MATCH 时给模型一个能改对的东西，而不是让它再猜一次）。 */
    private fun knownScopes(rows: List<StoredRow>): String {
        val values = rows.map { LogisticsAggregate.scopeOf(it) }.filter { it.isNotEmpty() }.distinct()
        return when {
            values.isEmpty() -> "表里没有方向/卸点"
            values.size <= 3 -> values.joinToString("／")
            else -> values.take(3).joinToString("／") + " 等 ${values.size} 个"
        }
    }
}
