// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十三批 · 法典门禁 **L6 边界不可越**（派工单 253；判据为源码级扫描＋白名单）。
//
// ## 判据（可执行，两个域分别扫）
// ① **抱朴不得直接 shell 起子进程**：`baopu/**` 源码里
//    `Runtime.getRuntime().exec` / `ProcessBuilder(` / `System.load(Library)` 的调用点
//    必须全部在白名单内。**现白名单为空**：抱朴本体零子进程（keepalive 包里读 logcat 的
//    ProcessBuilder 属证道 app 层，不在「抱朴」判据域——口径收窄在此声明）。
// ② **写文件必须经 PathGuard**：`agent/**`（模型可达层——注册表是唯一真相，工具是模型的手）
//    源码里文件写 API（写/追加/删/建目录）的调用点必须全部落在白名单内。白名单的每一格
//    都有理由，只认「(文件, API)」对：新文件 ⇒ 红；白名单文件里新用一种写 API ⇒ 红。
//    （存储层 stores 的落盘是 app 自身持久化，不是模型触发的写，不在本判据域——收窄在此声明。）
//
// ## 负例（本文件自证会红）
// 负例①：假 baopu 文件里出现 `ProcessBuilder(...).start()` ⇒ 子进程扫描器抓到；
// 负例②：假 agent 文件里出现不在白名单的 `writeText` ⇒ 写路径扫描器抓到；同一处写
//         登记进白名单（像真 main 那样有据）就放行——红的是「白名单外」，不是写本身。
// 反向（防恒红）：真 main 的全部写命中都在白名单内；扫描器对无违规的假文件不报。
// 反空转：扫描器对真 main 的写命中必须 ≥ 20 处——少于它就是扫描器瞎了，门禁假绿。
package com.example.zhengdao.baopu.constitution

import com.example.zhengdao.core.PathGuard
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L6BoundaryGateTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun cleanup() {
        tempRoots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newTempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempRoots += it }

    // ── 判据表（只读探针；白名单在本文件里逐格给理由）────────────────────────

    internal object SpawnLaw {
        /** 抱朴本体不得直接 shell 起子进程（派工单 253 L6 的三个点名形态）。 */
        val APIS: Map<String, Regex> = mapOf(
            "Runtime.exec" to Regex("""Runtime\s*\.\s*getRuntime\(\)\s*\.\s*exec\s*\("""),
            "ProcessBuilder" to Regex("""\bProcessBuilder\s*\("""),
            "System.load" to Regex("""\bSystem\s*\.\s*load(?:Library)?\s*\("""),
        )

        /**
         * 调用点白名单：文件 → 允许的 API 集。**现为空**——抱朴本体零子进程。
         * 若未来真有非用不可的子进程（例如某 SDK 只给命令行口），在这里加一行并写明理由；
         * 不在白名单里的调用点一律红。
         */
        val WHITELIST: Map<String, Set<String>> = emptyMap()
    }

    internal object WriteLaw {
        /** agent 层的文件写 API 面（写/追加/删/建目录；rename 不算——原子写的一半）。 */
        val APIS: Map<String, Regex> = mapOf(
            "writeText" to Regex("""\bwriteText\s*\("""),
            "writeBytes" to Regex("""\bwriteBytes\s*\("""),
            "appendText" to Regex("""\bappendText\s*\("""),
            "FileOutputStream" to Regex("""\bFileOutputStream\s*\("""),
            "FileWriter" to Regex("""\bFileWriter\s*\("""),
            "Files.write" to Regex("""\bFiles\s*\.\s*write\s*\("""),
            "Files.copy" to Regex("""\bFiles\s*\.\s*copy\s*\("""),
            "Files.move" to Regex("""\bFiles\s*\.\s*move\s*\("""),
            "deleteRecursively" to Regex("""\bdeleteRecursively\s*\("""),
            "mkdirs" to Regex("""\bmkdirs\s*\("""),
            "delete" to Regex("""\.delete\s*\(\s*\)"""),
        )

        /**
         * 写路径白名单：文件 → 允许的 API 集（(文件, API) 粒度，一格一个理由）。
         * 键相对 `baopu/`（如 `agent/tools/WriteFileTool.kt`）。
         *
         * 共同前提：工具收到的工作区目标路径已经过 GuardPipeline 第②段
         * （`pathKeysFor` → `BaopuGuardCore.checkPath` → `PathGuard.isInside`，按路径组件比较）；
         * 下面的白名单只豁免「守卫过的目标自身的落盘手法」（原子写 tmp、回收站、备份区、
         * 引擎自己的暂存与审计落点）——不是给“绕开守卫自己挑路径写”开口子。
         */
        val WHITELIST: Map<String, Set<String>> = mapOf(
            // 第二百七十四批：意见判定账（W3「结论落账可回放」的落点）。
            // 不是模型指的工作区路径：落点＝208 已装配的那棵私有目录（`抱朴记忆/`，
            // 取自 `DecisionLedger.installedRoot()`），文件名独立（`意见判定.jsonl`），
            // 追加-only、不覆盖不删除；也不往 208 那本账里写（它的 kind 是闭集，本批红线）。
            "agent/OpinionWiring.kt" to setOf("mkdirs", "appendText"),
            // 第二百七十七批：追认队列（L33 最小版「不打扰要留理由」的落点）。
            // 落点同 274：208 已装配的那棵私有目录（`抱朴记忆/`），文件名独立
            // （`追认队列.jsonl`），追加-only、不覆盖不删除；不往 208 那本账写。
            "agent/SelfRulingLedger.kt" to setOf("mkdirs", "appendText"),
            // 第二百七十八批：御批账（御案页签「批/驳/搁置」的落点）。
            // 落点同 274/277：208 已装配的那棵私有目录，文件名独立（`御批.jsonl`），
            // 追加-only；不往 208 写（那本记"做过什么"，这本记"你怎么裁的"）。
            "agent/RoyalDecisionLedger.kt" to setOf("mkdirs", "appendText"),
            // 引擎 spill：输出过长时的暂存落盘，落点 = spillRoot()/.baopu-spill（应用私有目录），
            // 不是模型指的工作区路径；失败时降级为截断回灌并明说（不静默）。
            "agent/RunEngine.kt" to setOf("mkdirs", "writeText"),
            // 工具失败审计 JSONL（208 之外的专业账）：落点由装配层注入的私有目录，追加-only。
            "agent/ToolFallback.kt" to setOf("mkdirs", "appendText"),
            // 产物栅格化：目标在守卫过的产物目录内；delete 只用于 tmp 清理。
            "agent/tools/ArtifactRaster.kt" to setOf("mkdirs", "delete"),
            // 转码：目标路径经管线守卫；先备份目录写备份，再原子写 tmp。
            "agent/tools/ConvertEncodingTool.kt" to setOf("mkdirs", "writeBytes", "delete"),
            // 删除/移回收站：目标路径经管线守卫；mkdirs 只为回收站目录。
            "agent/tools/DeletePathTool.kt" to setOf("mkdirs", "delete"),
            // 画板沙箱：HTML/SVG 出图写 tmp 后 rename（本地渲染产物）。
            "agent/tools/Draw.kt" to setOf("mkdirs", "writeText", "delete"),
            // 编辑文件：目标路径经管线守卫；原子写 tmp（半截文件比不写更坏）。
            "agent/tools/EditFileTool.kt" to setOf("mkdirs", "writeBytes", "delete"),
            // 生图：目标目录经管线守卫；原子写 tmp，rename 失败退直写。
            "agent/tools/GenerateImageTool.kt" to setOf("mkdirs", "writeBytes", "delete"),
            // 图片操作：目标路径经管线守卫；原子写 tmp（与 LibraryPaths.atomicWrite 同口径）。
            "agent/tools/ImageOpsTool.kt" to setOf("mkdirs", "writeBytes", "delete"),
            // 资料库整理：管线 P0-4 白名单——path 保持相对，越界由 normalizeRel +
            // destinationUnder + PathGuard 自己判（守卫没绕，只是换了口）。
            "agent/tools/LibraryTools.kt" to setOf("mkdirs"),
            // 移动改名：目标路径经管线守卫；名单文件重建时写回，归档行 appendText（只增不减）。
            "agent/tools/MovePathTool.kt" to setOf("mkdirs", "writeText", "appendText", "delete"),
            // 小说项目：目录经管线守卫；deleteRecursively 是「元数据写失败 ⇒ 回滚半截目录」。
            "agent/tools/NovelProjectTool.kt" to setOf("mkdirs", "writeText", "deleteRecursively", "delete"),
            // 写文件：目标路径经管线守卫；原子写 tmp。
            "agent/tools/WriteFileTool.kt" to setOf("mkdirs", "writeText", "delete"),
            // 288 复制：目标目录 mkdirs；复制半途失败删掉半截目标（源不动，不留坏文件）。
            "agent/CopyPathTool.kt" to setOf("mkdirs", "delete"),
            // 288 共享内核：目标目录 mkdirs；失败回删半截目标；整理回执 writeText 首行 +
            // appendText 逐条追加（源→目标，撤销按它逐条搬回）；undo 复制后删现位。
            "agent/PathCopyCore.kt" to setOf("mkdirs", "writeText", "appendText", "delete"),
            // 288 整理：范围/目标经管线守卫；mkdirs 只为配方目录，writeText 只写配方 json。
            "agent/OrganizeTool.kt" to setOf("mkdirs", "writeText"),
        )
    }

    private val BAOPU_ROOT = "src/main/java/com/example/zhengdao/baopu"
    private val AGENT_ROOT = "$BAOPU_ROOT/agent"

    /** 命中路径归一到「相对 baopu/」（白名单键的口径）。 */
    private fun List<SourceHit>.relBaopu(): List<SourceHit> =
        map { it.copy(relPath = it.relPath.substringAfter("baopu/")) }

    // ── 门①：抱朴本体零子进程 ────────────────────────────────────────────────

    @Test
    fun `门1_baopu本体里_子进程调用点必须全在白名单内_现白名单为空`() {
        val hits = GateIo.scanTree(BAOPU_ROOT, SpawnLaw.APIS).relBaopu()
        val offenders = hits.filter { it.api !in (SpawnLaw.WHITELIST[it.relPath] ?: emptySet()) }
        assertTrue(
            "抱朴本体里发现了不在白名单的子进程调用点（直接 shell 起子进程是 L6 红线）：\n" +
                offenders.joinToString("\n") { "  ${it.where()} [${it.api}] ${it.text}" },
            offenders.isEmpty(),
        )
    }

    // ── 门②：agent 层写文件必须走守卫（(文件,API) 白名单）────────────────────

    @Test
    fun `门2_agent层写API调用点必须全在白名单内_每格一个理由`() {
        val hits = GateIo.scanTree(AGENT_ROOT, WriteLaw.APIS).relBaopu()
        // 反空转：扫描器要是只认出个位数，说明判据表自己坏了，下面这条会假绿。
        assertTrue(
            "写 API 扫描只认出 ${hits.size} 处——真 main 里至少 20 处，扫描器瞎了（门禁假绿）",
            hits.size >= 20,
        )
        val offenders = hits.filter { it.api !in (WriteLaw.WHITELIST[it.relPath] ?: emptySet()) }
        assertTrue(
            "agent 层发现了不在白名单的写路径（写文件必须经 PathGuard / 白名单有据）：\n" +
                offenders.joinToString("\n") { "  ${it.where()} [${it.api}] ${it.text}" },
            offenders.isEmpty(),
        )
        // 白名单自身不能烂掉：每一格都得对应真 main 里真实存在的命中（防“名单漂移”）。
        val seen = hits.groupBy({ it.relPath }, { it.api })
        val stale = WriteLaw.WHITELIST
            .filter { (file, apis) -> (seen[file] ?: emptySet()).none { it in apis } }
            .keys
        assertTrue(
            "白名单里有格子已经对应不上任何真实命中（把理由留在账上，把死格子删掉）：$stale",
            stale.isEmpty(),
        )
    }

    // ── 负例①：假 baopu 文件起子进程 ⇒ 扫描器红 ─────────────────────────────

    @Test
    fun `负例1_假源码起子进程_扫描器抓到_白名单外必红`() {
        val bad = """
            package com.example.zhengdao.baopu

            internal object EvilRunner {
                fun run(): String {
                    val p = ProcessBuilder("/system/bin/logcat", "-d").start()
                    return p.inputStream.bufferedReader().readText()
                }
                fun load(): Unit = System.loadLibrary("evil")
            }
        """.trimIndent()
        val hits = GateIo.scanLines("fake/EvilRunner.kt", bad, SpawnLaw.APIS)
        assertEquals(
            "子进程扫描器必须抓到 ProcessBuilder 与 System.loadLibrary 两处：$hits",
            listOf("ProcessBuilder", "System.load"),
            hits.map { it.api },
        )
        val clean = GateIo.scanLines(
            "fake/Clean.kt",
            """
            internal object Clean {
                // 注释里提到 ProcessBuilder( 不算调用点
                fun plan(): String = "ProcessBuilder"
            }
            """.trimIndent(),
            SpawnLaw.APIS,
        )
        assertTrue("无子进程的假文件不许报（防恒红）", clean.isEmpty())
    }

    // ── 负例②：假 agent 文件绕开白名单写文件 ⇒ 扫描器红；登记有据则放行 ────────

    @Test
    fun `负例2_假源码在白名单外写文件_扫描器抓到_登记进白名单后放行`() {
        val bad = """
            package com.example.zhengdao.baopu.agent

            import java.io.File

            internal object SneakyWriter {
                fun write(root: File, text: String) {
                    File(root, "outside.txt").writeText(text)
                }
            }
        """.trimIndent()
        val hits = GateIo.scanLines("fake/SneakyWriter.kt", bad, WriteLaw.APIS)
        assertEquals("writeText", hits.single().api)

        val offendersUnderRealLaw = hits.filter { it.api !in (WriteLaw.WHITELIST[it.relPath] ?: emptySet()) }
        assertTrue(
            "白名单外的写必须红（fake/SneakyWriter.kt 不在名单里）",
            offendersUnderRealLaw.isNotEmpty(),
        )

        // 同一处写，一旦像真 main 那样登记进白名单（有据），就放行——红的是「白名单外」，不是写本身：
        val lawWithEntry = WriteLaw.WHITELIST + ("fake/SneakyWriter.kt" to setOf("writeText"))
        val offendersUnderExtendedLaw = hits.filter { it.api !in (lawWithEntry[it.relPath] ?: emptySet()) }
        assertTrue(offendersUnderExtendedLaw.isEmpty())
    }

    // ── 反空转（真树上的正面样本）：守卫过的写路径确实还在 ─────────────────────

    @Test
    fun `反空转_真main里白名单文件确实存在且命中可数`() {
        val hits = GateIo.scanTree(AGENT_ROOT, WriteLaw.APIS).relBaopu()
            .groupBy({ it.relPath }, { it.api })
        assertTrue("WriteFileTool 的原子写必须仍在（守卫过的写文件是常规路径）", hits.containsKey("agent/tools/WriteFileTool.kt"))
        assertTrue("引擎 spill 写必须仍在", hits.containsKey("agent/RunEngine.kt"))
        val writeToolHits = hits["agent/tools/WriteFileTool.kt"].orEmpty()
        assertTrue("WriteFileTool 至少该有 mkdirs+writeText：$writeToolHits", "writeText" in writeToolHits && "mkdirs" in writeToolHits)
    }

    // ── PathGuard 本体的判据仍在（这是「写必须经守卫」那个守卫；负例钉同前缀兄弟目录）──

    @Test
    fun `真码行为_路径守卫按组件比较_同前缀兄弟目录过不去`() {
        val root = newTempDir("l6-pathguard-")
        val inside = File(root, "data.txt")
        inside.writeText("x")
        assertTrue("工作区内路径必须放行", PathGuard.isInside(root, inside))
        val sibling = File(root.absolutePath + "-evil", "data.txt")
        assertTrue(
            "同前缀兄弟目录必须拒（PathGuard 的既有判据，钉在这里防回归）",
            !PathGuard.isInside(root, sibling),
        )
    }
}
