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
    data class Image(val blob: BlobRef, val mime: String, val width: Int, val height: Int) : Part  // 每次都以 base64 发送同样的字节
    data class ToolCall(val id: ToolCallId, val name: String, val argsJson: String) : Part
    data class ToolResult(val id: ToolCallId, val parts: List<Part>, val isError: Boolean) : Part  // parts 只含 Text / Image
    /** 服务商私有的推理内容，原样保存，只回传给同一 provider；同一 provider 换了 model 以后回不回传，由该 provider 的适配器决定（§3.2、§3.3）。model 记录是哪个模型产生的，供适配器判断，也供 UI 和诊断使用。 */
    data class Reasoning(val provider: ProviderId, val model: ModelId, val kind: ReasoningKind, val opaque: JsonElement,
                         val summary: String? = null /* 只给 UI 看，不回传 */) : Part
}
enum class ReasoningKind(val isReasoning: Boolean) {   // isReasoning = false：装的是历史，context/reasoning-dropped 不去掉它
    AnthropicThinking(true) /* 带 signature */, AnthropicRedactedThinking(true), AnthropicCompaction(false) /* 服务端 compaction 块，§5 */,
    ResponsesEncrypted(true) /* encrypted_content */, ChatReasoningField(true) /* reasoning_content 等 */,
}
```

**能力描述**：

```kotlin
data class ModelCapabilities(
    val tools: Boolean, val vision: Boolean, val contextTokens: Int, val maxOutputTokens: Int?,
    val reasoningLevels: List<ReasoningLevel>?, // null = 未知；空 = 没有可选档位；适配器负责映射成各家的档位名
    val promptCaching: PromptCaching,           // Implicit / Explicit(cache_control) / None
    val thinkingBinding: ThinkingBinding,       // None / PrefixBound（Anthropic preserved thinking）
    val verified: Set<VerifiedCapability>,      // 探测确认过的：TOOL_CALL、VISION、GROUNDING、REASONING_ECHO
)
```

**统一的错误类型**（交给 `requestError` 拦截点处理）：

```kotlin
sealed interface ModelError {
    Auth, SignInExpired, PlanNotEligible, QuotaExceeded /* 余额或额度用完，不重试 */, RateLimited(retryAfter), Overloaded,
    ContextOverflow, InvalidRequest(param, detail), Refusal(category), PrefixBindingMismatch, Network, Server(status),
    BadResponse(discardsStreamed) /* 响应解析失败或违反协议；回复里带着本次的密钥时 discardsStreamed = true，见下文"脱敏" */
}   // 每个都带 kind: ModelErrorKind（写进日志）；retryable 由 kind 决定：RateLimited、Overloaded、Network、Server、BadResponse
```

- 重试策略由各 provider 自己定义，参考 dsh 的默认值：最多 5 次，500ms–10s，带抖动，遵守 `retry-after`。每次重试都写一条 `request/retry` 日志。
- `InvalidRequest` 和 `Auth` 不重试。
- 每次 attempt 只发一个 HTTP 请求，重试只在 `requestError` 里发生、逐次记日志：OpenAI 适配器的 HTTP 客户端关掉了 OkHttp 的连接失败重试、重定向跟随（307/308 会重发 POST），也拦下了它对 `503` + `Retry-After: 0` 的自动重发；默认也不复用空闲连接（§3.1"连接复用"）。重定向（3xx）报不可重试的 `InvalidRequest(param = null)`：同一个地址再发还是重定向，这是要用户改的配置；`detail` 里带脱敏后的目标地址，提示检查 base URL、改成最终地址。`BadResponse` 本身的重试属性不变。

**适配器的注入端口**（`core.model`，构造适配器时注入，`ChatModel.complete` 的签名不变）：

```kotlin
fun interface CredentialResolver {      // Key 存储和 ChatGPT TokenSource 实现，app 组装
    suspend fun secret(credential: Credential, rejected: Boolean): String?   // None → null；rejected = 上一个值刚被 401 拒绝，要换新的；换不了抛 Auth / SignInExpired
}
fun interface BlobSource {              // 读当前会话 BlobStore 里 Part.Image 的字节
    suspend fun bytes(ref: BlobRef): ByteArray   // 缺失或损坏抛 IOException，适配器转成 InvalidRequest
}
```

- 密钥不进日志、异常消息和 `toString`。服务商的响应可能回显密钥：错误体、流内的 error 事件、解析失败的原文和 cause、OpenRouter 的 `metadata.raw`，进入 `ModelError`、探测结果之前一律脱敏（`core.model.openai.Redactor`）。响应头也算服务商的原文：OkHttp 校验服务端发来的值时抛的 `IllegalArgumentException` 会整段引用被拒的值，所以 `Retry-After` 搬运时不做值校验、原样保留，HTTP 不允许的值（比如含非 ASCII 字符）解析不出等待时间，就当没有这个头；传输边界再把 OkHttp 的这类异常换成固定文字的 `BadResponse`，不带 cause，并取消调用、关闭已经拿到的响应。JSON 解析失败时（流的数据行、整段 JSON 回复、模型列表），错误只说哪一种数据不是 JSON、有多长，不引用原文（见下一条的"诊断不引用原文"），kotlinx 的异常也不进 message、不挂成 cause：它的 message 会按固定宽度引用出错位置附近的输入（`JSON input: …`）。本次调用发出过的每个密钥（套餐模式刷新前被拒的旧令牌也算）都换成 `***`，原样的和标准 JSON 转义后的（`"` → `\"`、`\` → `\\`）都算，再按形状兜底（`Bearer …`、`sk-…`、JWT）；错误体先解析、后按形状兜底，顺序见下面"诊断不引用原文"第二类。错误码和类型只用来匹配，脱敏会改动它们时就当没有；`param` 按服务商写的字段处理。成功的回复也一样，都用含本次实际请求密钥的 redactor 处理；"测试并保存"的诊断不引用模型的回答和试调用参数，只说哪里不对、文字有多长、参数是什么形状；**业务标识不改写**，列出的模型 id 和 `providerMetadata` 里服务端回显的 id、model、结束原因、状态，只要脱敏会改动它（`Redactor.verbatim` 为 `null`），就整条（整项）省略，不输出 `***` 形式的假 id。"测试并保存"生成 `Passed` 之前，列表再按这一轮发过的全部密钥（包括取得列表以后才换上的 key）过滤一次。`candidateListed` 在内部用通过合法性校验的原始 id 比较，原始 id 不进任何结果。
- **模型产生的内容里出现本次请求的密钥，整条回复按 `BadResponse` 拒绝，不改写**（和 WP04 同一条规则，WP01 A11 写进契约）。模型产生的内容包括正文、调用参数（包括从正文还原出来的调用）、调用 id 和名字、推理摘要、推理的 opaque（Responses 的 `encrypted_content`、Chat 的推理字段；成员名和值都查）。opaque 按读到的写法查一次，再按日志写回的写法查一次：日志和由日志重建的请求用的是 kotlinx.serialization 写回的文字，它会改写数字（`1.23456e5`、`1.23456E+5`、`12345.6e1` 都写成 `123456.0`），所以很短的纯数字 key 可能只在写回的写法里出现。写不回去的数字（`1e400` 超出 double 的范围，日志根本记不下这条回复）让整条回复报 `BadResponse`，固定文字，不引用值。只有 Responses 的推理条目保留模型的 JSON 并原样写回；Chat 的推理是文字，调用参数按收到的文字保留，不重新写数字。
  - 为什么不改写：改写调用参数会改掉要执行的操作；我们从不把密钥放进上下文，模型输出里出现密钥本身就说明出了问题，宁可失败，也不落盘。
  - 出口原则：凡是会到达出口的模型内容都要检查，出口包括 delta、最终回复的 parts、日志、诊断文字和下一次请求；在到达任何出口之前就被丢弃的内容，不要求检查（WP04 连被放弃的块也查，比这条最低要求更严，两者不冲突）。OpenAI 适配器丢弃的路径，每条都有测试证明它的内容（原样的和转义写出的密钥）不出现在发出的 delta、最终的 parts 和按日志形状序列化后的回复里：Chat 一个 chunk 里第一个以后的 choice、旧式的 `delta.function_call`；Responses 不认识的 output 条目（`web_search_call`、`custom_tool_call` 等）、`response.output_item.added` 条目里的参数、`response.function_call_arguments.done` 里的参数、没有对应调用的参数增量。一个 chunk 只带一个 choice 时不看它的 `index`，按第一个 choice 读，内容到达出口，照常检查。被最终 output 替换掉的流式参数已经作为 delta 发出过，属于到达出口，按下面"已显示的参数整段再查"处理。
  - 诊断不引用原文：诊断文字也是出口。错误消息、异常的 message 和 cause 链、`ProbeResult`、日志和诊断字段，都不引用模型或服务端回复的原文，只给固定文字加结构信息：缺了哪个字段、第几个条目或调用、哪种事件、认识的取值（比如 `status` 是 `failed`），或者内容的类型和长度（`Unquoted`）。原因：解析器没读过的原文里，转义（`\u0070`、`\/`）能把密钥藏过脱敏。回复照旧整条拒绝，报 `BadResponse`；已经作为 delta 发出的参数照样按下面的异常结束规则查。分三类：
    - 模型回复里的原文，一律不引用：写坏的数据行、缺 `call_id` 或 `name` 的调用、用不了的调用 id、不认识的 `status` 和 `incomplete_details.reason`、"测试并保存"里模型的回答和试调用参数、不是 JSON 的模型列表。回复里的值违反中性类型的约束时（比如调用 id 含控制字符），错误只说违反了约束和异常类名，`IllegalArgumentException` 的 message 不进错误，cause 只留类名和堆栈。
    - 服务商的错误说明：先解析、后按形状兜底。读错误体时只把本次密钥的精确形式（原样的、标准转义的）换成 `***`，形状留到解析、解码以后再匹配：形状的匹配停在第一个转义处，读取时就匹配，只能盖住转义之前的半截，解码以后剩下的后半截既不是完整的密钥，也不成形状。服务商写的文字，按字段读出的（message、OpenRouter 的 `metadata.raw`、`param`、`{"error": "…"}` 的字符串；流内 error 事件和 `response.failed` 的也一样）和没有按字段读过的（不是 JSON 的错误体、重定向的目标地址）一样，都按 `Redactor.quote` 处理，规则和 WP04 相同：（1）原样和标准转义的本次密钥换成 `***`，其余说明保留；（2）剩下的文字宽松解码一次，解码没有改动任何文字，就按形状打码后引用（`sk-…`、JWT 和 `Bearer` 后面的 token 换成 `***`），没有可解码的转义时，在原文上打码就是完整的；（3）解码改动了文字，而且解码后出现本次密钥的任一写法，或者 `sk-`、JWT 形状，就整段换成固定说明（`Redactor.WITHHELD`），不留半截，否则照旧打码后引用；（4）`Bearer` 字样只打码它后面的 token，不触发整段替换，所以 `Invalid bearer token` 显示成 `Invalid bearer ***`，用户仍然看得到"key 无效"。字段经过了 JSON 解码，但字段自己的文字里还可能有一层转义（字面的 `\u0073k-…`），所以要再解一次。JSON 里没有 message 字段、或者根本不是错误对象时，只能整段引用：其中每个字符串（成员名和值）解码后按同样的规则查，有一个不能显示就整段换成固定说明，否则整段写回并打码。不直接查写回的整段，是因为写回时字符串会重新转义，字段文字里字面的转义多了一层，只解一次认不出来。
    - OkHttp 的 `IOException`：它的 message 会引用服务端的原始字节（读不懂的状态行；读不完的头部行按十六进制写出），所以 `Network` 的说明按异常类型给固定文字加类名，cause 只保留类名和堆栈，不带 message。WP04 的口径相同。会引用回复内容的三种情况都有本机原始 socket 的测试（两种协议，加上"测试并保存"的结果）：分块长度写短，下一行被当成块长度引用；`Content-Length` 写少、连接被复用，多出的行被当成下一个状态行引用；body 截在行中间，读到的内容按十六进制写进 `EOFException`。
  - 只做精确匹配：原样的和 JSON 转义后的形式都算，不按形状兜底，否则屏幕上正常出现的 token 也会被误拒。"测试并保存"一轮里发过的旧密钥也算。
  - 调用参数在每个调用结束时按**完整文本**查三种形式：原样的；能解析成 JSON 时，按标准转义重写后的；宽松解码后的（`JsonEscapes.lenientDecode`）。宽松解码不要求文本是合法 JSON：从左到右解一遍 `\uXXXX`（包括代理对）、`\/`、`\"`、`\\`、`\n`、`\t`、`\r`、`\b`、`\f`，先解 `\\`（所以 `\\u0070` 是一个反斜杠加 `u0070`，不会再解成 `p`），不认识的序列、不完整的 `\u`、落单的代理原样保留；结果只用来匹配，不改写内容。参数被截断（`MAX_TOKENS`）或者写坏（比如多一个 `}`）时，工具层照样会按 JSON 解码它们，所以转义写出的密钥照样要查出来。查的是完整文本，转义被切到几个片段里也不影响结果。从正文还原出来的调用，参数走同一套检查。WP04 有一份同名、同规则、同测试数据的 helper，两边合入后归进公共传输层。
  - 回复在终止事件之前失败时（流断开、解析失败、流内 error 事件，或者别的错误），先对已经作为 delta 发出的每个调用的参数整段做同样的检查，查到密钥就报带标记的 `BadResponse`，不报原来的错误（比如 `Network`）。日志不受这条影响（中断前缀只记文字 delta）；这条是为了让界面撤回已经显示的参数增量。正常结束时也对已显示的参数整段再查一次：Responses 的最终 output 可能替换掉流式参数，被替换掉的增量已经显示过了。
  - 已知缺口：正文、推理文字、还原失败后留在正文里的标记，以及 DSML 里没闭合的字符串参数值，都是字面文字，下游不会把它们当 JSON 解码，字面的反斜杠写法不是密钥，所以不做宽松解码。具体有两种情况：DSML 参数里没闭合的字符串，会把转义原文按字面写进 argsJson（重新转义成字符串值，解码后仍然是反斜杠文字）；截断的 `<tool_call>` 标记还原不了，原样作为正文留进日志。正文里用转义、空格、编码等方式改写过的密钥不检测：检测只认原样的、标准 JSON 转义的，以及调用参数宽松解码后的形式。另外，检测按通道分开：尾部窗口和结束时的整段再查都是每个通道（正文、推理、每个调用的参数）各查各的，如果一个密钥被拆在两个通道里（比如正文一半、推理一半），或者拆在两个调用的参数里，就检测不到；只有每一半都另外凑成某个密钥时，才会被拦下。usage 的计数读成有类型的整数，不和密钥比较：纯数字的 key 作为计数出现时，会进 usage 和日志；真实的 key 不会是纯数字。key 是很短的纯数字时，可能和推理条目里的计数、编号重合（数字也按子串匹配，key 是 `1` 时任何含数字 1 的值都会命中），含这些数字的 opaque 会让整条回复被拒。服务商写的文字按写出的样子查，再宽松解码一次查（按字段读出的先经过 JSON 解码）；用更多层转义写出的密钥认不出来。按形状打码时，形状只认字母、数字、`-` 和 `_`：不是本次请求发的 `sk-` key 如果带了别的字符（比如 `/`），没有可解码的转义时原地打码，只盖到那个字符为止，后面的部分可见；服务商的 key（`sk-proj-…`）只用这些字符，本次的 key 不论含什么字符都按精确形式打码。`Bearer` 和 token 之间的空白如果是用转义写的（`Bearer\u0020…`，只出现在多套一层转义的文字里），原文上认不出这个 token：token 是本次密钥或凭据形状时整段换成固定说明，否则照原样可见。这两条和 WP04 共用。错误体在 64 KiB 读取上限处截断时，如果截断点恰好落在用转义写出的 key 里，截下来的前半截既不是完整的 key（精确匹配认不出），解码以后也不一定成形状，这半截会留在错误里：只有服务商在超过 64 KiB 的错误体里用转义回显 key、截断点又正好落在其中时才会发生，代价是错误信息里露出 key 的前一部分（和 WP04 共用这一条）。
  - 增量边发边查，不推迟发布：每个通道（正文、推理、每个调用的参数）保留一个"最长形式减 1"的尾部窗口，凑出密钥的那一片不发出，之前的片段照常即时显示；流结束时再查最终的各个 part。
  - 拒绝用固定文字，带"已流出内容不保留"的标记：runtime 不把这次 attempt 已经流出的前缀记成 interrupted 的消息，只记 attempt 和固定原因；界面上已经显示的片段由 UI 按这个错误清掉。标记是 WP01 的 `ModelError.discardsStreamed`（`CredentialEcho.error()` 设上它）。
- 密钥不论多长都做精确匹配：设任何长度阈值，短的真实凭据都会直接漏出去，而 `test` 这类值按形状也认不出来。不需要认证的本地服务（Ollama、LM Studio 等）用 `Credential.None`，不要填占位 key：本地服务如果填了占位 key，它就和真密钥一样处理：在错误和诊断里被屏蔽，含它的模型 id（比如 key 填 `qwen`、模型叫 `qwen3:8b`）不出现在列表里，出现在模型的回复里会导致整条回复被拒。
  - "测试并保存"替用户指出 key 本身的问题，`ProbeResult.Failed.reason` 是有类型的原因，界面据此提示，不比对文字：key 是试调用请求自身固定内容的子串时（token、工具名、描述和 schema、提示文字、正确回复的参数 `{"token":"…"}`；比如 key 填 `token`、`pa`、`a`），任何正确的回复都会回显它，所以不发任何请求，直接返回 `CREDENTIAL_IN_TRIAL`；试调用的回复因为回显密钥被拒时，返回 `CREDENTIAL_ECHOED`，文字就是固定说明，不再过 Redactor（它不含服务商原文，屏蔽它只会把 1 个字符的 key 变成乱码）。
  - WP09/WP10 的义务：`needsKey = false` 的预设（Ollama、LM Studio）默认用 `Credential.None`，不显示 key 输入框；出现 `CREDENTIAL_IN_TRIAL` 或 `CREDENTIAL_ECHOED` 时，提示换一个 key，或者改成不需要 key。
- 任何截断都发生在用完整密钥集合脱敏之后。错误体最多读 64 KiB：多读"最长密钥长度（JSON 转义后的形式也算）减 1"个字节，把读到的全文里本次密钥的精确形式换掉（形状在解析以后才匹配，见"诊断不引用原文"第二类）；正文还没读完时，去掉末尾被读取切开的 UTF-8 字符和可能是密钥开头的部分（某个密钥的前缀，或者太短、还没成形的 `sk-…`、JWT），再按字符截到上限，不切开代理对；截掉了文字时，截下来的部分末尾再按同样的规则去掉一次（跨在上限上的形状被截成太短、认不出形状的开头）。"测试并保存"一轮里先后发出的每个密钥（换过的 key 也算）从每个请求一开始就进它的 redactor，所以 500 字的错误 detail、300 字的重定向地址和诊断片段，截断之前都已经用全部密钥脱敏过。
- **脱敏**（本次请求用到的密钥；契约写在 `CredentialResolver` 的 KDoc，WP03、WP04 各自实现，两边规则相同）：
  - **模型产生的内容一律不改写**。密钥出现在其中任何地方（正文、调用参数、推理预览、opaque 和签名块：Anthropic 的 `thinking`、`redacted_thinking`、`tool_use`，OpenAI 的 `reasoning`、`encrypted_content`，以及其他协议里的同类内容），**整条回复按 `BadResponse` 拒绝**，返回固定文字，并设上 `ModelError.discardsStreamed`。不改写的原因：Anthropic 把 thinking 绑定到它之前的整段对话，改写已经返回的正文或 `tool_use.input`，下一次请求就会报 prefix mismatch；改写调用参数还会改掉要执行的操作。我们从不把密钥放进上下文，模型输出里出现密钥本身就说明出了问题。
  - **`discardsStreamed`**：这次 attempt 已经流出的内容不保留。runtime 不把它记成 interrupted 的 `assistant/message`，只记 `assistant/attempt` 和固定原因；界面上已经显示的片段由 UI 按这个错误撤回或标记作废（WP09）。
  - **预览 delta 不推迟发布**：适配器收到片段就交给 `onDelta`，不为了检测密钥先扣住。delta 只用来显示，从不进日志；出现带 `discardsStreamed` 的错误时，已经流出的片段同样不留在日志里，界面按上一条撤回或标记作废。
  - **匹配方式**：只做精确匹配，同时匹配密钥的 JSON 转义形式，不按形状兜底。不设最短长度，所有密钥都匹配：任何阈值都会让短的真实凭据漏出去。不需要认证的本地服务用 `Credential.None`，不要填占位 key。
  - **服务端写的诊断字段**（错误正文、拒答 category、metadata）：本次密钥的原样和标准转义形式改写成 `***`，其余说明保留；宽松解码改动了文字、解码以后才现形的本次密钥或者 `sk-`、JWT 形状，整段换成固定说明（`Redactor.WITHHELD`），不留半截；没有可解码的转义时，形状在原文上打码，`Bearer` 字样只打码后面的 token，都不触发整段替换。OpenAI 适配器的具体顺序见上面"诊断不引用原文"第二类。
- **流式增量**：`Delta.Text` 依次拼起来一定是最终正文的前缀；推理和工具调用的预览不作保证，服务端降级时可能不出现在最终回复里，界面以最终的 `ChatResponse` 为准重新渲染。
- blob 按会话存放：每个会话建一个 ChatModel，或者用会话感知的 `BlobSource`，组装时决定。

**线路快照**（`core.model.WireProfile`，随 `ChatRequest.wire` 传给适配器，冻结在 `request/header` 和 `side/request` 里）：

```kotlin
data class WireProfile(
    val mappingVersion: Int,                    // 适配器映射规则的版本：同样的输入会发出不同字节时就升
    val credential: CredentialKind,             // API_KEY / CHATGPT_PLAN / NONE，只记种类，不记密钥引用的解析值
    val headers: Map<String, String> = emptyMap(),        // 非秘密的额外 header，名字不能像凭据
    val images: ImageMode = ImageMode.INLINE,             // INLINE 原图 / PLACEHOLDER 换成固定的占位文字（没有 vision）
    val reasoningField: String? = null, val echoReasoning: Boolean = false,   // Chat Completions 的推理字段与回传
    val reasoningEffort: WireSetting? = null,   // 推理档位映射后的字段和值（如 reasoning.effort = high）；null = 不发
    val maxTokensField: String? = null,         // max_tokens / max_completion_tokens / max_output_tokens；null = 不发上限
    val disableParallelToolCalls: Boolean = false,      // true：Chat/Responses 发 parallel_tool_calls:false，Anthropic 发 disable_parallel_tool_use:true；false 两者都不发
    val streamOptions: Boolean = false,
    val includeEncryptedReasoning: Boolean = false,       // Responses：include: ["reasoning.encrypted_content"]
    val recoverLeakedToolCalls: Boolean = false,          // 把漏成正文的工具调用还原（§3.1）
    val options: Map<String, String> = emptyMap(),        // 还没有专门字段的其他设置，小写名字，不放秘密
)
```

- **适配器只从 `request.wire` 读这些项**，不读实时的 `ProviderProfile` 或 `ModelCapabilities`：它们在任务中途可能被改。profile 只提供 endpoint 和凭据引用。
- 适配器在发送前拒绝两种请求（`InvalidRequest`）：`credential` 的种类和 profile 不一致；`mappingVersion` 不是它实现的版本。
- **线路快照的唯一生成入口是 `ChatModel.prepare(reasoning): RequestSettings(reasoning, wire)`**（`core.model`）：
  ```kotlin
  interface ChatModel {
      val info: ModelInfo
      fun prepare(reasoning: ReasoningLevel?): RequestSettings   // 纯函数，不抛；同一实例对同一档位结果相等，集合是副本
      suspend fun complete(request: ChatRequest, onDelta: (Delta) -> Unit = {}): ChatResponse
  }
  data class RequestSettings(val reasoning: ReasoningLevel?, val wire: WireProfile)
  ```
  - 适配器（WP03/WP04）实现它：按 `clampTo` 把档位钳制到 `info.capabilities.reasoningLevels`，再按自己 `mappingVersion` 的规则映射成 `reasoningEffort` 等线路项。钳制和映射在同一步完成，调用方不自己算。`ProviderProfile.wire(mappingVersion)` 是给适配器用的辅助函数，填来自 profile 的部分（凭据种类、headers 副本、推理字段与回传）。
  - 调用方：主循环（WP02）写 `request/header` 前调用，能力包写 `side/request` 前调用；两者把 `reasoning` 放进 `ChatRequest.reasoning`，把 `wire` 放进 `ChatRequest.wire`，并原样记进日志。**回放不调用 `prepare`**，两项都从日志取。
  - `ModelRouter` 把任务绑定到模型时，把 profile、能力和映射版本冻结进 ChatModel 实例，所以任务中途改设置不会改变 `prepare` 的结果。
  - 适配器测试继承 core 测试源里的 `ChatModelContract`（稳定性、钳制、`prepare` 的结果能被 `complete` 接受、外来 `mappingVersion` 或凭据种类在发送前被拒；`complete` 不改动已经给出的 settings（包括发出去的那份，原地修改也算）和之后 `prepare` 给出的 settings）。被测模型必须声明档位的真子集（不能是全部档位，也不能是 null），让某个档位真的被钳制，否则"先钳制再映射"查不出来，契约直接失败；真实模型档位未知时，测试里用声明了真子集的能力构造适配器。`ChatModelContractStrengthTest` 用带缺陷的 fake 证明这些检查会失败。
- 回放时，同一个 `mappingVersion` 的规则加上日志里的请求，必须逐字节重建当时发出的请求。
- OpenAI 适配器（WP03）的 `mappingVersion` 是 1（`OPENAI_MAPPING_VERSION`）。`options` 只有 Chat 的 `leaked_call_formats`：开启还原的格式，按枚举顺序、逗号分隔，必须和 `recoverLeakedToolCalls` 一致，也必须是这条线路允许的格式。本协议不用的项必须是默认值：Chat 的 `includeEncryptedReasoning`，Responses 的 `reasoningField`、`streamOptions`、`recoverLeakedToolCalls`。档位映射：Responses 和 OpenRouter 风格发 `reasoning.effort`，其余 Chat 发顶层 `reasoning_effort`，不发档位的线路为 `null`。不满足这些规则的快照在发送前报 `InvalidRequest(param = "wire.…")`，不发请求。

---

## 3. 各协议要点

### 3.1 Chat Completions 兼容（DeepSeek、通义、Kimi、GLM、豆包、硅基流动、OpenRouter、Ollama、LM Studio …）

- **工具调用**：`tools[].function` + `tool_choice:"auto"`。主循环每轮只执行一个调用，所以服务商支持时适配器在 `prepare` 里设 `wire.disableParallelToolCalls = true`，线路上发 `parallel_tool_calls:false`。如果模型仍然返回多个调用，第一个之外的每个都要配一条结果"已跳过：每步只执行一个动作"，否则下一次请求会被拒。调用的 `arguments` 不是字符串时不静默改写（Chat 和 Responses 相同）：缺省或 `null` 记成 `{}`；对象原样序列化（有的网关发对象）；数字、布尔、数组按原始 JSON 文本保留为 argsJson，交给工具层的边界校验给模型反馈"参数无效"。这些参数和字符串参数一样经过回显检查。
- **流式**：`stream:true` + `stream_options.include_usage`；要兼容某些网关返回 `usage:null` 的情况。
- **连接复用**（OpenAI 适配器的 HTTP 客户端，Responses 也用它）：默认不复用空闲连接，每个 attempt 新建连接（`HttpTimeouts.idleConnectionKeepAlive`，默认 `Duration.ZERO`，加载时校验，范围 0 到 5 分钟）。原因：服务端可以不打招呼（不带 `Connection: close`）关掉空闲的 keep-alive 连接，而 OkHttp 只对空闲满 10 秒的连接做健康检查；连接失败重试又是关着的（保住"一个 attempt 只发一个请求"），所以复用一条已经被关掉的连接，这次请求一定失败，白白占掉 `requestError` 的一次重试和一段退避。常见的本地服务（uvicorn、Node http、cpp-httplib）空闲超时在 5 秒左右，正好落在两步之间的间隔里。多一次 TCP/TLS 握手，和模型的延迟比可以忽略。**什么时候可以调大**：已经知道服务端的空闲超时比这个值长时（比如自己部署、配置可查的服务）。**调大以后仍然会碰到**：服务端的超时比这个值短，或者服务端在超时之前因为别的原因（重启、负载均衡切换）关掉连接时，下一次请求照样失败一次；这个剩下的竞争窗口交给 `requestError` 重试，不打开 OkHttp 的连接失败重试。
- **结束原因**（`finish_reason` → `stopReason`）：`length` / `max_tokens` → `MAX_TOKENS`；`content_filter`、`sensitive` 和 `refusal` 报 `Refusal`，带不带调用都一样。正常结束（`stop`、`end_turn`、`eos`、`tool_calls`、`function_call` 或没有原因）时，带调用就是 `TOOL_USE`（Ollama 带调用时也报 `stop`），不带调用就是 `END_TURN`（`tool_calls` / `function_call` 不带调用是 `OTHER`）。不认识的值一律 `OTHER`，带着的调用不执行。
- **推理内容**：字段名和"要不要回传"各家不同（`reasoning_content` 等）。在 `ProviderProfile.reasoningField` 和 `ProviderProfile.echoReasoning` 里按服务商配置，由能力探测确认；**探测不了的标"未验证"**。写请求头时这两项冻结进 `wire`（§2"线路快照"）。
- **工具调用漏成文字**：有的模型会把工具调用写成正文里的标记（DeepSeek DSML、`<tool_call>` 等），参考 Mika 的 `LeakedToolMarkup`。还原**默认关闭，所有预设（包括 DeepSeek、通义、Ollama）都不打开**：还原出来的调用没走服务商的工具通道，而 PolicyEngine 现在还不能对还原出来的调用要求用户确认。以后作为用户显式开启的兼容选项提供（UI 由 WP09 决定）。预设的 `leakedCallFormats` 记下这条线路有证据会漏出的格式（DeepSeek 是 DSML，通义和 Ollama 是 `<tool_call>`）。`ChatQuirks.recoverLeakedCalls` 只能开启这条线路允许的格式（`WireOptions.allowedLeakedCalls`）：预设线路的预设按 `WireOptions.defaultsFor` 的方式解析（指向官方 host 的自定义 profile 也算这个预设），允许的就是预设的 `leakedCallFormats`，线路自己不能再声明；自定义线路默认什么都不允许，必须在 `ChatQuirks.leakedCallFormats` 里显式声明实际会用到的格式，开启的格式必须是声明的子集。创建模型和开始"测试并保存"时校验，不满足就抛 `IllegalArgumentException`，不发请求。设置界面（WP09/WP10）的开关只能列出这条线路允许的格式。开启后，下面几条都满足才还原，否则正文原样保留：
  - 本次请求声明了工具，原生 `tool_calls` 里没有调用；
  - 整段正文去掉空白以后，只由某一种已开启格式的完整调用块组成：DSML 是 `function_calls` 外层里的闭合 invoke；`<tool_call>` 要有 `name` 和对象形式的参数，而且调用外壳的键集合只能是 `{name, arguments}` 或 `{name, parameters}`，多出任何别的键（比如 `note`），或者两种参数键同时出现，都保留正文。这条只管外壳，不管参数对象里有哪些键，也不读参数里的自然语言去猜要不要执行；
  - 前后有任何说明、引用、示例前缀或"不要执行"之类的话，都不还原；两种格式混在一起也不还原；
  - 每个名字都在 `request.tools` 里，全有或全无。

  判断不了就保留正文。还原出的调用 ID 记在 `providerMetadata.recovered_tool_calls` 里（会进日志）；"测试并保存"时，试调用如果是从正文还原的，算失败。
- **缓存**：靠服务端隐式前缀缓存，所以历史要尽量只追加；命中情况从 usage 里读。
- **实现约定**（`core.model.openai`，WP03）：
  - `ToolResult.isError` 在 OpenAI 两种协议里都没有对应字段，失败只靠结果文字说明。
  - `tool` 消息只能带文字：连续的若干条 TOOL 消息算一组（回答同一轮调用的结果可能分在几条消息里），组里所有结果的图片在这一组 `tool` 消息全部结束之后用一条 user 消息补上（每个结果以 "Images from tool call <id>:" 开头），中间不插别的消息；只有图片的结果，`tool` 消息写占位文字。不支持看图的模型，图片一律换成固定占位文字，保证前缀稳定。
  - 线路选项（`WireOptions`）是创建模型时传入的固定输入，适配器运行时不协商、没有可变状态：一次 `complete()` 只发一个请求，同一个模型对同一请求发出的线路总是一样，所有重试都归 `requestError`。
  - 可选字段 `parallel_tool_calls`、`stream_options` 发不发（`WireOptions.chat`），只在"测试并保存"时协商：试调用被服务商以 HTTP 400 或 422 拒绝，并且结构化的 `error.param` 点名该字段（没有 `param` 时看服务商的消息有没有点名）时，去掉它再试一次，每个字段最多一次。别的状态码、流里的错误事件和适配器自己写的文字都不算拒绝：3xx 的 detail 里虽然有目标地址（可能带着字段名），试调用照样直接失败。试调用通过时用的选项写进 `ProbeResult.Passed.wire`，由 app 跟 profile 一起保存，创建模型时传入；`OpenAiAdapters.model(...)` 的 `wire` 参数没有默认值，没探测过的线路要显式传 `WireOptions.defaultsFor(profile)`，这样保存的协商结果不会因为漏传而被默认值替掉。运行时再遇到这类 400，就是不可重试的 `InvalidRequest(param)`，UI 可以据 `param` 提示重新测试。
  - 漏成文字的工具调用，ID 由适配器生成。打开还原时，正文还是空白或者以 `<` 开头的期间，流式预览先扣住不发（它还可能整段都是调用，还原后正文为空）；一旦出现别的开头，正文只会原样保留，就全部放出。所以预览发出去的永远是最终文本的前缀，不管流在哪里断开。流结束时用 `DeltaLedger` 把预览漏掉的内容补发，保证增量拼起来和最终回复一致（文字、推理摘要、调用参数）。有的网关把调用名拆在几个片段里发，所以一个调用只有在它自己的参数开始以后，名字才算完整；调用按顺序公布（`ToolCallStarted`），前面的调用没公布，后面的就先等着，不会因为后一个调用开始传参数，就把前一个的半截名字发出去。Responses 的推理有摘要和原始推理文字两个通道，先出现的那个通道既用于增量也用于最终的摘要，两者不拼在一起。只有服务商的最终输出和它自己发的增量矛盾时才会不一致：增量收不回来，以最终回复为准，`providerMetadata.preview_diverged` 记下不一致的通道（`text`、`reasoning`、`calls`、`args`；已公布的调用按公布顺序必须正好是最终调用序列的开头，消失、换了名字或者顺序不一致都算 `calls`，只在后面新增调用不算），UI 应该用最终回复替换预览。
  - 发送前校验对话：每个工具调用都必须有结果，不能有孤立的结果；服务端上下文编辑只支持 Anthropic。不满足时直接报 `InvalidRequest`，不发请求。
  - 预设的已知差异写在 `ProviderPresets` 里，每条注明 VERIFIED / DOCUMENTED / UNVERIFIED。没有预设的 profile 用默认线路选项；指向某个预设官方地址的自定义 profile 用该预设的选项（`WireOptions.defaultsFor`）。
  - 输出上限的字段名是线路选项 `ChatQuirks.maxTokensField`：官方 OpenAI 用 `max_completion_tokens`（`max_tokens` 已弃用），兼容网关默认 `max_tokens`。

### 3.2 OpenAI Responses

- **请求结构**：`instructions`（系统提示词）+ `input[]`。input 的条目类型有 message、`function_call`、`function_call_output`、`reasoning`。对话里的 system 角色一律改成 developer 消息。
- **无状态**：`store:false`，**不用 `previous_response_id`**，每次都发完整的 input。
- **推理**：`include:["reasoning.encrypted_content"]`，把返回的加密推理条目原样保存，下一次请求原样回传（Mika 的做法）。用户选了档位才发 `reasoning.effort`。
  - 只回传同一 provider、同一 model 产生的加密推理（`RESPONSES_ENCRYPTED`，带 `encrypted_content`）：别的 model 能不能读旧 model 的加密推理没有保证，所以同一 provider 换了 model 以后，旧 model 的推理条目不发，别的 provider 的也不发（`Part.Reasoning` 的契约把这条规则交给适配器）。Chat Completions 的推理字段按同样的规则回传（§3.1"推理内容"，还要 `echoReasoning`）。
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

- **实现约定**（WP03）：
  - 发不发 `include`（`WireOptions.responses`）也只在"测试并保存"时协商：只认结构化的 `error.param == "include"`（这个词太常见，不看消息；`input[n].encrypted_content` 之类的错误不算）。运行时拒绝 `include` 的 400 是 `InvalidRequest(param = "include")`，和其他 400 一样交给上层。
  - 套餐模式遇到 401，向 `CredentialResolver` 要一次新令牌（`rejected = true`）后重发；再次 401 报 `SignInExpired`。
  - 流的终态：只有带 response 对象的 `response.completed` 或 `response.incomplete` 才算流结束，在它们之前断流报 `Network`。流的终态事件和整段 JSON 回复走同一套终态校验，不过就报 `BadResponse`：终态事件必须带 response 对象；状态以事件名为准（整段 JSON 以 `status` 为准，必须是 `completed` / `incomplete`），`status` 出现时必须和事件名一致（`completed` 事件里是 `in_progress` 不行，缺省或 `null` 可以）；`output` 出现且不是 `null` 时，必须是数组，每个条目都是带字符串 `type` 的对象，形状不对不会被静默过滤。`incomplete` 先按原因分类：`max_output_tokens` → `MAX_TOKENS`，`content_filter` → `Refusal`，其他或缺原因 → `OTHER`；不管回复里有没有工具调用，都不会变成 `TOOL_USE` 或 `END_TURN`。套餐模式按官方要求只认 `response.completed`（上表"流"一行）：`incomplete` 里除了 `content_filter` 报 `Refusal`，其余都报 `BadResponse`。
  - 回复的内容按 `output_index` 排序：流里 `response.output_item.done` 收到的条目和最终 `response.output` 里的条目按位置合并，只在一边出现的条目也保留。同一位置有两个版本时：
    - 逐个字段以最终 output 为准，但 JSON `null` 不覆盖有值的字段（`null` 和缺省等同）；推理条目是整份不透明的回放数据，其余字段都保留。
    - 只比较有语义的字段，不一致报 `BadResponse`：类型；message 的每个 content 的 `type`、`text`、`refusal`；function_call 的 `call_id`、名字、参数（条目 `id` 不比较，流式阶段仍用它关联参数增量）；reasoning 的 `id` 和加密内容。
    - 所以流里是空 content、最终是拒答时报 `BadResponse`，拒答不会被空 content 静默抹掉；一边缺 content 时，以有的那边为准。
  - 带图片的 `function_call_output`，`output` 用 `input_text` / `input_image` 数组。

### 3.3 Anthropic Messages

- **实现方式**：用官方 SDK `com.anthropic:anthropic-java`（Kotlin 直接用 Java SDK），提供 baseUrl 的服务商（Anthropic 兼容端点）通过 base URL 覆盖接入。
  - **M1 先做一个 spike**：验证 minSdk 30 下能否运行、APK 增加多少体积、和 R8 是否兼容。不过关的话，再评估用 OkHttp 直接实现线路格式，并记一条 ADR。
- **默认模型**：主模型 `claude-opus-5-5`，可选 `claude-sonnet-5-5`；轻量模型 `claude-haiku-5-5`；看图模型默认用主模型。模型清单以服务端 `/v1/models` 返回的为准，本地只做初值。
- **思考**：
  - Opus 5.5 的思考始终开启：不发 `thinking`，或者发 `{type:"adaptive"}`。**`budget_tokens` 和 `disabled` 都会被拒（400）**。
  - 思考深度用 `output_config.effort` 控制。**Opus 5.5 的默认值是 `medium`**，所以按用途显式设置：主模型做 GUI 任务时建议从 `high` 起调，轻量判断用 `low`。
  - 要给用户看思考进度时，设 `display:"summarized"`。
- **思考块的回传**：thinking、redacted_thinking 和 compaction 块（包括 signature）原样保存、原样回传。**换了模型也不在客户端删**：API 会自己略过当前模型读不了的块；客户端删掉的话，换回原模型时这些推理就永久丢了，从中间删块还会让后面的块报 `predecessor_missing`，compaction 块装着压缩后的历史，删掉就丢了历史。这也是铁律 7（历史只追加）。
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
  1. 调列模型接口。某个条目的 id 不能当模型 id 用（空白、超过 256 个字符、含控制字符）时只跳过这一条，其余条目照常列出，试调用照常进行；
  2. 用一个试用工具，让候选主模型真的调用一次（花几十个 token）。只有回复正常结束成工具调用（`stopReason == TOOL_USE`）才算通过：被输出上限截断、`incomplete` 等没正常完成的回复，即使带着正确的原生调用和 token 也算失败；从正文还原出来的调用也算失败；
  3. 两步都通过才保存，并写入 `verified` 能力；同时保存试调用通过时用的线路选项（OpenAI 两种协议：`ProbeResult.Passed.wire`，见 §3.1/§3.2），运行时只读它。
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

**恢复兜底**：如果收到 `PrefixBindingMismatch` 这类 400，去掉历史里所有的推理块（thinking、redacted_thinking，以及其他协议里 `ReasoningKind.isReasoning` 的块）后重试一次，并记日志（`context/reasoning-dropped`）。**compaction 块不去掉**：它装的是压缩后的历史，去掉它服务端就要重新处理压缩前的整段历史，可能撞上上下文上限，成本也会突增；文字和工具调用同样保留。这是一次性的恢复手段，不是常规路径。

**中断前缀里的 compaction 块**：被中断的回复（`assistant/message(interrupted=true)`）进入模型视图时，去掉其中所有的 `Part.Reasoning`，compaction 块也在内。回复不完整，其中的 compaction 块不回传，之后的请求由服务端重新压缩。视图是对日志的纯函数，前缀进入视图以后，每次请求里它的样子都相同，所以不违反"Anthropic 历史只追加"。契约写在 `SessionEvent` 的 KDoc（模型视图一节），WP17 复查。

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
