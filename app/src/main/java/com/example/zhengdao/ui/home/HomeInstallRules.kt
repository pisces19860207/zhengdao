// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.home

/**
 * 第七十三批 · HomeScreen 结构切片 ④ —— 卸载命令里**程序路径的解析**（纯逻辑）。
 *
 * `ui/HomeScreen.kt` 的 `programSizeMb()` 做两件事：①从 manifest 的 uninstall 命令里解析出
 * 要删的目录、映射到宿主路径；②真的去 `du`。本文件只接手 ①（输入 String、输出 List<String>，
 * 不碰 `Context`、不碰磁盘），②留在原函数——测体积这件事没法在 JVM 上假装。
 *
 * 返回的是**相对 `filesDir` 的宿主路径**（`home/…`、`rootfs/usr/…`），调用方据此拼上宿主根
 * 目录再逐个 `du` 求和；顺序与去重口径与改前逐字一致。
 */

/**
 * 解析 uninstall 命令中 `rm -rf` 的目标路径，映射为宿主侧相对目录。
 *
 * 口径（照搬改前，一条没变）：
 * - 正则 `rm\s+-rf\s+([^&\n]+)`：只取 `rm -rf` 之后、到 `&` 或换行之前的那串；
 * - 按空白切分后，只留**以 `/` 开头**（guest 内绝对路径）且**不含通配符 `*`** 的项——
 *   带 `*` 的没法 du，相对路径（`~/…`）映射不到宿主；
 * - `distinct()` 保序去重（同一目录在一条命令里出现两次只算一次体积）；
 * - 映射只认两类：`/root`、`/root/…` → `home/…`；`/usr`、`/usr/…` → `rootfs/usr…`；
 *   其它前缀（`/data/…`、`/opt/…`）不猜测，直接丢掉。
 *
 * 解析不出任何已知路径时返回**空表**，由调用方换算成 -1、弹窗改用"以实际占用为准"的措辞
 * ——不编造体积数字。
 */
internal fun uninstallProgramPaths(uninstallCmd: String): List<String> =
    Regex("rm\\s+-rf\\s+([^&\\n]+)").findAll(uninstallCmd)
        .flatMap { it.groupValues[1].trim().split(Regex("\\s+")) }
        .filter { it.startsWith("/") && !it.contains('*') }
        .distinct()
        .mapNotNull { p ->
            when {
                p == "/root" || p.startsWith("/root/") -> "home/" + p.removePrefix("/root/")
                p == "/usr" || p.startsWith("/usr/") -> "rootfs" + p
                else -> null
            }
        }
        .toList()
