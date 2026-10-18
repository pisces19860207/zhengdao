// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批 §1.1/§1.2（update_plan 的参数校验、全量覆盖、勾选只增不减、
// plan part 的 payload 形状）。计划数据的一切判据都在这个纯函数里——引擎只做
// 接线（落库 + 内存态），UI 只做渲染，两边都不许各自实现一份「截断/覆盖」语义。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作计划（计划卡）的**纯判据层**（第十七批 · 抱朴「工作计划卡」A/B 段）。
 *
 * 三条硬语义（派工单 §3 判据 J1–J3 的落地处）：
 * 1. **最多 8 条、每条 ≤60 字**（[MAX_ITEMS] / [MAX_TEXT_CHARS]）：超了**截断并记
 *    notice**（不拒绝——模型多发几条不该让整轮白跑）；`items` 为空才拒绝。
 * 2. **每次调用全量覆盖**（[applyOverwrite]）：先 5 条再 3 条 ⇒ 计划表就是 3 条，
 *    不是「合并成 8 条」。模型每完成一条就重发整张表，简单可靠、不用增量补丁。
 * 3. **勾选只增不减**（[applyOverwrite] 的 sticky done）：同一条目（按 text 原文
 *    匹配）上一版是 done，这一版回成 todo/doing ⇒ **保持 done**。选「保持」而不是
 *    「报错」的理由：done→doing 多半是模型重描进度时的笔误，为一条笔误终止本轮
 *    不值当；保持 done 并把它写进回灌（notice），模型下一版就对了。
 *
 * 零 Android 依赖（org.json 单测里有真实现，见 build.gradle 的 testImplementation）。
 */
internal object PlanRules {

    /** 计划条数上限（派工单 §1.1）。 */
    const val MAX_ITEMS = 8

    /** 单条 text 字数上限（派工单 §1.1）。 */
    const val MAX_TEXT_CHARS = 60

    const val STATUS_TODO = "todo"
    const val STATUS_DOING = "doing"
    const val STATUS_DONE = "done"

    /** 合法状态全集（schema 的 enum 与这里同源，两边不许漂移）。 */
    val STATUSES = listOf(STATUS_TODO, STATUS_DOING, STATUS_DONE)

    /** 一条计划。 */
    data class PlanItem(val text: String, val status: String)

    /** [parseItems] 的结局：Ok = 可入库；Error = 错误原文回灌（模型重试）。 */
    sealed interface Parse {
        data class Ok(val items: List<PlanItem>, val notices: List<String>) : Parse
        data class Error(val message: String) : Parse
    }

    /** [applyOverwrite] 的结局（sticky 命中也进 notices，让模型知道为什么没听它的）。 */
    data class OverwriteResult(val items: List<PlanItem>, val stickyNotes: List<String>)

    /**
     * 解析 + 校验 + 归一化（J1）。
     *
     * 为什么执行器里还要再校验一遍：SchemaCheck 只查**顶层**参数（items 在不在、
     * 是不是数组），数组里每个条目的 text/status 它不管——协议 A/B 两条路都这样。
     * 模型幻觉出的坏条目（第 3 条 status 写成 "finish"、text 是数字）只有这里拦得住。
     *
     * @return Ok = 归一化后的条目（≤8 条、每条 ≤60 字）＋ 截断/丢弃 notice；
     *   Error = 错误原文（回灌给模型，下一步它自己改）
     */
    /**
     * 状态别名 ⇒ 本仓三个规范值；认不出回 null。
     *
     * 为什么认别名：2026-10-19 真机，模型把「正在做」写成 `in_progress`，整张计划被拒 ⇒
     * 用户看到的是"第一步就被挡住"。各家 agent 惯例不同（in_progress / pending / completed…），
     * 让它多跑一轮去改一个词，成本远高于这里认一下。
     */
    fun normalizeStatus(raw: String): String? {
        val s = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        return when (s) {
            STATUS_TODO, "pending", "not_started", "waiting", "todo_", "待做", "未开始" -> STATUS_TODO
            STATUS_DOING, "in_progress", "inprogress", "active", "running", "doing_", "进行中", "正在做" -> STATUS_DOING
            STATUS_DONE, "completed", "complete", "finished", "done_", "完成", "已完成" -> STATUS_DONE
            else -> null
        }
    }
    fun parseItems(args: JSONObject): Parse {
        val raw = args.optJSONArray("items")
            ?: return Parse.Error(
                "参数「items」缺失或不是数组：要 [{text, status}, …]，" +
                    "status 只能是 todo / doing / done。",
            )
        if (raw.length() == 0) {
            return Parse.Error("items 是空的：先列 3–8 条计划（每条 ≤60 字）再调本工具。")
        }
        val notices = mutableListOf<String>()
        val kept = mutableListOf<PlanItem>()
        for (i in 0 until raw.length()) {
            val obj = raw.optJSONObject(i)
                ?: return Parse.Error("第 ${i + 1} 条不是对象：每条都该是 {\"text\": \"…\", \"status\": \"…\"}。")
            val textRaw = obj.opt("text")
            if (textRaw !is String) {
                return Parse.Error("第 ${i + 1} 条缺「text」（要非空字符串）。")
            }
            val text = textRaw.trim()
            if (text.isEmpty()) {
                notices += "第 ${i + 1} 条 text 是空的，已丢弃。"
                continue
            }
            // 别名归一（2026-10-19 真机：模型写 `in_progress` ⇒ 整步报废，用户看到的是"第一步就被挡住"）
            // 模型不是故意的，是各家习惯不同；认几个常见写法比让它重试一次便宜得多。
            val status = normalizeStatus(obj.optString("status"))
            if (status == null) {
                return Parse.Error(
                    "第 ${i + 1} 条 status「${obj.optString("status")}」不在允许范围（todo / doing / done）。",
                )
            }
            val cut = if (text.length > MAX_TEXT_CHARS) {
                notices += "第 ${i + 1} 条超过 $MAX_TEXT_CHARS 字，已截断。"
                text.take(MAX_TEXT_CHARS)
            } else {
                text
            }
            kept += PlanItem(cut, status)
        }
        if (kept.isEmpty()) {
            return Parse.Error("所有条目的 text 都是空的：先写清楚每一条要做什么。")
        }
        if (kept.size > MAX_ITEMS) {
            notices += "共 ${kept.size} 条，超过 $MAX_ITEMS 条上限，已截断到前 $MAX_ITEMS 条。"
        }
        return Parse.Ok(kept.take(MAX_ITEMS), notices)
    }

    /**
     * 全量覆盖 ＋ sticky done（J2/J3）。
     *
     * @param previous 上一版计划（引擎内存态；首轮/新一轮是空表）
     * @param incoming 本版计划（已过 [parseItems] 归一化）
     * @return items = 本版（内容全量覆盖，done 的条目状态被钉住）＋ stickyNotes
     */
    fun applyOverwrite(previous: List<PlanItem>, incoming: List<PlanItem>): OverwriteResult {
        val doneTexts = previous.asSequence()
            .filter { it.status == STATUS_DONE }
            .map { it.text }
            .toSet()
        val stickyNotes = mutableListOf<String>()
        val out = incoming.map { item ->
            if (item.text in doneTexts && item.status != STATUS_DONE) {
                stickyNotes += "「${item.text}」上一版已完成，保持 done。"
                item.copy(status = STATUS_DONE)
            } else {
                item
            }
        }
        return OverwriteResult(out, stickyNotes)
    }

    /**
     * 计划 → part payload 的 JSON（B 段落库形状；`part(kind='plan')` 存的就是它）。
     *
     * 键序固定（org.json 保插入序）⇒ 同一份计划永远序列化成同一串字节，
     * 「写入后读回逐字一致」（J4）才可判。
     */
    fun toJson(items: List<PlanItem>): String = JSONObject()
        .put("v", 1)
        .put(
            "items",
            JSONArray().also { arr ->
                items.forEach { item ->
                    arr.put(JSONObject().put("text", item.text).put("status", item.status))
                }
            },
        )
        .toString()

    /**
     * payload → 计划（[toJson] 的逆；容错：坏 JSON / 条目不全 ⇒ 跳过坏条目）。
     *
     * 不抛异常的理由：这是回读路径（UI 渲染、续跑回种），库里的历史数据不该有
     * 「一坏就崩」的权力；宁缺勿崩。
     */
    fun fromJson(payload: String): List<PlanItem> {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<PlanItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val text = obj.optString("text")
            val status = obj.optString("status")
            if (text.isNotEmpty() && status in STATUSES) out += PlanItem(text, status)
        }
        return out
    }

    /** 已完成条数。 */
    fun doneCount(items: List<PlanItem>): Int = items.count { it.status == STATUS_DONE }

    /**
     * 计划卡收起行的一句话（C 段 UI 直接用；现在先给回灌文案用）。
     * 例：`计划 3/5 完成`。
     */
    fun summaryLine(items: List<PlanItem>): String =
        "计划 ${doneCount(items)}/${items.size} 完成"

    /**
     * update_plan 的回灌文案（Ok 的 text；会经 DataFence 包起来喂给模型）。
     * notices（截断/丢弃/sticky）原样带上——模型看得见自己的输入被怎么修了。
     */
    fun renderAck(items: List<PlanItem>, notices: List<String>): String = buildString {
        append(summaryLine(items))
        append("。")
        if (notices.isNotEmpty()) {
            append("注意：")
            append(notices.joinToString("；"))
            append("。")
        }
        append("每完成一条就再调一次本工具（整张表重发：做完的标 done、正在做的标 doing）。")
    }
}
