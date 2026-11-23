# 几何包（state.geometry）README

静态几何判定内核：凸体支撑函数、GJK 最近距离、EPA 穿透深度（自研，零第三方物理库）。

## 公开语义（物理口径，semantics v1）

> **相交判定口径（物理口径，semantics v1）**
> `Gjk.distance` 承诺：两凸体表面**绝对间隙 ≤ 1e-8 cm（1 埃）一律记为接触**（`intersecting=true`，`distanceCm=0`）。
> 该口径是对外承诺，不是数值容差：不随形体尺度 L 变。几何内核的数值容差（收敛进度 `REL_EPS`、重复点 `DUP_REL`、权重窗 `WEIGHT_REL`、奇异判据 `REL_SINGULAR`）是随 L 归一的相对量（812/822），两类常量各司其职、禁止混用。
> 修改本口径（值、量纲或绝对性任一侧）＝破坏性变更：需独立单、`geometry.semantics.v2` 标记与调用方清单逐条评估。

- 对外语义全文（接触带／入口带／互锁／调用方清单／版本规则）：[docs/geometry-semantics.md](../geometry-semantics.md)。
- EPA 入口带：`CONTACT_DIST = 1e-7 cm`（10 埃）——超过则 `Epa.penetration` 返回 **null**，这是对外契约。
- 回归资产：[GeometrySemanticsRegressionTest](../../app/src/test/java/com/example/zhengdao/baopu/state/geometry/GeometrySemanticsRegressionTest.kt)
  （族 A 尺度不变性＋族 B 固定绝对间隙跨尺度语义一致性；族 B 兼作「物理口径被相对化」的负控制哨兵）。
