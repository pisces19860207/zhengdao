package com.example.zhengdao.ui.browser

/**
 * 玄览 Tab 接入占位 —— HomeTabs 新增索引的接线说明。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §1（既有 Tab 行：
 * HomeTabs 现 0/1/2，新浏览器 Tab = 新增索引）。
 *
 * ⚠️ 接入点（DSH 实施时在 MainActivity.kt 改，骨架不碰主代码，② 行号已复核）：
 * - `MainActivity.kt:660` `when (tab)`：新增分支 → 玄览内容（本包 [BrowserScreen]）。
 * - HomeTabs  Tab 行数 +1；新索引定名 **玄览**（承载"期望"，2026-10-13 用户定稿命名）。
 * - 前三 Tab 同步重命名为 抱朴/逍遥/怡然（用户定稿四 Tab：抱朴(真实)·逍遥(自由)·怡然(幸福)·玄览(期望)）。
 *
 * 状态：占位说明（无实现）。接入属阶段 1 工作。
 */
