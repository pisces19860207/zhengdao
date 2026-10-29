// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 第一百九十九批 · 相册分流与归属的**判据层**（`MediaOwnership`，零 Android）。
 *
 * 十一组（正例与负例成对，负例才是判据）：
 * ① 落点与相对路径（`RELATIVE_PATH` 唯一来源，末尾必须带 `/`）；
 * ② 账本编解码（键序固定、坏行一律丢，**不伪造归属**）；
 * ③ 账本追加（只追加、墓碑、前一行没换行也不粘行）；
 * ④ 判重（`sha256` 命中活记录才复用，墓碑不算）；
 * ⑤ 不覆盖命名（`-2/-3`；扩展名带不带点都行）；
 * ⑥ **删除判据五条**（账上没有/id 不符/不是本包/已删过/相册归属不是本包 ⇒ 全部 Ask）；
 * ⑦ 相册路径识别（三种写法认，`Download/证道/…` 不认）；
 * ⑧ 迁移计划（无冲突/已在位/同名不同内容改名/哈希空跳过/批内同图只搬一份）；
 * ⑨ 二遍全零（改名过的那份第二遍必须算「已在位」，不许一路 `-3`、`-4`）；
 * ⑩ 字节与文件两路 `sha256` 一致（同一个算法，不是两份实现）；
 * ⑪ 落点常量来自内核（防止第二套路径在这里长出来）。
 */
class MediaOwnershipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(
        id: Long = 7L,
        name: String = "证道截屏-20261025-083000.png",
        relPath: String = MediaOwnership.relShotDir() + name,
        sha: String = "a".repeat(64),
        owner: String = PKG,
        source: String = MediaOwnership.SOURCE_SCREENSHOT,
        state: String = MediaOwnership.STATE_ADDED,
        deletedAt: Long = 0L,
    ) = MediaOwnership.Entry(
        id = id,
        name = name,
        relPath = relPath,
        sha256 = sha,
        owner = owner,
        createdAt = 1_760_000_000_000L,
        source = source,
        bytes = 1024,
        state = state,
        deletedAt = deletedAt,
    )

    // ── ① 落点 ───────────────────────────────────────────────────────────────

    @Test
    fun `落点_截屏进系统截屏夹_生图目录不动_末尾必须带斜杠`() {
        // 268：截屏只有一条路，落系统截屏夹（与用户自己截的图同目录）
        assertEquals("Pictures/Screenshots/", MediaOwnership.relShotDir())
        // 负例：生图落点被顺手改掉（Pictures/证道/图片/ 不该动）
        assertEquals("Pictures/证道/图片/", MediaOwnership.relImageDir())
        // MediaStore 的 RELATIVE_PATH 不带结尾斜杠会被当成文件名前缀，这里钉死
        assertTrue(MediaOwnership.relShotDir().endsWith("/"))
        assertTrue(MediaOwnership.relImageDir().endsWith("/"))
    }

    @Test
    fun `落点_存量目录还在_只作账本识别`() {
        // 268 负例：只改名不改根 ≠ 把存量目录的真源抹掉——旧账本条目的 relPath 还是它
        assertEquals("Pictures/证道/截屏/", MediaOwnership.relLegacyShotDir())
    }

    @Test
    fun `落点_来源到目录_认不出的来源回null`() {
        assertEquals(MediaOwnership.relShotDir(), MediaOwnership.relDirOf(MediaOwnership.SOURCE_SCREENSHOT))
        assertEquals(MediaOwnership.relImageDir(), MediaOwnership.relDirOf(MediaOwnership.SOURCE_IMAGE))
        assertNull("来源写别的（比如 'file'）不许猜落点", MediaOwnership.relDirOf("file"))
        assertNull(MediaOwnership.relDirOf(""))
    }

    // ── ② 编解码 ─────────────────────────────────────────────────────────────

    @Test
    fun `账本_编码解码逐字段往返`() {
        val e = entry()
        val line = MediaOwnership.encode(e)
        assertTrue("一行一条，不许有换行", !line.contains("\n"))
        assertEquals(e, MediaOwnership.decode(line))
    }

    @Test
    fun `账本_墓碑字段只在删过时才出现`() {
        val live = MediaOwnership.encode(entry())
        assertFalse("活条目不该带 deletedAt", live.contains("deletedAt"))
        val dead = MediaOwnership.encode(entry(state = MediaOwnership.STATE_DELETED, deletedAt = 99L))
        assertTrue(dead.contains("\"deletedAt\":99"))
    }

    @Test
    fun `账本_坏行一律丢_不伪造归属`() {
        // 缺 owner ⇒ 这条没有归属意义，宁可当成没有（否则它会变成「自己写的」）
        assertNull(MediaOwnership.decode("""{"v":1,"id":3,"name":"x.png","sha256":"bb"}"""))
        // id 是 0 ⇒ 对不上任何 MediaStore 行
        assertNull(MediaOwnership.decode("""{"id":0,"name":"x.png","sha256":"bb","owner":"p"}"""))
        // sha 空 ⇒ 判重与对账都没意义
        assertNull(MediaOwnership.decode("""{"id":3,"name":"x.png","sha256":"","owner":"p"}"""))
        // 非 JSON / 空行 / 半截
        assertNull(MediaOwnership.decode(""))
        assertNull(MediaOwnership.decode("   "))
        assertNull(MediaOwnership.decode("{坏 json"))
        assertNull(MediaOwnership.decode("# 注释"))
    }

    @Test
    fun `账本_整份解析跳过坏行_好行照收`() {
        val good = MediaOwnership.encode(entry(id = 11L))
        val text = "{坏行\n$good\n\n" + MediaOwnership.encode(entry(id = 12L))
        val all = MediaOwnership.parseAll(text)
        assertEquals(2, all.size)
        assertEquals(listOf(11L, 12L), all.map { it.id })
    }

    // ── ③ 追加 ───────────────────────────────────────────────────────────────

    @Test
    fun `账本_只追加_两次写都在`() {
        val ws = tmp.newFolder("ws")
        assertTrue(MediaOwnership.append(ws, entry(id = 1L)))
        assertTrue(MediaOwnership.append(ws, entry(id = 2L)))
        val all = MediaOwnership.readAll(ws)
        assertEquals(2, all.size)
        assertEquals("文件名必须是 .归属.jsonl", ".归属.jsonl", MediaOwnership.ledgerFile(ws).name)
    }

    @Test
    fun `账本_前一行没有换行也不粘成坏行`() {
        val ws = tmp.newFolder("ws")
        val f = MediaOwnership.ledgerFile(ws)
        f.writeText(MediaOwnership.encode(entry(id = 1L)), Charsets.UTF_8) // 故意不写结尾换行
        assertTrue(MediaOwnership.append(ws, entry(id = 2L)))
        assertEquals("两行都得解析出来（粘行＝丢一条）", 2, MediaOwnership.readAll(ws).size)
    }

    @Test
    fun `账本_墓碑进文件但不算活记录`() {
        val ws = tmp.newFolder("ws")
        MediaOwnership.append(ws, entry(id = 5L))
        MediaOwnership.appendTombstone(ws, entry(id = 5L), nowMillis = 42L)
        val all = MediaOwnership.readAll(ws)
        assertEquals("两行都留着（只追加，不改写历史）", 2, all.size)
        assertEquals("活记录只剩墓碑前那一条", 1, MediaOwnership.live(all).size)
        assertEquals("墓碑要带删除时间", 42L, all.last().deletedAt)
        assertFalse("墓碑不是活记录", all.last().isLive)
    }

    @Test
    fun `账本_没有文件时读回空表`() {
        val ws = tmp.newFolder("empty")
        assertTrue(MediaOwnership.readAll(ws).isEmpty())
    }

    // ── ④ 判重 ───────────────────────────────────────────────────────────────

    @Test
    fun `判重_同图命中活记录_墓碑不命中`() {
        val sha = "c".repeat(64)
        val live = listOf(entry(id = 1L, sha = sha))
        assertEquals(1L, MediaOwnership.findLiveBySha(live, sha)!!.id)
        // 大小写不同的哈希也算同一份（账本来自不同写入路径）
        assertEquals(1L, MediaOwnership.findLiveBySha(live, sha.uppercase())!!.id)
        val dead = listOf(entry(id = 1L, sha = sha, state = MediaOwnership.STATE_DELETED, deletedAt = 1L))
        assertNull("删过的不能再算「已在相册」", MediaOwnership.findLiveBySha(dead, sha))
        assertNull(MediaOwnership.findLiveBySha(live, "d".repeat(64)))
    }

    @Test
    fun `按路径找活记录_大小写不敏感_墓碑不算`() {
        val e = entry(id = 3L, name = "A.PNG")
        val all = listOf(e)
        assertEquals(3L, MediaOwnership.findLiveByRelPath(all, e.relPath)!!.id)
        assertEquals(3L, MediaOwnership.findLiveByRelPath(all, e.relPath.lowercase())!!.id)
        assertNull(MediaOwnership.findLiveByRelPath(listOf(e.copy(state = MediaOwnership.STATE_DELETED)), e.relPath))
    }

    // ── ⑤ 命名 ───────────────────────────────────────────────────────────────

    @Test
    fun `命名_占用就加序号_绝不覆盖`() {
        assertEquals("x.png", MediaOwnership.uniqueName(emptySet(), "x", "png"))
        assertEquals("x-2.png", MediaOwnership.uniqueName(setOf("x.png"), "x", "png"))
        assertEquals("x-3.png", MediaOwnership.uniqueName(setOf("x.png", "x-2.png"), "x", "png"))
        // 扩展名带点也认（MediaStore 的 DISPLAY_NAME 习惯写全名）
        assertEquals("x-2.jpg", MediaOwnership.uniqueName(setOf("x.jpg"), "x", ".jpg"))
    }

    @Test
    fun `拆名字_没有扩展名时ext为空`() {
        assertEquals("x" to "png", MediaOwnership.splitName("x.png"))
        assertEquals("a.b" to "c", MediaOwnership.splitName("a.b.c"))
        assertEquals("没有点" to "", MediaOwnership.splitName("没有点"))
        // 点开头（.DS_Store）不算扩展名 —— 拆出来的 trunk 不能是空串
        assertEquals(".DS_Store" to "", MediaOwnership.splitName(".DS_Store"))
    }

    // ── ⑥ 删除判据 ───────────────────────────────────────────────────────────

    @Test
    fun `删除_五条对不上都要问_对上了才删`() {
        val e = entry(id = 9L)
        // ① 账上没有
        val noEntry = MediaOwnership.deleteVerdict(null, 9L, PKG, PKG)
        assertTrue(noEntry is MediaOwnership.DeleteVerdict.Ask)
        assertTrue((noEntry as MediaOwnership.DeleteVerdict.Ask).reason.contains("归属清单里没有"))
        // ② id 对不上
        assertTrue(MediaOwnership.deleteVerdict(e, 10L, PKG, PKG) is MediaOwnership.DeleteVerdict.Ask)
        // ③ 记账的不是本包
        assertTrue(
            MediaOwnership.deleteVerdict(e.copy(owner = "com.other"), 9L, "com.other", PKG)
                is MediaOwnership.DeleteVerdict.Ask,
        )
        // ④ 已经删过（墓碑）
        assertTrue(
            MediaOwnership.deleteVerdict(e.copy(state = MediaOwnership.STATE_DELETED), 9L, PKG, PKG)
                is MediaOwnership.DeleteVerdict.Ask,
        )
        // ⑤ 相册里那一行不是本包写的（账本被伪造/包名变了）
        assertTrue(
            MediaOwnership.deleteVerdict(e, 9L, "com.gallery.other", PKG)
                is MediaOwnership.DeleteVerdict.Ask,
        )
        // 正例：都对上 ⇒ 可以删
        val ours = MediaOwnership.deleteVerdict(e, 9L, PKG, PKG)
        assertTrue(ours is MediaOwnership.DeleteVerdict.Ours)
        assertEquals(9L, (ours as MediaOwnership.DeleteVerdict.Ours).entry.id)
        // 读不到 OWNER_PACKAGE_NAME（老系统/查询失败）⇒ 只要账本对得上就允许（此时 albumOwner=null）
        assertTrue(MediaOwnership.deleteVerdict(e, 9L, null, PKG) is MediaOwnership.DeleteVerdict.Ours)
    }

    @Test
    fun `删除_别人包名的账本条目_一句人话里带包名`() {
        val v = MediaOwnership.deleteVerdict(entry(owner = "com.foo.bar"), 7L, "com.foo.bar", PKG)
        val reason = (v as MediaOwnership.DeleteVerdict.Ask).reason
        assertTrue("要说清是谁写的：$reason", reason.contains("com.foo.bar"))
    }

    // ── ⑦ 相册路径识别 ───────────────────────────────────────────────────────

    @Test
    fun `相册路径_新截屏夹三种写法都认`() {
        val want = "Pictures/Screenshots/a.png"
        assertEquals(want, MediaOwnership.albumRelPathOf("Pictures/Screenshots/a.png"))
        assertEquals(want, MediaOwnership.albumRelPathOf("/storage/emulated/0/Pictures/Screenshots/a.png"))
        assertEquals(want, MediaOwnership.albumRelPathOf("Screenshots/a.png"))
        assertEquals(want, MediaOwnership.albumRelPathOf("  Pictures\\Screenshots\\a.png  "))
    }

    @Test
    fun `相册路径_存量目录三种写法仍认_旧账本条目删得掉`() {
        val want = "Pictures/证道/截屏/a.png"
        assertEquals(want, MediaOwnership.albumRelPathOf("Pictures/证道/截屏/a.png"))
        assertEquals(want, MediaOwnership.albumRelPathOf("/storage/emulated/0/Pictures/证道/截屏/a.png"))
        assertEquals(want, MediaOwnership.albumRelPathOf("证道/截屏/a.png"))
        assertEquals(want, MediaOwnership.albumRelPathOf("  Pictures\\证道\\截屏\\a.png  "))
    }

    @Test
    fun `相册路径_不是相册的一律null`() {
        assertNull(MediaOwnership.albumRelPathOf("Download/证道/截屏/a.png"))
        assertNull(MediaOwnership.albumRelPathOf("附件/a.png"))
        assertNull(MediaOwnership.albumRelPathOf(""))
        assertNull(MediaOwnership.albumRelPathOf("Pictures/别的应用/a.png"))
    }

    @Test
    fun `相册路径_只认三个子目录_新旧都在册`() {
        assertTrue(MediaOwnership.isAlbumPath(MediaOwnership.relShotDir() + "a.png"))
        assertTrue("存量目录必须还在删除判据里（旧账本条目要删得掉）",
            MediaOwnership.isAlbumPath(MediaOwnership.relLegacyShotDir() + "a.png"))
        assertTrue(MediaOwnership.isAlbumPath(MediaOwnership.relImageDir() + "a.png"))
        assertFalse(MediaOwnership.isAlbumPath("Pictures/证道/别的/a.png"))
        // 负例：相册根本身不是落点
        assertFalse(MediaOwnership.isAlbumPath("Pictures/证道/a.png"))
    }

    @Test
    fun `回读路径_相册口径换算成相对Pictures根`() {
        // 引擎的 shotRoot = <SD根>/Pictures ⇒ 给引擎的路径要去掉 Pictures/ 前缀
        assertEquals("Screenshots/a.png", MediaOwnership.relUnderPictures("Pictures/Screenshots/a.png"))
        // 生图同理（虽然生图本轮不回读，换算不许只对截屏成立）
        assertEquals("证道/图片/a.png", MediaOwnership.relUnderPictures("Pictures/证道/图片/a.png"))
        // 已是相对口径（退路兜底拼的）原样返回
        assertEquals("Screenshots/b.png", MediaOwnership.relUnderPictures("Screenshots/b.png"))
    }

    // ── ⑧⑨ 迁移计划 ──────────────────────────────────────────────────────────

    @Test
    fun `迁移_无冲突原名复制`() {
        val plan = MediaOwnership.planShotMigration(
            legacy = listOf(MediaOwnership.Item("a.png", "1"), MediaOwnership.Item("b.png", "2")),
            target = emptyList(),
        )
        assertEquals(listOf("a.png", "b.png"), plan.copies.map { it.to })
        assertTrue(plan.identical.isEmpty())
    }

    @Test
    fun `迁移_同名同哈希算已在位`() {
        val plan = MediaOwnership.planShotMigration(
            legacy = listOf(MediaOwnership.Item("a.png", "1")),
            target = listOf(MediaOwnership.Item("a.png", "1")),
        )
        assertTrue(plan.copies.isEmpty())
        assertEquals(listOf("a.png"), plan.identical)
    }

    @Test
    fun `迁移_同名不同内容_改名不覆盖`() {
        val plan = MediaOwnership.planShotMigration(
            legacy = listOf(MediaOwnership.Item("a.png", "1")),
            target = listOf(MediaOwnership.Item("a.png", "9")),
        )
        assertEquals(1, plan.copies.size)
        assertEquals("a-2.png", plan.copies[0].to)
        assertEquals(MediaOwnership.WHY_RENAMED, plan.copies[0].reason)
        assertEquals("a.png", plan.copies[0].from)
    }

    @Test
    fun `迁移_哈希为空跳过_不在没核对的前提下搬`() {
        val plan = MediaOwnership.planShotMigration(
            legacy = listOf(MediaOwnership.Item("a.png", ""), MediaOwnership.Item("", "3")),
            target = emptyList(),
        )
        assertTrue(plan.copies.isEmpty())
        assertEquals(2, plan.skipped.size)
    }

    @Test
    fun `迁移_批内两份同图只搬一份`() {
        val plan = MediaOwnership.planShotMigration(
            legacy = listOf(MediaOwnership.Item("a.png", "1"), MediaOwnership.Item("b.png", "1")),
            target = emptyList(),
        )
        assertEquals(1, plan.copies.size)
        assertEquals("a.png", plan.copies[0].to)
        assertEquals(listOf("b.png"), plan.identical)
    }

    @Test
    fun `迁移_二遍全零_改名过的那份不许一路加号`() {
        // 第一遍：目标已有 a.png 但内容不同 ⇒ 复制成 a-2.png
        val first = MediaOwnership.planShotMigration(
            legacy = listOf(MediaOwnership.Item("a.png", "1")),
            target = listOf(MediaOwnership.Item("a.png", "9")),
        )
        assertEquals("a-2.png", first.copies[0].to)
        // 第二遍：目标里已有这份内容（在 a-2.png 名下）⇒ 必须在位，不许再复制成 a-3
        val second = MediaOwnership.planShotMigration(
            legacy = listOf(MediaOwnership.Item("a.png", "1")),
            target = listOf(MediaOwnership.Item("a.png", "9"), MediaOwnership.Item("a-2.png", "1")),
        )
        assertTrue("二遍必须全零，实际 copies=${second.copies}", second.copies.isEmpty())
        assertEquals(listOf("a.png"), second.identical)
    }

    // ── ⑩ 哈希 ───────────────────────────────────────────────────────────────

    @Test
    fun `哈希_字节与文件两路一致`() {
        val bytes = "证道-相册-199".toByteArray(Charsets.UTF_8)
        val f = File(tmp.newFolder("h"), "x.bin")
        f.writeBytes(bytes)
        assertEquals(MediaOwnership.sha256(bytes), MediaOwnership.sha256(f))
        assertEquals(64, MediaOwnership.sha256(bytes).length)
        // 差一个字节就得是两个值（负例：别把「同尺寸」当「同图」）
        val other = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(MediaOwnership.sha256(other) == MediaOwnership.sha256(bytes))
    }

    // ── ⑪ 常量唯一来源 ───────────────────────────────────────────────────────

    @Test
    fun `常量_落点与账本名来自内核`() {
        assertEquals("证道", com.example.zhengdao.baopu.kernel.BaopuKernel.Data.DIR_ALBUM)
        assertEquals("截屏", com.example.zhengdao.baopu.kernel.BaopuKernel.Data.DIR_ALBUM_SHOTS)
        assertEquals("图片", com.example.zhengdao.baopu.kernel.BaopuKernel.Data.DIR_ALBUM_IMAGES)
        assertEquals(".归属.jsonl", com.example.zhengdao.baopu.kernel.BaopuKernel.Data.FILE_MEDIA_LEDGER)
        assertEquals(
            com.example.zhengdao.baopu.kernel.BaopuKernel.Data.FILE_MEDIA_LEDGER,
            MediaOwnership.LEDGER_NAME,
        )
    }

    private companion object {
        /** 测试用包名（真机上是 `BuildConfig.APPLICATION_ID`）。 */
        const val PKG = "com.example.zhengdao"
    }
}
