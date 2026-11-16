// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 六体读数公共件：事件形状＋日志落点。
//
// 杏仁核/岛叶/丘脑三件共用的最小输入形状。本文件不是器官，不带登记标记
// （登记闸只认器官类，六件闭集不受它影响）。夹在这个包里只为少一层引用。
package com.example.zhengdao.baopu.bio

/**
 * 一条进六体的输入事件。只有文本与来源标签——六体读数只看这两个。
 * 文本可为空串（读数按"无特征"走，不抛异常）；来源只是溯源标签，不参与分档。
 */
data class BioEvent(
    val text: String,
    val source: String,
) {
    companion object {
        /** 空事件：调用方没给正文时的合法输入。 */
        val EMPTY: BioEvent = BioEvent(text = "", source = "unknown")
    }
}

/**
 * 三件共用的日志落点：一行 stdout，仅此而已。
 * 派工单判据③"杏仁核与丘脑只 log 不写 run_log/账本"——log 就是这里（打印），
 * 落库/落账的代码路径在六体读数里**不存在**（BioSourceAssertTest 按源码钉）。
 */
internal object BioLog {
    /** 打一行带前缀的日志。无时间戳——读数确定性不被日志搅动（判据④）。 */
    fun line(tag: String, message: String) {
        println("[$tag] $message")
    }
}
