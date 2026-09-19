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