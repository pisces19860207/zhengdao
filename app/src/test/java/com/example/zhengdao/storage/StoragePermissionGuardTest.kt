// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.storage

import com.example.zhengdao.SrcRoots

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
 *
 * ⚠️ **第二百一十批（2026-10-25）再更新**：首启权限机制整体退役（用户口径：欢迎页不碰权限、
 * 申请入口唯一化到设置页「申请权限」卡）⇒ 冷启动不再发起**任何**权限请求。本类随之改写：
 * 「通知请求必须真的发起」翻成「一个 requestPermissions 都不许有」；清单声明的判据不动
 * （少声明 POST_NOTIFICATIONS，安卓 13+ 照样丢通知）。
 */
class StoragePermissionGuardTest {

    /** 从模块目录或仓库根出发找文件（单测的 CWD 在不同 Gradle 版本/IDE 下不一致）。 */
    private fun src(relative: String): File {
        val candidates = SrcRoots.candidates(relative)
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
        // 第二百四十批：判定实现搬到 :core/EnvironmentScope（ProotLauncher 只剩委托）。
        // 判据一字未改，只是被扫的文件换了 —— 且仍要求「全仓唯一实现」：
        // ProotLauncher.kt 里不许再出现第二份 isExternalStorageManager。
        val scope = src("../core/src/main/java/com/example/zhengdao/core/EnvironmentScope.kt").readText()
        assertTrue(
            "storageGranted 不查 isExternalStorageManager —— 36 上 WRITE 权限已 inert",
            scope.contains("isExternalStorageManager"),
        )
        assertTrue(
            "storageGranted 不认 WRITE_EXTERNAL_STORAGE —— target 28 设备的 legacy 通道被删",
            scope.contains("WRITE_EXTERNAL_STORAGE"),
        )
        // 只判**代码**：KDoc 里写着「这里不许再写第二份 isExternalStorageManager」是讲解，
        // 不是第二份判定（这条注释本身就是为了让下一个人别抄）。
        val launcherCode = src("src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt")
            .readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\n]*"), "")
        assertFalse(
            "ProotLauncher 里又出现了第二份 isExternalStorageManager（E2 消双判被回退）",
            launcherCode.contains("isExternalStorageManager"),
        )
    }

    @Test
    fun `冷启动不再请求 READ WRITE 运行时授权`() {
        // 134 批（lint ObsoleteSdkInt）：minSdk=36 ⇒ READ/WRITE_EXTERNAL_STORAGE 在 API 30+ 已 inert，
        // 原 `SDK_INT < 30` 的冷启动补授权分支是死代码，已整段删除。
        // 判据反过来钉：这两个权限常量不许再出现在 MainActivity 的请求代码里（写回去 = 回退死分支）。
        // 只看**剥掉注释**的代码：说明「死分支已删」的注释里会写这两个权限名。
        val activity = src("src/main/java/com/example/zhengdao/MainActivity.kt").readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\n]*"), "")
        assertFalse("冷启动又请求 READ_EXTERNAL_STORAGE 了（36 上是死分支）", activity.contains("READ_EXTERNAL_STORAGE"))
        assertFalse("冷启动又请求 WRITE_EXTERNAL_STORAGE 了（36 上是死分支）", activity.contains("WRITE_EXTERNAL_STORAGE"))
        // 第二百一十批（2026-10-25 · 入口与权限归位）：首启不再请求**任何**权限——
        // 用户口径「欢迎页不用放权限申请」＋申请入口唯一化（全 App 只许设置页「申请权限」
        // 卡那一处发起，源码门禁 PermissionEntryGateTest）。所以命令式请求一个都不许有。
        assertFalse(
            "冷启动又出现 requestPermissions 了（首启零权限请求，要授权走「申请权限」卡）",
            activity.contains("requestPermissions"),
        )
    }

    // ── #3 验收时发现的第二顶「帽子」（2026-10-09） ──
    //
    // 「装完自动清理 >500MB 的静默提示」在真机上从来没出现过：清单里**没有声明**
    // POST_NOTIFICATIONS、冷启动也不请求 ⇒ 安卓 13+ 直接丢掉通知，而代码这边看起来一切正常
    // （真机 `dumpsys package` = `granted=false`、`AppSettings importance=NONE`、通知列表为空）。
    // 与 READ 的 maxSdkVersion 帽子是同一类错误：不是逻辑写错，是**清单/授权**这一层缺一块。
    //
    // ⚠️ 第二百一十批（2026-10-25）口径变更：冷启动**不再**代用户请求 POST_NOTIFICATIONS
    // （首启零权限请求；用户主动去「设置 → 申请权限」卡里给）。**清单声明不动**——
    // 少声明安卓 13+ 一样丢通知，这条判据是「别把声明删了」，不是「冷启动要弹框」。

    @Test
    fun `通知权限仍在清单里_冷启动不代请求`() {
        assertTrue(
            "清单里缺 POST_NOTIFICATIONS —— 安卓 13+ 上所有通知（含自动清理的静默提示）都会被丢掉",
            usesPermission(manifestXml(), "POST_NOTIFICATIONS") != null,
        )
        // 剥掉注释再判：说明「这里原来有什么」的注释里必然出现这些反例词
        // （ColdStartDialogGuardTest 类 KDoc 记着这个坑）。
        val activity = src("src/main/java/com/example/zhengdao/MainActivity.kt").readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\n]*"), "")
        assertFalse(
            "冷启动又不请求 POST_NOTIFICATIONS 了——首启零权限请求（第二百一十批用户口径），" +
                "要授权走设置页「申请权限」卡；此断言红 = 有人把冷启动请求写回来了",
            activity.contains("POST_NOTIFICATIONS"),
        )
        // 申请点在卡片文件里（PermissionEntryGateTest 钉「只此一处」），这里只确认入口唯一化后
        // 老的那套一次性闸门（shouldAutoRequest / notifyAsked / markNotifyAsked）真的退役了。
        assertFalse(
            "首启自动请求闸门应随首启权限页一起退役（shouldAutoRequest 一族无调用方）",
            activity.contains("shouldAutoRequest") || activity.contains("markNotifyAsked"),
        )
    }
}
