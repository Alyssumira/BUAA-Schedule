package com.buaa.schedule.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T207 病灶 B：「这台机器现在能不能排精确闹钟」的唯一口径，纯 JVM 表驱动。
 *
 * [ReminderGuidance.canScheduleExactAt] 是判据本体：吃 `sdkInt` 与一枚 [ExactAlarmReading]，
 * **不碰 Context、不读 Build.VERSION、不碰系统服务** —— 于是「服务拿不到 ⇒ 不可用」这一档
 * 能在这里逐格断言（改前那一格写的是 `?: return true`，答的是「可用」）。
 *
 * 三档（本表的列口径）：
 * - `sdkInt < 31`：Android 12 以下根本没有这枚特殊权限 ⇒ 恒 true；
 *   SDK 档排在读数档之前，与调度侧五家的 `Build.VERSION.SDK_INT < Build.VERSION_CODES.S || 读授权`
 *   同序（`reminder/ReminderScheduler.kt` / `reminder/ClassProgressScheduler.kt` 两处 /
 *   `reminder/ClassProgressDnd.kt` / `reminder/TomorrowPreviewReceiver.kt` /
 *   `reminder/CourseFluidService.kt`）；
 * - [ExactAlarmReading.Unreadable]（SDK ≥ 31）：拿不到 `AlarmManager`、或读授权时系统抛
 *   （部分 ROM 的 `SecurityException`）⇒ **false**。改前两处都是反的：服务缺失答 true，
 *   读数裸调不吞异常 ⇒ 设置页可以写着「精确闹钟 / 提醒将准时触发」而实际一条都没排上，
 *   或在会抛的 ROM 上整页崩；
 * - [ExactAlarmReading.Granted] 才 true，[ExactAlarmReading.Denied] false。
 *
 * 「调用点把服务缺失折成 Unreadable」「调用点吞掉 SecurityException」那两手发生在
 * 拿得到 `Context` 的那一层，JVM 侧没有 Robolectric ⇒ 本表测不到，由
 * `ui/settings/ExactAlarmSingleVerdictGuardTest` 按源码形状钉住（未验到清单里也写着）。
 */
class ReminderGuidanceExactAlarmTest {

    private data class Row(
        val sdkInt: Int,
        val reading: ExactAlarmReading,
        val expected: Boolean,
        val why: String,
    )

    private val rows = listOf(
        // ---- SDK < 31：没有这枚特殊权限这回事，恒可用（SDK 档压倒读数档）----
        Row(29, ExactAlarmReading.Unreadable, true, "API 29 连 canScheduleExactAlarms() 都没有"),
        Row(30, ExactAlarmReading.Unreadable, true, "Android 11：无此权限概念"),
        Row(30, ExactAlarmReading.Granted, true, "次序格：SDK 档在前，读数不参与"),
        Row(30, ExactAlarmReading.Denied, true, "次序格：同上（真机读不出东西，摆它只为钉住短路边界）"),
        // ---- SDK = 31 起：只有真读到「已授予」才算可用 ----
        Row(31, ExactAlarmReading.Unreadable, false, "病灶格：服务拿不到 / 系统抛 ⇒ 改前答 true"),
        Row(31, ExactAlarmReading.Denied, false, "用户没在授权页里打开"),
        Row(31, ExactAlarmReading.Granted, true, "唯一该亮「正常」的一格"),
        Row(33, ExactAlarmReading.Unreadable, false, "Android 13 同样按不可用答"),
        Row(34, ExactAlarmReading.Denied, false, "Android 14 起默认拒绝"),
        Row(34, ExactAlarmReading.Granted, true, "Android 14 授权后"),
        Row(36, ExactAlarmReading.Unreadable, false, "Android 16：读数缺席就不算可用"),
        Row(36, ExactAlarmReading.Granted, true, "Android 16 授权后"),
        Row(37, ExactAlarmReading.Denied, false, "比 compileSdk 还新的一档也不放行"),
    )

    @Test
    fun `三档判据逐格 十三格表驱动`() {
        for (row in rows) {
            assertEquals(
                "sdk=${row.sdkInt} reading=${row.reading}（${row.why}）",
                row.expected,
                ReminderGuidance.canScheduleExactAt(row.sdkInt, row.reading),
            )
        }
    }

    /** 短路边界：30 ⇒ 恒 true、31 ⇒ 只有 Granted 才 true。写反一位就是「12 以上默认拒绝被放行」。 */
    @Test
    fun `闸门恰好在 31 不在 30 也不在 32`() {
        for (reading in ExactAlarmReading.entries) {
            assertTrue("30 以下任何读数都放行（$reading）", ReminderGuidance.canScheduleExactAt(30, reading))
            assertEquals("31 起只认 Granted（$reading）", reading == ExactAlarmReading.Granted, ReminderGuidance.canScheduleExactAt(31, reading))
        }
    }

    /**
     * 读数档的穷尽性：三档必须**恰有一档**算可用（SDK ≥ 31）。
     * 有人再加第四档（例如 `Unknown`）而不改判据时，这一格会红 —— 而不是让新档静默落到 false 或 true 上。
     */
    @Test
    fun `SDK 不低于 31 时三档里恰有一档答可用`() {
        val available = ExactAlarmReading.entries.filter { ReminderGuidance.canScheduleExactAt(31, it) }
        assertEquals("SDK ≥ 31 时只许 [Granted] 一档答可用，实到：$available", listOf(ExactAlarmReading.Granted), available)
    }

    /**
     * 「判不出来」那一档不许分家：服务拿不到与系统抛都被调用点折成
     * [ExactAlarmReading.Unreadable]（那一手是 Context 侧的形状，由守卫按源码钉），
     * 判据这一侧则与「明确拒绝」同向 —— 都不放行。改前服务拿不到答的是「可用」。
     */
    @Test
    fun `判不出来的那一档与明确拒绝同向 都不放行`() {
        assertEquals(
            "ExactAlarmReading 今天恰好三档（Granted / Denied / Unreadable）：加档要回来重钉本卡判据表",
            3,
            ExactAlarmReading.entries.size,
        )
        assertEquals("判不出来（服务没拿到 / 系统抛）= 不可用", false, ReminderGuidance.canScheduleExactAt(31, ExactAlarmReading.Unreadable))
        assertEquals("明确没授权 = 不可用", false, ReminderGuidance.canScheduleExactAt(31, ExactAlarmReading.Denied))
        assertEquals("两档同向", ReminderGuidance.canScheduleExactAt(34, ExactAlarmReading.Unreadable), ReminderGuidance.canScheduleExactAt(34, ExactAlarmReading.Denied))
    }
}
