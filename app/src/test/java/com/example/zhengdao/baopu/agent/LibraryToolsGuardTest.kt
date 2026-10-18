// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第三十一批源码级守卫（判据 L1/L3/L7/L8 ＋ C 段扫描门槛）。
 *
 * 行为判据在 [LibraryToolsTest]；这里钉「结构上不许犯」的几条：
 * - L1：`library_list` 函数体里一个写盘调用都不许有（行为测试之外的双保险）；
 * - L7：收料执行体不许再调 `writeExternalList`（_待外部转换.json 已停写）；
 * - L8/C2：装配层（BaopuRuntime）不许触发扫描/收料；导入扫描必须先数顶层再决定
 *   是否递归（门槛在位——启动路径不再有「无脑全量」）。
 *
 * 源码断言的口径沿用本仓老纪律：**先剥注释再断言**（否则注释里提一嘴就算过了）。
 */
class LibraryToolsGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    /** 剥注释（只剥整行 // 与块注释，不碰字符串里的 //——`http://` 必须留着）。 */
    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 取 [from] 到 [to] 之间的源码段（找不到 ⇒ 空串，断言自然红）。 */
    private fun segment(text: String, from: String, to: String): String {
        val i = text.indexOf(from)
        if (i < 0) return ""
        val j = text.indexOf(to, i + from.length)
        return if (j < 0) text.substring(i) else text.substring(i, j)
    }

    private val writeTokens = listOf(
        "writeText", "writeBytes", "appendText", "delete(", "deleteRecursively",
        "mkdir", "createNewFile", "renameTo", "atomicWrite", "outputStream", "FileOutputStream",
    )

    @Test
    fun `L1_library_list函数体没有任何写盘调用`() {
        val body = segment(code(TOOLS), "fun list(root:", "fun organizeSpec(")
        assertTrue("没找到 library_list 函数体（源码结构变了？）", body.isNotEmpty())
        writeTokens.forEach { t ->
            assertFalse("library_list 必须纯只读：出现了 $t（L1）", body.contains(t))
        }
    }

    @Test
    fun `L3_organize是CONFIRM且源码里写死`() {
        val src = code(TOOLS)
        assertTrue("library_organize 必须 CONFIRM（L3）", src.contains("permission = ToolPermission.CONFIRM"))
        // 两个工具的 spec 工厂都要在（表里注册的那两行见 ToolRegistry，别处还有守卫测）
        assertTrue(src.contains("fun listSpec("))
        assertTrue(src.contains("fun organizeSpec("))
    }

    @Test
    fun `L7_收料执行体不再写待外部转换清单`() {
        val body = segment(code(IMPORT), "fun execute(", "private fun resolveTarget(")
        assertTrue("没找到 execute 函数体", body.isNotEmpty())
        assertFalse("_待外部转换.json 已停写：execute 里不许再出现 writeExternalList(（L7）", body.contains("writeExternalList("))
        // 待删除清单保留写（UX 有读者：收料完成文案把用户导向该文件）
        assertTrue("待删除清单要保留写（谁在读见报告）", body.contains("writePendingDelete("))
    }

    @Test
    fun `C2_导入扫描先数顶层再决定递归`() {
        val body = segment(code(IMPORT), "fun scan(): ImportScan", "fun card(")
        assertTrue("没找到 scan 函数体", body.isNotEmpty())
        val gate = body.indexOf("countTopLevel")
        val recurse = body.indexOf("scanDeliver")
        assertTrue("扫描门槛不在：scan() 里没有 countTopLevel（C2）", gate >= 0)
        assertTrue("门槛形同虚设：countTopLevel 出现在 scanDeliver 之后（C2）", gate < recurse)
        assertTrue("门槛触发要明说", body.contains("overLimit"))
    }

    @Test
    fun `L8_装配层不许触发扫描或收料`() {
        val runtime = code(RUNTIME)
        listOf("scanTree(", "scanDeliver(", ".execute(", "pipeline.run(").forEach { t ->
            assertFalse("装配层（BaopuRuntime）不许直接触发扫描/收料：出现了 $t（L8）", runtime.contains(t))
        }
    }

    private companion object {
        const val TOOLS = "src/main/java/com/example/zhengdao/baopu/agent/tools/LibraryTools.kt"
        const val IMPORT = "src/main/java/com/example/zhengdao/baopu/library/LibraryImport.kt"
        const val RUNTIME = "src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt"
    }
}
