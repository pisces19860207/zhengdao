// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · **契约性测试**（业主 2026-11-23 逐条拍板，不是实现者的顺手断言）。
//
// 这里的每一条都满足三件事：
//   ① 有契约编号与拍板依据（改之前请先回去读依据，别当 bug 修）；
//   ② 遍历**全部**可能输入（格差三轴各 −2..2，共 125 种），不是点一两个例子；
//   ③ 一旦有人「顺手把阈值改正常」，这里必红——反直觉的行为是**被保护的**。
//
// 为什么不改成「三档独立网格」（每区域一套自己的网格）？见 C3 的注释与断言：
// 那会把「区域尺度」编码进网格尺寸本身，等于同一个事实存了两份（网格大小 + 区域倍数），
// 两份迟早漂移。本包只留一个尺度真相源 RegionGrade.scale，网格三档全区域共用。
package com.example.zhengdao.baopu.state.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewContractsTest {

    /** 三轴格差的所有可能值：Cell 每轴只有三档（−1/0/1），两格之差必在 −2..2。 */
    private val allOffsets: List<CellOffset> = buildList {
        (-2..2).forEach { right ->
            (-2..2).forEach { forward ->
                (-2..2).forEach { up -> add(CellOffset(right, forward, up)) }
            }
        }
    }

    @Test
    fun `契约表_格差全集恰一百二十五种`() {
        assertEquals("三轴各 5 种格差 ⇒ 5×5×5 = 125", 125, allOffsets.size)
        assertEquals("最远格距恒为 2（不会冒出第三档网格）", 2, allOffsets.maxOf { it.span })
    }

    /**
     * C1（业主 2026-11-23 拍板确认）：小室里永远没有「远」档。
     *
     * 依据：小室最远两格 × 尺度 1 = 2，按「≤1 近、≤3 中、>3 远」只到中档。
     * 这是**正确行为**，不是漏算——小屋里两步开外就是「中」，本来不存在远景。
     * 不要为了让三档都出现就去改阈值或抬区域倍数；要让远景出现，换区域档（见 C2 的共享网格）。
     */
    @Test
    fun `C1_小室永远没有远档_契约`() {
        val contract = "C1（业主 2026-11-23 拍板：小室无远档，阈值不许动）"
        val tiers = allOffsets.map { Projection.distanceTier(RegionGrade.SMALL_ROOM, it) }
        assertFalse("$contract：125 种格差里不许出现远档", DistanceTier.FAR in tiers)
        assertEquals("$contract：最远两格应恰好落在中档", DistanceTier.MID, tiers.maxBy { it.rank })
        assertEquals("$contract：近档仍然存在（不是把小室全判成中）", DistanceTier.NEAR, tiers.minBy { it.rank })
    }

    /**
     * C2（业主 2026-11-23 拍板确认）：厅堂与旷野在本三格网格上落档相同。
     *
     * 依据：厅堂 0/2/4、旷野 0/3/6，在「≤1 近、≤3 中、>3 远」下都是 近/中/远。
     * 这是**当前三格粒度的正确结果**，不是漏算：粒度不足以区分时就不许编出差别。
     * 有人想「修」成两档不同 ⇒ 他改的是阈值，会同时破坏 C1 与九例表。
     */
    @Test
    fun `C2_厅堂与旷野在三格网格落档相同_契约`() {
        val contract = "C2（业主 2026-11-23 拍板：厅堂与旷野同档，阈值不许动）"
        allOffsets.forEach { offset ->
            assertEquals(
                "$contract：格差 ${offset.rightSteps}/${offset.forwardSteps}/${offset.upSteps} 落档须相同",
                Projection.distanceTier(RegionGrade.HALL, offset),
                Projection.distanceTier(RegionGrade.OPEN_AIR, offset),
            )
        }
        assertEquals(
            "$contract：即便如此，厅堂仍要能从近走到远（三档都可达才算覆盖）",
            setOf(DistanceTier.NEAR, DistanceTier.MID, DistanceTier.FAR),
            allOffsets.map { Projection.distanceTier(RegionGrade.HALL, it) }.toSet(),
        )
    }

    /**
     * C3（本单设计取舍，随 C1/C2 一起写入契约）：三档区域**共用同一张三格网格**。
     *
     * 为什么不改成「三档独立网格」（小室 3 格 / 厅堂 5 格 / 旷野 7 格）：
     *  · 网格尺寸本身就成了第二个「区域尺度」真相源，与 RegionGrade.scale 并存 ⇒ 两处迟早漂移；
     *  · 独立网格下，同一个 (左,前,上) 在不同区域不再是同一格，「格距」得先换算才能比较，
     *    换算规则又是第三处真相；现在格差全区域同义，只有 scale 一处可调；
     *  · 三轴各三档（27 格）已经够写「左前上／右后下」这类叙事位置，细化网格属于另一单。
     *
     * 这条契约的机器形态：Cell 的构造参数只有三个格位档（不含 RegionGrade），
     * 且不存在按区域变化的格位档位表（三轴各自恒为 3 档）。
     */
    @Test
    fun `C3_三档区域共用一张三格网格_契约`() {
        val contract = "C3（共用网格：区域尺度只有一个真相源 RegionGrade.scale）"
        // Kotlin 编译器会给 data class 加静态 $stable（Compose 稳定性标记），那不是业务字段。
        val fields = Cell::class.java.declaredFields.filter {
            !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        assertEquals("$contract：Cell 只能有 across/depth/height 三个业务字段", 3, fields.size)
        assertTrue(
            "$contract：Cell 不许带 RegionGrade 字段（那会造出第二套网格）",
            fields.none { it.type == RegionGrade::class.java },
        )
        assertTrue("$contract：Cell 字段全是格位档枚举", fields.all { it.type.isEnum })
        assertEquals("$contract：横向三档", 3, Across.entries.size)
        assertEquals("$contract：纵深三档", 3, Depth.entries.size)
        assertEquals("$contract：竖向三档", 3, Height.entries.size)
        assertEquals(
            "$contract：所有格位对的最大格距恒为 2（不存在按区域扩格）",
            2, allOffsets.maxOf { it.span },
        )
    }
}
