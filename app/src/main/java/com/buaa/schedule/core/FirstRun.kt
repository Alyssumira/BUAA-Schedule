package com.buaa.schedule.core

import android.content.Context
import androidx.core.content.edit

/**
 * 首启状态：隐私同意 + 引导完成。
 *
 * 两者是**不同的门**，不要合并：
 * - 隐私同意是**出网闸门**。全应用唯一的后台出网点是 Gitee 更新检查，
 *   闸口就落在 [com.buaa.schedule.update.UpdateCheck.check] 一个地方；
 *   教务登录与课表导入由用户当面按下按钮触发，那一下本身就是同意，不再另设门。
 * - 引导完成只是"看过了"，用户从引导里跳过所有开关也应算完成，否则每次冷启动都拦。
 *
 * 老用户装上这版会重新见到一次引导（这两个键之前不存在），属预期：
 * 隐私门是新增的合规要求，必须让每个人过一遍。
 */
object FirstRun {

    private const val PREFS_NAME = "schedule_settings"
    private const val KEY_PRIVACY_AT = "privacy_consent_at"
    private const val KEY_ONBOARDING_DONE = "onboarding_completed"

    /** 是否已同意隐私说明；未同意时不得发起任何网络请求 */
    fun privacyAccepted(context: Context): Boolean =
        prefs(context).contains(KEY_PRIVACY_AT)

    /** 同意时刻（0 = 从未同意）；设置页要在行内把状态摆出来 */
    fun privacyConsentAt(context: Context): Long =
        prefs(context).getLong(KEY_PRIVACY_AT, 0L)

    fun acceptPrivacy(context: Context) {
        prefs(context).edit { putLong(KEY_PRIVACY_AT, System.currentTimeMillis()) }
    }

    /**
     * 撤回同意：连同"引导已完成"一起清掉。
     *
     * 只清隐私位会留下一个自相矛盾的中间态——更新检查从此静默，
     * 但用户再也不会看到那页解释这件事的说明。撤回就该回到问题面前。
     */
    fun revokePrivacy(context: Context) {
        prefs(context).edit {
            remove(KEY_PRIVACY_AT)
            putBoolean(KEY_ONBOARDING_DONE, false)
        }
    }

    fun onboardingCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun completeOnboarding(context: Context) {
        prefs(context).edit { putBoolean(KEY_ONBOARDING_DONE, true) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
