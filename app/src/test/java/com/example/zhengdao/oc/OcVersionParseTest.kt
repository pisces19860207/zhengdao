// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-17（第⑭刀 #7）回归：版本号解析**不抛** + verNewer 数值比较语义冻结。
 *
 * 背景：原实现 `split('.').map { it.toInt() }` 在畸形段（`2..0` / `2.0.` / `.2`）
 * 上抛 NumberFormatException —— release 里一个畸形 asset 名就能让更新检查整体失败、
 * 并中断同批其它合法 asset 的扫描。现在抽纯函数 [OcManager.parseVersionSegments]
 * （失败返回 null），正则源头同步收紧为 `\d+(\.\d+)*`。
 */
class OcVersionParseTest {

    // ── 畸形表：一律返回 null，绝不抛 ────────────────────────────────

    @Test
    fun `畸形版本号表 —— 返回 null 不抛`() {
        // 含超 Int 范围段（99999999999999999999）。
        // ⚠️ 不放「２.0」这类全角数字：Kotlin toIntOrNull 走 Character.digit 语义、
        //    会接受 Unicode 数字（实测），属无害怪癖——上游被 \d 正则约束为 ASCII。
        val malformed = listOf(
            "", ".", "..", "2..0", "2.0.", ".2", "v2.0.22", "2.0.22-rc.1",
            "2.0.22.1.2.3.4.5", "99999999999999999999", "2.0.x",
        )
        malformed.forEach { raw ->
            assertNull("「$raw」应返回 null", OcManager.parseVersionSegments(raw))
        }
    }

    // ── 合法表：逐段还原 ─────────────────────────────────────────────

    @Test
    fun `合法版本号表 —— 逐段还原`() {
        assertEquals(listOf(2, 0, 22), OcManager.parseVersionSegments("2.0.22"))
        assertEquals(listOf(2, 0), OcManager.parseVersionSegments("2.0"))
        assertEquals(listOf(10, 0), OcManager.parseVersionSegments("10.0"))
        assertEquals(listOf(2, 0, 22, 1), OcManager.parseVersionSegments("2.0.22.1"))
    }

    // ── verNewer 语义冻结（防将来被改成字符串比较）──────────────────

    @Test
    fun `verNewer 语义冻结 —— 数值比较而非字符串比较`() {
        val v = { s: String -> OcManager.parseVersionSegments(s)!! }
        // 逐位数值比较：10 > 9（字符串比较会得出 "10" < "9"）
        assertTrue(OcManager.verNewer(v("2.0.10"), v("2.0.9")))
        // "2.0" == "2.0.0"（缺段补 0）
        assertFalse(OcManager.verNewer(v("2.0"), v("2.0.0")))
        assertFalse(OcManager.verNewer(v("2.0.0"), v("2.0")))
        // 逐位数值比较：3.0.0 > 2.99.99
        assertTrue(OcManager.verNewer(v("3.0.0"), v("2.99.99")))
        // 相等不算新
        assertFalse(OcManager.verNewer(v("2.0.22"), v("2.0.22")))
    }
}
