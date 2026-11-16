// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P4-7（issue #17）：`LocalLifecycleOwner` 废弃 API 迁移的守卫。
 *
 * 审计原文：「`LocalLifecycleOwner` 废弃 3 处：`ui/HomeScreen.kt:228`、
 * `ui/SettingsScreen.kt:241`、`MainActivity.kt:300`」——开工实测已是 **8 处**
 * （设置页拆分又带了两个新文件进来，P2-5/P4-1 的后续效应）。
 *
 * 迁移路径（官方）：`androidx.compose.ui.platform.LocalLifecycleOwner`（Compose UI 1.7
 * 起废弃）→ `androidx.lifecycle.compose.LocalLifecycleOwner`（artifact
 * `lifecycle-runtime-compose`，本刀加入依赖，2.8.7，本地缓存已有 ⇒ 离线可构建）。
 */
class LocalLifecycleOwnerGuardTest {

    @Test
    fun `门禁_废弃 FQN 零残留`() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("androidx.compose.ui.platform.LocalLifecycleOwner") }
            .map { it.path }
            .toList()
        assertTrue(
            "还在用废弃的 compose.ui.platform.LocalLifecycleOwner：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `门禁_新 API 真的在用且依赖已声明`() {
        val root = File("src/main/java")
        val users = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("androidx.lifecycle.compose.LocalLifecycleOwner") }
            .toList()
        assertTrue("新 API 一个点都没用到（迁移没落地？）", users.isNotEmpty())

        // ⚠️ 单测的工作目录是模块目录（app/），不是仓库根
        val gradle = File("build.gradle.kts").readText()
        assertTrue(
            "依赖没声明（会解析失败）",
            gradle.contains("libs.androidx.lifecycle.runtime.compose"),
        )
        val toml = File("../gradle/libs.versions.toml").readText()
        assertTrue(
            "版本目录没登记",
            toml.contains("androidx-lifecycle-runtime-compose"),
        )
    }

    @Test
    fun `门禁_版本号没被碰（红线）`() {
        // 旧口径（钉死 2.1.1 / 36）在 v2.1.2 发版时变成假警报 —— 已改判为发版口径：
        // 版本名可以随发版前进，但 gradle ／ release note ／ README 中英必须四处一致。
        //
        // 683（2026-11-16）再改一层：versionCode 不再是一个手写的数字，而是**由构建派生**
        // （提交数 × 1000 ＋ 短号指纹，见 app/build.gradle.kts「versionCode 派生」段）。
        // 旧判据「README 里必须写出 gradle 里那个数」因此不成立 —— 那个数在每次提交上都不一样。
        // 现在的判据：①gradle 里**不许**再有写死的号（负例，写回去就是 673 第⑫项复发）；
        //   ②号必须取自派生值；③release note 仍在；④README 中英都跟上了版本名，
        //   且都不许再报一个写死的号（报了必然与产物对不上）。
        val gradle = File("build.gradle.kts").readText()
        val version = Regex("appVersionName\\s*=\\s*\"(\\d+\\.\\d+\\.\\d+)\"")
            .find(gradle)?.groupValues?.get(1) ?: error("找不到 appVersionName")
        val hardcoded = Regex("versionCode\\s*=\\s*\\d+").find(gradle)?.value
        assertTrue(
            "gradle 里又把 versionCode 写成了常量（$hardcoded）——两次提交会得到同一个号（683 的病根）",
            hardcoded == null,
        )
        assertTrue(
            "versionCode 必须取自派生值（versionCode = zdVersionCode…）",
            Regex("versionCode\\s*=\\s*zdVersionCode").containsMatchIn(gradle),
        )
        assertTrue(
            "release note `../.github/release-notes/v$version.md` 不在 —— 发版正文没跟上版本号",
            File("../.github/release-notes/v$version.md").isFile,
        )
        val cn = File("../README.md").readText()
        val en = File("../README.en.md").readText()
        assertTrue(
            "README.md 与 gradle 版本号不一致（应为 v$version）",
            cn.contains("当前版本：v$version"),
        )
        assertTrue(
            "README.en.md 与 gradle 版本号不一致（应为 v$version）",
            en.contains("Current release: v$version"),
        )
        val cnCode = Regex("versionCode\\s*\\d+").find(cn)?.value
        assertTrue("README.md 还报着写死的号（$cnCode）——它现在由构建派生", cnCode == null)
        val enCode = Regex("versionCode\\s*\\d+").find(en)?.value
        assertTrue("README.en.md 还报着写死的号（$enCode）——它现在由构建派生", enCode == null)
    }
}
