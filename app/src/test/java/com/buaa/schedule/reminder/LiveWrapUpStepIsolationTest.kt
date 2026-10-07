package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T227 #334：实况服务自己收尾时那两步（恢复勿扰 / 续排下一节窗口）的**兜底分兜**表。
 *
 * 病灶一句话：[CourseFluidService] 的 `finishLiveAndReschedule` 里那句
 * `runCatching { ClassProgressDnd.restore(...) }` 后面**没有 onFailure** —— 这一路是
 * "下课铃被省电策略吞掉时"的主补救路径，它一挂，整机停在「完全静默」而 logcat 一个字不留，
 * 只剩 `enter` 排的看门狗在「下课 + 30 分钟」把铃声放回来（`ClassProgressDnd.RESTORE_GRACE_MINUTES`）。
 * 同文件另外三枚同形的裸吞（契约那一手只有 `.onSuccess`、onDestroy 摘前台通知、降级重排没递出去）
 * 一起收：留痕收成文件顶层那几枚注入属性，两步各走各的兜底。
 *
 * 内核 [runLiveWrapUpSteps] 零 android、零时钟：`restore` / `reschedule` 与两枚报告口
 * 全由调用点递进来，所以这张表在 JVM 里量得到（本模块没有 Robolectric，起不了服务）。
 * 两侧格子：每一步"抛 / 不抛"各一格，四格齐全 ⇒ 红了点得出死的是哪半条。
 */
class LiveWrapUpStepIsolationTest {

    private class Recorder {
        val restoreRuns = mutableListOf<Int>()
        val rescheduleRuns = mutableListOf<Int>()
        val restoreFailures = mutableListOf<String>()
        val rescheduleFailures = mutableListOf<String>()

        fun steps(restoreThrows: Boolean, rescheduleThrows: Boolean) = runLiveWrapUpSteps(
            restoreDnd = {
                restoreRuns += restoreRuns.size
                if (restoreThrows) throw IllegalStateException("勿扰恢复抛了")
            },
            rescheduleWindow = {
                rescheduleRuns += rescheduleRuns.size
                if (rescheduleThrows) throw IllegalStateException("续排抛了")
            },
            onRestoreFailed = { restoreFailures += it.message.orEmpty() },
            onRescheduleFailed = { rescheduleFailures += it.message.orEmpty() },
        )
    }

    @Test
    fun `neither step throwing reports nothing`() {
        val r = Recorder()
        r.steps(restoreThrows = false, rescheduleThrows = false)

        assertEquals("正常档：两步各跑一遍", 1, r.restoreRuns.size)
        assertEquals("正常档：两步各跑一遍", 1, r.rescheduleRuns.size)
        assertTrue("两步都成 ⇒ 一句失败都不该念（念了就是假警报，取证读它的人会被带偏）",
            r.restoreFailures.isEmpty() && r.rescheduleFailures.isEmpty())
    }

    @Test
    fun `restore throwing is reported and does not bail out the reschedule`() {
        val r = Recorder()
        r.steps(restoreThrows = true, rescheduleThrows = false)

        assertEquals(
            "本卡的病灶那一格：恢复勿扰抛了必须有自己的一句（改前是一枚裸 runCatching，" +
                "整机静音挂到看门狗那 30 分钟，取证读不出这里失败过）",
            listOf("勿扰恢复抛了"), r.restoreFailures,
        )
        assertEquals("且它不许带走续排那一半：下一节课的实况全靠这一步", 1, r.rescheduleRuns.size)
        assertTrue("另一头不许被连坐报出来", r.rescheduleFailures.isEmpty())
    }

    @Test
    fun `reschedule throwing is reported without erasing the restore`() {
        val r = Recorder()
        r.steps(restoreThrows = false, rescheduleThrows = true)

        assertEquals("续排失败有自己的一句", listOf("续排抛了"), r.rescheduleFailures)
        assertTrue("勿扰那一半不受影响", r.restoreFailures.isEmpty())
        assertEquals("恢复勿扰仍然跑过（它是第一步）", 1, r.restoreRuns.size)
    }

    @Test
    fun `both steps failing leaves two separate verdicts`() {
        val r = Recorder()
        r.steps(restoreThrows = true, rescheduleThrows = true)

        assertEquals(
            "两半条链一起死时必须留下**两行**——本仓那一族\"一句 Log.w 罩住整段，" +
                "读日志的人分不出死的是哪半条\"（#333 / #335 与这里同一族）",
            listOf("勿扰恢复抛了"), r.restoreFailures,
        )
        assertEquals("续排那一半自己那一行", listOf("续排抛了"), r.rescheduleFailures)
    }

    @Test
    fun `restore always runs before the reschedule`() {
        val order = mutableListOf<String>()
        runLiveWrapUpSteps(
            restoreDnd = { order += "restore" },
            rescheduleWindow = { order += "reschedule" },
            onRestoreFailed = { order += "restore-failed" },
            onRescheduleFailed = { order += "reschedule-failed" },
        )

        assertEquals(
            "次序是语义的一部分：勿扰先恢复、再续排下一节 —— 反过来就是" +
                "\"下一节的上课铃立刻把勿扰挂回去，这一节的还静着\"（同 finishLiveAndReschedule 的 KDoc）",
            listOf("restore", "reschedule"), order,
        )
    }
}
