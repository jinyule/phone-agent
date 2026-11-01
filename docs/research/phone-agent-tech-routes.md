# Phone-Agent 技术路线调研：DSH-Android / dsh-Go / Mika

> 调研日期：2026-11-01｜方式：只读阅读源码与文档（未构建、未运行）｜文档与代码不一致处一律以代码为准
>
> | 项目 | 版本 / 提交 | 仓库位置 | 许可 |
> |---|---|---|---|
> | DSH-Android | v0.3.4 / `c6f3b55` | [jackwener/DSH-Android](https://github.com/jackwener/DSH-Android) | Apache-2.0 |
> | dsh-Go | 1.0.4 / `bf01594` | [ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) | AGPL-3.0（sdk/examples 为 MIT；手机操控派生自 Phase，"经作者许可使用"） |
> | Mika | v0.1.25 / `9cef375` | [wangzan101/Mika](https://github.com/wangzan101/Mika) | Apache-2.0（内含 GPL 的 PRoot，以子进程方式运行） |

---

## 0. 一句话结论

三个项目代表了三条"大脑放哪"的路线：

1. **嵌入通用 Agent 引擎**（DSH-Android：在 App 进程里通过 JNI 跑 Node 24 + 原版 dsh）
2. **端侧 Linux 运行时 + 引擎 + MCP 设备能力**（dsh-Go：在 PRoot 里跑 Ubuntu + dsh web）
3. **原生自研 Agent**（Mika：纯 Kotlin 主循环 + 后台虚拟屏）

在"手和眼"这一层（感知、执行、特权、虚拟屏），三家几乎**殊途同归**，形成了一套可以直接当行业做法的配方。

如果目标是做一个**以 GUI 操作为核心的手机 Agent 产品**，推荐**路线 A（Mika 式原生自研）**：
- 设备能力层做成**与传输无关的工具协议**，兼容 MCP，同时服务端内 loop 和 PC/云端 agent；
- 操控以**无障碍为基线**，再加 **shell 身份特权助手（先 Shizuku，后补无线 ADB 自启）驱动后台虚拟屏**。

---

## 1. 三个项目的架构与实现原理

### 1.1 DSH-Android：嵌入 Node 引擎，原版 dsh + 插件能力桥

```
┌──────────────── :main 进程（Kotlin · Compose · Hilt · Room）───────────────┐
│ UI · SessionStore（单一事件日志 + 投影）· EngineManager                       │
│ CapabilityHub（68 个设备工具）· TriggerHub（10 种触发器）                     │
│ 无障碍服务 · 通知监听 · 无键盘输入法 · 悬浮球 · 系统助手                       │
└──────┬──────────────────────────────▲───────────────────────────▲──────────┘
       │ AIDL 只用一次：start(Bundle) 返回 3 个 fd                    │
       │                     stdio：dsh SDK JSON-RPC    socketpair：能力桥 JSON-RPC
┌──────▼──────────────── :engine 前台服务（specialUse）───────────────────────┐
│ JNI → node::Start（libnode.so，Node 24 for bionic，16MB 栈线程）               │
│ dsh --profile android --patch app-layer.patch.yml                            │
│   + plugin-device（能力桥）/ plugin-guard（安全/续跑/预算）                      │
│   + plugin-mode（模式/延迟工具）/ plugin-extensions（MCP/技能）/ account          │
└──────────────────────────────────────────────────────────────── HTTPS → LLM ─┘
┌─ :shell（Shizuku UserService，uid 2000）：sh -c、建虚拟屏、跨屏挪任务、appops 静音 ─┐
```

**关键原理**

- **引擎嵌入**
  - Node 交叉编译成 Android 共享库，在独立进程里通过 JNI 启动。
  - Node 每个进程只能启动一次，所以"停止引擎"就是杀掉进程；每次重启算一个新"代次"，旧代次的事件丢弃。
  - 子进程用的 node 编译成 `libdshnode.so` 放在 nativeLibraryDir，因为 Android 只允许从这里 exec。
  - zygote 派生的进程带 AT_SECURE，Node 会忽略 `NODE_OPTIONS` 等环境变量，只能改走 argv 或 preload。
- **不 fork dsh**
  - 只对 dsh 打了 19 个 JS 补丁，分三类：平台兼容（flock、/system/bin/sh、硬链接回退、sharp 用 wasm 版等）、SDK 扩展点（审批/提问回移、插件注册 SDK 方法）、第三方模型适配。
  - Node 本身另有 17 个原生补丁（参考了 Termux 和 nodejs-mobile 的做法）。
- **Agent loop 完全由 dsh 负责**，App 通过钩子参与：
  - `agent/turn-stopping`：todo 没做完时自动续跑，每轮最多 8 次；
  - `agent/pre-step`：token 与成本预算；
  - App 侧在崩溃或断网后发续跑 prompt，并明确声明"已有结果的调用已经生效，不要重做"。
- **工具与上下文**
  - 68 个工具默认只在目录里列名字，模型调 `load_tools` 才加载定义，目录只追加。首条请求从约 19k token 降到约 5k。
  - 工具描述保持静态，权限、Shizuku 这类易变状态放进调用结果，以保住 prompt 缓存。
- **感知**
  - 无障碍文本树，每行形如 `[e12] Button "发送" #res_id {click} @x,y`，只给中心坐标、不给 bounds。
  - 相邻两次读屏中，特征相同且中心漂移 ≤48px 的元素沿用原编号；动作后只发 `+/-/~` 差分。
  - 可读元素少于 4 个时，用 ML Kit 中文 OCR 兜底，输出 `[t3]` 编号。
  - 截图可选，没有 SoM 标注，也没有 VLM 定位。
- **执行**
  - 点击：`performAction(CLICK)` → `dispatchGesture`（带 `setDisplayId`）→ Shizuku `input tap`。
  - 文本输入五级回退：SET_TEXT → 无障碍输入连接 → 自带无键盘 IME `commitText` → Shizuku `input text` 或临时切换 IME → 剪贴板粘贴。每一级都回读校验，字段一旦变化就停。
  - 动作后**固定等 400ms** 再读屏，没有事件驱动的等待。
- **虚拟屏（"不打扰模式"）**
  - Shizuku 身份下调用 `createVirtualDisplay`，flags 按系统版本递增（见 §2.4），再 `setDisplayImePolicy(LOCAL)`。
  - 启动 App：`am start --display`。
  - 取帧：ImageReader，经 Binder 传回。
  - 跳出到主屏的任务用 `moveRootTaskToDisplay` 挪回；接管时把任务挪到主屏，交还时再挪回。
  - 后台屏上的 App 用 appops 静音。
  - 代码门槛是 Android 11+，14+ 才有独立焦点。
- **安全**
  - hardline 近似 shell 分词，拦截危险命令；审批预设分四级风险。
  - `hand_to_user`：login / captcha / payment / secure_page。
  - 防风控与防打转：对外动作同 App 内随机间隔 8–20s；LoopGuard 四个信号逐级提醒、到顶拒绝执行。

**亮点**
- 进程隔离 + 代次 + 续跑，形成完整的崩溃恢复闭环。
- 超时阶梯：App 在 T−5s 作答，插件在 T+10s 发 cancel，dsh 在 T+15s 超时；被放弃的调用绝不会晚些执行。
- 延迟工具。
- 同一套无障碍代码按 displayId 参数化，同时服务主屏和虚拟屏。

**坑**
- **默认预设是"完全权限"**：只有 `sms_send` 和 `notification_reply` 会弹确认，用 `screen_tap` 点微信"发送"直接放行。风险按工具名划分，而不是按动作语义。
- 停止一个会话会杀掉整个引擎，所有会话一起停。
- 主屏和全局输入法切换没有互斥。
- 冷启动约 5s，包体约 110MB。
- **README 宣称的"自连 ADB""自带 Linux 跑 Python""SSH/Remote"在该提交的代码里都找不到**：`ShellAccess.kt` 的注释写明 "DSH never runs its own ADB client"。
- 没有 CI，也没有 GUI 任务成功率评测。

### 1.2 dsh-Go：Flutter 客户端 + PRoot Linux 运行时 + MCP 手机能力

```
Flutter UI（单 FlutterEngine，Riverpod）
  │ 23 个 MethodChannel + 7 个 EventChannel
  ▼
Kotlin 平台层：RuntimeController / PhoneCore / SafetyGate / PrivilegedDisplayCore
  │
  ├─ DshCarrier 抽象（一元 RPC：POST /api/*；流式：WebSocket /api/remote.mux 多路复用）
  │   ├─ 本机：PRoot(Ubuntu 24.04 + Node 24) → dsh web @127.0.0.1:3080-3100 + dsh-go-bridge
  │   ├─ SSH 端口转发 → 电脑上的 dsh web
  │   └─ DSH Remote（Noise IK 端到端加密 + Relay/WebRTC）
  │
  └─ 手机能力（不经 Flutter）：
      dsh-mcp-client → relay.cjs → 抽象命名空间 Unix socket
        → Kotlin McpServer（SO_PEERCRED 校验同 uid + 每次启动随机 token，常量时间比对）
特权：Shizuku UserService ｜ libadb-android 无线配对 → app_process PrivilegedDisplayMain（stdin/stdout 分帧 Bundle）
```

**关键原理**

- **运行时**
  - 构建期生成可复现的 rootfs tar.xz：所有下载物用 SHA 和 PIN 双重校验，deb 包经 gpgv 验签，npm 依赖锁定。proot 改名成 `libproot.so` 放进 jniLibs。
  - 为了能 exec App 私有目录里的二进制，**`targetSdk=28`**，代价是上不了 Google Play。
- **直接启动（默认关）**
  - DSH 主进程不进 proot，用 rootfs 里的 glibc `ld-linux` 显式加载 Node，并预加载 `preload.mjs`。
  - preload 负责路径映射、`child_process` 改写（bash/git/python 等仍进 proot）、原生模块哈希校验、DNS 换成 c-ares。
  - 部分机型的 seccomp 会杀掉 loader，所以默认关闭；失败后自动回退 proot。
  - 冷启动的主因是 dsh 启动时约 2.8 万次路径类 syscall，每次都被 proot 的 ptrace 翻译：proot 下 1.93s，不经 proot 1.07s。
- **Agent loop 完全复用 dsh**，App 每次启动时注入三样东西：
  - ① `phone.patch.yml`：把 MCP client 挂到组件树根部，模型因此看到 `mcp__phone__*`；
  - ② 托管的 AGENTS.md 里的"手机操控"一节；
  - ③ 三个技能。
  - 手机开关关掉时不注入 MCP，模型完全看不到手机工具，所以切换开关需要重启引擎。
- **13 个固定工具**：`apps / open / intent / notify / clipboard / screen / collect / tap / type / scroll / key / tap_xy / handoff`。
  - schema 刻意不随权限状态变化，以保护 KV 缓存；暂时做不了的调用返回一段说明文字，而不是去掉工具。
  - `intent` 只接受白名单内的种类：闹钟、计时器、日历、拨号、短信草稿、分享、导航、设置。
- **感知**
  - `ScreenFormat`：一行一个元素，可点击的行吸收子节点的文字（TalkBack 式朗读单元）。
  - 按纵向位置分顶 / 中 / 底三区；整页约 3000 字预算，超出折叠，但始终保留底部导航。
  - 会被确认的元素标 `⚠`，相对上一屏新出现的元素标 `+`。
  - **元素编号只在当前屏幕号（如 s7）内有效**；每次动作前重抓树，按指纹（窗口 | 边界 | 角色 | 文本）重新找到元素，对不上就拒绝执行。
  - 截图里画编号框（**SoM**），密码框区域涂灰；银行卡号和身份证号在离开手机前打码。
  - 没有 OCR。
- **执行**
  - 点击：ACTION_CLICK → 向上 4 层找可点击祖先 → 手势。
  - 输入：SET_TEXT 后 1.5s 内回读核对；提交只用 `ACTION_IME_ENTER`，不去猜发送按钮。
  - **动作后等界面稳定**：`EventClock` 数无障碍事件，等首个事件后再等 300ms 静默，总共最多 3s。比固定 sleep 更好。
- **虚拟屏**
  - 需要 Android 14+。PendingIntent + `launchDisplayId` 启动 App，反射调用 `injectInputEvent` 注入输入。
  - 自己拼了一个只暴露节点能力的 UiAutomation 连接读虚拟屏的树，不会挤掉用户的其他无障碍服务。
  - 开启时先建一个小虚拟屏、注入一次无害点击，作为**能力探针**识别不支持的 ROM。
- **安全：`SafetyGate`，规则写在原生代码里**
  - 硬拒绝：DSH 自身界面；约 30 个银行、券商、支付包名；系统设置、权限、安装器、SystemUI；密码、验证码输入框。
  - 询问：`tap_xy`；带提交的 `type`；目标命中敏感词；首次进入某个 App；"每次写都确认"名单（默认微信）。
  - 放行：其余动作。
  - 防自我确认：本服务注入手势期间，悬浮条按钮忽略触摸（`InjectGuard`）。
  - 解除硬规则需要长按 1.5s，用原始指针事件实现，无障碍动作触发不了。

**坑**
- **在"始终免确认"档位下，敏感词确认被整体跳过**（`SafetyGate.kt` 里 `if (waived(p)) return Verdict.Allow` 在敏感词检查之前），非名单电商 App 里的"支付 / 下单"会直接被点下去。这与 README 的"付款永远交还给你"不符。
- 工具调用不带会话身份，"停止"会取消本机所有正在跑的会话。
- 手机能力只给本机引擎，远程主机用不了。
- 无 CI，无 agent 评测。
- 手机操控代码的授权方式是"经作者许可使用"，不是开源许可，直接拷代码有风险。

### 1.3 Mika：原生 Kotlin Agent + 自研 ADB 拉起的 shell 助手 + 后台虚拟屏

```
:app 进程
  TaskService（前台服务）
   └ AgentLoop（:core 纯 Kotlin，端口-适配器）
       ├ 旁路角色：IntentGate（讨论/查/做）· 经验路线回放 · 看图描述/定位
       │          · 结局判定 · 记忆提取 · 知识起草 · spawn_agent（只读子 agent）
       ├ ActionExecutor：守卫 → 锁屏/被挪走关卡 → 付款判定（唯一入口）→ 执行 → 守卫计数
       └ Ports：ScreenPort / WebPort / SandboxPort / ChatModel / RunStore
  ├ AndroidScreen：无障碍（读树、带 setDisplayId 的手势）+ ML Kit OCR（中/拉丁）+ ImageReader 取帧
  ├ Web：系统 WebView + CDP（LocalSocket 直连 webview_devtools_remote_<pid>）
  └ Sandbox：libmikarun.so（seccomp）→ libproot.so → Alpine 3.22
helper 进程（uid 2000，app_process HelperMain）
  ← 自研 ADB：SPAKE2 配对（edwards25519）+ TLS 1.3 + exec: 服务拉起
  → 经 ContentProvider 把 Binder 交给 App（先核对签名指纹）；Mika 死了助手不退，等它回来再交接
  IMikaShell（AIDL）：建虚拟屏 / startActivityAsUser(launchDisplayId) / injectInputEvent
                     / moveRootTaskToDisplay / pm grant WRITE_SECURE_SETTINGS / appops
对外：MCP（127.0.0.1:8765，需 adb forward + 令牌）+ mika CLI
```

**关键原理**

- **core / app 分离**
  - 设备、模型、存储都抽象成接口，`PackageDependencyTest` 守住依赖方向：内核不认识具体能力，能力之间不互相引用。
  - 主循环在 JVM 上用 `FakeChatModel` + `ScriptedScreen` 跑场景测试。
  - 提示词全部资源化，`prompt-surface` 和 `loop-snapshots` 两类金样锁住给模型的全文和请求序列。
- **主循环**
  - 单主 agent，每轮只执行第一个工具调用；不带工具调用的回复就是最终回答。
  - 每个工具都带 `reason` 参数，用户的语言，在界面上就是这一步的说明。
  - 运行时状态（当前在哪、第几步、本地时间、用户语言）接到最后一条消息末尾，发过的消息一律不改。
  - 插话只撤掉正在等待的模型请求，不打断正在执行的工具。
- **上下文管理**
  - 请求按缓存分区：系统提示词与工具 / 开局上下文 / 只追加的历史 / 本步尾部。
  - 估算到上下文的 60% 才做一次**确定性压缩**（不调模型）：先把旧界面结果换成占位，还超就把旧往返折成摘要，压到 50% 以下。
  - 工具分组折叠，第一次用到才展开整组，请求前缀因此最多失效一次。
- **三种模型分工**
  - 主模型办事；轻量模型做意图门、路线匹配等小事，禁止做放弃、确认、接管这类决定；看图模型负责 describe 和 find_visually。
  - 协议有四种：Chat Completions、Messages、generateContent、Responses，另支持 ChatGPT 登录。
- **感知**
  - 无障碍元素表 `@eN`，坐标**统一归一到 0–999**；编号跨屏稳定；写操作的结果只发差异。
  - OCR 只补树里没有的字，并挂到包含它的控件上（`screenMap`）。
  - `TreeQuality` 判断树不够用时才附截图（自适应看图）。
- **看图定位的交叉核验**
  1. 先在元素树里找唯一的精确匹配；
  2. 找不到再让视觉模型输出 `bbox_2d`；
  3. 核对框里的名字与要找的是否一致；
  4. 在截图上画红框，让模型复看回答是 / 否；
  5. 没验证过准度的模型，要求两次一致才算。
- **特殊页面识别**
  - 防截屏页：两次全黑帧。
  - 系统选择器：用标准 Intent 解析出所在的包，不写死包名。
  - 分身 / 工作资料：用 `startAppInUser` 打开。
  - 跳出到主屏的 App 自动挪回；用户正在用的 App 不抢；选择器留给用户选。
- **自研 ADB 无线配对**
  - 配对码由无障碍从系统设置窗口里直接读，读不到就弹带输入框的通知。
  - 助手先给 App `pm grant WRITE_SECURE_SETTINGS`，之后 App 自己就能写 `adb_wifi_enabled`（自动重开无线调试）和 `enabled_accessibility_services`（自动恢复无障碍）。
- **浏览器**
  - 系统 WebView + CDP 实现"桌面级"：宽视口 1280、关触摸仿真、`webdriver=false`。
  - 真实输入：鼠标走贝塞尔轨迹，字母和数字逐键敲。
  - 能读网络响应正文（`Network.getResponseBody`）。
  - 翻页节奏：同站第 2 页起停留 4–7s 并递增，每站每任务最多 20 页。
- **安全**
  - `IrreversibleDetector` 词表 + 屏上金额（¥ / 元）判定"花钱"，**全代码只有一处做判定**。
  - 同意是一次性的，并与这一步的目标、金额绑定；无人值守时付款一律拒绝。
  - 外部内容用 `<untrusted>` 包裹；记忆用 Keystore 加 AES-GCM 加密。
  - 沙箱用 seccomp 实现默认断网。
- **知识与学习**
  - 41 份 APP.md / SITE.md，按前台包名或域名确定性注入。
  - 技能按需读取；记忆用 BM25 加汉字二元组检索。
  - 经验路线回放：不调模型、按槽位重做，碰到不可逆操作就交还模型。
- **真机评测**
  - 题目分 read / write / irreversible / impossible / barrier / memory / skills / web 等类别，又分调优池和保留池。
  - 走真实的 TaskService 路径，可用运行记录离线重打分。

**坑**
- **`phone_system_command` 能以 shell 身份（uid 2000）执行任意命令**，只拦"用命令代替点击 / 启动 / 分享"，不经过审批，是提示词注入的高危面。
- 大量反射调用隐藏接口：12 参数版 `startActivityAsUser`、`moveRootTaskToDisplay`、`injectInputEvent`。
- 读屏同时给元素表和完整窗口树，且不截断，token 偏重。
- 产品路径步数无上限，导致按上限比例算的叫停门槛实际到不了。
- 中断后不续跑。
- 主要只在一台 Android 14 手机上验证过。

---

## 2. 横向对比

| 维度 | DSH-Android | dsh-Go | Mika |
|---|---|---|---|
| UI 技术 | Kotlin Compose | Flutter + Kotlin 平台层 | Kotlin Compose |
| Agent 大脑 | 原版 dsh（嵌 Node） | 原版 dsh（PRoot 内）或远程主机 | **自研** Kotlin AgentLoop |
| UI↔引擎 | stdio JSON-RPC | HTTP + WS 多路复用 | 进程内 |
| 引擎↔设备 | socketpair NDJSON JSON-RPC（插件） | **MCP** over 抽象 socket（uid + token） | 进程内 Port 接口；特权走 AIDL |
| 手机工具 | 68 个（延迟加载） | 13 个固定 | 约 60 个，分组展开 |
| 感知主通道 | 无障碍文本树 + 差分 | 无障碍分区格式 + 屏幕号指纹 | 无障碍元素表 + 树 + OCR 补字 |
| 视觉 | OCR 兜底，截图可选 | SoM 截图，无 OCR | OCR + 自适应截图 + **VLM 定位交叉核验** |
| 坐标 | 屏幕像素中心点 | 像素，tap_xy 映射 | **0–999 归一** |
| 等待稳定 | 固定 400ms | **事件驱动 EventClock** | 等画面稳定 + 效果核实 |
| 虚拟屏 | 可选，11+（14+ 独立焦点） | 可选，14+ | **默认主路径**，12+（14+ 推荐） |
| 特权获取 | 仅 Shizuku | Shizuku / libadb-android | **自研 ADB 无线配对**（SPAKE2 + TLS） |
| 安全判定 | 按工具名分级，默认完全权限 | 原生 SafetyGate 三层规则 | 唯一判定点 + 词表 / 金额 + 一次性同意 |
| 主要安全坑 | 默认 GUI 发送不确认 | 最宽档跳过付款确认 | 任意 shell 工具无审批 |
| 上下文 | dsh compaction + 延迟工具 | dsh + 固定 schema | 缓存分区 + 确定性压缩 |
| 浏览器 | App 内浏览器工具 | 无 | WebView + CDP（桌面仿真、读网络） |
| Linux 环境 | 无（只有 Node） | Ubuntu PRoot（约 1GB） | Alpine PRoot + seccomp 断网 |
| 知识 / 记忆 | 18 APP.md + 经验库 | 3 技能 | 41 份知识 + 技能 + 加密记忆 + 路线回放 |
| 评测 | 单测 + 主机 e2e | 单测 | **金样 + 假模型 + 真机 eval（调优池/保留池）** |
| 对外接口 | — | 远程主机 | MCP server + CLI |
| 冷启动 / 包体 | 约 5s / 约 110MB | 2–3s+ / rootfs 约 1GB | 无引擎冷启动 |
| 分发 | APK 侧载 | targetSdk 28，无法上 Play | APK 侧载 |

---

## 3. 三家的共识（可直接当作"行业配方"）

1. **感知以无障碍树文本为主，截图、OCR、VLM 为辅。** 文本省 token、可精确定位；视觉只在自绘界面（游戏、Flutter、Canvas）或树稀疏时兜底。
2. **动作即观测。** 每个写动作的结果里自带新屏幕的差分和效果判定（无变化 / 跳到别的 App / 被限流），模型少一轮"再看一眼"。
3. **元素引用要防过期。** 稳定编号 + 屏幕版本号或指纹，对不上就拒绝执行，不点到变了的元素。
4. **输入要回读校验。** SET_TEXT 优先，失败再逐级回退，字段一旦变化就停，防止重复输入。
5. **把 `hand_to_user` / `handoff` 做成一等工具。** 登录、验证码、支付、防截屏页统一交给人，用户处理完交还，再重新读屏。
6. **防风控节奏。** 对外动作（发送、打招呼、投递）在同一 App 内随机间隔 **8–20s**（DSH-Android 和 Mika 的数值完全一样），翻页限速，认出限流提示就停。
7. **LoopGuard 阶梯。** 重复同一动作、连续无效果、反复读屏、连续失败这几种信号，分级提醒，到顶硬拒绝，用户再发言后重置。
8. **工具 schema 保持静态，状态放进结果。** 保住 prompt / KV 缓存。
9. **虚拟屏的实现配方高度一致**（见下）。

### 2.4 虚拟屏（后台操作）通用配方

```
前提：一个 shell 身份（uid 2000）的特权进程
      来源：Shizuku UserService，或经无线调试 ADB 执行 app_process 拉起
  1. 建屏：DisplayManager.createVirtualDisplay(name, w, h, dpi, ImageReader.surface, flags)
       基础  PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL
       13+   + TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED
       14+   + OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED      ← 不抢用户主屏焦点、键盘
     再反射调用 IWindowManager.setDisplayImePolicy(displayId, LOCAL)  ← 键盘只弹在虚拟屏
  2. 启动 App：ActivityOptions.setLaunchDisplayId(id)
       + 反射 startActivityAsUser / PendingIntent，失败退回 `am start --display <id>`
  3. 读界面：无障碍 windowsOnAllDisplays[displayId]，或自建 UiAutomation 连接
  4. 注入输入：无障碍 dispatchGesture（GestureDescription.setDisplayId）
       或反射 InputEvent.setDisplayId + InputManager.injectInputEvent；按键用 `input -d <id> keyevent`
  5. 取帧：ImageReader.acquireLatestImage（预览约 2fps，OCR / 看图按需）
  6. 跳出回收：动作后几秒内主屏冒出非用户打开的任务（如跳到支付页）→ moveRootTaskToDisplay 挪回
  7. 接管：把任务挪到主屏交给用户 → 用户点"处理好了" → 挪回虚拟屏，重新读屏
  8. 细节：appops PLAY_AUDIO ignore 静音；用户正在用的 App 不抢；
          两帧全黑判为防截屏页 → 转接管；开启时用能力探针识别不支持的 ROM
```

---

## 4. 技术路线

### 4.1 主轴：Agent 大脑放在哪

| 路线 | 代表 | 做法 | 优点 | 代价 / 风险 | 适合 |
|---|---|---|---|---|---|
| **A. 原生自研 Agent** | Mika | Kotlin（或 KMP）主循环，模型走云 API；设备能力通过接口在进程内调用 | 最轻、启动快；loop 可为 GUI 深度定制（意图门、看图核验、付款单点判定、经验回放）；JVM 可测、可金样 | 要自建多 provider、压缩、技能、MCP 等基础设施；通用能力（写代码、处理文件）要另加沙箱 | **以 GUI 操作为核心的消费级产品（推荐）** |
| **B. 嵌入现成通用引擎** | DSH-Android | 把 Node/Python 引擎编进 App 进程；设备能力以插件 / 能力桥注入 | 直接继承引擎的技能、MCP、子 agent、压缩，并跟随上游升级 | 交叉编译 + 原生补丁 + JS 补丁维护成本高；冷启动约 5s、包体约 110MB；loop 不可控，GUI 特化只能靠钩子和提示词；深度耦合引擎内部 | 已有成熟引擎，想"把它带上手机" |
| **C. 端侧 Linux 运行时 + 引擎 + MCP** | dsh-Go | PRoot 里跑完整发行版和引擎；设备能力以 MCP server 暴露；本机和远程统一 carrier 抽象 | 引擎几乎不改；Linux 工具链完整；MCP 让引擎可替换；本机 / 远程一套客户端 | targetSdk 28 无法上架；约 1GB；ptrace 开销；seccomp 机型兼容；GUI 特化弱 | 开发者向的"口袋 coding agent"附带手机操控 |
| **D. 外部大脑 + 手机被控端** | Mika 的 MCP/CLI、dsh-Go 的远程体现了一半 | PC / 云端 agent 经 ADB 或 MCP 驱动手机上的能力层 | 开发和调试最快；算力不受限；天然适合评测、数据采集、RPA | 依赖电脑或云手机，不是"手机上自己跑" | 研发期调试、评测回放、企业 RPA |

> 业界还有一批以 PC 端 ADB 驱动、纯视觉为主的开源项目（如 AppAgent、Mobile-Agent、droidrun 等），属于路线 D + 纯视觉感知。本次未调研，仅作参考。

### 4.2 副轴一：操控与特权通道

| 通道 | 能力 | 门槛 | 说明 |
|---|---|---|---|
| 纯无障碍 | 读树、点击、输入、全局键；只能在用户主屏上操作 | 用户手动开启无障碍；侧载 App 常被"受限制的设置"拦下 | 基线方案；锁屏后不能操作；会占用用户屏幕 |
| + Shizuku | shell 身份 → 虚拟屏、`am --display`、注入、挪任务、静音 | 要装第三方 App，重启后需要重新启动 | DSH-Android 和 dsh-Go 的做法；集成成本最低 |
| + 无线 ADB 自启 | 同上，且不依赖第三方 App；可 `pm grant WRITE_SECURE_SETTINGS`，进而自动重开无线调试、自动恢复无障碍 | 需要配对一次（Android 11+ 无线调试） | Mika 纯 Kotlin 自研 SPAKE2 + TLS；dsh-Go 用 libadb-android（工作量小得多） |
| Root / 系统签名 / OEM 预装 | 全部能力，含锁屏操作等 | 只能走厂商合作或预装 | 消费级侧载走不了，但是 OEM 方向的终局 |

**建议**：无障碍做基线，保证无特权也能用；特权核心做成**与传输无关**的一份代码，同一份 PrivilegedCore 同时支持 Shizuku Binder 和 app_process 的 stdin/Binder 交接（dsh-Go 已经这么做了）。先接 Shizuku，再用 libadb-android 或自研补上无线 ADB 自启。

### 4.3 副轴二：感知方案

| 方案 | 适用 | 成本 |
|---|---|---|
| 无障碍文本树（主） | 绝大多数原生 / WebView App | 低 token、可精确定位 |
| + OCR（ML Kit 端侧） | 自绘界面、树稀疏 | 端侧免费，有一定延迟 |
| + SoM 截图（给多模态主模型） | 布局理解、图标按钮 | 图片 token |
| + 独立 VLM 定位（`bbox_2d`） | 无树、无字的元素 | 需要交叉核验（Mika 的五步法） |
| 纯视觉（截图 → 坐标） | 跨平台、游戏 | 最贵，依赖强定位模型；三家都不以此为主 |

---

## 5. 推荐方案：路线 A + 可外接的能力层

### 5.1 架构蓝图

```
┌──────────────────────────── App 进程 ────────────────────────────┐
│ UI：对话 · 悬浮胶囊（状态/插话/双击发当前屏）· 审批卡 · 接管 · 轨迹回看 │
│                                                                  │
│ AgentCore（纯 Kotlin，JVM 可测）                                   │
│   loop：单主 agent + 旁路角色（意图门 / 结局判定 / 记忆提取）          │
│   prompt：资源化 + 金样；运行时状态挂在消息尾部                        │
│   context：缓存分区 · 只追加 · 确定性压缩                             │
│   tools：静态 schema · 分组展开 · 每个调用带 taskId/sessionId/reason   │
│   PolicyEngine：唯一安全判定点（硬拒绝 / 询问 / 放行）                 │
│   Guard：LoopGuard 阶梯 · 对外动作节奏 · 效果核实                     │
│                                                                  │
│ DeviceLayer（接口 + Android 实现）                                 │
│   Perception：a11y → Snapshot（0–999 坐标、稳定 id、版本号、diff、预算）│
│               OCR 兜底 · SoM 截图 · VLM 定位（交叉核验）              │
│   Actuation：a11y 动作 → 手势(displayId) → 特权注入；多级输入 + 回读    │
│   Surface：MainScreen | AgentDisplay（虚拟屏，跳出回收 / 接管 / 静音）  │
│   Intents：闹钟 / 日历 / 分享 / 深链等白名单捷径（优先于点界面）          │
│                                                                  │
│ ToolServer（可选，对外）：MCP（工具级 + 任务级）← PC agent / 评测 harness│
└───────────────────────────────┬──────────────────────────────────┘
                                │ Binder（AIDL，签名指纹校验）
┌───────────────────────────────▼──────────────────────────────────┐
│ PrivilegedCore（uid 2000，传输无关）：Shizuku UserService | 无线 ADB 拉起 │
│ 建虚拟屏 · 跨屏启动 · 输入注入 · 挪任务 · appops · pm grant · 能力探针    │
└──────────────────────────────────────────────────────────────────┘
```

为什么不选 B / C：
- 手机 GUI 场景的瓶颈在"手和眼"的可靠性，以及 loop 能否针对 GUI 定制，而不在通用 agent 生态。
- B / C 为引擎生态付出的代价（冷启动、包体、补丁维护、loop 不可控、无法上架）和收益不成比例。
- 如果确实需要编码或脚本能力，可以单独加一个 PRoot + seccomp 的沙箱工具（Mika 式），或通过 ToolServer 让外部 agent 来用手机。

### 5.2 分阶段落地

| 阶段 | 内容 | 退出标准 |
|---|---|---|
| **P0 骨架** | AgentCore（单 loop、多 provider、FakeChatModel 测试）+ 无障碍主屏（读树 Snapshot、tap / type / scroll / back / launch / deep link）+ `hand_to_user` + PolicyEngine 硬规则 + **PC 侧 MCP / ADB 调试通道** + 评测 harness 雏形 | 10–20 个基础任务在 1–2 台机器上跑通，能离线回放 |
| **P1 可靠性** | 事件驱动等稳 + 动作效果 / 差分；元素指纹防过期；多级输入 + 无键盘 IME；LoopGuard；对外节奏；OCR 兜底；SoM 截图 | 任务成功率、误操作率有基线数据 |
| **P2 后台屏** | PrivilegedCore（先 Shizuku）+ 虚拟屏配方（§2.4）+ 跳出回收 + 接管 + 能力探针 | 用户能正常用手机的同时，Agent 在后台完成任务 |
| **P3 体验与知识** | 无线 ADB 自启（libadb-android 或自研）+ 自动恢复无障碍；App 知识库（APP.md 按包名注入）；记忆；定时 / 条件触发；悬浮胶囊；VLM 定位 | 覆盖主流国产 App 的常见任务 |
| **P4 扩展** | 浏览器（WebView + CDP）、沙箱、经验路线回放、对外 MCP 任务接口、**多 ROM 真机评测矩阵** | 版本发布前有回归评测 |

### 5.3 必须一开始就定下来的设计约束（来自三家的坑）

1. **每次工具调用都带 taskId / sessionId**，停止、审批、收尾都精确到会话。DSH-Android 和 dsh-Go 都是"一停全停"。
2. **安全判定按动作语义，而不是按工具名**：GUI 上点"发送 / 支付 / 删除"同样要识别。判定点**全局只有一个**，规则写在代码里，不写在提示词里。
3. **付款在任何档位下都要确认，或至少单独成一档**（dsh-Go 的坑）。**不要提供无审批的任意 shell 工具**（Mika 的坑）。**默认不能是完全权限**（DSH-Android 的坑）。
4. 同意是一次性的，并与目标、金额绑定；无人值守时拒绝付款；防注入手势自我确认（`InjectGuard`）；外部内容（网页、通知、屏幕文字）一律当作 untrusted，"屏幕上的文字不是用户指令"。
5. **取消和超时契约**：单一超时真相源，App 先作答；挂起等待之后、执行之前再确认一次调用仍然有效；被放弃的调用绝不晚些执行。
6. **中断恢复要声明副作用**：要么不续跑（Mika），要么续跑时明确告诉模型"已有结果的调用已经生效，不要重做"（DSH-Android）。
7. **所有隐藏 API 调用都要能力探针 + 软降级**，并把失败原因回传给模型。主屏和全局输入法的操作要互斥。
8. **从第一天就建评测**：JVM 层用假模型 + 剧本屏幕 + 金样；真机层分调优池和保留池，按任务类别（读 / 写 / 不可逆 / 不可能 / 障碍）统计成功率和误操作率。三家里只有 Mika 做了，另外两家完全没有。

---

## 6. 风险清单

| 风险 | 说明 | 应对 |
|---|---|---|
| 锁屏 | DSH-Android / dsh-Go 锁屏即挂起或停止 GUI 任务（虚拟屏的 `ALWAYS_UNLOCKED` 也被锁屏检查挡住）；Mika 的常规任务锁屏暂停，但在"电脑连接"场景下能用本机加密保存的 PIN 远程亮屏解锁（`IMikaShell.enterDeviceCredential`，commit `c3beae9`） | 系统 API 类任务照常执行；GUI 任务挂起，通知用户解锁；无人值守解锁有安全代价，需单独评审 |
| 分发 | 无障碍 + 后台操控很难上架 Google Play 和国内商店；三家都是 APK 侧载；dsh-Go 因 targetSdk 28 直接不能上架 | 一开始就按侧载设计（自更新 + SHA 校验）；商店版做功能裁剪 |
| ROM 兼容 | 反射隐藏 API、自动重开无线调试、自动开无障碍都依赖原版系统行为；国产 ROM 有后台限制和"受限制的设置" | 能力探针、厂商设置跳转表、体检页、多 ROM 评测矩阵 |
| 第三方 ToS / 风控 | 自动化操作可能导致封号 | 人类化节奏、限流识别后停下、免责声明、默认询问 |
| 隐私 | 屏幕内容会发给模型；截图和运行记录会持久化 | 端侧脱敏（Luhn / 身份证校验位、密码框涂灰）、记录加密、按策略清理 |
| 提示词注入 | 网页、通知、聊天内容里可能嵌入指令 | untrusted 包裹 + 安全规则写在原生代码里 + 特权工具白名单 |
| 许可 | dsh-Go 是 AGPL，手机操控代码另有"经作者许可"的限制 | 借鉴思路即可；直接复用代码优先考虑 DSH-Android 和 Mika（Apache-2.0） |
| 文档可信度 | DSH-Android 的 README 有多项功能在代码里找不到；各项目文档都有过时之处 | 以代码为准 |

---

## 附：关键代码索引（便于继续深挖）

**DSH-Android**（前缀 `app/src/main/java/io/github/jackwener/dsh/`）
- 引擎：`core/engine/EngineService.kt`、`native/engine-jni/dshengine.cc`、`core/engine/JsonRpcPeer.kt`、`core/engine/RestartPolicy.kt`
- 能力：`core/capability/CapabilityHub.kt`、`core/capability/tools/ScreenTools.kt`、`core/capability/ScreenModel.kt`（稳定编号 / 差分）、`core/capability/TextInput.kt`（五级输入）、`core/capability/ScreenOcr.kt`
- 虚拟屏：`core/capability/AgentScreen.kt`、`core/platform/ShellUserService.kt`、`core/capability/ActivityLauncher.kt`
- 守卫：`core/capability/ActionGuard.kt`（LoopGuard / 节奏）、`js/packages/plugin-guard/`（hardline、continuation、experience）、`js/packages/plugin-mode/deferred.js`（延迟工具）
- 文档：`docs/architecture/01~17`（17 为自评）

**dsh-Go**（前缀 `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/`）
- 运行时：`runtime/build-runtime.sh`、`runtime/direct/`、`runtime/patches/pins.json`、`runtime/RuntimeController.kt`、`runtime/DirectLaunch.kt`、`runtime/SafeMode.kt`
- 通信：`packages/dsh_client/lib/src/carrier.dart`、`phone/PhoneBridge.kt`、`phone/McpServer.kt`、`phone/PhoneGuest.kt`
- 手机：`phone/PhoneToolCatalog.kt`、`phone/PhoneCore.kt`、`phone/a11y/ScreenFormat.kt`、`phone/a11y/EventClock.kt`、`phone/a11y/Actuator.kt`、`phone/background/PrivilegedDisplayCore.kt`、`phone/background/AdbStarter.kt`
- 安全：`phone/SafetyGate.kt`、`phone/SensitiveWords.kt`、`phone/a11y/ScreenMask.kt`

**Mika**（`c/` = `core/src/main/kotlin/com/wangzan101/mika/core/`，`a/` = `app/src/main/java/com/wangzan101/mika/`）
- 主循环：`c/agent/AgentLoop.kt`、`c/agent/SystemPrompt.kt`、`c/agent/ContextCompressor.kt`、`c/tool/ToolGroups.kt`、`c/action/ActionExecutor.kt`
- 感知：`c/uistate/ScreenPort.kt`、`c/uistate/ScreenSession.kt`、`c/uistate/TreeQuality.kt`、`c/vision/Locator.kt`、`a/cap/phone/a11y/ScreenReader.kt`
- 特权与虚拟屏：`c/adb/`（Spake2、PairingAuth、AdbTls、AdbConnection）、`c/shell/HelperHandoff.kt`、`a/cap/phone/shell/ShellService.kt`、`a/cap/phone/display/AgentDisplay.kt`
- 安全：`c/gate/IrreversibleDetector.kt`、`c/action/ConfirmDecision.kt`、`c/action/Approval.kt`、`c/cap/phone/PhoneAdvancedTools.kt`（system_command）
- 浏览器：`c/cap/web/cdp/`、`a/cap/web/cdp/DesktopProfile.kt`
- 评测与文档：`eval/README.md`、`core/src/test/resources/prompt-surface/`、`docs/arch-v2/01~14`
