// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十三批 · 八环读数埋点的**判据断言**（派工单 753 §二①–⑥＋边界③逐条落测）。
// 全部走 TempSandbox（建了必删），不碰真产物区、不碰真钟（天键注入）。
package com.example.zhengdao.baopu.loops

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 708 §三 八环 ok 行的合规格本（每环一份，值域全合法；值随环变，形状按白名单）。 */
private fun okReading(loop: Loop): Map<String, Any?> = when (loop) {
    Loop.RETRIEVAL -> mapOf("hits" to 3, "sources" to listOf("seg-1", "seg-2", "msg-7"), "ms" to 48)
    Loop.PLAN -> mapOf("stepsLeft" to 2, "stuckAt" to null, "stepRef" to null, "reason" to "剩 2/4 步未完；判不了就不猜")
    Loop.SALIENCE -> mapOf("threat" to 1, "novelty" to 2, "note" to "威胁低档（读数仅供参考）；样式新异")
    Loop.RELAY -> mapOf("route" to "门禁", "novelty" to 1, "note" to "已中继至门禁（如何处置由下游定）")
    Loop.RPE -> mapOf("rpe" to 1, "note" to "一次过且无人纠正——正向兑现")
    Loop.INSULA -> mapOf(
        "battery" to "87%（放电）",
        "storage" to "余 12.3GB / 共 128.0GB",
        "thermal" to "常温档（36.5℃）",
        "build" to "不在构建",
        "gates" to "绿",
        "formTrack" to "未接形轨缝",
        "note" to "只读快照·零落盘",
    )
    Loop.SUGGEST -> mapOf("echo" to 2, "inspiration" to 1, "replay" to 3, "adopted" to 1, "revoked" to 0)
    Loop.ACCEPT -> mapOf("person" to "UNVERIFIABLE", "foreshadow" to "UNVERIFIABLE", "detail" to "UNVERIFIABLE")
}

private const val TS0 = 1_700_000_000_000L

private fun row(
    loop: Loop,
    status: Status = Status.OK,
    ts: Long = TS0,
    reading: Map<String, Any?> = okReading(loop),
    ref: String = "BioLog:测试:第1行",
    needTicket: String? = null,
    sceneId: String = "753-演示",
    source: SourceKind = SourceKind.BIO,
): LoopReadingRow = LoopReadingRow(ts, sceneId, loop, status, reading, source, ref, needTicket)

class LoopReadingsTest {

    // ── 判据① 八环闭集；出现第九个 ⇒ 红 ──

    @Test
    fun 判据1_八环闭集恰好八环_线名逐字对派工单() {
        assertEquals(
            listOf("retrieval", "plan", "salience", "relay", "rpe", "insula", "suggest", "accept"),
            Loop.entries.map(Loop::wire),
        )
    }

    @Test
    fun 判据1_第九个环名一律拒判() {
        assertNull(Loop.of("memory"))
        assertNull(Loop.of("ninth"))
        assertNull(Loop.of("Retrieval"))
        assertNull(Loop.of(""))
    }

    @Test
    fun 判据1_九环名进不了账_手写行解析判坏() {
        TempSandbox.use("zd753-ninth") { dir ->
            val f = File(dir, "hand.jsonl")
            f.writeText(
                "{\"ts\":$TS0,\"sceneId\":\"s\",\"loop\":\"memory\",\"status\":\"ok\"," +
                    "\"reading\":{},\"source\":\"bio\",\"ref\":\"r\"}\n",
                Charsets.UTF_8,
            )
            assertNull(parseLine(f.readLines()[0]))
            assertEquals(listOf("第1行 解析失败（坏 JSON／九环名／缺 ts，都算坏行）"), LoopReadingsLedger.audit(f))
            assertTrue(LoopReadingsLedger.read(f).isEmpty())
        }
    }

    @Test
    fun 判据1_状态与来源同为闭集() {
        assertEquals(listOf("ok", "unwired", "error"), Status.entries.map(Status::wire))
        assertEquals(listOf("bio", "tool", "ui", "manual"), SourceKind.entries.map(SourceKind::wire))
        assertNull(Status.of("pending"))
        assertNull(SourceKind.of("model"))
    }

    // ── 判据② 每行必须带 ref；缺 ref 该行判废 ──

    @Test
    fun 判据2_缺ref账本拒收_一行不落也不留半个文件() {
        TempSandbox.use("zd753-ref") { dir ->
            val ws = File(dir, "ws")
            val outcome = LoopReadingsLedger.append(ws, row(Loop.RPE, ref = ""))
            assertTrue(outcome is AppendOutcome.Rejected)
            assertTrue((outcome as AppendOutcome.Rejected).problems.any { it.contains("ref") })
            assertFalse(LoopReadingsLedger.fileOf(ws).exists())
            assertFalse(LoopReadingsLedger.filesOf(ws).isNotEmpty())
        }
    }

    @Test
    fun 判据2_手写无ref行_audit点名判废() {
        TempSandbox.use("zd753-ref-audit") { dir ->
            val f = File(dir, "hand.jsonl")
            f.writeText(
                "{\"ts\":$TS0,\"sceneId\":\"s\",\"loop\":\"rpe\",\"status\":\"ok\"," +
                    "\"reading\":{\"rpe\":1,\"note\":\"一次过\"},\"source\":\"bio\"}\n",
                Charsets.UTF_8,
            )
            assertTrue(LoopReadingsLedger.audit(f).single().contains("ref"))
            // 解析口不丢行（结构能拆），但 ref 缺成空串——validate 必判废
            val stripped = parseLine(f.readLines()[0])!!
            assertEquals("", stripped.ref)
            assertTrue(validate(stripped).any { it.contains("ref") })
        }
    }

    // ── 判据③ unwired 行必须带 needTicket；缺 ⇒ 红 ──

    @Test
    fun 判据3_unwired缺needTicket_账本拒收且audit点名() {
        TempSandbox.use("zd753-ticket") { dir ->
            val ws = File(dir, "ws")
            val outcome = LoopReadingsLedger.append(
                ws,
                row(Loop.PLAN, status = Status.UNWIRED, reading = emptyMap()),
            )
            assertTrue(outcome is AppendOutcome.Rejected)
            assertTrue((outcome as AppendOutcome.Rejected).problems.any { it.contains("needTicket") })
            assertFalse(LoopReadingsLedger.fileOf(ws).exists())
            val f = File(dir, "hand.jsonl")
            f.writeText(
                "{\"ts\":$TS0,\"sceneId\":\"s\",\"loop\":\"plan\",\"status\":\"unwired\"," +
                    "\"reading\":{},\"source\":\"bio\",\"ref\":\"r\"}\n",
                Charsets.UTF_8,
            )
            assertTrue(LoopReadingsLedger.audit(f).single().contains("needTicket"))
        }
    }

    @Test
    fun 判据3_unwired带前置单号能落账_读回原样() {
        TempSandbox.use("zd753-ticket-ok") { dir ->
            val ws = File(dir, "ws")
            val outcome = LoopReadingsLedger.append(
                ws,
                row(Loop.PLAN, status = Status.UNWIRED, reading = emptyMap(), needTicket = "714", ref = "RunLog:708:B"),
            )
            assertTrue(outcome is AppendOutcome.Appended)
            val back = LoopReadingsLedger.read(LoopReadingsLedger.fileOf(ws)).single()
            assertEquals("714", back.needTicket)
            assertTrue(LoopReadingsLedger.audit(LoopReadingsLedger.fileOf(ws)).isEmpty())
        }
    }

    @Test
    fun 判据3_needTicket只属于unwired行() {
        assertTrue(validate(row(Loop.RPE, needTicket = "714")).any { it.contains("needTicket") })
    }

    // ── 判据④ 只追加；改写历史行 ⇒ 拒 ──

    @Test
    fun 判据4_只追加_历史行字节原样() {
        TempSandbox.use("zd753-append") { dir ->
            val ws = File(dir, "ws")
            val f = LoopReadingsLedger.fileOf(ws)
            LoopReadingsLedger.append(ws, row(Loop.SALIENCE, ref = "BioLog:杏仁核:1"))
            val after1 = f.readBytes()
            LoopReadingsLedger.append(ws, row(Loop.SALIENCE, ts = TS0 + 1_000L, ref = "BioLog:杏仁核:2"))
            val after2 = f.readBytes()
            assertTrue(after2.size > after1.size)
            assertTrue(after2.copyOfRange(0, after1.size).contentEquals(after1))
            assertEquals(2, LoopReadingsLedger.read(f).size)
        }
    }

    @Test
    fun 判据4_同输入重读是新行不是改写_两行同读数都合法() {
        TempSandbox.use("zd753-dup") { dir ->
            val ws = File(dir, "ws")
            LoopReadingsLedger.append(ws, row(Loop.RPE))
            LoopReadingsLedger.append(ws, row(Loop.RPE))
            val f = LoopReadingsLedger.fileOf(ws)
            assertEquals(2, LoopReadingsLedger.read(f).size)
            assertTrue(LoopReadingsLedger.audit(f).isEmpty())
        }
    }

    @Test
    fun 判据4_手改历史行_audit咬红() {
        TempSandbox.use("zd753-tamper") { dir ->
            val ws = File(dir, "ws")
            LoopReadingsLedger.append(ws, row(Loop.SALIENCE, ref = "BioLog:1"))
            val f = LoopReadingsLedger.fileOf(ws)
            val lines = f.readLines(Charsets.UTF_8).toMutableList()
            val tampered = JSONObject(lines[0])
            tampered.getJSONObject("reading").put("threat", 9)
            lines[0] = tampered.toString()
            f.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            assertTrue(LoopReadingsLedger.audit(f).isNotEmpty())
        }
    }

    @Test
    fun 判据4_删历史行的ref_audit咬红() {
        TempSandbox.use("zd753-strip-ref") { dir ->
            val ws = File(dir, "ws")
            LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "BioLog:1"))
            val f = LoopReadingsLedger.fileOf(ws)
            val j = JSONObject(f.readLines()[0])
            j.remove("ref")
            f.writeText(j.toString() + "\n", Charsets.UTF_8)
            assertTrue(LoopReadingsLedger.audit(f).single().contains("ref"))
        }
    }

    // ── 判据⑤ 滚动：按天切分＋超 10MB 换新文件，旧文件不删 ──

    @Test
    fun 判据5_跨天封卷_旧文件字节原样保留() {
        TempSandbox.use("zd753-day") { dir ->
            val ws = File(dir, "ws")
            val day = { ms: Long -> if (ms == TS0) "2026-11-18" else "2026-11-19" }
            LoopReadingsLedger.append(ws, row(Loop.RETRIEVAL, ts = TS0, source = SourceKind.TOOL, ref = "kb_search:r1"), dayKey = day)
            val active = LoopReadingsLedger.fileOf(ws)
            val day1Bytes = active.readBytes()
            val outcome = LoopReadingsLedger.append(
                ws,
                row(Loop.RETRIEVAL, ts = TS0 + 1, source = SourceKind.TOOL, ref = "kb_search:r2"),
                dayKey = day,
            )
            val rolled = (outcome as AppendOutcome.Appended).rolledFrom!!
            assertEquals("环读数-2026-11-18.jsonl", rolled)
            val rolledFile = File(active.parentFile, rolled)
            assertTrue(rolledFile.isFile)
            assertTrue(rolledFile.readBytes().contentEquals(day1Bytes))
            assertEquals(listOf("kb_search:r2"), LoopReadingsLedger.read(active).map(LoopReadingRow::ref))
            assertEquals(2, LoopReadingsLedger.filesOf(ws).size)
        }
    }

    @Test
    fun 判据5_达线封卷_旧文件一个不少() {
        TempSandbox.use("zd753-size") { dir ->
            val ws = File(dir, "ws")
            val before = LoopReadingsLedger.filesOf(ws).map(File::getName)
            LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "r1"), rollBytes = 200)
            LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "r1"), rollBytes = 200)
            val outcome = LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "r2"), rollBytes = 200)
            assertTrue(outcome is AppendOutcome.Appended)
            assertTrue((outcome as AppendOutcome.Appended).rolledFrom != null)
            val after = LoopReadingsLedger.filesOf(ws).map(File::getName)
            assertTrue(after.containsAll(before))
            assertEquals(2, after.size)
            assertEquals(3, LoopReadingsLedger.filesOf(ws).sumOf { LoopReadingsLedger.read(it).size })
        }
    }

    @Test
    fun 判据5_同天多卷_序号防撞永不覆盖() {
        TempSandbox.use("zd753-seq") { dir ->
            val ws = File(dir, "ws")
            val day = { _: Long -> "2026-11-19" }
            LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "r1"), dayKey = day, rollBytes = 1)
            val o2 = LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "r2"), dayKey = day, rollBytes = 1)
            val o3 = LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "r3"), dayKey = day, rollBytes = 1)
            assertEquals("环读数-2026-11-19.jsonl", (o2 as AppendOutcome.Appended).rolledFrom)
            assertEquals("环读数-2026-11-19-2.jsonl", (o3 as AppendOutcome.Appended).rolledFrom)
            assertEquals(3, LoopReadingsLedger.filesOf(ws).size)
            assertTrue(LoopReadingsLedger.filesOf(ws).sumOf { LoopReadingsLedger.read(it).size } == 3)
        }
    }

    @Test
    fun 判据5_残卷封存_坏首行不改不删() {
        TempSandbox.use("zd753-corrupt") { dir ->
            val ws = File(dir, "ws")
            val active = LoopReadingsLedger.fileOf(ws)
            active.parentFile.mkdirs()
            active.writeText("这不是JSON\n", Charsets.UTF_8)
            val broken = active.readBytes()
            val outcome = LoopReadingsLedger.append(ws, row(Loop.RPE, ref = "r1"))
            assertTrue(outcome is AppendOutcome.Appended)
            assertEquals("环读数-残卷.jsonl", (outcome as AppendOutcome.Appended).rolledFrom)
            assertTrue(File(active.parentFile, "环读数-残卷.jsonl").readBytes().contentEquals(broken))
            assertEquals(listOf("r1"), LoopReadingsLedger.read(active).map(LoopReadingRow::ref))
        }
    }

    @Test
    fun 判据5_滚动线常量是10MB_默认天键ISO日() {
        assertEquals(10L * 1024 * 1024, LoopReadingsLedger.ROLL_BYTES)
        val zone = ZoneId.systemDefault()
        val ms = LocalDate.of(2026, 11, 19).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("2026-11-19", dayKeyOf(ms))
    }

    // ── 判据⑥ 不引新依赖（org.json 既有）＋ schema 逐键 ──

    @Test
    fun 判据6_行形与派工单schema逐键对上() {
        val keys = { line: String -> JSONObject(line).keys().asSequence().toSet() }
        assertEquals(
            setOf("ts", "sceneId", "loop", "status", "reading", "source", "ref"),
            keys(encode(row(Loop.RPE))),
        )
        val unwired = encode(row(Loop.PLAN, status = Status.UNWIRED, reading = emptyMap(), needTicket = "714"))
        assertEquals(
            setOf("ts", "sceneId", "loop", "status", "reading", "source", "ref", "needTicket"),
            keys(unwired),
        )
        assertTrue(JSONObject(unwired).get("ts") is Long)
    }

    @Test
    fun 判据6_编码回读逐字对上_八环全回() {
        Loop.entries.forEach { loop ->
            assertEquals(row(loop), parseLine(encode(row(loop))))
        }
    }

    @Test
    fun 判据6_八环ok行全录一遍_读回与审计双绿() {
        TempSandbox.use("zd753-all") { dir ->
            val ws = File(dir, "ws")
            Loop.entries.forEachIndexed { i, loop ->
                val outcome = LoopReadingsLedger.append(ws, row(loop, ts = TS0 + i * 1_000L))
                assertTrue("环 $loop 应收上", outcome is AppendOutcome.Appended)
            }
            val f = LoopReadingsLedger.fileOf(ws)
            assertEquals(8, LoopReadingsLedger.read(f).size)
            assertTrue(LoopReadingsLedger.audit(f).isEmpty())
        }
    }

    // ── 白名单与值域（判据①的键位姊妹闸＋708 §三 值域） ──

    @Test
    fun ok行缺字段与多余字段都判废() {
        val missing = okReading(Loop.SALIENCE) - "novelty"
        assertTrue(validate(row(Loop.SALIENCE, reading = missing)).any { it.contains("缺字段") })
        val extra = okReading(Loop.SALIENCE) + ("text" to "一段正文……")
        assertTrue(validate(row(Loop.SALIENCE, reading = extra)).any { it.contains("多余字段") })
        assertTrue(validate(row(Loop.SALIENCE, reading = emptyMap())).any { it.contains("沉默") })
    }

    @Test
    fun 值域钉死_越界一律判废() {
        val threat = okReading(Loop.SALIENCE) + ("threat" to 5)
        assertTrue(validate(row(Loop.SALIENCE, reading = threat)).any { it.contains("超域") })
        val rpe = okReading(Loop.RPE) + ("rpe" to 2)
        assertTrue(validate(row(Loop.RPE, reading = rpe)).any { it.contains("超域") })
        val route = okReading(Loop.RELAY) + ("route" to "直答X")
        assertTrue(validate(row(Loop.RELAY, reading = route)).any { it.contains("闭集") })
        val verdict = okReading(Loop.ACCEPT) + ("person" to "MAYBE")
        assertTrue(validate(row(Loop.ACCEPT, reading = verdict)).any { it.contains("闭集") })
        val stuck = okReading(Loop.PLAN) + ("stuckAt" to -1)
        assertTrue(validate(row(Loop.PLAN, reading = stuck)).any { it.contains("超域") })
        // 755：stepRef 从 1 起（0 = 计划步号域外——引擎条目序号＋1，不存在第 0 条）
        val zeroRef = okReading(Loop.PLAN) + ("stepRef" to 0)
        assertTrue(validate(row(Loop.PLAN, reading = zeroRef)).any { it.contains("超域") })
    }

    @Test
    fun stuckAt允许null_判不了就不猜() {
        assertTrue(validate(row(Loop.PLAN)).isEmpty())
    }

    // ── 755③：loop=plan 的 reading 带 stepRef（与失败事件字段名一致） ──

    @Test
    fun 判据755_plan_ok行缺stepRef判废_字段名与失败事件一致() {
        val missing = okReading(Loop.PLAN) - "stepRef"
        assertTrue(
            "缺 stepRef 要点名（键位闭集：一 key 不缺）",
            validate(row(Loop.PLAN, reading = missing)).any { it.contains("缺字段 stepRef") },
        )
        // 键名逐字：stepRef（camel，与失败事件的归因字段同名；不是 step_no / step_ref）
        assertEquals(listOf("stepsLeft", "stuckAt", "stepRef", "reason"), READING_SPEC.getValue(Loop.PLAN).map { it.key })
    }

    @Test
    fun 判据755_stepRef带归因步号的plan行_校验编码回读全绿() {
        val reading = okReading(Loop.PLAN) + ("stepRef" to 3) + ("stuckAt" to 3)
        val r = row(Loop.PLAN, reading = reading)
        assertTrue(validate(r).isEmpty())
        val back = parseLine(encode(r))!!
        assertEquals(3, back.reading["stepRef"])
        // 账本也收（先判后收链路对 stepRef 无特判）
        TempSandbox.use("zd755-planref") { dir ->
            val outcome = LoopReadingsLedger.append(File(dir, "ws"), r)
            assertTrue(outcome is AppendOutcome.Appended)
            assertTrue(LoopReadingsLedger.audit((outcome as AppendOutcome.Appended).file).isEmpty())
        }
    }

    @Test
    fun 判据755_stepRef判不出单一落点给null_与stuckAt同一口径() {
        // ambiguous（候选≥2）/unattributed（原因码）不落单一数字 ⇒ null（判不了就不猜）；
        // 三态原文在 run_log.step_ref，ref 指过去——账面只放"有把握的那一个数或 null"。
        assertTrue(validate(row(Loop.PLAN)).isEmpty()) // stepRef=null 合法（fixture 即 null）
        val back = parseLine(encode(row(Loop.PLAN)))!!
        assertNull(back.reading["stepRef"])
        assertTrue(back.reading.containsKey("stepRef")) // 键必须在场——null 也是读数，不是沉默
    }

    @Test
    fun 非ok行reading必须空_沉默不算读数也只在ok行() {
        assertTrue(
            validate(row(Loop.RPE, status = Status.UNWIRED, reading = emptyMap(), needTicket = "714")).isEmpty(),
        )
        val unwired = row(Loop.RPE, status = Status.UNWIRED, reading = okReading(Loop.RPE), needTicket = "714")
        assertTrue(validate(unwired).any { it.contains("reading 必须为空") })
        assertTrue(validate(row(Loop.RPE, status = Status.ERROR, reading = okReading(Loop.RPE))).any { it.contains("reading 必须为空") })
    }

    @Test
    fun sceneId空也判废() {
        assertTrue(validate(row(Loop.RPE, sceneId = "")).any { it.contains("sceneId") })
    }

    // ── 边界③（633 红线）：读数字段严禁正文与音频 ──

    @Test
    fun 红线633_正文进不了串字段_超上限判废() {
        val prose = "正文".repeat(150)
        val bad = okReading(Loop.SALIENCE) + ("note" to prose)
        assertTrue(validate(row(Loop.SALIENCE, reading = bad)).any { it.contains("上限") })
    }

    @Test
    fun 红线633_换行断行判废() {
        val bad = okReading(Loop.SALIENCE) + ("note" to "多行\n断行")
        assertTrue(validate(row(Loop.SALIENCE, reading = bad)).any { it.contains("控制符") })
    }

    @Test
    fun 红线633_音频没有通道() {
        val inList = okReading(Loop.RETRIEVAL) + ("sources" to listOf("data:audio/mp3;base64,AAAA"))
        assertTrue(validate(row(Loop.RETRIEVAL, reading = inList)).any { it.contains("633") })
        val inNote = okReading(Loop.SALIENCE) + ("note" to "x base64,AAAA")
        assertTrue(validate(row(Loop.SALIENCE, reading = inNote)).any { it.contains("base64") })
        assertTrue(validate(row(Loop.RPE, ref = "data:text/plain,xx")).any { it.contains("633") })
    }
}
