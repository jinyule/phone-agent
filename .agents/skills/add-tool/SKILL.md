---
name: add-tool
description: Use when adding or changing a model-facing tool in phone-agent core (core.tools); walks the schema, risk class and policy classification, presentation, authored replay scenario, fixture refresh and prompt-surface goldens, in that order.
---

# Add a tool

按顺序做；每一步都有测试义务（AGENTS.md §5）。

## 1. Schema 和参数

- 在 `core/src/main/kotlin/dev/phoneagent/core/tools/` 实现 `PhaseOneTool`，登记进 `PhaseOneTools`。schema 的第一个必填参数是 `reason`。
- 参数只在边界校验（`ArgReader`）：模型给的 JSON 不可信，同进程里有类型的值信任编译器。
- 超时等可调参数是带校验的配置字段，不是常量。
- 测试：`PhaseOneToolsTest`（清单、顺序、schema 规则）加上这个工具自己的测试。

## 2. 风险分类和安全判定

- 给出 `RiskClass`；动作类工具还要写出 intent 的推导：`IntentResolver` 的实现是 `core.policy.rules` 里的 `RulesResolver`（规则见 `docs/design/policy-rules.md` §2），判定只在 `PolicyEngine`。产品和回放都经 `PolicyRules` 装配这三样（lexicon、resolver、engine），不要另起一份。
- 测试：intent 推导的每个分支一个测试；改了 `core.policy` 就要保持分支覆盖 100%（`koverVerifyCritical`）；规则或判定变了要升 `RULES_VERSION`。

## 3. 展示

- 轨迹卡片由 `PhaseOneTool` 统一生成（`presentCall`、`presentResult` 是 final）：摘要是 `reason`，明细是按 schema 顺序的参数加上结果的 `details`，都先经过 `mask`。工具只管把要上卡片的事实放进 `ToolResult.details`；key 由 app 映射成界面文案，文案走资源，卡片里只放数据。
- `mayChangePhone` 默认按 `RiskClass` 推出（不是 READ 就算）。只读却会改变 App 显示位置的工具（如 `hand_to_user`）要改写成 `true`：它决定被停止或超时的调用在卡片上显示成"结果未知"还是"失败"。
- 测试：卡片的明细和状态，参照 `PhaseOneToolsTest`、`MaskingBoundaryTest`。

## 4. 模型可见的文字

- 工具定义、反馈文字、系统提示词里提到它的地方，都在 prompt-surface 金样里。跑 `./gradlew :core:test`，审阅 `core/build/prompt-surface-actual/`，再按失败信息里的命令接受。

## 5. 回放

- 在 `AuthoredScenarios.kt` 里加一个用到这个工具的场景（或者扩展已有场景），加进 `ALL`，用 `-PsessionSnapshot=record` 写出 fixture，做隐私审查。
- 然后对全部 fixture 跑 `-PsessionSnapshot=refresh`：工具清单变了，所有请求和边车都变了。
- 命令清单和审阅方法见 `record-session` 技能第 2 节；fixture 和代码放在同一个 PR。

## 6. 提交前

用 `pre-push-checks` 技能挑检查集；改了 `core.policy` 或 `core.agent` 时 Kover 两个门槛都要过。
