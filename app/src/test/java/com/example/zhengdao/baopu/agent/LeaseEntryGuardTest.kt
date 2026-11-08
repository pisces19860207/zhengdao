// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · **入口净增 0** 的源级门禁（这类判据只能靠"谁碰了租约"这张账单钉住）：
//   · 生产代码里提到租约的文件**必须**就是那 9 个（4 新 + 5 改），一个也不许多；
//   · 设置页/偏好/提示词/工具目录里**一个提都不许有**（不加设置项、不加工具、不进前缀）；
//   · 建议层不许持 Store（自进化只能出建议，不能自己授租约）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaseEntryGuardTest {

    private val mainJava = File("src/main/java")

    /** 允许碰租约的**全部**文件（相对 `src/main/java/`，斜杠归一）：5 新 ＋ 5 改。 */
    private val allowed = setOf(
        "com/example/zhengdao/baopu/authz/LeaseRules.kt",
        "com/example/zhengdao/baopu/authz/LeaseStore.kt",
        "com/example/zhengdao/baopu/authz/LeaseLedger.kt",
        "com/example/zhengdao/baopu/authz/LeaseAdvice.kt",
        "com/example/zhengdao/baopu/authz/LeaseGate.kt",
        "com/example/zhengdao/baopu/agent/GuardPipeline.kt",
        "com/example/zhengdao/baopu/agent/ConfirmFlow.kt",
        "com/example/zhengdao/baopu/agent/RunEngine.kt",
        // 499 的硬线布尔式抽成 AskHardLines（行为逐字节不变）：租约借它，不复制第二份
        "com/example/zhengdao/baopu/agent/AskGovernance.kt",
        "com/example/zhengdao/ui/baopu/BaopuConfirmCard.kt",
    )

    /** 只认本批的**标识符**：仓里"租约"一词另有旧含义（派工单的文件租约），不能按中文扫。 */
    private val marker = Regex(
        "Lease(Store|Rules|Gate|Ledger|Offer|Advice|Limits|Scope|Grant|Verdict|Facts|Kinds)|leaseOffer",
    )

    private fun rel(f: File): String = f.relativeTo(mainJava).invariantSeparatorsPath

    private fun mentioned(): Set<String> =
        mainJava.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> marker.containsMatchIn(f.readText(Charsets.UTF_8)) }
            .map(::rel)
            .toSet()

    @Test
    fun `入口净增0_碰租约的文件只有那十个`() {
        val actual = mentioned()
        assertEquals("多一个文件就是多一个入口（或不许碰的地方碰了）", allowed, actual)
    }

    @Test
    fun `设置页与偏好里一个提都不许有`() {
        val offenders = mentioned().filter {
            it.contains("Settings", ignoreCase = true) ||
                it.contains("Prefs", ignoreCase = true) ||
                it.contains("Preferences", ignoreCase = true)
        }
        assertTrue("租约不许有设置项：$offenders", offenders.isEmpty())
    }

    @Test
    fun `C层零引用_提示词与上下文组装里没有租约`() {
        val offenders = mentioned().filter {
            it.contains("Preset", ignoreCase = true) ||
                it.contains("ContextAssembler", ignoreCase = true) ||
                it.contains("HistoryCodec", ignoreCase = true) ||
                it.contains("SystemPrompt", ignoreCase = true)
        }
        assertTrue("租约不进前缀：$offenders", offenders.isEmpty())
    }

    @Test
    fun `工具目录里没有租约工具`() {
        val hits = BaopuToolCatalog.ENTRIES.filter { it.name.contains("lease", ignoreCase = true) }
        assertTrue("不许新增工具：$hits", hits.isEmpty())
    }

    @Test
    fun `建议层不持 Store_不调 grant`() {
        val src = File(mainJava, "com/example/zhengdao/baopu/authz/LeaseAdvice.kt").readText(Charsets.UTF_8)
        val body = src.substringAfter("internal object LeaseAdviceRules")
        assertTrue("LeaseAdvice 不许引用 LeaseStore", !body.contains("LeaseStore"))
        assertTrue("LeaseAdvice 不许调 grant", !body.contains(".grant("))
    }

    @Test
    fun `卡面文案中英各一条_不许只加一半`() {
        val zh = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val en = File("src/main/res/values-en/strings.xml").readText(Charsets.UTF_8)
        assertTrue(zh.contains("name=\"baopu_confirm_lease_line\""))
        assertTrue(en.contains("name=\"baopu_confirm_lease_line\""))
    }
}
