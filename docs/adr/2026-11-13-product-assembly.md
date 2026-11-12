# 产品装配放进 core 的 assembly 包，App 和会话回放共用一个纯函数

- 日期：2026-11-13
- 状态：采纳

## 背景

一个任务要跑起来，需要把各个能力装成 `AgentSetup`，具体包括：
- policy 的 engine、resolver 和词表；
- ScreenComponents 的 analyzer 和 presenter；
- PhaseOneTools 的工具和分组；
- 系统提示；
- 拦截器；
- 循环配置。

装法本身有好几条约束：
- vision 只能有一个来源；
- analyzer 和 presenter 要用同一份 `ScreenFormatConfig`；
- 工具分组和清单的顺序决定提示前缀的缓存；
- 每次运行要记下判定用的规则（`policy/rules`）。

需要这样装配的地方有两处：App 跑真实任务，WP08 的会话回放要重放同一个 agent。如果各装各的，回放验证的就不是 App 实际跑的那个 agent。WP08 第一批已经在测试源里另写了一份 ReplayHarness，它自己也把这一点记成了已知风险。

## 决定

- core 新增装配包 `dev.phoneagent.core.assembly`。它是 core 里唯一能把能力装在一起的地方：
  - 可以引用所有契约包和所有能力包，隔离能力包 `adb` 除外；
  - core 里别的包都不能引用它；
  - `PackageDependencyTest` 守住这几条，并带反例。
- `assemble(model, ProductConfig)` 是纯函数，不做 I/O：
  - vision 只从会话主模型的 `info.capabilities` 取；
  - `ProductConfig` 包括本 App 的包名和名称，以及 `PolicyTuning`、`ScreenFormatConfig`、`TreeQualityConfig`、`DiffConfig`、`ToolsConfig`、`LoopConfig`；
  - 每个配置在它自己的类型构造时校验，本 App 的名称由 `PolicyRules` 校验，不合法就抛 `IllegalArgumentException`。
- 返回的 `ProductAssembly` 在一个会话里固定不变，内容包括：
  - 给 ScreenPort 用的 `analyzer`；
  - `presenter`、`policy`、`intents`；
  - 按 PHONE、GENERAL 两组排好的 `registry`；
  - 主对话的 `plan`：主模型、`SystemPrompt.BLOCKS` 和工具清单；
  - 默认拦截器（失败的请求会重试，思考签名失效时丢掉旧的思考块再试）、循环配置；
  - `policyRules`：`RULES_VERSION` 加上 `PolicyTuning` 的全部字段。
- `setup(taskId, files, screen, surfaces, apps, clock, timeSource)` 再补上任务和设备的部分，得到 `AgentSetup`。
- 主模型每个会话一个，因为它的 BlobSource 读的是这个会话的 blob。所以装配也是每个会话做一次。
- App 必须用 `analyzer` 来构造这个任务的 ScreenPort。
- 判定用的规则记成一个新的 ignorable 事件 `policy/rules(rulesVersion, tuning)`，由 runtime 在每次运行开头写：`task/start` 之后一条，每个 `session/resumed` 之后再一条。`tuning` 来自 `PolicyTuning.byName()`，构造时的范围校验用的也是这份名单。

## Alternatives considered

- **App 装配，回放在测试源里另写一份**（WP08 第一批的做法）：两份会悄悄分叉，比如改了工具分组、拦截器或 vision 的来源，只改了其中一份。回放就会验证一个 App 根本不跑的 agent。
- **装配放在 App 里，回放依赖 App 模块**：会话回放是 core 的 JVM 测试，依赖 Android 模块就跑不起来，也违反"core 不依赖 Android SDK"。
- **装配放进 testFixtures**：App 的产品代码不能依赖测试源集。
- **规则写进 `task/start`**：`task/start` 一个任务只有一条，可规则要按运行记，两次运行之间升级了 App，规则就可能变。旧的读取方不忽略未知字段，往 `task/start` 加字段会让旧版本读不了新日志；新的 ignorable 事件旧版本可以跳过。另外 `task/start` 在契约包 `session` 里，如果它带上 `PolicyTuning` 的类型，契约包就依赖了能力包 `policy.rules`。
- **把装配拆给各能力自己提供工厂**：能力之间不能互相引用（ADR `2026-11-01-core-contracts`），而装配恰恰要同时看到 policy 的词表、screen 的 presenter 和 tools。拆开以后，这几者之间的约束（同一份格式、同一个 vision）就没有地方能检查了。

## 后果

- core 里多了一类包。依赖规则从"能力只由 app 组装"改成"能力只由 assembly 组装"，`PackageDependencyTest` 也照这个改了。
- `ProductConfig` 新增可调参数时，回放场景和 App 的设置都要跟着看一遍。
- 需要重新评估的情况：出现第二种产品形态（例如开发者 ToolServer 想要另一套工具或策略），一个 `ProductConfig` 装不下的时候，再考虑把装配参数化，或者拆成几份。
