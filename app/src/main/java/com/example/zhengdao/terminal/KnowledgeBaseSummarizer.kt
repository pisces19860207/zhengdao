// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：OpenCode 官方 serve 模式 HTTP API（/api/session、…/message、…/interrupt、
//   …/permission/{id}/reply，均为本项目已实测的端点，见 oc/OcRepository.kt 与
//   docs/知识库-P2设计方案.md 第三节）；RFC 4180 的 CSV 引号转义语义。
package com.example.zhengdao.terminal

import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.parseMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 资料库 **P2 · 摘要** 的纯逻辑层。
 *
 * ## 为什么单独一个文件、且全是纯函数
 *
 * P2 的**网络与设备部分**在本机无法验证（新会话有没有模型、权限请求会不会卡死，
 * 见 `docs/知识库-P2设计方案.md` 第六节）。既然测不了，就把**能测的部分全部拔出来**：
 * 提示词怎么拼、响应怎么判"答完了"、摘要怎么回写进清单 —— 这三件事都是纯字符串/JSON
 * 变换，**完全不需要设备**，用 JVM 单测就能守住。
 *
 * 于是本文件的每一个 `fun` 都不碰 `Context`、不碰网络、不碰文件。
 * 真正的调用编排在 [KnowledgeBaseSummarizerRunner]（见 `terminal/` 同包另一处）里，
 * 那部分按项目纪律标为「待真机验证」。
 *
 * ## 三个已知坑的对应设计（都来自 P2 方案第六节）
 *
 * 1. **响应带 `{"data":…}` 信封** —— 实现在 [extractSummaries]，剥壳失败就返回 null
 *    （宁可"没摘要"，不可"把错误 JSON 当摘要写进去"）。
 * 2. **"答完了"没标志位** —— 不猜标志位，改用**稳定性收敛**（见 [isSettled]）：
 *    连续两轮取样文本一模一样 ⇒ 视为说完。代价是最多多等一轮，换来零误判。
 * 3. **权限请求会卡死** —— 用 [AUTO_DENY_DECISION] 主动回 `reject`（见 [shouldAutoDeny]）。
 */
object KnowledgeBaseSummarizer {

    /** 让 agent 干活的那句话。⚠️ 结尾的"不要写任何文件"是铁律的一部分。 */
    const val PROMPT_HEADER: String =
        "下面是一个资料库的文件清单。请为**每一个**文件写 2~3 句中文摘要，" +
            "说清它讲了什么、能用来干什么。\n" +
            // ⚠️ 这句是真机实测加的（2026-10-09）：不给这句时免费模型会一头扎进"我自己去读文件"，
            // 反复申请工具权限、轮询到超时也拿不到一行正文；给了这句它们才直接写摘要。
            // 另外 serve 报的 location.directory 在真机上是乱码路径，"自己去读"必然读不到。
            "⚠️ 不要调用任何工具、不要读取文件、不要执行命令 —— 每个文件的开头已经贴在下面了，" +
            "只根据这些内容作答，看完就直接写摘要。\n" +
            "严格按这个格式逐行输出，不要有开场白、不要有结尾话、不要加序号外的任何内容：\n" +
            "文件名<TAB>摘要\n\n" +
            "清单：\n"

    /**
     * 每行的分隔符。用 **TAB** 而不是 `|` 或 `:` ——
     * 文件名里出现 `|`、`:`、`-` 的概率远高于 TAB（实测资料库文件名含中文括号与空格）。
     */
    const val FIELD_SEP: Char = '\t'

    /** 一次最多喂多少个文件给模型（控制上下文长度与耗时）。 */
    const val MAX_FILES_PER_RUN: Int = 40

    /** 摘要行数上限（防模型啰嗦）。 */
    const val MAX_SUMMARY_CHARS: Int = 200

    /** 权限请求的自动决定：拒绝。理由见 [shouldAutoDeny]。 */
    const val AUTO_DENY_DECISION: String = "reject"

    // ── ① 提示词 ────────────────────────────────────────────────────────────

    /**
     * 拼提示词：清单文件名 + 每个文件的**前若干字节**当"试读"。
     *
     * 为什么带内容而不是只给文件名：模型只看到文件名时，摘要必然是"望文生义"
     * ——那还不如不给（错摘要比没摘要更坏，会让 agent 拿错资料）。
     * 所以每个文件附一段**截断后的原文**，让摘要至少基于真实内容。
     *
     * @param files 文件名（相对 `原始/` 的路径）到"试读文本"的映射；试读文本可为 null（读不出）
     * @param maxCharsPerFile 每个文件最多喂多少字符
     */
    fun buildPrompt(
        files: List<Pair<String, String?>>,
        maxCharsPerFile: Int = 1200,
    ): String {
        val sb = StringBuilder(PROMPT_HEADER)
        for ((name, body) in files.take(MAX_FILES_PER_RUN)) {
            sb.append("--- ").append(name).append(" ---\n")
            val text = body?.trim()?.takeIf { it.isNotEmpty() }
            if (text == null) {
                sb.append("（这个文件读不出文本内容，请只根据文件名给一句「可能是讲什么的」）\n")
            } else {
                sb.append(text.take(maxCharsPerFile))
                if (text.length > maxCharsPerFile) sb.append("\n…（内容已截断）")
                sb.append('\n')
            }
        }
        sb.append("\n再次强调：只输出「文件名").append(FIELD_SEP).append("摘要」这样的行，不要写任何文件。")
        return sb.toString()
    }

    // ── ② "答完了"的判定 ────────────────────────────────────────────────────

    /**
     * 稳定性收敛：连续两次取样**文本完全相同且非空** ⇒ 视为答完。
     *
     * 为什么不看去 `time.completed` 之类的标志位：本项目反复吃过"猜服务端字段路径"的亏
     * （`OcRepository.parseMessage` 的注释里记着：真实消息用 `type` 而不是 `role`，
     *  `parts` 实际叫 `content[]`，工具名在顶层 `name` 而不是 `tool`）。
     * 字段一旦猜错，失败形态是"永远等不到 ⇒ 空转到超时"，**没有报错**。
     * 稳定性收敛只依赖"文本变了没有"，这个语义不会因上游改名而失效。
     *
     * @param prev 上一轮取到的文本（首轮传 null）
     * @param curr 这一轮取到的文本
     */
    fun isSettled(prev: String?, curr: String?): Boolean =
        !curr.isNullOrBlank() && prev == curr

    /**
     * 从一轮消息里抽出**助手说的全部正文**。
     *
     * 只取 `ASSISTANT` 的 `Text` 段 —— 排除工具调用、推理段、用户自己的话。
     * 多条助手消息按出现顺序拼接（模型可能分几条回）。
     */
    fun assistantText(parts: List<OcPart>): String =
        parts.filterIsInstance<OcPart.Text>().joinToString("\n") { it.text }.trim()

    /**
     * 从 `GET /api/session/{id}/message` 的响应体里取**最后一条助手**的话（无则 null）。
     *
     * ⚠️ 必须**先按角色筛掉用户消息**。踩过的坑：外层 `parts[]` 里用户那条消息也带
     * `{"type":"text","text":"用户自己输入的话"}`，如果只按"外层 parts 优先"取文本、
     * 忘了先看角色，就会把**用户的问题**当成模型的回答（摘要会变成用户自己的原话，
     * 而且看起来"有内容"、不报错 —— 这正是本项目最怕的静默错）。
     */
    fun assistantTextFromMessages(rawJson: String): String? {
        val arr = unwrapArray(rawJson) ?: return null
        var last: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val info = o.optJSONObject("info") ?: o
            val msg = parseMessage(info) ?: continue
            // 🔴 只要助手的。用户消息的正文同样在 content/text 里，混进来就是灾难。
            if (msg.role != com.example.zhengdao.oc.OcMessage.Role.ASSISTANT) continue
            // ⚠️ 真实线格式：`{info:{id,type}, content:[…]}` —— content 与 **info 平级**。
            //    parseMessage 收的是 info 对象，所以它那份 content 读不到；正文必须从**外层**取。
            //    `parts[]` 是 v2 的另一种写法，两种都兼容（沿用 OcRepository.parseMessages 的口径）。
            //    取值顺序：外层 content → 外层 parts → parseMessage 从 info 里解析出来的（兜底）。
            val outerArr = o.optJSONArray("content") ?: o.optJSONArray("parts")
            val outer = outerArr?.let { p ->
                (0 until p.length()).mapNotNull { j ->
                    p.optJSONObject(j)?.let { com.example.zhengdao.oc.parsePart(it, "$msg.id:$j") }
                }
            }
            val parts = if (outer.isNullOrEmpty()) msg.parts else outer
            val text = assistantText(parts).takeIf { it.isNotEmpty() }
            if (text != null) last = text
        }
        return last
    }

    /**
     * 剥掉 OpenCode 的 `{"data": …}` **响应信封**。
     *
     * ⚠️ 这是本项目最难定位的一类坑（`OcRepository.unwrapArray` 的注释）：
     * 不剥壳不会抛异常，只会静默拿到空数组 —— 表现为"什么都没发生、日志一行都没有"。
     * 此处**不模仿**那个"有 data 取 data、否则原文"的宽松策略：拿到看不懂的东西就回 null。
     *
     * @return JSON 数组；输入既非数组、也无 `data` 数组时返回 null
     */
    internal fun unwrapArray(text: String?): JSONArray? {
        val t = text?.trimStart() ?: return null
        if (t.isEmpty()) return null
        return runCatching {
            if (t.startsWith("[")) JSONArray(t)
            else JSONObject(t).optJSONArray("data")
        }.getOrNull()
    }

    // ── ③ 解析摘要 ──────────────────────────────────────────────────────────

    /**
     * 把模型的回答解析成 `文件名 → 摘要`。
     *
     * 容错原则：**能救的救，救不了就丢那一条**（丢一条只是少一个摘要，写错一条会污染整个资料库）。
     * 具体宽容的地方：
     * - 行首的 `-` `*` `1.` 等列表符号剥掉
     * - 用 TAB 分不开时，退而试**第一个** 全角/半角冒号或 `|`
     * - 摘要过长按 [MAX_SUMMARY_CHARS] 截断
     * - 空摘要、解析不出的行直接跳过
     *
     * @param text 模型的原始回答
     * @param known 清单里的合法文件名（**只有在这个集合里的才收**，防止模型编文件）
     */
    fun parseSummaries(text: String?, known: Set<String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        text ?: return out
        for (rawLine in text.lines()) {
            val line = stripListMarker(rawLine.trim())
            if (line.isEmpty()) continue
            val pair = splitLine(line) ?: continue
            val (name, summary) = pair
            val cleanName = name.trim().trim('`', '"', '\'', '「', '」', '“', '”')
            if (cleanName !in known) continue            // 模型编出来的文件名，拒收
            val cleanSummary = summary.trim().trim('"', '”', '“')
            if (cleanSummary.isEmpty()) continue
            out[cleanName] = cleanSummary.take(MAX_SUMMARY_CHARS)
        }
        return out
    }

    /** 剥掉 `- ` / `* ` / `1. ` / `1、` / `1) ` 这类列表前缀。 */
    private fun stripListMarker(line: String): String {
        var s = line
        for (m in listOf("- ", "* ", "• ")) {
            if (s.startsWith(m)) return s.removePrefix(m).trimStart()
        }
        // 有序列表：数字 + . / 、 / ) / ．
        val m = Regex("^(\\d{1,3})\\s*[.、)．]\\s*").find(s)
        if (m != null) s = s.substring(m.value.length)
        return s.trimStart()
    }

    /** 尝试按 TAB → 冒号 → 竖线切开一行。切不出来返回 null。 */
    private fun splitLine(line: String): Pair<String, String>? {
        val tab = line.indexOf(FIELD_SEP)
        if (tab > 0) {
            val a = line.substring(0, tab)
            val b = line.substring(tab + 1)
            if (b.isNotBlank()) return a to b
        }
        for (sep in listOf('：', ':', '|')) {
            val i = line.indexOf(sep)
            // 从第 2 个字符起找：避免把 "C:\..." 这种路径里的冒号当分隔
            if (i >= 2) {
                val a = line.substring(0, i)
                val b = line.substring(i + 1)
                if (b.isNotBlank()) return a to b
            }
        }
        return null
    }

    /** `## 文件摘要` 小节的标题（回写与幂等判断共用，避免两处写成不同的字）。 */
    private const val SUMMARY_HEADING = "## 文件摘要"

    /**
     * 清单里"给 AI 的提示"那一节的标题。
     *
     * ⚠️ 必须与 [KnowledgeBase.indexText] 里的字面量**逐字一致** ——
     * 不一致的话，[renderInto] 找不到锚点就会把摘要追加到文末（而不是插在提示之前），
     * 结果是文件结构悄悄变化、没有任何报错。改这里时那边也要改。
     */
    private const val HINT_HEADING = "## 给 AI 的提示（重要）"

    /**
     * 把解析出来的摘要**合并**进清单正文。
     *
     * 合并规则（幂等、可重复调用）：
     * - 清单里已有 `## 文件摘要` 小节 ⇒ **整节替换**（不是追加），所以重跑不会越堆越长
     * - 没有 ⇒ **插到 `## 给 AI 的提示（重要）` 之前**；没有那一节才接在文末
     *   （为什么插在它前面：那份提示是针对整份清单的操作说明，放在最后读起来才是收尾；
     *    摘要是资料本体，应该在它上面）
     * - **保留**清单原有的"给 AI 的提示"一节，绝不改动
     *
     * 内容为空时返回 null —— 由调用方决定"不写"（空摘要写进去会让状态误报为"已整理"）。
     */
    fun renderInto(indexBody: String, summaries: Map<String, String>): String? {
        val effective = summaries.filterValues { it.isNotBlank() }
        if (effective.isEmpty()) return null

        val sb = StringBuilder()
        sb.append(SUMMARY_HEADING).append("\n\n")
        sb.append(SUMMARY_NOTE).append("\n\n")
        for ((name, summary) in effective) {
            sb.append("- **").append(name).append("** — ").append(summary).append("\n")
        }
        val block = sb.toString().trimEnd()

        val existing = indexBody.indexOf(SUMMARY_HEADING)
        if (existing >= 0) {
            // 已有小节 ⇒ 替换到下一个 `## ` 之前（或到文末）
            val afterMarker = indexBody.indexOf("\n## ", existing + SUMMARY_HEADING.length)
            val head = indexBody.substring(0, existing)
            val tail = if (afterMarker >= 0) indexBody.substring(afterMarker + 1) else ""
            return head + block + "\n\n" + tail
        }

        // 没有小节 ⇒ 插在"给 AI 的提示"之前，保持它是最后一段
        val hintAt = indexBody.indexOf(HINT_HEADING)
        if (hintAt >= 0) {
            val head = indexBody.substring(0, hintAt)
            val tail = indexBody.substring(hintAt)
            return head.trimEnd() + "\n\n" + block + "\n\n" + tail
        }

        // 连提示节都没有（理论到不了，清单由我们生成）—— 退化为接在末尾
        return indexBody.trimEnd() + "\n\n" + block + "\n"
    }

    // ── ③′ 清单重渲染时保住已有摘要（"两次写入互相抹"的防线）────────────────

    /** 摘要小节的说明行（[renderInto] 与 [carryOverSummary] 共用，避免两处写成不同的字）。 */
    internal const val SUMMARY_NOTE: String =
        "> 由证道借太极的免费模型生成 · 仅供快速定位，**细节请读原文件**。"

    /** 摘要行的形态：`- **文件名** — 摘要`。 */
    private val SUMMARY_LINE = Regex("""^- \*\*(.+?)\*\* — """)

    /** 从一行摘要里取出文件名；不是摘要行（标题/说明/空行）返回 null。 */
    internal fun summaryLineName(line: String): String? =
        SUMMARY_LINE.find(line.trim())?.groupValues?.get(1)

    /**
     * 把旧清单里的 `## 文件摘要` 一节**搬到**刚重渲染出来的新清单里。
     *
     * 为什么必须有这一步：`KnowledgeBase.rebuildIndex` 是**整份重渲染**
     * （模板 [KnowledgeBase.indexText] 里没有摘要节），所以只要 `原始/` 里有任何增删改
     * （指纹变了）或者清单被删掉重建，下一次扫描就会把模型辛苦跑出来的摘要**整段抹掉**。
     * 这不是"原件被毁"（`原始/` 全程只读、哈希可验），而是**同一份派生清单在两次写入之间
     * 互相抹** —— 用户看到的现象是"摘要莫名其妙没了，得再点一次「重新整理」"。
     *
     * 规则（刻意保守）：
     * - 旧清单里找不到摘要节 ⇒ 新清单**原样**返回
     * - 只搬**新清单里仍然存在**的文件行：原件已删，它的摘要留着只会误导 agent
     * - 一行摘要都不剩 ⇒ 整节不搬（不留空壳小节）
     * - 位置仍然守规矩：插在 `## 给 AI 的提示（重要）` **之前**
     */
    fun carryOverSummary(old: String?, fresh: String): String {
        if (old.isNullOrBlank()) return fresh
        val start = old.indexOf(SUMMARY_HEADING)
        if (start < 0) return fresh
        val hintAt = old.indexOf(HINT_HEADING, start)
        val block = if (hintAt < 0) old.substring(start) else old.substring(start, hintAt)

        val kept = block.lines().filter { line ->
            val name = summaryLineName(line) ?: return@filter true   // 标题与说明行照留
            fresh.contains("`$name`")                                 // 原件还在才留
        }
        if (kept.none { summaryLineName(it) != null }) return fresh

        val carried = kept.joinToString("\n").trim() + "\n\n"
        val at = fresh.indexOf(HINT_HEADING)
        val insertAt = if (at < 0) fresh.length else at
        val head = if (at < 0) fresh.trimEnd() + "\n\n" else fresh.substring(0, insertAt).trimEnd() + "\n\n"
        val tail = if (at < 0) "" else fresh.substring(insertAt)
        return head + carried + tail
    }

    // ── ④ 权限闸门 ──────────────────────────────────────────────────────────

    /**
     * 摘要任务遇到权限请求时，是否**自动拒绝**。
     *
     * 为什么是拒绝而不是批准：这个任务本来就**只需要读**。
     * 一个"读文件写摘要"的任务如果来申请写权限 / 执行命令，说明模型跑偏了 ——
     * 批准它有真实数据风险（本任务的目标是保护 `原始/` 只读）。
     *
     * ⚠️ 更要紧的是**不能挂着**：不响应权限请求，agent 会永远等下去
     * （P2 方案第六节第 3 条："任务永久挂起"）。所以宁可拒绝并结束，也不能沉默。
     *
     * @param action 权限请求里的动作名（如 "edit" / "write" / "bash"）
     * @param path 目标路径（可能为 null）
     * @return true = 自动拒绝该请求
     */
    fun shouldAutoDeny(action: String?, path: String?): Boolean {
        // 一律拒绝：本任务只读，任何请求都超出授权范围。
        // 保留参数是为了将来"只允许读操作"的收窄空间，也让调用点必须把上下文传进来。
        @Suppress("UNUSED_EXPRESSION")
        action
        @Suppress("UNUSED_EXPRESSION")
        path
        return true
    }

    /** 生成回执用的请求体（三态之一，见 OcRepository.respondPermission 的实测注释）。 */
    fun denyReplyBody(): String = JSONObject().apply { put("decision", AUTO_DENY_DECISION) }.toString()

    // ── ⑤ 状态文案 ──────────────────────────────────────────────────────────

    /**
     * 给用户看的一句话（设置页状态行 / 日志）。
     * 失败也要说人话 —— 这是项目里 [com.example.zhengdao.util.HumanizeError] 的口径。
     */
    fun failureLine(reason: String?): String {
        val r = reason?.trim().orEmpty()
        return if (r.isEmpty()) "这次没整理成（原因未知），下次再试"
        else "这次没整理成：$r（清单照旧可用）"
    }
}
