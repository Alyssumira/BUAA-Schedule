package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T180③：把**「parse 失败的那枚原串被当成兜底值交出去」这一形在 `app/src/main` 里 0 处**钉死，
 * 并钉住本卡的三样前提：判据只有 `resolveBuaaSemesterStart` 一份、它在首次导入那一处真的被
 * 用上且坏值走不到建学期、写库那一路**没有**第二道 parse 闸门（所以判据必须长在写库之前）。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `ui/editor/DeleteFlagConfigSurvivalGuardTest`（抹注释保字面量 + 花括号配平取块 + 原文切片不吃行号）。
 *
 * ## 病与那一形
 *
 * 改之前 `BuaaLoginScreen` 首次教务导入建学期那一段，把一枚坏日期原样写进了库：兜底值
 * 正是 parse 没能解析的那枚原串（旧形状 `runCatching { mondayOf(LocalDate.parse(rawStart)) }
 * .getOrDefault(rawStart)`）。后果链见 `BuaaSemesterStartPolicy` 段首：`Semester.startLocalDate`
 * 此后恒 null ⇒ 周课表空网格、`ReminderScheduler.rescheduleAll` 走 `cancelAll` 那一支、
 * 组件停在「假期中」、设置页把这串垃圾显示出来并能回写。
 *
 * ## 输入面（本仓规矩：守卫读工作树文本，就得把这些文件声明成测试任务的输入）
 *
 * 本守卫读 `app/src/main/java` 下的源码文本 —— 那一条已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），
 * 所以"改了源码、单跑守卫拿到 Gradle 缓存绿灯"那一档盖得住；本次没有新增**目录之外**的读取面
 * （不读 res/、不读清单、不读脚本），无需再动构建脚本。
 *
 * ## 钉法（两侧都有格子，另有反套套逻辑自检）
 *
 * 1. [那一形全树 0 处]：字面 `getOrDefault(rawStart)` 0 处 + 同标识符回 echo 的形状
 *    （`runCatching { … LocalDate.parse(X) … }.getOrDefault(X)` / `.getOrElse { X }`）0 处。
 *    **探测器自检**：把改之前的原文喂进去必须报出来（朝松的靶：探测器若是空转、这一格先红），
 *    再把盘上两枚**合法**的 `getOrDefault` 用法喂进去必须 0 报（朝窄的靶：探测器若宽到
 *    见 `getOrDefault` 就杀，这一格红 —— 那说明它会把上游合法的兜底一起拆掉）。
 * 2. [判据只有一份且零 android 零时钟]：`BuaaSemesterStartPolicy` 声明恰好一处、全树定义
 *    恰好一处、`import android` 0 处、`LocalDate.now(` 0 处（一旦允许读时钟，「今天所在周
 *    的周一」就会顺着这枚参数爬回来）、归一公式仍只有 `mondayOfWeekAnchor` 那一处。
 * 3. [调用点真的按结论分两路]：`BuaaLoginScreen` 内 `LocalDate.parse(` / `getOrDefault(` /
 *    `LocalDate.now(` 全 0 处，判据调用一处，`startDate = start.monday.toString(),` 逐字一处，
 *    `Undetermined` 那一支里恰好一句报错 + 一次 `onBack()` 且**不建 Semester、不进预览**，
 *    且报错排在 `previewBuaaCourses` 之前（本仓「顺序决定生死」那一族的老规矩）。
 * 4. [姊妹路一个字节没动]：`BuaaWebSession.refreshSchedule` 的 `?: existingStartDate` 与
 *    「未取得学期第一周周一，未改动课表」各恰好一处，那一路仍用 `getOrNull` 不用 `getOrDefault`。
 * 5. [写库链仍然没有第二道闸门]：`ScheduleRepository.replaceSemesterCoursesInTx` 体内
 *    `semesterDao.insert(semester.toEntity())` 一处且体内 `LocalDate.parse(` 0 处 ——
 *    这一格是**前提**不是判据：哪天有人在写库前补了一道 parse 闸门，本卡「必须长在写库之前」
 *    的理由就要重读，所以它必须红一次让人看见。（T223 之后这一族重钉：备份恢复那一路原本那道
 *    自 parse 闸门并进了 `data/backup/BackupSemesterRestorePolicy.kt`，于是**整个仓储层**
 *    `LocalDate.parse(` 0 处 —— 比改之前更严，且导入链依旧不经过那道闸门。）
 *
 * 本守卫只读源码，**不证明**运行时行为：教务真返回非 ISO 首周时用户看到的那一句新报错、
 * 以及"重试一次就成功"那一档归编排者装机。
 */
class BuaaSemesterStartWiringGuardTest {

    // ─────────────── ① 那一形全树 0 处（含探测器自检） ───────────────

    @Test
    fun `parse 失败的原串不许当兜底值 全树零命中`() {
        val echoes = mutableListOf<String>()
        var literalRawStart = 0
        for (file in mainSources()) {
            literalRawStart += occurrences(file.code, LITERAL_SHAPE)
            for (at in dateParseEchoFallbacks(file.code)) {
                echoes += "${file.path} ${lineOf(file.code, at)}: ${lineAt(file.code, at).trim()}"
            }
        }
        assertEquals(
            "`$LITERAL_SHAPE` 在 app/src/main 必须 0 处 —— 它就是 T180 那一形的字面本体" +
                "（兜底值 = 刚被 `LocalDate.parse` 拒掉的那枚原串）。实到 $literalRawStart 处",
            0,
            literalRawStart,
        )
        assertEquals(
            "「同标识符回 echo」那一形（`runCatching { … LocalDate.parse(X) … }.getOrDefault(X)`，" +
                "含 `.getOrElse { X }` 那一等改名版）在 app/src/main 必须 0 处。实到 " + echoes.size +
                " 处：\n  " + echoes.joinToString("\n  "),
            0,
            echoes.size,
        )
    }

    /**
     * 反套套逻辑：探测器**认得**改之前那两行原文，也**不误伤**盘上合法的 `getOrDefault`。
     *
     * 这一格是第 ① 层的"探测器不是空转"的证据 —— 只报"全树 0 命中"而探测器其实什么都抓不到，
     * 等于没有守卫（本仓的口径：别拿 grep 没匹配到当全绿）。两个方向各一头：
     * 朝松（探测器瞎）红在第一手，朝窄（探测器见 `getOrDefault` 就杀）红在第二手 ——
     * 那样的探测器会把 `BuaaInPageFetcher` 里那两枚合法兜底一起拆掉，红给它看。
     */
    @Test
    fun `探测器自己认得旧那一形 也不误伤合法兜底`() {
        val oldShape = """
            val rawStart = info.firstWeekMonday
                ?: java.time.LocalDate.now().minusDays(1L).toString()
            val startDate = runCatching {
                com.buaa.schedule.domain.schedule.WeekCalculator
                    .mondayOf(java.time.LocalDate.parse(rawStart)).toString()
            }.getOrDefault(rawStart)
        """.trimIndent()
        assertEquals(
            "探测器必须把改之前那一形原文抓出来（抓到 $LITERAL_SHAPE 那一手才算会做事）。" +
                "实到 " + dateParseEchoFallbacks(oldShape).size + " 处 —— 0 处说明探测器空转，" +
                "第 ① 层那句「全树 0 命中」是假的绿",
            1,
            dateParseEchoFallbacks(oldShape).size,
        )

        // 合法一：上游 `BuaaInPageFetcher.decodeJsString` 的「不是 JSON 就原样用」，与日期无关
        // （原文里是 JSONArray("[$value]")，这里写成拼接形式只为避开本测试文件自己的字符串插值）
        val legitJs = """
            return runCatching {
                val arr = org.json.JSONArray("[" + value + "]")
                if (arr.length() == 1) arr.getString(0) else value
            }.getOrDefault(value)
        """.trimIndent()
        assertEquals(
            "`decodeJsString` 那一形（同一枚标识符回 echo，但块内没有 `LocalDate.parse`）**不许**被算成命中：" +
                "它是合法的「解析不出来就按原样用」，实到 " + dateParseEchoFallbacks(legitJs).size + " 处",
            0,
            dateParseEchoFallbacks(legitJs).size,
        )

        // 合法二：整块失败的默认值（构造器调用，不是裸标识符）
        val legitDefault = """
            return runCatching {
                val resp = json.decodeFromString<BuaaTermWeeksResponse>(raw)
                SemesterInfo(weeks.size, first.startDate?.substringBefore(" "))
            }.getOrDefault(SemesterInfo(20, null))
        """.trimIndent()
        assertEquals(
            "`fetchSemesterInfo` 那一形（兜底是一枚新造的对象，不是被 parse 的那枚串）不许被算成命中；" +
                "实到 " + dateParseEchoFallbacks(legitDefault).size + " 处",
            0,
            dateParseEchoFallbacks(legitDefault).size,
        )

        // 盘上那两枚合法用法确实还在：它们被顺手删掉的话，上面的负对照就自证为空
        // ⚠️ T196④ 给 `SemesterInfo` 加了第三枚实参（那一档「为什么没有」的原因），构造点从
        // `SemesterInfo(20, null)` 变成 `SemesterInfo(20, null, SemesterInfoReason.…)`：
        // 针去掉右括号，**期望枚数仍是 1+1 = 2**，`20` 与 `null` 两枚实参逐字还在那一行上。
        val fetcher = blankCommentsKeepingLiterals(source(BUA_IN_PAGE_FETCHER))
        assertEquals(
            "`$fetcher` 里 `}.getOrDefault(value)` 与 `.getOrDefault(SemesterInfo(20, null,` 两枚合法兜底" +
                "必须都还在（它们是本层负对照的实物）。实到 " +
                occurrences(fetcher, "}.getOrDefault(value)") + " + " +
                occurrences(fetcher, ".getOrDefault(SemesterInfo(20, null,") + " 处",
            2,
            occurrences(fetcher, "}.getOrDefault(value)") +
                occurrences(fetcher, ".getOrDefault(SemesterInfo(20, null,"),
        )
    }

    // ─────────────── ② 判据只有一份，零 android、零时钟 ───────────────

    @Test
    fun `判据内核零 android 依赖 不读时钟 全仓只有一份`() {
        val raw = source(POLICY)
        val code = blankCommentsKeepingLiterals(raw)

        assertEquals(
            "纯 JVM 判据函数必须**零 android import**（仓库硬规矩：设备面一律由调用点当参数传进来）。" +
                "实到 " + occurrences(code, "import android") + " 处",
            0,
            occurrences(code, "import android"),
        )
        assertEquals(
            "`$POLICY` 里 `LocalDate.now(` 必须 0 处（实到 " + occurrences(code, "LocalDate.now(") +
                " 处）：判据一旦能读时钟，「今天所在周的周一」就会顺着这枚参数爬回来 —— 那正是本卡" +
                "否掉的那一等猜法（提前导入下一学期时整表日期与周次编号一起偏，且用户读不出来）。" +
                "要日期就只能由调用点传，而调用点今天传的是教务的原样字符串，一枚时钟都没动",
            0,
            occurrences(code, "LocalDate.now("),
        )
        assertEquals(
            "`System.currentTimeMillis` / `Calendar.` 之类的时钟读取面同样 0 处（实到 " +
                occurrences(code, "System.currentTimeMillis") + occurrences(code, "Calendar.") + " 处）",
            0,
            occurrences(code, "System.currentTimeMillis") + occurrences(code, "Calendar."),
        )
        assertEquals(
            "判据本体 `internal fun resolveBuaaSemesterStart(` 在 `$POLICY` 里恰好一处（实到 " +
                occurrences(code, "internal fun resolveBuaaSemesterStart(") + " 处）；" +
                "全 app/src/main 也只许一处（实到 " + mainDefinitionCount("fun resolveBuaaSemesterStart(") +
                " 处）：第二份定义 = 两把尺量同一件事，改其一不改其余",
            1,
            occurrences(code, "internal fun resolveBuaaSemesterStart("),
        )
        assertEquals(
            "归一公式必须仍引 `mondayOfWeekAnchor` 那唯一一份（本文件里 `mondayOfWeekAnchor(` 恰好一处，" +
                "实到 " + occurrences(code, "mondayOfWeekAnchor(") +
                " 处）：就地再写一遍 `minusDays(dayOfWeek.value - 1)` 就是全仓第二套锚点，" +
                "R7 P2-12 那一族（同一节课最多错开 6 天）会重新长出来",
            1,
            occurrences(code, "mondayOfWeekAnchor("),
        )
        assertEquals(
            "parse 入口只许一枚 `LocalDate.parse(`（实到 " + occurrences(code, "LocalDate.parse(") +
                " 处）：多一枚就是多一条「抢救格式」的路（斜杠 / 中文年月 / 补 01 那一等），" +
                "本卡的口径是认不出来就不动学期",
            1,
            occurrences(code, "LocalDate.parse("),
        )
        assertEquals(
            "`BuaaSemesterStart` 两枚结论各恰好一处声明（Resolved / Undetermined；实到 " +
                occurrences(code, "data class Resolved(") + " + " +
                occurrences(code, "data class Undetermined(") +
                " 处）：第三枚结论（比如 `Guessed`）会绕过本卡的判据，坏日期又有了入口",
            2,
            occurrences(code, "data class Resolved(") + occurrences(code, "data class Undetermined("),
        )
    }

    // ─────────────── ③ 调用点：判据真的被用上，坏值走不到建学期 ───────────────

    @Test
    fun `登录页不再自己判日期 只取内核的结论`() {
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))

        for (needle in listOf("LocalDate.parse(", "getOrDefault(", "LocalDate.now(", "getOrElse(")) {
            assertEquals(
                "`BuaaLoginScreen` 里 `$needle` 必须 0 处（实到 " + occurrences(code, needle) +
                    " 处）：首次导入建学期那一段现在只许取 `resolveBuaaSemesterStart` 的结论，" +
                    "不许自己 parse、不许自己读时钟、更不许再挂任何兜底值" +
                    "（顺带：旧表达式里读两次 `LocalDate.now()` 那一手也跟着没了）",
                0,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "判据调用 `resolveBuaaSemesterStart(info.firstWeekMonday)` 恰好一处（实到 " +
                occurrences(code, "resolveBuaaSemesterStart(info.firstWeekMonday)") +
                " 处）：摘掉它 = 这一族白修（第 ① 层全树 0 命中会跟着变空）",
            1,
            occurrences(code, "resolveBuaaSemesterStart(info.firstWeekMonday)"),
        )
        assertEquals(
            "两枚分支各恰好一处：`is BuaaSemesterStart.Undetermined ->` 一处 + " +
                "`is BuaaSemesterStart.Resolved ->` 一处（实到 " +
                occurrences(code, "is BuaaSemesterStart.Undetermined ->") + " + " +
                occurrences(code, "is BuaaSemesterStart.Resolved ->") +
                " 处）：少一枚分支 = `when` 不收全 = 有一等结论没人认领",
            2,
            occurrences(code, "is BuaaSemesterStart.Undetermined ->") +
                occurrences(code, "is BuaaSemesterStart.Resolved ->"),
        )
        assertEquals(
            "`$START_DATE_ASSIGN` 逐字恰好一处（开学日的唯一来源是内核归一后的那枚周一；实到 " +
                occurrences(code, START_DATE_ASSIGN) + " 处）。换成 `startDate = info.firstWeekMonday` " +
                "或任何 `?: ` 兜底，本格立刻红",
            1,
            occurrences(code, START_DATE_ASSIGN),
        )
    }

    @Test
    fun `认不出来那一支只报错不动学期`() {
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))

        val hits = indexOfAll(code, UNDETERMINED_BRANCH)
        assertEquals("`$UNDETERMINED_BRANCH` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val branch = braceBodyFrom(code, code.indexOf('{', hits.first() + UNDETERMINED_BRANCH.length))

        assertEquals(
            "那一支内 `showMessage(` 恰好一处（说实话的那一句；实到 " + occurrences(branch, "showMessage(") + " 处）",
            1,
            occurrences(branch, "showMessage("),
        )
        assertEquals(
            "那一支内 `isError = true` 恰好一处（实到 " + occurrences(branch, "isError = true") +
                " 处）：不带 isError 的那一句在页面上是灰字提示，用户读作「导入成功了」" +
                "—— 本卡要的是亮红的一条",
            1,
            occurrences(branch, "isError = true"),
        )
        assertEquals(
            "那一支内 `onBack()` 恰好一处（实到 " + occurrences(branch, "onBack(") +
                " 处）：不返回就是把用户留在一张永远不进预览的登录页上",
            1,
            occurrences(branch, "onBack("),
        )
        for (needle in listOf("Semester(", "previewBuaaCourses(", "importPrepared = true", "onImportPrepared(")) {
            assertEquals(
                "认不出来那一支里 `$needle` 必须 0 处（实到 " + occurrences(branch, needle) +
                    " 处）：这一支的语义就是**不动学期** —— 不建 `Semester`、不进导入预览、" +
                    "不置已备好那面旗、不跳导入页。落在这里的任何一手都会把一个没根据的学期推给用户",
                0,
                occurrences(branch, needle),
            )
        }
        assertTrue(
            "次序必须是 **报错 → 预览**：`showMessage(` 落在 index=" + branch.indexOf("showMessage(") +
                "，而 `previewBuaaCourses(` 在全文件 index=" + code.indexOf("previewBuaaCourses(") +
                "。本仓「顺序决定生死」那一族的老规矩：只断言「两处都在」不够 —— " +
                "把预览搬到 `when` 之前，坏日期那一档就照样进预览",
            code.indexOf("showMessage(", hits.first()) in 0 until code.indexOf("previewBuaaCourses(", hits.first()),
        )
        assertEquals(
            "Resolved 那一支里 `previewBuaaCourses(` 恰好一处（实到 " +
                occurrences(
                    braceBodyFrom(
                        code,
                        code.indexOf('{', code.indexOf(RESOLVED_BRANCH) + RESOLVED_BRANCH.length),
                    ),
                    "previewBuaaCourses(",
                ) + " 处）：只有归一成功的那一枚才进预览",
            1,
            occurrences(
                braceBodyFrom(code, code.indexOf('{', code.indexOf(RESOLVED_BRANCH) + RESOLVED_BRANCH.length)),
                "previewBuaaCourses(",
            ),
        )
    }

    // ─────────────── ④ 姊妹路：口径对齐，但一个字节没动 ───────────────

    @Test
    fun `静默刷新那一路仍然沿用库里的日期 判不出来就整趟失败`() {
        val code = blankCommentsKeepingLiterals(source(WEB_SESSION))

        for (needle in listOf(EXISTING_START_FALLBACK, REFUSE_MESSAGE)) {
            assertEquals(
                "`BuaaWebSession.refreshSchedule` 里 `$needle` 恰好一处（实到 " + occurrences(code, needle) +
                    " 处）：本卡的口径就是照它对齐的。它被搬走或被改成「猜一枚日期」，" +
                    "意味着两路不再同源 —— 那要连本卡一起重读",
                1,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "那一路的 parse 仍走 `getOrNull` 链（`.getOrNull()` 至少一处、`getOrDefault(` 0 处；实到 " +
                occurrences(code, "getOrDefault(") + " 处 getOrDefault）：" +
                "把 `getOrNull` 换成 `getOrDefault(raw)` 就是本卡那一形在姊妹路的复活",
            0,
            occurrences(code, "getOrDefault("),
        )
        assertEquals(
            "那一路也不许读时钟猜日期（`LocalDate.now(` 实到 " + occurrences(code, "LocalDate.now(") + " 处）",
            0,
            occurrences(code, "LocalDate.now("),
        )
    }

    // ─────────────── ⑤ 前提：写库链没有第二道 parse 闸门 ───────────────

    /**
     * 本卡判「构成用户可见缺陷」的前提是：坏串进了库就没人再拦。这一格把该前提钉成形状 ——
     * 哪天有人在写库之前补了一道 parse 闸门，这里会红一次，逼着来人重读这张卡
     * （判据该长在写库之前还是仓储层，是另一笔账，但那笔账得由红的那一次提出来）。
     */
    @Test
    fun `导入写库那一路对 startDate 一个字都不判 所以判据必须长在写库之前`() {
        val code = blankCommentsKeepingLiterals(source(REPOSITORY))
        val hits = indexOfAll(code, WRITE_TX_ANCHOR)
        assertEquals("`$WRITE_TX_ANCHOR` 恰好一处（导入写库的落点）：实到 " + hits.size + " 处", 1, hits.size)
        val body = braceBodyFrom(code, code.indexOf('{', hits.first() + WRITE_TX_ANCHOR.length))

        assertEquals(
            "体内 `semesterDao.insert(semester.toEntity())` 恰好一处（实到 " +
                occurrences(body, SEMESTER_INSERT) + " 处）：本层取的就是这一手原样落库的形状",
            1,
            occurrences(body, SEMESTER_INSERT),
        )
        assertEquals(
            "体内 `LocalDate.parse(` 必须 0 处（实到 " + occurrences(body, "LocalDate.parse(") +
                " 处）：写库前**没有**第二道闸门 —— 这就是坏串会一路读到 `Semester.startLocalDate` " +
                "变 null 的原因，也是判据必须长在调用点（写库之前）的唯一理由。" +
                "这一格红了说明前提变了，本卡的落点要重判",
            0,
            occurrences(body, "LocalDate.parse("),
        )
        // ── 备份恢复那一路的闸门（T180 之后由 T223 搬家，这一格按 KDoc 的说法"红一次让人看见"）──
        //
        // 改之前：仓里唯一那道 parse 闸门长在 `restoreBackupDataInTx` 体内（旧锚点
        // `WeekCalculator.mondayOf(LocalDate.parse(s.startDate))`）。它不在导入链上，救不了本卡。
        // T223 之后：主学期那一路并进 `data/backup/BackupSemesterRestorePolicy.kt`，仓储层**一个字都不 parse**
        // （那一枚 `?: fallbackStartDate` 就地兜底日历值的形状被守卫钉成 0 处），
        // 闸门换到内核里的 `Semester.startLocalDate`。本层的三格因此重钉，且比改之前更严：
        // ① 旧锚点 0 处 ② 整个仓储层 `LocalDate.parse(` 0 处 ③ 内核确实拿 startLocalDate 当唯一判据。
        assertEquals(
            "旧锚点 `$BACKUP_GATE`（仓储层自己 parse 备份带来的日期那一形）必须 0 处（实到 " +
                occurrences(code, BACKUP_GATE) + " 处）：T223 把主学期并进内核后，" +
                "认不出来时不再就地兜底一枚日历值。这一格红了说明有人在仓储层又自己 parse 了一遍" +
                "（= 第二把尺，且「认不出来就静默换成恢复那天」那一族会重新长出来）",
            0,
            occurrences(code, BACKUP_GATE),
        )
        assertEquals(
            "整个仓储层（抹注释后）`LocalDate.parse(` 必须 0 处（实到 " +
                occurrences(code, "LocalDate.parse(") +
                " 处）：改之前只有备份恢复那一路有 1 处，改之后一处都不许有 —— " +
                "日期认不认得出来只有 `domain/model/Semester.kt` 的 `startLocalDate` 那一把尺",
            0,
            occurrences(code, "LocalDate.parse("),
        )
        assertEquals(
            "内核 `BackupSemesterRestorePolicy.kt` 必须真的拿 `startLocalDate` 当唯一判据" +
                "（实到 " + occurrences(blankCommentsKeepingLiterals(source(BACKUP_POLICY)), BACKUP_START_GATE) +
                " 处，恰好一处才算闸门搬家而不是又添了一把尺）：导入链仍然不经过它，所以本卡的" +
                "「判据必须长在写库之前」这条前提一个字都没变",
            1,
            occurrences(blankCommentsKeepingLiterals(source(BACKUP_POLICY)), BACKUP_START_GATE),
        )
    }

    // ---------------- 探测器：同标识符回 echo 那一形 ----------------

    /**
     * 找出 [code] 里所有「一枚 `runCatching { … }` 块内含 `LocalDate.parse(<X>)`，
     * 而块的兜底值就是同一枚 `<X>`」的落点，返回兜底调用起始处（`.getOrDefault(` / `.getOrElse(`）
     * 的 index 列表。
     *
     * 只算**同一枚 runCatching 单元**里的回 echo，为的是不误伤两类合法用法：
     * `BuaaInPageFetcher.decodeJsString`（同标识符，但块内 parse 的是 JSON 不是日期）与
     * `ScheduleExporters` 里 `.getOrDefault(LocalDate.now())`（兜底是调用表达式，不是被 parse 的那枚串）。
     */
    private fun dateParseEchoFallbacks(code: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = code.indexOf("runCatching", from)
            if (at < 0) return hits
            from = at + "runCatching".length
            val open = code.indexOf('{', at)
            if (open < 0) continue
            val close = matchingBrace(code, open) ?: continue
            val block = code.substring(open + 1, close)
            val parsedArgs = parseArgumentsOf(block)
            if (parsedArgs.isEmpty()) continue
            var tail = close + 1
            while (tail < code.length && code[tail].isWhitespace()) tail++
            for (launcher in listOf(".getOrDefault(", ".getOrElse(")) {
                if (!code.startsWith(launcher, tail)) continue
                val argEnd = matchingParen(code, tail + launcher.length - 1) ?: continue
                val arg = code.substring(tail + launcher.length, argEnd).trim()
                // `.getOrElse { X }` / `.getOrElse run { X }`：把块体收成表达式再比
                val expr = if (arg.startsWith("{")) arg.removePrefix("{").trim().removeSuffix("}").trim() else arg
                if (!SIMPLE_PATH.matches(expr)) continue
                if (expr in parsedArgs) hits += tail
            }
        }
    }

    /** 取出 [block] 里每一枚 `LocalDate.parse(...)` 的实参文本（配平括号，去空白） */
    private fun parseArgumentsOf(block: String): Set<String> {
        val out = mutableSetOf<String>()
        var from = 0
        while (true) {
            val at = block.indexOf("LocalDate.parse(", from)
            if (at < 0) return out
            from = at + 1
            val open = at + "LocalDate.parse".length
            val close = matchingParen(block, open) ?: continue
            out += block.substring(open + 1, close).trim()
        }
    }

    /** 花括号配平（跳过字符串与字符字面量）；不配平返回 null */
    private fun matchingBrace(src: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 圆括号配平：[open] 指向 `(`，返回与之配平的 `)` 的下标 */
    private fun matchingParen(src: String, open: Int): Int? {
        if (src.getOrNull(open) != '(') return null
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private data class Source(val path: String, val code: String)

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        return files.map {
            Source(it.relativeTo(root).path.replace('\\', '/'), blankCommentsKeepingLiterals(it.readText()))
        }
    }

    private fun mainDefinitionCount(needle: String): Int = mainSources().sumOf { occurrences(it.code, needle) }

    private fun lineOf(code: String, index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

    private fun lineAt(code: String, index: Int): String {
        val lines = code.split('\n')
        return lines.getOrElse(lineOf(code, index) - 1) { "<越界>" }
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') { "锚点末尾不是花括号：写法变了，切不出一段体（实到「${
            source.getOrNull(open)
        }」）" }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信）。
     * 本卡尤其需要抹注释：`BuaaLoginScreen` 与 `BuaaSemesterStartPolicy` 的理由段里
     * **逐字写着旧那一形**（含 `$LITERAL_SHAPE`），不抹的话探测器会把自己钉的守卫当成病灶。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
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

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val POLICY = "com/buaa/schedule/data/import/BuaaSemesterStartPolicy.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val BUA_IN_PAGE_FETCHER = "com/buaa/schedule/data/import/BuaaInPageFetcher.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"

        /** T180 那一形的字面本体（旧 `BuaaLoginScreen` 里 `startDate` 那一枚 val 的兜底值） */
        const val LITERAL_SHAPE = "getOrDefault(rawStart)"

        const val START_DATE_ASSIGN = "startDate = start.monday.toString(),"
        const val UNDETERMINED_BRANCH = "is BuaaSemesterStart.Undetermined ->"
        const val RESOLVED_BRANCH = "is BuaaSemesterStart.Resolved ->"

        /** 姊妹路（静默刷新）那两枚口径锚点 */
        const val EXISTING_START_FALLBACK = "?: existingStartDate"
        const val REFUSE_MESSAGE = "未取得学期第一周周一，未改动课表"

        /** 导入写库落点与备份恢复那一路的闸门（旧锚点 + T223 搬家后的新锚点） */
        const val WRITE_TX_ANCHOR = "private suspend fun replaceSemesterCoursesInTx("
        const val SEMESTER_INSERT = "semesterDao.insert(semester.toEntity())"
        const val BACKUP_GATE = "WeekCalculator.mondayOf(LocalDate.parse(s.startDate))"
        const val BACKUP_POLICY = "com/buaa/schedule/data/backup/BackupSemesterRestorePolicy.kt"
        const val BACKUP_START_GATE = "backupRow?.startLocalDate"

        /** 裸标识符 / 点分路径（`rawStart`、`info.firstWeekMonday`）才算回 echo */
        val SIMPLE_PATH = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
    }
}
