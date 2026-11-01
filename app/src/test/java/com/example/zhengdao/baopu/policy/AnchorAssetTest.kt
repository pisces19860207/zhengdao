// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十七批 · 锚定集随包出厂：**行为判据**。
// ① 启动加载：快照 50 条且与资产行逐条一致；
// ② 改一字节不改常量 ⇒ 拒；
// ③ 删 signedBy ⇒ 拒，且准入一律 REFUSED（fail closed）；
// ⑤ 塞非法 JSON ⇒ 拒。
// 文件级门禁（指纹对真文件、禁词、-text）在 AnchorAssetGateTest。
package com.example.zhengdao.baopu.policy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnchorAssetTest {

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun assetFile(): File = listOf(
        File("src/main/assets/policy/anchor-set.jsonl"), // 单测工作目录 = app/
        File("app/src/main/assets/policy/anchor-set.jsonl"), // 工作目录 = 仓根
    ).firstOrNull { it.exists() } ?: error("出厂锚定集不在 src/main/assets")

    private fun assetText(): String = assetFile().readText()

    /** 负例改法：把第一行的某个签字字段抹掉再拼回去（内容变了 ⇒ 指纹层与形态层都该拦）。 */
    private fun textWithFirstLineDropped(key: String): String {
        val lines = assetText().lines().toMutableList()
        val o = JSONObject(lines[0])
        o.remove(key)
        lines[0] = o.toString()
        return lines.joinToString("\n")
    }

    /** 负例改法：原始字节翻转一位（不含末尾换行）。 */
    private fun flipOneByte(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 1).toByte() }

    private fun candidate() = CriteriaCandidate(
        id = "C-327",
        intent = "准入对照用草案",
        target = "app/src/main/java/com/example/zhengdao/baopu/eval/EvalFloor.kt#floorOf",
        outputForm = CriteriaOutputForm.CHECK_ITEMS,
        origin = CriteriaOrigin.HUMAN_PROPOSED,
        replay = ReplayProof(moreBlocked = 3, lessBlocked = 0, moreAsked = 0, ranAtMs = 0L),
        verdicts = emptyMap(),
        exemptActions = emptySet(),
    )

    // ── 判据①：启动加载，快照 50 条且逐条一致 ─────────────────────────────

    @Test
    fun `启动加载_快照50条_与资产行逐条一致`() {
        val bytes = assetFile().readBytes()
        val snapshot = AnchorAsset.fromAsset(bytes)
        assertTrue(snapshot.summary(), snapshot.ok)
        assertEquals(AnchorAsset.EXPECTED_COUNT, snapshot.count)

        val cases = snapshot.forAdmission()
        val lines = String(bytes, Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        assertEquals(lines.size, cases.size)
        lines.forEachIndexed { i, line ->
            val o = JSONObject(line)
            val c = cases[i]
            assertEquals("第 ${i + 1} 行 id", o.getString("id"), c.id)
            assertEquals("第 ${i + 1} 行 scene", o.getString("scene"), c.scene)
            assertEquals("第 ${i + 1} 行 oldVerdict", o.getString("oldVerdict"), c.oldVerdict)
            assertEquals("第 ${i + 1} 行 humanVerdict", o.getString("humanVerdict"), c.humanVerdict)
            assertEquals("第 ${i + 1} 行 evidence", o.getString("evidence"), c.evidence)
            assertEquals("第 ${i + 1} 行 screenedBy", o.getString("screenedBy"), c.screenedBy)
            assertEquals("第 ${i + 1} 行 signedBy", o.getString("signedBy"), c.signedBy)
            assertEquals("第 ${i + 1} 行 signedAt", o.getString("signedAt"), c.signedAt)
        }
        assertEquals(AnchorStatus.IN_FORCE, AnchorSet.status(cases))
    }

    // ── 判据②：改一字节不改常量 ⇒ 拒 ────────────────────────────────────

    @Test
    fun `改一字节不改常量_指纹层拒收`() {
        val snapshot = AnchorAsset.fromAsset(flipOneByte(assetFile().readBytes()))
        assertFalse("改一个字节就不是那份锚", snapshot.ok)
        assertTrue(snapshot.summary(), snapshot.refusal!!.contains("sha256"))
        assertTrue("拒收快照的准入集恒空", snapshot.forAdmission().isEmpty())
    }

    @Test
    fun `空资产_拒收`() {
        val snapshot = AnchorAsset.fromAsset(ByteArray(0))
        assertFalse(snapshot.ok)
    }

    // ── 判据③：删 signedBy ⇒ 拒，且准入一律 REFUSED ──────────────────────

    @Test
    fun `删signedBy_形态层拒收_准入一律拒绝`() {
        val tampered = textWithFirstLineDropped("signedBy")
        val snapshot = AnchorAsset.validate(tampered)
        assertFalse(snapshot.ok)
        assertTrue(snapshot.summary(), snapshot.refusal!!.contains("signedBy"))

        val admission = CriteriaEvolution.admit(candidate(), snapshot.forAdmission())
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.ANCHOR_NOT_IN_FORCE, admission.refuseCode)
    }

    @Test
    fun `拒收快照喂准入_一律REFUSED_不许跳过继续跑`() {
        val snapshot = AnchorAsset.Snapshot.refuse("演练：装配失败")
        assertTrue(snapshot.forAdmission().isEmpty())
        val admission = CriteriaEvolution.admit(candidate(), snapshot.forAdmission())
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.ANCHOR_NOT_IN_FORCE, admission.refuseCode)
    }

    // ── 判据⑤：塞非法 JSON ⇒ 拒 ─────────────────────────────────────────

    @Test
    fun `塞非法JSON_形态层拒收`() {
        val tampered = assetText().lineSequence()
            .mapIndexed { i, line -> if (i == 3) "{not-json" else line }
            .joinToString("\n")
        val snapshot = AnchorAsset.validate(tampered)
        assertFalse(snapshot.ok)
        assertTrue(snapshot.summary(), snapshot.refusal!!.contains("合法 JSON"))
    }

    @Test
    fun `缺signedAt_同款拒收`() {
        val snapshot = AnchorAsset.validate(textWithFirstLineDropped("signedAt"))
        assertFalse(snapshot.ok)
        assertTrue(snapshot.summary(), snapshot.refusal!!.contains("signedAt"))
    }

    // ── 第二道闸：有人连常量一起换时，形态与规模必须自己拦住 ────────────────

    @Test
    fun `条数不是50_规模层拒收`() {
        val fewer = assetText().lines().filter { it.isNotBlank() }.take(49).joinToString("\n")
        val snapshot = AnchorAsset.validate(fewer)
        assertFalse(snapshot.ok)
        assertTrue(snapshot.summary(), snapshot.refusal!!.contains("50"))
    }

    @Test
    fun `id重复_拒收`() {
        val lines = assetText().lines().filter { it.isNotBlank() }.toMutableList()
        val o = JSONObject(lines[1])
        o.put("id", JSONObject(lines[0]).getString("id"))
        lines[1] = o.toString()
        val snapshot = AnchorAsset.validate(lines.joinToString("\n"))
        assertFalse(snapshot.ok)
        assertTrue(snapshot.summary(), snapshot.refusal!!.contains("id 重复"))
    }

    // ── 出厂禁词（真账绝不出厂）────────────────────────────────────────────

    @Test
    fun `含历史回灌标记_禁词层拒收_且优先于指纹`() {
        val poisoned = ("{\"id\": \"X0\", \"scene\": \"s\", \"oldVerdict\": \"o\", " +
            "\"humanVerdict\": \"通过\", \"evidence\": \"e\", \"screenedBy\": \"b\", " +
            "\"signedBy\": \"u\", \"signedAt\": \"t\", \"source\": \"history\"}\n")
            .toByteArray()
        val snapshot = AnchorAsset.fromAsset(poisoned)
        assertFalse("禁词层在指纹层之前：哪怕有人顺手转了常量也拦", snapshot.ok)
        assertTrue(snapshot.summary(), snapshot.refusal!!.contains(AnchorAsset.FORBIDDEN_MARKER))
    }
}
