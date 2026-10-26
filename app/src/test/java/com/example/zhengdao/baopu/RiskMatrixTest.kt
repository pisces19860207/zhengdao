// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百一十九批 §3「判据 / 负例」。
// 本文件既是正例账，也是**门禁本体**：真注册表缺一档 ⇒ 红；未分类被放行 ⇒ 红；
// 阈值/关键词离开表 ⇒ 红；判定层长出一丁点 IO/时钟 ⇒ 红。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.AppendPersonaTool
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.CompatChannelTools
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.SkillTools
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RiskMatrixTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    // ── 门禁 1：注册表全覆盖 ──────────────────────────────────────────────

    @Test
    fun `注册表69个工具_每个都有档或显式豁免`() {
        val names = newRegistry().names()
        assertEquals("注册表工具数变了：先更新本批的工具→档表，再回来改这条", 69, names.size)
        assertEquals(
            "这些注册工具没有风险档（fail-closed 门禁）：${RiskMatrix.uncovered(names)}",
            emptyList<String>(),
            RiskMatrix.uncovered(names),
        )
        // 只有 bash 允许 baseOf=null：它是显式豁免（终端不参与分级）
        assertEquals(listOf("bash"), names.filter { RiskMatrix.baseOf(it) == null })
        assertTrue("bash 必须是显式豁免，不能靠 fail-closed 兜", RiskMatrix.isExempt("bash"))
        assertFalse("普通工具不得冒充豁免", RiskMatrix.isExempt("write_file"))
    }

    @Test
    fun `218预置六件已进表且没有别的多余行`() {
        val names = newRegistry().names().toSet()
        val pending = RiskTable.DEFAULT.tools.keys - names
        assertEquals(
            "表里多出/少掉了 218 的预置行：$pending",
            emptySet<String>(), // 218 已接线：六件从「待接线」变成「已注册」，本行随之收口
            pending,
        )
        val expected = mapOf(
            "sheet_read" to RiskClass.R0,
            "search_web" to RiskClass.R0,
            "schedule_task" to RiskClass.R2,
            "clipboard_write" to RiskClass.R1,
            "notify" to RiskClass.R1,
            "phone_dial" to RiskClass.R1,
        )
        expected.forEach { (name, risk) ->
            assertEquals("218 口径：$name 应为 $risk", risk, RiskMatrix.baseOf(name))
        }
    }

    @Test
    fun `四档每档至少三个工具_门禁不是空转`() {
        val counts = RiskTable.DEFAULT.tools.values.groupingBy { it.base }.eachCount()
        listOf(RiskClass.R0, RiskClass.R1, RiskClass.R2).forEach { risk ->
            assertTrue("$risk 只有 ${counts[risk] ?: 0} 个工具，表坏了", (counts[risk] ?: 0) >= 3)
        }
        // R3 的**基础档**在派工单里只有 generate_image 一条（花钱外发）；
        // 点「结算/支付」这类升到 R3 是参数级判据（见 dangerousClickWords），不是另一张基档行。
        assertTrue("R3 基档空了：${counts[RiskClass.R3] ?: 0}", (counts[RiskClass.R3] ?: 0) >= 1)
    }

    @Test
    fun `正例_抽样十个工具与表一致`() {
        val expected = mapOf(
            "read_file" to RiskClass.R0,
            "browser_snapshot" to RiskClass.R0,
            "preview_local_file" to RiskClass.R0,
            "write_file" to RiskClass.R1,
            "card_build" to RiskClass.R1,
            "browser_screenshot" to RiskClass.R1,
            "delete_path" to RiskClass.R2,
            "browser_click" to RiskClass.R2,
            "run_subtasks" to RiskClass.R2,
            "generate_image" to RiskClass.R3,
        )
        expected.forEach { (name, risk) ->
            val v = verdict(name)
            assertEquals("$name 的档与表不一致", risk, v.risk)
        }
    }

    // ── 原件区：硬拒，不是升档 ────────────────────────────────────────────

    @Test
    fun `原件区_写目标硬拒而不是升档`() {
        val relative = verdict("write_file", mapOf("path" to "资料库/原始/a.md", "content" to "x"))
        assertEquals(RiskDecision.REJECT, relative.decision)
        assertEquals("硬拒不是升档：底档仍是 R1", RiskClass.R1, relative.risk)
        assertEquals(RiskCode.PATH_ORIGINAL_RAW, relative.reasonCode)
        assertTrue("原件区是最硬的拒", relative.hardDeny)

        val ctx = RiskCtx(workspaceRoots = listOf("/sdcard/Download/证道"))
        val absolute = verdict(
            "write_file",
            mapOf("path" to "/sdcard/Download/证道/资料库/原始/a.md", "content" to "x"),
            ctx,
        )
        assertEquals(RiskDecision.REJECT, absolute.decision)

        // 157 后的私有原件区：filesDir/baopu/library/原始/
        val private = verdict(
            "write_file",
            mapOf(
                "path" to "/data/user/0/com.example.zhengdao/files/baopu/library/原始/a.md",
                "content" to "x",
            ),
            ctx,
        )
        assertEquals(RiskDecision.REJECT, private.decision)

        // 移动的源也是写目标（源被移走＝原件没了）
        val move = verdict("move_path", mapOf("from" to "资料库/原始/a.md", "to" to "产物/a.md"))
        assertEquals(RiskDecision.REJECT, move.decision)

        // 读原件区不在此限（既有 P0-2 只读红线管写）
        val read = verdict("read_file", mapOf("path" to "资料库/原始/a.md"))
        assertEquals(RiskClass.R0, read.risk)
        assertEquals(RiskDecision.PROCEED, read.decision)
    }

    @Test
    fun `原件区_同前缀兄弟目录不误伤`() {
        val sibling = verdict("write_file", mapOf("path" to "资料库/原始2/a.md", "content" to "x"))
        assertEquals(RiskDecision.PROCEED, sibling.decision)
    }

    // ── 工作区外 / 覆盖 / 批量 ────────────────────────────────────────────

    @Test
    fun `写工作区外升R2_相对路径天然在内`() {
        val ctx = RiskCtx(workspaceRoots = listOf("/sdcard/Download/证道"))
        val outside = verdict("write_file", mapOf("path" to "/sdcard/Download/a.txt", "content" to "x"), ctx)
        assertEquals(RiskClass.R2, outside.risk)
        assertEquals(RiskCode.PATH_OUTSIDE_WORKSPACE, outside.reasonCode)

        val inside = verdict(
            "write_file",
            mapOf("path" to "/sdcard/Download/证道/产物/a.txt", "content" to "x"),
            ctx,
        )
        assertEquals(RiskClass.R1, inside.risk)

        val relative = verdict("write_file", mapOf("path" to "产物/a.txt", "content" to "x"), ctx)
        assertEquals(RiskClass.R1, relative.risk)

        // 没给工作区根时绝对路径一律按「外面」fail-closed
        val unknownRoot = verdict("write_file", mapOf("path" to "/sdcard/Download/证道/产物/a.txt", "content" to "x"))
        assertEquals(RiskClass.R2, unknownRoot.risk)
    }

    @Test
    fun `同名覆盖_只有真要覆盖时才升R2`() {
        val ctx = RiskCtx(existingPaths = setOf("产物/a.md"))
        val overwrite = verdict(
            "write_file",
            mapOf("path" to "产物/a.md", "content" to "x", "overwrite" to true),
            ctx,
        )
        assertEquals(RiskClass.R2, overwrite.risk)
        assertEquals(RiskCode.OVERWRITE_EXISTING, overwrite.reasonCode)

        // overwrite=false 时 write_file 自己会失败，不算「覆盖」
        val refused = verdict("write_file", mapOf("path" to "产物/a.md", "content" to "x"), ctx)
        assertEquals(RiskClass.R1, refused.risk)

        // 原地改写的工具不按「撞名」升档（否则 R1 底档永远用不上）
        val edit = verdict("edit_file", mapOf("path" to "产物/a.md", "op" to "replace", "content" to "x"), ctx)
        assertEquals(RiskClass.R1, edit.risk)
    }

    @Test
    fun `批量阈值_删11必问_目标21必问_清空回收站必问`() {
        val eleven = verdict("delete_path", mapOf("path" to "产物/a.md", "paths" to paths(10)))
        assertEquals(RiskDecision.MUST_ASK, eleven.decision)
        assertEquals(RiskCode.DELETE_OVER_10, eleven.reasonCode)

        val ten = verdict("delete_path", mapOf("path" to "产物/a.md", "paths" to paths(9)))
        assertEquals(RiskDecision.PROCEED, ten.decision)
        assertEquals(RiskClass.R2, ten.risk)

        val many = verdict("card_build", mapOf("out_dir" to "产物/卡", "files" to paths(21)))
        assertEquals(RiskDecision.MUST_ASK, many.decision)
        assertEquals(RiskCode.BATCH_OVER_20, many.reasonCode)

        val twenty = verdict("card_build", mapOf("out_dir" to "产物/卡", "files" to paths(19)))
        assertEquals(RiskDecision.PROCEED, twenty.decision)

        val emptyTrash = verdict("delete_path", mapOf("empty_trash" to true))
        assertEquals(RiskDecision.MUST_ASK, emptyTrash.decision)
        assertEquals(RiskCode.EMPTY_TRASH, emptyTrash.reasonCode)
    }

    // ── 网页 / 字段 / URL / 语义 / 频率 ───────────────────────────────────

    @Test
    fun `点结算或提交升R3_普通点击仍R2`() {
        val pay = verdict("browser_click", mapOf("target" to "text=结算"))
        assertEquals(RiskClass.R3, pay.risk)
        assertEquals(RiskDecision.MUST_ASK, pay.decision)
        assertEquals(RiskCode.SUBMIT_LIKE_BUTTON, pay.reasonCode)

        val submit = verdict("browser_click", mapOf("target" to "#submit"))
        assertEquals(RiskClass.R3, submit.risk)

        val next = verdict("browser_click", mapOf("target" to "#next-page"))
        assertEquals(RiskClass.R2, next.risk)
        assertEquals(RiskDecision.PROCEED, next.decision)
    }

    @Test
    fun `密码框与验证码框硬拒_普通输入框不拒`() {
        val password = verdict(
            "browser_type",
            mapOf("target" to "input[type=password]", "text" to "hunter2"),
        )
        assertEquals(RiskDecision.REJECT, password.decision)
        assertEquals(RiskCode.PASSWORD_FIELD, password.reasonCode)
        assertTrue("密码框是硬拒", password.hardDeny)

        val captcha = verdict("browser_type", mapOf("target" to "#验证码", "text" to "1234"))
        assertEquals(RiskDecision.REJECT, captcha.decision)

        val plain = verdict("browser_type", mapOf("target" to "#search", "text" to "hello"))
        assertEquals(RiskClass.R2, plain.risk)
        assertEquals(RiskDecision.PROCEED, plain.decision)
    }

    @Test
    fun `URL内网硬拒_白名单可配置`() {
        val internal = verdict("browser_open", mapOf("url" to "http://10.0.0.1/admin"))
        assertEquals(RiskDecision.REJECT, internal.decision)
        assertEquals(RiskCode.PRIVATE_URL, internal.reasonCode)
        assertTrue("内网 URL 是硬拒", internal.hardDeny)

        assertEquals(
            RiskDecision.REJECT,
            verdict("browser_open", mapOf("url" to "http://localhost:8080/x")).decision,
        )
        assertEquals(
            RiskDecision.REJECT,
            verdict("search_web", mapOf("query" to "a", "site" to "192.168.1.1")).decision,
        )

        // 未配置白名单：只拒内网，公网可读
        assertEquals(
            RiskDecision.PROCEED,
            verdict("browser_open", mapOf("url" to "https://example.com/a")).decision,
        )

        val allow = RiskCtx(allowedUrlDomains = setOf("example.com"))
        assertEquals(
            RiskDecision.PROCEED,
            verdict("browser_open", mapOf("url" to "https://m.example.com/a"), allow).decision,
        )
        val denied = verdict("browser_open", mapOf("url" to "https://other.com/a"), allow)
        assertEquals(RiskDecision.REJECT, denied.decision)
        assertTrue(denied.reasons.any { it.id == "url_not_whitelisted" })
    }

    @Test
    fun `外发账号金额语义升R3_拨号不误伤`() {
        val phone = verdict("clipboard_write", mapOf("text" to "联系电话 13800138000"))
        assertEquals(RiskClass.R3, phone.risk)
        assertEquals(RiskCode.SENSITIVE_SEMANTICS, phone.reasonCode)

        assertEquals(RiskClass.R1, verdict("clipboard_write", mapOf("text" to "hello")).risk)
        assertEquals(RiskClass.R3, verdict("notify", mapOf("title" to "好了", "body" to "password: x")).risk)

        val scheduled = verdict("schedule_task", mapOf("when" to "0 21 * * *", "prompt" to "每天支付房租"))
        assertEquals(RiskClass.R3, scheduled.risk)
        assertEquals(RiskDecision.MUST_ASK, scheduled.decision)

        // 定时任务就算内容干净也必须问（218：会产生无人值守的自己）
        val benign = verdict("schedule_task", mapOf("when" to "0 21 * * *", "prompt" to "整理今天的资料"))
        assertEquals(RiskDecision.MUST_ASK, benign.decision)
        assertTrue(benign.reasons.any { it.id == "always_ask" })

        // 218 口径：拨号是 R1，号码是它的本职工作，不按账号语义升档
        val dial = verdict("phone_dial", mapOf("number" to "13800138000"))
        assertEquals(RiskClass.R1, dial.risk)
        assertEquals(RiskDecision.PROCEED, dial.decision)
    }

    @Test
    fun `频率超阈加一档`() {
        val twenty = (1..20).map { 59_000L + it }
        val hot = verdict(
            "read_file",
            mapOf("path" to "产物/a.md"),
            RiskCtx(nowMs = 60_000L, recentCalls = mapOf("read_file" to twenty)),
        )
        assertEquals(RiskClass.R1, hot.risk)
        assertEquals(RiskCode.FREQ_OVER_LIMIT, hot.reasonCode)

        val nineteen = verdict(
            "read_file",
            mapOf("path" to "产物/a.md"),
            RiskCtx(nowMs = 60_000L, recentCalls = mapOf("read_file" to twenty.take(19))),
        )
        assertEquals(RiskClass.R0, nineteen.risk)

        // 与其它升档叠加时取最高
        val payHot = verdict(
            "browser_click",
            mapOf("target" to "text=结算"),
            RiskCtx(nowMs = 60_000L, recentCalls = mapOf("browser_click" to twenty)),
        )
        assertEquals(RiskClass.R3, payHot.risk)
    }

    // ── fail-closed / 豁免 ────────────────────────────────────────────────

    @Test
    fun `未分类工具按R2必问并打unclassified标记`() {
        val v = verdict("mcp__some_server__do_thing")
        assertEquals(RiskClass.R2, v.risk)
        assertEquals(RiskDecision.MUST_ASK, v.decision)
        assertTrue(v.unclassified)
        assertEquals("unclassified_tool", v.auditTag)
        assertEquals(RiskCode.UNCLASSIFIED_TOOL, v.reasonCode)
        assertEquals("未分类也要带矩阵版本", 5, v.matrixVersion)
        assertFalse("必问不是硬拒", v.hardDeny)
        assertFalse("未分类绝不能被当 R0 放行", v.risk == RiskClass.R0)
    }

    @Test
    fun `bash不归本表_read是R0`() {
        assertNull("bash 不能在四档里", RiskMatrix.baseOf("bash"))
        assertTrue(RiskMatrix.isExempt("bash"))
        val bash = verdict("bash", mapOf("command" to "rm -rf /"))
        assertEquals(RiskDecision.EXEMPT, bash.decision)
        assertNull("豁免项没有风险档", bash.risk)
        assertEquals("豁免项没有主判据", RiskCode.NONE, bash.reasonCode)
        assertFalse(bash.hardDeny)
        assertEquals(5, bash.matrixVersion)

        assertEquals(RiskClass.R0, RiskMatrix.baseOf("read"))
        assertEquals(RiskClass.R0, verdict("read", mapOf("path" to "产物/a.md")).risk)
    }

    @Test
    fun `可解释字段_主判据与硬拒标记`() {
        val raw = verdict("write_file", mapOf("path" to "资料库/原始/a.md", "content" to "x"))
        assertEquals(RiskCode.PATH_ORIGINAL_RAW, raw.reasonCode)
        assertTrue(raw.hardDeny)

        val batch = verdict("delete_path", mapOf("path" to "产物/a.md", "paths" to paths(10)))
        assertEquals(RiskCode.DELETE_OVER_10, batch.reasonCode)
        assertFalse("必问不是硬拒", batch.hardDeny)

        val r3 = verdict("generate_image", mapOf("prompt" to "一只猫"))
        assertEquals(RiskCode.BASE_R3, r3.reasonCode)
        assertFalse(r3.hardDeny)

        val clean = verdict("read_file", mapOf("path" to "产物/a.md"))
        assertEquals(RiskCode.NONE, clean.reasonCode)
        assertFalse(clean.hardDeny)
    }

    @Test
    fun `matrixVersion_表里带版本且原样回填`() {
        // 228 B 片九行＋224 form_fill＋228 C 片示教/回放五件 ⇒ 4；232 table_query ⇒ 5
        assertEquals(5, RiskTable.DEFAULT.matrixVersion)
        assertEquals(5, verdict("read_file", mapOf("path" to "产物/a.md")).matrixVersion)

        val bumped = RiskTable.DEFAULT_VERSION + 1
        val v2 = RiskTable.DEFAULT.copy(matrixVersion = bumped)
        assertEquals(bumped, verdict("read_file", mapOf("path" to "产物/a.md"), table = v2).matrixVersion)
        // 豁免与未分类也要带版本（208 的账不分档位）
        assertEquals(bumped, verdict("bash", mapOf("command" to "x"), table = v2).matrixVersion)
        assertEquals(bumped, verdict("mcp__s__t", table = v2).matrixVersion)
    }

    @Test
    fun `负例_覆盖门禁能抓住没档的工具`() {
        assertEquals(
            listOf("ghost_tool"),
            RiskMatrix.uncovered(listOf("read_file", "ghost_tool", "bash")),
        )
    }

    // ── 表驱动：改一行 / 改一个阈值，行为跟着变 ──────────────────────────

    @Test
    fun `改表一行_同一个调用判定跟着变`() {
        val args = mapOf("path" to "产物/a.md", "content" to "x")
        assertEquals(RiskClass.R1, verdict("write_file", args).risk)

        val promoted = RiskTable.DEFAULT.copy(
            tools = RiskTable.DEFAULT.tools +
                ("write_file" to RiskTable.DEFAULT.tools.getValue("write_file").copy(base = RiskClass.R2)),
        )
        assertEquals(RiskClass.R2, verdict("write_file", args, table = promoted).risk)
    }

    @Test
    fun `阈值来自表_改阈值行为跟着变`() {
        val delete30 = mapOf("path" to "产物/a.md", "paths" to paths(29))
        assertEquals(RiskDecision.MUST_ASK, verdict("delete_path", delete30).decision)

        val loose = RiskTable.DEFAULT.copy(maxDeleteTargetsPerCall = 30, maxTargetsPerCall = 100)
        assertEquals(RiskDecision.PROCEED, verdict("delete_path", delete30, table = loose).decision)
    }

    @Test
    fun `关键词来自表_删掉结算就不再升R3`() {
        val click = mapOf("target" to "text=结算")
        assertEquals(RiskClass.R3, verdict("browser_click", click).risk)

        val noPay = RiskTable.DEFAULT.copy(
            dangerousClickWords = RiskTable.DEFAULT.dangerousClickWords - "结算",
        )
        assertEquals(RiskClass.R2, verdict("browser_click", click, table = noPay).risk)
    }

    // ── 纯度：纯函数 + 零 IO / 零时钟 ─────────────────────────────────────

    @Test
    fun `判定是纯函数_重复调用同判且不改入参`() {
        val args = JSONObject()
            .put("path", "/sdcard/Download/证道/产物/a.md")
            .put("content", "send 13800138000")
        val call = ToolCall("t1", "write_file", args)
        val ctx = RiskCtx(
            nowMs = 60_000L,
            recentCalls = mapOf("write_file" to (1..20).map { 50_000L + it }),
            workspaceRoots = listOf("/sdcard/Download/证道"),
            existingPaths = setOf("产物/a.md"),
            allowedUrlDomains = setOf("example.com"),
        )
        val before = args.toString()
        val ctxBefore = ctx.toString()

        val first = RiskMatrix.classify(call, ctx)
        val second = RiskMatrix.classify(call, ctx)
        assertEquals("同一输入两次判定必须一致", first, second)
        assertEquals("判定不许改 arguments", before, args.toString())
        assertEquals("判定不许改 ctx", ctxBefore, ctx.toString())
    }

    @Test
    fun `源码门禁_判定层不碰IO网络时钟与弹卡`() {
        val src = File("src/main/java/com/example/zhengdao/baopu/RiskMatrix.kt")
        assertTrue("源码文件找不到：${src.absolutePath}", src.isFile)
        val text = src.readText()
        val forbidden = listOf(
            "java.io.",
            "java.nio.",
            "java.net.",
            "okhttp",
            "android.",
            "System.currentTimeMillis",
            "System.nanoTime",
            "Thread(",
            "runBlocking",
            "Log.",
            "println(",
            "File(",
            "URL(",
            "HttpURLConnection",
            "Random(",
        )
        // 以 `(` 结尾的符号按「前面不是标识符字符」匹配：否则枚举值 `PRIVATE_URL(` 会把
        // `URL(` 这条判据误触（195 批同类假阳的教训：门禁自己也要能被门禁）。
        val hit = forbidden.filter { sym ->
            if (sym.endsWith("(")) {
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(sym)).containsMatchIn(text)
            } else {
                text.contains(sym)
            }
        }
        assertTrue("判定层里出现了副作用/IO/时钟/网络符号：$hit", hit.isEmpty())
        assertTrue("源码只有 ${text.lineSequence().count()} 行，门禁可能空转了", text.lineSequence().count() > 300)
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    /** 与 `ToolSchemaGateTest` 同一套真注册表夹具（58 条 ENTRIES ＋ 运行时 4 条＝62；228 批 B 片 +9）。 */
    private fun newRegistry(): ToolRegistry {
        val root = kotlin.io.path.createTempDirectory("zd-risk219-").toFile().also { roots += it }
        val files = { PersonaFiles(File(root, "global"), root) }
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        reg.register(SkillTools.spec(workspaceRoot = { root }, personaFiles = files))
        reg.register(AppendPersonaTool.spec(personaFiles = files))
        reg.register(CompatChannelTools.readSpec(readFileEnabled = true))
        reg.register(CompatChannelTools.bashSpec())
        return reg
    }

    private fun paths(count: Int): JSONArray = JSONArray().also { arr ->
        repeat(count) { arr.put("产物/f$it.md") }
    }

    private fun verdict(
        name: String,
        args: Map<String, Any?> = emptyMap(),
        ctx: RiskCtx = RiskCtx(),
        table: RiskTable = RiskTable.DEFAULT,
    ): RiskVerdict {
        val json = JSONObject()
        args.forEach { (k, v) -> json.put(k, v) }
        return RiskMatrix.classify(ToolCall("t", name, json), ctx, table)
    }
}
