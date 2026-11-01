# 安全策略规则（PolicyEngine 实现说明）

> 日期：2026-11-01｜配套：`docs/design/phase1-architecture.md` §3.6、§5、§6
>
> 代码：`core/src/main/kotlin/dev/phoneagent/core/policy/rules/`，入口是 `PolicyRules(ownPackages)`，它给出三样东西：
> `lexicon`（`RiskLexicon`，屏幕格式化用它标 ⚠）、`resolver`（`IntentResolver`）、`engine`（`PolicyEngine`）。三者共用同一份词表和同一份 App 名单。
> 三者都是纯函数、确定性、不抛异常；构造时校验名单和 `ownPackages`，有错就抛 `IllegalArgumentException`。
>
> 本文和代码不一致时以代码为准，并在同一个 PR 里改本文。改词表、阈值或提示文字，要同时改 `core/src/test/.../policy/rules/` 里对应的测试。

---

## 1. App 名单（硬拒绝）

**文件**：`core/src/main/resources/dev/phoneagent/core/policy/rules/blocked-apps.txt`，一行一个 App：

```
<包名>  <类别>  <名称>[|<名称>...]
```

- 类别：`bank`、`broker`、`payment`（拒绝原因都是 `hard.finance_app`），`system`（`hard.system_app`）。
- `#` 开头的是注释。格式错误（字段不够、包名不合法、未知类别、空名称、包名重复）时，`PolicyRules` 构造失败，错误信息带行号。
- **包名**和每次调用的目标包精确比较：当前屏的 `pkg`、`phone_launch` 的包名或链接里的包名、`phone_intent settings page=app_details` 的 `package`。
- **名称**只用于 `phone_launch app=<名字>`：归一化后（§3.1）整名相等才算，例如"招商 银行"能匹配"招商银行"，"支付"匹配不到"支付宝"。其他名字由 runtime 经 `AppCatalog` 解析，打开之后屏幕的 `pkg` 还会再查一遍。

**收录范围**：

| 类别 | 内容 |
|---|---|
| payment | 支付宝、云闪付、京东金融、翼支付、AlipayHK；PayPal、Venmo、Cash App、Google Wallet / Pay、Samsung Wallet、Zelle、Revolut、Wise、PhonePe、Paytm、PayPay、Toss、GCash、Klarna |
| bank | 国有大行和主要股份制银行的手机银行、招行掌上生活；Chase、Bank of America、Wells Fargo、Citi、HSBC、Barclays 等国际主流银行 |
| broker | 同花顺、东方财富、天天基金、涨乐财富通、富途、老虎、雪球、大智慧、蚂蚁财富；Robinhood、IBKR、Webull、Schwab、Fidelity、E*TRADE；Coinbase、Binance |
| system | SystemUI；AOSP / Google / MIUI 的安装器；AOSP / Google 的权限控制器；MIUI、华为、ColorOS、iQOO、三星的安全中心（手机管家），vivo 的权限管理 |

**来源**：Google Play 的应用页（`play.google.com/store/apps/details?id=<包名>`）；只在国内商店上架的 App，用厂商商店的应用页。社区维护的名单（例如 gkd-kit 订阅文档里的 `<包名>.md`）可以拿来交叉核对，但不能作为唯一来源。

**更新方式**：
1. 改 `blocked-apps.txt`，一个 App 一行，PR 描述里给出每个新包名的来源链接。
2. 跑 `./gradlew :core:test`。`BlockedAppsTest` 会加载这个文件，并抽查几个类别。
3. 不要收录太泛的名称（例如"Security""钱包"）：名称只用来匹配按名字启动，太泛会误拒普通 App。

**系统设置里的敏感页**不在这个文件里，因为它们属于同一个包（`com.android.settings`），要按页面判断（§6.1 第 2 步）：

| 判断依据 | 位置 | 内容 |
|---|---|---|
| 页面标题（顶部 150 以内第一个有名字的文本）、点击目标的标签 | `Pages.SENSITIVE_SETTINGS` | 无障碍、开发者选项、无线调试、USB 调试、权限、设备管理、安装未知应用、安全、隐私、锁屏、指纹、人脸、凭据、通知使用权、使用情况访问、悬浮窗、修改系统设置、VPN、恢复出厂、重置、账户等，含英文 |
| Activity 名片段 | `Pages.SENSITIVE_ACTIVITIES` | accessibility、development、permission、security、privacy、vpn、masterclear、account 等 |
| 链接 | `Launches.SENSITIVE_SETTINGS` | 链接里同时有 `settings` 和一个敏感片段（accessibility、developer、adb、permission、security、vpn、lock、reset……） |
| `phone_intent kind=settings` | `Launches.SAFE_SETTINGS_PAGES` | **白名单**：wifi、bluetooth、display、sound、battery、date、locale、location、storage、app_details。不在白名单里（包括不给 `page`）就拒绝 |

---

## 2. 意图解析（`IntentResolver`）

`resolve(call, risk, screen)`：先按工具名和参数（§7）把调用解码成一个"步骤"，再结合 `screen` 得出 `ActionIntent`，最后套上 `risk` 的下限。

| 工具 | kind | pkg | 其他 |
|---|---|---|---|
| `phone_observe`、`phone_wait` | READ | 当前屏 | |
| `ask_user`、`hand_to_user`、`end_not_done`、`phone_apps` | READ | — | 不碰任何 App |
| `phone_tap` | 词表对目标的判定（§3），没有则 NAVIGATE | 当前屏 | target；SPEND 时带金额（§4） |
| `phone_type` | 见下 | 当前屏 | target = 输入框，fieldClass（§5） |
| `phone_scroll` | NAVIGATE | 当前屏 | target = ref 指的元素 |
| `phone_swipe` | 见下 | 当前屏 | target = 起点元素 |
| `phone_key` | back / home：NAVIGATE；enter：见下 | 当前屏 | target = 有焦点的元素 |
| `phone_launch` | NAVIGATE | 包名；名单里的名称对应的包名；链接里的包名 | |
| `phone_intent` | NAVIGATE | `kind=settings` 时为设置 App，否则无 | |
| `request_confirmation` | 有 `amount` 时 SPEND；否则按 `action` 文字查词表；都没有则 OUTWARD | — | amount |
| 不认识的工具 | 声明为 READ 时 READ，否则 NAVIGATE（随后被拒绝，§6.1） | — | |
| 参数解析不了 | NAVIGATE（随后被拒绝） | 当前屏 | |

**点击目标**：带 ref 时在 `screen` 里按 ref 查；给坐标时取该点上最小的可点击元素，没有可点击的就取最小的元素。
- 有元素：用词表判定（§3）。如果元素没有标签、不是输入框也不是清空按钮（例如没有无障碍信息的 WebView），再看该点的 OCR 文字。
- 没有元素（画布、ref 不在这一屏）：看该点的 OCR 文字；还判断不了，而页面是支付页（§3.2），就算 SPEND。

**输入**（`phone_type`）：没有 ref 时，输入到有焦点的输入框，没有焦点就输入到唯一的输入框。
1. 密码、验证码、支付密码框：INPUT，带字段类别（随后转交用户）；
2. `submit=true` 且输入框不是搜索框：OUTWARD；
3. 设置 App 里：SETTINGS；
4. 其他：INPUT。

**滑动**：起点元素在词表里是 SPEND（"向右滑动支付"）→ SPEND；水平滑动距离 ≥ 400 且大于竖直距离，起点是可点击、宽度 ≥ 600 的列表行，且不在高度 < 400 的可滚动区域（轮播）里 → DELETE（左滑删除）；其他 → NAVIGATE。

**回车**：焦点在输入框上时，搜索框 → NAVIGATE，否则 OUTWARD；焦点在其他元素上，按词表判定它；没有焦点时，屏上有输入框并且全是搜索框 → NAVIGATE，否则 OUTWARD。

**没有屏幕**（`screen == null`）：`submit=true` 的输入和回车算 OUTWARD，其他输入算 INPUT（PLAIN），其他算 NAVIGATE。

**下限**：工具声明的 `RiskClass` 是下限。SPEND → SPEND，OUTWARD → OUTWARD，SETUP → SETTINGS，READ / WRITE 没有下限。严重程度从低到高：READ < NAVIGATE < INPUT < SETTINGS < DELETE < OUTWARD < SPEND。被抬高时去掉字段类别；但密码、验证码、支付密码框的输入不抬高，保证它们仍然转交用户。

---

## 3. 风险词表（`RiskLexicon`）

`RulesLexicon.activationRisk(element, screen)` 是**唯一**判断"点这个元素会产生什么效果"的地方：屏幕格式化用它标 ⚠，resolver 判定每一次点击元素也用它。所以用 ref 点击时，⚠ 和 verdict 一定一致；用坐标点击时，resolver 还会额外看 OCR（§2），只会更严，不会更松。花钱只在这里判定。

### 3.1 判定顺序

1. 输入框本身，以及输入框里或紧挨着输入框的清空按钮（"×""清空"、resource id 含 `clear`，或输入框里没有标签的图标）：无风险。左右 80 以内算紧挨着。
2. 设置 App 里，标签说的是敏感页（§1）：SETTINGS（第 2 步的硬拒绝会接着处理）。
3. 标签（元素名字；没有名字时取 3 层以内子元素的文字）按 §3.2 匹配。
4. 没有标签的图标按 resource id 的词判断：send / reply → OUTWARD，pay / checkout → SPEND，delete / remove → DELETE。
5. 泛化的确认按钮（确定、确认、好的、OK、Confirm……）看所在对话框：同一窗口里其他元素的文字和 OCR，用强词匹配，文字长度放宽到 40。有花钱的词，或者页面是支付页，就是 SPEND；否则有删除的词就是 DELETE；否则取第一个命中的词。
6. 支付页上没有标签的元素：SPEND（可能就是付款按钮）。
7. 设置 App 里的开关、复选框、单选框、选择器（或者子元素里有这些），以及"强行停止 / 停用 / 启用 / 允许 / 拒绝……"按钮：SETTINGS。

**支付页**：带 `ScreenFlag.PAYMENT`；或者有合计类标签（§4）；或者短文字里有"支付方式、付款方式、收银台、免密支付、选择付款"。

### 3.2 标签匹配（`Lexicon.classify`）

先归一化：NFKC（全角转半角，`￥` 变 `¥`）、转小写、去掉空白。

| 表 | 怎么算命中 | 例子 |
|---|---|---|
| **整词**（`Words.EXACT`） | 整个标签等于"前缀 + 词"，忽略末尾标点。前缀：无、立即、马上、一键、去、确认 | 评论、去评论、提交！、立即预订、发红包、立即沟通、报名、拨打 |
| **强词**（`Words.STRONG`） | 出现在长度 ≤ 12 的标签里，而且不是状态：前面不是"待 / 已 / 未"，后面（跳过标点）不是"成功、失败、记录、方式、码、中、详情……" | 立即支付 ¥39.90、确认付款、去结算(3)、提交订单、删除聊天记录、撤回、发送(1)、转发、打招呼、投简历 |
| **英文前缀**（`Words.ENGLISH_PREFIX`） | 标签以该短语开头，而且短语后面不接字母 | Send message、Pay now、Delete conversation、Place order. |
| **英文整词**（`Words.ENGLISH_EXACT`） | 整个标签等于该短语 | Submit、Say hi!、Apply now |

匹配前先去掉不是动作的名字（"支付宝、支付方式、付款方式、支付设置、货到付款、先用后付、花呗"）和支付方式广告（"支持……支付"）。
**否决词**：标签里有"验证码 / 校验码 / 动态码 / code"时不算 OUTWARD；有"搜索历史 / 搜索记录 / 缓存 / search history / cache"时不算 DELETE。

### 3.3 误报和漏报的取舍

每一条都有对应的测试（`LexiconTest`、`RulesLexiconTest`、`FieldsTest`、`ResolverTest`）。

| 情况 | 规则 | 取舍 |
|---|---|---|
| "发送验证码""重新发送验证码""Send code" | 不算对外 | 验证码发到用户自己手机上，不是发给别人。代价：极少见的"把验证码发给好友"会漏掉 |
| "删除搜索历史""清空缓存" | 不算删除 | 丢掉的东西能找回来，不值得打断用户。代价：谨慎 / 标准档下这类操作不确认 |
| 输入框里的"×""清空" | 不算删除 | 只是改输入内容 |
| "待付款""已发送""支付成功""付款码""删除记录""发送中" | 不算动作 | 这些是状态或入口 |
| "支付宝""支付方式""支持花呗分期付款" | 不算花钱 | 这些是名字、入口或广告 |
| "评论(23)""回复 3""提交搜索" | 不算对外 | 整词必须是整个标签；带计数的是入口。代价："评论一下"这类变体会漏掉，但真正发出去还要点"发送 / 发布" |
| "Payment methods""Posts""Sending…""Apply" | 不算 | 英文前缀要求词边界；单独的 Apply 常是筛选按钮 |
| 超过 12 个字的文字 | 不算按钮 | 当作正文。代价：很长的按钮标签（"确认支付并同意《用户协议》"）会漏掉；对话框正文另按第 5 步处理 |
| 搜索框里回车 / 提交 | 不算对外 | 同一行有"发送"类按钮的输入框，不算搜索框（"搜索或提问"的聊天框） |
| 支付页上没有标签的元素 | 算花钱 | 宁可多确认一次。代价：结算页上点没有标签的返回箭头也要确认，模型应该改用 `phone_key back` |
| 宽列表行上的长水平滑动 | 算删除 | 很多 App 左滑删除。代价：用水平滑动切页的行会多确认一次；轮播区域已排除 |
| 网址里同时有 settings 和 privacy 等词 | 当作打开敏感设置 | 宁可误拒少见的网页 |

---

## 4. 金额（`Amounts`）

**格式**：货币符号或单位在前或在后：`¥ ￥ $ US$ HK$ NT$ S$ A$ C$ € £ RMB CNY USD HKD EUR GBP JPY`、`元 块 美元 港元 港币 欧元 英镑 日元`；千分位必须是三位一组（`1,234.56`）；最多两位小数；可以带"万"（`¥1.2万`）。转成 `Money(minor, currency)`，minor 是分（或对应的最小单位）。
- 跟在"- − 减 省 抵 返"后面的金额是优惠，跳过（"立减5元 实付¥95" → ¥95）。
- 没有货币的数字不算，只有紧跟在合计类标签后面时才算（这时货币为 null）。

**花钱的金额**（`Amounts.forSpend`），按优先级：
1. 按钮标签里自己带的金额（"支付 ¥0.01"）；
2. 合计类标签同一行右边的金额。标签按优先级："实付、应付、需付、还需支付、支付金额、付款金额、待支付、you pay、amount due、to pay、合计、总计、总价、共计、总额、grand total、order total、total"。只看 30 字以内的文字。同一优先级有多个时，取阅读顺序最后的那个（通常是底部结算栏）；
3. 整屏只有一种金额时，取它；
4. 都不满足：没有金额。确认卡照样弹出，只是不显示金额。

---

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
   - **快照核对**：调用带 ref，而参数 `screen` 不等于 `ctx.screen.id`（包括没给 `screen`、`ctx.screen` 为 null）→ Refuse `call.stale_screen`。`ctx.approved` 不为 null 时（批准后复核）跳过这一步。
2. **硬拒绝**
   - `intent.pkg` 是本 App → `hard.own_app`；在名单里 → `hard.finance_app` / `hard.system_app`；
   - `phone_intent settings page=app_details` 的 `package` 是本 App 或在名单里 → 同上（那一页能强行停止、清除数据）；
   - 打开敏感设置页：链接（§1），或 `phone_intent kind=settings` 的页面不在白名单里 → `hard.sensitive_settings`；
   - 在敏感设置页上读屏或做任何屏幕操作，或者点击通往敏感页的入口 → `hard.sensitive_settings`。
   - 读屏（`phone_observe`）在被拒绝的 App 和敏感设置页上也拒绝：这些页面的内容不给模型看。`ask_user` 这类不碰 App 的工具不受影响。
3. **转交用户**
   - 输入到密码框 → PASSWORD；验证码框 → CAPTCHA；支付密码框 → PAYMENT；
   - 在下列页面上**操作**（点击、输入、滑动、回车；滚动和读屏不算）：支付密码页 → PAYMENT，人机验证页 → CAPTCHA，登录页 → LOGIN，`ScreenFlag.SECURE` → SECURE_PAGE，按这个优先级。点击"关闭、跳过、取消、返回、暂不登录、以后再说、Not now……"不转交，让 agent 能离开。
   - 登录页：带 `ScreenFlag.LOGIN`，或者有密文框 / 要验证码的输入框，并且短文字里有"登录 / login / sign in"。人机验证页：带 `ScreenFlag.CAPTCHA`，或者有"滑动验证、拖动滑块、安全验证、I'm not a robot……"。
4. **确认**（矩阵里和档位有关的行）

   | intent | 谨慎 | 标准 | 自主 |
   |---|---|---|---|
   | SPEND | 确认 | 确认 | 确认 |
   | OUTWARD | 确认 | `pkg` 不在 `outwardConfirmedPackages` 里时确认 | 放行 |
   | DELETE、SETTINGS | 确认 | 确认 | 放行 |
   | 其他非 READ，且 `pkg` 已知、不在 `operatedPackages` 里 | 确认（FIRST_IN_APP） | 放行 | 放行 |

   - 一次调用只出一张卡，原因按表里从上到下取第一个。
   - 无人值守（`ctx.unattended`）时，SPEND 一律 Refuse `spend.unattended`，提示模型如实告诉用户付款没有完成。其他确认照常返回 Confirm，由 runtime 的 `UserInteraction` 返回 UNAVAILABLE，调用不执行。
5. 其他：Allow。

`PolicyContext` 用到的字段：`level`、`unattended`、`screen`、`operatedPackages`、`outwardConfirmedPackages`、`approved`（各字段的计算方式见 `PolicyContext` 的 KDoc）。

### 6.2 拒绝原因与提示

`reason` 是稳定的规则 id，写进日志和轨迹。`hintForModel` 告诉模型下一步怎么做，但不提名单里的条目、词表里的词和阈值，免得模型拿来绕过规则。提示目前用英文：`PolicyContext` 里没有用户语言，需要的话 runtime 可以按 `reason` 换成本地化文字。

| reason | 什么时候 | 提示的意思 |
|---|---|---|
| `hard.own_app` | 本 App | 离开这一屏（back / home），换个 App 继续，或者告诉用户 |
| `hard.finance_app` | 银行、券商、支付 App | 不要换别的路去打开它；告诉用户这部分要他自己做，或者 `end_not_done` |
| `hard.system_app` | 系统界面 | back / home，换条路，或者告诉用户 |
| `hard.sensitive_settings` | 敏感设置页 | 回去换条路，或者请用户自己改这个设置 |
| `spend.unattended` | 无人值守时花钱 | 没人能批准，付款没有发生；不要换别的方式付；`end_not_done` 并照实说明 |
| `call.stale_screen` | ref 不是最新一屏的 | 先 `phone_observe`，再用这一屏的 ref 和 screen id |
| `call.unresolved` | 参数解析不了 | 改好参数再试 |
| `call.unknown_tool` | 安全策略不认识的工具 | 换一个工具 |

转交的 `hint` 写给用户（英文，"请你自己登录，然后点完成"之类），App 按 `HandoffKind` 显示本地化文字。

### 6.3 确认卡与一次性同意

| 字段 | 内容 |
|---|---|
| `callId` | 这次调用 |
| `reason` | §6.1 第 4 步的原因；`request_confirmation` 为 `REQUESTED_BY_MODEL` |
| `action` | 调用的 `reason` 参数（模型用用户的语言写）；`request_confirmation` 用它的 `action` 参数 |
| `target` | OUTWARD 时是页面标题（通常是会话对象），否则是目标元素的标签 |
| `content` | 输入的文字；点击发送或回车时，是输入框里的草稿；打码（§5） |
| `amount` | §4 |
| `pkg` | `intent.pkg`；`request_confirmation` 一律为 null |

- **批准后复核**：用户批准后，runtime 在新的一屏 S' 上重新 resolve 和 evaluate，并把已批准的卡放进 `ctx.approved`。这时跳过快照核对；算出的卡和已批准的卡**完全相同**才 Allow，否则返回新的 Confirm；S' 已经是要拒绝或转交的页面时，返回 Refuse 或 HandOff。所以一次同意只对这次调用、这个目标、这个金额和这段内容有效；金额或目标变了就要重新确认。不比较 fingerprint，因为付款页常有倒计时。
- 卡片是调用、intent 和屏幕的纯函数，同一调用在没变的屏上算出的卡相同，这是复核能通过的前提。
- `request_confirmation` 只会**增加**确认：它的卡没有 pkg，批准它不会计入 `outwardConfirmedPackages`；批准它也不会放行之后的发送或付款，因为那是另一次调用，有另一张卡。

---

## 7. 依赖的工具参数

resolver 按 phase1 §5 的工具名和下面的参数名解析（WP06 的 schema 为准）。改名时要同时改 `Steps.kt` 和 `StepsTest`。没列出的参数不影响判定。

| 工具 | 参数 | 解析规则（不符合就是"参数解析不了"） |
|---|---|---|
| `phone_tap` | `screen`、`ref` 或 `x`+`y` | 有 `ref` 键时按 ref 解析，必须是 `@e5` 或 `e5` 这种形式；否则 `x`、`y` 必须是 0–999 的整数（不能是字符串） |
| `phone_type` | `screen`、`ref`?、`text`、`submit`? | `text` 必须是字符串；有 `ref` 键时必须合法；`submit` 只有布尔 true 才算 |
| `phone_scroll` | `screen`、`ref`? | 有 `ref` 键时必须合法 |
| `phone_swipe` | `x1`、`y1`、`x2`、`y2` | 都必须是 0–999 的整数 |
| `phone_key` | `key` | back / home / enter，不分大小写 |
| `phone_launch` | `app` 或 `link`（二选一，非空） | `app` 是包名或 App 名称 |
| `phone_intent` | `kind`、`page`、`package` | `kind` 是 `SystemIntentKind` 之一，不分大小写；其他基本类型的字段转成字符串，非基本类型的忽略 |
| `request_confirmation` | `action`（非空）、`target`?、`content`?、`amount`? | `amount` 可以是字符串或数字 |
| `phone_observe`、`phone_wait`、`phone_apps`、`ask_user`、`hand_to_user`、`end_not_done` | — | 不看参数 |

WP09 装配时要加集成测试：`ToolRegistry` 里每个工具，要么 resolver 认识，要么声明为 READ（contracts 补充 #1 §7）。

---

## 8. 阈值

坐标都是 0–999 的归一化坐标。这些是规则本身的一部分，不是用户配置；改了要同步改本文和测试。

| 常量 | 值 | 位置 | 含义 |
|---|---|---|---|
| `Words.LABEL_MAX` | 12 | 词表 | 强词只在这么长以内的标签里算 |
| `Words.CONTEXT_MAX` | 40 | 词表 | 对话框正文里强词的长度上限 |
| `Elements.CHILD_DEPTH` | 3 | 标签 | 没有名字的控件往下找几层子元素的文字 |
| `Pages.SHORT` | 20 | 页面 | 页面判断只看这么长以内的文字 |
| `Pages.TITLE_BAND` | 150 | 页面 | 标题在屏幕顶部这一带 |
| `Amounts.LABEL_TEXT_MAX` | 30 | 金额 | 合计类标签所在文字的长度上限 |
| `Fields.CLEAR_NEAR` | 80 | 输入框 | 清空按钮离输入框左右的最大距离 |
| `ScreenIntents.SWIPE_DELETE_MIN` | 400 | 滑动 | 左滑删除的最小水平距离 |
| `ScreenIntents.ROW_MIN_WIDTH` | 600 | 滑动 | 列表行的最小宽度 |
| `ScreenIntents.CAROUSEL_MAX` | 400 | 滑动 | 低于这个高度的可滚动区域算轮播 |
