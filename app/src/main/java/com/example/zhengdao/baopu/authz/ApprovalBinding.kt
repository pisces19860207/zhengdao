// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P1②（法典卷八 **L26 授权绑定三元组**）：授权绑的是**哪一件事**，
// 只能由三元组说死——`actionId + argsHash + 对象清单摘要`。任一不符 ⇒ **视为未授权**，
// 不是"差不多就放行"，也不是"提醒一下"。
//
// ## 为什么 249 的六步还挡不住这一格
// 六步第①步比的是「这个**对象**在不在清单里」、第②步比的是「这个**动作类型**在不在授权里」。
// 两者都成立、而"这件事"已经变了的情形还有一格：**参数被改过**。同一份清单、同一个改正文，
// 上限从 2MB 漂到 20MB、或者快照里第 3 个对象的内容摘要换了一版——六步会照样说"过"。
// L26 要挡的正是这一格：**批准的是那一份，做的就必须是那一份**。
//
// ## 三元组在本仓的口径（为什么这样取）
// · `actionId`＝授权批下的**动作集**标识（`create_text,edit_text` 那种规范化写法）。
//   动作集变了＝批的不是这件事——即使每个单独的动作都还在旧授权里。
// · `argsHash`＝**卡面呈现参数**的摘要（走生产那一份哈希 [ApprovalBindings.argsHashOf]，
//   16 位十六进制；法典卷九 L26 明确「判据层不再另造一个哈希」）。限额、有效期、契约号、
//   会话这些"卡上给她看过的数"按**固定顺序**拼成一行再摘要：任何一个字段被改，这一格就变。
//   为什么不直接摘要 JSON 文本：`JSONObject.toString()` 不保证键序，键序漂动会让比对假红。
// · `objectsDigest`＝授权对象清单的规范化摘要（按 `objectId` 排序后取摘要）。
//   清单换了＝动的是别的对象。
//
// ## 两条纪律
// 1. **不做任何近似匹配**：三条全等才算同一件事（`same` 就是数据类相等，没有"忽略空格"、
//    没有"前缀相同"）。法典 L26 的负例③「文本相同但对象不同」判的就是近似匹配。
// 2. **撤销也按三元组定位，不许按文本匹配**：撤销单上带的三元组与授权时那份不一致 ⇒ 拒，
//    并且**不撤**（撤错单比不撤严重得多——"撤销"是对已生效授权的收口，撤错了就等于
//    把别人那张还在生效的授权悄悄关掉）。
//
// ## 为什么这一层是纯函数
// 与 219/242/249 同一房型：判定层零 IO、零时钟、不碰盘、不读全局，事实全部由调用方
// （`authz/TaskGrantWire.kt`）算好传进来。负例②「改一个参数用旧授权必拒」因此能在 JVM
// 里直接钉死，不需要真机、也不需要假文件。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.agent.RunTraceRules

/**
 * 一次批准绑到的那**一件事**（三元组；不可变，比较就是整份相等）。
 *
 * internal：它只在授权链内部比对，卡面与探针回读的是那一句人话
 * （[GrantReading.bindingNote] 给判定时的那一句、[TaskGrantBoard.revokeBrief] 给撤销时的那一句），
 * 不把三元组结构推到 UI 层去。
 */
internal data class ApprovalBinding(
    /** 授权批下的动作集（规范化写法；见文件头「口径」第 1 条）。 */
    val actionId: String,
    /** 卡面呈现参数的摘要（16 位十六进制；与 `run_trace.args_hash` 同一个算法）。 */
    val argsHash: String,
    /** 对象清单摘要（按 objectId 排序后的规范化摘要）。 */
    val objectsDigest: String,
)

/**
 * L26 的三元组判定（唯一实现；执行闸与撤销闸共用一套相等口径）。
 *
 * 结论码是稳定键（账本、勘验、交付报告都按它统计），改字面量＝老读数读不出结论。
 */
internal object ApprovalBindings {

    const val CODE_MATCH = "match"

    /** 三份里任一份不符的**汇总码**（读数只到"不符"这一层；不符在哪一份看 [executeAllowed] 的那句话）。 */
    const val CODE_MISMATCH = "binding_mismatch"

    /** 摘要位数（与 `RunTraceRules.ARGS_HASH_LEN` 同长；两份摘要放一起比对不刺眼）。 */
    const val DIGEST_LEN = 16

    /**
     * 对象清单摘要（**先排序再拼**：清单是集合，卡上顺序不该改变"是不是同一件事"）。
     *
     * 空清单也有一个确定值（不返回空串）：空串在别处一律读作"没这个字段"，
     * 拿它当摘要会把「清单被清空」与「从来没记过清单」混成一件事。
     */
    fun objectsDigestOf(objectIds: List<String>): String =
        sha256Hex(objectIds.sorted().joinToString("|")).take(DIGEST_LEN)

    /**
     * 授权动作集的规范化写法（排序去重后用 `,` 连；与 [GrantActionType.wire] 同一套词）。
     *
     * 为什么排序：`[改正文, 新建]` 与 `[新建, 改正文]` 是同一件事——不排就会让卡上的
     * 渲染顺序参与判定（那是 L26 禁止的"近似匹配"的反面：过度匹配）。
     */
    fun actionIdOf(actions: List<GrantActionType>): String =
        actions.map { it.wire }.distinct().sorted().joinToString(",")

    /**
     * 参数摘要的**唯一入口**（算法与 `run_trace.args_hash` 同一个：16 位十六进制）。
     *
     * 为什么收一个已经拼好的字符串而不是 JSONObject：`JSONObject.toString()` 不保证键序，
     * 直接摘要 JSON 文本会让"摘要不符"因为键序漂动而假红。调用方（`TaskGrantWire`）
     * 按固定顺序拼好再进来 ⇒ 同一份快照永远同一个值。
     */
    fun argsHashOf(snapshot: String): String = RunTraceRules.argsHashOf(snapshot)

    /** 执行闸：授权绑的不是这次动作 ⇒ 视为未授权（[first]＝false 时**不许**免卡）。 */
    fun executeAllowed(granted: ApprovalBinding?, actual: ApprovalBinding): Pair<Boolean, String> =
        when {
            granted == null -> false to "没有授权绑定：这次动作不走免卡（先开单）"
            granted == actual -> true to CODE_MATCH
            granted.actionId != actual.actionId ->
                false to "actionId 不符：批的动作集是「${granted.actionId}」，这次是「${actual.actionId}」"
            granted.argsHash != actual.argsHash ->
                false to "argsHash 不符：参数被改过（${granted.argsHash} → ${actual.argsHash}），旧授权作废"
            else ->
                false to "对象清单摘要不符：批的是「${granted.objectsDigest}」，这次动的是「${actual.objectsDigest}」"
        }

    /**
     * 撤销闸：按三元组定位那一单；不符 ⇒ 拒且**不撤**（法典 L26「不许按文本匹配撤销」）。
     *
     * 与 [executeAllowed] 的差别只有一处：撤销必须有一份**已存在**的绑定可比
     * （没有授权可撤是"没这一单"，不是"三元组不符"，所以不给 null 分支）。
     */
    fun revokeAllowed(granted: ApprovalBinding, target: ApprovalBinding): Pair<Boolean, String> =
        if (granted == target) {
            true to CODE_MATCH
        } else {
            false to "撤销目标与授权时不是同一个三元组：不许按文本匹配撤销" +
                "（actionId ${granted.actionId}/${target.actionId}，" +
                "argsHash ${granted.argsHash}/${target.argsHash}，" +
                "对象摘要 ${granted.objectsDigest}/${target.objectsDigest}）"
        }
}
