// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「记忆永不参与三态」的**源码门禁**（第四百三十八批，用户 2026-11-04 判据 3）。
 *
 * 判据原话：**记忆永不参与三态**。本仓的三态结论（右相验收 PASS/FAIL/UNVERIFIABLE、
 * 技能入库闸、漂移三态、判据结论等）是稳定层的「判」——记忆是「提示」，两个世界
 * 不许打通：三态模块里出现记忆类型的一个引用，等于记忆开始参与判定。
 *
 * 为什么按源码钉而不是只按行为钉：行为用例能证明「现在没通」，证明不了「谁也通不了」。
 * 与 [MemoryQualityGateTest] 同一个思路——把红线写成**文本形态的不变量**，谁以后在
 * 三态模块里 `import` 一句记忆类型，这里立刻红。每条判据都带**变异负例**：
 * 判据本身能被绕过，它就是假的。
 */
class MemoryTriStateGateTest {

    private companion object {
        /**
         * 三态结论模块（出「三态」判词的件；新增三态模块时把它登记进来）：
         * - :policy 宰相判件（MediatorJudge——它的 `Memory` 是立场记忆，与记忆库无关，
         *   门禁的禁词是记忆库的**类型名**，不会误伤）；
         * - app 侧三态结论件（右相验收／技能闸／漂移／判据结论／能力三态）。
         */
        val TRI_STATE_MODULES = listOf(
            "policy/src/main/java/com/example/zhengdao/baopu/MediatorJudge.kt",
            "app/src/main/java/com/example/zhengdao/baopu/ReflectionAutonomy.kt",
            "app/src/main/java/com/example/zhengdao/baopu/SkillAcceptance.kt",
            "app/src/main/java/com/example/zhengdao/baopu/SkillPromote.kt",
            "app/src/main/java/com/example/zhengdao/baopu/SkillContract.kt",
            "app/src/main/java/com/example/zhengdao/baopu/PostAdoption.kt",
            "app/src/main/java/com/example/zhengdao/baopu/DriftCheck.kt",
            "app/src/main/java/com/example/zhengdao/baopu/DecisionLedger.kt",
            "app/src/main/java/com/example/zhengdao/baopu/ModelCapabilityTable.kt",
            "app/src/main/java/com/example/zhengdao/baopu/LearnRead.kt",
            "app/src/main/java/com/example/zhengdao/baopu/LearnContextNote.kt",
        )

        /** 记忆库的类型名（出现一个就算记忆参与了判定；`Memory` 单词不算——宰相立场记忆同名）。 */
        val MEMORY_TYPES = listOf(
            "MemoryEntry", "MemoryStore", "MemoryRules", "MemoryTiers", "MemoryQuality",
            "MemoryLayout", "MemoryBackup", "GlobalMemory", "ProjectMemory", "EntryCodec",
        )
    }

    /** 从 app 模块工作目录回仓库根找文件（与 MemoryQualityGateTest.repoFile 同房型）。 */
    private fun repoFile(rel: String): File {
        for (f in listOf(File(rel), File("app/$rel"), File("../$rel"))) {
            if (f.isFile) return f
        }
        error("找不到源文件：$rel（模块搬家了？门禁跟着改）")
    }

    /** 读源码并剥掉注释（注释里提到记忆类型不算数，只看代码——与 MemoryQualityGateTest 同房型）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 红线：三态模块的代码里不许出现记忆库的任何类型名。 */
    private fun triStateEatsNoMemory(src: String): Boolean =
        MEMORY_TYPES.none { src.contains(it) }

    @Test
    fun `门禁_记忆永不参与三态_三态模块不引记忆类型`() {
        for (rel in TRI_STATE_MODULES) {
            val src = code(rel)
            val hits = MEMORY_TYPES.filter { src.contains(it) }
            assertTrue(
                "三态模块 $rel 引了记忆类型 $hits ——记忆开始参与判定了，踩 438 判据 3 的红线",
                triStateEatsNoMemory(src),
            )
        }
    }

    @Test
    fun `负例_往三态模块里塞一个记忆引用门禁必须红`() {
        val clean = code(TRI_STATE_MODULES.last())
        assertTrue("前提：钉的模块本身是干净的（清单或路径漂了？先修清单）", triStateEatsNoMemory(clean))
        assertFalse(
            "负例：三态模块引用 MemoryTiers ⇒ 门禁必须红",
            triStateEatsNoMemory(clean + "\nprivate val leaked: MemoryTiers? = null\n"),
        )
        assertFalse(
            "负例：引用 MemoryStore ⇒ 门禁必须红",
            triStateEatsNoMemory(clean + "\nprivate fun leak(m: MemoryStore?) = m\n"),
        )
    }

    @Test
    fun `门禁_三态清单里的文件必须真存在_防清单漂移`() {
        for (rel in TRI_STATE_MODULES) {
            assertTrue("三态模块 $rel 不存在了（搬家/改名 ⇒ 门禁清单跟着改，不许静默缩水）", repoFile(rel).isFile)
        }
    }
}
