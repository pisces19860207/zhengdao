// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十九批 B 段第 8 条（257 审计 A-P2-18）的复现用例。
//
// 缺陷原文：`KnowledgeIndexStore` 的两处读都写成
// `runCatching { f.readText() }.getOrDefault("")` 再 `decode("")` ⇒ null，
// 于是**读坏了的索引**与**从没建过索引**在调用方手里一模一样：界面显示「还没有索引」、
// 刷新按全量重建走（上一轮的增量复用静默没了），谁也不知道那格文件其实被写坏过。
//
// 本仓无 Robolectric ⇒ Context 侧（summary/刷新）用源闸钉；三态判据与指纹是纯函数，直测。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.SrcRoots
import com.example.zhengdao.baopu.kb.KbIndex
import com.example.zhengdao.baopu.kb.KbIndexCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KnowledgeIndexCorruptionTest {

    @Rule
    @JvmField
    val tmp: TemporaryFolder = TemporaryFolder()

    private val ws: File by lazy { tmp.newFolder("ws") }

    private fun writeIndex(text: String): File =
        KnowledgeLayout.indexFile(ws).also { it.parentFile!!.mkdirs(); it.writeText(text, Charsets.UTF_8) }

    private fun writeSnapshot(text: String): File =
        KnowledgeLayout.snapshotFile(ws).also { it.parentFile!!.mkdirs(); it.writeText(text, Charsets.UTF_8) }

    private val goodText: String = KbIndexCodec.encode(
        KbIndex(root = "/sdcard/Download/证道/知识库", rootLabel = "知识库", updatedAt = 1_760_000_000_000L, docs = emptyList()),
    )

    /**
     * 三态分明（**改前表现**：`load()` 对这三种输入返回同一个 null，本条根本写不出来）。
     */
    @Test
    fun `索引三态_没有_好的_坏了`() {
        assertTrue("文件都还没有 ⇒ Missing",
            KnowledgeIndexStore.loadRead(ws) is KnowledgeIndexStore.FileRead.Missing)
        writeIndex(goodText)
        val ok = KnowledgeIndexStore.loadRead(ws)
        assertTrue("合法索引 ⇒ Value", ok is KnowledgeIndexStore.FileRead.Value)
        assertEquals(1_760_000_000_000L, (ok as KnowledgeIndexStore.FileRead.Value).value.updatedAt)
        writeIndex("{ not json")
        val bad = KnowledgeIndexStore.loadRead(ws)
        assertTrue("JSON 坏 ⇒ Corrupt", bad is KnowledgeIndexStore.FileRead.Corrupt)
        assertTrue("要说得出环节：${(bad as KnowledgeIndexStore.FileRead.Corrupt).why}",
            bad.why.contains("解不开"))
        assertTrue("要带可对账的字节数与指纹", bad.bytes > 0 && bad.digest8.isNotEmpty())
    }

    /** 版本号不符也算「坏了」，不许当「没有索引」（128 批口径：旧号一律当损坏，走重建，不猜）。 */
    @Test
    fun `格式版本不符也是坏了`() {
        writeIndex("""{"format":"kb-index-v0","docs":[]}""")
        val r = KnowledgeIndexStore.loadRead(ws)
        assertTrue("旧格式／版本不符 ⇒ Corrupt，不是 Missing", r.isCorrupt())
    }

    /**
     * 「没有」与「坏了」在读数上分家，同时**老 API 的形态一字未动**（`load()` 仍给 null，
     * 既有调用方与 KnowledgeRefreshGateTest 的口径都不必跟着改）。
     */
    @Test
    fun `老API形态没变_但损坏不再等于没有`() {
        assertNull("还没有文件 ⇒ null", KnowledgeIndexStore.load(ws))
        writeIndex("{ not json")
        assertNull("文件坏了 ⇒ 老 API 仍返回 null（兼容面）", KnowledgeIndexStore.load(ws))
        // 同形的是老 API，不同形的是读数：这一句就是派工单要的「不许当没有索引」
        assertTrue(KnowledgeIndexStore.loadRead(ws).isCorrupt())
        assertFalse(KnowledgeIndexStore.loadRead(tmp.newFolder("empty-ws")).isCorrupt())
    }

    /** 快照同一口径（A-P2-18 点的是 :44 与 :49 两处，不能只修一半）。 */
    @Test
    fun `快照也分三态`() {
        assertTrue(KnowledgeIndexStore.loadSnapshotRead(ws) is KnowledgeIndexStore.FileRead.Missing)
        writeSnapshot(KnowledgeSnapshot.encode(KnowledgeSnapshot.Snapshot()))
        assertTrue("合法快照 ⇒ Value",
            KnowledgeIndexStore.loadSnapshotRead(ws) is KnowledgeIndexStore.FileRead.Value)
        assertNotNull("老 API 仍能拿到内容（兼容面）", KnowledgeIndexStore.loadSnapshot(ws))
        writeSnapshot("坏了一半")
        val r = KnowledgeIndexStore.loadSnapshotRead(ws)
        assertTrue("损坏的快照 ⇒ Corrupt（改前与「没有快照」同形）", r.isCorrupt())
        assertNull(r.valueOrNull())
        assertNull("老 API 仍返回 null", KnowledgeIndexStore.loadSnapshot(ws))
    }

    /**
     * 指纹只对账、不可逆、不含正文（索引里是用户文档片段，原文不许搬到日志/返回值里）。
     *
     * **改前**：没有指纹这个东西（也没有三态），损坏与空文件同形。
     */
    @Test
    fun `digest8是sha256前8位_改一个字就变`() {
        assertEquals("sha256(\"abc\") 的前 8 位", "ba7816bf", KnowledgeIndexStore.digest8("abc"))
        assertEquals("同一份坏文件两次读数必须一致",
            KnowledgeIndexStore.digest8("同一份"), KnowledgeIndexStore.digest8("同一份"))
        assertFalse("改一个字必须变码",
            KnowledgeIndexStore.digest8("同一份") == KnowledgeIndexStore.digest8("同一份 "))
        assertTrue(KnowledgeIndexStore.digest8("x").matches(Regex("^[0-9a-f]{8}$")))
    }

    /** 坏索引的读数里不许出现原文片段（金丝雀：只要有人把正文塞进返回值/日志就红）。 */
    @Test
    fun `读数不带原文`() {
        val canary = "用户日记里最私的一句"
        writeIndex("""{"format":"kb-index-v1","docs":[$canary}""")
        val r = KnowledgeIndexStore.loadRead(ws)
        assertTrue("该判成坏了", r is KnowledgeIndexStore.FileRead.Corrupt)
        assertFalse("Corrupt 的读数里出现了原文：$r", r.toString().contains(canary))
        r as KnowledgeIndexStore.FileRead.Corrupt
        assertTrue("对账线索：正数字节数＋ 8 位十六进制指纹",
            r.bytes > 0 && r.digest8.matches(Regex("^[0-9a-f]{8}$")))
    }

    /**
     * 调用方接线（源闸；蓝总管要求「每处调用方改动都要有对应用例」）。
     *
     * **改前表现**：`KnowledgeRefresh.kt` 里没有 `isCorrupt()`／「知识库索引读坏了」，
     * 界面读数只有 `hasIndex`（坏了＝没索引）⇒ 本条红。
     * 为什么不写行为级用例：`summary()`/`doRefresh()` 都要 Context（本仓无 Robolectric）；
     * 且 KnowledgeRefreshGateTest 钉住了「previousIndex = KnowledgeIndexStore.load(ws)」
     * （读不到 ⇒ previous=null ⇒ 全量重建），本批不许换成新 API，所以接线只能用源闸钉。
     */
    @Test
    fun `接线_刷新说得出损坏_界面拿得到标记`() {
        val src = SrcRoots.find("src/main/java/com/example/zhengdao/baopu/KnowledgeRefresh.kt")
            .readText(Charsets.UTF_8).replace("\r\n", "\n")
        assertTrue("界面读数必须带 indexBroken（坏了与没有分开递给 UI）",
            src.contains("indexBroken = read.isCorrupt()"))
        assertTrue("刷新时必须把损坏说出去（环节＋字节数＋指纹）",
            src.contains("知识库索引读坏了") && src.contains("it.digest8"))
        assertTrue("坏索引仍然按无索引全量重建（既有闸钉的口径不许动）",
            src.contains("previousIndex = KnowledgeIndexStore.load(ws)"))
    }
}
