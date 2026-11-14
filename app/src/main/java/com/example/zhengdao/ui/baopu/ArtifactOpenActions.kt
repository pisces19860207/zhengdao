// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十一批（打开产物）· 执行层：把 ArtifactOpen 的规格落地成真 Intent。
// 这里不写业务逻辑、不碰文案（文案键由产物架查），只回答「这一击是什么下场」。
//
// 三条边界：
// 1. 目标必须是工作区内的普通文件（PathGuard.isInside ＋ isFile），
//    产物台账行来自库，绝不拿外部传入的绝对路径去生成 Uri；
// 2. 只授予单个 Uri 的临时读权限，不给写、不给目录遍历；
// 3. resolveActivity 为空 ⇒ 明确回 NO_VIEWER；startActivity 抛
//    ActivityNotFoundException 也回 NO_VIEWER，两条路都不许静默。
package com.example.zhengdao.ui.baopu

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.example.zhengdao.core.PathGuard
import java.io.File

internal enum class ArtifactOpenOutcome { OPENED, NO_VIEWER, GONE, UNSUPPORTED, FAILED }

internal enum class ArtifactLocateOutcome { OPENED, SHOW_PATH, GONE }

/** 点「用其他应用打开」：现查盘上事实，再按规格发 ACTION_VIEW。 */
internal fun openArtifactExternally(ctx: Context, root: File, relPath: String): ArtifactOpenOutcome {
    val file = File(root, relPath)
    if (!PathGuard.isInside(root, file)) return ArtifactOpenOutcome.FAILED
    return when (val plan = ArtifactOpen.planOf(file.name, file.isFile)) {
        ArtifactOpenPlan.Gone -> ArtifactOpenOutcome.GONE
        ArtifactOpenPlan.Unsupported -> ArtifactOpenOutcome.UNSUPPORTED
        is ArtifactOpenPlan.Ready -> launchView(ctx, file, plan.spec)
    }
}

/** 把一份规格变成 Intent；返回发没发出去，以及为什么没发出去。 */
private fun launchView(ctx: Context, file: File, spec: ArtifactOpenIntent): ArtifactOpenOutcome {
    val uri = runCatching {
        FileProvider.getUriForFile(ctx, ctx.packageName + spec.authoritySuffix, file)
    }.getOrElse { return ArtifactOpenOutcome.FAILED }
    val intent = Intent(spec.action).apply {
        setDataAndType(uri, spec.mime)
        if (spec.withClipData) clipData = ClipData.newRawUri("", uri)
        if (spec.grantRead) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val resolved = runCatching {
        ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrNull()
    if (resolved == null) return ArtifactOpenOutcome.NO_VIEWER
    return runCatching {
        ctx.startActivity(intent)
        ArtifactOpenOutcome.OPENED
    }.getOrElse { e ->
        if (e is ActivityNotFoundException) ArtifactOpenOutcome.NO_VIEWER else ArtifactOpenOutcome.FAILED
    }
}

/**
 * 点「在文件管理器中定位」：先试着把父目录交给能处理目录 Uri 的应用，
 * 没有应用接（或这一击失败）就退化 SHOW_PATH，由界面显示绝对路径＋复制。
 */
internal fun locateArtifact(ctx: Context, root: File, relPath: String): ArtifactLocateOutcome {
    val file = File(root, relPath)
    if (!PathGuard.isInside(root, file) || !file.isFile) return ArtifactLocateOutcome.GONE
    val parent = file.parentFile ?: return ArtifactLocateOutcome.SHOW_PATH
    val uri = runCatching {
        FileProvider.getUriForFile(ctx, ctx.packageName + ArtifactOpen.AUTHORITY_SUFFIX, parent)
    }.getOrNull()
    val intent = uri?.let { folderUri ->
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, ArtifactOpen.FOLDER_MIME)
            clipData = ClipData.newRawUri("", folderUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    val hasViewer = intent != null && runCatching {
        ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrNull() != null
    return when (ArtifactOpen.locatePlanOf(onDisk = true, folderViewer = hasViewer)) {
        ArtifactLocatePlan.Gone -> ArtifactLocateOutcome.GONE
        ArtifactLocatePlan.ShowPath -> ArtifactLocateOutcome.SHOW_PATH
        ArtifactLocatePlan.OpenFolder -> if (intent == null) {
            ArtifactLocateOutcome.SHOW_PATH
        } else {
            runCatching {
                ctx.startActivity(intent)
                ArtifactLocateOutcome.OPENED
            }.getOrElse { ArtifactLocateOutcome.SHOW_PATH }
        }
    }
}
