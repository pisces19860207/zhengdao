// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 六体登记标记的**类型**（626 批一登记闸的落码锚点）。
//
// 626 定稿：批二建任何一件器官实现时，类上必须带登记标记，件名与
// `docs/bio-analog-registry.md` 第一列逐字相等；`tools/check-bio-analog.py`
// 按源码文本找标记（先剥注释），未登记即红。626 只立了闸没定义标记类型，
// 本批要落码，标记得能编译——本文件就是让标记"存在于类型系统"的最小件。
// 它不是器官（六件闭集不受影响），自己不带标记。
package com.example.zhengdao.baopu.bio

/** 六体仿生登记标记：件名必须与 docs/bio-analog-registry.md 第一列逐字相等。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class BioAnalog(val kind: String)
