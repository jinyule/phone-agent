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
├─ config/detekt/            detekt.yml + canary/（已知坏代码与必须报出的规则清单）
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
| core 内部的依赖方向：agent 不认识具体能力，能力之间不互相引用 | ArchUnit 或 Konsist 写的依赖测试（参考 Mika 的 `PackageDependencyTest`；M1 建包时加） |

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

**为什么要做**：主循环、提示词、上下文压缩、安全判定的回归，都能在没有真机、没有 key 的情况下确定性地复现。这是 dsh 最有价值的工程做法。手机场景的不同之处在于，工具的另一端是设备，所以**设备侧也要回放**。

**fixture 结构**：
```
core/src/test/resources/sessions/<scenario>/
├─ scenario.yml                 profile、模型协议、recording: live|authored、说明
├─ session.jsonl                事件溯源日志（phase1-architecture §3.9）
├─ blobs/                       截图等大对象，按内容哈希命名（可以为空）
├─ system-prompt.expected.md    边车：系统提示词全文
├─ tool-schemas.expected.json   边车：工具定义
└─ replay.override.json         可选：按调用下标注入异常、超时等
```

**回放怎么跑**：
- `ReplayChatModel`：从日志里的 assistant 事件得到逐次调用的脚本，**按调用顺序**提供回复；多出的调用报 "script exhausted"；结束时 `assertConsumed()`，少调用也失败；动态值用 `{{fromRequest:regex}}` 从实时请求中提取。
- `ReplayScreen`：按顺序提供录下的观察结果和动作结果；**同时断言本次请求的动作（工具名 + 归一化后的参数）与录制时一致**。
- 断言内容：
  - 每次模型请求（消息序列、工具清单）与录制时**逐字节一致**，这也是"模型可见 ⟺ 已记录"的执行检查；
  - PolicyEngine 的判定序列一致；
  - 事件序列一致（归一化之后）；
  - 两个边车文件一致。

**三种模式**（Gradle 属性 `-PsessionSnapshot=`）：
- `replay`：默认，CI 只跑这个。
- `record`：在真机加真实 key 上运行。来源可以是评测任务，也可以是手动任务；跑完自动做归一化、gitleaks 扫描，并生成一份**人工隐私审查清单**（截图、通讯录、聊天内容只允许来自测试账号）。
- `refresh`：无 key。模型输入不变、只是产品输出变了时，更新期望。

**归一化**：
- id 按首次出现替换成带类型的 token，如 `{{task:1}}`；
- 时间置零，耗时去掉；
- 设备相关的分辨率和包版本放进 `scenario.yml`。

**原则**：修 fixture，不修归一化器。任何非平凡的、模型可见或用户可见的改动，**同一个 PR 里**就要更新相应的 fixture。

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
| `ci.yml` | pull_request、push main、手动 | `static`（spotlessCheck、detektMain/detektTest、detekt canary、Android lint、gitleaks 扫全历史）· `core`（test + 会话回放 + `koverVerify` + `koverVerifyCritical` + build-logic 测试）· `app`（assembleDebug + Robolectric 单测）· `privileged-build`（构建 dex，检查 AIDL 版本常量）· **`all-checks-passed`**（汇总以上 job：failure、cancelled、skipped 都判红；**唯一的必过检查**） | P0 |
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
- CI 检查 AIDL 版本常量：改了 AIDL 文件就必须升版本号。

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
| 持久化变更 | Room 导出 schema + `MigrationTestHelper`；session 日志格式升版本时，旧版本的 fixture **永不改写** | P1 |
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
| `add-tool` | 新增工具的流程：schema → RiskClass 和 PolicyEngine 分类 → 展示函数 → 回放 fixture → 金样 | P0 |
| `record-session` | 录制、归一化、隐私审查 fixture | P0 |
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
| "至少一个会被拒绝的反例" | ✅ `verifyDetektCanary`；手动验证过 explicitApi、Kover 两条规则、detekt 在真实模块里都会拦截 | `config/detekt/canary/` |
| Kover | ✅ 每类行覆盖 ≥ 90%；`critical` 变体校验 policy/agent 分支覆盖 100%；`@ExcludeFromCoverage` 的 reason 由测试检查 | `CoverageConventionPlugin.kt` |
| 依赖可复现 | ✅ 校验文件 + 禁止动态版本 | `gradle/verification-metadata.xml` |
| lefthook、gitleaks、.editorconfig、.gitattributes | ✅ | 仓库根目录 |
| CI：`ci.yml` + `all-checks-passed`、Dependabot、PR 模板 | ✅（actionlint 通过；**还没在 GitHub 上跑过**） | `.github/` |
| AGENTS.md + 技能 | ✅ `pre-push-checks`、`code-review` | `.agents/skills/` |
| 推迟 | `add-tool`、`record-session` 两个技能（对应的工具注册和回放设施在 M1 落地，先写会误导代理）；Compose 字面量规则与包依赖测试（M1）；`release.yml`（M2 前）；`eval/` 模块（M1） | — |
