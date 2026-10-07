package com.buaa.schedule.update

import com.buaa.schedule.domain.update.UpdateCheckVerdict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T261④：把「检查更新」三处用户侧静默的**接线形状**钉成静态守卫（台账 #501 / #367 / #368）。
 *
 * ## 钉法点名（卡面 §4 的四种里用哪一种）
 *
 * 主尺 = **读形状**（抹注释 → 花括号配平取 `admit` / `present` 的 when 块 → 比索引顺序）。
 * 本卡的病灶不是某一行写错，而是**先后与归属**：隐私那一手排在手动判据之前就是彻底的 no-op，
 * 「被忽略」那一格排在新版本之后就会被提示重新吃掉。逐字节比对只能钉住"这一行今天长这样"，
 * 把顺序换一换、每一行逐字节都对得上，缺陷就整块回来 ⇒ 只有索引顺序与块归属钉得住。
 *
 * 辅钉三把，点名如下：
 * - **抹注释后数出现次数**：`KEY_SKIPPED` 的写与清除成对（改前清除是 0 枚）、
 *   结果档两枚各写一次、`Log.i` / `Log.w` 的落点枚数、三枚新函数各一枚定义加一处 UI 读者。
 * - **逐字节比对**：`Blocked` 那枚状态声明、`is Blocked -> manual`、
 *   `privacyAccepted = FirstRun.privacyAccepted(context),`、内核里那三句用户听到的话。
 * - **抹注释找锚点**（反套套逻辑那一对）：改前那道隐私 `return` 在**代码里 0 处**、
 *   在**含注释的原文里恰好 1 处**（KDoc 逐字留着做取证）。只查 0 那一半就成了
 *   「把取证删干净也算绿」，所以两半必须同时成对复算。
 *
 * ## 至少一枚要拦住「隐私闸又排到 force 之前」
 *
 * 第 ① 层：`UpdateCheck.kt` 里那道改前 `return` 代码 0 处 + 取证 1 处，且
 * `FirstRun.privacyAccepted(context)` 只许出现在 `admit(` 的实参里（一枚具名参数）；
 * 第 ② 层：内核 `admit` 块里「隐私那一档」与「手动那一手」必须**同格**
 * （`BlockedByPrivacy(trigger, …)` 带 trigger）——把档位写成不带 trigger 的常量静默，
 * 逐字节每一行还在，本层当场红。两层互为备份，谁单独被绕过去另一层还红。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读钟）。输入面：本守卫读 `src/main/java`
 * 下的工作树文本，已由 `app/build.gradle.kts` 里守卫任务整体声明成 `testDebugUnitTest` 的输入。
 * ⚠️ 未装机：三处修完之后屏上那三句话只由判据单测与本守卫的锚点核对背书，零 adb 读数。
 */
class UpdateCheckSilentBranchGuardTest {

    private companion object {
        const val GATE = "com/buaa/schedule/domain/update/UpdateCheckGate.kt"
        const val CHECK = "com/buaa/schedule/update/UpdateCheck.kt"
        const val DIALOG = "com/buaa/schedule/update/UpdateDialog.kt"
        const val SETTINGS = "com/buaa/schedule/ui/settings/SettingsScreen.kt"

        // ── ① 调用点：隐私是输入，不是一道 return ──
        const val OLD_PRIVACY_GATE = "if (!FirstRun.privacyAccepted(context)) return _state.value"
        const val PRIVACY_AS_INPUT = "privacyAccepted = FirstRun.privacyAccepted(context),"
        const val ADMIT_CALL = "val admission = UpdateCheckGate.admit("
        const val ADMISSION_GATE = "if (admission !is UpdateCheckVerdict.RunCheck) {"
        const val PRESENT_CALL = "val verdict = UpdateCheckGate.present("

        // ── ② 内核：规则锚点（顺序即语义） ──
        const val RULE_PRIVACY = "!privacyAccepted ->"
        const val RULE_THROTTLE = "!throttleAllowsCheck ->"
        const val RULE_RUN = "UpdateCheckVerdict.RunCheck(trigger)"
        const val RULE_IGNORE = "newestAboveInstalled == ignoredVersion ->"
        const val RULE_NEW = "UpdateCheckVerdict.NewVersionFound(trigger, newestAboveInstalled)"
        const val RULE_FAILURE = "failureReason != null ->"
        const val RULE_NOTHING = "newestAboveInstalled == null ->"
        const val ADMIT_BODY = "): UpdateCheckVerdict = when {"
        const val ANNOUNCE_PRIVACY = "is BlockedByPrivacy -> trigger == UpdateCheckTrigger.Manual"
        const val ANNOUNCE_IGNORE = "is SuppressedByIgnore -> trigger == UpdateCheckTrigger.Manual"
        const val ANNOUNCE_THROTTLE = "is HeldByThrottle -> trigger == UpdateCheckTrigger.Manual"
        const val TIERS = " : UpdateCheckVerdict"

        /** 判据本体的纯度口径（本仓明文：这些只许在调用点算好当参数递进来） */
        val FORBIDDEN_READS = listOf(
            "System.currentTimeMillis",
            "currentTimeMillis",
            "LocalDate.now",
            "Instant.now",
            "ZoneId",
            "TimeZone",
            "Build.VERSION",
            "SharedPreferences",
            "getSharedPreferences",
            "FirstRun",
            "Context",
            "java.net",
            "Log.",
        )

        // ── ③ 忽略那扇门：写 ↔ 清除成对，回读有 UI 读者 ──
        const val SKIP_WRITE = "putString(KEY_SKIPPED, info.version)"
        const val SKIP_CLEAR = "store.edit { remove(KEY_SKIPPED) }"
        const val SKIP_READ = "ignoredVersion = store.getString(KEY_SKIPPED, null),"
        const val CLEAR_FUN = "fun restoreIgnored(context: Context): String? {"
        const val READ_FUN = "fun ignoredVersion(context: Context): String? {"
        const val OUTCOME_FUN = "fun lastOutcome(context: Context): UpdateLastOutcome"
        const val SETTINGS_CLEAR_READ = "UpdateCheck.restoreIgnored(context)"
        const val SETTINGS_READ_BACK = "mutableStateOf(UpdateCheck.ignoredVersion(context))"
        const val SETTINGS_UNDO_ROW = "title = \"恢复更新提醒\","
        const val SETTINGS_UNDO_SUMMARY = "summary = \"已忽略 v\$ignoredUpdate · 点按撤销忽略并立刻重新检查一次\","

        // ── ④ 结果档：两档各落一次，那一行只有一处读者 ──
        const val OUTCOME_KEY = "private const val KEY_LAST_OUTCOME = \"update_last_outcome\""
        const val OUTCOME_FAIL_WRITE = "putString(KEY_LAST_OUTCOME, OUTCOME_FAILED)"
        const val OUTCOME_OK_WRITE = "putString(KEY_LAST_OUTCOME, OUTCOME_SUCCEEDED)"
        const val SETTINGS_RESTING_READ =
            "else -> UpdateCheckGate.restingLine(UpdateCheck.lastOutcome(context), last)"
        const val OLD_SUBTITLE = "\"每天第一次打开自动检查 · 上次检查 \$last\""
        const val KERNEL_NEVER_LINE =
            "UpdateLastOutcome.Never -> \"每天第一次打开自动检查 · 上次检查 \$lastCheckLabel\""

        // ── ⑤ 被挡那一手要留一条读得到的痕迹（HyperOS 把 logcat 截在 Info 级） ──
        const val BLOCKED_STATE =
            "data class Blocked(val title: String, val message: String, val manual: Boolean) : UpdateUiState"
        const val BLOCKED_VISIBLE = "is Blocked -> manual"
        const val BLOCKED_DIALOG = "is UpdateUiState.Blocked -> SimpleDialog("
        const val DIALOG_TITLE = "title = shown.title,"
        const val SUBTITLE_SAME_SOURCE = "state is UpdateUiState.Blocked -> state.message"
        const val LOG_PRIVACY_W = "Log.w(TAG, \"检查更新被隐私闸门挡下（用户手动按下，已出声）：\""
        const val LOG_PRIVACY_I = "Log.i(TAG, \"检查更新被隐私闸门挡下（自动触发，按合规要求不弹窗、只留痕）\")"
        const val LOG_IGNORE_I = "Log.i(TAG, \"最新版 v\${verdict.version} 已被用户忽略：\${verdict.spoken.message}\")"
        const val LOG_CLEAR_I = "Log.i(TAG, \"已撤销「忽略此版本」：v\$version 会随下一次检查重新提示\")"
    }

    // ─────────────── ① 主尺：隐私只许是输入，不许又变回一道 return ───────────────

    @Test
    fun `隐私闸门不再是 return 只作为 admit 的输入在场`() {
        val raw = source(CHECK)
        val code = blankComments(raw)

        assertEquals(
            "$CHECK 里改前那一手「$OLD_PRIVACY_GATE」在代码里必须 **0** 处 —— 它排在 force 判据之前，" +
                "把设置页那颗「检查更新」对未同意隐私的用户折成彻底 no-op（零弹窗、零副标题变化、零日志）" +
                "\n  复算：把 $CHECK 的 // 与块注释抹成空格后数这一串" +
                "\n  实到枚数：" + occurrences(code, OLD_PRIVACY_GATE),
            0,
            occurrences(code, OLD_PRIVACY_GATE),
        )
        assertEquals(
            "含注释的原文里「$OLD_PRIVACY_GATE」必须恰好 **1** 处（KDoc 逐字留着改前那一手做取证）。" +
                "实到 " + occurrences(raw, OLD_PRIVACY_GATE) + " 枚：读到 0 = 取证被搬走，上面那个 0 就成了" +
                "「注释删干净」的假绿；读到 2 = 病灶回潮",
            1,
            occurrences(raw, OLD_PRIVACY_GATE),
        )
        assertEquals(
            "$CHECK 里隐私读数恰好一枚，且必须是递给判据的具名参数「$PRIVACY_AS_INPUT」（实到 " +
                occurrences(code, PRIVACY_AS_INPUT) + " 枚）。自己另起一枚 `FirstRun.privacyAccepted` 判断" +
                "就是第二把闸，而那正是本卡的病灶本身",
            1,
            occurrences(code, PRIVACY_AS_INPUT),
        )
        assertEquals(
            "$CHECK 里 admit 与 present 各一枚在场（实到 " + occurrences(code, ADMIT_CALL) + " 与 " +
                occurrences(code, PRESENT_CALL) + "）：内核只许被问两次——出不出网一次、怎么呈现一次",
            listOf(1, 1),
            listOf(occurrences(code, ADMIT_CALL), occurrences(code, PRESENT_CALL)),
        )
        val admitAt = code.indexOf(ADMIT_CALL)
        val privacyAt = code.indexOf(PRIVACY_AS_INPUT)
        val gateAt = code.indexOf(ADMISSION_GATE)
        val presentAt = code.indexOf(PRESENT_CALL)
        assertTrue(
            "顺序钉：admit 调用（实到索引 $admitAt）必须先于分派闸门（$gateAt），闸门必须先于 present（$presentAt）。" +
                "把 privacyAccepted 这枚实参挪到 admit 之外自己判，就是「隐私闸又排到 force 之前」那一形",
            admitAt in 0 until gateAt && gateAt < presentAt,
        )
        assertTrue(
            "隐私那枚读数必须落在 admit 的实参表里（$privacyAt 必须在 $admitAt 之后、$gateAt 之前），" +
                "不许住在任何一条 return 的条件上",
            privacyAt > admitAt && privacyAt < gateAt,
        )
    }

    // ─────────────── ② 主尺：内核里的先后（顺序即语义） ───────────────

    @Test
    fun `内核 admit 与 present 的规则顺序钉住两处静默的复发方向`() {
        val code = blankComments(source(GATE))
        val admit = firstBalancedWhen(code, ADMIT_BODY, "admit 的 when 块")
        val present = lastBalancedWhen(code, ADMIT_BODY, "present 的 when 块")

        for ((label, anchor) in listOf(
            "隐私那一档" to RULE_PRIVACY,
            "节流那一档" to RULE_THROTTLE,
            "放行那一档" to RULE_RUN,
        )) {
            assertEquals(
                "admit 里「$anchor」恰好一枚（$label，实到 " + occurrences(admit, anchor) + "）。" +
                    "少一枚就是那一手没有自己的结论，多一枚就是有人新起了第二把闸",
                1,
                occurrences(admit, anchor),
            )
        }
        assertTrue(
            "**顺序钉（本卡题眼）**：隐私那一档（实到索引 " + admit.indexOf(RULE_PRIVACY) + "）之后必须紧跟" +
                "带 trigger 的 BlockedByPrivacy —— 也就是「$ANNOUNCE_PRIVACY」这类**看 trigger** 的格子得在场。" +
                "把隐私那一手折成不带 trigger 的静默常量，就是改前那道 return 换了一件衣服",
            admit.indexOf(RULE_PRIVACY) >= 0 && code.contains(ANNOUNCE_PRIVACY),
        )
        assertTrue(
            "顺序钉第二格：节流（" + admit.indexOf(RULE_THROTTLE) + "）排在放行（" + admit.indexOf(RULE_RUN) +
                "）之前，否则「今天已经查过」那一手会被当成放行直接出网",
            admit.indexOf(RULE_THROTTLE) < admit.indexOf(RULE_RUN),
        )

        assertEquals(
            "present 里「忽略那一档」恰好一枚（实到 " + occurrences(present, RULE_IGNORE) + "）：" +
                "摘掉它就是「忽略了还每天 nag」的复发方向",
            1,
            occurrences(present, RULE_IGNORE),
        )
        assertTrue(
            "**顺序钉**：被忽略那一档（实到 " + present.indexOf(RULE_IGNORE) + "）必须排在新版本档（" +
                present.indexOf(RULE_NEW) + "）之前，否则忽略判据永远轮不到说话——单向门换了一种写法",
            present.indexOf(RULE_IGNORE) in 0 until present.indexOf(RULE_NEW),
        )
        assertTrue(
            "顺序钉第二格：失败（" + present.indexOf(RULE_FAILURE) + "）排在一切呈现档之前，" +
                "否则网络挂了会念成「这一版被你忽略了」，那句是假话",
            present.indexOf(RULE_FAILURE) < present.indexOf(RULE_IGNORE) &&
                present.indexOf(RULE_FAILURE) < present.indexOf(RULE_NOTHING),
        )

        for (anchor in listOf(ANNOUNCE_PRIVACY, ANNOUNCE_IGNORE, ANNOUNCE_THROTTLE)) {
            assertEquals(
                "「$anchor」必须恰好一枚在场（实到 " + occurrences(code, anchor) + "）。" +
                    "把它改成常量 true = 未同意隐私的用户每次冷启动都被弹窗打断；改成常量 false = " +
                    "手动按下又一次 no-op —— 两侧都只许由 trigger 说话",
                1,
                occurrences(code, anchor),
            )
        }
        assertEquals(
            "档位枚数恰好 7（实到 " + occurrences(code, TIERS) + "）：折成一枚 Boolean 是 0，" +
                "把两档并一是 6 —— 每一档都要有自己那句实话",
            7,
            occurrences(code, TIERS),
        )
    }

    @Test
    fun `判据内核零 android 也不读钟读区读权限读偏好`() {
        val raw = source(GATE)
        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals("$GATE 必须零 android / androidx import。实到：" + offenders, emptyList<String>(), offenders)

        val code = blankComments(raw)
        for (needle in FORBIDDEN_READS) {
            assertEquals(
                "判据文件里「$needle」必须 0 处：读了它，「这一手要不要出网、念哪句话」就不再是纯函数 —— " +
                    "同一次冷启动的两次调用可能给出不同结论（本仓口径：这些留在调用点算好再当参数递进来）。" +
                    "\n  复算：把 $GATE 的注释抹成空格后数「$needle」\n  实到枚数：" + occurrences(code, needle),
                0,
                occurrences(code, needle),
            )
        }
        // 反套套逻辑：KDoc 里那条纯度承诺必须留痕，否则上面那串 0 没人负责
        assertEquals(
            "内核 KDoc 里那句纯度承诺必须恰好留痕一枚（含注释原文）。实到：" +
                occurrences(raw, "不读时钟、不读时区、不读权限、不读 `Build.VERSION`"),
            1,
            occurrences(raw, "不读时钟、不读时区、不读权限、不读 `Build.VERSION`"),
        )
    }

    // ─────────────── ③ 忽略那扇门：成对 + 回读 ───────────────

    @Test
    fun `忽略的写与清除成对 且界面既回读又有撤销那一行`() {
        val code = blankComments(source(CHECK))
        val settings = blankComments(source(SETTINGS))

        assertEquals(
            "$CHECK 里忽略的写入「$SKIP_WRITE」恰好一枚（实到 " + occurrences(code, SKIP_WRITE) + "）",
            1,
            occurrences(code, SKIP_WRITE),
        )
        assertEquals(
            "$CHECK 里的清除那一手「$SKIP_CLEAR」必须恰好一枚（实到 " + occurrences(code, SKIP_CLEAR) + "）。" +
                "改前它是 **0** 枚：KEY_SKIPPED 只有定义 / 读 / 写三处命中，点一次「忽略此版本」就永久不再提示" +
                "且无法撤销（台账 #367）。写与清除不配对 = 单向门又回来了",
            1,
            occurrences(code, SKIP_CLEAR),
        )
        assertEquals(
            "被忽略的版本号在检查那一手只许读**一次**并原样递给判据（「$SKIP_READ」，实到 " +
                occurrences(code, SKIP_READ) + "）。在这儿再过滤一遍就是第二把尺，" +
                "而第二把尺正是「忽略档没人说话」的成因",
            1,
            occurrences(code, SKIP_READ),
        )
        for ((label, anchor) in listOf(
            "清除路径那枚函数" to CLEAR_FUN,
            "回读那枚函数" to READ_FUN,
            "结果档那枚读函数" to OUTCOME_FUN,
        )) {
            assertEquals(
                "$CHECK 里 $label「$anchor」必须恰好一枚定义（实到 " + occurrences(code, anchor) + "）",
                1,
                occurrences(code, anchor),
            )
        }
        for ((label, anchor) in listOf(
            "撤销那一行的点击" to SETTINGS_CLEAR_READ,
            "撤销那一行的回读" to SETTINGS_READ_BACK,
            "撤销那一行的标题" to SETTINGS_UNDO_ROW,
            "撤销那一行的副标题" to SETTINGS_UNDO_SUMMARY,
        )) {
            assertEquals(
                "$SETTINGS 里 $label「$anchor」恰好一枚（实到 " + occurrences(settings, anchor) + "）。" +
                    "0 枚就是 UI 零回读（改前的形状），2 枚就是同一扇门开了两个出口",
                1,
                occurrences(settings, anchor),
            )
        }
    }

    // ─────────────── ④ 结果档落盘与那一行的唯一读者 ───────────────

    @Test
    fun `结果档两档各落一次 设置页那句读数只在内核写一处`() {
        val code = blankComments(source(CHECK))
        val gate = blankComments(source(GATE))
        val settings = blankComments(source(SETTINGS))

        assertEquals(
            "$CHECK 里结果档那枚键「$OUTCOME_KEY」恰好一枚（实到 " + occurrences(code, OUTCOME_KEY) + "）",
            1,
            occurrences(code, OUTCOME_KEY),
        )
        for (anchor in listOf(OUTCOME_FAIL_WRITE, OUTCOME_OK_WRITE)) {
            assertEquals(
                "$CHECK 里「$anchor」必须恰好一枚（实到 " + occurrences(code, anchor) + "）。" +
                    "少一枚就是有一手没落档，进程死后设置页又只能念一句时间戳（台账 #368）",
                1,
                occurrences(code, anchor),
            )
        }
        assertEquals(
            "$SETTINGS 里那一行的读数只许走内核那一句「$SETTINGS_RESTING_READ」（实到 " +
                occurrences(settings, SETTINGS_RESTING_READ) + "）",
            1,
            occurrences(settings, SETTINGS_RESTING_READ),
        )
        // 反套套逻辑：那句「上次检查」的文案只许有一个作者。
        // 改前它写在 $SETTINGS 里，成功与失败共用同一句；现在它住进内核 restingLine 的 Never 那一档。
        assertEquals(
            "改前写在设置页的那一句「$OLD_SUBTITLE」在 $SETTINGS 代码里必须 **0** 处（实到 " +
                occurrences(settings, OLD_SUBTITLE) + "）：还在就是同一句话两处写，" +
                "失败档又会被自己那句通用文案盖掉",
            0,
            occurrences(settings, OLD_SUBTITLE),
        )
        assertEquals(
            "同一句在 $GATE 的 Never 那一档恰好一枚（实到 " + occurrences(gate, KERNEL_NEVER_LINE) + "）：" +
                "上一格读到 0 而这一格也读到 0，就说明那句话整个丢了而不是搬了家",
            1,
            occurrences(gate, KERNEL_NEVER_LINE),
        )
    }

    // ─────────────── ⑤ 痕迹：被挡那一手要留一条读得到的记录 ───────────────

    @Test
    fun `被挡与失败各留一条读得到的痕迹 这一族零 Log d`() {
        val code = blankComments(source(CHECK))
        val dialog = blankComments(source(DIALOG))

        assertEquals(
            "$CHECK 里 Blocked 那枚状态声明逐字节一枚在场「$BLOCKED_STATE」（实到 " +
                occurrences(code, BLOCKED_STATE) + "）",
            1,
            occurrences(code, BLOCKED_STATE),
        )
        assertEquals(
            "弹窗闸门「$BLOCKED_VISIBLE」恰好一枚（实到 " + occurrences(code, BLOCKED_VISIBLE) + "）：" +
                "改成常量 true = 未同意隐私的用户每次冷启动都被打断；改成 false = 手动那一手又一次没反馈",
            1,
            occurrences(code, BLOCKED_VISIBLE),
        )
        for ((label, anchors) in listOf(
            "隐私被挡" to listOf(LOG_PRIVACY_W, LOG_PRIVACY_I),
            "忽略压制" to listOf(LOG_IGNORE_I),
            "撤销忽略" to listOf(LOG_CLEAR_I),
        )) {
            val hits = anchors.sumOf { occurrences(code, it) }
            assertEquals(
                "$label 那一手在 $CHECK 里必须留下恰好 " + anchors.size + " 枚 Log.i/Log.w 痕迹" +
                    "（改前是 **0** 枚：整段 return 连一条日志都不留）。实到 $hits 枚：" + anchors,
                anchors.size,
                hits,
            )
        }
        assertEquals(
            "这一族的落痕总数 = 8 枚（手动/自动隐私 2 + 节流 1 + 忽略压制 1 + 失败 1 + 忽略写入 1 + 撤销 2）。" +
                "少一枚就又有一档静默。实到 " +
                occurrences(code, "Log.i(TAG,") + occurrences(code, "Log.w(TAG,") + " 枚",
            8,
            occurrences(code, "Log.i(TAG,") + occurrences(code, "Log.w(TAG,"),
        )
        assertEquals(
            "$CHECK 里 `Log.d` 必须 0 枚（本仓实测：HyperOS 把 logcat 截在 Info 级，写了等于没写）。实到 " +
                occurrences(code, "Log.d"),
            0,
            occurrences(code, "Log.d"),
        )
        assertEquals(
            "$DIALOG 里 Blocked 必须有自己那一支「$BLOCKED_DIALOG」（实到 " +
                occurrences(dialog, BLOCKED_DIALOG) + "）——状态有了一档而弹窗不认识它，等于换了个地方静默",
            1,
            occurrences(dialog, BLOCKED_DIALOG),
        )
        assertEquals(
            "那一支必须念内核交下来的两句（「$DIALOG_TITLE」与 body 同源），实到 " +
                occurrences(dialog, DIALOG_TITLE) + " 枚标题锚点",
            1,
            occurrences(dialog, DIALOG_TITLE),
        )
        assertEquals(
            "$SETTINGS 里那一行念的必须是同一枚 message（「$SUBTITLE_SAME_SOURCE」），不许另写一句同义的话。实到 " +
                occurrences(blankComments(source(SETTINGS)), SUBTITLE_SAME_SOURCE) + " 枚",
            1,
            occurrences(settings, SUBTITLE_SAME_SOURCE),
        )
    }

    // ---------------- helpers（与 WidgetRolloverClaimWiringGuardTest 同写法） ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 按锚点取一枚 `when {` 块（花括号配平）；锚点找不到就抛，改名/挪家要回来重钉 */
    private fun blockAt(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到块的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** admit 与 present 用的是同一句签名尾，所以按出现顺序取第一枚 / 最后一枚 */
    private fun firstBalancedWhen(code: String, anchor: String, label: String): String = blockAt(code, anchor, label)

    private fun lastBalancedWhen(code: String, anchor: String, label: String): String {
        val first = code.indexOf(anchor)
        val second = code.indexOf(anchor, first + anchor.length)
        assertTrue("$label 找不到：$anchor 在 $GATE 里少于两枚", second >= 0)
        return blockAt(code.substring(second), anchor, label)
    }

    /** 把 `//` 与块注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
    private fun blankComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
