// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十八批 · 读数消费者的**装配端口**（形制照 LedgerPorts：进程内单例、可空、可回滚）。
//
// 为什么是"注入一个取数函数"而不是直接把 ReadingFacts 塞进来：
//   · `:policy` 够不着 :app 的四类产物（白名单），只能由装配层给一个**取数闭包**；
//   · 闭包每次调用现取 ⇒ 调解器读到的是"当前事实"，不是某次装配时的旧快照；
//   · `null`＝消费者关（未装配/被掐掉）⇒ 调解器走原路径，行为逐字节一致（判据②③）；
//     变异测试只要把这里置 null 就能验证决定回退。
//
// 零 IO、零状态、零 Android：本文件只持有闭包；provider 抛错一律吞成"无读数"——
// 消费读数不许把正常调解流程带崩（安全侧默认回退原行为）。
package com.example.zhengdao.baopu

object ReadingPorts {

    /** 当前取数闭包；`null`＝读数消费关闭（默认；回滚位）。 */
    @Volatile
    var provider: (() -> ReadingFacts)? = null

    /**
     * 取一次事实。provider 未装配 ⇒ null；provider 抛错 ⇒ null（回退原行为，不把异常抛进弹卡链）。
     */
    fun snapshot(): ReadingFacts? =
        if (provider == null) null else runCatching { provider!!() }.getOrNull()

    /** 关掉消费者（回滚 / 变异 / resetForTest 调用）。 */
    fun detach() {
        provider = null
    }
}
