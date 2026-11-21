// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 锁结设定谱行为臂：
// 判据① depthStage/lockPoint 成对（缺一红）；判据② lockSource 闭集＋球状体禁词即红；
// 判据③ 字段级 canon 戳（source/canonVersion/asOfCanon 与当前谱不符即拒）；
// 档位闭集逐个数（0–3 四阶 / 卡点七档含双压 / 来源三值 / 时长三档 / 消退三档 / 硬度三档）。
package com.example.zhengdao.baopu.state.fiction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FictionCanonTest {

    private fun lock(
        d: DepthStage? = DepthStage.IN_NECK_CANAL,
        p: LockPoint? = LockPoint.CORONAL_GROOVE,
        s: LockSource = LockSource.REFLEX_CONTRACTION,
        dur: LockDuration = LockDuration.MEDIUM,
        rel: ReleaseCondition = ReleaseCondition.SUBSIDED,
        firm: FirmnessDuringLock = FirmnessDuringLock.GLANS_BODY_HARDEST,
        src: String = FictionCanon.SOURCE_CANON,
        ver: Int = FictionCanon.CANON_VERSION,
        asOf: String = FictionCanon.AS_OF_CANON,
    ) = LockCanon(d, p, s, dur, rel, firm, src, ver, asOf)

    // ── 判据①：成对 ─────────────────────────────────────────────────────────

    @Test
    fun `判据一_只给深度不给卡点_红`() {
        assertThrows(IllegalArgumentException::class.java) { lock(p = null) }
    }

    @Test
    fun `判据一_只给卡点不给深度_红`() {
        assertThrows(IllegalArgumentException::class.java) { lock(d = null) }
    }

    @Test
    fun `判据一_成对给出_绿`() {
        val l = lock()
        assertEquals(DepthStage.IN_NECK_CANAL, l.depthStage)
        assertEquals(LockPoint.CORONAL_GROOVE, l.lockPoint)
    }

    // ── 档位闭集（照 759 单 §三，不重新设计）───────────────────────────────

    @Test
    fun `深度四阶_零一二三_未入至颈口入颈管入腔`() {
        assertEquals(4, DepthStage.values().size)
        val cn = DepthStage.values().associate { it.stage to it.cn }
        assertEquals(setOf(0, 1, 2, 3), cn.keys)
        assertEquals("未入", cn[0])
        assertEquals("至颈口", cn[1])
        assertEquals("入颈管", cn[2])
        assertEquals("入腔", cn[3])
    }

    @Test
    fun `卡点七档_含双压`() {
        assertEquals(7, LockPoint.values().size)
        val names = LockPoint.values().map { it.cn }.toSet()
        assertTrue(names.containsAll(setOf("冠倒钩", "冠状沟", "龟头体", "颈口", "颈管", "腔", "双压(冠+体)")))
    }

    @Test
    fun `来源三值闭集_时长三档_消退三档_硬度三档`() {
        assertEquals(listOf("反射性收缩", "压紧", "两者叠加"), LockSource.values().map { it.cn })
        assertEquals(listOf("短", "中", "长"), LockDuration.values().map { it.cn })
        assertEquals(3, ReleaseCondition.values().size)
        assertEquals(setOf("整根胀满", "龟头体最硬", "冠最硬"), FirmnessDuringLock.values().map { it.cn }.toSet())
    }

    // ── 判据②⑤：球状体禁令是断言不是注释 ──────────────────────────────────

    @Test
    fun `判据二_lockSource_出现球状体字样_红`() {
        for (w in listOf("球状体", "球状腺", "鼓球", "膨大结", "结状物", "knot", "bulbus")) {
            assertThrows("禁词 $w 必须被拒", IllegalArgumentException::class.java) {
                BulbusGuard.rejectIfBulbus("lockSource", "人类根部${w}膨胀")
            }
        }
    }

    @Test
    fun `判据二_拉丁同义写法大小写不敏感_红`() {
        assertThrows(IllegalArgumentException::class.java) {
            BulbusGuard.rejectIfBulbus("lockSource", "Bulbus Glandis 挤压")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BulbusGuard.rejectIfBulbus("lockSource", "KNOT 顶住")
        }
    }

    @Test
    fun `判据二_合法来源三值全部放行_绿`() {
        for (s in LockSource.values()) BulbusGuard.rejectIfBulbus("lockSource", s.cn)
    }

    // ── 判据③：canon 戳字段级校验 ──────────────────────────────────────────

    @Test
    fun `判据三_source_不是_canon_红`() {
        assertThrows(IllegalArgumentException::class.java) { lock(src = "作者假设") }
        assertThrows(IllegalArgumentException::class.java) { lock(src = "fact") }
    }

    @Test
    fun `判据三_canonVersion_或_asOfCanon_与当前谱不符_红`() {
        assertThrows(IllegalArgumentException::class.java) { lock(ver = FictionCanon.CANON_VERSION + 1) }
        assertThrows(IllegalArgumentException::class.java) { lock(asOf = "2026-11-20") }
    }

    @Test
    fun `设定谱当前版与命名口径_钉死`() {
        assertEquals(1, FictionCanon.CANON_VERSION)
        assertEquals("2026-11-21", FictionCanon.AS_OF_CANON)
        assertEquals("canon", FictionCanon.SOURCE_CANON)
        assertEquals("fiction", FictionCanon.REALITY_FICTION)
    }
}
