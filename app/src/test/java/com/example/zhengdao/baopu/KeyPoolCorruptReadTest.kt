// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十九批 B 段第 3 条（257 审计 A-P2-13）的复现用例。
//
// 缺陷原文：`KeyPool` 读盘失败一律 `getOrNull() ?: emptyList()` —— 「一把 Key 都没存过」与
// 「存过但读坏了」在调用方手里是**同一个空池**。空池不是中性的：`migrate` 见空池回落老格
// `user_key_$id`（把用户早先删掉的那把 Key 救活并发进请求），写侧（ProviderPrefs 的
// 加/删/禁用）见空池又把它当"当前状态"整池盖回去（坏数据成了唯一残留）。
//
// 全部 JVM 直测：不碰 SharedPreferences（本仓无 Robolectric，Context 在 JVM 里实例化不了，
// 见 ProviderPrefs 文件头的既有教训），判据都做成 Context-free 的纯函数/纯类型。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolCorruptReadTest {

    private val canary = "sk-CANARY-8f2b9d4a"

    /**
     * 三档分明：没存过（Blank）／存好了（Stored）／读坏了（Corrupt）。
     *
     * **改前表现**：这一条根本写不出来——没有 `read()`，`decode()` 对 `null` 与对坏数据
     * 返回同一个 `emptyList()`（既有 `KeyPoolTest.脏数据读成空池_不抛` 钉的正是这个同形），
     * 调用方拿到一个值、两种真相，谁也分不开。
     */
    @Test
    fun `读侧三档_没存过_存好了_读坏了`() {
        assertTrue(KeyPool.read(null) is KeyPool.PoolRead.Blank)
        assertTrue("全空白也算没存过", KeyPool.read("   ") is KeyPool.PoolRead.Blank)
        val ok = KeyPool.read(KeyPool.encode(listOf(KeyPool.Entry("sk-a"))))
        assertTrue("合法池 ⇒ Stored", ok is KeyPool.PoolRead.Stored)
        assertEquals("sk-a", (ok as KeyPool.PoolRead.Stored).entries.single().key)
        // 三种坏法：根本不是数组／是对象不是数组／数组没闭合
        listOf("not json", "{}", "[1,2").forEach { bad ->
            val r = KeyPool.read(bad)
            assertTrue("「$bad」必须判成读坏了，而不是空池", r is KeyPool.PoolRead.Corrupt)
            assertTrue("读坏了要带人话原因：${(r as KeyPool.PoolRead.Corrupt).reason}", r.reason.contains("读坏了"))
        }
    }

    /** 既有口径不许漂：新格写的是 `"[]"`（删光）时读侧仍算 Blank、仍回落老格（KeyPoolTest 钉着）。 */
    @Test
    fun `空数组格仍按既有口径_本批不顺手改它`() {
        assertTrue(KeyPool.read("[]") is KeyPool.PoolRead.Blank)
        assertEquals(
            listOf("sk-old"),
            KeyPool.migrate(stored = "[]", legacy = "sk-old").map { it.key },
        )
    }

    /**
     * 负例（本条最能说明"空池不是中性的"）：**坏池不许把删掉的老 Key 救活**。
     *
     * **改前表现**：这条是**绿的反例**——`decode` 把坏池压成空列表 ⇒ `migrate` 见空回落老格 ⇒
     * 返回 `[sk-deleted-long-ago]`，也就是"存过的东西读坏了"被当成"新格没存过"，
     * 把用户早就删掉的那把 Key 又请回来发进请求。改后必须读不出条目、也不回落。
     */
    @Test
    fun `负例_坏池不许把删掉的老Key救活`() {
        val r = KeyPool.read("[1,2", legacy = "sk-deleted-long-ago")
        assertTrue("坏池就是坏池，不是没存过", r is KeyPool.PoolRead.Corrupt)
        assertTrue("坏池的条目必须是空（只读侧能用这一句）", r.entriesOrEmpty().isEmpty())
        assertTrue(
            "老格不许回落：回落＝把已删的 Key 发出去",
            KeyPool.migrate("[1,2", "sk-deleted-long-ago").isEmpty(),
        )
    }

    /**
     * 写侧判据（`ProviderPrefs.poolWriteRefusal`）：坏池面前**拒绝整池回写**。
     *
     * **改前表现**：判据不存在——`addKey`/`removeKey`/`setKeyDisabled` 直接拿空池算 next，
     * 于是"删掉一把"变成"把整池覆成只剩剩下的（或全空）"，而原本那几把 Key 是因为
     * **读坏了**才消失的；覆写之后坏数据是唯一残留，Key 真没了。
     */
    @Test
    fun `写侧判据_坏池拒绝整池回写_空白不豁免`() {
        val corrupt = KeyPool.read("not json")
        assertTrue(corrupt.isCorrupt())
        assertNotNull(ProviderPrefs.poolWriteRefusal(corrupt))
        assertTrue("拒绝原因要交得出去（调用方拿去报）：${ProviderPrefs.poolWriteRefusal(corrupt)}",
            ProviderPrefs.poolWriteRefusal(corrupt)!!.contains("读坏了"))
        assertNull("没存过（Blank）⇒ 照常写：首次填 Key 不能被这条判据挡住",
            ProviderPrefs.poolWriteRefusal(KeyPool.read(null)))
        assertNull("合法池 ⇒ 照常写",
            ProviderPrefs.poolWriteRefusal(KeyPool.read(KeyPool.encode(listOf(KeyPool.Entry("sk-a"))))))
        assertNull("用户当场填了新 Key ⇒ 这是明确要求的替换，放行",
            ProviderPrefs.poolWriteRefusal(corrupt, "sk-new"))
        assertNotNull("空串／空白不算「明确替换」",
            ProviderPrefs.poolWriteRefusal(corrupt, "   "))
    }

    /**
     * 保密纪律：[KeyPool.PoolRead.Corrupt.reason] 只许写"坏了＋异常种类"，**不许带任何原文**。
     *
     * org.json 的异常 message 在某些输入下会把被解析的字符串片段打进去，而 Key 的红线是
     * 「不进 RunLog、不进上下文」（ProviderPrefs 文件头）。这条用金丝雀钉住：
     * **改前表现**＝判据不存在（也无从泄露），改动若把 `it.message` 原样拼进 reason 就红。
     */
    @Test
    fun `原因串不带原文_Key 不进日志`() {
        val r = KeyPool.read("""[{"k":"$canary"}""")
        assertTrue("数组没闭合 ⇒ 读坏了", r is KeyPool.PoolRead.Corrupt)
        val reason = (r as KeyPool.PoolRead.Corrupt).reason
        assertFalse("原因串泄露了 Key 原文：$reason", reason.contains(canary))
        assertFalse("原因串不许带 JSON 片段：$reason", reason.contains("\"k\""))
        assertTrue("只说种类：$reason", reason.contains("省略不记") || reason.contains("解析不出"))
    }

    /** 老 API 的兼容面：`decode`/`migrate` 的返回形态一个字没动（坏数据 ⇒ 空列表，不抛）。 */
    @Test
    fun `老API形态没变_坏数据仍不抛`() {
        assertTrue(KeyPool.decode(null).isEmpty())
        assertTrue(KeyPool.decode("not json").isEmpty())
        assertTrue(KeyPool.decode("{}").isEmpty())
        assertEquals(listOf("sk-a"),
            KeyPool.decode("""[{"u":1},{"k":"sk-a"}]""").map { it.key })
        assertEquals(KeyPool.read(KeyPool.encode(listOf(KeyPool.Entry("sk-z")))).entriesOrEmpty(),
            KeyPool.decode(KeyPool.encode(listOf(KeyPool.Entry("sk-z")))))
    }

    /**
     * 调用方接线（源闸；蓝总管 2026-10-28 要求「每处调用方改动都要有对应用例，不许只改签名」）。
     *
     * **改前表现**：源码里根本没有 `poolWriteRefusal` 的调用，`storedKey`/`keyPool` 走
     * `KeyPool.migrate`（坏池 ⇒ 空池 ⇒ 回落老格）⇒ 本条红。
     * 为什么只能钉源码：SharedPreferences 在 JVM 里实例化不了（本仓无 Robolectric，
     * ProviderPrefs 文件头有既有教训），判据本身由上面几条直测，这里管"接线没接漏"。
     *
     * **本批在真门禁里撞出来的纪律（写进用例防复发）**：Key 池这一层**没有日志设施**——
     * 93 批 `ModelPickerGateTest.负例二_Key不进日志也不进崩溃上报` 与 251 批
     * `CustomProtocolGateTest.本批新增代码不打印不内插Key` 把 `Log.` 在这几个文件里逐字禁死。
     * 本条第一版在 `ProviderPrefs` 里写了 `RunLog.log(拒绝原因)`，全量门禁当场红两条 ⇒
     * 坏池理由只能作为**值**交出去（`PoolRead.Corrupt.reason`／`poolWriteRefusal` 的返回串），
     * 不在这一层记日志；要报的人自己走 `keyPoolRead()`。
     */
    @Test
    fun `接线_写路径四处都先过坏池判据`() {
        val src = SrcRoots.find("src/main/java/com/example/zhengdao/baopu/ProviderPrefs.kt")
            .readText(Charsets.UTF_8).replace("\r\n", "\n")
        val guards = src.lineSequence()
            .count { it.contains("poolWriteRefusal(") && !it.contains("internal fun") }
        assertTrue("加／删／禁用／保存四处都要先问判据，实测 $guards 处", guards >= 4)
        assertTrue("读侧走三档 read()，不再用压成列表的 migrate",
            src.contains("val read = KeyPool.read(p.getString(keysOf(id), null), p.getString(keyOf(id), null))"))
        listOf("addKey", "removeKey", "setKeyDisabled").forEach { fn ->
            val body = src.substringAfter("fun $fn(").substringBefore("\n    }")
            val guard = body.indexOf("poolWriteRefusal(read)")
            val write = body.indexOf("saveKeyPool(")
            assertTrue("$fn 有坏池判据（改前源码里根本没有这一句）", guard >= 0)
            assertTrue("$fn 的判据排在落盘之前（顺序反了就还是整池盖）", guard < write)
        }
        val saveBody = src.substringAfter("fun save(").substringBefore("\n    }")
        assertTrue("save 拒绝之后连 saveKeyPool 都不调（next 为空 ⇒ 一格不盖）",
            saveBody.contains("if (next.isNotEmpty()) saveKeyPool("))
        val stored = src.substringAfter("fun storedKey(").substringBefore("\n    }")
        assertTrue(
            "storedKey 读坏了 ⇒ 给不出 Key（不许复活老格），理由交回上层去报",
            stored.contains("return null") && !stored.contains("KeyPool.migrate"),
        )
        assertFalse("本层不许有日志调用：坏池理由是返回值，不是日志（93/251 两批门禁）", src.contains("RunLog.log("))
        assertFalse("本层不许 import 日志设施", src.contains("import com.example.zhengdao.core.RunLog"))
    }
}
