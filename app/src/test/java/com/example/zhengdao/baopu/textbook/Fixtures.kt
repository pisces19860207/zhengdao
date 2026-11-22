// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.textbook

import org.json.JSONObject
import java.time.LocalDate

/** 所有测试的公共夹具：一条"刚好合法"的教材，负例只改一处。 */
internal object Fixtures {
    const val DAY: String = "2026-11-20"

    fun base(
        id: String = "TB-001",
        sourceType: String = "文献",
        source: String = "786 评测集 README §三",
        asOf: String = DAY,
        domain: String = "规范域",
        content: String = "字段 [字段:verdict] 只取三档；判据 [判据:NAR-CH-01] 为该档样例。",
        strength: String = "强",
        scope: String = "叙事连续性判定",
        revocable: Boolean = true,
        supersedes: String = "",
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("sourceType", sourceType)
        .put("source", source)
        .put("asOf", asOf)
        .put("domain", domain)
        .put("content", content)
        .put("strength", strength)
        .put("scope", scope)
        .put("revocable", revocable)
        .put("supersedes", supersedes)

    fun entry(
        id: String = "TB-001",
        asOf: String = DAY,
        supersedes: String = "",
        sourceType: SourceType = SourceType.文献,
        scope: String = "叙事连续性判定",
        content: String = "字段 [字段:verdict] 只取三档。",
    ): TextbookEntry = TextbookEntry(
        id = id,
        sourceType = sourceType,
        source = "786 评测集 README §三",
        asOf = asOf,
        domain = Domain.规范域,
        content = content,
        strength = "强",
        scope = scope,
        revocable = true,
        supersedes = supersedes,
    )

    fun codes(validation: Validation): String = validation.rejections.joinToString(",") { it.code.name }

    fun messages(validation: Validation): String = validation.rejections.joinToString("；") { it.message }

    fun oldDay(days: Long): String = LocalDate.of(2026, 11, 22).minusDays(days).toString()
}
