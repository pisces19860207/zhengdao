// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第四百一十三批 —— **「目录 ↔ 标签表不许漂移」**。
//
// 这一套只钉一件事：`ToolGroupTable.switchableNames()`（可开关工具目录，catalog 表序 ＋
// 运行时注册两条）里的**每一个名字**，`BaopuToolLabels` 都必须给出标签与副标题，而且这两个
// 键在中英两份 strings 里都真的存在。
//
// 为什么这件事要有门禁、而不是靠「记得补一行」：413 之前是两张手写 `when`，加工具忘了补
// 一行，回归网是「每个批次各自在测试里 `src.contains("\"x\" -> R.string.y")` 加一条字面量
// 断言」——忘加断言就没有网，而 141 批把逐工具开关换成组摘要卡之后，那两个函数在界面上
// 已经没有调用点，漏了甚至不会有人看见。现在覆盖性由目录派生：**多一个、少一个都红，且
// 失败信息点名缺哪个工具**。
//
// 判据与派工单的对应：
//   ① 每个目录名字都有标签          → `目录里每个工具都有标签与副标题`
//   ② 漏一个 ⇒ 测试红并指名          → 同一条（失败信息带 missing 列表）＋ `只加 catalog 的新工具必须被抓出来`
//   ③ 目录外多一个也红（双向不漂移）  → `标签表里不许有目录外的条目`
//   ⑤ 不动 UI                        → 本套不碰任何布局；页面仍是组开关（141 批裁定）
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.ToolGroupTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BaopuToolLabelCoverageTest {

    /** 唯一的目录源（与 `checkCoverage()` 同一个集合：ENTRIES ＋ 运行时注册两条）。 */
    private val catalog: List<String> = ToolGroupTable.switchableNames()

    @Test
    fun `目录不是空的_否则下面几条全是空转`() {
        // 防空转：目录为 0 时「缺 0 个」恒真，必须先把底数钉住。
        assertTrue("可开关工具目录为空，后续覆盖断言失去意义", catalog.size >= 60)
        assertEquals("目录里不该有重名（重复会掩盖漏项）", catalog.size, catalog.toSet().size)
        assertTrue("write_skill 必须在目录里（运行时注册的两条之一）", "write_skill" in catalog)
        assertTrue("append_persona 必须在目录里", "append_persona" in catalog)
    }

    @Test
    fun `目录里每个工具都有标签与副标题`() {
        val missLabel = BaopuToolLabels.missingLabels(catalog)
        val missDesc = BaopuToolLabels.missingDescs(catalog)
        assertEquals(
            "这些工具在 catalog 里、但没有开关标签（补 BaopuToolLabels.LABEL 一行）：$missLabel",
            emptyList<String>(),
            missLabel,
        )
        assertEquals(
            "这些工具在 catalog 里、但没有开关副标题（补 BaopuToolLabels.DESC 一行）：$missDesc",
            emptyList<String>(),
            missDesc,
        )
        // 取到的必须是真资源 id（0 表示 R 表里根本没这把键，编译期不会报，运行期显示空白）。
        catalog.forEach { name ->
            assertTrue("「$name」的标签 id 为 0（R.string 里没有这把键）", BaopuToolLabels.label(name) != 0)
            assertTrue("「$name」的副标题 id 为 0", BaopuToolLabels.desc(name) != 0)
        }
    }

    @Test
    fun `标签表里不许有目录外的条目`() {
        // 反向漂移：工具删了、文案还留着。症状是设置页「有个永远显示不出来的名字」，
        // 而正向断言（目录 → 表）完全发现不了它 ⇒ 必须单独一条。
        assertEquals("LABEL 里有目录外的名字（工具可能已删）：${BaopuToolLabels.staleLabels(catalog)}",
            emptyList<String>(), BaopuToolLabels.staleLabels(catalog))
        assertEquals("DESC 里有目录外的名字（工具可能已删）：${BaopuToolLabels.staleDescs(catalog)}",
            emptyList<String>(), BaopuToolLabels.staleDescs(catalog))
        // 两张表必须同键集：只有标签没有副标题，界面上就是「标题在、解释是空的」。
        assertEquals("LABEL 与 DESC 的键集必须完全一致",
            BaopuToolLabels.LABEL.keys.sorted(), BaopuToolLabels.DESC.keys.sorted())
        assertEquals("表条目数必须等于目录条数（多一个漏一个都算漂移）",
            catalog.size, BaopuToolLabels.LABEL.size)
    }

    @Test
    fun `只加 catalog 的新工具必须被抓出来_并指名`() {
        // 判据「加一个新工具（只加 catalog）⇒ 门禁红并指名缺哪个标签」的**可复现替身**：
        // 真去改 ENTRIES 会把别人的表搅乱，这里直接把「目录 ＋ 一个假名字」喂给同一个函数
        // —— 所以这条同时证明门禁不是恒真、且报错信息真的会点出那个名字。
        val withNew = catalog + "brand_new_tool_413"
        assertEquals(
            "新工具没补标签时必须被抓出来",
            listOf("brand_new_tool_413"),
            BaopuToolLabels.missingLabels(withNew),
        )
        assertEquals(
            "新工具没补副标题时必须被抓出来",
            listOf("brand_new_tool_413"),
            BaopuToolLabels.missingDescs(withNew),
        )
        // 目录为空时不应有任何名字被判缺（反向证明 filter 语义没写反）；
        // 目录原样喂进去仍为空（证明「补上就过」不是靠放宽判据换来的）。
        assertEquals(emptyList<String>(), BaopuToolLabels.missingLabels(emptyList()))
        assertEquals(emptyList<String>(), BaopuToolLabels.missingLabels(catalog))
    }

    @Test
    fun `取不到就抛_不是静默给空`() {
        // 设置页若真调到这两个函数，漏了必须当场炸（fail-fast），不许返回 0 显示成空白。
        try {
            BaopuToolLabels.label("__no_such_tool__")
            fail("取不到标签却返回了值：静默兜底会把「漏标签」藏到界面里")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息要点名是哪个工具：${e.message}", e.message!!.contains("__no_such_tool__"))
        }
        try {
            BaopuToolLabels.desc("__no_such_tool__")
            fail("取不到副标题却返回了值")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息要点名是哪个工具：${e.message}", e.message!!.contains("__no_such_tool__"))
        }
    }

    @Test
    fun `中英两份strings都真的有这两把键`() {
        // 键名规则在 413 之前的 75 条里 100% 成立（机械核过）：baopu_tool_<name> / _desc。
        // 所以这里用规则生成期望键名，扫 res/values* 下**全部** xml（logistics 的键不在主表里）。
        val zh = stringKeys("src/main/res/values")
        val en = stringKeys("src/main/res/values-en")
        assertTrue("中文键集合为空，说明路径没找对（防空跑）", zh.size > 100)
        assertTrue("英文键集合为空", en.size > 100)

        val missing = mutableListOf<String>()
        catalog.forEach { name ->
            listOf("baopu_tool_$name", "baopu_tool_${name}_desc").forEach { key ->
                if (key !in zh) missing += "zh:$key"
                if (key !in en) missing += "en:$key"
            }
        }
        assertEquals("这些字串键在该语言里不存在（漏了会显示空白）：$missing", emptyList<String>(), missing)
    }

    /** 某个 values 目录下全部 xml 里的 `name="..."` 集合。 */
    private fun stringKeys(dir: String): Set<String> {
        val d = repoFile(dir)
        assertTrue("找不到资源目录：${d.absolutePath}", d.isDirectory)
        val xmls = d.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.toList().orEmpty()
        assertTrue("$dir 下没有 xml", xmls.isNotEmpty())
        val re = Regex("""name="([a-zA-Z0-9_]+)"""")
        return xmls.flatMap { f -> re.findAll(f.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }
}
