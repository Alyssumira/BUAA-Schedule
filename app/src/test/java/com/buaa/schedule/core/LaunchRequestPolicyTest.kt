package com.buaa.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [launchRequestsOf] 的判据表（T71，台账 #112）。
 *
 * 表驱动，逐档覆盖：三枚请求各自的取值域边界（缺省哨兵、越界、白名单内外），
 * 以及本卡的正题 —— **同一枚合法的 extra 在「冷启动」与「重建」两档里结论相反**。
 * 装机实测的四条序列（冷启动点格子 / 进程被杀后点图标 / 进程被杀后再点格子 / 转屏）
 * 都在下面 `recreationIgrowsEveryRequest` 与 `freshLaunchAppliesEveryRequest` 里有对应行，
 * 判据本身在这里跑，设备侧只看效果。
 */
class LaunchRequestPolicyTest {

    /** 通知链路真正会发的那两条；与 `MainActivity.ROUTABLE_FROM_INTENT` 同源 */
    private val routes = setOf("spoc_scan", "spoc_login")

    // ---- 星期几那一档（组件格子） ----

    @Test
    fun dayOfTheWeekBoundariesOnAFreshLaunch() {
        for ((raw, expected) in listOf(
            NO_DAY_OF_WEEK to null, // 模板缺省 0：第一次点击吃模板，不算请求
            0 to null, // 与上一行同一个数，写两遍是因为它有两处来源（模板 / 普通启动没这个键）
            -1 to null,
            8 to null, // 越界：ISO 只有 1..7
            1 to 1, // 周一
            5 to 5, // 装机实测用的那一格
            7 to 7, // 周日
        )) {
            assertEquals("rawDayOfWeek=$raw", expected, decide(rawDayOfWeek = raw).dayOfWeek)
        }
    }

    // ---- 课程 id 那一档（组件行 / 通知） ----

    @Test
    fun courseIdBoundariesOnAFreshLaunch() {
        for ((raw, expected) in listOf(
            NO_COURSE_ID to null, // -1：组件模板的缺省哨兵
            -2L to null,
            0L to 0L, // 0 是合法行 id（通知侧 `if (courseId > 0L)` 只是不发，不代表 0 非法）
            1L to 1L,
            Long.MAX_VALUE to Long.MAX_VALUE,
        )) {
            assertEquals("rawCourseId=$raw", expected, decide(rawCourseId = raw).courseId)
        }
    }

    // ---- 页内路由那一档（通知按钮）：白名单是唯一判据 ----

    @Test
    fun routeBoundariesOnAFreshLaunch() {
        for ((raw, expected) in listOf(
            null to null,
            "" to null,
            "spoc_scan" to "spoc_scan",
            "spoc_login" to "spoc_login",
            "settings" to null, // 白名单外：MainActivity 是 launcher 页，外部应用能直接带 extra 进来
            "editor/3" to null,
            "SPOC_SCAN" to null, // 大小写敏感：路由名就是导航表里那串字面量
        )) {
            assertEquals("rawRoute=$raw", expected, decide(rawRoute = raw).route)
        }
    }

    // ---- 正题：重建那一档，三枚一起不认 ----

    /**
     * 本卡的病：`MainActivity` 是 launcher 页，用户点过一次组件格子之后那枚 extra 就留在
     * 任务栈的根 intent 上（装机实测 `dumpsys activity activities`：根 intent `(has extras)`
     * 且 `rootOfTask=true`）。此后进程被杀、用户只点桌面图标，系统重放那枚 intent ⇒
     * `onCreate` 若照抄，app 就"自己跳到某一天"。
     * 下面这一行用的全是**完全合法**的请求值：判据不许靠"值看起来不像真的"来兜，
     * 只认「这是不是一次已经跑过的实例的重建」。
     */
    @Test
    fun recreationIgnoresEveryRequest() {
        val launch = launchRequestsOf(
            rawCourseId = 3L,
            rawRoute = "spoc_scan",
            rawDayOfWeek = 5,
            routableRoutes = routes,
            hasSavedState = true,
        )
        assertNull("重建那一档还在认课程 id：图标进来自己打开编辑器", launch.courseId)
        assertNull("重建那一档还在认路由：图标进来自己打开扫码页（装机实测过这一档）", launch.route)
        assertNull("重建那一档还在认星期几：图标进来自己跳到那一天（台账 #112 的正题）", launch.dayOfWeek)
    }

    /** 对照档：同一组值在冷启动那一档必须三枚全认 —— 组件那一跳是真功能，不许被本卡顺手关掉 */
    @Test
    fun freshLaunchAppliesEveryRequest() {
        val launch = launchRequestsOf(
            rawCourseId = 3L,
            rawRoute = "spoc_scan",
            rawDayOfWeek = 5,
            routableRoutes = routes,
            hasSavedState = false,
        )
        assertEquals(3L, launch.courseId)
        assertEquals("spoc_scan", launch.route)
        assertEquals(5, launch.dayOfWeek)
    }

    /** 三档互不牵连：某一档没带请求，另外两档照旧（不许写成"要么全认要么全不认"） */
    @Test
    fun theThreeAxesAreIndependent() {
        val onlyDay = decide(rawDayOfWeek = 6)
        assertEquals(6, onlyDay.dayOfWeek)
        assertNull(onlyDay.courseId)
        assertNull(onlyDay.route)

        val onlyRoute = decide(rawRoute = "spoc_login")
        assertEquals("spoc_login", onlyRoute.route)
        assertNull(onlyRoute.dayOfWeek)
        assertNull(onlyRoute.courseId)

        val onlyCourse = decide(rawCourseId = 42L)
        assertEquals(42L, onlyCourse.courseId)
        assertNull(onlyCourse.dayOfWeek)
        assertNull(onlyCourse.route)
    }

    /** 空启动（普通桌面图标冷启动）：三枚都是缺省值 ⇒ 三档全无请求 */
    @Test
    fun plainLauncherStartCarriesNoRequest() {
        val launch = decide(
            rawCourseId = NO_COURSE_ID,
            rawRoute = null,
            rawDayOfWeek = NO_DAY_OF_WEEK,
        )
        assertEquals(LaunchRequests(null, null, null), launch)
    }

    /** 白名单由调用点交给内核：内核不许自己藏一份路由名单 */
    @Test
    fun whitelistIsOwnedByTheCallSite() {
        val empty = setOf<String>()
        assertNull(decide(rawRoute = "spoc_scan", routableRoutes = empty).route)
        assertEquals("spoc_scan", decide(rawRoute = "spoc_scan", routableRoutes = setOf("spoc_scan")).route)
        assertEquals("anything", decide(rawRoute = "anything", routableRoutes = setOf("anything")).route)
    }

    /** 缺省哨兵与生产方同源：这两枚数一改，组件模板那边（`WidgetCommon.kt:316` / `:770`）就对不上 */
    @Test
    fun sentinelValuesMatchTheProducers() {
        assertEquals(-1L, NO_COURSE_ID)
        assertEquals(0, NO_DAY_OF_WEEK)
        assertEquals(1..7, DAY_OF_WEEK_RANGE)
    }

    /** 冷启动那一档的便捷入口：只关心被点名那一维，其余两维按「没带请求」传 */
    private fun decide(
        rawCourseId: Long = NO_COURSE_ID,
        rawRoute: String? = null,
        rawDayOfWeek: Int = NO_DAY_OF_WEEK,
        routableRoutes: Set<String> = routes,
    ): LaunchRequests = launchRequestsOf(
        rawCourseId = rawCourseId,
        rawRoute = rawRoute,
        rawDayOfWeek = rawDayOfWeek,
        routableRoutes = routableRoutes,
        hasSavedState = false,
    )
}
