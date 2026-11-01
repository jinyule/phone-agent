# deepseek-harness（dsh）调研：AgentLoop 核心架构与工程体系

> 调研日期：2026-11-01｜仓库：[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
>
> 快照：`5badb15009`，版本 0.2.1-alpha.1，提交时间 2026-10-03，共 20,736 次提交。
>
> 方式：只读阅读源码与文档，未安装、构建或调用 API。关键结论对照代码抽查过；文档与代码不一致时以代码为准。
>
> 注意：DSH-Android 用的是 dsh 0.2.1，dsh-Go 用的是 0.2.1-rc.4，都比本地快照新。例如 SDK 的 `approval/request` 和 `user-question/ask` 是上游 rc.3 才加的，本地快照里没有（DSH-Android 的 `js/patches/08` 注明是从 rc.3 回移的）。

---

## 0. 结论

- **dsh 的形态**：一个"全插件"的 TypeScript agent harness，约 319 个包，基于自带的 Cordis 插件框架。**主循环本身很小**：`packages/core/agent-loop/src` 只有约 2400 行，扩展都挂在约 15 个固定的钩子点上。
- **最值得借鉴的四个设计**：
  1. **turn/step 两层状态机，配一个分"下一轮 / 下一步"两个队列的 inbox**（followup / steer / inject）。
  2. **在固定钩子点上做拦截链（waterfall）**：新行为通过钩子插入，主循环不改。
  3. **事件溯源的 session 日志**："模型可见 ⟺ 已记录"，即模型请求严格等于对日志做一次纯函数推导。由此可以回放、恢复，也可以拿日志当测试快照。
  4. **完整的失败语义**：保留中断时已输出的前缀；没启动的工具调用补写"未执行"，已启动但没结果的补写"结果未知"；在语义时刻把日志刷盘。
- **工程体系的核心**：
  - 规范尽量写成可执行的检查：约 70 个 `verify-*` 脚本，加 oxlint、tsc 和 per-file 100% 覆盖率。
  - **LLM 交互在领域事件日志层录制和回放**，不录 HTTP。
  - CI 用一个汇总 job 作为唯一的必过检查；发布只能手动触发，并经过 environment 审批，发布可安全重跑。
  - 仓库为 AI 编码代理做了专门设计：常驻规则文件、技能、文档字数预算。
- **不照搬的部分**：Cordis 框架、热重载与 YAML 叠加层、多代际格式迁移、多 provider 的 prompt 协调、PTC、多种 subagent 后端，以及为大团队设计的治理（基于 blame 的加权审批、双语配对门禁等）。
- **隐私提醒**：在官方 DeepSeek 接口上，dsh **默认**会在请求体里附带会话日志片段（`dsh_session_log`，文档原文 "enabled by default"）。

---

## 1. 整体形态：全插件的 Cordis harness

### 1.1 Cordis 的几个基本概念

- **服务注册**：Context 是一个服务仓库。`Service` 在构造时调用 `ctx.reflect.provide(name, this)` 注册自己，卸载时自动注销（`vendor/cordis/src/service.ts:42-58`）。
- **插件依赖**：插件可以是 `apply(ctx, config)` 函数，也可以是 Service 类；通过 `inject: [...]` 声明依赖，依赖没到齐时一直挂起。
- **"注册即副作用"**：`ctx.effect(fn)` 返回 disposer，`ctx.on()` 内部也登记成 effect（`vendor/cordis/src/events.ts:247-262`）。所以插件卸载时，它注册的东西会按序拆除。
- **五种事件派发模式**（`vendor/cordis/src/events.ts:183-245`）：

| 模式 | 语义 |
|---|---|
| emit | 同步调用，不等待返回的 promise |
| parallel | `allSettled` 并发执行，有失败则抛 `AggregateError` |
| serial | 按顺序逐个 await，**遇到第一个非空返回值就停下**（`docs/cordis-primer.md` 没写这一点） |
| bail | 同步执行，遇到第一个非空返回值就停下 |
| waterfall | 中间件链 `(…args, next)`。**不调用 `next()` 就截断后面的链**；`prepend` 可以排到最前 |

- **作用域**：每个 agent 有自己的子 context。挂在 `agent.ctx` 上的监听器只收到这个 agent 的事件，全局监听器收到所有 agent 的。
- **配置**：用 schemastery 声明 `static Config`，加载时校验；字段标 `.volatile()` 可以热更新而不重新挂载插件。

### 1.2 全插件的代价

- 一个行为分散在十几个监听器里，执行顺序靠 `prepend` 和注册顺序决定，调试时很难看清全貌。
- 大量 TypeScript 声明合并和类型体操；为热重载和卸载重入写了很多防御代码（vendor 有 23 处本地修改）。
- 运行时开销大：在手机上需要整套 Node。

**对我们的启示**：取"固定钩子点 + 拦截链"的思路，用编译期组合实现，不引入插件框架。

---

## 2. AgentLoop 主流程

### 2.1 层级与状态

- **四层结构**：turn（从 inbox 取出输入后的一次完整消化）⊃ step（一次模型请求，加上这次响应里请求的全部工具调用）⊃ attempt（同一 step 内的重试）；tool call 是单个工具调用（`docs/glossary.md:37-38`）。
- **驱动器状态**：`Phase = idle | maintenance | running{abort, turn, step}`（`packages/core/agent-loop/src/agent.ts:42-50`），驱动循环就是 `while (await this.turn()) {}`。
- **inbox 有两个队列**（`agent.ts:154-173`、`packages/core/agent-loop/src/inbox.ts:109-114`）：

| 方法 | 进入哪个队列 | 是否唤醒驱动器 | 语义 |
|---|---|---|---|
| `followup` | next-turn | 是 | 一条消息独占一个 turn |
| `steer` | next-step | 是 | 插话。不打断正在进行的流，在下一个 step 边界被取走；同时阻止本 turn 结束 |
| `inject` | next-step | 否 | 后台补充上下文，例如后台任务完成 |

  - turn 开始时取出全部 next-step 加 1 条 next-turn；step 之间只取 next-step。
  - inbox 的每次变化都写一条 `agent/inbox/spliced` 持久事件，所以排队的消息能扛住崩溃。

### 2.2 钩子点（按时序）

| 钩子 | 类型 | 时机 | 典型用途 |
|---|---|---|---|
| `system-prompt/assemble` | waterfall | step 提议前 | 改写 prompt 的段落、变量、工具 |
| **`agent/pre-step`** | waterfall | 每个 step 之前（`agent.ts:276-282`） | **拒绝本 step**；追加或改写上下文消息；压缩；刷盘 |
| `agent/request` | waterfall | 每次 attempt 之前（`agent.ts:576-579`） | 切换 provider、模型或推理强度；**不能改消息** |
| `llm/stream` | waterfall | 包住流式调用 | 先刷盘再发请求、录制回放；请求已深冻结，不可改写 |
| `agent/request-error` | waterfall | attempt 失败后，step 仍打开（`agent.ts:494-509`） | 返回 `{kind:'retry'}` 接管恢复（重试、溢出压缩、图片卸载） |
| `tools/pre-execute` | waterfall | 每个调用，按模型输出顺序 | allow / deny / cancel / ask（审批） |
| `tools.guard()` | 同步函数 | pre-execute 放行之后 | **不可被重排**的拒绝策略 |
| `tools/execute` | waterfall | 包住工具执行体 | 超时、刷盘、指标 |
| `tools/post-execute` | waterfall | 工具执行后 | 替换或拦截结果、附加上下文（大结果溢出存储、重复调用提醒） |
| `tools/result` | emit | 结果冻结后 | 只读观察 |
| **`agent/turn-stopping`** | serial | 本该自然结束且 next-step 为空时（`agent.ts:359-363`） | 想继续就调用 `agent.steer()`，不靠返回值否决 |
| `agent/status`、`agent/assistant-stream`、`session/event` | emit | 状态切换、流式分片、每次写日志 | UI 与 SDK 投影 |

### 2.3 时序（简化）

```
followup/steer → inbox(spliced 落盘) → 唤醒驱动器 → turn/start
  └─ 循环（每个 step）:
       取出输入 → system-prompt/assemble → agent/pre-step（可拒绝 / 可追加）→ step/start
       ┌─ attempt 循环:
       │   agent/request → prepareCall → 写 system/message*、user/message*（仅首次）
       │   → 写 request/header|context（有变化才写）→ deriveMessages() → 冻结 → llm/stream
       │   失败: 写 assistant/attempt → agent/request-error → retry? 回到循环顶部 : 抛出
       │   成功: 写 assistant/message
       └─ 无工具调用 → completed；撞到 max-tokens → 停止
       逐个工具: 写 tool/call → pre-execute →（ask → approval/request）→ guard
                → execute → post-execute → 写 tool/result → additionalContexts 进 next-step
       step/end → 可以结束且 next-step 为空 → agent/turn-stopping（可 steer）
  turn/end{reason} → inbox 还有消息则开下一个 turn，否则进入 idle
```

### 2.4 停止、并行与失败语义

- **停止条件**：
  - 助手消息里没有工具调用 → completed（`agent.ts:532-533`）；
  - 某个工具结果带 `concludesTurn`；
  - 撞到 max-tokens（丢弃这次的工具调用，不执行）；
  - pre-step 拒绝 → blocked。
- **主循环没有步数上限**（全仓搜不到 maxSteps）；Stop hook 强制续跑也没有上限，代码里留着 TODO。
- **并行工具调用**：
  - 只有工具声明了 `isConcurrencySafe(args) === true` 才进并行池，默认上限 10；其余的独占执行，起屏障作用（`packages/core/tools/src/index.ts:1303-1307`）。
  - pre-execute 和审批始终按模型输出顺序串行；结果也按模型顺序提交。
- **中断与失败**：

| 情况 | 处理 | 证据 |
|---|---|---|
| 流被中断 | 已交付给用户的前缀写成 `assistant/message{interrupted:true}`；什么都没交付则写 `assistant/attempt` | `agent.ts:448-477` |
| 工具阶段被中断 | 已启动的调用会排空等它们结束；未启动的补写 `ABORTED_BEFORE_DISPATCH` | `tool-calls.ts:238-260` |
| step 失败 | 写过 `tool/call` 但没有结果的，补 `TOOL_OUTCOME_UNKNOWN`；连 `tool/call` 都没写的，补 `TOOL_NOT_STARTED` | `agent.ts:342-353` |
| 崩溃后恢复 | 补写的结果带提示："结果未知，只读或幂等的操作才重试" | `packages/core/session/src/repair.ts:27-35` |
| 重试 | 在同一个 step 内重来，不重复 assembly、pre-step，也不重复写用户消息 | `agent.ts:406-424` |
| 插件故障 | 只结束当前 turn，不会打死驱动循环 | `agent.ts:255` |

---

## 3. 请求组装与缓存

- **请求是日志的纯函数**：请求 = 请求头配置 + `session.deriveMessages()` + 工具清单（`agent.ts:678-685`），没有单独的 `system` 字段。请求头只有变化时才写日志；`llm/stream` 拿到的请求是深冻结的。
  - 插件要改已有消息的内容，只能注册纯函数式的消息投影（`docs/architecture.md:127`）。
- **动态上下文不改 system prompt**：时间、审批策略这类信息拼成一条 `runtime-context` 用户消息，只在内容变化时追加。源码注释写明这样做是为了"不改写稳定的 system-prompt 缓存前缀"（`packages/interaction/user-approval/src/index.ts:155-170`）。
- **工具延迟加载是协议层原生支持的**：`ToolSchema.deferLoading` 加上 `ToolUpdate: 'addition-only' | 'in-history'`（`packages/llm/llm/src/types.ts:396-407`）。工具增删时写一条 `developer/message{tool-addition|tool-removal}`，追加在已缓存的历史之后（`agent.ts:635-649`）。
- **其他保缓存的做法**：plan 模式不改工具清单，以保持请求形状稳定；压缩时的总结请求复用当前对话前缀。

---

## 4. 工具执行管线

**工具声明**：`defineTool({ name, description, parameters, output, execute, timeoutMs, isConcurrencySafe, presentCall, presentResult, projectContent, finalizeContent, deferLoading })`（`packages/core/tools/src/schema.ts:554-632`）。其中 `presentCall` / `presentResult` 是纯函数，可以在回放时重新生成展示内容。

**工具没有声明权限的字段**。权限由外部的 pre-execute 监听器、guard、沙箱和审批策略施加。

**流程**：
1. 解析参数（JSON 解析失败时保留原文），做无损快照并冻结；
2. 写 `tool/call`；
3. pre-execute：`ask` 走审批；放行后再跑 guard；被拒绝时生成一条 `Error: reason` 结果，模型能看到；
4. execute：执行体内部做 schema 校验，不通过返回 `INVALID_ARGS`；执行体抛出的任何异常都归一化成错误结果；
5. **超时**：timeout-policy 用 deadline 替换信号，超时后返回 `TOOL_TIMEOUT`；
6. **post-execute**：
   - spill-policy：结果超过 `maxInlineTokens` 时保留头尾片段，全文存入溢出存储，并附一段可取回的定位说明；
   - repeat-tool-reminder：相同调用连续出现 3、5、8 次时追加提醒；它从不否决调用，用户插话会让计数归零；
7. 写 `tool/result`，带 `sourceEventSeqs` 指回对应的调用，以及展示用的 meta；
8. 工具附带的额外上下文进入 next-step 队列。

---

## 5. Session：事件溯源

- **事件与信封**：`SessionEventMap` 可以通过声明合并扩展。信封字段是 `type / seq / time / data / ignorable?`，消息类事件另带 `surfaceOp(append | replace{startSeq,endSeq})` 和 `sourceEventSeqs`（`packages/core/session/src/types.ts:439-517`）。压缩和裁剪都用 replace。
- **读取时"必须认识"**：遇到不认识的事件类型、又没有 `ignorable` 标记，就拒绝加载整个日志。当前 `SESSION_FORMAT_VERSION = 4`，每对相邻版本有一个迁移包，已提交的旧代际文件永不改写。
- **JSONL 存储**：每个 session 一个目录，可选 zstd 压缩；最多等 200ms 批量写入，配合 fsync；读取时忽略末尾残缺的一行。
- **语义检查点**：在模型请求发出前、工具执行体开始前、pre-step 时刷盘；**刷盘失败就不发请求、不执行工具**（`packages/session/session-checkpoint-policy/src/index.ts:63-83`）。
- **投影**：`ProjectionDefinition{ init, apply(纯函数), stateVersion }`，用来派生 UI 状态、统计等，可以持久化缓存。

---

## 6. 上下文压缩

- **触发**：
  - 压力：pre-step 时估算超过上下文窗口的 80%；
  - 溢出：收到 `CONTEXT_WINDOW_EXCEEDED`，有次数上限；
  - 手动：`/compact`。
- **策略顺序**：
  1. **tool-result-pruner**（不调用模型）：单个结果超过 8192 字符时，保留头 4096、尾 1024；
  2. **basic 总结**：调用一次 LLM 总结，用 replace 覆盖被总结的区间，默认保留尾部 16%；
  3. **image-offload**：把最老的图片换成"附件路径 + read_image"提示，这次重试不消耗重试预算。
- **全过程写日志**（`compaction/start|summary|prune|end`），中途崩溃可以被检测到。replace 会让缓存从被覆盖处开始失效。

---

## 7. LLM 层与人机交互

- **provider 抽象**：`LlmAdapter`；`prepareCall` 一次绑定配置、重试策略和上下文窗口，返回只能使用一次的 `PreparedLlmCall`。流式分片类型统一，adapter 抛出的异常归一化成 finish(error|aborted)。
- **重试**：策略归 provider 所有，默认最多 5 次，500ms 到 10s，带抖动；先写 `llm/retry` 日志，再进入可取消的等待。
- **token 计量**：按 4 字符折 1 token 估算；只有请求完全相同时才复用 provider 报告的 usage。
- **DeepSeek 专有**：thinking 签名回传（换模型时丢弃）、图片走 Files API。
  - 默认发送 `x-deepseek-harness-user-id`（匿名 UUID）和 `session-id` 请求头；
  - 默认在请求体里附带 `dsh_session_log` 会话日志片段（`docs/deepseek-llm-api-wire-extensions.md:74-76`）。
- **审批**：
  - `ctx.approval.request` 前后各写一条日志（asked / decided）；结果是 `allowed-once | rejected | cancelled | unavailable` 之一。
  - **没有应答方时判为 unavailable，即拒绝**（失败即拒绝）；**没有内置超时**。
  - 提问可以计时：超时后返回"pending"，用户之后的回答会作为一条用户消息到达。

---

## 8. 工程规范与 CI/CD

### 8.1 "规范即检查"

| 类别 | 代表规则 | 执行手段 |
|---|---|---|
| 类型 | strict、`noUncheckedIndexedAccess`、`exactOptionalPropertyTypes`；禁用 `any`；**不再新增 `as unknown`** | tsc；oxlint；`verify-no-unknown-casts`：存量 1,430 处做成基线，**只能减少，不能增加** |
| 异步 | 不允许悬空的 promise、误用的 promise | oxlint type-aware（注释称这是"价值最高的 lint 缺陷类"） |
| 联合类型 | 按判别字段 switch；封闭联合以 `assertNever` 结尾 | `switch-exhaustiveness-check` |
| 边界 | 同进程内有静态类型的值信任 TS，只在 parser、config、模型/工具 JSON、文件、进程、wire 边界校验；配置错误要大声失败；可调参数必须是 Config 字段 | 人工 review + `verify-cordis-config` |
| 文档 | 每个导出都有 JSDoc；文档里的代码块必须能编译；链接有效；字数不超预算 | `verify-export-jsdoc`、`doc-typecheck`、`verify-md-links`、`verify-doc-budgets` |
| 重复 | 跨文件克隆检测（60 token、6 行） | jscpd |
| i18n | UI 文案只能来自 locale 字典 | `verify-client-ui-i18n` |
| 生成物 | catalog、tsconfig paths 等提交进仓库 | 约 20 对 `gen-X` / `verify-X = gen-X --check` |

**元规则**：
- 能机械检查的规则，必须接进一个会被执行的门禁，并且要证明它能拒绝一个非法用例（`AGENTS.md:172`）。
- 门禁要防止"扫描范围被意外缩小或扫描为空"，例如 i18n 检查要求至少扫到 450 个文件。
- lint 配置本身也有测试。

### 8.2 测试体系（重点：LLM 回放）

- **分层**：
  - `test`：单测；
  - `test:coverage`：**per-file 100%**（`vitest.config.ts:382-383`），豁免必须写理由；按历史耗时分区并行，合并后统一判阈值；
  - `test:e2e`：真实 API，无 key 时自动跳过，**但 CI 在 secret 为空时直接失败**，防止全部跳过造成假绿；
  - `test:snapshot`：录制会话回放；
  - `test:bench`：性能预算是源码常量乘余量；
  - `test:web`：ARIA 快照。
- **录制会话回放的做法**：
  1. **录什么**：不录 HTTP，录 agent 正常持久化的 session JSONL。前提就是"模型可见 ⟺ 已记录"，所以同一份日志既是回放输入，又是期望输出。
  2. **fixture 组织**：`snapshots/<面>/<场景>/` 下放 `snapshot.yml`（profile、`recording: live|authored`）、`session.jsonl`、子 agent 日志，以及 `system-prompt.expected.md`、`tool-schemas.expected.json`、`workspace.expected/` 等边车文件。
  3. **三种模式**：`replay`（默认，无 key，可并行）、`record`（用真实 key 重写 fixture）、`refresh`（无 key；模型输入不变、产品输出变化时更新期望）。
  4. **注入方式**：通过真实的 CLI 子进程启动，叠加补丁把真实 LLM 插件替换成 `llm-replay`。进程里根本没有真实 provider，不可能误连网络。
  5. **按调用顺序匹配**，不按 hash。多出的调用报"script exhausted"；结束时 `assertConsumed` 确认脚本用完，少调用也失败；动态值用 `{{fromRequest:regex}}` 从实时请求中提取；抛错、挂起这类情况用 `replay.override.json` 按调用下标打补丁。
  6. **归一化**：易变 id 按首次出现替换成带类型的 token（如 `{{session:1}}`）；cwd 换成占位；时间置零。原则是**修 fixture，不修归一化器**。
  7. **何时必须更新**：任何非平凡的模型可见或用户可见改动，同一个 PR 里就要更新 snapshot。

### 8.3 本地钩子

lefthook，原则是"本地快，CI 全"：
- pre-commit：暂存文件跑 oxlint 并 `--fix`、`git diff --cached --check`、重新生成第三方声明、vendor manifest 守卫；
- pre-push：只跑 typecheck。

本地选择性检查由一个技能指导：先用 `change-scope` 拿到变更面，再挑最小的检查集。明确禁止默认跑全量，也禁止为了 push 重复跑已经通过的检查。

### 8.4 CI

- **PR 流水线（ci.yml）**：约 10 个 worker job 并行，覆盖静态检查、覆盖率、bench、构建后的消费方测试、Node 版本矩阵、Python、Windows。
  - **汇总 job `all-checks-passed` 是唯一的必过检查**：任何 failure、cancelled 或 skipped 都判红。这样增删或改名矩阵分支时，不用改分支保护。
  - 默认权限只读；新的提交会取消旧的运行；PR 上的缓存只读不写。
- **主干流水线（ci-master.yml）**：补充平台完整性检查（arm64、macOS、Wine 跑 Windows）。分工是"PR 负责合并判定，主干负责完整性参考"。
- **其他**：
  - e2e：PR、主干、每日定时都跑。注释明确禁止改成 `pull_request_target`，防止密钥泄露。
  - sandbox：只在主干上跑，在真实内核里验证沙箱，并断言通过的数量。
  - PR 预览部署到 Cloudflare。
  - workflow 文件本身也有测试（`scripts/ci-workflow.spec.ts`）。

### 8.5 CD

- **版本**：全家共用一个版本号，节奏是 `alpha` → `rc` → 正式；预发布发到 npm `next`，正式发到 `latest`。
- **流程**：
  1. 本地运行 bump 脚本改版本号；
  2. 走 release PR 合并；
  3. **人工打 tag**，CI 从不写仓库。
- **发布前**：每个 PR 都会做一次无凭据排练：build → pack → 把 tarball 装进仓库外的临时项目跑一遍。
- **发布**：`release-publish.yml` **只能手动触发**，走需要人工审批的 environment（`npm-publish`）。按 registry 的三种状态判定，可以安全重跑：
  - registry 没有这个版本 → 发布；
  - 有，且内容哈希相同 → 跳过；
  - 有，但内容不同 → 失败，用来抓"改了代码没升版本"。
- **桌面端**：签名和公证都在本地完成，不进 CI；自动更新只接受更高版本，禁止降级；上传过程留审计文件。

### 8.6 治理与面向 AI 的工程化

- **治理（适合大团队）**：
  - 基于 blame 的加权审批：需要 2 分，被改代码的作者权重更高；
  - PR 必须关联 Issue，并带 `kind/*` 和 `area/*` 标签；
  - 使用 GitHub 原生堆叠 PR；
  - postmortem 有固定章节，包括"为什么测试没抓住"；
  - Agent Notes（类似 ADR，"Alternatives considered"必填）；
  - upgrade guide；
  - 持久化 schema 的 digest 变化后，必须新增一条确认记录。
- **面向 AI 编码代理**：
  - `CLAUDE.md` 软链到 `AGENTS.md`；`.claude/skills` 软链到 `.agents/skills`，共 15 个技能（pre-push-checks、code-review、prose-standard 等）；
  - 根 `AGENTS.md` 有字数上限（1,960 词），一个事实只有一个归属，有"slop 清单"；
  - 注释只写契约，不写推理过程。

---

## 9. 对 phone-agent 的取舍

| dsh 做法 | 我们的取舍 | Kotlin 对应 |
|---|---|---|
| turn/step 状态机 + 双队列 inbox（followup/steer/inject） | **采用** | `sealed interface Phase`；两个 `ArrayDeque` + `Mutex` |
| 固定钩子点 + waterfall | **采用**（编译期组合，不要插件框架） | OkHttp 式 `Interceptor<I,O>` + `chain.proceed()` |
| serial turn-stopping 用 steer 续跑 | 采用，**并加续跑次数上限** | 有序的 `suspend` 监听器 |
| emit 观察事件 | 采用 | `SharedFlow` |
| 事件溯源日志 + "模型可见 ⟺ 已记录" | **采用** | `@Serializable sealed interface SessionEvent` + JSONL |
| 请求是日志的纯函数，并且冻结 | 采用 | `fold(events) → List<Message>` |
| 动态上下文作为追加的用户消息 | 采用（与 Mika 一致） | — |
| `isConcurrencySafe` 并发调度 | 简化：**写屏幕的动作一律独占** | `ToolDef.concurrencySafe` |
| 失败语义：中断前缀 / 未执行 / 结果未知 | **采用**；GUI 动作不幂等，恢复后**要求先重新读屏** | `withContext(NonCancellable)` 收尾写日志 |
| 语义检查点刷盘 | 采用：模型请求前、写动作执行前 | 追加写 + fsync |
| repeat-tool-reminder | 采用，与 LoopGuard 合并 | — |
| 先确定性裁剪，再总结压缩；图片卸载 | 采用：截图存文件、日志只记引用，只保留最近 N 张 | — |
| 审批"失败即拒绝" | 采用，**再加超时**（dsh 没有） | `CompletableDeferred` + `withTimeoutOrNull` |
| 主循环不设步数上限 | **不采用**：加步数上限和续跑上限 | pre-step 中 reject |
| Cordis、热重载、YAML 叠加、多代际迁移、PTC、多 subagent 后端 | 不采用 | — |
| 默认上传会话日志 | **不采用**（隐私） | — |
| 会话日志回放测试（record/replay/refresh） | **P0 采用** | `ReplayChatModel` + JUnit5 |
| 规范写成检查 + 基线只减不增 | P0 采用 | detekt/ktlint + baseline；Kover |
| 单一必过汇总 job；e2e 缺 secret 判红 | P0 采用 | GitHub Actions |
| 手动发布 + environment 审批 + 可安全重跑 + 打包产物验证 | P0 采用 | 签名 APK + `apksigner verify` + SHA256 |
| 更新只接受更高版本 | 采用 | App 内更新器 |
| AGENTS.md + 技能 + 文档预算 | P0 采用（精简版） | — |
| 加权审批、Issue 策略机器人、双语配对、堆叠 PR 强制 | 不采用（规模不匹配） | — |

具体落地见 `docs/design/engineering-baseline.md`，架构修订见 `docs/design/phase1-architecture.md` §1（D12–D14）、§3.8–3.9。
