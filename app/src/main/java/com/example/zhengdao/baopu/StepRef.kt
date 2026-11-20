// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百五十五批 · 失败→计划步号归因（三态；派工单 755 ①②③）。
//
// 病灶：714 已合（e6d86d8b2），但前额叶"卡步"恒 null——引擎从没记过
// "这次失败发生在计划的哪一步"，喂步号等于猜（BioReadoutWiring 的原话口径），
// 所以 714 把 recentFailures 全记 null 是对的。本件补的就是那道缺口：
// **当场归因**——失败/工具报错事件在发射那一刻，从引擎手里 update_plan
// 落下的计划指针（doing 标记）算出三态，随事件本身落账（run_log.step_ref 列）；
// 禁止任何"事后回扫日志/账行推步号"的路（本件不读日志、不读账，输入只有
// 计划条目与"库里有计划史"一个布尔——结构上没有回扫通道）。
//
// 三态（派工单 755 ②逐字）：
//   attributed(step=N)     —— 计划里恰好一条 doing，失败归它（步号＝条目序号＋1，从 1 起）
//   ambiguous(candidates)  —— ≥2 条 doing；候选升序全给，≥2 才许构造（缺一个都不是 ambiguous）
//   unattributed(code)     —— 判不了就显式说为什么，原因码闭集（第九个产不出来）：
//       no-plan            本会话从没建过计划（库里查不到任何计划快照）
//       step-not-recorded  计划在，但模型没标 doing——"正在做哪步"没记录
//       tool-failed-early  本轮失败发生在计划指针可用之前（新轮还没跑过一次成功的
//                          update_plan；会话有计划史也不拿旧指针冒充本轮归因）
//
// 四条硬规矩（判据逐字落码）：
//   ① 宁可 unattributed 也不给好看的假值——0 条 doing 就是没记录，
//     不猜"第一条没做完的"（与 694 前额叶"判不了就不猜"同一教条）；
//   ② 纯函数零状态：同输入两次返回逐字相等（判据"同输入两次一致"；
//     候选固定升序去重，wire 逐字节可复算）；
//   ③ 线格式是闭集：attributed:N ／ ambiguous:N[,N…] ／ unattributed:<code>，
//     [of] 认不出的一律 null——坏线进不了账（753 validate 同款纪律）；
//   ④ 字段名 [wire] 全仓一致：run_log 列名 step_ref、JSONL reading 键 stepRef
//     （755 ③与 753 对齐；列用 snake、键用 camel，各守各层既有约定）。
package com.example.zhengdao.baopu

/**
 * 失败→计划步号的三态归因（纯函数件；零状态、零落盘、零库读）。
 *
 * 生产调用面两处：引擎在失败/工具报错事件发射那一刻调 [from] 并把 [wire] 写进
 * `run_log.step_ref`（BaopuSql.insertRunLog 是唯一写口，失败行缺它直接拒）；
 * 喂入侧（BioFeeds）用 [of] 解回三态，只取 attributed 的步号喂前额叶。
 */
internal sealed interface StepRef {

    /** 线格式（列值/解析的唯一串形；同输入两次逐字一致）。 */
    val wire: String

    /** 归因成功：失败落在计划的第 [step] 步（1 起；0 或负数构造不出来）。 */
    data class Attributed(val step: Int) : StepRef {
        init {
            require(step >= 1) { "计划步号从 1 起（收到 $step）" }
        }

        override val wire: String = "attributed:$step"
    }

    /** 归因不成但有候选：计划里 ≥2 条 doing（[candidates] 升序去重；少于两条构造不出来）。 */
    data class Ambiguous(val candidates: List<Int>) : StepRef {
        init {
            require(candidates.size >= 2) { "ambiguous 至少两个候选（收到 ${candidates.size} 个）" }
        }

        override val wire: String = "ambiguous:" + candidates.joinToString(",")
    }

    /** 判不了：显式原因码（判据"裸 null ⇒ 红，除非显式 unattributed"的兑现处）。 */
    data class Unattributed(val code: Code) : StepRef {
        override val wire: String = "unattributed:${code.wire}"
    }

    /** 原因码闭集（派工单 755 ②逐字；[of] 拒判第九个名字）。 */
    enum class Code(val wire: String) {
        NO_PLAN("no-plan"),
        STEP_NOT_RECORDED("step-not-recorded"),
        TOOL_FAILED_EARLY("tool-failed-early"),
        ;

        companion object {
            /** 线名→原因码：不在闭集里的名字一律 null（753 Loop.of 同款拒判口）。 */
            fun of(wire: String): Code? = entries.firstOrNull { it.wire == wire }
        }
    }

    companion object {

        /**
         * 当场归因主入口（纯函数，判据"同输入两次一致"）。
         *
         * [plan] ＝ 发射那一刻的计划条目（引擎手里 update_plan 落下的内存指针；
         * 指针没立/不可信时引擎传空表——旧指针不冒充本轮归因）。
         * [storeHasPlan] ＝ 该会话库里有没有计划史；**只在计划为空时被读**
         * （空指针＋有计划史 ⇒ 失败早于本轮计划可用点；空指针＋无计划史 ⇒ 从没建过计划）。
         */
        fun from(plan: List<PlanRules.PlanItem>, storeHasPlan: Boolean): StepRef {
            val doing = plan.withIndex()
                .filter { it.value.status == PlanRules.STATUS_DOING }
                .map { it.index + 1 }
            return when {
                plan.isEmpty() ->
                    if (storeHasPlan) Unattributed(Code.TOOL_FAILED_EARLY) else Unattributed(Code.NO_PLAN)
                doing.size == 1 -> Attributed(doing.single())
                doing.isEmpty() -> Unattributed(Code.STEP_NOT_RECORDED)
                else -> Ambiguous(doing)
            }
        }

        /**
         * 线→三态：认不出/形状坏（候选 <2、步号 <1、候选不升序、原因码不在闭集）一律 null。
         * 严格到"升序去重"是为了 round-trip 逐字：[of](x.wire) 对三种态都还原同一个 x。
         */
        fun of(raw: String): StepRef? {
            val head = raw.substringBefore(':')
            val tail = raw.substringAfter(':', missingDelimiterValue = "")
            return when (head) {
                "attributed" -> tail.toIntOrNull()?.takeIf { it >= 1 }?.let { Attributed(it) }
                "ambiguous" -> candidatesOf(tail)
                "unattributed" -> Code.of(tail)?.let { Unattributed(it) }
                else -> null
            }
        }

        /** ambiguous 段的形状闸：≥2 个、全是 ≥1 的整数、严格升序去重——缺一条就不是合法线。 */
        private fun candidatesOf(tail: String): Ambiguous? {
            val parts = tail.split(',')
            if (parts.size < 2) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 1 }) return null
            if (nums != nums.sorted() || nums.toSet().size != nums.size) return null
            return Ambiguous(nums)
        }
    }
}
