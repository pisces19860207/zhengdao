// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：org.json 官方文档、FIPS 180-4（无直接关系，占位说明）。
package com.example.zhengdao.oc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型池免费判定（v1.0 任务一）的边界测试。
 * 产品定稿：providerID == "opencode" 且 **cost 数组全维度为 0** → 免费；
 * cost 缺失/无法确认 → 不标免费（把付费模型标成免费是事故）。
 */
class OcModelTest {

    private fun model(providerID: String, costJson: String?): OcModel? {
        val o = JSONObject().put("id", "test-model").put("providerID", providerID)
            .put("name", "测试模型")
        costJson?.let { o.put("cost", JSONArray(it)) }
        return OcModel.fromJson(o)
    }

    @Test
    fun 免费判定_opencode全零cost() {
        val m = model("opencode", """[{"input":0,"output":0}]""")
        assertTrue(m!!.free)
    }

    @Test
    fun 免费判定_嵌套数组全零() {
        val m = model("opencode", """[[0,0],[0]]""")
        assertTrue(m!!.free)
    }

    @Test
    fun 不免费_cost含非零() {
        val m = model("anthropic", """[{"input":3,"output":15}]""")
        assertFalse(m!!.free)
    }

    @Test
    fun 不免费_opencode但cost非零() {
        val m = model("opencode", """[{"input":0.5,"output":2}]""")
        assertFalse(m!!.free)
    }

    @Test
    fun 不免费_cost缺失() {
        // 缺 cost = 无法确认计费 → 不标免费（宁缺勿滥）
        val m = model("opencode", null)
        assertFalse(m!!.free)
    }

    @Test
    fun 不免费_cost空数组() {
        val m = model("opencode", "[]")
        assertFalse(m!!.free)
    }

    @Test
    fun fromJson_缺id返回null() {
        val o = JSONObject().put("providerID", "opencode").put("name", "无名")
        assertNull(OcModel.fromJson(o))
    }

    @Test
    fun fromJson_缺providerID返回null() {
        val o = JSONObject().put("id", "m1").put("name", "无主")
        assertNull(OcModel.fromJson(o))
    }

    @Test
    fun displayName_name优先_空则回退id() {
        val withName = OcModel.fromJson(
            JSONObject().put("id", "m1").put("providerID", "p1").put("name", "好名字")
        )!!
        assertEquals("好名字", withName.displayName)
        val noName = OcModel.fromJson(
            JSONObject().put("id", "m2").put("providerID", "p1")
        )!!
        assertEquals("m2", noName.displayName)
    }

    @Test
    fun 真实形态样例_opencode免费模型() {
        // 2026-10-07 真机 /api/model 实录的 big-pickle 形态（节选字段）
        val o = JSONObject()
            .put("id", "big-pickle")
            .put("providerID", "opencode")
            .put("name", "Big Pickle")
            .put("cost", JSONArray("[{\"input\":0,\"output\":0,\"cache_read\":0,\"cache_write\":0}]"))
            .put("limit", JSONObject().put("context", 190000).put("output", 30000))
        val m = OcModel.fromJson(o)!!
        assertTrue(m.free)
        assertEquals("big-pickle", m.id)
    }
}
