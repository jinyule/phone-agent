package dev.phoneagent.core.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.snapshot
import dev.phoneagent.core.testing.Screens.under
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenFormatterTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)
    private val click = setOf(ElementState.CLICKABLE)

    private fun page(
        vararg elements: UiElement,
        detail: SnapshotDetail = SnapshotDetail.BRIEF,
    ) = formatter.page(snapshot("s1", elements.toList()), detail)

    /** The element lines of a page (without header and zone titles). */
    private fun body(vararg elements: UiElement) = page(*elements).lines().drop(1).filterNot { it.startsWith("[") }

    @Nested
    inner class Header {
        private fun title(
            pkg: String?,
            activity: String?,
            surface: SurfaceRef = Screens.AGENT,
        ) = formatter
            .page(
                snapshot("s3", emptyList()).copy(pkg = pkg, activity = activity, surface = surface),
                SnapshotDetail.BRIEF,
            ).lines()
            .first()

        @Test
        fun `names the app and the page, shortening activities inside the package`() {
            assertEquals("screen s3 · com.a/.ui.Main · agent 屏 · 坐标 0–999", title("com.a", "com.a.ui.Main"))
            assertEquals("screen s3 · com.a/org.b.Main · agent 屏 · 坐标 0–999", title("com.a", "org.b.Main"))
            assertEquals("screen s3 · com.a · agent 屏 · 坐标 0–999", title("com.a", null))
            assertEquals("screen s3 · org.b.Main · agent 屏 · 坐标 0–999", title(null, "org.b.Main"))
            assertEquals("screen s3 · 未知应用 · agent 屏 · 坐标 0–999", title(null, null))
            assertEquals("screen s3 · com.a · 主屏 · 坐标 0–999", title("com.a", null, SurfaceRef(0, SurfaceKind.MAIN)))
        }

        @Test
        fun `lists every page flag in a fixed order`() {
            val all =
                formatter.page(
                    snapshot("s3", emptyList()).copy(flags = ScreenFlag.entries.reversed().toSet()),
                    SnapshotDetail.BRIEF,
                )
            assertEquals(
                "状态：禁止截屏 · 键盘已弹出 · 系统选择框在前面 · 登录页 · 人机验证 · 支付页 · App 提示操作太频繁 · 手机已锁屏 · " +
                    "按安全规则不显示这一屏的内容",
                all.lines()[1],
            )
            assertFalse(page().contains("状态："))
        }

        @Test
        fun `explains a sparse or empty tree, pointing to hand-off when screenshots are blocked`() {
            fun note(
                quality: TreeQuality,
                flags: Set<ScreenFlag> = emptySet(),
            ) = formatter
                .page(
                    snapshot("s3", emptyList()).copy(flags = flags, quality = quality),
                    SnapshotDetail.BRIEF,
                ).lines()

            assertEquals("（没有读到界面元素）", note(TreeQuality.GOOD).last())
            assertEquals(2, note(TreeQuality.GOOD).size)
            assertTrue(note(TreeQuality.SPARSE)[1].startsWith("树不完整：") && "image=true" in note(TreeQuality.SPARSE)[1])
            assertTrue(note(TreeQuality.EMPTY)[1].startsWith("读不到界面元素：") && "image=true" in note(TreeQuality.EMPTY)[1])
            val secure = setOf(ScreenFlag.SECURE)
            assertTrue("hand_to_user kind=secure_page" in note(TreeQuality.SPARSE, secure)[2])
            assertTrue("hand_to_user kind=secure_page" in note(TreeQuality.EMPTY, secure)[2])
            assertFalse("image=true" in note(TreeQuality.EMPTY, secure)[2])
        }
    }

    @Nested
    inner class Lines {
        @Test
        fun `prints role words, values, hints, states and centers of actionable elements`() {
            val lines =
                body(
                    el(
                        "e1",
                        ElementRole.INPUT,
                        "收件人",
                        states = setOf(ElementState.EDITABLE),
                    ).copy(value = "张三", hint = "输入名字"),
                    el("e2", ElementRole.INPUT, "搜索", box(0, 0, 100, 20)).copy(hint = "搜索"),
                    el("e3", ElementRole.SWITCH, "蓝牙", states = setOf(ElementState.CHECKED)),
                    el("e4", ElementRole.SWITCH, "WLAN"),
                    el("e5", ElementRole.CHECKBOX, "同意", states = setOf(ElementState.CHECKED)),
                    el("e6", ElementRole.RADIO, "男"),
                    el("e7", ElementRole.TAB, "消息", states = setOf(ElementState.SELECTED, ElementState.CHECKED)),
                    el(
                        "e8",
                        ElementRole.BUTTON,
                        "下一步",
                        states = setOf(ElementState.DISABLED, ElementState.LONG_CLICKABLE),
                    ),
                    el("e9", ElementRole.IMAGE, "头像"),
                    el("e10", ElementRole.LINK, "用户协议"),
                    el("e11", ElementRole.PICKER, "小时").copy(value = "10"),
                    el("e12", ElementRole.PROGRESS, "下载").copy(value = "45%"),
                    el("e13", ElementRole.CONTAINER, "卡片"),
                    el("e14", ElementRole.OTHER, "画布"),
                    el("e15", ElementRole.TEXT, "说明").copy(hint = "  "),
                )
            assertEquals(
                listOf(
                    "@e2 输入框 \"搜索\" (50,10)",
                    "@e1 输入框 \"收件人\" 值:\"张三\" {可编辑} (499,425)",
                    "@e3 开关 \"蓝牙\" {开} (499,425)",
                    "@e4 开关 \"WLAN\" {关} (499,425)",
                    "@e5 复选框 \"同意\" {已选} (499,425)",
                    "@e6 单选 \"男\" {未选} (499,425)",
                    "@e7 标签 \"消息\" {已选,当前} (499,425)",
                    "@e8 按钮 \"下一步\" {可长按,不可用} (499,425)",
                    "@e9 图片 \"头像\"",
                    "@e10 链接 \"用户协议\" (499,425)",
                    "@e11 滚轮 \"小时\" 值:\"10\" (499,425)",
                    "@e12 进度 \"下载\" 值:\"45%\"",
                    "@e13 \"卡片\"",
                    "@e14 \"画布\"",
                    "@e15 \"说明\"",
                ),
                lines,
            )
        }

        @Test
        fun `secret fields never show their value`() {
            val field = el("e1", ElementRole.INPUT, "密码").copy(hint = "6 位数字", secret = true)
            assertEquals(listOf("@e1 输入框 \"密码\" (提示:\"6 位数字\") {secret} (499,425)"), body(field))
            // A secret field's hint that repeats its name is not printed twice.
            assertEquals(listOf("@e1 输入框 \"密码\" {secret} (499,425)"), body(field.copy(hint = "密码")))
        }

        @Test
        fun `text is one line, masked, without ASCII quotes and clipped`() {
            val long = "很长".repeat(40)
            val lines =
                body(
                    el("e1", ElementRole.TEXT, "第一行\n  第二行\t\"引用\""),
                    el("e2", ElementRole.TEXT, "卡号 6222021234567894"),
                    el("e3", ElementRole.TEXT, long),
                )
            assertEquals("@e1 \"第一行 第二行 ＂引用＂\"", lines[0])
            assertEquals("@e2 \"卡号 「疑似银行卡 尾号7894」\"", lines[1])
            assertEquals("@e3 \"" + long.take(59) + "…\"", lines[2])
        }

        @Test
        fun `full detail keeps whole texts, values and rows, brief clips them`() {
            val tail = "幸福路88号3栋1201室"
            val address = ("收货地址：广东省深圳市南山区" + "科技园南区".repeat(30)).take(108 - tail.length) + tail
            assertEquals(108, address.length)
            val text = el("e1", ElementRole.TEXT, address, box(0, 200, 999, 260))
            val field =
                el("e2", ElementRole.INPUT, "备注", box(0, 300, 999, 360), click + ElementState.EDITABLE)
                    .copy(value = address)
            val row = el("e3", ElementRole.BUTTON, bounds = box(0, 400, 999, 500), states = click)
            val parts = (4..6).map { el("e$it", ElementRole.TEXT, "$it".repeat(40)).under("e3") }
            val joined = (4..6).joinToString(" · ") { "$it".repeat(40) }
            val full = page(text, field, row, *parts.toTypedArray(), detail = SnapshotDetail.FULL)
            assertTrue(full.contains("@e1 \"$address\"\n"), full)
            assertTrue(full.contains("@e2 输入框 \"备注\" 值:\"$address\" {可编辑} (499,330)"), full)
            assertTrue(full.contains("@e3 按钮 \"$joined\" (499,450)"), full)
            val brief = page(text, field, row, *parts.toTypedArray())
            assertTrue(brief.contains("@e1 \"${address.take(59)}…\"\n"), brief)
            assertTrue(brief.contains("@e3 按钮 \"${joined.take(99)}…\" (499,450)"), brief)
        }

        @Test
        fun `Unicode spaces count as whitespace and as digit-group separators`() {
            val lines =
                body(
                    el("e1", ElementRole.TEXT, "卡号\u00A06222\u00A00212\u2009 3456\u202F7894"),
                    el("e2", ElementRole.TEXT, "持卡人\u3000张三\u00A0 身份证\u3000110105\u300019491231\u3000002X"),
                    el("e3", ElementRole.TEXT, "订单使用的银行卡 6222021234567894"),
                )
            assertEquals("@e1 \"卡号 「疑似银行卡 尾号7894」\"", lines[0])
            assertEquals("@e2 \"持卡人 张三 身份证 「疑似身份证 尾号002X」\"", lines[1])
            assertEquals("@e3 \"订单使用的银行卡 「疑似银行卡 尾号7894」\"", lines[2])
        }

        @Test
        fun `a card word across a long space run still wins over an order label`() {
            // The card-word window around a number counts a run of spaces as one character, as on
            // the one-line text the model sees: long spaces or blank lines do not push the word out.
            val lines =
                body(
                    el("e1", ElementRole.TEXT, "银行卡" + " ".repeat(28) + "订单号：6222021234567894"),
                    el("e2", ElementRole.TEXT, "订单号：6222021234567894\n" + " ".repeat(10) + "储蓄卡"),
                    el("e3", ElementRole.TEXT, "订单号：6222021234567894\n\n\n\n" + " ".repeat(20) + "（银行卡支付）"),
                    el("e4", ElementRole.TEXT, "支付方式：储蓄卡" + "\n".repeat(30) + "订单号：6222021234567894"),
                    el("e5", ElementRole.TEXT, "订单号：\n\n" + " ".repeat(40) + "6222021234567894"),
                )
            assertEquals("@e1 \"银行卡 订单号：「疑似银行卡 尾号7894」\"", lines[0])
            assertEquals("@e2 \"订单号：「疑似银行卡 尾号7894」 储蓄卡\"", lines[1])
            assertEquals("@e3 \"订单号：「疑似银行卡 尾号7894」 （银行卡支付）\"", lines[2])
            assertEquals("@e4 \"支付方式：储蓄卡 订单号：「疑似银行卡 尾号7894」\"", lines[3])
            // No card word: the order number stays, however far it is from its label.
            assertEquals("@e5 \"订单号： 6222021234567894\"", lines[4])
        }

        @Test
        fun `every Unicode White_Space character, U+0085 included, folds into one space`() {
            val spaces = (0..0xFFFF).map(Int::toChar).filter { WHITE_SPACE.matches("$it") }
            assertEquals(25, spaces.size)
            val text = spaces.joinToString("") { "字$it" } + "尾"
            val line = body(el("e1", ElementRole.TEXT, "\u0085 $text \u2029"))[0]
            assertEquals("@e1 \"" + "字 ".repeat(25) + "尾\"", line)
        }

        @Test
        fun `marks risky actionable elements with a warning, plain text never`() {
            val lines =
                body(
                    el("e1", ElementRole.BUTTON, "支付", box(0, 900, 999, 950)),
                    el("e2", ElementRole.TEXT, "发送"),
                    el("e3", ElementRole.BUTTON, "取消"),
                )
            assertEquals(listOf("@e2 \"发送\"", "@e3 按钮 \"取消\" (499,425)", "@e1 ⚠ 按钮 \"支付\" (499,925)"), lines)
            val calm =
                ScreenFormatter().page(
                    snapshot("s1", listOf(el("e1", ElementRole.BUTTON, "支付"))),
                    SnapshotDetail.BRIEF,
                )
            assertFalse(calm.contains("⚠"))
        }
    }

    @Nested
    inner class Layout {
        @Test
        fun `puts blocks in zones by their vertical center`() {
            val text =
                page(
                    el("e1", ElementRole.TEXT, "标题", box(0, 0, 999, 100)),
                    el("e2", ElementRole.TEXT, "正文", box(0, 400, 999, 500)),
                    el("e3", ElementRole.TAB, "首页", box(0, 900, 250, 999), click),
                    el("e4", ElementRole.TEXT, "边界上", box(0, 140, 999, 160)),
                )
            assertEquals(
                listOf(
                    "screen s1 · com.tencent.mm/.ui.chatting.ChattingUI · agent 屏 · 坐标 0–999",
                    "[顶部]",
                    "@e1 \"标题\"",
                    "[中部]",
                    "@e2 \"正文\"",
                    "@e4 \"边界上\"",
                    "[底部]",
                    "@e3 标签 \"首页\" (125,949)",
                ),
                text.lines(),
            )
        }

        @Test
        fun `a tappable row absorbs its plain texts, but not across a list and not texts with children`() {
            val lines =
                body(
                    el("e1", ElementRole.LIST, "会话", box(0, 200, 999, 800), setOf(ElementState.SCROLLABLE)),
                    el(
                        "e2",
                        ElementRole.CONTAINER,
                        bounds = box(0, 200, 999, 300),
                        states = click,
                    ).under("e1"),
                    el("e3", ElementRole.TEXT, "张三").under("e2"),
                    el("e4", ElementRole.TEXT, "明天见").under("e2"),
                    el("e5", ElementRole.TEXT, "张三").under("e2"),
                    el("e6", ElementRole.IMAGE).under("e2"),
                    el("e7", ElementRole.TEXT, "未读").under("e1"),
                    el("e8", ElementRole.BUTTON, "群聊 · 张三", states = click).under("e1"),
                    el("e9", ElementRole.TEXT, "张三").under("e8"),
                    el(
                        "e10",
                        ElementRole.LIST,
                        "子列表",
                        states = setOf(ElementState.SCROLLABLE),
                    ).under("e8"),
                    el("e11", ElementRole.TEXT, "在子列表里").under("e10"),
                    el("e12", ElementRole.CONTAINER, "分组").under("e8"),
                    el("e13", ElementRole.TEXT, "组里").under("e12"),
                )
            // e13 crosses the plain container e12 to reach the button e8; e9 repeats e8's name.
            assertEquals(
                listOf(
                    "↕ 列表 @e1 \"会话\"",
                    "  @e2 \"张三 · 明天见\" (499,250)",
                    "  @e7 \"未读\"",
                    "  @e8 按钮 \"群聊 · 张三 · 组里\" (499,425)",
                    "  ↔ 列表 @e10 \"子列表\"",
                    "    @e11 \"在子列表里\"",
                    "  @e12 \"分组\"",
                ),
                lines,
            )
        }

        @Test
        fun `a container too large to be a row keeps its texts as lines`() {
            val root = el("e1", ElementRole.CONTAINER, bounds = box(0, 0, 999, 999), states = click)
            val paragraphs =
                (2..7).map {
                    val name = "第 ${it - 1} 段：" + "这是一段比较长的正文内容，".repeat(2)
                    el("e$it", ElementRole.TEXT, name, box(40, 100 * it, 960, 100 * it + 80)).under("e1")
                }
            val full = page(root, *paragraphs.toTypedArray(), detail = SnapshotDetail.FULL)
            for (paragraph in paragraphs) {
                assertTrue(full.contains("@${paragraph.ref} \"${paragraph.name}\""), full)
            }
            assertTrue(full.contains("@e1 (499,499)"), full)
        }

        @Test
        fun `a row absorbs only a few short texts`() {
            val row = el("e1", ElementRole.BUTTON, bounds = box(0, 400, 999, 500), states = click)

            fun texts(
                count: Int,
                length: Int,
            ) = (2..count + 1).map { el("e$it", ElementRole.TEXT, "$it".repeat(length)).under("e1") }.toTypedArray()
            assertEquals(1, body(row, *texts(5, 4)).size, "five short texts are one row")
            assertEquals(7, body(row, *texts(6, 4)).size, "six texts stay lines")
            assertEquals(4, body(row, *texts(3, 80)).size, "too long together: they stay lines")
            assertEquals(1, body(row, *texts(2, 80)).size)
        }

        @Test
        fun `absorbed labels are clipped to the row maximum`() {
            val row = el("e1", ElementRole.BUTTON, states = click)
            val texts = (2..5).map { el("e$it", ElementRole.TEXT, "$it".repeat(40)).under("e1") }
            val line = body(row, *texts.toTypedArray()).single()
            val joined = (2..5).joinToString(" · ") { "$it".repeat(40) }
            assertEquals("@e1 按钮 \"" + joined.take(99) + "…\" (499,425)", line)
        }

        @Test
        fun `horizontal and unnamed lists`() {
            val lines =
                body(
                    el(
                        "e1",
                        ElementRole.CONTAINER,
                        bounds = box(0, 300, 999, 400),
                        states = setOf(ElementState.SCROLLABLE),
                    ),
                    el("e2", ElementRole.TEXT, "推荐").under("e1"),
                    el("e3", ElementRole.LIST, "竖的", box(0, 500, 400, 800)),
                )
            assertEquals(listOf("↔ 列表 @e1", "  @e2 \"推荐\"", "↕ 列表 @e3 \"竖的\""), lines)
        }

        @Test
        fun `elements with a missing parent or in a parent loop are top level`() {
            val lines =
                body(
                    el("e1", ElementRole.TEXT, "孤儿").under("e99"),
                    el("e2", ElementRole.BUTTON, "甲", states = click).under("e3"),
                    el("e3", ElementRole.TEXT, "乙").under("e2"),
                )
            assertEquals(listOf("@e1 \"孤儿\"", "@e2 按钮 \"甲\" (499,425)", "@e3 \"乙\""), lines)
        }

        @Test
        fun `unnamed decoration is not printed`() {
            assertEquals(
                listOf("@e2 \"文字\""),
                body(
                    el("e1", ElementRole.CONTAINER),
                    el("e2", ElementRole.TEXT, "文字").under("e1"),
                    el("e3", ElementRole.IMAGE),
                ),
            )
        }
    }

    @Nested
    inner class Folding {
        private val small = ScreenFormatter(ScreenFormatConfig(briefBudget = 500, fullBudget = 800))

        private fun rows(
            count: Int,
            parent: String? = null,
        ) = (1..count).map {
            el("r$it", ElementRole.TEXT, "第 $it 行内容", box(0, 300, 999, 320)).copy(parent = parent?.let(::ElementRef))
        }

        @Test
        fun `a long list is cut with a scroll hint inside it, keeping the top and bottom`() {
            val list = el("l1", ElementRole.LIST, "列表", box(0, 200, 999, 800), setOf(ElementState.SCROLLABLE))
            val top = el("t1", ElementRole.TEXT, "标题", box(0, 0, 999, 100))
            val nav = el("n1", ElementRole.TAB, "我的", box(0, 900, 999, 999), click)
            val text = small.page(snapshot("s1", listOf(top, list) + rows(60, "l1") + nav), SnapshotDetail.BRIEF)
            assertTrue(text.length <= 500, "${text.length}")
            val lines = text.lines()
            val fold = lines.single { it.startsWith("  … 另有 ") }
            val shown = lines.count { it.startsWith("  @r") }
            assertEquals("  … 另有 ${60 - shown} 项（在 @l1 里滚动查看）", fold)
            assertEquals(lines.indexOf("[底部]") - 1, lines.indexOf(fold))
            assertEquals(listOf("[底部]", "@n1 标签 \"我的\" (499,949)"), lines.takeLast(2))
            assertTrue(lines.contains("@t1 \"标题\""))
        }

        @Test
        fun `items outside lists are counted at the end of the middle zone`() {
            val brief = small.page(snapshot("s1", rows(60)), SnapshotDetail.BRIEF).lines()
            val shown = brief.count { it.startsWith("@r") }
            assertEquals("… 另有 ${60 - shown} 项（phone_observe detail=full 可看全部）", brief.last())
            val full = small.page(snapshot("s1", rows(80)), SnapshotDetail.FULL)
            assertTrue(full.length <= 800)
            assertTrue(full.lines().last().endsWith("项（超出长度上限，滚动后再看）"), full.lines().last())
            assertTrue(full.lines().count { it.startsWith("@r") } > shown)
        }

        @Test
        fun `a list whose header does not fit is counted with the other hidden items`() {
            val list = el("l1", ElementRole.LIST, "后面的列表", box(0, 600, 999, 800), setOf(ElementState.SCROLLABLE))
            val lines =
                small
                    .page(
                        snapshot(
                            "s1",
                            rows(60) + list + rows(3, "l1").map { it.copy(ref = ElementRef("x" + it.ref.value)) },
                        ),
                        SnapshotDetail.BRIEF,
                    ).lines()
            assertFalse(lines.any { "@l1" in it })
            val shown = lines.count { it.startsWith("@r") }
            assertEquals("… 另有 ${60 - shown + 4} 项（phone_observe detail=full 可看全部）", lines.last())
        }

        @Test
        fun `OCR text follows the tree and is folded last`() {
            val ocr = (1..3).map { OcrLine("识别$it", box(0, it * 100, 200, it * 100 + 50)) }
            val text =
                formatter.page(
                    snapshot("s1", listOf(el("e1", ElementRole.TEXT, "树里"))).copy(ocr = ocr),
                    SnapshotDetail.BRIEF,
                )
            assertEquals(
                listOf(
                    "[中部]",
                    "@e1 \"树里\"",
                    "[屏上文字 · OCR，树里没有]",
                    "\"识别1\" (100,125)",
                    "\"识别2\" (100,225)",
                    "\"识别3\" (100,325)",
                ),
                text.lines().drop(1),
            )
            val many = (1..80).map { OcrLine("屏上第 $it 行文字", box(0, 10, 200, 20)) }
            val folded = small.page(snapshot("s1", emptyList()).copy(ocr = many), SnapshotDetail.BRIEF).lines()
            val shown = folded.count { it.startsWith("\"屏上第") }
            assertTrue(shown in 1..79)
            assertEquals("… 另有 ${80 - shown} 行屏上文字", folded.last())
            assertFalse(folded.contains("（没有读到界面元素）"))
        }
    }

    @Nested
    inner class AfterAction {
        private val before = Screens.CHAT
        private val sent = el("e11", ElementRole.TEXT, "我：十点半", box(600, 400, 960, 460)).under("e5")

        private fun after(
            outcome: ActionOutcome,
            plannedOn: SnapshotId? = before.id,
        ) = formatter.afterAction(outcome, plannedOn)

        @Test
        fun `without a screen after the action it says so`() {
            assertEquals("（动作后没能读到屏幕，用 phone_observe 查看当前画面）", after(ActionOutcome(Status.Ok, Effect.UNKNOWN)))
        }

        @Test
        fun `without a diff or in a new app it shows the whole page`() {
            val now = before.copy(id = SnapshotId("s13"))
            val page = formatter.page(now, SnapshotDetail.BRIEF)
            assertEquals(page, after(ActionOutcome(Status.Ok, Effect.CHANGED, now)))
            assertEquals(
                page,
                after(ActionOutcome(Status.Ok, Effect.NEW_APP, now, ScreenDiff(removed = listOf(ElementRef("e6"))))),
            )
        }

        @Test
        fun `shows added, changed and removed elements with refs of the new screen`() {
            val now =
                before.copy(
                    id = SnapshotId("s13"),
                    elements =
                        before.elements.filterNot { it.ref.value == "e6" }.map {
                            if (it.ref.value ==
                                "e9"
                            ) {
                                it.copy(value = "好的")
                            } else {
                                it
                            }
                        } +
                            sent,
                )
            val diff = ScreenDiffer().diff(before, now)
            assertEquals(
                listOf(
                    "screen s13 · 与 s12 相比的变化（其余不变）",
                    "状态：键盘已弹出",
                    "~ @e9 输入框 值:\"好的\" {可编辑,焦点} (480,955)",
                    "+ @e11 \"我：十点半\"",
                    "- @e6",
                ),
                after(ActionOutcome(Status.Ok, Effect.CHANGED, now, diff)).lines(),
            )
        }

        @Test
        fun `a changed child shows as its row, a changed list as its header, invisible changes are dropped`() {
            val now =
                before.copy(
                    id = SnapshotId("s13"),
                    elements =
                        before.elements.map {
                            when (it.ref.value) {
                                "e4" -> it.copy(name = "更多功能")
                                "e5" -> it.copy(name = "聊天")
                                else -> it
                            }
                        } + el("e20", ElementRole.CONTAINER),
                )
            val diff =
                ScreenDiff(
                    added = listOf(now.elements.last()),
                    changed =
                        now.elements.filter {
                            it.ref.value in
                                setOf("e4", "e5", "e10")
                        },
                )
            assertEquals(
                listOf(
                    "screen s13 · 与上一屏相比的变化（其余不变）",
                    "状态：键盘已弹出",
                    "~ @e3 按钮 \"更多功能\" (940,60)",
                    "~ ↕ 列表 @e5 \"聊天\"",
                    "~ @e10 ⚠ 按钮 \"发送\" (930,955)",
                ),
                after(ActionOutcome(Status.Ok, Effect.CHANGED, now, diff), plannedOn = null).lines(),
            )
        }

        @Test
        fun `a removed ref that the new screen prints again is listed under its new line, not as gone`() {
            // e6 disappeared and the surface gave its ref to the new message: the ref is shown in both
            // snapshots, so its new line is a change.
            val reused = el("e6", ElementRole.TEXT, "我：十点半", box(600, 400, 960, 460)).under("e5")
            val now =
                before.copy(
                    id = SnapshotId("s13"),
                    elements =
                        before.elements.filterNot { it.ref.value == "e6" } + reused,
                )
            val diff = ScreenDiffer().diff(before, now)
            assertTrue(diff.removed.isEmpty())
            // A diff that still lists the reused ref as removed (another analyzer) prints the same.
            assertEquals(
                after(ActionOutcome(Status.Ok, Effect.CHANGED, now, diff)),
                after(ActionOutcome(Status.Ok, Effect.CHANGED, now, diff.copy(removed = listOf(ElementRef("e6"))))),
            )
            assertEquals(
                listOf("screen s13 · 与 s12 相比的变化（其余不变）", "状态：键盘已弹出", "~ @e6 \"我：十点半\""),
                after(ActionOutcome(Status.Ok, Effect.CHANGED, now, diff)).lines(),
            )
        }

        @Test
        fun `a removed ref is left out only when the body prints its new binding`() {
            // Another analyzer's diff: e10 went to an unprinted container, and it reports that as added.
            val container = el("e10", ElementRole.CONTAINER, bounds = box(880, 930, 980, 980))
            val now = before.copy(id = SnapshotId("s13"), elements = before.elements.dropLast(1) + container)
            val diff = ScreenDiff(added = listOf(container), removed = listOf(ElementRef("e10")))
            assertEquals(
                listOf("screen s13 · 与 s12 相比的变化（其余不变）", "状态：键盘已弹出", "- @e10"),
                after(ActionOutcome(Status.Ok, Effect.CHANGED, now, diff)).lines(),
            )
        }

        @Test
        fun `a diff naming an element the screen does not have falls back to the full page`() {
            val now = before.copy(id = SnapshotId("s13"))
            val stray = el("e99", ElementRole.TEXT, "别的屏")
            val text = after(ActionOutcome(Status.Ok, Effect.CHANGED, now, ScreenDiff(changed = listOf(stray))))
            assertEquals("（与 s12 相比变化较大，下面是完整一屏，+ 是新出现的元素）", text.lines()[2], text)
        }

        @Test
        fun `an empty diff says nothing visible changed`() {
            val now = before.copy(id = SnapshotId("s13"))
            assertEquals(
                "screen s13 · 与 s12 相比没有可见变化\n状态：键盘已弹出",
                after(ActionOutcome(Status.Ok, Effect.NO_CHANGE, now, ScreenDiff())),
            )
        }

        @Test
        fun `a large change shows the full page with new elements marked`() {
            val fresh = (1..9).map { el("n$it", ElementRole.TEXT, "新内容$it") }
            val now = snapshot("s13", fresh.take(1) + before.elements.take(1) + fresh.drop(1))
            val text = after(ActionOutcome(Status.Ok, Effect.CHANGED, now, ScreenDiffer().diff(before, now)))
            val lines = text.lines()
            assertEquals("（与 s12 相比变化较大，下面是完整一屏，+ 是新出现的元素）", lines[1])
            assertTrue(lines.contains("@n1 + \"新内容1\""))
            assertTrue(lines.contains("@e1 按钮 \"返回\" (40,60)"))
        }

        @Test
        fun `the full page replaces the diff only above the changed share`() {
            val rows = (1..10).map { el("r$it", ElementRole.TEXT, "第 $it 行") }
            val old = snapshot("s1", rows)

            fun afterChanging(count: Int): String {
                val now = snapshot("s2", rows.mapIndexed { i, row -> if (i < count) row.copy(hint = "新") else row })
                return formatter.afterAction(
                    ActionOutcome(Status.Ok, Effect.CHANGED, now, ScreenDiffer().diff(old, now)),
                    old.id,
                )
            }
            // 5 of 10 is not more than half: still the diff; 6 of 10 is.
            assertTrue(afterChanging(5).startsWith("screen s2 · 与 s1 相比的变化（其余不变）"), afterChanging(5))
            assertEquals(5, afterChanging(5).lines().count { it.startsWith("~ ") })
            assertEquals("（与 s1 相比变化较大，下面是完整一屏，+ 是新出现的元素）", afterChanging(6).lines()[1])
        }

        @Test
        fun `a diff over the budget falls back to the folded page`() {
            val small = ScreenFormatter(ScreenFormatConfig(briefBudget = 500, fullPageShare = 0.99))
            val rows = (1..100).map { el("r$it", ElementRole.TEXT, "第 $it 行", box(0, 300, 999, 320)) }
            val old = snapshot("s1", rows)
            val now = snapshot("s2", rows.map { it.copy(name = it.name + "（新）") })
            val diff = ScreenDiff(changed = now.elements.take(40))
            val text = small.afterAction(ActionOutcome(Status.Ok, Effect.CHANGED, now, diff), old.id)
            assertTrue(text.startsWith("screen s2 · com.tencent.mm"), text)
            assertEquals("（与 s1 相比变化较大，下面是完整一屏，+ 是新出现的元素）", text.lines()[1])
            assertTrue(text.length <= 500)
        }
    }

    @Test
    fun `masks with the shared number masking`() {
        assertEquals("「疑似银行卡 尾号7894」", formatter.mask("6222021234567894"))
    }

    @Test
    fun `config is validated`() {
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(briefBudget = 499) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(fullBudget = 3499) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(labelMax = 7) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(rowMax = 59) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(topZoneEnd = -1) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(topZoneEnd = 900) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(bottomZoneStart = 1000) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(fullPageShare = 0.0) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(fullPageShare = 1.0) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(absorbMaxAreaShare = 0.0) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(absorbMaxAreaShare = 1.01) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(absorbMaxPieces = 0) }
        assertThrows<IllegalArgumentException> { ScreenFormatConfig(absorbMaxChars = 7) }
        ScreenFormatConfig(absorbMaxAreaShare = 1.0, absorbMaxPieces = 1, absorbMaxChars = 8)
    }
}

/** The Unicode White_Space property as the JDK knows it. */
private val WHITE_SPACE = Regex("\\p{IsWhite_Space}")
