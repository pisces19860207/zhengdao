// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批 §1.2/§3 J4（计划落库可回读：按 sessionId 读回，内容逐字一致）。
// 与 BaopuDbTest 同一纪律：本仓无 Robolectric ⇒ SQLiteOpenHelper 进不了 JVM，
// 这里钉 SQL 文本层（语句形状＋参数绑定顺序）与 payload 往返；真实执行归真机。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuDbPlanTest {

    // ── 「不改表结构」本身是判据 ───────────────────────────────────────

    @Test
    fun `plan不动表结构_不加表不加列不升版本`() {
        // part.kind 是自由文本列 ⇒ 新 kind 零 DDL 改动（派工单 §1.2 首选方案）。
        // 注意带括号比：`plan (` 才是不新建 plan 表；`plan_batch (` 是刀 0 的旧表，别误伤。
        assertFalse(
            "不许为 plan 新造表",
            BaopuSql.DDL.any { it.contains("CREATE TABLE IF NOT EXISTS plan (") },
        )
        // 版本号：plan 批次本身没动表（加一种 kind 不需要迁移）；
        // v7 是第十九批加的（session 封卷两列 closed_at / refined_at，有正式迁移）；
        // v8 是第二十批加的（session 工作区列 workspace，有正式迁移）——plan 部件自己依旧零 DDL。
        assertEquals("plan 批次不加表不加列；v7 归第十九批、v8 归第二十批、v9 归第三十八批（artifact）", 10, BaopuDb.VERSION)
    }

    // ── 写：消息行 ＋ plan part ────────────────────────────────────────

    @Test
    fun `insertPlanMessage_绑定顺序与seq口径`() {
        val stmt = BaopuSql.insertPlanMessage(sessionId = 5L, createdAt = 123L)
        assertTrue(stmt.sql.startsWith("INSERT INTO message"))
        assertTrue("assistant 角色", stmt.sql.contains("'assistant'"))
        assertTrue("done 状态", stmt.sql.contains("'done'"))
        assertTrue("seq 用 MAX+1 子查询（与引擎 nextSeq 同口径）", stmt.sql.contains("MAX(seq), -1) + 1"))
        // WHERE 子查询里还有一个 sessionId ⇒ 三个绑定位，别数错
        assertEquals(listOf("5", "5", "123"), stmt.args.toList())
    }

    @Test
    fun `insertPart_kind为plan_绑定顺序`() {
        val stmt = BaopuSql.insertPart(messageId = 9L, seq = 0, kind = "plan", payload = "{}", createdAt = 7L)
        assertEquals(
            "message_id, seq, kind, payload, created_at 依次绑定",
            listOf("9", "0", "plan", "{}", "7"),
            stmt.args.toList(),
        )
        assertTrue(stmt.sql.contains("INSERT INTO part"))
    }

    // ── 读：按 sessionId ＋ 消息序 ─────────────────────────────────────

    @Test
    fun `planPayloadsBySession_只取plan按消息序正序`() {
        val stmt = BaopuSql.planPayloadsBySession(3L)
        assertTrue("JOIN message 才能按会话过滤", stmt.sql.contains("JOIN message ON part.message_id = message.id"))
        assertTrue("只要 plan kind", stmt.sql.contains("part.kind = 'plan'"))
        assertTrue("按消息序正序（最后一条＝当前计划）", stmt.sql.contains("ORDER BY message.seq ASC"))
        assertEquals(listOf("3"), stmt.args.toList())
    }

    // ── J4：写入后读回逐字一致（payload 层的强形式） ──────────────────

    @Test
    fun `写入读回_内容逐字一致`() {
        val items = listOf(
            PlanRules.PlanItem("读文件", PlanRules.STATUS_DONE),
            PlanRules.PlanItem("甲".repeat(60), PlanRules.STATUS_DOING),
            PlanRules.PlanItem("收尾", PlanRules.STATUS_TODO),
        )
        // 写：入库的就是这个 payload（SessionPlanStore.insert 原样存）
        val stored = PlanRules.toJson(items)
        val part = BaopuSql.insertPart(1L, 0, "plan", stored, 100L)
        // 读：planPayloadsBySession 取回的正是 payload 列原文（SELECT part.payload）
        val payloadFromDb = part.args[3]
        assertEquals("payload 列原文逐字一致", stored, payloadFromDb)
        // 再解析回计划（UI/续跑回种走这条路）
        assertEquals(items, PlanRules.fromJson(payloadFromDb!!))
        assertEquals("再序列化也逐字一致", stored, PlanRules.toJson(PlanRules.fromJson(payloadFromDb)))
    }

    @Test
    fun `同一轮多次update_plan_保留全部取最后一条`() {
        // 口径（交付报告写明）：全部保留，取用方取最后一条。
        // SQL 层能钉的是「正序全部返回、最后一条排最后」；「取最后」的实现在
        // SessionPlanStore.latest（moveToLast），其行为由 PlanControllerTest 的
        // 假 store 覆盖；真实 SQLite 执行归真机。
        val stmt = BaopuSql.planPayloadsBySession(1L)
        assertTrue(stmt.sql.contains("ORDER BY message.seq ASC"))
        assertFalse("不许 DESC（会把最新的排最前，取错）", stmt.sql.contains("DESC"))
        assertFalse("不许 LIMIT 1（要能看到全部，UI 才知道计划变过几版）", stmt.sql.contains("LIMIT"))
    }
}
