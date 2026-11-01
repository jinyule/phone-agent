---
name: pre-push-checks
description: Use before pushing or opening a PR in phone-agent; picks the smallest set of Gradle checks that covers the changed files instead of running everything.
---

# Pre-push checks

先看变更面，再挑最小的检查集。不默认跑全量，也不重复跑本轮已经通过、且之后没有相关改动的检查。

## 1. 看变更面

```bash
git status --short
git diff --name-only origin/main...HEAD   # 已提交的部分
```

## 2. 按路径挑检查

| 改了什么 | 跑什么 |
|---|---|
| `core/src/main/**` | `./gradlew :core:test :core:koverVerify :core:koverVerifyCritical :core:detektMain` |
| `core/src/test/**` | `./gradlew :core:test :core:detektTest` |
| `privileged/**` | `./gradlew :privileged:assembleDebug :privileged:detektMain :privileged:lintDebug` |
| `app/src/main/**` | `./gradlew :app:assembleDebug :app:detektMain :app:lintDebug` |
| `app/src/test/**` 或 `app/src/main/res/**` | 再加 `:app:testDebugUnitTest` |
| `build-logic/**` | `./gradlew :build-logic:convention:check`，然后跑一次 `./gradlew check`（约定插件影响所有模块） |
| `config/detekt/**` | `./gradlew verifyDetektCanary detektMain detektTest` |
| `gradle/libs.versions.toml`、任何 `*.gradle.kts` | 先重新生成校验文件（见 AGENTS.md §2），再跑 `./gradlew check :app:assembleDebug` |
| `.github/**` | 有 actionlint 就跑 `actionlint`；没有就仔细读 diff，并说明没有本地验证 |
| 只改了 `docs/**` 或 `*.md` | `./gradlew spotlessCheck` |

任何 Kotlin 或 kts 改动，最后都跑一次 `./gradlew spotlessCheck`（pre-commit 钩子会自动 `spotlessApply`）。

## 3. 汇报

列出跑了哪些命令和结果；跳过了什么、为什么跳过。失败要贴出关键输出，不要概括成"有点问题"。
