package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpinionBudgetTest {
    private val opinion = Opinion(Source.CLASSIFIER, Severity.CRITICAL, "即时危险", ActionCode.SUGGEST_PAUSE, LawRef.L21, listOf("event:1"))

    @Test fun `default budget expresses one per round and three royal cards per day`() {
        assertEquals(1, Budget().perRoundMax)
        assertEquals(3, Budget().perDayRoyalMax)
        assertTrue(Budget().emergencyExempt)
    }

    @Test fun `ordinary budgets cannot exceed ceilings or become negative`() {
        listOf(-1, 2).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Budget(perRoundMax = value) }
        }
        listOf(-1, 4).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Budget(perDayRoyalMax = value) }
        }
        assertEquals(0, Budget(0, 0).perRoundMax)
    }

    @Test fun `critical L21 exemption carries the explanation unchanged`() {
        val reason = "现有条文未覆盖该即时危险"
        val exemption = Budget().exemptionFor(opinion, reason)
        assertNotNull(exemption)
        assertEquals(opinion, exemption!!.opinion)
        assertEquals(reason, exemption.whyNoApplicableLaw)
    }

    @Test fun `critical without L21 has no exemption`() {
        LawRef.entries.filter { it != LawRef.L21 }.forEach { law ->
            assertNull(Budget().exemptionFor(opinion.copy(lawRef = law), "没有适用条文"))
        }
    }

    @Test fun `L21 without critical severity has no exemption`() {
        listOf(Severity.MAJOR, Severity.MINOR).forEach {
            assertNull(Budget().exemptionFor(opinion.copy(severity = it), "没有适用条文"))
        }
    }

    @Test fun `emergencyExempt false disables even critical L21 exemption`() {
        val budget = Budget(emergencyExempt = false)
        assertFalse(budget.emergencyExempt)
        assertNull(budget.exemptionFor(opinion, "没有适用条文"))
    }

    @Test fun `blank explanation cannot create emergency exemption`() {
        listOf("", " ", "\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { Budget().exemptionFor(opinion, it) }
        }
    }

    @Test fun `constructing or copying exemption cannot bypass law severity or reason`() {
        val valid = EmergencyExemption(opinion, "没有适用条文")
        assertThrows(IllegalArgumentException::class.java) { valid.copy(whyNoApplicableLaw = "") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(opinion = opinion.copy(lawRef = LawRef.NONE)) }
        assertThrows(IllegalArgumentException::class.java) { EmergencyExemption(opinion.copy(severity = Severity.MAJOR), "没有适用条文") }
    }

    @Test fun `expressing an exemption does not consume or change budget`() {
        val budget = Budget()
        val snapshot = budget.copy()
        val first = budget.exemptionFor(opinion, "没有适用条文")
        assertEquals(first, budget.exemptionFor(opinion, "没有适用条文"))
        assertEquals(snapshot, budget)
    }
}
