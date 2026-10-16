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
        // 版本号可以随发版前进，但 gradle ／ release note ／ README 中英必须四处一致
        // （同判据详见 `TargetSdkGateTest > 版本号红线未动`）。
        val gradle = File("build.gradle.kts").readText()
        val version = Regex("appVersionName\\s*=\\s*\"(\\d+\\.\\d+\\.\\d+)\"")
            .find(gradle)?.groupValues?.get(1) ?: error("找不到 appVersionName")
        val code = Regex("versionCode\\s*=\\s*(\\d+)").find(gradle)?.groupValues?.get(1)
            ?: error("找不到 versionCode")
        assertTrue(
            "release note `../.github/release-notes/v$version.md` 不在 —— 发版正文没跟上版本号",
            File("../.github/release-notes/v$version.md").isFile,
        )
        val cn = File("../README.md").readText()
        assertTrue(
            "README.md 与 gradle 版本号不一致（应为 v$version / versionCode $code）",
            cn.contains("当前版本：v$version") && cn.contains("versionCode $code"),
        )
    }
}
