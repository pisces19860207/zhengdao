// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批 §8：R2「逐次人批」口径改订的**文字判据**。
//
// 为什么口径也要用测试钉：这条改动只动了"逐次"三个字，很容易在下一批被
// 顺手改回"R2 必问"或者一路放宽成"R2 不用问"——两种漂法都得有一条门禁在响。
package com.example.zhengdao.baopu.authz

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2ApprovalPolicyTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun confirmFlow(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/agent/ConfirmFlow.kt").readText()

    private fun guardPipeline(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt").readText()

    @Test
    fun 改订写清了旧口径与新口径() {
        val src = confirmFlow()
        assertTrue("要写明改订的是哪一条", src.contains("R2「逐次人批」口径改订"))
        assertTrue("要写清新的那一侧", src.contains("任务级明确授权"))
        assertTrue("旧口径必须原样留着给人对照", src.contains("**旧**"))
        assertTrue("新口径必须写出来", src.contains("**新**"))
        assertTrue("不许把 R2 降成 R1", src.contains("本批一个字都没动 R2 的分档与矩阵"))
    }

    @Test
    fun 改订引用了依据文档() {
        val src = confirmFlow()
        assertTrue(src.contains("派工单-2026-10-27-第二百四十九批"))
        assertTrue(src.contains("任务级明确授权-完整方案-2026-10-28.md"))
    }

    @Test
    fun 守卫管线不再说R2没有任何例外() {
        val src = guardPipeline()
        assertFalse(
            "旧的『`R2` **必问**；`R3` **必问且不可自动**』那句不许原样留着",
            src.contains("`R2` **必问**；`R3`"),
        )
        assertTrue("第 ③ 段要标出这是哪一批改的", src.contains("第二百四十九批改订"))
    }

    @Test
    fun 改订没有顺手放宽R3() {
        val src = guardPipeline()
        assertTrue("R3 仍必问且不可自动", src.contains("`R3` **必问且不可自动**"))
        assertTrue("R2 仍是必问那一档（只是多了人批来源）", src.contains("`R2` **必问**（"))
    }

    @Test
    fun 改订指向的六步是真的在代码里() {
        // 口径里写的"六步"不许是一句空话：它指的那个对象得真有六步。
        assertTrue(GrantStep.entries.size == 6)
        assertTrue(
            repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantGate.kt").isFile,
        )
    }
}
