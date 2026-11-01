# M0 工具链：JDK 25 + AGP 9.5 + Kotlin 2.4，detekt 用 2.0 alpha，依赖靠校验文件锁定

- 日期：2026-11-01
- 状态：采纳

## 背景

M0 要在写第一行业务代码之前把门禁搭好（`docs/design/engineering-baseline.md`）。2026-11 时的现状：
- Gradle 9.8.2、AGP 9.5.0、Kotlin 2.4.21 都是稳定版；
- Android 17（API 37）已经发布；
- detekt 1.x 停在 1.23.8，基于 Kotlin 2.0，不支持 Kotlin 2.4 的类型解析；
- 本机只有 JDK 17 和 27，Android Studio 自带 JBR 25。

## 决定

1. **构建 JDK 用 Temurin 25**，写在 `gradle/gradle-daemon-jvm.properties` 里，本机缺了 Gradle 会自动下载；CI 用 `setup-java` 装同一个版本。所有模块的**字节码目标是 17**（core 额外加 `-Xjdk-release=17`），保证 Android 能用 core 的类。
2. **detekt 用 `2.0.0-alpha.6`**。它基于 Kotlin 2.4.10、Gradle 9.6、AGP 9.3 构建，在 JDK 25 上测过，和我们的版本基本对齐。门禁用带类型解析的 `detektMain` / `detektTest`。
3. **SDK**：compileSdk 37、targetSdk 36、minSdk 30。targetSdk 先停在 36，等 M1 评估完 Android 17 的行为变更（无障碍、后台启动、虚拟屏相关）再升。lint 里关掉 `OldTargetApi`，免得这个有意的滞后变成 lint 错误。
4. **可复现的依赖解析**：
   - `gradle/verification-metadata.xml` 记录每个构件的 SHA-256；
   - 所有配置都开启 `failOnDynamicVersions` 和 `failOnChangingVersions`；
   - **不用 Gradle lockfile**。
5. 两个兼容性补丁，都写在 build-logic 里并注明原因：
   - Robolectric（SDK 36 沙箱）在 JDK 21+ 上要加 `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`；
   - 用依赖约束把 lifecycle 升到 2.11.0，因为 2.9.x 自带的 lint 检查和 AGP 9.5 的 lint 不兼容（`ObsoleteLintCustomCheck`）。

## Alternatives considered

- **detekt 1.23.8**：不支持 Kotlin 2.4，类型解析规则会失效或直接报错。
- **只用 ktlint、不用 detekt**：会丢掉基线文档 §2 依赖的规则，比如 `SuspendFunSwallowedCancellation`、`GlobalCoroutineUsage`、`ForbiddenMethodCall`、`ElseCaseInsteadOfExhaustiveWhen`、`Undocumented*`。
- **自己写 detekt 规则检查"runCatching 包住挂起调用"**：detekt 自带的 `SuspendFunSwallowedCancellation` 已经覆盖了。
- **JDK 21**：本机没有装；JDK 25 和 Android Studio 的 JBR 一致，也是 detekt 的测试 JDK。
- **JDK 27**：太新，AGP 和 Kotlin 都没有声明支持。
- **targetSdk 37**：行为变更还没评估，而无障碍和后台能力是本项目的核心。
- **Gradle lockfile**：AGP 项目会生成大量 lockfile，噪音很大；而且所有版本都来自 catalog、又禁止了动态版本，lockfile 只多出"传递依赖不会悄悄变化"这一点保证，这一点校验文件同样能保证。

## 后果

- **alpha 风险**：detekt 2.0 可能有误报或规则改名。对策：
  - `verifyDetektCanary` 会检查我们依赖的每条规则仍然能在已知的坏代码上报出问题；
  - 版本固定；正式版发布后升级，并重新跑 canary。
- **改依赖的成本**：每次改依赖都要重新生成校验文件，Dependabot 的 PR 需要人在分支上补一次（命令写在 `AGENTS.md` §2 和 `.github/dependabot.yml`）。
- **校验文件要在冷缓存下生成**：第一次 CI 就因此失败过。本机缓存里已有的组件按 `.pom` 解析，CI 冷缓存按 `.module` 解析，校验文件里缺了后者。所以生成命令固定加 `-g "$(mktemp -d)"`，一次大约 3–4 分钟。
- **已知缺口**：Robolectric 的 android-all jar 由 Robolectric 自己从 Maven Central 下载，不经过 Gradle，所以不受校验文件保护。它只用于测试，不进 APK。
- **何时重新评估**：detekt 2.0 正式版发布时；M1 评估完 Android 17 的行为变更时；AGP 或 Robolectric 修复上面两个兼容性问题时（届时删掉对应的补丁）。
