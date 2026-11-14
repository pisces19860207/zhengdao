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
            // 第二百九十八批：判决经验账（283 那个内存桩的落盘替身——判据①要的"30 天"
            // 跨重启才成立）。落点同 274/277：208 已装配的那棵私有目录（`抱朴记忆/`），
            // 文件名独立（`判决经验账.jsonl`），追加-only、不覆盖不删除；不往 208 那本账写。
            "agent/JudgmentExperienceLedger.kt" to setOf("mkdirs", "appendText"),
            // 第二百七十八批：御批账（御案页签「批/驳/搁置」的落点）。
            // 落点同 274/277：208 已装配的那棵私有目录，文件名独立（`御批.jsonl`），
            // 追加-only；不往 208 写（那本记"做过什么"，这本记"你怎么裁的"）。
            "agent/RoyalDecisionLedger.kt" to setOf("mkdirs", "appendText"),
            // 第四百二十八批：接管生产接线落账（进度账＋接管账，判据②③的落点）。
            // 落点同 274/277/298/278：208 已装配的那棵私有目录（`抱朴记忆/`），文件名独立
            // （`进度自报.jsonl`＋`接管账.jsonl`），追加-only、不覆盖不删除；不往 208 那本账写。
            "agent/TakeoverWiring.kt" to setOf("mkdirs", "appendText"),
            // 引擎 spill：输出过长时的暂存落盘，落点 = spillRoot()/.baopu-spill（应用私有目录），
            // 不是模型指的工作区路径；失败时降级为截断回灌并明说（不静默）。
            "agent/RunEngine.kt" to setOf("mkdirs", "writeText"),
            // 第五百零七批：停机记录（叫停 → 重启仍停 的落盘壳）。落点＝装配层注入的
            // App 私有 `filesDir/baopu/run-stop-state.json`——不在任何 workspace root 里，
            // 模型文件工具够不着（「解除只能人做」连改文件这条路都不存在）；
            // 一覆盖一读，delete 只用于人做的解除（删文件＝没有叫停记录）。
            "agent/StopStateStore.kt" to setOf("mkdirs", "writeText", "delete"),
            // 第二百九十六批：影子沙盒（预演唯一碰盘处）——建副本/拍快照全在 `.shadow/<opId>/`
            // 副本根里，真盘声明对象只读；deleteRecursively/delete 只用于清副本根自己
            // （判据层说 CONSISTENT 才清，违规副本留着当证据）。
            "agent/ShadowSandbox.kt" to setOf("mkdirs", "deleteRecursively", "delete"),
            // 第五百一十六批：许愿流水线的三类取数 IO 接线（CARD/CONTENT/NOVEL 的 WishPorts
            // 真端口）。写只有三样：草稿落 `许愿/<wishId>/草稿/`（装配层传进来的工作区内路径）、
            // 试跑写军师副本（RehearsalSandbox 的 runDir，358 批判据管的）、转正把草稿搬进
            // 真落点（产物／成品／正文；CardBuild 自带不覆盖闸、appendLedger 走 179 多写者协议）。
            // 试跑期真项目只读（前后 hashTree 各称一次）；510 的 WishSideEffectGateTest 钉
            // 「许愿包除技能写口外没有生产写口」，所以本件住在 agent/（被扫的域）登记进场。
            "agent/WishIOWiring.kt" to setOf("mkdirs", "writeText"),
            // 第五百一十二批：归因增量游标的落盘点（`AttributionCursor`）。写只有三样：
            // 游标文件本身（`<档案目录>/.归因游标.json`，点号开头＝不进产物读数）；
            // 落盘先写 tmp 再 rename（**renameTo 失败才 delete 旧件重试**，所以 delete 要登记）；
            // 目标目录由装配层注入的私有档案目录给出，不经模型参数。
            "agent/AttributionCursor.kt" to setOf("mkdirs", "writeText", "delete", "renameTo"),
            // 工具失败审计 JSONL（208 之外的专业账）：落点由装配层注入的私有目录，追加-only。
            "agent/ToolFallback.kt" to setOf("mkdirs", "appendText"),
            // 产物栅格化：目标在守卫过的产物目录内；delete 只用于 tmp 清理。
            "agent/tools/ArtifactRaster.kt" to setOf("mkdirs", "delete"),
            // 转码：目标路径经管线守卫；先备份目录写备份，再原子写 tmp。
            "agent/tools/ConvertEncodingTool.kt" to setOf("mkdirs", "writeBytes", "delete"),
            // 删除/移回收站：目标路径经管线守卫；mkdirs 只为回收站目录。
            "agent/tools/DeletePathTool.kt" to setOf("mkdirs", "delete"),
            // 第五百九十七批：立绘外貌 merge——唯一写动作是把人批的草稿写进**守卫过的卡路径**
            // （`checkPath` 在工具层自己跑，CONFIRM 管线＋已有外貌不覆盖在 PortraitMerge 判）。
            // 账本（`.appearance.jsonl`）的 mkdirs/appendText 在 portrait/ 包，不在 agent/ 扫描域。
            "agent/tools/PortraitAppearanceTool.kt" to setOf("writeText"),
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
            // 第三百二十八批：金吾卫喂入口的落盘（attempts 跨进程续跑＋装配账）——落点在
            // 应用私有目录 filesDir/jinwu-patrol/，不是模型指的工作区路径；mkdirs 建自己的
            // 账目录，appendText 是装配账一行一条（available/unavailable），writeText 是
            // attempts 整表快照（重启不归零的判据面，GapFeedback 差值喂板读它）。
            "agent/JinwuObserve.kt" to setOf("mkdirs", "writeText", "appendText"),
            // 第三百五十八批：推演盘（军师）的副本沙盒——**唯一碰盘处**，与 296 那格同一条理由。
            // 目标不是模型指的工作区路径，而是 App 私有 `cacheDir/rehearsal/rh-<runId>/`：
            // mkdirs 只建这棵副本根与其子目录；真盘声明对象只读（length/exists/canWrite/读字节）；
            // deleteRecursively/delete 只用于清副本树自己，动手前四件事 assert 齐
            // （是目录／父名＝rehearsal／名字带 rh-／在 cacheDir 里且不等于根），见 RehearsalSandbox.cleanupAt。
            "agent/RehearsalSandbox.kt" to setOf("mkdirs", "deleteRecursively", "delete"),
            // 第四百九十九批：打扰治理账（`打扰治理.jsonl`）——落点由 208 已装配的那棵私有目录给
            // （DecisionLedger.installedRoot），append-only 一行一条问不问的决定/放过/申诉/切档；
            // 与 274/277/298/278 同一条理由：这本账记"该不该打扰"，不是模型指的工作区路径。
            "agent/AskGovernance.kt" to setOf("mkdirs", "appendText"),
            // 第五百七十一批：相处改判账（法典 L34 的"账在哪"：改判落一行，原判/新判/依据）。
            // 落点同 274/499：208 已装配的那棵私有目录（DecisionLedger.installedRoot()），
            // 文件名独立（相处改判.jsonl），追加-only、不覆盖不删除；不往 208 那本账写。
            "agent/CompanionRejudgeLedger.kt" to setOf("mkdirs", "appendText"),
            // 第三百二十二批：模型小黑板（`产物/.board.md`）。**路径不是模型指的**——
            // 落点由 `MemoryLayout.productsDir(工作区根)` 定死，文件名是常量 `.board.md`，
            // 工具参数只有内容（`text`）没有路径 ⇒ 没有"自己挑路径写"的面，因此不需要 PathGuard。
            // mkdirs 只建 `产物/`（工作区根那一层由 App 保证）；writeText 写 `.board.md.tmp` 再改名
            // （原子替换，半条便签比没有更糟）；delete 只删自己那件 tmp/旧文件（改名前那一下）。
            // 点号开头 ⇒ 512 的 `isCountedProduct` 不计入「产物 N 件」；不写任何记忆树/账本。
            "agent/tools/BoardTools.kt" to setOf("mkdirs", "writeText", "delete"),
            // 第六百零九批：小说资产两件的写盘。**路径不是模型指的**——
            // NovelAssets.atomicWrite 的文件由调用方（写两件工具）在 `NovelFence` 围栏内、
            // 从工作区根派生，函数本身只写 `<name>.tmp` 再 rename（原子替换，半截文件比不写更坏）；
            // writeText 只碰那个 tmp、delete 只删自己那件 tmp（rename 失败时），不删别人的文件。
            // NovelTimelineTool 的 mkdirs 只建时间线目录的父层（同样在围栏内），不碰围栏外。
            // 围栏本身在 NovelFence，两个写工具都先过它再调这里 ⇒ 不需要再过一次 PathGuard。
            "agent/tools/NovelAssets.kt" to setOf("writeText", "delete"),
            // 第六百三十二批：轻办公两件的落盘口（docx/xlsx）。**路径不是模型指的**——
            // 落点由 `MemoryLayout.productsDir(工作区根)` 定死，文件名由 [OfficeSink.baseOf]
            // 清洗后加时间戳与序号（同名不覆盖）；工具参数里没有任何路径可填。
            // mkdirs 只建 `产物/`；writeBytes 写 `<名>.tmp` 再 rename（原子，半截 docx 比没有更坏）；
            // delete 只删自己那件 tmp。不写任何记忆树/账本（账走 FileOpLog）。
            "agent/tools/OfficeSink.kt" to setOf("mkdirs", "writeBytes", "delete"),
            "agent/tools/NovelTimelineTool.kt" to setOf("mkdirs"),
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
