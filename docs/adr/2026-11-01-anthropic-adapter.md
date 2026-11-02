# Anthropic 适配器用 OkHttp 直接实现 Messages 线路格式，不引入官方 Java SDK

- 日期：2026-11-02
- 状态：采纳（team-lead 2026-11-02 同意）

## 背景

`docs/design/model-layer.md` §3.3 原计划用官方 SDK `com.anthropic:anthropic-java` 实现 Anthropic 适配器，并要求 M1 先做 Android spike：minSdk 30 能不能跑、APK 增加多少、R8 是否兼容；不过关就改用 OkHttp 直接实现，并记 ADR。

除了 spike 的三项，WP04 对适配器还有这些硬约束：

- **每次尝试只发一个 HTTP 请求**。重试归主循环（phase1 §3.7），适配器和 HTTP 客户端都不能自己重发、跟随重定向或做认证 follow-up。
- **线路设置只从 `request.wire` 快照读**。请求体是"日志 + 快照"的纯函数，要能做逐字节金样（AGENTS.md §3 铁律 1）。
- 服务端回显的密钥在任何出口上都要打码，错误路径和成功回复的诊断信息（`providerMetadata`）都算；取消时要释放响应和连接。
- thinking / redacted_thinking / compaction 块原样保存、原样回传；beta 字段要能表达：`fallbacks`、`thinking.block_binding`、`context_management`。
- `core` 保持纯 JVM，不增加依赖面。

### Spike 方法与数字

做法：在 `/tmp` 下复制一份仓库，基于 `307e2a2`，不提交。只改 `app`：

- 一个 Gradle 属性选变体，加对应的依赖；
- `MainActivity` 里加一个调用点，让 R8 保留被调用的代码；
- release 构建打开 R8。项目本身的 release 没有开 minify，所以 spike 加了：
  - `isMinifyEnabled = true`；
  - `proguard-android-optimize.txt`；
  - 一份 `spike-rules.pro`，见下面"R8"一条。

其余配置照项目原样：AGP 9.5.0，minSdk 30，core 和 app 的依赖版本取自 `libs.versions.toml`。

三个变体：

- **none**：基线。调用点只做字符串拼接，不引入 OkHttp。
- **direct**：用 OkHttp 加 kotlinx.serialization 发一次流式 Messages 请求，请求里有 tools、thinking 和图片，依赖面和本适配器相同。
- **sdk**：在 direct 的基础上，再用 `com.anthropic:anthropic-java:3.3.0`（`anthropic-java-core` + `anthropic-java-client-okhttp`）的 beta Messages API 发同样的请求。用到的类型有：
  - 请求：`MessageCreateParams`、adaptive thinking、`BetaTool`、`tool_choice`；
  - 内容块：thinking、tool_use、tool_result、base64 图片、cache_control；
  - 流式：`BetaMessageAccumulator`。

  所以 sdk 和 direct 的差，就是 SDK 的边际成本。

统计用的工具：

- apkanalyzer：`apk download-size`，`dex packages --defined-only`；
- 解压后的 dex 字节数；
- `dexdump -d` 加平台的 `api-versions.xml`，用来查 API 级别；
- R8 mapping，用来把包名还原。

| 变体 | APK 字节 | 下载大小 | dex 字节 | 定义的方法数 | 类数 |
|---|---:|---:|---:|---:|---:|
| none | 1,786,276 | 793,448 | 1,141,288 | 6,476 | 1,383 |
| direct | 2,097,572 | 951,183 | 1,439,048 | 8,043 | 1,675 |
| sdk | 4,817,343 | 2,173,944 | 4,044,956 | 23,955 | 4,405 |
| sdk（排除 `META-INF/native-image`） | 4,725,880 | — | — | — | — |

- **SDK 相对 direct 的增量**：APK 多 2,719,771 B（约 2.6 MiB），下载大小多 1,222,761 B，方法多 15,912 个，类多 2,730 个。
- **增量的来源**（按 R8 mapping 还原后的包统计 sdk 变体中定义的方法）：
  - `com.anthropic.models` 5,215 个；
  - `com.fasterxml.jackson` 5,183 个；
  - `kotlin.reflect` 4,569 个，由 jackson-module-kotlin 拉进来。

  这三项是 SDK 本体和它的序列化栈，不能靠裁掉 okhttp client 模块省下来。
- **R8**：`spike-rules.pro` 里只有一条 `-dontwarn com.google.auto.value.AutoValue`。这一条是 AGP 在 sdk 变体报缺失类时生成的（`missing_rules.txt`）；none 和 direct 用同一份规则文件构建，它们不引用 AutoValue，这一条对它们不起作用。spike 构建的 `lintVital` 没有报错。
- **minSdk 30**：静态扫描 dex 中引用的 API 级别。高于 30 的只有一处：`okio.Buffer$inputStream$1.transferTo`（API 33），它是对 `InputStream.transferTo` 的覆写，在 API 30 上不会被调用到。direct 和 sdk 都有这一处，因为都依赖 okio。
  - **没有在设备或模拟器上运行**（WP04 不允许碰 adb 设备）。"能运行"的结论只来自 API 级别扫描和 lintVital。
- **行为**（对照 SDK 3.3.0 源码确认）：
  - SDK 内部的 OkHttp 客户端不暴露关闭重定向和认证 follow-up 的开关；
  - 默认会重试，`maxRetries(0)` 可以关掉；
  - 每个请求都附加 `X-Stainless-*` 平台头（OS、架构、运行时版本）；
  - 请求体由 SDK 的 builder 生成，beta 字段要通过 `putAdditionalProperty` 塞进去；
  - 序列化走 Jackson 加 jackson-module-kotlin，运行时要用 `kotlin.reflect`。thinking 块要原样存进 kotlinx 的 `JsonElement`（日志的类型）、再原样回传，每轮都得在 Jackson 的 `JsonValue` 和 kotlinx 的 `JsonElement` 之间来回转换。

## 决定

不引入 SDK。在 capability 包 `core.model.anthropic` 里用 OkHttp 加 kotlinx.serialization 直接实现 Messages API。不加新依赖，OkHttp 和 kotlinx.serialization 本来就是 core 的依赖。

### 实现要点

- **入口**：`AnthropicAdapters(credentials, timeouts, clock).model(profile, model, capabilities, blobs)`，按 `ModelProtocol.ANTHROPIC_MESSAGES` 创建 `ChatModel`。
- **HTTP 客户端**：和 OpenAI 系适配器（WP03）的行为一致。
  - 关掉连接失败重试（`retryOnConnectionFailure(false)`）、重定向和 SSL 重定向跟随。
  - 3xx 映射成**不可重试**的 `InvalidRequest(param = null)`：同一个地址再发还是重定向，这是用户要改的配置。`detail` 里带上 `Location`，先打码、再截断。
  - SSE 的行结尾认 LF、CRLF 和单独的 CR。
  - `Retry-After` 只接受有限、非负的值。
  - 取消时取消 Call，并释放响应和连接。
  - 服务端回显的内容一律用含本次密钥的 redactor 处理：错误体、流内 error 事件、协议违例的描述，以及成功回复 `providerMetadata` 里的每个字符串。
- **请求**：`POST {baseUrl}/v1/messages`，请求头如下：
  - `anthropic-version`：默认 `2026-10-21`；
  - `anthropic-beta`：用逗号拼接；
  - `x-api-key` 或 `Authorization: Bearer`；
  - `Accept: text/event-stream`。
- **映射版本 1**：`AnthropicWire.MAPPING_VERSION = 1`。
  - 快照由纯函数 `AnthropicWire.snapshot(profile, model, capabilities, reasoning)` 生成：同样的输入总是得到同样的快照。`complete()` 不生成快照，只读 `request.wire`。
  - 快照的 `options` 只认 `anthropic.*` 下的八个名字，未知名字、取值不合法、映射版本或凭据类型不符，一律在发送前报 `InvalidRequest`。
  - 带工具时总是发 `tool_choice:{type:"auto", disable_parallel_tool_use:true}`。这是映射版本 1 的固定规则（主循环每步只执行一个动作），不读快照里的 `parallelToolCalls`；那个字段属于 Chat Completions，在 Anthropic 快照里保持 `false`。要改这条规则，就要升 `MAPPING_VERSION`。
  - `output_config.effort` 走 `reasoningEffort` 字段，按 `request.reasoning` 显式设置，`MINIMAL` 映射为 `low`。
  - body 的字段顺序和合并、缓存规则写在 `MessagesWire` 的 KDoc 里，并由金样 `core/src/test/resources/wire/anthropic/golden/` 固定下来。
- **推荐设置**（`AnthropicSettings.defaultsFor`）：
  - 所有端点：adaptive thinking、`display:"summarized"`、`eager_input_streaming`。
  - 只在官方端点（`https://api.anthropic.com`）上再加两项：
    - `prefix_mismatch_behavior:"drop_block"`；
    - 对 Opus 5.5 和 Sonnet 5.5 开服务端降级 `fallbacks:"default"`。Haiku 5.5 没有降级。
  - 网关不发 Claude API 专有的 beta，因为网关可能拒收。
- **流式解析**（`MessagesStream`）：
  - 内容块原样拼装。
  - delta 加起来是最终回复的前缀；工具调用在名字完整后只通告一次。
  - 中途降级时，被放弃那次尝试的思考块和工具调用不进回复，`providerMetadata` 里记下 `fallback_discarded` 和 `preview_diverged`。
  - 终止事件缺失或无效时报 `BadResponse`；在 `message_stop` 之前断流报 `Network`。
  - `refusal` 抛出 `ModelError.Refusal(category)`。
- **工具参数**：保留原始 `input_json_delta` 文本，适配器不解析。按 schema 校验放在边界，即构造工具调用的地方（WP02 的 `ToolCall` 契约）。适配器报告 `MAX_TOKENS` 或 `Refusal`，主循环据此不执行工具。
- **错误映射**：
  - 先看 `error.type`，再看状态码；429 先于一切短语判断。
  - 绑定类短语映射为 `PrefixBindingMismatch`，上下文类短语映射为 `ContextOverflow`。
  - `Retry-After` 只在有限、非负时传出。

SDK 的源码和 claude-api 文档只作为线路格式的参考（字段名、beta 名、事件类型），不作为依赖。

## Alternatives considered

- **用官方 SDK（spike 结论之前的计划）**。技术上过关：R8 只差一条 `-dontwarn`，API 级别没问题。但有这些代价：
  - 体积：APK 多约 2.6 MiB，方法多约 1.6 万个，方法总数是 none 基线的 3.7 倍。对一个侧载分发、面向普通用户的应用，这个增量换来的只是一个协议的客户端。
  - 运行时反射：jackson-module-kotlin 带进 `kotlin.reflect`，单这一项就有 4.6k 个方法。
  - 行为控制：要满足"每次尝试一个请求"，就得绕过 SDK 自带的 OkHttp 客户端，因为它关不掉重定向和认证 follow-up。
  - 两种 JSON 类型：thinking、redacted_thinking、compaction 块要原样进日志、原样回传，每轮都要在 Jackson 的 `JsonValue` 和 kotlinx 的 `JsonElement` 之间转换，"逐字节一致"要靠两套序列化的行为一致来保证。
  - 头和请求体：`X-Stainless-*` 头要去掉；请求体要逐字节可控，用 SDK 的 builder 加 additional properties 做金样，比直接构造 JSON 更绕。
- **只用 SDK 的模型类和 accumulator，传输自己做**（用 `anthropic-java-core` 加自定义 `HttpClient`）。体积大头正好在 `com.anthropic.models`、Jackson 和 `kotlin.reflect`，所以这样省不下多少。另外两套 JSON 栈（Jackson 和 kotlinx.serialization）会并存。
- **和 OpenAI 系适配器共用 `HttpTransport`、`Sse`、`Redactor`**。现行规则是 capability 包之间不能互相引用。所以先复制一份，在 KDoc 里注明"keep them in step"。把它们提成契约层的公共包，需要另一个 WP 先改依赖规则。

## 后果

- **线路格式归我们维护**。API 新增字段、beta 或事件类型时要手动跟进：改映射时同时改金样；不兼容的改动要升 `MAPPING_VERSION`。未知事件类型会被忽略；未知块类型和 delta 类型会被拒收（`BadResponse`），所以一旦服务端启用新的块类型，用户会马上看到失败，而不是数据被静默丢掉。
- **以下行为只有按文档和 SDK 类型构造的 SSE fixture 覆盖，没经过真实 Claude 验证**（fixture 的 `: source:` 注释写明了这一点），拿到测试账号后要用真实流重录：
  - thinking 签名回传；
  - 缓存命中；
  - 拒答；
  - 服务端降级；
  - 服务端 compaction。

  本机网关（gpt-6-luna）的 e2e 只证明线路格式能通。
- `HttpTransport`、`Sse`、`Redactor` 有两份副本，改一处要同步另一处，直到有公共包。
- **后续项**：WP03 和 WP04 都合入以后，单独开一个改动，把公共传输层（`HttpTransport`、`Sse`、`Redactor`，以及 3xx、`Retry-After` 这些共同的映射规则）抽到一个中立的包里，两个适配器都改为引用它。这要先改包依赖规则，所以不放在本 WP 里做。
- **什么情况下重新评估**：
  - SDK 去掉 Jackson 或 kotlin-reflect 这类运行时反射依赖，体积降到和 direct 同一量级；
  - 体积不再是约束，比如 APK 预算放宽；
  - 需要 SDK 才方便用的能力，比如 Files API、Batch、Managed Agents；
  - 流式协议频繁变化，手写维护的成本超过体积代价。
