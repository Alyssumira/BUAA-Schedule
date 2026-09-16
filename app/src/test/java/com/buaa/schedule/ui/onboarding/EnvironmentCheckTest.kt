package com.buaa.schedule.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导页"下一步该不该拦人"的判口。
 *
 * 只有**阻塞项且判定为失败**才拦用户：[CheckStatus.Unknown] 表示这台机器根本没有这个概念
 * （Android 12 以下没有精确闹钟权限、非 16+ 没有实况提升），
 * 把它算成失败会让引导页在旧机型上永远走不完；
 * 非阻塞项（电池豁免、自启动）再重要也只是建议，拦人等于替用户做主。
 */
class EnvironmentCheckTest {

    private fun item(id: String, status: CheckStatus, blocking: Boolean) =
        CheckItem(id, id, "summary", status, blocking, null)

    @Test
    fun onlyBlockingFailuresHoldTheUser() {
        val items = listOf(
            item("notify", CheckStatus.Failed, blocking = true),
            item("alarm", CheckStatus.Failed, blocking = false),
            item("battery", CheckStatus.Unknown, blocking = true),
            item("promoted", CheckStatus.Unknown, blocking = false),
            item("focus", CheckStatus.Passed, blocking = true),
        )

        assertEquals(listOf("notify"), EnvironmentCheck.unmetBlockers(items).map { it.id })
    }

    @Test
    fun passedAndUnknownEnvironmentNeedsNoIntervention() {
        val items = listOf(
            item("notify", CheckStatus.Passed, blocking = true),
            item("alarm", CheckStatus.Unknown, blocking = true),
            item("battery", CheckStatus.Failed, blocking = false),
        )

        assertTrue(EnvironmentCheck.unmetBlockers(items).isEmpty())
    }
}
