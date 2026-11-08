// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十二批（原第五百三十八批并入，2026-11-08）· **模型小黑板**：
// 抱朴自己的那块便签——记中间态（待办、当前假设、下一步），跨轮次可读。
//
// 用户原话（2026-11-08）：「小黑板归模型管，就像你们自己的那个小黑板一样」。
// 所以归属是**模型的工作台**，不是用户的便签：`board_read` / `board_write` 两件由模型调，
// 治理中心那一块只是**只读镜像**（看模型在记什么）＋ 一个"清空"（人工）。
//
// 落点 `<工作区>/产物/.board.md`——**点号开头**，理由只有一条且是硬的：
// `ReflectionRunner.isCountedProduct` 把点号开头的文件排除在「产物 N 件」之外（512 游标同款口径），
// 便签不是产物，读数里多一件就是账不实。临时文件也点号开头（`.board.md.tmp`），同一口径兜住。
//
// 三条边界（派工单 §五·二 + 用户追加，逐字落地）：
//   1. **整篇替换**（不做 append）：append 会无限增长，也会让"上次那段"悄悄变成"这次的一段"；
//      要留旧内容，模型自己搬进产物目录——那是它的事，不是便签的事。
//   2. **硬上限 4KB / 20 行**：超限**拒写**并让模型先删旧，**不静默截断**
//      （截断最坏：模型以为写进去了，下一轮读到的是半截）。
//   3. **不进记忆、不参与判定**：本文件不碰 `MemoryStore` / `MemoryTiers` / `DecisionLedger`，
//      也不被 `RiskMatrix` / `GuardPipeline` 读——它只是盘上的一件文本，
//      `BoardToolsGateTest` 用源级断言把这条钉死（判据④⑤）。
//
// 落账只记**长度与行数**，不记正文（判据⑥）：便签里可能有"下一步查哪个文件"这类半成品线索，
// 全量进 run_log 等于把一份草稿抄进审计台账。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/**
 * 小黑板的判据（纯函数、零 IO、零 Android）——上限与计数都在这，落盘在 [BoardStore]。
 *
 * 为什么行数口径要写死成"非空行终结"：`String.lines()` 对 `"a\nb\n"` 会给 3 条
 * （末尾那个空串），于是"20 行整"的边界值会被判成 21 行——判据③要的"边界值放行"当场破。
 * 本文件的口径：**换行符数 ＋ 末行没换行则再加 1**，末尾换行不加。
 */
internal object BoardRules {

    /** 硬上限：字节（UTF-8）。 */
    const val MAX_BYTES: Int = 4096

    /** 上限的"人话"形状（4KB）——界面只显示这个数，不在渲染层做除法。 */
    const val MAX_KB: Int = 4

    /** 硬上限：行。 */
    const val MAX_LINES: Int = 20

    /** UTF-8 字节数（中文按 3 字节算，与盘上实际占位一致）。 */
    fun byteCount(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** 行数（空串＝0 行；末尾换行不额外算一行）。 */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        val breaks = text.count { it == '\n' }
        return breaks + if (text.endsWith('\n')) 0 else 1
    }

    /**
     * 能不能写（null＝通过；非 null＝**拒绝原因**，原话回灌给模型）。
     *
     * 拒因必须带上"现在多少 / 上限多少"与"先删旧"这句可执行的话——
     * 只说"超限"的失败，模型下一轮会再写一遍同样大的东西。
     */
    fun refusal(text: String): String? {
        val bytes = byteCount(text)
        val lines = lineCount(text)
        return when {
            bytes > MAX_BYTES ->
                "拒写：$bytes 字节 > 上限 $MAX_BYTES 字节（4KB）。小黑板要自己删旧——" +
                    "把还想留的几行留下、其余的搬进产物目录写成文件，然后再整篇覆盖一次。没写盘，原文还在盘上。"
            lines > MAX_LINES ->
                "拒写：$lines 行 > 上限 $MAX_LINES 行。小黑板要自己删旧——" +
                    "保留最近的和还需要的几行，其余搬进产物目录。没写盘，原文还在盘上。"
            else -> null
        }
    }
}

/**
 * 小黑板的落盘口（全仓唯一碰这个文件的地方；判据层 [BoardRules] 不认识磁盘）。
 *
 * 写盘先写 `产物/.board.md.tmp` 再改名（同 `ConsequenceStore` / `DriftStore` 房型）：
 * 半条便签比没有便签更糟——模型下一轮会把半截当全文读。
 */
internal object BoardStore {

    /** 稳定键：点号开头 ⇒ 不计入「产物 N 件」（512 口径，判据⑦）。改这个名字等于换一个文件。 */
    const val FILE_NAME: String = ".board.md"

    /** 落点：`<工作区>/产物/.board.md`。 */
    fun fileOf(wsRoot: File): File = File(MemoryLayout.productsDir(wsRoot), FILE_NAME)

    /** 读（不存在 ⇒ 空串：空便签是合法状态，不是错误）。读不出 ⇒ null（调用方如实说读不出）。 */
    fun read(wsRoot: File): String? {
        val f = fileOf(wsRoot)
        if (!f.isFile) return ""
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** 整篇替换（空串＝清空）。返回 false ＝没写成（盘上一个字节都没动）。 */
    fun write(wsRoot: File, text: String): Boolean = runCatching {
        val f = fileOf(wsRoot)
        val dir = f.parentFile ?: return@runCatching false
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        // 那个位置上要是躺着一件**不是普通文件**的东西（比如同名目录），一律不动它、如实失败：
        // 便签的所有权只覆盖它自己那一件文本，删除别人占位的东西不是它的权限。
        if (f.exists() && !f.isFile) return@runCatching false
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (f.exists() && !f.delete()) return@runCatching false
        val ok = tmp.renameTo(f)
        if (!ok) RunLog.log("小黑板改名失败（盘上仍是旧内容）")
        ok
    }.getOrElse {
        RunLog.log("小黑板写失败：${it.javaClass.simpleName}")
        false
    }

    /** 人工清空（治理中心那个按钮）：删文件而不是写空串——盘上不躺一件"空便签"。 */
    fun clear(wsRoot: File): Boolean = runCatching {
        val f = fileOf(wsRoot)
        !f.exists() || f.delete()
    }.getOrDefault(false)
}

// ── board_read ──────────────────────────────────────────────────────────────

/**
 * 读小黑板（R0 只读）。
 *
 * 返回一段 JSON 而不是裸文本：`text` 与写入逐字节相等（判据③要的是"写什么读什么"），
 * `bytes` / `lines` 给模型自己判"还剩多少余量"——不然它每轮都要写到被拒一次才知道满了。
 */
internal object BoardReadTool {

    const val NAME = "board_read"

    const val VERSION = 1

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "读你自己的小黑板（`产物/.board.md`，整篇）。" +
            "用来放**工作中间态**：待办、当前假设、下一步要查什么；跨轮次还在。" +
            "上限 4KB / 20 行，超了得自己删旧。它不进记忆、不参与任何判定，别把用户原话或隐私写进去。" +
            "（这个工具在 FILES 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0", // 只读：不动盘、不写账
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { _ -> run(workspaceRoot()) }

    fun run(workspaceRoot: File): ToolOutcome {
        val text = BoardStore.read(workspaceRoot)
            ?: return ToolOutcome.Failed("小黑板读不出（盘上那份不是 UTF-8 文本？）——如实说，不假装是空的")
        return ToolOutcome.Ok(
            JSONObject()
                .put("v", VERSION)
                .put("exists", BoardStore.fileOf(workspaceRoot).isFile)
                .put("bytes", BoardRules.byteCount(text))
                .put("lines", BoardRules.lineCount(text))
                .put("maxBytes", BoardRules.MAX_BYTES)
                .put("maxLines", BoardRules.MAX_LINES)
                .put("text", text)
                .toString(),
        )
    }
}

// ── board_write ─────────────────────────────────────────────────────────────

/**
 * 写小黑板（R1 写证道内：只写工作区 `产物/` 下的这一件便签，不动用户文件、不联网）。
 *
 * 参数只有 `text` 一个，**没有"从对话里搬一段"的口子**（判据③结构断言）：
 * 便签不是记忆，不该成为"把用户原话抄一份"的捷径。
 */
internal object BoardWriteTool {

    const val NAME = "board_write"

    const val ARG_TEXT = "text"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "写你自己的小黑板（`产物/.board.md`）——**整篇替换**，不是追加：" +
            "你要留的旧内容自己带上，没带进来的就没了。" +
            "上限 4KB / 20 行：超了会被**拒写**并让你先删旧（不会静默截断）。" +
            "放工作中间态（待办/假设/下一步），不写用户原文、不写隐私；它不进记忆、不参与判定。" +
            "空串＝清空。（这个工具在 FILES 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TEXT to jsonObjectOf(
                    "type" to "string",
                    "description" to "整篇新内容（替换旧内容）。空串＝清空。上限 4KB / 20 行。",
                ),
            ),
            "required" to jsonArrayOf(ARG_TEXT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 便签：每次一张卡会把"记一笔"变成打扰（风险档仍是 R1，矩阵照记）
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    fun run(args: JSONObject, workspaceRoot: File): ToolOutcome {
        val text = args.optString(ARG_TEXT)
        // 超限拒写（不静默截断）：判据②要的正是这一句
        BoardRules.refusal(text)?.let { return ToolOutcome.Failed(it) }
        if (!BoardStore.write(workspaceRoot, text)) {
            return ToolOutcome.Failed("小黑板写盘失败（盘上仍是旧内容）——如实说，别当写成了")
        }
        val bytes = BoardRules.byteCount(text)
        val lines = BoardRules.lineCount(text)
        // 落账：只记长度与行数，**不记正文**（判据⑥）
        RunLog.log("board_write: ${bytes}B 字节 / $lines 行")
        return ToolOutcome.Ok(
            JSONObject()
                .put("bytes", bytes)
                .put("lines", lines)
                .put("maxBytes", BoardRules.MAX_BYTES)
                .put("maxLines", BoardRules.MAX_LINES)
                .put("text", text)
                .toString(),
        )
    }
}
