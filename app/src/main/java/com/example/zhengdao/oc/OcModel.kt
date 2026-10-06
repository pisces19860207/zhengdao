// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.oc

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型池条目（v1.0 任务一）：GET /api/model 的 Model.Info 中 UI 需要的子集。
 *
 * 免费判定按产品定稿：providerID == "opencode" 且 **cost 数组全维度为 0**。
 * cost 缺失/无法确认时不标免费（宁缺勿滥——把付费模型标成免费是事故）。
 */
data class OcModel(
    val id: String,
    val providerID: String,
    val name: String,
    val free: Boolean,
) {
    /** 列表里的稳定显示名（provider 分组下只展示 id/name）。 */
    val displayName: String get() = name.ifBlank { id }

    companion object {
        fun fromJson(o: JSONObject): OcModel? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val providerID = o.optString("providerID").takeIf { it.isNotEmpty() } ?: return null
            return OcModel(
                id = id,
                providerID = providerID,
                name = o.optString("name"),
                free = providerID == "opencode" && isAllZeroCost(o.optJSONArray("cost")),
            )
        }

        /** cost 数组全维度为 0 判定：递归展开数组/对象里的所有数值，全部为 0 才算免费。
         *  空数组/缺失 = 无法确认计费，返回 false（不标免费）。 */
        private fun isAllZeroCost(cost: JSONArray?): Boolean {
            cost ?: return false
            if (cost.length() == 0) return false
            for (i in 0 until cost.length()) {
                when (val v = cost.get(i)) {
                    is Number -> if (v.toDouble() != 0.0) return false
                    is JSONArray -> if (!isAllZeroCost(v)) return false
                    is JSONObject -> {
                        for (k in v.keys()) {
                            when (val inner = v.opt(k)) {
                                is Number -> if (inner.toDouble() != 0.0) return false
                                is JSONArray -> if (!isAllZeroCost(inner)) return false
                            }
                        }
                    }
                }
            }
            return true
        }
    }
}
