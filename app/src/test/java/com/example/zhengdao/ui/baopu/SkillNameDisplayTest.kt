// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户：「可以不用这个（**改稿**）这种吗？用 改稿 其他颜色或者加黑加粗显示呢？」
// 显示层去掉 ** 标记，字重交给 Compose；存盘文本一个字不动。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillNameDisplayTest {

    @Test
    fun strips_bold_markers_only() {
        assertEquals("改稿", withoutBoldMarkers("**改稿**"))
        assertEquals("改稿：read_file", withoutBoldMarkers("**改稿**：read_file"))
        assertEquals("看 图 与 照片", withoutBoldMarkers("**看** 图 与 **照片**"))
    }

    @Test
    fun plain_text_unchanged() {
        assertEquals("写小说", withoutBoldMarkers("写小说"))
        assertEquals("【技能】续写：…", withoutBoldMarkers("【技能】续写：…"))
    }

    @Test
    fun stray_double_markers_are_dropped_for_display() {
        // 显示层不做 markdown 语义，成对的 ** 直接去掉；半截的也一样（免得星星露在卡上）
        assertEquals("改稿", withoutBoldMarkers("**改稿"))
        assertEquals("改稿", withoutBoldMarkers("改稿**"))
    }
}
