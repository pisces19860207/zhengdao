# 930 Personality Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 819 唯一人格模型上补齐八轴 values、动态 mood/emotion/pressure、五因子默认值来源、927 变动证据、内部矛盾与三态语言闸，同时保持 core 与 SurfaceDeriver 语义不变。

**Architecture:** 所有生产代码留在 `baopu/state/persona/`。公开 `Persona` 不增加新字段；新增 internal `PersonaEnhancement` 聚合既有 `Persona` 与新 layers。临床词、污名词、二次元标签统一走静态词法表，不做语义推理、同义词或向量扩展。

**Tech Stack:** Kotlin 2.2.10、JUnit 4、Android Gradle Plugin、detekt、AGP lint、Python 标准库门禁。

**Spec:** `docs/superpowers/specs/2026-11-25-930-personality-enhancement-design.md`

## Global Constraints

- 基线 `390fed25561b90564841ae6f779a42c8b21272ed`；分支 `feat/personality`；工作树 `wt-930`；不推远端。
- 不创建 `baopu/state/personality/`，不新建第二套 core/trait/surface。
- 五个新生产文件全部 internal：`PersonaValues.kt`、`PersonaMoodState.kt`、`PersonaLanguage.kt`、`TraitChanges.kt`、`PersonaEnhancement.kt`。
- 只修改既有 `PersonaModel.kt`、`PersonaChecks.kt` 及对应测试；`SurfaceDeriver.kt` 必须零 diff。
- `TraitLayer` 保留 819 七轴，新增五个 `AxisValue(tier, source)`；默认 `1/DEFAULT`，显式值 `EXPLICIT`，tier 强制 `0..3`。
- values 八轴各恰好一次、`0..3`；缺/重/越界红；values/core 冲突只 WARN。
- states 不得反推 trait/core；values 不得由职业、关系、身体锚点推导。
- 927 变动必须有 `event/latent/longterm` 证据；只有单事件红。
- 临床词表 34 条、四列、四类齐全；不含 `Big-Five`、`大五人格`、单独 `量表`。
- 临床词：台词绿；叙述者红；显式医生/心理从业者＋同句不确定词 WARN。沿用红码 `DIAGNOSIS`。
- 污名词只含 `直男癌/舔狗/精神病/神经病/娘炮/废物/太监`；台词 WARN，叙述者红。
- 二次元标签只允许 `SurfaceState.tags`；非 surface 命中红。
- 新函数 `<=59` 行、复杂度 `<=14`；禁止 `@Suppress`、禁止抬高 detekt/lint/水位预算。
- detekt `<=189`；AGP lint 原始 `errors=0/warnings=0`；全量 app/core 单测零失败；全量测试后水位 `<=315`。
- 新测试不创建临时目录；不得用定向测试读数冒充全量读数。
- 构建必须走 build-lock，`JAVA_HOME` 固定 Android Studio JBR；长任务写日志并用日志中的退出码判定。

## File Structure

| 文件 | 责任 |
|---|---|
| `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaModel.kt` | 既有 core/trait/surface/voice；新增五轴 `AxisValue` 与 surface tags |
| `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaChecks.kt` | 819 总检；十二轴快照；接入静态语言闸与 surface tags |
| `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaValues.kt` | 八轴 values 与 values/core WARN |
| `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaMoodState.kt` | 动态 mood/emotion/pressure |
| `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaLanguage.kt` | 34 条临床词、位置词法、污名词、标签、决定论闸 |
| `app/src/main/java/com/example/zhengdao/baopu/state/persona/TraitChanges.kt` | 927 三根证据与内部矛盾校验 |
| `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaEnhancement.kt` | internal 聚合与总检 |
| `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt` | 930 合法/冲突/矛盾夹具 |
| `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt` | 模型、values、state、927、矛盾、确定性 |
| `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930LanguageTest.kt` | 34 条三态、负例、污名词、标签 |
| `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930SourceAssertTest.kt` | 单一真相、禁止推导边、唯一 SurfaceDeriver、静态表 |
| `app/src/test/java/com/example/zhengdao/baopu/state/persona/PersonaChecksTest.kt` | 819 断言演进与语言闸集成 |
| `app/src/test/java/com/example/zhengdao/baopu/state/persona/SurfaceDeriverTest.kt` | 三组 surface 逐字节基线 |

---

### Task 1: 锁住 819 兼容基线并扩展 TraitLayer / SurfaceState

**Files:**
- Modify: `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaModel.kt:38-93`
- Modify: `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaChecks.kt:62-69`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/PersonaChecksTest.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/SurfaceDeriverTest.kt`
- Create: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt`

**Interfaces:**
- Consumes: 既有 `TraitLayer` 七轴、`SurfaceState.levels`、`SurfaceDeriver.derive`。
- Produces: `TraitLayer.AxisValue(tier, source)`、`TraitLayer.explicit(tier)`、`TraitLayer.BIG_FIVE_AXES`、`TraitLayer.bigFiveLevels()`、`TraitLayer.bigFiveTier(axis)`、`SurfaceState.tags`。

- [ ] **Step 1: 记录基线并准备本地构建配置**

```bash
export JAVA_HOME='D:/Program Files/Android/Android Studio/jbr'
git status --short --branch
git rev-parse HEAD
sha256sum app/src/main/java/com/example/zhengdao/baopu/state/persona/SurfaceDeriver.kt
cp ../zhengdao/local.properties local.properties
git status --short
```

Expected: `local.properties` 被 gitignore；记录 SHA-256，Task 9 必须相同。

- [ ] **Step 2: 写失败测试**

在 `Persona930ModelTest.kt` 写入：

```kotlin
package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Persona930ModelTest {
    @Test
    fun `五轴省略时为中档一并标记DEFAULT`() {
        val trait = PersonaFixture.trait()
        assertEquals(5, trait.bigFiveLevels().size)
        assertTrue(trait.bigFiveLevels().all { it.second.tier == 1 })
        assertTrue(trait.bigFiveLevels().all { it.second.source == "DEFAULT" })
    }

    @Test
    fun `五轴显式值标记EXPLICIT且越界被拒`() {
        val trait = PersonaFixture.trait().copy(
            agreeableness = TraitLayer.explicit(3),
            conscientiousness = TraitLayer.explicit(0),
        )
        assertEquals(3, trait.bigFiveTier(TraitLayer.AGREEABLENESS))
        assertEquals(0, trait.bigFiveTier(TraitLayer.CONSCIENTIOUSNESS))
        assertEquals("EXPLICIT", trait.agreeableness.source)
        val threw = runCatching { TraitLayer.explicit(4) }.exceptionOrNull()
        assertTrue(threw is IllegalArgumentException)
    }
}
```

在 `SurfaceDeriverTest.kt` 增加：

```kotlin
@Test
fun `930_三组既有surface轴串与819基线逐字节一致`() {
    val base = PersonaFixture.snapshot()
    val cases = listOf(
        base to "PATIENCE|3|轮数0·显性度Lv1|INTOXICATION|0|外部喂入酒意信号|FATIGUE|0|外部喂入疲惫信号|SHAME|0|防守阶段S0·立场中立|TENSION|0|显性度Lv1·硬否决=false",
        base.copy(turns = 4, salience = 3, dStage = 3, stanceLabel = "排斥", intoxication = 1, fatigue = 2) to
            "PATIENCE|1|轮数4·显性度Lv3|INTOXICATION|1|外部喂入酒意信号|FATIGUE|2|外部喂入疲惫信号|SHAME|2|防守阶段S3·立场排斥|TENSION|2|显性度Lv3·硬否决=false",
        base.copy(turns = 8, salience = 4, dStage = 6, stanceLabel = "排斥", vetoActive = true, intoxication = 3, fatigue = 3) to
            "PATIENCE|0|轮数8·显性度Lv4|INTOXICATION|3|外部喂入酒意信号|FATIGUE|3|外部喂入疲惫信号|SHAME|3|防守阶段S6·立场排斥|TENSION|3|显性度Lv4·硬否决=true",
    )
    cases.forEach { (snapshot, expected) ->
        val surface = SurfaceDeriver.derive(snapshot)
        val actual = surface.levels.joinToString("|") { "${it.axis.name}|${it.tier}|${it.source}" }
        assertEquals(expected, actual)
        assertTrue(surface.tags.isEmpty())
    }
}
```

在 `PersonaChecksTest.kt` 增加：

```kotlin
@Test
fun `930_特质快照明确819七轴演进为十二轴`() {
    val report = PersonaChecks.check(PersonaFixture.persona())
    val text = report.items.first { it.code == "TRAIT_AXES" }.message
    assertTrue(text.contains("819 七轴 → 930 十二轴"))
    TraitLayer.BIG_FIVE_AXES.forEach { axis -> assertTrue(text.contains(axis)) }
    assertTrue(text.contains("1(DEFAULT)"))
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.Persona930ModelTest --tests com.example.zhengdao.baopu.state.persona.SurfaceDeriverTest --tests com.example.zhengdao.baopu.state.persona.PersonaChecksTest'
```

Expected: 编译失败，指向 `AxisValue`、`bigFiveLevels`、`tags` 尚不存在。

- [ ] **Step 4: 实现最小模型改动**

`TraitLayer` 保留原七个参数，在其后追加五个默认参数：

```kotlin
data class TraitLayer(
    val sociability: Sociability,
    val directness: Directness,
    val stance: StanceStyle,
    val orientation: Orientation,
    val selfControl: SelfControl,
    val aggressiveness: Aggressiveness,
    val humor: Humor,
    val openness: AxisValue = AxisValue(1, "DEFAULT"),
    val conscientiousness: AxisValue = AxisValue(1, "DEFAULT"),
    val extraversion: AxisValue = AxisValue(1, "DEFAULT"),
    val agreeableness: AxisValue = AxisValue(1, "DEFAULT"),
    val emotionalStability: AxisValue = AxisValue(1, "DEFAULT"),
) {
    data class AxisValue(val tier: Int, val source: String) {
        init {
            require(tier in 0..3) { "五因子档位只取 0–3：$tier" }
            require(source == "DEFAULT" || source == "EXPLICIT") { "五因子来源只收 DEFAULT/EXPLICIT" }
        }
    }

    companion object {
        const val OPENNESS = "openness"
        const val CONSCIENTIOUSNESS = "conscientiousness"
        const val EXTRAVERSION = "extraversion"
        const val AGREEABLENESS = "agreeableness"
        const val EMOTIONAL_STABILITY = "emotionalStability"
        val BIG_FIVE_AXES = listOf(OPENNESS, CONSCIENTIOUSNESS, EXTRAVERSION, AGREEABLENESS, EMOTIONAL_STABILITY)

        fun explicit(tier: Int): AxisValue = AxisValue(tier, "EXPLICIT")
    }

    fun bigFiveLevels(): List<Pair<String, AxisValue>> = listOf(
        OPENNESS to openness,
        CONSCIENTIOUSNESS to conscientiousness,
        EXTRAVERSION to extraversion,
        AGREEABLENESS to agreeableness,
        EMOTIONAL_STABILITY to emotionalStability,
    )

    fun bigFiveTier(axis: String): Int? = bigFiveLevels().firstOrNull { it.first == axis }?.second?.tier
}
```

`SurfaceState` 改为：

```kotlin
data class SurfaceState(
    val levels: List<SurfaceLevel>,
    val tags: List<String> = emptyList(),
)
```

`PersonaChecks.traitChecks` 保留稳定码 `TRAIT_AXES`，消息改为：

```kotlin
private fun traitChecks(trait: TraitLayer): List<CheckItem> {
    val old = listOf(
        trait.sociability.label, trait.directness.label, trait.stance.label,
        trait.orientation.label, trait.selfControl.label, trait.aggressiveness.label, trait.humor.label,
    ).joinToString("/")
    val five = trait.bigFiveLevels().joinToString("/") { (axis, value) -> "$axis=${value.tier}(${value.source})" }
    return listOf(CheckItem(Severity.WARNING, "TRAIT_AXES", "819 七轴 → 930 十二轴（新增五因子）：$old/$five"))
}
```

- [ ] **Step 5: 重跑目标测试**

重复 Step 3 命令。Expected: 三类测试全绿；`SurfaceDeriver.kt` 的 SHA-256 与 Step 1 相同。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaModel.kt app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaChecks.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/PersonaChecksTest.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/SurfaceDeriverTest.kt
git commit -m "feat(930): 十二轴默认来源与surface标签兼容扩展"
```

---

### Task 2: 八轴 values 与 values/core WARN

**Files:**
- Create: `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaValues.kt`
- Create: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt`

**Interfaces:**
- Consumes: `CoreLayer`、`CoreNeed`、`CheckItem`、`Severity`。
- Produces: `ValueAxis`、`ValueWeight`、`PersonaValues`、`PersonaValueChecks.check(values, core)`。

- [ ] **Step 1: 写失败测试与夹具**

`Persona930Fixture.kt`：

```kotlin
package com.example.zhengdao.baopu.state.persona

internal object Persona930Fixture {
    fun values(): PersonaValues = PersonaValues(
        ValueAxis.entries.map { axis ->
            ValueWeight(axis, when (axis) {
                ValueAxis.FAMILY -> 3
                ValueAxis.CAREER -> 2
                ValueAxis.FREEDOM -> 1
                ValueAxis.SECURITY -> 0
                ValueAxis.INTIMACY -> 2
                ValueAxis.FACE -> 1
                ValueAxis.MONEY -> 1
                ValueAxis.FAITH -> 0
            })
        },
    )

    fun conflictingValues(): PersonaValues = PersonaValues(
        ValueAxis.entries.map { axis ->
            ValueWeight(axis, if (axis == ValueAxis.SECURITY) 0 else if (axis == ValueAxis.FREEDOM) 3 else 1)
        },
    )

    fun duplicateFamilyValues(): PersonaValues = PersonaValues(
        values().weights.map { if (it.axis == ValueAxis.FAMILY) it.copy(tier = 0) else it } +
            ValueWeight(ValueAxis.FAMILY, 3),
    )
}
```

`Persona930ModelTest` 增加：

```kotlin
@Test
fun `values八轴完整合法且core直接冲突只WARN`() {
    val clean = PersonaValueChecks.check(Persona930Fixture.values(), PersonaFixture.core())
    assertTrue(clean.none { it.severity == Severity.ERROR })

    val core = PersonaFixture.core().copy(
        drives = listOf(
            CoreDrive(CoreNeed.SECURITY, "被抛下", 1, "关系不确定时"),
            CoreDrive(CoreNeed.BELONGING, "无处可去", 2, "进入陌生场合时"),
            CoreDrive(CoreNeed.RECOGNITION, "被忽略", 3, "当众被忽略时"),
        ),
    )
    val conflict = PersonaValueChecks.check(Persona930Fixture.conflictingValues(), core)
    val warning = conflict.single { it.code == "VALUES_CORE_CONFLICT" }
    assertEquals(Severity.WARNING, warning.severity)
    assertTrue(warning.message.contains("SECURITY"))
    assertTrue(warning.message.contains("FREEDOM"))
    assertTrue(warning.message.contains("安全0"))
    assertTrue(warning.message.contains("自由3"))

    val freedomCore = core.copy(
        drives = listOf(
            CoreDrive(CoreNeed.FREEDOM, "被限制", 1, "被约束时"),
            CoreDrive(CoreNeed.BELONGING, "无处可去", 2, "进入陌生场合时"),
            CoreDrive(CoreNeed.RECOGNITION, "被忽略", 3, "当众被忽略时"),
        ),
    )
    val reverseValues = Persona930Fixture.conflictingValues().copy(
        weights = Persona930Fixture.conflictingValues().weights.map {
            if (it.axis == ValueAxis.SECURITY) it.copy(tier = 3) else if (it.axis == ValueAxis.FREEDOM) it.copy(tier = 0) else it
        },
    )
    val reverse = PersonaValueChecks.check(reverseValues, freedomCore)
    assertEquals("VALUES_CORE_CONFLICT", reverse.single().code)
    assertTrue(reverse.single().message.contains("自由0/安全3"))
}

@Test
fun `values缺轴重轴越界与同轴冲突均红`() {
    val missing = Persona930Fixture.values().copy(weights = Persona930Fixture.values().weights.drop(1))
    val duplicate = Persona930Fixture.duplicateFamilyValues()
    val invalid = Persona930Fixture.values().copy(
        weights = Persona930Fixture.values().weights.map { if (it.axis == ValueAxis.MONEY) it.copy(tier = 4) else it },
    )
    listOf(missing, duplicate, invalid).forEach { value ->
        assertTrue(PersonaValueChecks.check(value, PersonaFixture.core()).any { it.severity == Severity.ERROR })
    }
    assertTrue(PersonaValueChecks.check(duplicate, PersonaFixture.core()).any {
        it.code == "VALUES_AXES" && it.message.contains("FAMILY")
    })
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.Persona930ModelTest'
```

Expected: `PersonaValues`、`ValueAxis`、`PersonaValueChecks` 未解析。

- [ ] **Step 3: 实现 values**

```kotlin
package com.example.zhengdao.baopu.state.persona

internal enum class ValueAxis { FAMILY, CAREER, FREEDOM, SECURITY, INTIMACY, FACE, MONEY, FAITH }

internal data class ValueWeight(val axis: ValueAxis, val tier: Int)

internal data class PersonaValues(val weights: List<ValueWeight>)

internal object PersonaValueChecks {
    fun check(values: PersonaValues, core: CoreLayer): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        val axes = values.weights.map { it.axis }
        val duplicates = axes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (axes.size != ValueAxis.entries.size || axes.toSet() != ValueAxis.entries.toSet() || duplicates.isNotEmpty()) {
            out += CheckItem(Severity.ERROR, "VALUES_AXES", "values 八轴须各恰好一次；缺/重=${axes.toSet()}-${ValueAxis.entries.toSet()}/${duplicates}")
        }
        if (values.weights.any { it.tier !in 0..3 }) {
            out += CheckItem(Severity.ERROR, "VALUES_TIER", "values 档位只取 0–3")
        }
        if (out.isEmpty()) out += coreConflict(values, core)
        return out
    }

    private fun coreConflict(values: PersonaValues, core: CoreLayer): List<CheckItem> {
        val tier = values.weights.associate { it.axis to it.tier }
        val top = core.drives.filter { it.rank <= 2 }.map { it.need }.toSet()
        val securityCore = CoreNeed.SECURITY in top && tier[ValueAxis.SECURITY] == 0 && tier[ValueAxis.FREEDOM] == 3
        val freedomCore = CoreNeed.FREEDOM in top && tier[ValueAxis.FREEDOM] == 0 && tier[ValueAxis.SECURITY] == 3
        if (!securityCore && !freedomCore) return emptyList()
        val rank = core.drives.first { it.need == if (securityCore) CoreNeed.SECURITY else CoreNeed.FREEDOM }.rank
        return listOf(
            CheckItem(
                Severity.WARNING,
                "VALUES_CORE_CONFLICT",
                "core ${if (securityCore) "SECURITY" else "FREEDOM"} rank=$rank ｜ values 安全${tier[ValueAxis.SECURITY]}/自由${tier[ValueAxis.FREEDOM]}",
            ),
        )
    }
}
```

- [ ] **Step 4: 重跑目标测试**

重复 Step 2。Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaValues.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt
git commit -m "feat(930): 八轴价值观与core冲突提示"
```

---

### Task 3: 动态 mood / emotion / pressure

**Files:**
- Create: `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaMoodState.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt`

**Interfaces:**
- Consumes: 无稳定层类型。
- Produces: `MoodTier`、`Emotion`、`PersonaMoodState`、`PersonaStateChecks.check(state)`。

- [ ] **Step 1: 写失败测试**

夹具增加：

```kotlin
fun state(): PersonaMoodState = PersonaMoodState(
    mood = MoodTier.STEADY,
    emotion = Emotion.CALM,
    pressure = 1,
    note = "此刻有些紧",
)
```

测试增加：

```kotlin
@Test
fun `动态状态只记录压力与说明不反推稳定层`() {
    assertTrue(PersonaStateChecks.check(Persona930Fixture.state()).isEmpty())
    val bad = Persona930Fixture.state().copy(pressure = 4)
    assertEquals(
        "STATE_PRESSURE",
        PersonaStateChecks.check(bad).single { it.severity == Severity.ERROR }.code,
    )
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.Persona930ModelTest'
```

Expected: `PersonaMoodState` 等未解析。

- [ ] **Step 3: 实现动态状态**

```kotlin
package com.example.zhengdao.baopu.state.persona

internal enum class MoodTier(val label: String) { LOW("低落"), STEADY("平稳"), LIGHT("轻快"), HIGH("高涨") }

internal enum class Emotion(val label: String) { CALM, JOY, SADNESS, ANGER, FEAR, DISGUST }

internal data class PersonaMoodState(
    val mood: MoodTier,
    val emotion: Emotion,
    val pressure: Int,
    val note: String? = null,
)

internal object PersonaStateChecks {
    fun check(state: PersonaMoodState): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        if (state.pressure !in 0..3) {
            out += CheckItem(Severity.ERROR, "STATE_PRESSURE", "压力档位只取 0–3：${state.pressure}")
        }
        return out
    }
}
```

`note` 的语言检查在 Task 5 的聚合总检统一接入；本文件不得引用 `PersonaLanguage`、trait、core 或 surface，以保持 states 边界可由源码断言证明。

- [ ] **Step 4: 重跑目标测试**

重复 Step 2。Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaMoodState.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt
git commit -m "feat(930): 动态心境情绪压力层"
```

---

### Task 4: 静态语言表与三态词法闸

**Files:**
- Create: `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaLanguage.kt`
- Create: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930LanguageTest.kt`

**Interfaces:**
- Consumes: `CheckItem`、`Severity`。
- Produces: `ClinicalCategory`、`ClinicalAllowedPosition`、`ClinicalExemption`、`ClinicalTermRule`、`ClinicalPosition`、`ClinicalSubjectRole`、`PersonaLanguage`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Persona930LanguageTest {
    @Test
    fun `临床词34条四列四类且不自指五因子`() {
        assertEquals(34, PersonaLanguage.CLINICAL_TERMS.size)
        assertEquals(
            setOf("人格障碍", "依恋类型", "精神科诊断", "伪心理标签"),
            PersonaLanguage.CLINICAL_TERMS.map { it.category.label }.toSet(),
        )
        assertTrue(PersonaLanguage.CLINICAL_TERMS.all { it.allowedPosition == ClinicalAllowedPosition.DIALOGUE })
        assertTrue(PersonaLanguage.CLINICAL_TERMS.all { it.exemption == ClinicalExemption.MEDICAL_UNCERTAIN_NARRATION })
        val terms = PersonaLanguage.CLINICAL_TERMS.map { it.phrase }
        listOf("Big-Five", "大五人格", "量表", "读心术").forEach { assertFalse(it in terms) }
        assertTrue("人格量表" in terms && "心理量表" in terms)
    }

    @Test
    fun `34条临床词逐条叙述者红台词绿医疗不确定WARN`() {
        PersonaLanguage.CLINICAL_TERMS.forEach { rule ->
            val narration = PersonaLanguage.clinical("他是${rule.phrase}。")
            assertEquals("叙述者 ${rule.phrase}", listOf("DIAGNOSIS"), narration.map { it.code })
            val dialogue = PersonaLanguage.clinical("他说：「他是${rule.phrase}。」", ClinicalPosition.DIALOGUE)
            assertTrue("台词 ${rule.phrase}", dialogue.isEmpty())
            val medical = PersonaLanguage.clinical(
                "这位医生可能是${rule.phrase}。",
                subjectRole = ClinicalSubjectRole.DOCTOR,
            )
            assertEquals("医疗 ${rule.phrase}", listOf("CLINICAL_MEDICAL_UNCERTAIN"), medical.map { it.code })
        }
    }

    @Test
    fun `位置只按引号与直接引语且同句医疗不确定才WARN`() {
        assertTrue(PersonaLanguage.clinical("他说：“他是NPD”。").isEmpty())
        assertTrue(PersonaLanguage.clinical("她说：他是PTSD。").isEmpty())
        assertEquals(listOf("DIAGNOSIS"), PersonaLanguage.clinical("他说：“他是NPD").map { it.code })
        val warning = PersonaLanguage.clinical("这位医生可能是NPD。", subjectRole = ClinicalSubjectRole.DOCTOR)
        assertEquals(listOf("CLINICAL_MEDICAL_UNCERTAIN"), warning.map { it.code })
        assertEquals(Severity.WARNING, warning.single().severity)
        val crossSentence = PersonaLanguage.clinical(
            "这位医生是NPD。他可能累了。",
            subjectRole = ClinicalSubjectRole.DOCTOR,
        )
        assertEquals(listOf("DIAGNOSIS"), crossSentence.map { it.code })
        val nonMedical = PersonaLanguage.clinical("他可能是NPD。", subjectRole = ClinicalSubjectRole.NONE)
        assertEquals(listOf("DIAGNOSIS"), nonMedical.map { it.code })
        val mixed = PersonaLanguage.clinical(
            "他是NPD。这位医生可能是PTSD。",
            subjectRole = ClinicalSubjectRole.DOCTOR,
        )
        assertEquals(listOf("DIAGNOSIS"), mixed.map { it.code })
    }

    @Test
    fun `同形词负例不命中且不做运行时联想`() {
        listOf("他依赖我", "回避（动词）", "边缘", "PNPD", "attachments").forEach { text ->
            assertTrue(text, PersonaLanguage.clinical(text).isEmpty())
        }
    }

    @Test
    fun `污名词台词WARN叙述者红且普通情绪词不入表`() {
        assertEquals(
            setOf("直男癌", "舔狗", "精神病", "神经病", "娘炮", "废物", "太监"),
            PersonaLanguage.STIGMA_TERMS.toSet(),
        )
        listOf("恶心", "活该", "该死", "变态").forEach { assertFalse(it in PersonaLanguage.STIGMA_TERMS) }
        val dialogue = PersonaLanguage.stigma("你这个舔狗", ClinicalPosition.DIALOGUE)
        assertEquals(listOf("STIGMA_DIALOGUE"), dialogue.map { it.code })
        assertEquals(Severity.WARNING, dialogue.single().severity)
        val narration = PersonaLanguage.stigma("他是个舔狗")
        assertEquals(listOf("STIGMA_NARRATOR"), narration.map { it.code })
        assertEquals(Severity.ERROR, narration.single().severity)
    }

    @Test
    fun `二次元标签只收surface且非surface命中红`() {
        assertTrue(PersonaLanguage.surfaceTags(listOf("傲娇")).isEmpty())
        assertEquals(
            "ANIME_TAG_UNKNOWN",
            PersonaLanguage.surfaceTags(listOf("未知标签")).single().code,
        )
        assertEquals(
            "ANIME_TAG_OFF_SURFACE",
            PersonaLanguage.offSurfaceTags(listOf("她很傲娇")).single().code,
        )
        assertEquals(
            "DETERMINISM_FORBIDDEN",
            PersonaLanguage.determinism("人格决定命运").single().code,
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.Persona930LanguageTest'
```

Expected: `PersonaLanguage` 及枚举未解析。

- [ ] **Step 3: 实现静态表**

```kotlin
package com.example.zhengdao.baopu.state.persona

internal enum class ClinicalCategory(val label: String) {
    PERSONALITY_DISORDER("人格障碍"),
    ATTACHMENT_TYPE("依恋类型"),
    PSYCHIATRIC_DIAGNOSIS("精神科诊断"),
    PSEUDO_PSYCH_LABEL("伪心理标签"),
}

internal enum class ClinicalAllowedPosition { DIALOGUE }
internal enum class ClinicalExemption { MEDICAL_UNCERTAIN_NARRATION }
internal enum class ClinicalPosition { AUTO, DIALOGUE, NARRATOR }
internal enum class ClinicalSubjectRole { NONE, DOCTOR, MENTAL_HEALTH_PROFESSIONAL }

internal data class ClinicalTermRule(
    val phrase: String,
    val category: ClinicalCategory,
    val allowedPosition: ClinicalAllowedPosition,
    val exemption: ClinicalExemption,
)

internal object PersonaLanguage {
    val STIGMA_TERMS = listOf("直男癌", "舔狗", "精神病", "神经病", "娘炮", "废物", "太监")
    val SURFACE_TAGS = listOf("傲娇", "天然呆", "毒舌", "腹黑", "三无", "元气", "弱气", "大小姐")
    val CLINICAL_TERMS = listOf(
        rule("人格障碍", ClinicalCategory.PERSONALITY_DISORDER),
        rule("边缘型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("自恋型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("表演型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("偏执型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("分裂样人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("依赖型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("强迫型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("回避型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("分裂型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("依恋类型", ClinicalCategory.ATTACHMENT_TYPE),
        rule("安全依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("焦虑依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("回避依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("回避型依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("混乱依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("痴迷型依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("恐惧型依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("attachment", ClinicalCategory.ATTACHMENT_TYPE),
        rule("NPD", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("PTSD", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("BPD", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("抑郁症", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("双相障碍", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("精神分裂", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("多动症", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("创伤后应激障碍", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("人格量表", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("心理量表", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("心理测量", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("人格测验", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("MBTI", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("色彩性格", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("九型人格", ClinicalCategory.PSEUDO_PSYCH_LABEL),
    )

    private val UNCERTAINTY_TERMS = listOf("可能", "也许", "看起来像")
    private val DETERMINISM_TERMS = listOf("人格决定命运", "性格决定命运")
    private val QUOTED = Regex("\"([^\"]*)\"|「([^」]*)」|“([^”]*)”")
    private val DIRECT_SPEECH = Regex("(?:(?:他|她)?(?:说|问|答)|低声说)\\s*[：:]\\s*")
    private val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')

    private fun rule(phrase: String, category: ClinicalCategory) = ClinicalTermRule(
        phrase,
        category,
        ClinicalAllowedPosition.DIALOGUE,
        ClinicalExemption.MEDICAL_UNCERTAIN_NARRATION,
    )

    fun clinical(
        text: String,
        position: ClinicalPosition = ClinicalPosition.AUTO,
        subjectRole: ClinicalSubjectRole = ClinicalSubjectRole.NONE,
    ): List<CheckItem> {
        val findings = CLINICAL_TERMS.mapNotNull { rule ->
            val narratorHits = occurrences(text, rule.phrase)
                .filter { positionAt(text, it, position) == ClinicalPosition.NARRATOR }
            if (narratorHits.isEmpty()) return@mapNotNull null
            if (narratorHits.all { medicalUncertain(text, it, subjectRole) }) {
                CheckItem(Severity.WARNING, "CLINICAL_MEDICAL_UNCERTAIN", "医疗角色不确定引述：${rule.phrase}")
            } else {
                CheckItem(Severity.ERROR, "DIAGNOSIS", "禁心理诊断，命中：${rule.phrase}")
            }
        }
        return if (findings.any { it.severity == Severity.ERROR }) {
            findings.filter { it.severity == Severity.ERROR }
        } else {
            findings
        }
    }

    fun stigma(text: String, position: ClinicalPosition = ClinicalPosition.AUTO): List<CheckItem> =
        STIGMA_TERMS.mapNotNull { term ->
            val hits = occurrences(text, term)
            if (hits.isEmpty()) return@mapNotNull null
            if (hits.all { positionAt(text, it, position) == ClinicalPosition.DIALOGUE }) {
                CheckItem(Severity.WARNING, "STIGMA_DIALOGUE", "人物语言偏激：$term")
            } else {
                CheckItem(Severity.ERROR, "STIGMA_NARRATOR", "叙述者定型蔑称：$term")
            }
        }

    fun determinism(text: String): List<CheckItem> = DETERMINISM_TERMS
        .filter { it in text }
        .map { CheckItem(Severity.ERROR, "DETERMINISM_FORBIDDEN", "禁人格决定论：$it") }

    fun offSurfaceTags(texts: List<String>): List<CheckItem> = SURFACE_TAGS
        .filter { tag -> texts.any { tag in it } }
        .map { CheckItem(Severity.ERROR, "ANIME_TAG_OFF_SURFACE", "二次元标签只落 surface：$it") }

    fun surfaceTags(tags: List<String>): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        tags.filter { it !in SURFACE_TAGS }.forEach {
            out += CheckItem(Severity.ERROR, "ANIME_TAG_UNKNOWN", "未知 surface 标签：$it")
        }
        tags.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (tag, count) ->
            out += CheckItem(Severity.ERROR, "ANIME_TAG_DUP", "surface 标签重复：$tag×$count")
        }
        tags.forEach { out += clinical(it, ClinicalPosition.NARRATOR) + stigma(it, ClinicalPosition.NARRATOR) }
        return out
    }

    private fun occurrences(text: String, phrase: String): List<Int> {
        val source = text.lowercase()
        val needle = phrase.lowercase()
        val out = mutableListOf<Int>()
        var from = 0
        while (from <= source.length - needle.length) {
            val hit = source.indexOf(needle, from)
            if (hit < 0) break
            if (exactBoundary(source, hit, hit + needle.length, phrase)) out += hit
            from = hit + needle.length
        }
        return out
    }

    private fun exactBoundary(text: String, start: Int, end: Int, phrase: String): Boolean {
        if (phrase.any { it.code > 127 }) return true
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        return before?.isLetterOrDigit() != true && after?.isLetterOrDigit() != true
    }

    private fun positionAt(text: String, index: Int, forced: ClinicalPosition): ClinicalPosition {
        if (forced != ClinicalPosition.AUTO) return forced
        val quoted = QUOTED.findAll(text).any { match ->
            val range = match.groups[1]?.range ?: match.groups[2]?.range ?: match.groups[3]?.range
            range != null && index in range
        }
        val introduced = DIRECT_SPEECH.findAll(text).any { match ->
            val start = match.range.last + 1
            val end = SENTENCE_BREAKS.mapNotNull { text.indexOf(it, start) }.minOrNull() ?: text.length
            index in start until end && completeIntroducedSpeech(text.substring(start, end))
        }
        return if (quoted || introduced) ClinicalPosition.DIALOGUE else ClinicalPosition.NARRATOR
    }

    private fun completeIntroducedSpeech(speech: String): Boolean = when {
        speech.startsWith("「") -> "」" in speech
        speech.startsWith("“") -> "”" in speech
        speech.startsWith("\"") -> "\"" in speech.substring(1)
        else -> true
    }

    private fun medicalUncertain(text: String, index: Int, role: ClinicalSubjectRole): Boolean =
        role != ClinicalSubjectRole.NONE && UNCERTAINTY_TERMS.any { it in sentenceAt(text, index) }

    private fun sentenceAt(text: String, index: Int): String {
        var start = 0
        var end = text.length
        SENTENCE_BREAKS.forEach { ch ->
            val before = text.lastIndexOf(ch, index - 1)
            if (before >= start) start = before + 1
            val after = text.indexOf(ch, index)
            if (after >= 0 && after < end) end = after
        }
        return text.substring(start, end)
    }
}
```

- [ ] **Step 4: 重跑目标测试**

重复 Step 2。Expected: 34 条三态、负例、污名词、标签全绿。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaLanguage.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930LanguageTest.kt
git commit -m "feat(930): 静态临床词三态与surface语言闸"
```

---

### Task 5: 接入 PersonaChecks 并更新 819 断言

**Files:**
- Modify: `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaChecks.kt:19-40,71-110`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/PersonaChecksTest.kt:90-101`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930LanguageTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `SurfaceState.tags`、Task 4 的 `PersonaLanguage`。
- Produces: `PersonaChecks.check` 对 core/voice/surface 的完整语言闸；保留 `DIAGNOSIS` 稳定红码。

- [ ] **Step 1: 写失败测试**

修改旧黑名单用例：

```kotlin
@Test
fun `判据_禁心理诊断依恋类型与人格量表一律红`() {
    val terms = listOf("依恋类型", "回避依恋", "attachment", "人格量表", "色彩性格", "MBTI")
    assertEquals(6, terms.size)
    terms.forEach { term ->
        val poisoned = PersonaFixture.core().copy(
            drives = PersonaFixture.core().drives.map { it.copy(coreFear = "怕$term") },
        )
        val r = PersonaChecks.check(PersonaFixture.persona().copy(core = poisoned))
        assertTrue("命中'$term'须红：$term", "DIAGNOSIS" in codes(r))
    }
}
```

增加集成测试：

```kotlin
@Test
fun `930_台词临床词放行台词污名词只WARN`() {
    val original = PersonaFixture.voiceA()
    val voice = original.copy(
        lines = listOf(
            VoiceLine(
                "他说：「你是NPD。」你这个舔狗。",
                LineFeatures(SentenceLength.LONG, DictionLevel.LITERARY, "叫全名", "说实在的"),
            ),
        ),
    )
    val report = PersonaChecks.check(PersonaFixture.persona().copy(voice = voice))
    assertFalse("DIAGNOSIS" in codes(report))
    assertTrue("STIGMA_DIALOGUE" in report.warnings.map { it.code })
}

@Test
fun `930_surface标签合法且core标签越界红`() {
    val legal = PersonaFixture.persona().copy(
        surface = PersonaFixture.persona().surface.copy(tags = listOf("傲娇")),
    )
    assertTrue(PersonaChecks.check(legal).passed)
    val poisoned = PersonaFixture.core().copy(
        drives = PersonaFixture.core().drives.map { it.copy(trigger = "她很傲娇") },
    )
    assertTrue("ANIME_TAG_OFF_SURFACE" in codes(PersonaChecks.check(PersonaFixture.persona().copy(core = poisoned))))
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.PersonaChecksTest'
```

Expected: 新增集成断言失败；旧 `FORBIDDEN_TERMS` 仍会扫描台词。

- [ ] **Step 3: 替换私有词表并接入语言闸**

删除 `PersonaChecks.FORBIDDEN_TERMS`。`check` 中把 `diagnosisChecks(p)` 替换为 `languageChecks(p)`。

```kotlin
private fun languageChecks(p: Persona): List<CheckItem> {
    val narrative = mutableListOf<String>()
    p.core.drives.forEach {
        narrative += it.coreFear
        narrative += it.trigger
    }
    val v = p.voice.dimensions
    narrative += listOfNotNull(v.address, v.catchphrase, v.modalParticle, v.pauseEllipsis, v.breakdown)
    val out = narrative.flatMap {
        PersonaLanguage.clinical(it) +
            PersonaLanguage.stigma(it) +
            PersonaLanguage.determinism(it) +
            PersonaLanguage.offSurfaceTags(listOf(it))
    }.toMutableList()
    p.voice.lines.forEach { line ->
        out += PersonaLanguage.clinical(line.text, ClinicalPosition.DIALOGUE)
        out += PersonaLanguage.stigma(line.text, ClinicalPosition.DIALOGUE)
        out += PersonaLanguage.determinism(line.text)
    }
    return out
}
```

`surfaceChecks` 末尾增加：

```kotlin
out += PersonaLanguage.surfaceTags(surface.tags)
```

- [ ] **Step 4: 重跑目标测试**

重复 Step 2。Expected: 13 个既有测试与新增集成测试全绿。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaChecks.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/PersonaChecksTest.kt
git commit -m "feat(930): 819总检接入三态语言闸并留痕"
```

---

### Task 6: 927 三根变动与内部矛盾

**Files:**
- Create: `app/src/main/java/com/example/zhengdao/baopu/state/persona/TraitChanges.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt`

**Interfaces:**
- Consumes: `TraitLayer`、`AxisValue`、`PersonaLanguage`、`CheckReport`。
- Produces: `TraitChangeEvidence`、`TraitChanges.changedAxes`、`TraitChanges.review`、`TraitContradiction`、`TraitContradictionChecks.check`。

- [ ] **Step 1: 写失败测试**

夹具增加：

```kotlin
fun contradictions(): List<TraitContradiction> = listOf(
    TraitContradiction(
        highAxis = TraitLayer.AGREEABLENESS,
        lowAxis = TraitLayer.CONSCIENTIOUSNESS,
        note = "愿意体谅别人，但自己的承诺常常没跟上",
    ),
)
```

测试增加：

```kotlin
@Test
fun `927单事件改特质红latent与longterm可带事件`() {
    val before = PersonaFixture.trait()
    val after = before.copy(agreeableness = TraitLayer.explicit(3))
    val eventOnly = TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "e1"))
    assertEquals(listOf("agreeableness"), TraitChanges.changedAxes(before, after))
    assertTrue("TRAIT_CHANGE_SINGLE_EVENT" in eventOnly.errors.map { it.code })
    val latent = TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "e1", latentRef = "l1"))
    assertTrue(latent.passed)
    val longterm = TraitChanges.review(before, after, TraitChangeEvidence(longtermRef = "t1"))
    assertTrue(longterm.passed)
    assertEquals(latent.line(), TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "e1", latentRef = "l1")).line())
}

@Test
fun `927无根红无变化提示`() {
    val trait = PersonaFixture.trait()
    val noRoot = TraitChanges.review(trait, trait.copy(humor = Humor.WARM), TraitChangeEvidence())
    assertTrue("TRAIT_CHANGE_NO_ROOT" in noRoot.errors.map { it.code })
    val none = TraitChanges.review(trait, trait, TraitChangeEvidence(longtermRef = "t1"))
    assertTrue("TRAIT_CHANGE_NONE" in none.warnings.map { it.code })
}

@Test
fun `内部矛盾高宜人低尽责有实际读数`() {
    val trait = PersonaFixture.trait().copy(
        agreeableness = TraitLayer.explicit(3),
        conscientiousness = TraitLayer.explicit(0),
    )
    val report = TraitContradictionChecks.check(trait, Persona930Fixture.contradictions())
    assertTrue(report.isEmpty())
    assertEquals(3, trait.bigFiveTier(TraitLayer.AGREEABLENESS))
    assertEquals(0, trait.bigFiveTier(TraitLayer.CONSCIENTIOUSNESS))
    val missing = TraitContradictionChecks.check(trait, emptyList())
    assertEquals("TRAIT_CONTRADICTION_MISSING", missing.single().code)
    assertTrue(missing.single().message.contains("可能是人设不是人格"))
    val invalidCases = listOf(
        trait.copy(agreeableness = TraitLayer.explicit(1)) to Persona930Fixture.contradictions(),
        trait to listOf(TraitContradiction(TraitLayer.AGREEABLENESS, TraitLayer.AGREEABLENESS, "同轴")),
        trait to listOf(TraitContradiction("unknown", TraitLayer.CONSCIENTIOUSNESS, "未知轴")),
        trait to listOf(TraitContradiction(TraitLayer.AGREEABLENESS, TraitLayer.CONSCIENTIOUSNESS, "")),
    )
    invalidCases.forEach { (changed, items) ->
        assertTrue("TRAIT_CONTRADICTION_INVALID" in TraitContradictionChecks.check(changed, items).map { it.code })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.Persona930ModelTest'
```

Expected: `TraitChangeEvidence`、`TraitChanges`、`TraitContradiction` 未解析。

- [ ] **Step 3: 实现变动与矛盾判据**

```kotlin
package com.example.zhengdao.baopu.state.persona

internal data class TraitChangeEvidence(
    val eventRef: String? = null,
    val latentRef: String? = null,
    val longtermRef: String? = null,
)

internal data class TraitContradiction(val highAxis: String, val lowAxis: String, val note: String)

internal object TraitChanges {
    fun review(before: TraitLayer, after: TraitLayer, evidence: TraitChangeEvidence): CheckReport {
        val changed = changedAxes(before, after)
        if (changed.isEmpty()) {
            return CheckReport(listOf(CheckItem(Severity.WARNING, "TRAIT_CHANGE_NONE", "前后特质无变化")))
        }
        val hasLatent = !evidence.latentRef.isNullOrBlank()
        val hasLongterm = !evidence.longtermRef.isNullOrBlank()
        val hasEvent = !evidence.eventRef.isNullOrBlank()
        val code = when {
            !hasEvent && !hasLatent && !hasLongterm -> "TRAIT_CHANGE_NO_ROOT"
            hasEvent && !hasLatent && !hasLongterm -> "TRAIT_CHANGE_SINGLE_EVENT"
            else -> null
        }
        if (code == null) return CheckReport(emptyList())
        return CheckReport(
            listOf(CheckItem(Severity.ERROR, code, "特质变动须慢变根；变化轴=${changed.joinToString("/")}")),
        )
    }

    fun changedAxes(before: TraitLayer, after: TraitLayer): List<String> {
        val old = listOf(
            "sociability" to (before.sociability to after.sociability),
            "directness" to (before.directness to after.directness),
            "stance" to (before.stance to after.stance),
            "orientation" to (before.orientation to after.orientation),
            "selfControl" to (before.selfControl to after.selfControl),
            "aggressiveness" to (before.aggressiveness to after.aggressiveness),
            "humor" to (before.humor to after.humor),
        )
        val five = TraitLayer.BIG_FIVE_AXES.map { axis ->
            axis to (before.bigFiveTier(axis) to after.bigFiveTier(axis))
        }
        return (old + five).filter { (_, pair) -> pair.first != pair.second }.map { (axis, _) -> axis }
    }
}

internal object TraitContradictionChecks {
    fun check(trait: TraitLayer, contradictions: List<TraitContradiction>): List<CheckItem> {
        if (contradictions.isEmpty()) {
            return listOf(
                CheckItem(Severity.WARNING, "TRAIT_CONTRADICTION_MISSING", "无内部矛盾：可能是人设不是人格"),
            )
        }
        return contradictions.flatMapIndexed { index, item ->
            val high = trait.bigFiveTier(item.highAxis)
            val low = trait.bigFiveTier(item.lowAxis)
            val valid = item.highAxis in TraitLayer.BIG_FIVE_AXES &&
                item.lowAxis in TraitLayer.BIG_FIVE_AXES &&
                item.highAxis != item.lowAxis &&
                high != null && low != null && high >= 2 && low <= 1 &&
                item.note.isNotBlank()
            val errors = if (valid) emptyList() else listOf(
                CheckItem(Severity.ERROR, "TRAIT_CONTRADICTION_INVALID", "矛盾#$index 不满足高档>=2/低档<=1：${item.highAxis}/${item.lowAxis}"),
            )
            errors + PersonaLanguage.clinical(item.note) + PersonaLanguage.stigma(item.note) +
                PersonaLanguage.determinism(item.note) + PersonaLanguage.offSurfaceTags(listOf(item.note))
        }
    }
}
```

- [ ] **Step 4: 重跑目标测试**

重复 Step 2。Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/zhengdao/baopu/state/persona/TraitChanges.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt
git commit -m "feat(930): 927三根变动与内部矛盾判据"
```

---

### Task 7: 内部增强聚合与完整总检

**Files:**
- Create: `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaEnhancement.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt`
- Modify: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt`

**Interfaces:**
- Consumes: `Persona`、`PersonaValues`、`PersonaMoodState`、`TraitContradiction`、全部 checks。
- Produces: `PersonaEnhancement`、`PersonaEnhancementChecks.check`。

- [ ] **Step 1: 写失败测试**

夹具增加：

```kotlin
fun enhancement(): PersonaEnhancement = PersonaEnhancement(
    base = PersonaFixture.persona().copy(
        trait = PersonaFixture.trait().copy(
            agreeableness = TraitLayer.explicit(3),
            conscientiousness = TraitLayer.explicit(0),
        ),
        surface = PersonaFixture.persona().surface.copy(tags = listOf("傲娇")),
    ),
    values = values(),
    state = state(),
    contradictions = contradictions(),
)
```

测试增加：

```kotlin
@Test
fun `930聚合总检合法且同输入逐字一致`() {
    val a = PersonaEnhancementChecks.check(Persona930Fixture.enhancement())
    val b = PersonaEnhancementChecks.check(Persona930Fixture.enhancement())
    assertTrue(a.line(), a.passed)
    assertEquals(a, b)
    assertEquals(a.line(), b.line())
}

@Test
fun `930动态说明临床词按台词与叙述者三态接入`() {
    val dialogue = Persona930Fixture.enhancement().copy(
        state = Persona930Fixture.state().copy(note = "他说：「我是NPD。」"),
    )
    assertFalse("DIAGNOSIS" in PersonaEnhancementChecks.check(dialogue).errors.map { it.code })
    val narration = Persona930Fixture.enhancement().copy(
        state = Persona930Fixture.state().copy(note = "他是NPD。"),
    )
    assertTrue("DIAGNOSIS" in PersonaEnhancementChecks.check(narration).errors.map { it.code })
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.Persona930ModelTest'
```

Expected: `PersonaEnhancement` 未解析。

- [ ] **Step 3: 实现聚合**

```kotlin
package com.example.zhengdao.baopu.state.persona

internal data class PersonaEnhancement(
    val base: Persona,
    val values: PersonaValues,
    val state: PersonaMoodState,
    val contradictions: List<TraitContradiction>,
)

internal object PersonaEnhancementChecks {
    fun check(enhancement: PersonaEnhancement): CheckReport {
        val items = PersonaChecks.check(enhancement.base).items.toMutableList()
        items += PersonaValueChecks.check(enhancement.values, enhancement.base.core)
        items += PersonaStateChecks.check(enhancement.state)
        items += TraitContradictionChecks.check(enhancement.base.trait, enhancement.contradictions)
        enhancement.state.note?.let { note ->
            items += PersonaLanguage.clinical(note)
            items += PersonaLanguage.stigma(note)
            items += PersonaLanguage.determinism(note)
            items += PersonaLanguage.offSurfaceTags(listOf(note))
        }
        return CheckReport(items)
    }
}
```

- [ ] **Step 4: 重跑目标测试**

重复 Step 2。Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaEnhancement.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930Fixture.kt app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930ModelTest.kt
git commit -m "feat(930): 同包内部人格增强聚合"
```

---

### Task 8: 单一真相与禁止推导源码断言

**Files:**
- Create: `app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930SourceAssertTest.kt`

**Interfaces:**
- Consumes: `SrcRoots`、五个新生产文件、`PersonaModel.kt`、`PersonaChecks.kt`、`SurfaceDeriver.kt`。
- Produces: 无生产接口；提供六组源码闸。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.example.zhengdao.baopu.state.persona

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Persona930SourceAssertTest {
    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/persona/"
    private val newFiles = listOf(
        "PersonaValues.kt", "PersonaMoodState.kt", "PersonaLanguage.kt", "TraitChanges.kt", "PersonaEnhancement.kt",
    )

    @Test
    fun `全仓核心人格类型各声明一次且无平行personality包`() {
        val files = productionFiles()
        listOf("CoreLayer", "TraitLayer", "SurfaceState", "PersonaValues", "PersonaMoodState").forEach { name ->
            val count = files.count { Regex("\\b(?:data\\s+)?class\\s+$name\\b").containsMatchIn(stripComments(it.readText())) }
            assertEquals(name, 1, count)
        }
        assertFalse(files.any { it.path.replace('\\', '/').contains("/state/personality/") })
        assertFalse(files.any { "package com.example.zhengdao.baopu.state.personality" in it.readText() })
        newFiles.forEach { name -> assertFalse("$name 不得公开", code(name).contains("public ")) }
    }

    @Test
    fun `states不引用稳定层trait变动不引用states`() {
        val mood = code("PersonaMoodState.kt")
        listOf("TraitLayer", "CoreLayer", "SurfaceState", "SurfaceDeriver", "derive", "infer", "predict").forEach {
            assertFalse("mood 不得含 $it", mood.contains(it))
        }
        val changes = code("TraitChanges.kt")
        listOf("PersonaMoodState", "MoodTier", "Emotion", "pressure").forEach {
            assertFalse("trait 变动不得含 $it", changes.contains(it))
        }
    }

    @Test
    fun `values不由职业关系身体锚点推导`() {
        val values = code("PersonaValues.kt")
        listOf("Occupation", "Relationship", "RelationKind", "BodyAxis", "Anchor", "personaRate", "state.relation", "intake.peer", "derive", "infer").forEach {
            assertFalse("values 不得含 $it", values.contains(it))
        }
    }

    @Test
    fun `SurfaceDeriver唯一且930五件无表达推导函数`() {
        val personaFiles = SrcRoots.find(dir + "PersonaModel.kt").parentFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()
        val count = personaFiles.count {
            Regex("\\bobject\\s+SurfaceDeriver\\b").containsMatchIn(stripComments(it.readText()))
        }
        assertEquals(1, count)
        newFiles.forEach { name ->
            val text = code(name)
            assertFalse(Regex("\\bfun\\s+derive\\b").containsMatchIn(text))
            assertFalse(Regex("\\b(?:object|class)\\s+\\w*Deriver\\b").containsMatchIn(text))
        }
    }

    @Test
    fun `语言表静态且标签字段只落SurfaceState`() {
        val language = code("PersonaLanguage.kt")
        listOf("synonym", "vector", "embedding", "semantic", "同义", "向量").forEach {
            assertFalse("语言闸不得运行时联想 $it", language.contains(it))
        }
        val personaFiles = SrcRoots.find(dir + "PersonaModel.kt").parentFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()
        val tagFields = personaFiles.count { Regex("\\bval\\s+tags\\s*:").containsMatchIn(stripComments(it.readText())) }
        assertEquals(1, tagFields)
        assertTrue(code("PersonaModel.kt").contains("data class SurfaceState"))
    }

    @Test
    fun `五个新文件零IO随机时钟且不碰747710921`() {
        val text = newFiles.joinToString("\n") { code(it) }
        listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "writeText", "state.anchor", "state.relation", "intake.peer", "personaRate").forEach {
            assertFalse("新层不得含 $it", text.contains(it))
        }
    }

    private fun code(name: String): String = stripComments(SrcRoots.find(dir + name).readText())

    private fun productionFiles(): List<File> {
        val modules = listOf("app", "core", "native-core", "policy", "macrobenchmark")
        val roots = modules.flatMap { module ->
            listOf(File("$module/src/main/java"), File("../$module/src/main/java"))
        }
        return roots.map { it.absoluteFile }
            .filter { it.isDirectory }
            .distinct()
            .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" } }
            .distinctBy { it.absolutePath }
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '"' -> i = copyString(src, i, out)
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") { depth++; out.append("  "); i += 2 }
            else if (two == "*/") { depth--; out.append("  "); i += 2 }
            else { if (src[i] != '\n') out.append(' ') else out.append('\n'); i++ }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
            if (src[i] == '"') { i++; break }
            i++
        }
        return i
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.Persona930SourceAssertTest'
```

Expected: 首轮可能直接通过；若失败，必须按断言定位真实越界，不得删断言。

- [ ] **Step 3: 运行全部 persona 目标测试**

```bash
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:testDebugUnitTest --tests com.example.zhengdao.baopu.state.persona.*'
```

Expected: 819 与 930 所有 persona 测试全绿。

- [ ] **Step 4: 提交**

```bash
git add app/src/test/java/com/example/zhengdao/baopu/state/persona/Persona930SourceAssertTest.kt
git commit -m "test(930): 单一真相与禁止推导源码断言"
```

---

### Task 9: 全量四枪、819 兼容取证与交付

**Files:**
- Modify: none unless a gate finds a real defect.
- Create outside repo: `C:\Users\guoli\AndroidStudioProjects\930-人格维度-交付-2026-11-26.md`
- Create outside repo: `C:\Users\guoli\AndroidStudioProjects\930-人格维度-取证/`

**Interfaces:**
- Consumes: 最终源码 tip、全部测试、detekt/lint XML、水位脚本、git diff。
- Produces: 最终提交 SHA、四枪读数、bundle、交付报告。

- [ ] **Step 1: 确认最终源码 tip 干净且 SurfaceDeriver 零 diff**

```bash
git status --short --branch
git rev-parse HEAD
sha256sum app/src/main/java/com/example/zhengdao/baopu/state/persona/SurfaceDeriver.kt
git diff --exit-code 390fed25561b90564841ae6f779a42c8b21272ed -- app/src/main/java/com/example/zhengdao/baopu/state/persona/SurfaceDeriver.kt
git diff --check
```

Expected: 工作树干净；SHA-256 与 Task 1 相同；`SurfaceDeriver.kt` 零 diff。若不同，停止并修复，不得写“兼容”。

- [ ] **Step 2: 跑 detekt + core/app 全量单测**

```bash
export JAVA_HOME='D:/Program Files/Android/Android Studio/jbr'
start=$(date +%s)
printf '%s\n' "$start" > build/930-gates-tests.start
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:detektWall :app:detektReport :core:testDebugUnitTest :app:testDebugUnitTest' > build/930-gates-tests.log 2>&1
rc=$?
printf 'GATES_TESTS_EXIT=%s\n' "$rc" >> build/930-gates-tests.log
```

Expected: Gradle 真跑且有 `BUILD SUCCESSFUL`；core/app JUnit XML 全部新生。不得接受旧 XML 或定向测试读数。

- [ ] **Step 3: 读取单测与 detekt 实际读数**

```bash
PYTHONIOENCODING=utf-8 python - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
roots = [Path('app/build/test-results/testDebugUnitTest'), Path('core/build/test-results/testDebugUnitTest')]
tests = failures = errors = skipped = files = 0
for root in roots:
    for p in root.glob('TEST-*.xml'):
        assert p.stat().st_mtime >= int(Path('build/930-gates-tests.start').read_text()), f'stale XML: {p}'
        r = ET.parse(p).getroot()
        files += 1
        tests += int(r.attrib.get('tests', 0))
        failures += int(r.attrib.get('failures', 0))
        errors += int(r.attrib.get('errors', 0))
        skipped += int(r.attrib.get('skipped', 0))
assert files > 0
print(f'files={files} tests={tests} failures={failures} errors={errors} skipped={skipped}')
wall_report = Path('app/build/reports/detekt/detekt-wall.xml')
assert wall_report.stat().st_mtime >= int(Path('build/930-gates-tests.start').read_text()), f'stale detekt XML: {wall_report}'
wall = ET.parse(wall_report).getroot()
print('detektWall=', sum(1 for _ in wall.iter('error')))
PY
PYTHONIOENCODING=utf-8 python ../_zd-toolroom/01-gates/check-detekt-budget.py
```

Expected: `failures=0 errors=0`；`detektWall<=189`；预算闸通过。

- [ ] **Step 4: 跑全量测试后的水位闸**

```bash
PYTHONIOENCODING=utf-8 python tools/check-test-temp.py --strict
```

Expected: 实读 `<=315`。不得修改 `tools/test-temp-budget.json`，不得用定向测试后的低水位代替。

- [ ] **Step 5: 跑 AGP lint 原始零错零警**

```bash
export JAVA_HOME='D:/Program Files/Android/Android Studio/jbr'
export GRADLE_OPTS='-Dorg.gradle.daemon.registry.base=C:/tmp/zd930-lint-daemons -Xmx2g'
start=$(date +%s)
printf '%s\n' "$start" > build/930-gates-lint.start
powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Repo 'C:\Users\guoli\AndroidStudioProjects\wt-930' -TimeoutMin 180 -Run ':app:lintDebug --offline --no-daemon --no-configuration-cache --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g' > build/930-gates-lint.log 2>&1
rc=$?
printf 'GATES_LINT_EXIT=%s\n' "$rc" >> build/930-gates-lint.log
PYTHONIOENCODING=utf-8 python - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
report = Path('app/build/reports/lint-results-debug.xml')
assert report.stat().st_mtime >= int(Path('build/930-gates-lint.start').read_text()), f'stale lint XML: {report}'
root = ET.parse(report).getroot()
counts = {}
for issue in root.iter('issue'):
    sev = issue.attrib.get('severity', 'Unknown')
    counts[sev] = counts.get(sev, 0) + 1
print('LINT_RAW', counts)
assert counts.get('Error', 0) == 0 and counts.get('Fatal', 0) == 0 and counts.get('Warning', 0) == 0
PY
PYTHONIOENCODING=utf-8 python ../_zd-toolroom/01-gates/check-lint-budget.py
```

Expected: lint 日志有 `BUILD SUCCESSFUL`；XML 为新鲜 mtime；`LINT_RAW Error=0 Fatal=0 Warning=0`；预算闸通过。

- [ ] **Step 6: 采集 819 兼容与单一真相证据**

```bash
git diff --exit-code 390fed25561b90564841ae6f779a42c8b21272ed -- app/src/main/java/com/example/zhengdao/baopu/state/persona/SurfaceDeriver.kt
git grep -n 'object SurfaceDeriver' 390fed255 -- app/src/main/java/com/example/zhengdao/baopu/state/persona
git grep -n 'object SurfaceDeriver' -- app/src/main/java/com/example/zhengdao/baopu/state/persona
git grep -nE '(data )?class (CoreLayer|TraitLayer|SurfaceState|PersonaValues|PersonaMoodState)' -- app/src/main/java core/src/main/java
git grep -n 'val tags' -- app/src/main/java/com/example/zhengdao/baopu/state/persona
git diff 390fed25561b90564841ae6f779a42c8b21272ed -- app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaModel.kt app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaChecks.kt
```

Expected: 唯一 `SurfaceDeriver`；五个核心类型各一次；`val tags` 只在 `SurfaceState`；`CoreLayer` 声明零 diff。报告贴 819 七轴→十二轴、tags 默认空、旧黑名单用例更新前后。

- [ ] **Step 7: 归档证据并生成报告**

```bash
mkdir -p ../930-人格维度-取证/门禁
cp -p build/preflight-930-r2.log ../930-人格维度-取证/门禁/
cp -p build/930-gates-tests.log ../930-人格维度-取证/门禁/
cp -p build/930-gates-lint.log ../930-人格维度-取证/门禁/
cp -p app/build/reports/detekt/detekt-wall.xml ../930-人格维度-取证/门禁/
cp -p app/build/reports/detekt/detekt.xml ../930-人格维度-取证/门禁/
cp -p app/build/reports/lint-results-debug.xml ../930-人格维度-取证/门禁/
```

用实际 git tip 与提交标题生成报告第一行，不手填：

```bash
tip=$(git rev-parse HEAD)
subject=$(git log -1 --pretty=%s)
printf 'DONE · %.9s · %s\n' "$tip" "$subject" > '../930-人格维度-交付-2026-11-26.md'
```

报告必填：

1. 四层字段表与五个 internal 文件清单；
2. values 八轴完整、缺/重/越界红、values/core WARN 夹具实际消息；
3. 34 条临床词逐条三态表：叙述者 `DIAGNOSIS`、台词绿、医疗不确定 `CLINICAL_MEDICAL_UNCERTAIN`；
4. 污名词七条台词 WARN/叙述者红，普通情绪词不在表；
5. 内部矛盾实际 `agreeableness=3(EXPLICIT)`、`conscientiousness=0(EXPLICIT)` 与检查结果；
6. 819 断言更新前后：七轴→十二轴、默认 `1/DEFAULT`、tags 默认空、旧“大五人格”用例改“色彩性格”；
7. core 声明零 diff、`SurfaceDeriver.kt` 零 diff、唯一声明 grep、三组 surface 逐字节串；
8. 单一真相 grep、六组源码断言、preflight 5 组关键词逐条“已核，非重复”；
9. detekt、lint 原始/预算、core/app 全量单测、水位实测与日志路径；
10. 本地分支未推远端证明。

- [ ] **Step 8: 生成并验证本地 bundle**

```bash
tip=$(git rev-parse HEAD)
git bundle create "../930-personality-${tip:0:8}.bundle" 390fed25561b90564841ae6f779a42c8b21272ed..feat/personality
git bundle verify "../930-personality-${tip:0:8}.bundle"
git branch -vv
git status --short --branch
```

Expected: bundle 验证通过；`git branch -vv` 的 `feat/personality` 行没有 `[origin/...]`；不执行 `git push`。

- [ ] **Step 9: 若任一枪红的修复规则**

- 在最终 tip 上修复真实问题，创建新提交；
- 新提交使旧四枪读数失效，detekt/core/app/lint/水位必须全部在新 tip 重跑；
- 不得拼接旧 tip 的 detekt 与新 tip 的 lint；
- 不得用 `@Suppress`、抬高预算、删断言或修改 `SurfaceDeriver.kt` 制造绿色。
