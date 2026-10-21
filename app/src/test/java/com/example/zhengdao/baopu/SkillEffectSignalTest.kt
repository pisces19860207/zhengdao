// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十五批 · 进化地基 ①：技能使用**效果**信号（§B 92 闭环链第一环）。
//
// 派工单 §4 要的四件事都在这里钉死：四种 outcome 各一例、聚合计数、老库迁移后读写、
// 源码门禁（采集点只记 id／枚举／时刻，不许出现 prompt／args／正文；没有自动晋升调用）。
//
// 为什么只测 SQL 文本层＋纯函数（与 `SkillUseRecordTest`／`BaopuDbTest` 同一口径）：
// 本仓单测环境**没有 Robolectric**，android.jar 是 stub ⇒ SQLiteOpenHelper 在 JVM 里
// 实例化不了。真实 SQLite 执行（ALTER 真跑一遍、GROUP BY 真数一遍）标③，
// 由「加列看列况 ⇒ 幂等」＋「NULL 不计数」两条纯函数判据代替，真机读数归后续批次。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillEffectSignalTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("../$rel") }

    /** 源码取回（CRLF 归一，同 57 批房型）。 */
    private fun source(rel: String): String =
        repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val dbSource: String get() = source("app/src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
    private val auditSource: String get() = source("app/src/main/java/com/example/zhengdao/baopu/SkillAudit.kt")
    private val runtimeSource: String get() = source("app/src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    // ── S1：v12 表结构（同表加四列，不新建第二套审计表）────────────────────────

    @Test
    fun `S1_v12四列照派工单_全部可空_老行不许回填`() {
        assertEquals(
            "四列＝outcome／source／skill_version／reason_code（派工单 §1 的四个新字段）",
            listOf("outcome", "source", "skill_version", "reason_code"),
            BaopuSql.SKILL_EFFECT_COLUMNS.map { it.first },
        )
        assertEquals("全是 TEXT（枚举名／摘要都是文本），不加 NOT NULL", 4, BaopuSql.SKILL_EFFECT_COLUMNS.size)
        BaopuSql.SKILL_EFFECT_COLUMNS.forEach { (col, type) ->
            assertEquals("$col 的类型", "TEXT", type)
        }
        val stmts = BaopuSql.skillEffectColumnStmt(OLD_V11_COLUMNS)
        stmts.forEach { s ->
            assertTrue("加列必须可空：${s.sql}", s.sql.startsWith("ALTER TABLE skill_event ADD COLUMN"))
            assertFalse("不许 NOT NULL（老行留 NULL＝当时没这个口径）：${s.sql}", s.sql.contains("NOT NULL"))
            assertTrue("不许给默认值回填：${s.sql}", !s.sql.contains("DEFAULT"))
        }
    }

    @Test
    fun `S1_加列看列况_缺哪列给哪条_重跑零条`() {
        // v11 老库（57 批的十列）⇒ 四条 ALTER
        assertEquals(4, BaopuSql.skillEffectColumnStmt(OLD_V11_COLUMNS).size)
        // 新库／重跑迁移 ⇒ 一条都不给（幂等，照 v7 封卷两列／v10 deleted_at 房型）
        assertTrue(BaopuSql.skillEffectColumnStmt(OLD_V11_COLUMNS + NEW_COLUMNS).isEmpty())
        // 大小写不敏感（PRAGMA 回来的列名不保证大小写）
        assertTrue(BaopuSql.skillEffectColumnStmt(OLD_V11_COLUMNS + NEW_COLUMNS.map { it.uppercase() }).isEmpty())
        // 缺一半就补一半（升级中途被打断，下一次接着补）
        val half = BaopuSql.skillEffectColumnStmt(OLD_V11_COLUMNS + listOf("outcome", "source"))
        assertEquals(
            "ADD COLUMN 后面第一段就是列名（缺哪列补哪列）",
            listOf("skill_version", "reason_code"),
            half.map { it.sql.substringAfter("ADD COLUMN ").substringBefore(" ") },
        )
    }

    @Test
    fun `S1_建表DDL一个字没动_新装与升级同一条路径`() {
        assertEquals("建表一条＋索引一条，还是两条（新列走 v12 迁移，不改 CREATE）", 2, BaopuSql.SKILL_EVENT_DDL.size)
        val cols = BaopuSql.SKILL_EVENT_DDL[0]
            .substringAfter("skill_event (")
            .substringBeforeLast(")")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(" ").first().trimEnd(',') }
        assertEquals("57 批的十列原样", OLD_V11_COLUMNS, cols)
        // onCreate 与 onUpgrade 都要调 applyV12Migration（两处入口同一份迁移体）
        val body = dbSource.substringAfter("class BaopuDb")
        assertEquals(
            "新装与升级必须走同一条路径",
            2,
            Regex("applyV12Migration\\(db\\)").findAll(body).count(),
        )
        assertEquals("v15 = 第一百一十七批 run_queue（v14 = 第八十九批 memory 质量四列，v13 = 第八十七批 run_trace，v12 = 第八十五批效果信号）", 15, BaopuDb.VERSION)
    }

    @Test
    fun `S1_insertSkillEffect十一列_九个问号对九个值`() {
        val s = BaopuSql.insertSkillEffect(
            name = "做卡流程", sessionId = 7L, step = 3,
            outcome = "SUCCESS", source = "AUTO", skillVersion = "0a1b2c3d4e5f",
            reasonCode = "NONE", ok = true, nowMs = 1_700_000_000_000L,
        )
        val cols = s.sql.substringAfter("INSERT INTO skill_event (").substringBefore(")")
            .split(", ").map { it.trim() }
        assertEquals(
            listOf("kind", "target", "name", "session_id", "step", "ok", "outcome", "source", "skill_version", "reason_code", "at"),
            cols,
        )
        assertEquals("占位符个数＝绑定值个数", cols.size - 2, Regex("\\?").findAll(s.sql).count())
        assertEquals(9, s.args.size)
        assertEquals(
            "绑定顺序：名字／会话／步／成败／效果／来源／版本／理由／时刻",
            listOf("做卡流程", "7", "3", "1", "SUCCESS", "AUTO", "0a1b2c3d4e5f", "NONE", "1700000000000"),
            s.args.toList(),
        )
        assertTrue("kind 写死 effect", s.sql.contains("VALUES ('effect', 'skill'"))
        assertEquals("失败记 0", "0", BaopuSql.insertSkillEffect("n", null, null, "FAILURE", "AUTO", null, "NONE", false, 1L).args[3])
    }

    @Test
    fun `S1_效果行不碰detail与hash_那是57批的列`() {
        val s = BaopuSql.insertSkillEffect("n", 1L, 1, "SUCCESS", "AUTO", "0a1b2c3d4e5f", "NONE", true, 1L)
        listOf("detail", "hash").forEach { forbidden ->
            assertFalse("效果行不许写 $forbidden（丢弃理由留给 decision）", s.sql.contains(forbidden))
        }
    }

    @Test
    fun `S1_账本仍然只增不改不删`() {
        val all = BaopuSql.SKILL_EVENT_DDL.joinToString("\n") +
            BaopuSql.insertSkillEffect("n", null, null, "SUCCESS", "AUTO", null, "NONE", true, 1L).sql +
            BaopuSql.lastSkillEffect("n", 1L).sql +
            BaopuSql.skillOutcomeCounts("n").sql +
            BaopuSql.userTextAfter(1L, 0L).sql +
            BaopuSql.skillEffectColumnStmt(OLD_V11_COLUMNS).joinToString("\n") { it.sql }
        assertFalse("不许 UPDATE", all.contains("UPDATE "))
        assertFalse("不许 DELETE", all.contains("DELETE FROM skill_event"))
        assertFalse("不许 DROP", all.contains("DROP"))
    }

    // ── S2：outcome 判定（派工单 §2 的三档优先级；判不了就不猜）─────────────────

    @Test
    fun `S2_四种outcome各至少一例`() {
        assertEquals(
            "用成了 ⇒ SUCCESS（裸事实）",
            SkillOutcome.SUCCESS to SkillReasonCode.NONE,
            SkillEffects.classify(null, null, null, ok = true),
        )
        assertEquals(
            "用砸了 ⇒ FAILURE（裸事实）",
            SkillOutcome.FAILURE to SkillReasonCode.NONE,
            SkillEffects.classify(null, null, null, ok = false),
        )
        assertEquals(
            "本轮用户否定上一条 ⇒ USER_CORRECTED",
            SkillOutcome.USER_CORRECTED to SkillReasonCode.USER_NEGATED,
            SkillEffects.classify(SkillOutcome.SUCCESS, 5_000L, SkillReasonCode.USER_NEGATED, ok = true),
        )
        assertEquals(
            "上一条失败、窗口内又用一次 ⇒ RETRIED",
            SkillOutcome.RETRIED to SkillReasonCode.AFTER_FAILURE,
            SkillEffects.classify(SkillOutcome.FAILURE, 5_000L, null, ok = true),
        )
        assertEquals(
            "上一条被纠正、改完再来 ⇒ AFTER_CORRECTION 版重试",
            SkillOutcome.RETRIED to SkillReasonCode.AFTER_CORRECTION,
            SkillEffects.classify(SkillOutcome.USER_CORRECTED, 5_000L, null, ok = false),
        )
    }

    @Test
    fun `S2_窗口外与负龄不猜重试`() {
        val over = SkillEffects.RETRY_WINDOW_MS + 1
        assertEquals(
            "隔了 10 分钟以上再同一条技能＝新的使用，记裸成败",
            SkillOutcome.SUCCESS,
            SkillEffects.classify(SkillOutcome.FAILURE, over, null, ok = true).first,
        )
        assertEquals(
            "时刻倒走（时钟调整）⇒ 不算窗口内",
            SkillOutcome.SUCCESS,
            SkillEffects.classify(SkillOutcome.FAILURE, -1L, null, ok = true).first,
        )
        assertEquals(
            "上一条读不出 ⇒ 同样不许猜",
            SkillOutcome.FAILURE,
            SkillEffects.classify(null, null, null, ok = false).first,
        )
        assertEquals(
            "恰好在窗口边界内仍算重试",
            SkillOutcome.RETRIED,
            SkillEffects.classify(SkillOutcome.FAILURE, SkillEffects.RETRY_WINDOW_MS, null, ok = true).first,
        )
    }

    @Test
    fun `S2_纠正优先于重试`() {
        // 上一条失败、本轮用户又否定 ⇒ 记 USER_CORRECTED（用户的话是最硬的证据）
        assertEquals(
            SkillOutcome.USER_CORRECTED,
            SkillEffects.classify(SkillOutcome.FAILURE, 1_000L, SkillReasonCode.USER_REWROTE, ok = false).first,
        )
        assertEquals(SkillReasonCode.USER_REWROTE, SkillEffects.classify(SkillOutcome.FAILURE, 1_000L, SkillReasonCode.USER_REWROTE, ok = false).second)
    }

    @Test
    fun `S2_认脸只认短句_长句与正常句一律判不了`() {
        assertEquals(SkillReasonCode.USER_NEGATED, SkillEffects.correctionCode("不对，重说一遍"))
        assertEquals(SkillReasonCode.USER_NEGATED, SkillEffects.correctionCode("这条不对。"))
        assertEquals(SkillReasonCode.USER_REWROTE, SkillEffects.correctionCode("换成竖版再来一次"))
        // 判不了就不猜（派工单 §2 原话）
        assertNull(SkillEffects.correctionCode(null))
        assertNull(SkillEffects.correctionCode("   "))
        assertNull(SkillEffects.correctionCode("今天天气不错，先把资料库那批图导进来"))
        assertNull(
            "长句里的「不对」多半在讲别的事，不算对上一条技能的否定",
            SkillEffects.correctionCode("不对，你先看看这个新文件里的第三段有没有按我要的格式来写我再决定要不要改"),
        )
    }

    @Test
    fun `S2_认脸的文本认完就丢`() {
        val code = SkillEffects.correctionCode("不对，重说")
        assertEquals("产出只有枚举名，没有原话", "USER_NEGATED", code?.name)
        assertFalse("理由码里不许带用户的话", code!!.name.contains("不对"))
    }

    // ── S3：聚合与隐私闸门 ────────────────────────────────────────────────────

    @Test
    fun `S3_聚合四键恒在_脏值不认`() {
        val m = SkillEffects.tally(listOf("SUCCESS", "SUCCESS", "FAILURE", "USER_CORRECTED", "RETRIED", "GARBAGE", null))
        assertEquals(4, m.size)
        assertEquals(
            "四种 outcome 各按行数；脏值与 NULL 丢掉（不冒充、也不让总数虚高）",
            listOf(2, 1, 1, 1),
            listOf(
                m[SkillOutcome.SUCCESS], m[SkillOutcome.FAILURE],
                m[SkillOutcome.USER_CORRECTED], m[SkillOutcome.RETRIED],
            ),
        )
        val empty = SkillEffects.tally(emptyList())
        assertEquals("没记过也要四个键都是 0（调用方不必自己补零）", 4, empty.size)
        assertEquals(0, empty[SkillOutcome.RETRIED])
    }

    @Test
    fun `S3_名字闸门_只认安全化后不变的技能名`() {
        assertEquals("做卡流程", SkillEffects.nameToken("做卡流程"))
        assertNull("正文塞不进 name（sanitizeName 会剥掉空白与标点 ⇒ 不相等）", SkillEffects.nameToken("先填必填项。\n第二步再看"))
        assertNull("带空格的名字不是合法技能名", SkillEffects.nameToken("做卡 流程"))
        assertNull("带路径字符的不收", SkillEffects.nameToken("技能/做卡流程"))
        assertNull("超上限（32）的不收", SkillEffects.nameToken("卡".repeat(SkillStore.NAME_MAX_CHARS + 1)))
        assertNull(SkillEffects.nameToken(""))
        assertNull(SkillEffects.nameToken(null))
    }

    @Test
    fun `S3_版本闸门_只认12位小写摘要或横杠`() {
        assertEquals("0a1b2c3d4e5f", SkillEffects.versionToken("0a1b2c3d4e5f"))
        assertEquals("文件读不出＝「没有版本可记」", "-", SkillEffects.versionToken("-"))
        assertNull(SkillEffects.versionToken(null))
        assertNull(SkillEffects.versionToken("0a1b2c3d4e5"))
        assertNull(SkillEffects.versionToken("0A1B2C3D4E5F"))
        assertNull("正文当版本号也不收", SkillEffects.versionToken("先填必填项"))
    }

    @Test
    fun `S3_toStmt_任何一格不合格就整条不写`() {
        val good = signal()
        val stmt = SkillEffects.toStmt(good)
        assertTrue("合格的信号必须能拼出语句", stmt != null)
        stmt!!.args.filterNotNull().forEach { v ->
            assertFalse("绑出去的值不许有换行：$v", v.contains("\n"))
            assertFalse("绑出去的值不许有空白：$v", v.any { it.isWhitespace() })
            assertTrue("绑出去的值最长只能是技能名上限：$v", v.length <= SkillStore.NAME_MAX_CHARS || v.all { it.isDigit() })
        }
        // 正文混进来的三条路，全部整条拒绝
        assertNull(SkillEffects.toStmt(good.copy(name = "第一步填标题\n第二步选格式")))
        assertNull(SkillEffects.toStmt(good.copy(skillVersion = "先填必填项再保存")))
        assertNull(SkillEffects.toStmt(good.copy(skillVersion = "zzzzzzzzzzzz")))
        // 枚举字段恒在值域内
        val values = stmt.args.toList()
        assertTrue(values.contains(SkillOutcome.SUCCESS.name))
        assertTrue(values.contains(SkillSource.AUTO.name))
        assertTrue(values.contains(SkillReasonCode.NONE.name))
    }

    private fun signal(
        name: String = "做卡流程",
        version: String? = "0a1b2c3d4e5f",
        outcome: SkillOutcome = SkillOutcome.SUCCESS,
        source: SkillSource = SkillSource.AUTO,
        reason: SkillReasonCode = SkillReasonCode.NONE,
        ok: Boolean = true,
    ) = SkillEffects.Signal(
        name = name,
        skillVersion = version,
        outcome = outcome,
        source = source,
        reason = reason,
        sessionId = 7L,
        step = 3,
        attemptOk = ok,
        at = 1_700_000_000_000L,
    )

    // ── S4：读接口（只读聚合，不做晋升判断）──────────────────────────────────

    @Test
    fun `S4_outcomesFor的SELECT只数数_不带判据`() {
        val s = BaopuSql.skillOutcomeCounts("做卡流程")
        assertTrue("只读", s.sql.trimStart().startsWith("SELECT"))
        assertTrue("按 outcome 聚合", s.sql.contains("GROUP BY outcome"))
        assertTrue("只数效果行", s.sql.contains("kind = 'effect'"))
        assertTrue("老库的 NULL outcome 不计数", s.sql.contains("outcome IS NOT NULL"))
        assertEquals("唯一的入参是技能名", listOf("做卡流程"), s.args.toList())
        listOf("INSERT", "UPDATE", "DELETE", "promote", "canPromote", "archive").forEach { forbidden ->
            assertFalse("读接口不许带 $forbidden", s.sql.contains(forbidden))
        }
    }

    @Test
    fun `S4_上一条效果按id倒序取一条`() {
        val s = BaopuSql.lastSkillEffect("做卡流程", 7L)
        assertTrue(s.sql.contains("kind = 'effect'"))
        assertTrue("同会话同技能", s.sql.contains("name = ? AND session_id = ?"))
        assertTrue("按写入顺序取最后一条（at 会因时钟调整不单调）", s.sql.contains("ORDER BY id DESC LIMIT 1"))
        assertEquals(listOf("做卡流程", "7"), s.args.toList())
    }

    @Test
    fun `S4_读用户消息只为判纠正_表里没有它的位置`() {
        val s = BaopuSql.userTextAfter(7L, 100L)
        assertTrue("只读消息正文（进内存判完就丢）", s.sql.trimStart().startsWith("SELECT"))
        assertTrue("用户角色", s.sql.contains("m.role = 'user'"))
        assertTrue("正文在 seq=0 的 text part", s.sql.contains("p.kind = 'text' AND p.seq = 0"))
        assertTrue("只看上一条之后的", s.sql.contains("m.created_at > ?"))
        assertEquals(listOf("7", "100"), s.args.toList())
        // 表结构侧：效果行能写的列里没有正文／消息那一类
        val cols = BaopuSql.insertSkillEffect("n", 1L, 1, "SUCCESS", "AUTO", null, "NONE", true, 1L).sql
        listOf("payload", "body", "prompt", "text", "detail", "hash").forEach { forbidden ->
            assertFalse("效果行的列清单不许出现 $forbidden", cols.contains(forbidden))
        }
    }

    @Test
    fun `S4_步号与57批同一个来源`() {
        val s = BaopuSql.lastRunLogStep(7L)
        assertEquals("SELECT MAX(step) FROM run_log WHERE session_id = ?", s.sql)
        assertEquals(listOf("7"), s.args.toList())
    }

    // ── S5：源码门禁（采集点只记 id／枚举；没有自动晋升）───────────────────────

    @Test
    fun `S5_装配层一次使用记两行_use不动_effect另加`() {
        val gate = runtimeSource.substringAfter("useRecorder = com.example.zhengdao.baopu.agent.SkillTools.UseRecorder")
            .substringBefore("),")
        assertTrue("57 批那条照旧", gate.contains("val tip = recordSkillUse(appCtx, name, ok)"))
        assertTrue("本批另记一条效果信号", gate.contains("recordSkillEffect(appCtx, name, ok)"))
        val lastLines = gate.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.takeLast(2)
        assertEquals(
            "lambda 最后一句仍是 tip ⇒ 返回的是 use 的提醒，effect 的结果被丢弃（不改 skill_use 的语义）",
            listOf("tip", "},"),
            lastLines,
        )
    }

    @Test
    fun `S5_采集点写失败只log_带超时_不阻塞`() {
        val fn = effectFn()
        assertTrue("runCatching 兜底", fn.contains("runCatching {"))
        assertTrue("失败要落 RunLog（不静默）", fn.contains("RunLog.log("))
        assertTrue("写要等提交（有界，不无限等）", fn.contains(".get(3, java.util.concurrent.TimeUnit.SECONDS)"))
        assertTrue("先过闸门再拼 SQL", fn.contains("SkillEffects.toStmt(signal)"))
    }

    @Test
    fun `S5_采集点不出现正文与工具参数`() {
        val fn = effectFn()
        listOf("optString", "getString(\"body\"", "args.get", "payload", "prompt", "readText", ".text").forEach { forbidden ->
            assertFalse("采集点不许读／传正文类东西：$forbidden", fn.contains(forbidden))
        }
        assertTrue("进库的只有 SkillEffects 造出来的 token", fn.contains("SkillEffects.Signal("))
        assertTrue("用户消息只过认脸函数，不入库", fn.contains("SkillEffects.correctionCode("))
        assertFalse("效果记录不许调正文写盘", fn.contains("SkillStore.apply"))
    }

    @Test
    fun `S5_没有自动晋升退役`() {
        val newRegion = auditSource.substringAfter("// ── 第八十五批")
        val fn = effectFn()
        // 窗口右边界显式钉在**下一条批次注释**上：原来靠 `\n}`（对象结尾）兜底，
        // 第八十七批的 v13 run_trace 段插在同一对象里 ⇒ 会把别人的新表扫进「效果信号」判据。
        val sql = dbSource.substringAfter("// ── v12（第八十五批）")
            .substringBefore("// ── v13（第八十七批）")
        (newRegion + fn + sql).let { all ->
            listOf(
                "canPromote", "promote(", "archive(", "renameTo", "bumpUses", "expiryVerdict",
                "needsRecheck", "delete", "DELETE", "UPDATE ",
            ).forEach { forbidden ->
                assertFalse("效果信号不许带晋升／退役／处置动作：$forbidden", all.contains(forbidden))
            }
        }
    }

    @Test
    fun `S5_57批的use行与过期判据一行没动`() {
        val use = BaopuSql.insertSkillUse("n", 1L, 2, true, 0L)
        assertEquals("use 行的语义不许被本批改掉", "VALUES ('use', 'skill', ?, ?, ?, ?, ?)", use.sql.substringAfter(") "))
        assertTrue(
            "过期判据仍只读 kind='use'（effect 行不参与）",
            BaopuSql.recentUseOks("n", 3).sql.contains("kind = 'use'"),
        )
        assertTrue(
            "最后一次被用时间仍只读 kind='use'",
            BaopuSql.lastUseAt("n").sql.contains("kind = 'use'"),
        )
    }

    /** `recordSkillEffect` 的函数体（从签名到下一个 `latestSessionId` 的 KDoc）。 */
    private fun effectFn(): String =
        runtimeSource
            .substringAfter("private fun recordSkillEffect(appCtx: Context, name: String, ok: Boolean) {")
            .substringBefore("\n    /**\n     * 最近一个**有内容**的未归档会话的 id")

    // ── 列名常量（S1 的两份输入）──────────────────────────────────────────────

    private val OLD_V11_COLUMNS = listOf("id", "kind", "target", "name", "session_id", "step", "ok", "hash", "detail", "at")
    private val NEW_COLUMNS = listOf("outcome", "source", "skill_version", "reason_code")
}
