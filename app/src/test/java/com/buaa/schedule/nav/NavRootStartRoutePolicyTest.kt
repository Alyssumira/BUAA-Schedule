package com.buaa.schedule.nav

import com.buaa.schedule.ROOT_SAFE_START_ROUTES
import com.buaa.schedule.canBeNavRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪一枚落点配当 NavHost 的根栈」的表驱动单测（T244④，台账 #391 的判据本体）。
 *
 * 纯 JVM：判据是 `MainActivity.kt` 里的顶层 `canBeNavRoot`，只吃一枚可空字符串，
 * 不碰 NavController、不碰设备、体内不读钟 —— 零 android import 是本文件的前提，
 * 而它的纯度另有守卫逐形钉着（见 `NavRootStartDestinationGuardTest` ①）。
 *
 * 两侧变异都有格子压着：
 * - **朝窄**（判据退回基点那句只摘 `editor/` 前缀）⇒ ③ 那一族格子亮红：`import` 会重新
 *   被放行当根栈，台账 #391 那对 `[import, home]` 无限对倒就回来了；
 * - **朝宽**（白名单放宽到允许所有落点当根）⇒ ① 与 ③ 亮红：册子里多出一枚、
 *   `import` / `settings` 判成可当根。
 *
 * 调用点那两处的形状（`takeIf { canBeNavRoot(it) }` / `takeUnless { canBeNavRoot(it) }`、
 * `startDestination` 那一行吃的是过了判据的值）不在本文件里数，归
 * [NavRootStartDestinationGuardTest] —— 本文件只钉判据本身的对错表。
 */
class NavRootStartRoutePolicyTest {

    /** ① 册子本体：允许当根的落点恒等于首页那一枚，多一枚少一枚都算破册子。 */
    @Test
    fun rootSafeRegistryIsExactlyHome() {
        assertEquals(
            "ROOT_SAFE_START_ROUTES 只许有 home 这一枚：startDestination 是返回栈的地板，" +
                "坐在地板上的页面没有上一级可回，因此不能是那种返回键要靠 popBackStack 的页面" +
                "（台账 #391 的栈形状就是 import 当了地板）。册子内容：",
            setOf("home"),
            ROOT_SAFE_START_ROUTES.toSet(),
        )
    }

    /** ② null 不在册：调用点那句 `?: "home"` 负责把它折成首页，不该由判据兜着。 */
    @Test
    fun nullIsNotRootSafe() {
        assertFalse(
            "canBeNavRoot(null) 必须为假 —— 引导交回的 null 走的是调用点的 `?: \"home\"`，" +
                "判据这里若认 null 为真，startRoute?.takeIf 就把 null 折成了非空值，" +
                "两条兜底各写一份迟早走岔",
            canBeNavRoot(null),
        )
    }

    /** ③ 表驱动：每一枚落点逐格判过。 */
    @Test
    fun routeTruthTable() {
        for ((route, expected) in listOf(
            // 在册的唯一一枚
            "home" to true,
            // 底栏其余两族：从底栏随时能进，当根栈换不到便利，却能把返回键锁死
            "import" to false,
            "settings" to false,
            // 编辑器是带路径参数的一次性路由，按 route 模板匹配根本找不到起点
            "editor/-1" to false,
            "editor/12" to false,
            // 页内路由
            "spoc_scan" to false,
            "course_management" to false,
            "stats" to false,
            "buaa_login" to false,
            "import_history" to false,
            "iclass_login" to false,
            // 册子外的任何东西都不许当根栈
            "" to false,
            "home " to false,
            "HOME" to false,
            "home/extra" to false,
        )) {
            assertEquals(
                "$route 当根栈这一格判错（期望 $expected）：这一格就是台账 #391 的分界线",
                expected,
                canBeNavRoot(route),
            )
        }
    }

    /** ④ 判据与册子必须同源：判据不许自带第二份名单。 */
    @Test
    fun predicateAgreesWithRegistryForEveryMember() {
        for (route in ROOT_SAFE_START_ROUTES) {
            assertTrue("$route 在册却判成不可当根：判据与册子已经分成两本账", canBeNavRoot(route))
        }
        // 在册集合的大小就是判据为真的落点枚数这一档，由 ① 与 ③ 合力钉住：
        // ③ 里逐格写死了 home 之外全假，① 写死了册子只有 home —— 谁多谁少都会有一格对不上。
        assertEquals(
            "在册枚数必须与 ① 那条断言同口径",
            1,
            ROOT_SAFE_START_ROUTES.size,
        )
    }
}
