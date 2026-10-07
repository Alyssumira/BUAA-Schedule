package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T206 守卫：课前倒计时的**归属口径**（写侧存哪一格 / 读侧传哪一格）与**撤通知成对**
 * （撤了那条实况就必须同点清掉归属）钉死在盘上。纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。
 *
 * 读法与小工具抄 `data/backup/BackupSemesterRestoreWiringGuardTest`
 * （抹注释保字面量 + 花括号配平取块 + 原文切片不吃行号）。本守卫读 `app/src/main/java` 下的
 * 源码文本 —— 那一条已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里整目录进
 * `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），所以「改了源码、单跑守卫拿到
 * Gradle 缓存绿灯」那一档盖得住；本次没有新增目录之外的读取面。
 *
 * ## 判据表与它为什么需要钉（卡面 §主问题，落点是三种可能的**第 3 种**）
 *
 * | 窗口哪一型 | 谁造的 | startMillis | endMillis |
 * |---|---|---|---|
 * | 课前实况窗口（写侧拿到的那一枚） | `ReminderScheduler.createPendingIntent` 造、`ReminderReceiver` 改起点 | 收铃此刻 | **上课时刻** |
 * | 课堂计划窗口（三处读侧拿的那一枚） | `ClassProgressScheduler.planNextClassWindow` | **上课时刻** | 下课时刻 |
 *
 * 写侧那句 `countdownClassStart = window.endMillis` 与三处读侧的 `window.startMillis` 因此
 * **今天处处同值**，判据不是恒假（第 2 种可能排除）；但它靠的是「课前窗口的 end 恰好被写成
 * 上课时刻」这一枚**窗口形状**，而这条链上原本没有任何东西钉着它 —— JVM 测试里谓词全是注入的
 * 假参数（`ReminderScheduler` 的 `countingDownTo` 入参），真写侧从没与真读侧对过账。
 * 处置按卡面第 1/3 种：口径收成两枚具名取法（[ReminderNotifications.countdownAnchorOf] 与
 * [ReminderNotifications.classStartOf]）+ 本守卫钉住四处调用点与上表那两格造窗凭据
 * + 把 `countdownClassStart` 那句 KDoc 订正成它真正装的东西。
 *
 * **下一次改窗口形状就会破的是哪一处**（卡面第 3 种要的那一格）：
 * `ReminderScheduler.createPendingIntent` 体内那两行 `startMillis = classStartMillis,` /
 * `endMillis = classStartMillis,`（第 ③ 层钉）。谁把课前那一段的 `endMillis` 换成它的自然含义
 * （下课时刻，或让计划窗口直接进这条链），第 ③ 层当场红；若那一改动只改**读侧**，第 ② 层红。
 * 两侧一起改才会自洽，而那必须是一次有意识的收口，不是撞上的。
 *
 * ## 四家钉法逐格点名（卡面要求）
 *
 * 1. [写侧只从 countdownAnchorOf 取归属那一格] —— **数出现次数** + **逐字节** + **读形状**：
 *    写入句恰好 1 处、旧那一形 0 处、两枚取法函数全仓各恰好一处定义且**体里只读自己那一格**、
 *    归零入口与赋值点各按枚数台账钉住。
 * 2. [四处读侧的口径各归各型窗口] —— **逐字节** + **数出现次数**（含跨卡那两格的现形与反面形状 0 处）。
 * 3. [造窗那两格凭据仍写着 start 与 end 都是上课时刻] —— **读形状**（配平取 `createPendingIntent` 体）
 *    + **逐字节** + **数出现次数**；`ReminderReceiver` 那两句同层。
 * 4. [撤通知与清归属收进同一处] —— **读形状**（取 `cancelClassOngoing` / `stop` / `onDestroy` /
 *    `recoverMissedClassStart` 的体）+ **数出现次数** + **逐字节** + **顺序**（证据必须先读后清）。
 * 5. [课堂实况那条通知的 id 只剩一枚字面量] —— **数出现次数**（全 `app/src/main` 抹注释后恰好 1 处）
 *    + **逐字节**（服务侧那枚是别名，不是第二份数字）。
 * 6. [探测器自己认得改前那一形 也不把注释里的引用算进账] —— **抹注释找锚点**：把改前那三段形状
 *    喂给同一把尺，各必须数到 1 处；注释里逐字引用的那一行抹完必须 0 处。这一层防的是
 *    「锚点拼错的假绿」，本文件 KDoc 自己就逐字写着那些旧形状，所以它同时是自证不是空转。
 */
class CountdownOwnershipAnchorGuardTest {

    // ─────────────── ① 写侧：归属那一格只从一个具名取法来 ───────────────

    @Test
    fun `写侧只从 countdownAnchorOf 取归属那一格`() {
        val code = blankCommentsKeepingLiterals(source(NOTIFICATIONS))

        assertEquals(
            "归属的写入句必须是 `countdownClassStart = countdownAnchorOf(window)`（实到 " +
                occurrences(code, WRITE_LINE) + " 处）。卡面禁的就是把这句换成另一枚字段名了事 —— " +
                "取法必须由那枚具名函数承担，守卫才数得到；两枚字段名直接写死的形状在第 ⑥ 层有实物对照",
            1,
            occurrences(code, WRITE_LINE),
        )
        assertEquals(
            "改前那一形 `$OLD_WRITE_SHAPE` 必须 0 处（实到 " + occurrences(code, OLD_WRITE_SHAPE) +
                " 处）：留着它 = 同一件事两把尺，而只有具名那一把被第 ②③ 层对着账",
            0,
            occurrences(code, OLD_WRITE_SHAPE),
        )

        for (definition in listOf(COUNTDOWN_ANCHOR_DEF, CLASS_START_DEF)) {
            assertEquals(
                "`$definition` 在本文件里恰好一处（实到 " + occurrences(code, definition) + " 处）",
                1,
                occurrences(code, definition),
            )
            val head = "fun " + definition.substringAfter("fun ").substringBefore("(") + "("
            assertEquals(
                "`$head` 全 app/src/main 只许一处定义（实到 " + mainOccurrences(head) +
                    " 处）：第二份定义就是第二把尺，改其一不改其余（本仓派生口径那一族的老账）",
                1,
                mainOccurrences(head),
            )
        }
        // 两枚取法各只读自己那一格（读形状取「= 后面那一段」，不靠"看起来像"）
        assertEquals(
            "countdownAnchorOf 只许读 endMillis（课前实况窗口那一型的上课时刻在 end）",
            "window.endMillis",
            oneLineBodyAfter(code, COUNTDOWN_ANCHOR_DEF),
        )
        assertEquals(
            "classStartOf 只许读 startMillis（课堂计划窗口那一型的上课时刻在 start）",
            "window.startMillis",
            oneLineBodyAfter(code, CLASS_START_DEF),
        )

        assertEquals(
            "`clearCountdownOwnership(` 全 app/src/main 恰好三处（定义 1 + cancelClassOngoing 1 + " +
                "releaseCountdownOwnership 1；实到 " + mainOccurrences("clearCountdownOwnership(") +
                " 处）：第四处 = 有人绕过那两处入口自己归零，成对不变量就此没人负责",
            3,
            mainOccurrences("clearCountdownOwnership("),
        )
        assertEquals(
            "`countdownCourseId = ` 在 ReminderNotifications 里恰好四处（声明 1 + 写侧两臂 2 + 归零 1；实到 " +
                occurrences(code, "countdownCourseId = ") + " 处）：多一处就是多一条不经那两枚入口的写路",
            4,
            occurrences(code, "countdownCourseId = "),
        )
    }

    // ─────────────── ② 读侧：四处调用点各按自己那一型窗口取格 ───────────────

    @Test
    fun `四处读侧的口径各归各型窗口`() {
        val resyncer = blankCommentsKeepingLiterals(source(RESYNCER))
        val service = blankCommentsKeepingLiterals(source(SERVICE))
        val progress = blankCommentsKeepingLiterals(source(PROGRESS_SCHEDULER))
        val scheduler = blankCommentsKeepingLiterals(source(REMINDER_SCHEDULER))

        // 课堂计划窗口那一型（start = 上课）：三处
        assertEquals(
            "LiveClassResyncer 必须走具名取法 `$CLASS_START_CALL`（实到 " +
                occurrences(resyncer, CLASS_START_CALL) + " 处）",
            1,
            occurrences(resyncer, CLASS_START_CALL),
        )
        assertEquals(
            "`ClassProgressScheduler.rescheduleWindows` 那一读必须仍逐字传 `$PLAN_READ_OLD`（实到 " +
                occurrences(progress, PLAN_READ_OLD) + " 处）。⚠️ **跨卡那一格**：T205 之后这里若改成 " +
                "`ReminderNotifications.classStartOf(window)`，本层要与第 ① 层那把尺一起重钉 —— " +
                "两侧传法任何一枚单独改动都会让本层红（本卡要的正是这个：改一侧必须回答另一侧同不同口径）",
            1,
            occurrences(progress, PLAN_READ_OLD),
        )
        assertEquals(
            "`ReminderScheduler.shouldTakeDownClassProgress` 那一读必须仍逐字传 `$SEAM_READ_OLD`（实到 " +
                occurrences(scheduler, SEAM_READ_OLD) + " 处）：同一枚跨卡格，理由与上面那格一字不差",
            1,
            occurrences(scheduler, SEAM_READ_OLD),
        )

        // 课前实况窗口那一型（end = 上课）：服务侧两处都走具名取法
        assertEquals(
            "CourseFluidService 里 `countdownAnchorOf(` 恰好两处（recoverMissedClassStart 算 anchor + " +
                "onDestroy 的 CAS 实参；实到 " + occurrences(service, "countdownAnchorOf(") +
                " 处）：那一型窗口的上课时刻在 endMillis，挑错一格这条链就永远补不回被吞的上课铃",
            2,
            occurrences(service, "countdownAnchorOf("),
        )
        assertEquals(
            "服务侧读归属那一判必须用算好的 anchor（`$SERVICE_READ`；实到 " +
                occurrences(service, SERVICE_READ) + " 处）",
            1,
            occurrences(service, SERVICE_READ),
        )

        // 反面形状：读侧挑 end、或写侧挑 start，都会让某一等恒假（卡面第 2 种可能的静态面）
        assertEquals(
            "`ownsCountdownTo(window.courseId, window.endMillis)` 那一形全 app/src/main 必须 0 处（实到 " +
                mainOccurrences("ownsCountdownTo(window.courseId, window.endMillis)") +
                " 处）：那是把课堂计划窗口与课前实况窗口混成同一型的形状 —— " +
                "它恰好就是改前 CourseFluidService 那一句（第 ⑥ 层有对照）",
            0,
            mainOccurrences("ownsCountdownTo(window.courseId, window.endMillis)"),
        )
        assertEquals(
            "`isCountingDownTo(window.courseId, window.endMillis)` 那一形全 app/src/main 必须 0 处（实到 " +
                mainOccurrences("isCountingDownTo(window.courseId, window.endMillis)") + " 处）",
            0,
            mainOccurrences("isCountingDownTo(window.courseId, window.endMillis)"),
        )
        assertEquals(
            "生产接线只许一处把真谓词递给 seam（`ReminderNotifications::ownsCountdownTo`；实到 " +
                occurrences(scheduler, "ReminderNotifications::ownsCountdownTo") + " 处）",
            1,
            occurrences(scheduler, "ReminderNotifications::ownsCountdownTo"),
        )
    }

    // ─────────────── ③ 造窗形状的两格凭据 ───────────────

    @Test
    fun `课前那一型窗口的两格仍由造窗那两行供给`() {
        val scheduler = blankCommentsKeepingLiterals(source(REMINDER_SCHEDULER))
        val hits = indexOfAll(scheduler, CREATE_PI_HEAD)
        assertEquals("`$CREATE_PI_HEAD` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val body = braceBodyFrom(scheduler, scheduler.indexOf('{', hits.first() + CREATE_PI_HEAD.length))

        for (line in listOf(PLAN_START_SHAPE, PLAN_END_SHAPE)) {
            assertEquals(
                "造课前窗口的体内 `$line` 恰好一处（实到 " + occurrences(body, line) +
                    " 处）。**这一层就是卡面要的那一格「下一次改窗口形状就会破是哪一处」**：" +
                    "这两行把上课时刻同时写进 start 与 end，才有「写侧取 end、读侧取 start 却同值」。" +
                    "把它们改成自然形状（end=下课）⇒ 第 ①② 层的账当场对不上、判据变恒假，" +
                    "后果是 ReminderNotifications.isCountingDownTo 的 KDoc 自己写的那句" +
                    "「课前倒计时刚上岛就会被拆掉」",
                1,
                occurrences(body, line),
            )
        }
        assertEquals(
            "体内 `endMillis =` 恰好一处（实到 " + occurrences(body, "endMillis =") +
                " 处）：不许长出第二处给 end 赋别的时刻的写法",
            1,
            occurrences(body, "endMillis ="),
        )

        // 收铃那一步：只有起点被改成此刻，endMillis 一个字没动
        val receiver = blankCommentsKeepingLiterals(source(RECEIVER))
        assertEquals(
            "`$RECEIVER_COPY` 恰好一处（实到 " + occurrences(receiver, RECEIVER_COPY) +
                " 处）：这一句是「写侧那一格 endMillis 仍是上课时刻」的唯一改动点 —— " +
                "它若改成同时改 end，第 ① 层的 anchor 就换了含义",
            1,
            occurrences(receiver, RECEIVER_COPY),
        )
        assertEquals(
            "`$RECEIVER_GATE` 恰好一处（实到 " + occurrences(receiver, RECEIVER_GATE) +
                " 处）：课前那一段自己判「要不要起倒计时」也是从 endMillis 读上课时刻的（与写侧同一格）",
            1,
            occurrences(receiver, RECEIVER_GATE),
        )
        assertEquals(
            "`val classStartAt = scheduled.endMillis` 恰好一处（实到 " +
                occurrences(receiver, "val classStartAt = scheduled.endMillis") + " 处）",
            1,
            occurrences(receiver, "val classStartAt = scheduled.endMillis"),
        )
    }

    // ─────────────── ④ 撤通知与清归属收进同一处 ───────────────

    @Test
    fun `撤那条实况通知的入口都带着清归属`() {
        val code = blankCommentsKeepingLiterals(source(NOTIFICATIONS))
        val service = blankCommentsKeepingLiterals(source(SERVICE))

        // 唯一的「撤通知」实现：体内两半都在，且先清后撤
        val cancelHeads = indexOfAll(code, CANCEL_HEAD)
        assertEquals("`$CANCEL_HEAD` 恰好一处定义：实到 " + cancelHeads.size + " 处", 1, cancelHeads.size)
        val cancelBody = braceBodyFrom(code, code.indexOf('{', cancelHeads.first() + CANCEL_HEAD.length))
        assertEquals(
            "cancelClassOngoing 体内 `clearCountdownOwnership()` 恰好一处（实到 " +
                occurrences(cancelBody, "clearCountdownOwnership()") + " 处）",
            1,
            occurrences(cancelBody, "clearCountdownOwnership()"),
        )
        assertEquals(
            "cancelClassOngoing 体内那句 cancel 恰好一处（实到 " + occurrences(cancelBody, CANCEL_LINE) + " 处）",
            1,
            occurrences(cancelBody, CANCEL_LINE),
        )
        assertTrue(
            "两半的次序必须是**先清归属、再撤通知**（撤完就没人替你清）；实到：" + cancelBody,
            cancelBody.indexOf("clearCountdownOwnership()") < cancelBody.indexOf(CANCEL_LINE),
        )
        assertEquals(
            "`$CANCEL_LINE` 全 app/src/main 恰好一处（实到 " + mainOccurrences(CANCEL_LINE) +
                " 处）：第二处 = 又一枚「只撤通知、不清归属」的路径（本卡副问题的形状）",
            1,
            mainOccurrences(CANCEL_LINE),
        )
        // 改前那一形（服务自己按私有 id 撤）必须彻底消失：本仓「事故 A：同值不同名的两份常量」那一族
        assertEquals(
            "`$OLD_STOP_CANCEL` 那一形全 app/src/main 必须 0 处（实到 " +
                mainOccurrences(OLD_STOP_CANCEL) + " 处）：stop 的失败支只能走整包那句",
            0,
            mainOccurrences(OLD_STOP_CANCEL),
        )

        // stop() 的失败支：撤通知走整包那一句
        val stopHeads = indexOfAll(service, STOP_HEAD)
        assertEquals("`$STOP_HEAD` 恰好一处：实到 " + stopHeads.size + " 处", 1, stopHeads.size)
        val stopBody = braceBodyFrom(service, service.indexOf('{', stopHeads.first() + STOP_HEAD.length))
        assertEquals(
            "`CourseFluidService.stop` 失败那一支必须调 `$STOP_TAKE_DOWN`（实到 " +
                occurrences(stopBody, STOP_TAKE_DOWN) + " 处）。它以前自己撤通知而不清归属，" +
                "绿得全靠三枚调用点紧跟一句 cancelClassOngoing —— 那是**调用点在替它成对**，不是它自己成对",
            1,
            occurrences(stopBody, STOP_TAKE_DOWN),
        )
        assertEquals(
            "`CourseFluidService` 里不许再有 `NotificationManagerCompat`（实到 " +
                occurrences(service, "NotificationManagerCompat") + " 处）：撤这条通知的入口只在 " +
                "ReminderNotifications 那两处（整包 + 条件清）",
            0,
            occurrences(service, "NotificationManagerCompat"),
        )

        // onDestroy：摘通知与清归属同一格，宽限期那把闸是唯一豁免
        val destroyHeads = indexOfAll(service, ON_DESTROY_HEAD)
        assertEquals("`$ON_DESTROY_HEAD` 恰好一处：实到 " + destroyHeads.size + " 处", 1, destroyHeads.size)
        val destroyBody = braceBodyFrom(service, service.indexOf('{', destroyHeads.first() + ON_DESTROY_HEAD.length))
        assertEquals(
            "onDestroy 体内 `stopForeground(STOP_FOREGROUND_REMOVE)` 恰好一处（实到 " +
                occurrences(destroyBody, "stopForeground(STOP_FOREGROUND_REMOVE)") + " 处）",
            1,
            occurrences(destroyBody, "stopForeground(STOP_FOREGROUND_REMOVE)"),
        )
        assertEquals(
            "onDestroy 体内必须有条件版清归属（`releaseCountdownOwnership(` 一处 + 它的两个实参；实到 " +
                occurrences(destroyBody, DESTROY_RELEASE_HEAD) + " + " + occurrences(destroyBody, DESTROY_ARGS) +
                " 处）—— 通知被框架摘走而归属还挂着时，shouldTakeDownClassProgress 会一直答" +
                "「岛上正数着这节课、别收」，而它保护的那条通知早就不存在了",
            2,
            occurrences(destroyBody, DESTROY_RELEASE_HEAD) + occurrences(destroyBody, DESTROY_ARGS),
        )
        assertEquals(
            "onDestroy 那道宽限期闸 `$GRACE_GATE` 恰好一处（实到 " + occurrences(destroyBody, GRACE_GATE) +
                " 处）：它是「课前那段刚刚数到上课铃、归属此刻换当上课铃的证据」的唯一豁免；" +
                "摘掉它 = recoverMissedClassStart 的补救被 onDestroy 抢清（第 2 种可能那一等的另一半）",
            1,
            occurrences(destroyBody, GRACE_GATE),
        )

        // recoverMissedClassStart：读证据 → 落闸 → 清归属 → 才补排（次序反了就是恒假判据）
        val recoverHeads = indexOfAll(service, RECOVER_HEAD)
        assertEquals("`$RECOVER_HEAD` 恰好一处：实到 " + recoverHeads.size + " 处", 1, recoverHeads.size)
        val recoverBody = braceBodyFrom(service, service.indexOf('{', recoverHeads.first() + RECOVER_HEAD.length))
        val read = recoverBody.indexOf(SERVICE_READ)
        val flagDown = recoverBody.indexOf(GRACE_FLAG_DOWN)
        val release = recoverBody.indexOf(RECOVER_RELEASE)
        val reschedule = recoverBody.indexOf("ClassProgressScheduler.rescheduleNextWindow(")
        assertTrue(
            "补救那一段的次序必须是 **读归属 → 落宽限闸 → 清归属 → 补排**（实到 index：" +
                read + " / " + flagDown + " / " + release + " / " + reschedule + "）。" +
                "抢在读之前清 = 证据被自己抹掉（补排恒不触发）；清搬到补排之后 = 那一节的账又被续排链接住，" +
                "本卡副问题残账原地复发",
            read in 0 until flagDown && flagDown < release && release < reschedule,
        )
        assertEquals(
            "`$GRACE_FLAG_UP` 恰好一处（实到 " + occurrences(recoverBody, GRACE_FLAG_UP) + " 处）",
            1,
            occurrences(recoverBody, GRACE_FLAG_UP),
        )
        assertEquals(
            "宽限闸的声明恰好一处（`$GRACE_DECL`；实到 " + occurrences(service, GRACE_DECL) +
                " 处）：第二枚旗 = 第二把尺，且它会允许有人用另一条路径抢清证据",
            1,
            occurrences(service, GRACE_DECL),
        )
    }

    // ─────────────── ⑤ 通知 id 只剩一枚字面量 ───────────────

    @Test
    fun `课堂实况那条通知的 id 全仓只剩一枚字面量`() {
        val notifications = blankCommentsKeepingLiterals(source(NOTIFICATIONS))
        val service = blankCommentsKeepingLiterals(source(SERVICE))

        assertEquals(
            "`$LIVE_ID_LITERAL` 在 app/src/main（抹掉注释后）必须恰好一处（实到 " +
                mainOccurrences(LIVE_ID_LITERAL) + " 处）。本仓「两枚来源」那一族：同一枚事实写两遍，" +
                "改一处不改另一处，症状是「撤的不是发出去的那条」或「屏幕上同时两条实况」，全程零报错。" +
                "唯一合法落点是 ReminderNotifications 那枚 internal 常量",
            1,
            mainOccurrences(LIVE_ID_LITERAL),
        )
        assertEquals(
            "定义那一行必须逐字还在（实到 " + occurrences(notifications, LIVE_ID_DEF) + " 处）",
            1,
            occurrences(notifications, LIVE_ID_DEF),
        )
        assertEquals(
            "服务侧那枚必须是**别名**而不是第二份数字（`$SERVICE_ID_ALIAS`；实到 " +
                occurrences(service, SERVICE_ID_ALIAS) + " 处）",
            1,
            occurrences(service, SERVICE_ID_ALIAS),
        )
        // 别名之所以读得到，靠的是定义那一侧重了可见性；退回 private 就会让人再抄一份数字
        assertEquals(
            "定义必须是 internal（同模块的服务侧才读得到；实到 " +
                occurrences(notifications, LIVE_ID_INTERNAL) + " 处）",
            1,
            occurrences(notifications, LIVE_ID_INTERNAL),
        )
    }

    // ─────────────── ⑥ 探测器自检：认得改前那一形，不误伤注释 ───────────────

    /**
     * 只报「锚点几处」而锚点其实拼错，等于没有守卫。两个方向各一头：
     * - **朝松**（锚点拼错 ⇒ 永远数到 0、永远绿）：改前那三段形状喂进同一把尺必须各数到 1 处；
     * - **朝窄**（把注释里的逐字引用也算成代码）：同一句写在 KDoc 里、抹完注释必须 0 处 ——
     *   本守卫的 KDoc 与 main 里那些「改前形状」登记都逐字写着它们，不抹注释的第 ①④⑤ 层会假绿。
     */
    @Test
    fun `探测器自己认得改前那一形 也不把注释里的引用算进账`() {
        val oldWriter = """
            if (phase == LivePhase.BEFORE_CLASS) {
                countdownCourseId = window.courseId
                countdownClassStart = window.endMillis
            } else {
        """.trimIndent()
        assertEquals(
            "探测器必须抓得出改前那句写侧（`$OLD_WRITE_SHAPE` 数到 1 处才算第 ① 层那句「0 处」不是空锚点；" +
                "实到 " + occurrences(oldWriter, OLD_WRITE_SHAPE) + " 处）",
            1,
            occurrences(oldWriter, OLD_WRITE_SHAPE),
        )

        val oldServiceId = "class S { private const val NOTIFY_ID = 20_260_002 }"
        assertEquals(
            "探测器必须抓得出第二份通知 id 字面量那一形（实到 " +
                occurrences(oldServiceId, LIVE_ID_LITERAL) + " 处）",
            1,
            occurrences(oldServiceId, LIVE_ID_LITERAL),
        )

        val oldStop = """
            fun stop(context: Context) {
                runCatching { context.stopService(Intent()) }.onFailure {
                    NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
                }
            }
        """.trimIndent()
        val oldStopBody = braceBodyFrom(oldStop, oldStop.indexOf('{', "fun stop(context: Context) ".length))
        assertEquals(
            "探测器必须抓得出「stop 自己撤通知」那一形（`$OLD_STOP_CANCEL` 数到 1 处；实到 " +
                occurrences(oldStopBody, OLD_STOP_CANCEL) + " 处）—— 盘面第 ④ 层那句「0 处」才有对照物",
            1,
            occurrences(oldStopBody, OLD_STOP_CANCEL),
        )
        assertEquals(
            "同一体内 `$CANCEL_LINE` 必须 0 处（实到 " + occurrences(oldStopBody, CANCEL_LINE) +
                " 处）：锚点认名字不认「长得像」，换名就不认 —— 第 ④ 层那句「全仓一处」不是空锚点的另一半证据",
            0,
            occurrences(oldStopBody, CANCEL_LINE),
        )

        val quotedInComment = """
            /**
             * 旧形状：`countdownClassStart = window.endMillis`（T206 之前的写侧那一行）。
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "注释里逐字引用的那一行**不许**进第 ① 层的账：未抹注释时它确实在原文里（实到 " +
                occurrences(quotedInComment, OLD_WRITE_SHAPE) + " 处），抹完必须 0 处（实到 " +
                occurrences(blankCommentsKeepingLiterals(quotedInComment), OLD_WRITE_SHAPE) +
                " 处）。这一对照就是「只 grep 字面量的守卫会假绿」的实物证据",
            0,
            occurrences(blankCommentsKeepingLiterals(quotedInComment), OLD_WRITE_SHAPE),
        )
        assertEquals(
            "未抹注释时那一句必须在原文里（否则本层的对照就是空的）",
            1,
            occurrences(quotedInComment, OLD_WRITE_SHAPE),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    /** 取一行式函数体里 `): Long =` 后面的那一段（去掉空白），切不出来就抛而不是返回空串 */
    private fun oneLineBodyAfter(source: String, definition: String): String {
        val at = source.indexOf(definition)
        check(at >= 0) { "找不到 `$definition`：签名换写法了，本层切不出体" }
        val line = source.substring(at).lineSequence().first()
        val tail = line.substringAfter("): Long =", "")
        check(tail.isNotBlank()) { "那一行不是 `): Long = …` 的一行式形状了：本层取法要跟着改（实到「$line」）" }
        return tail.trim()
    }

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainOccurrences(needle: String): Int =
        findMainJavaDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { occurrences(blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n")), needle) }

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
        return hits
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

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
                c == '\'' -> {
                    // 字符字面量里的花括号不参与配平
                    var j = i + 1
                    if (src.getOrNull(j) == '\\') j += 2 else j += 1
                    if (src.getOrNull(j) == '\'') i = j
                }

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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信）。
     * 口径与 `BackupSemesterRestoreWiringGuardTest` / `CalendarSyncDiffClearPairingGuardTest` 同一把刀。
     * 本卡尤其需要抹注释：main 与本文件的 KDoc 都逐字登记着那些改前形状。
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
        const val NOTIFICATIONS = "com/buaa/schedule/reminder/ReminderNotifications.kt"
        const val SERVICE = "com/buaa/schedule/reminder/CourseFluidService.kt"
        const val RESYNCER = "com/buaa/schedule/reminder/LiveClassResyncer.kt"
        const val RECEIVER = "com/buaa/schedule/reminder/ReminderReceiver.kt"
        const val REMINDER_SCHEDULER = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val PROGRESS_SCHEDULER = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"

        // ---- ① 写侧 ----
        const val WRITE_LINE = "countdownClassStart = countdownAnchorOf(window)"
        const val OLD_WRITE_SHAPE = "countdownClassStart = window.endMillis"
        const val COUNTDOWN_ANCHOR_DEF =
            "internal fun countdownAnchorOf(window: ClassProgressScheduler.ClassWindow): Long = window.endMillis"
        const val CLASS_START_DEF =
            "internal fun classStartOf(window: ClassProgressScheduler.ClassWindow): Long = window.startMillis"

        // ---- ② 读侧 ----
        const val CLASS_START_CALL = "ReminderNotifications.classStartOf(window),"
        const val PLAN_READ_OLD = "isCountingDownTo(window.courseId, window.startMillis)"
        const val SEAM_READ_OLD = "countingDownTo(window.courseId, window.startMillis)"
        const val SERVICE_READ = "ownsCountdownTo(window.courseId, anchor)"

        // ---- ③ 造窗凭据 ----
        const val CREATE_PI_HEAD = "private fun createPendingIntent("
        const val PLAN_START_SHAPE = "startMillis = classStartMillis,"
        const val PLAN_END_SHAPE = "endMillis = classStartMillis,"
        const val RECEIVER_COPY = "window = scheduled.copy(startMillis = now),"
        const val RECEIVER_GATE = "classStartAt > now"

        // ---- ④ 撤通知成对 ----
        const val CANCEL_HEAD = "fun cancelClassOngoing(context: Context)"
        const val CANCEL_LINE = "NotificationManagerCompat.from(context).cancel(NOTIFY_ID_CLASS_PROGRESS)"
        const val OLD_STOP_CANCEL = "NotificationManagerCompat.from(context).cancel(NOTIFY_ID)"
        const val STOP_HEAD = "fun stop(context: Context)"
        const val STOP_TAKE_DOWN = "ReminderNotifications.cancelClassOngoing(context)"
        const val ON_DESTROY_HEAD = "override fun onDestroy()"
        const val DESTROY_RELEASE_HEAD = "ReminderNotifications.releaseCountdownOwnership("
        const val DESTROY_ARGS = "courseId = liveWindow.courseId,"
        const val RECOVER_HEAD = "private fun recoverMissedClassStart()"
        const val RECOVER_RELEASE = "ReminderNotifications.releaseCountdownOwnership(window.courseId, anchor)"
        const val GRACE_GATE = "if (!startBellGracePending) {"
        const val GRACE_FLAG_UP = "startBellGracePending = true"
        const val GRACE_FLAG_DOWN = "startBellGracePending = false"
        const val GRACE_DECL = "private var startBellGracePending = false"

        // ---- ⑤ 通知 id ----
        const val LIVE_ID_LITERAL = "20_260_002"
        const val LIVE_ID_DEF = "internal const val NOTIFY_ID_CLASS_PROGRESS = 20_260_002"
        const val LIVE_ID_INTERNAL = "internal const val NOTIFY_ID_CLASS_PROGRESS"
        const val SERVICE_ID_ALIAS = "private const val NOTIFY_ID = ReminderNotifications.NOTIFY_ID_CLASS_PROGRESS"
    }
}
