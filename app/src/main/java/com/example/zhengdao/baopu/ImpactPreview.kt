// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十批-后果预览（用户 2026-10-26 改名「影响提示」）§1/§2/§2.5。
//
// 一句话：**只读、限时、算事实**——回答「会怎样」，不回答「好不好」。
//
// 三条铁律（每条都有负例钉着，见 `ImpactGateTest`）：
// 1. **只读事实**：本文件不写盘、不弹卡、不联网（网页 DOM 读取由调用方按 §2 提供，
//    这里只接一个已经拿到的快照）；`ImpactPreview` 里没有 `write/delete/mkdir/rename`
//    任何字面，也没有 `Thread`（裸线程对账单 33 条一条不加）。
// 2. **限时 300ms**：所有事实读取都过 [Cursor]，超预算立刻返回 `unknown=true`
//    （界面显示「后果未知」）——**绝不把动作卡在预览后面**（预览本身也不在动作链上）。
// 3. **不重复实现判据**：原件区红线复用 `PathGuard.isInside`（只读调用），
//    工具→高风险分类复用 `ConfirmRisk` 的口径思路，不另立一套路径/权限真相。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.baopu.agent.ToolCall
import java.io.File
import org.json.JSONObject

/**
 * 影响提示的**算**（纯函数 + 只读事实）。
 *
 * 分工：本对象只回答「会怎样」（几个、多大、撞不撞名、跨不跨域）；**好坏/要不要拦**
 * 一个字都不说（那是 `ConfirmRisk`／第二百一十九批风险矩阵的事）。
 */
internal object ImpactPreview {

    /** 时间预算（派工单 §1：限时 300ms，p95）。超了 ⇒ `unknown=true`，绝不拖住动作。 */
    const val BUDGET_MS = 300L

    /** 名单（覆盖/退役）最多列几个（派工单 §2「前 3 名」）。 */
    const val MAX_NAMES = 3

    /**
     * 单个工具调用的影响。
     *
     * @param budgetMs 时间预算（缺省 [BUDGET_MS]；测试可压到 5ms 验降级）
     */
    fun preview(call: ToolCall, facts: LocalFacts, budgetMs: Long = BUDGET_MS): Impact {
        val t0 = facts.nowMs()
        val cur = Cursor(facts, t0, budgetMs)
        val raw = when (call.name) {
            "write_file" -> write(call.arguments, facts, cur, isEdit = false)
            "edit_file" -> write(call.arguments, facts, cur, isEdit = true)
            "delete_path" -> delete(call.arguments, facts, cur)
            "move_path" -> move(call.arguments, facts, cur)
            "browser_click" -> click(call.arguments, facts, cur)
            "schedule_task" -> schedule(call.arguments, cur)
            else -> Impact() // 其余动作不预览（读到这就该是 NONE，界面一个字不显示）
        }
        return raw.copy(elapsedMs = facts.nowMs() - t0)
    }

    /**
     * 一批调用的影响（合批：总条数、总体积、名单并集去重）。
     *
     * 为什么需要它：`delete_path` 只删单文件（工具硬拒目录），真机上的"删 30 个"就是
     * 30 次调用——只按单次算会永远报「删 1 个」，那是**假读数**。
     */
    fun previewAll(calls: List<ToolCall>, facts: LocalFacts, budgetMs: Long = BUDGET_MS): Impact {
        if (calls.isEmpty()) return Impact()
        val t0 = facts.nowMs()
        val each = calls.map { preview(it, facts, budgetMs = (budgetMs - (facts.nowMs() - t0)).coerceAtLeast(1L)) }
        val first = each.firstOrNull { it.kind != Impact.Kind.NONE } ?: return Impact()
        if (each.size == 1) return first
        return first.copy(
            kind = if (each.any { it.kind == Impact.Kind.DELETE }) Impact.Kind.DELETE else first.kind,
            newFiles = each.sumOf { it.newFiles },
            overwrite = each.flatMap { it.overwrite }.distinct().take(MAX_NAMES),
            overwriteTotal = each.sumOf { it.overwriteTotal },
            deleteCount = each.sumOf { it.deleteCount },
            deleteBytes = each.sumOf { it.deleteBytes },
            ledgerRows = each.sumOf { it.ledgerRows },
            moved = each.sumOf { it.moved },
            collisions = each.sumOf { it.collisions },
            originalsTouched = each.any { it.originalsTouched },
            refused = each.any { it.refused },
            unknown = each.any { it.unknown },
            elapsedMs = facts.nowMs() - t0,
        )
    }

    // ── 写 / 覆盖 ────────────────────────────────────────────────────────
    //
    // 事实来源只有两个：目标在不在、原件区判据（`PathGuard.isInside`）。**不读文件内容**
    // ——"改了几行"要读全文，那是 300ms 预算里最贵的一项，而它对"要不要点批准"没有增量。
    private fun write(args: JSONObject, facts: LocalFacts, cur: Cursor, isEdit: Boolean): Impact {
        val raw = args.optString("path").trim()
        if (raw.isEmpty()) return Impact(Impact.Kind.WRITE)
        val abs = resolve(facts, raw)
            // 拿不到工作区根 ⇒ 目标在不在都不知道 ⇒ 照实说"未知"（负例：绝不编「新建 1 个」）
            ?: return Impact(Impact.Kind.WRITE, unknown = true)
        val original = isOriginal(abs, facts)
        if (cur.out()) return Impact(Impact.Kind.WRITE, originalsTouched = original, unknown = true)
        val size = sizeOfOrNull(facts, abs)
        val exists = size != null
        // 覆盖类动作要顺带说清「这条已经在台账上了」——一次 COUNT，比再 stat 一次还便宜
        val ledger = if (exists) ledgerRows(facts, abs, cur) else 0
        // edit_file 只能改已存在的文件；目标不在 ⇒ 工具自己会拒（照实报，不猜原因）
        if (isEdit) {
            return if (exists) {
                Impact(
                    Impact.Kind.WRITE,
                    overwrite = listOf(nameOf(raw)),
                    overwriteTotal = 1,
                    originalsTouched = original,
                    ledgerRows = ledger,
                )
            } else {
                Impact(Impact.Kind.WRITE, refused = true, originalsTouched = original)
            }
        }
        val overwriteWanted = args.optBoolean("overwrite", false)
        return if (!exists) {
            Impact(Impact.Kind.WRITE, newFiles = 1, originalsTouched = original)
        } else if (overwriteWanted) {
            Impact(
                Impact.Kind.WRITE,
                overwrite = listOf(nameOf(raw)),
                overwriteTotal = 1,
                originalsTouched = original,
                ledgerRows = ledger,
            )
        } else {
            // 目标已存在且没给 overwrite ⇒ write_file 会拒（不是覆盖）——这是事实，不是判断
            Impact(Impact.Kind.WRITE, refused = true, originalsTouched = original)
        }
    }

    // ── 删 ──────────────────────────────────────────────────────────────
    private fun delete(args: JSONObject, facts: LocalFacts, cur: Cursor): Impact {
        // 清空回收站：数十个文件是常态，所以先数再算体积（体积也要数着加）。
        // 口径与工具逐字一致：`empty_trash` **只清文件、子目录留着**（DeletePathTool
        // 的 KDoc 原话）⇒ 这里也只数回收站**直接子项里的文件**，不递归——递归会把
        // 留着不动的子目录里的东西也报成"会被删"，那是假读数。
        if (args.optBoolean("empty_trash", false)) {
            val trash = trashDir(facts) ?: return Impact(Impact.Kind.DELETE, unknown = cur.out())
            if (cur.out()) return Impact(Impact.Kind.DELETE, unknown = true)
            val names = facts.listNames(trash).orEmpty()
            var count = 0
            var bytes = 0L
            val paths = mutableListOf<String>()
            for (n in names) {
                if (cur.out()) return Impact(Impact.Kind.DELETE, deleteCount = count, deleteBytes = bytes, unknown = true)
                val child = "$trash/$n"
                if (facts.isDir(child)) continue
                val s = facts.sizeOf(child) ?: continue
                count++
                bytes += s
                paths += child
            }
            return Impact(
                Impact.Kind.DELETE,
                deleteCount = count,
                deleteBytes = bytes,
                ledgerRows = facts.ledgerRowsFor(paths),
            )
        }
        val raw = args.optString("path").trim()
        if (raw.isEmpty()) return Impact(Impact.Kind.DELETE)
        val abs = resolve(facts, raw) ?: return Impact(Impact.Kind.DELETE, unknown = true)
        val original = isOriginal(abs, facts)
        if (cur.out()) return Impact(Impact.Kind.DELETE, originalsTouched = original, unknown = true)
        val isDir = facts.isDir(abs)
        val size = sizeOfOrNull(facts, abs)
        // 工具只删单个文件（目录硬拒）——预览先说清"会被拒"，而不是报「删 1 个」
        if (isDir || size == null) {
            return Impact(Impact.Kind.DELETE, refused = true, originalsTouched = original)
        }
        return Impact(
            Impact.Kind.DELETE,
            deleteCount = 1,
            deleteBytes = size,
            ledgerRows = facts.ledgerRowsFor(listOf(abs)),
            originalsTouched = original,
        )
    }

    // ── 移动 ────────────────────────────────────────────────────────────
    private fun move(args: JSONObject, facts: LocalFacts, cur: Cursor): Impact {
        val from = args.optString("from").trim()
        val to = args.optString("to").trim()
        if (from.isEmpty() || to.isEmpty()) return Impact(Impact.Kind.MOVE)
        val fromAbs = resolve(facts, from) ?: return Impact(Impact.Kind.MOVE, unknown = true)
        val toAbs = resolve(facts, to) ?: return Impact(Impact.Kind.MOVE, unknown = true)
        val original = isOriginal(fromAbs, facts) || isOriginal(toAbs, facts)
        if (cur.out()) return Impact(Impact.Kind.MOVE, originalsTouched = original, unknown = true)
        val sourceExists = sizeOfOrNull(facts, fromAbs) != null
        val collision = sizeOfOrNull(facts, toAbs) != null
        return Impact(
            Impact.Kind.MOVE,
            moved = if (sourceExists) 1 else 0,
            collisions = if (collision) 1 else 0,
            originalsTouched = original,
            refused = !sourceExists,
        )
    }

    // ── 网页提交类点击 ──────────────────────────────────────────────────
    //
    // 三件事里只有"表单读数"要靠 DOM；拿不到就**只报目标**，绝不编「已填 0 项」——
    // 「0 项已填」和「不知道填了几项」在批准前是完全不同的两句话。
    private fun click(args: JSONObject, facts: LocalFacts, cur: Cursor): Impact {
        val selector = args.optString("target").trim()
        if (selector.isEmpty()) return Impact(Impact.Kind.CLICK)
        if (cur.out()) return Impact(Impact.Kind.CLICK, unknown = true)
        val form = runCatching { facts.formState(selector) }.getOrNull()
        val name = form?.buttonText?.takeIf { it.isNotBlank() } ?: shortSelector(selector)
        return Impact(
            Impact.Kind.CLICK,
            nodeText = name,
            formFilled = form?.filled ?: 0,
            formRequiredEmpty = form?.requiredEmpty ?: 0,
            crossDomain = form?.crossDomain ?: false,
            formKnown = form != null,
        )
    }

    // ── 定时任务 ────────────────────────────────────────────────────────
    //
    // 只算"下次什么时候"（纯算术，不查库不碰闹钟）。当前 main 里还没有
    // `schedule_task` 工具（第二百一十八批在飞），所以参数形态按 `ScheduleTask`
    // 的字段名读；读不出 ⇒ NONE（不显示），绝不瞎猜。
    private fun schedule(args: JSONObject, cur: Cursor): Impact {
        if (cur.out()) return Impact(Impact.Kind.SCHEDULE, unknown = true)
        val freq = args.optString("freq").trim().ifEmpty { args.optString("frequency").trim() }
        val minute = if (args.has("minute_of_day")) args.optInt("minute_of_day", -1) else minuteOf(args.optString("time"))
        if (freq.isEmpty()) return Impact()
        val zone = args.optString("timezone").trim().ifEmpty { args.optString("zone").trim() }
        val now = args.optLong("now_ms", 0L).takeIf { it > 0 }
        val next = com.example.zhengdao.schedule.ScheduleRules.nextRunAfter(
            freq = com.example.zhengdao.schedule.ScheduleFreq.fromWire(freq) ?: return Impact(),
            minuteOfDay = minute.coerceIn(0, 1439),
            dayOfWeek = args.optInt("day_of_week", 0),
            onceDate = args.optString("once_date").trim(),
            zone = zone.ifEmpty { "Asia/Shanghai" },
            fromMs = now ?: System.currentTimeMillis(),
        )
        val perDay = when (freq) {
            "daily" -> 1
            else -> 0
        }
        return Impact(Impact.Kind.SCHEDULE, nextFireAtMs = next, firePerDay = perDay)
    }

    /** `"08:30"` → 510（分钟）；认不出 ⇒ -1（调用方 coerce 到 0 ⇒ 不报具体时刻）。 */
    private fun minuteOf(hhmm: String): Int {
        val parts = hhmm.trim().split(':')
        if (parts.size != 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    // ── 技能回放／技能自治／日记自治 ──
    // 三者的算都搬去 `ImpactGovernance`（同包顶层对象）：它们复用 196/205/147 的既有纯判据，
    // 与本文件的「文件系统事实」不是同一类活，分文件后各自可测、也不占本文件的 400 行额度。

    // ── 内部小件 ────────────────────────────────────────────────────────

    /**
     * 时间闸：**每一步之前**看一眼预算，超了就停手（返回"未知"）。
     *
     * 为什么不是"跑到 300ms 掐断"：Kotlin 里没有可抢占的中断（`File.length()` 阻塞在
     * SELinux/软链上时没法取消）。所以纪律是「一次事实读取都不许超预算地开始」——
     * 单次 stat 是微秒级，真正的风险是超大目录的**很多次** stat，那正是本闸拦的。
     *
     * 另有一层结构保证：预览**不在动作链上**（卡先出、行后到），所以哪怕真超了，
     * 也只是"这行不显示"，动作一行代码都没被它挡住。
     */
    internal class Cursor(val facts: LocalFacts, val t0: Long, val budgetMs: Long) {
        fun out(): Boolean = facts.nowMs() - t0 > budgetMs
    }

    /** 回收站目录（工作区根下 `回收站/`；名字与 [DeletePathTool.TRASH_DIR] 同一份真相）。 */
    private fun trashDir(facts: LocalFacts): String? {
        val root = facts.root ?: return null
        return File(root, com.example.zhengdao.baopu.agent.DeletePathTool.TRASH_DIR).absolutePath
    }

    /**
     * 输入路径 → 绝对路径（与管线第②段同一口径：相对 ⇒ 挂在工作区根下）。
     * 拿不到根 ⇒ null（调用方全部降级成"未知"）。
     */
    private fun resolve(facts: LocalFacts, raw: String): String? {
        if (File(raw).isAbsolute) return raw
        val root = facts.root ?: return null
        return File(root, raw).absolutePath
    }

    /**
     * 原件区判据（**只读复用 `PathGuard`**，不重写一遍路径归一）。
     *
     * 为什么要它：管线第②段命中原件区会**硬拒**（`GuardPipeline.rawRejection`）。
     * 预览若把这个说成"普通覆盖"，用户会以为点批准就能改——那是假话
     * （派工单 §4 负例 4 的靶心）。
     */
    private fun isOriginal(abs: String, facts: LocalFacts): Boolean {
        val raw = facts.rawDir ?: return false
        return PathGuard.isInside(File(raw), File(abs))
    }

    /** 大小读数（事实源自己会吞掉异常；这里只统一"目录不算大小"这一条口径）。 */
    private fun sizeOfOrNull(facts: LocalFacts, abs: String): Long? =
        runCatching { facts.sizeOf(abs) }.getOrNull()

    /**
     * 台账命中行数（带预算闸）。
     *
     * 为什么单独包一层：这是**唯一**会碰库的读数（一次 COUNT），比 stat 贵得多——
     * 慢盘/慢库的降级判据要在这里也生效，不能只有文件那一半有闸。
     */
    private fun ledgerRows(facts: LocalFacts, abs: String, cur: Cursor): Int {
        if (cur.out()) return 0
        return runCatching { facts.ledgerRowsFor(listOf(abs)) }.getOrDefault(0)
    }

    /** 名字（去掉目录与 URL 尾巴）：卡面一行里塞不下整条路径。 */
    private fun nameOf(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').ifEmpty { raw }

    /**
     * CSS 选择器的短名：`button#submit` → `button`；`div.a > button[type=submit]` → `button`；
     * `#submit`（没有标签名）→ `#submit`。
     *
     * 为什么要在 `#`/`.`/`[`/`:` 处切：卡面一行只有 30 字，而 `button#submit.btn.btn-primary`
     * 这种串印上去只会挤掉真正有用的数字（"必填还空 2 项"）。整串选择器不进日志也不进卡面。
     */
    private fun shortSelector(selector: String): String {
        val last = selector.trim().substringAfterLast('>').substringAfterLast(' ').trim()
        if (last.isEmpty()) return selector.take(12)
        val cut = last.indexOfFirst { it == '[' || it == ':' || it == '#' || it == '.' }
        val base = if (cut > 0) last.take(cut) else last
        return base.ifEmpty { last }.take(12)
    }
}
