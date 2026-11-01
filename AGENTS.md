# AGENTS.md

本文件是常驻规则，**上限 1500 词**。一个事实只放在一个地方，这里只给结论和指向；要加内容，先把别的内容搬走或压缩。

## 1. 项目与模块

PhoneAgent：开源、免费、侧载分发的安卓手机 GUI Agent。面向普通用户，兼容开发者；任务在**后台虚拟屏**上执行。当前阶段：**M0 工程基线**（`docs/design/phase1-architecture.md` §10）。

| 模块 | 内容 | 约束 |
|---|---|---|
| `core/` | 纯 Kotlin/JVM：主循环、session 日志、模型适配、工具、PolicyEngine | 不依赖 Android SDK；`explicitApi`；Kover 门槛 |
| `privileged/` | shell 身份（uid 2000）的助手，经 `app_process` 拉起 | 依赖尽量少；`explicitApi` |
| `app/` | Android 宿主：Compose UI、服务、适配器 | 文案只走资源 |
| `build-logic/` | Gradle 约定插件（`phoneagent.*`）与版本号公式 | 构建规则只写在这里 |

设计文档：`docs/design/`（架构、模型层、工程基线）。调研：`docs/research/`。决策记录：`docs/adr/`。

## 2. 常用命令

```bash
./gradlew check                       # 全部本地门禁：detekt（含类型解析）、canary、lint、单测、Kover
./gradlew spotlessApply               # 格式化（ktlint）
./gradlew :core:test                  # 只跑 core 单测
./gradlew :app:assembleDebug          # 构建调试 APK
./gradlew :build-logic:convention:check   # 版本号公式等构建逻辑的测试
lefthook install                      # 每个 clone 装一次本地钩子
```

- JDK 由 `gradle/gradle-daemon-jvm.properties` 固定为 Temurin 25，缺了 Gradle 会自动下载；字节码目标是 17。
- **改了依赖**，要重新生成校验文件，并和依赖改动一起提交：
  `./gradlew --write-verification-metadata sha256 --no-configuration-cache check :app:assembleDebug :app:assembleRelease`

## 3. 架构铁律

1. **模型可见 ⟺ 已记录**：进入模型请求的一切都能从 session 日志重建；请求组装是对日志的纯函数推导。
2. **主循环只通过固定拦截点扩展**（`preStep`、`request`、`requestError`、`toolPreExecute`、`toolExecute`、`toolPostExecute`、`turnStopping`）。改 `agent/` 包要同步更新架构文档 §3.8。
3. **安全判定只在 PolicyEngine**，它是 `toolPreExecute` 的最后一道 guard，不可重排。
4. **不向模型暴露任意 shell**：特权助手只提供有类型的 AIDL 方法。
5. **每次工具调用都带 taskId**。
6. **ChatGPT 订阅登录只走官方 token sharing**，不借用 Codex CLI 的 client_id（`docs/design/model-layer.md` §4）。
7. **Anthropic 路径的历史只追加**：客户端不改写旧消息，旧内容交给服务端 context editing / compaction 处理（model-layer §5）。

## 4. 编码规范（机器查不了的部分）

能机械检查的规则都在 detekt、编译器和 lint 里（`config/detekt/detekt.yml`，基线文档 §2）；这里只列需要人判断的：

- 跨边界的 id 用 `@JvmInline value class`（`TaskId`、`SessionId`、`ToolCallId`……），不用裸 `String`。
- **只在边界做校验**：模型输出的工具 JSON、配置、文件、Binder/IPC、网络。同进程内有类型的值信任编译器。
- **配置错误要大声失败**：在加载时校验；可调参数是带校验的配置字段，不是散落的常量。
- `catch` 要给错误命名并说明原因，`try` 只包一条语句；协程里不吞 `CancellationException`。
- 注释只写契约（行为、失败、时序、归属），不写推理过程。
- 依赖方向：`app → core`、`app → privileged`；core 内部 `agent` 不认识具体能力，能力之间不互相引用。
- 新增 detekt 依赖的规则时，要在 `config/detekt/canary/` 里补一个会被它拒绝的反例，并加进 `expected-rules.txt`。

## 5. 测试义务

| 改动 | 必须同时做 |
|---|---|
| core 的任何类 | 单测；每个类行覆盖 ≥ 90%（`koverVerify`） |
| `core.policy`、`core.agent` | 分支覆盖 100%（`koverVerifyCritical`） |
| 确实无法在 JVM 上测的代码 | `@ExcludeFromCoverage(reason = "…")`，reason 不能为空 |
| 模型可见内容（提示词、工具 schema、反馈文字） | 同一个 PR 里更新回放 fixture 和金样（M1 起） |
| `IPrivilegedCore` | 升 `PrivilegedProtocol.VERSION` |
| 版本号规则 | `AppVersionTest` |

修 fixture，不修归一化器。

## 6. 隐私与密钥

- fixture、截图、日志只能来自测试账号；提交前看一遍截图。
- 不提交密钥：gitleaks 在 pre-commit 和 CI 里扫描。签名材料（`*.jks`、`keystore.properties`）不进仓库。
- session 日志默认不上传，任何"附带日志"的功能都要用户显式同意。

## 7. 协作

- PR 用模板（Motivation / Changes / Testing + 勾选项）；唯一的必过检查是 `all-checks-passed`。
- 需要长期保留理由的决策写 ADR（`docs/adr/`，"Alternatives considered" 必填）。
- 技能在 `.agents/skills/`：`pre-push-checks`（按变更面挑检查集）、`code-review`（必查项）。
- 本项目是 Apache-2.0。借鉴外部代码只取兼容许可的项目（如 Mika、DSH-Android），保留原文件头并在 `NOTICE` 里登记；dsh-Go 是 AGPL，不借代码。
