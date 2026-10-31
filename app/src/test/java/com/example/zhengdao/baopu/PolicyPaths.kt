// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十三批 · 宰相搬进 `:policy` 之后，**扫源码的门禁怎么找到它**。
//
// 背景：253/271/292 那一串边界门禁（`GateIo`／`LawIo`／各家 `src()`）都是按
// `src/main/java/...` 这个**相对 :app 模块目录**的路径去读文件的。本批把宰相
// （`HarmonyMediator`／`MediatorJudge`／`ReframeFilter`／`PatienceProfile`／`MediatorWiring`）
// 搬进独立编译单元 `:policy`，那些路径就指到了空处——而"扫不到文件"的判据是**空集**，
// 空集永远零命中，于是门禁会从"红"悄悄变成"恒绿"（`LawIo.assertNotBlind` 写的就是这条坑）。
//
// 所以这里只补一件事：把 `:policy` 的文件取出来，**取不到就抛**。
// 不许兜底回 `:app`：那等于"模块没搬也算过"，正是要防的假绿。
//
// cwd 两档都要能跑：Gradle 单测的工作目录是模块目录（`app/`），IDE 直跑时可能是仓根，
// 与 `GateIo.repoFile` 同一口径，只是方向反过来（先 `../policy`，再 `policy`）。
package com.example.zhengdao.baopu

import java.io.File

internal object PolicyPaths {

    /** 宰相源码在 `:policy` 里的目录（相对仓根）。 */
    const val SRC_REL = "policy/src/main/java/com/example/zhengdao/baopu"

    /** `:policy` 构建脚本（相对仓根；依赖白名单闸扫它）。 */
    const val BUILD_REL = "policy/build.gradle.kts"

    private fun cand(rel: String): File =
        if (File(rel).exists()) File(rel) else File("../$rel")

    /** 宰相源码目录；不在就抛（门禁不许静默跳过）。 */
    fun srcDir(): File = cand(SRC_REL).also {
        check(it.isDirectory) {
            "找不到 :policy 源码目录（试了 $SRC_REL 与 ../$SRC_REL，cwd=${File(".").absolutePath}）" +
                "——宰相要是没搬进来，就别让扫它的门禁空转"
        }
    }

    fun file(name: String): File = File(srcDir(), name).also {
        check(it.isFile) { ":policy 里没有源文件 $name（${it.absolutePath}）" }
    }

    /** 读 `:policy` 里某个源文件的原文。 */
    fun read(name: String): String = file(name).readText(Charsets.UTF_8)

    /** `:policy` 主源码的全部 .kt（稳定排序，供扫树）。 */
    fun sources(): List<File> = srcDir().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.name }
        .toList()

    /** `:policy/build.gradle.kts` 原文（依赖白名单用它，读不到就抛）。 */
    fun buildScript(): String = cand(BUILD_REL).also {
        check(it.isFile) { "找不到 :policy 的构建脚本（$BUILD_REL，cwd=${File(".").absolutePath}）" }
    }.readText(Charsets.UTF_8)

    /** 断言消息里的路径（让红字能指到真文件）。 */
    fun rel(name: String): String = "$SRC_REL/$name"
}
