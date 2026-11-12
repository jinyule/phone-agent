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
  - `ElementRef`：`[a-z]+[1-9][0-9]*`（屏幕格式里的 `e12`）；
  - `StoragePath`：相对路径，每段 `[A-Za-z0-9._-]{1,255}`。

### 3. 事件 type 命名

- 形如 `<域>/<名>`，全小写，单词间用 `-`（`[a-z]+(-[a-z]+)*/[a-z]+(-[a-z]+)*`），由各事件类的 `@SerialName` 给出。
- `SessionEventTypes` 是唯一登记处，同时登记每种类型的 `ignorable`。测试核对三者一致：登记表、`SessionEvent` 密封层次、样例。
- **`ignorable` 由类型决定，不由写入方决定。** 推导模型消息、回合结构、任务设置要用到的类型，以及不变量检查执行和运行规则要用到的类型，不可忽略：
  - `task/start`、`task/level-changed`、`task/stopped`、`session/resumed`；
  - `turn/*`、`step/*`、`inbox/spliced`、`user/message`；
  - `request/header`、`assistant/message`、`context/reasoning-dropped`；
  - `tool/call`、`tool/result`、`policy/verdict`、`tool/started`、`tool/executed`；
  - `compaction/summary`、`compaction/prune`。

  其余只用于轨迹、诊断、审计和回放，可以忽略：
  - `assistant/attempt`、`request/retry`、`side/request`、`side/response`；
  - `approval/*`、`question/*`、`handoff/*`、`prompt/withdrawn`；
  - `compaction/start|end`、`screen/*`。

  `policy/verdict` 和 `session/resumed` 在第 3 轮改为不可忽略（第 13 节）。
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
  - `Verdict`、`SurfaceOp`、`SideOutcome`。
- **事件不走 kotlinx 多态。** `Envelope.type` 就是判别符，`data` 是不带 `type` 的负载对象，由 `SessionEventTypes` 按 type 选 serializer。这样遇到未知 type 时可以先看 `ignorable` 再决定跳过还是拒绝，并报出 seq。
- **Json 配置**：
  - Envelope 用 `encodeDefaults = false`：默认值 `ignorable=false`、`surfaceOp=Append`、`sources=[]` 不写出。
  - 文件头和负载用 `encodeDefaults = true`：负载完整写出，以后改了默认值也不会改变旧日志的含义。
  - 不认识的键一律拒绝。加字段就要升版本。
  - enum 按常量名写出，**常量名是格式的一部分**，不能改名。所有落盘的 enum 常量名、sealed 判别名、事件 type 及其 `ignorable` 都列在 `core/src/test/resources/session-format/format-names.txt`，`FormatNamesTest` 逐项比对；core 里其余 `@Serializable` 的 enum / sealed 必须登记为"不落盘"。
- **格式版本**：
  - 第一行文件头 `{"format":"phoneagent.session","version":N,…}`；
  - `SessionMigration(fromVersion)` 在原始 envelope JSON 上把版本 v 迁到 v+1；迁移链必须连续到当前版本；
  - **v1 格式的发布点（冻结点）是第一个 M1 PR。** 在那之前，`core/src/test/resources/session-format/v1/session.jsonl` 允许重新生成，只用下一条的生成任务；从那之后永不改写。WP01 内的几次重新生成：第 9 节的补充要求一次，第 11、12、13 节各一次。WP01 合入前没有任何代码在测试之外写过 v1 日志。
  - fixture 只由 `./gradlew :core:generateSessionFormatFixture` 生成（约定插件 `phoneagent.session-format`），它拒绝写入已经存在的版本目录；测试只读 fixture，没有写回开关。
- **代码里写 `serializer<X>()`，不写 `X.serializer()`**：detekt 做类型解析时不跑 serialization 插件，后者在它看来是未解析的引用。
- 不可序列化的类型（运行时对象，不进日志）：`Delta`、`Frame`、`agent` 包的类型、`PolicyContext`、`ElementRisk`。

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

  能力包只引用契约包和自己，能力之间互不引用，由装配包 `assembly` 组装（2026-11-13 修订，见 ADR `2026-11-13-product-assembly`；原来写的是由 app 组装）。
- 规则写在 `core/src/test/kotlin/dev/phoneagent/core/architecture/PackageDependencyTest.kt`，用 **Konsist** 检查 import（含通配符），以及代码里的全限定类名和全限定函数调用。注释和字符串、字符字面量的文字不算，但字符串模板 `${…}` 里的代码照样检查（含原始字符串和模板里嵌套的字符串）。没有 package 声明的文件直接算违规。
- `core/src/test/resources/architecture/counterexample/` 里放了九个会被拒绝的反例：契约用了能力、契约向上引用、能力互相引用（import、全限定类名、全限定函数调用、引用词表的实现）、藏在字符串模板和原始字符串模板里的引用，以及没有 package 声明的文件。两个模板反例作为生产代码能编译通过。其中 `screen/MarksRisk.kt` 演示第 9 节的词表用法：引用契约 `policy.RiskLexicon` 是允许的，引用实现 `policy.rules` 会被拒绝。

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
- `ConfirmCard` 和 `Money` 放在 `core.ports`，不在 `core.policy`（见第 9 节）。
- `TaskRuntime.answer` 返回 `Boolean`；`UserReply` 是 `Approve` / `Reject` / `Answer` / `Decline` / `HandoffDone`。

### 9. 等待用户、工具控制信号、共用的风险词表（协调者补充要求）

- **`UserInteraction`**（`core.ports`）：`ask(Question): AskResult`、`confirm(ConfirmCard): ConfirmResult`、`handOff(kind, reason): HandoffResult`。三个方法都是 suspend，由运行时实现。`ask` 和 `handOff` 由工具和运行时（处理 `Verdict.HandOff` 时）调用；`confirm` 只由运行时在处理 `Verdict.Confirm` 时调用。工具经 `ToolContext.interaction` 拿到的是只有 `ask` 和 `handOff` 的 `ToolInteraction`（第 12 节），每次调用一个实例。`request_confirmation` 也走 PolicyEngine 得到 Confirm（addendum §5），确认 UI 和 `approval/*` 事件只有一条路径。
  - 每个方法有自己的结果类型，工具不会收到不可能的回复，`when` 可以穷举。
  - 用户回复：`Answered` / `Declined`、`APPROVED` / `REJECTED`、`DONE` / `DECLINED`。
  - 超过等待时限：`EXPIRED`；没人能回答（无人值守）：立即返回 `UNAVAILABLE`。
  - 任务停止：调用被取消（`CancellationException`），没有结果，日志记 `prompt/withdrawn`。
  - 日志直接记这些结果类型：`approval/decided.result`、`question/answered.result`、`handoff/end.result`。日志和工具用同一套词汇，不再有单独的 outcome 枚举。
  - 放在 `ports` 是因为它是 core 通向用户的端口，而 `ToolContext`（`tool`，第 2 层）必须能引用它和 `ConfirmCard`。`ConfirmCard` 的 `kind: IntentKind` 改成 `reason: ConfirmReason`：确认的理由除了意图类型，还有"本任务首次进入某 App"和"模型用 `request_confirmation` 主动申报"。
- **`ToolResult.control: ToolControl?`**（`core.tool`）：目前只有 `EndTurn(reason: EndReason, summary)`，只能出现在 `OK` 结果上。主循环只认这个信号，不认工具名；`end_not_done` 返回它，本 turn 以 `END_NOT_DONE` 结束。
- **`RiskLexicon`**（`core.policy`，`fun interface`）：`activationRisk(element, screen): ElementRisk?`，`ElementRisk.kind` 只能是 OUTWARD / SPEND / DELETE / SETTINGS。
  - 实现在 policy 能力包（WP05）；屏幕格式化器（`screen` 能力包）和 PolicyEngine 都只依赖这个契约，所以 ⚠ 标记和判定用的是同一份词表。
  - ⚠ 表示"高风险，可能触发确认"。是否真的弹出确认卡，还取决于安全级别和任务历史，格式化器不知道这些。

### 10. 契约补充 #1 的其余各项（协调者决定）

这些都是只给接口、实现在别处的端口。新增的类型都不进日志，所以 v1 格式不变。

- **`core.ports.AppCatalog`**：`apps(): List<AppEntry>`，`AppEntry(packageName, label, launchable)` 在构造时校验包名和非空白的 label。由 app 实现，除取消外不抛异常。`ToolContext.apps` 为 `null` 表示没有这个能力，处理方式和 `screen == null` 一样。
- **`core.ports.SnapshotAnalyzer`**：`fingerprint`、`quality`、`diff` 都是纯函数。`fingerprint` 也读 OCR 结果：树为空或稀疏时（游戏、画布），只看元素算出的指纹永远不变，每个动作都会被误判为 NO_CHANGE。算法只在 core（`core.screen`）里写一份，`ScreenPort` 的实现经构造参数注入。这样 Android 侧和 core 不会各算一套指纹。
- **`core.policy.IntentResolver`**：`resolve(call, risk, screen): ActionIntent`，纯函数、不抛异常，`risk` 是下限。
  - "这次调用会干什么"也在 policy 里判定，工具少报效果绕不过去。
  - 代价是 policy 和工具各解析一遍 phase1 §5 的参数，由装配时的集成测试防止两边走样。
- **`PolicyContext.approved`**：批准后复核时传入刚批准的卡，新算出的卡与它完全相同（全部字段）才放行，"一次同意只绑定这张卡"也只在 PolicyEngine 里判定。
  - KDoc 写明 `operatedPackages` 和 `outwardConfirmedPackages` 怎么从日志算出来；
  - `PolicyContext` 本身仍不序列化，但必须能从日志推导。
- **`core.model.CredentialResolver` / `BlobSource`**：构造适配器时注入。`ChatModel.complete` 的签名不变，密钥和 blob 字节都不进请求以外的地方。
- **`phone_intent` 的字段键名**：以 phase1 §5 的字段表为准（表由 WP06 维护），`LaunchTarget.SystemIntent` 的 KDoc 引用它；类型不变，仍是 `Map<String, String>`。

### 11. 修复清单第 1 轮（fix-list-r1，协调者评审）

这些改动都发生在 v1 发布之前，v1 fixture 重新生成了一次（第 5 节）。

- **线路快照 `WireProfile`**（B1）：`request/header.wire` 和 `ChatRequest.wire` 带上一切影响线路字节、但不属于中性内容的设置，字段见 `model-layer.md` §2。只记凭据的种类，不记密钥引用的解析值；baseUrl 这类不影响模型输入的不记。
  - 适配器只从 `request.wire` 读这些项，不读实时的 `ProviderProfile`。`credential` 种类或 `mappingVersion` 对不上时，发送前以 `InvalidRequest` 拒绝。
  - `mappingVersion` 是适配器映射规则的版本，和 session 格式版本互相独立：同样的输入会发出不同字节时就升。回放用同版本的规则重建字节。
  - 具体取值由 WP03 的规则决定；core 只固定字段、校验和 `toString` 不输出 header 值。还没有专门字段的设置先放进 `options`（小写名字，不放秘密）。
  - `ChatRequest.reasoning` 记钳制以后的有效档位（`ReasoningLevel.clampTo`），线路上的字段和值在 `wire.reasoningEffort`。
- **旁路请求**（B2）：`request/header` 去掉 `role`，它和 `assistant/message` 只属于主对话。看图、轻量请求记成可忽略的 `side/request`（role、provider、model、protocol、完整的 `ChatRequest`）和 `side/response`（`Completed(response)` 或 `Failed(kind, message, httpStatus, retryAfterMs)`）。`side/response` 的 sources 恰好是一条还没有回复的 `side/request`；`side/request` 的 sources 指向触发它的主对话位置。
- **任务设置进日志**（B3）：选了不可忽略的 `task/start(level, unattended)` 事件，不放进文件头。
  - 文件头不经过迁移链（`SessionMigration` 只迁 envelope），在头里加字段以后就没法再改；而级别变化本来就要一个事件（`task/level-changed`），初值和变化用同一种机制，推导也只读事件。
  - `SessionStore.create` 把文件头和 `task/start` 一次原子写入，读取时第 1 条记录必须是它（被跳过的未知记录也不能顶替），其他位置不能出现。
  - `PolicyContextSourcesTest` 为 `PolicyContext` 的每个属性列出日志来源，新增属性不补来源就失败。
- **去掉 thinking 重试**（B5）：选了不可忽略的事件 `context/reasoning-dropped(upToSeq)`，不给请求头加字段。决定发生在 `requestError`，按日志顺序记一次就够；放在头里，之后每个头都得重复它。推导规则：之后的请求丢掉 seq ≤ upToSeq 的 `Part.Reasoning`，文字、工具调用和工具结果不变，丢光 parts 的消息整条省略。
- **中断前缀的投影**（B6）：interrupted 的 `assistant/message` 是否进模型视图，看它后面先出现的是另一条 `assistant/message`（同一 step 的重试取代了它，只给 UI 看）还是 `user/message`（steer、或 stop / 最终失败之后的下一次输入，前缀跟着进视图）。现有的记录顺序足够判断，不加新字段；interrupted 的消息 parts 不能为空。规则写在 `SessionEvent` 的 KDoc 里，样例在测试源码的 `DeriveRuleSamples`，WP02 的推导测试逐条运行。
- **`ModelError.retryAfterMs`**（B7）：基类属性，默认 `null`；`RateLimited`、`Overloaded`、`Server` 可以带上服务端给的延迟。
- **跨记录不变量**（S4）：写入和读取都检查 `SessionInvariants`（surfaceOp 和类型匹配、每个调用 id 最多一条 `tool/call` 和一条 `tool/result`、上面几条 B2/B3/B5 的规则）。
- **追加与取消**（S2、S3）：等锁时可以取消，什么都不写；拿到锁以后先检查取消；开始写的记录一定写完并计入，即使调用方已被取消（方法返回记录，job 报取消，调用方以 `records()` 为准）。写入过程中的任何失败都让 store 进入失败状态。收尾代码在 `withContext(NonCancellable)` 里追加。
- **存储的持久性**（B4、S8）：新建目录后，从最深的新目录往上逐个 fsync 父目录，直到第一个原本存在的祖先；写入失败删除临时文件。（第 12 节改成每次都同步整条链。）`StoragePort.openAppender` 的契约：调用方在拿到句柄前被取消时，由实现关闭句柄。
- **工具参数的 schema 子集**（S5）：注册时只接受 `core.tool.ToolSchema` 的 KDoc 列出的子集（每个 schema 一个 `type`；object / array / string / integer / number / boolean 各自允许的关键字；计数非负、下限不高于上限、enum 非空且不重复），并递归检查每一层。WP02 的参数校验器照这个子集实现，模型看到的 schema 就都能被完整执行。`ToolRegistry` 的 `expanded` 改为 `List<ToolGroup>`，展开顺序由调用方给出。
- **`ProviderProfile` 不泄漏密钥**（S6）：baseUrl 不能带 user info，出错信息不回显 URL；header 名里含 key、token、secret、auth、cookie 的一律拒绝（和 `WireProfile.headers` 同一条规则）；`toString` 只列 header 名。明文 http 只允许本机和局域网：loopback、私有、link-local、unique-local 地址字面量，`localhost` 和 `*.local`，只看文本、不查 DNS。没有另设"本地服务器"标记：地址本身就能判断，多一个开关就多一种配错的方式。
- **值域校验**（S9）：turn、step、attempt ≥ 1，`request/retry.attempt` ≥ 2，延迟 ≥ 0，请求头按它产生的 `ChatRequest` 校验。坏值在解码时成为所在行的 `SessionCorruptedException`。

### 12. 修复清单第 2 轮（fix-list-r2，协调者评审）

v1 仍未发布，fixture 和 `format-names.txt` 按新事件重新生成（生成器拒绝覆盖，先删旧的 v1 目录）。

- **目录链的持久性**（B1）：每次 `writeAtomically` 和 `openAppender` 都 fsync 整条链：文件所在目录、它上面直到根的每一层、根的父目录。目录是不是这次新建的不再参与判断：上一次同步失败后，目录已经存在，"只同步新目录"的重试会漏掉上级。代价是每次写多几次目录 fsync。根在构造时转成绝对路径（S1）。rename 之后的同步失败仍然抛错，此时新内容已经就位但不知道是否持久（S6，`StoragePort` 的 KDoc 写明调用方不能假设旧内容还在）。
- **线路快照的生成入口**（B2）：`ChatModel.prepare(reasoning): RequestSettings(reasoning, wire)`，适配器实现，把档位钳制和线路映射放在同一步；主循环写 `request/header`、能力包写 `side/request` 之前调用，回放不调用。同一实例对同一档位结果稳定（`ModelRouter` 冻结 profile、能力和映射版本）。`ChatModelContract` 是测试源码里的抽象契约测试，适配器测试继承它；core 里用 fake 跑一遍。`WireProfile.parallelToolCalls` 改名 `disableParallelToolCalls`（S9），true 才发"禁止并行"的线路字段。
- **执行事实和停止进日志**（B3）：
  - `tool/started(callId, verdictSeq)`：真正调用 `Tool.execute` 之前追加并刷盘；`verdictSeq` 必须是这个调用最新的 verdict，而且是 Allow。拒绝、转交、审批未通过、拦截器短路都没有它。修复按它区分 NOT_STARTED 和 OUTCOME_UNKNOWN。
  - `tool/executed(callId, status)`：execute 返回后、`toolPostExecute` 之前写原始状态；取消和超时不写。
  - `task/stopped(cause)`：停止流程的最后一条。第 2 轮曾把 `USER`、`LIMIT` 定为终态；**第 3 轮撤回**，四种原因都只结束当前这次运行，见第 13 节。`StopCause` 从 `core.agent` 移到 `core.session`（session 在第 4 层，不能引用第 5 层的 agent）。
  - 三者都不可忽略。`PolicyContext.operatedPackages` 和 `outwardConfirmedPackages` 改为按执行事实推导（KDoc），`PolicyContextSamples` 给出样例日志和期望值，`PolicyContextSourcesTest` 用参考推导核对样例并证明它们能区分常见的错误推导。
  - 不变量补充：结果之后不能再声明同一个调用（S7）；`tool/started` 要求已声明、没有结果、没开始过；`tool/executed` 要求已开始、没有结果、没记过。
- **工具拿不到 `confirm`**（B4）：`ToolContext.interaction` 的类型是 `ToolInteraction`（`ask`、`handOff`）；`UserInteraction` 继承它并加上 `confirm`。运行时用 `forTools()` 包一层视图交给工具，强转也拿不到 `confirm`。确认卡只来自 PolicyEngine 的 Confirm 判定。
- **其他**：integer 的上下界按 64 位整数比较（S3）；`retryAfterMs` 在构造时要求 ≥ 0，解析不了或为负的由适配器转成 `null`（S8）；推理去掉记录按 seq 生效、多条累加，中断前缀进视图时去掉其中的 `Part.Reasoning`（S5、S10），样例在 `DeriveRuleSamples`，参考投影核对。

### 13. 修复清单第 3 轮（fix-list-r3，协调者评审）

v1 仍未发布（冻结点是第一个 M1 PR）。`session/resumed` 和 `policy/verdict` 改成不可忽略，fixture 和 `format-names.txt` 重新生成（先删旧的 v1 目录），不升版本。

- **停止没有终态**（A1，回到 D14）：`USER`、`LIMIT`、`SHUTDOWN`、`ERROR` 都只结束当前这次运行，同一个 session 可以继续。新的不变量放在 `core.session.RunRules`，`SessionInvariants` 在写入和读取两端都调用它：
  - **需要答复的调用**（B2）：每条 `assistant/message` 里的工具调用（interrupted 的消息没有调用）加上每个 `tool/call`。它们在 `step/end`、`turn/end`、`task/stopped`、`session/resumed` 之前都要有 `tool/result`。停止、崩溃修复、截断跳过和模型视图配对都用这一个集合。只在 `task/stopped` 上检查不够：崩溃修复不写 `task/stopped`，缩小了范围的修复会让之后的每个请求都被服务端拒绝。
  - `turn/end(STOPPED)` 后面紧跟 `task/stopped`；`task/stopped` 和 `session/resumed` 时没有打开的 turn；`task/stopped` 之前这次运行的所有 append（录入 inbox 的也算）都已完成，之后的下一条已知记录是 `session/resumed`，四种 cause 一样。
  - **不自动续跑**（B1）：每个 `task/stopped` 和 `session/resumed` 之后进入"等待用户输入"，只有 turn 里一条 kind = USER 的 `user/message` 能结束它。等待期间可以先记后台消息（WP02 先记排着队的后台消息、再记用户的"继续"），但不能有 step、请求、回复、调用、提示、旁路请求或上下文改动。检查条件是"第一个 step 之前有 USER 输入"，不是"新 turn 的第一条输入是 USER"。
  - **半截停止**（B3）：日志可以停在 `turn/end(STOPPED)`；下一条已知记录必须是 `task/stopped`，恢复时写 `task/stopped(ERROR)`，含义是"停止收尾没有完成"，不猜原因。
  - **未知的可忽略记录对这些规则透明**（B3、A4）：store 重新打开时只从它认识的记录恢复状态，读取端如果更严，store 自己写出的日志下次就读不回来。读写两端用同一份实现，未知记录只受"第 1 条是 `task/start`"这条信封规则约束。
  - 修复写的结果跟着落盘的 `tool/started` 走：有 `tool/started` 的不能是 NOT_STARTED，没有的不能是 OUTCOME_UNKNOWN。
  - `session/resumed` 的定义放宽为"新的 runtime 实例接管日志"（B7）：包括进程重建，也包括同一进程里停止以后由新实例继续。停下的实例不再接受输入，所以每次停止以后都由新实例写 `session/resumed`。
- **截断的调用**（A8）：`MAX_TOKENS` 带调用时 `assistant/message` 原样记录，每个调用补一条 `tool/result(SKIPPED)`，不写 `tool/call`：截断的参数可能解析不出合法的 `ToolCall`，而 `tool/call` 装的是校验过的调用。`OTHER` 带调用是 BadResponse。
- **compaction 块不随推理去掉**（A9）：`ReasoningKind.isReasoning` 标出推理类；`context/reasoning-dropped` 只去掉推理类，`ANTHROPIC_COMPACTION` 保留。没有把 compaction 从 `Part.Reasoning` 拆出去：它和推理块一样是服务商私有、只回传给同一 provider 的不透明块，拆出去要多一种 Part、改所有适配器和 v1 格式，换来的只是去掉一个布尔属性。
- **跨模型回传**（A7）：`Part.Reasoning` 只回传给同一 provider；同一 provider 换了 model 以后回不回传由该 provider 的适配器决定。Anthropic 一律原样回传，由 API 略过当前模型读不了的块。
- **回复里出现密钥**（A11）：适配器不改写模型产生的任何内容，整条回复按 `BadResponse` 拒绝，返回固定文字。`ModelError` 加 `discardsStreamed`（只有这种 `BadResponse` 为 true）：runtime 不把已流出的前缀记成 interrupted 的 `assistant/message`，只记 `assistant/attempt`，UI 撤回或标记作废已显示的片段（预览 delta 照常即时发布，不为检测密钥扣住）。匹配只做精确匹配（含 JSON 转义形式），不设最短长度。没有选"加一种 ModelError"：它仍是一次违反约定的回复，分类还是 BAD_RESPONSE，只是已流出的内容不能留；也没有选"改写成 `***`"：会破坏 Anthropic 的推理绑定，改写调用参数还会改掉要执行的操作。
- **外发免确认资格**（A2）：只认 `tool/started.verdictSeq` 那条 Allow 之前这个调用最后一张 APPROVED 的卡，并且要求它是 OUTWARD、pkg 等于那条 Allow 的 intent.pkg。

### 14. 快照的详细度（WP02 合入前，协调者决定）

v1 仍未发布。`ScreenSnapshot` 加 `detail: SnapshotDetail = BRIEF`：端口实际按什么详细度读的这一屏，由端口填写（`snapshot` 是请求的那一档，动作和启动之后的回读是端口回读用的那一档）。字段带 `@EncodeDefault(NEVER)`：BRIEF 不写进日志，v1 fixture 逐字节不变，没有这个字段的日志读出来是 BRIEF，所以不升版本、不重新生成 fixture；`format-names.txt` 加上 `SnapshotDetail`。

- 用途：批准后复核读 S′ 时按计划屏（判定时的 `PolicyContext.screen`）的详细度读，和执行器对齐计划快照时数层用的详细度相同（phase1 §3.3"对齐的前提"、§3.8 批准后复核）。
- 它是元数据：屏幕格式不打印它，不进模型请求。

### 15. 收不回来的交接（WP02 合入前，协调者决定）

`HandoffResult` 加 `NOT_TAKEN_BACK`。原来的 FAILED 同时代表两种结局：交出时端口抛 `ScreenUnavailableException`，什么都没移动；用户答完以后收回时端口抛 IOException，App 留在主屏，用户做了什么也不知道。`handOff` 的调用方分不出这两种，hand_to_user 的卡片也就没法只在位置未知时显示 UNKNOWN。

- 现在：交出失败记 FAILED（交出时抛别的 IOException 也记 FAILED，但交出没有落定，`handOff` 抛异常，调用方拿不到它）；收回失败记 `NOT_TAKEN_BACK`。`handoff/end` 两种都可以带 detail（`hand over failed: …`、`take back failed: …`）。
- 调用方：hand_to_user 把两种结局分开告诉模型，卡片分别是 FAILED 和 UNKNOWN（`details.handoff` 是 `failed`、`not_taken_back`）。PolicyEngine 判的转交两种都按没有执行处理（`REFUSED` + `LoopTexts.handoffFailed`），和以前一样。
- policy 不读 `HandoffResult`：`handoff/start`、`handoff/end` 不论结局都是作废边界，`operatedPackages` 和 `outwardConfirmedPackages` 都不看交接，所以加这个值不会让任何判定变松。
- v1 未发布（第一个 M1 PR 才冻结），加枚举值不升版本；v1 fixture 里的 FAILED 样本照旧有效，`format-names.txt` 加上新值。

### 16. 工具读到的屏先判定（policy-rules §6.4，WP02b，协调者决定）

工具读到的每一屏（读屏、动作和启动的回读、截图前后的两屏），runtime 先合成一次 `phone_observe`（`core.policy.SCREEN_JUDGMENT_TOOL`，没有参数）交给 PolicyEngine，只有 Allow 才交给工具（phase1 §3.8"屏幕判定"）。为此改了这些契约：

- `screen/judged`（`ScreenJudged`，ignorable = false）：`callId`、`snapshot`、`purpose`（`TOOL` / `FRAME_PRECHECK`）、`outcome`（`SHOWN` / `WITHHELD`）、`rule?`、`hint?`。扣下时必须有 `rule`（规则名，或者 `confirm`、`hand_off`），只有扣下时才能有 `hint`，加载时校验。两个枚举和其他枚举一样按常量名大写序列化。每次判定都记，不只记被拒的：设备重执行要逐条比判定，"没有记录"也就不能当作放行。
- `ScreenFlag.WITHHELD`：扣下的屏给工具的副本带它，只留 id、surface、pkg、detail。
- `ScreenDiff.base` 必填：差分是和哪一屏比的。runtime 只在 base 是模型看过的屏时给差分，否则差分会把模型没看过的屏的内容当成"去掉的行"带出来。
- `ScreenFrame` 的 `som`、`snapshotId`、`checkedOn` 都必填：截图之前读的 S（编号框按它画）和截图之后核对、给模型看的 S2。只在图随 S2 返回时记。
- `ToolContext.framedSnapshot` 和 `FramedSnapshot`（`core.tool` 的公共契约，在 WP02b 的授权范围内）：工具要图只走这里，`ToolContext.screen` 的 `frame()` 返回 null。截图前后各读一屏（S、S2），图只在 S2 放行、和 S 同包时随 S2 返回；带编号框的图还要求 S2 的元素编号和位置与 S 一致，因为框是按 S 画的（协调者确认）。接口方法没有默认实现，所有 `ToolContext` 实现都要跟着改。
- `ToolResult.SCREEN`（`details.screen`）是契约："结果显示的那一屏"。runtime 按它认模型看过的屏、给扣下的屏追加提示。
- `LoopTexts.screenWithheld`、`reasonWithheld`：扣下时的说明和替换端口自由文字的固定文字。

v1 未发布（第一个 M1 PR 才冻结），以上都不升版本；v1 fixture 原地补字段和样本，`format-names.txt` 跟着加。

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
- **`UserInteraction` 三个方法共用一个 `UserReply` 返回类型**：`confirm` 也得处理"回答了一段文字"这种不可能的情况；超时和无人值守也不是用户的回复。
- **任务停止时返回 `CANCELLED` 结果**：工具可能把它当成普通结果继续往下执行；用协程取消，"停止"就只有一种表达。
- **`ConfirmCard` 留在 `policy`，把 `UserInteraction` 放进新的 `interaction` 契约层**：要多加一层，而且 `IntentKind` 和 `Money` 仍要跟着下移。
- **`ElementRisk` 带上"在当前级别下是否确认"**：格式化器拿不到安全级别和任务历史。要传进来，屏幕格式就得依赖 `PolicyContext`；而且同一屏的格式会随设置变化，回放和金样也要跟着分情况。
- **v1 加了字段就升到 v2**：v1 还没有合入，也没有任何代码写过 v1 日志；升版本只会留下一个永远用不到的空迁移。
- **给 `Tool` 加 `plan(call): PlannedEffect`，由工具自己申报效果**：要改每个工具和 fake，而且安全结论会依赖工具的申报（AGENTS.md §3.3）。所以改用 policy 里的 `IntentResolver`。
- **`ScreenPort` 实现自带指纹和差分算法**：Android 侧和 core 会各有一套，回放和单测对不上。

- **任务设置放进 `SessionHeader`**（B3 的另一个选项）：文件头不经过迁移链，字段一旦发布就改不了；级别变化仍然需要事件，同一个事实会有两种来源。
- **`RequestHeader.dropReasoningUpTo`**（B5 的另一个选项）：每个后续请求头都要重复它，漏写一次就会把旧 Reasoning 又发出去。
- **`request/header` 保留 `role`，旁路请求也写请求头**：主视图推导就得按 role 过滤所有头和回复，旁路的回复还会混进 `assistant/message`。
- **线路设置在回放时从当时的 `ProviderProfile` 取**：profile 会被用户修改，日志就不再能单独重建请求。
- **fixture 用环境变量开关写回**：一次误设就会改写已发布的版本；改用拒绝覆盖的生成任务。
- **执行事实从现有事件推导，不加 `tool/started`**（B3 的另一个选项）：WP02 实测了两个反例。工具进入 execute 后再 handOff，最后一条是 `handoff/start`，却已执行一次；真实执行返回 OK 和 `toolExecute` 拦截器短路返回 OK，日志完全相同，执行次数却是 1 和 0。
- **停止只写进 `turn/end`**：停在 turn 之外时没有 `turn/end`，而且 stop 前后日志相同，恢复时分不清"被用户停了"和"进程被杀"。
- **`ChatModel.wire(reasoning): WireProfile`，档位由调用方用 `clampTo` 自己算**（B2 的另一个选项）：钳制和映射分在两处，主循环和能力包可能各算出不同的档位；`RequestSettings` 把两者一次给出。
- **只同步这次新建的目录，失败的记在内存里下次补**（B1 的另一个选项）：换一个 port 实例或进程重启就丢了；整条链每次都同步，不需要记状态。
- **`ToolContext` 保留 `confirm`，运行时忽略工具造的卡**：工具造的卡仍然会显示给用户，而且"安全历史只认策略来源的卡"要在运行时另记来源；从类型上去掉更简单。
- **`USER`、`LIMIT` 作为终态**（第 2 轮的做法，第 3 轮撤回）：和 D14 的"停止后说'继续'"冲突。用户每停一次都得重开任务，丢掉整段上下文；跨 turn 的续跑上限（`StopCause.LIMIT`）一到，session 也就再也不能继续。改用"等待用户输入"状态，用不变量保证不会自动续跑。
- **`USER`、`LIMIT` 停止以后由同一个实例直接开新 turn，不写 `session/resumed`**（第 3 轮初稿的做法）：`task/stopped` 和下一个 turn 之间就能夹进 inbox 等记录，"停止之后写了什么"要按 cause 分别判断；而 WP02 的 TaskRuntime 停止以后本来就不再接受输入，继续的总是新实例。统一成"每次停止之后先 `session/resumed`"，规则和恢复路径只有一条。
- **密钥按最短长度兜底**（短于 8 个字符视为占位值、不匹配）：任何阈值都会让短的真实凭据漏出去。不需要认证的本地服务用 `Credential.None`。
- **`turn/end(STOPPED)` 和 `task/stopped` 之间不许出现未知记录**：store 重新打开时看不到未知记录，读写两端的判断就会不一致，停在这个位置的日志也无法恢复。
- **只在 `task/stopped` 检查未答复的调用**：崩溃修复不写 `task/stopped`，漏补结果的修复拦不住；所以 `step/end`、`turn/end`、`session/resumed` 上也检查。
- **详细度记在 `screen/snapshot` 事件上，而不是快照里**（第 14 节的另一个选项）：动作和启动之后回读的屏不经过这条记录，它们的详细度由端口决定，runtime 只能猜，"在 FULL 屏上点一下、再在回读屏上按 ref 点要确认的控件"这条路径照样两边详细度不同。
- **收回失败时 `handOff` 抛 `ScreenUnavailableException`**（第 15 节的方案 B，不加枚举值）：和交出没有落定时一样走 runtime 的 ERROR。要改 `ToolInteraction.handOff` 的语义；模型拿到的从"App 还留在用户屏幕上"变成一个异常类名；PolicyEngine 判的转交也从 REFUSED 变成 ERROR。
- **只改 `details.handoff` 的值**（第 15 节）：工具只拿到 `HandoffResult`，FAILED 分不出交出失败和收回失败，写不出两个值；要分就得解析 `handoff/end` 的 detail 文字，或者让工具去读日志。
- **runtime 在内存里记每个快照的详细度**（第 14 节的另一个选项）：不进日志，要在 runtime 里复刻端口"回读按计划屏的详细度"的规则，进程重建后也没有了。
- **在 `toolPostExecute` 拦截器里判新屏、剥结果**（第 16 节的另一个选项）：拦截器只看得到成品 `ToolResult`，要反向解析屏幕格式；截图那时已经进了 blob；拦截器还可能漏装或被重排。
- **扣下时给工具一个不带标记的空快照，或者让 `snapshot()` 抛异常**（第 16 节）：空快照和真的空屏分不出来，工具和 wait 会据此下结论；抛异常时动作的结果连状态都丢了，模型也不知道屏幕被扣下。
- **日志只记扣下的副本**（第 16 节）：PolicyEngine 要靠完整屏才认得出敏感页，下一个调用也得在完整屏上判。
- **只在被拒时记 `screen/judged`**（第 16 节）：设备重执行没法区分"放行了"和"没判"。
- **在 `ToolResults` 的出口过滤自由文字**（第 16 节）：那里已经分不出文字来自哪一屏；而且每个工具、每条出口都得记着过滤。
- **端口的自由文字一律换掉**（第 16 节 v1）：放行屏上动作失败的有用原因也丢了。
- **没有放行的新屏时，自由文字回退到 Gate 的屏或旧的放行屏**（第 16 节 v2）：失败的新读报的文字不一定来自旧屏（A 放行以后界面进了被拒的 B，读 B 失败，异常里写的是 B 的内容）。
- **由 formatter 判断差分基线能不能用**（第 16 节）：formatter 不读日志，不知道模型看过哪些屏；base 也不在差分里。
- **关掉截图**（第 16 节）：树读不全的页面（游戏、画布、WebView）只能靠图。
- **只靠 WP07 给 frame 带上包名**（第 16 节）：只能看出截图时的 App，看不出那一屏能不能给模型看。
- **截图以后给模型看 S，`latestSnapshot` 跳过 S2**（第 16 节 v2）：S2 放行、同包，但同一坐标上的控件可能已经从"返回"变成"发送"；下一个调用在旧屏 S 上判，会绕过确认。
- **截断的调用也写 `tool/call`**：`tool/call` 装的是校验过的 `ToolCall`，截断的参数可能根本不是 JSON；而且它们从未派发，写了反而让"有 `tool/call` = 派发过"不再成立。

## 后果

- 加一个字段就要升格式版本、写迁移函数、提交新版本的 fixture。这是换取"旧日志永远按原意读"的代价。
- `SessionStore.open` 只接受当前版本的日志。旧版本日志只能经迁移用 `read` 读出，不能接着追加；需要原地升级时再加。
- Konsist 只看源码，有两处看不到：
  - 不调用的全限定顶层属性引用（`dev.phoneagent.core.x.value`）；全限定的函数调用（`dev.phoneagent.core.x.foo()`、`….build { }`，带类型实参的 `….foo<A<B>>()` 也算，类型实参可以嵌套）能看到；
  - 不按目录放置的 package 声明（这类文件按 `package` 声明归类）。

  需要更严时，改用编译后检查或加 detekt 规则。
- 以下情况要重新评估本 ADR：
  - 出现插件式的第三方工具或 provider，需要开放多态；
  - 日志体积成为问题，需要更紧凑的编码。
