// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十三批 · 金吾卫采样的**源级门禁**（判据⑤：采样不含对话正文）。
//
// 这一条运行时验不到——窗口里今天没带正文，可能只是「今天凑巧」。所以从源码层钉两道闸：
//   闸一 [ToolStep] 字段集封死：八个字段一个不多一个不少；String 类型只许
//        callId／tool／argsDigest 三个（想添个承载正文的字段？类型闸先红）；
//        结果码是闭集枚举不是 String（正文借「结果码」混进来也没有门）。
//   闸二 JinwuPatrol.kt 的**代码**里禁出现任何承载正文的话头
//        （transcript／HistoryCodec／DataFence／wrapped／正文／对话）——
//        注释剥离后扫：注释里写「不许带正文」是说明，不是依赖。
//
// 老规矩：扫之前先证明门禁本身会咬人（合成违规必须被抓），免得「全绿」只是没扫到。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JinwuSamplingGateTest {

    private val patrolFile = File("src/main/java/com/example/zhengdao/baopu/inspect/JinwuPatrol.kt")

    /** 代码行（注释剥离）：KDoc 里写「不带正文」是说明，不是依赖（237 批同规矩）。 */
    private fun code(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    /** ToolStep 字段表（名字→类型；data class 声明区域内按行解析，带默认值也认得）。 */
    private fun toolStepFields(src: String): List<Pair<String, String>> =
        src.substringAfter("internal data class ToolStep(").substringBefore(')')
            .split(',')
            .mapNotNull { line ->
                Regex("""val\s+(\w+)\s*:\s*([A-Za-z_?][A-Za-z0-9_?]*)""").find(line.trim())
                    ?.let { it.groupValues[1] to it.groupValues[2] }
            }

    /** 闸二词表：能在代码里出现的承载正文的话头（一个都不许有）。 */
    private val bannedTranscriptTokens = listOf("transcript", "HistoryCodec", "DataFence", "wrapped", "正文", "对话")

    private fun transcriptHits(src: String): List<String> {
        val body = code(src)
        return bannedTranscriptTokens.filter { body.contains(it) }
    }

    // ── 闸一：字段集封死 ─────────────────────────────────────────────────────

    @Test
    fun `字段集_八个一个不多一个不少`() {
        val fields = toolStepFields(patrolFile.readText(Charsets.UTF_8))
        assertEquals(
            setOf("atMs", "callId", "tool", "argsDigest", "resultCode", "planStep", "writeTool", "taskReadOnly"),
            fields.map { it.first }.toSet(),
        )
    }

    @Test
    fun `String字段白名单_只有id名字参数摘要`() {
        val fields = toolStepFields(patrolFile.readText(Charsets.UTF_8))
        // String 类型只许这三个——多一个 String 字段就是多一个装正文的口袋。
        assertEquals(
            setOf("callId", "tool", "argsDigest"),
            fields.filter { it.second == "String" }.map { it.first }.toSet(),
        )
        // 反向也要钉：摘要三件套不许改型（改成泛型容器/自定义类就装得下正文了）。
        val types = fields.toMap()
        listOf("callId", "tool", "argsDigest").forEach { name ->
            assertEquals("$name 必须还是 String", "String", types[name])
        }
    }

    @Test
    fun `结果码是闭集枚举不是String`() {
        val types = toolStepFields(patrolFile.readText(Charsets.UTF_8)).toMap()
        assertEquals("StepResultCode", types["resultCode"])
    }

    // ── 闸二：代码里没有承载正文的话头 ───────────────────────────────────────

    @Test
    fun `源码_注释剥离后无正文话头`() {
        val hits = transcriptHits(patrolFile.readText(Charsets.UTF_8))
        assertTrue("JinwuPatrol.kt 代码里出现正文话头：$hits", hits.isEmpty())
        // 采样行口径当场再证一遍：只拼 tool／argsDigest／结果码，没有第四样。
        val sample = code(patrolFile.readText(Charsets.UTF_8))
            .substringAfter("private fun sampleLines()").substringBefore("private fun")
        assertTrue(sample.contains("s.tool"))
        assertTrue(sample.contains("s.argsDigest"))
        assertTrue(sample.contains("s.resultCode.wire"))
    }

    // ── 门禁自证：合成违规必须被抓，豁免不得误伤 ─────────────────────────────

    @Test
    fun `门禁自证_合成违规行会被抓出来`() {
        // 闸二会咬「transcript」字样的代码行。
        assertTrue(transcriptHits("val transcript: String = \"\"").contains("transcript"))
        assertTrue(transcriptHits("val t: HistoryCodec = x").contains("HistoryCodec"))
        // 闸一会咬「给 ToolStep 添 String 字段」的手——不用改生产文件，合成后过同一套解析。
        val original = patrolFile.readText(Charsets.UTF_8)
        val tampered = original.replace(
            "val taskReadOnly: Boolean = false,",
            "val taskReadOnly: Boolean = false,\n    val transcript: String = \"\",",
        )
        val stringFields = toolStepFields(tampered).filter { it.second == "String" }.map { it.first }.toSet()
        assertTrue("字段闸没咬住新增 String 字段", stringFields != setOf("callId", "tool", "argsDigest"))
    }

    @Test
    fun `门禁自证_注释豁免不误伤`() {
        // 注释里说「对话正文不许带」是说明——剥离后不得误伤。
        assertTrue(transcriptHits("// 采样不含对话正文\nval x = 1").isEmpty())
        assertTrue(transcriptHits("/* 正文在哈希里，不在文案里 */\nval x = 1").isEmpty())
    }

    // ── 出口形状：observe 只收 ToolStep、只出 GapItem ────────────────────────

    @Test
    fun `observe签名_喂一步出差距_产出必为GapItem`() {
        val src = code(patrolFile.readText(Charsets.UTF_8))
        assertTrue(
            "金吾卫的出口签名变了：observe(step: ToolStep): List<GapItem>",
            src.contains("fun observe(step: ToolStep): List<GapItem>"),
        )
        // 四触发各有一条构造 GapItem 的路（判据②产出必为 GapItem——不是自由文本）。
        val triggers = listOf("schemaTrigger", "repeatFailTrigger", "planSkipTrigger", "mismatchTrigger")
        triggers.forEach { name ->
            assertTrue("触发 $name 不在", src.contains("private fun $name"))
        }
        assertEquals("四触发之外不许另开采样路", 4, triggers.count { src.contains("private fun $it") })
    }
}
