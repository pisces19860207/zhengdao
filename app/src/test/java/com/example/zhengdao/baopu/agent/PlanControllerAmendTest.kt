// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十批 · amend 透传的**接线面**（判据①②③ 在起单处的落点）。
//
// 打三件事：
// 1. 改约落地 ⇒ ①旧计划契约退场（它按位置绑的是旧条目，不退就会把旧上限罩在新要求上）
//    ②透传口拿到"改后要求"（起单处据此重开单）；③回灌里两句实话都在。
// 2. 改约**没落地**（写法不合格被拒 / 没立状）⇒ 透传口不许被调用、上限不许退场
//    （判据③：不许借 amend 开后门）。
// 3. 空实现（存量调用点）⇒ 行为逐字节不变：只多一句"契约退场"，透传口不参与。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanStore
import com.example.zhengdao.baopu.plan.PlanContractBoard
import com.example.zhengdao.baopu.plan.PlanPledgeBoard
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlanControllerAmendTest {

    private class CStore : PlanStore {
        val inserts = mutableListOf<Pair<Long, String>>()
        override fun insert(sessionId: Long, payload: String) {
            inserts += sessionId to payload
        }

        override fun latest(sessionId: Long): String? = null
    }

    private lateinit var root: File

    @Before
    fun setUp() {
        PlanPledgeBoard.resetForTest()
        PlanContractBoard.clear()
        root = Files.createTempDirectory("amend-360").toFile()
        PlanPledgeBoard.attach(listOf(root))
    }

    @After
    fun tearDown() {
        PlanPledgeBoard.resetForTest()
        PlanContractBoard.clear()
        root.deleteRecursively()
    }

    // ── 造数 ────────────────────────────────────────────────────────────────

    private fun planArgs(vararg texts: String): JSONObject = JSONObject().put(
        "items",
        JSONArray().also { arr ->
            texts.forEach { arr.put(JSONObject().put("text", it).put("status", PlanRules.STATUS_TODO)) }
        },
    )

    private fun pledgeOf(vararg texts: String): JSONObject =
        JSONObject().put("acceptance", JSONArray(texts.map { "交付「$it」并核对" }))

    private fun contractOf(goal: String, steps: Int): JSONObject = JSONObject()
        .put("goal", goal)
        .put(
            "steps",
            JSONArray().also { arr ->
                repeat(steps) { arr.put(JSONObject().put("risk", "R1").put("may_touch", JSONArray(listOf("产物")))) }
            },
        )
        .put("touches", JSONArray(listOf("产物")))
        .put("expected_asks", 1)

    private fun amendOf(kind: String, text: String, to: String = "", reason: String = "路上想清楚了"): JSONObject =
        JSONObject()
            .put("by", "user")
            .put("reason", reason)
            .put(
                "ops",
                JSONArray().also { arr ->
                    arr.put(
                        JSONObject().put("kind", kind).put("text", text)
                            .also { o -> if (to.isNotEmpty()) o.put("to", to) },
                    )
                },
            )

    private fun controller(onAmend: (String) -> String = { "" }): PlanController = PlanController(
        sessionId = { 7L },
        store = CStore(),
        log = { },
        workspaceRoot = { root.absolutePath },
        crossSession = { false },
        onAmend = onAmend,
    )

    private val five = arrayOf("甲", "乙", "丙", "丁", "戊")

    /** 立状（走完整开工卡链路）＋ 批准一份契约（上限装进板子）。 */
    private fun standingWithContract(c: PlanController, goal: String = "把这周的笔记整理成报告") {
        val args = planArgs(*five).put("pledge", pledgeOf(*five))
        val candidate = c.previewPledge(args)
        assertNotNull("长程任务必须给开工卡候选", candidate)
        c.answerPledgeCard(candidate!!, approved = true)
        assertTrue("立状提交要成功", c.submit(args) is ToolOutcome.Ok)

        val withContract = planArgs(*five)
            .put("pledge", pledgeOf(*five))
            .put("contract", contractOf(goal, five.size))
        val declared = c.previewContract(withContract)
        assertNotNull("契约卡候选没出来", declared)
        c.answerContractCard(declared, accepted = true)
        assertTrue("带契约的提交要成功", c.submit(withContract) is ToolOutcome.Ok)
        assertTrue("上限没装进板子（前提不成立）", PlanContractBoard.hasContract())
    }

    // ── 判据①②③：改约落地 ⇒ 退旧上限 ＋ 透传新要求 ─────────────────────────

    @Test
    fun `判据1_改约落地_旧上限退场_透传口拿到改后要求_回灌两句实话都在`() {
        val passed = mutableListOf<String>()
        val c = controller { req ->
            passed += req
            "【测试透传】已交给起单处"
        }
        standingWithContract(c)

        // 用户改要求：把「甲」改成「删掉整个目录」（= 判据① 那个场景的落板形态）
        c.authorizeNextChange()
        val amended = planArgs("删掉整个目录", "乙", "丙", "丁", "戊")
            .put("amend", amendOf("revise", "甲", to = "删掉整个目录"))
        val out = c.submit(amended)
        assertTrue("改约提交要成功：$out", out is ToolOutcome.Ok)
        val ack = (out as ToolOutcome.Ok).text

        // ① 旧上限退场：它按位置绑的是旧条目，留着就会把 R1 罩在"删整个目录"上
        assertFalse("旧契约还挂在板上 ⇒ 旧上限罩住了新要求（判据①）", PlanContractBoard.hasContract())
        assertTrue("回灌没说清上限已退场", ack.contains("【契约退场】"))
        // ② 透传口拿到的是申报原文，不是转述
        assertEquals("透传口没被调用一次", 1, passed.size)
        assertEquals("把「甲」改成「删掉整个目录」", passed.single())
        assertTrue("透传的回灌没进 ack", ack.contains("【测试透传】已交给起单处"))
        // ③ 军令状同步更新（343 既有行为，本批不动它）
        assertEquals(2, PlanPledgeBoard.activeSnapshot()!!.version)
    }

    @Test
    fun `判据3_写法不合格的改约被拒_透传口不调用_上限不退场`() {
        val passed = mutableListOf<String>()
        val c = controller { req ->
            passed += req
            "【测试透传】不该出现"
        }
        standingWithContract(c)

        // 改一项状上根本没有的条目 ⇒ 判定层拒（原样回灌）
        c.authorizeNextChange()
        val bad = planArgs(*five)
            .put("amend", amendOf("revise", "己", to = "己改"))
        val out = c.submit(bad)
        assertTrue("写法不合格的改约必须被拒：$out", out is ToolOutcome.Ok)
        val ack = (out as ToolOutcome.Ok).text

        assertEquals("被拒的改约不许透传（否则等于借 amend 改单）", 0, passed.size)
        assertTrue("上限不该因为一次被拒的改约退场", PlanContractBoard.hasContract())
        assertFalse("被拒的改约不该说上限退场", ack.contains("【契约退场】"))
        assertEquals("军令状保持第 1 版", 1, PlanPledgeBoard.activeSnapshot()!!.version)
    }

    @Test
    fun `判据3_没立状的改约_沉默且不调用透传口`() {
        val passed = mutableListOf<String>()
        val c = controller { req ->
            passed += req
            "【测试透传】不该出现"
        }
        // 没立状：amend 本层不回话（Ignored），计划改动照 140 的卡走
        val args = planArgs("甲", "乙")
            .put("amend", amendOf("add", "丙"))
        val out = c.submit(args)
        assertTrue("提交本身要成功：$out", out is ToolOutcome.Ok)
        assertEquals("没立状 ⇒ 没有状可改，不该透传", 0, passed.size)
        assertFalse("没立状 ⇒ 没有上限可退", PlanContractBoard.hasContract())
    }

    @Test
    fun `空实现_存量调用点行为不变_只有契约退场那句`() {
        val c = controller() // onAmend 缺省空实现
        standingWithContract(c)

        c.authorizeNextChange()
        val amended = planArgs("删掉整个目录", "乙", "丙", "丁", "戊")
            .put("amend", amendOf("revise", "甲", to = "删掉整个目录"))
        val out = c.submit(amended) as ToolOutcome.Ok

        assertFalse(PlanContractBoard.hasContract())
        assertTrue("退场那句仍在（与透传口接没接无关）", out.text.contains("【契约退场】"))
        assertFalse("空实现不该冒出重开那句", out.text.contains("【任务契约重开】"))
    }
}
