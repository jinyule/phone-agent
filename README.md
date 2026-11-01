# PhoneAgent

开源、免费的安卓手机 GUI Agent：用自然语言描述任务，它在**后台虚拟屏**上替你操作 App，不占用你正在用的屏幕。面向普通用户，兼容开发者（MCP 接口）。通过 GitHub Releases 侧载分发，暂不上应用商店。

> 状态：**M0 工程基线**，还没有可用功能。路线图见 [`docs/design/phase1-architecture.md`](docs/design/phase1-architecture.md) §10。

## 系统要求

- Android 11+（API 30）；后台屏需要 Android 14+（API 34）
- 模型：OpenAI Chat Completions 兼容接口、OpenAI Responses、Anthropic Messages；可以自带 API Key，也可以用 ChatGPT 订阅账号登录（见 [`docs/design/model-layer.md`](docs/design/model-layer.md)）

## 开发

```bash
./gradlew check               # 全部本地门禁
./gradlew :app:assembleDebug  # 调试 APK
lefthook install              # 本地 git 钩子
```

需要 Android SDK（`ANDROID_HOME`）。JDK 由 Gradle 按 `gradle/gradle-daemon-jvm.properties` 自动准备。贡献前请读 [`AGENTS.md`](AGENTS.md)。

## 文档

- 设计：[`docs/design/`](docs/design/)（架构、模型层、工程基线）
- 调研：[`docs/research/`](docs/research/)
- 决策记录：[`docs/adr/`](docs/adr/)

## 许可证

[Apache-2.0](LICENSE)。改编自第三方的代码在 [`NOTICE`](NOTICE) 里注明出处。
