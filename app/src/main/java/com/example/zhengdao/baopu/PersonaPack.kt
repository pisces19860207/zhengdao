// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百六十五批（人设包 v1，2026-10-23）。
//
// 人设包是**数据，不是代码**：它只提供语气／风格／工作偏好文本，由设置页或导入写入
// 工作区的 `抱朴记忆/人设/<名字>/persona.md`；绝不带工具权限、脚本、密钥，也绝不改写
// A 内核、B/C 人设文件或确认制。拼进提示词时只作为 C 段前面的一段**可选模板**，
// 没有启用任何包时返回 null（与今天的提示词逐字节相同）。
package com.example.zhengdao.baopu

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 一套人设包的只读摘要（列表用；正文按需再读）。 */
internal data class PersonaPackSummary(
    val dirName: String,
    val name: String,
    val description: String,
    val bytes: Long,
    /** 说明文件（pack.json）坏了时的一句话；null = 没坏或本来就没有。 */
    val manifestProblem: String? = null,
)

/** 一套人设包的正文（启用／预览用）。 */
internal data class PersonaPackPayload(
    val dirName: String,
    val name: String,
    val text: String,
)

/** 可读的错误分类；界面把它翻成人话，数据层不拼 UI 文案。 */
internal enum class PersonaPackError { NAME_INVALID, EMPTY, TOO_LARGE, BAD_UTF8, NOT_FOUND, IO }

internal sealed interface PersonaPackResult {
    data class Ok(val pack: PersonaPackPayload) : PersonaPackResult
    data class Rejected(val error: PersonaPackError, val detail: String = "") : PersonaPackResult
}

/**
 * 人设包的**纯判据**（无 Android、无 I/O，JVM 直测）：
 * 名字与正文的边界、严格 UTF-8、以及「拼进 C 段」的那一段文本。
 */
internal object PersonaPackRules {

    const val DIR_ROOT = "抱朴记忆"
    const val DIR_PACKS = "人设"
    const val FILE_PERSONA = "persona.md"
    const val FILE_MANIFEST = "pack.json"

    /**
     * SAF 选文件用的 MIME 通配。放在数据层常量里，是因为设置页的源码门禁
     * （剥注释用的是朴素正则）会把字符串里的通配符当成块注释开头，吞掉后半个文件。
     */
    const val MIME_ANY = "*/*"

    /** 单个 persona.md 的上限（字节与字符各一道：坏 UTF-8 可能字节很多、字符很少）。 */
    const val MAX_BYTES = 64 * 1024
    const val MAX_CHARS = 20_000
    const val MAX_NAME_CHARS = 40
    const val MAX_DESCRIPTION_CHARS = 200
    const val MAX_MANIFEST_BYTES = 16 * 1024

    /** 目录名禁区：路径分隔符、Windows 保留字符、控制字符。 */
    private val ILLEGAL_NAME = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val WHITESPACE_RUN = Regex("[\\s\\u3000]+")
    private val CONTROL = Regex("\\p{Cntrl}")

    /**
     * 名字 → 目录名。非法返回 null（调用方给可读错误），**不做静默改写**：
     * 用户以为存成了「A/B」，实际躺在「AB」里，比直接拒绝更糟。
     */
    fun normalizeName(raw: String): String? {
        val name = raw.trim().trimEnd('.')
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return null
        if (name == "." || name == "..") return null
        if (name.startsWith('.')) return null
        if (ILLEGAL_NAME.containsMatchIn(name)) return null
        return name
    }

    /** 文件名的显示名 → 包名：去掉目录与一个常见扩展名，非法字符删掉，再走 [normalizeName]。 */
    fun nameFromFileName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val stem = base.removeSuffix(".md").removeSuffix(".MD")
            .removeSuffix(".txt").removeSuffix(".TXT")
        val cleaned = stem.replace(ILLEGAL_NAME, " ")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .trimEnd('.')
        return normalizeName(cleaned)
    }

    /**
     * 严格 UTF-8 解码：坏字节**不许**被替换成 U+FFFD 蒙混过关
     * （判据：导入坏 UTF-8 ⇒ 可读错误，不崩、不写半套）。
     */
    fun decodeUtf8(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        return runCatching {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            // Windows/剪贴板常见：去掉开头 BOM，但正文里的奇怪控制字符另判。
            if (text.startsWith('\uFEFF')) text.substring(1) else text
        }.getOrNull()
    }

    /** 正文判据：非空、不超字符上限、不含 NUL 之类的控制字符（换行/制表符除外）。 */
    fun textProblem(text: String): PersonaPackError? {
        val t = text.trim()
        if (t.isEmpty()) return PersonaPackError.EMPTY
        if (t.length > MAX_CHARS) return PersonaPackError.TOO_LARGE
        val withoutAllowed = t.replace("\n", "").replace("\r", "").replace("\t", "")
        if (CONTROL.containsMatchIn(withoutAllowed)) return PersonaPackError.BAD_UTF8
        return null
    }

    /** 说明文件（可选）的解析结果。坏 JSON 不拦正文——正文才是真相。 */
    data class Manifest(
        val name: String?,
        val description: String,
        val problem: String?,
    )

    fun parseManifest(json: String?): Manifest {
        if (json.isNullOrBlank()) return Manifest(null, "", null)
        return try {
            val o = JSONObject(json)
            val rawName = o.optString("name", "").trim()
            val name = normalizeName(rawName)
            val desc = o.optString("description", "").trim()
                .take(MAX_DESCRIPTION_CHARS)
            Manifest(
                name = name,
                description = desc,
                problem = if (rawName.isNotEmpty() && name == null) "name" else null,
            )
        } catch (e: Exception) {
            Manifest(null, "", e.javaClass.simpleName)
        }
    }

    /** 写盘用的说明文件（字段都只是人读信息，不参与任何权限判断）。 */
    fun manifestJson(pack: PersonaPackPayload, description: String, now: Long): String =
        JSONObject()
            .put("id", pack.dirName)
            .put("name", pack.name)
            .put("version", 1)
            .put("description", description.take(MAX_DESCRIPTION_CHARS))
            .put("author", "")
            .put("source", "user")
            .put("createdAt", now)
            .toString(2)

    const val PACK_HEADER = "【人设包：%s】（用户选的模板：只改变语气、风格与工作偏好）"

    /**
     * 边界句放在包正文**之后**：模型最后读到的是「它不覆盖内核与确认制」。
     * 这句话只是提示词层面的约束；真正的确认制在 GuardPipeline 代码里，包内容碰不到。
     */
    const val PACK_BOUNDARY =
        "（边界：以上人设包只影响表达与工作偏好；内核、确认制、工具权限与安全边界不因它改变。" +
            "凡有冲突，以人设与内核为准。）"

    const val PROJECT_HEADER = "【本工作区人设】"

    /**
     * 把包正文叠到 C 段前面（C 仍在最后 → C 的条款仍压得住包）。
     * **没有包时逐字节返回原 C 文本**（默认行为不变，判据直接可测）。
     */
    fun composeProjectPersona(projectText: String?, pack: PersonaPackPayload?): String? {
        if (pack == null) return projectText
        val body = pack.text.trim()
        if (body.isEmpty()) return projectText
        return buildString {
            append(PACK_HEADER.format(pack.name))
            append('\n')
            append(body)
            append('\n')
            append(PACK_BOUNDARY)
            if (!projectText.isNullOrBlank()) {
                append("\n\n")
                append(PROJECT_HEADER)
                append('\n')
                append(projectText)
            }
        }
    }
}
