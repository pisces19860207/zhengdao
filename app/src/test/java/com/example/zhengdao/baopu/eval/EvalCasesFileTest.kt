package com.example.zhengdao.baopu.eval

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 评测集本身的体检（**不需要 key、不花钱**）。
 *
 * 为什么要有它：门禁的价值全押在用例上。用例写错（判据种类拼错、路径撒不出来、
 * id 重复）时，门禁会安静地少判几条——那比红更糟。所以先把"用例本身合法"钉死。
 */
class EvalCasesFileTest {

    @Test
    fun `评测集可解析_用例够用_判据齐全`() {
        val file = locate()
        val cases = EvalCases.load(file)
        // 真工具名册（第九十批）：`tool_not_called` 写的工具名必须在里面。
        // 拼错的名字等于那条判据**永远绿**——谁都没禁住，比红更危险。
        // 取注册表那条唯一的加载口读名字，不在测试里抄第二遍名单（抄的那份会跟线上脱钩）。
        val toolNames = run {
            val reg = com.example.zhengdao.baopu.agent.ToolRegistry(
                com.example.zhengdao.baopu.agent.InMemoryToolPrefs(),
            )
            com.example.zhengdao.baopu.agent.BaopuToolCatalog.loadInto(reg) { File(".") }
            reg.names().toSet()
        }
        assertTrue("工具名册读出来是空的：那 tool_not_called 的核对等于没做", toolNames.isNotEmpty())

        assertTrue("用例少于 8 条就称不上门禁（现在 ${cases.size} 条）", cases.size >= 8)
        for (c in cases) {
            assertTrue("用例 ${c.id} 的 prompt 为空", c.prompt.isNotBlank())
            assertTrue("用例 ${c.id} 的 maxSteps 不合理：${c.maxSteps}", c.maxSteps in 1..30)
            for (a in c.asserts) {
                // 不需要 path 的判据（第九十批起有三个）：终答文本类、步数类、工具名类。
                val pathFree = a.kind == "final_contains" || a.kind == "steps_leq" || a.kind == "tool_not_called"
                assertTrue("用例 ${c.id} 的判据 ${a.kind} 需要 path", pathFree || a.path.isNotBlank())
                if (a.kind == "steps_leq") assertTrue("用例 ${c.id} 的 steps_leq 需要 n", a.n > 0)
                if (a.kind == "final_contains" || a.kind == "file_contains" || a.kind == "tool_not_called") {
                    assertTrue("用例 ${c.id} 的 ${a.kind} 需要 text", a.text.isNotBlank())
                }
                if (a.kind == "tool_not_called") {
                    assertTrue(
                        "用例 ${c.id} 的 tool_not_called 写了不存在的工具「${a.text}」",
                        a.text in toolNames,
                    )
                }
                if (a.kind == "unchanged") {
                    assertTrue(
                        "用例 ${c.id} 的 unchanged 指向 ${a.path}，但 files 里没撒这份种子",
                        c.files.any { it.path == a.path },
                    )
                }
            }
        }
        val ids = cases.map { it.id }.toSet()
        assertTrue("用例 id 有重复", ids.size == cases.size)
        // 第九十批：covers 只许 B/C 层用例写（parse 管未知值，这里管「谁该写」），
        // 且 fixture 必须写明是哪一层——否则「B/C 覆盖了」这句话没有凭据。
        for (c in cases.filter { it.usesLayerFixture }) {
            assertTrue("fixture 层用例 ${c.id} 没写 covers：覆盖门禁数不到它", c.covers.isNotBlank())
            assertTrue(
                "fixture 层用例 ${c.id} 的 fixture 没写明是哪一层（连「人设」字样都没有＝不知在测什么）",
                c.personaText.contains("人设"),
            )
        }
    }

    private fun locate(): File {
        var d: File? = File(".").absoluteFile
        repeat(6) {
            if (d != null && File(d, "eval/cases.json").isFile) return File(d, "eval/cases.json")
            d = d?.parentFile
        }
        throw AssertionError("找工作区根失败：从 ${File(".").absolutePath} 向上六层都没有 eval/cases.json")
    }
}
