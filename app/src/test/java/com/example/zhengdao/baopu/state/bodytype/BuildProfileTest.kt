// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百一十五批 · 档案测试：字段接口、默认、出口过闸、修饰维不打分。
package com.example.zhengdao.baopu.state.bodytype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProfileTest {

    @Test
    fun `字段接口_五档逐字锁定`() {
        assertEquals(
            listOf("skeletonTier", "bodyFatTier", "muscleTier", "ageTier", "bodyStateTier"),
            BodyProfile.TIER_FIELDS,
        )
    }

    @Test
    fun `构造即五档齐_少一档造不出来`() {
        // 五个 tier 字段全是非空枚举且无默认值主体：Kotlin 编译期就要求全部给出。
        val p = BuildFixtures.mid()
        assertTrue(p.combo.label.contains("中"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `角色标识空白即拒`() {
        BuildFixtures.mid(role = "  ")
    }

    @Test
    fun `默认档案就是中中中`() {
        assertEquals(SkeletonTier.MID, BodyProfile.DEFAULT.skeletonTier)
        assertEquals(BodyFatTier.MID, BodyProfile.DEFAULT.bodyFatTier)
        assertEquals(MuscleTier.MID, BodyProfile.DEFAULT.muscleTier)
        assertEquals(BuildCatalog.DEFAULT_COMBO, BodyProfile.DEFAULT.combo)
    }

    @Test
    fun `出口行含五坐标与依据`() {
        val line = BuildFixtures.mid().line()
        assertTrue(line.contains("骨架 中"))
        assertTrue(line.contains("体脂 中"))
        assertTrue(line.contains("肌量 中"))
        assertTrue(line.contains("年龄 中年"))
        assertTrue(line.contains("状态 久坐期"))
    }

    @Test
    fun `修饰维不打分_换年龄与状态常见程度逐字不变`() {
        val base = BuildFixtures.mid()
        val changed = base.copy(ageTier = AgeTier.ELDER, bodyStateTier = BodyStateTier.RECOVERING)
        assertEquals(base.combo, changed.combo)
        assertEquals(base.commonness, changed.commonness)
    }

    @Test
    fun `常见程度表驱动_与目录一致`() {
        val p = BuildFixtures.lowFatHighMuscle()
        assertEquals(BuildCatalog.entryOf(p.skeletonTier, p.bodyFatTier, p.muscleTier).commonness, p.commonness)
        assertEquals(Commonness.RARE, p.commonness)
    }
}
