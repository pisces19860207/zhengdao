// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百九十六批 · 接线**源码门禁**：RunEngine 在 JVM 里起不来（Android SQLite），
// 引擎侧的接线照本仓门禁范式钉成结构性事实（同款范式见 QuietWiringGateTest／
// CompressTriggerWiringGateTest）。通道本体的行为由 AnswerReflowTest 直测，这里只钉三件：
//
//   W1 压下的那一刻登记（带当下轮号）——照常弹的那条路不许登记；
//   W2 检查点答上就回灌，且引擎自发那一路**只灌 INFO**（算不算授权得由答复自己声明）；
//   W3 379 的压/放语义一个字没动（判据⑤：闸里不掺通道、通道里不判闸）；
//   W4 补丁只从上下文拼装口进，回灌路径**不写历史**（判据④的结构形状）；
//   W5 轮号只有一个来源（beginRound 那一口钟），别处不许自造；
//   W6 通道本体零 IO、不自己取时钟（文件头纪律 4）；
//   W7 处置账的不对称写在形状里（INFO 不落账；引擎不直接写处置账）。
//
// 第四百二十九批（C 域①②）补钉：
//   W8 executable 的调用点进了检查点（处置卡 AUTH 收下后、原卡补问执行前恰好一处），
//      取用（useAuthorization）依旧零消费——重试是下一次决策的事；
//   W9 AUTH 档接线：处置卡授权选项带 ONCE 范围提交（AUTH 必带范围，判据③）；
//   W10 REJECT 档接线：取消不带范围、收下即止不再重递原卡。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowWiringGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释的源码（结构性断言用；免得门禁被改注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
    private val reflow = "src/main/java/com/example/zhengdao/baopu/agent/AnswerReflow.kt"

    private fun engineCode(): String = codeOnly(read(engine))
    private fun reflowCode(): String = codeOnly(read(reflow))

    /** 从 [start] 那行起、到 [end] 首次出现为止的源码切片（含端点前的内容）。 */
    private fun slice(src: String, start: String, end: String): String {
        val s = src.indexOf(start)
        require(s >= 0) { "找不到切片起点：$start" }
        val e = src.indexOf(end, s + start.length)
        require(e > s) { "找不到切片终点：$end" }
        return src.substring(s, e)
    }

    private fun count(src: String, needle: String): Int = src.split(needle).size - 1

    // ── W1：压下的那一刻登记 ──────────────────────────────────────────────

    @Test
    fun `W1_被压下的卡当场登记_带压下那一刻的轮号`() {
        val defer = slice(engineCode(), "private fun quietDefer(", "private fun drainQuietQueue(")
        assertTrue("登记必须拿闸给的 deferred：$defer", defer.contains("outcome.deferred?.let {"))
        assertTrue("轮号取当下这轮（判据②的对照面）：$defer",
            defer.contains("AnswerReflow.register(it, currentRoundId)"))
        assertTrue("「照常弹」先短路返回，那条路没压卡就没得登记",
            defer.indexOf("return false") < defer.indexOf("AnswerReflow.register("))
    }

    // ── W2：答上就回灌，且自发那一路只灌 INFO ─────────────────────────────

    @Test
    fun `W2_检查点答上就回灌_引擎自发那一路只算补信息`() {
        val drain = slice(engineCode(), "private fun drainQuietQueue(", "private fun reflowQuietAnswer(")
        assertTrue("答上之后交给通道：$drain", drain.contains("reflowQuietAnswer(item, label, answers)"))
        val body = slice(engineCode(), "private fun reflowQuietAnswer(", "private fun quietShape(")
        assertTrue("自发那一路的种类写死 INFO（算不算授权由答复自己声明）：$body",
            body.contains("kind = ReflowKind.INFO"))
        assertFalse("引擎不许自作主张判授权（派工单 §4：重试是另一次决策）：$body",
            body.contains("ReflowKind.AUTH"))
        assertFalse("也不许自作主张判取消：$body", body.contains("ReflowKind.REJECT"))
        assertTrue("空话不回灌：$body", body.contains("if (text.isBlank()) return"))
        assertTrue("拒因要落日志，不静默吞：$body", body.contains("没灌：\${outcome.reason}"))
    }

    @Test
    fun `W2b_379 的收答日志口径一个字没改`() {
        val raw = read(engine)
        assertTrue("「她答=／没人答」那行仍在原处（补问账是 379 的账，本批只多一本）",
            raw.contains("log(\"补问收答｜\${item.headline}｜她答=\${picked ?: \"没人答\"}\")"))
    }

    // ── W3：379 的压/放语义没动（判据⑤） ──────────────────────────────────

    @Test
    fun `W3_问闸的地方还是那两处_闸与通道互不掺手`() {
        val code = engineCode()
        assertEquals("弹卡前问闸仍只有 askUser／askGroup 两处（本批没加第三个闸口）",
            2, count(code, "quietDefer(req)"))
        assertEquals("检查点还是那三个（轮次入口＋两个出卡口放行后），没多出第四个口：",
            4, count(code, "drainQuietQueue()"))
        assertTrue("379 那句短路原封不动（该弹就弹，本批没给闸加新条件）：",
            slice(code, "private fun quietDefer(", "private fun drainQuietQueue(")
                .contains("if (outcome.verdict == QuietCardVerdict.ASK) return false"))
        assertFalse("闸里不许掺通道（该不该压的判据不因此批而变）",
            codeOf("QuietCardGate.kt").contains("AnswerReflow"))
        assertFalse("QuietMode 同样干净", codeOf("QuietMode.kt").contains("AnswerReflow"))
        assertFalse("通道不判该不该弹卡（闸的结论不因本批而变）",
            reflowCode().contains("QuietCardGate()") || reflowCode().contains("onAsk("))
    }

    private fun codeOf(rel: String): String =
        codeOnly(read("src/main/java/com/example/zhengdao/baopu/agent/$rel"))

    // ── W4：补丁只从拼装口进，不写历史（判据④） ───────────────────────────

    @Test
    fun `W4_回灌路径一个字不写历史_补丁走 extraNotes`() {
        val code = engineCode()
        assertEquals("取补丁的口子全引擎只有一处：上下文拼装", 1, count(code, "AnswerReflow.notes("))
        assertTrue("它是**追加**在最后一段（队列空时与接线前逐字节一致）：",
            code.indexOf("extraNotes = extraNotes +") < code.indexOf("AnswerReflow.notes(currentRoundId)"))
        // 判据④「回灌后历史只追加」的机械形状：这条链路上没有历史的写入口。
        val path = slice(code, "private fun quietDefer(", "private fun quietShape(")
        listOf("insertMessage", "history +=", "history.plusAssign", "persistInterjection", "BaopuSql",
            "nextSeq", "compressionSummary").forEach {
            assertFalse("回灌链路里不许出现历史写入口「$it」：$path", path.contains(it))
        }
    }

    // ── W5：轮号只有一个来源 ──────────────────────────────────────────────

    @Test
    fun `W5_轮号只从开轮那口钟来_别处不许自造`() {
        val code = engineCode()
        // 写点＝行首就是字段名那一类；命名实参（`currentRoundId = currentRoundId,`）是**读**，
        // 它的行尾带逗号，不算写点。
        val writes = code.split("\n").map { it.trim() }
            .filter { it.startsWith("currentRoundId =") && !it.endsWith(",") }
        assertEquals("currentRoundId 只有一个写点：$writes", 1, writes.size)
        assertEquals("写点就是开轮那一刻的轮号：", "currentRoundId = roundMark.roundId", writes.single())
        assertTrue("写点紧跟 beginRound（与授权链同一口钟）：",
            code.indexOf("ActionStepClock.beginRound()") < code.indexOf("currentRoundId = roundMark.roundId"))
        assertTrue("声明处标明它是 run 级 volatile（照 306 批的轮号口径）：",
            read(engine).contains("@Volatile private var currentRoundId: Int = 0"))
        assertTrue("登记与取补丁都从这口钟读：",
            code.contains("AnswerReflow.register(it, currentRoundId)") &&
                code.contains("AnswerReflow.notes(currentRoundId)"))
    }

    // ── W6：通道本体零 IO、不自己取时钟 ───────────────────────────────────

    @Test
    fun `W6_通道纯逻辑_时钟与落点都由调用方给`() {
        val code = reflowCode()
        listOf("System.currentTimeMillis", "java.io.File", "appendText", "readLines", "Context")
            .forEach { assertFalse("通道本体不许自己取 IO/时钟「$it」", code.contains(it)) }
        assertEquals("落账只有一处（走 CardDisposition，它自己认落点）：",
            1, count(code, "CardDisposition.record("))
        assertTrue("处置时刻由调用方给（submit 的入参）：",
            code.contains("nowMs: Long") && code.contains("atMs = nowMs"))
    }

    // ── W7：处置账的不对称写在形状里 ──────────────────────────────────────

    @Test
    fun `W7_INFO 不落账是写死的_引擎不直接写处置账`() {
        assertTrue("「只补信息」没有落账分支：", reflowCode().contains("ReflowKind.INFO -> return null"))
        assertTrue("AUTH 落成批／REJECT 落成驳：",
            reflowCode().contains("ReflowKind.AUTH -> CardDisposition.Choice.APPROVE") &&
                reflowCode().contains("ReflowKind.REJECT -> CardDisposition.Choice.REJECT"))
        assertFalse("引擎不许绕过通道直接写处置账（写出点只有 AnswerReflow 与确认内核）",
            engineCode().contains("CardDisposition.record("))
        assertTrue("补问卡这一格进了闭集（哪张卡认得出才落得了账）：",
            read("src/main/java/com/example/zhengdao/baopu/CardDisposition.kt")
                .contains("REFLOW(\"reflow\", \"补问卡\")"))
    }

    @Test
    fun `W8_可执行性判定进了检查点_判据本体仍在通道_取用依旧零消费`() {
        // 396 把 executable 做成「可查的问句」但没人问；429② 把问句接进检查点：处置卡 AUTH
        // 收下后、原卡补问执行前，引擎问一次通道「她批的授权此刻活着吗」，真才落定
        // 「原卡不再补问」。取用（useAuthorization）依旧零消费——派工单 §4
        // 「不做自动重试原动作（重试是另一次决策）」一个字没松。
        val code = engineCode()
        assertEquals("引擎里 executable( 恰好一处（429② 的调用点，grep 判据）：",
            1, count(code, "AnswerReflow.executable("))
        val disposition = slice(code, "private fun reflowDisposition(", "private fun reflowQuietAnswer(")
        assertTrue("调用点在处置卡里（AUTH 收下之后、原卡补问执行之前）：$disposition",
            disposition.contains("AnswerReflow.executable("))
        assertFalse("取用依旧零消费（不做自动重试）：", code.contains("AnswerReflow.useAuthorization("))
        assertTrue("判据本体仍在通道（引擎只认问句不自己推）：",
            reflowCode().contains("fun executable(askId: String, currentTaskId: String, nowMs: Long): Boolean"))
    }

    // ── 429①：AUTH／REJECT 两档接上（处置卡；桥随 383-A 删了，三选一拼在检查点补问前） ──

    @Test
    fun `W9_AUTH 档接线_处置卡授权选项带ONCE范围提交_收下才落定`() {
        val drain = slice(engineCode(), "private fun drainQuietQueue(", "private fun reflowQuietAnswer(")
        assertTrue("补问前先递处置卡（收下 AUTH／REJECT 即止，不再重递原卡）：$drain",
            drain.contains("if (reflowDisposition(item)) continue"))
        assertTrue("AUTH 由她点的档自己声明（引擎不自作主张判授权，W2 的负面断言对自发路仍成立）：",
            drain.contains("\"reflow:auth\" -> ReflowKind.AUTH to ReflowScope.ONCE.wire"))
        assertTrue("AUTH 提交必带范围（判据③不许默认无限；卡面只摆「就这一次」一档，宁窄勿宽）：",
            drain.contains("ReflowScope.ONCE.wire"))
        assertTrue("收下 AUTH 要问通道可执行性才落定（429② 同一调用点）：",
            drain.contains("AnswerReflow.executable("))
    }

    @Test
    fun `W10_REJECT 档接线_取消不带范围_拒收落回原卡补问路`() {
        val drain = slice(engineCode(), "private fun drainQuietQueue(", "private fun reflowQuietAnswer(")
        assertTrue("REJECT 由她点的档自己声明，scopeWire 是空串（非 AUTH 不许带范围）：$drain",
            drain.contains("\"reflow:reject\" -> ReflowKind.REJECT to \"\""))
        assertTrue("处置卡提交走同一个 submit 口（引擎不碰处置账，W7 原样）：",
            drain.contains("AnswerReflow.submit("))
        assertTrue("拒收不落定——落回 396 的原卡补问路，拒因落日志不静默吞：",
            drain.contains("没灌：\${outcome.reason}"))
    }
}
