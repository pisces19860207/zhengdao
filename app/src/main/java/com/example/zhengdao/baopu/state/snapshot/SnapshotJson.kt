// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 统一状态快照：确定性 JSON 出口。
// 手写渲染而非通用序列化：固定键序、固定换行、固定数字格式，不依赖平台默认行为；
// 同输入逐字节一致，且每格都带溯源（单号·符号·文件:行号）。
package com.example.zhengdao.baopu.state.snapshot

/** 快照 → JSON 文本。纯函数：无 IO、无墙钟、无随机、无区域设置依赖。 */
internal object SnapshotJson {

    fun render(snapshot: Snapshot): String = buildString {
        append("{\n")
        append("  \"version\": ").append(quote(snapshot.version)).append(",\n")
        append("  \"tick\": ").append(snapshot.tick).append(",\n")
        append("  \"sections\": [\n")
        snapshot.sections.forEachIndexed { si, section ->
            append("    {\n")
            append("      \"id\": ").append(quote(section.id)).append(",\n")
            append("      \"fields\": [\n")
            section.fields.forEachIndexed { fi, field ->
                append("        ")
                append(renderField(field))
                if (fi != section.fields.lastIndex) append(',')
                append('\n')
            }
            append("      ]\n")
            append("    }")
            if (si != snapshot.sections.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    private fun renderField(field: SnapshotField): String {
        val spec = SnapshotSpec.of(field.id)
        val source = "${spec.sourceOrder} · ${spec.sourceSymbol} · ${spec.sourceFile}"
        return buildString {
            append("{\"id\": ").append(quote(field.id))
            when (val slot = field.slot) {
                is SnapshotSlot.Given -> {
                    append(", \"value\": ").append(quote(slot.text))
                    if (slot.number) append(", \"number\": true")
                }
                is SnapshotSlot.Absent -> append(", \"absent\": ").append(quote(slot.reason))
            }
            append(", \"source\": ").append(quote(source)).append('}')
        }
    }

    /** 只认 ASCII 控制字符与 JSON 两个元字符；非 ASCII 原样输出（UTF-8 由写出方决定）。 */
    private fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
