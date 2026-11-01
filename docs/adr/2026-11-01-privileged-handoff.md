# 特权助手交接：自钉签名、三种一次性口令、冻结 1–4 号事务、协议指纹表

- 日期：2026-11-01
- 状态：提议（WP12 A 段，待评审）

## 背景

phase1-architecture §2.2 / §3.5 和 engineering-baseline §6 定了特权助手的大框架：
- 助手经 `app_process` 以 shell 身份（uid 2000）运行，代码从 `base.apk` 加载；
- 通过 App 的 ContentProvider `call()` 交出 Binder，交之前核对 App 的签名证书指纹；
- App 收下前用 `identity()` 核对 uid 和 pid；
- 一次性口令经 stdin 传入；
- App 死后助手不退出，超时后 `releaseAll`；
- 协议或 versionCode 错配时让旧助手释放并退出；
- 改了 AIDL 就必须升 `PrivilegedProtocol.VERSION`。

落地时还有几件事框架没说清：
1. 助手怎么知道"正确的签名指纹"是哪个；
2. 电脑侧启动脚本的口令从哪来；
3. App 重启后，助手怎么重新交接；
4. 新版本的 App 怎么和协议不同的旧助手说话；
5. "未实现"怎么表达；
6. "改了 AIDL"怎么判定。

约束：
- 同为 shell 身份的其他进程能读 `/proc/<pid>/cmdline`，也能以 shell 身份调用 ContentProvider；
- 普通 App 不是 shell；
- App 更新后，旧助手还在跑旧代码；
- `privileged` 模块依赖尽量少（不引入协程）；
- `ServiceSpecificException` 不在公开 SDK 里。

## 决定

1. **签名指纹自钉**。启动参数里不带指纹。
   - 助手只在 `CLASSPATH` 等于目标包当前安装的 `sourceDir` 时才启动，也就是运行的代码就来自这个 APK。
   - 启动时记下这个包的 uid 和签名证书的 SHA-256（`apkContentsSigners` 排序后拼接，见 `CertPin`）。
   - 之后每次交接前都重新确认：包还装着，uid 和证书没变，Provider 也属于这个 uid。
   - 结果是：Binder 只会交给和助手本身同一把钥匙签名的 App。

2. **交接 Provider**：`<applicationId>.helper`。
   - 声明为 `exported`，访问权限设为 `INTERACT_ACROSS_USERS_FULL`：shell 持有这个权限，普通 App 拿不到。代码里再判一次调用方 uid 必须是 2000。
   - 提供三个方法：
     - `attach`：交接；
     - `arm`：预约口令（只收 shell 的调用）；
     - `status`：给脚本查状态（只收 shell 的调用）。

3. **App 的收下顺序**（`HandoffAdmission`），任何一步不过都拒绝：
   1. 调用方是 uid 2000，并且助手自报的 uid/pid 等于 Binder 调用的 uid/pid；
   2. 口令有效（这一步在碰对方 Binder 之前）；
   3. `identity()` 返回的 `[uid, pid]` 就是这个调用进程；
   4. `protocolVersion()`、versionCode、APK 路径与 App 自己一致。

   只有第 4 步不过的是**旧助手**：App 用掉它的口令，调它的 `releaseAll()`，回复 `STALE_PROTOCOL` 或 `STALE_APK`。助手收到拒绝就释放并退出，App 进入 `NeedsLaunch`。

4. **三种一次性口令**，都是 32 字节随机数的十六进制，比较时用常量时间：
   - **启动口令**：App 自己拉起助手时（WP13）签发，30 秒有效；
   - **预约口令**：`scripts/start-helper.sh` 在电脑上生成口令，先用 `content call --method arm` 把口令的 SHA-256 交给 App，再经 stdin 把明文交给助手，60 秒有效。App 只存哈希；
   - **续接口令**：每次交接成功，App 都回给助手下一个口令。App 把它持久化在 SharedPreferences（用 `commit`），App 重启后助手用它重新交接。

   用掉哪个口令就只清哪个，计时用 `elapsedRealtime`。口令从不进命令行和日志（相关类型的 `toString` 都做了脱敏）。

5. **助手生命周期**是一个纯状态机（`HelperLifecycle`），由 `HelperRuntime` 在单个 HandlerThread 上驱动：
   - 首次交接按 0/1/3/10 秒重试，仍失败就退出（退出码 1）；
   - App 拒绝：释放并退出；
   - App 进程死亡（通过对 App 交来的 token 做 `linkToDeath` 得知）：进入 `Orphaned`，宽限期过后 `releaseAll`，然后进入 `Idle`。宽限期默认 10 秒，可用 `--orphan-grace-ms` 调整；
   - App 进程再起来（`ActivityManager.addOnUidImportanceListener`）：用续接口令重新交接；
   - **新会话开始前一定先释放上一会话的资源**。计时器带会话号，迟到的计时器会被忽略；
   - App 被卸载或换了证书：释放并退出。

6. **版本错配**。
   - AIDL 的事务号全部显式写出，`protocolVersion = 1`、`identity = 2`、`releaseAll = 3`、`oneway shutdown = 4` 永久冻结。这样任何版本的 App 都能读出任何版本助手的协议号，也能让它释放并退出。
   - App 收下新助手时，对被替换的旧助手调 `shutdown()`。

7. **未实现的方法**抛 `UnsupportedOperationException`，消息以 `NOT_IMPLEMENTED` 开头，用 `PrivilegedError.isNotImplemented` 判定。Binder 原生就能跨进程传这个异常。

8. **AIDL 变更检查**。
   - `privileged/protocol-history.txt` 是只追加的 `<版本号> <指纹>` 列表。指纹是所有 `.aidl` 文件去掉注释、压缩空白后，按相对路径算出的 SHA-256。
   - `:privileged:verifyPrivilegedProtocol` 要求列表最后一行等于当前 `VERSION` 和当前指纹，失败时直接给出该追加的那一行。
   - 这个任务挂在 `check` 上，CI 的 `privileged-build` job 也单独跑它。
   - 反例单测：`PrivilegedProtocolCheckTest.changedAidlWithoutAVersionBumpFails`。

9. **debug 自检入口**是 `HelperSelfCheckReceiver`，只在 `src/debug` 里。
   - 它声明为 `exported="true"`，同时要求 `android.permission.DUMP`：shell 持有这个权限，普通 App 拿不到。androidx 的 `ProfileInstallReceiver` 也是这个写法。
   - 不用 `exported="false"`：非 root 的 shell 用 `am` 也调不到未导出的组件。

## Alternatives considered

- **签名指纹作为启动参数传入**（Mika 的做法）。
  - 脚本得先在电脑上算出指纹（`apksigner` 或解析 `dumpsys package`），多一个可能出错的输入。
  - 参数和代码来自两个来源，可能对不上。自钉之后，指纹和代码出自同一个 APK，不会对不上。
- **USB 口令写进 App 外部目录里的脚本文件**（Mika 的做法）。
  - 口令要长期以明文躺在手机存储上；为了跨过重启，它的有效期还得用墙上时钟算。
  - 预约口令的明文只在电脑和管道里出现，App 只存哈希，60 秒就过期。
- **App 签发口令，脚本用 `content call` 取回明文**。安全性和"预约"相当，因为两者都只对 shell 开放。不过取回明文意味着 App 的回复里带着口令；预约方式下 App 从不持有脚本口令的明文，所以选了预约。
- **AIDL 默认的顺序事务号**。在中间加一个方法，后面所有方法的事务号都会移位，新版本 App 就调不到旧助手的 `releaseAll`。所以显式写号，并冻结 1–4 号。
- **按进程名杀掉旧助手**（`pidof` + `kill`）。
  - App 的 uid 杀不了 uid 2000 的进程；
  - 让新助手去杀旧助手，前提是新助手已经跑起来，而且被杀的旧助手来不及释放虚拟屏和静音。
  - 通过 Binder 让旧助手 `shutdown()`，它可以先释放资源再退出。
- **"未实现"用负数错误码或 `ServiceSpecificException` 表示**。
  - 负数会和 B 段各方法自己的错误返回值混在一起，`void` 和 `Bundle` 方法也没地方放错误码；
  - `ServiceSpecificException` 不在公开 SDK 里，编译不过。
- **App 死后立刻 `releaseAll`**。简报和 §2.2 都要求先等一段时间，所以保留宽限期。代价见"后果"。
- **App 回来时保留旧会话的资源，交给新会话接着用**。新的 App 进程不知道旧会话建了哪些屏、静音了哪些包，容易泄漏，所以 A 段选了"先释放，再开新会话"。
- **轮询 `/proc` 来判断 App 是否活着或已重启**。轮询有延迟，还耗电。`linkToDeath` 和 uid importance 监听都是系统主动通知。
- **用协程 / StateFlow 管理助手状态**。`privileged` 要尽量少依赖，所以用纯状态机加 HandlerThread，也方便在 JVM 上逐个事件测试。
- **AIDL 检查的其他做法**：
  - 和 git 基线比较：需要完整历史，fork 来的 PR 和离线环境都不稳；
  - 把哈希写在 Kotlin 常量里：不直观，而且容易连同 AIDL 一起被顺手改掉；
  - 用 stable AIDL 的 `aidl_interface`：只有 Soong 支持，Gradle 用不了。

## 后果

- **shell 身份就是信任边界**。任何 shell 进程都能 `arm` 自己的口令，再交一个假助手给 App。但它本来就拥有 shell 的全部权限，伪装助手并不能多得到什么。真正要防的是非 shell 调用方（靠 uid 检查和 Provider 权限挡住），以及重复的或过期的助手（靠口令和版本检查挡住）。
- A 段里宽限期只是推迟释放：App 回来时也会先释放。WP14（SurfaceManager）如果需要让虚拟屏跨 App 重启存活（例如用 `VirtualDisplay.setSurface` 换成新的 Surface），要在新的协议版本里加一个显式的接管方法，并改 `Orphaned` 状态的语义。届时重新评估本条。
- 只支持主用户（uid 必须小于 100000）。工作资料和多用户以后再说。
- API 30 上 `IContentProvider.call` 的参数和 31 以上不同，已按版本分开处理，但只在 API 36 模拟器上实测过。
- 改 AIDL 的人除了升 `VERSION`，还要追加 history 行；检查任务会给出这一行的内容。
- 在 WP13 的内置 ADB 落地之前，`NeedsLaunch` 只会在 logcat 里提示运行 `scripts/start-helper.sh`，不会自动重新拉起助手。
