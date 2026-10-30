// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30）：**工作区不再是权限边界**。
//
// 沿革（读代码的人容易以为是漏改，写清楚）：
// - 156/157：工作区外要「一次会话问一次」，开关关就一律拒；
// - 229：读放大到全盘（搜索链路），写不放大（只认工作区＋`Download/证道`）；
// - **287（本批）**：用户裁定「和电脑的 agent 一样」——读写都全盘，工作区只做
//   「默认落点 / 索引先看哪里」。于是本文件里那套"四象限＋三档"判据整体退役，
//   只剩一条：**系统与凭据目录一律拒**（判据本体在 [FilePolicy]）。
//
// 为什么不在每个工具里各写一遍：十个工具各抄一遍就是十条语义——统一出口只留这一个，
// 将来要加"某类目录也不许碰"，改 [FilePolicy] 一处即可。
package com.example.zhengdao.baopu

import java.io.File

/**
 * 工具层「文件边界自检」的**统一出口**（287 起：只拦系统与凭据目录）。
 *
 * 分工（别混）：
 * - 管线第 ② 段（`GuardPipeline.guardPath`）是**第一道**：解析相对路径、拒系统/凭据目录、记账；
 * - 本函数是**第二道**（纯函数、JVM 直测）：绕过管线直调工具时也拦得住；
 * - 各工具自己的 `PathGuard` 检查（备份区/回收站/原件区）照旧跑在后面——那些是**功能**禁区，
 *   不是权限边界，与本文件无关。
 *
 * @param target 解析后的真实目标（绝对路径）
 * @param shown 给模型看的路径（相对工作区，或绝对路径）
 * @return null = 放行；非 null = 给模型的拒绝原文（调用方包成 `ToolOutcome.Rejected`）
 */
internal fun fileAccessCheck(target: File, shown: String): String? =
    FilePolicy.denyReason(target)?.let { "路径被拒：$shown——$it" }

/**
 * 覆盖前要不要备份（287：>5MB 不备份，但**必须在确认卡与回执里说明**）。
 *
 * 为什么不在这里直接备份：备份的落点（`备份/`）与命名（`*.bak`）是各工具的既有约定，
 * 判据只回答"该不该备"，避免两处各写一份大小口径。
 */
internal fun backupSkippedFor(existingBytes: Long): Boolean = existingBytes > FilePolicy.BACKUP_MAX_BYTES
