// 独立开发声明：本文件为本项目从零编写。
// 许可证正文的「文档记录 ↔ 仓库文件 ↔ 随包装配」三重一致性（E-104 的缺口不许复发）。
package com.example.zhengdao

import org.junit.Assert.assertEquals
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
     * 第一方声明 `LICENSE` 不进这两张表（它不是第三方正文），故当前两张表共 9 条 = `licenses/` 八份 + `OFL.txt`。
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
                    else -> "licenses/$token"             // licenses/ 下的八份正文
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
            "README 的两张表应记 9 份正文（licenses/ 八份 + 字体 OFL.txt）",
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
    fun `gitattributes 仍把这六份文本钉在 LF - 换行不同哈希就不同`() {
        val ga = File(repo, ".gitattributes")
        assertTrue(".gitattributes 必须存在", ga.isFile)
        val text = ga.readText(Charsets.UTF_8)
        listOf(
            "licenses/*.txt text eol=lf",
            "LICENSE text eol=lf",
            "app/src/main/assets/fonts/OFL.txt text eol=lf",
        ).forEach {
            assertTrue(
                ".gitattributes 缺少「$it」——Windows 检出会变 CRLF，README 里的 SHA256 就对不上了" +
                    "（这正是 E-104 补记里踩过的坑）",
                text.contains(it),
            )
        }
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
        listOf(
            "Apache-2.0.txt",
            "BSD-2-Clause.txt",
            "BSD-3-Clause.txt",
            "BSD-3-Clause-zstd.txt",
            "GPL-2.0.txt",
            "GPL-3.0.txt",
            "LGPL-3.0.txt",
            "LICENSE-zhengdao.txt",
            "NOTICES.txt",
        ).forEach {
            assertTrue("装配断言里漏了 $it —— 少一份就等于 APK 里没有它", g.contains(it))
        }
    }
}
