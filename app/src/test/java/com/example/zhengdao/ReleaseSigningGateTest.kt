package com.example.zhengdao

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发版前的「不许静默退化」门禁（源码级断言，不跑 Gradle）。
 *
 * 为什么用文本断言而不是行为单测：这几条约束的载体是**构建脚本与流水线配置**
 * （`app/build.gradle.kts` / `.github/workflows/ci.yml`），它们不在 JVM 单测的运行路径上；
 * 而它们一旦被顺手删掉，**没有任何测试会变红**——正是本项目已经踩过的那类坑
 * （审计 2026-10-12：「测试通过 ≠ 这件事有人管」，见 docs/ERRATA.md E-121 的教训段）。
 *
 * 四条（2026-10-14 审计第 2 期 R-24：判据已从"grep 源码文本"升级为"对**去注释后的代码**做
 * 结构化匹配" —— 旧的 `contains(...)` 会被「把这行注释掉」骗过，注释里同样有那些字）：
 * 1. 打 release 包必须有发版签名断言（缺 `ZHENGDAO_KEYSTORE_FILE` 直接失败），且留了显式绕过。
 * 2. CI 的 R8 冒烟必须**显式**传那个绕过 —— 否则 PR 上会因为没有钥匙而红。
 * 3. CI 必须跑许可证正文门禁 `tools/check-licenses.py`（E-105 的教训）。
 * 4. 版本号三处一致（构建脚本 / README / README.en），且每个版本都有 release note 正文。
 */
class ReleaseSigningGateTest {

    /** app 模块内的路径：CWD 可能是仓库根，也可能是模块目录（Gradle 单测是后者）。 */
    private fun appFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 仓库根下的路径。 */
    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("../$rel") }

    private fun readApp(rel: String): String = appFile(rel).readText()
    private fun readRepo(rel: String): String = repoFile(rel).readText()

    private val gradle: String get() = readApp("build.gradle.kts")
    private val ci: String get() = readRepo(".github/workflows/ci.yml")

    /**
     * `app/build.gradle.kts` **去掉注释后**的内容（R-24）。
     *
     * 旧断言直接在原文上 `contains(...)`，于是「把这行注释掉」照样绿 —— 注释里也写着那些字。
     * 真判据要的是"代码里真的这么写"。字符串字面量保留（`contains("Release")` 这类判据
     * 本身就是查字符串），只剥 `//` 与 `/* */`（含 Kotlin 原始字符串 `"""` 的处理）。
     */
    private val gradleCode: String get() = stripComments(gradle)

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && src.startsWith("\"\"\"", i) -> { // 原始字符串 """..."""
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append(src, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> { // 普通字符串 / 字符字面量（保留，不当作注释）
                    val quote = c
                    out.append(c)
                    i++
                    while (i < src.length) {
                        val d = src[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < src.length) { out.append(src[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun `打 release 包必须有发版签名断言_且留可绕过`() {
        val g = gradleCode // R-24：只看**代码**，注释里提一句不算数

        assertTrue(
            "app/build.gradle.kts 里必须真的读 ZHENGDAO_KEYSTORE_FILE（要 System.getenv(...)），只有注释不算",
            Regex("""System\s*\.\s*getenv\s*\(\s*"ZHENGDAO_KEYSTORE_FILE"\s*\)""").containsMatchIn(g),
        )
        assertTrue(
            "断言必须真的**抛出** GradleException（要 `throw GradleException(`，不是注释里提一句）",
            Regex("""throw\s+GradleException\s*\(""").containsMatchIn(g),
        )
        assertTrue(
            "断言必须只对带 Release 的任务生效（要真的从 gradle.startParameter.taskNames 过滤）",
            Regex("""gradle\s*\.\s*startParameter\s*\.\s*taskNames\s*\.\s*filter""").containsMatchIn(g) &&
                Regex("""contains\s*\(\s*"Release"\s*\)""").containsMatchIn(g),
        )
        assertTrue(
            "必须留一个显式绕过 -PallowDebugSignedRelease（要真的 hasProperty 检查；CI 的 R8 冒烟要用它）",
            Regex("""hasProperty\s*\(\s*"allowDebugSignedRelease"\s*\)""").containsMatchIn(g),
        )
    }

    @Test
    fun `CI 的 R8 冒烟必须显式放行签名断言`() {
        val line = ci.lines().firstOrNull { it.trimStart().startsWith("run:") && it.contains(":app:assembleRelease") }
        assertTrue("ci.yml 里找不到 R8 冒烟的 assembleRelease 步骤", line != null)
        assertTrue(
            "R8 冒烟（产物不发布）必须显式传 -PallowDebugSignedRelease，否则 PR 上会因为缺发版钥匙而红",
            line!!.contains("-PallowDebugSignedRelease")
        )
    }

    @Test
    fun `CI 必须跑许可证正文门禁`() {
        assertTrue("ci.yml 没有调用 tools/check-licenses.py（E-105 的门禁）", ci.contains("tools/check-licenses.py"))
        assertTrue("tools/check-licenses.py 不存在", repoFile("tools/check-licenses.py").exists())
    }

    @Test
    fun `版本号三处一致_且该版本有 release note`() {
        val g = gradle
        val name = Regex("val appVersionName = \"([0-9.]+)\"").find(g)?.groupValues?.get(1)
        val code = Regex("versionCode = (\\d+)").find(g)?.groupValues?.get(1)
        assertTrue("app/build.gradle.kts 里读不到 appVersionName", name != null)
        assertTrue("app/build.gradle.kts 里读不到 versionCode", code != null)

        val zh = readRepo("README.md")
        val en = readRepo("README.en.md")
        assertTrue("README.md 的「当前版本」没跟上：应写 v$name", zh.contains("当前版本：v$name"))
        assertTrue("README.md 的 versionCode 没跟上：应写 $code", zh.contains("versionCode $code"))
        assertTrue("README.en.md 的 Current release 没跟上：应写 v$name", en.contains("Current release: v$name"))
        assertTrue("README.en.md 的 versionCode 没跟上：应写 $code", en.contains("versionCode $code"))

        val notes = repoFile(".github/release-notes/v$name.md")
        assertTrue("缺 .github/release-notes/v$name.md —— tag 触发的 Release 会没有正文", notes.exists())
        assertTrue("release note 正文太短（像是占位）", notes.readText().length > 400)
    }
}
