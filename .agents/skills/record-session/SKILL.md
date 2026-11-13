---
name: record-session
description: Use when a phone-agent change touches anything model-visible or user-visible (prompts, tool schemas or feedback text, the loop, policy, screen format), when a session replay test fails, or when adding a session fixture; says when to record, refresh or edit an authored script, how to read a replay failure, and the privacy review.
---

# Record and refresh session fixtures

规则在 `docs/design/engineering-baseline.md` §3.2，这里只写操作步骤。**修 fixture，不修归一化器**：归一化规则是封闭的，回放失败时要改的是 fixture 或产品代码。

回放测试是 `SessionReplayTest`（`core/src/test/kotlin/dev/phoneagent/core/replay/`），每个 fixture 目录一个动态测试；authored 场景在同一个包的 `AuthoredScenarios.kt`。

## 1. 选哪种做法

| 情况 | 做法 |
|---|---|
| 模型输入没变，只是产品输出变了（提示词措辞、工具反馈、事件内容、判定、`RULES_VERSION`、analyzer） | `refresh` |
| 新场景，或者场景的剧情（模型怎么回、屏幕上有什么、用户怎么答）要变 | 改 `AuthoredScenarios.kt`（新场景要加进 `ALL`），再 `record` |
| 真模型加真机的录制（WP11 起） | `record` 的 live 来源；必须做第 4 节的隐私审查 |

`refresh` 永远不会改模型回复和设备事实。如果改动让录下的模型回复不再合理（比如工具改名了），那是剧情变了，走第二行。

## 2. 命令

录制 authored 场景（重写 `ALL` 里的每一个；剧情没变的场景写出来和原来逐字节相同；`record`、`refresh` 只跑 `dev.phoneagent.core.replay` 包）：

```bash
./gradlew :core:test -PsessionSnapshot=record
git status core/src/test/resources/sessions/            # 新目录和改动都要审阅，然后做第 4 节
```

改了模型可见内容时要跑的全部命令：

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

一个 fixture 的失败一次全部列出（`assertAll`，标题是场景名）；不一致的文件留在 `core/build/session-actual/<scenario>/`，可以直接和 fixture 做 diff。先看"走没走同一条路"，再看"产出变没变"：

1. **走了另一条路**：模型或设备的不一致（"script exhausted at request #n"、"prepare after the last recorded request"、"the recording made n requests, the replay m"、"no recorded port call left"、"call-n op k: the recording did …"），"stimuli never given"，"escaped the loop"，"requests the log does not rebuild"（铁律 1）。说明循环的行为变了，`refresh` 也救不了：要么是回归，要么剧情要改（第 1 节第二行）。
2. **policy pin**：`policy.rulesVersion: …` 或 `policy.tuning.<字段>: …`。规则版本或 tuning 和 fixture 录制时不同；审阅判定的变化以后 `refresh`。
3. **请求文件**（`requests.expected.jsonl`）：报出第一个不同的请求、它的位置（`.append[0].parts[0].text` 这样的 JSON 路径）和两边的整行。送进模型的东西变了；确认是有意的就 `refresh`。
4. **事件序列**（`session.jsonl`，归一化以后逐行比），同样报第一个不同的记录和字段。
5. **判定序列**（"policy/verdict and screen/judged, one line per decision"）：每行是一条 `policy/verdict` 或 `screen/judged`，写类型和用当前编解码器写出的完整 data。`policy/verdict` 含调用、档位、intent 和整个判定：Confirm 的卡片和原因，HandOff 的 kind，Refuse 的原因和提示。`screen/judged` 含屏、用途、结论、规则和提示。另有一条 "verdicts changed while RULES_VERSION is still N" 时，判定变了而规则版本没动：先在 `PolicyTuning` 里升 `RULES_VERSION`，再 `refresh`。
6. **边车**（系统提示词、工具定义）：和 prompt-surface 金样同一种写法，通常和金样一起变。

其他失败：
- `NormalizedFieldsTest` 报"unclassified fields"：日志或请求里新增了数值或 id 字段。按 `core/build/replay/normalized-fields.actual.txt` 把它加进 `core/src/test/resources/replay/normalized-fields.txt`，选一个类别（time、expiry、duration、seq、value、kept、task、session、call、prompt）。
- 装载失败（`ReplayFiles.load`）：信息里有文件名。"not normalized" 说明 fixture 被手改过，用 `record` 或 `refresh` 重新生成；"text shaped like a ..." 见第 4 节。
- `{{fromRequest:…}} matches nothing`：请求里已经没有模板要找的文字，剧情要改。
- 装载时 `UserScript` 失败：日志里有回放给不出的用户动作。目前不支持的是 SurfaceManager 移屏失败的 hand-off（结局 `FAILED`、`NOT_TAKEN_BACK`）和崩溃后的恢复（`session/resumed` 前面不是 `task/stopped`）；一次添加多条输入的 `inbox/spliced` 也不支持。这类会话先不做成 fixture。

## 4. 隐私审查（每次 record 之后，提交之前）

- 打开 `core/build/session-record/<scenario>/privacy-checklist.md`，逐项确认截图和用户图片、屏幕文字、用户输入和回答、App 列表都来自测试账号；标着"suspected number"的项（像卡号、手机号）要逐个看；看一遍 `blobs/` 里的每张图。
- 录制会用 gitleaks 扫 fixture 目录，扫出东西录制就失败，信息里有规则、文件和行（密钥是 REDACTED）。gitleaks 自己出错（配置读不了、超时）时录制也失败，信息写的是 "gitleaks failed"、fixture 没扫过，不是"有泄漏"。清单写"gitleaks 未扫描"（本机没装，测试输出里也有警告）时，本机装上 gitleaks 再扫一次，或者在 PR 里写明。
- 清单里 `{{credential:n}}` 有命中，说明 key 进了日志：先查清楚是怎么进去的。
- 任何模式装载 fixture 时，出现 `sk-`、JWT、`Bearer` 形状的文字都会失败。不要为了过检查去改写这类文字：它本来就不该在 fixture 里。
- 测试用的假 key 写成明显的占位，例如 `sk-test-not-a-real-key`，只放在单测里，不放进 fixture。
