// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百八十五批 §2 判据①「factory_c 字节不变（有测试比对 sha256，
// 多次改动后必须不变）」＋「单独存放、任何路径不许写」。
package com.example.zhengdao.baopu

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaFactoryTest {

    private fun factoryFile(): File = PersonaFactory.sourceFile()

    private fun factoryBytes(): ByteArray = factoryFile().readBytes()

    @Test
    fun `判据_出厂人设的字节指纹就是源码里那个常量`() {
        val bytes = factoryBytes()
        assertEquals("字节数变了（改内容必须同时改 BYTE_COUNT 与 SHA256）", PersonaFactory.BYTE_COUNT, bytes.size)
        assertEquals(
            "sha256 变了（改内容必须同时改常量，并说清为什么）",
            PersonaFactory.SHA256,
            PersonaFactory.sha256Hex(bytes),
        )
        assertTrue(PersonaFactory.matches(bytes))
    }

    @Test
    fun `判据_多次改动人设之后出厂文件仍然一字不差`() {
        // 模拟"用户/模型改了很多次"：每次往 active 里写不同的东西
        val root = Files.createTempDirectory("factory-immutable").toFile()
        val files = PersonaFiles(globalRoot = root, projectRoot = root, factoryProjectText = FACTORY)
        (1..5).forEach { n ->
            val cur = files.read(PersonaLayer.C_PROJECT).orEmpty()
            // 只能追加（PersonaRules.appendOnly）：新正文 = 原文 + 末尾新条款
            val p = PersonaRules.propose(PersonaLayer.C_PROJECT, cur, cur + "\n- 第 $n 条", "测试")!!
            assertTrue("第 $n 次追加应当成功", files.applyProposal(p))
        }
        // 出厂文件是**源码里的 assets 文件**，与运行时目录无关 —— 但这条测试要证明的是
        // "无论 active 怎么变，出厂那份的字节都没动"，所以再比一次。
        assertEquals(PersonaFactory.SHA256, PersonaFactory.sha256Hex(factoryBytes()))
        assertTrue(PersonaFactory.matches(factoryBytes()))
    }

    @Test
    fun `判据_恢复出厂也不写出厂文件`() {
        val root = Files.createTempDirectory("factory-restore").toFile()
        val files = PersonaFiles(globalRoot = root, projectRoot = root, factoryProjectText = FACTORY)
        val before = PersonaFactory.sha256Hex(factoryBytes())
        assertTrue(files.restoreFactory())
        assertEquals("恢复出厂写的是 active，不是出厂文件", before, PersonaFactory.sha256Hex(factoryBytes()))
    }

    /**
     * 判据「任何路径不许写」的源码级断言。
     *
     * 口径（两段，缺一段都能被绕过）：
     * 1. **去掉注释之后**，全仓只有 [PersonaFactory] 提到 `factory_c`——注释里提它是允许的
     *    （说明"出厂层"这件事必须写在代码旁边），但**代码**里多一处，就是第二条路径；
     * 2. [PersonaFactory] 自己**不含任何写 API**（writeText/appendText/writeBytes/delete/
     *    renameTo/createNewFile）。
     */
    @Test
    fun `负例_全仓没有任何第二条路径碰出厂人设`() {
        val main = listOf(File("app/src/main"), File("src/main"), File("../app/src/main"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到 app/src/main")
        fun code(text: String): String = text
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
        val ktHits = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { code(it.readText(Charsets.UTF_8)).contains("factory_c") }
            .map { it.name }
            .toSet()
        assertEquals(
            "去注释之后，只有 PersonaFactory.kt 可以在代码里碰 factory_c，实际：$ktHits",
            setOf("PersonaFactory.kt"),
            ktHits,
        )
        val factorySrc = main.walkTopDown()
            .first { f -> f.isFile && f.name == "PersonaFactory.kt" }
            .readText()
        listOf("writeText", "appendText", "writeBytes", "delete()", "renameTo", "createNewFile").forEach { api ->
            assertFalse("PersonaFactory 里出现了写 API：$api（出厂层必须只读）", factorySrc.contains(api))
        }
    }

    private companion object {
        /** 测试用的出厂正文（**不是**真文件内容：这条不校验真内容，只验语义）。 */
        const val FACTORY = "## 规矩\n- 出厂条文一\n"
    }
}
