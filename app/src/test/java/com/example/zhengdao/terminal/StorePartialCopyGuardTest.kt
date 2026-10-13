// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Qoder 审查 🟡-1 的**源码门禁**：`Store.adoptDir` 的复制失败分支必须清掉半截副本。
 *
 * 为什么用读源码的方式钉（而不是单测行为）：这条分支要求 `renameTo` 失败 **且**
 * `copyRecursively` 中途抛异常 —— 前者在单机上做不到（同盘 `renameTo` 总是成功，
 * 跨挂载点才是真实诱因），后者要靠权限/占用把复制打断（Windows 上无法确定性地造出来）。
 * `StoreTest` 已经把**正常**搬家路径（搬家、幂等、绝不覆盖同名）覆盖住了，这里只补一条
 * 形状门禁：谁把 `getOrElse` 里的清理删掉，谁就会看到它红。
 *
 * 背景（改前的样子）：`renameTo` 失败 → `copyRecursively(overwrite = false)` 中途抛异常
 * → `getOrDefault(false)` 接住、返回 false，但 **dst 里留着半截副本**；而 `dst.exists()`
 * 的分支会把它当成"已搬过"⇒ 永不重拷（脏残留 + 永不修复；真正的坏数据由 P3-2 的指纹校验
 * 在执行前拦住，所以是"脏"而不是"危险"）。
 */
class StorePartialCopyGuardTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（与 TmuxConfAppendTest 同一约定）。 */
    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun adoptDirBody(): String {
        val f = appFile("src/main/java/com/example/zhengdao/terminal/Store.kt")
        assertTrue("找不到 Store.kt：${f.path}", f.isFile)
        val src = f.readText()
        val start = src.indexOf("fun adoptDir(")
        assertTrue("源码里找不到 adoptDir（锚点变了就更新本测试）", start >= 0)
        val end = src.indexOf("fun adoptFile(", start)
        assertTrue("找不到 adoptDir 的边界（锚点变了就更新本测试）", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `复制失败必须清掉半截副本_否则下轮永不重拷`() {
        val body = adoptDirBody()
        val copy = body.indexOf("copyRecursively(dst, overwrite = false)")
        assertTrue("adoptDir 里的复制调用不见了：\n$body", copy >= 0)
        val elseMark = body.indexOf("getOrElse {", copy)
        assertTrue(
            "复制失败没有走 getOrElse 分支（旧的 getOrDefault(false) 会把半截副本留在目标里，" +
                "下一轮 dst.exists() 就当成「已搬过」⇒ 永不重拷。Qoder 🟡-1）：\n$body",
            elseMark >= 0,
        )
        val branch = body.substring(elseMark, body.indexOf("if (ok) moved++", elseMark))
        assertTrue("失败分支里没有清半截（缺 dst.deleteRecursively()）：\n$branch",
            branch.contains("dst.deleteRecursively()"))
        assertTrue("失败分支必须仍然返回 false（本轮没搬成功）：\n$branch", branch.contains("false"))
    }

    @Test
    fun `正常搬家路径不变_先改名后复制且绝不覆盖同名`() {
        val body = adoptDirBody()
        val rename = body.indexOf("renameTo(dst)")
        val copy = body.indexOf("copyRecursively(dst, overwrite = false)")
        assertTrue("先改名后复制这个顺序被人换了（改名是同盘快路径，必须排在前）：\n$body",
            rename >= 0 && copy > rename)
        assertTrue("目标已有同名条目时的跳过（绝不覆盖）不见了：\n$body",
            body.contains("dst.exists()"))
        assertTrue("复制成功后删源那句不见了：\n$body", body.contains("f.deleteRecursively()"))
    }
}
