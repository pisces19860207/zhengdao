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
        // 追加一 B 段（D5）：库里没记就是没记——展示行上 workspace 仍为 null，不许回填
        assertEquals(null, rows[0].workspace)
        // 分组后进「默认工作区」组（追加一 B 段），组标题是调用方拼好的透传值，
        // **不是**当前工作区路径本身
        val header = "默认工作区 · …/Download/证道"
        val groups = groupByWorkspace(rows, header)
        assertEquals(1, groups.size)
        assertEquals(header, groups[0].header)
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
    fun `D5_分组_NULL组归默认工作区排最前_组内保序`() {
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
        val groups = groupByWorkspace(rows, "默认工作区 · …/Download/证道")
        // 追加一 B 段改口径：NULL 组（旧会话的家）改排**最前**——存量老会话是
        // 「旧会话没了」焦虑的主体，垫底等于继续看不见；组标题标明是归口不是史实。
        assertEquals("默认工作区 · …/Download/证道", groups[0].header)
        assertEquals(listOf("/ws/新", "/ws/旧"), groups.drop(1).map { it.header })
        // 组内保序：仍是 updated_at DESC
        assertEquals(listOf(4L), groups[0].rows.map { it.id })
        assertEquals(listOf(2L), groups[1].rows.map { it.id })
        assertEquals(listOf(3L, 1L), groups[2].rows.map { it.id })
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

    // ── 第二十五批（会话库小修）判据 P1–P6 ──────────────────────────────

    @Test
    fun `P1_轮数实时数role_user条数_不读msg_count列`() {
        val sql = BaopuSql.sessionLibraryEntries().sql
        assertTrue(
            "会话库必须 LEFT JOIN 数出真轮数（第二十五批 A 段）：$sql",
            sql.contains("LEFT JOIN") && sql.contains("COUNT(*)") &&
                sql.contains("role = 'user'") && sql.contains("COALESCE"),
        )
        assertFalse(
            "不许再读没维护的 msg_count 列（真机全线 0 的根源）",
            sql.contains("msg_count"),
        )
    }

    @Test
    fun `P2_不改表结构_VERSION仍是8_无新加列`() {
        assertEquals(
            "第二十五批不动表结构（v9/v10/v11/v12/v13/v14 是后续批次的表，与本批无关；v12 为第八十五批效果信号、v13 为第八十七批 run_trace、v14 为第八十九批 memory 质量列）",
            14,
            com.example.zhengdao.baopu.BaopuDb.VERSION,
        )
        assertTrue(
            "workspace 列（v8）保持原样",
            BaopuSql.SESSION_WORKSPACE_COLUMNS == listOf("workspace" to "TEXT"),
        )
        assertTrue("DDL 常量不许有裸 ALTER", BaopuSql.DDL.none { it.contains("ALTER TABLE") })
    }

    @Test
    fun `P3_抽屉资料库desc与落点一致_源码级`() {
        // desc 只说落点页里真实存在的东西（挂载状态/导出记录），不许再承诺没有的
        val zh = File("src/main/res/values/strings.xml").readText()
        val desc = Regex("name=\"baopu_drawer_library_desc\">([^<]*)<").find(zh)?.groupValues?.get(1)
            ?: error("找不到 baopu_drawer_library_desc")
        assertFalse("不许再承诺「搬进来的书」（真资料库页是刀 2）：$desc", desc.contains("搬进来的书"))
        assertFalse("不许再承诺「整理进度」：$desc", desc.contains("整理进度"))
        assertTrue("desc 要说清落点（终端与资料页）：$desc", desc.contains("终端与资料"))
        // 落点确实开「终端与资料」页（SettingsDataActivity）——desc 与落点一致
        val drawer = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt").readText()
        assertTrue(
            "抽屉资料库落点必须是 openSettings（= SettingsDataActivity，即「终端与资料」页）",
            drawer.contains("openSettings(context)"),
        )
        val openSettings = Regex(
            "private fun openSettings\\(context: Context\\)[\\s\\S]*?SettingsDataActivity::class\\.java",
        ).containsMatchIn(drawer)
        assertTrue("openSettings 必须落 SettingsDataActivity", openSettings)
    }

    @Test
    fun `P4_删当前会话只回空态_不许自动跳别的会话_源码级`() {
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
            .readText()
        val funStart = src.indexOf("fun deleteSession(")
        assertTrue("找不到 deleteSession()", funStart >= 0)
        val funEnd = src.indexOf("\n    fun ", funStart + 1).let { next ->
            if (next < 0) src.length else next
        }
        val body = src.substring(funStart, funEnd)
        // (?!=) 排除 `==` 比较（if (sessionId == sid) 不是赋值）
        val assigns = Regex("sessionId\\s*=(?!=)\\s*(?!null)").findAll(body).toList()
        assertTrue(
            "deleteSession 里 sessionId 只许赋 null（回空态）——不许自动选别的会话（用户没选，别替他选）：" +
                assigns.joinToString("；") { it.value },
            assigns.isEmpty(),
        )
        assertTrue("删当前会话必须清视图（clearSessionView）", body.contains("clearSessionView()"))
    }

    @Test
    fun `P5_删非当前会话不动当前会话_源码级`() {
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
            .readText()
        val funStart = src.indexOf("fun deleteSession(")
        val funEnd = src.indexOf("\n    fun ", funStart + 1).let { next ->
            if (next < 0) src.length else next
        }
        val body = src.substring(funStart, funEnd)
        assertTrue(
            "删除必须带「是不是当前会话」的守卫：删非当前 ⇒ 当前会话不动（P5）",
            // 追加一后守卫是 `if (ok && sessionId == sid)`（先看删除成没成）；只要守卫存在即可
            body.contains("sessionId == sid"),
        )
    }

    @Test
    fun `P4_删当前会话有提示_浮层确认按钮里接Toast`() {
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSessionLibraryScreen.kt")
            .readText()
        assertTrue(
            "删除当前会话后必须说一声（baopu_session_deleted_current）",
            src.contains("R.string.baopu_session_deleted_current"),
        )
        assertTrue(
            "提示的判定要在删除前取（wasCurrent）",
            src.contains("val wasCurrent = row.id == controller.sessionId"),
        )
    }

    // ── 第二十五批追加一（删除与归属）判据 D1–D6 ────────────────────────

    @Test
    fun `D1_确认框必须组合在浮层Dialog内容之内_源码级`() {
        // 追加一 A 段真因：确认框原来与 Dialog 平级（函数体顶层），挂在主窗口组合树里，
        // 弹出的窗口 z 序在全屏浮层之下 ⇒ 真机「点删除没反应」。
        // 钉法：askDelete?.let（确认框）必须出现在 Dialog( 的 content 之内——
        // 即它的行首缩进深于 Dialog( 行（本仓 4 空格制：content 内 ≥ 8 空格）。
        val lines = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSessionLibraryScreen.kt")
            .readText().lines()
        // 认「浮层 Dialog 的开头」：只认 Dialog( 本身——onDismissRequest 可能在下一行
        val dialogLine = lines.indexOfFirst { it.contains("Dialog(") }
        val askLine = lines.indexOfFirst { it.contains("askDelete?.let") }
        assertTrue("找不到浮层 Dialog", dialogLine >= 0)
        assertTrue("找不到确认框组合（askDelete?.let）", askLine >= 0)
        assertTrue(
            "确认框必须出现在浮层 Dialog 之后（在 content 内组合），当前在第 ${askLine + 1} 行",
            askLine > dialogLine,
        )
        val indent = lines[askLine].takeWhile { it == ' ' }.length
        assertTrue(
            "确认框必须嵌在 Dialog content 内（缩进 ≥ 8 空格），当前缩进 $indent ——顶层平级就是真机「确认框被盖住」的根源",
            indent >= 8,
        )
    }

    @Test
    fun `D2_删除语句三张表按序_message在session前`() {
        val stmts = com.example.zhengdao.baopu.BaopuSql.deleteSessionStatements(1L)
        val messageIdx = stmts.indexOfFirst { it.sql.contains("DELETE FROM message") }
        val sessionIdx = stmts.indexOfFirst { it.sql.contains("DELETE FROM session") }
        assertTrue("必须删 message", messageIdx >= 0)
        assertTrue("必须删 session 本体", sessionIdx >= 0)
        assertTrue("message 必须先于 session（part 靠外键级联一起走）", messageIdx < sessionIdx)
    }

    @Test
    fun `D4_删除同步等结果且失败不静默`() {
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()
        val funStart = src.indexOf("fun deleteSession(")
        val funEnd = src.indexOf("\n    fun ", funStart + 1).let { next ->
            if (next < 0) src.length else next
        }
        val body = src.substring(funStart, funEnd)
        assertTrue(
            "db.write 的 Future 必须 .get() 等真落库（异步跑完前刷新列表＝删不掉的另一半真因）",
            body.contains(".get()"),
        )
        assertTrue("返回成败给浮层（失败要提示）", body.contains(": Boolean"))
        val ui = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSessionLibraryScreen.kt").readText()
        assertTrue(
            "删除失败必须有提示（baopu_session_delete_failed，不许静默）",
            ui.contains("R.string.baopu_session_delete_failed"),
        )
    }

    @Test
    fun `D5_库里不回填workspace_NULL仍是NULL`() {
        // 追加一 B 段红线：归口只在展示层，不许把 NULL 洗成默认路径写进库
        val src = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()
        val funStart = src.indexOf("fun loadSessionLibrary(")
        val funEnd = src.indexOf("\n    fun ", funStart + 1).let { next ->
            if (next < 0) src.length else next
        }
        val body = src.substring(funStart, funEnd)
        assertFalse(
            "loadSessionLibrary 不许把 workspace 回填成默认路径（copy(workspace =）",
            body.contains("copy(workspace ="),
        )
        val sql = BaopuSql.sessionLibraryEntries().sql
        assertTrue("会话库查询是只读 SELECT", sql.contains("SELECT"))
        assertFalse("会话库查询不许写 workspace 列", sql.contains("UPDATE"))
    }
}
