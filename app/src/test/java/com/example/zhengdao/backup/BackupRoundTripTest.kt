// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十一批 · 导出→导入的**整条链路**（真临时目录、真 zip、真字节比对）。
//
// 为什么必须真跑而不是读源码：判据里「同版本可完整还原（行数/字节一致）」
// 「冲突不覆盖」「导入失败不留半状态」三条，全都只有真写一遍才看得见。
package com.example.zhengdao.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupRoundTripTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd491").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun mountsUnder(base: File): List<BackupMount> {
        val mem = File(base, "抱朴记忆")
        return listOf(
            BackupMount("memory/global", mem, BackupGroup.MEMORY, splitLedger = true, skipDirNames = setOf("技能")),
            BackupMount("memory/project", File(base, "工作区/抱朴记忆"), BackupGroup.MEMORY, splitLedger = true),
            BackupMount("skills/global", File(mem, "技能"), BackupGroup.SKILLS),
            BackupMount("skills/project", File(base, "工作区/技能"), BackupGroup.SKILLS),
        )
    }

    /** 一棵像真机的树：账本（jsonl）＋人设（md）＋技能（两级）＋工作区侧一份人设。 */
    private fun makeTree(base: File): File {
        write(File(base, "抱朴记忆/决策记录.jsonl"), "{\"a\":1}\n{\"a\":2}\n")
        write(File(base, "抱朴记忆/人设.md"), "# 出厂项目人设\n1 先看再动\n2 不删用户文件\n")
        write(File(base, "抱朴记忆/技能/整理书稿/SKILL.md"), "---\nname: 整理书稿\n---\n拆章。\n")
        write(File(base, "工作区/抱朴记忆/人设-本项目.md"), "# 本项目\n小说工程\n")
        write(File(base, "工作区/技能/查物流/SKILL.md"), "查物流的四步。\n")
        return base
    }

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
    }

    private fun settingsBytes(): ByteArray {
        val dump = BackupRules.dumpSettings(
            mapOf(
                "zhengdao-settings" to mapOf(
                    "workspace_mode" to "default",
                    "user_keys_openai" to "sk-should-never-ship",
                    "api_key" to "x",
                ),
                "zhengdao-appearance" to mapOf("appearance" to "dark"),
            ),
        )
        return dump.json.toByteArray(Charsets.UTF_8)
    }

    private fun export(dest: File, mounts: List<BackupMount>, includeChat: Boolean = false): ExportOutcome {
        val walk = BackupWalker.walk(mounts)
        return BackupExporter.export(
            dest, walk.files,
            listOf(InMemoryFile(BackupImporter.SETTINGS_PATH, BackupGroup.SETTINGS, settingsBytes())),
            walk.skipped,
            ExportMeta("test-1.0", includeChat, 1_762_400_000_000L),
        )
    }

    private fun readZip(zip: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipFile(zip).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                out[e.name] = zf.getInputStream(e).use { it.readBytes() }
            }
        }
        return out
    }

    private fun zipOf(dest: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(dest.outputStream(), Charsets.UTF_8).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return dest
    }

    // ── ① 清单与内容一致 ─────────────────────────────────────────────────────

    @Test
    fun 导出清单逐条与实际字节一致且不含密钥与聊天原文() {
        val a = makeTree(File(root, "A"))
        val zip = File(root, "out.zip")
        val r = export(zip, mountsUnder(a))
        val ok = r as? ExportOutcome.Ok ?: error("导出失败：$r")

        val files = readZip(ok.file)
        val manifest = BackupManifestCodec.decode(files.getValue("manifest.json").toString(Charsets.UTF_8))
        val m = (manifest as? ManifestRead.Ok)?.manifest ?: error("清单读不回：$manifest")

        assertEquals("条目数必须与清单声明一致", m.entryCount, m.entries.size)
        assertEquals("清单之外不该有别的文件", m.entries.size + 1, files.size)
        for (e in m.entries) {
            val bytes = files[e.path] ?: error("清单里有、包里没有：${e.path}")
            assertEquals("字节数：${e.path}", e.size, bytes.size.toLong())
            assertEquals("hash：${e.path}", e.sha256, BackupRules.sha256Of(bytes))
        }
        assertEquals("字节总数", m.totalBytes, m.entries.sumOf { it.size })

        // 四组都在
        assertTrue("账本应有 1 条", m.countIn(BackupGroup.LEDGER) >= 1)
        assertTrue("人设与记忆应有 2 条", m.countIn(BackupGroup.MEMORY) >= 2)
        assertTrue("技能应有 2 条", m.countIn(BackupGroup.SKILLS) == 2)
        assertEquals("设置 1 条", 1, m.countIn(BackupGroup.SETTINGS))
        assertEquals("默认不含聊天原文", 0, m.countIn(BackupGroup.CHAT))
        assertFalse("默认 includesChat 必须是 false", m.includesChat)

        // 判据「导出里没有 key 与正文」
        assertFalse("包里不许有聊天正文", files.keys.any { it.startsWith("chat/") })
        val settingsJson = files.getValue(BackupImporter.SETTINGS_PATH).toString(Charsets.UTF_8)
        assertFalse("设置里不许有 user_keys_openai", settingsJson.contains("user_keys_openai"))
        assertFalse("设置里不许有 sk- 形状的值", settingsJson.contains("sk-should-never-ship"))
        assertTrue("正常设置要在（否则就是把整份设置丢了）", settingsJson.contains("workspace_mode"))
        assertTrue("账本按扩展名归组", m.entries.first { it.path == "memory/global/决策记录.jsonl" }.group == BackupGroup.LEDGER)
        assertTrue("人设归记忆组", m.entries.first { it.path == "memory/global/人设.md" }.group == BackupGroup.MEMORY)
    }

    // ── ② 同版本可完整还原 ───────────────────────────────────────────────────

    @Test
    fun 同版本导出再导入可完整还原行数与字节() {
        val a = makeTree(File(root, "A"))
        val zip = export(File(root, "out.zip"), mountsUnder(a)).let { (it as ExportOutcome.Ok).file }

        val b = File(root, "B")
        val before = readAllText(a)
        val preview = (BackupImporter.open(zip, mountsUnder(b), File(root, "work")) as OpenResult.Ready).preview
        assertEquals("全新机器上不该有冲突", 0, preview.conflicts.size)

        val applied = BackupImporter.apply(preview, emptySet())
        assertTrue("应当写入：$applied", applied is ApplyOutcome.Ok)
        assertEquals("新增条数", preview.news.size, (applied as ApplyOutcome.Ok).applied)

        val after = readAllText(b)
        assertEquals("行数与字节必须一致", before, after)
        for ((rel, text) in before) {
            assertEquals("$rel 的行数", text.lines().size, after.getValue(rel).lines().size)
            assertEquals("$rel 的字节", text.toByteArray(Charsets.UTF_8).size, after.getValue(rel).toByteArray(Charsets.UTF_8).size)
        }
        assertEquals("设置快照要能被壳层拿到", true, preview.settingsJson?.contains("workspace_mode") == true)
    }

    /** 把一棵树读成「相对路径 → 文本」，只收四个挂载点覆盖的范围。 */
    private fun readAllText(base: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val roots = listOf("抱朴记忆", "工作区/抱朴记忆", "抱朴记忆/技能", "工作区/技能")
        for (r in roots) {
            val dir = File(base, r)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile }.forEach {
                out[it.relativeTo(base).path.replace('\\', '/')] = it.readText(Charsets.UTF_8)
            }
        }
        return out.toSortedMap()
    }

    // ── ③ 冲突不覆盖 ────────────────────────────────────────────────────────

    @Test
    fun 冲突默认不覆盖勾了才覆盖() {
        val a = makeTree(File(root, "A"))
        val zip = export(File(root, "out.zip"), mountsUnder(a)).let { (it as ExportOutcome.Ok).file }

        val b = makeTree(File(root, "B"))
        val target = File(b, "抱朴记忆/决策记录.jsonl")
        target.writeText("{\"本地\":\"改过了\"}\n", Charsets.UTF_8)

        val preview = (BackupImporter.open(zip, mountsUnder(b), File(root, "work")) as OpenResult.Ready).preview
        val conflict = preview.conflicts.firstOrNull { it.entry.path == "memory/global/决策记录.jsonl" }
        assertNotNull("这一条必须被列成冲突", conflict)
        assertEquals("其余条目内容相同，不该进冲突", 1, preview.conflicts.size)

        val skipped = BackupImporter.apply(preview, emptySet()) as ApplyOutcome.Ok
        assertEquals("默认不覆盖：写入 0 条", 0, skipped.applied)
        assertEquals("本地内容必须原样还在", "{\"本地\":\"改过了\"}\n", target.readText(Charsets.UTF_8))

        val preview2 = (BackupImporter.open(zip, mountsUnder(b), File(root, "work2")) as OpenResult.Ready).preview
        val forced = BackupImporter.apply(preview2, setOf("memory/global/决策记录.jsonl")) as ApplyOutcome.Ok
        assertEquals("勾了才覆盖：只写这一条", 1, forced.applied)
        assertEquals("内容应换成备份里的", "{\"a\":1}\n{\"a\":2}\n", target.readText(Charsets.UTF_8))
    }

    // ── ④ 失败不留半状态 ────────────────────────────────────────────────────

    @Test
    fun 换名中途失败时已写的全部退回且不留残骸() {
        val a = makeTree(File(root, "A"))
        val zip = export(File(root, "out.zip"), mountsUnder(a)).let { (it as ExportOutcome.Ok).file }

        val b = makeTree(File(root, "B"))
        // 让 B 少一个「全局技能」文件 ⇒ 它是"新增"，会被自动写；它在失败项之前提交，必须被抹掉
        val freshTarget = File(b, "抱朴记忆/技能/整理书稿/SKILL.md")
        assertTrue("夹具：先删掉它", freshTarget.delete())
        // 本机这一条被用户改过 ⇒ 冲突（要显式勾选才会被覆盖）
        val ledger = File(b, "抱朴记忆/决策记录.jsonl")
        ledger.writeText("{\"本地\":\"改过了\"}\n", Charsets.UTF_8)
        // 把最后一项（skills/project/…）的落点占成**非空目录** ⇒ 换名必失败（文件替换不了非空目录）
        val blocked = File(b, "工作区/技能/查物流/SKILL.md")
        assertTrue(blocked.delete())
        blocked.mkdirs()
        write(File(blocked, "占位.txt"), "别动我\n")

        val preview = (BackupImporter.open(zip, mountsUnder(b), File(root, "work")) as OpenResult.Ready).preview
        val r = BackupImporter.apply(preview, setOf("memory/global/决策记录.jsonl"))
        assertTrue("必须报失败：$r", r is ApplyOutcome.Failed)
        assertTrue("且明确说已经退回去了", (r as ApplyOutcome.Failed).rolledBack)

        // 判据「失败不留半状态」：不管换名顺序如何，本机原来那一份必须一字未动
        assertEquals("被换掉的那条必须退回原样", "{\"本地\":\"改过了\"}\n", ledger.readText(Charsets.UTF_8))
        assertFalse("新文件不许留下", freshTarget.exists())
        assertTrue("被占位的那一项原样还在", File(blocked, "占位.txt").isFile)
        assertEquals("占位文件内容不许被改", "别动我\n", File(blocked, "占位.txt").readText(Charsets.UTF_8))

        // 不留残骸
        val leftovers = b.walkTopDown().filter {
            it.name.endsWith(BackupImporter.TMP_SUFFIX) || it.name.endsWith(BackupImporter.BAK_SUFFIX)
        }.toList()
        assertTrue("不许留 tmp/bak：$leftovers", leftovers.isEmpty())
    }

    // ── ⑤ 导入前校验清单 ────────────────────────────────────────────────────

    @Test
    fun 清单hash对不上时整包拒且一个文件都不写() {
        val payload = "{\"a\":1}\n".toByteArray(Charsets.UTF_8)
        val wrong = BackupManifest(
            appVersion = "test-1.0",
            createdAt = 1_762_400_000_000L,
            createdAtText = "2026-11-06 14:00:00",
            includesChat = false,
            entries = listOf(
                BackupEntry("memory/global/决策记录.jsonl", BackupGroup.LEDGER, payload.size.toLong(), "0".repeat(64)),
            ),
        )
        val zip = zipOf(
            File(root, "bad.zip"),
            mapOf(
                "manifest.json" to BackupManifestCodec.encode(wrong).toByteArray(Charsets.UTF_8),
                "memory/global/决策记录.jsonl" to payload,
            ),
        )
        val live = File(root, "B")
        val r = BackupImporter.open(zip, mountsUnder(live), File(root, "work"))
        assertTrue("必须拒：$r", r is OpenResult.Rejected)
        assertTrue("理由要说 hash：${(r as OpenResult.Rejected).reason}", r.reason.contains("hash"))
        assertFalse("本机一个文件都不许被建出来", File(live, "抱朴记忆/决策记录.jsonl").exists())
        assertEquals("临时目录要清干净", 0, File(root, "work").listFiles()?.size ?: 0)
    }

    @Test
    fun 包里有清单外文件时整包拒() {
        val payload = "x\n".toByteArray(Charsets.UTF_8)
        val m = BackupManifest(
            "test-1.0", 1L, "t", false,
            listOf(BackupEntry("memory/global/决策记录.jsonl", BackupGroup.LEDGER, payload.size.toLong(), BackupRules.sha256Of(payload))),
        )
        val zip = zipOf(
            File(root, "stray.zip"),
            mapOf(
                "manifest.json" to BackupManifestCodec.encode(m).toByteArray(Charsets.UTF_8),
                "memory/global/决策记录.jsonl" to payload,
                "chat/偷偷夹带.db" to "secret".toByteArray(Charsets.UTF_8),
            ),
        )
        val r = BackupImporter.open(zip, mountsUnder(File(root, "B")), File(root, "work"))
        assertTrue("必须拒：$r", r is OpenResult.Rejected)
        assertTrue("理由要点名那个文件", (r as OpenResult.Rejected).reason.contains("清单外"))
    }

    @Test
    fun 没有清单的zip直接拒() {
        val zip = zipOf(File(root, "plain.zip"), mapOf("memory/global/a.md" to "x\n".toByteArray()))
        val r = BackupImporter.open(zip, mountsUnder(File(root, "B")), File(root, "work"))
        assertTrue("必须拒：$r", r is OpenResult.Rejected)
        assertTrue((r as OpenResult.Rejected).reason.contains("manifest.json"))
    }
}
