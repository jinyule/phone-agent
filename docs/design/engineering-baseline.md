# Phone-Agent 工程基线（v0.2）

> 日期：2026-11-01
>
> 依据：`docs/research/deepseek-harness-agentloop-and-engineering.md`（dsh 的工程体系），以及 Mika 的金样和真机评测做法
>
> 定位：小团队 + AI 编码代理协作的 Kotlin/Android 项目。取 dsh 的"机制骨架"，不取它为大团队设计的治理。
>
> 优先级：**P0** = 第一行业务代码之前就位；**P1** = M2–M3 期间补齐；**P2** = 有外部用户或贡献者之后再做。
>
> v0.2（2026-11-01）：M0 已落地，见 §9；和原计划不同的做法已直接改进正文，理由见 `docs/adr/2026-11-01-m0-toolchain.md`。

---

## 1. 仓库布局（P0）

```
phone-agent/
├─ AGENTS.md                 常驻规则（≤1500 词，一个事实只有一个归属）
├─ CLAUDE.md -> AGENTS.md    软链
├─ .agents/skills/           代理技能（见 §8）
├─ .claude/skills -> ../.agents/skills
├─ build-logic/              Gradle 约定插件（included build）：kotlin-jvm、android-app、quality、coverage
├─ gradle/libs.versions.toml 版本目录；verification-metadata.xml（SHA-256）+ 禁止动态版本，不用 lockfile
├─ gradle/gradle-daemon-jvm.properties  构建 JDK（Temurin 25）
├─ config/detekt/            detekt.yml + canary/（已知坏代码与必须报出的规则清单）+ canary-aidl/（同上，证明 privileged 的 detekt 能解析 AIDL 生成的类型）
├─ core/  privileged/  app/  模块划分见 phase1-architecture §2（eval/ 在 M1 加）
├─ core/src/test/resources/
│   ├─ sessions/<scenario>/  会话回放 fixture（§3.2）
│   └─ prompt-surface/       提示词与工具定义的金样
├─ docs/
│   ├─ design/  research/    设计与调研文档
│   ├─ adr/                  yyyy-mm-dd-topic.md，"Alternatives considered"必填（TEMPLATE.md）
│   └─ postmortem/           NNNN-slug.md，固定章节（P1）
├─ lefthook.yml  .editorconfig  .gitattributes  .gitleaks.toml
└─ .github/workflows/        见 §5
```

---

## 2. 编码规范与执行手段（P0）

原则（来自 dsh）：
- **能机械检查的规则，必须接进 CI 门禁，并且至少有一个会被它拒绝的反例**。
- 存量问题做成基线，**只能减少，不能增加**。

| 规则 | 执行手段 |
|---|---|
| core 模块开 `explicitApi()`，`allWarningsAsErrors = true` | Kotlin 编译器 |
| 公开 API 必须有 KDoc，写清不明显的契约（前置条件、失败方式、线程/协程语义） | detekt `UndocumentedPublicClass/Function/Property`（仅 core、privileged） |
| 禁用 `!!` | detekt `UnsafeCallOnNullableType` |
| 禁用 `GlobalScope`；core 里禁用 `runBlocking` 和 `Thread.sleep`；协程必须结构化 | detekt `GlobalCoroutineUsage`、`ForbiddenMethodCall` |
| 判别分支用 sealed + 穷尽的 `when`，不写 `else` 兜底 | 编译器 + detekt `ElseCaseInsteadOfExhaustiveWhen` |
| 跨边界的 id 用 `@JvmInline value class`（`TaskId`、`SessionId`、`SnapshotId`、`ToolCallId`），不用裸 `String` | 人工 review；code-review 技能列为必查项 |
| **只在边界做校验**：模型输出的工具 JSON、配置、文件、Binder/IPC、网络。同进程内有类型的值信任编译器 | 人工 review |
| **配置错误要大声失败**：在加载时校验，不静默跳过；可调参数是带校验的配置字段，不是散落的 `DEFAULT_*` 常量 | 人工 review + 配置加载的单测 |
| 不允许空 `catch`。`catch` 必须给错误命名并说明原因，`try` 只包一条语句；协程里不能吞掉 `CancellationException`（DSH-Android 踩过这个坑） | detekt `EmptyCatchBlock`（不允许任何名字豁免）、`SwallowedException`、`TooGenericExceptionCaught`、`SuspendFunSwallowedCancellation`（检查 `runCatching` 包住挂起调用） |
| UI 文案走 `stringResource`，Compose 里不写字面量 | Android Lint `HardcodedText` 设为 error；Compose 部分加一个自定义 detekt 规则（`Text("…")` 字面量，M1 有 UI 代码时加） |
| 格式统一 | Spotless + ktlint；`.editorconfig`；文件以恰好一个换行结尾（`git diff --cached --check`） |
| 不提交密钥，fixture 和截图不含真实隐私 | gitleaks（pre-commit + CI）；fixture 一律来自测试账号（§3.2） |
| **模型可见 ⟺ 已记录**：进入模型请求的内容都能从 session 日志重建 | 回放测试：用日志重建请求，与录制时逐字节比较（§3.2） |
| **主循环只能通过固定拦截点扩展**；改 `agent/` 包必须同时更新 `docs/design/phase1-architecture.md` §3.8 | 依赖测试 + PR 模板勾选项 |
| core 内部的依赖方向：agent 不认识具体能力，能力之间不互相引用 | Konsist 写的 `core` 包依赖测试 `PackageDependencyTest`，带会被拒绝的反例（规则见 ADR `2026-11-01-core-contracts`） |

---

## 3. 测试体系

### 3.1 分层

| 层级 | 内容 | 何时跑 | 优先级 |
|---|---|---|---|
| L1 core 单测 | JUnit5 + kotlinx-coroutines-test；`FakeChatModel`（按剧本回复）+ `ScriptedScreen`（快照序列） | 本地 + PR | P0 |
| L2 会话回放 | §3.2 | 本地 + PR | P0 |
| L3 提示词金样 | 系统提示词、工具 schema、反馈文字的全文，与 L2 的边车文件共用 | 本地 + PR | P0 |
| L4 Android 单测 | Robolectric 测适配层（树转快照、格式化、PolicyEngine 与 Android 类型的接缝） | PR | P0 |
| L5 端口契约 | ScreenPort 和 IPrivilegedCore 的同一套用例在模拟器上跑 | 主干 | P1 |
| L6 真实 API | `@EnabledIfEnvironmentVariable("PA_E2E_KEY")`：无 key 时跳过，**但 CI 在 secret 为空时判红** | 主干 + 每日 | P1 |
| L7 真机评测 | 任务 YAML，分调优池和保留池；在自建 runner 上接 USB 真机 | 每日 + 发版前 | P1 |
| L8 UI 截图 | Roborazzi | PR | P2 |
| L9 性能 | kotlinx-benchmark 测 core 的格式化和压缩；Macrobenchmark 测冷启动 | 主干 | P2 |

**覆盖率（Kover）**：
- `core` 按类统计，行覆盖 ≥ 90%；`core.policy`、`core.agent` 两个包要求分支覆盖 100%（安全判定和主循环是最不能出错的地方）。
- `app` 不设门槛，只出报告。
- 豁免必须用注解 `@ExcludeFromCoverage(reason = "...")`，reason 不能为空，由测试检查。

### 3.2 会话回放测试（P0，最重要）

**为什么要做**：主循环、提示词、上下文压缩、安全判定的回归，都能在没有真机、没有 key 的情况下确定性地复现。这是 dsh 最有价值的工程做法。手机场景的不同之处在于，工具的另一端是设备，所以**设备侧也要回放**。代码在 `core` 的测试源集 `dev.phoneagent.core.replay`。

**fixture 结构**：
```
core/src/test/resources/sessions/<scenario>/
├─ scenario.json                录制方式（authored|live）、说明、模型和能力、task/start 的档位、
│                               和默认值不同的装配参数（含 tuning 覆盖）、policy pin、设备
├─ session.jsonl                事件溯源日志（phase1-architecture §3.9），归一化后
├─ requests.expected.jsonl      录制时模型收到的每个请求
├─ blobs/<sha256>               截图等大对象，文件名是内容的 SHA-256（可以没有）
├─ system-prompt.expected.md    边车：系统提示词全文
├─ tool-schemas.expected.json   边车：工具定义
└─ replay.override.json         可选：日志里没有的设备事实，和人为注入的故障
```
- `scenario.json` 用 kotlinx.serialization 严格读：未知键、缺键都失败。设备相关的值（surface、分辨率、dpi、ROM、App 版本、AppCatalog）只放这里，不进日志。policy pin 是录制时的 `RULES_VERSION` 和**完整**的 `PolicyTuning`（每个字段一项，不只是改过的）：默认值改了也会被比出来。
- `requests.expected.jsonl` 每行是一个 `ChatRequest`：`n`、`logSeq`（发出时日志最后一条记录的 seq）、`model` 和 `settings`（变化时才写，否则为 null）、`keep`（和上一个请求共有的前缀消息数）、`append`（其余消息）。为什么要存：`ReplayChecker` 证明"发出的 = 日志推出的"，事件比较证明"日志没变"，但两者都用当前代码推导；`deriveMessages` 或 `toRequest` 改了，它们照样通过，只有和录制时的请求比才看得出来。
- 比 `ChatRequest` 这一层，不比线路字节：`prepare` 的输出（wire 快照、钳制后的档位）本来就在请求里；线路字节是适配器在同一 `mappingVersion` 下的纯函数，由各适配器的线路金样覆盖；图片在请求里是内容哈希，比哈希一样严格。
- `replay.override.json` 的每条事实按（调用的 `{{call:n}}` token，这个调用的第几次端口调用）定位，计数时记录的和没记录的都算：`awaitChange` 的返回值（它从不进日志）、`frame` 返回 null、`snapshot`/`frame`/`awaitChange` 抛出或挂起、动作记成 `threw X` 时异常的 message、设备耗时 `delayMs`。`frame` 调用总能从日志里数出来（见下面 `ReplayScreen` 一条），所以 `frame` 的事实只标注那一次调用，不另占位置。

**回放怎么跑**：
- `ReplayChatModel`：请求 n 的结局是 `logSeq_n` 之后、`logSeq_{n+1}` 之前（含）的 `assistant/message` 或 `assistant/attempt`。正常回复先把文字作为 delta 流出再原样返回；中断前缀加 `assistant/attempt` 是先流出前缀、再抛按 kind 重建的 `ModelError`（重建后的 message 必须和记录逐字相同，否则装载失败）；只有中断前缀、没有结局的，流出前缀后挂到被取消。`prepare(level)` 钳制后必须等于录制时的档位，返回录下的 wire。多出的请求报 "script exhausted at request #n"；结束时 `assertConsumed()`，少调用也失败。
- `{{fromRequest:REGEX}}`：只能是 `argsJson` 里一个完整的 JSON 字符串值，REGEX 按 JSON 转义写、至少一个分组；取实时请求全部文字（含工具结果里的）中最后一个匹配的第 1 组，作为 JSON 字符串插入，`argsJson` 其余部分逐字保留。写 fixture 时 `assistant/message` 恢复成模板。
- 用户一侧（`UserScript`、`SessionDriver`）从日志读出：`inbox/spliced` 的每条新增输入、`task/level-changed`、`task/stopped` 的 USER/LIMIT/SHUTDOWN、`session/resumed`、对第 n 个提示的回答。输入和档位在回放日志写到录制时它前面那一条时给出（seq 必须正好相等）；停止的位置要退回到停止本身写下的记录（收尾的 CANCELLED、撤回的提示、被打断的回复、清空的 inbox）之前；回答在第 n 个提示进入等待时给出。ERROR 停止不给，看回放是否同样停下。装载即失败的：SurfaceManager 移屏失败的 hand-off（`FAILED`、`NOT_TAKEN_BACK`）、崩溃后的恢复（`session/resumed` 前面不是 `task/stopped`）、一次加多条输入的 `inbox/spliced`。
- `ReplayScreen`：每个调用一个队列，当前调用从回放日志推出（最后一条点名了某个调用、且它还没有 `tool/result` 的记录）。每次端口调用的方法必须和录制时一致；`act`、`launch` 还要比 `UiAction` 和 `on`、或 `LaunchTarget`。不一致就当场失败，并记进结束时的检查。快照的 fingerprint 和 quality 用当前的 analyzer 重算（quality 影响自动附图，是模型可见的；改 analyzer 要 refresh fixture），diff 按录制值给。设备耗时在虚拟时间里 delay。带帧的 observe：端口上的顺序是 S、`frame`、S2，日志里是 S、判定、S2、判定，帧留下时最后才有 `screen/frame{snapshotId = S}`。runtime 只在记下 `screen/judged` FRAME_PRECHECK SHOWN 之后抓帧，所以每条这样的判定就是一次 `frame`，排在 S 和 S2 之间。有 S 的 `screen/frame` 就给这一帧；没有（runtime 丢掉了：S2 被扣下、换了 App、框对不上、S2 读失败）就给 null。runtime 拿到 null 和丢帧走同一条路，日志和工具结果都不变。
- 断言（全部收集，一次报出）：
  - 请求文件逐字节一致，加上每次请求的 `ReplayChecker` 严格检查；
  - 判定序列一致：每条 `policy/verdict` 和 `screen/judged` 的完整 data，Confirm 的卡片和原因也在内；`scenario.json` 的 `rulesVersion`、`tuning` 和当前值严格相等，判定序列变了而版本没变时单独提示"没升 RULES_VERSION"；
  - 事件序列一致（两边都解码、用当前编解码器重写、再归一化）；
  - 两个边车文件一致（由第一个请求渲染，写法和 prompt-surface 金样相同）；
  - 脚本、设备队列、override、外部输入全部用完。
  - 不一致的文件留在 `core/build/session-actual/<scenario>/`，失败信息里给出第一个不同的请求或记录和它的 JSON 路径。

**三种模式**（Gradle 属性 `-PsessionSnapshot=`，由约定插件 `phoneagent.session-replay` 解析并传给 `:core:test`）：
- `replay`：默认，CI 只跑这个。取值非法，或 `CI=true` 时不是 `replay`，在配置阶段就失败。
- `record`：跑录制来源并写出新 fixture。M1 的来源是 JVM 上的 authored 场景；真模型加真机的来源留给 WP11。跑完自动归一化，先写到 `core/build/session-record/<scenario>/fixture/` 暂存，在那里用 gitleaks 扫描；扫描干净才搬进 `src`。扫出泄漏或 gitleaks 自身出错时，`src` 一个字节都不动。本机没装 gitleaks 时，authored 且没有密钥的来源只警告（清单里写"未扫描"，CI 扫全历史兜底），`recording: live` 或带 `secrets` 的来源直接失败。同时生成一份**人工隐私审查清单** `core/build/session-record/<scenario>/privacy-checklist.md`（不提交；截图、通讯录、聊天内容只允许来自测试账号）。
- `refresh`：无 key。用 fixture 里的模型回复、设备事实和外部输入跑一遍，先要求"走了同一条路"（模型和设备的脚本、外部输入都恰好用完，铁律 1 严格检查通过），再就地重写日志、请求文件、边车和 policy pin；模型回复和设备事实永远不会被它改掉。它不碰 prompt-surface 金样和 loop 金样：三者共用比对和接受的写法，各自一个接受开关。
- `record` 和 `refresh` 只跑回放测试，并且不进缓存、不算 up-to-date（它们写 `src/`）。

**归一化**（每个数值字段和 id 字段的类别登记在 `core/src/test/resources/replay/normalized-fields.txt`，新字段没登记测试就失败）：
- N1 `time`、`createdAt` 置 0；
- N2 `approval/asked.expiresAt` 改成相对本条记录的时间；
- N3 `request/retry.delayMs` 置 0（含随机抖动）；
- N4 task、session、call、prompt 四类 id 按首次出现换成 `{{task:1}}` 这样的 token，回放时换成 `task-1`（SessionId 只允许 `[A-Za-z0-9._-]`）；SnapshotId、元素 ref、BlobRef、provider、model、seq 引用、opaque、providerMetadata 不换；文字和 JSON 参数一律不改；
- N5 录制时把本次用到的 key 换成 `{{credential:n}}`（原样、转义一层、转义两层都换）；**每种模式**装载 fixture 时都检查，出现 `sk-`、JWT、`Bearer` 形状的文字就失败；
- N6 设备相关的值放进 `scenario.json`；
- N7 恢复 `{{fromRequest:…}}` 模板。

归一化器的锁定测试：模型可见的每一类改动（文字、参数、工具名、结果、两个调用的结果互换、两个不同的 id、header、压缩范围、opaque、图片……）归一化后仍然不同，每种一个测试；交换律（先归一化再 `deriveMessages` 和反过来逐字节相同；在每个 fixture 上，也在 `SessionSamples` 的每个位置上，含压缩、reasoning 截断、图片、side 请求，以及 wire header 里带密钥的 N5）；字段登记表完整；会丢值的类别（time、duration、expiry）只有 N1–N3 这四个字段，清单钉死在测试里，再加一个要在这里写理由（没进 fixture 的字段被改成丢值类别，回放也看不出来）。

**现有 fixture 守护的路径**（都是 authored，每个场景的 KDoc 写全它守护什么，见 `AuthoredScenarios`）：
- `observe-tap`（Anthropic 协议）：读屏，用 `{{fromRequest:…}}` 从屏上取 ref，再点击。
- `confirm-send-approved`（OpenAI Responses）：外发要确认，用户批准，再复核重读的屏 S′。
- `refused-finance-app`（Chat Completions）：打开金融 App 被硬拒绝，设备没动。
- `anthropic-thinking`：Claude 路径，带 thinking 块和 compaction 块、服务端压缩设置、非默认的 maxOutputTokens 和档位。回复流到一半断网，同一步重试，断掉的前缀只给界面看；签名过期，默认恢复链写 `context/reasoning-dropped`，重试时去掉截止那条回复的 thinking。
- `cart-cautious`、`cart-autonomous`：谨慎和自主两档下的 DELETE 和 SPEND（带金额）；自主档删除不问。
- `steer-mid-reply`：回复流到一半被 steer 打断，前缀和 steer 进入下一个请求。authored 刺激带 `after`，就在回复流出的时候给出。

**尚无 fixture 的路径**（回放这一层没有守护：单测各有覆盖，但没有请求文件能对上）：
- 客户端压缩（带 Replace 的 `compaction/summary`、`compaction/prune`）：这个分支上的 core 还没有生产者，所以"Anthropic 路径不做客户端压缩"（铁律 7）在回放层也没有守护（评审 D3 存活）。由 WP17 B 合入时补：它的 ContextPrune 是第一个生产者，同时补两个 fixture，一个是 OpenAI 路径上真的 prune，一个是 Anthropic 路径上不 prune、只追加（守铁律 7，评审的 D3 变异 `if (appendOnly)` 要被它杀死）。
- `ask_user`（`question/*`）和 `hand_to_user`（`handoff/*`）：本轮未做，留给下一轮或 WP11；SurfaceManager 移屏失败的 hand-off 装载即失败。
- 被扣下的屏（`screen/judged` WITHHELD）、带帧的 observe（vision、`screen/frame`、blobs）、`side/*` 请求：现有场景都不看图。帧的端口顺序和丢帧由 `RecordingRoundTripTest` 守护。
- 停止、关闭和恢复（`task/stopped`、`session/resumed`）、中途切换档位、inject 输入：驱动支持，`SessionDriverTest` 覆盖了，但还没有 fixture。
- live 录制（真模型、真机）：WP11。

**原则**：修 fixture，不修归一化器。任何非平凡的、模型可见或用户可见的改动，**同一个 PR 里**就要更新相应的 fixture（`record-session` 技能列出要跑的命令）。

---

## 4. 本地钩子（P0，lefthook）

**原则**：本地快，CI 全。

| 钩子 | 做什么 |
|---|---|
| pre-commit | 有 Kotlin/kts 暂存时跑 `spotlessApply` 并重新暂存；`git diff --cached --check`；`gitleaks git --staged`（本机没装 gitleaks 时提示并跳过，CI 兜底） |
| pre-push | `./gradlew :core:compileKotlin :core:detektMain --offline`，控制在 1 分钟内 |

**选择性检查**：由 `.agents/skills/pre-push-checks` 指导。先看变更面，再挑最小的检查集。不默认跑全量，也不为了 push 重复跑已经通过的检查。

---

## 5. CI（GitHub Actions）

所有 workflow 的共同约定：
- 默认 `permissions: contents: read`；
- `concurrency` 设 `cancel-in-progress`；
- `gradle/actions/setup-gradle` 在 PR 上只读缓存；
- 带 secret 的 workflow **只用 `pull_request` 或 `push` 触发，不用 `pull_request_target`**。

| Workflow | 触发 | Job | 优先级 |
|---|---|---|---|
| `ci.yml` | pull_request、push main、手动 | `static`（spotlessCheck、detektMain/detektTest、detekt canary、Android lint、gitleaks 扫全历史）· `core`（test + 会话回放 + `koverVerify` + `koverVerifyCritical` + build-logic 测试）· `app`（assembleDebug + debug 变体的 Robolectric 单测 + release 变体只跑 `src/testRelease` 里 release 与 debug 差异的检查 + 两个变体的类边界检查 `verify<Variant>ClassBoundaries`）· `privileged-build`（构建 dex + 单测，`verifyPrivilegedProtocol` 检查 AIDL 版本常量）· **`all-checks-passed`**（汇总以上 job：failure、cancelled、skipped 都判红；**唯一的必过检查**） | P0 |
| `main.yml` | push main | ci.yml 的全部内容 + 模拟器端口契约测试（API 34/35/36，`reactivecircus/android-emulator-runner`）+ 构建未签名的 release 包 | P1 |
| `e2e.yml` | 每日定时 + 手动 | 真实模型 API 测试；secret 为空时直接判红 | P1 |
| `eval.yml` | 每日定时 + 手动 | 自建 runner 接 USB 真机跑保留池，产出 `summary.md` 和 `metrics.json`；成功率下降超过阈值就标红 | P1 |
| `release.yml` | 手动触发，只接受 `v*` tag | 见 §6；走 environment `release`，需要人工审批 | P0（M2 前） |
| `dependabot.yml` | 每周 | gradle 和 github-actions 两个生态；设 cooldown（新版本冷却期），自动打标签 | P0 |

**分工**：PR 负责合并判定，主干负责完整性参考。CI 从不写仓库。

---

## 6. 版本与发布（CD）

**版本号**
- `gradle.properties` 是唯一来源，`versionName` 用 semver，例如 `0.3.0-alpha.2`、`0.3.0-rc.1`、`0.3.0`。
- `versionCode` 由公式计算，保证单调递增：`M*1_000_000 + m*10_000 + p*100 + s`。其中 `s` 的取值是：alpha 为 0–29，beta 为 30–59，rc 为 60–89，正式版为 99。有对应的单测。

**发布流程**
1. 本地运行 `./gradlew bumpVersion`，提交 release PR。
2. release PR 合并后，**人工**打 `vX.Y.Z` tag。
3. 手动触发 `release.yml`，经 environment 审批后依次执行：
   1. 用 secrets 里的 keystore 构建签名 APK（arm64-v8a；x86_64 只给开发用）；
   2. `apksigner verify --print-certs`，**核对证书指纹等于仓库里记录的指纹**；
   3. **验证打包产物**：在模拟器上安装 release APK，拉起 helper，跑一次冒烟测试（对应 dsh 的"装进临时项目跑一遍"）；
   4. 生成 SHA-256 和更新清单 `latest.json`，内容包括 versionCode、versionName、url、sha256、证书指纹、最低系统版本、渠道（stable/beta）；
   5. 创建 **draft** GitHub Release，附上 APK、SHA256 和清单；人工检查后再发布。
4. **可以安全重跑**：同一个 tag 已经有 Release 且产物哈希相同 → 跳过；哈希不同 → 失败（用来抓"改了代码没升版本"）。

**App 内更新**
- 只读取 GitHub Releases 上的清单；**只接受更高的 versionCode，禁止降级**。
- 安装前校验：SHA-256、包名、签名证书与当前安装的一致；系统安装器还会再验一次签名。

**特权助手与版本错配（必须遵守）**
- helper 是从 `base.apk` 加载的，**App 更新后，旧的 helper 进程可能还在运行旧代码**。
- 交接时比对 `protocolVersion` 和 APK 的 versionCode；不一致就让旧 helper `releaseAll()` 并退出，再重新拉起。
- CI 检查 AIDL 版本常量：改了 AIDL 文件就必须升版本号。实现（WP12）：
  - `privileged/protocol-history.txt` 是只追加的 `<版本号> <指纹>` 列表；指纹是所有 `.aidl` 文件按相对路径、按 AIDL 词元算的 SHA-256（去掉注释和词元之间的空白，字符串常量原样保留），所以只改注释或排版不算协议变更；
  - `:privileged:verifyPrivilegedProtocol`（挂在 `check` 上，CI 的 `privileged-build` 也单独跑）要求最后一行等于当前的 `PrivilegedProtocol.VERSION` 和当前指纹，失败时直接给出要追加的那一行；
  - AIDL 的 1–4 号事务（`protocolVersion`、`identity`、`releaseAll`、`shutdown`）永久冻结，任何版本的 App 都能让任何版本的旧 helper 释放并退出（phase1-architecture §3.5）。这四条的完整声明（返回类型、方法名、参数、`oneway`、事务号）写死在检查器里（`PrivilegedProtocolCheck.FROZEN`），AIDL 里少了或改了任何一条都失败，升版本也不行；
  - history 只追加：CI 的 `privileged-build` 传 `-PprivilegedProtocolBaseRef=<基线 sha>`（`pull_request` 用 `pull_request.base.sha`，`push` 用 `before`；基线不在 clone 里时 `scripts/ci/protocol-base-ref.sh` 按 sha 补拉，补拉不到就失败），检查器用 `git show` 读出基线的 history，要求它是当前 history 的前缀；基线里没有这个文件就当作空前缀。属性只接受完整 sha，分支名和短 sha 会被拒绝（它们会移动，任务却会按 up-to-date 跳过）。**这一项只在 CI 上做**：本地不传这个属性，只跑上面几项。

**签名密钥**
- release keystore 离线保存两份。CI 只通过 environment secret 使用它。
- 启用 APK Signature Scheme v3，保留日后轮换密钥的能力。

---

## 7. 轻量治理

| 项 | 做法 | 优先级 |
|---|---|---|
| PR 模板 | 三节：Motivation（关联 Issue）/ Changes / Testing（每种验证方式附证据：命令输出、回放 diff、真机录屏）；外加勾选项"改了主循环 → 已更新架构文档""改了模型可见内容 → 已更新回放 fixture" | P0 |
| 标签 | 只用 `kind/*`（feat/fix/refactor/docs/infra/test），不强制 area | P0 |
| ADR | 需要长期保留的决策理由，写进 `docs/adr/`，"Alternatives considered"必填；机械性改动不写 | P0 |
| postmortem | 只给"微妙、系统性、重新排查代价高"的问题写；固定章节：摘要 / 影响 / 时间线 / 根因 / **为什么测试没抓住** / 新增的防护 / 教训 | P1 |
| 持久化变更 | Room 导出 schema + `MigrationTestHelper`；session 日志格式升版本时，旧版本的 fixture **永不改写**：新版本的 fixture 只由 `./gradlew :core:generateSessionFormatFixture` 生成，它拒绝覆盖已有的版本目录；落盘的 enum 和判别名登记在 `session-format/format-names.txt` | P1 |
| 升级指南、CHANGELOG | 先用 Release notes 代替；有外部用户和配置项之后再加升级指南 | P2 |
| 不采用 | 基于 blame 的加权审批、Issue 策略机器人、强制堆叠 PR、双语配对门禁 | — |

---

## 8. 与 AI 编码代理协作

**AGENTS.md 的骨架**（每条 1–3 行，链接到它的归属文档）：
1. 项目一句话说明和模块地图；
2. 常用命令：build / test / 回放录制 / 评测；
3. 架构铁律：
   - 模型可见 ⟺ 已记录；
   - 只通过拦截点扩展主循环；
   - 安全判定只在 PolicyEngine；
   - 不向模型暴露任意 shell；
   - 每次工具调用都带 taskId；
4. 编码规范：只列 §2 里机器检查不了的那几条；
5. 测试义务：什么改动要更新什么 fixture；
6. 隐私：fixture、截图、日志只用测试账号；不提交密钥。

**技能**（`.agents/skills/`）：

| 技能 | 用途 | 优先级 |
|---|---|---|
| `pre-push-checks` | 按变更面挑最小检查集 | P0 |
| `code-review` | 必查项：taskId 贯穿、取消语义、只在边界校验、模型可见内容进日志、PolicyEngine 绕过风险、隐藏 API 的降级路径 | P0 |
| `add-tool` | 新增工具的流程：schema → RiskClass 和 PolicyEngine 分类 → 展示函数 → authored 场景并 refresh 全部 fixture → prompt-surface 金样 | P0 |
| `record-session` | 什么时候 record、refresh 或改 authored 脚本；回放失败怎么看；隐私审查；改了模型可见内容时要跑的全部命令 | P0 |
| `android-device-debug` | 真机或模拟器上调试 helper 和虚拟屏 | P1 |

**文档纪律**：
- 一个事实只有一个归属；
- 注释只写契约（行为、失败、时序、归属），不写推理过程；
- AGENTS.md 有字数上限，要加内容只能"搬走或压缩"。

---

## 9. M0 落地情况（2026-11-01）

| 项 | 状态 | 位置 |
|---|---|---|
| Gradle 约定插件 | ✅ `phoneagent.root / kotlin.jvm / android.application / android.library / quality / coverage` | `build-logic/convention` |
| 版本号公式与单测 | ✅ | `AppVersion.kt`、`AppVersionTest` |
| 静态检查 | ✅ Spotless + ktlint 1.8；detekt 2.0.0-alpha.6（带类型解析）；Android lint（警告即错误）；explicitApi + allWarningsAsErrors | `config/detekt/` |
| "至少一个会被拒绝的反例" | ✅ `verifyDetektCanary`；手动验证过 explicitApi、Kover 两条规则、detekt 在真实模块里都会拦截。Android 模块的 detekt 类型解析要带上本模块 javac 的输出（AIDL 生成的 Java 只作为 Kotlin 的源码出现，不在编译任务的 libraries 里）；`:privileged:verifyDetektCanary` 在这一点失效时报错 | `config/detekt/canary/`、`config/detekt/canary-aidl/`、`AndroidConventions.kt` |
| Kover | ✅ 每类行覆盖 ≥ 90%；`critical` 变体校验 policy/agent 分支覆盖 100%；`@ExcludeFromCoverage` 的 reason 由测试检查 | `CoverageConventionPlugin.kt` |
| 依赖可复现 | ✅ 校验文件 + 禁止动态版本 | `gradle/verification-metadata.xml` |
| lefthook、gitleaks、.editorconfig、.gitattributes | ✅ | 仓库根目录 |
| CI：`ci.yml` + `all-checks-passed`、Dependabot、PR 模板 | ✅ 2026-11-01 在 GitHub 上首次跑通（前两次失败暴露了校验文件的冷缓存和 aapt2 平台问题，见 ADR）；main 已设为必须通过 `all-checks-passed` | `.github/` |
| AGENTS.md + 技能 | ✅ `pre-push-checks`、`code-review` | `.agents/skills/` |
| 推迟 | `add-tool`、`record-session` 两个技能（对应的工具注册和回放设施在 M1 落地，先写会误导代理）；Compose 字面量规则（M1）；`release.yml`（M2 前）；`eval/` 模块（M1） | — |
