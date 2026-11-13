# 内置 ADB 在 App 侧的接线：Android 12 起步，封存身份，异步自动拉起

- 日期：2026-11-03
- 状态：采纳

## 背景

`core.adb`（WP13 A 段）只提供协议：配对、连接、跑固定命令。App 侧要补上这几件事：

- 平台 TLS exporter；
- 身份私钥的存储；
- mDNS；
- 拉起助手，并在助手死掉或过期时重新拉起。

约束：

- WP12 的 HelperClient 会在一把锁里调用监听者和 `onLaunchNeeded`，所以拉起不能在回调里同步等待交接（m2-notes 15:2x）；
- App 重启时，上一个进程留下的孤儿助手会带着续接口令回来，不能每次重启都并发拉起一个新助手（m2-notes 20:3x）；
- 口令只走 stdin；
- 扫描只扫回环，并且排除 App 自己占用的端口；
- 只有拉起所在的包和 debug 自检可以引用 `core.adb`。

## 决定

1. **只在 Android 12（API 31）及以上提供内置 ADB。** 配对要的 TLS 1.3 exporter 在 Android 上只有 `android.net.ssl.SSLSockets.exportKeyingMaterial`，它从 API 31 才有（`SSLSockets` 类本身从 29 就有）。Android 11 走兜底：Shizuku 或 USB 脚本。`BuiltinAdb` 标了 `@RequiresApi(S)`，调用方先判断 `SDK_INT`。
2. **身份私钥封存在 App 私有存储里。** PKCS#8 私钥用 AndroidKeyStore 里的 AES-256-GCM 密钥加密（别名 `phoneagent_adb_key_wrap`），写进 `noBackupFilesDir/adb/adbkey.sealed`：
   - 文件格式是一个格式字节加上 IV 和密文，经 AtomicFile 写入；
   - 打不开（封存密钥丢了、文件坏了、格式未知）就当作不可用，`AdbIdentity.loadOrCreate` 会换新身份并报 `REPLACED`。
   - 封存失败时 `write` 抛 IOException，旧文件不动；自动拉起记一笔日志，等下一次请求再试。
   - Keystore 自己内部的失败是非检查型的 `ProviderException`，和检查型的 `GeneralSecurityException` 一样处理（凭据存储的 `KeystoreCipher` 也是这样）。
3. **配对记录和端口缓存放在 SharedPreferences 里，用 `apply` 写**（`AdbPairingRecord`）。下面几种情况会清掉配对记录：
   - 新建或替换了身份；
   - 搜索以 UNAUTHORIZED 结束（`AdbConnectPort` 已经对同一个端点试过两次）。

   清掉以后不再自动拉起，直到用户重新配对。重新配对成功以后，本进程立即请求一次拉起（`startIfPaired` 第二次调用时转给 `requestLaunch`，仍受 `minLaunchIntervalMs` 约束），不用等进程重启。写丢了只有两种代价：下次搜索多花点时间，或者多要一次配对。
4. **自动拉起是异步的，不改 WP12。** `HelperAutoLaunch` 挂在 HelperClient 上，作为一个普通监听者，`onLaunchNeeded` 保持原样：
   - 监听者和启动检查只负责发请求，拉起在协程里执行；
   - 同一时间只跑一次拉起，期间来的请求合并成之后再跑一次；
   - 每次真正拉起之前，先看是不是已经连上；
   - 两次拉起的开始时间至少相隔 `minLaunchIntervalMs`（默认 10 s）；
   - 失败只记日志，不自动重试。
5. **进程启动时先等孤儿助手** `orphanResumeMs`（默认 5 s）：
   - 孤儿助手一看到 App 进程就会交接，失败后依次等 1 s、3 s、10 s 再试（`HelperLifecycle.DEFAULT_RETRY_DELAYS_MS`），所以 5 s 能盖住第 0、1、4 s 的三次尝试；
   - 时间过了还没连上（NotConnected 或 NeedsLaunch），才拉起；
   - 启动时已经是 NeedsLaunch（过期助手在监听者挂上之前就被拒了），就立即拉起，不等。
6. **拉起命令和 `scripts/start-helper.sh` 同形**：`read n; (echo "$n" | CLASSPATH=… setsid app_process … &); sleep 1`。stdin 是口令加 `\n`；`exec:` 下 stdin 不会关闭，`read` 要靠这个 `\n` 才能返回。口令在发命令之前才申请（`issueLaunchNonce`），所以不会出现在命令行和日志里。
7. **在 Conscrypt 上靠 BoringSSL 的原因认出对端 alert**（core 改动）：
   - 实测未配对的 key 收到的是 `SSLProtocolException`，消息里带着 `SSLV3_ALERT_CERTIFICATE_UNKNOWN`，而 A 段只看类型，会把它误判成 TLS；
   - 现在，消息里报的是对端 alert 的 `SSLProtocolException`，不再算我们自己的错误，在可能是拒绝的阶段归为 UNAUTHORIZED。JDK 收到的 alert 也按同一规则认，具体写法见 phase1 §7.1；
   - BoringSSL 把收到的 alert 记成原因码 1000 + alert 编号：有名字的写成 `SSLV3_*` / `TLSV1_*`（Android 12 起都带 `ALERT_`；Android 11 的表里 1110–1116 没有 `ALERT_`，例如 `TLSV1_CERTIFICATE_REQUIRED`），没有名字的写成 `reason(1NNN)`。它自己发现的错误都有自己的名字，没有以这两个前缀开头的（例如 `DECRYPTION_FAILED_OR_BAD_RECORD_MAC`、`BAD_ALERT`），仍然算 TLS。不按原因码的数值判断，因为它自己的原因也有 1000 以上的（1122 `PAKE_AND_KEY_SHARE_NOT_ALLOWED`）。
8. **扫描范围**：App 读不到 `/proc/sys/net/ipv4/ip_local_port_range`（Android 16 上是 EACCES），就退回 `LocalPortScan.FALLBACK_RANGE`（30000..65535），覆盖 Linux 默认的 32768..60999。`ownPorts` 现在为空：App 还没监听任何端口；ToolServer（phase1 §8）落地时要把自己的端口加进去。扫描只由 `loopbackScan` 构造，`BuiltinAdb` 和 debug 自检的 scan 场景用的是同一个。
9. **架构检查按字节码做**（build-logic 的 `verify<Variant>ClassBoundaries`，挂在 `check` 上，规则在 `AppClassBoundaries.kt`）：
   - 检查 debug 和 release 两个变体自己编译出的类（AGP 的 project 范围 `CLASSES`，Kotlin 和 javac 的产物都在里面）。一个类的字节里出现了包名（内部形式 `a/b/c/`，或者带点的形式，例如反射用的字符串常量），就算引用了这个包。
   - `dev.phoneagent.core.adb` 只能由 `dev.phoneagent.app.adb`（含子包）和 debug 自检引用。自检只放行 `AdbSelfCheck.kt` 编译出的 `AdbSelfCheck`、`AdbSelfCheckKt` 两个类（含它们的嵌套类和合成类），不再放行整个 `app.debug`。
   - `dev.phoneagent.app.adb` 只能由 `PhoneAgentApplication`（开启自动拉起）和同一个自检引用。所以经第三个包转手 `app.adb` 给出的东西，也要先在这里登记。
   - 源码里的写法都会落到字节码上：反引号、typealias（使用方写的是展开后的类型）、`src/main/java` 下的 .kt、.java、内联函数、经允许的类转手。所以原来的文本扫描 `CoreAdbBoundaryTest` 删掉了。
   - 已知的放宽和收紧：编译期常量会被拷贝，Kotlin 可能不留引用（常量本身不带能力）；字符串里带点的包名也算引用。
   - 扫描函数有单测（`ClassBoundariesTest`，构造的 class 字节和 javac 现编译的类）。规则本身是 build-logic 的代码，改清单就是改构建逻辑，要经过评审。

## Alternatives considered

- **Android 11 上反射调用 Conscrypt 的隐藏 exporter**：
  - 要用非 SDK 接口，受 hidden API 限制，版本之间也不稳定；
  - Android 11 的用户可以走 Shizuku 或 USB 脚本；
  - 所以不选。
- **私钥明文放在 `noBackupFilesDir`**：
  - 只有 App 自己读得到，但 core 的 `AdbKeyStore` 约定要求宿主加密存放；
  - Keystore 封存的代价只是密钥丢失时重新配对一次；
  - 所以不选。
- **用 androidx.security 的 EncryptedFile**：要新增依赖，而且这个库已经弃用；自己用 Keystore 加 GCM 只要几十行。不选。
- **改 WP12 的 `onLaunchNeeded`，在回调里直接拉起**：这个回调在 HelperClient 的锁里执行，等交接会死锁；改成监听者，就不用改 WP12 的接口。不选。
- **进程启动时立即拉起**：每次重启都会让新旧两个助手同时交接，WP12 已知缺口要求的"并发"就成了常态（m2-notes 20:3x）。不选。
- **失败后自动重试，或者盯着无线调试开关拉起**：
  - 无线调试关着、或者没配对时，重试只是空转；
  - 设置开关的监听属于断线恢复流程（phase1 §7.1），交给 WP15 的向导和 TaskService；
  - 这里先只记日志。
- **给 core 加一个平台钩子，由宿主判断异常是不是对端 alert**：
  - 判断依据还是 BoringSSL 的原因，放到宿主里也一样要匹配文本；
  - 在 JVM 上又测不到 Conscrypt；
  - 写在 core 里，至少能用实测的原文做单测。
  - 所以不选。

## 后果

- Android 11 没有内置 ADB，首次设置要走兜底路径。
- 恢复出厂或清除凭据以后，要重新配对一次。
- 缓存状态的 App 进程会被系统冻结（Android 14 起的 cached apps freezer）。实测：
  - 冻结期间收不到助手的死亡通知；
  - 进程下次运行时才收到通知并拉起。

  任务进行期间有 TaskService 前台服务，不受影响；空闲时助手死了，要等用户下次打开 App 或下一个任务。
- 对端 alert 的识别依赖 BoringSSL 写进消息的原因。要重新评估这一条的情况：Conscrypt 换了 TLS 实现，或者开始把对端 alert 报成单独的异常类型。到时以设备上的 `unpaired` 自检结果为准。
- `ownPorts` 要随 ToolServer 一起补上；否则扫描可能探测到自己的端口（不会误连：探测要求对端回 STLS）。
