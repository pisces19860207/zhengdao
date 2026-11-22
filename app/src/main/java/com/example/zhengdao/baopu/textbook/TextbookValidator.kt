// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 A · 自洽校验四条拒收。每条拒收带稳定原因码，正反例都在测试里钉死；
// 经验类缺样本量不进主库、只进待验证池（fatal=false），其余三类直接拒收。
package com.example.zhengdao.baopu.textbook

import org.json.JSONObject

internal enum class RejectCode {
    MISSING_FIELD, ILLEGAL_ENUM, MISSING_SOURCE, MISSING_AS_OF, INVALID_AS_OF,
    EXPERIENCE_MISSING_SAMPLE, NO_FIELD_REFERENCE, UNKNOWN_REFERENCE,
    FORBID_BEHAVIOR, FORBID_MINOR, FORBID_ACTIONABLE,
}

/** MAIN＝可入库；PENDING＝只进待验证池；REJECTED＝拒收。 */
internal enum class Pool { MAIN, PENDING, REJECTED }

internal data class Rejection(val code: RejectCode, val message: String, val fatal: Boolean = true)

internal data class Validation(val pool: Pool, val rejections: List<Rejection>) {
    val ok: Boolean get() = pool == Pool.MAIN
}

internal object TextbookValidator {
    /** 经验类的样本量写在 scope 里，形如 n=24；样本 <20 只能进待验证池。 */
    private val SAMPLE: Regex = Regex("n\\s*=\\s*([0-9]+)")

    /**
     * 禁内容三条。窄口径：只抓"对人下指令/未成年/能照着做的真实细节"，
     * 不抓教材里正常的判据描述（如"判据要求判未知"）。
     */
    private val FORBIDDEN: List<Pair<RejectCode, Regex>> = listOf(
        RejectCode.FORBID_BEHAVIOR to Regex(
            "你应该|你必须|请你|教你|手把手|照着做|按照以下操作步骤|操作步骤如下|第一步.{0,40}第二步",
        ),
        RejectCode.FORBID_MINOR to Regex(
            "未成年人|未成年|儿童|少年|幼女|不满\\s*14\\s*岁|不满十四岁|14\\s*岁以下|十四岁以下",
        ),
        RejectCode.FORBID_ACTIONABLE to Regex(
            "https?://|www\\.|[0-9]{11}[0-9]{0,7}|[0-9]{17}[0-9Xx]|" +
                "-?[0-9]{1,3}\\.[0-9]{4,}\\s*[,，]\\s*-?[0-9]{1,3}\\.[0-9]{4,}|" +
                "[0-9]+\\s*(mg|克|毫升|ml)\\s*(口服|服用|注射)",
        ),
    )

    fun validate(raw: JSONObject): Validation {
        when (val parsed = TextbookSchema.parse(raw)) {
            is EntryParse.Err -> return Validation(Pool.REJECTED, schemaRejections(parsed))
            is EntryParse.Ok -> {
                val entry = parsed.entry
                val rs = mutableListOf<Rejection>()
                rs += provenance(entry)
                rs += experience(entry)
                rs += references(entry)
                rs += forbidden(entry)
                val pool = when {
                    rs.any { it.fatal } -> Pool.REJECTED
                    rs.any { !it.fatal } -> Pool.PENDING
                    else -> Pool.MAIN
                }
                return Validation(pool, rs)
            }
        }
    }

    private fun schemaRejections(err: EntryParse.Err): List<Rejection> {
        val rs = err.missing.map { Rejection(RejectCode.MISSING_FIELD, "缺字段：$it") }.toMutableList()
        if ("source" in err.missing) {
            rs += Rejection(RejectCode.MISSING_SOURCE, "缺 source：教材必须有出处，否则不可回查")
        }
        if ("asOf" in err.missing) {
            rs += Rejection(RejectCode.MISSING_AS_OF, "缺 asOf：教材必须有基准日，否则无法判过期")
        }
        err.illegal.forEach { rs += Rejection(RejectCode.ILLEGAL_ENUM, "非法取值：$it") }
        return rs
    }

    private fun provenance(entry: TextbookEntry): List<Rejection> {
        val rs = mutableListOf<Rejection>()
        if (entry.source.isBlank()) {
            rs += Rejection(RejectCode.MISSING_SOURCE, "source 为空：教材必须有出处")
        }
        if (entry.asOf.isBlank()) {
            rs += Rejection(RejectCode.MISSING_AS_OF, "asOf 为空：教材必须有基准日")
        } else if (runCatching { java.time.LocalDate.parse(entry.asOf) }.isFailure) {
            rs += Rejection(RejectCode.INVALID_AS_OF, "asOf 不是 ISO 日期：${entry.asOf}")
        }
        return rs
    }

    private fun experience(entry: TextbookEntry): List<Rejection> {
        if (entry.sourceType != SourceType.经验总结) return emptyList()
        val n = SAMPLE.find(entry.scope)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (n < 20) {
            return listOf(
                Rejection(
                    RejectCode.EXPERIENCE_MISSING_SAMPLE,
                    "经验类样本量不足 20（scope 须写 n=<样本数>，派工单追加：样本<20 只能进待验证池，不许直接改参数）",
                    fatal = false,
                ),
            )
        }
        return emptyList()
    }

    private fun references(entry: TextbookEntry): List<Rejection> {
        if (!RefCatalog.hasRef(entry.content)) {
            return listOf(
                Rejection(
                    RejectCode.NO_FIELD_REFERENCE,
                    "引不到具体字段/判据 id：这是读书笔记，不是教材",
                ),
            )
        }
        val bad = RefCatalog.invalidRefs(entry.content)
        if (bad.isEmpty()) return emptyList()
        return listOf(
            Rejection(RejectCode.UNKNOWN_REFERENCE, "引用不在 786 字段/NAR 判据闭集内：${bad.joinToString("、")}"),
        )
    }

    private fun forbidden(entry: TextbookEntry): List<Rejection> = FORBIDDEN.mapNotNull { (code, re) ->
        if (re.containsMatchIn(entry.content)) {
            Rejection(code, "禁内容命中（$code）：该条不得入库")
        } else {
            null
        }
    }
}
