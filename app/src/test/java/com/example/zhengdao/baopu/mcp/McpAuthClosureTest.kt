// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十六批 · 「215 差距账逐项结账」的门禁。
//
// 三条判据（少一条就等于"结账单可以随便写"）：
// ① 覆盖与差距账**逐项一致**：topic 集合与顺序都对得上（不增不减）；
// ② 每条实现点指向的文件必须存在、且里面真有那个符号；
// ③ 每条证明指向的用例名必须真出现在测试源码里。
package com.example.zhengdao.baopu.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpAuthClosureTest {

    private val mainRoot = File("src/main/java/com/example/zhengdao")
    private val testRoot = File("src/test/java/com/example/zhengdao")

    @Test
    fun `覆盖与215差距账逐项一致`() {
        assertEquals(
            "结账单必须与差距账逐项对应（顺序也一致）",
            McpAuthGap.ITEMS.map { it.topic },
            McpAuthClosure.ITEMS.map { it.topic },
        )
        McpAuthGap.REQUIRED_TOPICS.forEach { required ->
            assertTrue("派工单点名的「$required」没有结账", McpAuthClosure.of(required) != null)
        }
    }

    @Test
    fun `每条实现点都真实存在`() {
        McpAuthClosure.ITEMS.forEach { item ->
            assertTrue("「${item.topic}」没写实现点", item.impl.isNotEmpty())
            item.impl.forEach { ref ->
                val path = ref.substringBefore("::")
                val symbol = ref.substringAfter("::", "")
                val file = File(mainRoot, path)
                assertTrue("「${item.topic}」指向的文件不存在：$path", file.isFile)
                if (symbol.isNotEmpty()) {
                    assertTrue(
                        "「${item.topic}」说 $path 里有 $symbol，实际找不到",
                        file.readText().contains(symbol),
                    )
                }
            }
        }
    }

    @Test
    fun `每条证明都真有对应用例`() {
        val testText = testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        McpAuthClosure.ITEMS.forEach { item ->
            assertTrue("「${item.topic}」没写证明", item.proofs.isNotEmpty())
            item.proofs.forEach { proof ->
                assertTrue("「${item.topic}」声称有用例「$proof」，测试源码里没有", testText.contains(proof))
            }
        }
    }

    @Test
    fun `每条都写了口径或遗留风险`() {
        McpAuthClosure.ITEMS.forEach { item ->
            assertTrue("「${item.topic}」的 note 不能空（要写清怎么做的/有什么代价）", item.note.length > 10)
        }
    }
}
