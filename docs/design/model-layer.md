# Phone-Agent 模型层设计（草案 v0.1）

> 日期：2026-11-01｜配套：`docs/design/phase1-architecture.md`（D7、§3.1）
>
> 需求（已确认）：
> - 协议：**OpenAI Chat Completions 兼容**、**OpenAI Responses**、**Anthropic Messages**
> - 凭据：**自带 API Key**，以及 **ChatGPT 订阅账号登录**（即"Codex OAuth"，用套餐额度调用模型）

---

## 1. 参考实现

| 来源 | 位置 | 价值 | 采用方式 |
|---|---|---|---|
| **Mika**（Kotlin，Apache-2.0） | `core/.../model/`：`OpenAiCompatModel`、`ResponsesModel`、`AnthropicModel`、`GeminiModel`、`ModelCatalog`、`Capabilities`；`core/.../chatgpt/`：`ChatGptAuth`、`ChatGptSession`、`LoopbackCallback` | **技术栈和需求最接近**：纯 Kotlin + OkHttp，四种协议，ChatGPT 登录（PKCE、JWKS 验 ID 令牌、刷新、本机回调），"测试并保存"的能力探测，泄漏的工具调用文本修复 | 主要参考；按 Apache-2.0 可以借鉴代码，但要保留 NOTICE |
| **codex**（OpenAI 官方，Apache-2.0） | `codex-rs/login/`（浏览器登录、设备码、刷新、撤销）、`codex-rs/core/`（Responses 客户端） | OpenAI 自己怎么做登录、刷新和 Responses 流式；错误分类 | 协议细节以它为准 |
| **pi-ai**（TS） | `packages/ai/src/auth/oauth/openai-chatgpt.ts`（官方 token sharing）、`openai-codex.ts`（借用 Codex CLI 的客户端身份）、`src/api/*`（30 多种协议适配器，含 `openai-prompt-cache.ts`） | 同一个仓库里同时有两种 ChatGPT 登录做法，正好对照；多服务商的消息转换（`transform-messages.ts`） | 参考消息转换和错误处理；**不采用** `openai-codex.ts` 的做法（见 §4.3） |
| **dsh** | `packages/llm/llm`（adapter 抽象、`prepareCall` 只能用一次、按 provider 定的重试策略、统一的流式分片）、`token-meter`、`llm-deepseek` | 适配层的抽象方式；重试写日志；usage 只在请求完全相同时复用 | 抽象方式 |
| **DSH-Android** | `js/packages/llm-service-lite`、`js/test/fixtures/gateways/`（93 个网关录制样本） | **线路级回放测试**：各家网关的 SSE 怪癖（`usage:null`、思考内容回传等）都做成 fixture | 测试方法 |

---

## 2. 架构

```
┌─ core/model ───────────────────────────────────────────────────────────────┐
│ ChatModel（phase1 §3.1）← ModelRouter（主 / 看图 / 轻量 三种用途）         │
│                                                                             │
│ ProviderProfile ── protocol ──┬─ ChatCompletionsAdapter   （OkHttp + SSE） │
│   id、预设、baseUrl、headers   ├─ ResponsesAdapter          （OkHttp + SSE） │
│   credential                   │    ├─ ApiKey 模式                          │
│                                │    └─ ChatGptPlan 模式：强制套餐接口的约束 │
│                                └─ AnthropicMessagesAdapter（官方 Java SDK） │
│ Credential（sealed）：ApiKey(secretRef) | ChatGptPlan(sessionRef) | None   │
│ CredentialStore：Android Keystore 加 AES-GCM；令牌刷新由带锁的 TokenSource │
│ 负责                                                                        │
│ 中性消息模型 ⇄ 各协议线路格式（纯函数，可单测，可回放）                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

**中性消息模型**（写进 session 日志，满足"模型可见 ⟺ 已记录"）：

```kotlin
sealed interface Part {
    data class Text(val text: String) : Part
    data class Image(val blob: BlobRef, val mime: String) : Part        // 每次都以 base64 发送同样的字节
    data class ToolCall(val id: ToolCallId, val name: String, val argsJson: String) : Part
    data class ToolResult(val id: ToolCallId, val parts: List<Part>, val isError: Boolean) : Part
    /** 服务商私有的推理内容，原样保存，只回传给同一 provider 和同一 model。 */
    data class Reasoning(val provider: ProviderId, val model: ModelId, val kind: ReasoningKind, val opaque: JsonElement) : Part
}
enum class ReasoningKind { AnthropicThinking /* 带 signature */, ResponsesEncrypted /* encrypted_content */, ChatReasoningField /* reasoning_content 等 */ }
```

**能力描述**：

```kotlin
data class ModelCapabilities(
    val tools: Boolean, val vision: Boolean, val contextTokens: Int, val maxOutputTokens: Int?,
    val reasoningEfforts: List<String>?,        // null = 未知；空 = 没有可选档位
    val promptCaching: PromptCaching,           // Implicit / Explicit(cache_control) / None
    val thinkingBinding: ThinkingBinding,       // None / PrefixBound（Anthropic preserved thinking）
    val verified: Set<VerifiedCap>,             // 探测确认过的：TOOL_CALL、VISION、GROUNDING …
)
```

**统一的错误类型**（交给 `requestError` 拦截点处理）：

```kotlin
sealed interface ModelError {
    Auth, SignInExpired, PlanNotEligible, RateLimited(retryAfter), Overloaded, ContextOverflow,
    InvalidRequest(param, detail), Refusal(category), PrefixBindingMismatch, Network, Server(status)
}
```

- 重试策略由各 provider 自己定义，参考 dsh 的默认值：最多 5 次，500ms–10s，带抖动，遵守 `retry-after`。每次重试都写一条 `llm/retry` 日志。
- `InvalidRequest` 和 `Auth` 不重试。

---

## 3. 各协议要点

### 3.1 Chat Completions 兼容（DeepSeek、通义、Kimi、GLM、豆包、硅基流动、OpenRouter、Ollama、LM Studio …）

- **工具调用**：`tools[].function` + `tool_choice:"auto"`。主循环每轮只执行一个调用，所以服务商支持时发 `parallel_tool_calls:false`。如果模型仍然返回多个调用，第一个之外的每个都要配一条结果"已跳过：每步只执行一个动作"，否则下一次请求会被拒。
- **流式**：`stream:true` + `stream_options.include_usage`；要兼容某些网关返回 `usage:null` 的情况。
- **推理内容**：字段名和"要不要回传"各家不同（`reasoning_content` 等）。在 `ProviderProfile.reasoningEcho` 里按服务商配置，由能力探测确认；**探测不了的标"未验证"**。
- **工具调用漏成文字**：有的模型会把工具调用写成正文里的标记（DeepSeek DSML、`<tool_call>` 等），参考 Mika 的 `LeakedToolMarkup`，识别出来按工具调用处理。
- **缓存**：靠服务端隐式前缀缓存，所以历史要尽量只追加；命中情况从 usage 里读。

### 3.2 OpenAI Responses

- **请求结构**：`instructions`（系统提示词）+ `input[]`。input 的条目类型有 message、`function_call`、`function_call_output`、`reasoning`。对话里的 system 角色一律改成 developer 消息。
- **无状态**：`store:false`，**不用 `previous_response_id`**，每次都发完整的 input。
- **推理**：`include:["reasoning.encrypted_content"]`，把返回的加密推理条目原样保存，下一次请求原样回传（Mika 的做法）。用户选了档位才发 `reasoning.effort`。
- **ChatGPT 套餐模式的硬性约束**（官方文档 *Preview limitations*，以官方为准）：

| 类别 | 要求 |
|---|---|
| 必须发 | `store:false`、`stream:true` |
| 不能发 | `max_output_tokens`、`temperature`、`top_p`、`truncation`、`metadata`、`user`、`prompt_cache_retention`、`background`、`conversation` 等 |
| 消息 | input 里不能有 system 角色的条目 |
| 工具 | 只支持 function / custom；不支持文件检索、Code Interpreter、托管 MCP、`tool_search`、原生 computer use |
| 输入 | 图片可以；不能用 Files 上传接口 |
| 流 | 必须把流读到 `response.completed` |

  适配器在套餐模式下**校验并剔除**不允许的字段；单测里要有"会被拒绝的反例"。

### 3.3 Anthropic Messages

- **实现方式**：用官方 SDK `com.anthropic:anthropic-java`（Kotlin 直接用 Java SDK），提供 baseUrl 的服务商（Anthropic 兼容端点）通过 base URL 覆盖接入。
  - **M1 先做一个 spike**：验证 minSdk 30 下能否运行、APK 增加多少体积、和 R8 是否兼容。不过关的话，再评估用 OkHttp 直接实现线路格式，并记一条 ADR。
- **默认模型**：主模型 `claude-opus-5-5`，可选 `claude-sonnet-5-5`；轻量模型 `claude-haiku-5-5`；看图模型默认用主模型。模型清单以服务端 `/v1/models` 返回的为准，本地只做初值。
- **思考**：
  - Opus 5.5 的思考始终开启：不发 `thinking`，或者发 `{type:"adaptive"}`。**`budget_tokens` 和 `disabled` 都会被拒（400）**。
  - 思考深度用 `output_config.effort` 控制。**Opus 5.5 的默认值是 `medium`**，所以按用途显式设置：主模型做 GUI 任务时建议从 `high` 起调，轻量判断用 `low`。
  - 要给用户看思考进度时，设 `display:"summarized"`。
- **思考块的回传**：thinking 块（包括 signature）原样保存、原样回传，绑定 model。换了模型时服务端会静默丢弃这些块，不计费。
- **preserved thinking（关键约束）**：思考块的签名绑定在它之前的整段对话前缀上（system + tools + 之前的所有消息）。
  - **改写、删除或重排更早的轮次，都会让之后所有的思考块失效**；2026-08-31 之后注册的账号会直接收到 400。
  - 对我们的影响和对策见 §5。
- **工具调用**：
  - 发 `tool_choice:{type:"auto"}` + `disable_parallel_tool_use:true`（主循环每步只执行一个动作）。
  - **不用强制的 `any`/`tool`**，Opus 5.5 和 Sonnet 5.5 上会返回 400。
  - 工具 schema 可以加 `strict:true`。
  - 流式时给客户端工具加 `eager_input_streaming:true`，**拿到的参数先按 schema 校验再执行**；在执行任何工具之前，先检查 `stop_reason` 是不是 `max_tokens` 或 `refusal`。
- **缓存**：最多 4 个 `cache_control` 断点。建议放在：工具清单末尾、system 末尾、历史里最后一个稳定点。用 `usage.cache_read_input_tokens` 验证是否命中，结果进 Token 统计。
- **输出与采样**：
  - 流式请求的 `max_tokens` 给足（约 64K），避免话说到一半被截断。
  - 不发 `temperature`、`top_p`、`top_k`：2026-10-21 的 API 版本起会返回 400。
  - 不用 assistant prefill（400）。
- **拒答**：处理 `stop_reason:"refusal"` 和 `stop_details.category`。
  - 在 Anthropic 官方端点上**默认开启服务端降级**：beta `server-side-fallback-2026-07-01`，`fallbacks:"default"`。Sonnet 5.5 只支持 `"default"` 形式，而且只在 Claude API 上可用。
  - 设置页要告诉用户这个开关已经开着，并允许关闭。
- **图片**：用 base64 发送（字节稳定，签名校验认的是字节）。截图长边 ≤1280，JPEG。

---

## 4. 凭据与登录

### 4.1 自带 API Key

- **存储**：每个服务商一份，Keystore 加 AES-GCM 加密；**不进日志，不进回放 fixture**（fixture 里只出现 `{{credential:1}}` 这样的占位）。
- **"测试并保存"**（参考 Mika）：
  1. 调列模型接口；
  2. 用一个试用工具，让候选主模型真的调用一次（花几十个 token）；
  3. 两步都通过才保存，并写入 `verified` 能力。
  4. 失败时把服务端的报错原样告诉用户。
  5. 用户填的是官方地址时，提示改用对应的预设。

### 4.2 ChatGPT 订阅账号：采用官方的 "Sign in with ChatGPT" token sharing

OpenAI 在 DevDay 2026 推出的这个通道，面向"开源、在本地运行"的 App，让 Plus/Pro 用户用套餐额度调用 Responses 接口。用户可以给每个 App 设每周用量上限。Mika 和 pi 的 `openai-chatgpt.ts` 用的就是它。

**登录流程**（按官方文档加 Mika 的实现）：

1. **本机标识 `ext_agent_host_id`**：每台设备生成一次，之后一直用。格式可以是 `urn:uuid:…`，官方更推荐 `urn:ietf:params:oauth:jwk-thumbprint:…`。它**不是凭据**。
2. **第一次登录**：`client_id=dynamic_agent_client` + `agent_name_hint=<App 名>`。回调里会给出**这个用户专属的 client_id**，必须**在换令牌之前存下**；之后的登录、换令牌、刷新都用它。
3. 每次登录都重新生成 state、nonce 和 PKCE（S256）。scope 是 `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`，`resource=https://api.openai.com/v1`。
4. **回调**：在 App 里起一个只绑定 `127.0.0.1` 随机端口的本机服务，地址是 `http://127.0.0.1:<port>/auth/callback`。授权页**用 Custom Tabs 打开，不用 WebView**（RFC 8252；而且 Google 账号登录会拦截 WebView）。只接收 state 对得上的回调，超时或取消就关闭服务。
5. **换令牌后验证 ID 令牌**：用 JWKS 做 RS256 验签，核对 iss、aud（= client_id）、exp 和 nonce；授予的 scope 里**必须有 `chatgpt.tokens.use.direct`**，否则提示"这个账号或套餐不支持"。
6. **刷新**：带锁的 TokenSource 在令牌过期前 2–3 分钟刷新，换来的刷新令牌一起保存。遇到 `invalid_grant` 这类终止性错误，清掉令牌，用原来的 client_id 重新登录。
7. **退出登录**：先在服务端撤销令牌，再清本地数据；保留 client_id 和本机标识。
8. **调用**：Bearer access token → `https://api.openai.com/v1/responses`，套餐模式的约束见 §3.2。模型从 `/v1/models` 列出。
9. **UI**：在添加服务商的地方放"使用 ChatGPT 继续"卡片；第一次登录后说明一次"用量计入你的 ChatGPT 套餐"；在显眼的位置放"管理用量"入口（指向 `chatgpt.com/settings/usage`，这也是 DevKit 的要求）。

**适用范围的风险（需要你拍板）**：
- 官方文档的适用对象是"**open-source and locally hosted apps**"。付费或远程托管的 App 要先填 interest form。
- 文档**没有提到移动端 App**。
- DevKit 的代码用的是**非商业许可**，所以**不要复制 DevKit 的代码**，按文档自己实现即可（可以参考 Apache-2.0 的 Mika）。

如果 phone-agent 开源、免费、在本机运行，看起来符合；否则要先申请。这一点影响 M2 的排期。

### 4.3 不采用的做法

- **借用 Codex CLI 的客户端身份**（固定 client_id `app_EMoam…`，回调 `localhost:1455`，调用 `chatgpt.com/backend-api/codex/responses`，带 `chatgpt-account-id` 和 `originator` 请求头）。pi 的 `openai-codex.ts` 和不少工具是这么做的，但这等于冒用别人的客户端，有违反服务条款和被吊销的风险。现在已经有官方通道，没有理由再这样做。
- **Anthropic 订阅额度授权**：本次没有发现面向第三方 App 的官方通道，所以 Claude 只走 API Key。

---

## 5. 上下文管理与缓存：按协议区分（修订 phase1 §3.9 的规则 2）

**全局原则：历史只追加。**
- runtime-context 只在内容变化时**作为一条新消息追加**，不回头改写之前的消息尾部。
- system 和 tools 在同一会话里保持稳定：工具只增不减，也不改写已有工具的定义。

这条原则同时服务于三家的前缀缓存和 Anthropic 的 preserved thinking。

**旧截图和旧界面结果的瘦身**：

| 协议 | 做法 |
|---|---|
| **Anthropic** | **不在客户端改写历史。** 用服务端 context editing（beta `context-management-2025-06-27`，`clear_tool_uses_20250919`）清理旧的工具结果，包括里面的截图；接近上下文上限时用服务端 compaction（beta `compact-2026-01-12`），**并且必须把返回的完整 `response.content`（包括 compaction 块）追加回历史**。另外设置兜底：`thinking.block_binding.prefix_mismatch_behavior:"drop_block"`（beta `thinking-binding-controls-2026-08-01`），把返回的 `input_transformations` 记进 session 日志 |
| **Responses / Chat Completions** | 可以在客户端做确定性裁剪（Replace），但**只在越过阈值时做一次，不要每步都做**，因为每次都会让前缀缓存失效。截图保留最近 N 张（默认 3 张） |

**模型分工与缓存**：缓存和思考块都按模型隔离。轻量模型（意图判断、结局判定）和看图模型（描述界面、看图定位）**一律作为旁路请求**，用自己的短上下文，**不在主对话里切换模型**。

**恢复兜底**：如果收到 `PrefixBindingMismatch` 这类 400，去掉历史里所有的 thinking 块后重试一次，并记日志。这是一次性的恢复手段，不是常规路径。

---

## 6. 测试

| 层级 | 内容 |
|---|---|
| **线路格式单测** | 中性消息 ⇄ 三种协议的请求 JSON，纯函数；每种协议各有一组金样；套餐模式的"剔除字段"、思考块回传、多出来的工具调用补结果，都要有反例 |
| **SSE 回放** | 每家网关录一份原始流，放在 `core/src/test/resources/wire/<provider>/<case>.sse`，喂给解析器（参考 DSH-Android 的 gateway fixtures）；覆盖 `usage:null`、思考字段、工具参数分片、拒答、中途断流 |
| **会话回放** | 见 `engineering-baseline.md` §3.2；fixture 里记录协议和模型，同一个场景可以在三种协议下各录一份 |
| **真实 API** | 每种协议一个 e2e，无 key 时跳过，CI 上缺 secret 判红；ChatGPT 套餐模式只做手动的冒烟清单（需要真实账号，不进 CI） |
| **preserved thinking 自检** | Anthropic e2e 里加一个"历史只追加"的检查：连续两次请求的 messages，前一次必须是后一次的前缀；请求里显式设 `prefix_mismatch_behavior:"error"`，这样老账号也能测到 |

---

## 7. 排期

| 里程碑 | 内容 |
|---|---|
| M1 | 三种协议的 API Key 模式；`ModelRouter` 的三种用途；"测试并保存"；线路格式单测和 SSE 回放；Anthropic SDK 的 Android spike |
| M2 | ChatGPT 套餐登录（要先确定 §4.2 的适用范围）；Anthropic 服务端 context editing / compaction 接入；Token 和缓存命中统计 |
| M4 | 更多协议（如 Gemini）、更多服务商预设 |
