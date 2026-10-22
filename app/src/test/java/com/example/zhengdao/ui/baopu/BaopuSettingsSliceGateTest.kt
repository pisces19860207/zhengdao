// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据：派工单第八十一批 §2「源码门禁：页面里对应逻辑只剩调用，不得留两份实现」。
//
// 为什么要有它：纯函数直测能证明 helper 自己是对的，证明不了 `BaopuSettingsScreen` 真的
// 改走了 helper——两边各留一份也能全绿（§4 打回「留了两份实现」就是这么来的）。
//
// 判据一律读**剥掉注释**后的源码（先剥块注释再剥整行注释，与 61/59/36 批同一套做法）。
//
// 141 批更新：工具区从长列表改成一张摘要卡，页面改走的 helper 由「折叠摘要」换成
// `BaopuToolArea`（`summarize` / `rowsOf` / `baopuToolRowTone`）。**门槛没有放宽**——
// 原来是「折叠摘要的判据只许在 helper、页面只剩一次调用」，现在是「摘要卡的三个数字与
// 露面行列只许在 helper、页面只剩各一次调用」，逐条对得上。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaopuSettingsSliceGateTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"), File("app/$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    /** 剥注释（保留字符串）后的源码——注释里提到关键词不算命中。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val page by lazy { code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt") }
    private val rules by lazy { code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsRules.kt") }
    private val zh by lazy { repoFile("src/main/res/values/strings.xml").readText(Charsets.UTF_8) }
    private val en by lazy { repoFile("src/main/res/values-en/strings.xml").readText(Charsets.UTF_8) }

    // ── 1. 页面只剩调用（删掉任一处调用 ⇒ 这条红 ⇒ 打回）──────────────────────

    @Test
    fun `门禁_五处判据都从 helper 取`() {
        listOf(
            "baopuSwitchableGroups(ToolGroup.values())",
            "stringResource(baopuGroupTitle(row.group))",
            "baopuToolRowTone(row.state)",
            "restoreLast(baopuPersonaLayerOf(d.scope), d.name)",
            "BaopuToolArea.summarize(toolGroups,",
            "BaopuToolArea.rowsOf(toolGroups,",
        ).forEach { call ->
            assertTrue("设置页里没有 $call —— 抽取没接线", page.contains(call))
        }
    }

    @Test
    fun `门禁_同包调用不需要 import_但helper文件必须真在`() {
        // 本批 helper 与页面同包（ui/baopu），所以页面里没有 import 行；这条钉的是文件本体没被删。
        assertTrue("helper 文件必须存在",
            repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsRules.kt").isFile)
        listOf(
            // 141：折叠摘要三件已退役，换成摘要卡四件（判据本体仍在 helper，页面只剩调用）
            "internal object BaopuToolArea",
            "internal data class BaopuToolAreaSummary",
            "internal data class BaopuToolAreaRow",
            "internal enum class BaopuToolRowTone",
            "internal fun baopuToolRowTone(",
            "internal fun baopuGroupTitle(",
            "internal enum class BaopuGroupState",
            "internal fun baopuGroupState(",
            "internal fun baopuGroupCaptionKey(",
            "internal fun baopuGroupSwitchOn(",
            "internal fun baopuPersonaLayerOf(",
        ).forEach { d ->
            assertTrue("helper 里必须有 $d", rules.contains(d))
        }
    }

    // ── 2. 页面不留第二份实现 ────────────────────────────────────────────────

    @Test
    fun `门禁_设置页不留第二份实现`() {
        listOf(
            "enum class BaopuFoldKind",
            "data class BaopuFoldSummary",
            "object BaopuToolFoldSummary",
            "fun baopuGroupTitle(",
            "fun baopuGroupCaptionKey(",
            "fun baopuGroupState(",
            "fun baopuPersonaLayerOf(",
            "fun layerOf(",
            "it != ToolGroup.COMPAT",
            "SkillScope.GLOBAL -> PersonaLayer.B_GLOBAL",
            "R.string.baopu_tools_group_default_on",
            "R.string.baopu_tools_group_default_off",
            "ToolGroup.CORE -> R.string.baopu_tools_group_core",
        ).forEach { moved ->
            assertEquals("设置页里不该再有 $moved", 0, page.split(moved).size - 1)
        }
    }

    @Test
    fun `门禁_helper 里每条判据只算一次`() {
        // 判据本体只有一份：折叠摘要那个 when 的四个出口、取组序的 filter、组标题的 when 分支
        listOf(
            // 141：摘要卡的三个数字与露面行列各只有一处实现
            "fun summarize(" to 1,
            "fun rowsOf(" to 1,
            "internal fun baopuToolRowTone(" to 1,
            // 第一百二十三批：取组序的判据从「只摘 COMPAT」扩成「再摘动态组（MCP）」，
            // 仍只有这一处 filter —— 断言跟着唯一实现走，不允许出现第二份。
            "all.filter { it != ToolGroup.COMPAT && !it.dynamic }" to 1,
            // 第一百零四批：COMPAT 的 fail-fast 从「标题映射里写一份」收成一处共享
            // （组用途映射也要抛同一个理由）⇒ 判据仍只有一份：声明一次、两张映射各调一次
            "private fun compatNoSwitch(): Nothing" to 1,
            "-> compatNoSwitch()" to 2,
            "SkillScope.GLOBAL -> PersonaLayer.B_GLOBAL" to 1,
            "SkillScope.PROJECT -> PersonaLayer.C_PROJECT" to 1,
        ).forEach { (needle, times) ->
            assertEquals("helper 里 $needle 出现次数", times, rules.split(needle).size - 1)
        }
        // 摘要卡的本体在 helper，页面只剩一次调用（两边各一份就是这个形状）
        assertEquals(1, page.split("BaopuToolArea.summarize(toolGroups,").size - 1)
        assertEquals(1, page.split("BaopuToolArea.rowsOf(toolGroups,").size - 1)
        assertEquals(1, page.split("stringResource(baopuGroupTitle(row.group))").size - 1)
        assertEquals(1, rules.split("internal fun baopuGroupTitle(").size - 1)
    }

    // ── 3. helper 是纯的：不碰 Compose、不碰 prefs、不碰 Context、不读写盘 ──────

    @Test
    fun `门禁_helper零Android零写盘`() {
        listOf(
            "import androidx.compose", "android.content.Context", "LocalContext", "prefs",
            "SharedPreferences", "BaopuRuntime", "Toast", "Context", "remember", "mutableStateOf",
            "stringResource", "getString(", "File(", ".writeText(", "mkdirs", "delete()", "renameTo",
        ).forEach { tok ->
            assertFalse("纯逻辑文件里不许出现 $tok", rules.contains(tok))
        }
        assertEquals("helper 一个字写盘语句都不许有", 0, Regex("\\.writeText\\(").findAll(rules).count())
        assertEquals("helper 不许新造开关读写口", 0, Regex("prefs\\.").findAll(rules).count())
    }

    @Test
    fun `门禁_helper不许另立工具名单或第二张组表`() {
        // 59 批 §1.1 那条红线（第二张组表）不因搬动而复活：这里不许出现成员名单，也不许出现
        // 引擎可见性判据的第二次调用（ProgressiveToolsTest G1 全仓钉它只能在 ToolRegistry.kt）。
        assertFalse("不许硬写工具名单",
            Regex("listOf\\(\\s*\"(read_file|list_dir|write_file|delete_path)\"").containsMatchIn(rules))
        assertFalse("不许碰可见性判据（那是第二条链）", Regex("visibleTools\\(|isVisibleTool\\(").containsMatchIn(rules))
        assertFalse("不许自己列成员表", rules.contains("ToolGroupTable.ENTRIES"))
        assertEquals("成员名单只许在页面里从那张表读一次", 1, page.split("ToolGroupTable.namesOf(it)").size - 1)
    }

    // ── 4. 搬走的是判据，不是文案：句子与读取点都得还在 ─────────────────────────

    @Test
    fun `门禁_默认可见两句文案没丢_只是改由helper查键`() {
        listOf("baopu_tools_group_default_on", "baopu_tools_group_default_off").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
            // 键还在，且引用它的那一行现在在 helper 里、恰好一次（不是删掉了，也不是留了两份）
            assertEquals("$key 在 helper 里被引用", 1, rules.split("R.string.$key").size - 1)
        }
        // 页面里 `group in ToolGroup.DEFAULT_VISIBLE` 那一次读取必须原样留着（61 批 G5 同口径）
        assertEquals("默认可见的读取点必须恰好一处且在页面里",
            1, Regex("group in ToolGroup\\.DEFAULT_VISIBLE").findAll(page).count())
    }

    @Test
    fun `门禁_没顺手把别的批次的钉子搬走`() {
        // 61 批那六句折叠文案在 141 批随长列表一起退役（成员名单不进 UI ⇒ 摘要行没有容身之处）。
        // 门禁反向钉：它们既不许被页面引用，字符串本身也不许留着当零引用资源。
        listOf(
            "baopu_tools_fold_note", "baopu_tools_fold_empty", "baopu_tools_fold_all_on",
            "baopu_tools_fold_all_off", "baopu_tools_fold_some_off", "baopu_tools_fold_list_sep",
        ).forEach { key ->
            assertFalse("$key 已退役，页面不许再引用", page.contains("R.string.$key"))
            assertFalse("$key 的字符串也该删掉", zh.contains("name=\"$key\""))
        }
        // 18 批 J1/J8：三张标签表仍是本页的 private fun，两个写入点仍各一处
        listOf("baopuToolLabel", "effortLabel", "baopuToolDesc").forEach { fn ->
            assertTrue("设置页必须有 private fun $fn", page.contains("private fun $fn"))
        }
        // 141：组开关三档各一处；单工具开关的写入点从「成员行」搬到「打开组时清单项」，
        // 仍恰好一处（成员名单不进 UI ⇒ 这是用户把以前关掉的工具开回来的唯一出路）。
        assertEquals(1, Regex("prefs\\.setGroupDisabled\\(row\\.group, !want\\)").findAll(page).count())
        assertEquals(1, Regex("prefs\\.setDisabled\\(it, false\\)").findAll(page).count())
        assertEquals(1, Regex("relockAll\\(listOf\\(row\\.group\\)\\)").findAll(page).count())
        assertEquals("本页只留这几个 prefs 方法（141：单工具开关的读改走 disabledNames 一次快照）",
            setOf(
                "setGroupDisabled", "setGroupApproved", "setDisabled",
                "disabledGroups", "approvedGroups", "disabledNames",
            ),
            Regex("prefs\\.(\\w+)\\(").findAll(page).map { it.groupValues[1] }.toSet())
        // 37/34/32 批钉在页内的字面量：回滚三钉 ＋ 归档七句 ＋ 草稿两钉，一个都没被我搬走
        listOf(
            "enabled = latest != null", "PersonaBackup.displayStamp(", "PersonaBackup.restore(",
            "R.string.skill_archived_restored", "R.string.skill_archived_occupied",
            "R.string.skill_archived_io_failed", "R.string.skill_archived_confirm",
            "R.string.skill_archived_deleted", "R.string.skill_archived_index_full, SkillRules.INDEX_CAP",
            "R.string.skill_archived_index_skipped", "draftSkillRows(projectRoot, globalRoot)",
            "archivedSkillRows(projectRoot, globalRoot)",
        ).forEach { keep ->
            assertTrue("设置页必须还留着 $keep", page.contains(keep))
        }
    }
}
