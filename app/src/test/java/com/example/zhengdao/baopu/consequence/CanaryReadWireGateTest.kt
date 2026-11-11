// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十八批（2026-11-11）·「读档用的线值必须在注册表内」的源级门禁。
//
// 病：BaopuRuntime 拿线值 "evolution" 读 currentCanaryBand，而 506 的
//     CanaryCapability 闭集里没有它 ⇒ 读侧认不出、恒回落 OFF；写侧
//     CanaryLadder.refusalOf 又按「没登记在册」把它的档位行全拒 ⇒
//     这格读数从来没有真值可读（代码在、功能没跑过）。
// 闸：从 BaopuRuntime.kt 磁盘源码解析出读档点实际用的线值，逐个问
//     CanaryRegistry.knows；不在册、或解析不出来（fail-closed）⇒ 红。
//     判据不写成「文件含某字面量」那种恒真式——负例真的造了未登记线值、
//     硬编码线值、删声明三种脏源码，逐个验门禁会红。
// 验收：补登记后，无账读到登记表缺省档（不再是 OFF 兜底）；真落一条
//     FULL 档位行后能读回 FULL ⇒ canaryAvailable 不再恒 false。
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRollbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CanaryReadWireGateTest {

    private val now = 1_760_000_000_000L
    private val ghost = "ghost_capability_588"
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    private val runtimeRel = "src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt"

    /** 声明点的字面量（解析不出来就是 null，门禁按 fail-closed 处理）。 */
    private val declaration = Regex(
        """private\s+const\s+val\s+CANARY_CAPABILITY\s*:\s*String\s*=\s*"([^"]+)"""",
    )

    /** 读档点：实参是常量名，或直接硬编码的字面量。 */
    private val readCall = Regex(
        """currentCanaryBand\(\s*([A-Za-z_][A-Za-z0-9_]*|"[^"]+")\s*\)""",
    )

    private lateinit var files: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        files = File.createTempFile("zd588-wire", "").let { it.delete(); it.mkdirs(); it }
        memory = File(files, "抱朴记忆")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(files, now) { entropy }
    }

    @After
    fun tearDown() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        DecisionRollbacks.handler = null
        files.deleteRecursively()
    }

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 从磁盘读真源码（不拿本次 render 的内存串自证）。 */
    private fun runtimeSrc(): String = repoFile(runtimeRel).readText().replace("\r\n", "\n")

    /**
     * 解析「读档用的线值」：常量名 ⇒ 用声明点字面量；硬编码字面量 ⇒ 直接取；
     * 别的表达式／声明不在 ⇒ null（门禁不许静默放行）。
     */
    private fun readWires(src: String): List<String>? {
        val declared = declaration.find(src)?.groupValues?.get(1) ?: return null
        val calls = readCall.findAll(src).toList()
        if (calls.isEmpty()) return null
        return calls.map { m ->
            val arg = m.groupValues[1]
            when {
                arg.startsWith("\"") -> arg.trim('"')
                arg == "CANARY_CAPABILITY" -> declared
                else -> return null
            }
        }
    }

    /** 门禁本体：读档线值必须全部在注册表内；解析不出也算红。 */
    private fun gate(src: String): Boolean {
        val wires = readWires(src) ?: return false
        return wires.isNotEmpty() && wires.all { CanaryRegistry.knows(it) }
    }

    /** 把声明点的线值换成 [wire]（只改声明行，不碰别处）。 */
    private fun withDeclaredWire(src: String, wire: String): String =
        declaration.replace(src) { m -> m.value.replace(m.groupValues[1], wire) }

    // ── 一、门禁正例 ──────────────────────────────────────────────────────

    @Test
    fun `判据1_读档用的线值必须在注册表内`() {
        val src = runtimeSrc()
        assertTrue("读档线值不在 CanaryCapability 闭集里 ⇒ 读档恒 OFF", gate(src))
        val wires = readWires(src)
        assertNotNull("读档点与声明点必须都解析得出来", wires)
        assertEquals("本仓只有一处按线值读档", 1, wires!!.size)
        val wire = wires.single()
        assertNotNull("线值认不出 ⇒ 恒回落 OFF 兜底", CanaryCapability.ofWire(wire))
        assertTrue("登记表必须认得它", CanaryRegistry.knows(wire))
    }

    // ── 二、负例：造未登记线值 ⇒ 门禁红 ──────────────────────────────────

    @Test
    fun `判据2_负例_造未登记线值_门禁红`() {
        assertFalse("负例材料本身必须真的没登记", CanaryRegistry.knows(ghost))
        val mutated = withDeclaredWire(runtimeSrc(), ghost)
        assertEquals("变异必须真的换掉声明点的线值", ghost, readWires(mutated)!!.single())
        assertFalse("未登记线值 ⇒ 门禁必须红", gate(mutated))
    }

    @Test
    fun `判据2b_负例_读档点硬编码未登记线值_门禁红`() {
        val src = runtimeSrc()
        val mutated = src.replace("currentCanaryBand(CANARY_CAPABILITY)", "currentCanaryBand(\"$ghost\")")
        assertFalse("变异必须真的发生（不能是恒真式）", mutated == src)
        assertFalse("绕过常量硬编码未登记线值也要红", gate(mutated))
    }

    @Test
    fun `判据2c_负例_声明点被删_门禁fail_closed`() {
        val src = runtimeSrc()
        val mutated = declaration.replace(src, "")
        assertNull("声明没了就解析不出线值", readWires(mutated))
        assertFalse("解析不出不许静默放行", gate(mutated))
    }

    // ── 三、修完读数能被读到（不再恒 OFF）────────────────────────────────

    @Test
    fun `判据3_无账时读到登记表缺省档_不是OFF兜底`() {
        val wire = readWires(runtimeSrc())!!.single()
        val expected = CanaryCapability.ofWire(wire)!!.defaultBand
        assertEquals(
            "缺省档要从登记表来，不是「认不出 ⇒ OFF」",
            expected,
            ConsequenceLedger.currentCanaryBand(emptyList(), wire),
        )
        assertNotEquals("缺省档不许是 OFF 兜底（否则等于没修）", CanaryBand.OFF, expected)
    }

    @Test
    fun `判据4_真跑_落FULL档位行能读回FULL`() {
        val wire = readWires(runtimeSrc())!!.single()
        repeat(CanaryLadder.MIN_RUNS) { i ->
            ConsequenceWiring.observe(CanaryObservation(wire, ok = true), now + i)
        }
        assertEquals(
            "达标两轮 ⇒ 写侧必须真的落一条 FULL 档位行",
            CanaryBand.FULL,
            ConsequenceWiring.decideCanaryBand(wire, now + 100, passedRounds = CanaryLadder.PASS_ROUNDS_FOR_FULL),
        )
        assertEquals("读数能被读到（不再恒 OFF）", CanaryBand.FULL, ConsequenceWiring.currentCanaryBand(wire))
    }

    @Test
    fun `判据4b_负例_未登记线值的档位行写侧一律被拒_这就是恒OFF的根因`() {
        val good = CanaryMetrics.of(
            (0 until CanaryLadder.MIN_RUNS).map { i ->
                CanarySample(atMs = now - i * 1000L, ok = true, askedHuman = false)
            },
            now,
        )
        val change = CanaryChange(
            ghost, CanaryBand.CANARY.wire, CanaryBand.FULL.wire,
            "auto", now, good, CanaryLadder.TRIGGER_AUTO_PROMOTE,
        )
        val refusal = CanaryLadder.refusalOf(change)
        assertNotNull("未登记能力转档必须被拒", refusal)
        assertTrue("拒因必须是「没登记在册」", refusal!!.contains("没登记在册"))
        assertNull("被拒 ⇒ 档位行落不了盘 ⇒ 读侧永远只有 OFF", ConsequenceLedger.draftOf(change))
        assertEquals("没档位行 ⇒ 恒 OFF", CanaryBand.OFF, ConsequenceLedger.currentCanaryBand(emptyList(), ghost))
    }
}
