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
 *
 * ⚠️ **第㉕刀（2026-10-15）更新**：targetSdk 28 → 36 后 legacy 视图消失、WRITE 权限 inert，
 * 存储判定改为双通道（见 `targetSdk 已抬 36_且存储判定走双通道`）。原先那条
 * 「targetSdk 还是 28」的断言是本刀**故意改掉**的判据 —— 它自己的失败信息就预告了这次重验。
 * 冷启动补授权那条仍然有效（30+ 上有 SDK_INT < 30 守卫，请求代码只为 target 28 设备保留）。
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
    fun `targetSdk 已抬 36_且存储判定走双通道`() {
        // 第㉕刀（2026-10-15）：targetSdk 28 → 36（依据 docs/ERRATA.md E-134：执行链迁
        // jniLibs 后，W^X 数据目录限制不再作用到 guest 二进制，真机四判据全过）。
        // 旧断言「targetSdk 还是 28」正是本刀要改掉的判据 —— 它自己的失败信息就写着
        // 「不再是 28 时必须连同 #1 的冒烟用例一起重新验证」，那次验证 = 第㉕刀真机回归
        // （共享存储在「所有文件访问」授权下的冒烟，见交付报告）。
        val gradle = src("build.gradle.kts").readText()
        assertTrue(
            "targetSdk 不是 36 —— 第㉕刀被回退了？",
            Regex("targetSdk\\s*=\\s*36\\b").containsMatchIn(gradle),
        )
        // requestLegacyExternalStorage 在 36 上已 inert（框架只对 target≤29 生效），
        // 但**刻意保留**：删它零收益，留着可防谁把 targetSdk 调回 <30 时视图悄悄变。
        assertTrue(
            "application 少了 android:requestLegacyExternalStorage=\"true\"（36 上 inert，但刻意保留）",
            Regex("requestLegacyExternalStorage\\s*=\\s*\"true\"").containsMatchIn(manifestXml()),
        )
        // 存储判定必须双通道：36 走「所有文件访问」、28 走 WRITE 权限。少前者 ⇒ 36 上共享
        // 存储全灭；少后者 ⇒ target 28 设备的 legacy 视图通道被删。
        val launcher = src("src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt").readText()
        assertTrue(
            "storageGranted 不查 isExternalStorageManager —— 36 上 WRITE 权限已 inert",
            launcher.contains("isExternalStorageManager"),
        )
        assertTrue(
            "storageGranted 不认 WRITE_EXTERNAL_STORAGE —— target 28 设备的 legacy 通道被删",
            launcher.contains("WRITE_EXTERNAL_STORAGE"),
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
