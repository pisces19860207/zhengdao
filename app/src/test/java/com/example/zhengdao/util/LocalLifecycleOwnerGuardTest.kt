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
        val gradle = File("build.gradle.kts").readText()
        // versionName 走 val appVersionName = "2.1.1"（:217），versionCode = 36（:274）
        assertTrue("versionName 必须仍是 2.1.1", gradle.contains("val appVersionName = \"2.1.1\""))
        assertTrue("versionCode 必须仍是 36", gradle.contains("versionCode = 36"))
    }
}
