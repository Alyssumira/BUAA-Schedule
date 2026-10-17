package com.buaa.schedule.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T407②：`SettingsGroup` 的**组体直处**（group body direct，即写在组体 content 里、没被 `item { }`
 * 包住的那一档语句）每次都组合当场跑、且不受 `visibleWhen` 保护这一格，本文件把它钉成机读的源码形状。
 * 覆盖面三件事（卡面点名的那三件，一枚都不许省）：
 *
 * **① 那一问不许再回到组体直处**（反向针）：`isRequestPin`+`AppWidgetSupported` 在
 * `ui/settings/SettingsScreen.kt` 里现在只有延迟位那一枚（`remember(widgetManager) { runCatching { … } }`），
 * 而病形那句「直接问桌面实例」的写法两栏都是 0 枚。③ 那格同时钉正形与病形，谁把那一问挪回去就当场红。
 *
 * **② 求值时机的判据怎么算**（「组体直处 vs 延迟位」）：不押「看起来在 lambda 里」——判据是从
 * `core/designsystem/SettingsStack.kt` 的**形参类型**推出来的：
 * `content: SettingsGroupScope.() -> Unit` **不带 @Composable**（:88），而 :90 是
 * `val scope = SettingsGroupScope().apply(content)` ⇒ `content` 在 `SettingsGroup` 被组合的那一刻
 * **逐语句当场执行**；分组可见性要到 :104-105 的 `AnimatedVisibility(visible = visibleWhen, …)` 才被读
 * （:91-93 那两枚早退只看 `scope.items.isEmpty()` 与 `visibleCount == 0`，与 `visibleWhen` 无关）。
 * 于是「延迟位」在这套形状里只有三枚来源，全部由被调方的形参类型证得：
 * `item(key, visible) { … }` 的**尾随 lambda**（`content: @Composable () -> Unit`，直到 :168 的
 * `item.content()` 才被调用）、`remember { … }`、`rememberSaveable { … }`。
 * 机器读的判据形状写在 [classify]：命中点落在哪一枚 span 里 ⇒ 一个标签串
 * （`立即求值(组内)` / `延迟位:item内容(组内)` / `延迟位:remember(组外)` …），
 * [theTimingClassifierSortsEachSourceShapeIntoADeferredSlotOrTheGroupBody] 拿十行合成样本逐档钉它。
 * ⚠️ 覆盖面如实写（**朝紧不朝宽**）：白名单之外的 lambda（`onClick = { … }`、`Column { … }`、
 * `ModalTransition(…) { … }`）在真实运行时里也是延迟的，本仪器**照样判它「立即求值」**——
 * 「认得不够」只会误红、「认得过多」会误绿，守卫只许朝前者错。样本第 8 行就是这一档的凭据。
 *
 * **③ `item(key = …, visible = …)` 的两枚形参同样是非 composable、即刻求值**：`SettingsGroupScope.item`
 * 的形参是 `key: Any? = null, visible: Boolean = true`（SettingsStack.kt:244-245），实参在 :90 那句
 * `apply(content)` 里就被求值 ⇒ **写进 `visible =` 的取数每次组合都跑，而且照样不受 `visibleWhen` 保护**。
 * 本卡盘面：全仓 `app/src/main/java` 的 `visible = ` 裸原文 23 枚、抹注释 22 枚（多的那枚是
 * SettingsStack.kt:239 那行 KDoc 里的散文），其中长在 `item(` 调用行上的恰好 7 枚，逐枚都是
 * 已握住的 state 比较或字面量、**零枚真取数**（⑥ 那格按白名单 + 逐字在册清单 + 两栏差三把钉它）。
 * 这一格是本轮新增的覆盖面：下一枚卡要是往 `item(visible = <取数>)` 里塞一次跨进程问话，
 * ⑤（全树组体直处的问句清单）看不见它 —— 它在 `item(` 的**圆括号**里、不在尾随花括号里 ⇒
 * 只有 ⑥ 听得见，别把 ⑥ 当冗余省掉。
 *
 * ## 第 ⓪ 问裁定（甲档）与这枚守卫的账
 * 甲 = 把那一问从组体直处挪进延迟位（`remember`），手感不变（首帧就知道能不能一键添加）。
 * 顺带把包闸一起做了（`runCatching { … }.getOrDefault(false)`，逐字同 WidgetPinRow.kt:65 那一形）——
 * 丙档那种「只包闸、不改求值时机」本卡判它**不算收口**：#712 那条机制讲的是时机，包闸只把「可能抛到调用方」
 * 降级成「可能念错文案」，每次组合那一趟 binder 与「停在别的分类也照问」两件事一件都没少。
 * 乙档（把 :768 的握住点与 :773 的 widgetCount 合成一次探测）**本卡不做**：它与卡面禁用面
 * 「不许动 :773-:786 那枚 widgetCount 的行为」直接冲突，那一格的账在册 #716，不归本卡。
 * 代价（与 main 里那段注释同批落盘）：甲 这一问现在落在设置页首次组合上，从不进「桌面组件」的用户也付一次；
 * 乙 缓存后桌面进程中途被杀再起来不再自纠；丙 探测抛了与真不支持共用同一行文案。
 * **零新内核**：甲档只是挪位置，没有新的判据本体要从 UI 里抽出去 ⇒ 不造 `domain/` 空壳。
 *
 * ## 尺子自免
 * 本文件**不用**「把源码摊成字符数组再物化回字符串」那一族写法（[withoutComments] 走 StringBuilder），
 * 一枚都不进 `CommentStrippingHelperShapeGuardTest` 那四枚写死值；点名的针一律拆开来写，
 * 散文顶不动 ③⑥ 那两把计数尺。
 *
 * 六枚 `@Test` 全是纯 JVM 源码核对：只 import `java.io.File` 与 JUnit，零 android import、零时钟读取、
 * 零 adb／零网络／零模拟器。**这处 binder 问话在真机上到底会不会抛到调用方，本卡没验到**
 * （这台机器上不允许装机）—— 包闸的理由来自同仓两处先例自己写的注释，不是来自实测。
 */
class SettingsGroupBodyImmediateEvalGuardTest {

    /**
     * ① 机制本体（SettingsStack.kt）：那一问为什么每次组合都跑，全凭这三枚形状的**次序** ——
     * content 形参不带 @Composable、:90 当场 `apply(content)`、`visibleWhen` 到 AnimatedVisibility 才被读。
     * 逐枚都是绝对值（各 1 枚，item 的 content 形参那一枚是 2 枚：声明一处 + `SettingsGroupItem` 构造参数一处），
     * 换了写法就红在这里，而不是让后面五格在错的推论上继续绿。
     */
    @Test
    fun theGroupBodyIsAppliedImmediatelyWhileVisibleWhenIsReadOnlyLater() {
        val stack = withoutComments(readMain(SETTINGS_STACK))
        val needles = listOf(
            Triple("content 形参不带 @Composable（每次组合当场跑的根据）", CONTENT_PARAM, 1),
            Triple(":90 那句当场 apply(content)", APPLY_CONTENT, 1),
            Triple("visibleWhen 的形参类型是 Boolean（不是 lambda）", VISIBLE_WHEN_PARAM, 1),
            Triple("AnimatedVisibility 读 visibleWhen（比 apply 晚）", ANIMATED_READS_VISIBLE_WHEN, 1),
            Triple("第一枚早退只看 items.isEmpty()", EARLY_RETURN_ON_EMPTY, 1),
            Triple("visibleCount 只数 item.visible", VISIBLE_COUNT_OF_ITEMS, 1),
            Triple("第二枚早退只看 visibleCount == 0", EARLY_RETURN_ON_ZERO, 1),
            Triple("item 的 visible 是非 composable 的 Boolean 形参", ITEM_VISIBLE_PARAM, 1),
            Triple("item 的 key 也是即刻求值的形参", ITEM_KEY_PARAM, 1),
            Triple("item 的 content 是尾随 lambda（到 :168 才被调）", ITEM_CONTENT_PARAM, 2),
            Triple("条目正文唯一的调用点 item.content()", ITEM_CONTENT_INVOKED, 1),
        )
        val drift = mutableListOf<String>()
        for ((label, needle, expected) in needles) {
            val at = occurrences(stack, needle)
            if (at != expected) drift.add(label + "「" + needle + "」实到 " + at + " 枚 ／ 写死 " + expected + " 枚")
        }
        val appliedAt = stack.indexOf(APPLY_CONTENT)
        val readAt = stack.indexOf(ANIMATED_READS_VISIBLE_WHEN)
        val itemReadAt = stack.indexOf(ITEM_INVISIBLE_READ)
        assertTrue(
            "组体直处之所以是「每次组合都跑且不受 visibleWhen 保护」，靠的是这三枚形状的次序：" +
                "apply(content) 在 :90、AnimatedVisibility(visible = visibleWhen) 在 :104-105、" +
                "条目可见性在 :142。次序一动（把 apply 挪进 AnimatedVisibility 之内、或给 content 加 " +
                "@Composable），本卡那套「组体直处 = 即刻求值」的判据就要整个重判 —— 后面五格读的都是它，" +
                "所以先红在这里：\n  apply(content)@" + appliedAt + " ／ visibleWhen 被读@" + readAt +
                " ／ item.visible 被读@" + itemReadAt + "\n" + drift.joinToString("\n"),
            drift.isEmpty() && appliedAt in 0 until readAt && appliedAt in 0 until itemReadAt,
        )
        assertEquals(
            "反向针：不许有人拿「给 content 加 @Composable」这一招来收这条机制 —— 那会改掉全仓 15 枚组的" +
                "公开形参语义（卡面禁用面点名：SettingsGroup / SettingsGroupScope 的形参语义不许在这一张里动，" +
                "要动先回编排者裁）：实到 " + occurrences(stack, ANNOTATED_CONTENT_PARAM) + " 枚",
            0,
            occurrences(stack, ANNOTATED_CONTENT_PARAM),
        )
    }

    /**
     * ② 判据本体（表驱动）：「组体直处 vs 延迟位」这一档**怎么算**要能机读，就得逐档钉仪器自己。
     * 十行合成样本，期望标签写在表里；再加一枚「每档几行」的分类账 —— 样本表被谁删到只剩两行时，
     * 第一条断言可以逐行全绿而覆盖面已经塌了，第二条才听得见。
     */
    @Test
    fun theTimingClassifierSortsEachSourceShapeIntoADeferredSlotOrTheGroupBody() {
        val rows = listOf(
            "组体直处的一句 if" to IMMEDIATE_IN_GROUP to listOf(
                "SettingsGroup(title = \"x\", visibleWhen = section == A) {",
                "    if (" + PROBE + ") {",
                "        item(key = \"a\") { Text(\"1\") }",
                "    }",
                "}",
            ),
            "组外的 remember 里的一问（本卡把那一问挪去的那一档）" to DEFERRED_REMEMBER_OUTSIDE to listOf(
                "val pinSupported = remember(widgetManager) {",
                "    runCatching { widgetManager." + PROBE + " }.getOrDefault(false)",
                "}",
                "SettingsGroup(title = \"x\") { item(key = \"a\") { Text(\"1\") } }",
            ),
            "item 尾随 lambda 里的一句" to DEFERRED_ITEM_INSIDE to listOf(
                "SettingsGroup(title = \"x\") {",
                "    item(key = \"a\") {",
                "        val v = " + PROBE,
                "        Text(v)",
                "    }",
                "}",
            ),
            "item 体内又套一枚 remember（widgetCount 那一格的形状）" to DEFERRED_REMEMBER_INSIDE to listOf(
                "SettingsGroup(title = \"x\") {",
                "    item(key = \"a\") {",
                "        val n = remember(widgetManager) { widgetManager." + PROBE + " }",
                "        Text(n)",
                "    }",
                "}",
            ),
            "item 的 visible 实参（③ 那一格的形状：即刻求值）" to IMMEDIATE_IN_GROUP to listOf(
                "SettingsGroup(title = \"x\") {",
                "    item(key = \"a\", visible = " + PROBE + ") { Text(\"1\") }",
                "}",
            ),
            "item 的 key 实参（同上，另一枚非 composable 形参）" to IMMEDIATE_IN_GROUP to listOf(
                "SettingsGroup(title = \"x\") {",
                "    item(key = " + PROBE + ".name) { Text(\"1\") }",
                "}",
            ),
            "composable 体里的裸语句（组外）" to IMMEDIATE_OUTSIDE to listOf(
                "val v = " + PROBE,
                "SettingsGroup(title = \"x\") { item(key = \"a\") { Text(\"1\") } }",
            ),
            "组外的一句 if（组外即刻求值，不归本卡管）" to IMMEDIATE_OUTSIDE to listOf(
                "if (" + PROBE + ") {",
                "    Column { Text(\"1\") }",
                "}",
            ),
            "rememberSaveable 的 lambda（组外）" to DEFERRED_SAVEABLE_OUTSIDE to listOf(
                "var open by rememberSaveable { mutableStateOf(" + PROBE + ") }",
                "SettingsGroup(title = \"x\") { item(key = \"a\") { Text(\"1\") } }",
            ),
            "item 体内的 onClick 回调（白名单外 ⇒ 有意判成延迟位的另一档，见类 KDoc 的朝紧声明）" to
                DEFERRED_ITEM_INSIDE to listOf(
                    "SettingsGroup(title = \"x\") {",
                    "    item(key = \"a\") {",
                    "        SettingsRow(title = \"t\", onClick = { " + PROBE + "() })",
                    "    }",
                    "}",
                ),
        )
        val offenders = mutableListOf<String>()
        val tally = linkedMapOf<String, Int>()
        for ((row, snippet) in rows) {
            val label = row.first
            val expected = row.second
            val text = snippet.joinToString("\n")
            val hit = text.indexOf(PROBE)
            if (hit < 0) {
                offenders.add(label + "：样本里没有「" + PROBE + "」这一行是空判据")
                continue
            }
            val actual = classify(sourceOf("样本", text), hit)
            tally[actual] = (tally[actual] ?: 0) + 1
            if (actual != expected) offenders.add(label + " 实到 " + actual + " ／ 写死 " + expected)
        }
        val book = listOf(
            IMMEDIATE_IN_GROUP to SAMPLE_IMMEDIATE_IN_GROUP,
            DEFERRED_REMEMBER_OUTSIDE to SAMPLE_REMEMBER_OUTSIDE,
            DEFERRED_ITEM_INSIDE to SAMPLE_ITEM_INSIDE,
            DEFERRED_REMEMBER_INSIDE to SAMPLE_REMEMBER_INSIDE,
            IMMEDIATE_OUTSIDE to SAMPLE_IMMEDIATE_OUTSIDE,
            DEFERRED_SAVEABLE_OUTSIDE to SAMPLE_SAVEABLE_OUTSIDE,
        ).mapNotNull { (slot, expected) ->
            val actual = tally[slot] ?: 0
            if (actual == expected) null else slot + " ×" + actual + " ／ 写死 ×" + expected
        }
        assertTrue(
            "判据 = 「命中点落在哪一枚 span 里」，不是「看起来在不在 lambda 里」：延迟位只认三枚**被调方形参" +
                "类型证得开**的尾随 lambda（item 的 content、remember、rememberSaveable），白名单之外一律判" +
                "「立即求值」（朝紧：误红可由人重判，误绿收不回来）。样本 " + rows.size +
                " 行 ／ 写死 " + SAMPLE_ROWS + " 行；分档账 实到 " +
                tally.entries.joinToString(" ／ ") { entry -> entry.key + " ×" + entry.value } +
                "；对不上的档：" + book.joinToString(" ／ ") + "\n不合的样本行：" +
                offenders.joinToString("\n"),
            offenders.isEmpty() && book.isEmpty() && rows.size == SAMPLE_ROWS,
        )
    }

    /**
     * ③ 落点本体（SettingsScreen.kt）：那一问现在只在延迟位里，组体直处一枚都不许有；正形与病形两把都钉。
     * 另钉分支本身「还在原位、但不再问桌面」，以及同仓那两枚先例的闸还在（本卡的包闸形状照第一枚抄的）。
     */
    @Test
    fun thePinQuestionNowSitsInARememberAndNeverAgainInTheGroupBody() {
        val screen = sourceOf(SETTINGS_SCREEN, readMain(SETTINGS_SCREEN))
        val hits = positions(screen.code, PIN_QUESTION)
        assertEquals(
            "全颗文件里「桌面让不让应用内一键添加」这一问不再是恰好 1 枚（口径 = 抹注释后；裸原文实测也是 " +
                "1 枚，因为 main 那段说明刻意没有逐字引用这个符号名 —— 它引用的是这两枚先例的文件名）。" +
                "**2 枚** = 有人又把这一问写回组体直处、或另起一处探测（先查下面那条落点判据）；" +
                "**0 枚** = 探测被摘了，那一组现在无条件念「不支持」：" + hint(screen, hits),
            1,
            hits.size,
        )
        val slot = classify(screen, hits.first())
        assertEquals(
            "这一问的落点不再是「组外的 remember」—— 它现在应当长在 " + PIN_PROBE_SHAPE + "（在 L" +
                lineOf(screen.code, hits.first()) + " 那一行里）这一档。读成「" + IMMEDIATE_IN_GROUP +
                "」就是本卡那条病本身：SettingsGroup 的 content 在 SettingsStack.kt:90 被 apply 当场跑，" +
                "而分组可见性要到 :104-105 才被读 ⇒ 停在别的分类、每次重组都要跨 binder 问一次桌面。" +
                "读成「" + DEFERRED_ITEM_INSIDE + "」也算红：那一问决定的是**注册哪几枚 item**，" +
                "挪进 item 正文里就注册不出来（甲档要求「首帧就知道能不能一键添加」的手感不变）：" +
                line(screen, hits.first()),
            DEFERRED_REMEMBER_OUTSIDE,
            slot,
        )
        val shapes = positions(screen.code, PIN_PROBE_SHAPE)
        assertEquals(
            "正形（包了闸的那一枚探测，逐字同 WidgetPinRow.kt 那一形）应当恰好 1 枚（实到 " + shapes.size +
                " 枚）—— 0 枚就是闸被摘了或换了写法（`.getOrElse`／`?.`／抽成函数），" +
                "摘闸要先把「个别 ROM 探测会抛 DeadObjectException」这条先例注释驳掉：",
            1,
            shapes.size,
        )
        val sick = Pair(occurrences(screen.code, SICK_SHAPE), occurrences(screen.raw, SICK_SHAPE))
        assertEquals(
            "病形反向针：组体直处那句「直接问桌面实例」的写法两栏都必须是 0 枚（抹注释栏 ／ 裸原文栏）。" +
                "裸原文也钉一枚，是因为 main 那段说明刻意没逐字引用它 —— 谁把病形只写进注释里，" +
                "这一格会先红，免得下一枚卡拿注释当「已经改过了」的证据：实到 " + sick,
            Pair(0, 0),
            sick,
        )
        val branch = positions(screen.code, BRANCH_SHAPE)
        assertEquals(
            "分支本身应当恰好 1 枚，而且它读的是那枚**已握住的** Boolean：" + hint(screen, branch),
            1,
            branch.size,
        )
        assertEquals(
            "分支那一行仍然长在组体直处 —— 这是「注册哪几枚 item」的决定点，甲档没打算改它（要改就得动 " +
                "item 的公开形参语义，卡面禁着）。它现在只读一枚 remember 的结果，所以每次组合那一趟 binder " +
                "已经没了 ⇒ 这一格钉的是「它还在原位、但它不再问桌面」：" + line(screen, branch.first()),
            IMMEDIATE_IN_GROUP,
            classify(screen, branch.first()),
        )
        val row = withoutComments(readMain(WIDGET_PIN_ROW))
        assertEquals(
            "WidgetPinRow 里那一枚包闸的探测不再是 1 枚 —— 它是本卡包闸形状的出处，" +
                "也是「唯独设置页这一格没包闸」那条结论的另一头（漂了就说明两头的形状都换了）：实到 " +
                occurrences(row, PIN_ROW_GATE) + " 枚",
            1,
            occurrences(row, PIN_ROW_GATE),
        )
        val bg = withoutComments(readMain(BACKGROUND_SYNC))
        assertEquals(
            "BackgroundSync 那枚不可抛的探测（hasAnyWidgetSafely = runCatching ＋ 按「有组件」兜底）不再是 " +
                "1 枚 —— 第二处先例；两处都漂了，本卡那条「设置页是唯一漏网」的账就得重判：实到 " +
                occurrences(bg, SAFE_WIDGET_PROBE) + " 枚",
            1,
            occurrences(bg, SAFE_WIDGET_PROBE),
        )
    }

    /**
     * ④ 别动手那一格（widgetCount）：卡面点名它不是本卡的靶（它的键永不变化那条账在册 #716），
     * 本卡一个字都没改它。这一格钉的是「它还在**双重延迟位**里」——
     * 谁照乙档那一型把它并进组体外的那一次探测，它就离开 item 正文、改读「组外的 remember」，本格当场红
     * （顺带把两枚结论绑成同一生命周期，那正是乙档的代价）；增减一枚 Provider 也红。
     */
    @Test
    fun theSixProviderCountProbeWasLeftWhereItWasAndStillPaysOnlyWhenTheCellRenders() {
        val screen = sourceOf(SETTINGS_SCREEN, readMain(SETTINGS_SCREEN))
        val heads = positions(screen.code, WIDGET_COUNT_HEAD)
        assertEquals(
            "「桌面现在有几枚组件」那一格的起手（remember(widgetManager) 的清单）不再是 1 枚 —— " +
                "本卡没动它，多一枚就是有人另起了第二份 Provider 清单（六枚 Provider 那笔账要重算）：" +
                hint(screen, heads),
            1,
            heads.size,
        )
        val probes = positions(screen.code, WIDGET_COUNT_PROBE)
        assertEquals(
            "getAppWidgetIds 的调用点不再是 1 枚（那一格里六枚 Provider 走同一枚 sumOf）：" + hint(screen, probes),
            1,
            probes.size,
        )
        assertEquals(
            "那一问的落点不再是「item 体内的 remember」—— 它今天**双重延迟**（item 正文 ＋ remember），" +
                "所以只在 WIDGET 分类真渲染时才付六趟。挪成「组外的 remember」就是本卡第 ⓪ 问的乙档" +
                "（两枚结论同生命周期、桌面中途被杀不再自纠），卡面禁用面不许；" +
                "挪成「" + IMMEDIATE_IN_GROUP + "」就是每次重组六趟 binder 的新病：" + line(screen, probes.first()),
            DEFERRED_REMEMBER_INSIDE,
            classify(screen, probes.first()),
        )
        val providers = occurrences(spanText(screen, WIDGET_COUNT_HEAD), CLASS_REF)
        assertEquals(
            "那份清单里的 Provider 枚数不再是 6（今日盘面：Today／Tomorrow／Week／WeekGrid／NextClass／TwoDay）" +
                "—— 加一枚减一枚都要先回编排者裁（本卡不许动这一格的行为）。" +
                "口径 = 从那一格最里层的延迟位 span 切出的正文里数「" + CLASS_REF + "」：实到 " + providers + " 枚",
            6,
            providers,
        )
    }

    /**
     * ⑤ 覆盖面（全树组体直处）：以后任何人都不许把跨进程／设备侧的问句写在组体直处。
     * 这一格是「零枚」的绝对判据，配三把「仪器没瞎」的凭据尺（扫到的 .kt 枚数下限 ＋ 全树组数 = 15
     * ＋ 每颗文件的「调用点枚数 == 切出的 span 枚数」，后者专门抓具名实参 `content = { … }` 那种绕过刀口的写法）。
     */
    @Test
    fun noGroupBodyAnywhereInMainAsksTheLauncherOrTheClockOnEveryComposition() {
        val tree = mainTree()
        assertTrue(
            "扫到的 .kt 少于下限 " + MAIN_KT_FLOOR + " 枚（实到 " + tree.size + " 枚）：路径不对 ⇒ " +
                "下面那句「0 枚病枚」就只是扫描器读空的另一种写法，不许当绿",
            tree.size >= MAIN_KT_FLOOR,
        )
        val callTexts = tree.sumOf { item -> occurrences(item.code, GROUP_CALL) }
        val declarations = tree.sumOf { item -> occurrences(item.code, GROUP_DECL) }
        val spans = tree.sumOf { item -> item.groupSpans.size }
        val blind = tree
            .filter { item -> item.callTexts - item.declarations != item.groupSpans.size }
            .joinToString(" ／ ") { item -> item.relative + "(" + (item.callTexts - item.declarations) + "≠" + item.groupSpans.size + ")" }
        assertEquals(
            "全树 SettingsGroup 的**调用点**枚数不再是 15（SettingsScreen 14 ＋ ImportScreen 1；另有 1 枚是 " +
                "SettingsStack.kt 里的定义，由「fun SettingsGroup(」那一把扣掉）。实到：调用点文本 " + callTexts +
                " − 定义 " + declarations + " = " + (callTexts - declarations) + " 枚，切出来的组体 span " + spans +
                " 枚 —— 两把不等时红的是「切不出来的写法」（例如把 content 写成 content = { … } 具名实参），" +
                "那种写法会绕过本仪器的刀口，而本卡那格判据就看不见它了。切不齐的文件：" + blind,
            Pair(GROUP_CALL_SITES, GROUP_CALL_SITES),
            Pair(callTexts - declarations, spans),
        )
        val offenders = mutableListOf<String>()
        for (item in tree) {
            if (item.groupSpans.isEmpty()) continue
            for (question in DEVICE_QUESTIONS) {
                for (hit in positions(item.code, question)) {
                    if (classify(item, hit) == IMMEDIATE_IN_GROUP) {
                        offenders.add(item.relative + line(item, hit) + " ［问句=" + question + "］")
                    }
                }
            }
        }
        assertTrue(
            "组体直处（每次组合当场跑、且不受 visibleWhen 保护）里现在一枚跨进程／设备侧的问句都不许有。" +
                "T407 之前盘上是 1 枚（设置页那枚「桌面让不让一键添加」，在册 #295 拆出来的那一档），" +
                "挪进 remember 之后归零 ⇒ 本格钉的是「归零」这个状态，问句清单 " + DEVICE_QUESTIONS.size +
                " 枚针。红了的处置是**再挪一枚进延迟位**，不是把本格的期望值改成 1：" +
                (if (offenders.isEmpty()) "（现测 0 枚）" else "\n" + offenders.joinToString("\n")),
            offenders.isEmpty(),
        )
    }

    /**
     * ⑥ 本轮新增的覆盖面（卡面点名别省）：`item(key = …, visible = …)` 的两枚形参是非 composable、
     * **即刻求值** ⇒ 往 `visible =` 里塞取数 = 每次组合都跑，而且和组体直处一样不受 visibleWhen 保护。
     * 盘面账（抹注释后 22 枚、裸原文 23 枚，差的那一枚是 SettingsStack.kt:239 的 KDoc 散文）逐枚点名：
     * 长在 `item(` 调用行上 7 枚 ＋ 别处（AnimatedVisibility 等）15 枚，逐枚过白名单、**零枚真取数**。
     * 尺子三把：白名单（形状）＋ 逐字在册清单两栏（谁新增都得回来抄读数）＋ 两栏差 1 枚（防「只查逐字节
     * 相等」那种「看着对但会误导」的放行，也防有人删注释当修尺）。
     */
    @Test
    fun everyVisibleArgumentInMainIsAHeldReadingAndNoneOfThemFetchesAnything() {
        val tree = mainTree()
        val itemRows = mutableListOf<String>()
        val otherRows = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        val wrongSlot = mutableListOf<String>()
        val drifters = mutableListOf<String>()
        var rawTotal = 0
        var strippedTotal = 0
        for (item in tree) {
            val raw = occurrences(item.raw, VISIBLE_ARG)
            val stripped = occurrences(item.code, VISIBLE_ARG)
            rawTotal += raw
            strippedTotal += stripped
            if (raw != stripped) drifters.add(item.relative + "[裸 " + raw + " ／ 抹 " + stripped + "]")
            for (hit in positions(item.code, VISIBLE_ARG)) {
                val hitLine = lineText(item.code, hit)
                val row = item.relative + "|" + hitLine
                val onItem = hitLine.startsWith("item(")
                if (onItem) itemRows.add(row) else otherRows.add(row)
                val expression = visibleExpression(hitLine)
                if (!HELD_READING.matches(expression)) {
                    fetched.add(
                        item.relative + " L" + lineOf(item.code, hit) + " 表达式「" + expression +
                            "」不在白名单里",
                    )
                }
                for (question in DEVICE_QUESTIONS) {
                    if (question in expression) {
                        fetched.add(item.relative + " L" + lineOf(item.code, hit) + " 把问句「" + question + "」写进了实参")
                    }
                }
                if (onItem && classify(item, hit) != IMMEDIATE_IN_GROUP) {
                    wrongSlot.add(row + " 落点 = " + classify(item, hit))
                }
            }
        }
        itemRows.sort()
        otherRows.sort()
        assertEquals(
            "全树「抹注释后」的 visible = 枚数不再是 22（T407 ⓪ 步复算：编排者量的 22 枚是抹注释那一栏，" +
                "裸 grep 那把念 23 枚，多的那一枚在 SettingsStack.kt:239 那行 KDoc 里 —— 数出现次数的尺" +
                "不抹注释就会把它当成一条真判据）。**多一枚** = 新增了一条可见性判据，先问它取不取数" +
                "（白名单那把跟着判）；**少一枚** = 有人换了写法（例如 visible=foo），字面判据跟不上了：" +
                "实到 抹 " + strippedTotal + " ／ 裸 " + rawTotal + " 枚",
            Pair(VISIBLE_STRIPPED_TOTAL, VISIBLE_RAW_TOTAL),
            Pair(strippedTotal, rawTotal),
        )
        assertEquals(
            "长在 item(key = …, visible = …) 调用行上的枚数不再是 7（ImportScreen 3 ＋ SettingsScreen 4）——" +
                "这一档才是本轮新增覆盖面钉的那一格。实到 " + itemRows.size + " 枚，差集 " +
                symmetricDiff(itemRows, ITEM_VISIBLE_ROWS) + "\n" + itemRows.joinToString("\n"),
            VISIBLE_ON_ITEM_SITES,
            itemRows.size,
        )
        assertEquals(
            "item 那 7 枚的逐字在册清单漂了（左＝在册、右＝盘上实到）。新增一枚的处置是把它的原文抄进册子" +
                "并逐枚问「它取不取数」，**不是放宽白名单**：\n在册 " + ITEM_VISIBLE_ROWS.joinToString(" ／ ") +
                "\n实到 " + itemRows.joinToString(" ／ "),
            ITEM_VISIBLE_ROWS,
            itemRows,
        )
        assertEquals(
            "别处（AnimatedVisibility 与同类尾形参）那 15 枚的逐字在册清单漂了 —— 这一把是 ⑥ 的**凭据尺**：" +
                "22 = 7 ＋ 15。只钉 7 那一把的话，扫描器少扫一颗文件也能给出「7 枚全对」：" +
                symmetricDiff(otherRows, OTHER_VISIBLE_ROWS) + "\n实到 " + otherRows.joinToString(" ／ "),
            OTHER_VISIBLE_ROWS,
            otherRows,
        )
        assertTrue(
            "白名单（判据形状）：visible 实参只许是**已握住的读数** —— 标识符／成员链／与字面量或另一枚读数" +
                "的比较（== != > >= < <=）／&&·|| 的组合／.isEmpty()·.isNotEmpty() 这两枚无参谓词／! 前缀。" +
                "真取数的形状（getInstance／getSystemService／getAppWidgetIds／checkSelfPermission／now()／" +
                "get*…）一枚都不许进实参：它在 SettingsStack.kt:90 那句 apply 里就被求值，每次组合付一次，" +
                "而且和组体直处一样不受 visibleWhen 保护。另附落点尺：这 7 枚必须逐枚判成「" +
                IMMEDIATE_IN_GROUP + "」（仪器把 item 圆括号里的实参认成即刻求值位，正是本卡 ③ 那格要的形状）" +
                "：\n病枚：" + (fetched.joinToString(" ／ ") + " ／ 落点不对：" + wrongSlot.joinToString(" ／ ")),
            fetched.isEmpty() && wrongSlot.isEmpty(),
        )
        assertEquals(
            "两栏合流＝证据没了（要么那句 KDoc 散文被删了 —— 那是在删证据不是在修尺；要么这把尺改成了读" +
                "裸原文 —— 那它就在数注释里的字）。在册差 1 枚的唯一来源：实到 " +
                drifters.joinToString(" ／ ") + "（写死 " + VISIBLE_DRIFT_FILE + "）",
            listOf(VISIBLE_DRIFT_FILE),
            drifters.sorted(),
        )
    }

    // ---- 读盘 ---------------------------------------------------------------------

    private fun readMain(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainTree(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(root.path + " 下一枚 .kt 都没扫到，是路径不对不是绿", files.isNotEmpty())
        return files.map { file -> sourceOf(file.relativeTo(root).path.replace(File.separatorChar, '/'), file.readText()) }
            .sortedBy { source -> source.relative }
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { path -> File(dir, path) }
                .firstOrNull { candidate -> candidate.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 " + File("").absolutePath)
    }

    // ---- 报错用的位置账 -------------------------------------------------------------

    private fun line(source: Source, hit: Int): String =
        " L" + lineOf(source.code, hit) + ": " + lineText(source.code, hit)

    private fun hint(source: Source, hits: List<Int>): String {
        if (hits.isEmpty()) return "（实到 0 枚）"
        return "\n" + hits.joinToString("") { hit -> line(source, hit) + "\n" }
    }

    /**
     * 从 [needle] 那一处起，交回**包着它的最里层延迟位**的正文（切不出来就抛：静默跳过等于没有守卫）。
     * needle 落在延迟位的开口之外时，取到的是再外一层（本卡那一格用的是 item 正文，够了）。
     */
    private fun spanText(source: Source, needle: String): String {
        val at = source.code.indexOf(needle)
        check(at >= 0) { "盘上读不到「" + needle + "」：那一格换了写法，本守卫要跟着改口径" }
        val slot = source.deferredSpans.filter { span -> span.covers(at) }.minByOrNull { span -> span.width }
        check(slot != null) { "「" + needle + "」不在任何延迟位里：切不出正文" }
        return source.code.substring(slot.from, slot.to)
    }

    /** 差集（两边各念一遍）：只念「实到清单」的话，看的人得自己心算谁多了谁少了哪一枚。 */
    private fun symmetricDiff(actual: List<String>, booked: List<String>): String {
        val added = actual.filter { row -> row !in booked }
        val missing = booked.filter { row -> row !in actual }
        return "新增 " + added.size + " 枚" + added.joinToString("／") { row -> "［" + row + "］" } +
            " ／ 少 " + missing.size + " 枚" + missing.joinToString("／") { row -> "［" + row + "］" }
    }
}

// ---- 仪器（文件顶层：纯文本刀法，测试类与它的样本行共用同一把尺）-----------------------

/** 一文件一枚：原文栏（散文顶得动它）与抹注释栏（尺子读它）都带上，⑤⑥ 两格两栏都要念。 */
private class Source(
    val relative: String,
    val raw: String,
    val code: String,
) {
    val groupSpans: List<Slot> = spansOf(code, GROUP)
    val deferredSpans: List<Slot> = DEFERRED_OPENERS.flatMap { opener -> spansOf(code, opener) }
    val callTexts: Int = occurrences(code, GROUP_CALL)
    val declarations: Int = occurrences(code, GROUP_DECL)
}

private class Slot(val opener: String, val from: Int, val to: Int) {
    fun covers(at: Int): Boolean = at in from..to
    val width: Int get() = to - from
}

/**
 * 判据本体（类 KDoc 的 ② 那一格把它逐档钉在合成样本上）：
 * 标签 = 「命中点最里层的延迟位（没有就写立即求值）」＋「在不在某一枚 SettingsGroup 的组体内」。
 */
private fun classify(source: Source, hit: Int): String {
    val inner = source.deferredSpans.filter { slot -> slot.covers(hit) }.minByOrNull { slot -> slot.width }
    val slot = when (inner?.opener) {
        null -> LABEL_IMMEDIATE
        ITEM_OPENER -> LABEL_ITEM
        REMEMBER_OPENER -> LABEL_REMEMBER
        SAVEABLE_OPENER -> LABEL_SAVEABLE
        else -> LABEL_ITEM
    }
    val place = if (source.groupSpans.any { group -> group.covers(hit) }) PLACE_INSIDE else PLACE_OUTSIDE
    return slot + "(" + place + ")"
}

private fun sourceOf(relative: String, text: String): Source = Source(relative, text, withoutComments(text))

/**
 * 只抹注释、保留字符串字面量、长度与换行位置不变（行号因此仍然可信）。
 * 走 StringBuilder 而不是「先把源码摊成字符数组、改写、再物化回字符串」那一族写法：那一种正被
 * `CommentStrippingHelperShapeGuardTest` 按枚数钉着，本卡不该同批去动别人那四枚写死值。
 */
private fun withoutComments(source: String): String {
    val builder = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        when {
            source.startsWith("//", i) -> {
                var j = i
                while (j < source.length && source[j] != '\n') j++
                repeat(j - i) { builder.append(' ') }
                i = j
            }

            source.startsWith("/*", i) -> {
                var depth = 1
                var j = i + 2
                while (j < source.length && depth > 0) {
                    when {
                        source.startsWith("/*", j) -> { depth++; j += 2 }
                        source.startsWith("*/", j) -> { depth--; j += 2 }
                        source.startsWith(TRIPLE_QUOTE, j) -> {
                            val end = source.indexOf(TRIPLE_QUOTE, j + 3)
                            j = if (end < 0) source.length else end + 3
                        }

                        source[j] == '"' || source[j] == '\'' -> j = skipLiteral(source, j)
                        else -> j++
                    }
                }
                for (k in i until minOf(j, source.length)) builder.append(if (source[k] == '\n') '\n' else ' ')
                i = j
            }

            source.startsWith(TRIPLE_QUOTE, i) -> {
                val end = source.indexOf(TRIPLE_QUOTE, i + 3)
                val stop = if (end < 0) source.length else end + 3
                builder.append(source, i, stop)
                i = stop
            }

            source[i] == '"' || source[i] == '\'' -> {
                val stop = skipLiteral(source, i)
                builder.append(source, i, stop)
                i = stop
            }

            else -> {
                builder.append(source[i])
                i++
            }
        }
    }
    return builder.toString()
}

/** 字符串／字符字面量的收尾下标（含闭引号）；未闭合就退回那一行的行首，绝不吞掉后面的整段代码。 */
private fun skipLiteral(source: String, at: Int): Int {
    if (source.startsWith(TRIPLE_QUOTE, at)) {
        val end = source.indexOf(TRIPLE_QUOTE, at + 3)
        return if (end < 0) source.length else end + 3
    }
    val quote = source[at]
    var j = at + 1
    while (j < source.length) {
        when {
            source[j] == '\\' -> j += 2
            source[j] == quote -> return j + 1
            source[j] == '\n' -> return j
            else -> j++
        }
    }
    return source.length
}

/** [at] 处必须是 [open]，交回配平后的 [close] 下标（跳过字面量里的括号）；配不平交回 -1。 */
private fun balance(text: String, at: Int, open: Char, close: Char): Int {
    if (at >= text.length || text[at] != open) return -1
    var depth = 0
    var i = at
    while (i < text.length) {
        val c = text[i]
        if (c == '"' || c == '\'') {
            i = skipLiteral(text, i)
            continue
        }
        if (c == open) {
            depth++
        } else if (c == close) {
            depth--
            if (depth == 0) return i
        }
        i++
    }
    return -1
}

/**
 * 找出 [opener] 每一次**调用**（跳过 `fun item(` 那种定义、跳过 `SettingsGroupItem(` 那种前缀粘连）
 * 之后的尾随 `{ … }` 区间。拿不到尾随花括号的（`content = { … }` 那种具名实参）不产出 span，
 * 由 ⑤ 那格「调用点枚数 == 切出的 span 枚数」那一把听见，不留成静默漏判。
 */
private fun spansOf(text: String, opener: String): List<Slot> {
    val spans = mutableListOf<Slot>()
    var from = 0
    while (true) {
        val at = text.indexOf(opener, from)
        if (at < 0) return spans
        from = at + opener.length
        val before = if (at == 0) ' ' else text[at - 1]
        if (before.isLetterOrDigit() || before == '_' || before == '$') continue
        if (at >= 4 && text.substring(at - 4, at) == "fun ") continue
        var i = at + opener.length
        if (i < text.length && text[i] == '(') {
            val close = balance(text, i, '(', ')')
            if (close < 0) continue
            i = close + 1
        }
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '{') continue
        val end = balance(text, i, '{', '}')
        if (end < 0) continue
        spans.add(Slot(opener, i, end))
    }
}

/**
 * `visible = ` 后面那一段实参：按括号深度走到同层的 `,` 或收尾的 `)` 为止。
 * 拿「同一行剩下的全部」当实参会把 `) {` 一起收进来，白名单就会误红（T407 ⓪ 步撞到的形状）。
 */
private fun visibleExpression(lineText: String): String {
    val builder = StringBuilder()
    var depth = 1
    for (c in lineText.substringAfter(VISIBLE_ARG)) {
        when (c) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) break
            }

            ',' -> if (depth == 1) break
        }
        builder.append(c)
    }
    return builder.toString().trim()
}

private fun occurrences(text: String, needle: String): Int = positions(text, needle).size

private fun positions(text: String, needle: String): List<Int> {
    val hits = mutableListOf<Int>()
    var from = 0
    while (true) {
        val at = text.indexOf(needle, from)
        if (at < 0) return hits
        hits.add(at)
        from = at + needle.length
    }
}

private fun lineOf(source: String, hit: Int): Int = source.substring(0, hit).count { it == '\n' } + 1

/** 一行的原文（去首尾空白）：⑥ 拿它认「这是不是 item( 调用行」，所以不压掉行中间的空格。 */
private fun lineText(source: String, hit: Int): String {
    val start = source.lastIndexOf('\n', hit).let { if (it < 0) 0 else it + 1 }
    val end = source.indexOf('\n', hit).let { if (it < 0) source.length else it }
    return source.substring(start, end).trim()
}

// ---- 在册常量 ---------------------------------------------------------------------

private const val SETTINGS_STACK = "com/buaa/schedule/core/designsystem/SettingsStack.kt"
private const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
private const val WIDGET_PIN_ROW = "com/buaa/schedule/ui/settings/WidgetPinRow.kt"
private const val BACKGROUND_SYNC = "com/buaa/schedule/widget/BackgroundSync.kt"

// ① 机制本体的针（逐字来自盘面，别改成 >=）
private const val CONTENT_PARAM = "content: SettingsGroupScope.() -> Unit,"
private const val ANNOTATED_CONTENT_PARAM = "content: @Composable SettingsGroupScope.() -> Unit,"
private const val APPLY_CONTENT = "SettingsGroupScope()." + "apply(content)"
private const val VISIBLE_WHEN_PARAM = "visibleWhen: Boolean = true,"
private const val ANIMATED_READS_VISIBLE_WHEN = "visible = visibleWhen,"
private const val EARLY_RETURN_ON_EMPTY = "if (scope.items.isEmpty()) return"
private const val VISIBLE_COUNT_OF_ITEMS = "val visibleCount = scope.items.count { it.visible }"
private const val EARLY_RETURN_ON_ZERO = "if (visibleCount == 0) return"
private const val ITEM_KEY_PARAM = "key: Any? = null,"
private const val ITEM_VISIBLE_PARAM = "visible: Boolean = true,"
private const val ITEM_CONTENT_PARAM = "content: @Composable () -> Unit,"
private const val ITEM_CONTENT_INVOKED = "item.content()"
private const val ITEM_INVISIBLE_READ = "visible = item.visible"

// ② 判据标签（机器读的形状，③⑤⑥ 三格都拿它当尺）
private const val LABEL_IMMEDIATE = "立即求值"
private const val LABEL_ITEM = "延迟位:item内容"
private const val LABEL_REMEMBER = "延迟位:remember"
private const val LABEL_SAVEABLE = "延迟位:rememberSaveable"
private const val PLACE_INSIDE = "组内"
private const val PLACE_OUTSIDE = "组外"
private const val IMMEDIATE_IN_GROUP = LABEL_IMMEDIATE + "(" + PLACE_INSIDE + ")"
private const val IMMEDIATE_OUTSIDE = LABEL_IMMEDIATE + "(" + PLACE_OUTSIDE + ")"
private const val DEFERRED_ITEM_INSIDE = LABEL_ITEM + "(" + PLACE_INSIDE + ")"
private const val DEFERRED_REMEMBER_INSIDE = LABEL_REMEMBER + "(" + PLACE_INSIDE + ")"
private const val DEFERRED_REMEMBER_OUTSIDE = LABEL_REMEMBER + "(" + PLACE_OUTSIDE + ")"
private const val DEFERRED_SAVEABLE_OUTSIDE = LABEL_SAVEABLE + "(" + PLACE_OUTSIDE + ")"

private const val GROUP = "SettingsGroup"
private const val GROUP_CALL = GROUP + "("
private const val GROUP_DECL = "fun SettingsGroup("
private const val ITEM_OPENER = "item"
private const val REMEMBER_OPENER = "remember"
private const val SAVEABLE_OPENER = "rememberSaveable"
private val DEFERRED_OPENERS = listOf(ITEM_OPENER, REMEMBER_OPENER, SAVEABLE_OPENER)

/** ② 的样本账：十行、六档各几行（写了就得对上，样本被删就红在覆盖面这一把上） */
private const val SAMPLE_ROWS = 10
private const val SAMPLE_IMMEDIATE_IN_GROUP = 3
private const val SAMPLE_REMEMBER_OUTSIDE = 1
private const val SAMPLE_ITEM_INSIDE = 2
private const val SAMPLE_REMEMBER_INSIDE = 1
private const val SAMPLE_IMMEDIATE_OUTSIDE = 2
private const val SAMPLE_SAVEABLE_OUTSIDE = 1

/** ② 样本里那一枚假问句的名字：它不进任何真尺，只用来让仪器有话可分类。 */
private const val PROBE = "askLauncher"

// ③ 落点本体的针（拆开写，本文件的散文不许被任何一把尺数进册子）
private const val PIN_QUESTION = "isRequestPin" + "AppWidgetSupported"
private const val PIN_PROBE_SHAPE = "runCatching { widgetManager." + PIN_QUESTION + " }.getOrDefault(false)"
private const val SICK_SHAPE = "if (widgetManager."
private const val BRANCH_SHAPE = "if (pinSupported) {"
private const val PIN_ROW_GATE = "runCatching { AppWidgetManager.getInstance(context)." + PIN_QUESTION + " }"
private const val SAFE_WIDGET_PROBE = "fun hasAnyWidgetSafely(context: Context): Boolean ="

// ④ 别动手那一格
private const val WIDGET_COUNT_HEAD = "val widgetCount = remember(widgetManager) {"
private const val WIDGET_COUNT_PROBE = "getAppWidgetIds"
private const val CLASS_REF = "::class.java"

/** ⑤ 全树组体直处的问句清单（T407 ⓪ 步现量：除本卡那一枚外，逐枚都已在延迟位里） */
private val DEVICE_QUESTIONS = listOf(
    PIN_QUESTION,
    "AppWidget" + "Manager",
    "getAppWidgetIds",
    "requestPinAppWidget",
    "hasAnyWidget",
    "getInstance",
    "getSystemService",
    "getSharedPreferences",
    "checkSelfPermission",
    "getBoolean",
    "getString(",
    "contentResolver",
    "LocalDate." + "now",
    "System." + "currentTimeMillis",
    "canScheduleExactAlarms",
    "packageManager",
    "getRunningServices",
)
private const val GROUP_CALL_SITES = 15
private const val MAIN_KT_FLOOR = 260
private const val TRIPLE_QUOTE = "\"\"\""

// ⑥ 的在册账（口径 = 抹注释后；裸原文那一栏一起钉，两栏合流就是证据没了）
private const val VISIBLE_ARG = "visible = "
private const val VISIBLE_STRIPPED_TOTAL = 22
private const val VISIBLE_RAW_TOTAL = 23
private const val VISIBLE_ON_ITEM_SITES = 7
private const val VISIBLE_DRIFT_FILE = "com/buaa/schedule/core/designsystem/SettingsStack.kt[裸 4 ／ 抹 3]"
private val ITEM_VISIBLE_ROWS = listOf(
    "com/buaa/schedule/ui/importing/ImportScreen.kt|item(key = \"icsInput\", visible = expandedSource == SOURCE_ICS) {",
    "com/buaa/schedule/ui/importing/ImportScreen.kt|item(key = \"shareInput\", visible = expandedSource == SOURCE_SHARE) {",
    "com/buaa/schedule/ui/importing/ImportScreen.kt|item(key = \"textInput\", visible = expandedSource == SOURCE_TEXT) {",
    "com/buaa/schedule/ui/settings/SettingsScreen.kt|item(key = \"iclass_logout\", visible = iclassSignedIn) {",
    "com/buaa/schedule/ui/settings/SettingsScreen.kt|item(key = \"multiSemester\", visible = allSemesters.size > 1) {",
    "com/buaa/schedule/ui/settings/SettingsScreen.kt|item(key = \"panelBlur\", visible = glassTier >= DesignTokens.GLASS_TIER_STANDARD) {",
    "com/buaa/schedule/ui/settings/SettingsScreen.kt|item(key = \"status\", visible = calendarSync.message != null) {",
)
private val OTHER_VISIBLE_ROWS = listOf(
    "com/buaa/schedule/core/designsystem/ModalTransition.kt|visible = open,",
    "com/buaa/schedule/core/designsystem/SettingsStack.kt|visible = isOpen,",
    "com/buaa/schedule/core/designsystem/SettingsStack.kt|visible = item.visible,",
    "com/buaa/schedule/core/designsystem/SettingsStack.kt|visible = visibleWhen,",
    "com/buaa/schedule/ui/home/DayView.kt|visible = isToday,",
    "com/buaa/schedule/ui/home/HomeScreen.kt|visible = menuOpen,",
    "com/buaa/schedule/ui/home/HomeScreen.kt|visible = selectedTab == 0 && weekChangeSummary != null,",
    "com/buaa/schedule/ui/home/HomeScreen.kt|visible = showFirstRunEmpty,",
    "com/buaa/schedule/ui/home/HomeScreen.kt|visible = showTimeModeToggle,",
    "com/buaa/schedule/ui/home/HomeScreen.kt|visible = showWeekNav,",
    "com/buaa/schedule/ui/home/HomeScreen.kt|visible = state.conflicts.isNotEmpty(),",
    "com/buaa/schedule/ui/home/WeekView.kt|visible = menuFor != null,",
    "com/buaa/schedule/ui/home/WeekView.kt|visible = visible,",
    "com/buaa/schedule/ui/home/WeekView.kt|visible = weekForContent == currentWeek,",
    "com/buaa/schedule/ui/home/WeekView.kt|visible = weekForContent == currentWeek,",
)

/** 白名单：已握住的读数（标识符／成员链／比较／&&·|| 组合／两枚无参谓词／! 前缀）。 */
private const val MEMBER_CHAIN = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"
private const val HELD_SIDE = MEMBER_CHAIN + "(\\.is(Not)?Empty\\(\\))?"
private const val HELD_RELATION =
    "(\\s*(==|!=|>=|<=|>|<)\\s*(null|true|false|-?[0-9]+|" + HELD_SIDE + "|\"[^\"]*\"))?"
private val HELD_READING =
    Regex("!?" + HELD_SIDE + HELD_RELATION + "(\\s(&&|\\|\\|)\\s(!?" + HELD_SIDE + HELD_RELATION + "))*")
