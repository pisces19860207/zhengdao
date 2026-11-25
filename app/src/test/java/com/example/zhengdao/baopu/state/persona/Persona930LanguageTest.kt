package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Persona930LanguageTest {
    @Test
    fun `临床词34条四列四类且不自指五因子`() {
        assertEquals(34, PersonaLanguage.CLINICAL_TERMS.size)
        assertEquals(
            setOf("人格障碍", "依恋类型", "精神科诊断", "伪心理标签"),
            PersonaLanguage.CLINICAL_TERMS.map { it.category.label }.toSet(),
        )
        assertTrue(PersonaLanguage.CLINICAL_TERMS.all { it.allowedPosition == ClinicalAllowedPosition.DIALOGUE })
        assertTrue(PersonaLanguage.CLINICAL_TERMS.all { it.exemption == ClinicalExemption.MEDICAL_UNCERTAIN_NARRATION })
        val terms = PersonaLanguage.CLINICAL_TERMS.map { it.phrase }
        listOf("Big-Five", "大五人格", "量表", "读心术").forEach { assertFalse(it in terms) }
        assertTrue("人格量表" in terms && "心理量表" in terms)
    }

    @Test
    fun `34条临床词逐条叙述者红台词绿医疗不确定WARN`() {
        PersonaLanguage.CLINICAL_TERMS.forEach { rule ->
            val narration = PersonaLanguage.clinical("他是${rule.phrase}。")
            assertEquals("叙述者 ${rule.phrase}", listOf("DIAGNOSIS"), narration.map { it.code })
            val dialogue = PersonaLanguage.clinical("他说：「他是${rule.phrase}。」", ClinicalPosition.DIALOGUE)
            assertTrue("台词 ${rule.phrase}", dialogue.isEmpty())
            val medical = PersonaLanguage.clinical(
                "这位医生可能是${rule.phrase}。",
                subjectRole = ClinicalSubjectRole.DOCTOR,
            )
            assertEquals("医疗 ${rule.phrase}", listOf("CLINICAL_MEDICAL_UNCERTAIN"), medical.map { it.code })
        }
    }

    @Test
    fun `位置只按引号与直接引语且同句医疗不确定才WARN`() {
        assertTrue(PersonaLanguage.clinical("他说：“他是NPD”。").isEmpty())
        assertTrue(PersonaLanguage.clinical("她说：他是PTSD。").isEmpty())
        assertEquals(listOf("DIAGNOSIS"), PersonaLanguage.clinical("他说：“他是NPD").map { it.code })
        val warning = PersonaLanguage.clinical("这位医生可能是NPD。", subjectRole = ClinicalSubjectRole.DOCTOR)
        assertEquals(listOf("CLINICAL_MEDICAL_UNCERTAIN"), warning.map { it.code })
        assertEquals(Severity.WARNING, warning.single().severity)
        val crossSentence = PersonaLanguage.clinical(
            "这位医生是NPD。他可能累了。",
            subjectRole = ClinicalSubjectRole.DOCTOR,
        )
        assertEquals(listOf("DIAGNOSIS"), crossSentence.map { it.code })
        val nonMedical = PersonaLanguage.clinical("他可能是NPD。", subjectRole = ClinicalSubjectRole.NONE)
        assertEquals(listOf("DIAGNOSIS"), nonMedical.map { it.code })
        val mixed = PersonaLanguage.clinical(
            "他是NPD。这位医生可能是PTSD。",
            subjectRole = ClinicalSubjectRole.DOCTOR,
        )
        assertEquals(listOf("DIAGNOSIS"), mixed.map { it.code })
    }

    @Test
    fun `闭合引号外同句叙述仍按叙述者判红`() {
        val cases = listOf(
            "他说：「你是NPD」，他是PTSD。",
            "她说：“你是NPD”，她是PTSD。",
            "他说:\"你是NPD\", 他是PTSD.",
        )
        cases.forEach { text ->
            assertEquals(text, listOf("DIAGNOSIS"), PersonaLanguage.clinical(text).map { it.code })
            val stigmaText = text.replace("NPD", "舔狗").replace("PTSD", "废物")
            assertEquals(
                stigmaText,
                listOf("STIGMA_NARRATOR"),
                PersonaLanguage.stigma(stigmaText).map { it.code },
            )
        }
    }

    @Test
    fun `同形词负例不命中且不做运行时联想`() {
        listOf("他依赖我", "回避（动词）", "边缘", "PNPD", "attachments", "NPD_备注").forEach { text ->
            assertTrue(text, PersonaLanguage.clinical(text).isEmpty())
        }
    }

    @Test
    fun `污名词台词WARN叙述者红且普通情绪词不入表`() {
        assertEquals(
            setOf("直男癌", "舔狗", "精神病", "神经病", "娘炮", "废物", "太监"),
            PersonaLanguage.STIGMA_TERMS.toSet(),
        )
        listOf("恶心", "活该", "该死", "变态").forEach { assertFalse(it in PersonaLanguage.STIGMA_TERMS) }
        val dialogue = PersonaLanguage.stigma("你这个舔狗", ClinicalPosition.DIALOGUE)
        assertEquals(listOf("STIGMA_DIALOGUE"), dialogue.map { it.code })
        assertEquals(Severity.WARNING, dialogue.single().severity)
        val narration = PersonaLanguage.stigma("他是个舔狗")
        assertEquals(listOf("STIGMA_NARRATOR"), narration.map { it.code })
        assertEquals(Severity.ERROR, narration.single().severity)
    }

    @Test
    fun `二次元标签只收surface且非surface命中红`() {
        assertTrue(PersonaLanguage.surfaceTags(listOf("傲娇")).isEmpty())
        assertEquals(
            "ANIME_TAG_UNKNOWN",
            PersonaLanguage.surfaceTags(listOf("未知标签")).single().code,
        )
        assertEquals(
            "ANIME_TAG_OFF_SURFACE",
            PersonaLanguage.offSurfaceTags(listOf("她很傲娇")).single().code,
        )
        assertEquals(
            "DETERMINISM_FORBIDDEN",
            PersonaLanguage.determinism("人格决定命运").single().code,
        )
    }
}
