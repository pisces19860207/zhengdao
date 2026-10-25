// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十批-后果预览 §2.5（卡面 ≤1 行 ≤30 字）＋ §3（注入限流）。
//
// 本文件放两件「说」的事：`ImpactLine`（事实 → 卡面那一行）与 `ImpactRisk`（动作 → 四档）。
// 「算」在 `ImpactPreview`／`ImpactGovernance`，「读」在 `ImpactFacts`——三者之间没有回头路。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall
import java.time.Instant
import java.time.ZoneId

/**
 * 事实 → 一句话（**卡面上那一行**）。
 *
 * 三条硬约束（都有单测钉着）：
 * 1. **最多一行**：返回值里绝不含 `\n`（含了就会变两行，那违反 §2.5）；
 * 2. **≤ [MAX_CHARS] 字**：模板按最坏情况设计，最后还有 [fit] 兜底截断；
 * 3. **只陈述事实**：不写「危险」「建议」「小心」这类评价词——那是风险档与用户自己的事。
 */
internal object ImpactLine {

    /** 卡面一行的字数上限（派工单 §2.5：≤30 字）。 */
    const val MAX_CHARS = 30

    /** 后果未知时的固定说法（超时/读不到；**不猜**，也不假装没有影响）。 */
    const val UNKNOWN_TEXT = "后果未知"

    /**
     * @return 要显示的那一行；null = 这类动作没有可说的（界面一个字都不显示）
     */
    fun text(impact: Impact): String? {
        if (impact.kind == Impact.Kind.NONE) return null
        if (impact.unknown) return UNKNOWN_TEXT
        val s = when (impact.kind) {
            Impact.Kind.WRITE -> write(impact)
            Impact.Kind.DELETE -> delete(impact)
            Impact.Kind.MOVE -> move(impact)
            Impact.Kind.CLICK -> click(impact)
            Impact.Kind.SCHEDULE -> schedule(impact)
            Impact.Kind.SKILL_REPLAY -> replay(impact)
            Impact.Kind.SKILL_GOVERN -> govern(impact)
            Impact.Kind.DIARY_MAINTAIN -> diary(impact)
            Impact.Kind.NONE -> return null
        }
        return s.takeIf { it.isNotBlank() }?.let { fit(it) }
    }

    private fun write(i: Impact): String = when {
        i.originalsTouched -> "原件区只读，会被拦下"
        i.refused -> "目标已存在且未允许覆盖，会失败"
        i.newFiles > 0 && i.overwriteTotal == 0 -> "新建 ${i.newFiles} 个文件"
        i.overwriteTotal > 0 -> {
            val one = i.overwrite.firstOrNull()
            when {
                i.ledgerRows > 0 -> "覆盖 ${i.overwriteTotal} 个、涉 ${i.ledgerRows} 条已发台账"
                i.overwriteTotal == 1 && one != null -> "覆盖「$one」"
                else -> "覆盖 ${i.overwriteTotal} 个文件"
            }
        }
        else -> ""
    }

    private fun delete(i: Impact): String = when {
        i.refused && i.deleteCount == 0 -> "目录或不存在，会被拒"
        i.originalsTouched -> "原件区只读，会被拦下"
        i.deleteCount == 0 -> ""
        else -> buildString {
            append("删 ").append(i.deleteCount).append(" 个文件")
            if (i.deleteBytes > 0) append("（").append(size(i.deleteBytes)).append("）")
            if (i.ledgerRows > 0) append("、涉 ").append(i.ledgerRows).append(" 条已发台账")
        }
    }

    private fun move(i: Impact): String = when {
        i.refused -> "源文件不在，会被拒"
        i.originalsTouched -> "原件区只读，会被拦下"
        i.collisions > 0 -> "移动 ${i.moved} 个，${i.collisions} 个撞名"
        i.moved > 0 -> "移动 ${i.moved} 个文件"
        else -> ""
    }

    private fun click(i: Impact): String = when {
        i.crossDomain -> "点「${i.nodeText}」：会跳到别的网站"
        i.formKnown && i.formRequiredEmpty > 0 -> "点「${i.nodeText}」：必填还空 ${i.formRequiredEmpty} 项"
        i.formKnown -> "点「${i.nodeText}」：已填 ${i.formFilled} 项"
        else -> "点「${i.nodeText}」"
    }

    private fun schedule(i: Impact): String =
        if (i.nextFireAtMs <= 0) "定时：算不出下次触发" else "下次触发：${stamp(i.nextFireAtMs)}"

    private fun replay(i: Impact): String = when {
        i.stepsHi <= 0 -> ""
        i.touches > 0 -> "预计 ${i.stepsLo}–${i.stepsHi} 步、碰 ${i.touches} 个文件"
        else -> "预计 ${i.stepsLo}–${i.stepsHi} 步"
    }

    private fun govern(i: Impact): String = when {
        i.retireCount == 0 && i.promoteCount == 0 -> "不动任何技能（保留 ${i.keepCount} 条）"
        i.promoteCount > 0 -> "退役 ${i.retireCount} 条、转正 ${i.promoteCount} 条"
        else -> "退役 ${i.retireCount} 条、保留 ${i.keepCount} 条"
    }

    private fun diary(i: Impact): String = when {
        i.archiveCount == 0 && i.cleanCount == 0 -> ""
        i.cleanCount > 0 -> "归档 ${i.archiveCount} 条、清理最多 ${i.cleanCount} 条"
        else -> "归档 ${i.archiveCount} 条"
    }

    /**
     * 兜底截断：**保证**单行且 ≤ [MAX_CHARS]。
     *
     * 为什么要兜底而不是只靠模板：模板里的名字/数字是外部来的（文件名可能很长），
     * 「模板看着短」不是保证；判据要有兜底才叫判据。
     */
    internal fun fit(raw: String): String {
        val one = raw.replace('\n', ' ').replace('\r', ' ').trim()
        if (one.length <= MAX_CHARS) return one
        return one.take(MAX_CHARS - 1) + "…"
    }

    /**
     * 字节 → 人话（1 位小数；卡面一行里塞得下）。
     *
     * 为什么不用 `String.format("%.1f", …)`：那要带 Locale，否则 lint 的
     * `DefaultLocale` 会记一笔（本仓 lint 预算是零，一分都不许涨）。整数算 tenths 也能
     * 得到同样的 `12.3MB`，还顺带避免了小数点的本地化（德/法语里它是逗号）。
     */
    private fun size(bytes: Long): String = when {
        bytes >= GB -> tenths(bytes, GB) + "GB"
        bytes >= MB -> tenths(bytes, MB) + "MB"
        bytes >= KB -> tenths(bytes, KB) + "KB"
        else -> "${bytes}B"
    }

    private fun tenths(value: Long, unit: Long): String {
        val t = (value * 10 + unit / 2) / unit // 四舍五入到 0.1
        return "${t / 10}.${t % 10}"
    }

    /** 时刻 → `10-27 08:30`（今年内不写年份；跨年才写）。同样不用 `String.format`。 */
    private fun stamp(atMs: Long): String {
        val z = runCatching { ZoneId.systemDefault() }.getOrNull() ?: return "稍后"
        val t = Instant.ofEpochMilli(atMs).atZone(z)
        val now = Instant.now().atZone(z)
        fun two(v: Int): String = if (v < 10) "0$v" else "$v"
        val md = "${two(t.monthValue)}-${two(t.dayOfMonth)} ${two(t.hour)}:${two(t.minute)}"
        return if (t.year == now.year) md else "${t.year}-$md"
    }

    private const val KB = 1024L
    private const val MB = 1024L * 1024
    private const val GB = 1024L * 1024 * 1024
}

/**
 * 动作 → 风险档（**只有四档，只为本批的"注不注入"服务**）。
 *
 * ⚠️ 这是**临时口径**：第二百一十九批（风险矩阵 `RiskMatrix.kt`）合入后，注入档位
 * 应当以它为准（派工单 §3 原话「219 的判定可引用 Impact 事实」）。本对象因此只提供
 * [tierOf]，不提供任何"该不该拦"的结论——拦截仍由 `ConfirmRisk`／管线负责。
 *
 * 四档的边界（与 219 的命名对齐，便于将来替换）：
 * - R0 只读（读文件/列目录/搜索/看页面/查能力）：**永不注入**；
 * - R1 轻交互（打开网页、滚动、等待、计划、提问）：不入注入队列；
 * - R2 写盘（写/改/移动/转码/图片处理/画图）：入队；
 * - R3 破坏性（删、清空回收站、批量 ≥3 个）：入队并带数字。
 */
internal object ImpactRisk {

    /** 只读工具名（与 `ToolScheduling` 的只读分类同口径：这些动作不改盘）。 */
    private val READ_ONLY = setOf(
        "read_file", "list_dir", "find_files", "search_in_files", "file_info",
        "kb_search", "kb_index", "library_list", "browser_snapshot", "capabilities",
        "card_inspect", "preview_local_file", "ask_user", "update_plan", "read",
    )

    /** 破坏性（R3）。 */
    private val DESTRUCTIVE = setOf("delete_path", "schedule_delete", "uninstall_agent")

    fun tierOf(call: ToolCall): Int = when {
        call.name in READ_ONLY -> 0
        call.name in DESTRUCTIVE -> 3
        call.name.startsWith("browser_") -> 1
        else -> 2
    }
}
