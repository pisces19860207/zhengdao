# 几何内核公开语义 v1（geometry.semantics.v1）

- 来源：838（ZERO_NORM_SQ 定性）＋ 839（EPA/入口常数定性表）的裁定落文档；850 批落地（2026-11-23）。
- 本文是**对外承诺**：以下两条带与一条互锁是消费方可以钉的契约；数值与源码可 grep 对上（`1e-8` / `1e-7` / `1e-9`）。
- 内核唯一长度口径：**cm**。所有"间隙/距离/深度"读数都是两形体**表面**的绝对量（Minkowski 差最近点距原点距离）。

## 一、接触带（物理口径，838 定性）

> 两凸体表面**绝对间隙 ≤ 1e-8 cm（1 Å，埃级）⇒ 一律报相交/接触**：
> `Gjk.distance` 返回 `intersecting=true` 且 `distanceCm=0`。

- 判定点：`ZERO_NORM_SQ = 1e-16`（平方口径，cm²；线性 = √1e-16 = 1e-8 cm），
  见 [Gjk.kt](../app/src/main/java/com/example/zhengdao/baopu/state/geometry/Gjk.kt) 容差常量表与判定行。
- 该阈是**物理口径不是数值容差**：判定只依赖绝对间隙，不随形体标尺 L、坐标量级、构型大小变。
- 域内噪声由四个相对量（`REL_EPS`/`DUP_REL`/`WEIGHT_REL`/`REL_SINGULAR`，812/822）承担，
  接触带不承担吸噪职责（域内阈高出噪声地板 ≥3 个数量级，L≈7.5 km 才追平——域外）。
- 已知正确行为（不是 bug，禁止"修"）：固定**相对**间隙的构型均匀缩放后 hit/miss 可翻转——
  物理间隙本身变了，翻转即正确（838 反例 3）。

## 二、入口带（物理口径，839 定性）

> `Epa.penetration` 的 null/非 null 是对外契约：
> GJK 距离 ≤ `CONTACT_DIST = 1e-7 cm`（10 Å）的对才进 EPA 出深度读数；**超过则返回 null**。

- 判定点：[Epa.kt](../app/src/main/java/com/example/zhengdao/baopu/state/geometry/Epa.kt) 入口门
  `if (gjk.distanceCm > CONTACT_DIST) return null`。
- 入口带 = 接触带 × 10（1e-7 = 10×1e-8，839 互锁读数 R4）：接触对（`distanceCm=0`）恒过门，
  不会出现「GJK 判接触而 EPA 拒入」的矛盾。
- 近贴分离对（间隙 ∈ (1e-8, 1e-7]）过门后若原点不在 Minkowski 差体内，EPA 给深度 0（非 null）——
  「入门但无有效深度体」的约定读数，消费方按 0 处理。
- **已知未兑现侧（850 探针复现，归 843）**：入口带内个别分离近贴构型（实测钉死：等球 r=50 cm、
  gap=5e-8 cm）`Epa.penetration` 抛 IndexOutOfBoundsException（EPA 初始构造非严格垃圾四面体 ×
  扩张掏空面集，与 840 §十.2 恰切假深度同根）。修复前该侧契约不成立，回归资产已按现状钉死并留转红提醒。

## 三、互锁与分界（两类常量禁止合并/互算）

1. **绝对地板必须细于接触带**：EPA 收敛地板 `EPA_EPS_ABS = 1e-9 cm` < 接触带 1e-8 cm（现细 10 倍）。
   若未来任何调整使地板 ≥ 接触带，则接触带封顶、地板必须下调——收敛精度不得粗于接触口径（839 §二）。
2. **两类常量各司其职，禁止合并、禁止互算、禁止把物理口径相对化**：
   - 物理口径（绝对量，公开承诺）：`ZERO_NORM_SQ`（接触带）、`CONTACT_DIST`（入口带）；
   - 数值容差（相对量，随标尺 L 归一，812/822）：`REL_EPS`、`DUP_REL`、`WEIGHT_REL`、`REL_SINGULAR`、`EPA_EPS_REL`；
   - 工程地板（非对外承诺）：`SCALE_FLOOR_CM = 1.0 cm`——防 L→0 时相对阈消失，与两类公开口径禁止混用；
   - 性能参数（`source=engineering`）：`MAX_ITER = 64`、`EPA_MAX_ITER = 96`——无量纲，调整只需性能证据，不动语义。
3. 由 ① 派生的回归义务：把 `ZERO_NORM_SQ` 改成相对量（ε·L²）必须被跨尺度回归咬红
   （见 [GeometrySemanticsRegressionTest](../app/src/test/java/com/example/zhengdao/baopu/state/geometry/GeometrySemanticsRegressionTest.kt) 族 B 与负控制说明）。

## 四、受影响调用方清单（2026-11-23 对本分支 HEAD grep 核实）

改接触带或入口带 ⇒ 下表逐条重估。行号以本分支 HEAD 为准（grep 实核，非抄录）。

| # | 消费方 | 位置 | 消费什么 |
|---|---|---|---|
| 1 | `Epa` 入口门（包内） | Epa.kt:50（850 注释加行后，原 :43） | `distanceCm=0` 归零读数恒过门（接触带→入口带衔接） |
| 2 | `BearingSampler`（790） | BearingSampler.kt:254–255 | `distanceCm ≤ CONTACT_DEPTH_TOL(0.5)` 才查 `Epa.penetration` 深度 |
| 3 | `BearingSampler`（790） | BearingSampler.kt:266 | 点探针 `Epa.penetration(...)?.depthCm ?: 0.0`（null⇒0 兜底） |
| 4 | `Broadphase` 细判 | Broadphase.kt:44 | `distanceCm ≤ CONTACT_TOL_CM(=0.0)`「恰好接触算接触」，直接继承归零语义 |
| 5 | `CavityMap` | CavityMap.kt:107–112 | `distanceCm>0`⇒面分离读数；否则 `Epa` null/非 null 分深度/接触档 |
| 6 | `ContactProbe` | ContactProbe.kt:62 | `Epa.penetration` 返回 null ⇒ 整条读数 null（对外缺项） |
| 7 | `ContactProbe` | ContactProbe.kt:135 | `depthCm ?: −distanceCm`——null/非 null 决定读数正负号分支 |
| 8 | `PoseValidity` | PoseValidity.kt:80–82 | 距离＋`Epa` 深度（null⇒0）判穿地/穿沙/自碰 |
| 9 | 790 距离四档 | BearingGeometry.kt:38–45、BearingReach.kt:65 | 贴体档 [0,2.0) cm 建立在 `distanceCm` 口径上 |
| 10 | 测试钉（回归资产） | EpaTest.kt:61–62、CavityFixtures.kt:148 | null 断言与 null⇒0 兜底 |

生产消费点 **7 处（6 个文件）**＋测试钉 **2 处**；另 `CavityTolerances.CONTACT_TOL_CM=0.0`（CavityTolerances.kt:22）
是接触带语义在腔隙层的直接继承者。

## 五、版本与变更规则

- 当前版本：**geometry.semantics.v1**（655 确立口径，838/839 定性，850 成文）。
- **改接触带或入口带任一侧**（值、量纲、绝对性、null/非 null 边界语义）＝破坏性变更：
  1. 必须另立单（不得夹带）；
  2. 版本号 bump 为 `geometry.semantics.v2`，本文件与 README、`GJK_ASSUMPTION`/`EPA_ASSUMPTION` 同一次改齐（不拆两次）；
  3. §四 调用方清单**逐条重估并通告消费方**，重估结论写进交付报告；
  4. 跨尺度回归族 B 必须随新口径重标定（负控制演练照做）。
- 数值容差类（相对量）与性能参数的调整**不触发**本版本规则——它们不在对外承诺面上；
  但若调整使 §三 互锁被破坏（地板 ≥ 接触带），同样按破坏性变更处理。
