# 安全策略规则（PolicyEngine 实现说明）

> 日期：2026-11-02（第 2 轮修复）｜配套：`docs/design/phase1-architecture.md` §3.6、§5、§6
>
> 代码：`core/src/main/kotlin/dev/phoneagent/core/policy/rules/`，入口是 `PolicyRules(ownPackages, ownLabels, tuning)`，它给出三样东西：
> `lexicon`（`RiskLexicon`，屏幕格式化用它标 ⚠）、`resolver`（`IntentResolver`）、`engine`（`PolicyEngine`）。三者共用同一份词表、同一份阈值（§8）和同一份 App 名单。
> 三者都是纯函数、确定性，对任何输入都不抛异常，读一屏的代价被读取上限封顶（§8）；构造时校验名单、`ownPackages`（都要是合法包名）、`ownLabels`（本 App 显示的名字，不能为空）和阈值，有错就抛 `IllegalArgumentException`。两个集合在构造时复制，之后调用方再改不影响判定。
>
> 本文和代码不一致时以代码为准，并在同一个 PR 里改本文。改词表、阈值或提示文字，要同时改 `core/src/test/.../policy/rules/` 里对应的测试。

---

## 0. 规则是启发式的：读不懂就保守

词表、页面识别和金额解析都是**启发式的纵深防护**：它们从控件文字、树结构、OCR 和 Activity 名猜一个动作的效果，不可能穷举所有 App、ROM 和写法。确定的保证只有这几条：名单里的 App、本 App 和敏感设置页一律拒绝（§1、§6.1 第 2 步）；认出来的付款每次确认、无人值守时不付款；批准只对整张卡相等的那一次调用有效（§6.3）。

规则漏掉的东西不靠"猜对"兜底，而靠**读不懂就保守**：读不出、分不清、对不上的时候，取更严的结果，不把"读不出"当成"没有"。会保守的情况：

| 情况 | 结果 | 位置 |
|---|---|---|
| 屏幕超过读取上限（元素和 OCR 行数、总字数、树深度），或父子关系成环 | 不读这一屏：屏幕动作和读屏都拒绝（`screen.unreadable`），`phone_key back / home` 照样放行；不标 ⚠；resolver 按没有屏幕解析；付款金额是 Unclear | §8 |
| 有货币标记或数字、但格式不支持的金额（`¥12,34`、`¥20.000`、位数太多） | Unclear：卡上不显示金额，复核一律再问 | §4 |
| 合计标签多到读不完，读到的又都没有金额 | Unclear | §4 |
| 一个点上叠着几个候选控件 | 每个都判定，取最严的；逃离豁免要所有候选都是逃离 | §2、§6.1 |
| 豁免（清空按钮、逃离按钮、发给本机的验证码、无害的删除） | 只认明确的说法；控件上任何一段文字说了付款、发送或删除，清空豁免就不成立；超过 `labelRead` 的长标签不当作豁免词 | §3.1、§3.2、§6.1 |
| 应用信息页、存储页上，认不出被管理的是哪个 App | 强行停止、清除数据、卸载、停用照样拒绝 | §1、§6.1 |
| 支付页上一个字都没有的控件、没有元素的触点 | SPEND | §2、§3.1 |
| 没有屏幕时提交的输入和回车 | OUTWARD（随后拒绝） | §2 |
| 复核时在新屏上算不出要确认的卡 | 视为不一致，用已批准卡的原因再问 | §6.1、§6.3 |
| 参数解析不了、不认识的工具 | 拒绝 | §6.1 |

**不保守**、会放行的已知情况写在 §3.3 的取舍表和 §9"已知覆盖缺口"里。评审或 eval 新发现的同义词、页面写法、Activity 名属于覆盖缺口：记进 §9，同时进 eval 待办，不按缺陷阻塞合入；豁免压过相反证据、风险被丢掉、"读不出"被当成"没有"、性能可以被拖垮、多候选没有取最严，这几类是结构性缺陷，要修。

---

## 1. App 名单（硬拒绝）

**文件**：`core/src/main/resources/dev/phoneagent/core/policy/rules/blocked-apps.txt`，一行一个 App，`#` 之后到行尾是注记：

```
<包名>  <类别>  <名称>[|<名称>...]  # 已核实 <日期> <来源>：<开发者>
```

- 类别：`bank`、`broker`、`payment`（拒绝原因都是 `hard.finance_app`），`system`（`hard.system_app`）。
- 格式错误（字段不够、包名不合法、未知类别、空名称、包名重复（不分大小写））时，`PolicyRules` 构造失败，错误信息带行号。
- **包名**不分大小写，和每次调用的目标包比较：`intent.pkg`（当前屏的 `pkg`、`phone_launch` 的包名），以及 `phone_intent settings page=app_details` 的 `package`。链接不带包名，打开后由新屏的 `pkg` 判定（§2）。
- **名称**归一化后（§3.2）整名相等才算，例如"招商 银行"能匹配"招商银行"，"支付"匹配不到"支付宝"。它只用在两个地方：桌面和应用选择器上点击的图标（§6.1 第 2 步，哪些包算桌面见下面的表），设置里某个 App 的应用信息页和存储页（识别被管理的 App）。`phone_launch` 只收包名（§7），不按名称匹配。本 App 的名称由 `ownLabels` 给出，同样用于这两处。

**收录范围**（2026-11-02）：

| 类别 | 内容 |
|---|---|
| payment | 支付宝、云闪付、京东金融、翼支付、AlipayHK；PayPal、Venmo、Cash App、Google Wallet / Pay、Samsung Wallet、Revolut、Wise、PhonePe、Paytm、PayPay、Toss、GCash、Klarna；Zelle（未核实） |
| bank | 工、农、中、建、交、邮储六家国有大行；招商、中信、浦发、民生、光大、平安、兴业、广发、华夏、浙商十家股份制银行（招行掌上生活、兴业生活、广发发现精彩、华夏华彩生活这几个信用卡 App 也在内）；网商银行；Chase、Bank of America、Wells Fargo、Citi、Capital One、Amex、HSBC、Barclays、Lloyds、N26、Monzo、RBC、CommBank、DBS、OCBC |
| broker | 同花顺、东方财富、天天基金、大智慧、雪球、蚂蚁财富；中信证券、中信建投、国泰海通、华泰（涨乐财富通）、招商证券、广发证券、银河证券、国信证券、平安证券、兴业证券；富途、老虎；Robinhood、IBKR、Webull、Schwab、Fidelity、E*TRADE；Coinbase、Binance |
| system | SystemUI、AOSP 安装器、AOSP 权限控制器、三星 Device Care（已核实）；Google / MIUI 安装器、Google 权限控制器、MIUI / 华为 / ColorOS / iQOO 的手机管家、vivo 权限管理（预装组件，没有公开来源，放在"未核实"段） |

**没有覆盖的**：城商行、农商行、外资行在华 App、大部分基金公司和区域券商的 App，以及上表以外的国际银行。这些 App 不会被硬拒绝，只按矩阵（§6.1 第 4 步）确认；付款、转账照样每次确认。

**来源**：每行注记写明核实的日期和来源：`play`（Google Play 应用页的标题和开发者）、`mi`（小米应用商店应用页的"包名"和开发者）、`qq`（腾讯应用宝应用页）、`aosp`（AOSP 里对应路径的 `AndroidManifest.xml`）。查不到来源的包名不收录；已经收录、但现在查不到的，移到文件末尾的"未核实"段，写明原因，仍然生效。

**更新方式**：
1. 改 `blocked-apps.txt`，一个 App 一行，注记写明来源和日期。
2. 跑 `./gradlew :core:test`。`BlockedAppsTest` 会加载这个文件，检查每行都有注记，并抽查几个类别。
3. 不要收录太泛的名称（例如"Security""钱包"）：桌面上名字相同的普通 App 会被误拒。

**系统设置里的敏感页**不在这个文件里，因为它们属于同一个包（`com.android.settings`），要按页面判断（§6.1 第 2 步）：

| 判断依据 | 位置 | 内容 |
|---|---|---|
| 页面标题候选（顶部 `titleBand` 以内、不属于任何控件的有名字元素，以及那一带的 OCR，全部都看）、点击目标上的全部文字（名字、内容、OCR） | `SettingsPages`（`SENSITIVE_SETTINGS`） | 无障碍、开发者选项、无线调试、USB 调试、权限、设备管理、安装未知应用、安全、隐私、锁屏、指纹、人脸、凭据、通知使用权、使用情况访问、悬浮窗、修改系统设置、VPN、恢复出厂、重置、账户等，含英文 |
| Activity 名片段 | `SettingsPages`（`SENSITIVE_ACTIVITIES`） | accessibility、development、permission、security、privacy、vpn、masterclear、account 等 |
| 一个 App 的设置页：应用信息页、存储页，以及它们弹出的确认对话框 | `SettingsPages`（`DETAILS`、`DETAILS_ACTIVITIES`、`MANAGEMENT`） | Activity 名含 installedappdetails、appinfo、appdetail、appstorage（AOSP 的 `Settings$AppStorageSettingsActivity`），或者屏上任何一段短文字说的是这类页面（应用信息、强行停止、存储和缓存、清除存储、清除数据、删除应用数据、Storage & cache、Clear storage……）时，页上的短文字（树和 OCR）按名称（本 App、名单）识别被管理的 App；识别出来就按那个 App 拒绝。其他 App（包括识别不出的）的这些页上，强行停止、清除数据 / 存储、卸载、停用、禁用这类控件一律拒绝（`hard.app_management`）；在说这些的对话框里点"确定""删除"这类按钮同样拒绝；权限入口按敏感页拒绝 |
| 桌面 / 应用选择器 | `HardRules.LAUNCHERS` | 列出的启动器和系统应用选择器包名：AOSP Launcher3、Pixel、MIUI、华为、荣耀、三星、vivo、OPPO、OnePlus 的桌面，`com.android.intentresolver` 和 `android`（系统的应用选择器）。在这些包的屏上，点到的图标名字按名称匹配本 App 和名单。不在清单里的桌面不按图标名称拒绝，只靠打开之后的新屏判定（§6.4）。**更新方式**：在 `LAUNCHERS` 里加包名，只收厂商预装的桌面和系统选择器，包名以真机上桌面前台时的 `pkg` 或 AOSP 源码为准；判定本身由 `CallsTest` 的桌面图标用例覆盖 |
| 链接 | `Launches.SCHEMES` | 只能打开 https、http、geo、mailto、market 链接（§6.1 第 1 步），设置页的链接打不开 |
| `phone_intent kind=settings` | `Launches.SAFE_SETTINGS_PAGES` | **白名单**：wifi、bluetooth、display、sound、battery、date、locale、location、storage、app_details。不在白名单里（包括不给 `page`）就拒绝 |

---

## 2. 意图解析（`IntentResolver`）

`resolve(call, risk, screen)`：先按工具名和参数（§7）把调用解码成一个"步骤"，再结合 `screen` 得出 `ActionIntent`，然后套上 `risk` 的下限，最后在 kind 定下来之后取金额（§4）。所以不论从哪条路得到 SPEND（点击、回车、滑动、`request_confirmation`、工具声明的下限），intent 都带着金额。

| 工具 | kind | pkg | 其他 |
|---|---|---|---|
| `phone_observe`、`phone_wait` | READ | 当前屏 | |
| `ask_user`、`hand_to_user`、`end_not_done`、`phone_apps` | READ | — | 不碰任何 App |
| `phone_tap` | 触点上的控件的判定（下面），没有则 NAVIGATE | 当前屏 | target；SPEND 时带金额（§4） |
| `phone_type` | 见下 | 当前屏 | target = 输入框，fieldClass（§5） |
| `phone_scroll` | 设置 App 里滚动选择器或滑块：SETTINGS；其他 NAVIGATE | 当前屏 | target = ref 指的元素 |
| `phone_swipe` | 见下 | 当前屏 | target = 起点的控件 |
| `phone_key` | back / home：NAVIGATE；enter：见下 | 当前屏 | target = 回车作用的元素 |
| `phone_launch` | NAVIGATE | `app` 是包名时为它；链接或不是包名时没有（随后被拒绝或由新屏判定，§6.1） | |
| `phone_intent` | NAVIGATE（所有 kind 都只打开预填好的界面，不直接提交） | `kind=settings` 时为设置 App，否则无 | |
| `request_confirmation` | 有 `amount` 时 SPEND；否则按 `action` 文字查词表；都没有则 OUTWARD | — | amount |
| 不认识的工具 | 声明为 READ 时 READ，否则 NAVIGATE（随后被拒绝，§6.1） | — | |
| 参数解析不了 | NAVIGATE（随后被拒绝） | 当前屏 | |

**点击会作用到哪里**（`Risks`，和执行器 WP07 的做法一致）：
- 带 ref：元素自己能点就点它；否则点往上最多 8 层里、覆盖元素中心的最近一个可点击祖先；都没有，就当作在元素中心按下（下一条）。
- 给坐标（或 ref 退化成中心点）：该点上所有"最里层"的可点击元素（自己里面没有别的可点击元素也在这一点上）都可能接到这次触摸，分不清哪个在上层。每个都判定，取最严的（§2 末尾的严重程度），同样严时取面积最小的。该点上没有可点击元素时，取最小的元素。
- 每个候选看到的文字（`Evidence`）：自己的名字；内容（任意深度的子孙文字，但跳过不覆盖它中心的、另外的可点击子控件，例如列表行里的"删除"按钮）；触点上的 OCR；它是可点击元素、而树里一个字也没有时（画出来的按钮），它范围里的 OCR。不可点击的大容器（WebView）里的 OCR 只按触点算。
- 没有任何元素（画布、ref 不在这一屏）：看触点上的 OCR；还判断不了，而页面是支付页（§3.1），就算 SPEND。

ref 和坐标两条路用的是同一份证据和同一套判定；屏幕格式化标 ⚠、resolver、硬拒绝（敏感设置入口、桌面图标）也都读这一份。

**输入**（`phone_type`）：没有 ref 时，输入到有焦点的输入框，没有焦点就输入到唯一的输入框。
1. 密码、验证码、支付密码框：INPUT，带字段类别（随后转交用户）；
2. `submit=true` 且页面是支付页：SPEND；
3. `submit=true` 且输入框不是搜索框：OUTWARD；
4. 设置 App 里：SETTINGS；
5. 其他：INPUT。

**滑动**：按起点做一次坐标判定（上面）。起点是 SPEND（"向右滑动支付"，包括起点上没有元素、只有 OCR 或画在画布上的付款滑块）→ SPEND，先于下面各条；设置 App 里从滑块、选择器、开关、复选框、单选框开始 → SETTINGS；水平距离 ≥ `rowSwipeMin` 且大于竖直距离，起点是可点击、宽度 ≥ `rowMinWidth` 的列表行，且不在高度 < `carouselMax` 的可滚动区域（轮播）里 → DELETE（邮件、聊天列表左右滑删除，宁可多确认）；其他 → NAVIGATE。

**回车**：焦点在不是输入框的元素上时，按点击它（ref）判定；否则页面是支付页 → SPEND；焦点在输入框上时，搜索框 → NAVIGATE，否则 OUTWARD；没有焦点时，屏上有输入框并且全是搜索框 → NAVIGATE，否则 OUTWARD。

**没有屏幕**（`screen == null`）：`submit=true` 的输入和回车算 OUTWARD，其他输入算 INPUT（PLAIN），其他算 NAVIGATE。这些调用随后都会被拒绝（§6.1 第 1 步，`call.no_screen`）。屏幕超过读取上限（§8）时同样这样解析，只是 `pkg` 仍取当前屏；这些调用随后被拒绝（`screen.unreadable`）。

**下限**：工具声明的 `RiskClass` 是下限。SPEND → SPEND，OUTWARD → OUTWARD，SETUP → SETTINGS，READ / WRITE 没有下限。严重程度从低到高：READ < NAVIGATE < INPUT < SETTINGS < DELETE < OUTWARD < SPEND。被抬高时去掉字段类别；但密码、验证码、支付密码框的输入不抬高，保证它们仍然转交用户。

**https 链接的边界**：https 链接可能经 App Link 直接打开银行或支付 App，policy 在启动这一步看不出来。兜底是动作之后的新屏：runtime 把返回给模型的每张新快照先当作一次 `phone_observe` 交给 PolicyEngine（§6.4），新屏的 `pkg` 在名单里就拒绝，并且不给模型看内容。

---

## 3. 风险词表（`RiskLexicon`）

"点这个控件会产生什么效果"共用一份词表（`Words` + `Lexicon`）和一处控件判定（`Controls`），判定入口有三处：`RulesLexicon.activationRisk`（屏幕格式化标 ⚠，按 ref 点击的方式算）、`Risks`（resolver 判定点击、滑动起点和回车，硬拒绝读同一份证据）、`request_confirmation` 的 `action` 文字（`Lexicon.classify`）。前两处对同一个元素用的是同一个函数，所以按 ref 点击时 ⚠ 和 verdict 一定一致；按坐标点击时会把该点所有候选都算上（§2），只会更严，不会更松。

### 3.1 判定顺序

控件上的文字是 §2 的证据：名字、内容、OCR。

1. 输入框本身，以及清空按钮：无风险。清空按钮是标签为清空输入的词（`Words.FIELD_CLEAR`：清空、清除、清空内容、×、clear……；单独的"删除"不算）、或 resource id 含 `clear` 的控件，在输入框里或左右 `clearNear` 以内；或者输入框里既没有标签也没有 resource id 的图标，而且输入框里有字、图标上没画字（或者画的字全是清空输入的词）。**否决**：控件上任何一段文字（名字、内容、OCR）说的是清空以外的风险（"支付""发送""删除聊天记录"……），就不是清空按钮，按那些字判定；resource id 是 `clear`、位置在输入框里都压不过它。超过 `labelRead` 个字的文字不算清空词。
2. 设置 App 里，文字说的是敏感页（§1）：SETTINGS（第 2 步的硬拒绝会接着处理）。
3. 文字按 §3.2 匹配，取最严的命中：
   - **控件自己的标签**（名字；没有名字时是它显示的唯一一段文字；支付页上是它的每一段文字）里，强词出现在任何位置都算，不论多长（"我已阅读并同意服务协议现在支付并完成本次交易"）；
   - 整个标签（名字，没有名字时是内容拼起来，只读前 `labelRead` 个字）整体匹配，开头就是强词时不论多长都算（"确认支付并同意《用户协议》"）；
   - 内容和 OCR 每一段分别匹配，所以行里一段很短的"删除"预览也会命中。
   - 显示几段文字、自己没有名字的控件（列表行：名字、预览、时间）逐段按长度上限读，一段长预览不算按钮（§3.3、§9）。
4. 没有任何文字的图标按 resource id 的词判断：send / reply → OUTWARD，pay / checkout → SPEND，delete / remove → DELETE。
5. 泛化的确认按钮（确定、确认、好的、OK、Confirm……）看所在对话框：同一窗口里其他元素的文字和 OCR，用强词匹配，长度上限放宽到 `contextMax`。有花钱的词，或者页面是支付页，就是 SPEND；否则有删除的词就是 DELETE；否则取第一个命中的词。
6. 支付页上一个字都没有的控件：SPEND（可能就是付款按钮）。
7. 设置 App 里的开关、复选框、单选框、选择器（或者子元素里有这些），以及"强行停止 / 停用 / 启用 / 允许 / 拒绝……"按钮：SETTINGS。

**支付页**：带 `ScreenFlag.PAYMENT`；或者有合计类标签（§4）；或者短文字里有"支付方式、付款方式、收银台、免密支付、选择付款"。

### 3.2 标签匹配（`Lexicon.classify`）

先归一化（`Text`）：NFKC（全角转半角，`￥` 变 `¥`）、转小写、繁体转简体（词表用到的字：轉帳、發送、刪除、確認、購買……）、去掉零宽字符和空白。

**长度上限只量"词"**：先去掉括号里的说明（"《服务协议》""(3)"：从一个左括号到它后面最近的任意一种右括号；后面没有右括号的左括号原样保留），以及金额和计数（"¥1,299.00""3件"；货币符号、数字、单位之间最多隔 8 个空白），再去掉不是动作的名字，剩下的字数和 `labelMax` 比较。所以"立即支付 ¥1,299.00"和"去结算(3)"都按短标签算。

| 表 | 怎么算命中 | 例子 |
|---|---|---|
| **整词**（`Words.EXACT`） | 整个标签等于"前缀 + 词"，忽略末尾标点；去掉带货币标记的金额之后再比一次（计数不去，"评论(23)"仍是入口）。前缀：无、立即、马上、一键、去、确认 | 评论、去评论、提交！、立即预订、发红包、立即沟通、报名、拨打、订阅 ¥20、开通会员 ¥15/月 |
| **强词**（`Words.STRONG`） | 出现在长度 ≤ `labelMax` 的标签里（控件自己的标签不限长度，§3.1 第 3 步），而且不是状态：前面不是"待 / 已 / 未"，后面（跳过标点）不是"成功、失败、方式、码、中、详情……"；"记录"只对花钱和对外算状态（"付款记录"是入口，"删除记录"是删除） | 立即支付 ¥39.90、确认付款、去结算(3)、提交订单、删除聊天记录、删除记录、撤回、发送(1)、转发、打招呼、投简历 |
| **英文前缀**（`Words.ENGLISH_PREFIX`） | 标签以该短语开头，而且短语后面不接拉丁字母（汉字也算词边界：Send消息）；字母之间隔着空格时连起来读（s e n d） | Send message、Pay now、Delete conversation、Place order. |
| **英文整词**（`Words.ENGLISH_EXACT`） | 整个标签等于该短语 | Submit、Say hi!、Apply now |

匹配前先去掉不是动作的名字（"支付宝、支付方式、付款方式、支付设置、货到付款、先用后付、花呗"）和支付方式广告（"支持……支付"）。
**否决**：整个标签就是"发送 / 获取（短信）验证码（到手机）"这类说法（英文 Send code、Resend OTP……）时不算 OUTWARD，验证码是发到用户自己手机上的；带了收件人（"发送验证码给张三"）或说的是别的（"Send postcode"）照样算。标签里有"搜索历史 / 搜索记录 / 缓存 / search history / cache"时不算 DELETE。

### 3.3 误报和漏报的取舍

每一条都有对应的测试（`LexiconTest`、`RulesLexiconTest`、`FieldsTest`、`ResolverTest`、`SpendingTest`、`TargetsTest`）。

| 情况 | 规则 | 取舍 |
|---|---|---|
| "发送验证码""重新发送验证码""Send code" | 不算对外 | 验证码发到用户自己手机上，不是发给别人。只认整个标签，"发送验证码给张三"照样确认 |
| "删除搜索历史""清空缓存" | 不算删除 | 丢掉的东西能找回来，不值得打断用户。代价：谨慎 / 标准档下这类操作不确认 |
| 输入框里的"×""清空" | 不算删除 | 只是改输入内容。控件上另有付款、发送、删除的字（id 是 clear、OCR 是"支付"；图标上画着"删除聊天记录"）时不豁免 |
| "待付款""已发送""支付成功""付款码""付款记录""发送中" | 不算动作 | 这些是状态或入口 |
| "支付宝""支付方式""支持花呗分期付款" | 不算花钱 | 这些是名字、入口或广告 |
| "评论(23)""回复 3""提交搜索" | 不算对外 | 整词必须是整个标签；带计数的是入口。代价："评论一下"这类变体会漏掉，但真正发出去还要点"发送 / 发布" |
| "Payment methods""Posts""Sending…""Apply" | 不算 | 英文前缀要求词边界；单独的 Apply 常是筛选按钮 |
| 去掉金额、计数、括号说明后超过 `labelMax` 个字的文字 | 不算按钮 | 当作正文；但控件自己的标签（名字、它显示的唯一一段文字）里有强词时照样算，不论位置和长度（"我已阅读并同意服务协议现在支付并完成本次交易"）；对话框正文另按第 5 步处理 |
| 显示几段文字、自己没有名字的控件（列表行） | 逐段读，每段按 `labelMax` | 聊天、邮件列表的长预览（"……已经帮你把房租支付给房东了"）不算按钮，否则每一行都要确认。代价见 §9 第 1 条 |
| 搜索框里回车 / 提交 | 不算对外 | 同一行有"发送"类按钮的输入框，不算搜索框（"搜索或提问"的聊天框） |
| 支付页上一个字都没有的控件 | 算花钱 | 宁可多确认一次。代价：结算页上点没有标签的返回箭头也要确认，模型应该改用 `phone_key back` |
| 宽列表行上的水平滑动（≥ `rowSwipeMin`） | 算删除 | 邮件、聊天列表常见左右滑删除，滑多远会触发看不出来。代价：用水平滑动切页的行会多确认一次；轮播区域已排除 |
| 付款滑块（"向右滑动支付""拖动滑块付款"） | 算花钱，不算人机验证 | 滑动、拖动、滑块的说法要和"验证、拼图、人机、校验"在同一段文字里才算人机验证（"拖动滑块完成拼图"），或者是"滑动验证""安全验证"这类说法本身 |
| 一个点上叠着几个控件 | 取最严的；逃离豁免要每个都是逃离 | 分不清哪个在上层（窗口层次要等契约补充，B 段） |
| 行里另外的可点击子控件（行尾"删除"按钮） | 不算行的文字 | 点行不会按到它；点它本身时按它自己的文字判定 |

---

## 4. 金额（`Amounts`）

**格式**（`Prices`）：货币符号或代码在前或在后：`¥ ￥ $ US$ HK$ NT$ S$ A$ C$ € £ RMB CNY USD HKD EUR GBP JPY`、`元 块 美元 港元 港币 欧元 英镑 日元`；千分位必须是三位一组（`1,234.56`）；最多两位小数；可以带"万"（`¥1.2万`）。转成 `Money(minor, currency)`，minor 是分（或对应的最小单位）。
- **读不出的写法**：有数字（或货币标记加数字）、但不是上面支持的格式时，仍然是一个金额，只是读不出值：千分位不是三位一组（`¥12,34`、`12,34`、`¥1,2345.00`）、超过两位小数（`¥20.000`），都是 Unclear，不当作"没有金额"。
- **币种**：明确的币种代码或单位（`JPY`、`HKD`、`元`……）为准；前后两个代码冲突时为 null；只有 `$` 或 `¥` 时为 null（多个国家都用，看不出是哪种），`€`、`£` 各自对应 EUR、GBP。
- 跟在"- − 减 省 抵 返"后面的金额是优惠，跳过（"立减5元 实付¥95" → ¥95）。
- 后面跟"件 个 份 项 张 次 items pcs"的数字是件数，不是金额（"共3件 合计：¥88.00" → ¥88.00）。
- 整数部分超过 15 位，或者换算成分超过 `Long` 的上限：读不出（Unclear），不截断。

**花钱的金额**（`Amounts.forSpend`）是三种结果之一（`Price`）：`Known`（一个金额）、`Absent`（屏上没有金额）、`Unclear`（有金额，但定不了付的是哪个）。按优先级：
1. `request_confirmation` 的 `amount` 参数（字符串或数字，没有币种也行；给了但读不出时整个调用算参数解析不了）；
2. 控件标签里自己带的、有货币标记的金额（"支付 ¥0.01"）；
3. 合计类标签之后的金额：先看同一段文字里标签结束之后的部分，再看同一行右边的文字；有货币标记的金额优先，整段都没有才取第一个数字（这时货币为 null）。标签按优先级："实付、应付、需付、还需支付、支付金额、付款金额、待支付、you pay、amount due、to pay、合计、总计、总价、共计、总额、grand total、order total、total"。只看 `totalTextMax` 字以内的文字。同一优先级有多个时，阅读顺序靠后的先试（通常是底部结算栏）。按这个顺序最多试 `totalsRead` 个标签，取第一个后面有金额的；屏上的标签比这更多、试过的又都没有金额时：Unclear；
4. 整屏有货币标记的金额只有一种时，取它；有几种不同的：Unclear；一个都没有：Absent。

屏幕超过读取上限（§8）时不读这一屏，第 2 步以后都不做，金额是 Unclear（这一屏上的动作随后也会被拒绝）；`request_confirmation` 的 `amount` 参数照常读。

`Known` 的金额写进 intent 和确认卡。`Absent` 时确认卡照样弹出，只是不显示金额。`Unclear` 时确认卡也不显示金额，而且**批准后复核一律视为不一致**（§6.3），再问一次：在确认卡带上不展示的绑定字段（B 段）之前，没法证明用户批准的就是这次付的金额。所以金额定不了的付款，超过再问上限后就不执行。

## 5. 输入框（`Fields`）

**字段类别**只看输入框的 hint、名字和 resource id，**不看输入的内容**。resource id 取最后一段，按 `_ . -` 和驼峰拆成词，再把相邻两词连起来，所以 `et_pay_pwd` 和 `smsCode` 都能识别。按下面的顺序取第一个命中的：

| 类别 | 文字 | resource id |
|---|---|---|
| PAYMENT_PASSWORD | 支付密码、交易密码、付款密码、支付口令、payment password / PIN | paypwd、paypassword |
| VERIFICATION_CODE | 验证码、校验码、动态码、短信码、动态密码、verification code、OTP、captcha | otp、captcha、vcode、smscode、verifycode、authcode |
| PASSWORD | 密码、口令、password、passcode | password、passwd、pwd、pin、passcode |
| BANK_CARD | 银行卡、卡号、card number | cardno、cardnumber、bankcard |
| ID_NUMBER | 身份证、证件号、ID number、ID card | idcard、idno、idnumber |

都没命中、但输入框是密文框时：页面上有支付密码类的文字就算 PAYMENT_PASSWORD，否则算 PASSWORD。

**转交**的只有 PASSWORD、VERIFICATION_CODE、PAYMENT_PASSWORD（phase1 §6）。BANK_CARD 和 ID_NUMBER 由 agent 输入，但确认卡和日志里的内容会打码。

**打码**（`Masking`，用于确认卡的 content）：通过 Luhn 校验的 13–19 位卡号，以及校验位正确的 18 位身份证号，只留最后 4 位；输入到 BANK_CARD / ID_NUMBER 框里的文字整段打码。

**搜索框**：hint 或名字里有"搜索 / search"，或 resource id 里有 search，或同一行有"搜索"按钮；并且同一行没有对外按钮。

---

## 6. 判定顺序、提示与确认卡（`PolicyEngine`）

### 6.1 顺序

`evaluate(call, intent, ctx)` 按下面的顺序，第一个有结果的生效：

1. **检查不了的调用**
   - 参数解析不了 → Refuse `call.unresolved`；
   - 不认识的工具，且 intent 不是 READ → Refuse `call.unknown_tool`；
   - `request_confirmation` → 一律 Confirm（`REQUESTED_BY_MODEL`），任何档位都一样；
   - `phone_key back / home` → Allow，任何屏都放行，保证 agent 能离开被拒绝的页面；
   - 屏幕动作（点击、输入、滚动、滑动、回车）而 `ctx.screen` 为 null → Refuse `call.no_screen`，提示先 `phone_observe`；
   - **快照核对**：调用带 ref，而参数 `screen` 不等于 `ctx.screen.id`（包括没给 `screen`）→ Refuse `call.stale_screen`。`ctx.approved` 不为 null 时（批准后复核）跳过这一步；
   - `phone_launch` 的 `app` 不是包名 → Refuse `call.launch_package`，提示先用 `phone_apps` 查包名；`link` 的 scheme 不是 https、http、geo、mailto、market → Refuse `call.link_scheme`（`intent:`、`weixin://`、`alipays://` 这类都打不开）。
2. **硬拒绝**
   - `intent.pkg` 是本 App → `hard.own_app`；在名单里 → `hard.finance_app` / `hard.system_app`；
   - `phone_intent settings page=app_details` 的 `package` 是本 App 或在名单里 → 同上（那一页能强行停止、清除数据）；
   - `phone_intent kind=settings` 的页面不在白名单里 → `hard.sensitive_settings`；
   - 以下只对屏幕动作和读屏（`phone_observe`），并且有屏幕时：
     - 屏幕超过读取上限或父子关系成环（§8）→ `screen.unreadable`，这一屏不读，下面几条都不再看（`phone_key back / home` 在第 1 步已经放行）；
     - 当前屏是敏感设置页（§1）→ `hard.sensitive_settings`；
     - 当前屏是应用信息页，被管理的 App 按名称认出是本 App 或名单里的 → 按那个 App 拒绝；
     - 动作会碰到的控件（点击：触点上所有候选；其他：目标元素）上的文字说的是敏感页 → `hard.sensitive_settings`；
     - 一个 App 的设置页（应用信息页、存储页和它们的对话框，§1）上，碰到的控件说的是强行停止、清除数据 / 存储、卸载、停用、禁用，或者在说这些的对话框里点"确定""删除"这类按钮 → `hard.app_management`，认不出是哪个 App 时也一样；
     - 桌面或应用选择器（`pkg` 在 `HardRules.LAUNCHERS` 里，§1）上，碰到的图标名字是本 App 或名单里的 App → 按那个 App 拒绝。
   - 读屏在被拒绝的 App 和敏感设置页上也拒绝：这些页面的内容不给模型看。`ask_user` 这类不碰 App 的工具不受影响。
3. **转交用户**
   - 输入到密码框 → PASSWORD；验证码框 → CAPTCHA；支付密码框 → PAYMENT；
   - 在下列页面上**操作**（点击、输入、滑动、回车；滚动和读屏不算）：支付密码页 → PAYMENT，人机验证页 → CAPTCHA，登录页 → LOGIN，`ScreenFlag.SECURE` → SECURE_PAGE，按这个优先级。点击"关闭、跳过、取消、返回、暂不登录、以后再说、Not now……"不转交，让 agent 能离开；但触点上**每一个**候选控件的标签、以及它们上面的每一段文字都必须是这类词，超过 `labelRead` 个字的标签不算。一个点同时落在小的"取消"和大的"本机号码一键登录"上时照样转交，和按 ref 点"一键登录"一致。
   - 登录页：带 `ScreenFlag.LOGIN`；或者有明确的登录流程（"一键登录、本机号码一键登录、手机号登录、验证码登录、短信登录、免密登录、密码登录、Sign in with……"）：在短文字里，或者在控件（以及控件里的文字）上，后者不论多长（"本机号码一键登录并同意用户服务协议和隐私政策"）；或者短文字里有"登录 / login / sign in"，并且有密文框、要密码或要手机号 / 账号的输入框。没有密码框不能当作"不是登录页"。"登录 / 注册"这类个人页上的入口不算。人机验证页：带 `ScreenFlag.CAPTCHA`；或者短文字里有"滑动验证、滑块验证、安全验证、人机验证、图形验证、拼图验证、请按顺序点击、I'm not a robot、Slide to verify……"；或者短文字里有滑动、拖动、滑块的说法，并且同一段文字里有"验证、拼图、人机、校验、verify、puzzle"（"拖动滑块完成拼图"）。单独的"向右滑动""拖动滑块付款"不算，付款滑块也这么写（§3.3）。
4. **确认**（矩阵里和档位有关的行）

   | intent | 谨慎 | 标准 | 自主 |
   |---|---|---|---|
   | SPEND | 确认 | 确认 | 确认 |
   | OUTWARD | 确认 | `pkg` 不在 `outwardConfirmedPackages` 里时确认 | 放行 |
   | DELETE、SETTINGS | 确认 | 确认 | 放行 |
   | 其他非 READ，且 `pkg` 已知、不在 `operatedPackages` 里 | 确认（FIRST_IN_APP） | 放行 | 放行 |

   - 一次调用只出一张卡，原因按表里从上到下取第一个。包名比较不分大小写。
   - 无人值守（`ctx.unattended`）时，SPEND 一律 Refuse `spend.unattended`，提示模型如实告诉用户付款没有完成。其他确认照常返回 Confirm，由 runtime 的 `UserInteraction` 返回 UNAVAILABLE，调用不执行。
   - **批准后复核**（`ctx.approved` 不为 null）是独立的一支，在第 2、3 步之后：不论当前档位和历史还要不要确认，都在新屏 S' 上算出这次调用的卡（原因取档位给的；档位不再要求时取 kind 对应的原因：SPEND、OUTWARD、DELETE、SETTINGS；都没有时用已批准卡的原因），按 §6.3 和已批准的卡比较。SPEND 在无人值守时照样拒绝。
5. 其他：Allow。

`PolicyContext` 用到的字段：`level`、`unattended`、`screen`、`operatedPackages`、`outwardConfirmedPackages`、`approved`（各字段的计算方式见 `PolicyContext` 的 KDoc）。其中 **`operatedPackages`** 只取这个任务里**已经执行**的非 READ 调用的 `intent.pkg`：最终放行、并且日志里有 `tool/started` 的调用才算；被拒绝、转交、确认被用户拒绝或没有执行的调用都不算，null 也不算（和 `PolicyContext` 的 KDoc 一致）。`phone_launch app=<包名>` 执行了就算在内（它的 intent 带包名），所以谨慎档下"启动 App，再第一次点击"时，首次确认落在启动这一步，之后的点击不再是 FIRST_IN_APP；用户拒绝了启动的确认时，这个包不计入，之后不论从哪条路进了这个 App，第一个非 READ 调用仍然出 FIRST_IN_APP（`CallsTest` 有这两个序列）。链接启动（`phone_launch link=…`）的 intent 没有包名，不计入：经链接进入一个 App 之后，谨慎档下的第一个非 READ 调用仍然出 FIRST_IN_APP。policy 只按传进来的集合判断，集合的推导由 runtime 负责（§6.4）。

### 6.2 拒绝原因与提示

`reason` 是稳定的规则 id，写进日志和轨迹。`hintForModel` 告诉模型下一步怎么做。它不列举名单里的 App 名称和包名，不给阈值和 reason id，免得模型拿来绕过规则；可以用类别词说明哪一类操作或页面被拒（"付款""无障碍、开发者选项""强行停止、清除数据"）。提示用中文（和工具说明、系统提示词一致），全部写在 `Verdicts.kt` 的 `Refusal` 里，回放金样（WP09）以那里为准。`PolicyRulesTest` 对每条提示检查：是中文；指向一个下一步（某个工具、`end_not_done`、告诉用户、请用户、修正参数、换一个工具）；不含数字；不含它自己的 reason id；不含名单里任何一个 App 的名称或包名（名单按 `BlockedApps.parse` 的读法拆：去掉 `#` 之后的注记，逐个名称按整词匹配）。

| reason | 什么时候 | 提示的意思 |
|---|---|---|
| `hard.own_app` | 本 App | 离开这一屏（back / home），在别的 App 里继续，或者告诉用户 |
| `hard.finance_app` | 银行、券商、支付 App | 不要绕道进入；告诉用户这一步要用户自己完成，或者 `end_not_done` |
| `hard.system_app` | 系统界面 | back / home 换一种方式继续，或者告诉用户 |
| `hard.sensitive_settings` | 敏感设置页 | 返回换一种方式，或者请用户自己改这项设置 |
| `hard.app_management` | 一个 App 的设置页上强行停止、清除数据、卸载、停用 | 返回，或者告诉用户需要用户自己操作 |
| `screen.unreadable` | 屏幕超过读取上限或结构异常（§8） | 离开这一屏（back / home），换一种方式继续，或者告诉用户 |
| `spend.unattended` | 无人值守时花钱 | 没人能批准，付款没有发生；不要换别的方式付；`end_not_done` 并照实说明 |
| `call.no_screen` | 屏幕动作时还没有屏幕 | 先 `phone_observe` |
| `call.stale_screen` | ref 不是最新一屏的 | 先 `phone_observe`，再用这一屏的 ref 和 screen 编号 |
| `call.launch_package` | `phone_launch` 的 `app` 不是包名 | 先用 `phone_apps` 查到包名再启动 |
| `call.link_scheme` | 链接的 scheme 不允许 | 只能开网页、地图、邮件、应用商店链接；开 App 先查包名 |
| `call.unresolved` | 参数解析不了 | 修正参数后重试 |
| `call.unknown_tool` | 安全策略不认识的工具 | 换一个工具 |

转交的 `hint` 写给用户（英文，契约 `Verdict.HandOff.hint` 的约定，"请你自己登录，然后点完成"之类），App 按 `HandoffKind` 显示本地化文字。

确认被用户拒绝、转交结束之后给模型看的文字，不由 policy 写，由 runtime（WP02）写。

### 6.3 确认卡与一次性同意

| 字段 | 内容 |
|---|---|
| `callId` | 这次调用 |
| `reason` | §6.1 第 4 步的原因；`request_confirmation` 为 `REQUESTED_BY_MODEL` |
| `action` | 调用的 `reason` 参数（模型用用户的语言写）；`request_confirmation` 用它的 `action` 参数 |
| `target` | OUTWARD 时是页面标题（通常是会话对象），否则是目标元素的标签 |
| `content` | 输入的文字（`append=true` 时是输入框原有的文字加上这次输入的文字，也就是输入后框里会有的内容）；点击发送或回车时，是输入框里的草稿；打码（§5） |
| `amount` | §4 的 `Known` 金额，否则 null |
| `pkg` | `intent.pkg`；`request_confirmation` 一律为 null |

- **批准后复核**：用户批准后，runtime 在新的一屏 S' 上重新 resolve 和 evaluate，并把已批准的卡放进 `ctx.approved`。这时跳过快照核对；S' 已经是要拒绝或转交的页面时，返回 Refuse 或 HandOff；否则走 §6.1 第 4 步的复核分支：在 S' 上算出的卡和已批准的卡**整张相等**（包括 action 和 content）、并且金额不是 Unclear，才 Allow，否则返回新的 Confirm。按 S' 算不出要确认的卡（例如批准的是付款，S' 上同一个 ref 变成了普通导航），视为不一致，用已批准卡的原因再问一次，不放行。所以一次同意只对这次调用、这个目标、这个金额和这段内容有效。不比较 fingerprint，因为付款页常有倒计时。
- 卡片是调用、intent 和屏幕的纯函数，同一调用在没变的屏上算出的卡相同，这是复核能通过的前提。
- 打码后的 content 相同、但原文不同（例如两个尾号相同的卡号），以及收件人在 target 里看不出的变化，目前比较不出来；B 段的绑定字段补上。
- `request_confirmation` 只会**增加**确认：它的卡没有 pkg，批准它不会计入 `outwardConfirmedPackages`；批准它也不会放行之后的发送或付款，因为那是另一次调用，有另一张卡。

### 6.4 runtime 的义务

policy 只判定，下面这些由 runtime（WP02）做，WP09 的集成测试照这一段写：

- **每个调用**（包括 READ 的工具）都先 `resolve` 再 `evaluate`；`resolve` 用的 `screen` 必须就是 `ctx.screen`（复核时是 S'）。
- **再问上限**：复核返回新的 Confirm 时再问用户，默认最多再问 1 次，可配置；超过上限这次调用不执行，并告诉模型原因（policy 不计次数，不保存状态）。
- **动作后的新屏**：返回给模型的每张新快照，先合成一次 `phone_observe` 交给 PolicyEngine 判定；被拒绝时不给模型看任何内容（这一条兜住 https App Link 打开的金融 App，§2）。policy 保证对名单 App、本 App、敏感设置页和超过读取上限的屏（§8），读屏一定拒绝。
- `operatedPackages` 只从已经执行的非 READ 调用推导（最终放行并有 `tool/started`；被拒绝、转交、确认被拒、没有执行的都不算，null 不算），`outwardConfirmedPackages` 只取结果为 APPROVED、原因为 OUTWARD 的卡的 `pkg`；两者都按 `PolicyContext` 的 KDoc 从日志推导（§6.1 末尾）。WP09 的集成测试要覆盖"拒绝启动确认后再进入同一个 App"这个序列。

---

## 7. 依赖的工具参数

resolver 按 phase1 §5 的工具名和下面的参数名解析，取值规则和屏幕工具（WP06 `ArgReader`）一致：能通过 `ToolArguments` 校验、工具会接受的写法，policy 都按同一个意思读。改名或改规则时要同时改 `Steps.kt`、`Args.kt` 和 `StepsTest`。没列出的参数不影响判定。

**通用规则**：JSON `null` 等于没给；字符串参数接受任何基本类型的内容（`5` 读作 `"5"`）；布尔参数接受 `true` / `false` 和字符串 `"true"` / `"false"`，没给就是 false，别的值算解析不了；坐标接受整数的任何写法（`500`、`500.0`、`"500"`），必须在 0–999；`screen`、`ref`、`app`、`link`、`package` 去掉首尾空白；ref 写成 `@e5` 或 `e5`，裸数字不是 ref。

| 工具 | 参数 | 解析规则（不符合就是"参数解析不了"） |
|---|---|---|
| `phone_tap` | `screen`、`ref` 或 `x`+`y` | `ref` 和坐标必须正好给一种 |
| `phone_type` | `screen`、`ref`?、`text`、`submit`?、`append`? | `text` 必须给；给了 `ref` 就必须合法；`submit` 为 true 时是"输入后提交"（§2），`append` 为 true 时接在原有文字后面 |
| `phone_scroll` | `screen`、`ref`? | 给了 `ref` 就必须合法 |
| `phone_swipe` | `x1`、`y1`、`x2`、`y2` | 都必须是合法坐标 |
| `phone_key` | `key` | 正好是 `back`、`home`、`enter`（小写，和工具的枚举一致） |
| `phone_launch` | `app` 或 `link`（正好一个，非空） | 都按原样读进步骤；`app` 不是包名、`link` 的 scheme 不允许时由 §6.1 第 1 步拒绝。包名比较不分大小写 |
| `phone_intent` | `kind`、`page`、`package` | `kind` 是 `SystemIntentKind` 的线上名字（小写）；基本类型的字段转成去掉首尾空白的字符串，非基本类型的忽略；`kind=settings` 时 `page` 必须原样匹配（小写），`page=app_details` 必须带合法的 `package` |
| `request_confirmation` | `action`（非空）、`target`?、`content`?、`amount`? | `amount` 可以是字符串或数字，必须能读成一个金额（§4） |
| `phone_observe`、`phone_wait`、`phone_apps`、`ask_user`、`hand_to_user`、`end_not_done` | — | 不看参数 |

WP09 装配时要加集成测试：`ToolRegistry` 里每个工具，要么 resolver 认识，要么声明为 READ（contracts 补充 #1 §7）；`ArgReader` 接受的每种写法都过一遍 resolver。

---

## 8. 阈值（`PolicyTuning`）

决定漏报和误报的启发式参数和读取上限集中在 `PolicyTuning`，经 `PolicyRules` 的构造参数传入，构造时校验范围，超出就抛 `IllegalArgumentException` 并说出字段名。默认值就是规则本身的一部分，不向普通用户暴露；改默认值或改规则要升 `PolicyTuning.RULES_VERSION`（现在是 3），并同步改本文和测试。长度按归一化后的字符数，距离是 0–999 的归一化坐标。

`RULES_VERSION` 和实际用的 `PolicyTuning` 由装配方（WP09）在任务开始时记进 session 日志，回放时比对（铁律 1：verdict 要能从日志重建）；policy 自己不记日志。

| 字段 | 默认值 | 范围 | 含义 |
|---|---|---|---|
| `labelMax` | 12 | 1–1000 | 强词只在这么长以内的标签里算（§3.2 的量法） |
| `contextMax` | 40 | 1–1000 | 泛化确认按钮所在对话框里，正文强词的长度上限 |
| `shortText` | 20 | 1–1000 | 认页面时只看这么长以内的文字 |
| `totalTextMax` | 30 | 1–1000 | 合计类标签所在文字的长度上限 |
| `labelRead` | 120 | 1–1000 | 风险判定只读控件标签（没有名字时是内容拼起来）的前这么多字；更长的标签不当作逃离、清空、搜索这类豁免词 |
| `titleBand` | 150 | 0–999 | 页面标题在屏幕顶部这一带 |
| `clearNear` | 80 | 0–999 | 清空按钮离输入框左右的最大距离 |
| `rowSwipeMin` | 100 | 0–999 | 列表行上算"可能删除"的最小水平滑动距离 |
| `rowMinWidth` | 600 | 0–999 | 列表行的最小宽度 |
| `carouselMax` | 400 | 0–999 | 低于这个高度的可滚动区域算轮播 |
| `maxElements` | 5000 | 1–10,000,000 | 读取上限：元素数加 OCR 行数 |
| `maxScreenText` | 100000 | 1–10,000,000 | 读取上限：全屏文字的总字数（名字、value、hint、resource id、OCR） |
| `maxDepth` | 100 | 1–10,000,000 | 读取上限：树里最长的一条父子链 |
| `totalsRead` | 8 | 1–10,000,000 | 一屏最多试这么多个合计类标签（§4） |

**读取上限**：超过 `maxElements`、`maxScreenText`、`maxDepth` 任何一个，或者父子关系成环，这一屏就不读（`Reading.readable`），按 §0 保守：屏幕动作和读屏拒绝（`screen.unreadable`），不标 ⚠，resolver 按没有屏幕解析，付款金额是 Unclear。默认值远大于真实的屏（通常是几百个元素、几千字、几十层以内）。上限以内：
- 每张快照只建一次索引（`ScreenIndex`：ref、父子、祖先、内容、深度、总字数，按快照对象缓存），点击目标、触点候选、对话框文字、同一行的按钮都查索引，不再两两比较、反复遍历祖先或线性查 ref；每段不同的文字在一张快照上只匹配一次；
- 括号说明一遍扫描去掉，金额里的空白有上限；联合标签只读 `labelRead` 个字；合计标签最多试 `totalsRead` 个；
- 最坏情况（例如格式化时每个元素都在不同的点上、一个点上叠着几千个泛化按钮）是元素数的平方级，由 `maxElements` 封顶。

有界性由 `BoundsTest` 证明：`BoundsProbe` 的每一类病态输入（未闭合的括号、长串空白和状态词、同一 bounds 逐层嵌套的可点击树、一个点上叠满控件、同一行的输入框和按钮、同一行的合计标签、一个对话框里的泛化按钮、深层联合标签，以及超过上限的屏）在独立的 JVM 里跑过 lexicon、resolver 和 engine 的全部入口（三档、复核），120 秒内没结束就杀掉并判失败。这个时间不是阈值，只是判定"卡住"之前等多久：它至少是开发机上各类实际用时（含 JVM 启动，最慢的约 3 秒）的三十倍，慢机器上也稳定。

语义常数不在这里：每单位 100 分、最多两位小数、整数最多 15 位（§4）、执行器最多往上找 8 层可点击祖先（§2）、包名最长 255 字。

---

## 9. 已知覆盖缺口

下面这些情况规则会漏判或放行。它们属于覆盖缺口（§0）：不阻塞合入，每一条同时进 eval 待办（WP09 的 eval 集），补上就从这里删掉。新发现的缺口按"情况 / 现在的结果 / 兜底"写进来。

| # | 情况 | 现在的结果 | 兜底 |
|---|---|---|---|
| 1 | 没有名字、显示几段文字的控件（列表行那样），风险词只出现在其中一段超过 `labelMax` 的长文字中间，而不在开头 | 不算按钮（§3.3 逐段读的取舍），按导航放行 | 真正扣款前通常还有支付页或支付密码页：支付页上无字的控件算 SPEND，支付密码页转交用户 |
| 2 | 不在 `HardRules.LAUNCHERS` 里的桌面（第三方桌面、没收录的厂商桌面） | 点名单 App 的图标，不按名称拒绝 | 打开之后的新屏 `pkg` 在名单里就拒绝，内容不给模型看（§6.4） |
| 3 | 词表里没有的同义词和新说法（"评论一下"、各 App 自造的付款、发送按钮词） | 按导航放行 | 提交的输入和回车照样按 OUTWARD 确认（§2）；付款同第 1 条 |
| 4 | 新的设置页 Activity 名、ROM 改过的页面标题 | 认不出敏感页时，不按 `hard.sensitive_settings` 拒绝 | 设置 App 里的开关、选择器、"开启 / 关闭 / 允许 / 拒绝"按 SETTINGS 确认（谨慎、标准档；自主档放行） |
| 5 | ROM 改过应用信息页、存储页的文字和 Activity 名 | 认不出是一个 App 的设置页时，不按被管理的 App 拒绝，管理按钮也不按 `hard.app_management` 拒绝 | "强行停止""停用"按 SETTINGS，"清除数据""卸载"按 DELETE 确认（自主档放行） |
| 6 | 新的人机验证、登录写法 | 不转交，按点击本身判定 | 输入到密码框、验证码框照样转交（看输入框的 hint 和 resource id，§5） |
| 7 | 联合标签（§3.1 第 3 步）里，风险词被一段在 `labelRead` 之后才闭合的括号说明隔开，而每段文字自己都不含风险词 | 不算 | 每段文字照样逐段读；这种写法要求风险词被拆在几段文字里 |
| 8 | 名单以外的金融 App（§1"没有覆盖的"） | 不硬拒绝 | 付款、转账照样每次确认 |
| 9 | 打码后相同、原文不同的 content，以及 target 里看不出的收件人变化（§6.3） | 复核比较不出来 | B 段的绑定字段 |
