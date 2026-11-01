---
name: code-review
description: Use when reviewing a phone-agent diff or PR; lists the project-specific must-check items that static analysis cannot catch.
---

# Code review（phone-agent 必查项）

先读 `AGENTS.md` §3–§5。机器能查的（格式、detekt、explicitApi、lint、覆盖率、gitleaks）交给 CI，review 只看下面这些，**每条都要明确给出"通过 / 有问题 / 不适用"**。

## 必查项

1. **taskId 贯穿**：每个工具调用、session 事件、特权助手调用都带 taskId；没有"全局当前任务"这种隐式状态。
2. **取消语义**：
   - 协程里没有吞掉 `CancellationException`（含 `runCatching`、`catch (e: Exception)`）；
   - steer 只取消进行中的模型请求，不打断正在执行的工具；
   - 写动作执行前已刷盘，刷盘失败就不执行。
3. **只在边界校验**：模型输出的工具参数、配置、文件、IPC、网络响应都在入口校验；内部不重复做防御性检查，也没有遗漏入口校验。
4. **模型可见 ⟺ 已记录**：新增或修改的模型可见内容都能从 session 日志重建；有对应的回放 fixture 或金样更新。
5. **PolicyEngine 绕过风险**：
   - 新的写动作或新工具有没有 RiskClass，有没有经过 `toolPreExecute`？
   - 有没有路径能绕开 PolicyEngine 直接调到 ScreenPort 或特权助手？
6. **特权边界**：AIDL 没有新增"执行任意命令"的能力；改了 `IPrivilegedCore` 就升了 `PrivilegedProtocol.VERSION`；身份校验（uid、pid、签名指纹）没有被削弱。
7. **隐藏 API 的降级路径**：用到隐藏 API 或特定 ROM 行为的地方，有能力探测和失败时的降级，并且有日志。
8. **按协议区分的上下文处理**：Anthropic 路径没有改写旧消息（preserved thinking）；ChatGPT 套餐模式的 Responses 请求没有带被禁止的字段。
9. **隐私**：fixture、截图、日志里没有真实个人数据；没有默认上传。
10. **文档同步**：改了主循环 → 架构文档 §3.8；新决策 → ADR。

## 输出

按严重程度排序：Blocker / Should fix / Nit。每条给出 `文件:行号`、问题、失败场景（什么输入导致什么错误结果）和建议。没有问题的项写"通过"，不要省略。
