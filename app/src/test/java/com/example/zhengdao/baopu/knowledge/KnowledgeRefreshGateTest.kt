// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 判据：派工单-2026-10-24-第一百八十二批 §0.5 的五条负例（全量重扫 / 赌 FileObserver /
// 原件被写 / 索引不可重建 / 后台无脑扫）——源码级钉死，防止以后“顺手”改回去。
package com.example.zhengdao.baopu.knowledge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRefreshGateTest {

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel")).firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel")

    private fun src(rel: String): String =
        repoFile(rel).readText(Charsets.UTF_8)
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `负例_不赌FileObserver_刷新走MediaStore增量与周期兜底`() {
        val refresh = src("src/main/java/com/example/zhengdao/baopu/KnowledgeRefresh.kt")
        assertFalse("刷新正确性不许押在 FileObserver 上", refresh.contains("FileObserver"))
        assertTrue("观察者走唯一 MediaStore 出口（可选加速）", refresh.contains("MediaStoreSink.registerFilesObserver"))
        assertFalse("别处不许直接碰 MediaStore（MediaPathGateTest G3）", refresh.contains("MediaStore."))
        assertTrue("必须有周期兜底", refresh.contains("PERIODIC"))
        assertTrue("必须有前台增量", refresh.contains("Trigger.FOREGROUND"))
        val sink = src("src/main/java/com/example/zhengdao/baopu/MediaStoreSink.kt")
        assertTrue("观察者的 MediaStore 实现在唯一出口里", sink.contains("MediaStore.Files"))
        assertTrue(sink.contains("ContentObserver"))
    }

    @Test
    fun `负例_后台无脑扫_只有启动与手动可越过前台闸`() {
        val rules = src("src/main/java/com/example/zhengdao/baopu/knowledge/KnowledgeRefreshRules.kt")
        assertTrue("门控必须看前台", rules.contains("foreground"))
        assertTrue("启动一次性放行", rules.contains("Trigger.START -> Gate(true"))
        assertTrue("周期必须要求前台", rules.contains("!foreground -> Gate(false"))
    }

    @Test
    fun `负例_全量重扫_增量必须带上一版索引`() {
        val store = src("src/main/java/com/example/zhengdao/baopu/knowledge/KnowledgeIndexStore.kt")
        assertTrue("主根必须用 previous 增量", store.contains("previous = mainPrev"))
        assertTrue("外部根也必须用 previous", store.contains("previous = prev"))
        assertTrue("快照 diff 负责删除", store.contains("KnowledgeSnapshot.diff"))
    }

    @Test
    fun `负例_原件被写_迁移与整理都不许 rename或删源`() {
        val layout = src("src/main/java/com/example/zhengdao/baopu/knowledge/KnowledgeLayout.kt")
        assertFalse("迁移只复制，不许 rename 旧原件", layout.contains("renameTo"))
        val organize = src("src/main/java/com/example/zhengdao/baopu/knowledge/KnowledgeOrganize.kt")
        assertFalse("整理只写副本，不许 rename 原件", organize.contains("renameTo"))
        assertTrue("整理前必须再核源 sha", organize.contains("sha != item.sha256"))
        assertTrue("目标越界整批拒绝", organize.contains("targetsSafe"))
    }

    @Test
    fun `负例_索引不可重建_索引必须落在可删的索引目录`() {
        val layout = src("src/main/java/com/example/zhengdao/baopu/knowledge/KnowledgeLayout.kt")
        assertTrue(layout.contains("SUB_INDEX = \"索引\""))
        assertTrue(layout.contains("fun indexFile("))
        val refresh = src("src/main/java/com/example/zhengdao/baopu/KnowledgeRefresh.kt")
        assertTrue("读不到索引 ⇒ 走全量重建（previous=null）", refresh.contains("previousIndex = KnowledgeIndexStore.load(ws)"))
    }

    @Test
    fun `装配_索引读侧合并与启动触发都接上`() {
        val app = src("src/main/java/com/example/zhengdao/ZhengdaoApp.kt")
        assertTrue(app.contains("KnowledgeRefresh.install(this)"))
        val refresh = src("src/main/java/com/example/zhengdao/baopu/KnowledgeRefresh.kt")
        assertTrue(refresh.contains("KbIndexStore.installExtra"))
        val runtime = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(runtime.contains("KnowledgeRefresh.install(appCtx)"))
    }
}
