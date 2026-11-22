// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十九批 · 表层 state 的唯一入口：由 670 角色轨状态快照推五维（耐心/酒意/疲惫/羞耻/紧张）。
// 硬规矩：表层只能由状态推，不改①core ②trait；只有"性格开朗"这种笼统标签拿不到状态 ⇒ 推不出、判红。
// 纯函数、无 IO、无随机、无时钟；每一维都带 source，写清由哪条状态推出。
package com.example.zhengdao.baopu.state.persona

/** 表层推导器。五维各有确定性档位函数，输入同一快照必得同一表层。 */
object SurfaceDeriver {

    /** 快照合法性：轮数非负、显性度/阶段/酒意/疲惫在量程内。非法输入直接拒。 */
    fun validate(s: TrackStateSnapshot) {
        require(s.turns >= 0) { "轮数不得为负：${s.turns}" }
        require(s.salience in 1..4) { "显性度只取 Lv1–4：${s.salience}" }
        require(s.dStage in 0..6) { "防守阶段只取 0–6：${s.dStage}" }
        require(s.intoxication in 0..3) { "酒意只取 0–3：${s.intoxication}" }
        require(s.fatigue in 0..3) { "疲惫只取 0–3：${s.fatigue}" }
    }

    /** 主推导：五维齐、每维带来源。 */
    fun derive(s: TrackStateSnapshot): SurfaceState {
        validate(s)
        return SurfaceState(
            listOf(
                SurfaceLevel(SurfaceAxis.PATIENCE, patience(s), "轮数${s.turns}·显性度Lv${s.salience}"),
                SurfaceLevel(SurfaceAxis.INTOXICATION, s.intoxication, "外部喂入酒意信号"),
                SurfaceLevel(SurfaceAxis.FATIGUE, s.fatigue, "外部喂入疲惫信号"),
                SurfaceLevel(SurfaceAxis.SHAME, shame(s), "防守阶段S${s.dStage}·立场${s.stanceLabel}"),
                SurfaceLevel(SurfaceAxis.TENSION, tension(s), "显性度Lv${s.salience}·硬否决=${s.vetoActive}"),
            ),
        )
    }

    /**
     * 耐心：轮数越多越不耐，显性度越高（越逼近）也越不耐；硬否决再降一档。
     * 由 3 起、两项各按阈值递减，封在 0–3。
     */
    private fun patience(s: TrackStateSnapshot): Int {
        var tier = 3
        if (s.turns >= 4) tier -= 1
        if (s.turns >= 8) tier -= 1
        if (s.salience >= 3) tier -= 1
        if (s.vetoActive) tier -= 1
        return clamp(tier)
    }

    /** 羞耻：只在掩饰/憋声之后（S3+）起，随阶段升，立场为"排斥"时再升一档。 */
    private fun shame(s: TrackStateSnapshot): Int {
        if (s.dStage < 3) return 0
        var tier = s.dStage - 2 // S3=1 … S6=4
        if (s.stanceLabel == "排斥") tier += 1
        return clamp(tier)
    }

    /** 紧张：显性度三档以上起，硬否决直接顶到 3。 */
    private fun tension(s: TrackStateSnapshot): Int {
        if (s.vetoActive) return 3
        return when {
            s.salience <= 1 -> 0
            s.salience == 2 -> 1
            else -> 2
        }
    }

    private fun clamp(tier: Int): Int = maxOf(0, minOf(3, tier))
}
