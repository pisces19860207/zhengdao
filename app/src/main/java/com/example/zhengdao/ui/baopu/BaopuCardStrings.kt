// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **答案卡文案表**（模板 `labelKey` → `R.string.*` 的唯一映射处）。
//
// ## 为什么需要这张表，而不是 `resources.getIdentifier(key, …)`
// 模板 JSON 里写的是 strings 的**键**（理由见 `ResultCardTemplate.kt`）。界面要把它变成字，
// 有两条路：
//   ① `getIdentifier(name, "string", pkg)` —— 运行期按名字查，`DiscouragedApi` 是一条 lint 警告，
//      而本仓的 lint 预算基线是 **0/0**（`tools/lint-baseline.json`），多加一条警告就把闸打红；
//      它还会让 R8 的资源压缩看不见引用（release 包里可能被裁掉）。
//   ② 一张静态 `when` 表 —— 类型安全、R8 看得见、语言切换照常，代价是新增文案要多写一行。
// 选②。这也是本仓既有做法（设置页的工具名 `when ("logistics_query" -> R.string.…)` 同款）。
//
// ## 这张表为什么不放 `BaopuResultCard.kt`
// 派工单 §2.5 的门禁原文是「模板 JSON 变更不得要求改 `BaopuResultSheet.kt`」。
// 所以「组件」与「措辞」分成两个文件：加一个场景＝加一份 JSON ＋ 加中英两条 <string> ＋
// 在这张表里加一行；**组件的排版与判据一行都不用动**。
// `ResultCardTemplateGateTest` 两边都钉：模板里出现的每个键都必须在这张表里，
// 这张表里的每个键都必须在中英两份 XML 里——任何一边漏了当场红，而不是等用户看见一串键名。
package com.example.zhengdao.ui.baopu

import android.content.res.Resources
import com.example.zhengdao.R

/**
 * 取一条卡文案。
 *
 * @param key 模板 JSON 里的 `labelKey` / `titleKey` / `emptyTextKey`，或本批自己的动作名
 * @return 找不到就**回键名本身**（不会出现在用户眼前：门禁测试会先红；回空串则会让界面画一个空标签）
 */
internal fun baopuCardString(key: String, res: Resources): String = when (key) {
    // 卡壳通用
    "baopu_card_unfilled" -> res.getString(R.string.baopu_card_unfilled)
    "baopu_card_body_line" -> res.getString(R.string.baopu_card_body_line)
    "baopu_card_strip_line" -> res.getString(R.string.baopu_card_strip_line)
    "baopu_card_collapse" -> res.getString(R.string.baopu_card_collapse)
    "baopu_card_expand" -> res.getString(R.string.baopu_card_expand)
    "baopu_card_dismiss_all" -> res.getString(R.string.baopu_card_dismiss_all)
    "baopu_card_hidden_count" -> res.getString(R.string.baopu_card_hidden_count)
    "baopu_card_others_rows" -> res.getString(R.string.baopu_card_others_rows)
    "baopu_card_count" -> res.getString(R.string.baopu_card_count)
    "baopu_card_copied" -> res.getString(R.string.baopu_card_copied)
    "baopu_card_dial_desc" -> res.getString(R.string.baopu_card_dial_desc)
    "baopu_card_copy_desc" -> res.getString(R.string.baopu_card_copy_desc)
    "baopu_card_sheet_desc" -> res.getString(R.string.baopu_card_sheet_desc)
    "baopu_card_copy_pair" -> res.getString(R.string.baopu_card_copy_pair)

    // 只读动作
    "baopu_card_action_copy_all" -> res.getString(R.string.baopu_card_action_copy_all)
    "baopu_card_action_detail" -> res.getString(R.string.baopu_card_action_detail)
    "baopu_card_action_follow_up" -> res.getString(R.string.baopu_card_action_follow_up)

    // 校验样式
    "baopu_card_error_required" -> res.getString(R.string.baopu_card_error_required)
    "baopu_card_error_number" -> res.getString(R.string.baopu_card_error_number)
    "baopu_card_error_too_many" -> res.getString(R.string.baopu_card_error_too_many)
    "baopu_card_error_generic" -> res.getString(R.string.baopu_card_error_generic)
    "baopu_card_error_summary" -> res.getString(R.string.baopu_card_error_summary)

    // 物流
    "baopu_card_logistics_title" -> res.getString(R.string.baopu_card_logistics_title)
    "baopu_card_logistics_g_summary" -> res.getString(R.string.baopu_card_logistics_g_summary)
    "baopu_card_logistics_orders" -> res.getString(R.string.baopu_card_logistics_orders)
    "baopu_card_logistics_vehicles" -> res.getString(R.string.baopu_card_logistics_vehicles)
    "baopu_card_logistics_pieces_total" -> res.getString(R.string.baopu_card_logistics_pieces_total)
    "baopu_card_logistics_loaded" -> res.getString(R.string.baopu_card_logistics_loaded)
    "baopu_card_logistics_pending" -> res.getString(R.string.baopu_card_logistics_pending)
    "baopu_card_logistics_g_doc" -> res.getString(R.string.baopu_card_logistics_g_doc)
    "baopu_card_logistics_g_customer" -> res.getString(R.string.baopu_card_logistics_g_customer)
    "baopu_card_logistics_g_goods" -> res.getString(R.string.baopu_card_logistics_g_goods)
    "baopu_card_logistics_g_route" -> res.getString(R.string.baopu_card_logistics_g_route)
    "baopu_card_logistics_g_vehicle" -> res.getString(R.string.baopu_card_logistics_g_vehicle)
    "baopu_card_logistics_waybill" -> res.getString(R.string.baopu_card_logistics_waybill)
    "baopu_card_logistics_order_no" -> res.getString(R.string.baopu_card_logistics_order_no)
    "baopu_card_logistics_status" -> res.getString(R.string.baopu_card_logistics_status)
    "baopu_card_logistics_data_time" -> res.getString(R.string.baopu_card_logistics_data_time)
    "baopu_card_logistics_customer_name" -> res.getString(R.string.baopu_card_logistics_customer_name)
    "baopu_card_logistics_customer_phone" -> res.getString(R.string.baopu_card_logistics_customer_phone)
    "baopu_card_logistics_goods" -> res.getString(R.string.baopu_card_logistics_goods)
    "baopu_card_logistics_pieces" -> res.getString(R.string.baopu_card_logistics_pieces)
    "baopu_card_logistics_weight" -> res.getString(R.string.baopu_card_logistics_weight)
    "baopu_card_logistics_volume" -> res.getString(R.string.baopu_card_logistics_volume)
    "baopu_card_logistics_direction" -> res.getString(R.string.baopu_card_logistics_direction)
    "baopu_card_logistics_unload" -> res.getString(R.string.baopu_card_logistics_unload)
    "baopu_card_logistics_driver" -> res.getString(R.string.baopu_card_logistics_driver)
    "baopu_card_logistics_driver_phone" -> res.getString(R.string.baopu_card_logistics_driver_phone)
    "baopu_card_logistics_plate" -> res.getString(R.string.baopu_card_logistics_plate)
    "baopu_card_logistics_others" -> res.getString(R.string.baopu_card_logistics_others)
    "baopu_card_logistics_peer" -> res.getString(R.string.baopu_card_logistics_peer)

    // 学生知识点（演示样例）
    "baopu_card_study_title" -> res.getString(R.string.baopu_card_study_title)
    "baopu_card_study_g_ask" -> res.getString(R.string.baopu_card_study_g_ask)
    "baopu_card_study_g_points" -> res.getString(R.string.baopu_card_study_g_points)
    "baopu_card_study_g_steps" -> res.getString(R.string.baopu_card_study_g_steps)
    "baopu_card_study_g_source" -> res.getString(R.string.baopu_card_study_g_source)
    "baopu_card_study_question" -> res.getString(R.string.baopu_card_study_question)
    "baopu_card_study_points" -> res.getString(R.string.baopu_card_study_points)
    "baopu_card_study_pitfall" -> res.getString(R.string.baopu_card_study_pitfall)
    "baopu_card_study_steps" -> res.getString(R.string.baopu_card_study_steps)
    "baopu_card_study_source" -> res.getString(R.string.baopu_card_study_source)
    "baopu_card_study_action_answer" -> res.getString(R.string.baopu_card_study_action_answer)

    // 选题方案（样例）
    "baopu_card_write_title" -> res.getString(R.string.baopu_card_write_title)
    "baopu_card_write_g_plan" -> res.getString(R.string.baopu_card_write_g_plan)
    "baopu_card_write_g_risk" -> res.getString(R.string.baopu_card_write_g_risk)
    "baopu_card_write_topic" -> res.getString(R.string.baopu_card_write_topic)
    "baopu_card_write_style" -> res.getString(R.string.baopu_card_write_style)
    "baopu_card_write_words" -> res.getString(R.string.baopu_card_write_words)
    "baopu_card_write_duration" -> res.getString(R.string.baopu_card_write_duration)
    "baopu_card_write_risk" -> res.getString(R.string.baopu_card_write_risk)

    else -> key
}
