// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第七十七批 · 结构拆分第六阶段：Settings 存储/数据页纯逻辑抽取》§1
// （原存储设置页「存储占用 / 缓存清理」两卡里的内联判定）。
//
// 边界：只搬**判定与汇总**——量目录、真删目录、拼文案资源一律留在页面侧
// （那三件都要 Context / IO，进不来也不该进来）。本文件零 Android 依赖，
// 输入输出都是普通值与数据类，JVM 单测直接打。
package com.example.zhengdao.ui.settings

import com.example.zhengdao.terminal.StorageAudit

/**
 * 存储与缓存页的**纯判据层**（第七十七批 · §B 80 切片）。
 *
 * 页面里原本有三处「同一份明细、三种问法」的内联计算（可选工具体积、脚本缓存
 * 在不在、Agent 缓存合计），加一处分组名 → 白话展示名的映射。本对象把它们收成
 * 一处判据：页面只留调用，语义（过滤条件、求和口径、映射表）只此一份。
 *
 * ⚠️ 数据契约：`group` 的取值（"hermes 工具" / "公共区安装脚本"）由
 * [StorageAudit] 数据层定义，本文件按**原样字符串**匹配——这是跨层契约，
 * 不是界面文案，不做 i18n 抽取，改了会破坏分组匹配。
 */
internal object StorageRules {

    /** [StorageAudit] 明细里「hermes 工具」组的组名（数据契约，非界面文案）。 */
    private const val GROUP_HERMES_TOOLS = "hermes 工具"

    /** [StorageAudit] 明细里「公共区安装脚本」组的组名（数据契约，非界面文案）。 */
    private const val GROUP_INSTALL_SCRIPT = "公共区安装脚本"

    /**
     * 明细分组名 → 展示名（原 `SettingsStoragePage` 里 `when (item.group)` 四路映射）。
     *
     * 为什么值得抽：数据层分组名是给机器匹配的（"hermes 工具"），用户要看白话
     * （「装 hermes 工具时下载的文件」）——用户原话「不知道清理的是什么，专业人才
     * 看得懂」。映射表集中一处，新增分组只许改这里，页面渲染逻辑不动。
     *
     * else 分支**原样返回**：未登记的新分组不猜、不吞（猜错比不翻译更糟）。
     */
    fun groupLabel(group: String): String = when (group) {
        GROUP_HERMES_TOOLS -> "装 hermes 工具时下载的文件"
        "hermes 自检" -> "hermes 自检记录"
        GROUP_INSTALL_SCRIPT -> "安装时下载的脚本"
        else -> group
    }

    /**
     * 「hermes 工具」组里**可清项**的体积合计（原 `audit.filter { … }.sumOf { it.mb }`）。
     *
     * 判据 = `removable && group == "hermes 工具"`：核心运行时、ffmpeg（必需包）、
     * 用户点名保留的 hermes 自装扩展都在 [StorageAudit] 侧标了 `removable = false`，
     * 这里只按标记汇总，不自己认「什么能清」。（2026-10-11 深夜起可清表为空
     * ⇒ 本函数恒 0 ⇒ 页面入口不渲染；代码留着，放开某个工具时这里自动生效。）
     *
     * @return 可清体积（MB）；没有可清项时为 0
     */
    fun optionalToolsMb(items: List<StorageAudit.Item>): Long =
        items.filter { it.removable && it.group == GROUP_HERMES_TOOLS }.sumOf { it.mb }

    /**
     * 公共区**安装脚本缓存**在不在明细里（原 `audit.any { it.group == "公共区安装脚本" }`）。
     *
     * 有这一组才渲染「清脚本缓存」入口——那份缓存让重装免下载，可脚本本身有问题时
     * 用户看到的是永远同一个失败且毫无自救手段（公共区连卸载 App 都清不掉）。
     */
    fun hasScriptCache(items: List<StorageAudit.Item>): Boolean =
        items.any { it.group == GROUP_INSTALL_SCRIPT }

    /**
     * Agent 包缓存各目录体积合计（原 `agentSizes.sumOf { it.second }`）。
     *
     * 入口按合计 > 0 切两种按钮文案；明细逐行列在确认框里，这里只给总数。
     */
    fun agentCacheMb(sizes: List<Pair<String, Long>>): Long = sizes.sumOf { it.second }
}
