// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十二批（2026-11-08）· **小黑板与治理出口「免解锁、不进名册」的行为判据**。
//
// 用户口径（2026-11-08）：「`board_read`/`board_write` **免解锁、默认可用**；**不在 `capabilities`
// 工具组清单里显示**（注册与 `RiskMatrix` 行保留）；`governance_state` 等自治理中心工具同样处理」。
//
// 判据逐条（派工单 §判据 ③④⑤）：
//   ③ 冷启首轮即可 `board_write` 成功——**真跑**：冷注册表（不带任何解锁、NO_SESSION）里取到的是
//      真身（不是 groupLockedStub），`executor` 调下去把字写进盘，再读回逐字相等；
//   ④ `capabilities` 输出不含 `board_*`／`governance_state`——**两处都扫**：
//      「当前可见工具」那一行、以及「全量名册（组 → 组内工具）」的 FILES 组那一行；
//   ⑤ 可见性仍是**同一条链**：关掉 FILES 组 ⇒ 这三件一起消失（免解锁不是绕过用户开关的后门）。
//
// 为什么这几条必须空过注册表、不能直接调 `BoardWriteTool.run`：
// 「免解锁」这件事的判据在**可见性链**（`isVisibleTool`）与 `find()` 的桩切换上，直接调工具
// 函数等于绕过被验的东西（同样的错 322 那批在判据④上踩过一次，这里不再踩）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoardUnlockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var wsSeq = 0

    private fun ws(): File = tmp.newFolder("workspace${wsSeq++}")

    /** 冷注册表：全新 prefs（没有任何"长期同意"）、没解锁任何组、currentSessionId ＝ NO_SESSION。 */
    private fun coldRegistry(root: File, prefs: ToolPrefs = InMemoryToolPrefs()): ToolRegistry {
        val reg = ToolRegistry(prefs)
        BaopuToolCatalog.loadInto(reg) { root }
        return reg
    }

    private fun namesOf(reg: ToolRegistry): List<String> {
        val arr = reg.toolsJsonOpenAi()
        return (0 until arr.length()).map {
            arr.getJSONObject(it).getJSONObject("function").optString("name")
        }
    }

    // ── ③ 冷启首轮 board_write 就成功 ─────────────────────────────────────────

    @Test
    fun `冷启首轮_board_write直接写成功_不用先解锁`() {
        val root = ws()
        val reg = coldRegistry(root)
        // 前置：冷启时这两个工具必须在 tools 数组里（模型根本看不见它就不用谈"调得动"）
        val names = namesOf(reg)
        assertTrue("冷启 tools 数组里必须有 board_write：$names", "board_write" in names)
        assertTrue("冷启 tools 数组里必须有 board_read：$names", "board_read" in names)

        // 取到的是**真身**：组未解锁时 find() 会换成 groupLockedStub（执行一律 Rejected）——
        // 这里 run 下去必须是 Ok，等于同时证明了"没有被换成桩"
        val spec = reg.find("board_write")
        assertTrue("board_write 必须注册着", spec != null)
        val out = spec!!.executor(JSONObject().put("text", "第一步：先看 BoardTools"))
        assertTrue("冷启首轮就要写得进去（拿到的：$out）", out is ToolOutcome.Ok)

        // 盘上真有那几行（不是"回了 Ok 但没落盘"）
        val onDisk = BoardStore.read(root)
        assertEquals("第一步：先看 BoardTools", onDisk)
        // 落账只记长度与行数这条不在这里重复验（322 的 BoardToolsTest 已钉）
    }

    @Test
    fun `冷启首轮_board_read与governance_state同样免解锁`() {
        val root = ws()
        BoardStore.write(root, "便签正文")
        val reg = coldRegistry(root)
        val read = reg.find("board_read")!!.executor(JSONObject())
        assertTrue("board_read 冷启就要读得出来（拿到的：$read）", read is ToolOutcome.Ok)
        assertTrue("读回的字要在里面", (read as ToolOutcome.Ok).text.contains("便签正文"))
        // 治理出口同一条口径：桩会给 Rejected，真身会回六段 JSON
        val gov = reg.find("governance_state")!!.executor(JSONObject())
        assertFalse("governance_state 冷启不许被换成桩（拿到 Rejected 就是桩）", gov is ToolOutcome.Rejected)
    }

    // ── ④ capabilities 输出不含这三件 ────────────────────────────────────────

    @Test
    fun `capabilities名册与当前可见两处都不含board与governance`() {
        val reg = coldRegistry(ws())
        val out = reg.handle(JSONObject())
        assertTrue("capabilities 必须回 Ok（拿到的：$out）", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        // ⚠️ 判据必须按**工具名整词**匹配，不能用 `contains`：`clipboard_write` 里就含
        // `board_write` 这串字——第一版用 contains 当场假红（542 自查抓到，与 428 N2 同型）。
        fun mentions(name: String): Boolean =
            Regex("""(?<![a-z0-9_])${Regex.escape(name)}(?![a-z0-9_])""").containsMatchIn(text)
        listOf("board_read", "board_write", "governance_state").forEach { name ->
            assertFalse("capabilities 输出里不许出现 $name（判据④）\n$text", mentions(name))
        }
        // 负例：同一个匹配函数对**确实念了名字**的工具必须为真（否则上面三条可能是恒真）
        assertTrue("匹配函数本身要能命中：delete_path 在名册里", mentions("delete_path"))
        assertTrue("clipboard_write 也要能命中（它就在 DEVICE 组那一行）", mentions("clipboard_write"))
        // 正例：同组的其它工具照常出现——证明不是"整段 FILES 都被摘掉了"
        assertTrue("FILES 组本身还要在名册里（组没被藏起来）", text.contains("files"))
        assertTrue("同组的 delete_path 照常念名字", text.contains("delete_path"))
    }

    // ── ⑤ 免解锁不是绕过用户开关 ──────────────────────────────────────────────

    @Test
    fun `用户关掉FILES组_免解锁这三件一起消失`() {
        val root = ws()
        val prefs = InMemoryToolPrefs()
        prefs.setGroupDisabled(ToolGroup.FILES, true)
        val reg = coldRegistry(root, prefs)
        val names = namesOf(reg)
        listOf("board_read", "board_write", "governance_state").forEach {
            assertFalse("FILES 组被用户关掉后 $it 不许再露面（用户开关优先）", it in names)
        }
        val out = reg.find("board_write")!!.executor(JSONObject().put("text", "x"))
        assertTrue("关掉组之后调用必须被拒（回桩），拿到的：$out", out is ToolOutcome.Rejected)
        assertFalse("一个字都不许落盘", BoardStore.fileOf(root).exists())
    }

    @Test
    fun `单件开关也压得住_关掉board_write只影响它自己`() {
        val root = ws()
        val prefs = InMemoryToolPrefs()
        prefs.setDisabled("board_write", true)
        val reg = coldRegistry(root, prefs)
        val names = namesOf(reg)
        assertFalse("board_write 被单独关掉后不许露面", "board_write" in names)
        assertTrue("board_read 不受影响（单件开关只压一件）", "board_read" in names)
    }

    // ── ① 设置页那张工具组清单里不出现（542 收尾口径）──────────────────────────

    @Test
    fun `设置页显示名单不含两件_注册与矩阵行都还在`() {
        val shown = ToolGroupTable.displayNamesOf(ToolGroup.FILES)
        listOf("board_read", "board_write").forEach {
            assertFalse("设置页那张工具组清单里不许出现 $it", it in shown)
        }
        // 同组别的工具照旧在（证明不是把整组摘空、也不是整段消失）
        assertTrue("delete_path 照旧出现在设置页清单里", "delete_path" in shown)
        // 注册与分组仍是真值：成员表里还在——可见性、回退建议、矩阵分档都按这张表算
        val real = ToolGroupTable.namesOf(ToolGroup.FILES)
        listOf("board_read", "board_write").forEach {
            assertTrue("$it 仍是 FILES 的成员（注册与分组行不许动）", it in real)
        }
        // 风险档两行也还在（R0 / R1；抽检与统计看得见这两件动作）
        val root = ws()
        assertEquals("board_read 的风险档仍应是 R0", "R0", BoardReadTool.spec { root }.riskTier)
        assertEquals("board_write 的风险档仍应是 R1", "R1", BoardWriteTool.spec { root }.riskTier)
    }
}
