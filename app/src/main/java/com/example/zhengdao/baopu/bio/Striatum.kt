// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百九十四批 · 纹状体·奖赏预测误差（六体第⑤件，登记表第四行）。
//
// 第七百五十六批 · corrected 语义收口（只改这一个字段的语义，四格表与 Rpe 输出形状不动）：
//   714 §遗留2 披露本字段在生产侧恒 false（521 HabitWiring:194 写死），诊断＝"有实现但
//   从不触发"（非占位：算法层 41/46 行有 true 分支、StriatumTest 全四格覆盖）。
//   裁定＝补语义，不删字段：删掉会让 0 档（ok＋被纠正）永不可达、三档塌成两档，
//   违背本文件头"三档 {-1,0,+1} 要全可达"的域定义与 523 三件套①登记。
//   收口口径：corrected 只能来自一次**显式修正事件**（[Correction]：cause 来源指针＋
//   from/to 修正前后值，三件缺一构造不出来）；不是"误差变小"，不是"后来猜对了"。
//   corrected 因此是只读派生属性，没有裸布尔写入口——"凭空 true"在编译期就做不到。
//   边界：不做跨轮回填（收工拍拿不到下一轮的纠正证据 ⇒ evidence=null，如实为 false）。
//
// 机理：奖赏预测误差——"预期 vs 实际"的差信号。只做：对一次工具结果出
// {rpe: -1|0|+1, note} 的差读数。不做：不投票、不改档——授权档是人改的
// （AskGovernance 那张面），习惯分/晋升退役是既有评分面的权；本件连它们的
// 类型都不引用（BioSourceAssertTest 钉禁引擎符号），读完即弃、不攒任何分。
//
// 差读数口径（本批裁定，纯机械四格表，输入只有两个布尔）：
//   预测基线＝"这次调用会成"。实际只有三种落点：
//   · err           ⇒ -1  预测落空（失败就是失败，用户有没有跟着纠正都改变不了它）；
//   · ok ＋ 被纠正  ⇒  0  结果在手但被打回——正（成了）负（打回）相抵，差读数为零；
//   · ok ＋ 未纠正  ⇒ +1  一次过且无人纠正，正向兑现。
// 严格 RPE 里"兑现如预期"该记 0；但本件的输入缝只有成败与纠正两个布尔，
// 三档 {-1,0,+1} 要全可达只有这一张表——0 留给"成了但被打回"（最接近
// "预期落空一半"的形态），+1 给"一次过"。替代口径（ok+未纠正=0）会让 +1
// 永不触发，等于把三档砍成两档，违背读数域定义。
//
// 本件无判定权（523 三件套②）：不产生 RiskDecision/PROCEED/REJECT，不改档，不写权威账。
package com.example.zhengdao.baopu.bio

/** 纹状体·奖赏预测误差（纯函数器官，零状态零落盘）。 */
@BioAnalog("纹状体")
internal object Striatum {

    /**
     * 一次**显式修正事件**（756）：预测值被后续证据改判。三件缺一不可——构造口即拒。
     *
     * [cause]＝什么证据触发了修正（来源指针，非空；如 `USER_NEGATED@skill:查文献`），
     * 不是"误差变小"或"后来猜对"这类结果描述；[from]/[to]＝修正前/后的差读数，
     * 都必须落在 {-1,0,+1} 三档域内且**不相等**（相等＝没发生修正动作 ⇒ 拒）。
     * 判不了就不猜：拿不到来源指针时不要构造本类，让 [Outcome.corrected] 走 false。
     */
    data class Correction(val cause: String, val from: Int, val to: Int) {
        init {
            require(cause.isNotBlank()) { "修正事件必须带 cause（来源指针）" }
            require(from in -1..1 && to in -1..1) { "修正前后值必须落在 {-1,0,+1}" }
            require(from != to) { "from 与 to 相等＝没有发生修正动作" }
        }
    }

    /**
     * 一次工具结果的机械面：[ok]＝这次成没成（引擎结果 Ok 与否，Failed/Rejected 都算错）；
     * [evidence]＝显式修正事件（null＝没有修正动作，corrected 即 false）。
     */
    data class Outcome(val ok: Boolean, val evidence: Correction?) {
        /** corrected 只能由 evidence 派生——没有裸布尔写入口，"凭空 true"构造不出来。 */
        val corrected: Boolean get() = evidence != null
    }

    /** 差读数：value 恒在 {-1, 0, +1}；note 一句机械说明（无时间戳）。字段名/类型与 753 `loop=rpe` 行一致。 */
    data class Rpe(val value: Int, val note: String)

    /** 差读数主入口。同一 outcome 调两次，[Rpe] 逐字相等（判据④）。 */
    fun rpe(outcome: Outcome): Rpe {
        val value = when {
            !outcome.ok -> NEGATIVE
            outcome.corrected -> ZERO
            else -> POSITIVE
        }
        val why = when (value) {
            NEGATIVE ->
                if (outcome.corrected) {
                    "错且用户跟着纠了——预测落空，差读数 $NEGATIVE（纠正不改变失败这个事实）"
                } else {
                    "错——预测落空，差读数 $NEGATIVE"
                }
            ZERO -> "成了但被打回——正负相抵，差读数 $ZERO"
            else -> "一次过且无人纠正——正向兑现，差读数 $POSITIVE"
        }
        val e = outcome.evidence
        val trace = if (e == null) "corrected=false"
        else "corrected=true cause=${e.cause} from=${e.from} to=${e.to}"
        BioLog.line(TAG, "rpe=$value ok=${outcome.ok} $trace")
        return Rpe(value = value, note = "$why（读数仅供参考：不投票、不改档，判定权在既有裁决面）")
    }

    private const val TAG = "纹状体"

    /** 三档差读数（域定义封死：不在这三档里的值产不出来）。 */
    private const val NEGATIVE = -1
    private const val ZERO = 0
    private const val POSITIVE = 1
}
