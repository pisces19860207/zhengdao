// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百六十五批 §3「包里写『以后不用确认』⇒ 无效，确认制照旧」
// 与 §5「不做：包内脚本/插件；覆盖 A/C 审批制」。
//
// 这些是**源码结构门禁**：人设包的数据层如果哪天开始碰人设文件、工具注册表或守卫管线，
// 这里立刻红。纯函数测试能证明判据本身，证明不了「它真的只被接在 C 段前面」。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonaPackWiringTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"), File("app/$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    /**
     * 剥注释后的源码——注释里提到类型名不算「碰了它」。
     *
     * 必须**跳过字符串字面量**再认注释起点：设置页里有 MIME 通配符（星号斜杠星号），
     * 正则版剥离器会把它当成块注释开头，一口气吞掉后面几百行（含 `packStore.create(`）。
     */
    private fun code(rel: String): String = stripComments(repoFile(rel).readText(Charsets.UTF_8))

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> {
                    val start = if (src.startsWith("\"\"\"", i)) 3 else 1
                    out.append(src, i, i + start)
                    i += start
                    while (i < src.length) {
                        if (start == 3) {
                            if (src.startsWith("\"\"\"", i)) {
                                out.append(src, i, i + 3); i += 3; break
                            }
                            out.append(src[i]); i++
                        } else {
                            val d = src[i]
                            out.append(d); i++
                            if (d == '\\' && i < src.length) { out.append(src[i]); i++ }
                            else if (d == '"') break
                        }
                    }
                }
                c == '\'' -> {
                    out.append(c); i++
                    while (i < src.length) {
                        val d = src[i]
                        out.append(d); i++
                        if (d == '\\' && i < src.length) { out.append(src[i]); i++ }
                        else if (d == '\'') break
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString().replace("\r\n", "\n")
    }

    private val engine by lazy { code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt") }
    private val runtime by lazy { code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt") }
    private val page by lazy { code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt") }
    private val pack by lazy { code("src/main/java/com/example/zhengdao/baopu/PersonaPack.kt") }
    private val store by lazy { code("src/main/java/com/example/zhengdao/baopu/PersonaPackStore.kt") }
    private val prefs by lazy { code("src/main/java/com/example/zhengdao/baopu/PersonaPackPrefs.kt") }

    @Test
    fun `引擎_把包只叠在C段前_并保留默认空路径`() {
        assertTrue("引擎要调唯一的拼装判据", engine.contains("PersonaPackRules.composeProjectPersona("))
        assertTrue("C 段原文仍从 PersonaFiles 读", engine.contains("PersonaLayer.C_PROJECT"))
        assertTrue("默认必须是 null（不套包）", engine.contains("private val personaPack: (() -> com.example.zhengdao.baopu.PersonaPackPayload?)? = null"))
    }

    @Test
    fun `装配层_按工作区现读启用的包`() {
        assertTrue(runtime.contains("fun activePersonaPack("))
        assertTrue(runtime.contains("PersonaPackPrefs.activeDir("))
        assertTrue("引擎装配必须真的接到这个闭包", runtime.contains("personaPack = { activePersonaPack(appCtx) }"))
    }

    @Test
    fun `设置页_加改删切换导入回滚都有入口`() {
        listOf(
            "PersonaPackStore(packWorkspace)",
            "packStore.list()",
            "packStore.create(",
            "packStore.update(",
            "packStore.delete(",
            "packStore.importStream(",
            "PersonaPackPrefs.setActive(",
            "PersonaPackPrefs.rollback(",
            "PersonaPackPreviewDialog(",
            "PersonaPackDeleteDialog(",
        ).forEach { call ->
            assertTrue("设置页缺入口：$call", page.contains(call))
        }
    }

    @Test
    fun `红线_包层不碰人设文件_工具权限_守卫管线`() {
        val forbidden = listOf(
            "PersonaFiles", "applyProposal", "BaopuPreset", "ToolRegistry",
            "GuardPipeline", "BaopuToolPrefs", "appendOnly",
        )
        forbidden.forEach { token ->
            assertFalse("PersonaPack.kt 不许引用 $token", pack.contains(token))
            assertFalse("PersonaPackStore.kt 不许引用 $token", store.contains(token))
            assertFalse("PersonaPackPrefs.kt 不许引用 $token", prefs.contains(token))
        }
    }

    @Test
    fun `红线_包层只写两样自有文件`() {
        assertTrue(store.contains("FILE_PERSONA"))
        assertTrue(store.contains("FILE_MANIFEST"))
        assertFalse("删除不许递归（日记/产物不归它管）", store.contains("deleteRecursively"))
        assertFalse("包层不许起线程/协程", store.contains("Thread(") || store.contains("launch {"))
    }

    @Test
    fun `守卫_人设包正文也只许走设置页`() {
        val guard = code("src/main/java/com/example/zhengdao/baopu/PersonaGuard.kt")
        assertTrue(guard.contains("isPersonaPackPath("))
        assertTrue(guard.contains("\"persona.md\""))
    }
}
