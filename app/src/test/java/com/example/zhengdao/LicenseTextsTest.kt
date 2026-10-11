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
     * 与 `| GNU GPL v3.0 | 仓库根 [`LICENSE`](../LICENSE) | … | 35,149 | 674 | `3972…` |`。
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
                    token == "LICENSE" -> token           // 仓库根 LICENSE
                    else -> "licenses/$token"             // licenses/ 下的四份正文
                }
                out[path] = Record(bytes, lines, sha)
            }
        return out
    }

    private data class Record(val bytes: Long, val lines: Int, val sha256: String)

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

    /** 行数口径与 README 一致：以 `\n` 计行，末行有换行符就不多算一行（文件由 eol=lf 保底）。 */
    private fun lineCount(text: String): Int =
        text.count { it == '\n' } + if (text.endsWith("\n")) 0 else 1

    @Test
    fun `README 记的每一份正文都与仓库文件逐字节一致`() {
        val records = parseRecords()
        assertEquals(
            "README 的两张表应记 6 份正文（licenses/ 四份 + 仓库根 LICENSE + 字体 OFL.txt）",
            6,
            records.size,
        )
        records.forEach { (path, rec) ->
            val f = File(repo, path)
            assertTrue("README 提到的正文不存在：$path", f.isFile)
            assertEquals(
                "$path 的字节数与 README 不符（在 Windows 上先看 .gitattributes 的 eol=lf 是否生效）",
                rec.bytes,
                f.length(),
            )
            val text = f.readText(Charsets.UTF_8)
            assertEquals("$path 的行数与 README 不符", rec.lines, lineCount(text))
            assertEquals("$path 的 SHA256 与 README 不符", rec.sha256, sha256(f))
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
        listOf("Apache-2.0.txt", "BSD-3-Clause.txt", "GPL-2.0.txt", "GPL-3.0.txt", "LGPL-3.0.txt").forEach {
            assertTrue("装配断言里漏了 $it —— 少一份就等于 APK 里没有它", g.contains(it))
        }
    }
}
