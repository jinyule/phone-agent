---
name: record-session
description: Use when a phone-agent change touches anything model-visible or user-visible (prompts, tool schemas or feedback text, the loop, policy, screen format), when a session replay test fails, or when adding a session fixture; says when to record, refresh or edit an authored script, how to read a replay failure, and the privacy review.
---

# Record and refresh session fixtures

规则在 `docs/design/engineering-baseline.md` §3.2，这里只写操作步骤。**修 fixture，不修归一化器**：归一化规则是封闭的，回放失败时要改的是 fixture 或产品代码。

> 草稿（WP08 2a）。回放测试 `SessionReplayTest`、authored 场景 `AuthoredScenarios` 和录制器在 WP08 2b 合入；在那之前 `record`、`refresh` 只跑 `dev.phoneagent.core.replay` 包里的单测。

## 1. 选哪种做法

| 情况 | 做法 |
|---|---|
| 模型输入没变，只是产品输出变了（提示词措辞、工具反馈、事件内容、判定、`RULES_VERSION`、analyzer） | `refresh` |
| 新场景，或者场景的剧情（模型怎么回、屏幕上有什么、用户怎么答）要变 | 改 `AuthoredScenarios.kt`，再 `record` 这个场景 |
| 真模型加真机的录制（WP11 起） | `record` 的 live 来源；必须做第 4 节的隐私审查 |

`refresh` 永远不会改模型回复和设备事实。如果改动让录下的模型回复不再合理（比如工具改名了），那是剧情变了，走第二行。

## 2. 改了模型可见内容时要跑的全部命令

```bash
./gradlew :core:test                                    # 看哪些期望变了
# prompt-surface 金样和 loop 金样：审阅 core/build/prompt-surface-actual/ 以后接受
cp -R core/build/prompt-surface-actual/. core/src/test/resources/prompt-surface/
./gradlew :core:test -PsessionSnapshot=refresh          # 会话 fixture 就地重写
git diff core/src/test/resources/                       # 逐个审阅：每处变化都要说得出原因
./gradlew :core:test                                    # 回到 replay，确认全绿
```

prompt-surface 金样和 loop 金样用上面同一条 `cp -R` 接受，会话 fixture 用 `refresh` 重写；`refresh` 不会顺带重写两类金样。fixture 和代码改动放在**同一个 PR**。

## 3. 回放失败怎么看

失败报告一次列出全部不一致，按这个顺序看：

1. **请求文件**（`requests.expected.jsonl`）：报出第一个不同的请求和字段。说明送进模型的东西变了；确认是有意的就 `refresh`。
2. **判定序列**：按调用列出档位、intent、判定和原因。出现"判定变了但没升 RULES_VERSION"时，先在 `PolicyTuning` 里升版本，再 `refresh`。
3. **事件序列**（归一化以后逐行比）。
4. **边车**（系统提示词、工具定义）。
5. **没用完**：脚本、设备队列、override、外部输入还有剩余，或者多出了调用（"script exhausted at request #n"、"no recorded port call left"）。说明循环走了另一条路，通常要改剧情。

其他失败：
- `NormalizedFieldsTest` 报"unclassified fields"：日志或请求里新增了数值或 id 字段。按 `core/build/replay/normalized-fields.actual.txt` 把它加进 `core/src/test/resources/replay/normalized-fields.txt`，选一个类别（time、expiry、duration、seq、value、kept、task、session、call、prompt）。
- 装载失败（`ReplayFiles.load`）：信息里有文件名。"not normalized" 说明 fixture 被手改过，用 `record` 或 `refresh` 重新生成；"text shaped like a ..." 见第 4 节。
- `{{fromRequest:…}} matches nothing`：请求里已经没有模板要找的文字，剧情要改。

## 4. 隐私审查（每次 record 之后，提交之前）

- 打开 `core/build/session-record/<scenario>/privacy-checklist.md`，逐项确认截图、屏幕文字、用户输入和回答、AppCatalog 都来自测试账号；看一遍 `blobs/` 里的每张图。
- 清单写"gitleaks 未扫描"时，本机装上 gitleaks 再扫一次，或者在 PR 里写明。
- 清单里 `{{credential:n}}` 有命中，说明 key 进了日志：先查清楚是怎么进去的。
- 任何模式装载 fixture 时，出现 `sk-`、JWT、`Bearer` 形状的文字都会失败。不要为了过检查去改写这类文字：它本来就不该在 fixture 里。
- 测试用的假 key 写成明显的占位，例如 `sk-test-not-a-real-key`，只放在单测里，不放进 fixture。
