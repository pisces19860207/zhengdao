// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 画像黑箱的存储外壳（引擎见 [TwoLayerProfile]，限频见 [ColdStartThrottle]）。
//
// 为什么文件不叫"画像"也不放可读目录（M7 判据「画像文件不在可读目录」）：
//   - 落点由装配层给 `filesDir/baopu_internal/`——**不在 `抱朴记忆/`**（那棵用户可看可备份），
//     **不在工作区**（`Download/证道`），文件名也不含"画像"两字；
//   - 文件内容天生没有原文：引擎的类型上就只有机器键/时刻/权重/哈希（`TwoLayerProfile.feed`
//     的形状闸），所以"扫文件搜到原文"这件事在写侧就不可能，不靠读侧过滤。
//
// 黑箱的对外面积（`ProfileInvisibilityTest` 逐个钉死，加一个读口就是红）：
//   feed（写）/ recompute（充电重算）/ clearShortTerm（清短期层）/ conclusion（勘验，只出结论）
//   / distribution·isStableKind（harness 自用判据，M7 接线批来接）/ installed（装配检查）。
//   **没有**"读原始采样列表"的函数——模型与人都拿不到明细。
//
// 三条照抄账本纪律：写失败不抛（留一行日志）；`@Synchronized`；没装配时只进内存不落盘。
package com.example.zhengdao.baopu.profile

import com.example.zhengdao.core.RunLog
import org.json.JSONObject
import java.io.File

internal object UserProfileBlackbox {

    /** 私有内部目录名（装配层传入 `filesDir/baopu_internal`；不在任何用户可读目录下）。 */
    const val INTERNAL_DIR = "baopu_internal"

    const val PROFILE_FILE = "p2l_state.json"
    const val THROTTLE_FILE = "p2l_throttle.json"

    @Volatile
    private var dir: File? = null

    private val engine = TwoLayerProfile()
    private val throttle = ColdStartThrottle()

    /** 装配（只存目录；幂等——重复装配**不**重置状态，文件里的账还在）。 */
    @Synchronized
    fun install(internalDir: File) {
        dir = internalDir
        val pf = File(internalDir, PROFILE_FILE)
        if (pf.isFile) {
            runCatching {
                val o = JSONObject(pf.readText(Charsets.UTF_8))
                if (o.optInt("v", 0) == TwoLayerProfile.VERSION) engine.loadFrom(o)
            }.onFailure { RunLog.log("画像黑箱：状态读不回，按空画像起——${it.javaClass.simpleName}") }
        }
        val tf = File(internalDir, THROTTLE_FILE)
        if (tf.isFile) {
            runCatching {
                throttle.loadFrom(JSONObject(tf.readText(Charsets.UTF_8)))
            }.onFailure { RunLog.log("画像黑箱：限频状态读不回，按新装机起——${it.javaClass.simpleName}") }
        }
    }

    fun installed(): Boolean = dir != null

    /**
     * 唯一写口：喂一条采样。屏幕不亮/不在前台/暂停/超日额 ⇒ 拒（**超限即停**）。
     *
     * 形状闸不过（散文 kind）⇒ IllegalArgumentException——那是调用方的 bug，不该静默。
     */
    fun feed(
        kind: String,
        weight: Int,
        fromUser: Boolean,
        now: Long,
        screenOn: Boolean,
        foreground: Boolean,
        textSha: String = "",
        textLen: Int = 0,
    ): FeedVerdict {
        val verdict = throttle.feed(now, screenOn, foreground)
        if (verdict is FeedVerdict.Deny) return verdict
        engine.feed(kind, weight, fromUser, now, textSha, textLen)
        engine.purge(now)
        save()
        return verdict
    }

    /** 重算（衰减归一＋清理＋落盘）：**只认充电**；用电池时一律跳过并返回 false。 */
    fun recompute(now: Long, charging: Boolean): Boolean {
        if (!throttle.allowRecompute(charging)) return false
        engine.recompute(now)
        save()
        return true
    }

    /** 清短期层（删明细保聚合；清后第一周回弱先验 1:1:1）。返回删掉几条。 */
    fun clearShortTerm(now: Long): Int {
        val removed = engine.clearShortTerm(now)
        save()
        return removed
    }

    /** harness 自用判据读口（不进上下文、不渲染；M7 接线批的唯一消费形态）。 */
    fun distribution(now: Long, kinds: Collection<String>): Map<String, Double> =
        engine.distribution(now, kinds)

    fun isStableKind(kind: String, now: Long): Boolean = engine.isStableKind(kind, now)

    /**
     * 勘验出口：只出结论（一行、全计数/布尔），限频状态一并给。**没有一条明细**。
     */
    @Synchronized
    fun conclusion(now: Long): String = engine.conclusion(now) +
        "；冷启动限频=${if (throttle.coldStartActive(now)) "生效" else "已过"}" +
        "（今日已收=${throttle.todayCount(now)}，暂停=${if (throttle.paused) "是" else "否"}）"

    /** 引擎 TTL 重调（7–15 夹回；默认 14——勘验/运维口，不进界面）。 */
    fun retuneTtl(days: Int): Int {
        val v = engine.retuneTtl(days)
        save()
        return v
    }

    @Synchronized
    private fun save() {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            File(d, PROFILE_FILE).writeText(engine.toJson().toString(), Charsets.UTF_8)
            File(d, THROTTLE_FILE).writeText(throttle.toJson().toString(), Charsets.UTF_8)
        }.onFailure {
            RunLog.log("画像黑箱：落盘失败——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 测试专用：单例状态跨用例污染，谁用谁还（房型照 SelfRulingLedger）。 */
    @Synchronized
    fun resetForTest() {
        dir = null
        engine.loadFrom(JSONObject().put("v", TwoLayerProfile.VERSION))
        throttle.loadFrom(JSONObject().put("v", ColdStartThrottle.VERSION))
    }
}
