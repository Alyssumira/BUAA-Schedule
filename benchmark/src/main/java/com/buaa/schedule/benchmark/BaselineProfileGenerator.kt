package com.buaa.schedule.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Profile 的「冷启动」那一半 —— 全工程唯一带 `includeInStartupProfile = true` 的采集点。
 *
 * 与 [InteractionBaselineProfileGenerator] 的分工是刻意的：带这个参数的采集会**另出
 * 一份 startup profile 文件**（`baselineProfiles/` 下与常规 baseline profile 并列），
 * 两边内容各管各的用途 —— startup 那份只该装启动路径上的方法。把翻周、滚设置这些
 * 交互混进同一次采集，等于让用户每次冷启动都为「打开设置页」付一遍编译成本 ——
 * 那正是要治的「启动慢」的反面。
 *
 * 步骤停在首帧（pressHome → 启动 → 等空闲）也是刻意的：扫码页（CameraX + MLKit
 * 的帧回调）与 WebView 导入页都不进 profile，理由写在
 * [InteractionBaselineProfileGenerator] 的类注释里。
 *
 * 这一半**不做**「起点页确认」，与交互那四条相反，这不是漏了：它停在首帧，采的就是
 * 首帧真正落在哪一页的组合路径。已播种的状态下今天有课，那一帧是今日页
 * （`HomeScreen.kt:180` 的 `selectedTab = if (hasTodayCourses) 1 else 0`），
 * 而"用户冷启动第一眼看到的那一屏"本来就该进 startup profile —— 换页只会让它偏离。
 * 需要站定起点页的是交互场景：它们要点只有某一侧才渲染的锚点。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /** 采集冷启动到首帧这一段，产出 startup profile。 */
    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }
}