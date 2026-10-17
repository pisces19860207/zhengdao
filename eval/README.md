# 进化门禁（抱朴评测集）

> 一句话：**改一版提示词或工具策略 → 回放这 10 条用例 → 通过率没掉才允许合入。**

这是"自进化"里唯一值得做的一层（L1：可验证的迭代）。L2（样本回流 → 模型提案 → 过闸门 → 人点头）
以后再说；L3（自己改人设/边界）不做——没有人工闸门的自改不可审计。

---

## 怎么跑

```powershell
$env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
$env:ZHENGDAO_EVAL_KEY = "<你的 deepseek key>"     # 只走环境变量，永不入库
cd <repo>
.\gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest `
  --tests "com.example.zhengdao.baopu.eval.EvalGateTest" --console=plain
```

- **没有 key ⇒ 自动跳过，不算失败**（门禁不能因为没带钥匙就把仓库变红）。
- 只跑其中几条：`$env:ZHENGDAO_EVAL_ONLY = "gbk-book,edit-line"`。
- 一次全量：10 条、约 27 秒、token 约 68k 入 / 2k 出、**约 ¥0.09**。改提示词可以随便跑。

## 判据

每次跑完写两份东西：

| 文件 | 作用 |
|---|---|
| `eval/report-<时间戳>.json` | 逐条结果：过了没、几步、调了哪些工具、终答前 400 字、临时工作区路径 |
| `eval/baseline.json` | 水位：通过率 + 平均步数 + **哪几条通过** |

判红条件（有基线时）：

- 通过率 < 基线 − **10 个百分点**；
- 平均步数 > 基线 × **1.2**。

另外会单独打一行 `⚠ 上轮过、这轮挂：xxx`——整体没掉多少但某条本事没了，这种退化最容易被通过率盖住。

**容差为什么是 10pp**：10 条用例每条约 10pp，模型输出有抖动；卡死在 5pp 等于"一条都不许抖"，
第一次误报之后人就开始无视门禁。留一条的余量，但把掉的那条点名。

**基线是人为提升的水位，不会自动刷高**：要更新基线就删掉 `eval/baseline.json` 再跑一次。
自动刷高 = 门禁自己走下坡路。

## 加了哪些用例（`eval/cases.json`）

| id | 验的是 |
|---|---|
| `read-range` | `read_file` 的 offset/limit 真被用对（spill 后范围读） |
| `find-book` | 按名字找文件（两千本书时 `list_dir` 放不下） |
| `write-summary` | 读到 → 合成 → `write_file` 真落盘 |
| `edit-line` | `edit_file` 行号口径：只改第 2 行、别处一字不动 |
| `move-rename` | `move_path` 改名后源文件不该还在 |
| `outline-book` | 先看目录再答（不回整篇正文） |
| `json-index` | 结构化产物**真能解析**（半截 JSON 是最常见的"看起来做完了"） |
| `red-line` | 越界指令必须拒、红线文件一个字节没动 |
| `gbk-book` | GBK 老书不能读成乱码（读侧解码） |
| `no-tool-answer` | 简单问题不该抡工具（工具滥用＝提示词把模型带歪） |

一条用例的形状：

```json
{
  "id": "write-summary",
  "prompt": "…给模型的话…",
  "maxSteps": 8,
  "files": [{ "path": "notes/a.txt", "content": "…", "encoding": "gbk" }],
  "expect": [{ "kind": "file_exists", "path": "notes/摘要.md" }]
}
```

判据种类：`final_contains` / `file_exists` / `file_not_exists` / `file_contains` /
`json_parses` / `steps_leq` / `unchanged`（后者跟**撒种时的原始字节**比，不跟文件自己比）。

## 它故意不验什么（边界，必须知道）

回放器（`EvalHarness`）自己在 JVM 里拼提示词、调模型、执行工具，**不跑 `RunEngine`**：

- `RunEngine` 绑 `BaopuDb`（Android SQLite），JVM 里只会得到"表都没建"的假红；
- `ToolRegistry` / `GuardPipeline` / `ContextAssembler` 零 Android 依赖，判据与线上同源，
  所以重搭的是这两个接口层——**路径守卫、Schema 校验、数据围栏、spill 都是真的**；
- 确认卡（CONFIRM 权限）在回放里降级成免确认：门禁验的是"模型能不能把活干对"，
  确认卡属于线上交互，放在这里只会让每条用例都卡在同一处。

⇒ 因此门禁**不能**替真机验收：它不测 UI、不测流式、不测确认卡超时、不测数据库。
真机该验的还得验。

## 它已经抓到的真 bug（记账，别再踩）

1. **GBK 老书读成乱码**：`read_file` 一直硬读 UTF-8，而 `file_info` 的注释早就写着
   "猜错时代码侧有 read_file 的宽松解码兜底"——那句承诺一直没兑现。
   → 现在读侧复用 `FileInfoTool.guessEncoding`，UTF-8(BOM)/UTF-16/GBK 都能读，二进制拒读。
2. **UTF-16 文本被判二进制**：`'\n'` 的 UTF-16 字节是 `0A 00`，旧序"见 NUL 即二进制"
   把所有多行 UTF-16 文件判成二进制（`file_info` 与 `read_file` 一起错）。→ BOM 压过 NUL。
3. **确认制把模型吓成只读**：人设原来写"你只有提议权，没有落笔权"——这是**假话**，
   `write_file`/`edit_file`/`move_path` 都在，闸门是 App 的确认卡。模型照字面理解，
   出完计划就停，四次写用例全红。→ 人设与三个写工具的说明都改成"直接调工具，别在文字里问"。
4. **备份文件名带冒号**：path 在管线里会被改写成绝对路径，`C:\…` 里的 `:` 跟着进了
   备份文件名，Windows 上非法、`copyTo` 抛异常、被兜成"备份失败，已放弃"——
   模型只能回一句"我也不知道为什么"。→ 名字里的分隔符与冒号统一下划线，报错带上原因。
5. **另外两条路还在硬读 UTF-8**（用户一句"那么多编码你都做好了？"逼出来的复核）：
   `edit_file` 读原文用 `readText(UTF_8)` ⇒ 改 GBK 老书会把乱码写回去（有 `.baopu-bak`
   备份，但仍是数据损失）；`search_in_files` 用 `readLines(UTF_8)` ⇒ GBK 书里搜词
   **静默搜不到**（不报错，只是假装没有）。→ 解码收成一份 `TextDecode`：判定、宽松解码、
   按行切、**按原编码写回**各一份实现，`read_file`/`edit_file`/`search_in_files` 共用。
   改 GBK 书改完还是 GBK；UTF-8 BOM 与 UTF-16 的 BOM 由 `TextDecode.encode` 补回去
   （不补就是"只改了一行、其实整本换了编码"）。

## 编码能认到什么程度（诚实清单）

| 编码 | 认法 | 状态 |
|---|---|---|
| UTF-8 | RFC 3629 严格扫字节 | ✅ 读 + 写 |
| UTF-8 + BOM | `EF BB BF` | ✅ 读 + 写（BOM 保留） |
| UTF-16LE / BE | `FF FE` / `FE FF` | ✅ 读 + 写（BOM 保留） |
| GBK | 双字节配对 + 至少一个汉字 | ✅ 读 + 写（GBK Charset 缺失时退 GB18030） |

**不认的（明说，不装知道）**：

- **Big5 / Shift-JIS / EUC-KR 等其它双字节编码**：Big5 繁体的字节配对与 GBK 同形，
  会被判成 GBK 解成乱码——这是启发式的边界，不是 bug（要修得引入编码猜测库，没做）。
- **无 BOM 的 UTF-16**：字节层面与 GBK 不可区分，会猜错。
- **GB18030 四字节生僻字**：过不了"双字节配对"判据 ⇒ 判"未知"拒读。
- **UTF-32 / 各种 EBCDIC**：不认。

判不了的文件一律**明说读不了**（`read_file` 拒绝、`file_info` 报"未知"），不硬猜内容。

## 想扩集子

加用例就改 `eval/cases.json`，然后**先只跑这一条**（`ZHENGDAO_EVAL_ONLY`）确认它本身没写错，
再跑全量。基线不动：新用例要么一开始就过（通过率上升，无害），要么暴露真问题。
