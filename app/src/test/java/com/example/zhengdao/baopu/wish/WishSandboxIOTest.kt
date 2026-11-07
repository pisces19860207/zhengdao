// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十六批 · 判据②的测试：三类取数 IO 各一条，全部对着**真零件**（CardBuild／
// 资产读件／NovelProjectStore／RehearsalSandbox）证明盘上事实：
//   · CARD：两次 build 全在军师副本，真项目 `成品/` 一个字节没动；
//   · CONTENT：技能取数读的是仓库真资产 `assets/baopu/skills/微头条流程`，写只落
//     `许愿/<id>/草稿/`，`产物/` 只读；
//   · NOVEL：项目副本里落草稿章，旧章字节不变，真书目录没动。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.agent.WishIOWiring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WishSandboxIOTest {

    private lateinit var workspace: File
    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        workspace = tempDir("zd-w516-ws")
        cacheRoot = tempDir("zd-w516-cache")
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    private fun tempDir(tag: String) = File.createTempFile(tag, "").let { it.delete(); it.mkdirs(); it }

    private fun persona() = PersonaFiles(projectRoot = tempDir("zd-w516-proj"), globalRoot = tempDir("zd-w516-glob"))

    /** 技能资产读件：生产接 Context.assets，测试接仓库同一段字节（同一份文件）。 */
    private fun repoAsset(name: String): String? {
        val rel = "src/main/assets/baopu/skills/$name/SKILL.md"
        val f = File(rel).let { if (it.isFile) it else File("app/$rel") }
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    private fun ports() = WishIOWiring.ports(
        workspace = workspace, persona = persona(), cacheRoot = cacheRoot,
        readSkillAsset = ::repoAsset, stamp = { "20261107-090000" },
    )

    private fun runThrough(product: WishProduct, wish: String, id: String): RehearsalReading {
        val ports = ports()
        val ctx = WishContext(id, wish, product)
        val body = ports.generateBody(ctx)
        assertTrue("草稿要落得下去", ports.writeDraft(ctx, body))
        return ports.runRehearsal(ctx)
    }

    // ── 判据②·CARD：card_build 走副本，不写真项目 ─────────────────────────

    @Test
    fun 判据2_CARD_两次build在副本里真成品一个字节不动() {
        val real = File(workspace, "成品/既有卡").apply { mkdirs() }
        val sentinel = File(real, "旧卡.txt").apply { writeText("别动我", Charsets.UTF_8) }

        val reading = runThrough(WishProduct.CARD, "我想要一张能把设定变成世界书卡片的卡", "w-card")

        assertTrue("试跑要过：${reading.note}", reading.ok)
        assertTrue("真成品目录要没动", reading.realUntouched)
        assertEquals("哨兵原样", "别动我", sentinel.readText())
        assertEquals("真成品目录没多文件", 1, real.walkTopDown().filter { it.isFile }.count())
        assertFalse("副本要清干净", File(cacheRoot, "rehearsal").exists())
        assertTrue("自检要过", ports().collectCheck(WishContext("w-card", "", WishProduct.CARD)) is SelfCheckVerdict.Pass)
    }

    // ── 判据②·CONTENT：读真技能资产，写只落草稿 ───────────────────────────

    @Test
    fun 判据2_CONTENT_技能读真资产写只落草稿产物只读() {
        val productDir = File(workspace, "产物").apply { mkdirs() }
        val ledger = File(productDir, "台账.md")
        val oldLedger = "# 产物台账\n\n## 总览\n\n只追加不改旧行；打勾＝把 ☐ 改 ✔ 并补日期。\n\n" +
            "## 2026-11-06 批次（10:00 落盘）\n\n- ☐ 20261106_微头条_1_旧稿.md（300 字）\n"
        ledger.writeText(oldLedger, Charsets.UTF_8)

        val reading = runThrough(WishProduct.CONTENT, "我想要一个能把想法变成微头条的流程", "w-content")

        assertTrue("试跑要过：${reading.note}", reading.ok)
        assertTrue("产物目录要没动", reading.realUntouched)
        assertTrue("草稿落在许愿自己的草稿目录", File(WishIOWiring.draftDir(workspace, "w-content"), "草稿卡.md").isFile)
        assertEquals("产物目录只有台账一份文件（没有新稿）", 1, productDir.listFiles { f -> f.isFile }!!.count())
        assertEquals("台账一个字节没动", oldLedger, ledger.readText())
        assertTrue("自检要过", ports().collectCheck(WishContext("w-content", "", WishProduct.CONTENT)) is SelfCheckVerdict.Pass)
    }

    // ── 判据②·NOVEL：读项目副本，写只落草稿章 ─────────────────────────────

    @Test
    fun 判据2_NOVEL_项目副本里落章旧章一字不变真书没动() {
        val book = File(workspace, "小说/测试书").apply { mkdirs() }
        File(book, "book.json").writeText(
            "{\"format\":\"zhengdao-novel/1\",\"title\":\"测试书\",\"outline\":\"少年上山学艺\"," +
                "\"chapterTargetWords\":3000,\"characters\":[{\"name\":\"阿岩\",\"desc\":\"樵夫\"}]}",
            Charsets.UTF_8,
        )
        val ch1 = File(File(book, "正文").apply { mkdirs() }, "第一章.md")
        val ch1Bytes = "第一章　上山。\n" + "山道很长，走到天黑才到山门。\n".repeat(30)
        ch1.writeText(ch1Bytes, Charsets.UTF_8)

        val reading = runThrough(WishProduct.NOVEL, "我想要一个能把大纲变成正文的书", "w-novel")

        assertTrue("试跑要过：${reading.note}", reading.ok)
        assertTrue("真书目录要没动", reading.realUntouched)
        assertEquals("旧章一字不变", ch1Bytes, ch1.readText())
        assertEquals("真书正文没多章", 1, File(book, "正文").listFiles { f -> f.isFile }!!.count())
        assertFalse("副本要清干净", File(cacheRoot, "rehearsal").exists())
        assertTrue("自检要过", ports().collectCheck(WishContext("w-novel", "", WishProduct.NOVEL)) is SelfCheckVerdict.Pass)
    }

    // ── 负例：没有小说项目时照实说，不假装试跑过 ──────────────────────────

    @Test
    fun 负例_没有小说项目试跑照实失败() {
        val reading = runThrough(WishProduct.NOVEL, "我想要一个能把大纲变成正文的书", "w-empty")
        assertFalse("没项目不许报 ok", reading.ok)
        assertTrue("真盘当然没动", reading.realUntouched)
        assertTrue(reading.note.contains("还没有小说项目"))
    }
}
