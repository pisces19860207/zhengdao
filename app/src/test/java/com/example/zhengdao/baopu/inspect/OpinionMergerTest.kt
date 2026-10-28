package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpinionMergerTest {
    private fun opinion(
        source: Source = Source.PATROL,
        severity: Severity = Severity.MINOR,
        gist: String = "待核",
        law: LawRef = LawRef.L23,
        refs: List<String> = listOf("event:1"),
        vote: Int = 0,
    ) = Opinion(source, severity, gist, ActionCode.REVIEW, law, refs, vote)

    @Test fun `same event from four sources merges severity evidence and votes`() {
        val opinions = listOf(
            opinion(Source.CLASSIFIER, Severity.MINOR, "待核", refs = listOf("event:1", "risk:1"), vote = 2),
            opinion(Source.PATROL, Severity.CRITICAL, "漏账", refs = listOf("event:1", "log:1"), vote = -1),
            opinion(Source.AUDIT, Severity.MAJOR, "账实不符", refs = listOf("event:1", "audit:1", "log:1"), vote = -2),
            opinion(Source.SHADOW, Severity.MINOR, "推演待核", refs = listOf("event:1", "shadow:1"), vote = 2),
        )
        val result = OpinionMerger.merge(opinions) { group -> group.single { it.source == Source.PATROL } }
        assertTrue(result.suggestions.isEmpty())
        val actual = result.merged.single()
        assertEquals(Severity.CRITICAL, actual.severity)
        assertEquals("漏账", actual.gist)
        assertEquals(Source.PATROL, actual.source)
        assertEquals(listOf("event:1", "risk:1", "log:1", "audit:1", "shadow:1"), actual.evidence)
        assertEquals(1, actual.vote)
    }

    @Test fun `negative four selecting a minor gist must not lower critical severity`() {
        val critical = opinion(severity = Severity.CRITICAL, gist = "有风险")
        val minor = opinion(source = Source.AUDIT, gist = "漏账")
        listOf(listOf(critical, minor), listOf(minor, critical)).forEach { inputs ->
            val merged = OpinionMerger.merge(inputs) { minor }.merged.single()
            assertEquals("漏账", merged.gist)
            assertEquals(Severity.CRITICAL, merged.severity)
        }
    }

    @Test fun `major outranks minor independent of order`() {
        val major = opinion(severity = Severity.MAJOR)
        val minor = opinion()
        listOf(listOf(major, minor), listOf(minor, major)).forEach {
            assertEquals(Severity.MAJOR, OpinionMerger.merge(it) { group -> group.first() }.merged.single().severity)
        }
    }

    @Test fun `more specific gist can be shorter and its action stays paired`() {
        val broad = opinion(gist = "请进一步核验").copy(action = ActionCode.REVIEW)
        val specific = opinion(source = Source.SHADOW, gist = "删多了").copy(action = ActionCode.SUGGEST_PAUSE)
        val actual = OpinionMerger.merge(listOf(broad, specific)) { specific }.merged.single()
        assertEquals("删多了", actual.gist)
        assertEquals(ActionCode.SUGGEST_PAUSE, actual.action)
    }

    @Test fun `a made up specificity result is rejected`() {
        val original = opinion()
        assertThrows(IllegalArgumentException::class.java) {
            OpinionMerger.merge(listOf(original)) { original.copy(gist = "自造意见") }
        }
    }

    @Test fun `non NONE law wins even when selected critical opinion has NONE`() {
        val critical = opinion(severity = Severity.CRITICAL, law = LawRef.NONE)
        val supported = opinion(source = Source.AUDIT, law = LawRef.L23)
        assertEquals(LawRef.L23, OpinionMerger.merge(listOf(critical, supported)) { critical }.merged.single().lawRef)
    }

    @Test fun `selected non NONE law is kept without inventing a new reference`() {
        val a = opinion(law = LawRef.L3)
        val b = opinion(source = Source.AUDIT, law = LawRef.L23)
        assertEquals(LawRef.L23, OpinionMerger.merge(listOf(a, b)) { b }.merged.single().lawRef)
    }

    @Test fun `critical without any law stays NONE`() {
        val a = opinion(severity = Severity.CRITICAL, law = LawRef.NONE)
        assertEquals(LawRef.NONE, OpinionMerger.merge(listOf(a)) { it.first() }.merged.single().lawRef)
    }

    @Test fun `suggestions never enter merge even when event and vote match`() {
        val suggestion = opinion(law = LawRef.NONE, refs = listOf("event:1", "suggestion:1"), vote = -2)
        val admitted = opinion(severity = Severity.CRITICAL, vote = 2)
        val actual = OpinionMerger.merge(listOf(suggestion, admitted)) { group ->
            assertEquals(listOf(admitted), group)
            group.first()
        }
        assertEquals(listOf(suggestion), actual.suggestions)
        assertEquals(listOf(admitted), actual.merged)
    }

    @Test fun `suggestions cannot bridge otherwise unrelated events`() {
        val a = opinion(gist = "待核", refs = listOf("event:1"))
        val b = opinion(source = Source.AUDIT, refs = listOf("event:2"))
        val suggestion = opinion(gist = "待核", law = LawRef.NONE, refs = listOf("event:2"))
        val result = OpinionMerger.merge(listOf(a, b, suggestion)) { it.first() }
        assertEquals(2, result.merged.size)
        assertEquals(listOf(suggestion), result.suggestions)
    }

    @Test fun `only suggestions or no input never invokes specificity selection`() {
        val suggestion = opinion(law = LawRef.NONE)
        val result = OpinionMerger.merge(listOf(suggestion)) { error("suggestions entered merger") }
        assertTrue(result.merged.isEmpty())
        assertEquals(listOf(suggestion), result.suggestions)
        assertEquals(OpinionMergeResult(emptyList(), emptyList()), OpinionMerger.merge(emptyList()) { error("empty group") })
    }

    @Test fun `normalized source gist key removes spaces width and case differences`() {
        val a = opinion(gist = "Ａ 待核", refs = listOf("event:1"))
        val b = opinion(gist = "a待核", refs = listOf("event:2"))
        assertEquals(1, OpinionMerger.merge(listOf(a, b)) { it.first() }.merged.size)
    }

    @Test fun `same gist across different sources does not merge without event evidence`() {
        val a = opinion(refs = listOf("event:1"))
        val b = opinion(source = Source.AUDIT, refs = listOf("event:2"))
        assertEquals(2, OpinionMerger.merge(listOf(a, b)) { it.first() }.merged.size)
    }

    @Test fun `shared later evidence is not an event identity`() {
        val a = opinion(gist = "漏账", refs = listOf("event:1", "log:shared"))
        val b = opinion(gist = "缺证", refs = listOf("event:2", "log:shared"))
        assertEquals(2, OpinionMerger.merge(listOf(a, b)) { it.first() }.merged.size)
    }

    @Test fun `evidence identity remains case sensitive`() {
        val a = opinion(gist = "漏账", refs = listOf("event:A"))
        val b = opinion(gist = "缺证", refs = listOf("event:a"))
        assertEquals(2, OpinionMerger.merge(listOf(a, b)) { it.first() }.merged.size)
    }

    @Test fun `bridging evidence and normalized keys merges transitively in every order`() {
        val a = opinion(gist = "缺证", refs = listOf("event:1"), vote = 2)
        val b = opinion(source = Source.AUDIT, gist = "漏账", refs = listOf("event:2"), vote = -1)
        val bridge = opinion(gist = "缺 证", refs = listOf("event:2"), vote = -2)
        val orders = listOf(listOf(a, b, bridge), listOf(a, bridge, b), listOf(b, a, bridge), listOf(b, bridge, a), listOf(bridge, a, b), listOf(bridge, b, a))
        orders.forEach {
            val merged = OpinionMerger.merge(it) { group -> group.first() }.merged.single()
            assertEquals(setOf("event:1", "event:2"), merged.evidence.toSet())
            assertEquals(-1, merged.vote)
        }
    }

    @Test fun `deduplicated evidence still sums every supplied vote`() {
        val a = opinion(vote = -1)
        val merged = OpinionMerger.merge(listOf(a, a)) { it.first() }.merged.single()
        assertEquals(listOf("event:1"), merged.evidence)
        assertEquals(-2, merged.vote)
    }

    @Test fun `vote overflow is rejected rather than reversing sensitivity`() {
        assertThrows(ArithmeticException::class.java) {
            OpinionMerger.merge(listOf(opinion(vote = Int.MAX_VALUE), opinion(vote = 1))) { it.first() }
        }
        assertThrows(ArithmeticException::class.java) {
            OpinionMerger.merge(listOf(opinion(vote = Int.MIN_VALUE), opinion(vote = -1))) { it.first() }
        }
    }

    @Test fun `cancelling votes do not overflow based on input order`() {
        val orders = listOf(
            listOf(Int.MAX_VALUE, 1, -1), listOf(1, Int.MAX_VALUE, -1), listOf(1, -1, Int.MAX_VALUE),
            listOf(Int.MIN_VALUE, -1, 1), listOf(-1, Int.MIN_VALUE, 1), listOf(-1, 1, Int.MIN_VALUE),
        )
        orders.forEach { votes ->
            val expected = if (Int.MAX_VALUE in votes) Int.MAX_VALUE else Int.MIN_VALUE
            assertEquals(expected, OpinionMerger.merge(votes.map { opinion(vote = it) }) { it.first() }.merged.single().vote)
        }
    }

    @Test fun `merging is repeatable and does not mutate input opinions or references`() {
        val refs = mutableListOf("event:1", "log:1")
        val input = listOf(opinion(refs = refs), opinion(source = Source.AUDIT, refs = listOf("event:1", "log:2")))
        val snapshot = input.map { it.copy(evidence = it.evidence.toList()) }
        val first = OpinionMerger.merge(input) { it.first() }
        assertEquals(first, OpinionMerger.merge(input) { it.first() })
        assertEquals(snapshot, input)
        assertEquals(listOf("event:1", "log:1"), refs)
    }
}
