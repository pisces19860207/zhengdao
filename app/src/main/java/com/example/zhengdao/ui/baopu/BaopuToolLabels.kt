// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据：派工单第四百一十三批（工具开关 ↔ 目录不漂移）。
//
// 为什么有这份文件：设置页原先用两张手写 `when`（`baopuToolLabel` / `baopuToolDesc`，
// 各 75 个分支）做「工具名 → 文案」映射，与 `BaopuToolCatalog.ENTRIES` 之间**没有任何
// 机械约束**——加一个工具忘了补一行，只能靠人记得同时去改测试；而且 141 批把逐工具开关
// 整段换成组摘要卡之后，这两个函数在界面上**已经没有调用点**，只剩测试在钉它们存在。
//
// 413 的口径（用户裁定，逐字）：「目录 ↔ 标签表不许漂移」。落点：
// - 目录侧唯一来源 = `ToolGroupTable.switchableNames()`（catalog 表序 ＋ 运行时注册两条）；
// - 标签侧 = 本文件的 [LABEL] / [DESC] 两张数据表；
// - 一致性由 `BaopuToolLabelCoverageTest` 逐名字机械核对：**漏一个就红并点名**，不再依赖
//   「某人记得给测试补一条字面量断言」。
//
// 为什么键仍然手写：Android 的 `R.string.*` 是编译期常量，无法由工具名拼出来 ⇒「键值」
// 必然手写；本批能做到的是**覆盖性从目录派生**（多一个、少一个都会红）。这条边界写进
// 交付报告，不冒充「全自动」。
//
// ⚠️ 本文件只回答「这个工具叫什么、一句话是什么」，不参与可见性判定：开关仍是组级
// （141 批裁定「成员名单不进 UI」），这里一个字都不改。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R

internal object BaopuToolLabels {

    /** 工具名 → 开关标签。键集必须与目录逐名相等（`BaopuToolLabelCoverageTest` 钉）。 */
    val LABEL: Map<String, Int> = mapOf(
        // 第二百一十八批：工具补齐 v1（六件）
        "sheet_read" to R.string.baopu_tool_sheet_read,
        // 第二百三十二批：通用表格问答
        "table_query" to R.string.baopu_tool_table_query,
        "search_web" to R.string.baopu_tool_search_web,
        "schedule_task" to R.string.baopu_tool_schedule_task,
        "event_trigger" to R.string.baopu_tool_event_trigger,
        "clipboard_write" to R.string.baopu_tool_clipboard_write,
        "notify" to R.string.baopu_tool_notify,
        "phone_dial" to R.string.baopu_tool_phone_dial,
        "card_inspect" to R.string.baopu_tool_card_inspect,
        "card_export" to R.string.baopu_tool_card_export,
        "card_build" to R.string.baopu_tool_card_build,
        // 第二百八十八批：复制/批量/整理五件
        "copy_path" to R.string.baopu_tool_copy_path,
        "bulk_move" to R.string.baopu_tool_bulk_move,
        "bulk_copy" to R.string.baopu_tool_bulk_copy,
        "organize" to R.string.baopu_tool_organize,
        "organize_preview" to R.string.baopu_tool_organize_preview,
        "capabilities" to R.string.baopu_tool_capabilities,
        "preview_local_file" to R.string.baopu_tool_preview_local_file,
        "read_file" to R.string.baopu_tool_read_file,
        "list_dir" to R.string.baopu_tool_list_dir,
        "search_in_files" to R.string.baopu_tool_search_in_files,
        "find_files" to R.string.baopu_tool_find_files,
        // 492 B②：索引浏览/检索（FILES 组）
        "index_browse" to R.string.baopu_tool_index_browse,
        "index_search" to R.string.baopu_tool_index_search,
        // 527：治理出口（只读 R0，FILES 组）
        "governance_state" to R.string.baopu_tool_governance_state,
        // 322：模型小黑板两件（FILES 组；漏一行 ⇒ 设置页开工具卡直接抛，fail-fast）
        "board_read" to R.string.baopu_tool_board_read,
        "board_write" to R.string.baopu_tool_board_write,
        // 541：待办中心三件（FILES 组，同 542 免解锁口径；漏一行 ⇒ 设置页开工具卡直接抛）
        "todo_add" to R.string.baopu_tool_todo_add,
        "todo_list" to R.string.baopu_tool_todo_list,
        "todo_mark_done" to R.string.baopu_tool_todo_mark_done,
        "book_outline" to R.string.baopu_tool_book_outline,
        "file_info" to R.string.baopu_tool_file_info,
        "write_file" to R.string.baopu_tool_write_file,
        "edit_file" to R.string.baopu_tool_edit_file,
        "move_path" to R.string.baopu_tool_move_path,
        "convert_encoding" to R.string.baopu_tool_convert_encoding,
        "browser_open" to R.string.baopu_tool_browser_open,
        "browser_snapshot" to R.string.baopu_tool_browser_snapshot,
        // 262 批：巡按取证快照（漏这行 ⇒ 设置页开工具卡直接抛，同 update_plan 教训）
        "browser_evidence" to R.string.baopu_tool_browser_evidence,
        "browser_click" to R.string.baopu_tool_browser_click,
        "browser_type" to R.string.baopu_tool_browser_type,
        "browser_scroll" to R.string.baopu_tool_browser_scroll,
        "browser_screenshot" to R.string.baopu_tool_browser_screenshot,
        // 第一百七十七批：坐标四件＋等待（同批新增工具必须同步两张映射表，
        // 否则真机进本页会 IllegalArgumentException —— 见下面 update_plan 那条教训）
        "browser_tap" to R.string.baopu_tool_browser_tap,
        "browser_swipe" to R.string.baopu_tool_browser_swipe,
        "browser_long_press" to R.string.baopu_tool_browser_long_press,
        "browser_key" to R.string.baopu_tool_browser_key,
        "browser_wait" to R.string.baopu_tool_browser_wait,
        // 228 批 B 片九件：导航三件/动作三件/标签三件（同批新增工具必须同步两张映射表，
        // 否则真机进本页会 IllegalArgumentException —— 见下面 update_plan 那条教训）
        "browser_back" to R.string.baopu_tool_browser_back,
        "browser_forward" to R.string.baopu_tool_browser_forward,
        "browser_reload" to R.string.baopu_tool_browser_reload,
        "browser_select" to R.string.baopu_tool_browser_select,
        "browser_hover" to R.string.baopu_tool_browser_hover,
        "browser_scroll_into_view" to R.string.baopu_tool_browser_scroll_into_view,
        "browser_tabs_list" to R.string.baopu_tool_browser_tabs_list,
        "browser_tabs_switch" to R.string.baopu_tool_browser_tabs_switch,
        "browser_tabs_close" to R.string.baopu_tool_browser_tabs_close,
        // 第十七批 A+B 段加的 update_plan：当时没同步这张表 ⇒ 2026-10-18 真机开本页直接崩
        // （IllegalArgumentException: 未知抱朴工具「update_plan」）。回归网见 BaopuSettingsGuardTest 的
        // 「catalog 每个工具都有标签/副标题」那条。
        "update_plan" to R.string.baopu_tool_update_plan,
        "write_skill" to R.string.baopu_tool_write_skill,
        "append_persona" to R.string.baopu_tool_append_persona,
        "library_list" to R.string.baopu_tool_library_list,
        "library_organize" to R.string.baopu_tool_library_organize,
        "kb_index" to R.string.baopu_tool_kb_index,
        "kb_search" to R.string.baopu_tool_kb_search,
        "delete_path" to R.string.baopu_tool_delete_path,
        "image_ops" to R.string.baopu_tool_image_ops,
        "generate_image" to R.string.baopu_tool_generate_image,
        // 第一百八十六批：画板/图表/表格。漏这三行 ⇒ 设置页开工具卡直接抛（同 update_plan 教训）
        "render_artifact" to R.string.baopu_tool_render_artifact,
        "draw_chart" to R.string.baopu_tool_draw_chart,
        "draw_table" to R.string.baopu_tool_draw_table,
        // 小说工程三件（第一百五十五批）：漏这两组分支 ⇒ 设置页开工具卡直接抛（同 update_plan 教训）
        "novel_create" to R.string.baopu_tool_novel_create,
        "novel_project" to R.string.baopu_tool_novel_project,
        "novel_continue" to R.string.baopu_tool_novel_continue,
        "ask_user" to R.string.baopu_tool_ask_user,
        // 第一百二十七批：多 Agent 协作（run_subtasks）。不补这两行 ⇒ 设置页开工具卡直接抛。
        "run_subtasks" to R.string.baopu_tool_run_subtasks,
        // 第一百九十五批：查询。漏这一行 ⇒ 设置页开工具卡直接抛（baopuToolLabel 的 fail-fast）
        "logistics_query" to R.string.baopu_tool_logistics_query,
        // 第二百二十四批：表单填写（只填不提交）。漏这一行 ⇒ 设置页开工具卡直接抛（fail-fast）
        "form_fill" to R.string.baopu_tool_form_fill,
        // 228 批 C 片：示教/回放五件。漏这一行 ⇒ 设置页开工具卡直接抛（fail-fast）
        "teach_record_start" to R.string.baopu_tool_teach_record_start,
        "teach_record_stop" to R.string.baopu_tool_teach_record_stop,
        "teach_skill_save" to R.string.baopu_tool_teach_skill_save,
        "browser_replay_skill" to R.string.baopu_tool_browser_replay_skill,
        "teach_site_trust" to R.string.baopu_tool_teach_site_trust,
        // 第二百六十九批：打包/解包。漏这一行 ⇒ 设置页开工具卡直接抛（fail-fast）
        // 第四百九十一批：备份/还原（漏这一行 ⇒ 设置页开工具卡直接抛，fail-fast）
        "export_data" to R.string.baopu_tool_export_data,
        "import_data" to R.string.baopu_tool_import_data,
        "archive_pack" to R.string.baopu_tool_archive_pack,
        "archive_extract" to R.string.baopu_tool_archive_extract,
        // 502：MCP 自配五件（漏这一行 ⇒ 设置页开工具卡直接抛，fail-fast）
        "mcp_list" to R.string.baopu_tool_mcp_list,
        "mcp_add_server" to R.string.baopu_tool_mcp_add_server,
        "mcp_connect" to R.string.baopu_tool_mcp_connect,
        "mcp_disconnect" to R.string.baopu_tool_mcp_disconnect,
        "mcp_set_token" to R.string.baopu_tool_mcp_set_token,
    )

    /** 工具名 → 开关副标题。键集必须与 [LABEL] 完全一致（同一张目录，缺一即红）。 */
    val DESC: Map<String, Int> = mapOf(
        // 第二百一十八批：工具补齐 v1（六件）
        "sheet_read" to R.string.baopu_tool_sheet_read_desc,
        // 第二百三十二批：通用表格问答
        "table_query" to R.string.baopu_tool_table_query_desc,
        "search_web" to R.string.baopu_tool_search_web_desc,
        "schedule_task" to R.string.baopu_tool_schedule_task_desc,
        "event_trigger" to R.string.baopu_tool_event_trigger_desc,
        "clipboard_write" to R.string.baopu_tool_clipboard_write_desc,
        "notify" to R.string.baopu_tool_notify_desc,
        "phone_dial" to R.string.baopu_tool_phone_dial_desc,
        "card_inspect" to R.string.baopu_tool_card_inspect_desc,
        "card_export" to R.string.baopu_tool_card_export_desc,
        "card_build" to R.string.baopu_tool_card_build_desc,
        // 第二百八十八批：复制/批量/整理五件
        "copy_path" to R.string.baopu_tool_copy_path_desc,
        "bulk_move" to R.string.baopu_tool_bulk_move_desc,
        "bulk_copy" to R.string.baopu_tool_bulk_copy_desc,
        "organize" to R.string.baopu_tool_organize_desc,
        "organize_preview" to R.string.baopu_tool_organize_preview_desc,
        "capabilities" to R.string.baopu_tool_capabilities_desc,
        "preview_local_file" to R.string.baopu_tool_preview_local_file_desc,
        "read_file" to R.string.baopu_tool_read_file_desc,
        "list_dir" to R.string.baopu_tool_list_dir_desc,
        "search_in_files" to R.string.baopu_tool_search_in_files_desc,
        "find_files" to R.string.baopu_tool_find_files_desc,
        // 492 B②：索引浏览/检索（FILES 组）
        "index_browse" to R.string.baopu_tool_index_browse_desc,
        "index_search" to R.string.baopu_tool_index_search_desc,
        "governance_state" to R.string.baopu_tool_governance_state_desc,
        // 322：模型小黑板两件（读一件、写一件，同一份上限口径）
        "board_read" to R.string.baopu_tool_board_read_desc,
        "board_write" to R.string.baopu_tool_board_write_desc,
        // 541：待办中心三件（读一件、立/标各一件；"标注≠完成"的口径写进第二句）
        "todo_add" to R.string.baopu_tool_todo_add_desc,
        "todo_list" to R.string.baopu_tool_todo_list_desc,
        "todo_mark_done" to R.string.baopu_tool_todo_mark_done_desc,
        "book_outline" to R.string.baopu_tool_book_outline_desc,
        "file_info" to R.string.baopu_tool_file_info_desc,
        "write_file" to R.string.baopu_tool_write_file_desc,
        "edit_file" to R.string.baopu_tool_edit_file_desc,
        "move_path" to R.string.baopu_tool_move_path_desc,
        "convert_encoding" to R.string.baopu_tool_convert_encoding_desc,
        "browser_open" to R.string.baopu_tool_browser_open_desc,
        "browser_snapshot" to R.string.baopu_tool_browser_snapshot_desc,
        "browser_evidence" to R.string.baopu_tool_browser_evidence_desc,
        "browser_click" to R.string.baopu_tool_browser_click_desc,
        "browser_type" to R.string.baopu_tool_browser_type_desc,
        "browser_scroll" to R.string.baopu_tool_browser_scroll_desc,
        "browser_screenshot" to R.string.baopu_tool_browser_screenshot_desc,
        "browser_tap" to R.string.baopu_tool_browser_tap_desc,
        "browser_swipe" to R.string.baopu_tool_browser_swipe_desc,
        "browser_long_press" to R.string.baopu_tool_browser_long_press_desc,
        "browser_key" to R.string.baopu_tool_browser_key_desc,
        "browser_wait" to R.string.baopu_tool_browser_wait_desc,
        "browser_back" to R.string.baopu_tool_browser_back_desc,
        "browser_forward" to R.string.baopu_tool_browser_forward_desc,
        "browser_reload" to R.string.baopu_tool_browser_reload_desc,
        "browser_select" to R.string.baopu_tool_browser_select_desc,
        "browser_hover" to R.string.baopu_tool_browser_hover_desc,
        "browser_scroll_into_view" to R.string.baopu_tool_browser_scroll_into_view_desc,
        "browser_tabs_list" to R.string.baopu_tool_browser_tabs_list_desc,
        "browser_tabs_switch" to R.string.baopu_tool_browser_tabs_switch_desc,
        "browser_tabs_close" to R.string.baopu_tool_browser_tabs_close_desc,
        "update_plan" to R.string.baopu_tool_update_plan_desc,
        "write_skill" to R.string.baopu_tool_write_skill_desc,
        "append_persona" to R.string.baopu_tool_append_persona_desc,
        "library_list" to R.string.baopu_tool_library_list_desc,
        "library_organize" to R.string.baopu_tool_library_organize_desc,
        "kb_index" to R.string.baopu_tool_kb_index_desc,
        "kb_search" to R.string.baopu_tool_kb_search_desc,
        "delete_path" to R.string.baopu_tool_delete_path_desc,
        "image_ops" to R.string.baopu_tool_image_ops_desc,
        "generate_image" to R.string.baopu_tool_generate_image_desc,
        // 第一百八十六批：画板/图表/表格
        "render_artifact" to R.string.baopu_tool_render_artifact_desc,
        "draw_chart" to R.string.baopu_tool_draw_chart_desc,
        "draw_table" to R.string.baopu_tool_draw_table_desc,
        // 小说工程三件（第一百五十五批）
        "novel_create" to R.string.baopu_tool_novel_create_desc,
        "novel_project" to R.string.baopu_tool_novel_project_desc,
        "novel_continue" to R.string.baopu_tool_novel_continue_desc,
        "ask_user" to R.string.baopu_tool_ask_user_desc,
        "run_subtasks" to R.string.baopu_tool_run_subtasks_desc,
        // 第一百九十五批：查询。漏这一行 ⇒ 设置页开工具卡直接抛（baopuToolDesc 的 fail-fast）
        "logistics_query" to R.string.baopu_tool_logistics_query_desc,
        // 第二百二十四批：表单填写（只填不提交）。漏这一行 ⇒ 设置页工具卡副标题直接抛
        "form_fill" to R.string.baopu_tool_form_fill_desc,
        // 228 批 C 片：示教/回放五件。漏这一行 ⇒ 设置页工具卡副标题直接抛（fail-fast）
        "teach_record_start" to R.string.baopu_tool_teach_record_start_desc,
        "teach_record_stop" to R.string.baopu_tool_teach_record_stop_desc,
        "teach_skill_save" to R.string.baopu_tool_teach_skill_save_desc,
        "browser_replay_skill" to R.string.baopu_tool_browser_replay_skill_desc,
        "teach_site_trust" to R.string.baopu_tool_teach_site_trust_desc,
        // 第二百六十九批：打包/解包（同上，漏了 fail-fast）
        "export_data" to R.string.baopu_tool_export_data_desc,
        "import_data" to R.string.baopu_tool_import_data_desc,
        "archive_pack" to R.string.baopu_tool_archive_pack_desc,
        "archive_extract" to R.string.baopu_tool_archive_extract_desc,
        // 502：MCP 自配五件（同上，漏了 fail-fast）
        "mcp_list" to R.string.baopu_tool_mcp_list_desc,
        "mcp_add_server" to R.string.baopu_tool_mcp_add_server_desc,
        "mcp_connect" to R.string.baopu_tool_mcp_connect_desc,
        "mcp_disconnect" to R.string.baopu_tool_mcp_disconnect_desc,
        "mcp_set_token" to R.string.baopu_tool_mcp_set_token_desc,
    )

    /** 取标签；没有就是配置错误（调用方只有设置页，漏了立即炸而不是显示空串）。 */
    fun label(name: String): Int = LABEL[name]
        ?: throw IllegalArgumentException(
            "未知抱朴工具「$name」：开关标签映射漏了，请补 BaopuToolLabels.LABEL",
        )

    /** 取副标题；同上。 */
    fun desc(name: String): Int = DESC[name]
        ?: throw IllegalArgumentException(
            "未知抱朴工具「$name」：开关副标题映射漏了，请补 BaopuToolLabels.DESC",
        )

    /** 目录里有、[LABEL] 里没有的名字（空 = 不漂移）。门禁读它，失败信息点名缺哪些。 */
    fun missingLabels(names: Collection<String>): List<String> = names.filter { it !in LABEL }

    /** 同上，副标题侧。 */
    fun missingDescs(names: Collection<String>): List<String> = names.filter { it !in DESC }

    /** [LABEL] 里躺着、目录里已经没有的名字（工具删了忘删文案）。 */
    fun staleLabels(names: Collection<String>): List<String> = LABEL.keys.filter { it !in names }.sorted()

    /** 同上，副标题侧。 */
    fun staleDescs(names: Collection<String>): List<String> = DESC.keys.filter { it !in names }.sorted()
}
