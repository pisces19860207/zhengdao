// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 唯一渲染出口：把读数拼成稳定文本，字段名对齐 731.surface。
// 纯函数、固定顺序，同输入两次逐字相同；红时不出值。
package com.example.zhengdao.baopu.state.thermo

internal object ThermoRenderer {

    /** 唯一出口：三 surface 字段逐个列出，字段名逐字对齐 731，供其直接引用。 */
    fun render(value: ThermoReading): String = listOf(
        "体积=" + VolumeGrade.of(value.volume).label,
        "垂度=" + HangGrade.of(value.hang).label,
        "皱缩=" + RugosityGrade.of(value.rugosity).label,
        "贴合=" + ClingGrade.of(value.cling).label,
        "提睾=" + CremastericGrade.of(value.cremasteric).label,
        "surface[湿膜档=" + value.surface.wetFilm +
            "/色变档=" + value.surface.colorShift +
            "/血管可见档=" + value.surface.vesselVisible + "]",
    ).joinToString(" · ") + " · $THERMO_DISCLAIMER"
}
