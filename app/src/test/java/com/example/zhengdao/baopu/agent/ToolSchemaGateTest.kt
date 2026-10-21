// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百一十九批 §0「缺 schema 的工具不允许注册（**源码门禁**）」与
// §2「源码门禁：新注册工具缺 schema ⇒ 红」。
//
// 三道门（都在本文件，形制照仓库既有门禁 `BareThreadGuardTest` / `ChannelSwitchTruthGateTest`）：
// 1. **源码门禁**：`app/src/main` 里每一处 `ToolSpec(` 构造都必须带 `inputSchema`——
//    「忘了写 schema 的新工具」在编译前就被拦下，不用等谁去点那个工具；
// 2. **注册表门禁**：真注册表（[BaopuToolCatalog] 27 条 ＋ 运行时 4 条 = 31 个）
//    的 schema 体检账必须为空，且名册与分组表双向相等；
// 3. **负例**：给扫描器喂一段「一个好样本 + 一个坏样本」的源码，必须只抓住坏的那个
//    ——证明门 1 的判据不是永远返回空表。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolSchemaGateTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    // ── 门 1：源码里每个 ToolSpec( 都要带 inputSchema ──────────────────────

    @Test
    fun `源码门禁_每个ToolSpec构造都必须带inputSchema`() {
        val root = File("src/main/java/com/example/zhengdao")
        assertTrue("源码根目录找不到（测试工作目录应是 app/）：${root.absolutePath}", root.isDirectory)
        val offenders = mutableListOf<String>()
        var total = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { f ->
            val (missing, found) = scan(f.readText())
            total += found
            missing.forEach { block ->
                offenders += "${f.relativeTo(root).path} → ${block.replace('\n', ' ').take(140)}"
            }
        }
        assertTrue(
            "这些 ToolSpec 构造没带 inputSchema（缺 schema 的工具不许注册）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // 反空转：扫描器要是自己坏了（一个块都没认出），上面那条会假绿。
        // 真树里现在有 31+ 处构造（27 条 ENTRIES 工厂 + 4 条运行时 + 评估假件），少于 25 就是扫描器瞎了。
        assertTrue("扫描器只认出 $total 处 ToolSpec 构造 —— 它自己坏了（真树里至少 25 处）", total >= 25)
    }

    @Test
    fun `负例_扫描器只抓没带schema的那一个`() {
        val sample = """
            // 注释里提到 ToolSpec( 不算
            /* KDoc 里提到 ToolSpec( 也不算 */
            private val bad = ToolSpec(
                name = "坏样本",
                description = "故意没写 schema",
            ) { ToolOutcome.Ok("y") }

            private val good = ToolSpec(
                name = "好样本",
                description = "写了 schema",
                inputSchema = jsonObjectOf("type" to "object"),
            ) { ToolOutcome.Ok("y") }

            internal data class ToolSpec(val name: String)
        """.trimIndent()
        val (found, total) = scan(sample)
        assertEquals("扫描器该认出 2 处构造（好事例 + 坏事例），实到 $total", 2, total)
        assertEquals("扫描器只该抓住没带 schema 的那一个，实到 ${found.size} 段：$found", 1, found.size)
        assertTrue("抓住的必须是「坏样本」那一段：${found[0]}", found[0].contains("坏样本"))
    }

    // ── 门 2：真注册表的体检账为空 ─────────────────────────────────────────

    @Test
    fun `注册表门禁_真注册表面册与分组表一致且体检账为空`() {
        val reg = newRegistry()
        assertEquals(
            "注册表实到的工具与 ToolGroupTable（唯一一张分组表）不一致",
            ToolGroupTable.GROUP_OF.keys,
            reg.names().toSet(),
        )
        // 免费通道那两条也在名册里（第一百一十九批把它们搬到了 CompatChannelTools，
        // 就是为了让 JVM 门禁也能看见 bash 的 schema）
        assertNotNull("read 没注册", reg.all().firstOrNull { it.name == CompatChannelTools.NAME_READ })
        assertNotNull("bash 没注册", reg.all().firstOrNull { it.name == CompatChannelTools.NAME_BASH })
        val issues = reg.schemaIssues()
        assertTrue(
            "真注册表里有工具 schema 不合规（本该在 CI 就红，不该让用户当第一个发现者）：\n" +
                issues.entries.joinToString("\n") { (name, list) -> "  $name → $list" },
            issues.isEmpty(),
        )
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun newRegistry(): ToolRegistry {
        val root = kotlin.io.path.createTempDirectory("zd-gate119-").toFile().also { roots += it }
        val files = { PersonaFiles(File(root, "global"), root) }
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        reg.register(SkillTools.spec(workspaceRoot = { root }, personaFiles = files))
        reg.register(AppendPersonaTool.spec(personaFiles = files))
        reg.register(CompatChannelTools.readSpec(readFileEnabled = true))
        reg.register(CompatChannelTools.bashSpec())
        return reg
    }

    /**
     * 在一段 Kotlin 源码里数 `ToolSpec(` 构造块，并找出**不带 `inputSchema`** 的那些。
     *
     * @return (缺 schema 的块原文, 认出的构造块总数)。总数是给「反空转」用的：
     *   扫描器要是自己坏了、一个块都没认出，光看「缺的为空」会假绿。
     *
     * 为什么要自己扫而不是正则一把梭：构造块跨多行、里面还有 `)`、字符串里还有括号，
     * 正则数不清括号。这里是一个只认四件事的小状态机——普通字符串、原始字符串、
     * 行注释、块注释——其余字符按括号深度走，深度归零就是这一块的终点。
     *
     * `internal class ToolSpec(` 这种**声明**要跳过（它本来就不该带 schema）。
     */
    private fun scan(src: String): Pair<List<String>, Int> {
        val found = mutableListOf<String>()
        var total = 0
        val n = src.length
        var i = 0
        var inNormal = false
        var inRaw = false
        var start = -1
        var depth = 0
        while (i < n) {
            val c = src[i]
            when {
                inRaw -> if (src.startsWith("\"\"\"", i)) { inRaw = false; i += 3 } else i++
                inNormal -> if (c == '\\') i += 2 else {
                    if (c == '"') inNormal = false
                    i++
                }
                src.startsWith("//", i) -> while (i < n && src[i] != '\n') i++
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < n && !src.startsWith("*/", i)) i++
                    i += 2
                }
                src.startsWith("\"\"\"", i) -> { inRaw = true; i += 3 }
                c == '"' -> { inNormal = true; i++ }
                start < 0 && src.startsWith("ToolSpec(", i) && isIdentifierBoundary(src, i) && !isDeclaration(src, i) -> {
                    start = i
                    depth = 0
                    total++
                    i += "ToolSpec".length
                }
                start >= 0 && c == '(' -> { depth++; i++ }
                start >= 0 && c == ')' -> {
                    depth--
                    if (depth == 0) {
                        val block = src.substring(start, i + 1)
                        if (!block.contains("inputSchema")) found += block
                        start = -1
                    }
                    i++
                }
                else -> i++
            }
            if (i < 0) break
        }
        return found to total
    }

    /** 前面那个字符不是标识符字符 ⇒ 这是一个真正的 `ToolSpec` 记号（不是 `myToolSpec(`）。 */
    private fun isIdentifierBoundary(src: String, at: Int): Boolean =
        at == 0 || !(src[at - 1].isLetterOrDigit() || src[at - 1] == '_')

    /** `ToolSpec(` 前面 30 个字符里出现 `class ` ⇒ 这是声明（`data class ToolSpec(`），不是构造。 */
    private fun isDeclaration(src: String, at: Int): Boolean {
        val head = src.substring(maxOf(0, at - 30), at).trimEnd()
        return head.endsWith("class ") || head.endsWith("class")
    }
}
