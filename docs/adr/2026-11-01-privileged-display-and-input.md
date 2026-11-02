# 后台虚拟屏与输入：助手自己保证的边界、反射的降级方式、能力探针

- 日期：2026-11-01
- 状态：提议（WP12 B 段，待评审）

## 背景

phase1-architecture §3.5 列出了 `IPrivilegedCore` 的能力面，调研报告 §2.4 给出了虚拟屏的通用配方，§5.3 第 7 条要求"所有隐藏 API 调用都要能力探针 + 软降级，并把失败原因回传给模型"。A 段（ADR `2026-11-01-privileged-handoff.md`）已经把助手进程、交接和身份做完，B 段要实现能力本身。

落地时要回答的问题：
1. 助手在多大程度上替 App 把关？安全判定本该只在 PolicyEngine（D8、§6），但助手是 uid 2000，一个写错的参数就能打扰用户、甚至把用户正在用的 App 挪走。
2. 隐藏接口缺失或被拒时怎么办。
3. 一个 App 从虚拟屏跳到主屏后，怎么在"能挪回来"和"不碰用户自己的任务"之间取得平衡。
4. 静音和授权用反射还是命令。
5. 这些设备相关的代码怎么测。

## 决定

1. **助手只保证"不越界"，不替 PolicyEngine 做内容判断**。助手不看 intent 的语义（哪个 App、什么链接、是不是付款），那是 App 侧 PolicyEngine 的事。助手只保证下面这些，无论 App 传什么：
   - 只往自己建的屏启动和注入；主屏、别人的屏一律拒绝（`StartResult.REFUSED` / false）。
   - 用户在主屏**可见**地用着的 App 不启动（`IN_USE_ON_MAIN`）；agent 的另一块屏正在用它也不启动（`IN_USE_BY_AGENT`）；任务列表读不出来时同样不启动。
   - 进命令行的包名先校验（`PackageNames`）：Java 包名格式，并且已安装；UID、`u0aNN`、`--` 开头的选项都会被命令读成别的东西，一律拒绝。`setAudioMuted` 返回 false，冷启动的目标不合格时整个启动返回 `REFUSED`。
   - 锁屏时什么都不启动（`LOCKED`，协议 2 新增）：用户不在场时，agent 不该打开任何 App。锁屏状态读不到也按锁屏处理。
   - 冷启动只在"这个 App 的任务全在这块屏上"时才 `am force-stop`，否则降级为普通启动并返回 `COLD_IGNORED`。
   - intent 里的 `FLAG_GRANT_*`（URI 授权）在启动前被清掉：助手是 shell 身份，不替任何人授予 URI 权限。
   - 只启动 App 自己的 uid 也能启动的 Activity（`LaunchTargets`）：intent 先解析成唯一的 Activity，它必须已导出（或属于本 App），它要求的权限 App 的 uid 必须已获得（`checkPermission(perm, appUid)`）；启动时把 component 设成检查过的那个。解析不出来是 `NOT_FOUND`；能处理它的 Activity 不止一个、未导出、缺权限都是 `REFUSED`。例如 PhoneAgent 没有 `CALL_PHONE`，`ACTION_CALL tel:` 就被拒绝。助手以 shell 身份运行，权限远多于 App，这一条只管"调用身份的权限上限"，付款、发送这类内容判定仍归 PolicyEngine。
   - 带嵌套 intent 的请求一律拒绝（`REFUSED`，`NestedIntents`）：extras 里任何一层的 Intent、Intent 数组或列表，`ACTION_CHOOSER`，selector，带 intent 的 ClipData 条目；extras 读不出来也拒绝。嵌套的 intent 会被接收方再启动一次，助手没检查过它启动什么、用谁的权限。ClipData 里的普通 URI 照旧允许。
   - 只支持主用户（`userId` 必须是助手自己的用户或 `USER_CURRENT`）。分身和工作资料留到以后。
   - `injectKey` 只放行导航和编辑键（`PressKeys`）。音量、电源、Home、最近任务、助手、相机、媒体键作用于整机而不是 agent 的屏，一律拒绝。
     `phone_key` 的 schema 仍然有 `home`（phase1 §5）：主屏模式下由无障碍的 `performGlobalAction` 实现；后台屏上由 ScreenPort（WP14）直接返回 Failed，原因"后台屏上没有桌面，请用 phone_launch 切换 App"，不调用 `injectKey`。
   - `injectStroke` 校验点数（≤1024）、时间从 0 开始且单调不减、总时长 ≤10 秒、每个点都在本屏尺寸内。
   - `grantSelf` 只接受 `WRITE_SECURE_SETTINGS`，而且写完回读 `checkPermission` 再报成功。

2. **`moveTask` 以"任务归属"为准**（`TaskOwnership`）。可以挪的只有两类：
   - agent 自己启动的任务（启动成功后记下 taskId，挪到主屏做接管后仍然算自己的，所以能挪回来）；
   - 在 `watchMainScreen` 的窗口内冒到主屏的任务——这正是"虚拟屏上的 App 从后台打开了另一个 App"的情形（研究 §2.4 第 6 步），那个新任务不是 agent 启动的，但必须能挪回去。
   目标屏也只能是主屏或 agent 自己的屏。用户自己打开的任务永远不会被拉进后台屏。

3. **隐藏接口统一经 `SystemBridge` 反射，失败带原因**。`CapabilityUnavailable` 的消息里有缺的是什么，调用方要么回退、要么把原因报给上层：
   - `moveRootTaskToDisplay` 失败回退 `am display move-stack`；
   - `startActivityAsUser`（12 参数）失败回退 `am start --display`（先按 component，再按 action）；
   - `getTasks` 读不出来时，启动和挪任务一律拒绝，而不是猜；
   - `setDisplayImePolicy` 设完回读（有 getter 时）；建屏时失败只记日志（不打字的场景照常可用），`probe()` 里是明确的一项；
   - 整个能力层构建失败时降级成 `NoCapabilities`：交接照常，每个能力返回失败值，`probe()` 和 `mainArrivals()` 抛带原因的 `NOT_IMPLEMENTED`。

4. **`probe()` 在助手自己的 `ImageReader` 上跑**：建一块 360x720 的小屏 → 设 IME 策略 → 读任务列表 → 注入一次落在空屏中间的点击 → 注册主屏监听 → 释放。返回 `steps`/`results` 两个平行数组，每项是 `ok` 或原因。不需要 App 传 Surface，帧也不出助手进程。

5. **静音和授权用平台命令，程序名有白名单**（`ShellCommands`：`am`/`pm`/`appops`/`input`，参数由助手拼）。`appops` 的 `PLAY_AUDIO` 反射需要 `AppOpsManager` 的隐藏重载和 `MANAGE_APP_OPS_MODES`，各版本差异大；命令形式 Mika 已在真机验证过。仍然没有"执行任意命令"的接口。
   静音前的模式记在 `/data/local/tmp/phoneagent_audio_mute_<user>`，助手启动时先把上一个助手没来得及恢复的包还原。原本没设置过的包恢复成 `allow` 而不是显式 `default`（显式 `default` 会让 App 一直没声音）。账本里没有记录的包，解除静音什么都不做（返回 true）：不是本助手静音的，别人设的模式不去动。

6. **测试分两层**：
   - 判断逻辑全部写成纯函数或纯状态类（`DisplaySpec`、`PressKeys`、`StrokeSpec`、`LaunchGate`、`TaskOwnership`、`MainScreenLog`、`AudioMuteLedger`、`GrantSpec`），JVM 单测覆盖每个分支，并用变异（去掉某项保护看测试是否变红）证明断言有效。
   - 反射和系统交互在授权的模拟器上验证，入口是 debug 构建里的自检广播（`display` 和 `launch` 两个场景），它把每一步打进 logcat 并用 `PASS`/`FAIL` 作为广播结果，可以脚本化。

## Alternatives considered

- **助手不做任何把关，全交给 PolicyEngine**（接近 Mika 的 `phone_system_command` 思路）。一个参数写错就能打扰用户；而且助手是 uid 2000，错误代价比 App 大。现在这几条都是"不越界"而不是"判断内容"，不会和 PolicyEngine 的职责重叠。
- **从 `phone_key` 的 schema 里去掉 `home`**（B 段最初的做法）。工具清单要保持稳定；主屏模式下 `home` 由无障碍实现，是能用的。所以 schema 保留 `home`，后台屏上由 ScreenPort 给出带原因的失败，助手照样拒绝注入 HOME。
- **助手也做 intent 白名单**（Mika 的 `ShellLinks` / `SystemIntents`）。那会出现两份规则，而设计明确要求判定点只有一个（D8）。助手只处理与身份有关的东西：清掉 URI 授权标志，拒绝嵌套 intent。
- **`moveTask` 不做归属检查**（Mika 的做法）。那样 App 一个错误的 taskId 就能把用户正在用的 App 拉进后台屏。代价是跳出回收需要 `watchMainScreen` 的窗口配合，见"后果"。
- **用 `UiAutomation` 或无障碍手势注入**，而不是 `injectInputEvent`。无障碍手势是 WP14 的路径（`dispatchGesture` + `setDisplayId`），B 段提供的是最底层的回退；两者并存是 §3.3 "点击逐级回退"的要求。
- **`createDisplay` 用 `VirtualDisplayConfig`**（API 34+ 的新接口，可以设更多属性）。它要求构造隐藏的 builder，收益只有几个暂时用不上的属性，不如 6 参数重载稳。
- **appops / grant 用反射**。签名在 API 30–36 之间变化多（`setMode` 有 int 和 String 两种 op 形式，`unsafeCheckOpRawNoThrow` 有四个重载），而命令形式是稳定的。真要省掉一次 fork，以后可以在反射成功时优先走反射。
- **把 `PLAY_AUDIO` 恢复成显式 `default`**。那会让 App 一直没声音（Mika 踩过）；没设置过的恢复成 `allow` 与系统默认一致，`appops get` 也回到"没有设置"的语义。
- **主屏监视用轮询**。轮询有延迟也耗电；`ITaskStackListener` 是系统主动通知，只当"变了"的信号用（各版本回调参数不同，不解析），注册不上时才退回轮询。
- **帧在助手侧编码后经 Binder 回传**。那要在 Binder 上传大块数据，还要在助手里做图像处理。App 自己的 `ImageReader` 直接拿到 Surface，帧不多走一跳。

## 后果

- 跳出回收依赖 App 先调 `watchMainScreen`：没开窗口时冒到主屏的任务不算 `spilled`，`moveTask` 会拒绝。§7.2 的主循环本来就在每个动作前调它，WP14 实现 ScreenPort 时要保证这一点。
- 窗口内冒到主屏的任务都可以被挪回，包括用户在这 3 秒内自己切换到的 App。窗口短、且只有 App 显式调用才会挪，但这是一个真实的权衡；要更严格的话，需要把"是不是 agent 引起的"判定（Mika 的 `MainScreenSpill`）也做进来，那属于 WP14 的 ScreenPort。
- 只支持主用户：分身、工作资料里的 App 现在启动不了（`StartResult.REFUSED`）。
- 有多个 App 能处理的链接（比如装了两个浏览器）会被拒绝：助手问不了用户选哪个。WP14 要先在 App 侧解析出用户的默认处理者，再把明确的 component 传进来。
- WP14 的 ScreenPort 要在后台屏上把 `phone_key home` 直接变成 Failed（原因见决定 1），不能交给 `injectKey`：助手的拒绝只是一个 false，模型看不到原因。
- 只在 API 36 模拟器上验证过。API 30–33 没有 `OWN_FOCUS`，虚拟屏会抢主屏焦点，这正是 D2 "后台屏要求 API 34+"的原因；API 34/35 的行为未实测。
- `releaseAll` 会解除静音、释放所有屏、清掉任务归属，屏上的任务随之销毁（`DESTROY_CONTENT_ON_REMOVAL`）。三类资源分开释放，每类里的每个资源（每块屏、每个被静音的包）也单独释放：某一个抛异常只记日志，其余照常释放，失败的那个留到下一次 `releaseAll` 再试；`releaseAll` 本身不抛（与 A 段 `HeldResource` 的语义一致，代码在 `cap/Release.kt`）。单独的 `releaseDisplay` 失败时，屏也留在助手手里，由下一次 `releaseAll` 重试。App 死后的宽限期语义不变（A 段 ADR），所以 App 重启后虚拟屏上的任务不会保留。要让任务跨 App 重启存活，需要新的协议方法，见 A 段 ADR 的"后果"。
- `probe()` 会建一块小屏并注入一次点击，大约几百毫秒；它只在 onboarding 和诊断时调用。
