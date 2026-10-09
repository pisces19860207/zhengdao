// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 存储读取的**静态防复发守卫**（#1，2026-10-09）。
 *
 * 背景：第 0 步那次事故（E-004／E-005 一系）根本不是代码写错，而是**清单里一个属性**
 * ——`READ_EXTERNAL_STORAGE` 带着 `maxSdkVersion="32"` 帽子，Android 13+ 上 READ 权限
 * 直接为空，于是"能写（私有目录）不能读"，hermes 找不到用户文件。修法是把帽子摘掉
 * （见 `app/src/main/AndroidManifest.xml:13-21` 的注释）。
 *
 * 真机上的"读用户真实文件 → 回写"冒烟用例在 `app/src/androidTest/.../StorageReadWriteSmokeTest.kt`，
 * 但它需要一台 arm64 真机（本包只打 `arm64-v8a`，见 `app/build.gradle.kts` 的 abiFilters —— 用户定案），
 * CI 上没有这样的模拟器，`connectedAndroidTest` 进不了流水线。
 *
 * 所以"接进 CI"落在这一份 JVM 单测上：它守的是**那次事故的真正成因**（清单属性 + legacy 视图 +
 * 冷启动补授权），跟着 `:app:testDebugUnitTest` 每次提交都跑。真机用例守住"真的能读写"，
 * 静态守卫守住"配置别再被改回去"，两者缺一不可。
 */
class StoragePermissionGuardTest {

    /** 从模块目录或仓库根出发找文件（单测的 CWD 在不同 Gradle 版本/IDE 下不一致）。 */
    private fun src(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        val found = candidates.firstOrNull { it.isFile }
        if (found == null) {
            throw AssertionError("找不到 $relative（试过：${candidates.joinToString { it.path }}）")
        }
        return found
    }

    /** 去掉 XML 注释，免得注释里提到的属性被当成真的声明。 */
    private fun manifestXml(): String =
        src("src/main/AndroidManifest.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun usesPermission(xml: String, name: String): String? =
        Regex("<uses-permission[^>]*android:name=\"[^\"]*$name\"[^>]*/?>")
            .find(xml)?.value

    @Test
    fun `读权限不许再带 maxSdkVersion 帽子`() {
        val element = usesPermission(manifestXml(), "READ_EXTERNAL_STORAGE")
            ?: throw AssertionError("清单里没有 READ_EXTERNAL_STORAGE —— 第 0 步的修复被删了？")
        assertFalse(
            "READ_EXTERNAL_STORAGE 又带上 maxSdkVersion 了：Android 13+ 上 READ 权限会变空，" +
                "共享存储「能写不能读」的根因就是它（见 AndroidManifest 注释与 ERRATA）",
            element.contains("maxSdkVersion"),
        )
    }

    @Test
    fun `写权限与所有文件访问仍在清单里`() {
        val xml = manifestXml()
        assertTrue("缺 WRITE_EXTERNAL_STORAGE", usesPermission(xml, "WRITE_EXTERNAL_STORAGE") != null)
        assertTrue("缺 MANAGE_EXTERNAL_STORAGE", usesPermission(xml, "MANAGE_EXTERNAL_STORAGE") != null)
    }

    @Test
    fun `应用仍走 legacy 存储视图且 targetSdk 还是 28`() {
        // 这两个是一对：target 28 + requestLegacyExternalStorage 才让 MANAGE 之外的基础
        // /sdcard 读写保持老视图。改动它们属于"上架前才谈"的决定（docs/知识库-收工条件.md C1），
        // 不该在维护里悄悄发生。
        assertTrue(
            "application 少了 android:requestLegacyExternalStorage=\"true\"",
            Regex("requestLegacyExternalStorage\\s*=\\s*\"true\"").containsMatchIn(manifestXml()),
        )
        val gradle = src("build.gradle.kts").readText()
        assertTrue(
            "targetSdk 不再是 28 —— 共享存储视图会变，必须连同 #1 的冒烟用例一起重新验证",
            Regex("targetSdk\\s*=\\s*28\\b").containsMatchIn(gradle),
        )
    }

    @Test
    fun `冷启动会补 READ WRITE 运行时授权`() {
        val activity = src("src/main/java/com/example/zhengdao/MainActivity.kt").readText()
        assertTrue("冷启动没再请求 READ_EXTERNAL_STORAGE", activity.contains("READ_EXTERNAL_STORAGE"))
        assertTrue("冷启动没再请求 WRITE_EXTERNAL_STORAGE", activity.contains("WRITE_EXTERNAL_STORAGE"))
        assertTrue("没有真正发起请求（requestPermissions）", activity.contains("requestPermissions"))
    }

    // ── #3 验收时发现的第二顶「帽子」（2026-10-09） ──
    //
    // 「装完自动清理 >500MB 的静默提示」在真机上从来没出现过：清单里**没有声明**
    // POST_NOTIFICATIONS、冷启动也不请求 ⇒ 安卓 13+ 直接丢掉通知，而代码这边看起来一切正常
    // （真机 `dumpsys package` = `granted=false`、`AppSettings importance=NONE`、通知列表为空）。
    // 与 READ 的 maxSdkVersion 帽子是同一类错误：不是逻辑写错，是**清单/授权**这一层缺一块。

    @Test
    fun `通知权限声明了，并且冷启动会请求`() {
        assertTrue(
            "清单里缺 POST_NOTIFICATIONS —— 安卓 13+ 上所有通知（含自动清理的静默提示）都会被丢掉",
            usesPermission(manifestXml(), "POST_NOTIFICATIONS") != null,
        )
        val activity = src("src/main/java/com/example/zhengdao/MainActivity.kt").readText()
        assertTrue(
            "冷启动没有再请求 POST_NOTIFICATIONS（安卓 13+ 必须运行时请求）",
            activity.contains("POST_NOTIFICATIONS"),
        )
        assertTrue(
            "请求 POST_NOTIFICATIONS 要按 SDK_INT >= 33 判版本，低版本上没有这个权限常量对应的运行时权限",
            Regex("SDK_INT\\s*>=\\s*33").containsMatchIn(activity),
        )
    }
}
