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
 * 四条：
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

    @Test
    fun `打 release 包必须有发版签名断言_且留可绕过`() {
        val g = gradle
        assertTrue(
            "app/build.gradle.kts 里找不到发版签名断言（缺 ZHENGDAO_KEYSTORE_FILE 就该失败）",
            g.contains("ZHENGDAO_KEYSTORE_FILE")
        )
        assertTrue("断言必须真的抛错（GradleException），否则只是注释", g.contains("GradleException"))
        assertTrue(
            "断言必须只对带 Release 的任务生效（否则 debug / 单测会被误伤）",
            g.contains("taskNames.filter") && g.contains("contains(\"Release\")")
        )
        assertTrue(
            "必须留一个显式绕过 -PallowDebugSignedRelease（CI 的 R8 冒烟要用它）",
            g.contains("allowDebugSignedRelease")
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
