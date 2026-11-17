// 第六百七十批 · 判据⑨：与 Python 参考实现跨语言对拍。
// 同一份场景脚本（parity-scenarios.json）两国语言各跑一遍，
// 期望读数由 tools/character-track-parity.py 从参考实现产出（parity-expected.json，
// 内嵌参考实现 sha256 指纹）。本测试逐 op 比对：返回值 msg、状态 state、snapshot。
package com.example.zhengdao.baopu.state.track

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackParityTest {

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/track/$name")!!.bufferedReader().use { it.readText() }

    /**
     * 深比较（对象无序、数组有序、数值按 double、NULL 须两边同位）。
     * 不用 JSONObject.similar()：Android 平台的 org.json 桩类没有这个方法，
     * 编译期吃的是桩、运行期才是真实实现——判据不能赌 classpath 顺序。
     */
    private fun jsonEquals(a: Any, b: Any): Boolean = when {
        a === JSONObject.NULL || b === JSONObject.NULL -> a === JSONObject.NULL && b === JSONObject.NULL
        a is JSONObject && b is JSONObject ->
            a.length() == b.length() && a.keys().asSequence().all { b.has(it) && jsonEquals(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { jsonEquals(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun stanceOf(label: String): Stance = when (label) {
        "欲望" -> Stance.DESIRE
        "中立" -> Stance.NEUTRAL
        "排斥" -> Stance.AVERSION
        else -> error("未知立场：$label")
    }

    /** 与 Python runner 的 run_op 逐条同义（脚本驱动信号记 USER 来源）。 */
    private fun runOp(track: CharacterTrack, op: JSONObject): String? = when (op.getString("op")) {
        "feed" -> track.feed(TrackSignal(op.getInt("sig"), SignalSource.USER))
        "tick" -> track.tick(op.optBoolean("anchor", false))
        "attacker" -> track.attackerStep()
        "touch" -> track.touch(op.getInt("layer"))
        "touch_max" -> track.touch(minOf(3, TrackParams.DEFAULT.salienceLayerMax.getValue(track.salience)))
        "inertia" -> {
            val value = op.getString("template").replace("{layer}", track.layer.toString())
            track.holdInertia(value)
            value
        }
        else -> error("未知 op：${op.getString("op")}")
    }

    /** 列表须显式包 JSONArray：真 org.json 的 put(String, Object) 原样存对象，不做 Collection 转换。 */
    private fun stateJson(track: CharacterTrack): JSONObject {
        val view = track.stateView()
        return JSONObject()
            .put("stance", view.stance)
            .put("salience", view.salience)
            .put("d_stage", view.dStage)
            .put("a_stage", view.aStage)
            .put("layer", view.layer)
            .put("turns", view.turns)
            .put("anchors_hit", JSONArray(view.anchorsHit))
            .put("window", JSONArray(view.window))
            .put("inertia", view.inertia ?: JSONObject.NULL)
    }

    @Test
    fun parity_eight_scenarios_step_by_step() {
        val scenarios = JSONObject(resource("parity-scenarios.json")).getJSONArray("scenarios")
        val expectedDoc = JSONObject(resource("parity-expected.json"))
        val expected = expectedDoc.getJSONArray("scenarios")
        assertEquals("场景数须一致", scenarios.length(), expected.length())
        var steps = 0
        for (s in 0 until scenarios.length()) {
            val sc = scenarios.getJSONObject(s)
            val exp = expected.getJSONObject(s)
            assertEquals("场景 id 须一致", sc.getString("id"), exp.getString("id"))
            val track = CharacterTrack(stance = stanceOf(sc.getString("stance")))
            val ops = sc.getJSONArray("ops")
            val expSteps = exp.getJSONArray("steps")
            assertEquals("场景 ${sc.getString("id")} 步数须一致", ops.length(), expSteps.length())
            for (i in 0 until ops.length()) {
                val op = ops.getJSONObject(i)
                val msg = runOp(track, op)
                val e = expSteps.getJSONObject(i)
                val where = "场景 ${sc.getString("id")} 第 ${i + 1} 步 ${op.getString("op")}"
                assertTrue("$where：不变量违例", track.invariantsHold())
                assertTrue("$where：op 须同源", jsonEquals(op, e.getJSONObject("op")))
                val expMsg = e.get("msg")
                if (expMsg == JSONObject.NULL) {
                    assertEquals("$where：msg 应为 null", null, msg)
                } else {
                    assertEquals("$where：msg 逐字相同", expMsg, msg)
                }
                val actual = stateJson(track)
                assertTrue(
                    "$where：状态逐字段相同 期望=${e.getJSONObject("state")} 实际=$actual",
                    jsonEquals(e.getJSONObject("state"), actual),
                )
                assertEquals("$where：snapshot 逐字节相同", e.getString("snapshot"), track.snapshot())
                steps += 1
            }
        }
        assertEquals("总步数须与夹具一致", 174, steps)
    }

    @Test
    fun parity_fixture_binds_reference_fingerprint() {
        val doc = JSONObject(resource("parity-expected.json"))
        assertEquals("参考实现字节数（派工单 8,345 B）", 8345, doc.getInt("reference_bytes"))
        assertEquals(
            "参考实现 sha256（指纹绑定对拍基准）",
            "38c6aa4f537cbc67cbb49752226484458c089c4387f9d4442b34b0e47ac27c09",
            doc.getString("reference_sha256"),
        )
    }
}