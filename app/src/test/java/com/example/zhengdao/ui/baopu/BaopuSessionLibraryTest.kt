// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.BaopuSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二十批（会话库与工作区）判据 K1–K8 的单测。
 *
 * 三类钉法（本仓库单测环境没有 Robolectric，android.jar 是 stub）：
 * 1. **纯函数**：K1（迁移幂等）、K2（语句形态）、K3/K4（列表模型）——直接调；
 * 2. **语句层**：K2 的 UPDATE 绑定顺序；
 * 3. **源码级**（仿 util/BareThreadGuardTest 的文本对账精神）：K5（删除必经确认框）、
 *    K6（工作区单一真源）、K7（封卷顺序钉死）、K8（zh/en 键对称）——读源码文本断言。
 *    真实 SQLite 执行（v8 迁移真的加列）属证据等级③，归真机验证（蓝总管）。
 */
class BaopuSessionLibraryTest {

    // ── K1：v7→v8 幂等 ──────────────────────────────────────────────────

    @Test
    fun `K1_v8缺列才给ALTER_有列给空_重复迁移不炸`() {
        val alter = "ALTER TABLE session ADD COLUMN workspace TEXT"
        // v7 旧库：session 没有 workspace ⇒ 恰好一条 ALTER
        val stmts = BaopuSql.sessionWorkspaceColumnStmt(
            listOf("id", "title", "closed_at", "refined_at"),
        )
        assertEquals(1, stmts.size)
        assertEquals(alter, stmts[0].sql)
        assertTrue(stmts[0].args.isEmpty())
        // 已有列（v8 新库 / 重跑迁移）⇒ 空；大小写不敏感
        assertTrue(BaopuSql.sessionWorkspaceColumnStmt(listOf("id", "workspace")).isEmpty())
        assertTrue(BaopuSql.sessionWorkspaceColumnStmt(listOf("id", "WORKSPACE")).isEmpty())
        // 幂等：同一列况重复调用结果一致（不炸、不叠加）
        val again = BaopuSql.sessionWorkspaceColumnStmt(listOf("id", "workspace"))
        assertEquals(BaopuSql.sessionWorkspaceColumnStmt(listOf("id", "workspace")), again)
    }

    @Test
    fun `K1_加列语句不进DDL常量_DDL全IF_NOT_EXISTS`() {
        // 加列必须走「先看列况」的纯函数；DDL 常量里出现裸 ALTER 就等于放弃了幂等
        assertTrue("DDL 常量不许有裸 ALTER", BaopuSql.DDL.none { it.contains("ALTER TABLE") })
    }

    // ── K2：新会话记工作区 ───────────────────────────────────────────────

    @Test
    fun `K2_setSessionWorkspace语句形态与绑定顺序`() {
        val stmt = BaopuSql.setSessionWorkspace(7L, "/storage/emulated/0/Download/证道")
        assertEquals("UPDATE session SET workspace = ? WHERE id = ?", stmt.sql)
        assertEquals(
            arrayOf<String?>("/storage/emulated/0/Download/证道", "7"),
            stmt.args,
        )
    }

    @Test
    fun `K2_send建会话后接线记工作区_源码级`() {
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
            .readText()
        assertTrue(
            "send() 建会话必须补记工作区（recordSessionWorkspace）",
            src.contains("recordSessionWorkspace(created)"),
        )
        assertTrue(
            "recordSessionWorkspace 必须取 Workspace.hostDir 的解析结果（不许另立真源）",
            src.contains("Workspace.hostDir(appCtx).absolutePath"),
        )
    }

    // ── K3：老会话不编历史 ───────────────────────────────────────────────

    @Test
    fun `K3_workspace为NULL呈现为未记录_不许回填当前工作区`() {
        val now = 1_700_000_000_000L
        val currentWs = "/storage/emulated/0/Download/证道"
        val rows = buildSessionRows(
            listOf(
                BaopuSessionEntry(1, "老会话", 3, now, closedAt = null, workspace = null),
            ),
            now = now,
            datePattern = "M月d日",
            fallbackTitle = "未命名会话",
        )
        assertEquals(1, rows.size)
        assertEquals(null, rows[0].workspace)
        // 分组后落进「未记录」组，组标题是未知标签，**不是**当前工作区
        val groups = groupByWorkspace(rows, "（工作区未记录）")
        assertEquals(1, groups.size)
        assertEquals("（工作区未记录）", groups[0].header)
        assertFalse(groups[0].header.contains(currentWs))
    }

    @Test
    fun `K3_空标题兜底取未命名`() {
        val now = 1_700_000_000_000L
        val rows = buildSessionRows(
            listOf(BaopuSessionEntry(1, "  ", 1, now, closedAt = null, workspace = null)),
            now = now,
            datePattern = "M月d日",
            fallbackTitle = "未命名会话",
        )
        assertEquals("未命名会话", rows[0].title)
    }

    // ── K4：列表排序与徽标 ───────────────────────────────────────────────

    @Test
    fun `K4_按updated_at倒序_封过卷标已封卷`() {
        val t0 = 1_700_000_000_000L
        val rows = buildSessionRows(
            listOf(
                BaopuSessionEntry(1, "旧", 2, t0, closedAt = t0, workspace = null),
                BaopuSessionEntry(2, "新", 5, t0 + 9_000, closedAt = null, workspace = null),
                BaopuSessionEntry(3, "中", 3, t0 + 1_000, closedAt = null, workspace = null),
            ),
            now = t0 + 10_000,
            datePattern = "M月d日",
            fallbackTitle = "未命名会话",
        )
        assertEquals(listOf(2L, 3L, 1L), rows.map { it.id })
        assertTrue("closed_at 非空 ⇒ 已封卷", rows[2].sealed)
        assertFalse(rows[0].sealed)
        assertFalse(rows[1].sealed)
    }

    @Test
    fun `K4_分组工作区组按最新排_NULL组垫底`() {
        val t0 = 1_700_000_000_000L
        val rows = buildSessionRows(
            listOf(
                BaopuSessionEntry(1, "a", 1, t0, null, "/ws/旧"),
                BaopuSessionEntry(2, "b", 1, t0 + 3_000, null, "/ws/新"),
                BaopuSessionEntry(3, "c", 1, t0 + 2_000, null, "/ws/旧"),
                BaopuSessionEntry(4, "d", 1, t0 + 1_000, null, null),
            ),
            now = t0 + 10_000,
            datePattern = "M月d日",
            fallbackTitle = "未命名会话",
        )
        val groups = groupByWorkspace(rows, "（工作区未记录）")
        assertEquals(listOf("/ws/新", "/ws/旧", "（工作区未记录）"), groups.map { it.header })
        // 组内保序：仍是 updated_at DESC
        assertEquals(listOf(2L), groups[0].rows.map { it.id })
        assertEquals(listOf(3L, 1L), groups[1].rows.map { it.id })
    }

    @Test
    fun `K4_长路径中段省略_短路径原样`() {
        assertEquals("/short/path", shortenPath("/short/path"))
        val long = "/storage/emulated/0/Download/a-very-long-folder-name-inside"
        val s = shortenPath(long)
        assertEquals(25, s.length)
        assertTrue(s.contains("…"))
        assertEquals(long.take(12), s.take(12))
        assertEquals(long.takeLast(12), s.takeLast(12))
    }

    @Test
    fun `K4_时间今天时分_更早走日期格式`() {
        // 固定参考点：2026-10-18 12:00 本地时区
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, 9, 18, 12, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val now = cal.timeInMillis
        // 12:00 往前 3 小时 55 分 = 08:05（同一天 ⇒ HH:mm）
        assertEquals("08:05", formatSessionTime(now - 14_100_000L, now, "M月d日"))
        assertEquals("10月17日", formatSessionTime(now - 86_400_000L, now, "M月d日"))
    }

    @Test
    fun `K4_封卷是历史_openSession不许写closed_at`() {
        // §2.1.3：已封卷的会话也允许打开继续聊，但不许顺手清 closed_at——
        // 封卷是历史事实（提炼补算靠 closed_at 有/refined_at 没来筛），账只增。
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
            .readText()
        val funStart = src.indexOf("fun openSession(")
        assertTrue("找不到 openSession()", funStart >= 0)
        val funEnd = src.indexOf("\n    fun ", funStart + 1).let { next ->
            if (next < 0) src.length else next
        }
        val body = src.substring(funStart, funEnd)
        assertFalse(
            "openSession 函数体不许碰 closed_at（抹封卷 = 编造历史）",
            body.contains("closed_at"),
        )
    }

    // ── K5：删除要确认（源码级） ─────────────────────────────────────────

    @Test
    fun `K5_deleteSession只在确认框confirmButton里调`() {
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSessionLibraryScreen.kt")
            .readText()
        val lines = src.lines()
        val callLines = lines.withIndex()
            .filter { it.value.contains("controller.deleteSession(") }
            .map { it.index }
        assertTrue("删除调用必须存在", callLines.size == 1)
        // 匹配「confirmButton = {」而不是裸 confirmButton——文件头 KDoc 里提到这个词
        val confirmLines = lines.withIndex()
            .filter { it.value.contains("confirmButton = {") }
            .map { it.index }
        assertTrue("浮层里必须有确认框", confirmLines.isNotEmpty())
        val confirm = confirmLines.first()
        val call = callLines.first()
        assertTrue(
            "deleteSession 调用（行 ${call + 1}）必须落在确认框 confirmButton（行 ${confirm + 1}）之后的短窗口内——" +
                "行尾「删除」按钮只许置 askDelete 状态，不许直接删",
            call > confirm && call - confirm < 40,
        )
    }

    // ── K6：工作区只有一个真源（源码级） ─────────────────────────────────

    @Test
    fun `K6_workspace键字面量只许命中terminal_Workspace`() {
        val root = File("src/main/java/com/example/zhengdao")
        val bad = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                // 行级去注释：// 后的内容不算（注释里提到这两个键是讲解，不是第二真源）。
                // 块注释形态在本仓这两个键的语境里未出现，且造红（负例）走的是真代码。
                val code = f.readText().lines()
                    .joinToString("\n") { it.substringBefore("//") }
                code.contains("workspace_mode") || code.contains("workspace_path")
            }
            .map { it.path.replace('\\', '/') }
            .filter { !it.endsWith("terminal/Workspace.kt") }
            .toList()
        assertTrue(
            "workspace 两键是 terminal/Workspace 的私有真源，不许在别处出现（含抱朴）：\n$bad",
            bad.isEmpty(),
        )
    }

    // ── K7：不动封卷顺序（源码级） ───────────────────────────────────────

    @Test
    fun `K7_newSession里封卷必须在清sessionId之前`() {
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
            .readText()
        val funStart = src.indexOf("fun newSession()")
        assertTrue("找不到 newSession()", funStart >= 0)
        val funEnd = src.indexOf("\n    fun ", funStart + 1).let { next ->
            if (next < 0) src.length else next
        }
        val body = src.substring(funStart, funEnd)
        val close = body.indexOf("closeCurrentSession()")
        val clear = body.indexOf("sessionId = null")
        assertTrue("newSession() 里必须先 closeCurrentSession()", close >= 0)
        assertTrue("newSession() 里必须清 sessionId", clear >= 0)
        assertTrue(
            "封卷顺序不许动：closeCurrentSession()（偏移 $close）必须在 sessionId = null（偏移 $clear）之前",
            close < clear,
        )
    }

    // ── K8：zh/en 的 baopu_* 键对称 ──────────────────────────────────────

    @Test
    fun `K8_zh与en的baopu键集合对称`() {
        fun keys(path: String): Set<String> = File(path).readText()
            .split('\n')
            .mapNotNull { line ->
                Regex("name=\"(baopu_[a-z_]+)\"").find(line)?.groupValues?.get(1)
            }
            .toSet()
        val zh = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")
        assertTrue("zh 的 baopu_* 键不该为空（扫描器自己失效了？）", zh.isNotEmpty())
        assertEquals("zh/en 的 baopu_* 键集合必须对称", zh, en)
    }
}
