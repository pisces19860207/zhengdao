// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 收尾闸的验收器（纯函数，不碰数据库、不碰 Android）：
// 「抱朴说做完了」之后，机械核对这一轮写过的产物，不靠模型自己声明。
//
// 设计边界（用户口径 2026-10-17）：
// - 只做机械判定：文件存在 / 非空 / 合法 UTF-8 / JSON 可解析。
// - **不验「写得好不好」**：审美与内容判断永远归人，机器硬做只会假打绿灯。
package com.example.zhengdao.baopu.verify

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** 一条验收发现（哪条路径、哪项没过、凭什么判的）。 */
data class Finding(
    val path: String,
    val check: String,
    val detail: String,
)

object ArtifactVerifier {

    const val CHECK_ESCAPE = "escape"
    const val CHECK_MISSING = "missing"
    const val CHECK_EMPTY = "empty"
    const val CHECK_NOT_FILE = "not_a_file"
    const val CHECK_BAD_ENCODING = "bad_encoding"
    const val CHECK_BAD_JSON = "bad_json"

    /** 这些扩展名做 UTF-8 严格解码与（若 json）解析；二进制一律不碰，避免误报。 */
    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "xml", "yaml", "yml",
        "kt", "kts", "java", "sql", "html", "htm", "log", "ini", "conf", "properties",
    )

    /** 单文件最多读这么多字节做校验（再大只验存在与非空，避免收尾卡住）。 */
    const val MAX_CHECK_BYTES = 4L * 1024 * 1024

    /**
     * 验一批「本轮写过的相对路径」。
     * @return 失败项；空列表 = 全过。
     */
    fun check(root: File, touched: Collection<String>): List<Finding> {
        val out = ArrayList<Finding>()
        val seen = LinkedHashSet<String>()
        for (raw in touched) {
            val rel = normalize(raw)
            if (rel == null) {
                out += Finding(raw.trim(), CHECK_ESCAPE, "路径越界或不可解析，已拒绝")
                continue
            }
            if (seen.add(rel)) out += checkOne(root, rel)
        }
        return out
    }

    /** 规范成工作区内的相对路径；越界/空/绝对路径/带盘符/带 scheme ⇒ null。 */
    fun normalize(raw: String): String? {
        val t = raw.trim().replace('\\', '/')
        if (t.isEmpty()) return null
        // 绝对路径一律拒：工作区内的产物只能是相对路径（否则无法判断它落在哪）
        if (t.startsWith("/")) return null
        if (t.contains("://")) return null
        if (t.length >= 2 && t[1] == ':') return null
        val parts = t.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun checkOne(root: File, rel: String): List<Finding> {
        val f = File(root, rel)
        if (!f.exists()) return listOf(Finding(rel, CHECK_MISSING, "本轮写过，收尾时找不到"))
        if (!f.isFile) return listOf(Finding(rel, CHECK_NOT_FILE, "路径存在但不是文件"))
        if (f.length() == 0L) return listOf(Finding(rel, CHECK_EMPTY, "文件是 0 字节"))

        val out = ArrayList<Finding>(2)
        val ext = f.extension.lowercase()
        if (ext in TEXT_EXTS) {
            decodeFailure(f)?.let { out += Finding(rel, CHECK_BAD_ENCODING, it) }
        }
        if (ext == "json") {
            parseFailure(f)?.let { out += Finding(rel, CHECK_BAD_JSON, it) }
        }
        return out
    }

    private fun decodeFailure(f: File): String? {
        val bytes = readHead(f) ?: return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            null
        } catch (e: CharacterCodingException) {
            "不是合法 UTF-8（首个坏字节位置约 ${firstBadByte(bytes)}）"
        }
    }

    private fun parseFailure(f: File): String? {
        val bytes = readHead(f) ?: return null
        val text = String(bytes, StandardCharsets.UTF_8).trim()
        return try {
            if (text.startsWith("{")) JSONObject(text) else JSONArray(text)
            null
        } catch (e: Exception) {
            "JSON 解析失败：${(e.message ?: e.javaClass.simpleName).take(80)}"
        }
    }

    /** 读文件头（超上限只读前 4 MB）；读不出来返回 null ⇒ 不误判为坏文件。 */
    private fun readHead(f: File): ByteArray? = try {
        f.inputStream().use { ins ->
            val len = minOf(f.length(), MAX_CHECK_BYTES).toInt()
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = ins.read(buf, read, len - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) null else buf.copyOf(read)
        }
    } catch (e: Exception) {
        null
    }

    /** 二分找第一个让前缀解不出来的位置（只用于回报，不用于判定）。 */
    private fun firstBadByte(bytes: ByteArray): Int {
        var lo = 0
        var hi = bytes.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val ok = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, mid))
                true
            } catch (e: CharacterCodingException) {
                false
            }
            if (ok) lo = mid + 1 else hi = mid
        }
        return lo.coerceAtMost(bytes.size)
    }

    /** 回灌给模型的自检报告（纯文本；RunEngine 会套 DataFence 再发）。 */
    fun render(findings: List<Finding>): String {
        if (findings.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("自检未通过 ").append(findings.size).append(" 项，先用工具修好再回答：\n")
        for (f in findings.take(20)) {
            sb.append("- ").append(f.path).append("：").append(label(f.check))
            if (f.detail.isNotBlank()) sb.append("（").append(f.detail).append("）")
            sb.append('\n')
        }
        if (findings.size > 20) sb.append("……还有 ").append(findings.size - 20).append(" 项\n")
        sb.append("修完直接回答，不用复述这份清单。")
        return sb.toString()
    }

    /** 给人看的一句话摘要（界面用）。 */
    fun summarize(findings: List<Finding>): String {
        if (findings.isEmpty()) return "自检通过"
        val byCheck = findings.groupingBy { it.check }.eachCount()
        val parts = byCheck.entries.joinToString("、") { "${label(it.key)} ${it.value}" }
        return "自检未通过 ${findings.size} 项（$parts）"
    }

    /** 检查项的人话名。 */
    fun label(check: String): String = when (check) {
        CHECK_ESCAPE -> "路径越界"
        CHECK_MISSING -> "文件不存在"
        CHECK_EMPTY -> "文件为空"
        CHECK_NOT_FILE -> "不是文件"
        CHECK_BAD_ENCODING -> "不是合法 UTF-8"
        CHECK_BAD_JSON -> "JSON 解析不了"
        else -> check
    }
}
