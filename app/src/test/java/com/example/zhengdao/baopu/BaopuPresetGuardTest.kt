// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A 内核不可写门禁（刀 0b · 追加二第 5 条 · 2026-10-16）。
 *
 * 判据 4 原来只停在文字上（「任何代码路径都不能改 BaopuPreset」）——本门禁把它
 * 钉成**可执行的源码断言**，范式照本仓 `bridge/JsEmbedTest`（扫桥源码零 `eval(`）：
 *
 * 1. `baopu/` 包内**没有任何文件**对 `BaopuPreset` 赋值（`BaopuPreset.xxx = …`）；
 * 2. `BaopuPreset.kt` 自身**只暴露 val**：无 `var` 声明、无 setter 形态函数、
 *    无任何文件 I/O（写不了盘才真的改不了）；
 * 3. 负例自证：把「改成 var」「直接赋值」两种旧形态内嵌为回归串，断言判据**必然不过**。
 *
 * 为什么值得单开一个文件：A 内核是用户划的线（「自进化 = 记忆自进化，
 * 明确不自动改 A 内核」）。文字约定会随重构腐化，源码断言不会。
 */
class BaopuPresetGuardTest {

    private fun baopuDir(): File {
        val roots = listOf(
            File("app/src/main/java/com/example/zhengdao/baopu"),
            File("src/main/java/com/example/zhengdao/baopu"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("找不到 baopu 包目录（cwd=${File("").absolutePath}）")
    }

    private fun presetSource(): String {
        val f = File(baopuDir(), "BaopuPreset.kt")
        assertTrue("找不到 BaopuPreset.kt：${f.path}", f.isFile)
        return f.readText()
    }

    /** 去注释（先剥再断言，否则 KDoc 里提一嘴就算自己拦自己）。 */
    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    /** 包内全部 Kotlin 源（含 BaopuPreset.kt 自身）。 */
    private fun packageSources(): List<Pair<String, String>> =
        baopuDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to stripped(it.readText()) }
            .toList()

    // ── 门禁本体 ──────────────────────────────────────────────────────────

    @Test
    fun `门禁一_包内无任何文件给 BaopuPreset 赋值`() {
        val offenders = packageSources()
            .filter { (_, code) -> Regex("""BaopuPreset\.\w+\s*=(?!=)""").containsMatchIn(code) }
            .map { it.first }
        assertTrue(
            "以下文件对 BaopuPreset 赋值（A 内核只读，判据 4）：${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `门禁二_BaopuPreset 只暴露 val_无 var 无 setter`() {
        val code = stripped(presetSource())
        assertFalse(
            "BaopuPreset 里出现 var 声明（只暴露 val）",
            Regex("""\bvar\s+\w+""").containsMatchIn(code),
        )
        assertFalse(
            "BaopuPreset 里出现 setter 形态函数",
            Regex("""fun\s+set\w*""").containsMatchIn(code),
        )
        assertFalse(
            "BaopuPreset 里出现写盘形态（改不了盘才真的改不了）",
            Regex("""writeText|\.delete\(|Files\.(write|copy|move|delete)""").containsMatchIn(code),
        )
        // 正向：它确实还是那个只读 object，且 personaText 还在（防被整片删掉变成空门禁）
        assertTrue(code.contains("internal object BaopuPreset"))
        assertTrue(code.contains("fun personaText("))
        assertTrue(code.contains("const val VERSION"))
    }

    @Test
    fun `门禁三_包内没有别的文件调 BaopuPreset 的写路径`() {
        // 目前 BaopuPreset 只有读接口；此门禁防的是"给它加个写方法然后被人调"——
        // 一旦出现写形态方法名（write/update/delete/set/apply/overwrite），立刻红。
        val code = stripped(presetSource())
        val writeish = Regex("""fun\s+(write|update|delete|remove|set|apply|overwrite|rewrite)\w*\s*\(""")
        assertFalse(
            "BaopuPreset 出现了写形态方法（A 内核不可写）：${writeish.find(code)?.value}",
            writeish.containsMatchIn(code),
        )
    }

    // ── 负例自证（旧形态 ⇒ 判据必然不过） ─────────────────────────────────

    /** 旧形态一：把 VERSION 改成 var（"顺手让它可变"）。 */
    private val oldVarForm = "internal object BaopuPreset {\n    var VERSION = 1\n}"

    /** 旧形态二：别处直接赋值（"改个版本号而已"）。 */
    private val oldAssignForm = "fun bump() {\n    BaopuPreset.VERSION = 2\n}"

    /** 旧形态三：给它加个写盘方法（"把新文案写进去"）。 */
    private val oldWriteForm =
        "internal object BaopuPreset {\n    fun writePersona(text: String) {\n        File(\"x\").writeText(text)\n    }\n}"

    @Test
    fun `负例一_改成 var 必须不过门禁二`() {
        val code = stripped(oldVarForm)
        assertTrue(Regex("""\bvar\s+\w+""").containsMatchIn(code))
    }

    @Test
    fun `负例二_直接赋值必须不过门禁一`() {
        assertTrue(Regex("""BaopuPreset\.\w+\s*=(?!=)""").containsMatchIn(stripped(oldAssignForm)))
    }

    @Test
    fun `负例三_加写盘方法必须不过门禁二三`() {
        val code = stripped(oldWriteForm)
        assertTrue(Regex("""writeText""").containsMatchIn(code))
        assertTrue(Regex("""fun\s+(write|update|delete|remove|set|apply|overwrite|rewrite)\w*\s*\(""").containsMatchIn(code))
    }

    @Test
    fun `正向_PersonaRules 对 A 内核一律拒绝提案`() {
        assertFalse(PersonaRules.canWrite(PersonaLayer.A_KERNEL))
        assertTrue(PersonaRules.canWrite(PersonaLayer.B_GLOBAL))
        assertTrue(PersonaRules.canWrite(PersonaLayer.C_PROJECT))
        assertTrue(
            "A 内核的提案必须直接被拒（返回 null）",
            PersonaRules.propose(PersonaLayer.A_KERNEL, "旧", "新", "理由") == null,
        )
        assertTrue(
            "B 层提案可以生成",
            PersonaRules.propose(PersonaLayer.B_GLOBAL, "旧", "新", "理由") != null,
        )
    }
}
