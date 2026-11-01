# core 公共契约：值类 id、`<域>/<名>` 事件类型、严格的 JSONL 日志、分层的错误类型，Konsist 守包依赖

- 日期：2026-11-01
- 状态：采纳

## 背景

M1/M2 的主循环、OpenAI 系适配器、Anthropic 适配器、PolicyEngine、屏幕格式与工具、Android ScreenPort 会并行开发，它们共用 `:core` 的类型和接口（WP01）。这些契约一旦有人依赖就很难改，尤其是 session 日志：**已写下的日志必须永远能按原意读回**（phase1 §3.9 规则 4），而"模型可见 ⟺ 已记录"要求日志里的信息足够重建每次请求。

需要定下来的问题：可空性怎么用；id 长什么样；事件 type 怎么命名；错误分几层；多态类型怎么序列化；core 内部的包怎么划分、用什么工具守住依赖方向。

## 决定

### 1. 可空性

- `null` 只表示"没有 / 不知道 / 没做"，每个可空字段的 KDoc 写明 `null` 的具体含义。例：`ScreenSnapshot.ocr == null` 是没跑 OCR，`emptyList()` 是跑了没识别到；`Usage.reasoningTokens == null` 是 provider 没报。
- 集合和布尔值不可空：空集合就是"没有"，不用 `null` 表示空。
- 可选字段给默认值，只给大多数调用方不关心的字段。`Envelope.surfaceOp` 不可空，默认 `Append`（设计稿原为 `SurfaceOp? = null`，见下文"偏离"）。
- 互斥的几种状态用 sealed 类型，不用几个可空字段拼：`SurfaceAllocation`、`Verdict`、`ActionOutcome.Status`、`DecodedLine`。
- 字段之间的不变量写在 `init` 里 `require`（"`secret` ⇒ `value == null`"、"`interrupted` ⇔ `stopReason == null`"、"`answer` 存在 ⇔ `ANSWERED`"）。反序列化走同一个 `init`，所以坏数据在解码时就被拒绝。

### 2. id

- 跨边界的 id 都是 `@Serializable @JvmInline value class`，JSON 里是裸字符串，`toString()` 返回原值。
- 默认不透明：非空白、≤ 256 字符、无控制字符；core 不解析其内部结构，也不负责生成（主循环决定生成方式，测试里可注入）。
- 少数 id 有格式，因为它们会进文件路径或屏幕格式：
  - `SessionId`：一个文件名段 `[A-Za-z0-9._-]{1,128}`，不能是 `.` 或 `..`；
  - `BlobRef`：64 位小写 sha256 十六进制；
  - `ElementRef`：`[a-z]+[1-9][0-9]*`（屏幕格式里的 `b3`）；
  - `StoragePath`：相对路径，每段 `[A-Za-z0-9._-]{1,255}`。

### 3. 事件 type 命名

- 形如 `<域>/<名>`，全小写，单词间用 `-`（`[a-z]+(-[a-z]+)*/[a-z]+(-[a-z]+)*`），由各事件类的 `@SerialName` 给出。
- `SessionEventTypes` 是唯一登记处，同时登记每种类型的 `ignorable`。测试核对三者一致：登记表、`SessionEvent` 密封层次、样例。
- **`ignorable` 由类型决定，不由写入方决定。** 推导模型消息或回合结构要用到的类型不可忽略：
  - `turn/*`、`step/*`、`inbox/spliced`、`user/message`；
  - `request/header`、`assistant/message`；
  - `tool/call`、`tool/result`；
  - `compaction/summary`、`compaction/prune`。

  其余只用于轨迹、诊断和审计，可以忽略：
  - `assistant/attempt`、`request/retry`；
  - `policy/verdict`、`approval/*`、`question/*`、`handoff/*`；
  - `compaction/start|end`、`screen/*`、`session/resumed`。
- 名字和 `ignorable` 发布后不再改。改负载形状就升格式版本，并写迁移函数。

### 4. 错误分层

| 层 | 类型 | 谁处理 |
|---|---|---|
| 构造校验 | `IllegalArgumentException`（`require`） | 编程错误；边界处转换成本层错误（日志解码时成为 `SessionCorruptedException`，工具参数成为 `ToolStatus.INVALID_ARGS`） |
| 模型 | `sealed class ModelError : Exception`，带可序列化的 `kind: ModelErrorKind`，`retryable` 由 kind 决定 | 适配器只抛 `ModelError`；`CancellationException` 原样传播；日志里记 kind |
| 存储 | `IOException` 家族：`StoragePort` 的一切失败；`SessionLogException`（corrupted / unknown type / unsupported version / write / flush）；`BlobCorruptedException`；`ScreenUnavailableException` | 写或刷盘失败后 `SessionStore` 不再接受写入，调用方停止任务；重新 `open` 时修复截断的尾行 |
| 业务结局 | 不是异常：`ToolResult.status`、`Verdict`、`ActionOutcome` | 工具"现在做不了"用结果说明；`PolicyEngine.evaluate` 不抛异常 |

### 5. 多态序列化

- **嵌套的多态类型**都用 sealed 加 `@SerialName`（snake_case），判别字段是 kotlinx 默认的 `"type"`。不用 `SerializersModule` 开放多态，也不改 `classDiscriminator`。涉及的类型有：
  - `Part`、`Credential`、`ContextEditPolicy`；
  - `Target`、`UiAction`、`LaunchTarget`、`ActionOutcome.Status`、`SurfaceCapability`；
  - `Verdict`、`SurfaceOp`。
- **事件不走 kotlinx 多态。** `Envelope.type` 就是判别符，`data` 是不带 `type` 的负载对象，由 `SessionEventTypes` 按 type 选 serializer。这样遇到未知 type 时可以先看 `ignorable` 再决定跳过还是拒绝，并报出 seq。
- **Json 配置**：
  - Envelope 用 `encodeDefaults = false`：默认值 `ignorable=false`、`surfaceOp=Append`、`sources=[]` 不写出。
  - 文件头和负载用 `encodeDefaults = true`：负载完整写出，以后改了默认值也不会改变旧日志的含义。
  - 不认识的键一律拒绝。加字段就要升版本。
  - enum 按常量名写出，**常量名是格式的一部分**，不能改名。
- **格式版本**：
  - 第一行文件头 `{"format":"phoneagent.session","version":N,…}`；
  - `SessionMigration(fromVersion)` 在原始 envelope JSON 上把版本 v 迁到 v+1；迁移链必须连续到当前版本；
  - `core/src/test/resources/session-format/v1/session.jsonl` 永不改写。
- **代码里写 `serializer<X>()`，不写 `X.serializer()`**：detekt 做类型解析时不跑 serialization 插件，后者在它看来是未解析的引用。
- 不可序列化的类型（运行时对象，不进日志）：`Delta`、`Frame`、`agent` 包的类型、`PolicyContext`。

### 6. 包结构与依赖测试

- **契约包**及其层次：
  - 第 0 层：`core` 根包、`ids`；
  - 第 1 层：`model`、`ports`；
  - 第 2 层：`tool`；
  - 第 3 层：`policy`；
  - 第 4 层：`session`；
  - 第 5 层：`agent`。

  契约包只引用比自己低的层，不引用能力包。
- **能力包**是其余所有包，分两种：
  - 契约包的子包，按前两段算作一个能力：`model.openai`、`model.anthropic`、`policy.rules`；
  - 其他顶层包：`storage`、`screen`、`tools`。

  能力包只引用契约包和自己，能力之间互不引用，由 app 负责组装。
- 规则写在 `core/src/test/kotlin/dev/phoneagent/core/architecture/PackageDependencyTest.kt`，用 **Konsist** 检查 import（含通配符）和代码里的全限定类名（注释和字符串不算）。`core/src/test/resources/architecture/counterexample/` 里放了四个会被拒绝的反例：契约用了能力、能力互相引用、契约向上引用、全限定名引用。

### 7. 依赖与插件位置

- serialization 插件只加在 `core/build.gradle.kts`，不放进 `phoneagent.kotlin.jvm` 约定：目前只有 core 持久化 JSON。
- `kotlinx-serialization-json`、`kotlinx-coroutines-core` 是 core 的 `api` 依赖，因为契约里有 `JsonObject` 和 `StateFlow`。
- OkHttp 和 okhttp-sse 是 `implementation`：适配器会放在 `model.openai` / `model.anthropic`，HTTP 类型不出现在契约里。
- MockWebServer（mockwebserver3 + junit5）只用于测试。

### 8. 和设计稿不同的地方（已同步更新设计文档）

- `Envelope.surfaceOp` 不可空，默认 `Append`。
- blob 存为 `blobs/<sha256>`，没有扩展名，mime 记在事件里。
- `ToolDef` 放在 `core.model`：`ChatRequest` 要用它，而 `tool` 又依赖 `model`，放在 `tool` 会成环。
- `SurfaceManager.agentDisplay` 返回 `SurfaceAllocation`（`Ready` / `Unavailable(reason, detail)`），不返回 `Result<SurfaceRef>`。
- `Tool.concurrencySafe` 是抽象属性，没有默认值。
- 重试日志的 type 叫 `request/retry`，不叫 `llm/retry`。

## Alternatives considered

- **id 用裸 `String` 或 typealias**：编译器分不清 `TaskId` 和 `SessionId`，传错参数只能靠测试发现。
- **事件直接用 kotlinx 多态**（`SessionEvent` 序列化成 `{"type":…, 字段…}`）：
  - 未知 type 在多态解码时直接失败，没法按 `ignorable` 跳过；
  - envelope 字段和负载字段混在一个对象里，负载加字段可能撞名。
- **`ignoreUnknownKeys = true`**：旧代码读新日志时会静默丢字段，重建出的请求和当时发出的不同，违反"模型可见 ⟺ 已记录"。
- **负载也用 `encodeDefaults = false`**：行更短，但旧日志的含义会随代码里的默认值变化。
- **ArchUnit**：
  - 它检查字节码：Kotlin 的顶层函数在 `*Kt` 类里，内联函数和值类在字节码里形状不同，规则要绕着写；
  - 反例必须参与编译才能被检查，会混进测试类路径。
  - Konsist 直接读源码，反例可以作为普通资源文件放着，不参与编译。代价见"后果"。
- **serialization 插件放进约定插件**：所有 JVM 模块都会带上，眼下只有 core 需要。
- **保留设计稿的 `SurfaceOp? = null`**：`null` 和 `Append` 表示同一件事，同一条记录就有两种写法。
- **blob 文件名带 `.jpg`**：内容寻址不该依赖扩展名，而且 PNG / WebP 也会存进来。
- **`agentDisplay` 返回 `Result<SurfaceRef>`**：`Result` 里装的是任意 `Throwable`，UI 无法穷举"为什么不能用后台屏"。
- **`concurrencySafe` 默认 `false`**：新工具忘了声明也能编译，与"每个工具都要说明能不能并行"的要求冲突。

## 后果

- 加一个字段就要升格式版本、写迁移函数、提交新版本的 fixture。这是换取"旧日志永远按原意读"的代价。
- `SessionStore.open` 只接受当前版本的日志。旧版本日志只能经迁移用 `read` 读出，不能接着追加；需要原地升级时再加。
- Konsist 只看源码，有两处看不到：
  - 函数级的全限定引用（`dev.phoneagent.core.x.foo()`）；
  - 不按目录放置的 package 声明（这类文件按 `package` 声明归类）。

  需要更严时，改用编译后检查或加 detekt 规则。
- 以下情况要重新评估本 ADR：
  - 出现插件式的第三方工具或 provider，需要开放多态；
  - 日志体积成为问题，需要更紧凑的编码。
