// 独立开发声明：本文件为本项目原创。
// 许可证正文的「文档记录 ↔ 仓库文件 ↔ 随包装配」三重一致性（E-104 的缺口不许复发）。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LicenseTextsTest {

    /** 单测工作目录 = app/，仓库根在上一级（同 AgentManifestVerifyTest 的写法）。 */
    private val repo = File("..")

    /**
     * 从 `licenses/README.md` 的两张表里读出「文件路径 → (字节数, 行数, SHA256)」。
     *
     * 表行形如：`| [`Apache-2.0.txt`](Apache-2.0.txt) | Apache License 2.0 | … | 11,358 | 202 | `cfc7…` |`
     * 与 `| SIL OFL 1.1 | `app/src/main/assets/fonts/OFL.txt` | … | 4,572 | 95 | `6728…` |`。
     * 取路径的规则：该行里**第一个**「像路径」的反引号片段（`*.txt` 或 `LICENSE`）。
     * 第一方声明 `LICENSE` 不进这两张表（它不是第三方正文），故当前两张表共 9 条 =
     * `licenses/` 九条（八份许可证正文 ＋ `NOTICES.txt` 汇总；其中 LGPL-2.1 是 252 加的）。
     * 字体 `OFL.txt` 那一条**已不在本仓**：2026-10-28 终端连带字体搬去 `zhengdao-term`，
     * README 的 OFL 行同时删了（所以这里的 9 不是「加了一份又丢了一份」的巧合，是账真的平了）。
     */
    private fun parseRecords(): Map<String, Record> {
        val md = File(repo, "licenses/README.md")
        assertTrue("licenses/README.md 必须存在（它是正文的来源与校验记录）", md.isFile)
        val out = LinkedHashMap<String, Record>()
        md.readText(Charsets.UTF_8).lineSequence()
            .filter { it.startsWith("|") }
            .forEach { row ->
                val cells = row.trim().trim('|').split('|').map { it.trim() }
                val sha = (cells.lastOrNull() ?: "").trim('`')
                if (!Regex("^[0-9a-f]{64}$").matches(sha)) return@forEach
                val lines = cells.getOrNull(cells.size - 2)?.replace(",", "")?.toIntOrNull() ?: return@forEach
                val bytes = cells.getOrNull(cells.size - 3)?.replace(",", "")?.toLongOrNull() ?: return@forEach
                // 该行里第一个「像路径」的反引号片段：Apache-2.0.txt / LICENSE / app/src/main/assets/fonts/OFL.txt
                val token = Regex("`([^`]+)`").findAll(row)
                    .map { it.groupValues[1] }
                    .firstOrNull { it == "LICENSE" || it.endsWith(".txt") }
                    ?: return@forEach
                val path = when {
                    token.contains('/') -> token          // app/src/main/assets/fonts/OFL.txt
                    token == "LICENSE" -> token           // 仓库根 LICENSE（历史形态；现已不进表）
                    else -> "licenses/$token"             // licenses/ 下的九份正文（252 加了 LGPL-2.1）
                }
                out[path] = Record(bytes, lines, sha)
            }
        return out
    }

    private data class Record(val bytes: Long, val lines: Int, val sha256: String)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 把文件读成「仓库内形态」：LF 换行。
     *
     * 本机 `core.autocrlf=true` 时，**老检出**的工作区文件是 CRLF（`.gitattributes` 的 `eol=lf` 只对
     * 新检出 / 被重写的文件生效），而 README 记的是仓库形态（LF）。这里核对的是「文档 ↔ 仓库内容」，
     * 不该因为本地检出陈旧而红 —— 随包那份也由构建期归一成 LF（见 `stageLicenseAssets`）。
     */
    private fun canonicalBytes(f: File): ByteArray =
        String(f.readBytes(), Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
            .toByteArray(Charsets.UTF_8)

    /** 行数口径与 README 一致：以 `\n` 计行，末行有换行符就不多算一行（文件由 eol=lf 保底）。 */
    private fun lineCount(text: String): Int =
        text.count { it == '\n' } + if (text.endsWith("\n")) 0 else 1

    @Test
    fun `README 记的每一份正文都与仓库文件逐字节一致`() {
        val records = parseRecords()
        assertEquals(
            "README 的两张表应记 9 条正文（licenses/ 九条 = 八份许可证正文 + NOTICES 汇总；" +
                "字体 OFL 正文已随终端搬去 zhengdao-term，本仓不再列）",
            9,
            records.size,
        )
        var stale = 0
        records.forEach { (path, rec) ->
            val f = File(repo, path)
            assertTrue("README 提到的正文不存在：$path", f.isFile)
            val raw = f.readBytes()
            val canonical = canonicalBytes(f)
            if (canonical.size != raw.size) stale++
            assertEquals(
                "$path 的字节数与 README 不符（README 记的是仓库内 LF 形态）",
                rec.bytes,
                canonical.size.toLong(),
            )
            assertEquals(
                "$path 的行数与 README 不符",
                rec.lines,
                lineCount(String(canonical, Charsets.UTF_8)),
            )
            assertEquals("$path 的 SHA256 与 README 不符", rec.sha256, sha256(canonical))
        }
        if (stale > 0) {
            println(
                "[LicenseTextsTest] 提示：$stale 份正文在本机工作区是 CRLF 形态（陈旧检出）；" +
                    "删掉重新检出即变 LF（`git ls-files --eol` 看 w/crlf → w/lf）。" +
                    "见 docs/ERRATA.md E-104 第三条补记。",
            )
        }
    }

    @Test
    fun `gitattributes 仍把许可正文钉在 LF - 换行不同哈希就不同（字体 OFL 已随终端搬走）`() {
        val ga = File(repo, ".gitattributes")
        assertTrue(".gitattributes 必须存在", ga.isFile)
        val text = ga.readText(Charsets.UTF_8)
        listOf(
            "licenses/*.txt text eol=lf",
            "LICENSE text eol=lf",
        ).forEach {
            assertTrue(
                ".gitattributes 缺少「$it」——Windows 检出会变 CRLF，README 里的 SHA256 就对不上了" +
                    "（这正是 E-104 补记里踩过的坑）",
                text.contains(it),
            )
        }
        // 字体那份 OFL 正文 2026-10-28 随终端搬去 zhengdao-term，本仓不再分发该文件，
        // `.gitattributes` 里那行也删了。这里改成**反向**断言：还留着这行说明有人把已搬走的东西写回来了。
        assertFalse(
            ".gitattributes 不该再钉 app/src/main/assets/fonts/OFL.txt——那个文件已随终端搬走，钉着一行无效规则",
            text.contains("app/src/main/assets/fonts/OFL.txt"),
        )
        // 同理：rootfs/ 自 248/250 起整仓搬去 zhengdao-term，本仓再给 rootfs/ 写属性规则就是死规则
        // （2026-10-28 已删掉 agents.json / agents.json.sig 两行）。谁把它们写回来，这里立刻红。
        assertFalse(
            ".gitattributes 不该再有 rootfs/ 规则——rootfs/ 已随终端搬去 zhengdao-term，留着是死规则",
            text.lineSequence().any { it.trimStart().startsWith("rootfs/") },
        )
    }

    @Test
    fun `构建期仍把 licenses 装进 assets - 否则 APK 里没有正文`() {
        val g = File("build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue("build.gradle.kts 里没有 stageLicenseAssets：随包正文的装配被删了", g.contains("stageLicenseAssets"))
        // AGP 9 起 assets 只能走 Variant API 的生成目录（sourceSets 不收 Provider）：
        // 这两句一起断言，改回旧写法或者把挂载删掉都会红。
        assertTrue("build.gradle.kts 里没有 addGeneratedSourceDirectory：assets 生成目录没挂上", g.contains("addGeneratedSourceDirectory"))
        assertTrue("build.gradle.kts 里没有 variant.sources.assets：挂载点被删了", g.contains("variant.sources.assets"))
        assertTrue(
            "build.gradle.kts 里没有 normalizeToLf：随包正文会随构建机的换行设置漂移（E-104 第三条补记）",
            g.contains("normalizeToLf"),
        )
    }

    /**
     * 分类账必须平（2026-10-28 第二百六十六批）。
     *
     * 装配任务里有 `PACKAGED`（随包）与 `ARCHIVED`（仅历史留档）两份清单，装配时按 `PACKAGED`
     * 白名单拷贝。这里在 JVM 侧把同一本账算一遍 —— 装配那边是 Gradle 任务，只有真打一次包才会报，
     * 而这条单测跑一次全量单测就能看见：
     *   ① 仓库 `licenses/` 下的每个 `.txt` 都被分类（两边都不在 ⇒ 有人加了正文忘了登记）；
     *   ② 两份清单不重叠（同一份既随包又不随包是自相矛盾）；
     *   ③ 清单里登记的文件都真的在仓库里。
     * 为什么用户在意：2026-10-28 他说「别把那些许可证也带进去了」——
     * 漏登记会让旧正文悄悄跟着新包走，多登记会让包缺正文，两种都在这条里红。
     */
    @Test
    fun `随包与留档两份清单覆盖了 licenses 下全部正文 - 且不重叠`() {
        val g = File("build.gradle.kts").readText(Charsets.UTF_8)
        fun listOf(name: String): List<String> {
            val i = g.indexOf("val $name = listOf(")
            assertTrue("build.gradle.kts 里找不到 $name 清单", i >= 0)
            val j = g.indexOf(")", i)
            return Regex("\"([^\"]+)\"").findAll(g.substring(i, j)).map { it.groupValues[1] }.toList()
        }
        val packaged = listOf("PACKAGED")
        val archived = listOf("ARCHIVED")

        assertTrue("PACKAGED 为空", packaged.isNotEmpty())
        assertTrue("ARCHIVED 为空", archived.isNotEmpty())
        assertEquals(
            "PACKAGED 与 ARCHIVED 有重叠：${packaged.intersect(archived.toSet())}",
            emptyList<String>(),
            packaged.intersect(archived.toSet()).toList(),
        )
        // README.md 是记录页（不是许可证正文），它随包但没有 .txt 后缀。
        val onDisk = File(repo, "licenses").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".txt") }
            .map { it.name }
            .sorted()
        val classified = (packaged + archived).filter { it.endsWith(".txt") }.sorted()
        assertEquals(
            "licenses/ 下的正文与两份清单不一致（未分类的会悄悄跟包 / 漏装的会让包缺正文）",
            onDisk,
            classified,
        )
        packaged.filter { it.endsWith(".txt") }.forEach {
            assertTrue("PACKAGED 里的 $it 在仓库里不存在", File(repo, "licenses/$it").isFile)
        }
        archived.filter { it.endsWith(".txt") }.forEach {
            assertTrue("ARCHIVED 里的 $it 在仓库里不存在（历史正文不许删）", File(repo, "licenses/$it").isFile)
        }
    }
}
