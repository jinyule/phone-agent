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

   App 对未受信助手的同步调用（`identity`、`protocolVersion`、旧助手的 `releaseAll`）捕获任意 RuntimeException，因为 `Parcel.readException` 会原样还原远端抛的异常：前两个失败按 `BINDER_ELSEWHERE` 拒绝，口令不动；旧助手释放失败仍按 `Stale` 处理并进入 `NeedsLaunch`。

   四步都通过后，App 先 `linkToDeath`，成功了才提交：一次保存同时消耗口令、换发续接口令（`HandoffNonces.admit`），然后替换连接，最后才通知被替换的旧助手 `shutdown()`，紧接着回复。之前任何一步失败，口令状态都不变，在用助手的续接口令也不受影响。口令在检查期间失效（过期，或被新的 `arm` 替换），`admit` 返回 null，按 `BAD_NONCE` 拒绝；旧助手分支也先检查这一点（`consume`），失效就不再调它的 `releaseAll()`。

   App 进程可能死在提交的任何时刻，回复也就送不到。所以续接口令**一代重叠**：换发时，助手手里可能还握着的那个续接口令保留为"上一代"，直到下一次交接成功。自我续接时保留的是它刚出示的口令，替换时保留的是被替换助手的口令。重启后的 App 里，仍在运行的那个助手用它重新接上。续接口令绑定领取它的助手进程（pid），只有那个进程出示才算数。

   一代重叠够用，前提是**一次只接纳一个助手**：`HelperClient.offer` 从判定开始持有一把专用的锁，直到通知完被替换的旧助手才放开，所以同一时刻最多只有一个交接处于"已保存、还没通知旧助手"之间。否则两个都没收到回复的交接会接连换发，把旧助手手里唯一的续接口令挤出去（r3 复审实测）。本进程通知过退出的助手再来交接，直接按 `BAD_NONCE` 拒绝，不动口令。状态监听者和 `onLaunchNeeded` 可能在这把锁里运行，不能同步等待另一次交接：在别的线程上等会死锁，在自己线程上发起会抛 `IllegalStateException`。WP13 的内置启动要异步拉起助手。

4. **三种一次性口令**，都是 32 字节随机数的十六进制，比较时用常量时间：
   - **启动口令**：App 自己拉起助手时（WP13）签发，30 秒有效；
   - **预约口令**：`scripts/start-helper.sh` 在电脑上生成口令，先用 `content call --method arm` 把口令的 SHA-256 交给 App，再经 stdin 把明文交给助手，60 秒有效。App 只存哈希；
   - **续接口令**：每次交接成功，App 都回给助手下一个口令。App 把它持久化在 SharedPreferences（用 `commit`，整份槽位一次写入），App 重启后助手用它重新交接。它绑定领取它的助手进程，并与上一代重叠一次（见决定 3）。

   用掉哪个口令就只清哪个，计时用 `elapsedRealtime`。`elapsedRealtime` 每次开机从 0 开始，新开机的已运行时间可能又落回旧口令的有效窗口，所以启动口令和预约口令的时间戳旁边还存着开机标识（`Settings.Global.BOOT_COUNT`）：标识不一致或者读不到，一律当作过期。续接口令不计时，也不看开机标识。口令从不进命令行和日志（相关类型的 `toString` 都做了脱敏）。

   **开机标识的来源**：`BOOT_COUNT` 是公开 API（API 24 起），带 `@Readable`，普通 App 可以直接读。写入它的是 `PowerManagerService`：在 `onBootPhase(PHASE_THIRD_PARTY_APPS_CAN_START)` 时调用 `incrementBootCount()`，值不存在就从 0 开始再加 1（AOSP 11 到 16 都是这样）。这一步发生在任何第三方 App 能启动之前，所以 App 读不到上一次开机的旧值。只有厂商删掉了这段代码，App 才会读不到；目前没有这类设备的证据。

   **读不到时**（两种做法里选了"立刻失败"）：`arm` 拒绝，什么都不写，回复里带原因，`scripts/start-helper.sh` 把原因打印出来就退出，不去拉起助手；`issueLaunch` 抛 `IllegalStateException`，带同样的原因。已经在运行的助手照常用续接口令接回。代价是这样的设备上无法拉起新助手。另一种做法是用"每个进程随机生成的负数"代替开机标识，只让本进程内签发的口令有效；它能在这类设备上继续工作，但 `arm` 和交接之间 App 进程被杀就会失败，而且掩盖了配置问题。按"配置错误要大声失败"，没有选它。

5. **助手生命周期**是一个纯状态机（`HelperLifecycle`），由 `HelperRuntime` 在单个 HandlerThread 上驱动：
   - 首次交接按 0/1/3/10 秒重试，仍失败就退出（退出码 1）；
   - App 拒绝：释放并退出；
   - App 进程死亡（通过对 App 交来的 token 做 `linkToDeath` 得知）：进入 `Orphaned`，宽限期过后 `releaseAll`，然后进入 `Idle`。宽限期默认 10 秒，可用 `--orphan-grace-ms` 调整；
   - App 进程再起来（`ActivityManager.addOnUidImportanceListener`）：用续接口令重新交接；
   - **新会话开始前一定先释放上一会话的资源**。计时器带会话号，迟到的计时器会被忽略；
   - `releaseAll` 逐个资源尽力释放：某个资源释放失败只记日志，留到下一次 `releaseAll` 再试，不影响其余资源，也不会挡住后面的退出；
   - App 被卸载或换了证书：释放并退出。
     - 每次交接前、App 死亡时、宽限期到期时都复核包（固定的 uid 和证书）。
     - App 进程死亡和包删除是异步的，死亡回调那一刻包可能还在；所以宽限期到期时要再查一次。
     - `Orphaned` 和 `Idle` 期间没有任何事件能发现卸载：uid 监听只报告进程出现，App 的 token 已经死了。所以这两个状态下每隔 `--package-check-ms`（默认 30 秒，1000..600000）低频复核一次。从别的状态进入这两个状态时开一条新的复核链，链号让迟到的计时器失效。
     - 最坏退出延迟约为 max(宽限期, 复核间隔)，默认 30 秒。

6. **版本错配**。
   - AIDL 的事务号全部显式写出，`protocolVersion = 1`、`identity = 2`、`releaseAll = 3`、`oneway shutdown = 4` 永久冻结。这样任何版本的 App 都能读出任何版本助手的协议号，也能让它释放并退出。
   - App 收下新助手时，对被替换的旧助手调 `shutdown()`。

7. **未实现的方法**抛 `UnsupportedOperationException`，消息以 `NOT_IMPLEMENTED` 开头，用 `PrivilegedError.isNotImplemented` 判定。Binder 原生就能跨进程传这个异常。

8. **AIDL 变更检查**。
   - `privileged/protocol-history.txt` 是只追加的 `<版本号> <指纹>` 列表。指纹是所有 `.aidl` 文件按相对路径、按 AIDL 词元（`AidlTokens`：去掉注释和词元之间的空白，字符串和字符常量原样保留）算出的 SHA-256。`int f()=1;` 和 `int f ( ) = 1 ;` 指纹相同，常量 `"https://one"` 改成 `"https://two"` 指纹不同。
   - 第一版按"去注释、压缩空白"算指纹，会把 `f()=1` 和 `f() = 1` 当成不同协议，也会把字符串里的 `//` 当成注释。评审修复（2026-11-01）改成按词元算，history 第 1 行按新算法重算了一次：当时还没有发布过带旧指纹的 APK，AIDL 本身也没改。以后再改算法，就要按改协议处理（升版本、追加一行），不能重算旧行。
   - `:privileged:verifyPrivilegedProtocol` 要求列表最后一行等于当前 `VERSION` 和当前指纹，失败时直接给出该追加的那一行。
   - 1–4 号事务的完整声明写死在检查器里（`PrivilegedProtocolCheck.FROZEN`，按词元比较），少了或改了任何一条都失败，不管版本号是多少。去掉 `shutdown` 的 `oneway` 再正常升版本、追加一行，照样失败。
   - 接口描述符也冻结为 `dev.phoneagent.privileged.IPrivilegedCore`（`FROZEN_DESCRIPTOR`）。每个事务开头都用它做 `enforceInterface`，描述符一变，连 1–4 号事务也调不通。描述符由 package 和接口名决定，`@Descriptor` 之类的注解可以改掉它，所以检查器要求文件以 `package dev.phoneagent.privileged;` 开头、之后只有 import、`interface IPrivilegedCore` 前面什么都没有。加 `@Descriptor` 再正常升版本、追加一行，照样失败；`PrivilegedCoreTest` 另外固定了 `DESCRIPTOR` 的字面值。
   - 只追加：CI 传 `-PprivilegedProtocolBaseRef=<基线 sha>`（`pull_request` 事件用 `pull_request.base.sha`，`push` 事件用 `before`，checkout 用 `fetch-depth: 0`；`before` 被强推掉以后不在任何分支上，checkout 拿不到，由 `scripts/ci/protocol-base-ref.sh` 按 sha 补拉，补拉不到就让 job 失败，不退回空基线），检查器用 `git show` 读出基线的 history，要求它是当前 history 的前缀。基线里没有这个文件按空前缀处理；基线 sha 不在 clone 里就失败。这个属性只接受完整 sha（40 或 64 位小写十六进制）：分支名、tag、短 sha 会移动，而任务的 up-to-date 判断和缓存只看属性字符串，ref 移到别的提交时任务会被跳过；完整 sha 指向的内容不会变，可以放心当缓存键。本地不传这个属性，这一项不做。
   - 这个任务挂在 `check` 上，CI 的 `privileged-build` job 也单独跑它。
   - 反例：`PrivilegedProtocolCheckTest`（改 AIDL 不升版本、改冻结区、改写或删除 history 行）和 `VerifyPrivilegedProtocolTaskTest`（TestKit，在临时 git 仓库里跑真实任务：原地改写 history 行只在带基线时失败）。

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
- **续接口令不重叠，靠"助手确认收到"来换代**。App 先发新口令，等助手用新口令再来一次才让旧口令失效。它连新助手也能保住，但要在交接里加一轮往返，App 和助手两边都要改，还要和旧版本助手兼容。一代重叠只改 App 一侧的存储，就够让仍在运行的那个助手接回来，所以选了重叠。
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
- **轮询 `/proc` 来判断 App 是否活着或已重启**。轮询有延迟，还耗电。`linkToDeath` 和 uid importance 监听都是系统主动通知。只有"卸载"没有可用的通知，所以只在 App 死后低频复核包，见下一条。
- **用广播或回调监听包的卸载和替换**。
  - `Context.registerReceiver`：shell 自建的 ActivityThread 在 AMS 里没有对应的 ProcessRecord，API 34 及以上注册会被拒绝。
  - 隐藏的 `IPackageManager.registerPackageMonitorCallback`：API 35 起才有，覆盖不了 30–34。以后可以作为更及时的触发加上，低频复核仍然保留作为兜底。
- **用协程 / StateFlow 管理助手状态**。`privileged` 要尽量少依赖，所以用纯状态机加 HandlerThread，也方便在 JVM 上逐个事件测试。
- **AIDL 检查的其他做法**：
  - 每次都和 git 基线比较（本地也比）：本地没有可信的基线，fork 和离线环境也不稳。所以只在 CI 上比：PR 的 base sha 总是有的。冻结区不需要基线，写死在检查器里，本地也查；
  - 把哈希写在 Kotlin 常量里：不直观，而且容易连同 AIDL 一起被顺手改掉；
  - 用 stable AIDL 的 `aidl_interface`：只有 Soong 支持，Gradle 用不了。

## 后果

- **shell 身份就是信任边界**。任何 shell 进程都能 `arm` 自己的口令，再交一个假助手给 App。但它本来就拥有 shell 的全部权限，伪装助手并不能多得到什么。真正要防的是非 shell 调用方（靠 uid 检查和 Provider 权限挡住），以及重复的或过期的助手（靠口令和版本检查挡住）。
- A 段里宽限期只是推迟释放：App 回来时也会先释放。WP14（SurfaceManager）如果需要让虚拟屏跨 App 重启存活（例如用 `VirtualDisplay.setSurface` 换成新的 Surface），要在新的协议版本里加一个显式的接管方法，并改 `Orphaned` 状态的语义。届时重新评估本条。
- 只支持主用户（uid 必须小于 100000）。工作资料和多用户以后再说。
- API 30 上 `IContentProvider.call` 的参数和 31 以上不同，已按版本分开处理，但只在 API 36 模拟器上实测过。
- 改 AIDL 的人除了升 `VERSION`，还要追加 history 行；检查任务会给出这一行的内容。
- 续接口令一代重叠的代价：被替换的旧助手，它的续接口令要到下一次交接成功后才失效。它如果收到 `shutdown()` 后不退出，可以回来一次，把新助手顶掉（新助手收到拒绝后释放并退出）；最终仍只剩一个助手。剩下一个窗口没有消除：App 已经通知旧助手退出、新助手还没收到回复时死掉，旧助手按要求退出，新助手的一次性口令已经用掉，两个都接不上，要重新拉起（A 段：重跑 `scripts/start-helper.sh`）。消除它需要在协议里加一步"助手确认收到"，要动 App 和助手两边并处理跨版本兼容，暂不做。
- 在 WP13 的内置 ADB 落地之前，`NeedsLaunch` 只会在 logcat 里提示运行 `scripts/start-helper.sh`，不会自动重新拉起助手。
