// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十四批 · 项目账本的**行为级判据**（过注册表真跑，不看注释）。
//
// 判据逐条（派工单 §判据 ①–⑩；⑨ 入口净增 0 与 ⑩ detekt/lint 是静态读数，见交付报告）：
//   ① sourceMsgId 可选（574）：不传 ⇒ 工具层自动补本轮消息号（真跑断言账行带真实 messageId）；
//   ② 自动补出来的行**可跳**：来源号是纯数字消息号 ⇒ parseJump 解析成功（屏幕层给跳）；
//   ③ 后台无消息号 ⇒ 落 null（行里没有 sourceMsgId 键）＋不可跳＋**无「缺口-XXX」占位号**（负例）；
//   ④ 右相 PASS 才「已收」，UNVERIFIABLE 停「待验收」——三例真跑（正例 / 没落盘 / 账上有行但文件不在）；
//   ⑤ append-only 可回放 —— 账文件只追加，重放出来与工具回执一致；
//   ⑥ 旧占位号行（「缺口-544」这类）仍可读可列表——不回填、不迁移；
//   ⑦ 「已收」不可退回「在飞」—— 工具层真跑拒绝，盘上状态不变；
//   ⑧ 归档后账文件仍全 —— 归档只多一行，其余行一条不少，条目仍在（includeArchived 能看见）；
//   ⑨ 四态计数与账文件重放一致 —— 用例**自己**解析 JSON 行重算（不复用 LedgerRules.fold）；
//   ⑩ 点号开头不计产物数 / 三工具免解锁、不在 capabilities 名册。
//
// ⚠️ FileOpLog 与 TurnSourceWiring 都是进程级单例：每条用例前后都 reset，
// 否则上一条的账行/消息号会漏进来把"后台"喂成"有本轮消息号"（假绿）。
package com.example.zhengdao.baopu.ledger

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.ReflectionRunner
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.TurnSourceWiring
import com.example.zhengdao.ui.baopu.LedgerBoardRules
import com.example.zhengdao.ui.baopu.LedgerJump
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LedgerCenterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var wsSeq = 0

    private fun ws(): File = tmp.newFolder("workspace${wsSeq++}")

    @Before
    fun resetLedgers() {
        FileOpLog.resetForTest()
        TurnSourceWiring.resetForTest()
    }

    @After
    fun clearLedgers() {
        FileOpLog.resetForTest()
        TurnSourceWiring.resetForTest()
    }

    /** 冷注册表：全新 prefs（没有任何"长期同意"）、没解锁任何组、currentSessionId ＝ NO_SESSION。 */
    private fun coldRegistry(root: File): ToolRegistry {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        return reg
    }

    private fun okJson(out: ToolOutcome): JSONObject {
        assertTrue("这一步该成功，拿到的是：$out", out is ToolOutcome.Ok)
        return JSONObject((out as ToolOutcome.Ok).text)
    }

    private fun addEntry(workspace: File, title: String, src: String = "msg-2026-11-09-001", at: Long = 1_000L): String =
        okJson(LedgerAddTool.run(JSONObject().put("title", title).put("sourceMsgId", src), workspace, at))
            .getString("id")

    /** 落一件真产物（工作区相对 `产物/…`）＋账上一行写记录。 */
    private fun seedDelivery(workspace: File, rel: String, at: Long) {
        val f = File(workspace, rel)
        f.parentFile?.mkdirs()
        f.writeText("# 交付\n内容\n", Charsets.UTF_8)
        FileOpLog.record("write_file", FileOpKind.WRITE, rel, "交付", at)
    }

    private fun statusOf(workspace: File, id: String): LedgerState? =
        LedgerStore.items(workspace).firstOrNull { it.id == id }?.status

    // ── ⑧ 免解锁与入口 ────────────────────────────────────────────────────────

    @Test
    fun `冷启免解锁_三件在tools数组里_名册里没有`() {
        val root = ws()
        val reg = coldRegistry(root)
        val arr = reg.toolsJsonOpenAi()
        val names = (0 until arr.length()).map {
            arr.getJSONObject(it).getJSONObject("function").optString("name")
        }
        listOf("ledger_add", "ledger_status", "ledger_list").forEach {
            assertTrue("冷启 tools 数组里必须有 $it：$names", it in names)
        }
        // 真身（不是 groupLockedStub）：桩会给 Rejected
        val out = reg.find("ledger_list")!!.executor(JSONObject())
        assertTrue("ledger_list 冷启不许被换成桩（Rejected 就是桩）：$out", out is ToolOutcome.Ok)
        assertTrue(
            "冷启首轮就要立得起来：$out",
            reg.find("ledger_add")!!.executor(
                JSONObject().put("title", "冷启第一条").put("sourceMsgId", "msg-cold"),
            ) is ToolOutcome.Ok,
        )
        assertTrue("盘上真有了", LedgerStore.fileOf(root).isFile)

        // 名册两处都不念名字（按整词匹配；与 BoardUnlockTest / TodoCenterTest 同一把尺）
        val roster = (reg.handle(JSONObject()) as ToolOutcome.Ok).text
        fun mentions(name: String): Boolean =
            Regex("""(?<![a-z0-9_])${Regex.escape(name)}(?![a-z0-9_])""").containsMatchIn(roster)
        listOf("ledger_add", "ledger_status", "ledger_list").forEach {
            assertFalse("capabilities 名册里不许出现 $it（541/544 同一口径）\n$roster", mentions(it))
        }
        // 负例：同一个匹配函数对确实念了名字的工具必须为真（否则上面三条可能恒真）
        assertTrue("匹配函数本身要能命中：delete_path 在名册里", mentions("delete_path"))
    }

    // ── ①/②/③ 来源号可选与自动补（574） ─────────────────────────────────────

    @Test
    fun `不传sourceMsgId_工具层自动补本轮消息号_账行带真id_可跳`() {
        val workspace = ws()
        // 引擎在新一轮落库后会把消息号装进信箱——这里装一个"本轮"的号当引擎替身
        TurnSourceWiring.installTurn(42L)
        val json = okJson(
            LedgerAddTool.run(JSONObject().put("title", "不传来源号的一条"), workspace, 1_000L),
        )
        assertEquals("判据①：账行要带上本轮的真实 messageId", "42", json.getString("sourceMsgId"))
        val item = LedgerStore.items(workspace).single()
        assertEquals("落盘的也是真值", "42", item.sourceMsgId)
        // 判据②：自动补出来的是纯数字消息号 ⇒ 屏幕层解析得动（有解析结果才给跳）
        val parsed = LedgerBoardRules.parseJump(item.sourceMsgId)
        assertEquals("纯数字＝(0, 消息id)，会话由屏幕层按消息反查", 0L to 42L, parsed)
        val row = LedgerBoardRules.rows(listOf(item)) { src ->
            if (LedgerBoardRules.parseJump(src) != null) LedgerJump(0L, 42L) else null
        }.single()
        assertTrue("解析出来就该可跳", row.canJump)
        // 传了就用模型的（不覆盖）：显式来源号原样落账
        val explicit = okJson(
            LedgerAddTool.run(
                JSONObject().put("title", "自带来源的一条").put("sourceMsgId", "缺口-544"),
                workspace,
                1_100L,
            ),
        )
        assertEquals("显式传的来源号原样保留", "缺口-544", explicit.getString("sourceMsgId"))
    }

    @Test
    fun `负例_后台无消息号_落null不可跳_不写占位号`() {
        val workspace = ws()
        // 信箱是空的（定时/后台轮没有用户消息）——不许拿上一轮的旧值、不许编占位号
        val json = okJson(
            LedgerAddTool.run(JSONObject().put("title", "后台记的一条"), workspace, 1_000L),
        )
        assertTrue("回执不带占位号：$json", !json.getString("sourceMsgId").contains("缺口"))
        val item = LedgerStore.items(workspace).single()
        assertEquals("判据③：来源落 null（空串＝无）", "", item.sourceMsgId)
        val addLine = LedgerStore.fileOf(workspace).readLines(Charsets.UTF_8).single { JSONObject(it).optString("kind") == "add" }
        val o = JSONObject(addLine)
        assertFalse("行里没有 sourceMsgId 键（null 不写成假号）", o.has("sourceMsgId"))
        assertFalse("全行不许出现「缺口-」占位号", addLine.contains("缺口-"))
        // 屏幕层同口径：没有来源号 ⇒ 连解析都不调、不给跳
        var calls = 0
        val row = LedgerBoardRules.rows(listOf(item)) {
            calls++
            null
        }.single()
        assertFalse("判据③：不可跳", row.canJump)
        assertEquals("没有来源号不该去查库", 0, calls)
    }

    @Test
    fun `负例_schema层_sourceMsgId不再是必填`() {
        val root = ws()
        val reg = coldRegistry(root)
        val spec = reg.find("ledger_add")!!
        val required = spec.inputSchema.getJSONArray("required")
        val keys = (0 until required.length()).map { required.getString(it) }
        assertTrue("title 还是必填：$keys", "title" in keys)
        assertFalse("判据⑤的另一半：sourceMsgId 不在 required 里（574 改可选）：$keys", "sourceMsgId" in keys)
    }

    @Test
    fun `旧占位号行仍可读可列表_不回填不迁移`() {
        val workspace = ws()
        // 直接种一行 544 时代的旧占位号账（旧行一个字节不动）
        val old = LedgerRow(
            kind = LedgerKind.ADD,
            id = "L1",
            atMs = 1_000L,
            title = "旧占位号的一条",
            status = "flying",
            sourceMsgId = "缺口-544",
        )
        assertTrue(LedgerStore.append(workspace, old))
        // 新规则照常在它后面立新条：账文件是追加，旧行不改一字
        TurnSourceWiring.installTurn(7L)
        okJson(LedgerAddTool.run(JSONObject().put("title", "新规则的一条"), workspace, 2_000L))
        val lines = LedgerStore.fileOf(workspace).readLines(Charsets.UTF_8)
        assertEquals("旧行原样还在（不迁移）", "缺口-544", JSONObject(lines.first()).optString("sourceMsgId"))
        val listed = okJson(LedgerListTool.run(workspace))
        assertEquals("判据⑥：旧行照常出现在列表里", 2, listed.getJSONArray("items").length())
        val items = LedgerStore.items(workspace)
        assertEquals("判据⑥：旧行照读", "缺口-544", items.first { it.id == "L1" }.sourceMsgId)
        // 旧行的占位号解析不出真消息 ⇒ 不可跳（但没有来源号 ≠ 读不出）
        assertFalse("占位号不是消息号 ⇒ 不给跳", LedgerBoardRules.parseJump("缺口-544") != null)
    }

    // ── ② 右相三态 ────────────────────────────────────────────────────────────

    @Test
    fun `正例_有落盘且文件在_右相PASS_进已收`() {
        val workspace = ws()
        val t0 = 1_000_000L
        val id = addEntry(workspace, "把交付报告写完", at = t0)
        seedDelivery(workspace, "产物/报告.md", t0 + 100)
        val json = okJson(
            LedgerStatusTool.run(JSONObject().put("id", id).put("status", "accepted"), workspace, t0 + 200),
        )
        assertEquals("右相该判 PASS：$json", LedgerRules.VERDICT_PASS, json.getString("verdict"))
        assertEquals("PASS 才进已收", LedgerState.ACCEPTED.wire, json.getString("status"))
        assertTrue(json.getBoolean("accepted"))
        assertEquals("账上得有 add＋done 两行", 2, LedgerStore.rows(workspace).size)
        assertEquals("done 行带 verdict", LedgerKind.DONE, LedgerStore.rows(workspace).last().kind)
    }

    @Test
    fun `负例A_什么都没落盘_右相判UNVERIFIABLE_停在待验收`() {
        val workspace = ws()
        val id = addEntry(workspace, "只说了做完但没落盘")
        val json = okJson(
            LedgerStatusTool.run(JSONObject().put("id", id).put("status", "已收"), workspace, 2_000L),
        )
        assertEquals(LedgerRules.VERDICT_UNVERIFIABLE, json.getString("verdict"))
        assertEquals("判不了不许进已收", LedgerState.PENDING.wire, json.getString("status"))
        assertFalse(json.getBoolean("accepted"))
        assertTrue("要把缺什么回给模型：$json", json.getString("reason").contains("UNVERIFIABLE"))
        assertEquals(LedgerState.PENDING, statusOf(workspace, id))
    }

    @Test
    fun `负例B_账上有行但文件不在_右相判FAIL_停在待验收`() {
        val workspace = ws()
        val id = addEntry(workspace, "说写了但盘上没有")
        FileOpLog.record("write_file", FileOpKind.WRITE, "产物/不存在.md", "交付", 1_500L)
        val json = okJson(
            LedgerStatusTool.run(JSONObject().put("id", id).put("status", "accepted"), workspace, 2_000L),
        )
        assertEquals("右相该判 FAIL：$json", "FAIL", json.getString("verdict"))
        assertEquals(LedgerState.PENDING.wire, json.getString("status"))
    }

    // ── ④ 已收不可退回在飞 ────────────────────────────────────────────────────

    @Test
    fun `已收之后退回在飞_工具层拒且状态不变`() {
        val workspace = ws()
        val t0 = 1_000_000L
        val id = addEntry(workspace, "会走完全程的一条", at = t0)
        seedDelivery(workspace, "产物/a.md", t0 + 100)
        okJson(LedgerStatusTool.run(JSONObject().put("id", id).put("status", "accepted"), workspace, t0 + 200))
        assertEquals(LedgerState.ACCEPTED, statusOf(workspace, id))

        val before = LedgerStore.rows(workspace).size
        val back = LedgerStatusTool.run(JSONObject().put("id", id).put("status", "flying"), workspace, t0 + 300)
        assertTrue("已收不许退回在飞：$back", back is ToolOutcome.Failed)
        assertEquals("被拒就不许落账", before, LedgerStore.rows(workspace).size)
        assertEquals("状态一个字节没动", LedgerState.ACCEPTED, statusOf(workspace, id))
    }

    // ── ⑤ 归档：账文件仍全 ────────────────────────────────────────────────────

    @Test
    fun `用户归档后默认视图没有它_账文件一行不少`() {
        val workspace = ws()
        val t0 = 1_000_000L
        val id = addEntry(workspace, "归档的那一条", at = t0)
        seedDelivery(workspace, "产物/b.md", t0 + 100)
        okJson(LedgerStatusTool.run(JSONObject().put("id", id).put("status", "accepted"), workspace, t0 + 200))
        val before = LedgerStore.rows(workspace)

        val write = LedgerStore.archive(workspace, id, LedgerActor.USER, t0 + 300)
        assertTrue("用户点归档该成功：$write", write is LedgerWrite.Ok)

        val after = LedgerStore.rows(workspace)
        assertEquals("只许**多**一行 archive", before.size + 1, after.size)
        assertEquals("add 那一行原地不动", before.first().toJson().toString(), after.first().toJson().toString())
        assertEquals(LedgerKind.ARCHIVE, after.last().kind)
        assertTrue("默认视图里没有它", LedgerRules.live(LedgerStore.items(workspace)).isEmpty())
        assertTrue("条目还在账上", LedgerRules.archived(LedgerStore.items(workspace)).any { it.id == id })
        // includeArchived 能看见它（账不删的可读证据）
        val listed = okJson(LedgerListTool.run(workspace, includeArchived = true))
        assertTrue("includeArchived 要把归档的给出来", listed.getJSONArray("items").length() == 1)
        // 归档不动状态：仍是已收
        assertEquals(LedgerState.ACCEPTED, statusOf(workspace, id))
    }

    @Test
    fun `在飞的不许归档_用户点也拒`() {
        val workspace = ws()
        val id = addEntry(workspace, "还在做")
        val before = LedgerStore.rows(workspace).size
        val refused = LedgerStore.archive(workspace, id, LedgerActor.USER, 2_000L)
        assertTrue("在飞不许归档：$refused", refused is LedgerWrite.Refused)
        assertEquals("拒了就不许落账", before, LedgerStore.rows(workspace).size)
        assertTrue("账面上它还该在", LedgerRules.live(LedgerStore.items(workspace)).any { it.id == id })
    }

    @Test
    fun `模型调ledger_archive_注册表里没有这个工具`() {
        val root = ws()
        val reg = coldRegistry(root)
        assertNull("`ledger_archive` 不许被注册成工具", reg.find("ledger_archive"))
        assertFalse("tools 数组里也不许有", "ledger_archive" in reg.names())
        // 就算绕过注册表直接喊落盘口，非用户角色也拒（第二道闸）
        val workspace = ws()
        val id = addEntry(workspace, "这条只有用户能归档")
        LedgerStore.moveTo(workspace, id, LedgerState.WITHDRAWN, 1_500L)
        val before = LedgerStore.rows(workspace).size
        listOf(LedgerActor.MODEL, LedgerActor.RIGHT_MINISTER).forEach { actor ->
            val refused = LedgerStore.archive(workspace, id, actor, 1_600L)
            assertTrue("$actor 不许归档：$refused", refused is LedgerWrite.Refused)
        }
        assertEquals("两次被拒都不许落账", before, LedgerStore.rows(workspace).size)
    }

    // ── ⑥ 计数与账文件重放一致（用例自己重算） ────────────────────────────────

    @Test
    fun `四态计数与账文件重放一致`() {
        val workspace = ws()
        addEntry(workspace, "第一条", src = "m1", at = 1_000L)
        val second = addEntry(workspace, "第二条", src = "m2", at = 1_100L)
        val third = addEntry(workspace, "第三条", src = "m3", at = 1_200L)
        val fourth = addEntry(workspace, "第四条", src = "m4", at = 1_300L)
        // 在飞 2（第一、第四）／待验收 1（第二）／冻结 1（第三）
        okJson(LedgerStatusTool.run(JSONObject().put("id", second).put("status", "pending"), workspace, 1_400L))
        okJson(LedgerStatusTool.run(JSONObject().put("id", third).put("status", "frozen"), workspace, 1_500L))
        assertNotNull(fourth)

        val listed = okJson(LedgerListTool.run(workspace))
        // 独立重算：自己读行、自己取每个 id 的最后一个 status（**不调 LedgerRules.fold**）
        val lastStatus = LinkedHashMap<String, String>()
        LedgerStore.fileOf(workspace).readLines(Charsets.UTF_8).forEach { line ->
            if (line.isBlank()) return@forEach
            val o = JSONObject(line)
            val st = o.optString("status")
            if (st.isNotEmpty()) lastStatus[o.getString("id")] = st
        }
        assertEquals("在飞", lastStatus.values.count { it == "flying" }, listed.getInt("flying"))
        assertEquals("待验收", lastStatus.values.count { it == "pending" }, listed.getInt("pending"))
        assertEquals("已收", lastStatus.values.count { it == "accepted" }, listed.getInt("accepted"))
        assertEquals("冻结", lastStatus.values.count { it == "frozen" }, listed.getInt("frozen"))
        assertEquals("撤", lastStatus.values.count { it == "withdrawn" }, listed.getInt("withdrawn"))
        assertEquals(
            "在飞 2 · 待验收 1 · 已收 0 · 冻结 1 · 撤 0",
            listed.getString("header"),
        )
    }

    // ── ⑦ 点号开头不计产物数 ──────────────────────────────────────────────────

    @Test
    fun `账文件在产物目录之外_不算产物`() {
        val workspace = ws()
        addEntry(workspace, "记一笔")
        val ledgerFile = LedgerStore.fileOf(workspace)
        assertEquals("落点就是工作区根", workspace.canonicalFile, ledgerFile.parentFile.canonicalFile)
        assertTrue("文件名点号开头", ledgerFile.name.startsWith("."))
        assertFalse("512 的 isCountedProduct 必须把它排除在产物数外", ReflectionRunner.isCountedProduct(ledgerFile))

        // 产物目录里的读数不受影响：真产物照数、账一件不加
        seedDelivery(workspace, "产物/真产物.md", 2_000L)
        val products = MemoryLayout.productsDir(workspace).listFiles { f -> ReflectionRunner.isCountedProduct(f) }
        assertEquals("产物目录里只有那件真产物", 1, products?.size ?: 0)
        // 负例：对照件必须算数（否则上面那条可能恒真）
        assertTrue(
            "对照：普通产物文件要算数",
            ReflectionRunner.isCountedProduct(File(MemoryLayout.productsDir(workspace), "真产物.md")),
        )
    }

    // ── 其它边界 ──────────────────────────────────────────────────────────────

    @Test
    fun `41字拒收_账本不动`() {
        val workspace = ws()
        val out = LedgerAddTool.run(
            JSONObject().put("title", "一".repeat(41)).put("sourceMsgId", "m1"),
            workspace,
            1_000L,
        )
        assertTrue("41 字必须拒：$out", out is ToolOutcome.Failed)
        assertFalse("拒了就不许建账本文件", LedgerStore.fileOf(workspace).exists())
    }

    @Test
    fun `同标题同来源不重复立_撤过之后可以重立`() {
        val workspace = ws()
        val first = okJson(
            LedgerAddTool.run(JSONObject().put("title", "同一件事").put("sourceMsgId", "m1"), workspace, 1_000L),
        )
        val second = okJson(
            LedgerAddTool.run(JSONObject().put("title", " 同一件事 ").put("sourceMsgId", "m1"), workspace, 2_000L),
        )
        assertEquals("同一件事不该立两条", first.getString("id"), second.getString("id"))
        assertFalse("第二次是复用，不是新建", second.getBoolean("created"))
        assertEquals("账本里只有一条 add 行", 1, LedgerStore.rows(workspace).count { it.kind == LedgerKind.ADD })

        // 撤掉之后算旧账：重做要新立一条（不是复用撤掉的那条）
        okJson(LedgerStatusTool.run(JSONObject().put("id", first.getString("id")).put("status", "撤"), workspace, 2_100L))
        val third = okJson(
            LedgerAddTool.run(JSONObject().put("title", "同一件事").put("sourceMsgId", "m1"), workspace, 2_200L),
        )
        assertTrue("撤过的可以重立", third.getBoolean("created"))
        assertTrue("新 id 不复用旧的", third.getString("id") != first.getString("id"))
    }

    @Test
    fun `不认得的状态点名报错_不落账`() {
        val workspace = ws()
        val id = addEntry(workspace, "随便一件事")
        val before = LedgerStore.rows(workspace).size
        val out = LedgerStatusTool.run(JSONObject().put("id", id).put("status", "做完啦"), workspace, 2_000L)
        assertTrue("不认得的写法必须拒：$out", out is ToolOutcome.Failed)
        val reason = (out as ToolOutcome.Failed).error
        assertTrue("拒因要把可选值列出来：$reason", reason.contains("flying") || reason.contains("在飞"))
        assertEquals("拒了就不许落账", before, LedgerStore.rows(workspace).size)
    }

    @Test
    fun `账本坏行跳过不崩_前面几行照读`() {
        val workspace = ws()
        addEntry(workspace, "先立一条")
        LedgerStore.fileOf(workspace).appendText("{这行是半截 JSON\n", Charsets.UTF_8)
        assertEquals("坏行只丢它自己，前面的行照读", 1, LedgerRules.live(LedgerStore.items(workspace)).size)
    }
}
