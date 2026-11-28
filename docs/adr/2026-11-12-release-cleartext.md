# release 也全局允许明文 HTTP：限制只放在 ProviderProfile，别的 HTTP 客户端各自拒绝明文

- 日期：2026-11-12
- 状态：采纳

## 背景

用户常在本机或局域网里跑模型服务，例如电脑上的 Ollama（`http://192.168.1.5:11434/v1`）、LM Studio，或者手机上 Termux 里的 llama.cpp（`http://127.0.0.1:8080/v1`）。这类服务基本只提供明文 http。让普通用户为局域网服务配 TLS 证书，不现实。

Android 的网络安全配置（`network_security_config.xml`）在平台层决定能不能走明文。OkHttp 在 Android 上也遵守它：不允许时，请求直接失败（`CLEARTEXT communication … not permitted`）。这套配置有三个限制：

- 只能按域名列出例外，不能写 IP 网段。"私网地址"（`10/8`、`172.16/12`、`192.168/16`、链路本地、IPv6 ULA）没法表达。
- 只能整个 App 一份。不能只给模型客户端放开，别的客户端照旧拦着。
- 运行时不能改。

WP09 A 段（2026-11-03）把 release 的 `base-config` 设成 `cleartextTrafficPermitted="true"`，取舍只写在 XML 注释里。审查（WP09 r1 S5）要求把它记成 ADR，因为这样一来，以后 App 里任何别的 HTTP 客户端，在平台层都没有明文拦截了。

## 决定

1. **平台层允许明文。** debug 和 release 用同一份 `network_security_config.xml`：`base-config` 允许明文，不加 `domain-config`。

2. **模型请求的明文限制，只在 `ProviderProfile` 里做**（core，`ProviderProfile.kt` 的 `baseUrlProblem` 和 `LocalHosts.kt` 的 `isLocalHost`）：
   - `https` 不限地址；
   - `http` 只允许本地地址：回环（`127/8`、`::1`）、私网（`10/8`、`172.16/12`、`192.168/16`、`fc00::/7`）、链路本地（`169.254/16`、`fe80::/10`）、`localhost`、`*.local`；
   - 判断只看字面量，不做 DNS 解析，所以域名指向私网也不算本地。

   设置文件加载、表单检查、适配器建模型，都要先构造 `ProviderProfile`，所以三处的结论一致。这是模型请求唯一的明文关口，放宽它要先改这份 ADR。

3. **其他 HTTP 客户端不能依赖平台层拦截，要各自拒绝明文。** 现在只有模型适配器这一个 HTTP 客户端（`core.model.openai` 的 `HttpTransport`）。以后新增客户端（自更新 D10、ChatGPT 登录的 token 交换、ToolServer 周边的出站请求、任何"附带日志"的上传）时，必须同时做到：
   - OkHttp 客户端的 `connectionSpecs` 里不放 `ConnectionSpec.CLEARTEXT`（例如只放 `MODERN_TLS`）。这样 OkHttp 在客户端这一层就拒绝 http。不用 OkHttp 的，连接前自己检查 scheme 是 `https`。
   - URL 只能来自代码里的常量，或者经过检查的类型，不直接取模型输出或远端返回的内容。
   - 有一个测试钉住"给 http 地址会被拒绝"。

   只有模型适配器的客户端可以带 `CLEARTEXT`，它的地址都经过第 2 条的检查。

4. **入站服务不受这份配置管。** ToolServer、ChatGPT 登录回调都是只绑 `127.0.0.1` 的本机服务，接收明文请求。网络安全配置只管 App 发出的连接，所以它们的约束（只绑回环、要令牌或 state）写在各自的设计里（phase1 §8、model-layer §4.2），和本 ADR 无关。

## Alternatives considered

- **`domain-config` 只给 `localhost`、`127.0.0.1`、`10.0.2.2` 放开明文，`base-config` 保持拦截：**
  - 能覆盖手机本机的服务和模拟器访问宿主机，但覆盖不了最常见的场景：局域网里另一台电脑上的 Ollama。它的地址是 DHCP 分配的 `192.168.x.y`，网络安全配置写不了网段，也没法事先列出具体 IP。
  - `*.local` 可以用 `includeSubdomains` 的 `local` 覆盖，但很多路由器不解析 mDNS，用户多半填 IP。
  - 结果是：大部分本地模型用户在 release 上一保存就看到 CLEARTEXT 失败，App 里却没有任何能修的办法。核心场景用不了，所以不选。
- **只在 debug 放开，release 全部拦截：**
  - release 就完全不能用本地 http 服务了。离线、隐私、免费是本地模型的主要动机，面向普通用户、侧载分发的 App 不能去掉它。
  - debug 和 release 的行为也会分叉：模拟器上测过的 `10.0.2.2` 配置，到 release 上失败。所以不选。
- **用户逐个确认明文地址（像浏览器的"不安全"提示）：**
  - 平台层照样要全局放开，因为配置运行时不能改。确认只是在 App 层多一道对话框，和 `ProviderProfile` 的检查在同一层，不会给别的客户端多一层平台拦截。
  - 本地服务是这个功能的主要用法，每次都确认，用户只会养成直接点"继续"的习惯。
  - 公网 http 本来就被 `ProviderProfile` 拒绝，确认能挡住的只剩"本地地址上的明文"，而那正是要支持的用法。代价大、收益小，所以不选。
- **让用户给局域网服务配 TLS（自签证书加 `user` 信任锚）：**
  - Android 7 以后，App 默认不信任用户安装的 CA。`trust-anchors` 可以放进 `domain-config`，只对列出的目的地信任 `user` CA，不必对所有 https 连接打开。
  - 但用户要为每个本地服务生成证书、装到服务上，再把 CA 装进手机。普通用户做不到。
  - `domain-config` 还得在打包时写出具体的域名或 IP。局域网地址是 DHCP 动态分配的，没法预先列出，和第一条备选的问题一样。所以不选。

## 后果

- 平台层不再兜底。哪个新 HTTP 客户端忘了第 3 条，就可能明文发出请求，而且不会有任何报错。代码评审必须核对第 3 条：新增 `OkHttpClient`、`HttpURLConnection`，或者别的发出网络请求的地方，都要有不含 `CLEARTEXT` 的 `connectionSpecs`（或者 scheme 检查）和对应的测试。以后可以把这一条加进 `.agents/skills/code-review` 的必查项，或者做成 detekt 规则。
- 局域网里的 key 是明文传输的。本地服务大多用 `Credential.None`（model-layer §2），确实填了 key 的，会经过局域网明文发送。WP09 r1 B3 已经保证：已存的 key 只发给保存时的 origin，改了地址就要重新填。
- 出现下面任一情况，要重新评估这份 ADR：
  - Android 的网络安全配置支持按网段放开明文；
  - 除模型适配器以外，还有别的客户端需要明文；
  - App 开始内嵌 WebView 或别的会加载远端内容的组件（它们同样不受平台拦截）；
  - `ProviderProfile` 的本地地址规则要放宽（例如允许域名解析到私网）。
