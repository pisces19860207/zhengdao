// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.core

import com.example.zhengdao.core.kb.KnowledgeBase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A5/A6/A8/A9（2026-10-29）：**只报不改行为**。
 *
 * 四处原来都是 `getOrDefault(默认值)` / `?: run {…}`：把「坏了」和「没有」混成同一个默认值，
 * 事后排查分不清是"确实没事"还是"出事了没人知道"。这里钉住四条人话说明：
 * 带异常类名、带路径/两端、并点明「不是空/确实没删/不迁移」这类容易被误读的语义。
 */
class MigrationFailureNoteTest {

    @Test
    fun `A5 目录列不出来要点明是 IO 问题而不是空目录`() {
        val note = ZhengdaoDirs.dirListFailureNote("/legacy/dir", IOException("EPERM"))
        assertTrue("带异常类名", note.contains("IOException"))
        assertTrue("带路径", note.contains("/legacy/dir"))
        assertTrue("点明是列不出来，不是空目录", note.contains("列不出来"))
    }

    @Test
    fun `A6 搬不动要点明 rename 与 copy 都失败并带两端路径`() {
        val note = ZhengdaoDirs.moveFailureNote("/a/x.txt", "/b/x.txt", IOException("ENOSPC"))
        assertTrue("带异常类名", note.contains("IOException"))
        assertTrue("带源路径", note.contains("/a/x.txt"))
        assertTrue("带目标路径", note.contains("/b/x.txt"))
        assertTrue("点明两条路都失败", note.contains("rename 与 copy 都失败"))
    }

    @Test
    fun `A8 清理失败要与真的删了 0 份区分开`() {
        val note = RunLog.pruneFailureNote(IOException("EACCES"))
        assertTrue("带异常类名", note.contains("IOException"))
        assertTrue("点明一份都没删", note.contains("一份都没删"))
        assertTrue("明确排除'清完发现 0 份'的误读", note.contains("清完发现 0 份"))
    }

    @Test
    fun `A9 取旧日志目录失败要点明本次不迁移`() {
        val note = RunLog.legacyDirFailureNote(IOException("EPERM"))
        assertTrue("带异常类名", note.contains("IOException"))
        assertTrue("点明本次不迁移", note.contains("不迁移"))
    }

    @Test
    fun `四处说明都能区分来源_不串台`() {
        val notes = listOf(
            ZhengdaoDirs.dirListFailureNote("p", IOException()),
            ZhengdaoDirs.moveFailureNote("a", "b", IOException()),
            RunLog.pruneFailureNote(IOException()),
            RunLog.legacyDirFailureNote(IOException()),
        )
        assertTrue("四种场景要有四种不同措辞（否则日志里分不清是谁出的事）", notes.toSet().size == 4)
        assertTrue(
            "资料库那套（A1/A3）的措辞也不能与迁移那套撞车",
            KnowledgeBase.oldIndexReadFailureNote("f", IOException()) !in notes,
        )
    }
}
