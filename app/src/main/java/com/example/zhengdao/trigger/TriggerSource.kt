// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

/**
 * 事件触发源（第三百九十九批「手机侧自己接活」）。
 *
 * 派工单 §一 排了四个源，**本刀只做前两个**——通知关键词与文件变化是后两刀，
 * 枚举里**不占位**：放两个死值进去，`when` 会逼每个调用方空转、门禁还得另扫「不许用」，
 * 比不加还贵。后两刀来了往这里加一行 + 各自的判据，`fromWire` 认不出旧值也不受影响。
 *
 * 与 167 定时任务（`schedule/` 包）的关系，一句话：**167 是「人派活」的定时器，
 * 本包是「有件事能把抱朴叫醒」的事件源层**。两者的定时形状刻意共用一套语义
 * （频率/时刻/去重槽位都出自 [com.example.zhengdao.schedule.ScheduleRules]），
 * 但源级开关、限频、事件账是本包自己的——派工单 §二四条硬规矩只挂在这层。
 */
enum class TriggerSource(val wire: String) {
    /** 定时：到点（一次/每天/每周；cron 仍不支持——218 批 `schedule_task` 同一挂账口径）。 */
    SCHED("sched"),

    /** 日程/提醒：日历事件开始前 N 分钟（事件来自系统日历提供器，只读本机）。 */
    CALENDAR("calendar"),
    ;

    companion object {
        fun fromWire(v: String?): TriggerSource? = entries.firstOrNull { it.wire == v }
    }
}
