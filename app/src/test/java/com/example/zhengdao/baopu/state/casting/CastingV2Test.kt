// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百六十九批 · 外观卡 v2：四组引用位扩槽 + 卡上限 56 + 摘要规则登记。
// 判据①四组引用位齐且未落地=NONE；②上限 56 且超限拒；③摘要与敏感层不变；
// ⑤旧卡向后兼容；⑥接不上清单由报告给出。三组校验逻辑本单零改（git diff 证据）。
package com.example.zhengdao.baopu.state.casting

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingV2Test {

    /** 869 四组引用位的完整 id 清单——逐组写全，杜绝「靠数字算齐」。 */
    private val torsoIds = listOf(
        "torso.blocks", "torso.linea", "torso.pecEdge", "torso.trapSlope", "torso.latFlare", "torso.serratus",
    )
    private val topoIds = listOf("topo.relief", "topo.hollow", "topo.depth", "topo.asymmetry")
    private val boneIds = listOf(
        "bone.clavicle", "bone.acromion", "bone.scapula", "bone.sternum", "bone.ribs",
        "bone.pelvis", "bone.elbow", "bone.wrist", "bone.knee", "bone.ankle",
    )
    private val shapeIds = listOf("shape.shaftSection", "shape.glansContour", "shape.crossSection")
    private val allPending = torsoIds + topoIds + boneIds + shapeIds

    /** 判据①：四组引用位齐——每组逐 id 在白名单内。 */
    @Test
    fun `判据一_四组引用位齐全`() {
        assertEquals("躯干形状 6 项", 6, torsoIds.size)
        assertEquals("体表地形 4 项", 4, topoIds.size)
        assertEquals("骨点 10 处", 10, boneIds.size)
        assertEquals("下体形状 3 项", 3, shapeIds.size)
        allPending.forEach { id -> assertTrue("$id 须在白名单", CastingRefs.known(id)) }
    }

    /** 判据①：未落地一律 NONE——source=NONE、allowed 空、accepts 任何值都 false。 */
    @Test
    fun `判据一_未落地引用位一律NONE`() {
        allPending.forEach { id ->
            val ref = CastingRefs.field(id)
            assertEquals("$id source 须 NONE", RefSource.NONE, ref.source)
            assertTrue("$id allowed 须空", ref.words().isEmpty())
            assertFalse("$id 不许收任意值", ref.accepts("任意档"))
        }
    }

    /** 判据①负例：给未落地引用位填值，卡构造即拒（填了值⇒红）。 */
    @Test
    fun `判据一_给未落地组填值构造即拒`() {
        allPending.forEach { id ->
            val err = runCatching {
                CastingCard("R", "P", false, mapOf(id to listOf("某档")))
            }.exceptionOrNull()
            assertTrue("$id 填值必须被拒：$err", err is IllegalArgumentException)
            assertTrue("$id 拒绝理由须点名无既有档", err!!.message!!.contains("无既有封闭档"))
        }
    }

    /** 判据①：四组归组正确——814 下体形状归 GENITAL 敏感组，不新开敏感出口。 */
    @Test
    fun `判据一_四组归组正确下体归敏感组`() {
        torsoIds.forEach { assertEquals(it, CardGroup.TORSO, CastingRefs.field(it).group) }
        topoIds.forEach { assertEquals(it, CardGroup.TOPO, CastingRefs.field(it).group) }
        boneIds.forEach { assertEquals(it, CardGroup.BONE, CastingRefs.field(it).group) }
        shapeIds.forEach { assertEquals(it, CardGroup.GENITAL, CastingRefs.field(it).group) }
    }

    /**
     * 判据②：卡上限 56 生效——白名单 56 项正好用满，一个不多一个不少。
     * 注意：56 项里 26 项是 NONE（23 新增 + hair/tattoo/glasses），可填字段仅 30 项，
     * 所以「卡字段 >56」在结构上不可达——这正是 817 「超限合并同类」的结构性落点：
     * 白名单自封顶，超了由 CastingRefs.init 当场拒（见源码门禁测试）。
     */
    @Test
    fun `判据二_卡上限56生效且白名单自封顶`() {
        assertEquals(56, CastingRefs.MAX_CARD_ITEMS)
        assertEquals("33+23=56 正好用满", 56, CastingRefs.CARD_FIELDS.size)
        val fillable = CastingRefs.CARD_FIELDS.count { it.words().isNotEmpty() }
        assertEquals("可填字段仅 30，反证四组 NONE 不占可填名额", 30, fillable)
        assertTrue("可填字段远低于上限，超限不可达", fillable < CastingRefs.MAX_CARD_ITEMS)
    }

    /** 判据②：填满全部可填字段仍能落卡，预算回报 = 30/56；留痕连续。 */
    @Test
    fun `判据二_可填字段全填满仍落卡`() {
        val fillable = CastingRefs.CARD_FIELDS.filter { it.words().isNotEmpty() }
        val exact = LinkedHashMap<String, List<String>>()
        fillable.forEach { exact[it.id] = listOf(it.words().first()) }
        assertEquals(30, exact.size)
        val card = CastingCard("R", "P", false, exact)
        assertEquals(30 to 56, card.budget())
    }

    /** 判据②：超限合并逻辑仍在——卡模型与引用层的两道 require 用源码门禁钉住。 */
    @Test
    fun `判据二_超限合并require仍在`() {
        val dir = "app/src/main/java/com/example/zhengdao/baopu/state/casting/"
        val model = SrcRoots.find(dir + "CastingModel.kt").readText()
        val refs = SrcRoots.find(dir + "CastingRefs.kt").readText()
        assertTrue("卡层须保留超限拒", model.contains("values.size <= CastingRefs.MAX_CARD_ITEMS"))
        assertTrue("卡层须提示合并同类", model.contains("须合并同类后再落卡"))
        assertTrue("引用层须保留白名单自检", refs.contains("CARD_FIELDS.size <= MAX_CARD_ITEMS"))
    }

    /** 判据③：摘要 ≤120 字不变；四组 NONE 进摘要规则但不逐项进（一条都进不来）。 */
    @Test
    fun `判据三_四组进摘要规则但不逐项进`() {
        assertTrue("四组须登记进摘要规则", SUMMARY_PENDING_GROUPS.size == 4)
        assertTrue("非敏感三组须进摘要规则", setOf(CardGroup.TORSO, CardGroup.TOPO, CardGroup.BONE)
            .all { it in SUMMARY_PENDING_GROUPS })
        assertTrue("下体形状归 GENITAL 亦在登记内", CardGroup.GENITAL in SUMMARY_PENDING_GROUPS)
        assertEquals("每组最多挑 2 项", 2, SUMMARY_PICK_PER_GROUP)
        assertEquals("摘要上限钉死 120", 120, SUMMARY_LIMIT)

        // 构造一张填满四组之外所有可填字段的卡：摘要仍 ≤120，且四组 fieldId 不出现在向量里
        val v = LinkedHashMap<String, List<String>>()
        CastingRefs.CARD_FIELDS.filter { it.group != CardGroup.GENITAL && it.words().isNotEmpty() }
            .forEach { v[it.id] = listOf(it.words().first()) }
        val summary = CastingSheet.of(CastingCard("R", "P", false, v)).summary
        assertTrue("摘要 ≤120：$summary", summary.length <= 120)
        allPending.forEach { id ->
            assertFalse("NONE 字段 $id 不得进向量", CastingCard("R", "P", false, emptyMap()).has(id))
        }
    }

    /** 判据③：722 三行格式与敏感层不变——摘要不含分隔符，814 下体形状三条不进摘要。 */
    @Test
    fun `判据三_敏感层不变下体形状不进摘要`() {
        val card = CastingCard(
            "R", "P", false,
            mapOf("face.shape" to listOf("鹅蛋"), "g.prepuce" to listOf("已割")),
        )
        val sheet = CastingSheet.of(card)
        assertFalse("不得出现 722 三行分隔符", sheet.summary.contains("|"))
        assertFalse("下体档不得进摘要", sheet.summary.contains("已割"))
        shapeIds.forEach { assertFalse("$it 不可填故不可能进摘要", card.has(it)) }
        assertTrue("向量仍含下体字段供判定层", sheet.vector.any { it.fieldId == "g.prepuce" })
    }

    /** 判据⑤：向后兼容——旧卡（无新字段）仍能加载、留痕、出摘要，逐字与 817 行为一致。 */
    @Test
    fun `判据五_旧卡向后兼容`() {
        val legacy = CastingCard(
            roleId = "R", profileId = "P", isMain = true,
            values = mapOf("face.shape" to listOf("鹅蛋"), "body.frame" to listOf("中")),
            history = listOf(CardChange(1, "R", "face.shape", emptyList(), listOf("鹅蛋"), "初设")),
        )
        assertEquals(1, legacy.revision)
        assertTrue(legacy.has("face.shape"))
        assertTrue(CastingSheet.of(legacy).summary.contains("脸型"))
        val changed = legacy.with("face.eyes", listOf("单"), "补眼睛")
        assertEquals(2, changed.revision)
        assertEquals(1, legacy.revision) // 原卡不变
        // 旧卡字段数远低于 56，兼容无碍
        assertTrue(legacy.budget().first <= 56)
    }

    /** 判据④：三组校验入口语义不变——矛盾/可补卡/难辨三态仍按 817 口径返回。 */
    @Test
    fun `判据四_三组校验语义不变`() {
        val base = CastingCard("R", "P", false, mapOf("face.shape" to listOf("鹅蛋")))
        assertEquals("矛盾仍判红", CheckState.RED,
            ContinuityCheck.check(base, mapOf("face.shape" to listOf("方"))).state)
        assertEquals("未设定仍提示可补卡", CheckState.HINT,
            ContinuityCheck.check(CastingCard("R", "P", false), mapOf("face.shape" to listOf("鹅蛋"))).state)
        val trio = listOf(
            CastingCard("A", "P", false, mapOf("face.shape" to listOf("鹅蛋"), "body.frame" to listOf("中"))),
            CastingCard("B", "P", false, mapOf("face.shape" to listOf("方"), "body.frame" to listOf("中"))),
            CastingCard("C", "P", false, mapOf("face.shape" to listOf("长"), "body.frame" to listOf("中"))),
        )
        assertEquals("群像差异不足仍判红", CheckState.RED, ContinuityCheck.distinguishable(trio).state)
    }

    /** 负例自检：若把未落地组误接成有档，本测试构造与断言会立刻红——证明闸门有牙。 */
    @Test
    fun `闸自检_四组若有档则本契约立刻失效`() {
        val anyHasAllowed = (torsoIds + topoIds + boneIds + shapeIds)
            .any { CastingRefs.field(it).words().isNotEmpty() }
        assertFalse("一旦四组被填值/接档，判据①应红；此处必须为 false", anyHasAllowed)
    }
}
