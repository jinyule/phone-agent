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
   - 锁屏时什么都不启动（`LOCKED`，协议 2 新增）：用户不在场时，agent 不该打开任何 App。锁屏状态读不到也按锁屏处理，`probe()` 的 lock 一项会报出原因（协议 4 起，见决定 4）。
   - 冷启动只在"这个 App 的任务全在这块屏上"时才 `am force-stop`，否则降级为普通启动并返回 `COLD_IGNORED`。
   - intent 里的 `FLAG_GRANT_*`（URI 授权）在启动前被清掉：助手是 shell 身份，不替任何人授予 URI 权限。
   - 只启动 App 自己的 uid 也能启动的 Activity（`LaunchTargets`）：intent 先解析成唯一的 Activity，它必须已导出（或属于本 App），它要求的权限 App 的 uid 必须已获得（`checkPermission(perm, appUid)`）；启动时把 component 设成检查过的那个。解析不出来是 `NOT_FOUND`；能处理它的 Activity 不止一个、未导出、缺权限都是 `REFUSED`。例如 PhoneAgent 没有 `CALL_PHONE`，`ACTION_CALL tel:` 就被拒绝。助手以 shell 身份运行，权限远多于 App，这一条只管"调用身份的权限上限"，付款、发送这类内容判定仍归 PolicyEngine。
   - 可能夹带 intent 的请求一律拒绝（`REFUSED`，`NestedIntents`）。嵌套的 intent 会被接收方再启动一次，助手没检查过它启动什么、用谁的权限。
     - `ACTION_CHOOSER`、selector、带 intent 的 ClipData 条目拒绝；ClipData 里的普通 URI 照旧允许。
     - extras 用**允许清单**（协议 4 起），不再去找 intent：r1a 复审实测了 6 种藏 intent 的形状（SparseArray、Map 的值、作为 extra 的 ClipData、`RunningTaskInfo.baseIntent`、`ShortcutInfo` 的 intent、`ChooserTarget` 的 Bundle 里的 `EXTRA_INTENT`），逐个补分支封不完。
     - 允许：null、primitive 及其数组；String、CharSequence、Uri 及其数组和 ArrayList（ArrayList 也可以是装箱整数，`putIntegerArrayListExtra`）；值也在清单内的 Bundle；ContentValues 组成的 ArrayList，每个值只能是前两类叶子（联系人插入的 `ContactsContract.Intents.Insert.DATA`）。
     - 其余一律拒绝：任意其他 Parcelable 和 Parcelable 数组、SparseArray、Map、Serializable、ClipData、IBinder。要放行别的类型，必须连同"检查它里面装了什么"一起加进清单；其他纯数据 Parcelable 等有实际需要时再加。
     - Bundle、数组、列表、ContentValues 共用一个嵌套深度上限；extras 读不出来也拒绝。
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
   - `startActivityAsUser`（12 参数）不可用时回退 `am start --display`，把 intent 完整写成参数：`-n` 组件，加上 `-a` action（MAIN 也带上，协议 5 起：MAIN+LAUNCHER 去掉 MAIN 就成了另一个 intent）、`-d` data、`-t` type、每个 `-c` category、`-f` flags（十进制，`am` 用 `Integer.decode` 读）。带 extras 的 intent 不走这条回退，返回 `ERROR`（协议 4 起）：extras 还写不成等价的参数，丢掉它们就是启动了另一个 intent（完整映射留在暂缓项）；
   - `getTasks` 读不出来时，启动和挪任务一律拒绝，而不是猜；
   - `setDisplayImePolicy` 设完一定回读。设不上、回读不是 LOCAL，或者回读做不了（getter 不存在、调用失败、答复不是整数，都算"IME 未验证"，协议 4 起），就把刚建的屏释放掉，`createDisplay` 返回 `FAILED`：键盘弹到用户屏上是不能接受的，不存在"不打字就照常用"的屏。释放本身失败时，这块屏进"待清理"集合（协议 4 起）：`owns`/`sizeOf`/`ids` 和所有能力（启动、注入、移任务）都不认它，`releaseAll` 再释放它，释放成功才移出集合；失败原因写"没能释放、等待清理"，不写"已释放"。`probe()` 的 IME 一项照常报告原因；
   - API 34 以下 `createDisplay` 返回 `REFUSED`，不建屏（没有 `OWN_FOCUS`，屏会抢用户的焦点，D2）；`probe()` 在建屏之前就报告"background displays need API 34+"；
   - 整个能力层构建失败时降级成 `NoCapabilities`：交接照常，每个能力返回失败值，`probe()` 和 `mainArrivals()` 抛带原因的 `NOT_IMPLEMENTED`。

4. **`probe()` 在助手自己的 `ImageReader` 上跑**：建一块 360x720 的小屏 → 设 IME 策略 → 读任务列表 → 注入一次落在空屏中间的点击 → 注册主屏监听 → 读锁屏状态 → 释放。返回 `steps`/`results` 两个平行数组，每项是 `ok` 或原因，`ok` 是全部通过。不需要 App 传 Surface，帧也不出助手进程。
   - 锁屏一步（`lock`，协议 4 起）：读到"锁着"或"没锁"都是 `ok`，值本身不影响结果；`isKeyguardLocked` 不存在、调用失败、答复不是 Boolean 时写原因，`ok` 为 false。读不到时每次启动都会返回 `LOCKED`，这正是 onboarding 要点名的 ROM 问题。probe 和 `startActivity` 共用同一份读取（`Keyguard.read()`）和同一个带原因的结果（`LockState`），两边不会各说各的。

5. **静音和授权用平台命令，程序名有白名单**（`ShellCommands`：`am`/`pm`/`appops`/`input`，参数由助手拼）。`appops` 的 `PLAY_AUDIO` 反射需要 `AppOpsManager` 的隐藏重载和 `MANAGE_APP_OPS_MODES`，各版本差异大；命令形式 Mika 已在真机验证过。仍然没有"执行任意命令"的接口。
   静音前的模式记在 `/data/local/tmp/phoneagent_audio_mute_<user>`，助手启动时先把上一个助手没来得及恢复的包还原。原本没设置过的包恢复成 `allow` 而不是显式 `default`（显式 `default` 会让 App 一直没声音）。账本里没有记录的包，解除静音什么都不做（返回 true）：不是本助手静音的，别人设的模式不去动。原模式要先写进账本文件，写不进去就不静音（返回 false）：重启后的助手恢复不了的静音不能发生。启动时恢复账本，只认"包名格式 + `appops` 认识的模式"的行，其余丢弃并记日志，不会交给 `appops`。

6. **测试分两层**：
   - 判断逻辑全部写成纯函数或纯状态类（`DisplaySpec`、`PressKeys`、`StrokeSpec`、`LaunchGate`、`TaskOwnership`、`MainScreenLog`、`AudioMuteLedger`、`GrantSpec`），JVM 单测覆盖每个分支，并用变异（去掉某项保护看测试是否变红）证明断言有效。
   - 反射和系统交互在授权的模拟器上验证，入口是 debug 构建里的自检广播（`display` 和 `launch` 两个场景），它把每一步打进 logcat 并用 `PASS`/`FAIL` 作为广播结果，可以脚本化。

7. **屏固定竖向**：不加 `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`。坐标契约是"建屏时的尺寸"：`injectStroke` 的坐标、截图的尺寸始终是建屏的宽高，不会因为 App 要横屏而对调。想横屏的 App 由系统 letterbox。WP14 的 ScreenPort 按这个契约换算坐标。

## Alternatives considered

- **助手不做任何把关，全交给 PolicyEngine**（接近 Mika 的 `phone_system_command` 思路）。一个参数写错就能打扰用户；而且助手是 uid 2000，错误代价比 App 大。现在这几条都是"不越界"而不是"判断内容"，不会和 PolicyEngine 的职责重叠。
- **从 `phone_key` 的 schema 里去掉 `home`**（B 段最初的做法）。工具清单要保持稳定；主屏模式下 `home` 由无障碍实现，是能用的。所以 schema 保留 `home`，后台屏上由 ScreenPort 给出带原因的失败，助手照样拒绝注入 HOME。
- **助手也做 intent 白名单**（Mika 的 `ShellLinks` / `SystemIntents`）。那会出现两份规则，而设计明确要求判定点只有一个（D8）。助手只处理与身份有关的东西：清掉 URI 授权标志，拒绝嵌套 intent。
- **`moveTask` 不做归属检查**（Mika 的做法）。那样 App 一个错误的 taskId 就能把用户正在用的 App 拉进后台屏。代价是跳出回收需要 `watchMainScreen` 的窗口配合，见"后果"。
- **用 `UiAutomation` 或无障碍手势注入**，而不是 `injectInputEvent`。无障碍手势是 WP14 的路径（`dispatchGesture` + `setDisplayId`），B 段提供的是最底层的回退；两者并存是 §3.3 "点击逐级回退"的要求。
- **`createDisplay` 用 `VirtualDisplayConfig`**（API 34+ 的新接口，可以设更多属性）。它要求构造隐藏的 builder，收益只有几个暂时用不上的属性，不如 6 参数重载稳。
- **appops / grant 用反射**。签名在 API 30–36 之间变化多（`setMode` 有 int 和 String 两种 op 形式，`unsafeCheckOpRawNoThrow` 有四个重载），而命令形式是稳定的。真要省掉一次 fork，以后可以在反射成功时优先走反射。
- **把 `PLAY_AUDIO` 恢复成显式 `default`**。那会让 App 一直没声音（Mika 踩过）；没设置过的恢复成 `allow` 与系统默认一致，`appops get` 也回到"没有设置"的语义。
- **屏跟着内容旋转**（`ROTATES_WITH_CONTENT`，Mika 的做法）。横屏 App 会让屏的宽高对调，agent 正在用的坐标和截图尺寸随之失效，WP14 还得跟踪旋转。固定竖向更简单，代价是横屏 App 有黑边。
- **主屏监视用轮询**。轮询有延迟也耗电；`ITaskStackListener` 是系统主动通知，只当"变了"的信号用（各版本回调参数不同，不解析），注册不上时才退回轮询。
- **帧在助手侧编码后经 Binder 回传**。那要在 Binder 上传大块数据，还要在助手里做图像处理。App 自己的 `ImageReader` 直接拿到 Surface，帧不多走一跳。

## 后果

- 跳出回收依赖 App 先调 `watchMainScreen`：没开窗口时冒到主屏的任务不算 `spilled`，`moveTask` 会拒绝。§7.2 的主循环本来就在每个动作前调它，WP14 实现 ScreenPort 时要保证这一点。
- 窗口内冒到主屏的任务都可以被挪回，包括用户在这 3 秒内自己切换到的 App。窗口短、且只有 App 显式调用才会挪，但这是一个真实的权衡；要更严格的话，需要把"是不是 agent 引起的"判定（Mika 的 `MainScreenSpill`）也做进来，那属于 WP14 的 ScreenPort。
- 只支持主用户：分身、工作资料里的 App 现在启动不了（`StartResult.REFUSED`）。
- 有多个 App 能处理的链接（比如装了两个浏览器）会被拒绝：助手问不了用户选哪个。WP14 要先在 App 侧解析出用户的默认处理者，再把明确的 component 传进来。
- WP14 的 ScreenPort 要在后台屏上把 `phone_key home` 直接变成 Failed（原因见决定 1），不能交给 `injectKey`：助手的拒绝只是一个 false，模型看不到原因。
- 只在 API 36 模拟器上验证过。API 30–33 没有 `OWN_FOCUS`，虚拟屏会抢主屏焦点，这正是 D2 "后台屏要求 API 34+"的原因，所以协议 3 起这些版本上直接拒绝建屏；API 34/35 的行为未实测。
- `releaseAll` 会解除静音、释放所有屏、清掉任务归属，屏上的任务随之销毁（`DESTROY_CONTENT_ON_REMOVAL`）。三类资源分开释放，每类里的每个资源（每块屏、每个被静音的包）也单独释放：某一个抛异常只记日志，其余照常释放，失败的那个留到下一次 `releaseAll` 再试；`releaseAll` 本身不抛（与 A 段 `HeldResource` 的语义一致，代码在 `cap/Release.kt`）。单独的 `releaseDisplay` 失败时，屏同样进"待清理"集合：App 已经要求释放，它不再可用，只由下一次 `releaseAll` 重试。App 死后的宽限期语义不变（A 段 ADR），所以 App 重启后虚拟屏上的任务不会保留。要让任务跨 App 重启存活，需要新的协议方法，见 A 段 ADR 的"后果"。
- `probe()` 会建一块小屏并注入一次点击，大约几百毫秒；它只在 onboarding 和诊断时调用。
