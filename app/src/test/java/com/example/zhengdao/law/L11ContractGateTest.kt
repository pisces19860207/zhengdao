// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L11 技能必须可证伪（契约面）**——本文件是**门禁**，会真红。
//
// ## 为什么这份是门禁
// 它断言的是**生产对象** `SkillContract.validate`：缺段/读不成的正文必须给出问题清单，
// 三段齐才返回空表。生产把校验改松（缺 `success` 也放行），这条**当场红**。
// （271-补：原来 L11 整份是"规范"；按"能红的就该进门禁账"拆开，这一半留在门禁账上，
//   另一半见 `spec/L11SnapshotSpec.kt`。）
//
// ## 判据（可执行）
// ① `appliesWhen`／`success`／`protected` 三段齐 ⇒ `validate` 返回空表；
// ② 缺 `success` ⇒ 报「缺 success 段」；一条契约都没有 ⇒ 报 `contract_missing`；
// ③ 补上 `success` 后不该再报这一条（防"一律报错"的假门禁）。
//
// ## 负例（本文件自证会红）
// 把缺 `success` 的正文改判为通过 ⇒ `L11_负例1_缺success段必须被契约校验拒` 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.SkillContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L11ContractGateTest {

    private val fullContract = """
        # 整理台账
        <!-- contract appliesWhen url_domain=a.com -->
        <!-- contract success element_appears=结果 -->
        <!-- contract protected path:/admin -->
        - 按日期排
    """.trimIndent()

    // ── 正例：三段齐 ⇒ 可证伪 ──────────────────────────────────────────────

    @Test
    fun `L11_正例_契约三段齐可证伪`() {
        assertEquals(
            "三段齐的契约不该报问题：${SkillContract.validate(fullContract)}",
            emptyList<String>(),
            SkillContract.validate(fullContract),
        )
        // 契约能被读回来（不是只"没报错"就算过）
        val parsed = SkillContract.parse(fullContract)
        assertTrue("三段齐必须解析成 Contracted：$parsed", parsed is SkillContract.Parsed.Contracted)
        assertTrue("解析出来必须算「齐」", (parsed as SkillContract.Parsed.Contracted).complete)
    }

    // ── 负例：缺契约段 ⇒ 不许入库 ───────────────────────────────────────────

    @Test
    fun `L11_负例1_缺success段必须被契约校验拒`() {
        val noSuccess = """
            # 整理台账
            <!-- contract appliesWhen url_domain=a.com -->
            - 按日期排
        """.trimIndent()
        val problems = SkillContract.validate(noSuccess)
        assertTrue("缺 success 必须报出来：$problems", problems.any { it.contains("success") })

        val absent = SkillContract.validate("- 只有一句人话，没有任何契约行")
        assertTrue("一条契约都没有必须报：$absent", absent.any { it.contains("contract_missing") })

        // 反面对照：补上 success 就不该再报这一条
        val fixed = noSuccess + "\n<!-- contract success element_appears=结果 -->"
        assertFalse(
            "补上 success 后不该再报缺段：${SkillContract.validate(fixed)}",
            SkillContract.validate(fixed).any { it.contains("success") },
        )
    }
}
