// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 岛叶单测：六体征齐全＋缺缝兜底＋温度档边界＋形轨只读缝（判据⑥）。
// "不抄账"钉法：岛叶的形轨数值只从只读 API 现读现显（用 666 的 SilhouetteWire 夹具验缝），
// 断言快照是值、取两次不留痕。
package com.example.zhengdao.baopu.bio

import com.example.zhengdao.baopu.body.SilhouettePosition
import com.example.zhengdao.baopu.body.SilhouettePosture
import com.example.zhengdao.baopu.body.SilhouetteWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsulaTest {

    private val full = Insula.Sources(
        batteryPct = { 87 },
        charging = { true },
        thermalC = { 36.5 },
        storageBytes = { (13L * 1024 * 1024 * 1024) to (64L * 1024 * 1024 * 1024) },
        buildSeconds = { 90L },
        gatesLine = { "绿：全量与 lint 都绿" },
        formTrackLine = {
            SilhouetteWire.grade(
                heightField = SilhouetteWire.FIXTURE,
                fabricName = "棉衬衫布",
                position = SilhouettePosition.WAIST,
                posture = SilhouettePosture.STAND,
            ).sentence
        },
    )

    // ── 满缝快照：六体征＋形轨现读，字段都有实义 ──

    @Test
    fun fullSnapshotReadsEveryVital() {
        val s = Insula.snapshot(full)
        assertEquals("87%（充电中）", s.battery)
        assertEquals("余 13.0GB / 共 64.0GB", s.storage)
        assertEquals("常温档（36.5℃）", s.thermal)
        assertEquals("构建中 90s", s.build)
        assertEquals("绿：全量与 lint 都绿", s.gates) // 门禁一句话原样转递，岛叶不判色
        assertTrue(s.formTrack.contains("隐藏")) // 形轨只读 API 现读：夹具 H=1.2/棉衬衫 C=2.5 → r=0.48
        assertTrue(s.note.contains("零落盘"))
    }

    // ── 确定性（判据④）：同一份缝取两次，快照逐字一致；快照是值不留痕 ──

    @Test
    fun snapshotTwiceIsIdentical() {
        assertEquals(Insula.snapshot(full), Insula.snapshot(full))
    }

    // ── 缺缝兜底（登记表口径：读数缺失即兜底，不造数不补零） ──

    @Test
    fun emptySourcesGiveAllMissing() {
        val s = Insula.snapshot()
        assertEquals("无读数", s.battery)
        assertEquals("无读数", s.storage)
        assertEquals("无读数", s.thermal)
        assertEquals("不在构建", s.build)
        assertEquals("无读数", s.gates)
        assertEquals("未接形轨缝", s.formTrack) // 缝没接（本批默认态）与接了没读数是两句话
    }

    @Test
    fun wiredButReadlessSaysMissingNotUnwired() {
        val s = Insula.snapshot(Insula.Sources(formTrackLine = { null }, gatesLine = { "  " }))
        assertEquals("形轨无读数", s.formTrack)
        assertEquals("无读数", s.gates) // 空白行也算没读数，不转递空话
    }

    @Test
    fun outOfRangeReadsAreNotFaked() {
        assertEquals("无读数", Insula.snapshot(Insula.Sources(batteryPct = { 200 })).battery)
        assertEquals(
            "无读数",
            Insula.snapshot(Insula.Sources(storageBytes = { 1L to 0L })).storage, // 总量 0 ⇒ 不显
        )
        assertEquals(
            "充电态未知",
            Insula.snapshot(Insula.Sources(batteryPct = { 50 }, charging = { null })).battery
                .substringAfterLast("（").substringBeforeLast("）"),
        )
    }

    // ── 温度档边界（左闭右开，压线归高档） ──

    @Test
    fun thermalBands() {
        val cases = listOf(
            29.9 to "凉档", 30.0 to "常温档", 36.9 to "常温档",
            37.0 to "温档", 39.9 to "温档", 40.0 to "热档",
        )
        for ((c, band) in cases) {
            val s = Insula.snapshot(Insula.Sources(thermalC = { c }))
            assertTrue("温度 $c 应落在 $band：实际 ${s.thermal}", s.thermal.startsWith(band))
        }
        assertEquals(
            "无读数",
            Insula.snapshot(Insula.Sources(thermalC = { Double.NaN })).thermal, // 非有限值不造档
        )
    }

    // ── 523 三件套①：有它＝一次调用汇齐七体征的读数面；没它＝没有这个面 ──

    @Test
    fun triple1UnifiedVitalsFaceIsTheDifference() {
        val s = Insula.snapshot(full)
        // 七格一次拿全且都有实义（散落日志做不到的事——这就是装与不装的差异）。
        for ((name, v) in listOf(
            "battery" to s.battery, "storage" to s.storage, "thermal" to s.thermal,
            "build" to s.build, "gates" to s.gates, "formTrack" to s.formTrack, "note" to s.note,
        )) {
            assertTrue("$name 应有读数：$v", v.isNotEmpty())
        }
        // 探头没接（缝缺）⇒ 逐格"无读数"，不造数——缺读数即兜底，快照面仍在。
        val bare = Insula.snapshot()
        assertEquals("无读数", bare.battery)
        assertEquals("无读数", bare.gates)
    }
}
