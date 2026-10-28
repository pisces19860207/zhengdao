package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpinionContractTest {
    private fun opinion(gist: String = "需复核", evidence: List<String> = listOf("event:1")) =
        Opinion(Source.PATROL, Severity.MAJOR, gist, ActionCode.REVIEW, LawRef.NONE, evidence)

    @Test fun `four sources use the same contract without guessing a law`() {
        val samples = listOf(
            OpinionAdapter.CLASSIFIER.adapt(Severity.CRITICAL, "删除不可逆", ActionCode.SUGGEST_PAUSE, LawRef.NONE, listOf("risk:1")),
            OpinionAdapter.PATROL.adapt(Severity.MAJOR, "勘验缺证", ActionCode.SUPPLEMENT_EVIDENCE, LawRef.NONE, listOf("patrol:1")),
            OpinionAdapter.AUDIT.adapt(Severity.MAJOR, "账实不符", ActionCode.REVIEW, LawRef.NONE, listOf("audit:1")),
            OpinionAdapter.SHADOW.adapt(Severity.CRITICAL, "影响超推演", ActionCode.SUGGEST_PAUSE, LawRef.NONE, listOf("shadow:1")),
        )
        assertEquals(Source.entries.toList(), samples.map { it.source })
        samples.forEach {
            assertEquals(LawRef.NONE, it.lawRef)
            assertEquals(0, it.vote)
            assertTrue(it.evidence.isNotEmpty())
        }
    }

    @Test fun `eight Han characters are accepted unchanged`() {
        val gist = "一二三四五六七八"
        assertEquals(gist, opinion(gist).gist)
    }

    @Test fun `negative one nine Han characters are rejected during construction`() {
        assertThrows(IllegalArgumentException::class.java) { opinion("一二三四五六七八九") }
    }

    @Test fun `supplementary Han characters count as characters not UTF16 units`() {
        val han = String(Character.toChars(0x20000))
        assertEquals(han.repeat(8), opinion(han.repeat(8)).gist)
        assertThrows(IllegalArgumentException::class.java) { opinion(han.repeat(9)) }
    }

    @Test fun `blank and multiline gist are rejected`() {
        listOf("", "  ", "缺\n证", "缺\r证", "缺\u2028证", "缺\u2029证").forEach {
            assertThrows(IllegalArgumentException::class.java) { opinion(it) }
        }
    }

    @Test fun `negative three no evidence cannot construct an opinion`() {
        assertThrows(IllegalArgumentException::class.java) { opinion(evidence = emptyList()) }
        listOf(listOf(""), listOf(" "), listOf("event:1", "")).forEach {
            assertThrows(IllegalArgumentException::class.java) { opinion(evidence = it) }
        }
    }

    @Test fun `copy cannot bypass gist or evidence validation`() {
        assertThrows(IllegalArgumentException::class.java) { opinion().copy(gist = "一二三四五六七八九") }
        assertThrows(IllegalArgumentException::class.java) { opinion().copy(evidence = emptyList()) }
    }

    @Test fun `hearsay is not an evidence reference even beside a real reference`() {
        listOf(listOf("据说"), listOf(" 据说 "), listOf("event:1", "据说")).forEach {
            assertThrows(IllegalArgumentException::class.java) { opinion(evidence = it) }
        }
        OpinionAdapter.entries.forEach {
            assertThrows(IllegalArgumentException::class.java) {
                it.adapt(Severity.MINOR, "待核", ActionCode.REVIEW, LawRef.NONE, listOf("据说"))
            }
        }
    }

    @Test fun `law reference is a closed set with explicit NONE`() {
        assertEquals(listOf("NONE") + (1..23).map { "L$it" }, LawRef.entries.map { it.name })
        listOf("", " ", "L0", "L24", "据说").forEach {
            assertThrows(IllegalArgumentException::class.java) { LawRef.valueOf(it) }
        }
    }

    @Test fun `action codes and severity are enums`() {
        assertTrue(ActionCode::class.java.isEnum)
        assertEquals(listOf("CRITICAL", "MAJOR", "MINOR"), Severity.entries.map { it.name })
    }

    @Test fun `NONE marks noncritical opinions as suggestions only`() {
        assertTrue(opinion().suggestionOnly)
        assertTrue(opinion().copy(severity = Severity.MINOR).suggestionOnly)
        assertFalse(opinion().copy(severity = Severity.CRITICAL).suggestionOnly)
        LawRef.entries.filter { it != LawRef.NONE }.forEach {
            assertFalse(opinion().copy(lawRef = it).suggestionOnly)
        }
    }

    @Test fun `all adapters retain explicit fields and do not truncate`() {
        OpinionAdapter.entries.forEach { adapter ->
            val actual = adapter.adapt(Severity.MINOR, "待核", ActionCode.PROPOSE_REVISION, LawRef.L3, listOf("event:2"), -1)
            assertEquals(Opinion(adapter.source, Severity.MINOR, "待核", ActionCode.PROPOSE_REVISION, LawRef.L3, listOf("event:2"), -1), actual)
            assertThrows(IllegalArgumentException::class.java) {
                adapter.adapt(Severity.MINOR, "一二三四五六七八九", ActionCode.REVIEW, LawRef.NONE, listOf("event:2"))
            }
        }
    }

    @Test fun `adapters snapshot evidence without modifying the input`() {
        val refs = mutableListOf("event:1")
        val actual = OpinionAdapter.PATROL.adapt(Severity.MINOR, "待核", ActionCode.REVIEW, LawRef.NONE, refs)
        refs.add("event:2")
        assertEquals(listOf("event:1"), actual.evidence)
    }
}
