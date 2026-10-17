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

        assertTrue("用例少于 8 条就称不上门禁（现在 ${cases.size} 条）", cases.size >= 8)
        for (c in cases) {
            assertTrue("用例 ${c.id} 的 prompt 为空", c.prompt.isNotBlank())
            assertTrue("用例 ${c.id} 的 maxSteps 不合理：${c.maxSteps}", c.maxSteps in 1..30)
            for (a in c.asserts) {
                assertTrue("用例 ${c.id} 的判据 ${a.kind} 需要 path", a.kind == "final_contains" || a.kind == "steps_leq" || a.path.isNotBlank())
                if (a.kind == "steps_leq") assertTrue("用例 ${c.id} 的 steps_leq 需要 n", a.n > 0)
                if (a.kind == "final_contains" || a.kind == "file_contains") {
                    assertTrue("用例 ${c.id} 的 ${a.kind} 需要 text", a.text.isNotBlank())
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
