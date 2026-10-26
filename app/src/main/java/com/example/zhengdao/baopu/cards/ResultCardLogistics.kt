// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **195 那张表的呈现适配**（派工单 §2：物流查询卡的字段按用户口径）。
//
// ## 为什么适配层不放在 195 里
// 派工单 §4「不许碰」名单第一个就是 `baopu/logistics/**`——那是已经合入的数据层，
// 本批只**消费**它的公开 API（`StoredRow` / `LogisticsStatus.label` / `LogisticsPhone.parse`）。
// 适配层放在呈现侧，好处是 195 一行不改：卡的字段顺序、哪些可拨、哪两行注定「未填」，
// 全在这里；哪天 195 补了客户列，改这一个文件就够。
//
// ## 三个「未填」是**事实**，不是偷懒
// 派工单 §2 要求卡上摆「客户姓名／客户电话」，而 195 的字段契约
// （`LogisticsField`：单号/配货单号/方向/卸点/货名/件数/重量/体积/司机/手机号/车牌/状态）
// **没有客户这两列**。这里照实留两行、值由 [ResultCardBinder] 落成「未填」——
// 这正好是派工单负例⑤要的行为（表里没有就写未填，不许空白、不许补 0）。
// 「起点」与「所属线」同理没有列，本适配层**不摆**这两行：摆一行永远「未填」的字段
// 只会让她以为表被读坏了。补列是 195 侧的活，已写进交付报告的挂账项。
//
// ## 号码：判据只有一个出处
// 这一层不判断「是不是号码」，只把 195 判过的 `DialNumber.dial` 贴上（[dialsOf]）。
// 多个号取第一个：卡上的号码是「点一下打给他」，不是号码簿；多号进台账（195 已经有台账）。
package com.example.zhengdao.baopu.cards

import com.example.zhengdao.baopu.logistics.Aggregate
import com.example.zhengdao.baopu.logistics.LogisticsExportedAt
import com.example.zhengdao.baopu.logistics.LogisticsPhone
import com.example.zhengdao.baopu.logistics.QueryOutcome
import com.example.zhengdao.baopu.logistics.StoredRow
import java.util.TimeZone

object ResultCardLogistics {

    /** 模板 id（= `assets/baopu/cards/logistics.waybill.json`）。 */
    const val TEMPLATE_ID = "logistics.waybill"

    /** 「其它票」那一组的标题键。 */
    const val OTHERS_TITLE_KEY = "baopu_card_logistics_others"

    /** 其它票一行的值格式（方向 · 件数 · 车牌；全是数据，不是句子）。 */
    const val PEER_LINE_KEY = "baopu_card_logistics_peer"

    /** 值表的键（与模板 JSON 的 `key` 一一对应；少一个就是卡上少一行）。 */
    const val K_WAYBILL = "waybill"
    const val K_ORDER_NO = "orderNo"
    const val K_STATUS = "status"
    const val K_DATA_TIME = "dataTime"
    const val K_CUSTOMER_NAME = "customerName"
    const val K_CUSTOMER_PHONE = "customerPhone"
    const val K_GOODS = "goods"
    const val K_PIECES = "pieces"
    const val K_WEIGHT = "weight"
    const val K_VOLUME = "volume"
    const val K_DIRECTION = "direction"
    const val K_UNLOAD = "unload"
    const val K_DRIVER = "driver"
    const val K_DRIVER_PHONE = "driverPhone"
    const val K_PLATE = "plate"

    /** 小结组（195 的聚合数：她问「北京装没装」时，答案就是这几行）。 */
    const val K_ORDERS = "orders"
    const val K_VEHICLES = "vehicles"
    const val K_PIECES_TOTAL = "piecesTotal"
    const val K_LOADED = "loaded"
    const val K_PENDING = "pending"

    /**
     * 一行库存 → 值表（**原样**：表里怎么写就怎么给，不补 0、不换算单位）。
     *
     * 客户两键**从不写入**：195 没有这两列，写一个空串与不写等价，但「不写」更能说明
     * 「这个字段本项目现在拿不到」，而不是「这一单恰好没填」。
     */
    fun valuesOf(row: StoredRow, zone: TimeZone): Map<String, String> = buildMap {
        put(K_WAYBILL, row.waybill)
        put(K_ORDER_NO, row.orderNo)
        put(K_STATUS, row.effectiveStatus().label)
        // 数据时间是**表级**的（同一份导出每行相同），但它必须出现在卡上（派工单 §2 单据组的第三行）
        val at = row.exported()
        put(
            K_DATA_TIME,
            if (at.known) LogisticsExportedAt.display(at.millis!!, zone)
            else LogisticsExportedAt.sourceNote(at, zone),
        )
        put(K_GOODS, row.goods)
        put(K_PIECES, row.pieces?.toString() ?: row.piecesRaw)
        put(K_WEIGHT, row.weight)
        put(K_VOLUME, row.volume)
        put(K_DIRECTION, row.direction)
        put(K_UNLOAD, row.unload)
        put(K_DRIVER, row.driver)
        put(K_DRIVER_PHONE, row.phone)
        put(K_PLATE, row.plates().joinToString("/"))
    }

    /**
     * 聚合数 → 值表（`Aggregate` 是 195 算好的那一份，本层只做 `toString`，不重算）。
     *
     * `piecesTotal` 为 null 时**不写这个键**：195 用 null 表达「这一维整维都没说」
     * （缺件数列／所有行都空），卡上据此落「未填」——写成 0 就是替表编了个数。
     */
    fun aggregateValues(agg: Aggregate): Map<String, String> = buildMap {
        put(K_ORDERS, agg.orders.toString())
        put(K_VEHICLES, agg.vehicles.toString())
        agg.piecesTotal?.let { put(K_PIECES_TOTAL, it.toString()) }
        put(K_LOADED, agg.loaded.size.toString())
        put(K_PENDING, agg.pending.size.toString())
    }

    /** 可拨号码表（形状判据在 195；这里只取第一个）。 */
    fun dialsOf(row: StoredRow): Map<String, String> {
        val first = LogisticsPhone.forRow(row).firstOrNull() ?: return emptyMap()
        return mapOf(K_DRIVER_PHONE to first.dial)
    }

    /**
     * 一次查询 → 一张卡。
     *
     * @return null ＝ 这一问没有行（没表／没命中／反问），**不弹空卡**；
     *         这类情形的话术仍归 195 的三行答案（不改它）
     */
    fun cardOf(
        outcome: QueryOutcome,
        registry: ResultCardRegistry,
        lookup: (String) -> String,
        zone: TimeZone,
    ): ResultCardSpec? {
        val agg = (outcome as? QueryOutcome.Found)?.agg ?: return null
        val rows = agg.rows
        if (rows.isEmpty()) return null
        val head = rows.first()
        val base = registry.card(
            templateId = TEMPLATE_ID,
            // 小结（聚合）＋ 第一票的逐字段：两张表合并，键不重叠
            values = valuesOf(head, zone) + aggregateValues(agg),
            lookup = lookup,
            dials = dialsOf(head),
            itemCount = rows.size,
        ) ?: return null
        val withId = base.copy(identity = head.waybill)
        val others = rows.drop(1)
        if (others.isEmpty()) return withId
        // 多票：「一张卡里分条」——第一票照模板铺全字段，其余各一行（方向 · 件数 · 车牌），
        // 免得一屏塞进 N 份完整字段（那正是 179 长文卡的地盘，不是答案卡）。
        val peerTitle = lookup(OTHERS_TITLE_KEY)
        val unfilled = lookup(ResultCardTemplate.DEFAULT_EMPTY_KEY)
        val peerRows = others.map { r ->
            CardRow(
                key = r.waybill,
                label = r.waybill,
                value = lookup(PEER_LINE_KEY).format(
                    r.direction.ifEmpty { r.unload },
                    r.pieces?.toString() ?: unfilled,
                    r.plates().joinToString("/").ifEmpty { unfilled },
                ),
                copyable = true,
            )
        }
        return withId.copy(groups = withId.groups + CardGroup(key = "others", title = peerTitle, rows = peerRows))
    }
}
